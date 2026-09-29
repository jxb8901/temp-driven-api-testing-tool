package att.validation;

import java.util.LinkedHashMap;
import java.util.Map;

/** A physical source range. Coordinates are one-based, independent of Excel row/column. */
public final class SourceLocation {
    private final String file;
    private final int line, column, endLine, endColumn;
    private final String excerpt;
    private final int excerptStartLine;

    public SourceLocation(String file, int line, int column, int endLine, int endColumn, String excerpt) {
        this(file, line, column, endLine, endColumn, excerpt, line);
    }

    public SourceLocation(String file, int line, int column, int endLine, int endColumn,
                          String excerpt, int excerptStartLine) {
        if (file == null || file.trim().isEmpty() || line < 1 || column < 1
                || endLine < line || endColumn < 1 || (endLine == line && endColumn < column)
                || excerptStartLine < 1 || (excerpt != null && line < excerptStartLine)) {
            throw new IllegalArgumentException("Invalid one-based source range");
        }
        this.file = file; this.line = line; this.column = column;
        this.endLine = endLine; this.endColumn = endColumn; this.excerpt = excerpt;
        this.excerptStartLine = excerptStartLine;
    }

    public String file() { return file; }
    public int line() { return line; }
    public int column() { return column; }
    public int endLine() { return endLine; }
    public int endColumn() { return endColumn; }
    /** Optional, already redacted source excerpt; producers must not attach secrets. */
    public String excerpt() { return excerpt; }
    public int excerptStartLine() { return excerptStartLine; }
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("file", file); map.put("line", line); map.put("column", column);
        map.put("endLine", endLine); map.put("endColumn", endColumn);
        if (excerpt != null) {
            map.put("excerpt", excerpt);
            if (excerptStartLine != line) map.put("excerptStartLine", excerptStartLine);
        }
        return map;
    }
}
