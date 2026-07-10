/* Author: Jeffrey + ChatGPT */
package com.company.apitest.config;

/** Documentation and validation contract for one V2 tool argument. */
public final class ToolArgumentConfig {
    private final String key;
    private final String name;
    private final String description;
    private final boolean required;
    private final String delimit;

    public ToolArgumentConfig(String key, String name, String description, boolean required, String delimit) {
        this.key = key;
        this.name = name;
        this.description = description;
        this.required = required;
        this.delimit = delimit == null ? "" : delimit;
    }

    public String key() { return key; }
    public String name() { return name; }
    public String description() { return description; }
    public boolean required() { return required; }
    public String delimit() { return delimit; }
    public boolean multiValue() { return !delimit.isEmpty(); }
}
