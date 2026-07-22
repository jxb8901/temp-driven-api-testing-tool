/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.validation.JsonSupport;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;

/** Encodes typed Java Tool/DB output without changing the object exposed to expressions. */
public final class ObjectOutputCodec {
    public String encode(Object value, String format) {
        if ("json".equalsIgnoreCase(format)) return JsonSupport.write(value) + "\n";
        if ("yaml".equalsIgnoreCase(format)) {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            return new Yaml(options).dump(value);
        }
        if ("xml".equalsIgnoreCase(format)) {
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<result>\n");
            append(xml, value, "  ");
            return xml.append("</result>\n").toString();
        }
        throw new IllegalArgumentException("Structured Action output must be json, yaml, or xml: " + format);
    }

    private void append(StringBuilder xml, Object value, String indent) {
        if (value == null) { xml.append(indent).append("<value type=\"null\"/>\n"); return; }
        if (value instanceof Map) {
            xml.append(indent).append("<value type=\"map\">\n");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                xml.append(indent).append("  <entry name=\"").append(escape(String.valueOf(entry.getKey()))).append("\">\n");
                append(xml, entry.getValue(), indent + "    ");
                xml.append(indent).append("  </entry>\n");
            }
            xml.append(indent).append("</value>\n");
            return;
        }
        if (value instanceof Iterable) {
            xml.append(indent).append("<value type=\"list\">\n");
            for (Object item : (Iterable<?>) value) { xml.append(indent).append("  <item>\n"); append(xml, item, indent + "    "); xml.append(indent).append("  </item>\n"); }
            xml.append(indent).append("</value>\n");
            return;
        }
        String type = value instanceof Boolean ? "boolean" : value instanceof Number ? "number" : "string";
        xml.append(indent).append("<value type=\"").append(type).append("\">").append(escape(String.valueOf(value))).append("</value>\n");
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
