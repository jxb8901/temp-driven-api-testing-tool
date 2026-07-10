/* Author: Jeffrey + ChatGPT */
package com.company.apitest.config;

/** One parsed dataColumns item. */
public final class DataColumnConfig {
    private final String key;
    private final String columnName;
    private final boolean yaml;

    public DataColumnConfig(String key, String columnName, boolean yaml) {
        this.key = key;
        this.columnName = columnName;
        this.yaml = yaml;
    }

    public String key() { return key; }
    public String columnName() { return columnName; }
    public boolean yaml() { return yaml; }
}
