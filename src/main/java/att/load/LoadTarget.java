package att.load;

import att.flow.FlowRegistry;
import att.template.StageTemplate;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Resolved load target backed by the ordinary ATT Template/Flow/Tool runtime model. */
public final class LoadTarget {
    private final String type, id;
    private final StageTemplate template;
    private final FlowRegistry flows;
    private final Path templatesRoot;

    LoadTarget(String type, String id, StageTemplate template, FlowRegistry flows, Path templatesRoot) {
        this.type = type; this.id = id; this.template = template; this.flows = flows; this.templatesRoot = templatesRoot;
    }
    public String type() { return type; }
    public String id() { return id; }
    public StageTemplate template() { return template; }
    public FlowRegistry flows() { return flows; }
    public Path templatesRoot() { return templatesRoot; }
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", type); result.put("id", id); result.put("template", template.name());
        result.put("source", template.sourceFile().toString());
        return Collections.unmodifiableMap(result);
    }
}
