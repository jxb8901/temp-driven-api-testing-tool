package att.flow;

import att.Version;
import att.config.SchemaSupport;
import att.config.YamlSupport;
import att.template.TemplateAction;
import att.template.StageTemplate;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Package-local immutable Flow registry and static dependency resolver. */
public final class FlowRegistry {
    public static final int MAX_DEPTH = 3;
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.v[1-9][0-9]*$");
    private final Path projectRoot;
    private final Path root;
    private final Map<String, FlowDefinition> byId = new LinkedHashMap<String, FlowDefinition>();
    private final Map<String, Path> descriptors = new LinkedHashMap<String, Path>();
    private final List<Path> descriptorsWithoutId = new ArrayList<Path>();
    private final boolean frozen;
    private int parsedCount;

    public FlowRegistry(Path projectRoot, Path templatesRoot) throws Exception {
        this(projectRoot, templatesRoot, true);
    }

    public FlowRegistry(Path projectRoot, Path templatesRoot, boolean validateAll) throws Exception {
        this.projectRoot = att.core.IdentifierValidator.canonicalPath(projectRoot, "package root");
        this.frozen = false;
        Path templates = templatesRoot.isAbsolute() ? templatesRoot : projectRoot.resolve(templatesRoot);
        Path canonicalTemplates = att.core.IdentifierValidator.canonicalPath(templates, "templates root");
        if (!canonicalTemplates.startsWith(this.projectRoot)) throw new IllegalArgumentException("Templates root escapes package root: " + templatesRoot);
        this.root = canonicalTemplates.resolve("flows").normalize();
        if (Files.exists(root)) {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) throw new IllegalArgumentException("Flow root must be a non-symlink directory: " + root);
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

    private FlowRegistry(Path projectRoot, Path root, Map<String, FlowDefinition> compiled) {
        this.projectRoot = projectRoot;
        this.root = root;
        this.byId.putAll(compiled);
        this.descriptors.clear();
        this.descriptorsWithoutId.clear();
        this.parsedCount = compiled.size();
        this.frozen = true;
    }

    public FlowDefinition get(String id) {
        try { return loadId(id); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
    }

    public List<FlowDefinition> all() { return Collections.unmodifiableList(new ArrayList<FlowDefinition>(byId.values())); }
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
        return new FlowRegistry(projectRoot, root, byId);
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
        try (Stream<Path> paths = Files.walk(root)) {
            java.util.Iterator<Path> iterator = paths.filter(path -> path.getFileName().toString().equals("flow.yaml")).iterator();
            while (iterator.hasNext()) files.add(iterator.next());
        }
        Collections.sort(files);
        for (Path descriptor : files) {
            if (Files.isSymbolicLink(descriptor) || !Files.isRegularFile(descriptor, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw att.validation.DiagnosticException.of(att.validation.DiagnosticCodes.PATH_INVALID,
                        "Unsafe Flow descriptor", "Flow descriptor must be a regular non-symlink file: " + descriptor,
                        "Use a regular flow.yaml below the package templates/flows directory.").withLocation(descriptor.toString(), "flow", null, null, null, null, null);
            Path real = descriptor.toRealPath();
            if (!real.startsWith(root.toRealPath()))
                throw att.validation.DiagnosticException.of(att.validation.DiagnosticCodes.PATH_INVALID,
                        "Flow descriptor escapes Flow root", "resolvedPath=" + real + ", allowedRoot=" + root,
                        "Keep Flow descriptors below templates/flows.").withLocation(descriptor.toString(), "flow", null, null, null, null, null);
            if (Files.isRegularFile(descriptor.getParent().resolve("template.yaml")))
                throw att.validation.DiagnosticException.of(att.validation.DiagnosticCodes.TEMPLATE_INVALID,
                        "Directory contains both template.yaml and flow.yaml", descriptor.getParent().toString(),
                        "Split the directory so it contains either a Template or a Flow descriptor.")
                        .withLocation(descriptor.toString(), "flow", null, null, null, null, null);
            String text = new String(Files.readAllBytes(descriptor), java.nio.charset.StandardCharsets.UTF_8);
            Matcher header = Pattern.compile("(?m)^id:[ \\t]*([A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.v[1-9][0-9]*)[ \\t]*(?:#.*)?$").matcher(text);
            if (!header.find()) { descriptorsWithoutId.add(descriptor); continue; }
            String id = header.group(1);
            Path previous = descriptors.put(id, descriptor);
            if (previous != null) throw att.validation.DiagnosticException.of(att.validation.DiagnosticCodes.TEMPLATE_INVALID,
                    "Duplicate Flow ID '" + id + "'", previous.getParent() + ", " + descriptor.getParent(),
                    "Give each Flow a unique canonical id.").withLocation(descriptor.toString(), "id", null, null, null, null, null);
        }
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
        }
        parsedCount++;
        if (!id.equals(flow.id())) throw new IllegalArgumentException("Flow descriptor id changed while compiling: expected " + id + " but found " + flow.id());
        byId.put(id, flow);
        return flow;
    }

    private FlowDefinition load(Path descriptor) throws Exception {
        Map<String, Object> map;
        {
            Object loaded = YamlSupport.load(descriptor);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("Flow must be a YAML map: " + descriptor);
            map = objectMap((Map<?, ?>) loaded);
        }
        rejectLegacyResultFields(map, descriptor);
        Object configuredVersion = map.get("schemaVersion");
        String flowVersion = configuredVersion == null ? "" : String.valueOf(configuredVersion);
        boolean currentVersion = Version.FLOW_SCHEMA.equals(flowVersion);
        boolean previousVersion = Version.PREVIOUS_FLOW_SCHEMA.equals(flowVersion);
        if (!currentVersion && !previousVersion) throw new IllegalArgumentException("Unsupported Flow schemaVersion: " + flowVersion);
        String suffix = currentVersion ? "v3.1" : "v3.0";
        Path schema = projectRoot.resolve("schemas/att-flow-" + suffix + ".schema.json");
        if (Files.isRegularFile(schema)) att.validation.JsonSchemaVerifier.verify(schema, map);
        Map<String, Object> actionContract = new LinkedHashMap<String, Object>();
        String templateVersion = currentVersion ? Version.TEMPLATE_SCHEMA : Version.PREVIOUS_TEMPLATE_SCHEMA;
        actionContract.put("schemaVersion", templateVersion);
        actionContract.put("name", text(map.get("name")));
        actionContract.put("description", text(map.get("description")));
        actionContract.put("actions", map.get("actions"));
        Path templateSchema = projectRoot.resolve("schemas/att-template-" + (currentVersion ? "v3.1" : "v3.0") + ".schema.json");
        if (Files.isRegularFile(templateSchema)) att.validation.JsonSchemaVerifier.verify(templateSchema, actionContract);
        SchemaSupport.requireVersion(map, flowVersion, "flow");
        SchemaSupport.rejectUnknown(map, "flow", "schemaVersion", "id", "name", "description", "actions");
        String id = text(map.get("id"));
        if (!isCanonicalId(id)) throw new IllegalArgumentException("Flow id must be a static canonical ID ending in .vN: " + id);
        SchemaSupport.string(map.get("name"), "flow.name", true);
        SchemaSupport.string(map.get("description"), "flow.description", true);
        return new FlowDefinition(id, text(map.get("name")), text(map.get("description")), descriptor.getParent(),
                actions(map.get("actions"), id, templateVersion));
    }

    private void rejectLegacyResultFields(Map<String, Object> flow, Path descriptor) {
        Object configured = flow.get("actions");
        if (!(configured instanceof Map)) return;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            if (!(entry.getValue() instanceof Map)) continue;
            Map<?, ?> action = (Map<?, ?>) entry.getValue();
            String id = String.valueOf(entry.getKey());
            if (action.containsKey("renderAs")) {
                String old = String.valueOf(action.get("renderAs"));
                String suggestion = "Legacy field 'renderAs' is no longer supported. Replace it with:\n  result:\n    format: " + old;
                if ("file".equalsIgnoreCase(old)) suggestion = "Legacy 'renderAs: file' mixed representation and persistence. Choose result.format and result.path explicitly, for example:\n  result:\n    format: text\n    path: rendered/{filename}";
                throw migrationError(descriptor, id, "renderAs", suggestion);
            }
            if (action.containsKey("saveAs")) {
                Object old = action.get("saveAs");
                Map<?, ?> save = old instanceof Map ? (Map<?, ?>) old : Collections.emptyMap();
                String format = save.get("format") == null ? null : String.valueOf(save.get("format"));
                String path = save.get("path") == null ? (old instanceof String ? String.valueOf(old) : null) : String.valueOf(save.get("path"));
                StringBuilder suggestion = new StringBuilder("Legacy field 'saveAs' is no longer supported. Replace it with:\n  result:\n");
                if (format != null && !format.trim().isEmpty()) suggestion.append("    format: ").append(format).append('\n');
                else suggestion.append("    # Choose result.format explicitly; the legacy default depends on the Action and call target.\n");
                if (path != null) suggestion.append("\n    path: ").append(path);
                if (Boolean.TRUE.equals(save.get("overwrite"))) suggestion.append("\n    overwrite: true");
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

    private List<TemplateAction> actions(Object configured, String id, String templateSchema) {
        if (!(configured instanceof Map) || ((Map<?, ?>) configured).isEmpty()) throw new IllegalArgumentException("Flow actions must be a non-empty ordered map: " + id);
        List<TemplateAction> result = new ArrayList<TemplateAction>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.trim().isEmpty() || key.contains(".") || !(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Invalid Flow action: " + id + "." + key);
            TemplateAction action = new TemplateAction(key, objectMap((Map<?, ?>) entry.getValue()), templateSchema);
            validateActionShape(action, id);
            result.add(action);
        }
        return result;
    }

    private void validateActionShape(TemplateAction action, String flowId) {
        String type = action.type().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Arrays.asList("render", "tool", "db", "assert", "log", "assign", "flow").contains(type)) throw new IllegalArgumentException("Unsupported Flow action type: " + type);
        if (action.raw().containsKey("with")) throw new IllegalArgumentException("Flow action with is not supported: " + flowId + "." + action.id());
        if ("flow".equals(type) && action.use().trim().isEmpty()) throw new IllegalArgumentException("Flow action use is required: " + flowId + "." + action.id());
        if (!"flow".equals(type) && action.raw().containsKey("use")) throw new IllegalArgumentException("use is valid only for Flow actions: " + flowId + "." + action.id());
        if ("tool".equals(type) && action.call().trim().isEmpty()) throw new IllegalArgumentException("Tool action call is required: " + flowId + "." + action.id());
        if ("assign".equals(type) && (action.name().trim().isEmpty() || action.expression().trim().isEmpty())) throw new IllegalArgumentException("Assign action name and expression are required: " + flowId + "." + action.id());
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
