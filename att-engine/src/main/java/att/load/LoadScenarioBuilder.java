package att.load;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Composes typed Load scenarios from validated policy and workload inputs. */
public final class LoadScenarioBuilder {
    public static final String MODEL_VIRTUAL_USERS = "virtualUsers";
    public static final String MODEL_ARRIVAL_RATE = "arrivalRate";

    private static final List<String> POLICY_FIELDS = Arrays.asList(
            "schemaVersion", "seed", "testdata", "load", "execution", "thresholds", "evidence");
    private static final List<String> VIRTUAL_USER_FIELDS = Arrays.asList(
            "users", "warmup", "rampUp", "duration", "rampDown");
    private static final List<String> ARRIVAL_RATE_FIELDS = Arrays.asList(
            "arrivalRate", "maxConcurrent", "overloadPolicy", "warmup", "rampUp", "duration", "rampDown");

    private final LoadScenarioLoader loader;

    public LoadScenarioBuilder(LoadScenarioLoader loader) {
        if (loader == null) throw new IllegalArgumentException("LoadScenarioLoader is required");
        this.loader = loader;
    }

    /** Builds one fixed-target Quick Load scenario through the current ATT Load schema and semantics. */
    public LoadScenario buildQuickLoad(String model, String targetType, String targetId,
                                       Map<String, Object> businessInput,
                                       Map<String, Object> policy,
                                       Map<String, Object> loadOverrides,
                                       Map<String, Object> workloadExecution) throws Exception {
        return buildQuickLoad(model, targetType, targetId, businessInput, policy, loadOverrides,
                workloadExecution, java.util.Collections.<String>emptyList());
    }

    /** Builds a fixed-target scenario with explicit package-relative Load-level Testdata imports. */
    public LoadScenario buildQuickLoad(String model, String targetType, String targetId,
                                       Map<String, Object> businessInput,
                                       Map<String, Object> policy,
                                       Map<String, Object> loadOverrides,
                                       Map<String, Object> workloadExecution,
                                       List<String> testdata) throws Exception {
        return loader.loadInline(composeQuickLoad(model, targetType, targetId, businessInput,
                policy, loadOverrides, workloadExecution, testdata));
    }

    /** Returns the logical one-workload map before parsing it into the immutable Engine model. */
    public Map<String, Object> composeQuickLoad(String model, String targetType, String targetId,
                                                Map<String, Object> businessInput,
                                                Map<String, Object> policy,
                                                Map<String, Object> loadOverrides,
                                                Map<String, Object> workloadExecution) {
        return composeQuickLoad(model, targetType, targetId, businessInput, policy, loadOverrides,
                workloadExecution, java.util.Collections.<String>emptyList());
    }

    /** Returns the logical scenario with explicit package-relative Load-level Testdata imports. */
    public Map<String, Object> composeQuickLoad(String model, String targetType, String targetId,
                                                Map<String, Object> businessInput,
                                                Map<String, Object> policy,
                                                Map<String, Object> loadOverrides,
                                                Map<String, Object> workloadExecution,
                                                List<String> testdata) {
        requireModel(model);
        if (!("template".equals(targetType) || "flow".equals(targetType) || "tool".equals(targetType)))
            throw new IllegalArgumentException("Quick Load target must be a Template, Flow, or Tool");
        if (targetId == null || targetId.trim().isEmpty() || targetId.length() > 512)
            throw new IllegalArgumentException("Quick Load target ID is invalid");

        Map<String, Object> root = copyPolicy(policy);
        if (testdata != null && !testdata.isEmpty()) {
            List<Object> combinedTestdata = new ArrayList<Object>();
            Object existing = root.get("testdata");
            if (existing instanceof List) combinedTestdata.addAll((List<?>) existing);
            else if (existing != null) combinedTestdata.add(existing);
            for (String descriptor : testdata) {
                if (descriptor == null || descriptor.trim().isEmpty())
                    throw new IllegalArgumentException("Quick Load Testdata paths must be non-empty strings");
                combinedTestdata.add(descriptor.trim());
            }
            root.put("testdata", combinedTestdata);
        }
        Map<String, Object> load = map(root.get("load"));
        boolean virtualUsers = load.containsKey("users");
        boolean arrivalRate = load.containsKey("arrivalRate");
        if (virtualUsers == arrivalRate || virtualUsers != MODEL_VIRTUAL_USERS.equals(model))
            throw new IllegalArgumentException("Quick Load policy does not match the selected load model");
        Map<String, Object> overrides = map(loadOverrides);
        List<String> allowedLoad = virtualUsers ? VIRTUAL_USER_FIELDS : ARRIVAL_RATE_FIELDS;
        for (String key : overrides.keySet()) {
            if (!allowedLoad.contains(key)) throw new IllegalArgumentException("Unsupported Quick Load field: " + key);
            load.put(key, LoadIsolation.deepCopy(overrides.get(key)));
        }
        ensureModel(load, model);
        root.put("load", load);

        Map<String, Object> business = map(businessInput);
        for (String key : business.keySet())
            if (!Arrays.asList("inputs", "vars", "arguments").contains(key))
                throw new IllegalArgumentException("Quick Load input contains unsupported field: " + key);
        Map<String, Object> inputs = map(business.get("inputs"));
        Map<String, Object> vars = map(business.get("vars"));
        Map<String, Object> arguments = map(business.get("arguments"));
        if ("tool".equals(targetType) && !vars.isEmpty())
            throw new IllegalArgumentException("Tool Quick Load uses arguments and does not support vars");
        if (!"tool".equals(targetType) && !arguments.isEmpty())
            throw new IllegalArgumentException("Tool arguments are supported only for Tool Quick Load");

        Map<String, Object> target = new LinkedHashMap<String, Object>();
        target.put("type", targetType);
        target.put("id", targetId);
        if ("tool".equals(targetType) && !arguments.isEmpty()) target.put("arguments", arguments);
        Map<String, Object> workload = new LinkedHashMap<String, Object>();
        workload.put("id", workloadId(targetType, targetId));
        workload.put("target", target);
        if (!inputs.isEmpty()) workload.put("inputs", inputs);
        if (!"tool".equals(targetType) && !vars.isEmpty()) workload.put("vars", vars);

        Map<String, Object> execution = map(workloadExecution);
        for (String key : execution.keySet())
            if (!"thinkTime".equals(key)) throw new IllegalArgumentException("Unsupported Quick Load execution field: " + key);
        if (!execution.isEmpty()) workload.put("execution", execution);
        return composeScenario(root, java.util.Collections.<Map<String, Object>>singletonList(workload));
    }

    /** Shared root-policy/workload composer also used by the Advanced Load builder. */
    public Map<String, Object> composeScenario(Map<String, Object> policy, List<Map<String, Object>> workloads) {
        if (workloads == null || workloads.isEmpty()) throw new IllegalArgumentException("A Load scenario requires at least one workload");
        Map<String, Object> root = copyPolicy(policy);
        List<Map<String, Object>> copies = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> workload : workloads) copies.add(LoadIsolation.deepCopyMap(workload));
        root.put("workloads", copies);
        return root;
    }

    private Map<String, Object> copyPolicy(Map<String, Object> policy) {
        if (policy == null) throw new IllegalArgumentException("Load policy is required");
        for (String key : policy.keySet())
            if (!POLICY_FIELDS.contains(key) && !key.startsWith("x-"))
                throw new IllegalArgumentException("Load policy contains unsupported field: " + key);
        return LoadIsolation.deepCopyMap(policy);
    }

    private static void requireModel(String model) {
        if (!MODEL_VIRTUAL_USERS.equals(model) && !MODEL_ARRIVAL_RATE.equals(model))
            throw new IllegalArgumentException("Quick Load model must be virtualUsers or arrivalRate");
    }

    private static void ensureModel(Map<String, Object> load, String model) {
        boolean users = load.containsKey("users");
        boolean arrivalRate = load.containsKey("arrivalRate");
        if (users == arrivalRate || users != MODEL_VIRTUAL_USERS.equals(model))
            throw new IllegalArgumentException("Quick Load intensity must keep the selected model");
    }

    private static String workloadId(String type, String id) {
        String safe = (type + "-" + id).replaceAll("[^A-Za-z0-9._-]", "-");
        if (safe.length() > 72) safe = safe.substring(0, 72);
        return "quick-" + safe;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? LoadIsolation.deepCopyMap((Map<String, Object>) value)
                : new LinkedHashMap<String, Object>();
    }
}
