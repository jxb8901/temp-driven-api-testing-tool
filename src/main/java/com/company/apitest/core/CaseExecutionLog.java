/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Appends ordered V2 case, stage and action diagnostics into one UTF-8 case log.
 */
public class CaseExecutionLog {
    private final Path path;

    public CaseExecutionLog(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[0]);
    }

    public Path path() {
        return path;
    }

    public synchronized void append(String section, Object data) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("[").append(section).append("]\n");
        if (data == null) {
            text.append("\n");
        } else if (data instanceof String) {
            text.append(data).append("\n\n");
        } else {
            text.append(new Yaml().dump(data)).append("\n");
        }
        Files.write(path, text.toString().getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
    }
}
