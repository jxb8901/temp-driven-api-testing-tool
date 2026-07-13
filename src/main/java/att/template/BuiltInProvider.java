/* Author: Jeffrey + ChatGPT */
package att.template;

import java.util.Map;
import java.util.Set;

/** Internal extension boundary for ATT-owned template built-ins. */
public interface BuiltInProvider {
    Set<String> names();
    Object invoke(String name, Map<String, Object> arguments);
}
