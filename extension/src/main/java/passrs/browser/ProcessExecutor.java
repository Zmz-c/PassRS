package passrs.browser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class ProcessExecutor {

    private static final Charset PROCESS_OUTPUT_CHARSET = Charset.defaultCharset();
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();

    ProcessResult run(List<String> command, long timeoutMillis, Path workingDirectory) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("python browser bridge cancelled");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread readerThread = startOutputReader(process.getInputStream(), output);
        activeProcesses.add(process);
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
            int exitCode = process.exitValue();
            return new ProcessResult(exitCode, output.toString());
        } finally {
            activeProcesses.remove(process);
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
            }
        }
    }

    void cancelCurrentProcess() {
        for (Process process : List.copyOf(activeProcesses)) {
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

    ProcessResult probe(List<String> command, long timeoutMillis, Path workingDirectory) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .directory(workingDirectory == null ? null : workingDirectory.toFile())
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
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new ProcessResult(-1, "python dependency probe timeout");
            }
            return new ProcessResult(process.exitValue(), output.toString());
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return new ProcessResult(-1, e.getMessage() == null ? "unknown error" : e.getMessage());
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

    record ProcessResult(int exitCode, String output) {
    }
}
