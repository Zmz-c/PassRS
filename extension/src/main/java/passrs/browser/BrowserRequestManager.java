package passrs.browser;

import passrs.config.ExtensionConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class BrowserRequestManager {

    private static final String SCRIPT_RESOURCE_PATH = "browser/drission_request.py";
    private static final String SCRIPT_DIR_NAME = "browser";
    private static final String SCRIPT_FILE_NAME = "drission_request.py";
    private static final String PROFILE_DIR_NAME = "profile";
    private static final String STATE_FILE_NAME = "state.txt";
    private static final String REQUEST_FILE_PREFIX = "request-";
    private static final int DEBUG_PORT = 9777;
    private static final long DISCOVERY_COMMAND_TIMEOUT_MILLIS = 1500L;
    private static final long DEFAULT_BRIDGE_TIMEOUT_EXTRA_MILLIS = 5000L;
    private static final long NAVIGATION_POST_BRIDGE_TIMEOUT_EXTRA_MILLIS = 20000L;
    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();
    private static final Charset PROCESS_OUTPUT_CHARSET = Charset.defaultCharset();

    private final Path workspaceRoot;
    private final Path sessionRoot;
    private final Object processLock = new Object();
    private final Object scriptLock = new Object();
    private final Object pythonProbeLock = new Object();
    private final ReentrantLock browserSessionLock = new ReentrantLock(true);
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();
    private volatile Path cachedScriptFile;
    private volatile List<String> preferredPythonCommand = List.of();
    private final Map<String, String> pythonProbeCache = new LinkedHashMap<>();

    public BrowserRequestManager() {
        workspaceRoot = Path.of(System.getProperty("java.io.tmpdir"), "passrs-browser");
        sessionRoot = workspaceRoot.resolve("session-" + Long.toHexString(Instant.now().toEpochMilli())
                + "-" + Integer.toHexString(System.identityHashCode(this)));
    }

    public BrowserResponse execute(BrowserRequest request, ExtensionConfig.Snapshot snapshot) {
        if (request == null) {
            throw new IllegalArgumentException("request is null");
        }
        if (isEmpty(request.url())) {
            throw new IllegalArgumentException("request url is empty");
        }
        browserSessionLock.lock();
        try {
            Files.createDirectories(sessionRoot);
            Path profileDir = sessionRoot.resolve(PROFILE_DIR_NAME);
            Files.createDirectories(profileDir);
            Path requestFile = Files.createTempFile(sessionRoot, REQUEST_FILE_PREFIX, ".txt");
            Path stateFile = sessionRoot.resolve(STATE_FILE_NAME);
            Path scriptFile = ensureScriptFile();
            try {
                writeRequestFile(requestFile, request);

                List<String> arguments = new ArrayList<>();
                arguments.add("--action");
                arguments.add("navigate");
                arguments.add("--request-file");
                arguments.add(requestFile.toString());
                arguments.add("--browser-type");
                arguments.add(snapshot.browserType());
                if (!isEmpty(snapshot.browserPath())) {
                    arguments.add("--browser-path");
                    arguments.add(snapshot.browserPath());
                }
                arguments.add("--timeout-ms");
                arguments.add(String.valueOf(snapshot.timeoutMs()));
                if (snapshot.loadStaticResources()) {
                    arguments.add("--load-static-resources");
                }

                long bridgeTimeoutMillis = snapshot.timeoutMs() + bridgeTimeoutExtraMillis(request);
                String output = execute(arguments, bridgeTimeoutMillis, scriptFile, profileDir, stateFile, snapshot.pythonPath());
                return parseResponse(output);
            } finally {
                try {
                    Files.deleteIfExists(requestFile);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("browser bridge prepare failed: " + e.getMessage(), e);
        } finally {
            browserSessionLock.unlock();
        }
    }

    public synchronized void close(ExtensionConfig.Snapshot snapshot) {
        boolean locked = false;
        try {
            try {
                locked = browserSessionLock.tryLock(2500L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!locked) {
                return;
            }
            Path profileDir = sessionRoot.resolve(PROFILE_DIR_NAME);
            Path stateFile = sessionRoot.resolve(STATE_FILE_NAME);
            if (!Files.exists(profileDir)) {
                return;
            }
            Path scriptFile = ensureScriptFile();
            List<String> arguments = new ArrayList<>();
            arguments.add("--action");
            arguments.add("cleanup");
            arguments.add("--browser-type");
            arguments.add(snapshot.browserType());
            if (!isEmpty(snapshot.browserPath())) {
                arguments.add("--browser-path");
                arguments.add(snapshot.browserPath());
            }
            execute(arguments, 8000L, scriptFile, profileDir, stateFile, snapshot.pythonPath());

            arguments = new ArrayList<>();
            arguments.add("--action");
            arguments.add("close");
            arguments.add("--browser-type");
            arguments.add(snapshot.browserType());
            if (!isEmpty(snapshot.browserPath())) {
                arguments.add("--browser-path");
                arguments.add(snapshot.browserPath());
            }
            execute(arguments, 10000L, scriptFile, profileDir, stateFile, snapshot.pythonPath());
        } catch (Exception ignored) {
        } finally {
            if (locked) {
                browserSessionLock.unlock();
            }
        }
    }

    public void cancelCurrentProcess() {
        List<Process> processes = new ArrayList<>(activeProcesses);
        for (Process process : processes) {
            if (process == null) {
                continue;
            }
            process.destroy();
            try {
                if (!process.waitFor(800L, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    public void cleanup() {
        try {
            if (Files.exists(sessionRoot)) {
                try (var paths = Files.walk(sessionRoot)) {
                    paths.sorted((left, right) -> right.compareTo(left))
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
                }
            }
        } catch (IOException ignored) {
        }
    }

    private Path ensureScriptFile() throws IOException {
        synchronized (scriptLock) {
            Path scriptDir = workspaceRoot.resolve(SCRIPT_DIR_NAME);
            Files.createDirectories(scriptDir);
            Path scriptFile = cachedScriptFile != null ? cachedScriptFile : scriptDir.resolve(SCRIPT_FILE_NAME);
            try (InputStream inputStream = BrowserRequestManager.class.getClassLoader().getResourceAsStream(SCRIPT_RESOURCE_PATH)) {
                if (inputStream == null) {
                    throw new IllegalStateException("browser bridge script resource not found");
                }
                Files.copy(inputStream, scriptFile, StandardCopyOption.REPLACE_EXISTING);
            }
            cachedScriptFile = scriptFile;
            return scriptFile;
        }
    }

    private void writeRequestFile(Path requestFile, BrowserRequest request) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(requestFile, StandardCharsets.UTF_8)) {
            writeLine(writer, "METHOD", encodeString(request.method()));
            writeLine(writer, "URL", encodeString(request.url()));
            writeLine(writer, "HEADER_COUNT", String.valueOf(request.headers().size()));
            for (int i = 0; i < request.headers().size(); i++) {
                writeLine(writer, "HEADER_" + i, encodeString(request.headers().get(i)));
            }
            writeLine(writer, "BODY", encodeBytes(request.body()));
        }
    }

    private void writeLine(BufferedWriter writer, String key, String value) throws IOException {
        writer.write(key);
        writer.write('=');
        writer.write(value == null ? "" : value);
        writer.newLine();
    }

    private String execute(List<String> arguments, long timeoutMillis, Path scriptFile, Path profileDir,
                           Path stateFile, String pythonPath) {
        List<List<String>> commands = buildPythonCommands(scriptFile, profileDir, stateFile, arguments, pythonPath);
        if (commands.isEmpty()) {
            throw new IllegalStateException("no usable python interpreter found; configure Python Path in PassRS settings");
        }
        Exception lastError = null;
        List<String> errors = new ArrayList<>();
        for (List<String> command : commands) {
            try {
                String output = runCommand(command, timeoutMillis);
                rememberSuccessfulPythonCommand(command, scriptFile);
                return output;
            } catch (Exception e) {
                lastError = e;
                errors.add(String.join(" ", command.subList(0, Math.min(command.size(), 2))) + ": " + safeMessage(e));
            }
        }
        throw new IllegalStateException(lastError == null
                ? "python browser bridge execute failed"
                : buildPythonFailureMessage(errors, lastError), lastError);
    }

    private List<List<String>> buildPythonCommands(Path scriptFile, Path profileDir, Path stateFile,
                                                   List<String> arguments, String pythonPath) {
        List<List<String>> commands = new ArrayList<>();
        Set<String> added = new HashSet<>();
        if (!preferredPythonCommand.isEmpty()) {
            addVerifiedCommand(commands, added, preferredPythonCommand, scriptFile, profileDir, stateFile, arguments);
        }
        if (!isEmpty(pythonPath)) {
            addConfiguredPythonCommand(commands, added, normalizePythonCommand(pythonPath), scriptFile, profileDir, stateFile, arguments);
        }
        if (isWindows()) {
            for (String candidate : findWindowsPythonExecutables()) {
                addWindowsPythonCommand(commands, added, normalizePythonCommand(candidate), scriptFile, profileDir, stateFile, arguments);
            }
        } else if (isMac()) {
            for (String candidate : findMacPythonExecutables()) {
                addVerifiedCommand(commands, added, normalizePythonCommand(candidate), scriptFile, profileDir, stateFile, arguments);
            }
        } else {
            for (String candidate : findPosixPythonExecutables()) {
                addVerifiedCommand(commands, added, normalizePythonCommand(candidate), scriptFile, profileDir, stateFile, arguments);
            }
        }
        return commands;
    }

    private void addConfiguredPythonCommand(List<List<String>> commands, Set<String> added, List<String> pythonCommand,
                                            Path scriptFile, Path profileDir, Path stateFile, List<String> arguments) {
        if (pythonCommand == null || pythonCommand.isEmpty()) {
            return;
        }
        if (isWindows()) {
            addWindowsPythonCommand(commands, added, pythonCommand, scriptFile, profileDir, stateFile, arguments);
            return;
        }
        addVerifiedCommand(commands, added, pythonCommand, scriptFile, profileDir, stateFile, arguments);
    }

    private void rememberSuccessfulPythonCommand(List<String> command, Path scriptFile) {
        int scriptIndex = command.indexOf(scriptFile.toString());
        if (scriptIndex <= 0) {
            return;
        }
        preferredPythonCommand = List.copyOf(command.subList(0, scriptIndex));
    }

    private void addWindowsPythonCommand(List<List<String>> commands, Set<String> added, List<String> pythonCommand,
                                         Path scriptFile, Path profileDir, Path stateFile, List<String> arguments) {
        if (pythonCommand == null || pythonCommand.isEmpty()) {
            return;
        }
        addVerifiedCommand(commands, added, pythonCommand, scriptFile, profileDir, stateFile, arguments);
        String executable = pythonCommand.get(0);
        if (pythonCommand.size() == 1 && isPythonLauncher(executable)) {
            List<String> launcherCommand = new ArrayList<>(pythonCommand);
            launcherCommand.add("-3");
            addVerifiedCommand(commands, added, launcherCommand, scriptFile, profileDir, stateFile, arguments);
        }
    }

    private void addVerifiedCommand(List<List<String>> commands, Set<String> added, List<String> pythonCommand,
                                    Path scriptFile, Path profileDir, Path stateFile, List<String> arguments) {
        if (!isExecutableCandidateAvailable(pythonCommand)) {
            return;
        }
        if (!isPythonBridgeEnvironmentReady(pythonCommand)) {
            return;
        }
        addCommand(commands, added, buildCommand(pythonCommand, scriptFile, profileDir, stateFile, arguments));
    }

    private void addCommand(List<List<String>> commands, Set<String> added, List<String> command) {
        if (command == null || command.isEmpty()) {
            return;
        }
        String key = String.join("\u0000", command);
        if (added.add(key)) {
            commands.add(command);
        }
    }

    private List<String> buildCommand(List<String> pythonCommand, Path scriptFile, Path profileDir,
                                      Path stateFile, List<String> arguments) {
        List<String> command = new ArrayList<>(pythonCommand);
        command.add(scriptFile.toString());
        command.add("--port");
        command.add(String.valueOf(DEBUG_PORT));
        command.add("--user-data-path");
        command.add(profileDir.toString());
        command.add("--state-file");
        command.add(stateFile.toString());
        command.addAll(arguments);
        return command;
    }

    private String runCommand(List<String> command, long timeoutMillis) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("python browser bridge cancelled");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.directory(workspaceRoot.toFile());
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread readerThread = startOutputReader(process.getInputStream(), output);
        synchronized (processLock) {
            activeProcesses.add(process);
        }
        try {
            boolean completed;
            try {
                completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("python browser bridge cancelled", e);
            }
            if (!completed) {
                process.destroyForcibly();
                joinReader(readerThread);
                throw new IllegalStateException("python browser bridge timeout");
            }
            joinReader(readerThread);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("python browser bridge exit=" + process.exitValue() + " output=" + output);
            }
            return output.toString();
        } finally {
            synchronized (processLock) {
                activeProcesses.remove(process);
            }
        }
    }

    private Thread startOutputReader(InputStream inputStream, StringBuilder output) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, PROCESS_OUTPUT_CHARSET))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        if (output.length() > 0) {
                            output.append('\n');
                        }
                        output.append(line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "PassRS-browser-output");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void joinReader(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private BrowserResponse parseResponse(String output) {
        if (isEmpty(output)) {
            throw new IllegalStateException("python browser bridge output is empty");
        }
        Map<String, String> lines = parseKeyValueLines(output);
        int headerCount = parseInt(lines.get("HEADER_COUNT"), 0);
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < headerCount; i++) {
            String headerLine = decodeString(lines.get("HEADER_" + i));
            int index = headerLine.indexOf(':');
            if (index <= 0) {
                continue;
            }
            headers.put(headerLine.substring(0, index).trim(), headerLine.substring(index + 1).trim());
        }
        return new BrowserResponse(
                parseInt(lines.get("STATUS"), -1),
                decodeString(lines.get("REASON")),
                headers,
                decodeBytes(lines.get("BODY")),
                decodeString(lines.get("FINAL_URL")),
                decodeString(lines.get("TITLE"))
        );
    }

    private Map<String, String> parseKeyValueLines(String output) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            int index = line.indexOf('=');
            if (index <= 0) {
                continue;
            }
            result.put(line.substring(0, index), line.substring(index + 1));
        }
        return result;
    }

    private List<String> findWindowsPythonExecutables() {
        Set<String> result = new LinkedHashSet<>();
        addProcessOutputCandidates(result, "where.exe", "python.exe");
        addProcessOutputCandidates(result, "where.exe", "python3.exe");
        addProcessOutputCandidates(result, "where.exe", "py.exe");
        addPythonLauncherCandidates(result);
        addPathCandidates(result);

        List<Path> searchRoots = new ArrayList<>();
        addSearchRoot(searchRoots, getEnvIgnoreCase("LOCALAPPDATA"), "Programs", "Python");
        addSearchRoot(searchRoots, getEnvIgnoreCase("PROGRAMFILES"), "Python");
        addSearchRoot(searchRoots, getEnvIgnoreCase("PROGRAMFILES(X86)"), "Python");
        for (Path root : searchRoots) {
            try {
                if (!Files.isDirectory(root)) {
                    continue;
                }
                List<Path> subDirs;
                try (var paths = Files.list(root)) {
                    subDirs = paths.filter(Files::isDirectory)
                            .sorted((left, right) -> right.getFileName().toString().compareToIgnoreCase(left.getFileName().toString()))
                            .toList();
                }
                for (Path dir : subDirs) {
                    addFileIfExists(result, dir.resolve("python.exe"));
                    addFileIfExists(result, dir.resolve("python3.exe"));
                    addFileIfExists(result, dir.resolve("py.exe"));
                }
            } catch (IOException ignored) {
            }
        }
        return new ArrayList<>(result);
    }

    private List<String> findMacPythonExecutables() {
        Set<String> result = new LinkedHashSet<>();
        addPosixPathCandidates(result, "python3", "python");
        addCandidate(result, "/opt/homebrew/bin/python3");
        addCandidate(result, "/opt/homebrew/bin/python");
        addCandidate(result, "/usr/local/bin/python3");
        addCandidate(result, "/usr/local/bin/python");
        addCandidate(result, "/usr/bin/python3");
        addCandidate(result, Path.of(System.getProperty("user.home", ""), ".pyenv", "shims", "python3").toString());
        addCandidate(result, Path.of(System.getProperty("user.home", ""), ".pyenv", "shims", "python").toString());
        addVersionedPythonFrameworkCandidates(result, "/Library/Frameworks/Python.framework/Versions");
        addVersionedPythonFrameworkCandidates(result, Path.of(System.getProperty("user.home", ""),
                "Library", "Frameworks", "Python.framework", "Versions").toString());
        return new ArrayList<>(result);
    }

    private List<String> findPosixPythonExecutables() {
        Set<String> result = new LinkedHashSet<>();
        addPosixPathCandidates(result, "python3", "python");
        addCandidate(result, "/usr/local/bin/python3");
        addCandidate(result, "/usr/bin/python3");
        addCandidate(result, Path.of(System.getProperty("user.home", ""), ".pyenv", "shims", "python3").toString());
        addCandidate(result, Path.of(System.getProperty("user.home", ""), ".pyenv", "shims", "python").toString());
        return new ArrayList<>(result);
    }

    private void addVersionedPythonFrameworkCandidates(Set<String> result, String versionsRootText) {
        if (isEmpty(versionsRootText)) {
            return;
        }
        try {
            Path versionsRoot = Path.of(versionsRootText);
            if (!Files.isDirectory(versionsRoot)) {
                return;
            }
            try (var paths = Files.list(versionsRoot)) {
                paths.filter(Files::isDirectory)
                        .sorted((left, right) -> right.getFileName().toString().compareToIgnoreCase(left.getFileName().toString()))
                        .forEach(dir -> addFileIfExists(result, dir.resolve("bin").resolve("python3")));
            }
        } catch (Exception ignored) {
        }
    }

    private void addSearchRoot(List<Path> roots, String parentPath, String... children) {
        if (isEmpty(parentPath)) {
            return;
        }
        Path root = Path.of(parentPath);
        for (String child : children) {
            root = root.resolve(child);
        }
        if (Files.isDirectory(root)) {
            roots.add(root);
        }
    }

    private void addPythonLauncherCandidates(Set<String> result) {
        for (String line : runProcessAndCollectOutput(List.of("py", "-0p"))) {
            addCandidate(result, extractWindowsExePath(line));
        }
    }

    private void addPathCandidates(Set<String> result) {
        String pathValue = getEnvIgnoreCase("Path");
        if (isEmpty(pathValue)) {
            return;
        }
        for (String item : pathValue.split(java.io.File.pathSeparator)) {
            if (isEmpty(item)) {
                continue;
            }
            try {
                Path dir = Path.of(stripWrappingQuotes(item.trim()));
                addFileIfExists(result, dir.resolve("python.exe"));
                addFileIfExists(result, dir.resolve("python3.exe"));
                addFileIfExists(result, dir.resolve("py.exe"));
            } catch (Exception ignored) {
            }
        }
    }

    private void addProcessOutputCandidates(Set<String> result, String... command) {
        for (String line : runProcessAndCollectOutput(Arrays.asList(command))) {
            addCandidate(result, line);
        }
    }

    private void addPosixPathCandidates(Set<String> result, String... names) {
        for (String name : names) {
            for (String line : runProcessAndCollectOutput(List.of("which", name))) {
                addCandidate(result, line);
            }
        }
    }

    private List<String> runProcessAndCollectOutput(List<String> command) {
        List<String> lines = new ArrayList<>();
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!isEmpty(line)) {
                        lines.add(line.trim());
                    }
                }
            }
            if (!process.waitFor(DISCOVERY_COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
            if (process != null) {
                process.destroyForcibly();
            }
        }
        return lines;
    }

    private void addCandidate(Set<String> result, String candidate) {
        String normalized = normalizeCandidatePath(candidate);
        if (isEmpty(normalized)) {
            return;
        }
        try {
            Path path = Path.of(normalized);
            if (Files.isRegularFile(path)) {
                result.add(path.toAbsolutePath().toString());
            }
        } catch (Exception ignored) {
        }
    }

    private void addFileIfExists(Set<String> result, Path file) {
        if (file != null && Files.isRegularFile(file)) {
            result.add(file.toAbsolutePath().toString());
        }
    }

    private List<String> normalizePythonCommand(String pythonPath) {
        String normalized = normalizeCandidatePath(pythonPath);
        if (isEmpty(normalized)) {
            return List.of(pythonPath);
        }
        Path path;
        try {
            path = Path.of(normalized);
        } catch (Exception ignored) {
            return List.of(pythonPath);
        }
        if (Files.isDirectory(path) && isWindows()) {
            Path pythonExe = path.resolve("python.exe");
            if (Files.isRegularFile(pythonExe)) {
                return List.of(pythonExe.toString());
            }
            Path python3Exe = path.resolve("python3.exe");
            if (Files.isRegularFile(python3Exe)) {
                return List.of(python3Exe.toString());
            }
            Path launcherExe = path.resolve("py.exe");
            if (Files.isRegularFile(launcherExe)) {
                return List.of(launcherExe.toString());
            }
        }
        if (Files.isDirectory(path) && !isWindows()) {
            Path python3 = path.resolve("bin").resolve("python3");
            if (Files.isRegularFile(python3)) {
                return List.of(python3.toString());
            }
            Path python = path.resolve("bin").resolve("python");
            if (Files.isRegularFile(python)) {
                return List.of(python.toString());
            }
            Path directPython3 = path.resolve("python3");
            if (Files.isRegularFile(directPython3)) {
                return List.of(directPython3.toString());
            }
            Path directPython = path.resolve("python");
            if (Files.isRegularFile(directPython)) {
                return List.of(directPython.toString());
            }
        }
        return List.of(normalized);
    }

    private boolean isExecutableCandidateAvailable(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String executable = command.get(0);
        if (isEmpty(executable)) {
            return false;
        }
        String normalized = normalizeCandidatePath(executable);
        if (!isEmpty(normalized)) {
            try {
                Path path = Path.of(normalized);
                if (Files.isRegularFile(path)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        if (!isWindows()) {
            try {
                Path path = Path.of(executable);
                if (Files.isRegularFile(path)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            return isCommandOnPath(executable);
        }
        return isCommandOnPath(executable);
    }

    private boolean isPythonBridgeEnvironmentReady(List<String> pythonCommand) {
        String key = String.join("\u0000", pythonCommand);
        synchronized (pythonProbeLock) {
            String cached = pythonProbeCache.get(key);
            if (cached != null) {
                return "OK".equals(cached);
            }
        }

        String probeResult = probePythonBridgeEnvironment(pythonCommand);
        synchronized (pythonProbeLock) {
            pythonProbeCache.put(key, probeResult);
        }
        return "OK".equals(probeResult);
    }

    private String probePythonBridgeEnvironment(List<String> pythonCommand) {
        List<String> command = new ArrayList<>(pythonCommand);
        command.add("-c");
        command.add("import DrissionPage, lxml.etree; print('PASSRS_BRIDGE_OK')");
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .directory(workspaceRoot.toFile())
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), PROCESS_OUTPUT_CHARSET))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append('\n');
                    }
                    output.append(line);
                }
            }
            if (!process.waitFor(Math.max(DISCOVERY_COMMAND_TIMEOUT_MILLIS, 2500L), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "python dependency probe timeout";
            }
            if (process.exitValue() == 0 && output.toString().contains("PASSRS_BRIDGE_OK")) {
                return "OK";
            }
            return output.length() == 0 ? "python dependency probe failed" : output.toString();
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return safeMessage(e);
        }
    }

    private boolean isCommandOnPath(String executable) {
        List<String> probe = isWindows() ? List.of("where.exe", executable) : List.of("which", executable);
        for (String line : runProcessAndCollectOutput(probe)) {
            String candidate = normalizeCandidatePath(extractWindowsExePath(line));
            if (isEmpty(candidate)) {
                continue;
            }
            try {
                if (Files.isRegularFile(Path.of(candidate))) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private String normalizeCandidatePath(String candidate) {
        if (isEmpty(candidate)) {
            return null;
        }
        String normalized = stripWrappingQuotes(candidate.trim());
        if (normalized.startsWith("*")) {
            normalized = normalized.substring(1).trim();
        }
        return stripWrappingQuotes(normalized);
    }

    private String extractWindowsExePath(String text) {
        if (isEmpty(text)) {
            return null;
        }
        String lower = text.toLowerCase();
        int exeIndex = lower.indexOf(".exe");
        if (exeIndex < 0) {
            return text;
        }
        int driveIndex = -1;
        for (int i = 0; i + 2 < text.length(); i++) {
            char first = text.charAt(i);
            char second = text.charAt(i + 1);
            char third = text.charAt(i + 2);
            if (Character.isLetter(first) && second == ':' && (third == '\\' || third == '/')) {
                driveIndex = i;
                break;
            }
        }
        if (driveIndex >= 0 && exeIndex + 4 > driveIndex) {
            return text.substring(driveIndex, exeIndex + 4);
        }
        return text;
    }

    private String stripWrappingQuotes(String value) {
        if (isEmpty(value)) {
            return value;
        }
        String result = value.trim();
        if ((result.startsWith("\"") && result.endsWith("\"")) || (result.startsWith("'") && result.endsWith("'"))) {
            result = result.substring(1, result.length() - 1);
        }
        return result.trim();
    }

    private boolean isPythonLauncher(String executable) {
        if (isEmpty(executable)) {
            return false;
        }
        String lower = executable.toLowerCase();
        return "py".equals(lower) || "py.exe".equals(lower) || lower.endsWith("\\py.exe");
    }

    private boolean isWindows() {
        return OS_NAME.contains("win");
    }

    private boolean isMac() {
        return OS_NAME.contains("mac");
    }

    private String encodeString(String value) {
        return Base64.getEncoder().encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private String encodeBytes(byte[] value) {
        return Base64.getEncoder().encodeToString(value == null ? new byte[0] : value);
    }

    private String decodeString(String value) {
        if (isEmpty(value)) {
            return "";
        }
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private byte[] decodeBytes(String value) {
        if (isEmpty(value)) {
            return new byte[0];
        }
        return Base64.getDecoder().decode(value);
    }

    private int parseInt(String value, int defaultValue) {
        if (isEmpty(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String getEnvIgnoreCase(String key) {
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safeMessage(Exception exception) {
        return exception == null || exception.getMessage() == null ? "unknown error" : exception.getMessage();
    }

    private long bridgeTimeoutExtraMillis(BrowserRequest request) {
        if (request == null) {
            return DEFAULT_BRIDGE_TIMEOUT_EXTRA_MILLIS;
        }
        if ("POST".equalsIgnoreCase(request.method()) && request.body().length == 0) {
            return NAVIGATION_POST_BRIDGE_TIMEOUT_EXTRA_MILLIS;
        }
        return DEFAULT_BRIDGE_TIMEOUT_EXTRA_MILLIS;
    }

    private String buildPythonFailureMessage(List<String> errors, Exception lastError) {
        if (errors == null || errors.isEmpty()) {
            return safeMessage(lastError);
        }
        String message = "python browser bridge execute failed: " + String.join(" | ", errors);
        String joined = String.join("\n", errors);
        if (joined.contains("No module named 'lxml.etree'")) {
            message += " | missing dependency: install lxml in the selected Python environment";
        } else if (joined.contains("No module named 'DrissionPage'")) {
            message += " | missing dependency: install DrissionPage in the selected Python environment";
        }
        return message;
    }
}
