/* Author: Jeffrey + ChatGPT */
package att.validation;

/** Single strict JSON parser/writer used by every ATT JSON producer. */
public final class JsonSupport {
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = create();
    private JsonSupport() {}
    private static com.fasterxml.jackson.databind.ObjectMapper create() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        return mapper;
    }
    public static com.fasterxml.jackson.databind.ObjectMapper mapper() { return MAPPER; }
    public static String write(Object value) { try { return MAPPER.writeValueAsString(value); } catch (java.io.IOException e) { throw new IllegalArgumentException("Unable to write JSON: " + e.getMessage(), e); } }
}
