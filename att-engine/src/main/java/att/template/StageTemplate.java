/*
 * Author: Jeffrey + ChatGPT
 */

package att.template;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loaded V2 template directory.
 */
public class StageTemplate {
    private final String name;
    private final String schemaVersion;
    private final Path directory;
    private final Path sourceFile;
    private final List<TemplateAction> actions;
    public StageTemplate(String name, Path directory, List<TemplateAction> actions) {
        this(name, directory, actions, att.Version.TEMPLATE_SCHEMA);
    }
    public StageTemplate(String name, Path directory, List<TemplateAction> actions, String schemaVersion) {
        this(name, directory, actions, schemaVersion, directory.resolve("template.yaml"));
    }
    public StageTemplate(String name, Path directory, List<TemplateAction> actions, String schemaVersion, Path sourceFile) {
        this.name = name;
        this.schemaVersion = schemaVersion;
        this.directory = directory;
        this.sourceFile = sourceFile;
        this.actions = actions == null ? Collections.<TemplateAction>emptyList() : new ArrayList<TemplateAction>(actions);
    }

    public String name() { return name; }
    public String schemaVersion() { return schemaVersion; }
    public Path directory() { return directory; }
    public Path sourceFile() { return sourceFile; }
    public List<TemplateAction> actions() { return actions; }
}
