/* Author: Jeffrey + ChatGPT */
package att.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Creates the strict safe YAML parser used by V2 configuration and cells. */
public final class YamlSupport {
    private YamlSupport() {}
    public static Yaml parser() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setCodePointLimit(3_000_000);
        return new Yaml(new SafeConstructor(options));
    }
}
