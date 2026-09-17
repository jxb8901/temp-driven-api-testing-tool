package att.config;

import att.validation.SourceLocation;
import org.yaml.snakeyaml.error.Mark;
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
