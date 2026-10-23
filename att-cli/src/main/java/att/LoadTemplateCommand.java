package att;

import att.config.FrameworkConfig;
import att.core.ExecutionOptions;
import att.debug.DebugEngine;
import att.load.*;
import java.nio.file.Path;
import java.util.*;
import static att.load.LoadTemplateCatalog.obj;

/** CLI adapter for the same versioned composer consumed by Studio inspection. */
final class LoadTemplateCommand {
    private LoadTemplateCommand() { }

    @SuppressWarnings("unchecked")
    static Map<String,Object> list(Path root) throws Exception {
        LoadTemplateCatalog catalog = new LoadTemplateCatalog(root);
        List<Map<String,Object>> items = new ArrayList<Map<String,Object>>();
        Map<String,Object> page;
        int offset = 0;
        do {
            page = catalog.list("", offset, 100);
            items.addAll((List<Map<String,Object>>)page.get("items"));
            if (page.get("nextOffset") == null) break;
            offset = ((Number)page.get("nextOffset")).intValue();
        } while (true);
        page.put("items", items);
        return page;
    }

    static LoadScenario compose(Path root, FrameworkConfig config, CliOptions options) throws Exception {
        LoadTemplateCatalog catalog = new LoadTemplateCatalog(root);
        Map<String,Object> definition = catalog.load(options.loadTemplate());
        Map<String,Object> bindings;
        boolean single = String.valueOf(definition.get("mode")).matches("single-(vu|tps)");
        if (single != !options.debugTargetType().isEmpty())
            throw new IllegalArgumentException("Single-target template requires --debug type id; multi-target template requires --debug --bindings with no positional target");
        final DebugEngine debug = new DebugEngine(root, options.configPath(), config, new att.exec.IbmMqClientFactory(), true, Collections.<String>emptyList(), Collections.<String>emptyList());
        final ExecutionOptions intent = ExecutionOptions.forApi("load", options.configPath(), options.environment(),
                null, null, null, null, null, null, false, false, false, false, null, "selected",
                options.debugTargetType(), options.debugTargetId(), options.debugInput(), false, null,
                options.loadUsers(), options.loadArrivalRate(), options.loadWarmup(), options.loadRampUp(),
                options.loadDuration(), options.loadRampDown(), options.loadThinkTime(), options.loadMaxConcurrent(),
                options.loadOverloadPolicy(), options.variableOverrides());
        if (single) {
            @SuppressWarnings("unchecked") List<Map<String,Object>> rows = (List<Map<String,Object>>)definition.get("workloads");
            bindings = obj("schemaVersion", LoadTemplateCatalog.BINDINGS_SCHEMA, "bindings", obj((String)rows.get(0).get("id"),
                    obj("target", obj("type", options.debugTargetType(), "id", options.debugTargetId()))));
            // Explicit single-target CLI policy follows the chosen mode; incompatible model overrides fail.
            Map<String,Object> load = new LinkedHashMap<String,Object>();
            if (options.loadUsers() != null) load.put("users", Integer.valueOf(options.loadUsers()));
            if (options.loadArrivalRate() != null) load.put("arrivalRate", options.loadArrivalRate());
            if (options.loadMaxConcurrent() != null) load.put("maxConcurrent", Integer.valueOf(options.loadMaxConcurrent()));
            if (options.loadOverloadPolicy() != null) load.put("overloadPolicy", options.loadOverloadPolicy());
            if (!load.isEmpty()) {
                @SuppressWarnings("unchecked") Map<String,Object> policy = (Map<String,Object>)definition.get("policy");
                @SuppressWarnings("unchecked") Map<String,Object> defaults = (Map<String,Object>)policy.get("load");
                defaults.putAll(load);
                if (rows.get(0).get("load") instanceof Map) {
                    @SuppressWarnings("unchecked") Map<String,Object> local = (Map<String,Object>) rows.get(0).get("load");
                    local.putAll(load);
                }
            }
            if (options.loadThinkTime() != null) {
                @SuppressWarnings("unchecked") Map<String,Object> execution = rows.get(0).get("execution") instanceof Map
                        ? (Map<String,Object>) rows.get(0).get("execution") : new LinkedHashMap<String,Object>();
                execution.put("thinkTime", options.loadThinkTime());
                rows.get(0).put("execution", execution);
            }
        } else bindings = catalog.bindings(options.loadBindings());
        @SuppressWarnings("unchecked") Map<String,Object> policy = (Map<String,Object>)definition.get("policy");
        @SuppressWarnings("unchecked") Map<String,Object> load = (Map<String,Object>)policy.get("load");
        if (options.loadDuration() != null) load.put("duration", options.loadDuration());
        if (options.loadWarmup() != null) load.put("warmup", options.loadWarmup());
        if (options.loadRampUp() != null) load.put("rampUp", options.loadRampUp());
        if (options.loadRampDown() != null) load.put("rampDown", options.loadRampDown());
        for (Object item : (List<?>) definition.get("workloads")) {
            Map<?,?> row = (Map<?,?>) item;
            if (row.get("load") instanceof Map) {
                @SuppressWarnings("unchecked") Map<String,Object> local = (Map<String,Object>) row.get("load");
                if (options.loadDuration() != null) local.put("duration", options.loadDuration());
                if (options.loadWarmup() != null) local.put("warmup", options.loadWarmup());
                if (options.loadRampUp() != null) local.put("rampUp", options.loadRampUp());
                if (options.loadRampDown() != null) local.put("rampDown", options.loadRampDown());
            }
        }
        LoadScenario scenario = catalog.compose(definition, bindings,
                (type,id) -> single ? debug.loadBootstrapInputForLoad(intent) : debug.loadBootstrapInputForLoad(type,id));
        LoadRunCoordinator.resolveAndValidateTargets(root, config, scenario);
        return scenario;
    }
}
