/* Author: Jeffrey + ChatGPT */
package att.template;

import org.yaml.snakeyaml.Yaml;
import att.config.YamlSupport;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Recursively indexes V2 template directories by full path and symbolic name. */
public final class StageTemplateLoader {
    private final Path root;
    private final Map<String, Path> byPath = new LinkedHashMap<String, Path>();
    private final Map<String, Path> byName = new LinkedHashMap<String, Path>();

    public StageTemplateLoader(Path projectRoot, Path templatesRoot) throws Exception {
        this.root = (templatesRoot.isAbsolute() ? templatesRoot : projectRoot.resolve(templatesRoot)).normalize();
        index();
    }

    public StageTemplate load(String reference) throws Exception {
        Path directory = byName.get(reference);
        if (directory == null) directory = byPath.get(reference.replace('\\', '/'));
        if (directory == null) throw new IllegalArgumentException("Unknown template name/path: " + reference);
        return loadDirectory(reference, directory);
    }

    private void index() throws Exception {
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Templates root does not exist: " + root);
        try (Stream<Path> paths = Files.walk(root)) {
            java.util.Iterator<Path> iterator = paths.filter(Files::isDirectory).iterator();
            while (iterator.hasNext()) {
                Path directory = iterator.next();
                Path descriptor = directory.resolve("template.yaml");
                if (!Files.isRegularFile(descriptor)) continue;
                String relative = root.relativize(directory).toString().replace('\\', '/');
                byPath.put(relative, directory);
                Map<String, Object> yaml = yaml(descriptor);
                Object name = yaml.get("name");
                if (name != null && !String.valueOf(name).trim().isEmpty()) {
                    String symbolic = String.valueOf(name).trim();
                    Path previous = byName.put(symbolic, directory);
                    if (previous != null) throw new IllegalArgumentException("Duplicate template name '" + symbolic + "': " + previous + ", " + directory);
                }
            }
        }
    }

    private StageTemplate loadDirectory(String reference, Path directory) throws Exception {
        if (!directory.normalize().startsWith(root)) throw new IllegalArgumentException("Template escapes root: " + reference);
        Map<String, Object> map = yaml(directory.resolve("template.yaml"));
        List<TemplateAction> actions = new ArrayList<TemplateAction>();
        Object configured = map.get("actions");
        if (!(configured instanceof Map)) throw new IllegalArgumentException("Template actions must be an ordered map: " + directory);
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            if (!(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Action must be a map: " + entry.getKey());
            actions.add(new TemplateAction(String.valueOf(entry.getKey()), objectMap((Map<?, ?>) entry.getValue())));
        }
        if (actions.isEmpty()) throw new IllegalArgumentException("Template must contain at least one action: " + directory);
        Map<String, Object> defaults = map.get("config") instanceof Map
                ? objectMapValue(((Map<?, ?>) map.get("config")).get("actionDefaults")) : objectMapValue(map.get("actionDefaults"));
        return new StageTemplate(text(map.get("name"), reference), directory, actions, defaults);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(Path file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file)) {
            Object loaded = YamlSupport.parser().load(reader);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("Template must be a YAML map: " + file);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) loaded).entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
            return result;
        }
    }

    private Map<String, Object> objectMapValue(Object value) { return value instanceof Map ? objectMap((Map<?, ?>) value) : new LinkedHashMap<String, Object>(); }
    private Map<String, Object> objectMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : value.entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
        return result;
    }
    private String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
}
