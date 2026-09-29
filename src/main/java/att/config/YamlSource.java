package att.config;

import att.validation.SourceLocation;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.nodes.*;
import java.nio.file.Path;
import java.util.*;

/** Source marks captured from the same parse that constructs the configuration value. */
public final class YamlSource {
    private final String file;
    private final Map<String, SourceLocation> locations = new LinkedHashMap<String, SourceLocation>();
    private final Map<String, ScalarNode> scalars = new LinkedHashMap<String, ScalarNode>();

    YamlSource(Path file, Node root) {
        this.file = file.toString();
        if (root != null) index(root, "", Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>()));
    }

    private void index(Node node, String pointer, Set<Node> ancestors) {
        // Aliases can share nodes; recursive aliases must not recurse forever.
        if (locations.size() >= 100000 || !ancestors.add(node)) return;
        locations.put(pointer, range(file, node.getStartMark(), node.getEndMark()));
        if (node instanceof ScalarNode) scalars.put(pointer, (ScalarNode) node);
        if (node instanceof MappingNode) {
            for (NodeTuple tuple : ((MappingNode) node).getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode) {
                    String key = ((ScalarNode) tuple.getKeyNode()).getValue();
                    index(tuple.getValueNode(), pointer + "/" + escape(key), ancestors);
                }
            }
        } else if (node instanceof SequenceNode) {
            List<Node> items = ((SequenceNode) node).getValue();
            for (int i = 0; i < items.size(); i++) index(items.get(i), pointer + "/" + i, ancestors);
        }
        ancestors.remove(node);
    }

    /** Missing properties point to the closest existing parent, never an invented line. */
    public SourceLocation location(String field) {
        String pointer = pointer(field);
        while (!locations.containsKey(pointer) && !pointer.isEmpty()) {
            int slash = pointer.lastIndexOf('/');
            pointer = slash < 0 ? "" : pointer.substring(0, slash);
        }
        return locations.get(pointer);
    }

    /** Exact mapping only when YAML has not folded or escaped the scalar text. */
    public SourceLocation expressionLocation(String field, int offset, java.util.List<String> lines) {
        ScalarNode scalar = scalars.get(pointer(field));
        if (scalar == null) return location(field);
        SourceLocation range = location(field);
        if (range.line() != range.endLine() && blockScalar(scalar)) {
            SourceLocation physical = blockScalarExpressionLocation(scalar, range, offset, lines);
            if (physical != null) return physical;
        }
        if (range.line() != range.endLine() || range.line() > lines.size()) return range;
        String line = lines.get(range.line() - 1);
        int start = range.column() - 1;
        int end = Math.min(line.length(), range.endColumn() - 1);
        if (start > end) return range;
        String raw = line.substring(start, end);
        String value = scalar.getValue();
        int quote = 0;
        if (raw.equals(value)) quote = 0;
        else if (raw.length() >= 2 && (raw.charAt(0) == '\'' || raw.charAt(0) == '"')
                && raw.charAt(raw.length() - 1) == raw.charAt(0)
                && raw.substring(1, raw.length() - 1).equals(value)) quote = 1;
        else return range;
        int column = start + quote + Math.min(Math.max(0, offset), value.length()) + 1;
        return new SourceLocation(file, range.line(), column, range.line(), column, null);
    }

    public boolean isBlockScalar(String field) {
        ScalarNode scalar = scalars.get(pointer(field));
        return scalar != null && blockScalar(scalar);
    }

    /** Decoded value captured by SnakeYAML for a scalar field, if present. */
    public String scalarValue(String field) {
        ScalarNode scalar = scalars.get(pointer(field));
        return scalar == null ? null : scalar.getValue();
    }

    /** Finds a short, single-line fragment directly in a block scalar's physical body. */
    public SourceLocation blockScalarTextLocation(String field, String text, List<String> lines) {
        ScalarNode scalar = scalars.get(pointer(field));
        if (scalar == null || !blockScalar(scalar) || text == null || text.isEmpty()) return null;
        SourceLocation range = location(field);
        int lastLine = Math.min(range.endLine(), lines.size());
        for (int line = range.line() + 1; line <= lastLine; line++) {
            String raw = lines.get(line - 1);
            int column = raw.indexOf(text);
            if (column >= 0) return new SourceLocation(file, line, column + 1, line, column + 1, null);
        }
        return null;
    }

    /** Points at the first non-blank physical body line when no precise fragment can be mapped. */
    public SourceLocation blockScalarBodyLocation(String field, List<String> lines) {
        if (!isBlockScalar(field)) return null;
        SourceLocation range = location(field);
        int lastLine = Math.min(range.endLine(), lines.size());
        for (int line = range.line() + 1; line <= lastLine; line++) {
            String raw = lines.get(line - 1);
            if (!raw.trim().isEmpty()) return new SourceLocation(file, line, indentation(raw) + 1,
                    line, indentation(raw) + 1, null);
        }
        return null;
    }

    private static boolean blockScalar(ScalarNode scalar) {
        return scalar.getScalarStyle() == DumperOptions.ScalarStyle.FOLDED
                || scalar.getScalarStyle() == DumperOptions.ScalarStyle.LITERAL;
    }

    /** Maps decoded block-scalar offsets back to their original physical source line/column. */
    private SourceLocation blockScalarExpressionLocation(ScalarNode scalar, SourceLocation range,
                                                         int offset, List<String> lines) {
        if (range.line() >= lines.size()) return null;
        int lastLine = Math.min(range.endLine(), lines.size());
        int baseIndent = Integer.MAX_VALUE;
        for (int line = range.line(); line < lastLine; line++) {
            String raw = lines.get(line);
            if (raw.trim().isEmpty()) continue;
            baseIndent = Math.min(baseIndent, indentation(raw));
        }
        if (baseIndent == Integer.MAX_VALUE) return null;

        StringBuilder decoded = new StringBuilder();
        List<Integer> sourceLines = new ArrayList<Integer>();
        List<Integer> sourceColumns = new ArrayList<Integer>();
        String previous = null;
        int previousIndent = baseIndent;
        for (int index = range.line(); index < lastLine; index++) {
            String raw = lines.get(index);
            int indent = Math.min(indentation(raw), raw.length());
            String content = raw.substring(Math.min(baseIndent, raw.length()));
            if (content.trim().isEmpty()) content = "";
            if (previous != null) {
                boolean preserveBreak = scalar.getScalarStyle() == DumperOptions.ScalarStyle.LITERAL
                        || previous.isEmpty() || content.isEmpty()
                        || previousIndent > baseIndent || indent > baseIndent;
                decoded.append(preserveBreak ? '\n' : ' ');
                sourceLines.add(index); sourceColumns.add(indent + 1);
            }
            for (int column = 0; column < content.length(); column++) {
                decoded.append(content.charAt(column));
                sourceLines.add(index + 1); sourceColumns.add(baseIndent + column + 1);
            }
            previous = content;
            previousIndent = indent;
        }
        String value = scalar.getValue();
        if (decoded.length() != value.length() || !decoded.toString().equals(value)) return null;
        if (value.isEmpty() || sourceLines.isEmpty()) return null;
        int position = Math.max(0, Math.min(offset, value.length() - 1));
        return new SourceLocation(file, sourceLines.get(position), sourceColumns.get(position),
                sourceLines.get(position), sourceColumns.get(position), null);
    }

    private static int indentation(String line) {
        int count = 0;
        while (count < line.length() && (line.charAt(count) == ' ' || line.charAt(count) == '\t')) count++;
        return count;
    }

    public static SourceLocation range(String file, Mark start, Mark end) {
        if (start == null) return null;
        if (end == null) end = start;
        return new SourceLocation(file, start.getLine() + 1, start.getColumn() + 1,
                end.getLine() + 1, end.getColumn() + 1, null);
    }

    private static String escape(String key) { return key.replace("~", "~0").replace("/", "~1"); }
    private static String pointer(String field) {
        if (field == null || field.isEmpty() || "$".equals(field)) return "";
        if (field.startsWith("/")) return field;
        String text = field.startsWith("$.") ? field.substring(2) : field.startsWith("$") ? field.substring(1) : field;
        StringBuilder result = new StringBuilder();
        StringBuilder key = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c == '.' || c == '[') {
                if (key.length() > 0) { result.append('/').append(escape(key.toString())); key.setLength(0); }
                if (c == '[') {
                    int end = text.indexOf(']', index + 1);
                    if (end < 0) return result.toString();
                    String bracket = text.substring(index + 1, end);
                    if (bracket.length() >= 2 && ((bracket.startsWith("'") && bracket.endsWith("'"))
                            || (bracket.startsWith("\"") && bracket.endsWith("\"")))) bracket = bracket.substring(1, bracket.length() - 1);
                    result.append('/').append(escape(bracket)); index = end;
                }
            } else key.append(c);
        }
        if (key.length() > 0) result.append('/').append(escape(key.toString()));
        return result.toString();
    }
}
