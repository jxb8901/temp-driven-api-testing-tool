package att.config;

import att.Version;
import att.validation.JsonSchemaVerifier;
import att.validation.SchemaFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** The selected Package is the only owner and resolution scope for Tool definitions. */
public final class PackageToolRegistry {
    private static final int MAX_TOOLS = 512;
    private static final long MAX_DESCRIPTOR_BYTES = 256 * 1024L;

    private PackageToolRegistry() {}

    public static Map<String, ToolConfig> load(Path packageRoot) throws IOException {
        Path root = packageRoot.toAbsolutePath().normalize();
        Path toolsRoot = root.resolve("tools");
        if (!Files.exists(toolsRoot, LinkOption.NOFOLLOW_LINKS))
            return Collections.emptyMap();
        if (Files.isSymbolicLink(toolsRoot) || !Files.isDirectory(toolsRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Package Tool root tools/ is not a safe directory");

        List<Path> descriptors = new ArrayList<Path>();
        try (Stream<Path> stream = Files.list(toolsRoot)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".yaml")).forEach(descriptors::add);
        }
        descriptors.sort(Comparator.comparing(p -> p.getFileName().toString()));
        if (descriptors.size() > MAX_TOOLS)
            throw new IOException("Package tools/ exceeds the descriptor limit of " + MAX_TOOLS);
        Map<String, ToolConfig> result = new LinkedHashMap<String, ToolConfig>();
        Path schema = SchemaFiles.resolve(root, "att-tool-v1.schema.json");
        for (Path candidate : descriptors) {
            String logical = "tools/" + candidate.getFileName().toString();
            try {
                PackageConfig.safeRelative(root, logical);
                if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(candidate) > MAX_DESCRIPTOR_BYTES)
                    throw new IOException("Descriptor is missing, unsafe or larger than 256 KiB");
                Path file = candidate.toRealPath();
                if (!file.startsWith(toolsRoot.toRealPath())) throw new IOException("Tool descriptor escapes tools/");
                Object source = YamlSupport.load(file);
                JsonSchemaVerifier.verify(schema, source);
                if (!(source instanceof Map)) throw new IllegalArgumentException("Expected one Tool YAML mapping");
                Map<?, ?> raw = (Map<?, ?>) source;
                String id = String.valueOf(raw.get("id"));
                // A qualified legacy name may be retained as the Package-local ID;
                // it is NOT a shared group and is still resolved only inside this Package.
                if (id.startsWith("db.") || id.startsWith("mq.") || id.startsWith("http.")
                        || id.startsWith("ssh.") || id.equals("db") || id.equals("mq")
                        || id.equals("http") || id.equals("ssh"))
                    throw new IllegalArgumentException("Tool ID conflicts with a global Helper callable: " + id);
                int dot = id.lastIndexOf('.');
                String group = dot < 0 ? "" : id.substring(0, dot);
                String local = dot < 0 ? id : id.substring(dot + 1);
                if (local.isEmpty() || (dot >= 0 && group.isEmpty()))
                    throw new IllegalArgumentException("Invalid Tool ID " + id);
                Map<String, Object> fields = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : raw.entrySet())
                    if (!"schemaVersion".equals(entry.getKey()) && !"id".equals(entry.getKey()))
                        fields.put(String.valueOf(entry.getKey()), entry.getValue());
                if (!fields.containsKey("name")) fields.put("name", id);
                if (!fields.containsKey("description")) fields.put("description", "Package Tool " + id);
                if (fields.get("arguments") instanceof Map) {
                    Map<String, Object> arguments = new LinkedHashMap<String, Object>();
                    for (Map.Entry<?, ?> argument : ((Map<?, ?>) fields.get("arguments")).entrySet()) {
                        Object value = argument.getValue();
                        if (value instanceof Map && !((Map<?, ?>) value).containsKey("description")) {
                            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
                            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet())
                                metadata.put(String.valueOf(item.getKey()), item.getValue());
                            metadata.put("description", String.valueOf(metadata.get("name")));
                            value = metadata;
                        }
                        arguments.put(String.valueOf(argument.getKey()), value);
                    }
                    fields.put("arguments", arguments);
                }
                Map<String, Object> one = Collections.singletonMap(local, fields);
                FrameworkConfigLoader.addTools(one, result, group, Collections.<String>emptyList(), null,
                        "", "", "tools", file, true, false, false, true);
            } catch (Exception failure) {
                throw new IOException("Package Tool " + logical + " is invalid: " + failure.getMessage(), failure);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** Content-addressed closure for accepted execution/configuration revision identity. */
    public static String revision(Map<String, ToolConfig> tools) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> keys = new ArrayList<String>(tools.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            ToolConfig tool = tools.get(key);
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            Path source = tool.sourceFile();
            if (source != null && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                digest.update(Files.readAllBytes(source));
            } else {
                digest.update(String.valueOf(tool.call()).getBytes(StandardCharsets.UTF_8));
                digest.update(String.valueOf(tool.commandArgv()).getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) 0);
        }
        StringBuilder value = new StringBuilder();
        for (byte b : digest.digest()) value.append(String.format("%02x", b & 0xff));
        return value.toString();
    }
}
