/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs shell commands with timeout handling and stdout/stderr capture.
 */
public class CommandRunner {
    public CommandResult run(String command, Duration timeout) throws IOException, InterruptedException {
        return run(command, timeout, null);
    }

    public CommandResult run(String command, Duration timeout, java.nio.file.Path workingDirectory) throws IOException, InterruptedException {
        List<String> commandArguments = split(command);
        if (commandArguments.isEmpty()) throw new IOException("Tool command is blank");
        ProcessBuilder builder = new ProcessBuilder(commandArguments);
        if (workingDirectory != null) builder.directory(workingDirectory.toFile());
        Process process = builder.start();
        // Drain both streams concurrently so a verbose script cannot block on a full pipe.
        StreamCollector stdout = new StreamCollector(process.getInputStream());
        StreamCollector stderr = new StreamCollector(process.getErrorStream());
        Thread outThread = new Thread(stdout);
        Thread errThread = new Thread(stderr);
        outThread.start();
        errThread.start();

        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            outThread.join();
            errThread.join();
            return new CommandResult(-1, stdout.text(), stderr.text(), true);
        }
        outThread.join();
        errThread.join();
        return new CommandResult(process.exitValue(), stdout.text(), stderr.text(), false);
    }

    /** Parses the configured command without invoking a shell. */
    private List<String> split(String command) throws IOException {
        List<String> result = new ArrayList<String>();
        StringBuilder token = new StringBuilder();
        boolean single = false, dual = false, escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) { token.append(c); escaped = false; }
            else if (c == '\\' && !single) escaped = true;
            else if (c == '\'' && !dual) single = !single;
            else if (c == '"' && !single) dual = !dual;
            else if (Character.isWhitespace(c) && !single && !dual) {
                if (token.length() > 0) { result.add(token.toString()); token.setLength(0); }
            } else token.append(c);
        }
        if (escaped || single || dual) throw new IOException("Unclosed quote/escape in tool command");
        if (token.length() > 0) result.add(token.toString());
        return result;
    }

    private static class StreamCollector implements Runnable {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        StreamCollector(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[4096];
            int read;
            try {
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                }
            } catch (IOException ignored) {
                // Best effort log capture.
            }
        }

        String text() {
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
