package att.flow;

import att.Version;
import att.config.SchemaSupport;
import att.config.YamlSupport;
import att.template.TemplateAction;
import att.template.StageTemplate;
import att.resource.PackageResourceResolver;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Package-local immutable Flow registry and static dependency resolver. */
public final class FlowRegistry {
    public static final int MAX_DEPTH = 3;
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.v[1-9][0-9]*$");
    private final Path projectRoot;
    private final List<Path> roots;
    private final Map<String, FlowDefinition> byId = new LinkedHashMap<String, FlowDefinition>();
    private final Map<String, Path> descriptors = new LinkedHashMap<String, Path>();
    private final List<Path> descriptorsWithoutId = new ArrayList<Path>();
    private final Map<Path, byte[]> sourceSnapshots;
    private final Set<Path> capturedTemplateDescriptors;
    private final boolean snapshotMode;
    private final boolean frozen;
    private int parsedCount;

    public FlowRegistry(Path projectRoot, Path templatesRoot) throws Exception {
        this(projectRoot, templatesRoot, true);
    }

    public FlowRegistry(Path projectRoot, Path templatesRoot, boolean validateAll) throws Exception {
        this(projectRoot, templatesRoot, validateAll, null, null);
    }

    public FlowRegistry(Path projectRoot, att.config.FrameworkConfig config, boolean validateAll) throws Exception {
        this(projectRoot, PackageFlowRoots.resolve(projectRoot, config), validateAll, null, null);
    }

    /**
     * Builds a registry from captured Flow bytes and the same snapshot's Template descriptor paths.
     * Both collections must represent the complete descriptor metadata captured for the Flow tree.
     */
    public FlowRegistry(Path projectRoot, Path templatesRoot, boolean validateAll,
                        Map<Path, byte[]> capturedSources, Set<Path> capturedTemplateDescriptors) throws Exception {
        this(projectRoot, PackageFlowRoots.resolve(projectRoot, PackageFlowRoots.DEFAULT_FLOW_ROOT, templatesRoot),
                validateAll, capturedSources, capturedTemplateDescriptors);
    }

    public FlowRegistry(Path projectRoot, PackageFlowRoots.Resolution resolution, boolean validateAll,
                        Map<Path, byte[]> capturedSources, Set<Path> capturedTemplateDescriptors) throws Exception {
        PackageResourceResolver resources = new PackageResourceResolver(projectRoot);
        this.projectRoot = resources.packageRoot();
        this.frozen = false;
        this.snapshotMode = capturedSources != null;
        if (snapshotMode && capturedTemplateDescriptors == null)
            throw new IllegalArgumentException("Captured Template descriptor metadata is required with Flow source snapshots");
        if (!snapshotMode && capturedTemplateDescriptors != null && !capturedTemplateDescriptors.isEmpty())
            throw new IllegalArgumentException("Captured Template descriptors require captured Flow source snapshots");
        this.sourceSnapshots = new LinkedHashMap<Path, byte[]>();
        if (capturedSources != null) for (Map.Entry<Path, byte[]> entry : capturedSources.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) throw new IllegalArgumentException("Flow source snapshot is invalid");
            this.sourceSnapshots.put(entry.getKey().toAbsolutePath().normalize(), entry.getValue().clone());
        }
        this.capturedTemplateDescriptors = new LinkedHashSet<Path>();
        if (capturedTemplateDescriptors != null) for (Path descriptor : capturedTemplateDescriptors) {
            if (descriptor == null) throw new IllegalArgumentException("Template descriptor snapshot is invalid");
            this.capturedTemplateDescriptors.add(descriptor.toAbsolutePath().normalize());
        }
        List<Path> resolvedRoots = new ArrayList<Path>();
        if (resolution == null) throw new IllegalArgumentException("Flow root resolution is required");
        if (snapshotMode) {
            if (!resolution.existingRoots().isEmpty()) resolvedRoots.addAll(resolution.existingRoots());
            else if (resolution.configuredFlowRoot() != null) resolvedRoots.add(resolution.configuredFlowRoot().toAbsolutePath().normalize());
        } else {
            resolvedRoots.addAll(resolution.existingRoots());
        }
        this.roots = Collections.unmodifiableList(new ArrayList<Path>(resolvedRoots));
        if (snapshotMode || !roots.isEmpty()) {
            indexDescriptors();
            if (validateAll) {
                if (!descriptorsWithoutId.isEmpty()) {
                    Path descriptor = descriptorsWithoutId.get(0);
                    throw att.validation.DiagnosticException.of(att.validation.DiagnosticCodes.TEMPLATE_INVALID,
                            "Flow descriptor has no static canonical id", descriptor.toString(),
                            "Add an id ending in .vN; dynamic Flow ids are not supported.")
                            .withLocation(descriptor.toString(), "id", null, null, null, null, null);
                }
                for (String id : new ArrayList<String>(descriptors.keySet())) loadId(id);
                validateGraph();
            }
        }
    }

    private FlowRegistry(Path projectRoot, List<Path> roots, Map<String, FlowDefinition> compiled) {
        this.projectRoot = projectRoot;
        this.roots = Collections.unmodifiableList(new ArrayList<Path>(roots));
        this.byId.putAll(compiled);
        this.descriptors.clear();
        this.descriptorsWithoutId.clear();
        this.parsedCount = compiled.size();
        this.frozen = true;
        this.snapshotMode = false;
        this.sourceSnapshots = Collections.emptyMap();
        this.capturedTemplateDescriptors = Collections.emptySet();
    }

    public FlowDefinition get(String id) {
        try { return loadId(id); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
    }

    public List<FlowDefinition> all() { return Collections.unmodifiableList(new ArrayList<FlowDefinition>(byId.values())); }
    /** Canonical descriptor IDs available for target discovery; IDs are not loaded or executed here. */
    public List<String> ids() {
        List<String> result = new ArrayList<String>(descriptors.keySet());
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }
    /** Returns the indexed descriptor for a discovered ID without compiling it. */
    public Path descriptorPath(String id) { return descriptors.get(id); }
    /** Returns a defensive copy of the exact descriptor bytes used to compile a loaded Flow. */
    public byte[] descriptorBytes(String id) {
        Path descriptor = descriptors.get(id);
        if (descriptor == null) return null;
        byte[] captured = sourceSnapshots.get(descriptor.toAbsolutePath().normalize());
        return captured == null ? null : captured.clone();
    }
    public int size() { return byId.size(); }
    public int parsedCount() { return parsedCount; }

    /**
     * Compiles the selected Template's Flow dependency closure and returns a
     * read-only registry safe to share between concurrent load iterations.
     */
    public synchronized FlowRegistry freezeFor(StageTemplate template) {
        if (frozen) return this;
        if (template != null) {
            for (TemplateAction action : template.actions()) {
                if ("flow".equalsIgnoreCase(action.type())) validateInvocation(action);
            }
        }
        return new FlowRegistry(projectRoot, roots, byId);
    }

    public static boolean isCanonicalId(String value) {
        return value != null && !value.contains("${") && !value.contains("#{") && ID.matcher(value).matches();
    }

    public void validateInvocation(TemplateAction action) {
        if (!isCanonicalId(action.use())) throw new IllegalArgumentException("Flow use must be one static canonical ID ending in .vN: " + action.use());
        if (action.raw().containsKey("with")) throw new IllegalArgumentException("Flow action with is not supported: " + action.id());
        FlowDefinition target = get(action.use());
        if (target == null) throw new IllegalArgumentException("Unresolved Flow reference '" + action.use() + "'");
        resolve(target, new ArrayList<String>(), 1);
    }

    private void indexDescriptors() throws Exception {
        List<Path> files = new ArrayList<Path>();
        List<Path> allowedRoots = new ArrayList<Path>();
        for (Path root : roots) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (snapshotMode) allowedRoots.add(normalizedRoot);
            else if (Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(root) || !Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    throw new IllegalArgumentException("Flow root must be a non-symlink directory: " + root);
                allowedRoots.add(root.toRealPath());
            }
        }
        if (snapshotMode) {
            for (Path path : sourceSnapshots.keySet()) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!"flow.yaml".equals(normalized.getFileName().toString()) || !underAnyRoot(normalized, allowedRoots))
                    throw new IllegalArgumentException("Captured Flow descriptor is outside the Flow root");
                files.add(normalized);
            }
            for (Path path : capturedTemplateDescriptors) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!"template.yaml".equals(normalized.getFileName().toString()) || !underAnyRoot(normalized, allowedRoots))
                    throw new IllegalArgumentException("Captured Template descriptor is outside the Flow root");
            }
        } else {
            for (Path root : roots) {
                if (!Files.exists(root)) continue;
                try (Stream<Path> paths = Files.walk(root)) {
                    java.util.Iterator<Path> iterator = paths.filter(path -> path.getFileName().toString().equals("flow.yaml")).iterator();
                    while (iterator.hasNext()) files.add(iterator.next());
                }
            }
        }
        Collections.sort(files);
        for (Path descriptor : files) {
            if (!snapshotMode && (Files.isSymbolicLink(descriptor) || !Files.isRegularFile(descriptor, java.nio.file.LinkOption.NOFOLLOW_LINKS)))
                throw att.validation.DiagnosticException.of(att.validation.DiagnosticCodes.PATH_INVALID,
                        "Unsafe Flow descriptor", "Flow descriptor must be a regular non-symlink file: " + descriptor,
                        "Use a regular flow.yaml below the Package Flow roots.").withLocation(descriptor.toString(), "flow", null, null, null, null, null);
            Path real = snapshotMode ? descriptor : descriptor.toRealPath();
            if (!underAnyRoot(real, allowedRoots))
                throw att.validation.DiagnosticException.of(att.validation.DiagnosticCodes.PATH_INVALID,
                        "Flow descriptor escapes Flow root", "resolvedPath=" + real + ", allowedRoots=" + allowedRoots,
                        "Keep Flow descriptors below the Package Flow roots.").withLocation(descriptor.toString(), "flow", null, null, null, null, null);
            Path templateDescriptor = descriptor.getParent().resolve("template.yaml").toAbsolutePath().normalize();
            boolean hasTemplate = snapshotMode
                    ? capturedTemplateDescriptors.contains(templateDescriptor)
                    : Files.isRegularFile(templateDescriptor);
            if (hasTemplate)
                throw att.validation.DiagnosticException.of(att.validation.DiagnosticCodes.TEMPLATE_INVALID,
                        "Directory contains both template.yaml and flow.yaml", descriptor.getParent().toString(),
                        "Split the directory so it contains either a Template or a Flow descriptor.")
                        .withLocation(descriptor.toString(), "flow", null, null, null, null, null);
            byte[] bytes = sourceSnapshots.get(descriptor.toAbsolutePath().normalize());
            if (bytes == null) bytes = Files.readAllBytes(descriptor);
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            Matcher header = Pattern.compile("(?m)^id:[ \\t]*([A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.v[1-9][0-9]*)[ \\t]*(?:#.*)?$").matcher(text);
            if (!header.find()) { descriptorsWithoutId.add(descriptor); continue; }
            String id = header.group(1);
            Path previous = descriptors.put(id, descriptor);
            if (previous != null) throw att.validation.DiagnosticException.of(att.validation.DiagnosticCodes.TEMPLATE_INVALID,
                    "Duplicate Flow ID '" + id + "'", previous.getParent() + ", " + descriptor.getParent(),
                    "Give each Flow a unique canonical id across flows/ and templates/flows.").withLocation(descriptor.toString(), "id", null, null, null, null, null);
        }
    }

    private static boolean underAnyRoot(Path path, List<Path> allowedRoots) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : allowedRoots) if (normalized.startsWith(root)) return true;
        return false;
    }

    private FlowDefinition loadId(String id) throws Exception {
        if (frozen) return byId.get(id);
        if (byId.containsKey(id)) return byId.get(id);
        Path descriptor = descriptors.get(id);
        if (descriptor == null) return null;
        FlowDefinition flow;
        try { flow = load(descriptor); }
        catch (Exception error) {
            att.validation.JsonSchemaVerifier.SchemaValidationException schema = att.validation.JsonSchemaVerifier.SchemaValidationException.find(error);
            String field = schema == null ? "flow" : schema.field();
            att.validation.DiagnosticException diagnostic = att.validation.DiagnosticException.wrap(att.validation.DiagnosticCodes.TEMPLATE_INVALID,
                    "Invalid Flow '" + id + "'", error, descriptor.toString(), field,
                    "Correct the indicated Flow descriptor field.");
            if (schema == null) throw YamlSupport.locate(diagnostic, descriptor, field);
            throw YamlSupport.locateSchema(diagnostic, descriptor, schema.structuredViolations());
        } finally {
            // Snapshot-mode captures are scoped to the validation pass. Non-snapshot loads promote
            // the capture into YamlSupport's bounded process-wide map so nested runtime diagnostics
            // keep source marks without retaining every descriptor in an unbounded ThreadLocal.
            if (snapshotMode) YamlSupport.clearSnapshotSource(descriptor);
            else YamlSupport.promoteSnapshotSource(descriptor);
        }
        parsedCount++;
        if (!id.equals(flow.id())) throw new IllegalArgumentException("Flow descriptor id changed while compiling: expected " + id + " but found " + flow.id());
        byId.put(id, flow);
        return flow;
    }

    private FlowDefinition load(Path descriptor) throws Exception {
        Map<String, Object> map;
        {
            Path key = descriptor.toAbsolutePath().normalize();
            byte[] snapshot = sourceSnapshots.get(key);
            if (snapshot == null && !snapshotMode) {
                snapshot = Files.readAllBytes(descriptor);
                sourceSnapshots.put(key, snapshot.clone());
            }
            Object loaded = snapshot == null ? null : YamlSupport.load(descriptor, snapshot);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("Flow must be a YAML map: " + descriptor);
            map = objectMap((Map<?, ?>) loaded);
        }
        rejectLegacyResultFields(map, descriptor);
        Object configuredVersion = map.get("schemaVersion");
        String flowVersion = configuredVersion == null ? "" : String.valueOf(configuredVersion);
        boolean current = Version.FLOW_SCHEMA.equals(flowVersion);
        boolean historicalV35 = Version.HISTORICAL_FLOW_SCHEMA_V3_5.equals(flowVersion);
        boolean historicalV34 = Version.HISTORICAL_FLOW_SCHEMA_V3_4.equals(flowVersion);
        boolean historical = historicalV35 || historicalV34;
        if (current) {
            rejectRemovedActionContract(map, descriptor);
        }
        if (!current && !historical) {
            Path declaredSchema = att.validation.SchemaFiles.resolveVersion(projectRoot, flowVersion);
            Path currentSchema = att.validation.SchemaFiles.resolve(projectRoot, "att-flow-v3.6.schema.json");
            att.validation.SchemaMigrationGuidance.verify(declaredSchema, currentSchema, map,
                    flowVersion, Version.FLOW_SCHEMA);
            throw new IllegalArgumentException("Unsupported Flow schemaVersion '" + flowVersion
                    + "'; ATT 4.1.0 supports only " + Version.FLOW_SCHEMA
                    + ". Migrate nested Actions to the current typed-result contract and see docs/reference/appendices/migrations.md.");
        }
        Path schema = att.validation.SchemaFiles.resolve(projectRoot,
                current ? "att-flow-v3.6.schema.json" : historicalV35 ? "att-flow-v3.5.schema.json" : "att-flow-v3.4.schema.json");
        if (current) att.validation.SchemaMigrationGuidance.verify(schema, schema, map, flowVersion, Version.FLOW_SCHEMA);
        else att.validation.JsonSchemaVerifier.verify(schema, map);
        Map<String, Object> actionContract = new LinkedHashMap<String, Object>();
        String templateVersion = current ? Version.TEMPLATE_SCHEMA
                : historicalV35 ? Version.HISTORICAL_TEMPLATE_SCHEMA_V3_5 : Version.HISTORICAL_TEMPLATE_SCHEMA_V3_4;
        actionContract.put("schemaVersion", templateVersion);
        actionContract.put("name", text(map.get("name")));
        actionContract.put("description", text(map.get("description")));
        actionContract.put("actions", map.get("actions"));
        Path templateSchema = att.validation.SchemaFiles.resolve(projectRoot,
                current ? "att-template-v3.6.schema.json" : historicalV35 ? "att-template-v3.5.schema.json" : "att-template-v3.4.schema.json");
        att.validation.JsonSchemaVerifier.verify(templateSchema, actionContract);
        SchemaSupport.requireVersion(map, flowVersion, "flow");
        SchemaSupport.rejectUnknown(map, "flow", "schemaVersion", "id", "name", "description", "actions");
        String id = text(map.get("id"));
        if (!isCanonicalId(id)) throw new IllegalArgumentException("Flow id must be a static canonical ID ending in .vN: " + id);
        SchemaSupport.string(map.get("name"), "flow.name", true);
        SchemaSupport.string(map.get("description"), "flow.description", true);
        return new FlowDefinition(id, text(map.get("name")), text(map.get("description")), descriptor.getParent(),
                flowVersion, actions(map.get("actions"), id, templateVersion, historical));
    }

    private void rejectRemovedActionContract(Map<String, Object> flow, Path descriptor) {
        Object configured = flow.get("actions");
        if (!(configured instanceof Map)) return;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            if (SchemaSupport.isDisabledKey(entry.getKey())) continue;
            if (!(entry.getValue() instanceof Map)) continue;
            Map<?, ?> action = (Map<?, ?>) entry.getValue();
            String id = String.valueOf(entry.getKey());
            for (String field : new String[]{"result", "render", "file", "fields"}) if (action.containsKey(field)) {
                String guidance = "Remove the common Action result block. Keep the typed value in output.result; helper evidence uses helper evidence.output. Render returns String; Log uses value and format.";
                throw migrationError(descriptor, id, field, guidance);
            }
        }
    }

    private void rejectLegacyResultFields(Map<String, Object> flow, Path descriptor) {
        Object configured = flow.get("actions");
        if (!(configured instanceof Map)) return;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            if (SchemaSupport.isDisabledKey(entry.getKey())) continue;
            if (!(entry.getValue() instanceof Map)) continue;
            Map<?, ?> action = (Map<?, ?>) entry.getValue();
            String id = String.valueOf(entry.getKey());
            if (action.containsKey("renderAs")) {
                String old = String.valueOf(action.get("renderAs"));
                String suggestion = "Legacy field 'renderAs' is no longer supported. Render returns the exact rendered String and does not create a file. The old format hint has no replacement.";
                throw migrationError(descriptor, id, "renderAs", suggestion);
            }
            if (action.containsKey("saveAs")) {
                Object old = action.get("saveAs");
                Map<?, ?> save = old instanceof Map ? (Map<?, ?>) old : Collections.emptyMap();
                String format = save.get("format") == null ? null : String.valueOf(save.get("format"));
                String path = save.get("path") == null ? (old instanceof String ? String.valueOf(old) : null) : String.valueOf(save.get("path"));
                StringBuilder suggestion = new StringBuilder("Legacy field 'saveAs' is no longer supported. Render now returns String and does not create a file. Pass its output.result directly to the consuming Tool/resource.");
                if (format != null && !format.trim().isEmpty()) suggestion.append(" Legacy format was '").append(format).append("'.");
                if (path != null) suggestion.append(" Legacy path '").append(path).append("' has no implicit replacement.");
                if (Boolean.TRUE.equals(save.get("overwrite"))) suggestion.append(" Legacy overwrite is not carried forward.");
                throw migrationError(descriptor, id, "saveAs", suggestion.toString());
            }
        }
    }

    private att.validation.DiagnosticException migrationError(Path descriptor, String action, String field, String suggestion) {
        String fullField = "actions." + action + "." + field;
        return new att.validation.DiagnosticException(att.validation.DiagnosticCodes.TEMPLATE_INVALID,
                "Legacy Action field '" + field + "' is not supported", "Flow Action " + action + " uses " + field,
                descriptor.toString(), fullField, null, null, null, null, action, suggestion, null);
    }

    private List<TemplateAction> actions(Object configured, String id, String templateSchema, boolean historical) {
        if (!(configured instanceof Map) || ((Map<?, ?>) configured).isEmpty()) throw new IllegalArgumentException("Flow actions must be a non-empty ordered map: " + id);
        List<TemplateAction> result = new ArrayList<TemplateAction>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            if (SchemaSupport.isDisabledKey(entry.getKey())) continue;
            String key = String.valueOf(entry.getKey());
            if (key.trim().isEmpty() || key.contains(".") || !(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Invalid Flow action: " + id + "." + key);
            att.testdata.TestdataSyntax.rejectDirectReferences(entry.getValue(), "Flow action " + id + "." + key);
            TemplateAction action = new TemplateAction(key, objectMap((Map<?, ?>) entry.getValue()), templateSchema);
            validateActionShape(action, id, historical);
            result.add(action);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Flow actions must contain at least one active Action: " + id);
        return result;
    }

    private void validateActionShape(TemplateAction action, String flowId, boolean historical) {
        String type = action.type().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Arrays.asList(historical ? "render" : "", "tool", "db", "assert", "log", "assign", historical ? "" : "set", "flow").contains(type)) throw new IllegalArgumentException("Unsupported Flow action type: " + type);
        if (action.raw().containsKey("with")) throw new IllegalArgumentException("Flow action with is not supported: " + flowId + "." + action.id());
        if ("flow".equals(type) && action.use().trim().isEmpty()) throw new IllegalArgumentException("Flow action use is required: " + flowId + "." + action.id());
        if (!"flow".equals(type) && action.raw().containsKey("use")) throw new IllegalArgumentException("use is valid only for Flow actions: " + flowId + "." + action.id());
        if ("tool".equals(type) && action.call().trim().isEmpty()) throw new IllegalArgumentException("Tool action call is required: " + flowId + "." + action.id());
        if ("assign".equals(type) && (action.name().trim().isEmpty() || action.expression().trim().isEmpty())) throw new IllegalArgumentException("Assign action name and expression are required: " + flowId + "." + action.id());
        if ("set".equals(type) && (action.name().trim().isEmpty() || action.expression().trim().isEmpty())) throw new IllegalArgumentException("Set action name and expression are required: " + flowId + "." + action.id());
        if ("set".equals(type) && !action.name().matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Set action name must match [A-Za-z_][A-Za-z0-9_]*: " + flowId + "." + action.id());
        if ("assert".equals(type) && action.assertion().trim().isEmpty()) throw new IllegalArgumentException("Assert action assert is required: " + flowId + "." + action.id());
    }

    private void validateGraph() {
        for (FlowDefinition flow : byId.values()) visit(flow, new ArrayList<String>(), 1);
    }

    private void resolve(FlowDefinition flow, List<String> path, int depth) {
        visit(flow, path, depth);
    }

    private void visit(FlowDefinition flow, List<String> path, int depth) {
        if (path.contains(flow.id())) {
            List<String> cycle = new ArrayList<String>(path); cycle.add(flow.id());
            throw new IllegalArgumentException("Flow dependency cycle detected: " + String.join(" -> ", cycle));
        }
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Maximum Flow nesting depth " + MAX_DEPTH + " exceeded: " + String.join(" -> ", path));
        List<String> next = new ArrayList<String>(path); next.add(flow.id());
        for (TemplateAction action : flow.actions()) if ("flow".equalsIgnoreCase(action.type())) {
            if (!isCanonicalId(action.use())) throw new IllegalArgumentException("Flow use must be one static canonical ID ending in .vN: " + action.use());
            FlowDefinition target = get(action.use());
            if (target == null) throw new IllegalArgumentException("Unresolved Flow reference '" + action.use() + "'");
            visit(target, next, depth + 1);
        }
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static Map<String, Object> objectMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : value.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }
}
