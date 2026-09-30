package att.template;

/** A represented document whose text is authoritative and must not be reparsed implicitly. */
public final class DocumentValue {
    private final String format;
    private final String text;

    public DocumentValue(String format, String text) {
        if (format == null || !java.util.Arrays.asList("text", "json", "yaml", "xml").contains(format.toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException("DocumentValue format must be text, json, yaml, or xml");
        if (text == null) throw new IllegalArgumentException("DocumentValue text must not be null");
        this.format = format.toLowerCase(java.util.Locale.ROOT);
        this.text = text;
    }
    public String format() { return format; }
    public String text() { return text; }
    @Override public String toString() { return text; }
}
