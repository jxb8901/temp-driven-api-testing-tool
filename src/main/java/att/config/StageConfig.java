/* Author: Jeffrey + ChatGPT */
package att.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Defines one V2 stage and its Excel columns. */
public final class StageConfig {
    private final String key;
    private final String template;
    private final List<DataColumnConfig> dataColumns;
    private final boolean required;
    private final String onFailure;
    private final String runWhen;

    public StageConfig(String key, String template, List<DataColumnConfig> dataColumns,
                       boolean required, String onFailure, String runWhen) {
        this.key = key;
        this.template = template;
        this.dataColumns = dataColumns == null ? Collections.<DataColumnConfig>emptyList() : new ArrayList<DataColumnConfig>(dataColumns);
        this.required = required;
        this.onFailure = blank(onFailure) ? "stop" : onFailure;
        this.runWhen = blank(runWhen) ? "normal" : runWhen;
    }

    public String key() { return key; }
    public String name() { return key; }
    public String template() { return template; }
    public List<DataColumnConfig> dataColumns() { return Collections.unmodifiableList(dataColumns); }
    public boolean required() { return required; }
    public String onFailure() { return onFailure; }
    public String runWhen() { return runWhen; }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
