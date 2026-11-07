package att.config;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Field-specific projection; Package data is never merged into trusted Runtime Config. */
public final class EffectiveConfigurationResolver {
    private EffectiveConfigurationResolver() {}

    public static FrameworkConfig resolve(FrameworkConfig global, PackageConfig pkg, Path root,
                                          String environmentOrigin, String globalDigest, int maxTimeoutMs,
                                          boolean legacyTestdata, java.util.List<String> diagnostics, Map<?,?> globalDeclared) throws Exception {
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
        for(Map.Entry<String,String> field : origins.entrySet()) {
            if("global".equals(field.getValue()) && !"environment".equals(field.getKey()) && !"testdata".equals(field.getKey())
                    && !declared(globalDeclared,field.getKey()))field.setValue("runtime-default");
        }
        String identity = semanticIdentity(global, pkg, root);
        return new FrameworkConfig(global, pkg, new EffectiveExecutionConfig(identity, environmentOrigin, pkg.digest(), globalDigest, origins, diagnostics), legacyTestdata);
    }

    /** Execution identity ignores comments, shadowed source values, and temporary materialization roots. */
    private static String semanticIdentity(FrameworkConfig global, PackageConfig pkg, Path root) throws Exception {
        Map<String, String> fields = new TreeMap<String, String>();
        fields.put("environment", canonical(global.environment()));
        fields.put("timeoutMs", canonical(pkg.timeoutMs() == null ? global.timeoutMs() : pkg.timeoutMs()));
        fields.put("templatesRoot", canonical(pkg.templatesRoot().toString().replace('\\', '/')));
        fields.put("testcaseRoot", canonical(pkg.testcaseRoot().toString().replace('\\', '/')));
        fields.put("report.mode", canonical(global.report().mode()));
        fields.put("report.fileNamePattern", canonical(pkg.reportFileName() == null
                ? global.report().fileNamePattern() : pkg.reportFileName()));
        fields.put("report.columns", canonical(pkg.reportColumns() == null
                ? global.report().columns() : pkg.reportColumns()));
        fields.put("report.junit.caseLogEmbedThresholdBytes", canonical(pkg.junitLimit() == null
                ? global.report().junitCaseLogEmbedThresholdBytes() : pkg.junitLimit()));
        fields.put("report.html.caseLogInlineLimitBytes", canonical(pkg.htmlLimit() == null
                ? global.report().htmlCaseLogInlineLimitBytes() : pkg.htmlLimit()));
        fields.put("run.defaultMode", canonical(global.run().defaultMode()));
        fields.put("run.timestampFormat", canonical(pkg.timestampFormat() == null
                ? global.run().timestampFormat() : pkg.timestampFormat()));
        fields.put("run.runIdFormat", canonical(pkg.runIdFormat() == null
                ? global.run().runIdFormat() : pkg.runIdFormat()));
        fields.put("run.debugIdFormat", canonical(pkg.debugIdFormat() == null
                ? global.run().debugIdFormat() : pkg.debugIdFormat()));
        fields.put("xml.namespaceMode", canonical(pkg.namespaceMode() == null
                ? global.xmlNamespaceMode() : pkg.namespaceMode()));
        fields.put("caseLog.yamlAnchors", canonical(pkg.yamlAnchors() == null
                ? global.caseLogYamlAnchors() : pkg.yamlAnchors()));
        java.util.List<String> descriptors = new java.util.ArrayList<String>();
        for (Path descriptor : pkg.present() ? pkg.descriptors(global.environment()) : global.testdataDescriptors()) {
            Path normalized = descriptor.toAbsolutePath().normalize();
            descriptors.add(normalized.startsWith(root.toAbsolutePath().normalize())
                    ? root.toAbsolutePath().normalize().relativize(normalized).toString().replace('\\', '/')
                    : descriptor.getFileName().toString());
        }
        java.util.Collections.sort(descriptors);
        fields.put("testdata.layers", canonical(descriptors));
        fields.put("tools.ids", canonical(new java.util.TreeSet<String>(global.tools().keySet())));
        fields.put("dbhelpers.ids", canonical(new java.util.TreeSet<String>(global.dbHelpers().keySet())));
        fields.put("mqhelpers.ids", canonical(new java.util.TreeSet<String>(global.mqHelpers().keySet())));
        fields.put("sshhelpers.ids", canonical(new java.util.TreeSet<String>(global.sshHelpers().keySet())));
        fields.put("httphelpers.ids", canonical(new java.util.TreeSet<String>(global.httpHelpers().keySet())));
        StringBuilder value = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet())
            value.append(field.getKey()).append('=').append(field.getValue()).append('\n');
        return hex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String canonical(Object value) {
        if (value == null) return "null";
        if (value instanceof Map) {
            Map<String, Object> sorted = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            StringBuilder result = new StringBuilder("{");
            for (Map.Entry<String, Object> entry : sorted.entrySet()) result.append(canonical(entry.getKey()))
                    .append(':').append(canonical(entry.getValue())).append(';');
            return result.append('}').toString();
        }
        if (value instanceof Iterable) {
            StringBuilder result = new StringBuilder("[");
            for (Object item : (Iterable<?>) value) result.append(canonical(item)).append(';');
            return result.append(']').toString();
        }
        if (value instanceof Path) return ((Path) value).normalize().toString().replace('\\', '/');
        String text = String.valueOf(value);
        return text.length() + ":" + text;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
    private static boolean declared(Map<?,?> source, String path) {
        Object current=source;
        for(String part:path.split("\\.")) {
            if(!(current instanceof Map)||!((Map<?,?>)current).containsKey(part))return false;
            current=((Map<?,?>)current).get(part);
        }
        return current!=null;
    }
}
