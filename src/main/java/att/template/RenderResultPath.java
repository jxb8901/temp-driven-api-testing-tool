package att.template;

import att.core.IdentifierValidator;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared Render result.path token, source-relative, and lexical-safety rules. */
public final class RenderResultPath {
    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]+)\\}");
    private static final Pattern ALLOWED = Pattern.compile("\\{(?:filename|name|ext|index|relativePath)\\}");

    private RenderResultPath() { }

    /** Validates Render tokens while treating normal ATT expressions as opaque path segments. */
    public static String maskExpressionsAndValidate(String pattern) {
        String masked = maskExpressions(pattern);
        Matcher tokens = TOKEN.matcher(masked);
        while (tokens.find()) if (!isToken(tokens.group(1))) {
            throw new IllegalArgumentException("Unknown Render result.path token {" + tokens.group(1) + "}");
        }
        String residue = ALLOWED.matcher(masked).replaceAll("");
        if (residue.indexOf('{') >= 0 || residue.indexOf('}') >= 0) {
            throw new IllegalArgumentException("Malformed Render result.path token: " + pattern);
        }
        return masked;
    }

    public static String expand(String pattern, String relativePath, int index) {
        String masked = maskExpressionsAndValidate(pattern);
        if (!masked.equals(pattern)) throw new IllegalArgumentException("Render result.path must be ATT-rendered before token expansion");
        String filename = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        int dot = filename.lastIndexOf('.');
        String name = dot <= 0 ? filename : filename.substring(0, dot);
        String ext = dot < 0 || dot == filename.length() - 1 ? "" : filename.substring(dot + 1);
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put("filename", filename); values.put("name", name); values.put("ext", ext);
        values.put("index", String.valueOf(index)); values.put("relativePath", relativePath);
        Matcher matcher = TOKEN.matcher(pattern);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(expanded, Matcher.quoteReplacement(values.get(matcher.group(1))));
        matcher.appendTail(expanded);
        if (expanded.indexOf("{") >= 0 || expanded.indexOf("}") >= 0) throw new IllegalArgumentException("Malformed Render result.path token: " + pattern);
        return expanded.toString();
    }

    /** Returns the same source path relative to the pre-glob root used at runtime. */
    public static String relativeSource(Path templateDirectory, String payloadPattern, Path source) throws Exception {
        int wildcard = firstGlobCharacter(payloadPattern);
        String prefix = wildcard < 0 ? payloadPattern : payloadPattern.substring(0, wildcard);
        int slash = prefix.lastIndexOf('/');
        String directory = slash < 0 ? "" : prefix.substring(0, slash);
        Path root = templateDirectory.toRealPath()
                .resolve(directory.replace('/', java.io.File.separatorChar)).normalize().toRealPath();
        return RenderPayloadResolver.portable(root.relativize(source));
    }

    /** Validates a statically expanded path with the runtime's relative-path contract. */
    public static String safeRelativeTarget(String expandedPath) {
        Path relative = IdentifierValidator.relativePath(expandedPath, "Render result.path");
        return relative.normalize().toString().replace('\\', '/');
    }

    private static boolean isToken(String token) {
        return "filename".equals(token) || "name".equals(token) || "ext".equals(token)
                || "index".equals(token) || "relativePath".equals(token);
    }

    private static String maskExpressions(String pattern) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < pattern.length();) {
            if (index + 1 < pattern.length() && (pattern.startsWith("${", index) || pattern.startsWith("#{", index))) {
                int end = expressionEnd(pattern, index + 1);
                result.append("ATTEXPR");
                index = end + 1;
            } else result.append(pattern.charAt(index++));
        }
        return result.toString();
    }

    private static int expressionEnd(String text, int openingBrace) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = openingBrace; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == quote) quote = 0;
                continue;
            }
            if (value == '\'' || value == '"') quote = value;
            else if (value == '{') depth++;
            else if (value == '}' && --depth == 0) return index;
        }
        throw new IllegalArgumentException("Unterminated ATT expression in Render result.path: " + text);
    }

    private static int firstGlobCharacter(String value) {
        int result = -1;
        for (char token : new char[]{'*', '?', '{', '['}) {
            int found = value.indexOf(token);
            if (found >= 0 && (result < 0 || found < result)) result = found;
        }
        return result;
    }
}
