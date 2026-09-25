package att.flow;

import att.template.TemplateAction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable versioned reusable Flow Action group. */
public final class FlowDefinition {
    private final String id;
    private final String name;
    private final String description;
    private final Path directory;
    private final List<TemplateAction> actions;

    FlowDefinition(String id, String name, String description, Path directory, List<TemplateAction> actions) {
        this.id = id; this.name = name; this.description = description; this.directory = directory;
        this.actions = Collections.unmodifiableList(new ArrayList<TemplateAction>(actions));
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public Path directory() { return directory; }
    public List<TemplateAction> actions() { return actions; }
}
