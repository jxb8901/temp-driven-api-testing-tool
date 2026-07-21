/*
 * Author: Jeffrey + ChatGPT
 */

package att.template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One ordered V2 action inside a template.
 */
public class TemplateAction {
    private final String key;
    private final String id;
    private final String type;
    private final String description;
    private final String payload;
    private final String renderAs;
    private final String name;
    private final String expression;
    private final String call;
    private final String message;
    private final String file;
    private final String saveAs;
    private final String assertion;
    private final String expected;
    private final String actual;
    private final String onFailure;
    private final String level;
    private final Map<String, Object> fields;
    private final Map<String, Object> raw;
    private final Map<String, Object> retry;
    private final Long timeoutMs;
    private final boolean overwrite;

    public TemplateAction(String key, Map<String, Object> values) {
        this.key = key;
        Map<String, Object> data = values == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>(values);
        this.raw = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(data));
        this.id = text(data.get("id"), key);
        this.type = text(data.get("type"), "tool");
        this.description = text(data.get("description"), "");
        this.payload = text(data.get("payload"), "");
        this.renderAs = text(data.get("renderAs"), "");
        this.name = text(data.get("name"), "");
        this.expression = text(data.get("expression"), "");
        this.call = text(data.get("call"), "");
        this.message = text(data.get("message"), "");
        this.file = text(data.get("file"), "");
        this.saveAs = text(data.get("saveAs"), "");
        this.assertion = text(data.get("assert"), "");
        this.expected = text(data.get("expected"), "");
        this.actual = text(data.get("actual"), "");
        this.onFailure = failureMode(data.get("onFailure"));
        this.level = text(data.get("level"), "INFO");
        this.fields = map(data.get("fields"));
        this.retry = map(data.get("retry"));
        this.timeoutMs = data.get("timeoutMs") == null ? null : Long.valueOf(String.valueOf(data.get("timeoutMs")));
        this.overwrite = data.get("overwrite") != null && Boolean.parseBoolean(String.valueOf(data.get("overwrite")));
    }

    public String key() { return key; }
    public String id() { return id; }
    public String type() { return type; }
    public String description() { return description; }
    public String payload() { return payload; }
    public String renderAs() { return renderAs; }
    public String name() { return name; }
    public String expression() { return expression; }
    public String call() { return call; }
    public String message() { return message; }
    public String file() { return file; }
    public String saveAs() { return saveAs; }
    public String assertion() { return assertion; }
    public String expected() { return expected; }
    public String actual() { return actual; }
    public boolean overwrite() { return overwrite; }
    public String onFailure() { return onFailure; }
    public String level() { return level; }
    public Map<String, Object> fields() { return fields; }
    public Map<String, Object> raw() { return raw; }
    public Map<String, Object> retry() { return retry; }
    public Long timeoutMs() { return timeoutMs; }

    private static String text(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static String failureMode(Object value) {
        String mode = value == null ? "stop" : String.valueOf(value).trim();
        if (mode.isEmpty()) mode = "stop";
        if (!("stop".equals(mode) || "continue".equals(mode))) {
            throw new IllegalArgumentException("Action onFailure must be stop or continue: " + mode);
        }
        return mode;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map)) {
            return Collections.<String, Object>emptyMap();
        }
        return new LinkedHashMap<String, Object>((Map<String, Object>) value);
    }
}
