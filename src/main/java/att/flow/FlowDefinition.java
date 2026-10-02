package att.flow;

import att.Version;
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
    private final String schemaVersion;
    private final Path directory;
    private final List<TemplateAction> actions;

    FlowDefinition(String id, String name, String description, Path directory, String schemaVersion, List<TemplateAction> actions) {
        this.id = id; this.name = name; this.description = description; this.directory = directory; this.schemaVersion = schemaVersion;
        this.actions = Collections.unmodifiableList(new ArrayList<TemplateAction>(actions));
    }

    public String id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public String schemaVersion() { return schemaVersion; }
    public String templateSchemaVersion() {
        if (Version.HISTORICAL_FLOW_SCHEMA_V3_5.equals(schemaVersion)) return Version.HISTORICAL_TEMPLATE_SCHEMA_V3_5;
        return Version.HISTORICAL_FLOW_SCHEMA_V3_4.equals(schemaVersion)
                ? Version.HISTORICAL_TEMPLATE_SCHEMA_V3_4 : Version.TEMPLATE_SCHEMA;
    }
    public Path directory() { return directory; }
    public List<TemplateAction> actions() { return actions; }
}
