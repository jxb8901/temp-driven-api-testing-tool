package att;

import att.config.YamlSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicitly converts historical inline Tool fixtures at authoring time.
 * Never called by Engine, Server, Worker, CLI or any production source.
 */
public final class TestPackageTools {
    private TestPackageTools() {}

    @SuppressWarnings("unchecked")
    public static void migrate(Path config, Path packageRoot) throws Exception {
        if (!Files.exists(config)) return;
        Object document = YamlSupport.load(config);
        if (!(document instanceof Map)) return;
        Map<String,Object> global = new LinkedHashMap<String,Object>((Map<String,Object>) document);
        Object inline = global.remove("tools"), groups = global.remove("toolGroups");
        if (inline == null && groups == null) return;
        Map<String,Map<String,Object>> converted = new LinkedHashMap<String,Map<String,Object>>();
        copyTools(converted, "", inline, null, null);
        Path dir = config.toAbsolutePath().getParent();
        Path runtimeRoot = dir.getFileName().toString().equals("config") ? dir.getParent() : dir;
        if (groups instanceof Iterable) for (Object imported : (Iterable<?>) groups) {
            Path groupFile = runtimeRoot.resolve(String.valueOf(imported)).normalize();
            if (!groupFile.startsWith(runtimeRoot) || !Files.isRegularFile(groupFile))
                throw new IllegalArgumentException("Unknown fixture Tool Group: " + imported);
            Map<String,Object> group = (Map<String,Object>) YamlSupport.load(groupFile);
            copyTools(converted, String.valueOf(group.get("id")) + ".", group.get("tools"), group.get("script"), group.get("ssh"));
        }
        Path toolRoot = packageRoot.resolve("tools");
        Files.createDirectories(toolRoot);
        for (Map.Entry<String,Map<String,Object>> item : converted.entrySet()) {
            Path file = toolRoot.resolve(item.getKey() + ".yaml").normalize();
            if (!file.startsWith(toolRoot))
                throw new IllegalArgumentException("Unsafe fixture Tool: " + item.getKey());
            Files.write(file, YamlSupport.parser().dump(item.getValue()).getBytes(StandardCharsets.UTF_8));
        }
        global.put("schemaVersion", "att-config/v2.13");
        Files.write(config, YamlSupport.parser().dump(global).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static void copyTools(Map<String,Map<String,Object>> converted, String prefix,
                                  Object source, Object groupScript, Object groupSsh) {
        if (!(source instanceof Map)) return;
        for (Map.Entry<?,?> entry : ((Map<?,?>) source).entrySet()) {
            if (String.valueOf(entry.getKey()).startsWith("x-")) continue;
            String id = prefix + entry.getKey();
            if (!(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Invalid Tool " + id);
            Map<String,Object> raw = new LinkedHashMap<String,Object>((Map<String,Object>) entry.getValue());
            Map<String,Object> tool = new LinkedHashMap<String,Object>();
            tool.put("schemaVersion", "att-tool/v1");
            tool.put("id", id);
            tool.put("name", raw.getOrDefault("name", id));
            tool.put("description", raw.getOrDefault("description", "Fixture " + id));
            if (groupSsh instanceof Map && !((Map<?,?>) groupSsh).containsKey("helper"))
                throw new IllegalArgumentException("Legacy Tool Group direct SSH credentials require a global SSH Helper.");
            if (groupSsh != null) tool.put("ssh", groupSsh);
            for (Map.Entry<String,Object> field : raw.entrySet()) {
                if ("name".equals(field.getKey()) || "description".equals(field.getKey())) continue;
                if ("output".equals(field.getKey())) {
                    String output = String.valueOf(field.getValue());
                    tool.put("stdoutFormat", "txt".equals(output) ? "text" : output);
                } else if ("result".equals(field.getKey()) && field.getValue() instanceof Map) {
                    tool.put("stdoutFormat", ((Map<?,?>) field.getValue()).get("format"));
                } else if ("command".equals(field.getKey()) && groupScript != null) {
                    List<String> command = new ArrayList<String>();
                    appendArgv(command, groupScript);
                    appendArgv(command, field.getValue());
                    tool.put("command", command);
                } else tool.put(field.getKey(), field.getValue());
            }
            if (tool.containsKey("command") && !tool.containsKey("stdoutFormat"))
                tool.put("stdoutFormat", "text");
            if (converted.put(id, tool) != null)
                throw new IllegalArgumentException("Duplicate fixture Tool " + id);
        }
    }

    private static void appendArgv(List<String> out, Object value) {
        if (value instanceof Iterable) {
            for (Object part : (Iterable<?>) value) out.add(String.valueOf(part));
        } else if (value != null) {
            out.add(String.valueOf(value));
        }
    }
}
