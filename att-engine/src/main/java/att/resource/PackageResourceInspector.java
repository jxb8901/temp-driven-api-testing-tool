package att.resource;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.config.SuiteConfigResolver;
import att.config.ToolArgumentConfig;
import att.config.ToolConfig;
import att.core.StageCaseData;
import att.core.TestCase;
import att.excel.ExcelTestSuiteLoader;
import att.flow.FlowDefinition;
import att.flow.FlowRegistry;
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.template.TemplateAction;
import att.debug.DebugEngine;
import att.config.YamlSupport;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-only logical package index used by the authenticated Server inspector Worker.
 * It only opens resources reachable through the package configuration and never executes Tools.
 */
public final class PackageResourceInspector {
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_INLINE_BACK_REFERENCES = 100;
    private static final int MAX_RESOURCES = 20000;
    private static final int MAX_WORKBOOKS = 500;
    private static final long MAX_TOTAL_WORKBOOK_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static final long MAX_WORKBOOK_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_REVISION_BYTES = 256L * 1024L * 1024L;
    private static final Pattern TOOL_CALL = Pattern.compile("#\\{\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*\\(");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(password|passwd|token|secret|authorization|api[_-]?key|client[_-]?secret)\\s*([:=])\\s*(['\"]?)[^\\s,'\";}]+");
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/=_-]+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^/@\\s:]+:[^/@\\s]+@");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----");

    private final Path packageRoot;
    private final PackageResourceResolver resources;
    private final Path configPath;
    private final String environment;
    private final Set<String> safeTextSources;
    private final int maxSourceBytes;
    private final int maxResponseBytes;

    public PackageResourceInspector(Path packageRoot, Path configPath, String environment,
                                    Collection<String> safeTextSources,
                                    int maxSourceBytes, int maxResponseBytes) {
        this.resources = new PackageResourceResolver(packageRoot);
        this.packageRoot = resources.packageRoot();
        this.configPath = configPath == null ? Paths.get("config/config.yaml") : configPath;
        this.environment = environment;
        this.safeTextSources = new HashSet<String>(
                safeTextSources == null ? Collections.<String>emptySet() : safeTextSources);
        this.maxSourceBytes = positive(maxSourceBytes, 65536, "maxSourceBytes");
        this.maxResponseBytes = positive(maxResponseBytes, 262144, "maxResponseBytes");
    }

    public Map<String, Object> inspect(String action, String type, String resourceId,
                                       String query, int offset, int limit) throws Exception {
        return inspect(action,type,resourceId,query,offset,limit,null);
    }

    public Map<String, Object> inspect(String action, String type, String resourceId,
                                       String query, int offset, int limit, String expectedRevisionDigest) throws Exception {
        if (!("list".equals(action) || "detail".equals(action) || "source".equals(action)))
            throw new IllegalArgumentException("Unsupported inspection action");
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (query != null && query.length() > 200) throw new IllegalArgumentException("query is too long");
        if (type != null && !supportedType(type)) throw new IllegalArgumentException("Unsupported resource type");
        Index index = index();
        if (expectedRevisionDigest != null && !expectedRevisionDigest.equals(index.revisionDigest))
            throw new StaleResourceCursorException();
        if ("list".equals(action)) return page(index, type, query, offset, limit);
        if (resourceId == null || resourceId.length() > 512) throw new IllegalArgumentException("resourceId is invalid");
        Resource resource = index.byId.get(resourceId);
        if (resource == null || type != null && !type.equals(resource.type)) throw new ResourceNotFoundException();
        if ("source".equals(action)) return source(index, resource);
        return detail(index, resource);
    }

    private Map<String, Object> page(Index index, String type, String query, int offset, int limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Resource> matches = new ArrayList<Resource>();
        for (Resource resource : index.ordered) {
            if (type != null && !type.equals(resource.type)) continue;
            if (!needle.isEmpty() && !resource.matches(needle)) continue;
            matches.add(resource);
        }
        if (offset > matches.size()) throw new IllegalArgumentException("offset exceeds the current resource list");
        int end = Math.min(matches.size(), offset + limit);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (int i = offset; i < end; i++) items.add(summary(matches.get(i), index));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("total", matches.size());
        result.put("nextOffset", end < matches.size() ? Integer.valueOf(end) : null);
        result.put("diagnostics", index.diagnostics);
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    private Map<String, Object> detail(Index index, Resource resource) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resource", summary(resource, index));
        result.put("definition", resource.definition);
        result.put("diagnostics", resource.diagnostics);
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    private Map<String, Object> source(Index index, Resource resource) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resource", summary(resource, index));
        result.put("revisionDigest", index.revisionDigest);
        if (resource.yamlSource != null) {
            if (!sourceAllowed(resource.yamlSource)) {
                result.put("available", Boolean.FALSE);
                result.put("reason", "visibility-policy");
                return bounded(result);
            }
            try {
                Object authored = loadYaml(resource.yamlSource, maxSourceBytes);
                Object safe = safeMap(yamlMapping(authored));
                String text = dumpYaml(safe);
                if (utf8(text).length > maxSourceBytes) {
                    result.put("available", Boolean.FALSE);
                    result.put("reason", "size-limit");
                    return bounded(result);
                }
                result.put("available", Boolean.TRUE);
                result.put("format", "yaml");
                result.put("text", text);
                result.put("redacted", Boolean.valueOf(!deepEquals(authored, safe)));
                return bounded(result);
            } catch (ResponseTooLargeException oversized) {
                result.put("available", Boolean.FALSE);
                result.put("reason", "size-limit");
                return bounded(result);
            } catch (Exception ignored) {
                result.put("available", Boolean.FALSE);
                result.put("reason", "source-unavailable");
                return bounded(result);
            }
        }
        if (resource.textSource != null) {
            try {
                String logicalPath = resources.fromInternalPath(resource.textSource, PackageResourceResolver.Kind.FILE).logicalName();
                if (!safeTextSources.contains(logicalPath)) {
                    result.put("available", Boolean.FALSE);
                    result.put("reason", "source-unavailable");
                    return bounded(result);
                }
                String text = decodeUtf8(readBounded(resource.textSource, maxSourceBytes));
                result.put("available", Boolean.TRUE);
                result.put("format", "text");
                result.put("logicalPath", logicalPath);
                result.put("text", scrub(text));
                result.put("redacted", Boolean.valueOf(!text.equals(scrub(text))));
                return bounded(result);
            } catch (ResponseTooLargeException oversized) {
                result.put("available", Boolean.FALSE);
                result.put("reason", "size-limit");
                return bounded(result);
            } catch (Exception ignored) {
                result.put("available", Boolean.FALSE);
                result.put("reason", "source-unavailable");
                return bounded(result);
            }
        }
        result.put("available", Boolean.FALSE);
        result.put("reason", "source-unavailable");
        return bounded(result);
    }

    private Map<String, Object> bounded(Map<String, Object> value) {
        int bytes = utf8(new Yaml().dump(value)).length;
        if (bytes > maxResponseBytes) throw new ResponseTooLargeException();
        return value;
    }

    private Index index() throws Exception {
        Index index = new Index();
        Path configFile = configPath.isAbsolute() ? configPath : packageRoot.resolve(configPath);
        configFile = resources.fromInternalPath(configFile, PackageResourceResolver.Kind.FILE).canonicalPath();
        if (Files.size(configFile) > MAX_CONFIG_BYTES) throw new ResourceLimitException();
        indexFile(index, configFile);
        FrameworkConfig config = new FrameworkConfigLoader().load(configFile, packageRoot, environment);
        Path templatesRoot = config.templatesRoot().isAbsolute()
                ? config.templatesRoot() : packageRoot.resolve(config.templatesRoot());
        templatesRoot = resources.fromInternalPath(templatesRoot, PackageResourceResolver.Kind.DIRECTORY).canonicalPath();

        indexTemplates(index, config, templatesRoot);
        indexFlows(index, config, templatesRoot);
        indexTools(index, config);
        indexCases(index, config);
        indexDebugSidecars(index, config);
        indexQuickLoadPolicies(index);
        resolveReferences(index);
        index.revisionDigest = digestFiles(index.files, index.debugSidecars, index.quickLoadPolicies);
        Collections.sort(index.ordered, new Comparator<Resource>() {
            @Override public int compare(Resource left, Resource right) {
                return left.resourceId.compareTo(right.resourceId);
            }
        });
        return index;
    }

    /** Returns a safe, fixed-target Debug form projection and its private package revision. */
    public Map<String, Object> inspectDebugForm(String type, String resourceId, String expectedRevisionDigest) throws Exception {
        Index index = index();
        requireExpectedRevision(index, expectedRevisionDigest);
        Resource resource = index.byId.get(resourceId);
        if (resource == null || !debuggable(resource.type) || !resource.type.equals(type) || !"ready".equals(resource.state))
            throw new ResourceNotFoundException();
        DebugEngine engine = new DebugEngine(packageRoot, frameworkConfig());
        Map<String, Object> projection = engine.projectDiscoverableInput(resource.type, resource.logicalId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resource", summary(resource, index));
        result.put("target", target(resource));
        result.put("input", projection.get("input"));
        result.put("redacted", projection.get("redacted"));
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    /** Returns safe business defaults and a validated one-workload Quick Load preview. */
    public Map<String, Object> inspectQuickLoadForm(String type, String resourceId, String model,
                                                    String expectedRevisionDigest) throws Exception {
        Index index = index();
        requireExpectedRevision(index, expectedRevisionDigest);
        Resource resource = index.byId.get(resourceId);
        if (resource == null || !debuggable(resource.type) || !resource.type.equals(type) || !"ready".equals(resource.state))
            throw new ResourceNotFoundException();
        return quickLoadProjection(index, resource, model, null, null, null);
    }

    /** Validates business defaults and policy overrides, returning a private typed scenario for draft creation. */
    public Map<String, Object> validateQuickLoadInput(String type, String logicalId, String model,
                                                      Map<String, Object> businessInput,
                                                      Map<String, Object> loadOverrides,
                                                      Map<String, Object> workloadExecution) throws Exception {
        return validateQuickLoadInput(type, logicalId, model, businessInput, loadOverrides, workloadExecution,
                Collections.<String>emptyList());
    }

    /** Validates business inputs, pacing, and explicit Load-level Testdata descriptor paths. */
    public Map<String, Object> validateQuickLoadInput(String type, String logicalId, String model,
                                                      Map<String, Object> businessInput,
                                                      Map<String, Object> loadOverrides,
                                                      Map<String, Object> workloadExecution,
                                                      List<String> testdata) throws Exception {
        if (!debuggable(type) || logicalId == null || logicalId.trim().isEmpty() || logicalId.length() > 512)
            throw new ResourceNotFoundException();
        Index index = index();
        Resource resource = index.resolve(type, logicalId);
        if (resource == null || !"ready".equals(resource.state)) throw new ResourceNotFoundException();
        return quickLoadProjection(index, resource, model, businessInput, loadOverrides, workloadExecution, testdata);
    }

    private Map<String, Object> quickLoadProjection(Index index, Resource resource, String model,
                                                    Map<String, Object> businessInput,
                                                    Map<String, Object> loadOverrides,
                                                    Map<String, Object> workloadExecution) throws Exception {
        return quickLoadProjection(index, resource, model, businessInput, loadOverrides, workloadExecution,
                Collections.<String>emptyList());
    }

    private Map<String, Object> quickLoadProjection(Index index, Resource resource, String model,
                                                    Map<String, Object> businessInput,
                                                    Map<String, Object> loadOverrides,
                                                    Map<String, Object> workloadExecution,
                                                    List<String> testdata) throws Exception {
        FrameworkConfig config = frameworkConfig();
        DebugEngine engine = new DebugEngine(packageRoot, config);
        Map<String, Object> projectedInput = engine.projectLoadBusinessInput(resource.type, resource.logicalId, businessInput);
        @SuppressWarnings("unchecked") Map<String, Object> normalizedInput =
                (Map<String, Object>) projectedInput.get("normalizedInput");
        att.load.LoadScenarioLoader loader = new att.load.LoadScenarioLoader(packageRoot);
        Map<String, Object> policy = loader.loadQuickLoadPolicy(model);
        att.load.LoadScenario scenario = new att.load.LoadScenarioBuilder(loader).buildQuickLoad(model,
                resource.type, resource.logicalId, normalizedInput, policy, loadOverrides, workloadExecution, testdata);
        att.load.LoadTarget target = new att.load.LoadTargetResolver(packageRoot, config).resolve(scenario);
        new att.load.LoadTargetValidator(packageRoot, config).validate(scenario, target);
        Map<String, Object> scenarioMap = scenario.toMap();
        Map<String, Object> normalizedScenario = new LinkedHashMap<String, Object>(scenarioMap);
        List<Object> scenarioTestdata = new ArrayList<Object>();
        Object policyTestdata = policy.get("testdata");
        if (policyTestdata instanceof List) scenarioTestdata.addAll((List<?>) policyTestdata);
        else if (policyTestdata != null) scenarioTestdata.add(policyTestdata);
        if (testdata != null) scenarioTestdata.addAll(testdata);
        if (!scenarioTestdata.isEmpty()) normalizedScenario.put("testdata", scenarioTestdata);
        Map<String, Object> safeScenarioProjection = projectSafeLoadScenario(scenarioMap, engine);
        @SuppressWarnings("unchecked") Map<String, Object> safeScenario =
                (Map<String, Object>) safeScenarioProjection.get("value");
        boolean redacted = Boolean.TRUE.equals(projectedInput.get("redacted"))
                || Boolean.TRUE.equals(safeScenarioProjection.get("redacted"));
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(120);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resource", summary(resource, index));
        result.put("target", target(resource));
        result.put("model", model);
        result.put("input", projectedInput.get("input"));
        result.put("debugLocalTestdataOmitted", projectedInput.get("debugLocalTestdataOmitted"));
        result.put("loadTestdata", testdata == null ? Collections.emptyList() : new ArrayList<String>(testdata));
        result.put("redacted", Boolean.valueOf(redacted));
        result.put("preview", safeScenario);
        result.put("previewYaml", new Yaml(options).dump(safeScenario));
        result.put("normalizedScenario", normalizedScenario);
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    /** Returns the safe model-specific policy defaults used to initialize Advanced Load. */
    public Map<String, Object> inspectQuickLoadPolicy(String model) throws Exception {
        Index index = index();
        Map<String, Object> policy = new att.load.LoadScenarioLoader(packageRoot).loadQuickLoadPolicy(model);
        Map<String, Object> projection = new DebugEngine(packageRoot, frameworkConfig()).projectSafeValue(policy);
        @SuppressWarnings("unchecked") Map<String, Object> safePolicy = (Map<String, Object>) projection.get("value");
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(120);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("model", model);
        result.put("policy", safePolicy);
        result.put("previewYaml", new Yaml(options).dump(safePolicy));
        result.put("redacted", Boolean.valueOf(Boolean.TRUE.equals(projection.get("redacted"))));
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    /** Validates a full inline Load scenario and every target closure without scheduling traffic. */
    public Map<String, Object> validateLoadScenario(Map<String, Object> requested) throws Exception {
        if (requested == null) throw new IllegalArgumentException("Load scenario is required");
        Index index = index();
        Map<String, Object> normalizedRequest = att.load.LoadIsolation.deepCopyMap(requested);
        FrameworkConfig config = frameworkConfig();
        DebugEngine engine = new DebugEngine(packageRoot, config);
        restoreAdvancedBusinessDefaults(normalizedRequest, engine);
        Map<String, Object> policy = new LinkedHashMap<String, Object>(normalizedRequest);
        Object rawWorkloads = policy.remove("workloads");
        if (!(rawWorkloads instanceof List)) throw new IllegalArgumentException("workloads must be an array");
        List<Map<String, Object>> workloads = new ArrayList<Map<String, Object>>();
        for (Object raw : (List<?>) rawWorkloads) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("Every workload must be an object");
            @SuppressWarnings("unchecked") Map<String, Object> workload = (Map<String, Object>) raw;
            workloads.add(workload);
        }
        Map<String, Object> composed = new att.load.LoadScenarioBuilder(new att.load.LoadScenarioLoader(packageRoot))
                .composeScenario(policy, workloads);
        att.load.LoadScenario scenario = new att.load.LoadScenarioLoader(packageRoot).loadInline(composed);
        att.load.LoadRunCoordinator.resolveAndValidateTargets(packageRoot, config, scenario);
        Map<String, Object> scenarioMap = scenario.toMap();
        Map<String, Object> projection = projectSafeLoadScenario(scenarioMap, engine);
        @SuppressWarnings("unchecked") Map<String, Object> safeScenario = (Map<String, Object>) projection.get("value");
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(120);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("model", scenario.model() == att.load.LoadScenario.Model.CLOSED ? "virtualUsers" : "arrivalRate");
        result.put("preview", safeScenario);
        result.put("previewYaml", new Yaml(options).dump(safeScenario));
        result.put("redacted", Boolean.valueOf(Boolean.TRUE.equals(projection.get("redacted"))));
        result.put("normalizedScenario", scenarioMap);
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    /** Restores only redacted sidecar defaults while keeping user-entered Load values intact. */
    private void restoreAdvancedBusinessDefaults(Map<String, Object> scenario, DebugEngine engine) throws Exception {
        Object rawWorkloads = scenario.get("workloads");
        if (!(rawWorkloads instanceof List)) return;
        for (Object rawWorkload : (List<?>) rawWorkloads) {
            if (!(rawWorkload instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> workload = (Map<String, Object>) rawWorkload;
            Object rawMix = workload.get("mix");
            if (rawMix instanceof List) {
                for (Object rawEntry : (List<?>) rawMix) {
                    if (!(rawEntry instanceof Map)) continue;
                    @SuppressWarnings("unchecked") Map<String, Object> entry = (Map<String, Object>) rawEntry;
                    if (entry.get("target") instanceof Map) {
                        @SuppressWarnings("unchecked") Map<String, Object> target = (Map<String, Object>) entry.get("target");
                        restoreTargetBusinessDefaults(engine, target, entry);
                    }
                }
            } else if (workload.get("target") instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> target = (Map<String, Object>) workload.get("target");
                restoreTargetBusinessDefaults(engine, target, workload);
            }
        }
    }

    private void restoreTargetBusinessDefaults(DebugEngine engine, Map<String, Object> target,
                                               Map<String, Object> businessOwner) throws Exception {
        Object rawType = target.get("type"), rawId = target.get("id");
        if (!(rawType instanceof String) || !(rawId instanceof String)) return;
        String type = (String) rawType, id = (String) rawId;
        Map<String, Object> submitted = new LinkedHashMap<String, Object>();
        submitted.put("schemaVersion", att.Version.DEBUG_SCHEMA);
        submitted.put("inputs", mapValue(businessOwner.get("inputs")));
        if ("tool".equals(type)) submitted.put("arguments", mapValue(target.get("arguments")));
        else submitted.put("vars", mapValue(businessOwner.get("vars")));
        Map<String, Object> projection = engine.projectLoadBusinessInput(type, id, submitted);
        Object rawNormalized = projection.get("normalizedInput");
        if (!(rawNormalized instanceof Map)) throw new IllegalStateException("Load sidecar projection returned invalid business values");
        @SuppressWarnings("unchecked") Map<String, Object> normalized = (Map<String, Object>) rawNormalized;
        Object inputs = normalized.get("inputs");
        if (inputs instanceof Map && (!((Map<?, ?>) inputs).isEmpty() || businessOwner.containsKey("inputs")))
            businessOwner.put("inputs", inputs);
        if ("tool".equals(type)) {
            Object arguments = normalized.get("arguments");
            if (arguments instanceof Map && (!((Map<?, ?>) arguments).isEmpty() || target.containsKey("arguments")))
                target.put("arguments", arguments);
        } else {
            Object vars = normalized.get("vars");
            if (vars instanceof Map && (!((Map<?, ?>) vars).isEmpty() || businessOwner.containsKey("vars")))
                businessOwner.put("vars", vars);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) value)
                : new LinkedHashMap<String, Object>();
    }

    private Map<String, Object> projectSafeLoadScenario(Map<String, Object> scenario, DebugEngine engine) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>(scenario);
        List<Object> workloads = new ArrayList<Object>();
        boolean[] redacted = new boolean[] { false };
        Object rawWorkloads = scenario.get("workloads");
        if (rawWorkloads instanceof List) for (Object rawWorkload : (List<?>) rawWorkloads) {
            if (!(rawWorkload instanceof Map)) { workloads.add(rawWorkload); continue; }
            @SuppressWarnings("unchecked") Map<String, Object> workload = new LinkedHashMap<String, Object>((Map<String, Object>) rawWorkload);
            Map<String, Object> business = new LinkedHashMap<String, Object>();
            if (workload.containsKey("inputs")) business.put("inputs", workload.get("inputs"));
            if (workload.containsKey("vars")) business.put("vars", workload.get("vars"));
            Object rawTarget = workload.get("target");
            Map<String, Object> target = null;
            if (rawTarget instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> copiedTarget = new LinkedHashMap<String, Object>((Map<String, Object>) rawTarget);
                target = copiedTarget;
                if (target.containsKey("arguments")) business.put("arguments", target.get("arguments"));
            }
            Map<String, Object> projected = engine.projectSafeValue(business);
            @SuppressWarnings("unchecked") Map<String, Object> safeBusiness = (Map<String, Object>) projected.get("value");
            redacted[0] |= Boolean.TRUE.equals(projected.get("redacted"));
            if (safeBusiness.containsKey("inputs")) workload.put("inputs", safeBusiness.get("inputs"));
            if (safeBusiness.containsKey("vars")) workload.put("vars", safeBusiness.get("vars"));
            if (target != null) {
                if (safeBusiness.containsKey("arguments")) target.put("arguments", safeBusiness.get("arguments"));
                workload.put("target", target);
            }
            Object rawMix = workload.get("mix");
            if (rawMix instanceof List) {
                List<Object> mix = new ArrayList<Object>();
                for (Object rawEntry : (List<?>) rawMix) {
                    if (!(rawEntry instanceof Map)) { mix.add(rawEntry); continue; }
                    @SuppressWarnings("unchecked") Map<String, Object> entry = new LinkedHashMap<String, Object>((Map<String, Object>) rawEntry);
                    Map<String, Object> entryBusiness = new LinkedHashMap<String, Object>();
                    if (entry.containsKey("inputs")) entryBusiness.put("inputs", entry.get("inputs"));
                    if (entry.containsKey("vars")) entryBusiness.put("vars", entry.get("vars"));
                    Object entryTarget = entry.get("target");
                    Map<String, Object> copiedEntryTarget = null;
                    if (entryTarget instanceof Map) {
                        @SuppressWarnings("unchecked") Map<String, Object> copied = new LinkedHashMap<String, Object>((Map<String, Object>) entryTarget);
                        copiedEntryTarget = copied;
                        if (copied.containsKey("arguments")) entryBusiness.put("arguments", copied.get("arguments"));
                    }
                    Map<String, Object> entryProjection = engine.projectSafeValue(entryBusiness);
                    @SuppressWarnings("unchecked") Map<String, Object> safeEntryBusiness = (Map<String, Object>) entryProjection.get("value");
                    redacted[0] |= Boolean.TRUE.equals(entryProjection.get("redacted"));
                    if (safeEntryBusiness.containsKey("inputs")) entry.put("inputs", safeEntryBusiness.get("inputs"));
                    if (safeEntryBusiness.containsKey("vars")) entry.put("vars", safeEntryBusiness.get("vars"));
                    if (copiedEntryTarget != null) {
                        if (safeEntryBusiness.containsKey("arguments")) copiedEntryTarget.put("arguments", safeEntryBusiness.get("arguments"));
                        entry.put("target", copiedEntryTarget);
                    }
                    mix.add(entry);
                }
                workload.put("mix", mix);
            }
            workloads.add(workload);
        }
        if (rawWorkloads instanceof List) safe.put("workloads", workloads);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("value", safe); result.put("redacted", Boolean.valueOf(redacted[0]));
        return result;
    }

    /** Validates typed inline Debug values against a resolvable logical target. */
    public Map<String, Object> validateDebugInput(String type, String logicalId,
                                                  Map<String, Object> input) throws Exception {
        if (!debuggable(type) || logicalId == null || logicalId.trim().isEmpty() || logicalId.length() > 512)
            throw new ResourceNotFoundException();
        Index index = index();
        Resource resource = index.resolve(type, logicalId);
        if (resource == null || !"ready".equals(resource.state)) throw new ResourceNotFoundException();
        DebugEngine engine = new DebugEngine(packageRoot, frameworkConfig());
        Map<String, Object> projection = engine.validateInlineInput(type, resource.logicalId, input);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resource", summary(resource, index));
        result.put("target", target(resource));
        result.put("input", projection.get("input"));
        result.put("redacted", projection.get("redacted"));
        result.put("normalizedInput", projection.get("normalizedInput"));
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    /** Checks a Server-issued draft revision without returning the digest to the caller. */
    public void verifyRevision(String expectedRevisionDigest) throws Exception {
        requireExpectedRevision(index(), expectedRevisionDigest);
    }

    private void requireExpectedRevision(Index index, String expectedRevisionDigest) {
        if (expectedRevisionDigest != null && !expectedRevisionDigest.equals(index.revisionDigest))
            throw new StaleResourceCursorException();
    }

    private FrameworkConfig frameworkConfig() throws Exception {
        Path file = configPath.isAbsolute() ? configPath : packageRoot.resolve(configPath);
        file = resources.fromInternalPath(file, PackageResourceResolver.Kind.FILE).canonicalPath();
        return new FrameworkConfigLoader().load(file, packageRoot, environment);
    }

    private static boolean debuggable(String type) {
        return "template".equals(type) || "flow".equals(type) || "tool".equals(type);
    }

    private Map<String, Object> target(Resource resource) {
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        target.put("type", resource.type);
        target.put("id", scrub(resource.logicalId));
        return target;
    }

    private void indexDebugSidecars(Index index, FrameworkConfig config) {
        for (Resource resource : index.ordered) {
            if ("template".equals(resource.type) || "flow".equals(resource.type)) {
                if (resource.yamlSource != null) indexDebugSidecar(index, resource.yamlSource.getParent().resolve("debug.yaml"));
            } else if ("tool".equals(resource.type)) {
                ToolConfig tool = config.tools().get(resource.logicalId);
                if (tool == null) continue;
                String group = tool.groupId().isEmpty() ? tool.localKey() : tool.groupId();
                indexDebugSidecar(index, packageRoot.resolve("config/tools").resolve(group + ".debug.yaml"));
            }
        }
    }

    private void indexDebugSidecar(Index index, Path file) {
        Path candidate = file.toAbsolutePath().normalize();
        if (!candidate.startsWith(packageRoot)) return;
        String logicalName = packageRoot.relativize(candidate).toString().replace('\\', '/');
        try {
            PackageResourceResolver.PackageResource resource = resources.fromInternalPath(file, PackageResourceResolver.Kind.FILE);
            index.files.add(resource.canonicalPath());
            logicalName = resource.logicalName();
            index.debugSidecars.put(logicalName, resource.canonicalPath());
        } catch (Exception ignored) {
            index.debugSidecars.put(logicalName, null);
        }
    }

    private void indexTemplates(Index index, FrameworkConfig config, Path templatesRoot) {
        try {
            StageTemplateLoader loader = new StageTemplateLoader(packageRoot, templatesRoot, false);
            for (String relative : loader.paths()) {
                if (index.ordered.size() >= MAX_RESOURCES) throw new ResourceLimitException();
                Path descriptor = templatesRoot.resolve(relative).resolve("template.yaml");
                StageTemplate template;
                Resource resource = new Resource("template", resourceId("template", relative), relative);
                try {
                    descriptor = resources.fromInternalPath(descriptor, PackageResourceResolver.Kind.FILE).canonicalPath();
                    indexFile(index, descriptor);
                    template = loader.load(relative);
                    resource.name = template.name();
                    resource.description = stringValue(yamlMap(descriptor).get("description"));
                    resource.state = "ready";
                    resource.yamlSource = descriptor;
                    resource.definition = safeMap(yamlMap(descriptor));
                    index.templateAliases.put(alias("template", relative), resource);
                    index.templateAliases.put(alias("template", template.name()), resource);
                    index.templateAliases.put(alias("template", resource.name), resource);
                    addActions(index, resource, template.actions());
                } catch (Exception error) {
                    resource.state = "invalid";
                    resource.diagnostics.add(diagnostic("ATT-RESOURCE-TEMPLATE-INVALID", "Template definition is invalid"));
                    if (Files.isRegularFile(descriptor)) {
                        try { indexFile(index, descriptor); } catch (Exception ignored) { }
                    }
                }
                index.add(resource);
            }
        } catch (ResourceLimitException error) {
            throw error;
        } catch (Exception error) {
            index.diagnostics.add(diagnostic("ATT-RESOURCE-TEMPLATE-INDEX-FAILED", "Template resources could not be indexed"));
        }
    }

    private void indexFlows(Index index, FrameworkConfig config, Path templatesRoot) {
        try {
            FlowRegistry registry = new FlowRegistry(packageRoot, templatesRoot, false);
            for (String id : registry.ids()) {
                if (index.ordered.size() >= MAX_RESOURCES) throw new ResourceLimitException();
                Resource resource = new Resource("flow", resourceId("flow", id), id);
                try {
                    FlowDefinition flow = registry.get(id);
                    Path descriptor = resources.fromInternalPath(
                            flow.directory().resolve("flow.yaml"), PackageResourceResolver.Kind.FILE).canonicalPath();
                    indexFile(index, descriptor);
                    resource.name = flow.name();
                    resource.description = flow.description();
                    resource.state = "ready";
                    resource.yamlSource = descriptor;
                    resource.definition = safeMap(yamlMap(descriptor));
                    index.flowAliases.put(alias("flow", id), resource);
                    index.flowAliases.put(alias("flow", flow.name()), resource);
                    addActions(index, resource, flow.actions());
                } catch (Exception error) {
                    resource.state = "invalid";
                    resource.diagnostics.add(diagnostic("ATT-RESOURCE-FLOW-INVALID", "Flow definition is invalid"));
                }
                index.add(resource);
            }
        } catch (ResourceLimitException error) {
            throw error;
        } catch (Exception error) {
            index.diagnostics.add(diagnostic("ATT-RESOURCE-FLOW-INDEX-FAILED", "Flow resources could not be indexed"));
        }
    }

    private void indexTools(Index index, FrameworkConfig config) {
        List<String> keys = new ArrayList<String>(config.tools().keySet());
        Collections.sort(keys);
        for (String key : keys) {
            if (index.ordered.size() >= MAX_RESOURCES) throw new ResourceLimitException();
            ToolConfig tool = config.tools().get(key);
            Resource resource = new Resource("tool", resourceId("tool", key), key);
            resource.name = tool.name();
            resource.description = scrub(tool.description());
            resource.state = "ready";
            resource.definition = toolDefinition(tool);
            Path script = permittedScript(tool);
            if (script != null) {
                resource.textSource = script;
                indexFileIfPackage(index, script);
            }
            if (tool.sourceFile() != null) indexFileIfPackage(index, tool.sourceFile());
            index.toolAliases.put(alias("tool", key), resource);
            index.add(resource);
        }
    }

    private void indexCases(Index index, FrameworkConfig config) {
        Path testcasesRoot = config.testcasesRoot().isAbsolute()
                ? config.testcasesRoot() : packageRoot.resolve(config.testcasesRoot());
        try {
            testcasesRoot = resources.fromInternalPath(testcasesRoot, PackageResourceResolver.Kind.DIRECTORY).canonicalPath();
        } catch (Exception error) {
            index.diagnostics.add(diagnostic("ATT-RESOURCE-CASE-ROOT-UNAVAILABLE", "Configured testcase root is unavailable"));
            return;
        }
        List<Path> workbooks = new ArrayList<Path>();
        try (Stream<Path> paths = Files.walk(testcasesRoot)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                    .forEach(path -> { if (workbooks.size() >= MAX_WORKBOOKS) throw new ResourceLimitException(); workbooks.add(path); });
        } catch (ResourceLimitException limit) {
            throw limit;
        } catch (Exception error) {
            index.diagnostics.add(diagnostic("ATT-RESOURCE-CASE-INDEX-FAILED", "Test Case resources could not be indexed"));
            return;
        }
        Collections.sort(workbooks);
        long totalWorkbookBytes = 0;
        for (Path workbook : workbooks) {
            if (index.ordered.size() >= MAX_RESOURCES) throw new ResourceLimitException();
            try {
                Path canonical = resources.fromInternalPath(workbook, PackageResourceResolver.Kind.FILE).canonicalPath();
                String suite = resources.fromInternalPath(canonical, PackageResourceResolver.Kind.FILE).logicalName();
                long workbookBytes=Files.size(canonical);totalWorkbookBytes+=workbookBytes;
                if (workbookBytes > MAX_WORKBOOK_BYTES || totalWorkbookBytes > MAX_TOTAL_WORKBOOK_BYTES) {
                    index.add(invalidSuite(suite, "Workbook exceeds the inspection size limit"));
                    continue;
                }
                indexFile(index, canonical);
                FrameworkConfig suiteConfig = new SuiteConfigResolver(packageRoot, config).resolve(canonical);
                Path sidecar = sidecar(canonical);
                indexFile(index, resources.fromInternalPath(sidecar, PackageResourceResolver.Kind.FILE).canonicalPath());
                List<TestCase> cases = new ExcelTestSuiteLoader(suiteConfig).load(canonical);
                for (TestCase testCase : cases) {
                    if (index.ordered.size() >= MAX_RESOURCES) throw new ResourceLimitException();
                    Resource resource = new Resource("case",
                            resourceId("case", suite + "\n" + testCase.caseId()), testCase.caseId());
                    resource.name = testCase.caseName();
                    resource.state = "ready";
                    for (String tag : testCase.tags()) resource.tags.add(scrub(tag));
                    resource.provenance.put("suite", suite);
                    resource.provenance.put("workbookId", testCase.workbookId());
                    resource.provenance.put("groupId", testCase.groupId());
                    resource.provenance.put("sheet", testCase.sheetName());
                    resource.provenance.put("rowNumber", Integer.valueOf(testCase.rowNumber()));
                    resource.definition.put("caseDataState", "hidden");
                    resource.definition.put("caseId", testCase.caseId());
                    resource.definition.put("tags", new ArrayList<String>(resource.tags));
                    List<Map<String, Object>> stages = new ArrayList<Map<String, Object>>();
                    for (Map.Entry<String, StageCaseData> stageEntry : testCase.stages().entrySet()) {
                        StageCaseData stage = stageEntry.getValue();
                        Map<String, Object> value = new LinkedHashMap<String, Object>();
                        value.put("key", stage.key());
                        value.put("template", scrub(stage.templateName()));
                        value.put("valuesState", "hidden");
                        stages.add(value);
                        resource.relations.add(new Relation("template", stage.templateName()));
                    }
                    resource.definition.put("stages", stages);
                    index.add(resource);
                }
            } catch (ResourceLimitException error) {
                throw error;
            } catch (Exception error) {
                String logical;
                try { logical = resources.fromInternalPath(workbook, PackageResourceResolver.Kind.FILE).logicalName(); }
                catch (Exception ignored) { continue; }
                index.add(invalidSuite(logical, "Workbook or sidecar could not be inspected"));
            }
        }
    }

    private Resource invalidSuite(String logicalSuite, String message) {
        Resource resource = new Resource("case", resourceId("case-suite", logicalSuite), logicalSuite);
        resource.name = Paths.get(logicalSuite).getFileName().toString();
        resource.state = "invalid";
        resource.provenance.put("suite", logicalSuite);
        resource.diagnostics.add(diagnostic("ATT-RESOURCE-CASE-INVALID", message));
        return resource;
    }

    private void addActions(Index index, Resource resource, List<TemplateAction> actions) {
        for (TemplateAction action : actions) {
            if ("flow".equalsIgnoreCase(action.type()) && action.use() != null && !action.use().isEmpty()) {
                resource.relations.add(new Relation("flow", action.use()));
            }
            String calledTool = toolCall(action.call());
            if (calledTool != null) resource.relations.add(new Relation("tool", calledTool));
        }
    }

    private void resolveReferences(Index index) {
        for (Resource resource : index.ordered) {
            List<Map<String, Object>> resolved = new ArrayList<Map<String, Object>>();
            for (Relation relation : resource.relations) {
                Resource target = index.resolve(relation.type, relation.logicalId);
                Map<String, Object> value = new LinkedHashMap<String, Object>();
                value.put("type", relation.type);
                value.put("logicalId", relation.logicalId);
                value.put("resourceId", target == null ? null : target.resourceId);
                value.put("resolution", target == null ? "unresolved" : "resolved");
                resolved.add(value);
                if (target == null) {
                    resource.diagnostics.add(diagnostic("ATT-RESOURCE-REFERENCE-UNRESOLVED",
                            "Reference to " + relation.type + " '" + scrub(relation.logicalId) + "' is unresolved"));
                } else {
                    target.backReferenceCount++;
                    if (target.backReferences.size() < MAX_INLINE_BACK_REFERENCES)
                        target.backReferences.add(referenceOf(resource));
                }
            }
            resource.references = resolved;
        }
    }

    private Map<String, Object> summary(Resource resource, Index index) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resourceId", resource.resourceId);
        result.put("type", resource.type);
        result.put("logicalId", scrub(resource.logicalId));
        result.put("name", scrub(resource.name));
        result.put("description", resource.description == null || resource.description.isEmpty() ? "" : "[HIDDEN]");
        result.put("tags", new ArrayList<String>(resource.tags));
        result.put("state", resource.state);
        result.put("sourceAvailable", Boolean.valueOf(resource.yamlSource != null && sourceAllowed(resource.yamlSource)
                || resource.textSource != null));
        result.put("provenance", sanitize(resource.provenance, null));
        result.put("references", resource.references);
        result.put("referencedBy", resource.backReferences);
        result.put("referencedByCount", Integer.valueOf(resource.backReferenceCount));
        result.put("referencedByHasMore", Boolean.valueOf(resource.backReferenceCount > resource.backReferences.size()));
        result.put("diagnostics", resource.diagnostics);
        return result;
    }

    private Map<String, Object> toolDefinition(ToolConfig tool) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("toolId", tool.key());
        result.put("groupId", tool.groupId());
        result.put("name", tool.name());
        result.put("description", scrub(tool.description()));
        result.put("executionKind", tool.commandBacked() ? "command" : "call");
        result.put("timeoutMs", tool.timeoutMs());
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ToolArgumentConfig> entry : tool.arguments().entrySet()) {
            ToolArgumentConfig argument = entry.getValue();
            Map<String, Object> field = new LinkedHashMap<String, Object>();
            field.put("name", argument.name());
            field.put("description", scrub(argument.description()));
            field.put("required", Boolean.valueOf(argument.required()));
            field.put("type", argument.type());
            field.put("enum", argument.enumValues());
            field.put("multiValue", Boolean.valueOf(argument.multiValue()));
            arguments.put(entry.getKey(), field);
        }
        result.put("arguments", arguments);
        return safeMap(result);
    }

    private Path permittedScript(ToolConfig tool) {
        List<String> argv = new ArrayList<String>();
        argv.addAll(tool.groupScriptArgv());
        argv.addAll(tool.commandArgv());
        for (String token : argv) {
            if (token == null || token.isEmpty() || token.contains("$" + "{") || token.contains("#" + "{")
                    || token.contains("&" + "{")) continue;
            try {
                Path candidate = Paths.get(token);
                if (!candidate.isAbsolute()) candidate = packageRoot.resolve(candidate);
                PackageResourceResolver.PackageResource resource =
                        resources.fromInternalPath(candidate, PackageResourceResolver.Kind.FILE);
                if (safeTextSources.contains(resource.logicalName()) && textExtension(resource.logicalName())
                        && Files.size(resource.canonicalPath()) <= maxSourceBytes) return resource.canonicalPath();
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static boolean textExtension(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        return value.endsWith(".sh") || value.endsWith(".bash") || value.endsWith(".py")
                || value.endsWith(".js") || value.endsWith(".ts") || value.endsWith(".sql")
                || value.endsWith(".groovy") || value.endsWith(".rb") || value.endsWith(".pl")
                || value.endsWith(".ps1") || value.endsWith(".bat") || value.endsWith(".cmd")
                || value.endsWith(".txt");
    }

    private Map<String, Object> referenceOf(Resource resource) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", resource.type);
        result.put("resourceId", resource.resourceId);
        result.put("logicalId", scrub(resource.logicalId));
        result.put("name", scrub(resource.name));
        return result;
    }

    private Map<String, Object> safeMap(Map<?, ?> source) {
        Set<String> allowed = new HashSet<String>(java.util.Arrays.asList(
                "schemaVersion", "id", "name", "description", "actions", "parameters", "params", "inputs",
                "outputs", "variables", "environment", "timeoutMs", "timeoutSec", "retry", "retryOn", "assert",
                "assertions", "expected", "expect", "flow", "tool", "type", "use", "call", "when", "condition",
                "if", "foreach", "report", "config", "caseId", "groupId", "workbookId", "tags", "template",
                "stage", "stages", "executionKind", "toolId", "logicalId", "arguments", "required", "enum",
                "multiValue", "value", "default", "format", "properties", "items", "schema", "additionalProperties",
                "title", "version"));
        Object value = safeProjection(source, null, allowed);
        if (value instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) value;
            return result;
        }
        return new LinkedHashMap<String, Object>();
    }

    /** Recursively keep descriptor structure while excluding arbitrary authored scalar payloads. */
    private Object safeProjection(Object value, String key, Set<String> allowed) {
        if (key != null && sensitiveKey(key)) return "[REDACTED]";
        if (key != null && freeFormKey(key)) return "[HIDDEN]";
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String name = String.valueOf(entry.getKey());
                Object child = entry.getValue();
                if (allowed.contains(name) || sensitiveKey(name) || freeFormKey(name)) {
                    result.put(name, safeProjection(child, name, allowed));
                } else if (child instanceof Map || child instanceof Iterable) {
                    Object projected = safeProjection(child, name, allowed);
                    if (projected instanceof Map && !((Map<?, ?>) projected).isEmpty()) result.put(name, projected);
                    else if (projected instanceof Iterable && ((Iterable<?>) projected).iterator().hasNext()) result.put(name, projected);
                }
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                Object projected = safeProjection(item, null, allowed);
                if (projected instanceof Map && ((Map<?, ?>) projected).isEmpty()) continue;
                if (projected != null) result.add(projected);
            }
            return result;
        }
        if (value instanceof String) return key != null && allowed.contains(key) ? scrub((String) value) : null;
        return key != null && allowed.contains(key) ? value : null;
    }

    private static boolean freeFormKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.equals("description") || normalized.equals("message") || normalized.equals("note")
                || normalized.equals("text") || normalized.equals("body") || normalized.equals("payload")
                || normalized.equals("command") || normalized.equals("script") || normalized.equals("value")
                || normalized.equals("default") || normalized.equals("expected") || normalized.equals("expect")
                || normalized.equals("values") || normalized.equals("casedata") || normalized.equals("outputs")
                || normalized.equals("call") || normalized.equals("expression") || normalized.equals("assert")
                || normalized.equals("actual") || normalized.equals("when") || normalized.equals("condition")
                || normalized.equals("if") || normalized.equals("runwhen");
    }

    private boolean sourceAllowed(Path source) {
        try {
            String logical = resources.fromInternalPath(source, PackageResourceResolver.Kind.FILE).logicalName();
            return safeTextSources.contains(logical);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Object sanitize(Object value, String key) {
        if (key != null && sensitiveKey(key)) return "[REDACTED]";
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String name = String.valueOf(entry.getKey());
                result.put(name, sanitize(entry.getValue(), name));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) result.add(sanitize(item, null));
            return result;
        }
        if (value instanceof String) return scrub((String) value);
        return value;
    }

    private static boolean sensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.contains("password") || normalized.contains("passwd") || normalized.contains("token")
                || normalized.contains("secret") || normalized.contains("credential") || normalized.equals("authorization")
                || normalized.contains("apikey") || normalized.contains("accesskey") || normalized.contains("clientid")
                || normalized.contains("username") || normalized.contains("privatekey") || normalized.contains("identityfile")
                || normalized.equals("jdbcurl") || normalized.contains("cookie")
                || normalized.equals("headers") || normalized.equals("header")) return true;
        return normalized.equals("url") || normalized.equals("host") || normalized.equals("hostname")
                || normalized.equals("port") || normalized.equals("endpoint") || normalized.equals("queue")
                || normalized.equals("channel") || normalized.equals("address");
    }

    private String scrub(String value) {
        if (value == null) return null;
        String result = value.replace(packageRoot.toString(), "[package]");
        result = AUTH_SCHEME.matcher(result).replaceAll("$1 [REDACTED]");
        result = KEY_VALUE_SECRET.matcher(result).replaceAll("$1$2[REDACTED]");
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1[REDACTED]@");
        result = PRIVATE_KEY.matcher(result).replaceAll("[REDACTED PRIVATE KEY]");
        return att.core.PathPresentation.displayDiagnosticText(result, packageRoot);
    }

    private static String dumpYaml(Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(120);
        return new Yaml(options).dump(value);
    }

    private static Map<String, Object> yamlMap(Path file) throws Exception {
        Object loaded = loadYaml(file, 1024 * 1024);
        return yamlMapping(loaded);
    }

    private static Map<String,Object> yamlMapping(Object loaded) {
        if (!(loaded instanceof Map)) throw new IllegalArgumentException("Resource YAML must be a mapping");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) loaded).entrySet())
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }

    private static Object loadYaml(Path file,int byteLimit) throws Exception {
        byte[] bytes=readBounded(file,byteLimit);
        String source=decodeUtf8(bytes);
        return YamlSupport.parser().load(new StringReader(source));
    }

    private static byte[] readBounded(Path file,int byteLimit) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream(Math.min(byteLimit,8192));
        try(InputStream input=Files.newInputStream(file)) {
            byte[] buffer=new byte[8192];int count,total=0;
            while((count=input.read(buffer))!=-1) {
                total+=count;if(total>byteLimit)throw new ResponseTooLargeException();
                output.write(buffer,0,count);
            }
        }
        return output.toByteArray();
    }

    private Path sidecar(Path workbook) {
        String name = workbook.getFileName().toString().replaceFirst("(?i)\\.xlsx$", ".yaml");
        return workbook.resolveSibling(name);
    }

    private String digestFiles(Set<Path> files, Map<String, Path> debugSidecars,
                               Map<String, Path> quickLoadPolicies) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> ordered = new ArrayList<Path>(files);
        Collections.sort(ordered);
        long total = 0;
        byte[] buffer = new byte[8192];
        for (Path file : ordered) {
            PackageResourceResolver.PackageResource resource =
                    resources.fromInternalPath(file, PackageResourceResolver.Kind.FILE);
            byte[] name = utf8(resource.logicalName());
            digest.update(name);
            digest.update((byte) 0);
            try (InputStream input = Files.newInputStream(resource.canonicalPath())) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_REVISION_BYTES) throw new ResourceLimitException();
                    digest.update(buffer, 0, read);
                }
            }
            digest.update((byte) 0xff);
        }
        for (Map.Entry<String, Path> sidecar : debugSidecars.entrySet()) {
            digest.update(utf8("debug-sidecar"));
            digest.update((byte) 0);
            digest.update(utf8(sidecar.getKey()));
            digest.update((byte) 0);
            digest.update((byte) (sidecar.getValue() == null ? 0 : 1));
            digest.update((byte) 0xff);
        }
        for (Map.Entry<String, Path> policy : quickLoadPolicies.entrySet()) {
            digest.update(utf8("quick-load-policy"));
            digest.update((byte) 0);
            digest.update(utf8(policy.getKey()));
            digest.update((byte) 0);
            digest.update((byte) (policy.getValue() == null ? 0 : 1));
            digest.update((byte) 0xff);
        }
        return hex(digest.digest());
    }

    private void indexQuickLoadPolicies(Index index) {
        for (String filename : new String[]{"load.visualuser.yaml", "load.arrivalrate.yaml"}) {
            Path candidate = packageRoot.resolve("load").resolve(filename).toAbsolutePath().normalize();
            String logicalName = packageRoot.relativize(candidate).toString().replace('\\', '/');
            try {
                PackageResourceResolver.PackageResource resource = resources.fromInternalPath(candidate, PackageResourceResolver.Kind.FILE);
                index.files.add(resource.canonicalPath());
                index.quickLoadPolicies.put(resource.logicalName(), resource.canonicalPath());
            } catch (Exception ignored) {
                index.quickLoadPolicies.put(logicalName, null);
            }
        }
    }

    private void indexFile(Index index, Path file) throws Exception {
        PackageResourceResolver.PackageResource resource =
                resources.fromInternalPath(file, PackageResourceResolver.Kind.FILE);
        index.files.add(resource.canonicalPath());
    }

    private void indexFileIfPackage(Index index, Path file) {
        try { indexFile(index, file); } catch (Exception ignored) { }
    }

    private String toolCall(String call) {
        if (call == null) return null;
        Matcher matcher = TOOL_CALL.matcher(call);
        if (!matcher.find()) return null;
        return matcher.group(1);
    }

    private Map<String, Object> diagnostic(String code, String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", code);
        result.put("summary", scrub(message));
        return result;
    }

    private String resourceId(String type, String logicalId) {
        String key = type + "\n" + logicalId;
        return type + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(utf8(key));
    }

    private static String alias(String type, String logicalId) {
        return type + "|" + (logicalId == null ? "" : logicalId.trim());
    }

    private static boolean supportedType(String value) {
        return "case".equals(value) || "template".equals(value) || "flow".equals(value) || "tool".equals(value);
    }

    private static String stringValue(Object value) { return value == null ? "" : String.valueOf(value); }
    private static int positive(int value, int fallback, String field) {
        if (value == 0) return fallback;
        if (value < 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
    private static boolean deepEquals(Object left, Object right) { return left == null ? right == null : left.equals(right); }

    private static final class Index {
        final Map<String, Resource> byId = new LinkedHashMap<String, Resource>();
        final Map<String, Resource> templateAliases = new HashMap<String, Resource>();
        final Map<String, Resource> flowAliases = new HashMap<String, Resource>();
        final Map<String, Resource> toolAliases = new HashMap<String, Resource>();
        final List<Resource> ordered = new ArrayList<Resource>();
        final List<Map<String, Object>> diagnostics = new ArrayList<Map<String, Object>>();
        final Set<Path> files = new TreeSet<Path>();
        final Map<String, Path> debugSidecars = new TreeMap<String, Path>();
        final Map<String, Path> quickLoadPolicies = new TreeMap<String, Path>();
        String revisionDigest;

        void add(Resource resource) {
            Resource prior = byId.put(resource.resourceId, resource);
            if (prior != null) throw new IllegalArgumentException("Duplicate logical resource identity");
            ordered.add(resource);
        }

        Resource resolve(String type, String logicalId) {
            String key = alias(type, logicalId);
            if ("template".equals(type)) return templateAliases.get(key);
            if ("flow".equals(type)) return flowAliases.get(key);
            if ("tool".equals(type)) return toolAliases.get(key);
            return null;
        }
    }

    private static final class Resource {
        final String type;
        final String resourceId;
        final String logicalId;
        String name = "";
        String description = "";
        String state = "ready";
        Path yamlSource;
        Path textSource;
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        final Map<String, Object> provenance = new LinkedHashMap<String, Object>();
        final List<String> tags = new ArrayList<String>();
        final List<Relation> relations = new ArrayList<Relation>();
        List<Map<String, Object>> references = new ArrayList<Map<String, Object>>();
        final List<Map<String, Object>> backReferences = new ArrayList<Map<String, Object>>();
        int backReferenceCount;
        final List<Map<String, Object>> diagnostics = new ArrayList<Map<String, Object>>();

        Resource(String type, String resourceId, String logicalId) {
            this.type = type; this.resourceId = resourceId; this.logicalId = logicalId;
        }

        boolean matches(String query) {
            if (contains(logicalId, query) || contains(name, query) || contains(description, query)) return true;
            for (String tag : tags) if (contains(tag, query)) return true;
            return false;
        }

        private static boolean contains(String value, String query) {
            return value != null && value.toLowerCase(Locale.ROOT).contains(query);
        }
    }

    private static final class Relation {
        final String type;
        final String logicalId;
        Relation(String type, String logicalId) { this.type = type; this.logicalId = logicalId; }
    }

    public static final class ResourceNotFoundException extends RuntimeException { }
    public static final class ResourceLimitException extends RuntimeException { }
    public static final class ResponseTooLargeException extends RuntimeException { }
    public static final class StaleResourceCursorException extends RuntimeException { }
}

