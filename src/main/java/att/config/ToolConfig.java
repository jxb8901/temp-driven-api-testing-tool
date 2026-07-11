/* Author: Jeffrey + ChatGPT */
package att.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** V2 external tool execution and argument contract. */
public final class ToolConfig {
    private final String key;
    private final String name;
    private final String description;
    private final String command;
    private final String output;
    private final Map<String, ToolArgumentConfig> arguments;

    public ToolConfig(String key, String name, String description, String command, String output,
                      Map<String, ToolArgumentConfig> arguments) {
        this.key = key;
        this.name = name;
        this.description = description;
        this.command = command;
        this.output = output;
        this.arguments = arguments == null ? Collections.<String, ToolArgumentConfig>emptyMap()
                : new LinkedHashMap<String, ToolArgumentConfig>(arguments);
    }

    public String key() { return key; }
    public String name() { return name; }
    public String description() { return description; }
    public String command() { return command; }
    public String output() { return output; }
    public Map<String, ToolArgumentConfig> arguments() { return Collections.unmodifiableMap(arguments); }
}
