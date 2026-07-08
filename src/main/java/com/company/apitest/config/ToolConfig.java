/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for one external tool defined in config.yaml.
 */
public class ToolConfig {
    private final String key;
    private final String name;
    private final String command;
    private final String output;
    private final Map<String, Object> arguments;
    private final Map<String, String> inject;

    public ToolConfig(String key, String name, String command, String output, Map<String, Object> arguments, Map<String, String> inject) {
        this.key = key;
        this.name = name;
        this.command = command;
        this.output = output;
        this.arguments = arguments == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>(arguments);
        this.inject = inject == null ? Collections.<String, String>emptyMap() : new LinkedHashMap<String, String>(inject);
    }

    public String key() { return key; }
    public String name() { return name; }
    public String command() { return command; }
    public String output() { return output; }
    public Map<String, Object> arguments() { return arguments; }
    public Map<String, String> inject() { return inject; }
}
