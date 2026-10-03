package att.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Formats local paths at human-readable diagnostic boundaries. */
public final class PathPresentation {
    private PathPresentation() { }

    public static String displayPath(Path value, Path projectRoot) {
        if (value == null) return "";
        Path path = value.toAbsolutePath().normalize();
        Path root = projectRoot == null ? null : projectRoot.toAbsolutePath().normalize();
        if (root != null) {
            Path canonicalRoot = canonicalIfPresent(root);
            Path candidate = canonicalIfPresent(path);
            if (path.equals(root) || candidate.equals(canonicalRoot)) return "$ATT_HOME";
            if (path.startsWith(root) && containedForDisplay(path, candidate, canonicalRoot)) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                return relative.isEmpty() ? "$ATT_HOME" : "$ATT_HOME/" + relative;
            }
            if (candidate.startsWith(canonicalRoot)) {
                String relative = canonicalRoot.relativize(candidate).toString().replace('\\', '/');
                return relative.isEmpty() ? "$ATT_HOME" : "$ATT_HOME/" + relative;
            }
        }
        Path name = path.getFileName();
        return name == null ? "$EXTERNAL" : "$EXTERNAL/" + name.toString();
    }

    /** Replaces the project root when it appears inside an exception or process line. */
    public static String displayText(String text, Path projectRoot) {
        if (text == null || projectRoot == null) return text;
        Path root = projectRoot.toAbsolutePath().normalize();
        String value = text;
        Path canonicalRoot = canonicalIfPresent(root);
        String[] spellings = new String[]{root.toString(), root.toString().replace('\\', '/'), root.toString().replace('/', '\\'),
                canonicalRoot.toString(), canonicalRoot.toString().replace('\\', '/'), canonicalRoot.toString().replace('/', '\\')};
        for (String spelling : spellings) {
            if (spelling == null || spelling.isEmpty()) continue;
            value = replaceRoot(value, spelling);
        }
        return value.replace("$ATT_HOME\\", "$ATT_HOME/");
    }

    /** Returns a detached, presentation-safe copy for structured evidence. */
    public static Object displayStructure(Object value, Path projectRoot) {
        return displayStructure(value, projectRoot, null);
    }

    private static Object displayStructure(Object value, Path projectRoot, String field) {
        if (value instanceof Path) return displayPath((Path) value, projectRoot);
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? null : String.valueOf(entry.getKey());
                copy.put(entry.getKey(), displayStructure(entry.getValue(), projectRoot, key));
            }
            return copy;
        }
        if (value instanceof Iterable) {
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) copy.add(displayStructure(item, projectRoot, field));
            return copy;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> copy = new ArrayList<Object>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++)
                copy.add(displayStructure(java.lang.reflect.Array.get(value, i), projectRoot, field));
            return copy;
        }
        if (!(value instanceof String)) return value;
        String text = displayText((String) value, projectRoot);
        if (isLocalPathField(field) && looksAbsolutePath((String) value)) {
            try { return displayPath(java.nio.file.Paths.get((String) value), projectRoot); }
            catch (RuntimeException ignored) { return text; }
        }
        return text;
    }

    private static boolean isLocalPathField(String field) {
        if (field == null || field.toLowerCase(java.util.Locale.ROOT).contains("remote")) return false;
        String key = field.toLowerCase(java.util.Locale.ROOT);
        return key.equals("path") || key.equals("file") || key.equals("directory") || key.equals("dir")
                || key.equals("cwd") || key.equals("root") || key.endsWith("path") || key.endsWith("file")
                || key.endsWith("directory") || key.endsWith("dir");
    }

    private static boolean looksAbsolutePath(String value) {
        return value.startsWith("/") || value.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static String replaceRoot(String value, String root) {
        StringBuilder result = new StringBuilder(value.length());
        int cursor = 0;
        int match;
        while ((match = value.indexOf(root, cursor)) >= 0) {
            int end = match + root.length();
            boolean componentStart = match == 0 || !Character.isLetterOrDigit(value.charAt(match - 1))
                    && value.charAt(match - 1) != '_';
            if (componentStart && (end == value.length() || value.charAt(end) == '/' || value.charAt(end) == '\\')) {
                result.append(value, cursor, match).append("$ATT_HOME");
                cursor = end;
            } else {
                result.append(value, cursor, end);
                cursor = end;
            }
        }
        result.append(value, cursor, value.length());
        return result.toString();
    }

    private static boolean containedForDisplay(Path lexical, Path canonical, Path root) {
        if (Files.exists(lexical)) return canonical.startsWith(root);
        Path existing = lexical.getParent();
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return lexical.startsWith(root);
        return canonicalIfPresent(existing).startsWith(root);
    }

    private static Path canonicalIfPresent(Path path) {
        try { return path.toRealPath(); }
        catch (IOException unavailable) { return path.toAbsolutePath().normalize(); }
    }
}
