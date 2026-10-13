package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Separates the shared authorization identity from the persisted job attribution label. */
record RequestIdentity(String authorizationPrincipal, String persistedPrincipal) {
    static final String ANONYMOUS = "anonymous";
    private static final int MAX_LABEL_CODE_POINTS = 120;
    private static final Pattern LABEL = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} ._'’\\-]*");

    static RequestIdentity resolve(boolean authenticationRequired, String servletPrincipal, JsonNode request) {
        if (authenticationRequired) return new RequestIdentity(servletPrincipal, servletPrincipal);
        String persisted = ANONYMOUS;
        if (request != null && request.has("principal")) {
            JsonNode value = request.get("principal");
            if (!value.isTextual()) throw new IllegalArgumentException("principal must be a text label");
            persisted = formatAnonymousPrincipal(value.asText());
        }
        return new RequestIdentity(ANONYMOUS, persisted);
    }

    static String formatAnonymousPrincipal(String value) {
        if (value == null) throw invalidLabel();
        String label = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        if (label.isEmpty() || label.codePointCount(0, label.length()) > MAX_LABEL_CODE_POINTS || !LABEL.matcher(label).matches())
            throw invalidLabel();
        return label + "(anonym)";
    }

    static JsonNode withoutSubmittedPrincipal(JsonNode request) {
        if (request == null || !request.isObject() || !request.has("principal")) return request;
        ObjectNode safe = request.deepCopy();
        safe.remove("principal");
        return safe;
    }

    private static IllegalArgumentException invalidLabel() {
        return new IllegalArgumentException("principal must be 1-120 letters or numbers with optional spaces, dots, underscores, apostrophes, or hyphens; the (anonym) suffix is reserved");
    }
}
