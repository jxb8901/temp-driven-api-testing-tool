/*
 * Author: Jeffrey + ChatGPT
 */

package att.template;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal placeholder renderer for the framework's ${name} template syntax.
 */
public final class TemplateRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private TemplateRenderer() {
    }

    public static String render(String template, Map<String, ?> context) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = context.get(key);
            // Missing values render as empty strings to keep XML generation deterministic for draft cases.
            matcher.appendReplacement(output, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
