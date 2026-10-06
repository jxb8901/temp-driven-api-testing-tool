/* Author: Jeffrey + ChatGPT */
package att.config;

/** One testcase group mapped to one Excel worksheet. */
public final class SheetGroupConfig {
    private final String id;
    private final String sheetName;

    public SheetGroupConfig(String id, String sheetName) {
        this.id = id;
        this.sheetName = sheetName;
    }

    public String id() { return id; }
    public String sheetName() { return sheetName; }
}
