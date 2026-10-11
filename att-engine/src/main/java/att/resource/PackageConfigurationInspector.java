package att.resource;

import att.config.DbHelperConfig;
import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
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
    private static final List<String> RESOURCE_LISTS = Arrays.asList(
            "dbhelpers", "mqhelpers", "sshhelpers", "httphelpers", "testdata");
    private static final List<String> ALL_SECTIONS = Arrays.asList(
            "dbhelpers", "mqhelpers", "sshhelpers", "httphelpers", "toolGroups", "tools", "testdata");

    private final Path packageRoot;
    private final Path configPath;
    private final PackageResourceResolver resources;
    private final int maxResponseBytes;

    public PackageConfigurationInspector(Path packageRoot, Path configPath, int maxResponseBytes) {
        this.resources = new PackageResourceResolver(packageRoot);
        this.packageRoot = resources.packageRoot();
        this.configPath = configPath == null ? Paths.get("config/config.yaml") : configPath;
        if (maxResponseBytes < 1024) throw new IllegalArgumentException("maxResponseBytes must be at least 1024");
        this.maxResponseBytes = maxResponseBytes;
    }

    public Map<String, Object> inspect(String action, String environment, String otherEnvironment) throws Exception {
        return inspect(action, environment, otherEnvironment, null, 0, 0);
    }

    public Map<String, Object> inspect(String action, String environment, String otherEnvironment,
                                       String section, int offset, int limit) throws Exception {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        if (limit < 0 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
        if ("declared".equals(action)) return bounded(declared());
        if ("effective".equals(action)) return bounded(effective(environment, section, offset, limit));
        if ("compare".equals(action)) return bounded(compare(environment, otherEnvironment, offset, limit));
        throw new IllegalArgumentException("Unsupported configuration inspection action");
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
            return result;
        } catch (ConfigurationLimitException exceeded) {
            throw exceeded;
        } catch (Exception invalid) {
            return invalid("declared", null, "ATT-CONFIG-INVALID", "Package configuration is unavailable or invalid");
        }
    }

    private Map<String, Object> effective(String environment, String section, int offset, int limit) {
        String safeEnvironment = normalizedEnvironment(environment);
        try {
            Map<?, ?> raw = readConfig();
            validateReferencedFiles(raw, safeEnvironment);
            FrameworkConfig config = new FrameworkConfigLoader().load(configFile(), packageRoot, safeEnvironment);
            return snapshot(raw, config, "effective", section, offset, limit);
        } catch (ConfigurationLimitException exceeded) {
            throw exceeded;
        } catch (Exception invalid) {
            return invalid("effective", safeEnvironment, "ATT-CONFIG-INVALID", "Selected configuration is unavailable or invalid");
        }
    }

    private Map<String, Object> compare(String leftEnvironment, String rightEnvironment, int offset, int limit) {
        String leftName = normalizedEnvironment(leftEnvironment);
        String rightName = normalizedEnvironment(rightEnvironment);
        if (leftName == null || rightName == null) throw new IllegalArgumentException("left and right environments must be valid profile names");
        if (leftName.equalsIgnoreCase(rightName)) throw new IllegalArgumentException("left and right environments must differ");
        Map<String, Object> left = effective(leftName, null, 0, 0);
        Map<String, Object> right = effective(rightName, null, 0, 0);
        Map<String, Object> result = base("compare", "ready", text(left.get("schemaVersion"), 64));
        result.put("leftEnvironment", leftName);
        result.put("rightEnvironment", rightName);
        List<Map<String, Object>> diagnostics = new ArrayList<Map<String, Object>>();
        if (!"ready".equals(left.get("state"))) diagnostics.addAll(diagnostics(left));
        if (!"ready".equals(right.get("state"))) diagnostics.addAll(diagnostics(right));
        if (!diagnostics.isEmpty()) {
            result.put("state", "invalid");
            result.put("diagnostics", diagnostics);
            result.put("fields", Collections.emptyList());
            return result;
        }
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> leftFields = flatten(left);
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> rightFields = flatten(right);
        Set<String> paths = new LinkedHashSet<String>();
        paths.addAll(leftFields.keySet());
        paths.addAll(rightFields.keySet());
        List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
        for (String path : paths) {
            Map<String, Object> before = leftFields.get(path);
            Map<String, Object> after = rightFields.get(path);
            boolean hidden = isSensitivePath(path)
                    || before != null && "hidden".equals(before.get("state"))
                    || after != null && "hidden".equals(after.get("state"));
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("path", path);
            if (hidden) {
                item.put("left", field("hidden", null, null, null));
                item.put("right", field("hidden", null, null, null));
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
        result.put("state", "ready");
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

    private Map<String, Object> snapshot(Map<?, ?> raw, FrameworkConfig config, String view,
                                         String selectedSection, int offset, int limit) throws Exception {
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
        sections.add(testdataSection(config, origin(raw, environment, "testdata")));
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
        result.put("sections", sections);
        result.put("diagnostics", Collections.emptyList());
        return result;
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
            for (String hidden : Arrays.asList("url", "username", "password", "properties", "evidence.sql", "evidence.parameters")) hidden(fields, hidden);
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
            for (String hidden : Arrays.asList("queueManager", "host", "port", "channel", "username", "password", "requestQueue", "replyQueue", "evidencePayload")) hidden(fields, hidden);
            List<Map<String, Object>> instances = new ArrayList<Map<String, Object>>();
            for (Map.Entry<String, MqHelperConfig> instance : helper.instances().entrySet()) {
                Map<String, Object> child = entry(instance.getKey(), source);
                Map<String, Object> childFields = fields(child);
                for (String hidden : Arrays.asList("queueManager", "host", "port", "channel", "username", "password", "requestQueue", "replyQueue")) hidden(childFields, hidden);
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
                for (String hidden : Arrays.asList("host", "user", "port", "identityFile")) hidden(childFields, hidden);
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
            for (String hidden : Arrays.asList("baseUrl", "headers", "username", "password", "token", "trustStore", "trustStorePassword")) hidden(fields, hidden);
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
            if (descriptor.selection() != null) {
                visible(fields, "selection.strategy", descriptor.selection().strategy(), source);
                visible(fields, "selection.exhaustion", descriptor.selection().exhaustion(), source);
            } else {
                missing(fields, "selection.strategy", source);
                missing(fields, "selection.exhaustion", source);
            }
            hidden(fields, "records");
            entries.add(entry);
        }
        return section("testdata", "Testdata descriptors", source, entries);
    }

    private Map<?, ?> readConfig() throws Exception {
        PackageResourceResolver.PackageResource resource = configPath.isAbsolute()
                ? resources.fromInternalPath(configPath, PackageResourceResolver.Kind.FILE)
                : resources.resolvePackageRelative(configPath.toString().replace('\\', '/'), PackageResourceResolver.Kind.FILE);
        if (Files.size(resource.canonicalPath()) > MAX_CONFIG_BYTES) throw new ConfigurationLimitException();
        Object loaded = YamlSupport.load(resource.canonicalPath());
        if (!(loaded instanceof Map)) throw new IllegalArgumentException("Configuration must be a YAML mapping");
        return (Map<?, ?>) loaded;
    }

    private Path configFile() throws Exception {
        return configPath.isAbsolute() ? resources.fromInternalPath(configPath, PackageResourceResolver.Kind.FILE).canonicalPath()
                : resources.resolvePackageRelative(configPath.toString().replace('\\', '/'), PackageResourceResolver.Kind.FILE).canonicalPath();
    }

    private void validateReferencedFiles(Map<?, ?> raw, String environment) throws Exception {
        long totalBytes = 0;
        int count = 0;
        Map<?, ?> profile = profileMap(raw, environment);
        for (String key : Arrays.asList("toolGroups")) {
            List<?> values = listOrEmpty(raw.get(key));
            for (Object value : values) {
                if (!(value instanceof String)) continue;
                totalBytes = checkDescriptorLimit(++count, totalBytes, String.valueOf(value));
            }
        }
        for (String key : RESOURCE_LISTS) {
            Object configured = profile != null && profile.containsKey(key) ? profile.get(key) : raw.get(key);
            for (Object value : listOrEmpty(configured)) {
                if (!(value instanceof String)) continue;
                totalBytes = checkDescriptorLimit(++count, totalBytes, String.valueOf(value));
            }
        }
    }

    private long checkDescriptorLimit(int count, long totalBytes, String authored) throws Exception {
        if (count > MAX_REFERENCED_FILES) throw new ConfigurationLimitException();
        long nextBytes = totalBytes + checkDescriptor(authored);
        if (nextBytes > MAX_TOTAL_DESCRIPTOR_BYTES) throw new ConfigurationLimitException();
        return nextBytes;
    }

    private long checkDescriptor(String authored) throws Exception {
        PackageResourceResolver.PackageResource resource = resources.resolvePackageRelative(authored, PackageResourceResolver.Kind.FILE);
        Path logical = packageRoot.resolve(authored.replace('\\', '/')).normalize();
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
        String id = String.valueOf(entry.get("id"));
        String path = prefix + "." + id;
        target.put(path + ".$entry", field("visible", Boolean.TRUE, entry.get("origin"), "visible"));
        flattenFields(target, path, object(entry.get("fields")));
        Object arguments = entry.get("arguments");
        if (arguments instanceof Iterable) for (Object argumentValue : (Iterable<?>) arguments) {
            Map<?, ?> argument = object(argumentValue);
            flattenFields(target, path + ".arguments." + argument.get("id"), object(argument.get("fields")));
        }
        Object instances = entry.get("instances");
        if (instances instanceof Iterable) for (Object instanceValue : (Iterable<?>) instances) flattenEntry(target, path + ".instances", object(instanceValue));
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
        PackageResourceResolver.PackageResource resource = resources.fromInternalPath(path, PackageResourceResolver.Kind.FILE);
        entry.put("source", resource.logicalName());
    }

    private static void visible(Map<String, Object> fields, String key, Object value, String origin) {
        fields.put(key, field("visible", value, origin, null));
    }

    private static void missing(Map<String, Object> fields, String key, String origin) {
        fields.put(key, field("missing", null, origin, null));
    }

    private static void hidden(Map<String, Object> fields, String key) { fields.put(key, field("hidden", null, null, null)); }

    private static void putVisible(Map<String, Object> fields, String key, Object value, String origin) {
        fields.put(key, field("visible", value, origin, null));
    }

    private static void putHidden(Map<String, Object> fields, String key) { fields.put(key, field("hidden", null, null, null)); }

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
