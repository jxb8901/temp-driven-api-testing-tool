/* Author: Jeffrey + ChatGPT */
package att.config;

import java.util.Map;

/** Optional presentation policy; independent of transport parsing and retention. */
public final class ResourceOutputConfig {
    private final String format;
    private final int maxChars;
    private ResourceOutputConfig(String format, int maxChars) { this.format = format; this.maxChars = maxChars; }
    public String format() { return format; }
    public int maxChars() { return maxChars; }
    public static ResourceOutputConfig from(Object evidence) {
        if (!(evidence instanceof Map) || !((Map<?, ?>) evidence).containsKey("output")) return null;
        Map<?, ?> output = SchemaSupport.map(((Map<?, ?>) evidence).get("output"), "evidence.output");
        SchemaSupport.rejectUnknown(output, "evidence.output", "format", "maxChars");
        String format = SchemaSupport.string(output.get("format"), "evidence.output.format", true);
        if (!java.util.Arrays.asList("json", "yaml", "xml", "text", "sqlplus").contains(format))
            throw new IllegalArgumentException("Invalid evidence.output.format: " + format);
        Object limit = output.get("maxChars");
        int max = limit == null ? 10000 : Integer.parseInt(String.valueOf(limit));
        if (max < 1 || max > 1000000) throw new IllegalArgumentException("evidence.output.maxChars must be 1..1000000");
        return new ResourceOutputConfig(format, max);
    }
    public Map<String, Object> render(Object value) {
        return render(value, java.util.Collections.<String>emptyList());
    }
    public Map<String, Object> render(Object value, java.util.List<String> secrets) {
        String text = new att.template.TypedValueFormatter().format(value, format, secrets);
        java.util.List<String> ordered = new java.util.ArrayList<String>(secrets);
        ordered.removeIf(item -> item == null || item.isEmpty());
        ordered.sort((left, right) -> Integer.compare(right.length(), left.length()));
        for (String secret : ordered) if (secret != null && !secret.isEmpty()) text = text.replace(secret, "[REDACTED_SECRET]");
        Map<String, Object> output = new java.util.LinkedHashMap<String, Object>();
        output.put("format", format);
        output.put("truncated", text.length() > maxChars);
        output.put("text", text.length() > maxChars ? text.substring(0, maxChars) : text);
        return output;
    }
}
