/* Author: Jeffrey + ChatGPT */
package att.template;

import att.Version;
import att.config.SchemaSupport;

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
    private final Path projectRoot;
    private final Map<String, Path> byPath = new LinkedHashMap<String, Path>();
    private final Map<String, Path> byName = new LinkedHashMap<String, Path>();

    public StageTemplateLoader(Path projectRoot, Path templatesRoot) throws Exception {
        this.projectRoot = projectRoot;
        Path canonicalProject = att.core.IdentifierValidator.canonicalPath(projectRoot, "package root");
        Path configured = templatesRoot.isAbsolute() ? templatesRoot : projectRoot.resolve(templatesRoot);
        this.root = att.core.IdentifierValidator.canonicalPath(configured, "templates root");
        if (!root.startsWith(canonicalProject)) throw new IllegalArgumentException("Templates root escapes package root: " + templatesRoot);
        index();
    }

    public StageTemplate load(String reference) throws Exception {
        Path directory = byName.get(reference);
        try {
            if (directory == null) {
                att.core.IdentifierValidator.relativePath(reference, "Template path");
                directory = byPath.get(reference);
            }
            if (directory == null) throw new IllegalArgumentException("No template has this symbolic name or templates-root-relative path. Available template names: " + String.join(", ", byName.keySet()));
            return loadDirectory(reference, directory);
        } catch (att.validation.DiagnosticException e) {
            throw e;
        } catch (Exception e) {
            att.validation.JsonSchemaVerifier.SchemaValidationException schema = att.validation.JsonSchemaVerifier.SchemaValidationException.find(e);
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.TEMPLATE_INVALID,
                    "Invalid template '" + reference + "'", e.getMessage(),
                    directory == null ? root.toString() : directory.resolve("template.yaml").toString(),
                    schema == null ? "template" : schema.field(), null, null, null, reference, null,
                    "Correct the template name/path, descriptor field, action contract, payload, or referenced call.", e);
        }
    }

    public List<StageTemplate> all() throws Exception {
        List<String> paths = new ArrayList<String>(byPath.keySet());
        java.util.Collections.sort(paths);
        List<StageTemplate> templates = new ArrayList<StageTemplate>();
        for (String path : paths) templates.add(loadDirectory(path, byPath.get(path)));
        return templates;
    }
    public List<String> paths() {
        List<String> paths = new ArrayList<String>(byPath.keySet());
        java.util.Collections.sort(paths);
        return java.util.Collections.unmodifiableList(paths);
    }

    private void index() throws Exception {
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Templates root does not exist: " + root);
        try (Stream<Path> paths = Files.walk(root)) {
            java.util.Iterator<Path> iterator = paths.filter(Files::isDirectory).iterator();
            while (iterator.hasNext()) {
                Path directory = iterator.next();
                Path descriptor = directory.resolve("template.yaml");
                if (!Files.isRegularFile(descriptor) || Files.isSymbolicLink(descriptor)) continue;
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
        Path schema = projectRoot.resolve("schemas/att-template-v2.3.schema.json");
        if (Files.isRegularFile(schema)) att.validation.JsonSchemaVerifier.verify(schema, map);
        SchemaSupport.requireVersion(map, Version.TEMPLATE_SCHEMA, "template");
        SchemaSupport.rejectUnknown(map, "template", "schemaVersion", "name", "description", "actions");
        SchemaSupport.string(map.get("description"), "template.description", true);
        List<TemplateAction> actions = new ArrayList<TemplateAction>();
        Object configured = map.get("actions");
        if (!(configured instanceof Map)) throw new IllegalArgumentException("Template actions must be an ordered map: " + directory);
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) configured).entrySet()) {
            if (!(entry.getValue() instanceof Map)) throw new IllegalArgumentException("Action must be a map: " + entry.getKey());
            String actionKey = String.valueOf(entry.getKey());
            if (actionKey.trim().isEmpty() || actionKey.contains(".")) throw new IllegalArgumentException("Action key must be non-blank and dot-free: " + actionKey);
            Map<?, ?> actionMap = (Map<?, ?>) entry.getValue();
            SchemaSupport.rejectUnknown(actionMap, "actions." + actionKey, "type", "onFailure", "retry", "description", "name", "expression", "payload", "renderAs", "saveAs", "overwrite", "call", "assert", "expected", "actual", "message", "level", "fields", "timeoutMs");
            SchemaSupport.string(actionMap.get("type"), "actions." + actionKey + ".type", true);
            if (actionMap.get("description") != null) SchemaSupport.string(actionMap.get("description"), "actions." + actionKey + ".description", true);
            if (actionMap.get("overwrite") != null && !(actionMap.get("overwrite") instanceof Boolean)) throw new IllegalArgumentException("actions." + actionKey + ".overwrite must be a boolean");
            for (String mapping : new String[]{"retry", "fields"}) if (actionMap.get(mapping) != null && !(actionMap.get(mapping) instanceof Map)) throw new IllegalArgumentException("actions." + actionKey + "." + mapping + " must be a map");
            actions.add(new TemplateAction(actionKey, objectMap(actionMap)));
        }
        if (actions.isEmpty()) throw new IllegalArgumentException("Template must contain at least one action: " + directory);
        return new StageTemplate(text(map.get("name"), reference), directory, actions);
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

    private Map<String, Object> objectMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> e : value.entrySet()) result.put(String.valueOf(e.getKey()), e.getValue());
        return result;
    }
    private String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value); }
}
