package att.server;

import att.Version;
import att.config.YamlSupport;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit, conflict-safe migration from the legacy dataDir catalog into the Runtime Config source. */
public final class SutMetricsCatalogMigration {
    private static final int MAX_CONFIG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CATALOG_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SOURCES = 64;
    private static final int MAX_PROFILES = 256;

    record Result(String state, String configRevision, Path outputPath, Path configBackup, Path catalogBackup) { }

    private SutMetricsCatalogMigration() { }

    static Result migrate(Path configPath, Path catalogPath, Path outputPath, boolean apply) throws Exception {
        Path config = configPath.toAbsolutePath().normalize();
        Path catalog = catalogPath.toAbsolutePath().normalize();
        if (apply && outputPath != null) throw new IllegalArgumentException("--apply cannot be combined with --output.");
        byte[] configBytes = boundedRead(config, MAX_CONFIG_BYTES, "Runtime Config");
        byte[] catalogBytes = boundedRead(catalog, MAX_CATALOG_BYTES, "legacy SUT Metrics catalog");
        Map<String, Object> configRoot = object(YamlSupport.parse(new String(configBytes, StandardCharsets.UTF_8)), "Runtime Config");
        String currentSchema = String.valueOf(configRoot.get("schemaVersion"));
        if (!Set.of(Version.CONFIG_SCHEMA, Version.PREVIOUS_CONFIG_SCHEMA).contains(currentSchema))
            throw new IllegalArgumentException("Migrate the Runtime Config to " + Version.PREVIOUS_CONFIG_SCHEMA
                    + " or " + Version.CONFIG_SCHEMA + " before moving the legacy SUT Metrics catalog.");

        Map<String, Object> catalogRoot = ServerRuntime.JSON.readValue(catalogBytes, Map.class);
        Map<String, Object> migrated = fromLegacyCatalog(catalogRoot);
        Object existing = configRoot.get("sutMetrics");
        if (existing != null) {
            Map<String, Object> existingDefinitions = normalizeRuntimeCatalog(object(existing, "config.sutMetrics"));
            if (!existingDefinitions.equals(migrated))
                throw new IllegalStateException("Runtime Config already contains different SUT Metrics definitions; no merge or overwrite was performed.");
            validateCandidate(config, runtimeConfigText(configRoot));
            return new Result("already-migrated", digest(configBytes), config, null, null);
        }

        Map<String, Object> candidateRoot = new LinkedHashMap<>(configRoot);
        candidateRoot.put("schemaVersion", Version.CONFIG_SCHEMA);
        candidateRoot.put("sutMetrics", migrated);
        byte[] candidateBytes = dumpYaml(candidateRoot);
        String candidateRevision = digest(candidateBytes);
        validateCandidate(config, new String(candidateBytes, StandardCharsets.UTF_8));

        if (!apply) {
            if (outputPath == null) throw new IllegalArgumentException("Supply --output <candidate.yaml> or explicitly choose --apply.");
            Path output = outputPath.toAbsolutePath().normalize();
            if (output.equals(config) || output.equals(catalog))
                throw new IllegalArgumentException("A dry-run candidate must use a new path separate from both inputs.");
            writeNewOrVerify(output, candidateBytes);
            return new Result("candidate-written", candidateRevision, output, null, null);
        }

        Path configBackup = config.resolveSibling(config.getFileName() + ".pre-sut-metrics-" + digest(configBytes) + ".yaml");
        Path catalogBackup = catalog.resolveSibling(catalog.getFileName() + ".pre-migration-" + digest(catalogBytes) + ".json");
        backupOrVerify(config, configBackup, configBytes);
        backupOrVerify(catalog, catalogBackup, catalogBytes);
        if (!MessageDigest.isEqual(boundedRead(config, MAX_CONFIG_BYTES, "Runtime Config"), configBytes)
                || !MessageDigest.isEqual(boundedRead(catalog, MAX_CATALOG_BYTES, "legacy SUT Metrics catalog"), catalogBytes))
            throw new IllegalStateException("A migration source changed during preflight; no Runtime Config update was published.");
        atomicReplace(config, candidateBytes);
        return new Result("applied", candidateRevision, config, configBackup, catalogBackup);
    }

    private static void validateCandidate(Path originalConfig, String source) throws Exception {
        Path root = ServerConfig.runtimeConfigRoot(originalConfig);
        new att.config.FrameworkConfigLoader().loadSutMetricsConfiguration(originalConfig, root, source);
        Path temporary = Files.createTempDirectory("att-sut-metrics-migration-");
        Path candidate = temporary.resolve("config.yaml");
        Path data = temporary.resolve("data");
        Files.createDirectories(data);
        Files.write(candidate, source.getBytes(StandardCharsets.UTF_8));
        try (SutMetricsService ignored = new SutMetricsService(data, candidate, root,
                name -> System.getenv("ATT_SECRET_" + name), path -> { })) {
            // Construction validates all source, Metric Source, Group and Profile references before any write.
        } finally {
            deleteTree(temporary);
        }
    }

    private static String runtimeConfigText(Map<String, Object> configRoot) {
        return new String(dumpYaml(configRoot), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> fromLegacyCatalog(Map<String, Object> legacy) {
        if (!"att-sut-metrics-catalog/v1".equals(legacy.get("schemaVersion")))
            throw new IllegalArgumentException("The legacy SUT Metrics catalog must use att-sut-metrics-catalog/v1.");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataSources", normalizeRows(legacy.get("sources"), "sources", MAX_SOURCES, "Data Source", RowKind.DATA_SOURCE));
        result.put("metricSources", normalizeRows(legacy.getOrDefault("metricSources", List.of()), "metricSources", MAX_SOURCES, "Metric Source", RowKind.METRIC_SOURCE));
        result.put("groups", normalizeRows(legacy.getOrDefault("groups", List.of()), "groups", MAX_PROFILES, "Metric Group", RowKind.GROUP));
        result.put("profiles", normalizeRows(legacy.get("profiles"), "profiles", MAX_PROFILES, "Metrics Profile", RowKind.PROFILE));
        return result;
    }

    private static Map<String, Object> normalizeRuntimeCatalog(Map<String, Object> configured) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataSources", normalizeRows(configured.get("dataSources"), "dataSources", MAX_SOURCES, "Data Source", RowKind.DATA_SOURCE));
        result.put("metricSources", normalizeRows(configured.get("metricSources"), "metricSources", MAX_SOURCES, "Metric Source", RowKind.METRIC_SOURCE));
        result.put("groups", normalizeRows(configured.get("groups"), "groups", MAX_PROFILES, "Metric Group", RowKind.GROUP));
        result.put("profiles", normalizeRows(configured.get("profiles"), "profiles", MAX_PROFILES, "Metrics Profile", RowKind.PROFILE));
        return result;
    }

    private enum RowKind { DATA_SOURCE, METRIC_SOURCE, GROUP, PROFILE }

    private static List<Map<String, Object>> normalizeRows(Object raw, String field, int limit, String kind, RowKind rowKind) {
        if (!(raw instanceof List<?> rows) || rows.size() > limit)
            throw new IllegalArgumentException("SUT Metrics " + field + " must be a list with at most " + limit + " entries.");
        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : rows) {
            Map<String, Object> row = deepMap(object(item, "SUT Metrics " + kind));
            String id = row.get("id") instanceof String text ? text : null;
            if (id == null || id.isBlank() || !ids.add(id))
                throw new IllegalArgumentException("Legacy SUT Metrics " + field + " contains a missing or duplicate id.");
            row.remove("createdAt");
            if (rowKind == RowKind.METRIC_SOURCE) {
                row.remove("revision");
                Map<String, Object> connection = object(row.get("connection"), "Metric Source connection");
                connection.remove("revision");
                connection.remove("runtimeConfigRevision");
                connection.remove("packageRevision");
            } else if (rowKind == RowKind.DATA_SOURCE) {
                row.remove("revision");
                removeNulls(row);
            } else if (rowKind == RowKind.PROFILE) {
                if ("att-sut-metrics-profile/v2".equals(row.get("schemaVersion"))) row.remove("revision");
                // The legacy v1 API persisted absent parameter defaults as null, but the
                // versioned profile contract represents an absent default by omission.
                removeProfileNulls(row);
            }
            normalized.add(row);
        }
        return normalized;
    }

    /** Old catalog rows are persisted from normalized API objects and contain optional null fields. */
    private static void removeNulls(Map<String, Object> value) {
        value.entrySet().removeIf(entry -> entry.getValue() == null);
        for (Object child : value.values()) {
            if (child instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) nested;
                removeNulls(map);
            } else if (child instanceof List<?> list) {
                for (Object item : list) if (item instanceof Map<?, ?> nested) {
                    @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) nested;
                    removeNulls(map);
                }
            }
        }
    }

    private static void removeProfileNulls(Map<String, Object> value) {
        boolean normalizedMultiOutput = Boolean.TRUE.equals(value.get("multiOutput"));
        value.entrySet().removeIf(entry -> entry.getValue() == null
                && !(normalizedMultiOutput && "window".equals(entry.getKey())));
        for (Object child : value.values()) {
            if (child instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) nested;
                removeProfileNulls(map);
            } else if (child instanceof List<?> list) {
                for (Object item : list) if (item instanceof Map<?, ?> nested) {
                    @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) nested;
                    removeProfileNulls(map);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMap(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            Object item = entry.getValue();
            if (item instanceof Map<?, ?> nested) result.put(entry.getKey(), deepMap((Map<String, Object>) nested));
            else if (item instanceof List<?> list) {
                List<Object> copied = new ArrayList<>();
                for (Object child : list) copied.add(child instanceof Map<?, ?> nested
                        ? deepMap((Map<String, Object>) nested) : child);
                result.put(entry.getKey(), copied);
            } else result.put(entry.getKey(), item);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(field + " must be a mapping.");
        return (Map<String, Object>) value;
    }

    private static byte[] boundedRead(Path path, int limit, String label) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(path) || Files.size(path) < 1 || Files.size(path) > limit)
            throw new IOException(label + " is unavailable or exceeds its migration size limit.");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > limit) throw new IOException(label + " exceeds its migration size limit.");
        return bytes;
    }

    private static byte[] dumpYaml(Map<String, Object> value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setPrettyFlow(true);
        options.setSplitLines(false);
        return new Yaml(options).dump(value).getBytes(StandardCharsets.UTF_8);
    }

    private static void backupOrVerify(Path source, Path backup, byte[] expected) throws Exception {
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(backup) || !MessageDigest.isEqual(Files.readAllBytes(backup), expected))
                throw new IOException("A migration backup already exists with different content.");
            return;
        }
        Path parent = backup.getParent();
        if (parent == null) throw new IOException("Migration backups must have a parent directory.");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".att-sut-metrics-backup-", ".tmp");
        try {
            restrict(temporary, false);
            Files.copy(source, temporary, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING);
            if (!MessageDigest.isEqual(Files.readAllBytes(temporary), expected))
                throw new IOException("A migration backup failed verification.");
            try { Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("r--------")); }
            catch (UnsupportedOperationException ignored) { }
            Files.createLink(backup, temporary);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void writeNewOrVerify(Path output, byte[] bytes) throws Exception {
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(output) || !MessageDigest.isEqual(Files.readAllBytes(output), bytes))
                throw new IOException("The candidate output already exists with different content.");
            return;
        }
        atomicCreate(output, bytes);
    }

    private static void atomicCreate(Path output, byte[] bytes) throws Exception {
        Path parent = output.getParent();
        if (parent == null) throw new IOException("Migration output must have a parent directory.");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".att-sut-metrics-migration-", ".tmp");
        try {
            restrict(temporary, false);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.createLink(output, temporary);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void atomicReplace(Path output, byte[] bytes) throws Exception {
        Path parent = output.getParent();
        if (parent == null) throw new IOException("Migration output must have a parent directory.");
        Files.createDirectories(parent);
        java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions = null;
        try { permissions = Files.getPosixFilePermissions(output, LinkOption.NOFOLLOW_LINKS); }
        catch (UnsupportedOperationException ignored) { }
        Path temporary = Files.createTempFile(parent, ".att-sut-metrics-migration-", ".tmp");
        try {
            restrict(temporary, false);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (permissions != null) Files.setPosixFilePermissions(temporary, permissions);
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void restrict(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException ignored) { }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new LinkedHashMap<>();
        boolean apply = false;
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            if ("--apply".equals(arg)) { apply = true; continue; }
            if (!("--config".equals(arg) || "--catalog".equals(arg) || "--output".equals(arg)) || index + 1 >= args.length)
                throw new IllegalArgumentException("Usage: --config <config.yaml> --catalog <dataDir/sut-metrics-catalog.json> [--output <candidate.yaml> | --apply]");
            if (options.putIfAbsent(arg, args[++index]) != null) throw new IllegalArgumentException("Migration arguments may not be repeated.");
        }
        if (!options.containsKey("--config") || !options.containsKey("--catalog") || (apply && options.containsKey("--output")))
            throw new IllegalArgumentException("Usage: --config <config.yaml> --catalog <dataDir/sut-metrics-catalog.json> [--output <candidate.yaml> | --apply]");
        Path config = Path.of(options.get("--config")), catalog = Path.of(options.get("--catalog"));
        Path output = options.containsKey("--output") ? Path.of(options.get("--output")) : null;
        Result result = migrate(config, catalog, output, apply);
        if ("already-migrated".equals(result.state())) System.out.println("SUT Metrics catalog already matches the validated Runtime Config; no files changed.");
        else if ("candidate-written".equals(result.state())) System.out.println("Validated SUT Metrics migration candidate written to " + result.outputPath());
        else System.out.println("SUT Metrics catalog migrated. Runtime Config backup: " + result.configBackup()
                + "; legacy catalog backup: " + result.catalogBackup());
    }
}
