package att.resource;

import att.config.DbHelperConfig;
import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.config.RuntimeConfigPath;
import att.config.HttpHelperConfig;
import att.config.MqHelperConfig;
import att.config.SshConfig;
import att.config.SshHelperConfig;
import att.config.ToolArgumentConfig;
import att.config.ToolConfig;
import att.config.YamlSupport;
import att.testdata.TestdataDescriptor;
import att.testdata.TestdataDescriptorLoader;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Safe declared and effective configuration projections for the authenticated Server inspector. */
public final class PackageConfigurationInspector {
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;
    private static final int MAX_REFERENCED_FILES = 512;
    private static final long MAX_TOTAL_DESCRIPTOR_BYTES = 16L * 1024L * 1024L;
    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");
    private static final Pattern JAVA_CLASS = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]{0,255}");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)([\"']?(?:password|passwd|passphrase|token|secret|authorization|credential|api[_-]?key|access[_-]?key|private[_-]?key|client[_-]?secret|refresh[_-]?token)[\"']?\\s*(?::|=|\\bis\\b)\\s*[\"']?)([^\\s,'\";}<>]+)");
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/=_-]+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(\\b(?:jdbc:)?[a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]+@");
    private static final Pattern XML_SECRET = Pattern.compile(
            "(?is)(<(password|passwd|passphrase|token|secret|authorization|credential|api[_-]?key|access[_-]?key|private[_-]?key|client[_-]?secret)(?:\\s+[^>]*)?>)[^<]*(</\\2\\s*>)");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----");
    private static final List<String> RESOURCE_LISTS = Arrays.asList(
            "dbhelpers", "mqhelpers", "sshhelpers", "httphelpers", "testdata");
    private static final List<String> ALL_SECTIONS = Arrays.asList(
            "dbhelpers", "mqhelpers", "sshhelpers", "httphelpers", "toolGroups", "tools", "testdata");

    private final Path packageRoot;
    private final Path configPath;
    private PackageConfigurationSnapshots snapshots;
    private Object snapshotContext;
    private boolean sharedRuntime;

    /** Shared views never resolve Package-local Testdata records or dependencies. */
    public PackageConfigurationInspector forSharedRuntime() { sharedRuntime=true; return this; }
    private final PackageResourceResolver resources;
    private final PackageResourceResolver configResources;
    private final int maxResponseBytes;
    private final boolean redactionEnabled;
    private final boolean includeAllFields;
    private final List<String> sensitivePaths, sensitiveFieldPatterns;

    public PackageConfigurationInspector(Path packageRoot, Path configPath, int maxResponseBytes) {
        this(packageRoot, configPath, maxResponseBytes, true, Collections.<String>emptyList(), Collections.<String>emptyList());
    }

    public PackageConfigurationInspector(Path packageRoot, Path configPath, int maxResponseBytes,
                                         boolean redactionEnabled, List<String> sensitivePaths,
                                         List<String> sensitiveFieldPatterns) {
        this(packageRoot, configPath, maxResponseBytes, redactionEnabled, sensitivePaths, sensitiveFieldPatterns, false);
    }

    public PackageConfigurationInspector(Path packageRoot, Path configPath, int maxResponseBytes,
                                         boolean redactionEnabled, List<String> sensitivePaths,
                                         List<String> sensitiveFieldPatterns, boolean includeAllFields) {
        this.resources = new PackageResourceResolver(packageRoot);
        this.packageRoot = resources.packageRoot();
        this.configPath = configPath == null ? Paths.get("config/config.yaml") : configPath;
        Path effectiveConfig = RuntimeConfigPath.resolve(this.configPath, this.packageRoot);
        this.configResources = new PackageResourceResolver(RuntimeConfigPath.root(effectiveConfig));
        if (maxResponseBytes < 1024) throw new IllegalArgumentException("maxResponseBytes must be at least 1024");
        this.maxResponseBytes = maxResponseBytes;
        this.redactionEnabled = redactionEnabled;
        this.includeAllFields = includeAllFields;
        this.sensitivePaths = sensitivePaths == null ? Collections.<String>emptyList() : new ArrayList<String>(sensitivePaths);
        this.sensitiveFieldPatterns = sensitiveFieldPatterns == null ? Collections.<String>emptyList() : new ArrayList<String>(sensitiveFieldPatterns);
    }

    /** Share trusted parsed state, never safe response maps, across daemon requests. */
    public PackageConfigurationInspector withSnapshots(PackageConfigurationSnapshots snapshots, Object context) {
        this.snapshots=snapshots; this.snapshotContext=context; return this;
    }

    public Map<String, Object> inspect(String action, String environment, String otherEnvironment) throws Exception {
        return inspect(action, environment, otherEnvironment, null, 0, 0);
    }

    public Map<String, Object> inspect(String action, String environment, String otherEnvironment,
                                       String section, int offset, int limit) throws Exception {
        return inspect(action, environment, otherEnvironment, section, offset, limit, false);
    }

    public Map<String, Object> inspect(String action, String environment, String otherEnvironment,
                                       String section, int offset, int limit, boolean includeInherited) throws Exception {
        return inspect(action, environment, otherEnvironment, section, offset, limit, includeInherited, false);
    }

    public Map<String, Object> inspect(String action, String environment, String otherEnvironment,
                                       String section, int offset, int limit, boolean includeInherited,
                                       boolean previewGenerated) throws Exception {
        return inspect(action,environment,otherEnvironment,section,offset,limit,includeInherited,previewGenerated,null);
    }

    public Map<String, Object> inspect(String action, String environment, String otherEnvironment,
                                       String section, int offset, int limit, boolean includeInherited,
                                       boolean previewGenerated, String query) throws Exception {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        if (limit < 0 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (query != null && query.length() > 200) throw new IllegalArgumentException("query must contain at most 200 characters");
        if (query != null) for (int i=0;i<query.length();i++)
            if (Character.isISOControl(query.charAt(i))) throw new IllegalArgumentException("query must contain printable characters");
        if ("declared".equals(action)) return bounded(declared());
        if ("effective".equals(action)) return bounded(effective(environment, section, offset, limit, query));
        if ("compare".equals(action)) return bounded(compare(environment, otherEnvironment, section, offset, limit, includeInherited));
        if ("testdata-records".equals(action)) return bounded(testdataRecords(environment, section, offset, limit, previewGenerated));
        throw new IllegalArgumentException("Unsupported configuration inspection action");
    }

    private Map<String,Object> testdataRecords(String environment, String descriptorId, int offset, int limit,
                                               boolean previewGenerated) throws Exception {
        if (descriptorId == null || !descriptorId.matches("[A-Za-z][A-Za-z0-9_-]{0,127}"))
            throw new PackageResourceInspector.ResourceNotFoundException();
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        String safeEnvironment = normalizedEnvironment(environment);
        PackageConfigurationSnapshots.Snapshot cached = configurationSnapshot(safeEnvironment);
        Map<?,?> raw = cached == null ? readConfig() : cached.raw;
        validateReferencedFiles(raw, safeEnvironment);
        Path file = configFile();
        FrameworkConfig config = cached == null ? new FrameworkConfigLoader().loadDiscovery(file, RuntimeConfigPath.root(file), packageRoot, safeEnvironment) : cached.config;
        TestdataDescriptor selected = null;
        String selectedSource = null;
        for (Path path : config.testdataDescriptors()) {
            PackageResourceResolver.PackageResource resolved = resources.fromInternalPath(path, PackageResourceResolver.Kind.FILE);
            if (Files.size(resolved.canonicalPath()) > MAX_DESCRIPTOR_BYTES) throw new ConfigurationLimitException();
            TestdataDescriptor candidate = new TestdataDescriptorLoader(packageRoot).load(resolved.canonicalPath());
            if (descriptorId.equals(candidate.id())) {
                if (selected != null) throw new IllegalArgumentException("Testdata descriptor id is ambiguous in the effective registry");
                selected = candidate;
                selectedSource = resolved.logicalName();
            }
        }
        if (selected == null) throw new PackageResourceInspector.ResourceNotFoundException();
        if (offset > selected.count()) throw new IllegalArgumentException("offset exceeds the current Testdata descriptor");
        int start = offset;
        boolean generated = selected.generated();
        boolean previewRequired = generated && !previewGenerated;
        int end = previewRequired ? start : (int)Math.min(selected.count(), (long)start + limit);
        List<Map<String,Object>> records = new ArrayList<Map<String,Object>>();
        for (int index = start; index < end; index++) {
            Object authored = selected.record(index);
            boolean[] redacted = new boolean[]{false};
            Object safe = projectTestdataValue(authored, "testdata." + descriptorId + ".records[" + index + "]", null, redacted);
            Map<String,Object> record = new LinkedHashMap<String,Object>();
            record.put("index", Integer.valueOf(index));
            Long sequence = selected.sequence(index);
            if (sequence != null) record.put("sequence", sequence);
            record.put("value", safe);
            record.put("redacted", Boolean.valueOf(redacted[0]));
            records.add(record);
        }
        Map<String,Object> result = base("testdata-records", previewRequired ? "preview-required" : "ready", att.Version.TESTDATA_SCHEMA);
        result.put("descriptorId", selected.id());
        result.put("source", selectedSource);
        result.put("environment", config.environment());
        result.put("generated", Boolean.valueOf(generated));
        result.put("previewRequired", Boolean.valueOf(previewRequired));
        result.put("recordCount", Long.valueOf(selected.count()));
        result.put("previewGenerated", Boolean.valueOf(generated && previewGenerated));
        if (generated) {
            boolean[] generationRedacted = new boolean[]{false};
            result.put("generation", projectTestdataValue(selected.generatorDefinition(),
                    "testdata." + descriptorId + ".generation", null, generationRedacted));
            result.put("generationRedacted", Boolean.valueOf(generationRedacted[0]));
        }
        result.put("offset", Integer.valueOf(start));
        result.put("limit", Integer.valueOf(limit));
        result.put("total", Long.valueOf(selected.count()));
        result.put("nextOffset", !previewRequired && end < selected.count() ? Integer.valueOf(end) : null);
        result.put("records", records);
        result.put("redactionEnabled", Boolean.valueOf(redactionEnabled));
        result.put("diagnostics", Collections.emptyList());
        return result;
    }

    private Object projectTestdataValue(Object value, String path, String key, boolean[] redacted) {
        if (key != null && (alwaysHiddenField(key) || redactionEnabled && personalField(key) || configuredSensitivePath(path)
                || configuredSensitiveField(key) || redactionEnabled && freeFormValueField(key))) {
            redacted[0] = true;
            return "[REDACTED]";
        }
        if (value instanceof Map) {
            Map<String,Object> result = new LinkedHashMap<String,Object>();
            for (Map.Entry<?,?> entry : ((Map<?,?>)value).entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                String childPath = path + "." + childKey;
                result.put(childKey, projectTestdataValue(entry.getValue(), childPath, childKey, redacted));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>(); int index = 0;
            for (Object item : (Iterable<?>)value)
                result.add(projectTestdataValue(item, path + "[" + index++ + "]", null, redacted));
            return result;
        }
        if (value instanceof String) {
            String authored = (String)value;
            String safe = scrubTestdataText(authored);
            if (!authored.equals(safe)) redacted[0] = true;
            return safe;
        }
        return value;
    }

    private static boolean alwaysHiddenField(String name) {
        return att.security.SecretProtection.sensitiveName(name);
    }

    private static boolean personalField(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").matches("username|email|phone|address|ssn|nationalid|passport|dateofbirth|birthdate|creditcard|cardnumber|cvv|iban|routingnumber");
    }

    private static boolean freeFormValueField(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.equals("payload") || normalized.equals("value") || normalized.equals("body")
                || normalized.equals("message") || normalized.equals("description");
    }

    private String scrubTestdataText(String value) {
        String result = value.replace(packageRoot.toString(), "[package]");
        result = att.security.SecretProtection.text(result).replace(att.security.SecretProtection.REDACTED, "[REDACTED]");
        return att.core.PathPresentation.displayDiagnosticText(result, packageRoot);
    }

    private Map<String, Object> declared() {
        try {
            Map<?, ?> raw = readConfig();
            Map<String, Object> result = base("declared", "declared", text(raw.get("schemaVersion"), 64));
            result.put("globals", declaredGlobals(raw));
            List<Map<String, Object>> profiles = profiles(raw);
            result.put("environments", profiles);
            result.put("sections", declaredSections(raw, profiles));
            result.put("diagnostics", Collections.emptyList());
            result.put("redactionEnabled", Boolean.valueOf(redactionEnabled));
            return result;
        } catch (ConfigurationLimitException exceeded) {
            throw exceeded;
        } catch (Exception invalid) {
            return invalid("declared", null, "ATT-CONFIG-INVALID", "Package configuration is unavailable or invalid");
        }
    }

    private Map<String, Object> effective(String environment, String section, int offset, int limit) {
        return effective(environment,section,offset,limit,null);
    }

    private Map<String, Object> effective(String environment, String section, int offset, int limit, String query) {
        String safeEnvironment = normalizedEnvironment(environment);
        try {
            PackageConfigurationSnapshots.Snapshot cached = configurationSnapshot(safeEnvironment);
            Map<?, ?> raw = cached == null ? readConfig() : cached.raw;
            validateReferencedFiles(raw, safeEnvironment);
            Path file = configFile();
            FrameworkConfig config = cached == null ? new FrameworkConfigLoader().loadDiscovery(file, RuntimeConfigPath.root(file), packageRoot, safeEnvironment) : cached.config;
            return snapshot(raw, config, "effective", section, offset, limit, query);
        } catch (ConfigurationLimitException exceeded) {
            throw exceeded;
        } catch (Exception invalid) {
            Map<String, Object> result = invalid("effective", safeEnvironment, "ATT-CONFIG-INVALID", "Selected configuration is unavailable or invalid");
            result.put("redactionEnabled", Boolean.valueOf(redactionEnabled));
            return result;
        }
    }

    private Map<String, Object> compare(String leftEnvironment, String rightEnvironment, String selectedSection,
                                        int offset, int limit, boolean includeInherited) {
        if (selectedSection == null || selectedSection.trim().isEmpty())
            throw new IllegalArgumentException("section is required for configuration comparison");
        if (!"globals".equals(selectedSection) && !ALL_SECTIONS.contains(selectedSection))
            throw new IllegalArgumentException("Unknown configuration section");
        String leftName = normalizedEnvironment(leftEnvironment);
        String rightName = normalizedEnvironment(rightEnvironment);
        if (leftName == null || rightName == null) throw new IllegalArgumentException("left and right environments must be valid profile names");
        if (leftName.equalsIgnoreCase(rightName)) throw new IllegalArgumentException("left and right environments must differ");
        Map<String, Object> left = effective(leftName, null, 0, 0);
        Map<String, Object> right = effective(rightName, null, 0, 0);
        Map<String, Object> result = base("compare", "ready", text(left.get("schemaVersion"), 64));
        result.put("leftEnvironment", leftName);
        result.put("rightEnvironment", rightName);
        result.put("section", selectedSection);
        result.put("redactionEnabled", Boolean.valueOf(redactionEnabled));
        result.put("includeInherited", Boolean.valueOf(includeInherited));
        List<Map<String, Object>> diagnostics = new ArrayList<Map<String, Object>>();
        diagnostics.addAll(diagnostics(left));
        diagnostics.addAll(diagnostics(right));
        if ("invalid".equals(left.get("state")) || "invalid".equals(right.get("state"))) {
            result.put("state", "invalid");
            result.put("diagnostics", diagnostics);
            result.put("fields", Collections.emptyList());
            return result;
        }
        result.put("executionReadiness", "not-validated");
        if (!sectionExists(left, selectedSection) && !sectionExists(right, selectedSection))
            throw new IllegalArgumentException("Unknown configuration section");
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> leftFields = flatten(left);
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> rightFields = flatten(right);
        Set<String> paths = new LinkedHashSet<String>();
        paths.addAll(leftFields.keySet());
        paths.addAll(rightFields.keySet());
        String sectionPrefix = "globals".equals(selectedSection) ? "globals." : selectedSection + ".";
        paths.removeIf(path -> !path.startsWith(sectionPrefix));
        List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
        for (String path : paths) {
            Map<String, Object> before = leftFields.get(path);
            Map<String, Object> after = rightFields.get(path);
            String sharedSource = sharedSourceForPath(path, leftFields, rightFields);
            if (!includeInherited && sharedSource != null && sameVisibleField(before, after)) continue;
            if (!includeInherited && !profileScoped(path, before, after, leftFields, rightFields)) continue;
            boolean hidden = hidesForDisplayPolicy(path)
                    || alwaysHiddenField(path.substring(path.lastIndexOf('.') + 1))
                    || configuredSensitivePath(path)
                    || configuredSensitiveField(path.substring(path.lastIndexOf('.') + 1))
                    || before != null && "hidden".equals(before.get("state"))
                    || after != null && "hidden".equals(after.get("state"));
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("path", path);
            if (sharedSource != null) item.put("source", sharedSource);
            if (hidden) {
                Map<String,Object> protectedFields = new LinkedHashMap<String,Object>();
                hidden(protectedFields, path.substring(path.lastIndexOf('.')+1));
                Object protectedField = protectedFields.values().iterator().next();
                item.put("left", protectedField);
                item.put("right", protectedField);
                item.put("change", "hidden");
            } else {
                item.put("left", before == null ? field("missing", null, null, null) : before);
                item.put("right", after == null ? field("missing", null, null, null) : after);
                if (before == null) item.put("change", "added");
                else if (after == null) item.put("change", "removed");
                else if (!ObjectsEqual.value(before.get("value"), after.get("value"))) item.put("change", "changed");
                else if (!ObjectsEqual.value(before.get("origin"), after.get("origin"))) item.put("change", "origin-changed");
                else item.put("change", "same");
            }
            fields.add(item);
        }
        result.put("state", diagnostics.isEmpty() ? "ready" : "degraded");
        if (limit > 0) {
            if (offset > fields.size()) throw new IllegalArgumentException("offset exceeds the current comparison");
            int start = Math.min(offset, fields.size());
            int end = Math.min(fields.size(), start + limit);
            result.put("offset", Integer.valueOf(start));
            result.put("limit", Integer.valueOf(limit));
            result.put("total", Integer.valueOf(fields.size()));
            result.put("nextOffset", end < fields.size() ? Integer.valueOf(end) : null);
            result.put("fields", new ArrayList<Map<String, Object>>(fields.subList(start, end)));
        } else result.put("fields", fields);
        result.put("diagnostics", diagnostics);
        return result;
    }

    private static boolean profileScoped(String path, Map<String,Object> left, Map<String,Object> right,
                                         Map<String,Map<String,Object>> leftFields,
                                         Map<String,Map<String,Object>> rightFields) {
        if ("profile".equals(left == null ? null : left.get("origin"))
                || "profile".equals(right == null ? null : right.get("origin"))) return true;
        int first = path.indexOf('.');
        int second = first < 0 ? -1 : path.indexOf('.', first + 1);
        if (first < 0 || second < 0 || "globals".equals(path.substring(0, first))) return false;
        String entryMarker = path.substring(0, second) + ".$entry";
        Map<String,Object> leftEntry=leftFields.get(entryMarker),rightEntry=rightFields.get(entryMarker);
        return "profile".equals(leftEntry==null?null:leftEntry.get("origin"))
                ||"profile".equals(rightEntry==null?null:rightEntry.get("origin"));
    }

    private static String sharedSourceForPath(String path, Map<String,Map<String,Object>> leftFields,
                                              Map<String,Map<String,Object>> rightFields) {
        Map<String,Object> leftEntry=entryForPath(path,leftFields),rightEntry=entryForPath(path,rightFields);
        Object leftSource=leftEntry==null?null:leftEntry.get("source");
        Object rightSource=rightEntry==null?null:rightEntry.get("source");
        return leftSource instanceof String && leftSource.equals(rightSource) ? (String)leftSource : null;
    }

    private static Map<String,Object> entryForPath(String path, Map<String,Map<String,Object>> fields) {
        Map<String,Object> nearest=null; int longest=-1;
        for (Map.Entry<String,Map<String,Object>> candidate:fields.entrySet()) {
            String marker=candidate.getKey();
            if (!marker.endsWith(".$entry")) continue;
            String prefix=marker.substring(0,marker.length()-".$entry".length());
            if ((path.equals(marker)||path.startsWith(prefix+"."))&&prefix.length()>longest) {
                nearest=candidate.getValue(); longest=prefix.length();
            }
        }
        return nearest;
    }

    private static boolean sameVisibleField(Map<String,Object> left, Map<String,Object> right) {
        return left!=null && right!=null && "visible".equals(left.get("state"))
                && "visible".equals(right.get("state"))
                && ObjectsEqual.value(left.get("value"),right.get("value"));
    }

    private Map<String, Object> snapshot(Map<?, ?> raw, FrameworkConfig config, String view,
                                         String selectedSection, int offset, int limit, String query) throws Exception {
        String environment = config.environment();
        Map<String, Object> result = base(view, "ready", text(raw.get("schemaVersion"), 64));
        result.put("environment", environment);
        result.put("environments", profiles(raw));
        Map<String, Object> globals = new LinkedHashMap<String, Object>();
        String globalOrigin = "global";
        putVisible(globals, "environment", config.environment(), environmentOrigin(raw, environment));
        putVisible(globals, "timeoutMs", Integer.valueOf(config.timeoutMs()), globalOrigin);
        putVisible(globals, "xml.namespaceMode", config.xmlNamespaceMode(), globalOrigin);
        putVisible(globals, "caseLog.yamlAnchors", Boolean.valueOf(config.caseLogYamlAnchors()), globalOrigin);
        putVisible(globals, "processOutput.memoryLimitBytes", Integer.valueOf(config.processOutput().memoryLimitBytes()), globalOrigin);
        putVisible(globals, "processOutput.artifactLimitBytes", Long.valueOf(config.processOutput().artifactLimitBytes()), globalOrigin);
        putVisible(globals, "report.mode", config.report().mode(), globalOrigin);
        putHidden(globals, "outputDirectory");
        putHidden(globals, "templates.root");
        putHidden(globals, "testcase.root");
        putHidden(globals, "report.fileNamePattern");
        result.put("globals", globals);

        List<Map<String, Object>> sections = new ArrayList<Map<String, Object>>();
        String dbOrigin = origin(raw, environment, "dbhelpers");
        sections.add(dbSection(config.dbHelpers(), dbOrigin));
        String mqOrigin = origin(raw, environment, "mqhelpers");
        sections.add(mqSection(config.mqHelpers(), mqOrigin));
        String sshOrigin = origin(raw, environment, "sshhelpers");
        sections.add(sshSection(config.sshHelpers(), sshOrigin));
        String httpOrigin = origin(raw, environment, "httphelpers");
        sections.add(httpSection(config.httpHelpers(), httpOrigin));
        sections.add(toolSection(config.tools()));
        if(!sharedRuntime)sections.add(testdataSection(config, origin(raw, environment, "testdata")));
        if (selectedSection != null) for (Map<String,Object> section : sections) {
            if (!selectedSection.equals(section.get("id"))) continue;
            @SuppressWarnings("unchecked") List<Map<String,Object>> entries=(List<Map<String,Object>>)section.get("entries");
            if (query != null && !query.trim().isEmpty()) entries.removeIf(entry->!entryMatches(entry,query.trim().toLowerCase(Locale.ROOT)));
            if ("testdata".equals(selectedSection)) entries.sort((left,right)->{
                String a=String.valueOf(left.get("id")),b=String.valueOf(right.get("id"));
                int order=String.CASE_INSENSITIVE_ORDER.compare(a,b);return order!=0?order:a.compareTo(b);
            });
            break;
        }
        result.put("redactionEnabled", Boolean.valueOf(redactionEnabled));
        result.put("sections", sections);
        List<Map<String,Object>> readiness = new ArrayList<Map<String,Object>>();
        for (att.config.EnvironmentRequirement requirement : config.environmentRequirements())
            if (readiness.size() < 4) readiness.add(requirement.diagnostic(environment));
        for (att.config.HelperDiscoveryFailure failure : config.helperDiscoveryFailures())
            if (readiness.size() < 4) readiness.add(failure.diagnostic(environment));
        int omitted = config.environmentRequirements().size() + config.helperDiscoveryFailures().size() - readiness.size();
        if (omitted > 0) readiness.add(diagnostic("ATT-CONFIG-DEPENDENCIES-TRUNCATED",
                omitted + " additional dependency requirements or unavailable Helpers; validate the selected execution target for details"));
        result.put("diagnostics", readiness);
        result.put("executionReadiness", "not-validated");
        if (!readiness.isEmpty()) result.put("state", "degraded");
        hideSensitiveFields(result);
        if (limit > 0) {
            Map<String, Object> selected = null;
            int total = "globals".equals(selectedSection) ? globals.size() : 0;
            for (Map<String, Object> section : sections) {
                @SuppressWarnings("unchecked") List<Map<String, Object>> entries = (List<Map<String, Object>>) section.get("entries");
                if (section.get("id").equals(selectedSection)) {
                    selected = section;
                    total = entries.size();
                    if (offset > total) throw new IllegalArgumentException("offset exceeds the current configuration section");
                    int end = Math.min(total, offset + limit);
                    section.put("entries", new ArrayList<Map<String, Object>>(entries.subList(offset, end)));
                } else section.put("entries", Collections.emptyList());
                section.put("entryCount", Integer.valueOf(entries.size()));
            }
            if (selectedSection != null && selected == null && !"globals".equals(selectedSection))
                throw new IllegalArgumentException("Unknown configuration section");
            if ("globals".equals(selectedSection) && offset > total)
                throw new IllegalArgumentException("offset exceeds the global configuration fields");
            result.put("section", selectedSection);
            result.put("offset", Integer.valueOf(offset));
            result.put("limit", Integer.valueOf(limit));
            result.put("total", Integer.valueOf(total));
            result.put("nextOffset", selectedSection != null && offset + limit < total ? Integer.valueOf(offset + limit) : null);
        }
        return result;
    }

    private static boolean entryMatches(Map<?,?> entry, String query) {
        for (String key:Arrays.asList("id","source")) {
            Object value=entry.get(key);
            if (value instanceof String && ((String)value).toLowerCase(Locale.ROOT).contains(query)) return true;
        }
        return false;
    }

    private static boolean sectionExists(Map<String, Object> snapshot, String requested) {
        if ("globals".equals(requested)) return true;
        Object raw = snapshot.get("sections");
        if (!(raw instanceof Iterable)) return false;
        for (Object item : (Iterable<?>) raw) if (requested.equals(object(item).get("id"))) return true;
        return false;
    }

    private void hideSensitiveFields(Map<String, Object> snapshot) {
        Object rawGlobals = snapshot.get("globals");
        if (rawGlobals instanceof Map) for (Map.Entry<?, ?> field : ((Map<?, ?>) rawGlobals).entrySet())
            hideField(field.getValue(), "globals." + field.getKey());
        Object rawSections = snapshot.get("sections");
        if (!(rawSections instanceof Iterable)) return;
        for (Object rawSection : (Iterable<?>) rawSections) {
            Map<?, ?> section = object(rawSection);
            String sectionId = String.valueOf(section.get("id"));
            Object rawEntries = section.get("entries");
            if (!(rawEntries instanceof Iterable)) continue;
            for (Object rawEntry : (Iterable<?>) rawEntries) {
                Map<?, ?> entry = object(rawEntry);
                String prefix = sectionId + "." + entry.get("id");
                Object rawFields = entry.get("fields");
                if (rawFields instanceof Map) for (Map.Entry<?, ?> field : ((Map<?, ?>) rawFields).entrySet())
                    hideField(field.getValue(), prefix + "." + field.getKey());
                Object instances = entry.get("instances");
                if (instances instanceof Iterable) for (Object rawInstance : (Iterable<?>)instances) {
                    Map<?,?> instance = object(rawInstance);
                    Object instanceFields = instance.get("fields");
                    if (instanceFields instanceof Map) for (Map.Entry<?,?> field : ((Map<?,?>)instanceFields).entrySet())
                        hideField(field.getValue(), prefix + ".instances." + instance.get("id") + "." + field.getKey());
                }
                Object rawArguments = entry.get("arguments");
                if (rawArguments instanceof Iterable) for (Object rawArgument : (Iterable<?>) rawArguments) {
                    Map<?, ?> argument = object(rawArgument);
                    Object argumentFields = argument.get("fields");
                    if (argumentFields instanceof Map) for (Map.Entry<?, ?> field : ((Map<?, ?>) argumentFields).entrySet())
                        hideField(field.getValue(), prefix + ".arguments." + argument.get("id") + "." + field.getKey());
                }
            }
        }
    }

    private void hideField(Object rawField, String path) {
        if (!(rawField instanceof Map)) return;
        @SuppressWarnings("unchecked") Map<String, Object> field = (Map<String, Object>) rawField;
        if ("visible".equals(field.get("state")) && (hidesForDisplayPolicy(path)
                || alwaysHiddenField(path.substring(path.lastIndexOf('.') + 1))
                || configuredSensitivePath(path) || configuredSensitiveField(path.substring(path.lastIndexOf('.') + 1)))) {
            boolean mandatory = alwaysHiddenField(path.substring(path.lastIndexOf('.') + 1));
            field.clear(); field.put("state", "hidden");
            field.put("value", mandatory ? "[REDACTED]" : "[HIDDEN]");
            field.put("reasonCode", mandatory ? "MANDATORY_SECRET" : "OPTIONAL_FIELD_POLICY");
        }
    }

    private boolean hidesForDisplayPolicy(String path) {
        return redactionEnabled && (isSensitivePath(path) || personalField(path.substring(path.lastIndexOf('.') + 1))) && !includeAllFields;
    }

    private boolean configuredSensitivePath(String path) {
        if (!redactionEnabled) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        for (String configured : sensitivePaths) {
            String pattern = configured.toLowerCase(Locale.ROOT);
            if (pattern.equals(lower)) return true;
            if (pattern.indexOf('*') >= 0) {
                String regex = java.util.regex.Pattern.quote(pattern).replace("\\Q*\\E", ".*");
                if (lower.matches(regex)) return true;
            }
        }
        return false;
    }

    private boolean configuredSensitiveField(String fieldName) {
        if (!redactionEnabled) return false;
        for (String expression : sensitiveFieldPatterns) {
            try { if (Pattern.compile(expression, Pattern.CASE_INSENSITIVE).matcher(fieldName).matches()) return true; }
            catch (RuntimeException ignored) { /* Configuration parsing validates patterns. */ }
        }
        return false;
    }

    private Map<String, Object> declaredGlobals(Map<?, ?> raw) {
        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        declaredField(fields, "schemaVersion", raw.get("schemaVersion"), "global");
        declaredField(fields, "environment", raw.get("environment"), "global");
        declaredField(fields, "timeoutMs", raw.get("timeoutMs"), "global");
        Object xml = raw.get("xml");
        declaredField(fields, "xml.namespaceMode", child(xml, "namespaceMode"), "global");
        Object caseLog = raw.get("caseLog");
        declaredField(fields, "caseLog.yamlAnchors", child(caseLog, "yamlAnchors"), "global");
        Object execution = raw.get("execution");
        Object processOutput = child(execution, "processOutput");
        declaredField(fields, "processOutput.memoryLimitBytes", child(processOutput, "memoryLimitBytes"), "global");
        declaredField(fields, "processOutput.artifactLimitBytes", child(processOutput, "artifactLimitBytes"), "global");
        return fields;
    }

    private List<Map<String, Object>> profiles(Map<?, ?> raw) {
        Object value = raw.get("environments");
        if (!(value instanceof Map)) return Collections.emptyList();
        Object defaultValue = raw.get("environment");
        String defaultName = defaultValue instanceof String ? (String) defaultValue : null;
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (!(entry.getKey() instanceof String)) continue;
            String authored = (String) entry.getKey();
            boolean disabled = authored.startsWith("x-");
            String name = disabled ? authored.substring(2) : authored;
            if (!ENVIRONMENT.matcher(name).matches()) continue;
            Map<String, Object> profile = new LinkedHashMap<String, Object>();
            profile.put("name", name);
            profile.put("state", disabled ? "disabled" : "active");
            profile.put("default", Boolean.valueOf(!disabled && defaultName != null && name.equalsIgnoreCase(defaultName)));
            result.add(profile);
        }
        return result;
    }

    private List<Map<String, Object>> declaredSections(Map<?, ?> raw, List<Map<String, Object>> profiles) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        Object environments = raw.get("environments");
        Map<?, ?> profileMap = environments instanceof Map ? (Map<?, ?>) environments : Collections.emptyMap();
        for (String id : ALL_SECTIONS) {
            Map<String, Object> section = new LinkedHashMap<String, Object>();
            section.put("id", id);
            section.put("title", title(id));
            section.put("root", declaration(raw.get(id), raw.containsKey(id), id));
            List<Map<String, Object>> overrides = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> profile : profiles) {
                String name = String.valueOf(profile.get("name"));
                Map<?, ?> profileValue = profileMap(profileMap.get(profileKey(profileMap, name)));
                boolean disabled = "disabled".equals(profile.get("state"));
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("environment", name);
                if (disabled) item.put("state", "disabled");
                else if (profileValue != null && profileValue.containsKey(id)) {
                    item.put("state", "overridden");
                    item.put("entryCount", Integer.valueOf(count(profileValue.get(id), id)));
                } else item.put("state", "inherited");
                overrides.add(item);
            }
            section.put("profiles", overrides);
            result.add(section);
        }
        return result;
    }

    private static Map<String, Object> declaration(Object value, boolean declared, String id) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("state", declared ? "declared" : "absent");
        result.put("entryCount", Integer.valueOf(declared ? count(value, id) : 0));
        return result;
    }

    private Map<String, Object> dbSection(Map<String, DbHelperConfig> helpers, String source) throws Exception {
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        for (DbHelperConfig helper : helpers.values()) {
            Map<String, Object> entry = entry(helper.id(), source);
            Map<String, Object> fields = fields(entry);
            visible(fields, "driverClass", JAVA_CLASS.matcher(nullToEmpty(helper.driverClass())).matches() ? helper.driverClass() : "unavailable", source);
            visible(fields, "readOnly", Boolean.valueOf(helper.readOnly()), source);
            visible(fields, "isolation", safeEnum(helper.isolation(), "driverDefault", "readUncommitted", "readCommitted", "repeatableRead", "serializable"), source);
            visible(fields, "timeoutSeconds", Integer.valueOf(helper.timeoutSeconds()), source);
            visible(fields, "transaction.scope", safeEnum(helper.transactionScope(), "case", "statement"), source);
            visible(fields, "transaction.onEnd", safeEnum(helper.transactionOnEnd(), "commit", "rollback"), source);
            visible(fields, "result.maxRows", Integer.valueOf(helper.maxRows()), source);
            visible(fields, "result.maxCellBytes", Integer.valueOf(helper.maxCellBytes()), source);
            visible(fields, "result.maxBytes", Integer.valueOf(helper.maxBytes()), source);
            visible(fields, "pool.maxSize", Integer.valueOf(helper.poolMaxSize()), source);
            visible(fields, "pool.minIdle", Integer.valueOf(helper.poolMinIdle()), source);
            visible(fields, "pool.connectionTimeoutMs", Long.valueOf(helper.poolConnectionTimeoutMs()), source);
            visible(fields, "url", scrubTestdataText(helper.url()), source);
            if (includeAllFields || !redactionEnabled) {
                Map<String,Object> safeProperties = new LinkedHashMap<String,Object>();
                for (Map.Entry<String,String> property : helper.properties().entrySet()) {
                    String propertyPath = "dbhelpers." + helper.id() + ".properties." + property.getKey();
                    safeProperties.put(property.getKey(), alwaysHiddenField(property.getKey())
                            || configuredSensitivePath(propertyPath) || configuredSensitiveField(property.getKey())
                            ? "[REDACTED]" : scrubTestdataText(property.getValue()));
                }
                visible(fields, "properties", safeProperties, source);
                visible(fields, "evidence.sql", scrubTestdataText(helper.evidenceSql()), source);
                visible(fields, "evidence.parameters", scrubTestdataText(helper.evidenceParameters()), source);
            } else {
                for (String hidden : Arrays.asList("properties", "evidence.sql", "evidence.parameters")) hidden(fields, hidden);
            }
            visible(fields, "username", helper.username(), source);
            hidden(fields, "password");
            logicalSource(entry, helper.sourceFile());
            entries.add(entry);
        }
        return section("dbhelpers", "DB helpers", source, entries);
    }

    private Map<String, Object> mqSection(Map<String, MqHelperConfig> helpers, String source) throws Exception {
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        for (MqHelperConfig helper : helpers.values()) {
            Map<String, Object> entry = entry(helper.logicalId(), source);
            Map<String, Object> fields = fields(entry);
            visible(fields, "isGroup", Boolean.valueOf(helper.isGroup()), source);
            visible(fields, "selectionStrategy", safeEnum(helper.selectionStrategy(), "single", "roundRobin", "random"), source);
            visible(fields, "charset", Integer.valueOf(helper.charset()), source);
            visible(fields, "encoding", helper.encoding(), source);
            visible(fields, "expiry", helper.expiry(), source);
            visible(fields, "format", safeEnum(helper.format(), "", "text", "binary", "MQSTR", "MQHRF2"), source);
            visible(fields, "persistence", safeEnum(helper.persistence(), "", "persistent", "nonPersistent"), source);
            visible(fields, "responseFormat", safeEnum(helper.responseFormat(), "auto", "text", "json", "yaml", "xml", "binary"), source);
            visible(fields, "requestReplyWaitMs", Integer.valueOf(helper.requestReplyWaitMs()), source);
            visible(fields, "maxResponseBytes", Integer.valueOf(helper.maxResponseBytes()), source);
            visible(fields, "pool.maxSize", Integer.valueOf(helper.poolMaxSize()), source);
            visible(fields, "pool.minIdle", Integer.valueOf(helper.poolMinIdle()), source);
            visible(fields, "pool.borrowTimeoutMs", Long.valueOf(helper.poolBorrowTimeoutMs()), source);
            visible(fields, "queueManager", helper.queueManager(), source);
            visible(fields, "host", helper.host(), source);
            visible(fields, "port", Integer.valueOf(helper.port()), source);
            visible(fields, "channel", helper.channel(), source);
            visible(fields, "requestQueue", helper.requestQueue(), source);
            visible(fields, "requestQueues", helper.requestQueues(), source);
            visible(fields, "requestQueueSelectionStrategy", helper.requestQueueSelectionStrategy(), source);
            visible(fields, "replyQueue", helper.replyQueue(), source);
            visible(fields, "username", helper.username(), source);
            hidden(fields, "password");
            if (redactionEnabled && !includeAllFields) hidden(fields, "evidencePayload");
            else visible(fields, "evidencePayload", helper.evidencePayload(), source);
            List<Map<String, Object>> instances = new ArrayList<Map<String, Object>>();
            for (Map.Entry<String, MqHelperConfig> instance : helper.instances().entrySet()) {
                Map<String, Object> child = entry(instance.getKey(), source);
                Map<String, Object> childFields = fields(child);
                visible(childFields, "queueManager", instance.getValue().queueManager(), source);
                visible(childFields, "host", instance.getValue().host(), source);
                visible(childFields, "port", Integer.valueOf(instance.getValue().port()), source);
                visible(childFields, "channel", instance.getValue().channel(), source);
                visible(childFields, "requestQueue", instance.getValue().requestQueue(), source);
                visible(childFields, "requestQueues", instance.getValue().requestQueues(), source);
                visible(childFields, "requestQueueSelectionStrategy", instance.getValue().requestQueueSelectionStrategy(), source);
                visible(childFields, "replyQueue", instance.getValue().replyQueue(), source);
                visible(childFields, "username", instance.getValue().username(), source);
                hidden(childFields, "password");
                instances.add(child);
            }
            entry.put("instances", instances);
            logicalSource(entry, helper.sourceFile());
            entries.add(entry);
        }
        return section("mqhelpers", "MQ helpers", source, entries);
    }

    private Map<String, Object> sshSection(Map<String, SshHelperConfig> helpers, String source) {
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        for (SshHelperConfig helper : helpers.values()) {
            Map<String, Object> entry = entry(helper.id(), source);
            Map<String, Object> fields = fields(entry);
            visible(fields, "strategy", safeEnum(helper.strategy(), "single", "roundRobin", "random", "fanout"), source);
            visible(fields, "maxConcurrency", Integer.valueOf(helper.maxConcurrency()), source);
            visible(fields, "connectTimeoutMs", Integer.valueOf(helper.connectTimeoutMs()), source);
            visible(fields, "commandTimeoutMs", Integer.valueOf(helper.commandTimeoutMs()), source);
            List<Map<String, Object>> instances = new ArrayList<Map<String, Object>>();
            for (Map.Entry<String, SshConfig> instance : helper.instances().entrySet()) {
                Map<String, Object> child = entry(instance.getKey(), source);
                Map<String, Object> childFields = fields(child);
                visible(childFields, "host", instance.getValue().host(), source);
                visible(childFields, "user", instance.getValue().user(), source);
                visible(childFields, "port", Integer.valueOf(instance.getValue().port()), source);
                hidden(childFields, "identityFile");
                instances.add(child);
            }
            entry.put("instances", instances);
            entries.add(entry);
        }
        return section("sshhelpers", "SSH helpers", source, entries);
    }

    private Map<String, Object> httpSection(Map<String, HttpHelperConfig> helpers, String source) throws Exception {
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        for (HttpHelperConfig helper : helpers.values()) {
            Map<String, Object> entry = entry(helper.id(), source);
            Map<String, Object> fields = fields(entry);
            visible(fields, "authMode", safeEnum(helper.authType(), "", "none", "basic", "bearer", "token"), source);
            visible(fields, "connectTimeoutMs", Integer.valueOf(helper.connectTimeoutMs()), source);
            visible(fields, "readTimeoutMs", Integer.valueOf(helper.readTimeoutMs()), source);
            visible(fields, "responseFormat", safeEnum(helper.responseFormat(), "auto", "text", "json", "yaml", "xml", "binary"), source);
            visible(fields, "maxResponseBytes", Integer.valueOf(helper.maxResponseBytes()), source);
            visible(fields, "followRedirects", Boolean.valueOf(helper.followRedirects()), source);
            visible(fields, "maxConnections", Integer.valueOf(helper.maxConnections()), source);
            visible(fields, "maxConnectionsPerRoute", Integer.valueOf(helper.maxConnectionsPerRoute()), source);
            visible(fields, "connectionRequestTimeoutMs", Integer.valueOf(helper.connectionRequestTimeoutMs()), source);
            visible(fields, "keepAliveMs", Integer.valueOf(helper.keepAliveMs()), source);
            visible(fields, "idleEvictMs", Integer.valueOf(helper.idleEvictMs()), source);
            visible(fields, "baseUrl", scrubTestdataText(helper.baseUrl().toString()), source);
            visible(fields, "headers", att.security.SecretProtection.value(helper.headers()), source);
            visible(fields, "username", helper.username(), source);
            for (String hidden : Arrays.asList("password", "token", "trustStore", "trustStorePassword")) hidden(fields, hidden);
            entries.add(entry);
        }
        return section("httphelpers", "HTTP helpers", source, entries);
    }

    private Map<String, Object> toolSection(Map<String, ToolConfig> tools) {
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, ToolConfig> toolEntry : tools.entrySet()) {
            ToolConfig tool = toolEntry.getValue();
            Map<String, Object> entry = entry(toolEntry.getKey(), "global");
            Map<String, Object> fields = fields(entry);
            visible(fields, "groupId", tool.grouped() ? safeIdentifier(tool.groupId()) : "", "global");
            visible(fields, "stdoutFormat", safeEnum(tool.stdoutFormat(), "", "text", "json", "yaml", "xml"), "global");
            visible(fields, "commandBacked", Boolean.valueOf(tool.commandBacked()), "global");
            if (tool.timeoutMs() != null) visible(fields, "timeoutMs", tool.timeoutMs(), "global");
            List<Map<String, Object>> arguments = new ArrayList<Map<String, Object>>();
            for (Map.Entry<String, ToolArgumentConfig> argumentEntry : tool.arguments().entrySet()) {
                ToolArgumentConfig argument = argumentEntry.getValue();
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", safeIdentifier(argumentEntry.getKey()));
                Map<String, Object> argumentFields = new LinkedHashMap<String, Object>();
                visible(argumentFields, "type", safeEnum(argument.type(), "any", "string", "number", "integer", "boolean", "object", "array"), "global");
                visible(argumentFields, "required", Boolean.valueOf(argument.required()), "global");
                visible(argumentFields, "multiValue", Boolean.valueOf(argument.multiValue()), "global");
                visible(argumentFields, "namedArgv", Boolean.valueOf(argument.namedArgv()), "global");
                visible(argumentFields, "repeatArgName", Boolean.valueOf(argument.repeatArgName()), "global");
                item.put("fields", argumentFields);
                arguments.add(item);
            }
            entry.put("arguments", arguments);
            entries.add(entry);
        }
        return section("tools", "Tools and Tool groups", "global", entries);
    }

    private Map<String, Object> testdataSection(FrameworkConfig config, String source) throws Exception {
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        TestdataDescriptorLoader loader = new TestdataDescriptorLoader(packageRoot);
        for (Path path : config.testdataDescriptors()) {
            PackageResourceResolver.PackageResource resource = resources.fromInternalPath(path, PackageResourceResolver.Kind.FILE);
            if (Files.size(resource.canonicalPath()) > MAX_DESCRIPTOR_BYTES) throw new ConfigurationLimitException();
            TestdataDescriptor descriptor = loader.load(resource.canonicalPath());
            Map<String, Object> entry = entry(descriptor.id(), source);
            entry.put("source", resource.logicalName());
            Map<String, Object> fields = fields(entry);
            visible(fields, "schemaVersion", att.Version.TESTDATA_SCHEMA, source);
            visible(fields, "generated", Boolean.valueOf(descriptor.generated()), source);
            visible(fields, "recordCount", Long.valueOf(descriptor.count()), source);
            if (descriptor.selection() != null) {
                visible(fields, "selection.strategy", descriptor.selection().strategy(), source);
                visible(fields, "selection.exhaustion", descriptor.selection().exhaustion(), source);
            } else {
                missing(fields, "selection.strategy", source);
                missing(fields, "selection.exhaustion", source);
            }
            visible(fields, "recordCount", Long.valueOf(descriptor.count()), source);
            hidden(fields, "records");
            entries.add(entry);
        }
        return section("testdata", "Testdata descriptors", source, entries);
    }

    private PackageConfigurationSnapshots.Snapshot configurationSnapshot(String environment) throws Exception {
        if(snapshots == null)return null;
        if(!configFile().startsWith(packageRoot))return null;
        try { return snapshots.get(packageRoot,configPath,environment,snapshotContext,true,sharedRuntime); }
        catch(Exception unavailable) { return null; } // Cache failures preserve the original uncached validation path.
    }

    private Map<?, ?> readConfig() throws Exception {
        if(snapshots != null&&configFile().startsWith(packageRoot))try { return snapshots.get(packageRoot,configPath,null,snapshotContext,false).raw; }
        catch(Exception unavailable) { /* Read and validate using the uncached path below. */ }
        PackageResourceResolver.PackageResource resource = configResources.fromInternalPath(configFile(), PackageResourceResolver.Kind.FILE);
        if (Files.size(resource.canonicalPath()) > MAX_CONFIG_BYTES) throw new ConfigurationLimitException();
        long started=System.nanoTime();
        Object loaded = YamlSupport.load(resource.canonicalPath());
        att.metrics.InspectionMetrics.JVM.record(att.metrics.InspectionMetrics.Stage.CONFIG_PARSE,System.nanoTime()-started);
        if (!(loaded instanceof Map)) throw new IllegalArgumentException("Configuration must be a YAML mapping");
        return (Map<?, ?>) loaded;
    }

    private Path configFile() throws Exception {
        Path effective = RuntimeConfigPath.resolve(configPath, packageRoot);
        return configResources.fromInternalPath(effective, PackageResourceResolver.Kind.FILE).canonicalPath();
    }

    private void validateReferencedFiles(Map<?, ?> raw, String environment) throws Exception {
        long totalBytes = 0;
        int count = 0;
        Map<?, ?> profile = profileMap(raw, environment);
        for (String key : Arrays.asList("toolGroups")) {
            List<?> values = listOrEmpty(raw.get(key));
            for (Object value : values) {
                if (!(value instanceof String)) continue;
                totalBytes = checkDescriptorLimit(++count, totalBytes, String.valueOf(value), true);
            }
        }
        for (String key : RESOURCE_LISTS) {
            if(sharedRuntime&&"testdata".equals(key))continue;
            Object configured = profile != null && profile.containsKey(key) ? profile.get(key) : raw.get(key);
            for (Object value : listOrEmpty(configured)) {
                if (!(value instanceof String)) continue;
                if (!"testdata".equals(key) && !Files.exists(configResources.internalCandidate(
                        RuntimeConfigPath.root(configFile()).resolve(String.valueOf(value))), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (++count > MAX_REFERENCED_FILES) throw new ConfigurationLimitException();
                    continue; // The discovery loader still validates the path and reports unavailable Helpers.
                }
                totalBytes = checkDescriptorLimit(++count, totalBytes, String.valueOf(value), !"testdata".equals(key));
            }
        }
    }

    private long checkDescriptorLimit(int count, long totalBytes, String authored, boolean central) throws Exception {
        if (count > MAX_REFERENCED_FILES) throw new ConfigurationLimitException();
        long nextBytes = totalBytes + checkDescriptor(authored, central);
        if (nextBytes > MAX_TOTAL_DESCRIPTOR_BYTES) throw new ConfigurationLimitException();
        return nextBytes;
    }

    private long checkDescriptor(String authored, boolean central) throws Exception {
        PackageResourceResolver resolver = central ? configResources : resources;
        Path base = central ? RuntimeConfigPath.root(RuntimeConfigPath.resolve(configPath, packageRoot)) : packageRoot;
        PackageResourceResolver.PackageResource resource = resolver.resolvePackageRelative(authored, PackageResourceResolver.Kind.FILE);
        Path logical = base.resolve(authored.replace('\\', '/')).normalize();
        if (central && !logical.startsWith(base.resolve("config")))
            throw new IllegalArgumentException("Shared descriptor escaped config/");
        if (Files.isSymbolicLink(logical)) throw new IllegalArgumentException("Package configuration descriptor is invalid");
        long size = Files.size(resource.canonicalPath());
        if (size > MAX_DESCRIPTOR_BYTES) throw new ConfigurationLimitException();
        return size;
    }

    private static Map<?, ?> profileMap(Map<?, ?> raw, String environment) {
        Object profiles = raw.get("environments");
        if (!(profiles instanceof Map)) return null;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) profiles).entrySet()) {
            if (entry.getKey() instanceof String && ((String) entry.getKey()).equalsIgnoreCase(environment)
                    && entry.getValue() instanceof Map) return (Map<?, ?>) entry.getValue();
        }
        return null;
    }

    private static String origin(Map<?, ?> raw, String environment, String key) {
        Map<?, ?> profile = profileMap(raw, environment);
        if (profile != null && profile.containsKey(key)) return "profile";
        if (raw.containsKey(key)) return "global";
        return "default";
    }

    private static Map<String, Map<String, Object>> flatten(Map<String, Object> snapshot) {
        Map<String, Map<String, Object>> result = new TreeMap<String, Map<String, Object>>();
        flattenFields(result, "globals", object(snapshot.get("globals")));
        Object sections = snapshot.get("sections");
        if (sections instanceof Iterable) for (Object sectionValue : (Iterable<?>) sections) {
            Map<?, ?> section = object(sectionValue);
            String sectionId = String.valueOf(section.get("id"));
            Object entries = section.get("entries");
            if (entries instanceof Iterable) for (Object entryValue : (Iterable<?>) entries) flattenEntry(result, sectionId, object(entryValue));
        }
        return result;
    }

    private static void flattenEntry(Map<String, Map<String, Object>> target, String prefix, Map<?, ?> entry) {
        flattenEntry(target,prefix,entry,null);
    }

    private static void flattenEntry(Map<String, Map<String, Object>> target, String prefix, Map<?, ?> entry,
                                     String inheritedSource) {
        String id = String.valueOf(entry.get("id"));
        String path = prefix + "." + id;
        String source=entry.get("source") instanceof String ? (String)entry.get("source") : inheritedSource;
        Map<String,Object> marker=field("visible", Boolean.TRUE, entry.get("origin"), "visible");
        if(source!=null)marker.put("source",source);
        target.put(path + ".$entry", marker);
        flattenFields(target, path, object(entry.get("fields")));
        Object arguments = entry.get("arguments");
        if (arguments instanceof Iterable) for (Object argumentValue : (Iterable<?>) arguments) {
            Map<?, ?> argument = object(argumentValue);
            flattenFields(target, path + ".arguments." + argument.get("id"), object(argument.get("fields")));
        }
        Object instances = entry.get("instances");
        if (instances instanceof Iterable) for (Object instanceValue : (Iterable<?>) instances) flattenEntry(target, path + ".instances", object(instanceValue), source);
    }

    private static void flattenFields(Map<String, Map<String, Object>> target, String prefix, Map<?, ?> fields) {
        for (Map.Entry<?, ?> item : fields.entrySet()) {
            Map<?, ?> value = object(item.getValue());
            target.put(prefix + "." + item.getKey(), copyField(value));
        }
    }

    private static Map<String, Object> copyField(Map<?, ?> field) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : field.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }

    private static boolean isSensitivePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".url") || lower.endsWith(".baseurl") || lower.endsWith(".headers")
                || lower.endsWith(".username") || lower.endsWith(".password") || lower.endsWith(".token")
                || lower.endsWith(".truststore") || lower.endsWith(".truststorepassword")
                || lower.endsWith(".properties") || lower.endsWith(".evidence.sql")
                || lower.endsWith(".evidence.parameters") || lower.endsWith(".evidencepayload")
                || lower.endsWith(".host") || lower.endsWith(".port") || lower.endsWith(".queuemanager")
                || lower.endsWith(".channel") || lower.endsWith(".requestqueue") || lower.endsWith(".replyqueue")
                || lower.endsWith(".identityfile") || lower.endsWith(".user") || lower.endsWith(".records");
    }

    private static Map<String, Object> base(String view, String state, String schemaVersion) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("view", view);
        result.put("state", state);
        if (schemaVersion != null) result.put("schemaVersion", schemaVersion);
        return result;
    }

    private static Map<String, Object> invalid(String view, String environment, String code, String summary) {
        Map<String, Object> result = base(view, "invalid", null);
        if (environment != null) result.put("environment", environment);
        result.put("globals", Collections.emptyMap());
        result.put("sections", Collections.emptyList());
        result.put("diagnostics", Collections.singletonList(diagnostic(code, summary)));
        return result;
    }

    private static List<Map<String, Object>> diagnostics(Map<String, Object> response) {
        Object value = response.get("diagnostics");
        if (!(value instanceof List)) return Collections.emptyList();
        @SuppressWarnings("unchecked") List<Map<String, Object>> result = (List<Map<String, Object>>) value;
        return result;
    }

    private static Map<String, Object> diagnostic(String code, String summary) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", code);
        result.put("summary", summary);
        return result;
    }

    private Map<String, Object> bounded(Map<String, Object> response) {
        byte[] bytes = new Yaml().dump(response).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxResponseBytes) throw new ResponseTooLargeException();
        return response;
    }

    private static Map<String, Object> section(String id, String title, String origin, List<Map<String, Object>> entries) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", id); result.put("title", title); result.put("origin", origin); result.put("entries", entries);
        return result;
    }

    private static Map<String, Object> entry(String id, String origin) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", safeIdentifier(id)); result.put("origin", origin); result.put("fields", new LinkedHashMap<String, Object>());
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(Map<String, Object> entry) { return (Map<String, Object>) entry.get("fields"); }

    private void logicalSource(Map<String, Object> entry, Path path) throws Exception {
        if (path == null) return;
        Path canonical=path.toRealPath();
        PackageResourceResolver resolver=canonical.startsWith(packageRoot)?resources:configResources;
        PackageResourceResolver.PackageResource resource = resolver.fromInternalPath(canonical, PackageResourceResolver.Kind.FILE);
        entry.put("source", resource.logicalName());
    }

    private static void visible(Map<String, Object> fields, String key, Object value, String origin) {
        fields.put(key, field("visible", value, origin, null));
    }

    private static void missing(Map<String, Object> fields, String key, String origin) {
        fields.put(key, field("missing", null, origin, null));
    }

    private static void hidden(Map<String, Object> fields, String key) {
        Map<String,Object> item = field("hidden", null, null, null);
        boolean mandatory = alwaysHiddenField(key);
        item.put("value", mandatory ? "[REDACTED]" : "[HIDDEN]");
        item.put("reasonCode", mandatory ? "MANDATORY_SECRET" : "UNAVAILABLE_BY_POLICY");
        fields.put(key, item);
    }

    private static void putVisible(Map<String, Object> fields, String key, Object value, String origin) {
        fields.put(key, field("visible", value, origin, null));
    }

    private static void putHidden(Map<String, Object> fields, String key) { hidden(fields,key); }

    private static Map<String, Object> field(String state, Object value, Object origin, String change) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("state", state);
        if ("visible".equals(state) && value != null) result.put("value", value);
        if (origin != null) result.put("origin", origin);
        if (change != null) result.put("change", change);
        return result;
    }

    private static void declaredField(Map<String, Object> target, String key, Object value, String origin) {
        if (value == null) target.put(key, field("missing", null, origin, null));
        else if (value instanceof String && ((String) value).length() <= 256) target.put(key, field("visible", value, origin, null));
        else if (value instanceof Number || value instanceof Boolean) target.put(key, field("visible", value, origin, null));
        else target.put(key, field("unavailable", null, null, null));
    }

    private static String normalizedEnvironment(String environment) {
        if (environment == null || environment.trim().isEmpty()) return null;
        if (!ENVIRONMENT.matcher(environment.trim()).matches())
            throw new IllegalArgumentException("environment must be a valid profile name");
        return environment.trim();
    }

    private static String environmentOrigin(Map<?, ?> raw, String selected) {
        Object profiles = raw.get("environments");
        if (!(profiles instanceof Map) || selected == null) return "global";
        for (Object key : ((Map<?, ?>) profiles).keySet())
            if (selected.equalsIgnoreCase(String.valueOf(key)) && !String.valueOf(key).startsWith("x-")) return "profile";
        return "global";
    }

    private static String safeEnum(String value, String... allowed) {
        if (value == null) return "";
        for (String candidate : allowed) if (candidate.equals(value)) return value;
        return "unavailable";
    }

    private static String safeIdentifier(String value) {
        return value != null && value.length() <= 128 && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*") ? value : "unavailable";
    }

    private static String title(String id) {
        if ("dbhelpers".equals(id)) return "DB helpers";
        if ("mqhelpers".equals(id)) return "MQ helpers";
        if ("sshhelpers".equals(id)) return "SSH helpers";
        if ("httphelpers".equals(id)) return "HTTP helpers";
        if ("toolGroups".equals(id)) return "Tool groups";
        if ("testdata".equals(id)) return "Testdata descriptors";
        return "Tools";
    }

    private static int count(Object value, String id) {
        if (value instanceof List) return ((List<?>) value).size();
        if (value instanceof Map) {
            int count = 0;
            for (Object key : ((Map<?, ?>) value).keySet()) if (!(key instanceof String) || !((String) key).startsWith("x-")) count++;
            return count;
        }
        return value == null ? 0 : 0;
    }

    private static List<?> listOrEmpty(Object value) { return value instanceof List ? (List<?>) value : Collections.emptyList(); }

    private static Object child(Object value, String key) { return value instanceof Map ? ((Map<?, ?>) value).get(key) : null; }

    private static String text(Object value, int max) {
        if (!(value instanceof String)) return null;
        String result = (String) value;
        return result.length() <= max ? result : null;
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private static Map<?, ?> profileMap(Object value) { return value instanceof Map ? (Map<?, ?>) value : null; }

    private static String profileKey(Map<?, ?> profiles, String name) {
        for (Object key : profiles.keySet()) if (key instanceof String && ((String) key).equalsIgnoreCase(name)) return (String) key;
        return name;
    }

    private static Map<?, ?> object(Object value) { return value instanceof Map ? (Map<?, ?>) value : Collections.emptyMap(); }

    public static final class ResponseTooLargeException extends RuntimeException { }
    public static final class ConfigurationLimitException extends RuntimeException { }

    private static final class ObjectsEqual {
        private static boolean value(Object left, Object right) { return left == null ? right == null : left.equals(right); }
    }
}
