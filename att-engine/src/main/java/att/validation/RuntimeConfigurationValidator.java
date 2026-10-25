package att.validation;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.config.PackageConfig;
import att.config.RuntimeConfigPath;
import att.config.YamlSupport;
import att.testdata.TestdataRegistry;
import att.server.api.AttValidationReport;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/** Read-only Runtime Config, Environment Profile, Helper and Package Testdata validation. */
public final class RuntimeConfigurationValidator {
    private RuntimeConfigurationValidator() { }

    public static AttValidationReport validate(Path configFile, Path runtimeRoot, Path packageRoot,
                                               String requestedEnvironment, String backend,
                                               boolean allProfiles) {
        return validate(configFile,runtimeRoot,packageRoot,requestedEnvironment,backend,allProfiles,null);
    }

    public static AttValidationReport validate(Path configFile, Path runtimeRoot, Path packageRoot,
                                               String requestedEnvironment, String backend,
                                               boolean allProfiles, Set<String> permittedPackageEnvironments) {
        Path file = configFile.toAbsolutePath().normalize();
        Path root = runtimeRoot.toAbsolutePath().normalize();
        AttValidationReport report = new AttValidationReport(packageRoot == null ? "CONFIG" : "CONFIG_PACKAGE", backend);
        report.context("runtimeConfig",logical(file,root));
        if(packageRoot!=null)report.context("packageManifest","package.yaml");
        if(requestedEnvironment!=null)report.context("environment",requestedEnvironment);
        AttValidationReport.Section shared = report.addSection("Shared Runtime Config", logical(file, root), "PASS");
        List<String> profiles;
        Map<?, ?> raw;
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(file))
                throw new IllegalArgumentException("Shared Runtime Config is missing, unreadable, or a symbolic link");
            Object loaded = YamlSupport.load(file);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("Shared Runtime Config must be a YAML mapping");
            raw = (Map<?, ?>) loaded;
            profiles = environmentProfiles(raw, requestedEnvironment, allProfiles);
            if(allProfiles)report.context("environments",profiles.isEmpty()?Collections.singletonList("default"):profiles);
            else if(requestedEnvironment==null&&profiles.isEmpty())report.context("environment","default");
        } catch (Exception invalid) {
            shared.diagnostic(diagnostic("ATT-CONFIG-INVALID", "ERROR", logical(file, root), "config",
                    safe(invalid.getMessage(), root), "Correct the Runtime Config YAML, schema, or referenced path."));
            shared.status("FAIL");
            report.exitCode(2);
            return report;
        }

        FrameworkConfigLoader loader = new FrameworkConfigLoader();
        List<String> checkedProfiles = profiles.isEmpty() ? Collections.singletonList(null) : profiles;
        boolean sharedFailed = false;
        for (String profile : checkedProfiles) {
            String name = profile == null ? "default" : profile;
            AttValidationReport.Section environment = shared.child(name + " — Tool Groups and DB/MQ/HTTP/SSH Helpers", logical(file, root), "PASS");
            try {
                FrameworkConfig config = loader.loadGlobalDiscovery(file, root, profile);
                addHelperFailures(environment, config, profile, root);
                if (packageRoot != null) {
                    if(permittedPackageEnvironments==null||permittedPackageEnvironments.contains(name.toLowerCase(Locale.ROOT))) {
                        FrameworkConfig effective = loader.loadDiscovery(file, root, packageRoot, profile);
                        addHelperFailures(environment, effective, profile, root);
                    } else {
                        AttValidationReport.Section skipped=environment.child("Package effective configuration","package.yaml","SKIP");
                        skipped.diagnostic(diagnostic("ATT-ENVIRONMENT-FORBIDDEN","INFO","package.yaml","environment",
                                "Environment is excluded by the active Server Package policy",null));
                    }
                }
            } catch (Exception invalid) {
                environment.diagnostic(diagnostic("ATT-CONFIG-INVALID", "ERROR", logical(file, root), "environments." + name,
                        safe(invalid.getMessage(), root, packageRoot), "Correct the selected Environment Profile and its static references."));
                sharedFailed = true;
            }
            if (sectionHasError(environment)) sharedFailed = true;
        }

        if (packageRoot != null) {
            boolean packageFailed = false;
            AttValidationReport.Section manifestSection = report.addSection("Package Manifest / Effective Config / Testdata",
                    "package.yaml", "PASS");
            try {
                PackageConfig manifest = PackageConfig.load(packageRoot);
                Set<String> declared = new LinkedHashSet<String>();
                Object environments = raw.get("environments");
                if (environments instanceof Map) for (Object key : ((Map<?, ?>) environments).keySet())
                    if (key instanceof String && !att.config.SchemaSupport.isDisabledKey(key)) declared.add((String) key);
                if (raw.get("environment") instanceof String) declared.add((String) raw.get("environment"));
                manifest.validateEnvironments(declared);
                List<List<Path>> layers = manifest.descriptorLayers();
                if (!manifest.present() && layers.size() == 1 && layers.get(0).isEmpty()) {
                    for (String profile : checkedProfiles) {
                        FrameworkConfig effective = loader.loadDiscovery(file, root, packageRoot, profile);
                        new TestdataRegistry(packageRoot, effective.testdataDescriptors(), Collections.<Path>emptyList()).validateAll();
                    }
                } else {
                    for (List<Path> layer : layers)
                        new TestdataRegistry(packageRoot, layer, Collections.<Path>emptyList()).validateAll();
                }
            } catch (Exception invalid) {
                manifestSection.diagnostic(diagnostic("ATT-PACKAGE-INVALID", "ERROR", "package.yaml", "manifest/testdata",
                        safe(invalid.getMessage(), packageRoot), "Correct the Package Manifest and every declared Testdata descriptor."));
                packageFailed = true;
            }
            if (packageFailed) manifestSection.status("FAIL");
        }
        if (sharedFailed) { shared.status("FAIL"); report.exitCode(2); }
        return report;
    }

    private static List<String> environmentProfiles(Map<?, ?> raw, String requested, boolean all) {
        Object value = raw.get("environments");
        List<String> names = new ArrayList<String>();
        Set<String> normalized=new LinkedHashSet<String>();
        if (value instanceof Map) for (Object key : ((Map<?, ?>) value).keySet()) {
            if (att.config.SchemaSupport.isDisabledKey(key)) continue;
            if (!(key instanceof String)||!((String)key).matches("[A-Za-z][A-Za-z0-9_-]{0,31}"))
                throw new IllegalArgumentException("Environment Profile names must be valid strings");
            String name=(String)key;
            if(!normalized.add(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Environment Profile names must be unique ignoring case");
            names.add(name);
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        if (all) {
            Object defaultEnvironment=raw.get("environment");
            if(defaultEnvironment instanceof String&&!containsIgnoreCase(names,(String)defaultEnvironment))names.add((String)defaultEnvironment);
            Collections.sort(names,String.CASE_INSENSITIVE_ORDER);
            return names;
        }
        String selected = requested;
        if (selected == null) selected = raw.get("environment") instanceof String ? (String) raw.get("environment") : null;
        if (selected == null) return Collections.emptyList();
        return Collections.singletonList(selected);
    }

    private static boolean containsIgnoreCase(List<String> values,String target) {
        for(String value:values)if(value.equalsIgnoreCase(target))return true;return false;
    }

    public static List<String> declaredProfiles(Path configFile) throws Exception {
        Object loaded=YamlSupport.load(configFile.toAbsolutePath().normalize());
        if(!(loaded instanceof Map))throw new IllegalArgumentException("Shared Runtime Config must be a YAML mapping");
        return Collections.unmodifiableList(new ArrayList<String>(environmentProfiles((Map<?,?>)loaded,null,true)));
    }

    private static void addHelperFailures(AttValidationReport.Section section, FrameworkConfig config,
                                          String profile, Path root) {
        for (att.config.HelperDiscoveryFailure failure : config.helperDiscoveryFailures()) {
            Map<String,Object> diagnostic = failure.diagnostic(profile);
            diagnostic.put("severity", "ERROR");
            diagnostic.put("source", String.valueOf(diagnostic.remove("descriptor")));
            diagnostic.put("message", diagnostic.remove("summary"));
            section.diagnostic(diagnostic);
        }
    }

    private static boolean sectionHasError(AttValidationReport.Section section) {
        for (Map<String,Object> diagnostic : section.diagnosticsView())
            if (!"WARNING".equals(diagnostic.get("severity")) && !"INFO".equals(diagnostic.get("severity"))) return true;
        return false;
    }

    private static Map<String,Object> diagnostic(String code, String severity, String source,
                                                 String field, String message, String suggestion) {
        Map<String,Object> out = new LinkedHashMap<String,Object>();
        out.put("code", code); out.put("severity", severity); out.put("source", source);
        out.put("field", field); out.put("message", message); out.put("suggestion", suggestion);
        return out;
    }

    private static String logical(Path file, Path root) {
        Path absolute = file.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString().replace('\\', '/') : absolute.getFileName().toString();
    }

    private static String safe(String value, Path root) {
        return safe(value, root, null);
    }

    private static String safe(String value, Path runtimeRoot, Path packageRoot) {
        if (value == null || value.trim().isEmpty()) return "Configuration validation failed";
        String result = value;
        if (runtimeRoot != null) result = result.replace(runtimeRoot.toAbsolutePath().normalize().toString(), "<runtime>");
        if (packageRoot != null) result = result.replace(packageRoot.toAbsolutePath().normalize().toString(), "<package>");
        result = att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(
                att.server.api.internal.DiagnosticSanitizer.redactText(result), packageRoot);
        return result.length() > 400 ? result.substring(0, 400) : result;
    }
}
