package att.config;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** The selected Package owns standalone Tools and grouped Tool manifests. */
public final class PackageToolRegistry {
    private static final int MAX_TOOLS = 512;
    private static final int MAX_GROUPS = 128;
    private static final long MAX_DESCRIPTOR_BYTES = 256 * 1024L;
    private static final String GROUP_FILENAME = "tool-group.yaml";

    private PackageToolRegistry() {}

    public static Map<String, ToolConfig> load(Path packageRoot) throws IOException {
        Path root = packageRoot.toAbsolutePath().normalize();
        Path toolsRoot = root.resolve("tools");
        if (!Files.exists(toolsRoot, LinkOption.NOFOLLOW_LINKS)) return Collections.emptyMap();
        if (Files.isSymbolicLink(toolsRoot) || !Files.isDirectory(toolsRoot, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Package Tool root tools/ is not a safe directory");

        List<Path> standalone = new ArrayList<Path>();
        List<Path> groupDirs = new ArrayList<Path>();
        try (Stream<Path> stream = Files.list(toolsRoot)) {
            for (Path candidate : (Iterable<Path>) stream::iterator) {
                if (Files.isSymbolicLink(candidate))
                    throw new IOException("Symlink is not permitted in Package Tool discovery: " + candidate.getFileName());
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        && candidate.getFileName().toString().endsWith(".yaml")) standalone.add(candidate);
                if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                        && Files.exists(candidate.resolve(GROUP_FILENAME), LinkOption.NOFOLLOW_LINKS)) groupDirs.add(candidate);
            }
        }
        Comparator<Path> byName = Comparator.comparing(p -> p.getFileName().toString());
        standalone.sort(byName);
        groupDirs.sort(byName);
        if (standalone.size() > MAX_TOOLS || groupDirs.size() > MAX_GROUPS)
            throw new IOException("Package Tool discovery exceeds configured limits");
        Path toolSchema = SchemaFiles.resolve(root, "att-tool-v1.schema.json");
        Path groupSchema = SchemaFiles.resolve(root, "att-tool-group-v3.0.schema.json");
        Map<String, ToolConfig> result = new LinkedHashMap<String, ToolConfig>();
        Set<String> groups = new HashSet<String>();
        int count = 0;
        for (Path candidate : standalone) {
            if (++count > MAX_TOOLS) throw new IOException("Package Tool count exceeds " + MAX_TOOLS);
            loadTool(root, toolsRoot, toolSchema, candidate, "", Collections.<String>emptyList(), "", "", result);
        }
        for (Path folder : groupDirs) {
            Path manifest = folder.resolve(GROUP_FILENAME);
            try {
                Map<?, ?> group = readYaml(root, toolsRoot, manifest, groupSchema);
                String groupId = String.valueOf(group.get("id"));
                if (!groupId.matches("[A-Za-z_][A-Za-z0-9_-]*"))
                    throw new IllegalArgumentException("Invalid Tool Group id: " + groupId);
                if (!groups.add(groupId))
                    throw new IllegalArgumentException("Duplicate Tool Group id: " + groupId);
                List<String> script = command(group.get("script"));
                for (String token : script) {
                    if (token.trim().isEmpty() || token.contains("$" + "{") || token.contains("#" + "{"))
                        throw new IllegalArgumentException("Group dispatcher argv must be static: " + groupId);
                }
                String sshHelper = "";
                String sshStrategy = "";
                if (group.get("ssh") instanceof Map) {
                    Map<?, ?> ssh = (Map<?, ?>) group.get("ssh");
                    sshHelper = String.valueOf(ssh.get("helper")).toLowerCase(java.util.Locale.ROOT);
                    if (ssh.get("selection") instanceof Map) {
                        Object strategy = ((Map<?, ?>) ssh.get("selection")).get("strategy");
                        if (strategy != null) sshStrategy = String.valueOf(strategy);
                    }
                }
                Set<String> files = new HashSet<String>();
                for (Object member : (List<?>) group.get("tools")) {
                    String filename = String.valueOf(member);
                    if (!filename.matches("[A-Za-z_][A-Za-z0-9_.-]*[.]yaml")
                            || GROUP_FILENAME.equals(filename) || filename.contains(".."))
                        throw new IllegalArgumentException("Unsafe group member filename: " + filename);
                    if (!files.add(filename))
                        throw new IllegalArgumentException("Duplicate group member filename: " + filename);
                    if (++count > MAX_TOOLS)
                        throw new IOException("Package Tool count exceeds " + MAX_TOOLS);
                    loadTool(root, toolsRoot, toolSchema, folder.resolve(filename),
                            groupId, script, sshHelper, sshStrategy, result);
                }
            } catch (att.validation.DiagnosticException diagnostic) {
                throw diagnostic;
            } catch (Exception failure) {
                throw invalid(manifest, "Invalid Package Tool Group", failure);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> command(Object raw) throws IOException {
        if (raw == null) return Collections.emptyList();
        if (raw instanceof String) return att.exec.CommandRunner.parseCommand((String) raw);
        if (raw instanceof List) {
            List<String> tokens = new ArrayList<String>();
            for (Object item : (List<?>) raw) {
                if (!(item instanceof String)) throw new IOException("Group script argv must contain strings");
                tokens.add((String) item);
            }
            return tokens;
        }
        throw new IOException("Group script must be a command String or argv list");
    }

    private static Map<?, ?> readYaml(Path root, Path toolsRoot, Path candidate, Path schema) throws Exception {
        String relative = root.relativize(candidate.toAbsolutePath().normalize()).toString().replace('\\', '/');
        PackageConfig.safeRelative(root, relative);
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || Files.size(candidate) > MAX_DESCRIPTOR_BYTES)
            throw new IOException("Tool YAML is missing, unsafe or larger than 256 KiB: " + relative);
        Path file = candidate.toRealPath();
        if (!file.startsWith(toolsRoot.toRealPath()))
            throw new IOException("Package Tool YAML escapes tools/: " + relative);
        Object source = YamlSupport.load(file);
        JsonSchemaVerifier.verify(schema, source);
        if (!(source instanceof Map))
            throw new IllegalArgumentException("Expected one Tool YAML mapping: " + relative);
        return (Map<?, ?>) source;
    }

    private static void loadTool(Path root, Path toolsRoot, Path schema, Path candidate,
                                 String group, List<String> script, String groupSshHelper, String groupSshStrategy,
                                 Map<String, ToolConfig> result) throws IOException {
        try {
            Map<?, ?> raw = readYaml(root, toolsRoot, candidate, schema);
            String id = String.valueOf(raw.get("id"));
            if (!group.isEmpty() && !id.matches("[A-Za-z_][A-Za-z0-9_-]*"))
                throw new IllegalArgumentException("Grouped Tool id must be an unqualified local name: " + id);
            String key = group.isEmpty() ? id : group + "." + id;
            if (key.startsWith("db.") || key.startsWith("mq.") || key.startsWith("http.")
                    || key.startsWith("ssh.") || key.equals("db") || key.equals("mq")
                    || key.equals("http") || key.equals("ssh"))
                throw new IllegalArgumentException("Tool ID conflicts with a global Helper callable: " + key);

            int dot = key.lastIndexOf('.');
            String actualGroup = dot < 0 ? "" : key.substring(0, dot);
            String local = dot < 0 ? key : key.substring(dot + 1);
            if (local.isEmpty() || (dot >= 0 && actualGroup.isEmpty()))
                throw new IllegalArgumentException("Invalid Tool ID " + key);

            String sshHelper = groupSshHelper;
            String sshStrategy = groupSshStrategy;
            if (raw.get("ssh") instanceof Map) {
                if (!groupSshHelper.isEmpty())
                    throw new IllegalArgumentException("Tool SSH binding cannot override its Group SSH binding");
                Map<?, ?> ssh = (Map<?, ?>) raw.get("ssh");
                sshHelper = String.valueOf(ssh.get("helper")).toLowerCase(java.util.Locale.ROOT);
                Object selection = ssh.get("selection");
                if (selection instanceof Map) {
                    Object strategy = ((Map<?, ?>) selection).get("strategy");
                    if (strategy != null) sshStrategy = String.valueOf(strategy);
                }
            }
            Map<String, Object> fields = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : raw.entrySet())
                if (!"schemaVersion".equals(entry.getKey()) && !"id".equals(entry.getKey())
                        && !"ssh".equals(entry.getKey()))
                    fields.put(String.valueOf(entry.getKey()), entry.getValue());
            if (!fields.containsKey("name")) fields.put("name", id);
            if (!fields.containsKey("description")) fields.put("description", "Package Tool " + key);
            if (fields.get("arguments") instanceof Map) {
                Map<String, Object> arguments = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) fields.get("arguments")).entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof Map && !((Map<?, ?>) value).containsKey("description")) {
                        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
                        for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet())
                            metadata.put(String.valueOf(item.getKey()), item.getValue());
                        metadata.put("description", String.valueOf(metadata.get("name")));
                        value = metadata;
                    }
                    arguments.put(String.valueOf(entry.getKey()), value);
                }
                fields.put("arguments", arguments);
            }
            FrameworkConfigLoader.addTools(Collections.singletonMap(local, fields), result, actualGroup,
                    script, null, sshHelper, sshStrategy, "tools", candidate.toRealPath(),
                    true, false, false, true);
        } catch (att.validation.DiagnosticException diagnostic) {
            throw diagnostic;
        } catch (Exception failure) {
            throw invalid(candidate, "Invalid Package Tool", failure);
        }
    }

    private static att.validation.DiagnosticException invalid(Path source, String message, Exception reason) {
        att.validation.DiagnosticException error = att.validation.DiagnosticException.wrap(
                att.validation.DiagnosticCodes.TOOL_INVALID, message + " '" + source + "'",
                reason, source.toString(), "tools",
                "Correct the Package-local att-tool/v1 or att-tool-group/v3.0 descriptor.");
        return YamlSupport.locate(error, source, error.field());
    }

    /** Content-addressed closure including group membership/defaults and member Tool definitions. */
    public static String revision(Map<String, ToolConfig> tools) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<String> keys = new ArrayList<String>(tools.keySet());
        Collections.sort(keys);
        Set<Path> hashedGroups = new HashSet<Path>();
        for (String key : keys) {
            ToolConfig tool = tools.get(key);
            digest.update(key.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            Path source = tool.sourceFile();
            if (source != null && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                digest.update(Files.readAllBytes(source));
                if (tool.grouped()) {
                    Path manifest = source.getParent().resolve(GROUP_FILENAME);
                    if (hashedGroups.add(manifest) && Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS))
                        digest.update(Files.readAllBytes(manifest));
                }
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
