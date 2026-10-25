package att.resource;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.config.PackageConfig;
import att.config.RuntimeConfigPath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Package-owned defaults have a separate projection from Shared Configuration. */
public final class PackageManifestInspector {
    private final Path root, config;
    private final PackageConfigurationInspector projection;
    public PackageManifestInspector(Path root, Path config) {
        this(root, config, true, Collections.<String>emptyList(), Collections.<String>emptyList());
    }
    public PackageManifestInspector(Path root, Path config, boolean redactionEnabled, List<String> sensitivePaths, List<String> sensitiveFields) {
        this.root = root.toAbsolutePath().normalize(); this.config = config;
        this.projection = new PackageConfigurationInspector(root, config, 262144, redactionEnabled, sensitivePaths, sensitiveFields);
    }

    public Map<String, Object> inspect(String view, String environment) throws Exception {
        if (!java.util.Arrays.asList("declared", "effective", "compare", "resolve").contains(view))
            throw new IllegalArgumentException("Package Configuration view must be declared, effective or compare");
        PackageConfig manifest = PackageConfig.load(root);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("view", view); result.put("scope", "package"); result.put("schemaVersion", att.Version.PACKAGE_SCHEMA);
        result.put("logicalPath", PackageConfig.FILE_NAME); result.put("present", manifest.present());
        result.put("manifestDigest", manifest.digest()); result.put("declared", projection.projectPackageValue(manifest.declared(root), "package", new boolean[1]));
        if ("declared".equals(view)) return result;
        FrameworkConfigLoader loader = new FrameworkConfigLoader();
        FrameworkConfig effective = loader.loadDiscovery(config, RuntimeConfigPath.root(config), root, environment);
        result.put("diagnostics", effective.effectiveConfiguration().diagnostics());
        result.putAll(effective.effectiveConfiguration().metadata(effective.environment()));
        if ("resolve".equals(view)) { result.remove("declared"); return result; }
        result.put("fields", fields(effective, effective.effectiveConfiguration().origins()));
        if ("compare".equals(view)) {
            FrameworkConfig global = loader.loadGlobalDiscovery(config, RuntimeConfigPath.root(config), effective.environment());
            result.put("globalFields", fields(global, Collections.<String, String>emptyMap()));
        }
        return result;
    }

    /** The source publisher validates this private candidate; nothing writes into the live Package. */
    public Map<String, Object> validateCandidate(String source, String environment) throws Exception {
        String before = PackageSourceRevision.capture(root, config);
        PackageConfig candidate = PackageConfig.parse(root, source);
        FrameworkConfig effective = new FrameworkConfigLoader().loadPackageCandidateDiscovery(config, RuntimeConfigPath.root(config), root, environment, source);
        for (List<Path> layer : candidate.descriptorLayers())
            new att.testdata.TestdataRegistry(root, layer, Collections.<Path>emptyList()).validateAll();
        String after = PackageSourceRevision.capture(root, config);
        if (!before.equals(after)) throw new IllegalArgumentException("Package or global sources changed during Manifest validation; validate again");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("validationRevision", digestRevision(after, candidate.digest()));
        result.put("valid", true); result.put("manifestDigest", candidate.digest());
        result.put("declared", projection.projectPackageValue(candidate.declared(root), "package", new boolean[1]));
        result.put("environment", effective.environment()); result.put("environmentOrigin", effective.effectiveConfiguration().environmentOrigin());
        result.put("configurationIdentity", effective.effectiveConfiguration().identity());
        result.put("schemaRegistryDigest", att.validation.SchemaFiles.registryDigest());
        result.put("diagnostics", effective.effectiveConfiguration().diagnostics());
        result.put("fields", fields(effective, effective.effectiveConfiguration().origins()));
        return result;
    }

    private static String digestRevision(String sources, String manifest) throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder result=new StringBuilder();
        for(byte b:digest.digest((sources+"\n"+manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8)))result.append(String.format("%02x",b&255));
        return result.toString();
    }

    private Map<String, Object> fields(FrameworkConfig value, Map<String, String> origins) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("environment", value.environment()); values.put("timeoutMs", value.timeoutMs());
        values.put("resources.templatesRoot", value.templatesRoot().toString().replace('\\', '/'));
        values.put("resources.testcaseRoot", value.testcasesRoot().toString().replace('\\', '/'));
        values.put("execution.runIdFormat", value.run().runIdFormat()); values.put("execution.debugIdFormat", value.run().debugIdFormat());
        values.put("run.id.timestampFormat", value.run().timestampFormat());
        values.put("report.fileNamePattern", value.report().fileNamePattern()); values.put("report.columns", value.report().columns());
        values.put("report.html.caseLogInlineLimitBytes", value.report().htmlCaseLogInlineLimitBytes());
        values.put("report.junit.caseLogEmbedThresholdBytes", value.report().junitCaseLogEmbedThresholdBytes());
        values.put("caseLog.yamlAnchors", value.caseLogYamlAnchors()); values.put("xml.namespaceMode", value.xmlNamespaceMode());
        List<String> testdata = new ArrayList<String>();
        for (Path path : value.testdataDescriptors()) if (path.toAbsolutePath().normalize().startsWith(root))
            testdata.add(root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/'));
        values.put("testdata", testdata);
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Map<String, Object> field = new LinkedHashMap<String, Object>();
            boolean[] masked = new boolean[1];
            field.put("value", projection.projectPackageValue(entry.getValue(), "package." + entry.getKey(), masked));
            field.put("state", masked[0] ? "redacted" : "visible"); field.put("masked", masked[0]);
            String origin = "environment".equals(entry.getKey()) && value.effectiveConfiguration() != null
                    ? value.effectiveConfiguration().environmentOrigin() : origins.get(entry.getKey());
            field.put("origin", origin == null ? "global" : origin);
            field.put("authoredPath", "package".equals(origin) ? "package.yaml" : "runtime-default".equals(origin) ? "" : "config/config.yaml");
            fields.put(entry.getKey(), field);
        }
        return fields;
    }
}
