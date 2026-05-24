package passrs.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PythonEnvironmentResolver {

    private static final int DISCOVERY_COMMAND_TIMEOUT_MILLIS = 1500;
    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();

    private final ProcessExecutor processExecutor;
    private final Path workspaceRoot;
    private final Object pythonProbeLock = new Object();
    private final Map<String, String> pythonProbeCache;
    private volatile List<String> preferredPythonCommand = List.of();

    PythonEnvironmentResolver(ProcessExecutor processExecutor, Path workspaceRoot, Map<String, String> pythonProbeCache) {
        this.processExecutor = processExecutor;
        this.workspaceRoot = workspaceRoot;
        this.pythonProbeCache = pythonProbeCache;
    }

    List<List<String>> buildPythonCommands(Path scriptFile, Path profileDir, Path stateFile,
                                           List<String> arguments, String pythonPath) {
        List<List<String>> commands = new ArrayList<>();
        Set<String> added = new HashSet<>();
        if (!preferredPythonCommand.isEmpty()) {
            addVerifiedCommand(commands, added, preferredPythonCommand, scriptFile, profileDir, stateFile, arguments);
        }
        if (!BrowserMessageCodec.isEmpty(pythonPath)) {
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

    void rememberSuccessfulPythonCommand(List<String> command, Path scriptFile) {
        int scriptIndex = command.indexOf(scriptFile.toString());
        if (scriptIndex <= 0) {
            return;
        }
        preferredPythonCommand = List.copyOf(command.subList(0, scriptIndex));
    }

    boolean isPythonBridgeEnvironmentReady(List<String> pythonCommand) {
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

    String buildFailureMessage(List<String> errors, Exception lastError) {
        if (errors == null || errors.isEmpty()) {
            return lastError == null || lastError.getMessage() == null ? "unknown error" : lastError.getMessage();
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

    boolean isExecutableCandidateAvailable(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String executable = command.get(0);
        if (BrowserMessageCodec.isEmpty(executable)) {
            return false;
        }
        String normalized = normalizeCandidatePath(executable);
        if (!BrowserMessageCodec.isEmpty(normalized)) {
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
        command.add(String.valueOf(9777));
        command.add("--user-data-path");
        command.add(profileDir.toString());
        command.add("--state-file");
        command.add(stateFile.toString());
        command.addAll(arguments);
        return command;
    }

    private String probePythonBridgeEnvironment(List<String> pythonCommand) {
        List<String> command = new ArrayList<>(pythonCommand);
        command.add("-c");
        command.add("import DrissionPage, lxml.etree; print('PASSRS_BRIDGE_OK')");
        ProcessExecutor.ProcessResult result = processExecutor.probe(command, Math.max(DISCOVERY_COMMAND_TIMEOUT_MILLIS, 2500L), workspaceRoot);
        if (result.exitCode() == 0 && result.output().contains("PASSRS_BRIDGE_OK")) {
            return "OK";
        }
        return BrowserMessageCodec.isEmpty(result.output()) ? "python dependency probe failed" : result.output();
    }

    private boolean isCommandOnPath(String executable) {
        List<String> probe = isWindows() ? List.of("where.exe", executable) : List.of("which", executable);
        ProcessExecutor.ProcessResult result = processExecutor.probe(probe, DISCOVERY_COMMAND_TIMEOUT_MILLIS, workspaceRoot);
        for (String line : result.output().split("\\R")) {
            String candidate = normalizeCandidatePath(extractWindowsExePath(line));
            if (BrowserMessageCodec.isEmpty(candidate)) {
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

    private List<String> findWindowsPythonExecutables() {
        Set<String> result = new LinkedHashSet<>();
        addProcessOutputCandidates(result, "where.exe", "python.exe");
        addProcessOutputCandidates(result, "where.exe", "python3.exe");
        addProcessOutputCandidates(result, "where.exe", "py.exe");
        addPythonLauncherCandidates(result);
        addPathCandidates(result);
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
        if (BrowserMessageCodec.isEmpty(versionsRootText)) {
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

    private void addPythonLauncherCandidates(Set<String> result) {
        for (String line : processExecutor.probe(List.of("py", "-0p"), DISCOVERY_COMMAND_TIMEOUT_MILLIS, workspaceRoot).output().split("\\R")) {
            addCandidate(result, extractWindowsExePath(line));
        }
    }

    private void addPathCandidates(Set<String> result) {
        String pathValue = getEnvIgnoreCase("Path");
        if (BrowserMessageCodec.isEmpty(pathValue)) {
            return;
        }
        for (String item : pathValue.split(java.io.File.pathSeparator)) {
            if (BrowserMessageCodec.isEmpty(item)) {
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
        for (String line : processExecutor.probe(Arrays.asList(command), DISCOVERY_COMMAND_TIMEOUT_MILLIS, workspaceRoot).output().split("\\R")) {
            addCandidate(result, line);
        }
    }

    private void addPosixPathCandidates(Set<String> result, String... names) {
        for (String name : names) {
            for (String line : processExecutor.probe(List.of("which", name), DISCOVERY_COMMAND_TIMEOUT_MILLIS, workspaceRoot).output().split("\\R")) {
                addCandidate(result, line);
            }
        }
    }

    private void addCandidate(Set<String> result, String candidate) {
        String normalized = normalizeCandidatePath(candidate);
        if (BrowserMessageCodec.isEmpty(normalized)) {
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
        if (BrowserMessageCodec.isEmpty(normalized)) {
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

    private boolean isPythonLauncher(String executable) {
        if (BrowserMessageCodec.isEmpty(executable)) {
            return false;
        }
        String lower = executable.toLowerCase();
        return "py".equals(lower) || "py.exe".equals(lower) || lower.endsWith("\\py.exe");
    }

    private String normalizeCandidatePath(String candidate) {
        if (BrowserMessageCodec.isEmpty(candidate)) {
            return null;
        }
        String normalized = stripWrappingQuotes(candidate.trim());
        if (normalized.startsWith("*")) {
            normalized = normalized.substring(1).trim();
        }
        return stripWrappingQuotes(normalized);
    }

    private String extractWindowsExePath(String text) {
        if (BrowserMessageCodec.isEmpty(text)) {
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
        if (BrowserMessageCodec.isEmpty(value)) {
            return value;
        }
        String result = value.trim();
        if ((result.startsWith("\"") && result.endsWith("\"")) || (result.startsWith("'") && result.endsWith("'"))) {
            result = result.substring(1, result.length() - 1);
        }
        return result.trim();
    }

    private boolean isWindows() {
        return OS_NAME.contains("win");
    }

    private boolean isMac() {
        return OS_NAME.contains("mac");
    }

    private String getEnvIgnoreCase(String key) {
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
