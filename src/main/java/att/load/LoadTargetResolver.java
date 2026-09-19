package att.load;

import att.Version;
import att.config.FrameworkConfig;
import att.config.ToolConfig;
import att.flow.FlowDefinition;
import att.flow.FlowRegistry;
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.template.TemplateAction;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves one load target without starting a scheduler or executing a Tool. */
public final class LoadTargetResolver {
    private final Path projectRoot;
    private final FrameworkConfig config;

    public LoadTargetResolver(Path projectRoot, FrameworkConfig config) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize(); this.config = config;
    }

    public LoadTarget resolve(LoadScenario scenario) throws Exception {
        try {
            StageTemplateLoader templates = new StageTemplateLoader(projectRoot, config.templatesRoot(), false);
            FlowRegistry flows = new FlowRegistry(projectRoot, config.templatesRoot(), false);
            String type = scenario.targetType();
            String id = scenario.targetId();
            if ("template".equals(type)) return new LoadTarget(type, id, templates.loadSelected(id), flows, config.templatesRoot());
            if ("flow".equals(type)) {
                FlowDefinition flow = flows.get(id);
                if (flow == null) throw new IllegalArgumentException("Unknown Flow '" + id + "'");
                Map<String, Object> action = new LinkedHashMap<String, Object>();
                action.put("type", "flow"); action.put("use", id);
                StageTemplate wrapper = new StageTemplate(flow.name(), flow.directory(),
                        Collections.singletonList(new TemplateAction("loadFlow", action, Version.TEMPLATE_SCHEMA)),
                        Version.TEMPLATE_SCHEMA, flow.directory().resolve("flow.yaml"));
                return new LoadTarget(type, id, wrapper, flows, config.templatesRoot());
            }
            if ("tool".equals(type)) {
                ToolConfig tool = findTool(id);
                if (tool == null) throw new IllegalArgumentException("Unknown Tool '" + id + "'");
                Map<String, Object> action = new LinkedHashMap<String, Object>();
                action.put("type", "tool"); action.put("call", call(tool.key(), scenario.targetArguments()));
                Path source = tool.sourceFile() == null ? projectRoot.resolve("config/config.yaml") : tool.sourceFile();
                StageTemplate wrapper = new StageTemplate(tool.name(), source.getParent(),
                        Collections.singletonList(new TemplateAction("loadTool", action, Version.TEMPLATE_SCHEMA)),
                        Version.TEMPLATE_SCHEMA, source);
                return new LoadTarget(type, id, wrapper, flows, config.templatesRoot());
            }
            throw new IllegalArgumentException("target.type must be template, flow, or tool");
        } catch (DiagnosticException e) {
            throw e;
        } catch (Exception e) {
            throw new DiagnosticException(DiagnosticCodes.LOAD_INVALID, "Unable to resolve load target",
                    e.getMessage(), scenario.source().toString(), "target", null, null, null, null, null,
                    "Correct target.type/target.id and ensure its Template, Flow, Tool, and dependencies are available.", e);
        }
    }

    private ToolConfig findTool(String id) {
        ToolConfig direct = config.tool(id);
        if (direct != null) return direct;
        ToolConfig match = null;
        for (ToolConfig candidate : config.tools().values()) {
            if (!candidate.localKey().equals(id)) continue;
            if (match != null) return null;
            match = candidate;
        }
        return match;
    }

    private String call(String tool, Map<String, Object> arguments) {
        StringBuilder result = new StringBuilder("#{").append(tool).append('(');
        int index = 0;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (index++ > 0) result.append(", ");
            result.append(entry.getKey()).append('=').append(literal(entry.getValue()));
        }
        return result.append(")}").toString();
    }

    private String literal(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        if (value instanceof Iterable) {
            StringBuilder result = new StringBuilder("["); int index = 0;
            for (Object item : (Iterable<?>) value) { if (index++ > 0) result.append(", "); result.append(literal(item)); }
            return result.append(']').toString();
        }
        if (value instanceof Map) throw new IllegalArgumentException("Load Tool arguments do not support nested map literals: " + value);
        String text = String.valueOf(value).replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n");
        return "'" + text + "'";
    }
}
