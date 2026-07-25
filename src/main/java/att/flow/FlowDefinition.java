package att.flow;

import att.template.TemplateAction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable att-flow/v3.0 descriptor. */
public final class FlowDefinition {
    private final String id;
    private final String name;
    private final String description;
    private final Path directory;
    private final Map<String, Input> inputs;
    private final List<TemplateAction> actions;
    private final Map<String, Output> outputs;

    FlowDefinition(String id, String name, String description, Path directory,
                   Map<String, Input> inputs, List<TemplateAction> actions, Map<String, Output> outputs) {
        this.id = id; this.name = name; this.description = description; this.directory = directory;
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<String, Input>(inputs));
        this.actions = Collections.unmodifiableList(new ArrayList<TemplateAction>(actions));
        this.outputs = Collections.unmodifiableMap(new LinkedHashMap<String, Output>(outputs));
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public Path directory() { return directory; }
    public Map<String, Input> inputs() { return inputs; }
    public List<TemplateAction> actions() { return actions; }
    public Map<String, Output> outputs() { return outputs; }

    public static final class Input {
        private final String type; private final boolean required; private final boolean hasDefault; private final Object defaultValue;
        Input(String type, boolean required, boolean hasDefault, Object defaultValue) {
            this.type = type; this.required = required; this.hasDefault = hasDefault; this.defaultValue = defaultValue;
        }
        public String type() { return type; }
        public boolean required() { return required; }
        public boolean hasDefault() { return hasDefault; }
        public Object defaultValue() { return defaultValue; }
    }

    public static final class Output {
        private final String type; private final String from;
        Output(String type, String from) { this.type = type; this.from = from; }
        public String type() { return type; }
        public String from() { return from; }
    }
}
