package att.server;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.math.BigDecimal;

/** Safe YAML export parsing without binary-floating-point rounding of business values. */
final class ArtifactYamlConstructor extends SafeConstructor {
    ArtifactYamlConstructor(LoaderOptions options) {
        super(options);
        Construct integers = yamlConstructors.get(Tag.INT);
        yamlConstructors.put(Tag.INT, new AbstractConstruct() {
            @Override public Object construct(Node node) {
                number(node);
                return integers.construct(node);
            }
        });
        yamlConstructors.put(Tag.FLOAT, new AbstractConstruct() {
            @Override public Object construct(Node node) {
                String value = number(node).replace("_", "");
                if (!value.contains(":")) return new BigDecimal(value);
                boolean negative = value.startsWith("-");
                if (negative || value.startsWith("+")) value = value.substring(1);
                BigDecimal result = BigDecimal.ZERO;
                for (String part : value.split(":")) result = result.multiply(BigDecimal.valueOf(60)).add(new BigDecimal(part));
                return negative ? result.negate() : result;
            }
        });
    }

    private String number(Node node) {
        String value = constructScalar((ScalarNode) node);
        if (value.length() > 1000) throw new IllegalArgumentException("Artifact number exceeds limits");
        return value;
    }
}
