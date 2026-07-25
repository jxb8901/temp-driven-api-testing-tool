package att.flow;

import att.Version;
import att.config.SchemaSupport;
import att.config.YamlSupport;
import att.template.TemplateAction;
import att.template.UnifiedTemplateEngine;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Package-local immutable Flow registry, contract validator and static graph resolver. */
public final class FlowRegistry {
    public static final int MAX_DEPTH = 3;
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.v[1-9][0-9]*$");
    private static final Pattern EXACT = Pattern.compile("^\\$\\{(.+)}$");
    private static final Set<String> TYPES = new LinkedHashSet<String>(java.util.Arrays.asList("string", "integer", "number", "boolean", "object", "array"));
    private final Path projectRoot;
    private final Path root;
    private final Map<String, FlowDefinition> byId = new LinkedHashMap<String, FlowDefinition>();
    private final Map<String, Path> descriptors = new LinkedHashMap<String, Path>();
    private final List<Path> descriptorsWithoutId = new ArrayList<Path>();
    private int parsedCount;

    public FlowRegistry(Path projectRoot, Path templatesRoot) throws Exception {
        this(projectRoot, templatesRoot, true);
    }

    public FlowRegistry(Path projectRoot, Path templatesRoot, boolean validateAll) throws Exception {
        this.projectRoot = att.core.IdentifierValidator.canonicalPath(projectRoot, "package root");
        Path templates = templatesRoot.isAbsolute() ? templatesRoot : projectRoot.resolve(templatesRoot);
        Path canonicalTemplates = att.core.IdentifierValidator.canonicalPath(templates, "templates root");
        if (!canonicalTemplates.startsWith(this.projectRoot)) throw new IllegalArgumentException("Templates root escapes package root: " + templatesRoot);
        this.root = canonicalTemplates.resolve("flows").normalize();
        if (Files.exists(root)) {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) throw new IllegalArgumentException("Flow root must be a non-symlink directory: " + root);
            indexDescriptors();
            if (validateAll) {
                if (!descriptorsWithoutId.isEmpty()) throw new IllegalArgumentException("Flow descriptor has no static canonical id: " + descriptorsWithoutId.get(0));
                for (String id : new ArrayList<String>(descriptors.keySet())) loadId(id);
                validateGraph();
            }
        }
    }

    public FlowDefinition get(String id) {
        try { return loadId(id); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
    }
    public List<FlowDefinition> all() { return Collections.unmodifiableList(new ArrayList<FlowDefinition>(byId.values())); }
    public int size() { return byId.size(); }
    public int parsedCount() { return parsedCount; }

    public static boolean isCanonicalId(String value) {
        return value != null && !value.contains("${") && !value.contains("#{") && ID.matcher(value).matches();
    }

    public void validateInvocation(TemplateAction action) {
        if (!isCanonicalId(action.use())) {
            throw new IllegalArgumentException("Flow use must be one static canonical ID ending in .vN: " + action.use());
        }
        FlowDefinition target = get(action.use());
        if (target == null) throw new IllegalArgumentException("Unresolved Flow reference '" + action.use() + "'");
        validateBindings(action, target);
        try { resolve(target, new ArrayList<String>(), 1); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
    }

    public static boolean matchesType(String type, Object value) {
        if (value == null) return true;
        if ("string".equals(type)) return value instanceof String;
        if ("boolean".equals(type)) return value instanceof Boolean;
        if ("object".equals(type)) return value instanceof Map;
        if ("array".equals(type)) return value instanceof List;
        if ("number".equals(type)) return value instanceof Number;
        if ("integer".equals(type)) {
            if (!(value instanceof Number)) return false;
            try { return new BigDecimal(String.valueOf(value)).stripTrailingZeros().scale() <= 0; }
            catch (Exception ignored) { return false; }
        }
        return false;
    }

    public static void requireType(String owner, String type, Object value, boolean allowNull) {
        if (value == null && allowNull) return;
        if (!matchesType(type, value) || value == null) throw new IllegalArgumentException(owner + " must be " + type + " but was " + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private void indexDescriptors() throws Exception {
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> paths = Files.walk(root)) {
            java.util.Iterator<Path> iterator = paths.filter(path -> path.getFileName().toString().equals("flow.yaml")).iterator();
            while (iterator.hasNext()) files.add(iterator.next());
        }
        Collections.sort(files);
        for (Path descriptor : files) {
            if (Files.isSymbolicLink(descriptor) || !Files.isRegularFile(descriptor, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Flow descriptor must be a regular non-symlink file: " + descriptor);
            Path real = descriptor.toRealPath();
            if (!real.startsWith(root.toRealPath())) throw new IllegalArgumentException("Flow descriptor escapes flow root: " + descriptor);
            if (Files.isRegularFile(descriptor.getParent().resolve("template.yaml"))) throw new IllegalArgumentException("A directory cannot contain both template.yaml and flow.yaml: " + descriptor.getParent());
            String text = new String(Files.readAllBytes(descriptor), java.nio.charset.StandardCharsets.UTF_8);
            Matcher header = Pattern.compile("(?m)^id:[ \\t]*([A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.v[1-9][0-9]*)[ \\t]*(?:#.*)?$").matcher(text);
            if (!header.find()) { descriptorsWithoutId.add(descriptor); continue; }
            String id = header.group(1);
            Path previous = descriptors.put(id, descriptor);
            if (previous != null) throw new IllegalArgumentException("Duplicate Flow ID '" + id + "': " + previous.getParent() + ", " + descriptor.getParent());
        }
    }

    private FlowDefinition loadId(String id) throws Exception {
        if (byId.containsKey(id)) return byId.get(id);
        Path descriptor = descriptors.get(id);
        if (descriptor == null) return null;
        FlowDefinition flow = load(descriptor);
        parsedCount++;
        if (!id.equals(flow.id())) throw new IllegalArgumentException("Flow descriptor id changed while compiling: expected " + id + " but found " + flow.id());
        byId.put(id, flow);
        return flow;
    }

    @SuppressWarnings("unchecked")
    private FlowDefinition load(Path descriptor) throws Exception {
        Map<String, Object> map;
        try (Reader reader = Files.newBufferedReader(descriptor)) {
            Object loaded;
            synchronized (YamlSupport.parser()) { loaded = YamlSupport.parser().load(reader); }
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("Flow must be a YAML map: " + descriptor);
            map = objectMap((Map<?, ?>) loaded);
        }
        Path schema = projectRoot.resolve("schemas/att-flow-v3.0.schema.json");
        if (Files.isRegularFile(schema)) att.validation.JsonSchemaVerifier.verify(schema, map);
        Map<String, Object> actionContract = new LinkedHashMap<String, Object>();
        actionContract.put("schemaVersion", Version.TEMPLATE_SCHEMA);
        actionContract.put("name", text(map.get("name")));
        actionContract.put("description", text(map.get("description")));
        actionContract.put("actions", map.get("actions"));
        Path templateSchema = projectRoot.resolve("schemas/att-template-v3.0.schema.json");
        if (Files.isRegularFile(templateSchema)) att.validation.JsonSchemaVerifier.verify(templateSchema, actionContract);
        SchemaSupport.requireVersion(map, Version.FLOW_SCHEMA, "flow");
        SchemaSupport.rejectUnknown(map, "flow", "schemaVersion", "id", "name", "description", "inputs", "actions", "outputs");
        String id = text(map.get("id"));
        if (!isCanonicalId(id)) throw new IllegalArgumentException("Flow id must be a static canonical ID ending in .vN: " + id);
        SchemaSupport.string(map.get("name"), "flow.name", true);
        SchemaSupport.string(map.get("description"), "flow.description", true);
        Map<String, FlowDefinition.Input> inputs = inputs(map.get("inputs"), id);
        List<TemplateAction> actions = actions(map.get("actions"), id);
        Map<String, FlowDefinition.Output> outputs = outputs(map.get("outputs"), id);
        FlowDefinition result = new FlowDefinition(id, text(map.get("name")), text(map.get("description")), descriptor.getParent(), inputs, actions, outputs);
        validateLocalContext(result);
        return result;
    }

    private Map<String, FlowDefinition.Input> inputs(Object configured, String id) {
        if (!(configured instanceof Map)) throw new IllegalArgumentException("Flow inputs must be a map: " + id);
        Map<String, FlowDefinition.Input> result = new LinkedHashMap<String, FlowDefinition.Input>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Invalid Flow input: " + id + "." + name);
            Map<String, Object> value = objectMap((Map<?, ?>) entry.getValue());
            SchemaSupport.rejectUnknown(value, "inputs." + name, "type", "required", "default");
            String type = text(value.get("type"));
            if (!TYPES.contains(type)) throw new IllegalArgumentException("Unsupported Flow input type: " + type);
            boolean required = value.get("required") != null && Boolean.parseBoolean(String.valueOf(value.get("required")));
            boolean hasDefault = value.containsKey("default");
            if (required && hasDefault) throw new IllegalArgumentException("Flow input cannot be required and define default: " + id + "." + name);
            if (hasDefault) requireType("Flow input default " + id + "." + name, type, value.get("default"), false);
            result.put(name, new FlowDefinition.Input(type, required, hasDefault, value.get("default")));
        }
        return result;
    }

    private List<TemplateAction> actions(Object configured, String id) {
        if (!(configured instanceof Map) || ((Map<?, ?>) configured).isEmpty()) throw new IllegalArgumentException("Flow actions must be a non-empty ordered map: " + id);
        List<TemplateAction> result = new ArrayList<TemplateAction>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.trim().isEmpty() || key.contains(".") || !(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Invalid Flow action: " + id + "." + key);
            Map<String, Object> actionValues = objectMap((Map<?, ?>) entry.getValue());
            if (actionValues.get("with") != null && !(actionValues.get("with") instanceof Map)) {
                throw new IllegalArgumentException("Flow action with must be a map: " + id + "." + key);
            }
            TemplateAction action = new TemplateAction(key, actionValues, Version.TEMPLATE_SCHEMA);
            validateActionShape(action, id);
            result.add(action);
        }
        return result;
    }

    private Map<String, FlowDefinition.Output> outputs(Object configured, String id) {
        if (!(configured instanceof Map)) throw new IllegalArgumentException("Flow outputs must be a map: " + id);
        Map<String, FlowDefinition.Output> result = new LinkedHashMap<String, FlowDefinition.Output>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Invalid Flow output: " + id + "." + name);
            Map<String, Object> value = objectMap((Map<?, ?>) entry.getValue());
            SchemaSupport.rejectUnknown(value, "outputs." + name, "type", "from");
            String type = text(value.get("type")); String from = text(value.get("from"));
            if (!TYPES.contains(type) || !EXACT.matcher(from).matches()) throw new IllegalArgumentException("Flow output requires supported type and one exact ${...} source: " + id + "." + name);
            if (from.contains("#{")) throw new IllegalArgumentException("Flow output cannot invoke a Tool: " + id + "." + name);
            result.put(name, new FlowDefinition.Output(type, from));
        }
        return result;
    }

    private void validateActionShape(TemplateAction action, String flowId) {
        String type = action.type().toLowerCase(java.util.Locale.ROOT);
        if (!java.util.Arrays.asList("render", "tool", "db", "assert", "log", "assign", "flow").contains(type)) throw new IllegalArgumentException("Unsupported Flow action type: " + type);
        if ("flow".equals(type) && action.use().trim().isEmpty()) throw new IllegalArgumentException("Flow action use is required: " + flowId + "." + action.id());
        if (!"flow".equals(type) && (action.raw().containsKey("use") || action.raw().containsKey("with"))) throw new IllegalArgumentException("use/with are valid only for Flow actions: " + flowId + "." + action.id());
        if ("tool".equals(type) && action.call().trim().isEmpty()) throw new IllegalArgumentException("Tool action call is required: " + flowId + "." + action.id());
        if ("assign".equals(type) && (action.name().trim().isEmpty() || action.expression().trim().isEmpty())) throw new IllegalArgumentException("Assign action name and expression are required: " + flowId + "." + action.id());
        if ("assert".equals(type) && action.assertion().trim().isEmpty()) throw new IllegalArgumentException("Assert action assert is required: " + flowId + "." + action.id());
    }

    private void validateLocalContext(FlowDefinition flow) {
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null);
        Set<String> prior = new LinkedHashSet<String>();
        Set<String> assignments = new LinkedHashSet<String>();
        for (TemplateAction action : flow.actions()) {
            if (action.raw().containsKey("runAlways") || action.raw().containsKey("failureImpact") || action.raw().containsKey("totalTimeoutMs")) throw new IllegalArgumentException("Deferred Flow control field in " + flow.id() + "." + action.id());
            validateValue(action.raw(), engine, flow, prior, assignments, flow.id() + "." + action.id(), true);
            if ("assign".equalsIgnoreCase(action.type()) && !assignments.add(action.name())) throw new IllegalArgumentException("Duplicate Flow runtime assignment: " + flow.id() + "." + action.name());
            prior.add(action.id());
        }
        for (Map.Entry<String, FlowDefinition.Output> output : flow.outputs().entrySet()) validateValue(output.getValue().from(), engine, flow, prior, assignments, flow.id() + ".outputs." + output.getKey(), false);
    }

    @SuppressWarnings("unchecked")
    private void validateValue(Object value, UnifiedTemplateEngine engine, FlowDefinition flow, Set<String> prior,
                               Set<String> assignments, String owner, boolean allowOutput) {
        if (value instanceof Map) { for (Object nested : ((Map<?, ?>) value).values()) validateValue(nested, engine, flow, prior, assignments, owner, allowOutput); return; }
        if (value instanceof Iterable) { for (Object nested : (Iterable<?>) value) validateValue(nested, engine, flow, prior, assignments, owner, allowOutput); return; }
        if (!(value instanceof String)) return;
        String text = String.valueOf(value);
        for (String path : engine.parseContextPaths(text)) {
            String rootName = first(path);
            if (!("input".equals(rootName) || "actions".equals(rootName) || "runtime".equals(rootName) || "flow".equals(rootName) || (allowOutput && "output".equals(rootName)))) throw new IllegalArgumentException("Flow context may not access '${" + path + "}' in " + owner);
            if ("input".equals(rootName)) {
                String name = child(path, "input");
                if (name.isEmpty() || !flow.inputs().containsKey(name)) throw new IllegalArgumentException("Unknown Flow input reference '${" + path + "}' in " + owner);
            }
            if ("runtime".equals(rootName)) {
                String name = child(path, "runtime");
                if (name.isEmpty() || !assignments.contains(name)) throw new IllegalArgumentException("Flow runtime reference is not assigned yet: ${" + path + "} in " + owner);
            }
            if ("flow".equals(rootName)) {
                String name = child(path, "flow");
                if (!("id".equals(name) || "invocationId".equals(name) || "depth".equals(name))) throw new IllegalArgumentException("Unknown Flow metadata reference '${" + path + "}' in " + owner);
            }
            if ("actions".equals(rootName)) {
                String action = child(path, "actions");
                if (!prior.contains(action)) throw new IllegalArgumentException("Flow action reference is not available yet: ${" + path + "} in " + owner);
                String remainder = path.substring(("actions." + action).length());
                if (!(remainder.equals(".output") || remainder.startsWith(".output.") || remainder.startsWith(".output["))) throw new IllegalArgumentException("Flow callers may access only a prior Action output: ${" + path + "} in " + owner);
            }
        }
    }

    private void validateGraph() {
        for (FlowDefinition flow : byId.values()) {
            for (TemplateAction action : flow.actions()) if ("flow".equalsIgnoreCase(action.type())) validateInvocation(action);
            validateStaticTypes(flow);
        }
        for (FlowDefinition flow : byId.values()) visit(flow, new ArrayList<String>(), 1);
    }

    private void resolve(FlowDefinition flow, List<String> path, int depth) throws Exception {
        if (path.contains(flow.id())) { List<String> cycle = new ArrayList<String>(path); cycle.add(flow.id()); throw new IllegalArgumentException("Flow dependency cycle detected: " + String.join(" -> ", cycle)); }
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Maximum Flow nesting depth " + MAX_DEPTH + " exceeded: " + String.join(" -> ", path));
        List<String> next = new ArrayList<String>(path); next.add(flow.id());
        for (TemplateAction action : flow.actions()) if ("flow".equalsIgnoreCase(action.type())) {
            FlowDefinition target = loadId(action.use());
            if (target == null) throw new IllegalArgumentException("Unresolved Flow reference '" + action.use() + "'");
            validateBindings(action, target);
            resolve(target, next, depth + 1);
        }
        validateStaticTypes(flow);
    }

    private void validateStaticTypes(FlowDefinition flow) {
        Map<String, TemplateAction> prior = new LinkedHashMap<String, TemplateAction>();
        for (TemplateAction action : flow.actions()) {
            if ("flow".equalsIgnoreCase(action.type())) {
                FlowDefinition target = get(action.use());
                for (Map.Entry<String, Object> binding : action.with().entrySet()) {
                    String sourceType = declaredPathType(flow, prior, binding.getValue());
                    String targetType = target.inputs().get(binding.getKey()).type();
                    if (sourceType != null && !compatible(sourceType, targetType)) throw new IllegalArgumentException("Flow binding type mismatch: " + flow.id() + "." + action.id() + ".with." + binding.getKey() + " is " + sourceType + " but " + target.id() + " requires " + targetType);
                }
            }
            prior.put(action.id(), action);
        }
        for (Map.Entry<String, FlowDefinition.Output> output : flow.outputs().entrySet()) {
            String sourceType = declaredPathType(flow, prior, output.getValue().from());
            if (sourceType != null && !compatible(sourceType, output.getValue().type())) throw new IllegalArgumentException("Flow output type mismatch: " + flow.id() + "." + output.getKey() + " is declared " + output.getValue().type() + " but source is " + sourceType);
        }
    }

    private String declaredPathType(FlowDefinition owner, Map<String, TemplateAction> prior, Object value) {
        if (!(value instanceof String)) return null;
        Matcher matcher = EXACT.matcher(String.valueOf(value));
        if (!matcher.matches()) return null;
        String path = matcher.group(1);
        if (path.startsWith("input.")) {
            FlowDefinition.Input input = owner.inputs().get(first(path.substring(6)));
            return input == null ? null : input.type();
        }
        Matcher nested = Pattern.compile("^actions\\.([^.]+)\\.output\\.outputs\\.([^.\\[]+)$").matcher(path);
        if (!nested.matches()) return null;
        TemplateAction source = prior.get(nested.group(1));
        if (source == null || !"flow".equalsIgnoreCase(source.type())) return null;
        FlowDefinition definition = get(source.use());
        FlowDefinition.Output output = definition == null ? null : definition.outputs().get(nested.group(2));
        return output == null ? null : output.type();
    }

    private boolean compatible(String source, String target) {
        return source.equals(target) || ("integer".equals(source) && "number".equals(target));
    }

    private void visit(FlowDefinition flow, List<String> path, int depth) {
        if (path.contains(flow.id())) { List<String> cycle = new ArrayList<String>(path); cycle.add(flow.id()); throw new IllegalArgumentException("Flow dependency cycle detected: " + String.join(" -> ", cycle)); }
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Maximum Flow nesting depth " + MAX_DEPTH + " exceeded: " + String.join(" -> ", path));
        List<String> next = new ArrayList<String>(path); next.add(flow.id());
        for (TemplateAction action : flow.actions()) if ("flow".equalsIgnoreCase(action.type())) visit(get(action.use()), next, depth + 1);
    }

    private void validateBindings(TemplateAction action, FlowDefinition target) {
        for (String supplied : action.with().keySet()) if (!target.inputs().containsKey(supplied)) throw new IllegalArgumentException("Unknown input '" + supplied + "' for Flow " + target.id());
        for (Map.Entry<String, FlowDefinition.Input> entry : target.inputs().entrySet()) {
            if (entry.getValue().required() && !action.with().containsKey(entry.getKey())) throw new IllegalArgumentException("Missing required input '" + entry.getKey() + "' for Flow " + target.id());
            if (!action.with().containsKey(entry.getKey())) continue;
            Object value = action.with().get(entry.getKey());
            rejectCalls(value, "Flow binding " + action.id() + ".with." + entry.getKey());
            if (!(value instanceof String) || !String.valueOf(value).contains("${")) requireType("Flow binding " + action.id() + ".with." + entry.getKey(), entry.getValue().type(), value, !entry.getValue().required());
        }
    }

    private void rejectCalls(Object value, String owner) {
        if (value instanceof Map) { for (Object nested : ((Map<?, ?>) value).values()) rejectCalls(nested, owner); return; }
        if (value instanceof Iterable) { for (Object nested : (Iterable<?>) value) rejectCalls(nested, owner); return; }
        if (value instanceof String && String.valueOf(value).contains("#{")) throw new IllegalArgumentException(owner + " cannot invoke a Tool or built-in");
    }

    private static String child(String path, String root) {
        String prefix = root + ".";
        if (!path.startsWith(prefix)) return "";
        return first(path.substring(prefix.length()));
    }

    private static String first(String path) { int dot = path.indexOf('.'); int bracket = path.indexOf('['); int end = dot < 0 ? path.length() : dot; if (bracket >= 0 && bracket < end) end = bracket; return path.substring(0, end); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static Map<String, Object> objectMap(Map<?, ?> value) { Map<String, Object> result = new LinkedHashMap<String, Object>(); for (Map.Entry<?, ?> entry : value.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue()); return result; }
}
