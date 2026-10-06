package att.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;

/** Formats local paths at human-readable diagnostic boundaries. */
public final class PathPresentation {
    private static final Pattern QUOTED_ABSOLUTE_PATH = Pattern.compile("(['\"])((?:/[^\\r\\n'\"]+)|(?:[A-Za-z]:[\\\\/][^\\r\\n'\"]+)|(?:\\\\\\\\[^\\r\\n'\"]+))\\1");
    private static final Pattern ABSOLUTE_PATH_TOKEN = Pattern.compile("(?<![A-Za-z0-9_/:])(?:[A-Za-z]:[\\\\/]|//|\\\\\\\\|/)");
    private static final String[] DIAGNOSTIC_PROSE_BOUNDARIES = {
            " to ", " from ", " because ", " while ", " when ", " after ", " before ",
            " during ", " and ", " or ", " then ", " but ", " (Permission denied)",
            " (Access is denied)", " (No such file or directory)", " (File exists)",
            " (The system cannot find the file specified)"
    };

    private PathPresentation() { }

    public static String displayPath(Path value, Path projectRoot) {
        if (value == null) return "";
        String portable = displayPortableAbsolute(value.toString(), projectRoot);
        if (portable != null) return portable;
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

    /** Replaces the package root in free-form text without interpreting arbitrary values as local paths. */
    public static String displayText(String text, Path projectRoot) {
        return displayText(text, projectRoot, false);
    }

    /** Keeps explicitly remote values intact while formatting ordinary diagnostic text. */
    static String displayText(String text, Path projectRoot, boolean preserveText) {
        if (text == null || preserveText) return text;
        String value = text;
        if (projectRoot != null) {
            Path root = projectRoot.toAbsolutePath().normalize();
            Path canonicalRoot = canonicalIfPresent(root);
            String[] spellings = new String[]{root.toString(), root.toString().replace('\\', '/'), root.toString().replace('/', '\\'),
                    canonicalRoot.toString(), canonicalRoot.toString().replace('\\', '/'), canonicalRoot.toString().replace('/', '\\')};
            for (String spelling : spellings) {
                if (spelling == null || spelling.isEmpty()) continue;
                value = replaceRoot(value, spelling);
            }
        }
        return value.replace("$ATT_HOME\\", "$ATT_HOME/");
    }

    /** Redacts external absolute paths when formatting diagnostic text, where they are not needed verbatim. */
    public static String displayDiagnosticText(String text, Path projectRoot) {
        return displayDiagnosticText(text, projectRoot, java.util.Collections.<String>emptySet());
    }

    /** Preserves explicit remote paths while redacting other absolute paths in diagnostics. */
    static String displayDiagnosticText(String text, Path projectRoot, Set<String> preservedPaths) {
        if (text == null) return null;
        List<String> tokens = new ArrayList<String>();
        String masked = text;
        if (preservedPaths != null && !preservedPaths.isEmpty()) {
            List<String> ordered = new ArrayList<String>(preservedPaths);
            ordered.sort((left, right) -> Integer.compare(right == null ? 0 : right.length(), left == null ? 0 : left.length()));
            for (String path : ordered) {
                if (path == null || path.isEmpty() || !masked.contains(path)) continue;
                String token = "\u0001ATT_REMOTE_PATH_" + tokens.size() + "\u0002";
                masked = replaceBoundedPath(masked, path, token);
                tokens.add(path);
            }
        }
        String value = displayText(masked, projectRoot);
        value = redactQuotedAbsolutePaths(value, projectRoot);
        value = redactAbsolutePathTokens(value, projectRoot);
        for (int index = 0; index < tokens.size(); index++)
            value = value.replace("\u0001ATT_REMOTE_PATH_" + index + "\u0002", tokens.get(index));
        return value;
    }

    /** Returns a detached, presentation-safe copy for structured evidence. */
    public static Object displayStructure(Object value, Path projectRoot) {
        Set<String> remotePaths = new LinkedHashSet<String>();
        collectRemotePaths(value, remotePaths, new java.util.IdentityHashMap<Object, Boolean>());
        return displayStructure(value, projectRoot, null, remotePaths);
    }

    private static Object displayStructure(Object value, Path projectRoot, String field, Set<String> remotePaths) {
        if (value instanceof Path) return isRemoteField(field) ? value.toString() : displayPath((Path) value, projectRoot);
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? null : String.valueOf(entry.getKey());
                copy.put(entry.getKey(), displayStructure(entry.getValue(), projectRoot, key, remotePaths));
            }
            return copy;
        }
        if (value instanceof Iterable) {
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) copy.add(displayStructure(item, projectRoot, field, remotePaths));
            return copy;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> copy = new ArrayList<Object>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++)
                copy.add(displayStructure(java.lang.reflect.Array.get(value, i), projectRoot, field, remotePaths));
            return copy;
        }
        if (!(value instanceof String)) return value;
        String text = isDiagnosticField(field)
                ? displayDiagnosticText((String) value, projectRoot, remotePaths)
                : displayText((String) value, projectRoot, isRemoteField(field));
        if (isLocalPathField(field) && isAbsolutePathText((String) value)) return displayPathText((String) value, projectRoot);
        return text;
    }

    static boolean isRemoteField(String field) {
        if (field == null) return false;
        return isRemotePathField(field) || isUrlOrUriField(field);
    }

    static boolean isDiagnosticField(String field) {
        if (field == null) return false;
        String key = field.toLowerCase(java.util.Locale.ROOT);
        return key.equals("diagnostic") || key.equals("message") || key.equals("detail")
                || key.equals("summary") || key.equals("suggestion") || key.equals("cause")
                || key.equals("error") || key.equals("exception") || key.equals("reason")
                || key.equals("retrydecision") || key.endsWith("error") || key.endsWith("warning")
                || key.endsWith("diagnostic");
    }

    static boolean isLocalPathField(String field) {
        if (field == null || isRemotePathField(field) || isUrlOrUriField(field)) return false;
        String key = field.toLowerCase(java.util.Locale.ROOT);
        return key.equals("path") || key.equals("file") || key.equals("directory") || key.equals("dir")
                || key.equals("cwd") || key.equals("root") || key.endsWith("path") || key.endsWith("file")
                || key.endsWith("directory") || key.endsWith("dir");
    }

    private static boolean isRemotePathField(String field) {
        String key = field.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
        boolean pathLike = key.endsWith("path") || key.endsWith("file")
                || key.endsWith("directory") || key.endsWith("dir");
        return pathLike && (key.startsWith("remote") || key.endsWith("remotepath")
                || key.endsWith("remotefile") || key.endsWith("remotedirectory") || key.endsWith("remotedir"));
    }

    private static boolean isUrlOrUriField(String field) {
        String key = field.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
        return key.equals("url") || key.endsWith("url") || key.equals("uri") || key.endsWith("uri");
    }

    static boolean isAbsolutePathText(String value) {
        return isUncPath(value) || isDriveAbsolute(value)
                || (value != null && value.startsWith("/"));
    }

    static String displayPathText(String value, Path projectRoot) {
        if (!isAbsolutePathText(value)) return value;
        String portable = displayPortableAbsolute(value, projectRoot);
        if (portable != null) return portable;
        try {
            Path path = java.nio.file.Paths.get(value);
            return path.isAbsolute() ? displayPath(path, projectRoot) : externalBasename(value);
        } catch (RuntimeException ignored) {
            return externalBasename(value);
        }
    }

    private static String displayPortableAbsolute(String value, Path projectRoot) {
        if (isUncPath(value)) {
            if (projectRoot != null && isUncPath(projectRoot.toString())) {
                String path = slashForm(value);
                String root = slashForm(projectRoot.toString());
                while (root.length() > 2 && root.endsWith("/")) root = root.substring(0, root.length() - 1);
                if (path.equals(root)) return "$ATT_HOME";
                if (path.startsWith(root + "/")) return "$ATT_HOME/" + path.substring(root.length() + 1);
            }
            return externalBasename(value);
        }
        if (isDriveAbsolute(value)) {
            try { if (java.nio.file.Paths.get(value).isAbsolute()) return null; }
            catch (RuntimeException ignored) { }
            return externalBasename(value);
        }
        return null;
    }

    private static boolean isDriveAbsolute(String value) {
        return value != null && value.matches("^[A-Za-z]:[\\\\/].*");
    }

    private static boolean isUncPath(String value) {
        return value != null && (value.startsWith("\\\\") || value.startsWith("//"));
    }

    private static String slashForm(String value) {
        String result = value.replace('\\', '/');
        while (result.contains("//")) result = result.replace("//", "/");
        return result;
    }

    private static String externalBasename(String value) {
        if (value == null || value.isEmpty()) return "$EXTERNAL";
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '/' || value.charAt(end - 1) == '\\')) end--;
        if (end == 0) return "$EXTERNAL";
        int slash = Math.max(value.lastIndexOf('/', end - 1), value.lastIndexOf('\\', end - 1));
        String name = value.substring(slash + 1, end);
        return name.isEmpty() ? "$EXTERNAL" : "$EXTERNAL/" + name;
    }

    private static void collectRemotePaths(Object value, Set<String> paths, java.util.IdentityHashMap<Object, Boolean> visited) {
        if (value instanceof Path) return;
        if (!(value instanceof Map) && !(value instanceof Iterable)
                && (value == null || !value.getClass().isArray())) return;
        if (value == null || visited.put(value, Boolean.TRUE) != null) return;
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).toLowerCase(java.util.Locale.ROOT);
                Object item = entry.getValue();
                if (key.contains("remote") && (key.endsWith("path") || key.endsWith("file")) && item != null)
                    paths.add(String.valueOf(item));
                collectRemotePaths(item, paths, visited);
            }
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) collectRemotePaths(item, paths, visited);
        } else if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) collectRemotePaths(java.lang.reflect.Array.get(value, index), paths, visited);
        }
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

    private static String redactQuotedAbsolutePaths(String text, Path projectRoot) {
        Matcher matcher = QUOTED_ABSOLUTE_PATH.matcher(text);
        StringBuffer result = new StringBuffer(text.length());
        while (matcher.find()) {
            String path = trimTrailingPunctuation(matcher.group(2));
            String suffix = matcher.group(2).substring(path.length());
            String replacement = matcher.group(1) + embeddedPath(path, projectRoot) + suffix + matcher.group(1);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String redactAbsolutePathTokens(String text, Path projectRoot) {
        Matcher matcher = ABSOLUTE_PATH_TOKEN.matcher(text);
        StringBuilder result = new StringBuilder(text.length());
        int cursor = 0;
        while (matcher.find(cursor)) {
            int start = matcher.start();
            if (start < cursor) continue;
            int end = scanDiagnosticPathEnd(text, start);
            String candidate = text.substring(start, end);
            String path = trimTrailingPunctuation(candidate);
            if (path.isEmpty()) path = candidate;
            result.append(text, cursor, start).append(embeddedPath(path, projectRoot));
            result.append(text, start + path.length(), end);
            cursor = end;
        }
        result.append(text, cursor, text.length());
        return result.toString();
    }

    /**
     * Bounds an unquoted path at common diagnostic prose and separators while allowing spaces
     * inside filenames (for example, "token (final).pem").
     */
    private static int scanDiagnosticPathEnd(String text, int start) {
        int end = start;
        while (end < text.length() && text.charAt(end) != '\r' && text.charAt(end) != '\n'
                && text.charAt(end) != '\'' && text.charAt(end) != '"'
                && text.charAt(end) != '<' && text.charAt(end) != '>') {
            char current = text.charAt(end);
            if (current == ',' || current == ';') break;
            if (current == ':' && end + 1 < text.length() && text.charAt(end + 1) == ' ') break;
            if (current == ' ' && startsDiagnosticProse(text, end)) break;
            if (end > start && (current == '/' || current == '\\')
                    && end > 0 && Character.isWhitespace(text.charAt(end - 1))
                    && isAbsolutePathRootAt(text, end)) {
                end--;
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
                break;
            }
            end++;
        }
        return end;
    }

    private static boolean startsDiagnosticProse(String text, int offset) {
        for (String boundary : DIAGNOSTIC_PROSE_BOUNDARIES) {
            if (offset + boundary.length() <= text.length()
                    && text.regionMatches(true, offset, boundary, 0, boundary.length())) return true;
        }
        return false;
    }

    private static boolean isAbsolutePathRootAt(String text, int offset) {
        Matcher matcher = ABSOLUTE_PATH_TOKEN.matcher(text);
        matcher.region(offset, text.length());
        return matcher.lookingAt();
    }

    private static String replaceBoundedPath(String text, String path, String replacement) {
        StringBuilder result = new StringBuilder(text.length());
        int cursor = 0;
        int match;
        while ((match = text.indexOf(path, cursor)) >= 0) {
            int end = match + path.length();
            if (isPathBoundaryBefore(text, match) && isPathBoundaryAfter(text, end)) {
                result.append(text, cursor, match).append(replacement);
                cursor = end;
            } else {
                result.append(text, cursor, match + 1);
                cursor = match + 1;
            }
        }
        result.append(text, cursor, text.length());
        return result.toString();
    }

    private static boolean isPathBoundaryBefore(String text, int offset) {
        if (offset == 0) return true;
        char previous = text.charAt(offset - 1);
        return !isPathCharacter(previous);
    }

    private static boolean isPathBoundaryAfter(String text, int offset) {
        if (offset == text.length()) return true;
        return !isPathCharacter(text.charAt(offset));
    }

    private static boolean isPathCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-' || value == '.'
                || value == '+' || value == '%' || value == '/' || value == '\\';
    }

    private static String trimTrailingPunctuation(String path) {
        int end = path.length();
        while (end > 1 && ".!?".indexOf(path.charAt(end - 1)) >= 0) end--;
        return path.substring(0, end);
    }

    private static String embeddedPath(String value, Path projectRoot) {
        return displayPathText(value, projectRoot);
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
