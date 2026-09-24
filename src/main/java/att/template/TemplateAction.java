/*
 * Author: Jeffrey + ChatGPT
 */

package att.template;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
    private final ActionSaveConfig saveAs;
    private final String db;
    private final Map<String, Object> query;
    private final Map<String, Object> update;
    private final String assertion;
    private final String expected;
    private final String actual;
    private final String onFailure;
    private final String level;
    private final Map<String, Object> fields;
    private final Map<String, Object> raw;
    private final Map<String, Object> retry;
    private final Map<String, EvidenceCollector> evidence;
    private final Long timeoutMs;
    private final String use;
    private final String runWhen;

    public TemplateAction(String key, Map<String, Object> values) {
        this(key, values, "att-template/v2.3");
    }

    public TemplateAction(String key, Map<String, Object> values, String schemaVersion) {
        this.key = key;
        Map<String, Object> data = values == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>(values);
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
        this.saveAs = save(data.get("saveAs"), data.get("overwrite"), schemaVersion);
        this.db = text(data.get("db"), "");
        this.query = map(data.get("query"));
        this.update = map(data.get("update"));
        this.assertion = text(data.get("assert"), "");
        this.expected = text(data.get("expected"), "");
        this.actual = text(data.get("actual"), "");
        this.onFailure = failureMode(data.get("onFailure"));
        this.level = text(data.get("level"), "INFO");
        this.fields = map(data.get("fields"));
        this.retry = map(data.get("retry"));
        this.evidence = collectors(data.get("evidence"));
        this.timeoutMs = data.get("timeoutMs") == null ? null : Long.valueOf(String.valueOf(data.get("timeoutMs")));
        this.use = text(data.get("use"), "");
        this.runWhen = text(data.get("runWhen"), "");

        validateDirectDbExecutionControls();

        // PackageValidator historically uses raw() to reject fields that are not
        // legal for an Action type. Direct DB timeout/query-retry are now legal,
        // so hide only those two already-validated fields from that legacy
        // forbidden-field check while retaining every other raw key.
        Map<String, Object> validationRaw = new LinkedHashMap<String, Object>(data);
        if ("db".equalsIgnoreCase(this.type)) {
            validationRaw.remove("timeoutMs");
            if (!this.query.isEmpty()) validationRaw.remove("retry");
        }
        this.raw = Collections.unmodifiableMap(validationRaw);
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
    public String saveAs() { return saveAs.path(); }
    public ActionSaveConfig saveConfig() { return saveAs; }
    public String db() { return db; }
    public Map<String, Object> query() { return query; }
    public Map<String, Object> update() { return update; }
    public String assertion() { return assertion; }
    public String expected() { return expected; }
    public String actual() { return actual; }
    public boolean overwrite() { return saveAs.overwrite(); }
    public String onFailure() { return onFailure; }
    public String level() { return level; }
    public Map<String, Object> fields() { return fields; }
    public Map<String, Object> raw() { return raw; }
    public Map<String, Object> retry() { return retry; }
    public Map<String, EvidenceCollector> evidence() { return evidence; }
    public Long timeoutMs() { return timeoutMs; }
    public String use() { return use; }
    public String runWhen() { return runWhen; }

    private void validateDirectDbExecutionControls() {
        if (!"db".equalsIgnoreCase(type)) return;
        if (timeoutMs != null && (timeoutMs.longValue() < 1L || timeoutMs.longValue() > 3600000L)) {
            throw new IllegalArgumentException("DB Action timeoutMs must be 1..3600000: " + id);
        }
        if (retry.isEmpty()) return;
        if (!update.isEmpty()) {
            throw new IllegalArgumentException("Automatic retry is not supported for mutating DB Actions because the database execution outcome may be uncertain after timeout or failure: " + id);
        }
        if (query.isEmpty()) return; // exactly-one query/update is reported by normal DB validation.
        int maxAttempts = integer(retry.get("maxAttempts"), -1);
        int intervalMs = integer(retry.get("intervalMs"), -1);
        if (maxAttempts < 2 || maxAttempts > 10) throw new IllegalArgumentException("retry.maxAttempts must be 2..10: " + id);
        if (intervalMs < 0 || intervalMs > 3600000) throw new IllegalArgumentException("retry.intervalMs must be 0..3600000: " + id);
        Object configured = retry.get("retryOn");
        if (!(configured instanceof Iterable)) throw new IllegalArgumentException("retry.retryOn must be a non-empty list: " + id);
        Set<String> categories = new LinkedHashSet<String>();
        for (Object value : (Iterable<?>) configured) {
            String category = String.valueOf(value);
            if (!("ASSERTION".equals(category) || "TIMEOUT".equals(category))) {
                throw new IllegalArgumentException("retry.retryOn supports ASSERTION and TIMEOUT only: " + id);
            }
            if (!categories.add(category)) throw new IllegalArgumentException("retry.retryOn must contain unique values: " + id);
        }
        if (categories.isEmpty()) throw new IllegalArgumentException("retry.retryOn must not be empty: " + id);
        if (categories.contains("ASSERTION") && assertion.trim().isEmpty()) {
            throw new IllegalArgumentException("retryOn ASSERTION requires action assert: " + id);
        }
    }

    private static int integer(Object value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException error) { return fallback; }
    }

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

    @SuppressWarnings("unchecked")
    private static Map<String, EvidenceCollector> collectors(Object value) {
        if (!(value instanceof Map)) return Collections.<String, EvidenceCollector>emptyMap();
        Map<String, EvidenceCollector> result = new LinkedHashMap<String, EvidenceCollector>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            String id = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map)) {
                throw new IllegalArgumentException("Evidence collector must be a map: " + id);
            }
            result.put(id, new EvidenceCollector(id, (Map<String, Object>) entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    private static ActionSaveConfig save(Object value, Object siblingOverwrite, String schemaVersion) {
        if (value == null) return ActionSaveConfig.none();
        if (value instanceof Map) {
            Map<String, Object> map = new LinkedHashMap<String, Object>((Map<String, Object>) value);
            boolean overwrite = map.get("overwrite") != null && Boolean.parseBoolean(String.valueOf(map.get("overwrite")));
            return new ActionSaveConfig(text(map.get("path"), ""), text(map.get("format"), ""), overwrite, false);
        }
        boolean overwrite = siblingOverwrite != null && Boolean.parseBoolean(String.valueOf(siblingOverwrite));
        return new ActionSaveConfig(String.valueOf(value), "raw", overwrite, "att-template/v2.3".equals(schemaVersion));
    }
}
