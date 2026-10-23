package att.load;

import att.Version;
import att.config.YamlSupport;
import att.resource.PackageResourceResolver;
import att.validation.JsonSchemaVerifier;
import att.validation.SchemaFiles;
import java.nio.file.*;
import java.util.*;

/** Versioned target-agnostic authoring contract shared by CLI and Studio. Never executes traffic. */
public final class LoadTemplateCatalog {
    public static final String SCHEMA = "att-load-template/v1";
    public static final String BINDINGS_SCHEMA = "att-load-bindings/v1";
    public static final List<String> MODES = Collections.unmodifiableList(Arrays.asList(
            "single-vu", "single-tps", "single-workload-mix", "multi-vu", "multi-tps", "multi-workload-mix"));
    private static final String[] NAMES = {"Single VU", "Single TPS", "Single Workload Mix", "Multi-workload VU", "Multi-workload TPS", "Multi-workload Mix"};
    private final Path root;
    public LoadTemplateCatalog(Path root) { this.root = new PackageResourceResolver(root).packageRoot(); }

    public interface BusinessDefaults {
        Map<String,Object> read(String type, String id) throws Exception;
    }

    public Map<String,Object> load(String reference) throws Exception {
        Map<String,Object> definition;
        if (reference != null && reference.startsWith("builtin:")) {
            String mode = reference.substring("builtin:".length());
            if (!MODES.contains(mode)) throw invalid("templateId", "Unknown built-in template " + reference);
            definition = builtin(mode);
        } else definition = read(reference);
        return validate(definition);
    }

    public Map<String,Object> validate(Map<String,Object> definition) throws Exception {
        LoadScenarioLoader.rejectCustomExecutionId(definition);
        JsonSchemaVerifier.verify(SchemaFiles.resolveVersion(root, SCHEMA), definition);
        String mode = String.valueOf(definition.get("mode"));
        List<Map<String,Object>> workloads = rows(definition.get("workloads"));
        boolean multiple = mode.startsWith("multi-");
        boolean mixed = mode.endsWith("mix");
        if ((!multiple && workloads.size() != 1) || (multiple && workloads.size() < 2))
            throw invalid("workloads", "Mode " + mode + " requires " + (multiple ? "at least two workloads" : "exactly one workload"));
        Set<String> ids = new HashSet<String>();
        Map<String,Object> dummy = obj("schemaVersion", BINDINGS_SCHEMA);
        Map<String,Object> bindings = new LinkedHashMap<String,Object>();
        for (Map<String,Object> workload : workloads) {
            String id = String.valueOf(workload.get("id"));
            if (!ids.add(id)) throw invalid("workloads.id", "Duplicate workload slot " + id);
            Map<String,Object> bound = new LinkedHashMap<String,Object>();
            if (mixed) {
                List<Map<String,Object>> mix = rows(workload.get("mix"));
                if (mix.size() < 2) throw invalid("workloads." + id + ".mix", "Mixed modes require at least two slots per workload");
                Map<String,Object> entries = new LinkedHashMap<String,Object>();
                for (Map<String,Object> entry : mix) {
                    String slot = String.valueOf(entry.get("id"));
                    if (entries.put(slot, obj("type", "tool", "id", "placeholder")) != null)
                        throw invalid("workloads." + id + ".mix", "Duplicate mix slot " + slot);
                }
                bound.put("mix", entries);
            } else {
                if (workload.containsKey("mix")) throw invalid("workloads." + id + ".mix", "Fixed-target mode cannot contain a mix");
                bound.put("target", obj("type", "tool", "id", "placeholder"));
            }
            bindings.put(id, bound);
        }
        dummy.put("bindings", bindings);
        // The ordinary Engine parser checks all pacing, evidence, timing and weight semantics.
        composeValidated(definition, dummy, null);
        return copy(definition);
    }

    public Map<String,Object> bindings(String reference) throws Exception { return read(reference); }

    public LoadScenario compose(String reference, Map<String,Object> bindings, BusinessDefaults defaults) throws Exception {
        return composeValidated(load(reference), bindings, defaults);
    }

    public LoadScenario compose(Map<String,Object> definition, Map<String,Object> bindings, BusinessDefaults defaults) throws Exception {
        return composeValidated(validate(definition), bindings, defaults);
    }

    private LoadScenario composeValidated(Map<String,Object> definition, Map<String,Object> descriptor,
                                          BusinessDefaults defaults) throws Exception {
        JsonSchemaVerifier.verify(SchemaFiles.resolveVersion(root, BINDINGS_SCHEMA), descriptor);
        String mode = String.valueOf(definition.get("mode"));
        boolean mixed = mode.endsWith("mix"), multiple = mode.startsWith("multi-"), arrival = mode.endsWith("tps");
        Map<String,Object> supplied = map(descriptor.get("bindings"));
        LinkedHashMap<String,Map<String,Object>> slots = new LinkedHashMap<String,Map<String,Object>>();
        for (Map<String,Object> workload : rows(definition.get("workloads"))) slots.put(String.valueOf(workload.get("id")), workload);
        instantiate(slots, supplied, multiple, "bindings");
        Map<String,Object> scenario = copy(map(definition.get("policy")));
        scenario.put("schemaVersion", Version.LOAD_SCHEMA_CURRENT);
        List<Map<String,Object>> workloads = new ArrayList<Map<String,Object>>();
        for (Map.Entry<String,Map<String,Object>> slot : slots.entrySet()) {
            String id = slot.getKey(); Map<String,Object> binding = map(supplied.get(id));
            Map<String,Object> workload = copy(slot.getValue()); workload.put("id", id);
            if (mixed) {
                if (binding.containsKey("target")) throw invalid("bindings." + id + ".target", "Mixed workload requires mix bindings");
                Map<String,Object> selected = map(binding.get("mix"));
                LinkedHashMap<String,Map<String,Object>> mixSlots = new LinkedHashMap<String,Map<String,Object>>();
                for (Map<String,Object> entry : rows(workload.get("mix"))) mixSlots.put(String.valueOf(entry.get("id")), entry);
                instantiate(mixSlots, selected, true, "bindings." + id + ".mix");
                List<Map<String,Object>> entries = new ArrayList<Map<String,Object>>();
                for (Map.Entry<String,Map<String,Object>> entry : mixSlots.entrySet()) {
                    Map<String,Object> result = copy(entry.getValue()); result.put("id", entry.getKey());
                    bind(result, map(selected.get(entry.getKey())), defaults);
                    entries.add(result);
                }
                workload.put("mix", entries);
            } else {
                if (binding.containsKey("mix")) throw invalid("bindings." + id + ".mix", "Fixed workload requires one target");
                bind(workload, map(binding.get("target")), defaults);
            }
            for (String field : Arrays.asList("inputs", "vars", "testdata"))
                if (binding.containsKey(field)) workload.put(field, merged(map(workload.get(field)), map(binding.get(field))));
            Map<String,Object> load = merged(map(scenario.get("load")), map(workload.get("load")));
            if (arrival != load.containsKey("arrivalRate") || arrival == load.containsKey("users"))
                throw invalid("workloads." + id + ".load", "Pacing must match template mode " + mode);
            workloads.add(workload);
        }
        scenario.put("workloads", workloads);
        return new LoadScenarioLoader(root).loadInline(scenario);
    }

    /** Extras are explicit clones of a named prototype. Existing required slots cannot disappear. */
    private static void instantiate(LinkedHashMap<String,Map<String,Object>> slots, Map<String,Object> values,
                                    boolean dynamic, String path) {
        for (String id : new ArrayList<String>(slots.keySet()))
            if (!values.containsKey(id)) throw invalid(path + "." + id, "Missing required binding slot");
        Map<String,Map<String,Object>> prototypes = new LinkedHashMap<String,Map<String,Object>>(slots);
        for (String id : values.keySet()) {
            if (slots.containsKey(id)) {
                if (map(values.get(id)).containsKey("prototype")) throw invalid(path + "." + id, "Existing slot cannot change prototype");
                continue;
            }
            String prototype = String.valueOf(map(values.get(id)).get("prototype"));
            if (!dynamic || !prototypes.containsKey(prototype))
                throw invalid(path + "." + id, "Extra slot requires an explicit valid prototype from the template");
            if (slots.size() >= 128) throw invalid(path, "At most 128 instantiated slots are supported");
            slots.put(id, copy(prototypes.get(prototype)));
        }
    }

    private static void bind(Map<String,Object> output, Map<String,Object> selection, BusinessDefaults defaults) throws Exception {
        String type = String.valueOf(selection.get("type")), id = String.valueOf(selection.get("id"));
        Map<String,Object> business = defaults == null ? new LinkedHashMap<String,Object>() : defaults.read(type, id);
        for (String field : Arrays.asList("inputs", "vars", "arguments")) {
            Map<String,Object> value = merged(map(business.get(field)), map(selection.get(field)));
            if (!value.isEmpty()) {
                if (("vars".equals(field) && "tool".equals(type)) || ("arguments".equals(field) && !"tool".equals(type)))
                    throw invalid(field, "Business field is incompatible with target type " + type);
                if (!"arguments".equals(field)) output.put(field, value);
            }
        }
        Map<String,Object> target = obj("type", type, "id", id);
        Map<String,Object> arguments = merged(map(business.get("arguments")), map(selection.get("arguments")));
        if (!arguments.isEmpty()) target.put("arguments", arguments);
        output.put("target", target);
    }

    /** Bounded package scan; templates remain separate from legacy policy/scenario discovery. */
    public Map<String,Object> list(String query, int offset, int limit) throws Exception {
        return list(query, offset, limit, Integer.MAX_VALUE);
    }

    /** Pages retain complete definitions; byte-limited callers follow nextOffset to fetch each definition. */
    public Map<String,Object> list(String query, int offset, int limit, int maxBytes) throws Exception {
        if (offset < 0 || limit < 1 || limit > 100) throw invalid("cursor", "Invalid template page");
        List<Map<String,Object>> all = new ArrayList<Map<String,Object>>();
        for (String mode : MODES) all.add(item("builtin:" + mode, validate(builtin(mode)), true));
        boolean truncated = false;
        Path folder = root.resolve("load/templates");
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            new PackageResourceResolver(root).resolvePackageRelative("load/templates", PackageResourceResolver.Kind.DIRECTORY);
            List<Path> paths = new ArrayList<Path>();
            try (java.util.stream.Stream<Path> files = Files.walk(folder, 8)) {
                Iterator<Path> candidates = files.limit(1025).iterator();
                while (candidates.hasNext()) paths.add(candidates.next());
            }
            truncated = paths.size() > 1024;
            Collections.sort(paths);
            for (Path path : paths.subList(0, Math.min(1024, paths.size()))) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toString().matches(".*\\.ya?ml$")) continue;
                String reference = root.relativize(path).toString().replace('\\', '/');
                try { all.add(item(reference, load(reference), false)); }
                catch (Exception failure) { all.add(obj("id", reference, "name", reference, "builtin", false, "validation", "invalid")); }
            }
        }
        String search = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<Map<String,Object>> filtered = new ArrayList<Map<String,Object>>();
        for (Map<String,Object> item : all) if (Boolean.TRUE.equals(item.get("builtin")) || (item.get("id") + " " + item.get("name") + " " + item.get("mode")).toLowerCase(Locale.ROOT).contains(search)) filtered.add(item);
        int end = Math.min(offset, filtered.size());
        List<Map<String,Object>> page = new ArrayList<Map<String,Object>>();
        Map<String,Object> result = obj("items", page, "total", filtered.size(), "nextOffset", null, "truncated", truncated);
        while (end < filtered.size() && page.size() < limit) {
            Map<String,Object> candidate = filtered.get(end);
            page.add(candidate);
            result.put("nextOffset", end + 1 < filtered.size() ? end + 1 : null);
            if (maxBytes != Integer.MAX_VALUE && serializedBytes(result) > maxBytes) {
                page.remove(page.size() - 1);
                if (!page.isEmpty()) break;
                // An individually oversized definition must not prevent browsing the rest of the catalog.
                candidate = new LinkedHashMap<String,Object>(candidate);
                candidate.remove("definition");
                candidate.put("validation", "response-too-large");
                page.add(candidate);
                if (serializedBytes(result) > maxBytes) throw invalid("catalog", "Response budget is too small for template metadata");
            }
            end++;
        }
        result.put("nextOffset", end < filtered.size() ? end : null);
        return result;
    }

    private static int serializedBytes(Map<String,Object> page) {
        return Math.max(new org.yaml.snakeyaml.Yaml().dump(page).getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                att.validation.JsonSupport.write(page).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    /** Catalog revision has no Environment dependency; executable drafts retain the full package revision. */
    public String sourceRevision() throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update((SCHEMA + Version.PRODUCT + att.validation.SchemaFiles.registryDigest()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path directory = root.resolve("load/templates");
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory)) {
            List<Path> files = new ArrayList<Path>();
            try (java.util.stream.Stream<Path> stream = Files.walk(directory, 8)) {
                stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                    .filter(path -> path.toString().endsWith(".yaml") || path.toString().endsWith(".yml"))
                    .limit(1025).forEach(files::add);
            }
            if (files.size() > 1024) throw invalid("catalog", "Template catalog exceeds 1024 files");
            Collections.sort(files);
            for (Path file : files) {
                Path safe = new PackageResourceResolver(root).fromInternalPath(file, PackageResourceResolver.Kind.FILE).canonicalPath();
                if (Files.size(safe) > 256L * 1024) throw invalid("source", "Template file exceeds 256 KiB");
                digest.update(root.relativize(file).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0); digest.update(Files.readAllBytes(safe)); digest.update((byte) 0xff);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static Map<String,Object> item(String id, Map<String,Object> definition, boolean builtin) {
        return obj("id", id, "name", definition.get("name"), "mode", definition.get("mode"), "schemaVersion", SCHEMA,
                "builtin", builtin, "validation", "valid", "definition", definition);
    }
    private Map<String,Object> read(String reference) throws Exception {
        Path file = new PackageResourceResolver(root).resolvePackageRelative(reference, PackageResourceResolver.Kind.FILE).canonicalPath();
        if (Files.size(file) > 256L * 1024) throw invalid("source", "Template/bindings file exceeds 256 KiB");
        return map(YamlSupport.load(file));
    }
    public static Map<String,Object> builtin(String mode) {
        int index = MODES.indexOf(mode);
        if (index < 0) throw invalid("mode", "Unknown template mode");
        boolean arrival = mode.endsWith("tps"), mixed = mode.endsWith("mix");
        List<Map<String,Object>> workloads = new ArrayList<Map<String,Object>>();
        for (int i = 1; i <= (mode.startsWith("multi-") ? 2 : 1); i++) {
            Map<String,Object> workload = obj("id", "workload-" + i);
            if (mixed) workload.put("mix", Arrays.asList(obj("id", "slot-1", "weight", 70), obj("id", "slot-2", "weight", 30)));
            workloads.add(workload);
        }
        Map<String,Object> load = arrival ? obj("arrivalRate", "1/s", "maxConcurrent", 1, "overloadPolicy", "drop", "duration", "10s") : obj("users", 1, "duration", "10s");
        return obj("schemaVersion", SCHEMA, "name", NAMES[index], "mode", mode, "policy", obj("load", load, "evidence", obj("mode", "metrics")), "workloads", workloads);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) {
        if (value == null) return new LinkedHashMap<String,Object>();
        if (!(value instanceof Map)) throw invalid("definition", "Expected an object");
        return (Map<String,Object>)value;
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value) { return value == null ? Collections.<Map<String,Object>>emptyList() : (List<Map<String,Object>>)value; }
    private static Map<String,Object> copy(Map<String,Object> value) { return LoadIsolation.deepCopyMap(value); }
    private static Map<String,Object> merged(Map<String,Object> a, Map<String,Object> b) { Map<String,Object> result = copy(a); result.putAll(copy(b)); return result; }
    public static Map<String,Object> obj(Object... pairs) { Map<String,Object> result = new LinkedHashMap<String,Object>(); for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i], pairs[i+1]); return result; }
    private static IllegalArgumentException invalid(String field, String detail) { return new IllegalArgumentException(field + ": " + detail); }
}
