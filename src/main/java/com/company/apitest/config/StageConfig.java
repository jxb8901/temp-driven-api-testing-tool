/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.config;

/**
 * Defines one configured execution stage and the Excel column that selects its template.
 */
public class StageConfig {
    private final String key;
    private final String name;
    private final boolean required;
    private final String onFailure;
    private final String runWhen;

    public StageConfig(String key, String name, boolean required, String onFailure, String runWhen) {
        this.key = key;
        this.name = name;
        this.required = required;
        this.onFailure = onFailure == null || onFailure.trim().isEmpty() ? "stop" : onFailure;
        this.runWhen = runWhen == null || runWhen.trim().isEmpty() ? "normal" : runWhen;
    }

    public String key() { return key; }
    public String name() { return name; }
    public boolean required() { return required; }
    public String onFailure() { return onFailure; }
    public String runWhen() { return runWhen; }
}
