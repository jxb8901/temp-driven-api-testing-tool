/*
 * Author: Jeffrey + ChatGPT
 */

package att.template;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loaded V2 template directory.
 */
public class StageTemplate {
    private final String name;
    private final Path directory;
    private final List<TemplateAction> actions;
    private final Map<String, Object> actionDefaults;

    public StageTemplate(String name, Path directory, List<TemplateAction> actions) {
        this(name, directory, actions, Collections.<String, Object>emptyMap());
    }

    public StageTemplate(String name, Path directory, List<TemplateAction> actions, Map<String, Object> actionDefaults) {
        this.name = name;
        this.directory = directory;
        this.actions = actions == null ? Collections.<TemplateAction>emptyList() : new ArrayList<TemplateAction>(actions);
        this.actionDefaults = actionDefaults == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>(actionDefaults);
    }

    public String name() { return name; }
    public Path directory() { return directory; }
    public List<TemplateAction> actions() { return actions; }
    public Map<String, Object> actionDefaults() { return actionDefaults; }
}
