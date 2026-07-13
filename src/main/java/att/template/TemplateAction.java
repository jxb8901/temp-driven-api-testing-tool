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
    private final String payload;
    private final String call;
    private final String expression;
    private final String message;
    private final String saveAs;
    private final String assertion;
    private final String onFailure;
    private final String level;
    private final Map<String, Object> fields;
    private final Map<String, Object> output;
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
        this.payload = text(data.get("payload"), "");
        this.call = text(data.get("call"), "");
        this.expression = text(data.get("expression"), "");
        this.message = text(data.get("message"), "");
        this.saveAs = text(data.get("saveAs"), "");
        this.assertion = text(data.get("assert"), "");
        this.onFailure = failureMode(data.get("onFailure"));
        this.level = text(data.get("level"), "INFO");
        this.fields = map(data.get("fields"));
        this.output = map(data.get("output"));
        this.retry = map(data.get("retry"));
        this.timeoutMs = data.get("timeoutMs") == null ? null : Long.valueOf(String.valueOf(data.get("timeoutMs")));
        this.overwrite = data.get("overwrite") != null && Boolean.parseBoolean(String.valueOf(data.get("overwrite")));
    }

    public String key() { return key; }
    public String id() { return id; }
    public String type() { return type; }
    public String payload() { return payload; }
    public String call() { return call; }
    public String expression() { return expression; }
    public String message() { return message; }
    public String saveAs() { return saveAs; }
    public String assertion() { return assertion; }
    public boolean overwrite() { return overwrite; }
    public String onFailure() { return onFailure; }
    public String level() { return level; }
    public Map<String, Object> fields() { return fields; }
    public Map<String, Object> output() { return output; }
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
