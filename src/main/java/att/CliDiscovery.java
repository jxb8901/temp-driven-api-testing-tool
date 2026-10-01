/* Author: Jeffrey + ChatGPT */
package att;

import att.config.FrameworkConfig;
import att.config.ToolConfig;
import att.core.ExecutionOptions;
import att.debug.DebugEngine;
import att.flow.FlowRegistry;
import att.load.LoadProfileLoader;
import att.load.LoadScenario;
import att.load.LoadScenarioLoader;
import att.load.LoadTarget;
import att.load.LoadTargetResolver;
import att.load.LoadTargetValidator;
import att.load.LoadWorkload;
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.validation.DiagnosticException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Read-only discovery for the no-target Debug and Load CLI forms. */
public final class CliDiscovery {
    private static final Pattern DECLARED_LOAD_VERSION = Pattern.compile(
            "(?m)^\\s*schemaVersion\\s*:\\s*['\\\"]?att-load/");

    private CliDiscovery() { }

    public static Map<String, Object> debug(Path root, FrameworkConfig config) throws Exception {
        List<Map<String, Object>> targets = debugTargets(root, config);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("targets", targets);
        return result;
    }

    public static Map<String, Object> load(Path root, FrameworkConfig config) throws Exception {
        Path canonicalRoot = root.toAbsolutePath().normalize();
        List<Map<String, Object>> scenarios = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> invalid = new ArrayList<Map<String, Object>>();
        Path loadRoot = canonicalRoot.resolve("load");
        if (Files.isDirectory(loadRoot) && !Files.isSymbolicLink(loadRoot)) {
            List<Path> candidates = new ArrayList<Path>();
            try (Stream<Path> paths = Files.walk(loadRoot)) {
                paths.filter(Files::isRegularFile).filter(path -> !Files.isSymbolicLink(path))
                        .filter(CliDiscovery::yamlFile).filter(path -> !path.equals(loadRoot.resolve("load.yaml")))
                        .forEach(candidates::add);
            }
            Collections.sort(candidates);
            LoadScenarioLoader loader = new LoadScenarioLoader(canonicalRoot);
            LoadTargetResolver resolver = new LoadTargetResolver(canonicalRoot, config);
            LoadTargetValidator validator = new LoadTargetValidator(canonicalRoot, config);
            for (Path candidate : candidates) {
                if (!declaresLoadScenario(candidate)) continue;
                try {
                    LoadScenario scenario = loader.load(candidate);
                    for (LoadWorkload workload : scenario.workloads()) {
                        LoadScenario single = scenario.forWorkload(workload);
                        LoadTarget target = resolver.resolve(single);
                        validator.validate(single, target);
                    }
                    scenarios.add(scenarioSummary(canonicalRoot, scenario));
                } catch (Exception error) {
                    invalid.add(invalidScenario(canonicalRoot, candidate, error));
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("scenarios", scenarios);
        result.put("invalid", invalid);
        Path profilePath = canonicalRoot.resolve("load/load.yaml");
        Map<String, Object> profile = new LinkedHashMap<String, Object>();
        profile.put("path", relative(canonicalRoot, profilePath));
        try {
            Map<String, Object> policy = new LoadProfileLoader(canonicalRoot).loadDefault();
            profile.put("available", policy != null);
            result.put("profile", profile);
            List<String> quickCommands = new ArrayList<String>();
            if (policy != null) {
                for (Map<String, Object> target : quickLoadTargets(canonicalRoot, config, policy)) {
                    quickCommands.add("./att.sh load --debug " + target.get("type") + " "
                            + shellQuote(String.valueOf(target.get("id"))));
                }
            }
            result.put("quickLoadCommands", quickCommands);
        } catch (Exception error) {
            profile.put("available", false);
            profile.put("error", diagnosticText(error));
            result.put("profile", profile);
            result.put("quickLoadCommands", Collections.emptyList());
        }
        return result;
    }

    public static List<Map<String, Object>> debugTargets(Path root, FrameworkConfig config) throws Exception {
        Path canonicalRoot = root.toAbsolutePath().normalize();
        DebugEngine engine = new DebugEngine(canonicalRoot, config);
        List<Map<String, Object>> targets = new ArrayList<Map<String, Object>>();

        List<String> toolIds = new ArrayList<String>(config.tools().keySet());
        Collections.sort(toolIds);
        for (String id : toolIds) addDebugTarget(targets, engine, "tool", id);

        StageTemplateLoader templates = new StageTemplateLoader(canonicalRoot, config.templatesRoot(), false);
        for (String path : templates.paths()) {
            try {
                StageTemplate template = templates.load(path);
                addDebugTarget(targets, engine, "template", path, template.name());
            } catch (Exception ignored) { }
        }

        FlowRegistry flows = new FlowRegistry(canonicalRoot, config.templatesRoot(), false);
        for (String id : flows.ids()) addDebugTarget(targets, engine, "flow", id);
        sortTargets(targets);
        return targets;
    }

    private static List<Map<String, Object>> quickLoadTargets(Path root, FrameworkConfig config,
                                                               Map<String, Object> quickLoadPolicy) throws Exception {
        Path canonicalRoot = root.toAbsolutePath().normalize();
        DebugEngine engine = new DebugEngine(canonicalRoot, config);
        List<Map<String, Object>> targets = new ArrayList<Map<String, Object>>();

        List<String> toolIds = new ArrayList<String>(config.tools().keySet());
        Collections.sort(toolIds);
        for (String id : toolIds) addQuickLoadTarget(targets, engine, "tool", id, id, quickLoadPolicy);

        StageTemplateLoader templates = new StageTemplateLoader(canonicalRoot, config.templatesRoot(), false);
        for (String path : templates.paths()) {
            try {
                StageTemplate template = templates.load(path);
                addQuickLoadTarget(targets, engine, "template", path, template.name(), quickLoadPolicy);
            } catch (Exception ignored) { }
        }

        FlowRegistry flows = new FlowRegistry(canonicalRoot, config.templatesRoot(), false);
        for (String id : flows.ids()) addQuickLoadTarget(targets, engine, "flow", id, id, quickLoadPolicy);
        sortTargets(targets);
        return targets;
    }

    private static void sortTargets(List<Map<String, Object>> targets) {
        Collections.sort(targets, new Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> left, Map<String, Object> right) {
                int type = String.valueOf(left.get("type")).compareTo(String.valueOf(right.get("type")));
                return type == 0 ? String.valueOf(left.get("id")).compareTo(String.valueOf(right.get("id"))) : type;
            }
        });
    }

    public static void printDebug(Map<String, Object> result, String format) {
        if ("json".equals(format)) {
            System.out.println(att.validation.JsonSupport.write(result));
            return;
        }
        @SuppressWarnings("unchecked") List<Map<String, Object>> targets = (List<Map<String, Object>>) result.get("targets");
        System.out.println("Debug targets:");
        for (String type : new String[]{"tool", "template", "flow"}) {
            boolean heading = false;
            for (Map<String, Object> target : targets) if (type.equals(target.get("type"))) {
                if (!heading) { System.out.println("  " + Character.toUpperCase(type.charAt(0)) + type.substring(1) + "s:"); heading = true; }
                System.out.println("    " + target.get("id") + "  " + target.get("command"));
                if (target.containsKey("sidecar")) System.out.println("      Debug input: " + target.get("sidecar"));
            }
            if (!heading) System.out.println("  " + Character.toUpperCase(type.charAt(0)) + type.substring(1) + "s: (none)");
        }
    }

    public static void printLoad(Map<String, Object> result, String format) {
        if ("json".equals(format)) {
            System.out.println(att.validation.JsonSupport.write(result));
            return;
        }
        @SuppressWarnings("unchecked") List<Map<String, Object>> scenarios = (List<Map<String, Object>>) result.get("scenarios");
        @SuppressWarnings("unchecked") List<Map<String, Object>> invalid = (List<Map<String, Object>>) result.get("invalid");
        System.out.println("Load scenarios:");
        if (scenarios.isEmpty()) System.out.println("  (none)");
        for (Map<String, Object> scenario : scenarios) {
            System.out.println("  " + scenario.get("path") + "  " + scenario.get("summary"));
            System.out.println("    " + scenario.get("command"));
        }
        for (Map<String, Object> item : invalid)
            System.out.println("  INVALID " + item.get("path") + ": " + item.get("diagnostic"));
        @SuppressWarnings("unchecked") Map<String, Object> profile = (Map<String, Object>) result.get("profile");
        if (Boolean.TRUE.equals(profile.get("available"))) {
            System.out.println("Quick Load policy: " + profile.get("path"));
            @SuppressWarnings("unchecked") List<String> commands = (List<String>) result.get("quickLoadCommands");
            if (commands.isEmpty()) System.out.println("  Add a valid Debug sidecar to a target to make it Quick Load-ready.");
            else for (String command : commands) System.out.println("  " + command);
        } else {
            System.out.println("Quick Load needs " + profile.get("path") + " or explicit --users/--arrival-rate and --duration policy.");
            if (profile.containsKey("error")) System.out.println("  Profile error: " + profile.get("error"));
        }
    }

    private static void addDebugTarget(List<Map<String, Object>> targets, DebugEngine engine, String type, String id) {
        addDebugTarget(targets, engine, type, id, id);
    }

    private static void addDebugTarget(List<Map<String, Object>> targets, DebugEngine engine, String type,
                                       String id, String displayName) {
        try {
            Path sidecar = engine.validateDiscoverableTarget(type, id);
            if (sidecar == null) return;
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("type", type); entry.put("id", id); entry.put("name", displayName);
            entry.put("command", "./att.sh debug " + type + " " + shellQuote(id));
            entry.put("sidecar", sidecar.toAbsolutePath().normalize().toString());
            targets.add(entry);
        } catch (Exception ignored) { }
    }

    private static void addQuickLoadTarget(List<Map<String, Object>> targets, DebugEngine engine, String type,
                                           String id, String displayName, Map<String, Object> quickLoadPolicy) {
        try {
            Path sidecar = engine.validateDiscoverableTargetForLoad(type, id, quickLoadPolicy);
            if (sidecar == null) return;
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("type", type); entry.put("id", id); entry.put("name", displayName);
            entry.put("sidecar", sidecar.toAbsolutePath().normalize().toString());
            targets.add(entry);
        } catch (Exception ignored) { }
    }

    private static Map<String, Object> scenarioSummary(Path root, LoadScenario scenario) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("path", relative(root, scenario.source()));
        item.put("command", "./att.sh load " + shellQuote(relative(root, scenario.source())));
        List<String> targets = new ArrayList<String>();
        for (LoadWorkload workload : scenario.workloads())
            targets.add(workload.id() + "=" + workload.targetType() + ":" + workload.targetId());
        String intensity = scenario.model() == LoadScenario.Model.CLOSED
                ? scenario.configuredUsers() + " users" : scenario.configuredArrivalRatePerSecond() + "/s";
        item.put("summary", targets + "; " + intensity + "; duration=" + scenario.duration());
        item.put("workloads", targets);
        return item;
    }

    private static Map<String, Object> invalidScenario(Path root, Path path, Exception error) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("path", relative(root, path));
        item.put("diagnostic", diagnosticText(error));
        return item;
    }

    private static String diagnosticText(Exception error) {
        DiagnosticException diagnostic = DiagnosticException.find(error);
        if (diagnostic != null) {
            String detail = diagnostic.detail();
            return diagnostic.summary() + (detail == null || detail.trim().isEmpty() ? "" : ": " + detail.replace('\n', ' '));
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static boolean declaresLoadScenario(Path path) {
        try {
            Object loaded = att.config.YamlSupport.load(path);
            if (loaded instanceof Map) {
                Object version = ((Map<?, ?>) loaded).get("schemaVersion");
                return version != null && String.valueOf(version).startsWith("att-load/");
            }
        } catch (Exception error) {
            try { return DECLARED_LOAD_VERSION.matcher(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).find(); }
            catch (Exception ignored) { return false; }
        }
        return false;
    }

    private static boolean yamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private static String relative(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root) ? root.relativize(normalized).toString().replace('\\', '/') : normalized.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
