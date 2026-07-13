/* Author: Jeffrey + ChatGPT */
package att.config;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Path;

/** V2 external tool execution and argument contract. */
public final class ToolConfig {
    private final String key;
    private final String name;
    private final String description;
    private final List<String> commandArgv;
    private final List<String> groupScriptArgv;
    private final String groupId;
    private final String localKey;
    private final SshConfig ssh;
    private final Path sourceFile;
    private final String output;
    private final Map<String, ToolArgumentConfig> arguments;

    public ToolConfig(String key, String name, String description, String command, String output,
                      Map<String, ToolArgumentConfig> arguments) {
        this(key, key, "", name, description, parse(command), Collections.<String>emptyList(), output, arguments, null);
    }

    public ToolConfig(String key, String localKey, String groupId, String name, String description,
                      List<String> commandArgv, List<String> groupScriptArgv, String output,
                      Map<String, ToolArgumentConfig> arguments, SshConfig ssh) {
        this(key, localKey, groupId, name, description, commandArgv, groupScriptArgv, output, arguments, ssh, null);
    }

    public ToolConfig(String key, String localKey, String groupId, String name, String description,
                      List<String> commandArgv, List<String> groupScriptArgv, String output,
                      Map<String, ToolArgumentConfig> arguments, SshConfig ssh, Path sourceFile) {
        this.key = key;
        this.localKey = localKey;
        this.groupId = groupId == null ? "" : groupId;
        this.name = name;
        this.description = description;
        this.commandArgv = immutable(commandArgv);
        this.groupScriptArgv = immutable(groupScriptArgv);
        this.output = output;
        this.arguments = arguments == null ? Collections.<String, ToolArgumentConfig>emptyMap()
                : new LinkedHashMap<String, ToolArgumentConfig>(arguments);
        this.ssh = ssh;
        this.sourceFile = sourceFile;
    }

    public String key() { return key; }
    public String name() { return name; }
    public String description() { return description; }
    public String command() { return printable(commandArgv); }
    public List<String> commandArgv() { return commandArgv; }
    public List<String> groupScriptArgv() { return groupScriptArgv; }
    public String groupId() { return groupId; }
    public String localKey() { return localKey; }
    public boolean grouped() { return !groupId.isEmpty(); }
    public SshConfig ssh() { return ssh; }
    public Path sourceFile() { return sourceFile; }
    public String output() { return output; }
    public Map<String, ToolArgumentConfig> arguments() { return Collections.unmodifiableMap(arguments); }

    private static List<String> parse(String command) {
        try { return att.exec.CommandRunner.parseCommand(command == null ? "" : command); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("Invalid tool command: " + e.getMessage(), e); }
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(values == null ? Collections.<String>emptyList() : new ArrayList<String>(values));
    }

    private static String printable(List<String> argv) {
        StringBuilder result = new StringBuilder();
        for (String value : argv) { if (result.length() > 0) result.append(' '); result.append(value); }
        return result.toString();
    }
}
