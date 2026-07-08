/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One ordered V1.2 action inside a Tool Invocation Template.
 */
public class TemplateAction {
    private final String key;
    private final String id;
    private final String type;
    private final String payload;
    private final String call;
    private final String expression;
    private final String message;

    public TemplateAction(String key, Map<String, Object> values) {
        this.key = key;
        Map<String, Object> data = values == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>(values);
        this.id = text(data.get("id"), key);
        this.type = text(data.get("type"), "tool");
        this.payload = text(data.get("payload"), "");
        this.call = text(data.get("call"), "");
        this.expression = text(data.get("expression"), "");
        this.message = text(data.get("message"), "");
    }

    public String key() { return key; }
    public String id() { return id; }
    public String type() { return type; }
    public String payload() { return payload; }
    public String call() { return call; }
    public String expression() { return expression; }
    public String message() { return message; }

    private static String text(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }
}
