/* Author: Jeffrey + ChatGPT */
package att.config;

/** Documentation and validation contract for one V2 tool argument. */
public final class ToolArgumentConfig {
    private final String key;
    private final String name;
    private final String description;
    private final boolean required;
    private final String delimit;
    private final String argName;
    private final String argNameMode;
    private final String type;
    private final java.util.List<String> enumValues;

    public ToolArgumentConfig(String key, String name, String description, boolean required, String delimit) {
        this(key, name, description, required, delimit, "", "once");
    }

    public ToolArgumentConfig(String key, String name, String description, boolean required, String delimit, String argName) {
        this(key, name, description, required, delimit, argName, "once");
    }

    public ToolArgumentConfig(String key, String name, String description, boolean required, String delimit, String argName, String argNameMode) {
        this(key, name, description, required, delimit, argName, argNameMode, "any", java.util.Collections.<String>emptyList());
    }

    public ToolArgumentConfig(String key, String name, String description, boolean required, String delimit,
                              String argName, String argNameMode, String type, java.util.List<String> enumValues) {
        this.key = key;
        this.name = name;
        this.description = description;
        this.required = required;
        this.delimit = delimit == null ? "" : delimit;
        this.argName = argName == null ? "" : argName;
        this.argNameMode = argNameMode == null ? "once" : argNameMode;
        this.type = type == null || type.trim().isEmpty() ? "any" : type.trim().toLowerCase(java.util.Locale.ROOT);
        this.enumValues = java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(
                enumValues == null ? java.util.Collections.<String>emptyList() : enumValues));
    }

    public String key() { return key; }
    public String name() { return name; }
    public String description() { return description; }
    public boolean required() { return required; }
    public String delimit() { return delimit; }
    public String argName() { return argName; }
    public String argNameMode() { return argNameMode; }
    public String type() { return type; }
    public java.util.List<String> enumValues() { return enumValues; }
    public boolean multiValue() { return !delimit.isEmpty(); }
    public boolean namedArgv() { return !argName.isEmpty(); }
    public boolean repeatArgName() { return "repeat".equals(argNameMode); }
}
