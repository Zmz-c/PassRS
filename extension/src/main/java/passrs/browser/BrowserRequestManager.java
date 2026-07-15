package passrs.browser;

import passrs.config.ExtensionConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class BrowserRequestManager {

    private static final String SCRIPT_RESOURCE_PATH = "browser/drission_request.py";
    private static final String SCRIPT_DIR_NAME = "browser";
    private static final String SCRIPT_FILE_NAME = "drission_request.py";
    private static final String PROFILE_DIR_NAME = "profile";
    private static final String STATE_FILE_NAME = "state.txt";
    private static final String REQUEST_FILE_PREFIX = "request-";
    private static final long DEFAULT_BRIDGE_TIMEOUT_EXTRA_MILLIS = 5000L;
    private static final long NAVIGATION_POST_BRIDGE_TIMEOUT_EXTRA_MILLIS = 20000L;

    private final Path workspaceRoot;
    private final Path sessionRoot;
    private final Object scriptLock = new Object();
    private final ReentrantLock browserSessionLock = new ReentrantLock(true);
    private final ProcessExecutor processExecutor;
    private final PythonEnvironmentResolver pythonEnvironmentResolver;
    private volatile Path cachedScriptFile;

    public BrowserRequestManager() {
        workspaceRoot = Path.of(System.getProperty("java.io.tmpdir"), "passrs-browser");
        sessionRoot = workspaceRoot.resolve("session-" + Long.toHexString(Instant.now().toEpochMilli())
                + "-" + Integer.toHexString(System.identityHashCode(this)));
        processExecutor = new ProcessExecutor();
        pythonEnvironmentResolver = new PythonEnvironmentResolver(processExecutor, workspaceRoot, new LinkedHashMap<>());
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
        processExecutor.cancelCurrentProcess();
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
            writeLine(writer, "METHOD", BrowserMessageCodec.encodeString(request.method()));
            writeLine(writer, "URL", BrowserMessageCodec.encodeString(request.url()));
            writeLine(writer, "HEADER_COUNT", String.valueOf(request.headers().size()));
            for (int i = 0; i < request.headers().size(); i++) {
                writeLine(writer, "HEADER_" + i, BrowserMessageCodec.encodeString(request.headers().get(i)));
            }
            writeLine(writer, "BODY", BrowserMessageCodec.encodeBytes(request.body()));
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
        List<List<String>> commands = pythonEnvironmentResolver.buildPythonCommands(scriptFile, profileDir, stateFile, arguments, pythonPath);
        if (commands.isEmpty()) {
            throw new IllegalStateException("no usable python interpreter found; configure Python Path in PassRS settings");
        }
        Exception lastError = null;
        List<String> errors = new ArrayList<>();
        for (List<String> command : commands) {
            ProcessExecutor.ProcessResult result;
            try {
                result = processExecutor.run(command, timeoutMillis, workspaceRoot);
            } catch (IOException e) {
                lastError = e;
                errors.add(commandLabel(command) + ": " + safeMessage(e));
                continue;
            } catch (Exception e) {
                errors.add(commandLabel(command) + ": " + safeMessage(e));
                throw bridgeExecutionFailure(errors, e);
            }
            if (result.exitCode() != 0) {
                IllegalStateException error = new IllegalStateException(
                        "python browser bridge exit=" + result.exitCode() + " output=" + result.output());
                errors.add(commandLabel(command) + ": " + safeMessage(error));
                // The interpreter already ran the bridge. Trying another interpreter can
                // replay a non-idempotent request whose response was merely unreadable.
                throw bridgeExecutionFailure(errors, error);
            }
            pythonEnvironmentResolver.rememberSuccessfulPythonCommand(command, scriptFile);
            return result.output();
        }
        throw bridgeExecutionFailure(errors, lastError);
    }

    private String commandLabel(List<String> command) {
        return String.join(" ", command.subList(0, Math.min(command.size(), 2)));
    }

    private IllegalStateException bridgeExecutionFailure(List<String> errors, Exception error) {
        return new IllegalStateException(error == null
                ? "python browser bridge execute failed"
                : pythonEnvironmentResolver.buildFailureMessage(errors, error), error);
    }

    private BrowserResponse parseResponse(String output) {
        if (isEmpty(output)) {
            throw new IllegalStateException("python browser bridge output is empty");
        }
        Map<String, String> lines = BrowserMessageCodec.parseKeyValueLines(output);
        int headerCount = parseInt(lines.get("HEADER_COUNT"), 0);
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < headerCount; i++) {
            String headerLine = BrowserMessageCodec.decodeString(lines.get("HEADER_" + i));
            if (!isEmpty(headerLine)) {
                headers.add(headerLine);
            }
        }
        return new BrowserResponse(
                parseInt(lines.get("STATUS"), -1),
                BrowserMessageCodec.decodeString(lines.get("REASON")),
                headers,
                BrowserMessageCodec.decodeBytes(lines.get("BODY")),
                BrowserMessageCodec.decodeString(lines.get("FINAL_URL")),
                BrowserMessageCodec.decodeString(lines.get("TITLE"))
        );
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

    private boolean isEmpty(String value) {
        return BrowserMessageCodec.isEmpty(value);
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
}
