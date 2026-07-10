/* Author: Jeffrey + ChatGPT */
package com.company.apitest.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolved per-row data for one V2 stage. */
public final class StageCaseData {
    private final String key;
    private final String templateName;
    private final Map<String, Object> values;

    public StageCaseData(String key, String templateName, Map<String, Object> values) {
        this.key = key;
        this.templateName = templateName;
        this.values = values == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>(values);
    }

    public String key() { return key; }
    public String templateName() { return templateName; }
    public Map<String, Object> values() { return Collections.unmodifiableMap(values); }
}
