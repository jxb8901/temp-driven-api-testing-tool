package att.security;

import java.lang.reflect.Array;
import java.util.*;
import java.util.regex.*;

/** Mandatory, presentation-independent protection shared by Engine and public transports.
 * No switch disables this policy. Unknown objects and cyclic containers fail closed.
 */
public final class SecretProtection {
    public static final String REDACTED = "[REDACTED_SECRET]";
    private static final String NAMES = "password|passwd|passphrase|token|secret|api[-_]?key|authorization|credential|private[-_]?key|cookie|set-cookie|access[-_]?key|identityfile|truststorepassword|client[-_]?secret";
    private static final Pattern MARKER = Pattern.compile("\\[REDACTED(?:_SECRET)?\\]");
    private static final Pattern ASSIGNMENT = Pattern.compile("(?i)((?<![A-Za-z0-9])(?:" + NAMES + ")\\b[\\\"']?[ \\t]*[=:][ \\t]*)");
    private static final Pattern SENSITIVE_BLOCK_KEY = Pattern.compile("(?i)((?<![A-Za-z0-9])(?:" + NAMES + ")\\b[\\\"\']?[ \\t]*[=:][ \\t]*+)");
    private static final Pattern HEADER = Pattern.compile("(?i)(\\b(?:proxy-authorization|authorization|set-cookie|cookie)\\b[ \\t]*[:=][ \\t]*+)(?![\"'])[^\\r\\n]*");
    private static final Pattern AUTH = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/_.~=-]+");
    private static final Pattern URL_AUTH = Pattern.compile("(://)[^\\s/@<>]+:[^\\s/@<>]+@");
    private static final Pattern XML = Pattern.compile("(?is)(<((?:[\\w.-]+:)?(?:" + NAMES + "))(?:\\s[^>]*)?>).*?(</\\2\\s*>)");
    private static final Pattern PEM = Pattern.compile("(?s)-----BEGIN (?:[A-Z0-9 ]* )?PRIVATE KEY-----.*?(?:-----END (?:[A-Z0-9 ]* )?PRIVATE KEY-----|$)");
    private static final Pattern TAGGED = Pattern.compile("(?im)(!secret\\s+)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\r\\n]+)");
    private SecretProtection() { }

    public static boolean sensitiveName(String name) {
        if (name == null) return false;
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return key.contains("password") || key.contains("passwd") || key.contains("passphrase")
                || key.contains("token") || key.contains("secret") || key.contains("credential")
                || key.contains("apikey") || key.contains("privatekey") || key.contains("accesskey")
                || key.contains("authorization") || key.contains("cookie") || key.equals("identityfile");
    }

    public static String text(String value) { return text(value, Collections.<String>emptyList()); }
    public static String text(String value, Collection<String> registered) {
        if (value == null) return "";
        String result = value;
        List<String> secrets = new ArrayList<String>();
        if (registered != null) for (String secret : registered) if (secret != null && !secret.isEmpty()) secrets.add(secret);
        Collections.sort(secrets, new Comparator<String>() {
            public int compare(String a, String b) { return Integer.compare(b.length(), a.length()); }
        });
        for (String secret : secrets) result = replaceRegistered(result, secret);
        result = PEM.matcher(result).replaceAll(REDACTED);
        result = replace(HEADER, result, 1, 0);
        result = AUTH.matcher(result).replaceAll("$1 " + REDACTED);
        result = URL_AUTH.matcher(result).replaceAll("$1" + REDACTED + "@");
        result = replace(XML, result, 1, 3);
        result = redactSensitiveBlocks(result);
        result = redactAssignments(result);
        result = TAGGED.matcher(result).replaceAll("$1" + REDACTED);
        return csv(result);
    }

    private static String replaceRegistered(String value, String secret) {
        Matcher marker = MARKER.matcher(value); StringBuilder safe = new StringBuilder(); int cursor=0;
        while(marker.find()) {
            safe.append(value.substring(cursor,marker.start()).replace(secret,REDACTED)).append(marker.group());
            cursor=marker.end();
        }
        return safe.append(value.substring(cursor).replace(secret,REDACTED)).toString();
    }

    private static String replace(Pattern pattern, String value, int prefix, int suffix) {
        Matcher m = pattern.matcher(value); StringBuffer out = new StringBuffer();
        while (m.find()) {
            // Parser prose names a lexical token; it is not a credential field.
            String marker = REDACTED;
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(prefix) + marker + (suffix == 0 ? "" : m.group(suffix))));
        }
        m.appendTail(out); return out.toString();
    }

    /** Redact complete quoted values and fail closed to end-of-line for plain scalars. */
    private static String redactAssignments(String value) {
        Matcher matcher = ASSIGNMENT.matcher(value);
        StringBuilder result = new StringBuilder(value.length());
        int copiedThrough = 0;
        int searchFrom = 0;
        while (matcher.find(searchFrom)) {
            String prefix = matcher.group(1);
            if (matcher.start() >= 11
                    && value.regionMatches(true, matcher.start() - 11, "unexpected ", 0, 11)
                    && prefix.matches("(?i)token[ \\t]*:[ \\t]*")) {
                searchFrom = matcher.end();
                continue;
            }

            int start = matcher.end();
            if (start >= value.length() || value.charAt(start) == '\r' || value.charAt(start) == '\n') {
                searchFrom = matcher.end();
                continue;
            }

            char quote = value.charAt(start);
            int end;
            String replacement;
            if (quote == '\"' || quote == '\'') {
                int close = quotedValueEnd(value, start, quote);
                if (close >= 0) {
                    end = close + 1;
                    replacement = quote + REDACTED + quote;
                } else {
                    end = lineEnd(value, start);
                    replacement = quote + REDACTED;
                }
            } else {
                end = plainValueEnd(value, start);
                replacement = REDACTED;
            }
            if (end <= start) {
                searchFrom = matcher.end();
                continue;
            }
            result.append(value, copiedThrough, start).append(replacement);
            copiedThrough = end;
            searchFrom = end;
        }
        return result.append(value, copiedThrough, value.length()).toString();
    }

    private static int plainValueEnd(String value, int start) {
        int end = lineEnd(value, start);
        for (int i = start; i < end; i++) {
            char c = value.charAt(i);
            if (c == ',' || c == ';' || c == '&'
                    || c == '<' || c == '>' || c == '}') return i;
        }
        return end;
    }

    private static int quotedValueEnd(String value, int start, char quote) {
        for (int i = start + 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n') return -1;
            if (c == '\\' && i + 1 < value.length()) {
                i++;
            } else if (quote == '\'' && c == '\'' && i + 1 < value.length()
                    && value.charAt(i + 1) == '\'') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return -1;
    }

    /** True while a sensitive YAML/container value at the end may continue in the next read. */
    public static boolean hasIncompleteSensitiveBlock(String value) {
        Matcher matcher = SENSITIVE_BLOCK_KEY.matcher(value);
        while (matcher.find()) {
            int start = matcher.end();
            if (start >= value.length()) return true;
            char first = value.charAt(start);
            if (first == '{' || first == '[') {
                if (!containerClosed(value, start)) return true;
            } else if (first == '\n' || first == '\r') {
                if (!indentedBlockComplete(value, matcher.start(), start)) return true;
            } else if (first == '|' || first == '>') {
                int headerEnd = lineEnd(value, start);
                if (headerEnd >= value.length()
                        || !indentedBlockComplete(value, matcher.start(), headerEnd)) return true;
            }
        }
        return false;
    }

    private static boolean containerClosed(String value, int start) {
        Deque<Character> closings = new ArrayDeque<Character>();
        char quote = 0;
        boolean escaped = false;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                closings.push(c == '{' ? '}' : ']');
            } else if (c == '}' || c == ']') {
                if (closings.isEmpty() || closings.pop() != c) return false;
                if (closings.isEmpty()) return true;
            }
        }
        return false;
    }

    private static boolean indentedBlockComplete(String value, int keyStart, int newline) {
        int lineStart = value.lastIndexOf('\n', Math.max(0, keyStart - 1)) + 1;
        int parentIndent = 0;
        for (int i = lineStart; i < keyStart; i++) {
            char c = value.charAt(i);
            if (c != ' ' && c != '\t') { parentIndent = 0; break; }
            parentIndent++;
        }
        int cursor = newline;
        while (cursor < value.length()) {
            if (value.charAt(cursor) == '\r') cursor++;
            if (cursor < value.length() && value.charAt(cursor) == '\n') cursor++;
            else break;
            if (cursor >= value.length()) return false;
            int nextEnd = lineEnd(value, cursor);
            int indentation = 0;
            while (cursor + indentation < nextEnd) {
                char c = value.charAt(cursor + indentation);
                if (c != ' ' && c != '\t') break;
                indentation++;
            }
            if (cursor + indentation == nextEnd) {
                cursor = nextEnd;
                continue;
            }
            if (indentation <= parentIndent) return true;
            cursor = nextEnd;
        }
        return false;
    }

    /** Mask only the sensitive nested value, retaining later sibling fields and diagnostics. */
    private static String redactSensitiveBlocks(String value) {
        Matcher matcher = SENSITIVE_BLOCK_KEY.matcher(value);
        StringBuilder result = new StringBuilder(value.length());
        int copiedThrough = 0;
        int searchFrom = 0;
        while (matcher.find(searchFrom)) {
            int start = matcher.end();
            int end = -1;
            if (start < value.length()) {
                char first = value.charAt(start);
                if (first == '{' || first == '[') {
                    end = containerEnd(value, start);
                } else if (first == '\n' || first == '\r') {
                    end = indentedBlockEnd(value, matcher.start(), start);
                } else if (first == '|' || first == '>') {
                    int lineEnd = lineEnd(value, start);
                    end = lineEnd == value.length() ? lineEnd : indentedBlockEnd(value, matcher.start(), lineEnd);
                    if (end < 0) end = lineEnd;
                }
            }
            if (end <= start) {
                searchFrom = matcher.end();
                continue;
            }
            result.append(value, copiedThrough, matcher.start()).append(matcher.group(1)).append(REDACTED);
            copiedThrough = end;
            searchFrom = end;
        }
        return result.append(value, copiedThrough, value.length()).toString();
    }

    /** A truncated or malformed nested collection is entirely sensitive: fail closed. */
    private static int containerEnd(String value, int start) {
        Deque<Character> closings = new ArrayDeque<Character>();
        char quote = 0;
        boolean escaped = false;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                closings.push(c == '{' ? '}' : ']');
            } else if (c == '}' || c == ']') {
                if (closings.isEmpty() || closings.pop() != c) return value.length();
                if (closings.isEmpty()) return i + 1;
            }
        }
        return value.length();
    }

    private static int lineEnd(String value, int from) {
        int i = from;
        while (i < value.length() && value.charAt(i) != '\n' && value.charAt(i) != '\r') i++;
        return i;
    }

    /** Consume only YAML continuation lines indented beyond the sensitive field. */
    private static int indentedBlockEnd(String value, int keyStart, int newline) {
        int lineStart = value.lastIndexOf('\n', Math.max(0, keyStart - 1)) + 1;
        int parentIndent = 0;
        for (int i = lineStart; i < keyStart; i++) {
            char c = value.charAt(i);
            if (c != ' ' && c != '\t') { parentIndent = 0; break; }
            parentIndent++;
        }
        int cursor = newline;
        int lastEnd = -1;
        while (cursor < value.length()) {
            if (value.charAt(cursor) == '\r') cursor++;
            if (cursor < value.length() && value.charAt(cursor) == '\n') cursor++;
            else break;
            int nextEnd = lineEnd(value, cursor);
            int indentation = 0;
            while (cursor + indentation < nextEnd) {
                char c = value.charAt(cursor + indentation);
                if (c != ' ' && c != '\t') break;
                indentation++;
            }
            boolean blank = cursor + indentation == nextEnd;
            if (!blank && indentation <= parentIndent) break;
            lastEnd = nextEnd;
            cursor = nextEnd;
        }
        return lastEnd;
    }

    /** Preserve CSV layout while protecting columns declared by a secret header. */
    private static String csv(String value) {
        if (value.indexOf(',') < 0 || value.indexOf('\n') < 0) return value;
        List<List<int[]>> rows = new ArrayList<List<int[]>>(); List<int[]> row = new ArrayList<int[]>();
        int start = 0; boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') { if (quoted && i + 1 < value.length() && value.charAt(i + 1) == '"') i++; else quoted = !quoted; }
            else if (!quoted && (c == ',' || c == '\r' || c == '\n')) {
                row.add(new int[]{start, i});
                if (c != ',') { rows.add(row); row = new ArrayList<int[]>(); if (c == '\r' && i + 1 < value.length() && value.charAt(i + 1) == '\n') i++; }
                start = i + 1;
            }
        }
        if (start < value.length() || !row.isEmpty()) { row.add(new int[]{start, value.length()}); rows.add(row); }
        if (rows.size() < 2) return value;
        List<Integer> columns = new ArrayList<Integer>();
        for (int i = 0; i < rows.get(0).size(); i++) {
            int[] span = rows.get(0).get(i);
            if (sensitiveName(value.substring(span[0], span[1]))) columns.add(i);
        }
        StringBuilder out = new StringBuilder(value);
        for (int r = rows.size() - 1; r > 0; r--) for (int c = columns.size() - 1; c >= 0; c--) {
            int column = columns.get(c); if (column >= rows.get(r).size()) continue;
            int[] span = rows.get(r).get(column); out.replace(span[0], span[1], "\"" + REDACTED + "\"");
        }
        return out.toString();
    }

    /** An opaque pointer contains no default value. Server restores it only after exact path/policy validation. */
    private static boolean keepDefaultReference(Object value) {
        if (!(value instanceof Map) || ((Map<?,?>)value).size()!=1) return false;
        Object pointer=((Map<?,?>)value).get("$attDebugKeepDefault");
        return pointer instanceof String && ((String)pointer).startsWith("/") && ((String)pointer).length()<=2048
                && ((String)pointer).indexOf('\n')<0 && ((String)pointer).indexOf('\r')<0;
    }

    public static Object value(Object input) { return value(input, new IdentityHashMap<Object,Boolean>(), 0); }
    private static Object value(Object input, IdentityHashMap<Object,Boolean> active, int depth) {
        if (input == null || input instanceof Boolean || input instanceof Number) return input;
        if (input instanceof CharSequence || input instanceof Character) return text(input.toString());
        if (depth > 64 || active.put(input, Boolean.TRUE) != null) return REDACTED;
        try {
            if (input instanceof Map<?,?>) {
                Map<String,Object> safe = new LinkedHashMap<String,Object>();
                for (Map.Entry<?,?> e : ((Map<?,?>)input).entrySet()) {
                    String key = e.getKey() instanceof String ? (String)e.getKey() : "[UNKNOWN_KEY]";
                    Object child = e.getValue();
                    if (sensitiveName(key) && keepDefaultReference(child)) {
                        safe.put(key, Collections.singletonMap("$attDebugKeepDefault", ((Map<?,?>)child).get("$attDebugKeepDefault")));
                    } else if (sensitiveName(key) && child instanceof Map && "hidden".equals(((Map<?,?>)child).get("state"))) {
                        Map<String,Object> protectedField = new LinkedHashMap<String,Object>();
                        protectedField.put("state", "hidden"); protectedField.put("value", "[REDACTED]");
                        protectedField.put("reasonCode", "MANDATORY_SECRET"); safe.put(key, protectedField);
                    } else safe.put(key, sensitiveName(key) ? REDACTED : value(child, active, depth + 1));
                }
                // Explicitly tagged secret records protect the entire record, including unknown layouts.
                if (Boolean.TRUE.equals(((Map<?,?>)input).get("secret")) || "secret".equals(((Map<?,?>)input).get("classification"))) return REDACTED;
                return safe;
            }
            List<Object> safe = new ArrayList<Object>();
            if (input instanceof Iterable<?>) { for (Object item : (Iterable<?>)input) safe.add(value(item, active, depth + 1)); return safe; }
            if (input.getClass().isArray()) { for (int i = 0; i < Array.getLength(input); i++) safe.add(value(Array.get(input, i), active, depth + 1)); return safe; }
            return REDACTED;
        } finally { active.remove(input); }
    }
}
