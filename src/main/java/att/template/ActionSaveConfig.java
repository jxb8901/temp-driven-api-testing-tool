/* Author: Jeffrey + ChatGPT */
package att.template;

/** Normalized Tool/DB action artifact settings across V2.3 and V2.5 templates. */
public final class ActionSaveConfig {
    private final String path;
    private final String format;
    private final boolean overwrite;
    private final boolean legacy;

    public ActionSaveConfig(String path, String format, boolean overwrite, boolean legacy) {
        this.path = path == null ? "" : path;
        this.format = format == null ? "" : format;
        this.overwrite = overwrite;
        this.legacy = legacy;
    }

    public static ActionSaveConfig none() { return new ActionSaveConfig("", "", false, false); }
    public String path() { return path; }
    public String format() { return format; }
    public boolean overwrite() { return overwrite; }
    public boolean legacy() { return legacy; }
    public boolean configured() { return !path.trim().isEmpty(); }
}
