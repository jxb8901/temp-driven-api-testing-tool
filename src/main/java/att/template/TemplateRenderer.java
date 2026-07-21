/*
 * Author: Jeffrey + ChatGPT
 */

package att.template;

import java.util.Map;

/**
 * Minimal placeholder renderer for the framework's ${name} template syntax.
 */
public final class TemplateRenderer {
    private TemplateRenderer() {
    }

    public static String render(String template, Map<String, ?> context) {
        try {
            return new UnifiedTemplateEngine(null).renderScopedLenient(template, context);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to render template expression: " + e.getMessage(), e);
        }
    }
}
