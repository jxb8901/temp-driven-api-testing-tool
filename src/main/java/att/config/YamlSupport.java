/* Author: Jeffrey + ChatGPT */
package att.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Creates the strict safe YAML parser used by V2 configuration and cells. */
public final class YamlSupport {
    private YamlSupport() {}
    // Reuse a single configured Yaml instance to avoid repeated construction overhead
    private static final Yaml PARSER;
    static {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setCodePointLimit(3_000_000);
        PARSER = new Yaml(new SafeConstructor(options));
    }
    public static Yaml parser() { return PARSER; }
}
