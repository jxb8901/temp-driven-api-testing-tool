/* Author: Jeffrey + ChatGPT */
package att.template;

/** Immutable common representation and optional persistence settings for an Action result. */
public final class ActionResultConfig {
    private final String path;
    private final String format;
    private final boolean overwrite;
    private final boolean specified;

    public ActionResultConfig(String path, String format, boolean overwrite) {
        this(path, format, overwrite, true);
    }

    private ActionResultConfig(String path, String format, boolean overwrite, boolean specified) {
        this.path = path == null ? "" : path;
        this.format = format == null ? "" : format;
        this.overwrite = overwrite;
        this.specified = specified;
    }

    public static ActionResultConfig none() { return new ActionResultConfig("", "", false, false); }
    public String path() { return path; }
    public String format() { return format; }
    public boolean overwrite() { return overwrite; }
    public boolean specified() { return specified; }
    public boolean configured() { return !path.trim().isEmpty(); }
}
