/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.exec;

import com.company.apitest.config.FrameworkConfig;
import com.company.apitest.core.Contexts;
import com.company.apitest.core.TestCase;
import com.company.apitest.template.TemplateRenderer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapts framework test cases to the existing shell-based API execution tool.
 */
public class ApiExecutor {
    private final Path projectRoot;
    private final FrameworkConfig config;
    private final CommandRunner commandRunner;

    public ApiExecutor(Path projectRoot, FrameworkConfig config) {
        this(projectRoot, config, new CommandRunner());
    }

    public ApiExecutor(Path projectRoot, FrameworkConfig config, CommandRunner commandRunner) {
        this.projectRoot = projectRoot;
        this.config = config;
        this.commandRunner = commandRunner;
    }

    public ExecutorResult execute(TestCase testCase, Path requestXml, Path responseXml) throws Exception {
        Map<String, Object> context = new LinkedHashMap<String, Object>(Contexts.requestContext(testCase));
        context.put("requestXml", requestXml.toString());
        context.put("responseXml", responseXml.toString());
        context.put("environment", config.environment());
        context.put("api", testCase.api());

        // The command itself is template-driven so existing project scripts can be reused unchanged.
        String command = TemplateRenderer.render(config.executorCommand(), context);
        CommandResult result = commandRunner.run(command, Duration.ofSeconds(config.timeoutSeconds()));
        writeExecutionLog(testCase, command, result);

        if (result.timedOut()) {
            return new ExecutorResult(false, "Executor timed out after " + config.timeoutSeconds() + " seconds");
        }
        if (result.exitCode() != 0) {
            return new ExecutorResult(false, "Executor failed with exit code " + result.exitCode() + ": " + result.stderr());
        }
        if (!Files.exists(responseXml)) {
            return new ExecutorResult(false, "Executor completed but response XML was not produced: " + responseXml);
        }
        return new ExecutorResult(true, "OK");
    }

    private void writeExecutionLog(TestCase testCase, String command, CommandResult result) throws Exception {
        Path logDir = config.logDirectory().isAbsolute() ? config.logDirectory() : projectRoot.resolve(config.logDirectory()).normalize();
        Files.createDirectories(logDir);
        Path log = logDir.resolve(testCase.caseId() + "-executor.log");
        String content = "command: " + command + System.lineSeparator()
                + "exitCode: " + result.exitCode() + System.lineSeparator()
                + "timedOut: " + result.timedOut() + System.lineSeparator()
                + "--- stdout ---" + System.lineSeparator()
                + result.stdout() + System.lineSeparator()
                + "--- stderr ---" + System.lineSeparator()
                + result.stderr();
        Files.write(log, content.getBytes("UTF-8"));
    }

    public static class ExecutorResult {
        private final boolean success;
        private final String message;

        public ExecutorResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean success() { return success; }
        public String message() { return message; }
    }
}
