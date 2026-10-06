package att.load;

import att.config.YamlSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Migrates old load test fixtures in memory so behavior tests exercise the active v1.3 schema. */
final class LoadTestSupport {
    private LoadTestSupport() { }

    @SuppressWarnings("unchecked")
    static String currentScenario(String yaml) {
        try {
            Object parsed = YamlSupport.parser().load(yaml);
            if (!(parsed instanceof Map)) return yaml;
            Map<String, Object> root = (Map<String, Object>) parsed;
            Object version = root.get("schemaVersion");
            if ("att-load/v1.3".equals(version)) return yaml;
            if (!"att-load/v1.0".equals(version) && !"att-load/v1.3".equals(version)) return yaml;
            if ("att-load/v1.0".equals(version)) {
                Map<String, Object> workload = new LinkedHashMap<String, Object>();
                workload.put("id", "default");
                move(root, workload, "target");
                move(root, workload, "inputs");
                move(root, workload, "load");
                Object oldExecution = root.remove("execution");
                if (oldExecution instanceof Map) {
                    Map<String, Object> execution = (Map<String, Object>) oldExecution;
                    if (execution.containsKey("thinkTime")) {
                        Map<String, Object> workloadExecution = new LinkedHashMap<String, Object>();
                        workloadExecution.put("thinkTime", execution.get("thinkTime"));
                        workload.put("execution", workloadExecution);
                    }
                    if (execution.containsKey("execIdFormat")) {
                        Map<String, Object> runExecution = new LinkedHashMap<String, Object>();
                        runExecution.put("execIdFormat", execution.get("execIdFormat"));
                        root.put("execution", runExecution);
                    }
                }
                List<Object> workloads = new ArrayList<Object>();
                workloads.add(workload);
                root.put("workloads", workloads);
            }
            root.put("schemaVersion", "att-load/v1.3");
            return new org.yaml.snakeyaml.Yaml().dump(root);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid Load test fixture", failure);
        }
    }

    static Path writeScenario(Path path, String yaml) throws Exception {
        Files.write(path, currentScenario(yaml).getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static void move(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) to.put(key, from.remove(key));
    }
}
