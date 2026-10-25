package att.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Field-specific projection; Package data is never merged into trusted Runtime Config. */
public final class EffectiveConfigurationResolver {
    private EffectiveConfigurationResolver() {}

    public static FrameworkConfig resolve(FrameworkConfig global, PackageConfig pkg, Path root,
                                          String environmentOrigin, String globalDigest, int maxTimeoutMs,
                                          boolean legacyTestdata, java.util.List<String> diagnostics) throws Exception {
        if (pkg.timeoutMs() != null && pkg.timeoutMs() > maxTimeoutMs)
            throw new IllegalArgumentException("package.yaml [Manifest] defaults.timeoutMs exceeds administrator packagePolicy.maxTimeoutMs");
        PackageConfig.safeRelative(root, pkg.templatesRoot().toString().replace('\\', '/'));
        PackageConfig.safeRelative(root, pkg.testcaseRoot().toString().replace('\\', '/'));
        Map<String, String> origins = new LinkedHashMap<String, String>();
        origins.put("environment", environmentOrigin);
        origins.put("resources.templatesRoot", pkg.templatesOrigin());
        origins.put("resources.testcaseRoot", pkg.testcaseOrigin());
        origins.put("testdata", legacyTestdata && !pkg.present() ? "global" : "package");
        origins.put("timeoutMs", pkg.timeoutMs() == null ? "global" : "package");
        origins.put("execution.runIdFormat", pkg.runIdFormat() == null ? "global" : "package");
        origins.put("execution.debugIdFormat", pkg.debugIdFormat() == null ? "global" : "package");
        origins.put("run.id.timestampFormat", pkg.timestampFormat() == null ? "global" : "package");
        origins.put("report.fileNamePattern", pkg.reportFileName() == null ? "global" : "package");
        origins.put("report.columns", pkg.reportColumns() == null ? "global" : "package");
        origins.put("report.html.caseLogInlineLimitBytes", pkg.htmlLimit() == null ? "global" : "package");
        origins.put("report.junit.caseLogEmbedThresholdBytes", pkg.junitLimit() == null ? "global" : "package");
        origins.put("caseLog.yamlAnchors", pkg.yamlAnchors() == null ? "global" : "package");
        origins.put("xml.namespaceMode", pkg.namespaceMode() == null ? "global" : "package");
        String identity = PackageConfig.sha256((globalDigest + "\n" + pkg.digest() + "\n" + global.environment()
                + "\n" + att.Version.PRODUCT + "\n" + att.validation.SchemaFiles.registryDigest() + "\n" + root.toAbsolutePath().normalize()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new FrameworkConfig(global, pkg, new EffectiveExecutionConfig(identity, environmentOrigin, pkg.digest(), origins, diagnostics), legacyTestdata);
    }
}
