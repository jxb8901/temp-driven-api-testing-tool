package att.config;

import att.Version;
import att.validation.JsonSchemaVerifier;
import att.validation.SchemaFiles;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final String digest, defaultEnvironment, name, description;
    private final List<String> tags;
    private final Path templatesRoot, testcaseRoot;
    private final boolean templatesDeclared, testcaseDeclared;
    private final List<Path> descriptors;
    private final Map<String, List<Path>> environmentDescriptors;
    private final Integer timeoutMs, htmlLimit, junitLimit;
    private final String runIdFormat, debugIdFormat, timestampFormat, reportFileName, namespaceMode;
    private final Map<String, String> reportColumns;
    private final Boolean yamlAnchors;

    private PackageConfig(Path root, Map<?, ?> document, boolean present, String digest) throws Exception {
        this.present = present;
        this.digest = digest;
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
            return parse(root, new String(bytes, StandardCharsets.UTF_8), sha256(bytes));
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
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid(FILE_NAME, "size-limit");
        LoaderOptions options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0); options.setCodePointLimit(MAX_BYTES);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        for (Event event : yaml.parse(new StringReader(source))) {
            if (event instanceof AliasEvent || event instanceof NodeEvent && ((NodeEvent) event).getAnchor() != null)
                throw invalid(FILE_NAME, "anchors and aliases are not permitted");
        }
        Object loaded = yaml.load(source);
        try { JsonSchemaVerifier.verify(SchemaFiles.resolve(root, "att-package-v1.schema.json"), loaded); }
        catch (JsonSchemaVerifier.SchemaValidationException e) { throw invalid(e.field(), "invalid-schema or forbidden field"); }
        return new PackageConfig(root, (Map<?, ?>) loaded, true, digest);
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
    public String defaultEnvironment() { return defaultEnvironment; }
    public String name() { return name; }
    public String description() { return description; }
    public List<String> tags() { return tags; }
    public Path templatesRoot() { return templatesRoot; }
    public Path testcaseRoot() { return testcaseRoot; }
    public List<Path> descriptors(String environment) { List<Path> selected = environmentDescriptors.get(environment.toLowerCase(Locale.ROOT)); return selected == null ? descriptors : selected; }
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
        Map<String, Object> out = new LinkedHashMap<String, Object>(); out.put("schemaVersion", Version.PACKAGE_SCHEMA);
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
