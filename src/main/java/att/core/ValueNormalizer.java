/* Author: Jeffrey + ChatGPT */
package att.core;

import java.util.Locale;

/** Shared V2 blank-marker normalization. */
public final class ValueNormalizer {
    private ValueNormalizer() {}

    public static String normalize(String value) {
        if (value == null) return "";
        String text = value.trim();
        String upper = text.toUpperCase(Locale.ROOT);
        if (text.isEmpty() || "N/A".equals(upper) || "NA".equals(upper) || "NULL".equals(upper) || "NONE".equals(upper)) return "";
        return text;
    }
}
