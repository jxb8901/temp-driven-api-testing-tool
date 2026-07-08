/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs shell commands with timeout handling and stdout/stderr capture.
 */
public class CommandRunner {
    public CommandResult run(String command, Duration timeout) throws IOException, InterruptedException {
        List<String> shellCommand = Arrays.asList("sh", "-c", command);
        Process process = new ProcessBuilder(shellCommand).start();
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
