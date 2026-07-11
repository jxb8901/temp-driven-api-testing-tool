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
    private final Path directory;
    private final List<TemplateAction> actions;
    public StageTemplate(String name, Path directory, List<TemplateAction> actions) {
        this.name = name;
        this.directory = directory;
        this.actions = actions == null ? Collections.<TemplateAction>emptyList() : new ArrayList<TemplateAction>(actions);
    }

    public String name() { return name; }
    public Path directory() { return directory; }
    public List<TemplateAction> actions() { return actions; }
}
