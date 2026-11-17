package att.config;

import att.Version;
import att.resource.PackageResourceResolver;
import att.validation.JsonSchemaVerifier;
import att.validation.SchemaFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.NodeEvent;

/** Validated, immutable Package metadata. It cannot carry infrastructure or registration settings. */
public final class PackageConfig {
    public static final String FILE_NAME = "package.yaml";
    public static final int MAX_BYTES = 262144;
    private final boolean present;
    private final String digest, schemaVersion, defaultEnvironment, name, description;
    private final List<String> tags;
    private final Path templatesRoot, testcaseRoot;
    private final boolean templatesDeclared, testcaseDeclared;
    private final List<Path> descriptors;
    private final Map<String, List<Path>> environmentDescriptors;
    private final Integer timeoutMs, htmlLimit, junitLimit;
    private final String runIdFormat, debugIdFormat, timestampFormat, reportFileName, namespaceMode;
    private final Map<String, String> reportColumns;
    private final Boolean yamlAnchors;
    private final Set<String> observabilityFields;
    private final Map<String, PackageMetricResource> metricSourceResources, metricGroupResources, metricProfileResources;

    private PackageConfig(Path root, Map<?, ?> document, boolean present, String digest) throws Exception {
        this(root, document, present, digest, null);
    }

    private PackageConfig(Path root, Map<?, ?> document, boolean present, String digest,
                          MetricResourceOverride metricResourceOverride) throws Exception {
        this.present = present;
        this.digest = digest;
        Object declaredSchemaVersion = document.get("schemaVersion");
        this.schemaVersion = declaredSchemaVersion instanceof String ? (String) declaredSchemaVersion : Version.PACKAGE_SCHEMA;
        this.defaultEnvironment = text(document, "defaultEnvironment");
        Map<?, ?> metadata = section(document, "metadata");
        this.name = text(metadata, "name"); this.description = text(metadata, "description");
        List<String> tags = new ArrayList<String>();
        if (metadata.containsKey("tags")) for (Object tag : (List<?>) metadata.get("tags")) tags.add((String) tag);
        this.tags = Collections.unmodifiableList(tags);
        Map<?, ?> resources = section(document, "resources");
        this.templatesDeclared = resources.containsKey("templatesRoot"); this.testcaseDeclared = resources.containsKey("testcaseRoot");
        this.templatesRoot = safeRelative(root, resources.containsKey("templatesRoot") ? text(resources, "templatesRoot") : "templates");
        this.testcaseRoot = safeRelative(root, resources.containsKey("testcaseRoot") ? text(resources, "testcaseRoot") : "testcase");
        Map<?, ?> testdata = section(document, "testdata");
        this.descriptors = paths(root, testdata.get("descriptors"));
        Map<String, List<Path>> environments = new LinkedHashMap<String, List<Path>>();
        for (Map.Entry<?, ?> entry : section(testdata, "environments").entrySet()) {
            String key = ((String) entry.getKey()).toLowerCase(Locale.ROOT);
            if (environments.containsKey(key)) throw invalid("testdata.environments", "duplicate environment ignoring case");
            environments.put(key, paths(root, entry.getValue()));
        }
        this.environmentDescriptors = Collections.unmodifiableMap(environments);
        Map<?, ?> observability = section(document, "observability");
        LinkedHashSet<String> declaredObservabilityFields = new LinkedHashSet<String>();
        for (String field : new String[] {"metricSources", "groups", "profiles"})
            if (observability.containsKey(field)) declaredObservabilityFields.add(field);
        this.observabilityFields = Collections.unmodifiableSet(declaredObservabilityFields);
        this.metricSourceResources = metricResources(root, observability.get("metricSources"), "observability.metricSources",
                "load/sut-metrics/sources/", 64, Collections.singleton("att-sut-metrics-metric-source/v1"), metricResourceOverride);
        this.metricGroupResources = metricResources(root, observability.get("groups"), "observability.groups",
                "load/sut-metrics/groups/", 256, Collections.singleton("att-sut-metrics-group/v1"), metricResourceOverride);
        this.metricProfileResources = metricResources(root, observability.get("profiles"), "observability.profiles",
                "load/sut-metrics/profiles/", 256, Collections.singleton("att-sut-metrics-profile/v2"), metricResourceOverride);
        Map<?, ?> defaults = section(document, "defaults");
        this.timeoutMs = number(defaults, "timeoutMs");
        Map<?, ?> execution = section(defaults, "execution");
        this.runIdFormat = text(execution, "runIdFormat"); this.debugIdFormat = text(execution, "debugIdFormat");
        this.timestampFormat = text(section(section(defaults, "run"), "id"), "timestampFormat");
        if (timestampFormat != null) java.time.format.DateTimeFormatter.ofPattern(timestampFormat);
        att.core.ExecutionIdentityFormat.validateRunIdFormat(runIdFormat);
        att.core.ExecutionIdentityFormat.validateDebugIdFormat(debugIdFormat);
        Map<?, ?> report = section(defaults, "report");
        this.reportFileName = text(report, "fileNamePattern");
        if (reportFileName != null) {
            if (reportFileName.contains("/") || reportFileName.contains("\\") || reportFileName.contains(":") || reportFileName.contains(".."))
                throw invalid("defaults.report.fileNamePattern", "must be a managed-output file name");
            FrameworkConfigLoader.report(Collections.singletonMap("report", report));
        }
        Map<String, String> columns = null;
        if (report.containsKey("columns")) {
            columns = new LinkedHashMap<String, String>();
            for (Map.Entry<?, ?> entry : section(report, "columns").entrySet()) columns.put((String) entry.getKey(), (String) entry.getValue());
        }
        this.reportColumns = columns == null ? null : Collections.unmodifiableMap(columns);
        this.htmlLimit = number(section(report, "html"), "caseLogInlineLimitBytes");
        this.junitLimit = number(section(report, "junit"), "caseLogEmbedThresholdBytes");
        this.namespaceMode = text(section(defaults, "xml"), "namespaceMode");
        this.yamlAnchors = (Boolean) section(defaults, "caseLog").get("yamlAnchors");
    }

    public static PackageConfig load(Path packageRoot) throws IOException {
        Path root = packageRoot.toAbsolutePath().normalize();
        Path file = root.resolve(FILE_NAME);
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS))
                return new PackageConfig(root, Collections.emptyMap(), false, sha256(new byte[0]));
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(file))
                throw invalid(FILE_NAME, "unreadable or outside-package");
            byte[] bytes;
            try (java.io.InputStream input = Files.newInputStream(file)) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_BYTES) throw invalid(FILE_NAME, "size-limit");
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            return parse(root, decodeUtf8(bytes, FILE_NAME), sha256(bytes));
        } catch (Exception error) {
            if (error instanceof PackageConfigException) throw (PackageConfigException) error;
            // Never expose host paths, YAML record values, or arbitrary parser exception text.
            throw invalid(FILE_NAME, "invalid-schema; validate against " + Version.PACKAGE_SCHEMA);
        }
    }

    /** Used by the same draft validator as live parsing; no network schemas or runtime config merging. */
    public static PackageConfig parse(Path root, String source) throws IOException {
        try { return parse(root.toAbsolutePath().normalize(), source, sha256(source.getBytes(StandardCharsets.UTF_8))); }
        catch (PackageConfigException e) { throw e; }
        catch (Exception e) { throw invalid(FILE_NAME, "invalid-schema; validate against " + Version.PACKAGE_SCHEMA); }
    }

    private static PackageConfig parse(Path root, String source, String digest) throws Exception {
        return parse(root, source, digest, null);
    }

    private static PackageConfig parse(Path root, String source, String digest,
                                       MetricResourceOverride metricResourceOverride) throws Exception {
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid(FILE_NAME, "size-limit");
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0); options.setCodePointLimit(MAX_BYTES);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        for (Event event : yaml.parse(new StringReader(source))) {
            if (event instanceof AliasEvent || event instanceof NodeEvent && ((NodeEvent) event).getAnchor() != null)
                throw invalid(FILE_NAME, "anchors and aliases are not permitted");
        }
        Object loaded = yaml.load(source);
        if (!(loaded instanceof Map<?, ?>)) throw invalid(FILE_NAME, "invalid-schema; validate against " + Version.PACKAGE_SCHEMA);
        Object rawVersion = ((Map<?, ?>) loaded).get("schemaVersion");
        String schemaVersion = rawVersion instanceof String ? (String) rawVersion : "";
        String schemaName = Version.PACKAGE_SCHEMA_V1.equals(schemaVersion) ? "att-package-v1.schema.json"
                : Version.PACKAGE_SCHEMA.equals(schemaVersion) ? "att-package-v2.schema.json" : null;
        if (schemaName == null) throw invalid("schemaVersion", "unsupported-package-schema");
        try { JsonSchemaVerifier.verify(SchemaFiles.resolve(root, schemaName), loaded); }
        catch (JsonSchemaVerifier.SchemaValidationException e) { throw invalid(e.field(), "invalid-schema or forbidden field"); }
        return new PackageConfig(root, (Map<?, ?>) loaded, true, digest, metricResourceOverride);
    }

    /** Parses one candidate over an existing explicitly inventoried Package resource without writing it. */
    public static PackageConfig loadWithMetricResourceOverride(Path packageRoot, String relativePath,
                                                               String source) throws IOException {
        if (packageRoot == null) throw invalid(FILE_NAME, "package-root-unavailable");
        Path root = packageRoot.toAbsolutePath().normalize();
        try {
            PackageConfig current = load(root);
            if (!current.present()) throw invalid(FILE_NAME, "manifest-required-for-resource-validation");
            String normalizedPath = canonicalMetricResourcePath(root, relativePath);
            if (!containsMetricResourcePath(current, normalizedPath))
                throw invalid("observability", "resource-is-not-in-an-explicit-inventory");
            if (source == null || source.indexOf('\0') >= 0
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(source))
                throw invalid(normalizedPath, "invalid-utf8-or-unavailable");
            byte[] candidateBytes = source.getBytes(StandardCharsets.UTF_8);
            if (candidateBytes.length > MAX_BYTES) throw invalid(normalizedPath, "size-limit");
            byte[] manifestBytes = readManifestBytes(root);
            if (!current.digest().equals(sha256(manifestBytes)))
                throw invalid(FILE_NAME, "manifest-changed-during-resource-validation");
            String manifestSource = decodeUtf8(manifestBytes, FILE_NAME);
            MetricResourceOverride override = new MetricResourceOverride(normalizedPath, source, false);
            return parse(root, manifestSource, current.digest(), override);
        } catch (PackageConfigException invalid) { throw invalid; }
        catch (Exception unavailable) {
            if (unavailable instanceof IOException) throw (IOException) unavailable;
            throw invalid(relativePath == null ? "observability" : relativePath, "invalid-resource-override");
        }
    }

    /** Parses a v2 Package inventory addition and its new resource as one detached candidate. */
    public static PackageConfig loadWithCandidateManifestAndMetricResourceOverride(
            Path packageRoot, String candidateManifestSource, String relativePath, String source,
            String expectedCurrentManifestDigest) throws IOException {
        if (packageRoot == null) throw invalid(FILE_NAME, "package-root-unavailable");
        Path root = packageRoot.toAbsolutePath().normalize();
        try {
            PackageConfig current = load(root);
            if (!current.present() || !Version.PACKAGE_SCHEMA.equals(current.schemaVersion()))
                throw invalid(FILE_NAME, "new-metric-resources-require-an-existing-v2-package");
            if (expectedCurrentManifestDigest == null || !expectedCurrentManifestDigest.matches("[a-f0-9]{64}")
                    || !expectedCurrentManifestDigest.equals(current.digest()))
                throw invalid(FILE_NAME, "manifest-changed-before-resource-validation");
            String normalizedPath = canonicalMetricResourcePath(root, relativePath);
            if (containsMetricResourcePath(current, normalizedPath))
                throw invalid("observability", "resource-is-already-inventoried");
            if (source == null || source.indexOf('\0') >= 0
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(source))
                throw invalid(normalizedPath, "invalid-utf8-or-unavailable");
            byte[] candidateBytes = source.getBytes(StandardCharsets.UTF_8);
            if (candidateBytes.length > MAX_BYTES) throw invalid(normalizedPath, "size-limit");
            if (candidateManifestSource == null || candidateManifestSource.indexOf('\0') >= 0
                    || !StandardCharsets.UTF_8.newEncoder().canEncode(candidateManifestSource))
                throw invalid(FILE_NAME, "invalid-utf8-or-unavailable");
            byte[] candidateManifestBytes = candidateManifestSource.getBytes(StandardCharsets.UTF_8);
            if (candidateManifestBytes.length > MAX_BYTES) throw invalid(FILE_NAME, "size-limit");

            Path target = safeMetricResourceTarget(root, normalizedPath);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(target) > MAX_BYTES
                        || !java.util.Arrays.equals(Files.readAllBytes(target), candidateBytes))
                    throw invalid(normalizedPath, "resource-path-already-exists");
            }

            MetricResourceOverride override = new MetricResourceOverride(normalizedPath, source, true);
            PackageConfig candidate = parse(root, candidateManifestSource, sha256(candidateManifestBytes), override);
            if (!onlyAddsMetricResourcePath(current, candidate, root, normalizedPath))
                throw invalid(FILE_NAME, "candidate-manifest-may-only-add-this-resource-to-its-inventory");
            byte[] currentManifestBytes = readManifestBytes(root);
            if (!expectedCurrentManifestDigest.equals(sha256(currentManifestBytes)))
                throw invalid(FILE_NAME, "manifest-changed-during-resource-validation");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !java.util.Arrays.equals(Files.readAllBytes(target), candidateBytes))
                throw invalid(normalizedPath, "resource-changed-during-validation");
            return candidate;
        } catch (PackageConfigException invalid) { throw invalid; }
        catch (Exception unavailable) {
            if (unavailable instanceof IOException) throw (IOException) unavailable;
            throw invalid(relativePath == null ? "observability" : relativePath, "invalid-resource-and-manifest-candidate");
        }
    }

    private static Path safeMetricResourceTarget(Path root, String relativePath) throws IOException {
        Path canonicalRoot = root.toRealPath();
        Path target = canonicalRoot.resolve(safeRelative(canonicalRoot, relativePath)).normalize();
        if (!target.startsWith(canonicalRoot)) throw invalid("observability", "resource-path-escapes-package");
        for (Path current = target; current != null && current.startsWith(canonicalRoot); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw invalid(relativePath, "resource-path-contains-symbolic-link");
            if (!current.equals(target) && Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw invalid(relativePath, "resource-path-traverses-non-directory");
            if (current.equals(canonicalRoot)) break;
        }
        return target;
    }

    private static boolean onlyAddsMetricResourcePath(PackageConfig current, PackageConfig candidate,
                                                      Path root, String relativePath) {
        String field;
        if (relativePath.startsWith("load/sut-metrics/sources/")) field = "metricSources";
        else if (relativePath.startsWith("load/sut-metrics/groups/")) field = "groups";
        else if (relativePath.startsWith("load/sut-metrics/profiles/")) field = "profiles";
        else return false;
        Map<String, Object> oldManifest = new LinkedHashMap<String, Object>(current.declared(root));
        Map<String, Object> newManifest = new LinkedHashMap<String, Object>(candidate.declared(root));
        Map<String, Object> oldObservability = mutableMap(oldManifest.get("observability"));
        Map<String, Object> newObservability = mutableMap(newManifest.get("observability"));
        List<String> oldPaths = stringList(oldObservability.get(field));
        List<String> newPaths = stringList(newObservability.get(field));
        if (Collections.frequency(newPaths, relativePath) != 1) return false;
        List<String> withoutCandidate = new ArrayList<String>(newPaths);
        withoutCandidate.remove(relativePath);
        if (!oldPaths.equals(withoutCandidate)) return false;
        oldObservability.remove(field);
        newObservability.remove(field);
        if (oldObservability.isEmpty()) oldManifest.remove("observability");
        else oldManifest.put("observability", oldObservability);
        if (newObservability.isEmpty()) newManifest.remove("observability");
        else newManifest.put("observability", newObservability);
        return oldManifest.equals(newManifest);
    }

    private static Map<String, Object> mutableMap(Object value) {
        if (!(value instanceof Map<?, ?>)) return new LinkedHashMap<String, Object>();
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        return copy;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?>)) return new ArrayList<String>();
        List<String> copy = new ArrayList<String>();
        for (Object item : (List<?>) value) if (item instanceof String) copy.add((String) item);
        else return new ArrayList<String>();
        return copy;
    }

    private static byte[] readManifestBytes(Path root) throws IOException {
        Path file = root.resolve(FILE_NAME);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.isReadable(file) || Files.size(file) > MAX_BYTES)
            throw invalid(FILE_NAME, "unreadable-or-outside-package");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_BYTES) throw invalid(FILE_NAME, "size-limit");
        return bytes;
    }

    private static String decodeUtf8(byte[] bytes, String field) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidUtf8) { throw invalid(field, "invalid-utf8"); }
    }

    private static String canonicalMetricResourcePath(Path root, String authoredPath) throws IOException {
        if (authoredPath == null || authoredPath.length() > 512 || authoredPath.contains("\\"))
            throw invalid("observability", "unsafe-resource-path");
        Path relative = safeRelative(root, authoredPath);
        String normalized = relative.toString().replace('\\', '/');
        if (!normalized.equals(authoredPath) || !yamlPath(normalized)
                || !normalized.matches("load/sut-metrics/(?:sources|groups|profiles)/.+\\.ya?ml"))
            throw invalid("observability", "resource-path-must-be-canonical-and-inventory-scoped");
        return normalized;
    }

    private static boolean containsMetricResourcePath(PackageConfig config, String path) {
        return java.util.stream.Stream.of(config.metricSourceResources(), config.metricGroupResources(), config.metricProfileResources())
                .flatMap(resources -> resources.values().stream()).anyMatch(resource -> path.equals(resource.relativePath()));
    }

    private static final class MetricResourceOverride {
        private final String relativePath, source;
        private final boolean allowMissing;
        private MetricResourceOverride(String relativePath, String source, boolean allowMissing) {
            this.relativePath = relativePath;
            this.source = source;
            this.allowMissing = allowMissing;
        }
    }

    private static Map<String, PackageMetricResource> metricResources(Path root, Object raw, String field,
                                                                        String requiredPrefix, int maxItems,
                                                                        Set<String> allowedSchemas,
                                                                        MetricResourceOverride metricResourceOverride) throws Exception {
        if (raw == null) return Collections.emptyMap();
        if (!(raw instanceof List<?>)) throw invalid(field, "expected-path-list");
        List<?> paths = (List<?>) raw;
        if (paths.size() > maxItems) throw invalid(field, "item-limit");
        if (paths.isEmpty()) return Collections.emptyMap();
        Path canonicalRoot;
        PackageResourceResolver resolver;
        try {
            canonicalRoot = root.toAbsolutePath().normalize().toRealPath();
            resolver = new PackageResourceResolver(canonicalRoot);
        } catch (Exception unavailable) {
            throw invalid(field, "package-root-unavailable");
        }
        Map<String, PackageMetricResource> result = new LinkedHashMap<String, PackageMetricResource>();
        Set<Path> canonicalFiles = new HashSet<Path>();
        for (int index = 0; index < paths.size(); index++) {
            Object item = paths.get(index);
            String pathField = field + "[" + index + "]";
            if (!(item instanceof String)) throw invalid(pathField, "expected-path");
            String authoredPath = (String) item;
            Path relative;
            try { relative = safeRelative(canonicalRoot, authoredPath); }
            catch (Exception unsafe) { throw invalid(pathField, "unsafe-or-noncanonical-package-path"); }
            String logicalPath = relative.toString().replace('\\', '/');
            if (!logicalPath.startsWith(requiredPrefix) || logicalPath.length() <= requiredPrefix.length())
                throw invalid(pathField, "path-must-be-under-" + requiredPrefix);
            if (!yamlPath(logicalPath)) throw invalid(pathField, "expected-yaml-file");
            boolean overlayEntry = metricResourceOverride != null
                    && logicalPath.equals(metricResourceOverride.relativePath);
            Path file;
            String canonicalLogicalPath;
            Path expectedFile = canonicalRoot.resolve(relative).normalize();
            if (overlayEntry && metricResourceOverride.allowMissing
                    && Files.notExists(expectedFile, LinkOption.NOFOLLOW_LINKS)) {
                try { file = resolver.internalCandidate(expectedFile); }
                catch (Exception unsafe) { throw invalid(pathField, "missing-or-unsafe-package-file"); }
                if (!file.equals(expectedFile)) throw invalid(pathField, "noncanonical-package-resource-path");
                canonicalLogicalPath = logicalPath;
            } else {
                PackageResourceResolver.PackageResource resource;
                try { resource = resolver.resolvePackageRelative(logicalPath, PackageResourceResolver.Kind.FILE); }
                catch (Exception unsafeOrMissing) { throw invalid(pathField, "missing-or-unsafe-package-file"); }
                canonicalLogicalPath = resource.logicalName();
                file = resource.canonicalPath();
            }
            if (!canonicalLogicalPath.startsWith(requiredPrefix) || !yamlPath(canonicalLogicalPath))
                throw invalid(pathField, "canonical-target-must-remain-under-" + requiredPrefix);
            if (!canonicalFiles.add(file)) throw invalid(pathField, "duplicate-package-file");
            MetricDefinition loaded = overlayEntry
                    ? readMetricDefinition(metricResourceOverride.source, canonicalRoot, pathField, allowedSchemas)
                    : readMetricDefinition(file, canonicalRoot, pathField, allowedSchemas);
            Map<?, ?> definition = loaded.definition;
            Object rawId = definition.get("id");
            if (!(rawId instanceof String) || ((String) rawId).isEmpty()) throw invalid(pathField + ".id", "invalid-resource-id");
            String id = (String) rawId;
            if (result.containsKey(id)) throw invalid(pathField + ".id", "duplicate-resource-id-within-kind");
            result.put(id, new PackageMetricResource(id, logicalPath, file, definition, loaded.digest));
        }
        return Collections.unmodifiableMap(result);
    }

    private static MetricDefinition readMetricDefinition(Path file, Path schemaRoot, String field,
                                                         Set<String> allowedSchemas) throws Exception {
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_BYTES) throw invalid(field, "size-limit");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_BYTES) throw invalid(field, "size-limit");
                    output.write(buffer, 0, count);
                }
            }
            bytes = output.toByteArray();
        } catch (PackageConfigException invalid) { throw invalid; }
        catch (Exception unavailable) { throw invalid(field, "unreadable-package-file"); }
        return parseMetricDefinition(bytes, schemaRoot, field, allowedSchemas);
    }

    private static MetricDefinition readMetricDefinition(String source, Path schemaRoot, String field,
                                                         Set<String> allowedSchemas) throws Exception {
        if (source == null || !StandardCharsets.UTF_8.newEncoder().canEncode(source))
            throw invalid(field, "invalid-utf8");
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw invalid(field, "size-limit");
        return parseMetricDefinition(bytes, schemaRoot, field, allowedSchemas);
    }

    private static MetricDefinition parseMetricDefinition(byte[] bytes, Path schemaRoot, String field,
                                                          Set<String> allowedSchemas) throws Exception {
        String source = decodeUtf8(bytes, field);
        Object parsed;
        try {
            LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0); options.setCodePointLimit(MAX_BYTES);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            for (Event event : yaml.parse(new StringReader(source)))
                if (event instanceof AliasEvent || event instanceof NodeEvent && ((NodeEvent) event).getAnchor() != null)
                    throw invalid(field, "anchors-and-aliases-are-not-permitted");
            parsed = yaml.load(source);
        } catch (PackageConfigException invalid) { throw invalid; }
        catch (Exception malformedYaml) { throw invalid(field, "invalid-yaml"); }
        if (!(parsed instanceof Map<?, ?>)) throw invalid(field, "expected-definition-map");
        Map<?, ?> definition = (Map<?, ?>) parsed;
        Object rawVersion = definition.get("schemaVersion");
        if (!(rawVersion instanceof String) || !allowedSchemas.contains(rawVersion))
            throw invalid(field + ".schemaVersion", "unsupported-resource-schema");
        try {
            JsonSchemaVerifier.verify(SchemaFiles.resolveVersion(schemaRoot, (String) rawVersion), definition);
        } catch (JsonSchemaVerifier.SchemaValidationException schemaError) {
            throw invalid(field + "." + schemaError.field(), "invalid-schema");
        } catch (PackageConfigException invalid) { throw invalid; }
        catch (Exception unavailable) { throw invalid(field, "schema-validation-unavailable-or-invalid"); }
        return new MetricDefinition(definition, sha256(bytes));
    }

    private static final class MetricDefinition {
        private final Map<?, ?> definition;
        private final String digest;
        private MetricDefinition(Map<?, ?> definition, String digest) {
            this.definition = definition;
            this.digest = digest;
        }
    }

    private static boolean yamlPath(String path) { return path.endsWith(".yaml") || path.endsWith(".yml"); }

    private static Map<String, Object> immutableDefinition(Map<?, ?> definition) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : definition.entrySet()) {
            if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("SUT Metrics definition has a non-string field name.");
            copy.put((String) entry.getKey(), immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?>) return immutableDefinition((Map<?, ?>) value);
        if (value instanceof List<?>) {
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (List<?>) value) copy.add(immutableValue(item));
            return Collections.unmodifiableList(copy);
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("SUT Metrics definition contains an unsupported value.");
    }

    /** Reject unsafe spellings and check all existing ancestors while permitting undiscovered roots. */
    public static Path safeRelative(Path root, String name) throws IOException {
        if (name == null || name.isEmpty() || name.contains("\\") || name.contains(":") || name.startsWith("/") || name.endsWith("/"))
            throw invalid("resources/testdata", "outside-package path");
        for (String part : name.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals(".."))
            throw invalid("resources/testdata", "noncanonical or outside-package path");
        Path relative = Paths.get(name);
        if (relative.isAbsolute()) throw invalid("resources/testdata", "outside-package path");
        Path boundary = root.toAbsolutePath().normalize();
        Path canonical = Files.exists(boundary) ? boundary.toRealPath() : boundary;
        Path cursor = boundary;
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().startsWith(canonical))
                throw invalid("resources/testdata", "outside-package symlink");
        }
        return relative;
    }

    private static List<Path> paths(Path root, Object value) throws IOException {
        List<Path> result = new ArrayList<Path>();
        if (value != null) for (Object item : (List<?>) value) {
            String name = (String) item;
            Path path = safeRelative(root, name);
            if (!name.matches(".*\\.ya?ml")) throw invalid("testdata", "expected YAML descriptor path");
            result.add(root.resolve(path).normalize());
        }
        return Collections.unmodifiableList(result);
    }
    private static Map<?, ?> section(Map<?, ?> map, String key) { Object value = map.get(key); return value == null ? Collections.emptyMap() : (Map<?, ?>) value; }
    private static String text(Map<?, ?> map, String key) { return (String) map.get(key); }
    private static Integer number(Map<?, ?> map, String key) { Object value = map.get(key); return value == null ? null : ((Number) value).intValue(); }
    private static PackageConfigException invalid(String field, String reason) { return new PackageConfigException("package.yaml [Manifest] " + field + ": " + reason); }
    static String sha256(byte[] bytes) throws Exception { StringBuilder result = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", b & 255)); return result.toString(); }
    public static final class PackageConfigException extends IllegalArgumentException { private PackageConfigException(String message) { super(message); } }
    public boolean present() { return present; }
    public String digest() { return digest; }
    public String schemaVersion() { return schemaVersion; }
    public String defaultEnvironment() { return defaultEnvironment; }
    public String name() { return name; }
    public String description() { return description; }
    public List<String> tags() { return tags; }
    public Path templatesRoot() { return templatesRoot; }
    public Path testcaseRoot() { return testcaseRoot; }
    public List<Path> descriptors(String environment) { List<Path> selected = environmentDescriptors.get(environment.toLowerCase(Locale.ROOT)); return selected == null ? descriptors : selected; }
    /** Explicit Package-owned SUT Metric Sources, keyed by their schema-validated resource IDs. */
    public Map<String, PackageMetricResource> metricSourceResources() { return metricSourceResources; }
    /** Explicit Package-owned SUT Metric Groups, keyed by their schema-validated resource IDs. */
    public Map<String, PackageMetricResource> metricGroupResources() { return metricGroupResources; }
    /** Explicit Package-owned flat/legacy SUT Metrics Profiles, keyed by their schema-validated resource IDs. */
    public Map<String, PackageMetricResource> metricProfileResources() { return metricProfileResources; }
    /** Whether the Package explicitly declares SUT Metrics inventory, including empty inventories. */
    public boolean hasMetricInventory() { return !observabilityFields.isEmpty(); }
    public List<List<Path>> descriptorLayers() {
        List<List<Path>> layers = new ArrayList<List<Path>>(); layers.add(descriptors); layers.addAll(environmentDescriptors.values());
        return Collections.unmodifiableList(layers);
    }
    public void validateEnvironments(java.util.Set<String> globallyDeclared) {
        java.util.Set<String> names = new java.util.HashSet<String>();
        for (String name : globallyDeclared) names.add(name.toLowerCase(Locale.ROOT));
        for (String name : environmentDescriptors.keySet()) if (!names.contains(name)) throw invalid("testdata.environments", "unknown global environment " + name);
        if (defaultEnvironment != null && !names.contains(defaultEnvironment.toLowerCase(Locale.ROOT))) throw invalid("defaultEnvironment", "unknown global environment");
    }
    public String templatesOrigin() { return templatesDeclared ? "package" : "runtime-default"; }
    public String testcaseOrigin() { return testcaseDeclared ? "package" : "runtime-default"; }
    public Map<String, Object> declared(Path root) {
        Map<String, Object> out = new LinkedHashMap<String, Object>(); out.put("schemaVersion", schemaVersion);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        if (name != null) metadata.put("name", name); if (description != null) metadata.put("description", description);
        if (!tags.isEmpty()) metadata.put("tags", tags); if (!metadata.isEmpty()) out.put("metadata", metadata);
        if (defaultEnvironment != null) out.put("defaultEnvironment", defaultEnvironment);
        Map<String, Object> resources = new LinkedHashMap<String, Object>();
        if (templatesDeclared) resources.put("templatesRoot", templatesRoot.toString().replace('\\', '/'));
        if (testcaseDeclared) resources.put("testcaseRoot", testcaseRoot.toString().replace('\\', '/'));
        if (!resources.isEmpty()) out.put("resources", resources);
        Map<String, Object> testdata = new LinkedHashMap<String, Object>();
        testdata.put("descriptors", logicalPaths(root, descriptors));
        Map<String, Object> environments = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, List<Path>> entry : environmentDescriptors.entrySet()) environments.put(entry.getKey(), logicalPaths(root, entry.getValue()));
        if (!environments.isEmpty()) testdata.put("environments", environments); out.put("testdata", testdata);
        if (!observabilityFields.isEmpty()) {
            Map<String, Object> observability = new LinkedHashMap<String, Object>();
            if (observabilityFields.contains("metricSources")) observability.put("metricSources", logicalPaths(metricSourceResources));
            if (observabilityFields.contains("groups")) observability.put("groups", logicalPaths(metricGroupResources));
            if (observabilityFields.contains("profiles")) observability.put("profiles", logicalPaths(metricProfileResources));
            out.put("observability", observability);
        }
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        if (timeoutMs != null) defaults.put("timeoutMs", timeoutMs);
        Map<String, Object> execution = new LinkedHashMap<String, Object>();
        if (runIdFormat != null) execution.put("runIdFormat", runIdFormat); if (debugIdFormat != null) execution.put("debugIdFormat", debugIdFormat);
        if (!execution.isEmpty()) defaults.put("execution", execution);
        if (timestampFormat != null) defaults.put("run", Collections.singletonMap("id", Collections.singletonMap("timestampFormat", timestampFormat)));
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        if (reportFileName != null) report.put("fileNamePattern", reportFileName); if (reportColumns != null) report.put("columns", reportColumns);
        if (htmlLimit != null) report.put("html", Collections.singletonMap("caseLogInlineLimitBytes", htmlLimit));
        if (junitLimit != null) report.put("junit", Collections.singletonMap("caseLogEmbedThresholdBytes", junitLimit));
        if (!report.isEmpty()) defaults.put("report", report);
        if (yamlAnchors != null) defaults.put("caseLog", Collections.singletonMap("yamlAnchors", yamlAnchors));
        if (namespaceMode != null) defaults.put("xml", Collections.singletonMap("namespaceMode", namespaceMode));
        if (!defaults.isEmpty()) out.put("defaults", defaults);
        return Collections.unmodifiableMap(out);
    }
    private static List<String> logicalPaths(Path root, List<Path> paths) {
        List<String> names = new ArrayList<String>(); for (Path path : paths) names.add(root.toAbsolutePath().normalize().relativize(path).toString().replace('\\', '/'));
        return Collections.unmodifiableList(names);
    }
    private static List<String> logicalPaths(Map<String, PackageMetricResource> resources) {
        List<String> names = new ArrayList<String>();
        for (PackageMetricResource resource : resources.values()) names.add(resource.relativePath());
        return Collections.unmodifiableList(names);
    }

    /** One explicit Package inventory entry. The logical path preserves manifest provenance. */
    public static final class PackageMetricResource {
        private final String id, relativePath;
        private final Path canonicalPath;
        private final String digest;
        private final Map<String, Object> definition;
        private PackageMetricResource(String id, String relativePath, Path canonicalPath, Map<?, ?> definition, String digest) {
            this.id = id; this.relativePath = relativePath; this.canonicalPath = canonicalPath;
            this.digest = digest;
            this.definition = immutableDefinition(definition);
        }
        public String id() { return id; }
        public String relativePath() { return relativePath; }
        public Path canonicalPath() { return canonicalPath; }
        public String digest() { return digest; }
        /** Schema-validated resource content as an immutable deep copy. */
        public Map<String, Object> definition() { return definition; }
    }
    Integer timeoutMs() { return timeoutMs; }
    String runIdFormat() { return runIdFormat; }
    String debugIdFormat() { return debugIdFormat; }
    String timestampFormat() { return timestampFormat; }
    String reportFileName() { return reportFileName; }
    Map<String, String> reportColumns() { return reportColumns; }
    Integer htmlLimit() { return htmlLimit; }
    Integer junitLimit() { return junitLimit; }
    String namespaceMode() { return namespaceMode; }
    Boolean yamlAnchors() { return yamlAnchors; }
}
