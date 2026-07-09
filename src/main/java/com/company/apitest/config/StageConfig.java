/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.config;

/**
 * Defines one execution stage owned by the selected test case template.
 */
public class StageConfig {
    private final String key;
    private final String name;
    private final String template;
    private final String templatePath;
    private final boolean required;
    private final String onFailure;
    private final String runWhen;

    public StageConfig(String key, String name, boolean required, String onFailure, String runWhen) {
        this(key, name, "", "", required, onFailure, runWhen);
    }

    public StageConfig(String key, String name, String template, String templatePath, boolean required, String onFailure, String runWhen) {
        this.key = key;
        this.name = name == null || name.trim().isEmpty() ? key : name;
        this.template = template == null ? "" : template;
        this.templatePath = templatePath == null ? "" : templatePath;
        this.required = required;
        this.onFailure = onFailure == null || onFailure.trim().isEmpty() ? "stop" : onFailure;
        this.runWhen = runWhen == null || runWhen.trim().isEmpty() ? "normal" : runWhen;
    }

    public String key() { return key; }
    public String name() { return name; }
    public String template() { return template; }
    public String templatePath() { return templatePath; }
    public boolean required() { return required; }
    public String onFailure() { return onFailure; }
    public String runWhen() { return runWhen; }
}
