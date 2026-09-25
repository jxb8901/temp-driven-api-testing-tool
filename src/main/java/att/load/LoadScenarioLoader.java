package att.load;

import att.Version;
import att.config.SchemaSupport;
import att.config.YamlSupport;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;
import att.validation.JsonSchemaVerifier;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads and semantically validates att-load/v1.0 and att-load/v1.1 YAML scenarios. */
public final class LoadScenarioLoader {
    private static final Pattern DURATION = Pattern.compile("^([0-9]+)(ms|s|m|h)$");
    private static final Pattern RATE = Pattern.compile("^([1-9][0-9]*(?:\\.[0-9]+)?)/(s|m)$");
    private static final Pattern WORKLOAD_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");
    private final Path projectRoot;

    public LoadScenarioLoader(Path projectRoot) { this.projectRoot = projectRoot.toAbsolutePath().normalize(); }
    public LoadScenario load(Path source) throws Exception { return load(source, LoadOverrides.none()); }

    public LoadScenario load(Path configured, LoadOverrides overrides) throws Exception {
        Path source = configured.isAbsolute() ? configured.normalize() : projectRoot.resolve(configured).normalize();
        if (!Files.isRegularFile(source) || Files.isSymbolicLink(source))
            throw invalid(source, "Load scenario does not exist or is not a regular non-symlink file", "scenario", "Create a regular YAML file and pass its path to att load.", null);
        try {
            Object loaded = YamlSupport.load(source);
            if (!(loaded instanceof Map)) throw new IllegalArgumentException("Load scenario must be a YAML map");
            Map<String, Object> map = objectMap((Map<?, ?>) loaded);
            String version = string(map.get("schemaVersion"), "schemaVersion");
            LoadOverrides effectiveOverrides = overrides == null ? LoadOverrides.none() : overrides;
            Path schema;
            if (Version.LOAD_SCHEMA.equals(version)) {
                applyLegacyOverrides(map, effectiveOverrides);
                schema = projectRoot.resolve("schemas/att-load-v1.0.schema.json");
            } else if (Version.LOAD_SCHEMA_V1_1.equals(version)) {
                applyV11Overrides(map, effectiveOverrides);
                schema = projectRoot.resolve("schemas/att-load-v1.1.schema.json");
            } else {
                throw failure("schemaVersion", "Unsupported load scenario schemaVersion '" + version + "'; expected "
                        + Version.LOAD_SCHEMA + " or " + Version.LOAD_SCHEMA_V1_1);
            }
            if (Files.isRegularFile(schema)) JsonSchemaVerifier.verify(schema, map);
            if (Version.LOAD_SCHEMA.equals(version)) {
                SchemaSupport.requireVersion(map, Version.LOAD_SCHEMA, "load scenario");
                return semanticV10(source, map);
            }
            return semanticV11(source, map);
        } catch (DiagnosticException e) {
            throw e;
        } catch (SemanticFailure e) {
            DiagnosticException diagnostic = new DiagnosticException(DiagnosticCodes.LOAD_INVALID,
                    "Invalid load scenario", e.getMessage(), source.toString(), e.field(), null, null, null,
                    null, null, "Correct the referenced load scenario field and rerun att load.", e);
            throw YamlSupport.locate(diagnostic, source, e.field());
        } catch (JsonSchemaVerifier.SchemaValidationException e) {
            String field = normalizeField(e.field());
            DiagnosticException diagnostic = new DiagnosticException(DiagnosticCodes.LOAD_INVALID,
                    "Invalid load scenario", e.getMessage(), source.toString(), field, null, null, null,
                    null, null, "Correct the scenario field and validate it against the schema selected by schemaVersion.", e);
            throw YamlSupport.locateSchema(diagnostic, source, e.structuredViolations());
        } catch (Exception e) {
            DiagnosticException diagnostic = new DiagnosticException(DiagnosticCodes.LOAD_INVALID,
                    "Invalid load scenario", e.getMessage(), source.toString(), "scenario", null, null, null,
                    null, null, "Correct the YAML, workload model, duration/rate syntax, or target fields.", e);
            throw YamlSupport.locate(diagnostic, source, diagnostic.field());
        }
    }

    private LoadScenario semanticV10(Path source, Map<String, Object> root) {
        Map<String, Object> thresholds = mapOptional(root.get("thresholds"), "thresholds");
        LoadWorkload workload = parseWorkload("default", root, "", thresholds);
        Map<String, Object> evidence = mapOptional(root.get("evidence"), "evidence");
        Long seed = longInteger(root.get("seed"), "seed");
        return new LoadScenario(source, workload.targetType(), workload.targetId(), workload.targetArguments(),
                workload.inputs(), workload.model(), workload.users(), workload.arrivalRatePerSecond(),
                workload.arrivalRate(), workload.warmup(), workload.rampUp(), workload.duration(), workload.rampDown(),
                workload.thinkTimePolicy(), seed, workload.maxConcurrent(), workload.overloadPolicy(), thresholds, evidence);
    }

    private LoadScenario semanticV11(Path source, Map<String, Object> root) {
        List<Object> raw = list(root.get("workloads"), "workloads");
        if (raw.isEmpty()) throw failure("workloads", "workloads must contain at least one workload");
        List<LoadWorkload> workloads = new ArrayList<LoadWorkload>();
        Set<String> ids = new HashSet<String>();
        LoadScenario.Model model = null;
        Duration warmup = null, rampUp = null, duration = null, rampDown = null;
        for (int i = 0; i < raw.size(); i++) {
            String prefix = "workloads[" + i + "]";
            Map<String, Object> map = map(raw.get(i), prefix);
            String id = string(map.get("id"), prefix + ".id");
            if (!WORKLOAD_ID.matcher(id).matches()) throw failure(prefix + ".id", "workload id must match [A-Za-z0-9][A-Za-z0-9._-]*");
            if (!ids.add(id)) throw failure(prefix + ".id", "duplicate workload id '" + id + "'");
            Map<String, Object> thresholds = mapOptional(map.get("thresholds"), prefix + ".thresholds");
            LoadWorkload workload = parseWorkload(id, map, prefix + ".", thresholds);
            if (model == null) model = workload.model();
            else if (model != workload.model()) throw failure(prefix + ".load", "mixed closed and arrivalRate workload models are not supported in att-load/v1.1");
            if (warmup == null) {
                warmup = workload.warmup(); rampUp = workload.rampUp(); duration = workload.duration(); rampDown = workload.rampDown();
            } else if (!warmup.equals(workload.warmup()) || !rampUp.equals(workload.rampUp())
                    || !duration.equals(workload.duration()) || !rampDown.equals(workload.rampDown())) {
                throw failure(prefix + ".load", "all workloads must use the same warmup/rampUp/duration/rampDown timing envelope");
            }
            workloads.add(workload);
        }
        Map<String, Object> thresholds = mapOptional(root.get("thresholds"), "thresholds");
        validateThresholds(model, thresholds, "thresholds");
        Map<String, Object> evidence = mapOptional(root.get("evidence"), "evidence");
        Long seed = longInteger(root.get("seed"), "seed");
        return new LoadScenario(source, Version.LOAD_SCHEMA_V1_1, workloads, seed, thresholds, evidence);
    }

    private LoadWorkload parseWorkload(String id, Map<String, Object> root, String prefix, Map<String, Object> thresholds) {
        Map<String, Object> target = map(root.get("target"), prefix + "target");
        String type = string(target.get("type"), prefix + "target.type");
        String targetId = string(target.get("id"), prefix + "target.id");
        Map<String, Object> arguments = mapOptional(target.get("arguments"), prefix + "target.arguments");
        Map<String, Object> inputs = mapOptional(root.get("inputs"), prefix + "inputs");
        Map<String, Object> load = map(root.get("load"), prefix + "load");
        Map<String, Object> execution = mapOptional(root.get("execution"), prefix + "execution");
        if (("template".equals(type) || "flow".equals(type)) && !arguments.isEmpty())
            throw failure(prefix + "target.arguments", "target.arguments is supported only for Tool targets");

        Object usersValue = load.get("users");
        Object rateValue = load.get("arrivalRate");
        LoadScenario.Model model;
        int users = 0; double rate = 0.0; String rateText = null; int maxConcurrent = 0; String overload = "";
        if (usersValue != null) {
            model = LoadScenario.Model.CLOSED;
            users = integer(usersValue, prefix + "load.users", 1);
            if (rateValue != null || load.get("maxConcurrent") != null || load.get("overloadPolicy") != null)
                throw failure(prefix + "load", "closed workload must not configure arrivalRate/maxConcurrent/overloadPolicy");
        } else {
            model = LoadScenario.Model.ARRIVAL_RATE;
            rateText = string(rateValue, prefix + "load.arrivalRate");
            rate = parseRate(rateText, prefix + "load.arrivalRate");
            maxConcurrent = integer(load.get("maxConcurrent"), prefix + "load.maxConcurrent", 1);
            overload = string(load.get("overloadPolicy"), prefix + "load.overloadPolicy").toLowerCase(java.util.Locale.ROOT);
            if (!"drop".equals(overload)) throw failure(prefix + "load.overloadPolicy", "load.overloadPolicy supports only drop");
            if (execution.get("thinkTime") != null) throw failure(prefix + "execution.thinkTime", "execution.thinkTime is valid only for closed users workloads");
        }
        Duration warmup = duration(load.get("warmup"), prefix + "load.warmup", false);
        Duration rampUp = duration(load.get("rampUp"), prefix + "load.rampUp", false);
        Duration duration = duration(load.get("duration"), prefix + "load.duration", true);
        Duration rampDown = duration(load.get("rampDown"), prefix + "load.rampDown", false);
        ThinkTimePolicy thinkTime = thinkTime(execution.get("thinkTime"), prefix + "execution.thinkTime");
        validateThresholds(model, thresholds, prefix + "thresholds");
        return new LoadWorkload(id, type, targetId, arguments, inputs, model, users, rate, rateText,
                warmup, rampUp, duration, rampDown, thinkTime, maxConcurrent, overload, thresholds);
    }

    private ThinkTimePolicy thinkTime(Object value, String field) {
        if (value == null) return ThinkTimePolicy.fixed(Duration.ZERO);
        if (value instanceof String) return ThinkTimePolicy.fixed(duration(value, field, false));
        if (!(value instanceof Map)) throw failure(field, field + " must be a duration or a {min, max} map");
        Map<String, Object> range = map(value, field);
        for (String key : range.keySet()) {
            if (!"min".equals(key) && !"max".equals(key)) throw failure(field + "." + key, field + " supports only min and max");
        }
        if (!range.containsKey("min")) throw failure(field + ".min", field + " range requires min");
        if (!range.containsKey("max")) throw failure(field + ".max", field + " range requires max");
        Duration min = duration(range.get("min"), field + ".min", false);
        Duration max = duration(range.get("max"), field + ".max", false);
        if (max.compareTo(min) < 0) throw failure(field + ".max", field + ".max must be greater than or equal to " + field + ".min");
        return ThinkTimePolicy.uniform(min, max);
    }

    private void applyLegacyOverrides(Map<String, Object> root, LoadOverrides overrides) {
        Map<String, Object> load = copyMap(root.get("load"));
        Map<String, Object> execution = copyMap(root.get("execution"));
        applyOverrideMaps(load, execution, overrides);
        root.put("load", load); if (!execution.isEmpty()) root.put("execution", execution);
    }

    private void applyV11Overrides(Map<String, Object> root, LoadOverrides overrides) {
        if (!overrides.any()) return;
        List<Object> values = list(root.get("workloads"), "workloads");
        if (values.size() != 1) throw failure("workloads", "load-model CLI overrides are ambiguous for multi-workload scenarios; configure each workload in YAML");
        Map<String, Object> workload = map(values.get(0), "workloads[0]");
        Map<String, Object> load = copyMap(workload.get("load"));
        Map<String, Object> execution = copyMap(workload.get("execution"));
        applyOverrideMaps(load, execution, overrides);
        workload.put("load", load); if (!execution.isEmpty()) workload.put("execution", execution);
        List<Object> replaced = new ArrayList<Object>(); replaced.add(workload); root.put("workloads", replaced);
    }

    private void applyOverrideMaps(Map<String, Object> load, Map<String, Object> execution, LoadOverrides overrides) {
        putInteger(load, "users", overrides.users()); put(load, "arrivalRate", overrides.arrivalRate());
        put(load, "warmup", overrides.warmup()); put(load, "rampUp", overrides.rampUp());
        put(load, "duration", overrides.duration()); put(load, "rampDown", overrides.rampDown());
        putInteger(load, "maxConcurrent", overrides.maxConcurrent()); put(load, "overloadPolicy", overrides.overloadPolicy());
        put(execution, "thinkTime", overrides.thinkTime());
    }

    private static void put(Map<String, Object> map, String key, String value) { if (value != null) map.put(key, value); }
    private static void putInteger(Map<String, Object> map, String key, String value) {
        if (value == null) return;
        try { map.put(key, Integer.valueOf(value)); }
        catch (NumberFormatException e) { map.put(key, value); }
    }

    private Duration duration(Object value, String field, boolean positive) {
        if (value == null) return Duration.ZERO;
        if (!(value instanceof String)) throw failure(field, field + " must use a duration such as 500ms, 30s, or 5m");
        Matcher matcher = DURATION.matcher(((String) value).trim());
        if (!matcher.matches()) throw failure(field, field + " must use <integer><ms|s|m|h>");
        long amount;
        try { amount = Long.parseLong(matcher.group(1)); } catch (NumberFormatException e) { throw failure(field, field + " is too large"); }
        if (positive && amount == 0) throw failure(field, field + " must be greater than zero");
        long multiplier = "ms".equals(matcher.group(2)) ? 1L : "s".equals(matcher.group(2)) ? 1000L : "m".equals(matcher.group(2)) ? 60000L : 3600000L;
        try { return Duration.ofMillis(Math.multiplyExact(amount, multiplier)); }
        catch (ArithmeticException e) { throw failure(field, field + " is too large"); }
    }

    private double parseRate(String value, String field) {
        Matcher matcher = RATE.matcher(value.trim());
        if (!matcher.matches()) throw failure(field, field + " must use a documented rate such as 100/s or 6000/m");
        double amount = Double.parseDouble(matcher.group(1));
        double perSecond = "m".equals(matcher.group(2)) ? amount / 60.0 : amount;
        if (Double.isInfinite(perSecond) || Double.isNaN(perSecond) || perSecond <= 0.0)
            throw failure(field, field + " is too large or is not positive");
        return perSecond;
    }

    private void validateThresholds(LoadScenario.Model model, Map<String, Object> thresholds, String fieldPrefix) {
        for (Map.Entry<String, Object> entry : thresholds.entrySet()) {
            String field = fieldPrefix + "." + entry.getKey();
            if (!LoadThresholdEvaluator.isSupportedName(entry.getKey()))
                throw failure(field, field + " is not a supported threshold");
            if (model == LoadScenario.Model.CLOSED && ("droppedRate".equals(entry.getKey()) || "achievedArrivalRate".equals(entry.getKey())))
                throw failure(field, field + " is valid only for arrivalRate workloads");
            String value = String.valueOf(entry.getValue()).trim();
            Matcher matcher = Pattern.compile("^(<|<=|>|>=|==)\\s*([0-9]+(?:\\.[0-9]+)?)(%|ms|/s|/m)$").matcher(value);
            if (!matcher.matches()) throw failure(field, field + " must use its documented operator and unit");
            String unit = matcher.group(3);
            if (("errorRate".equals(entry.getKey()) || "droppedRate".equals(entry.getKey())) && !"%".equals(unit))
                throw failure(field, field + " must use %");
            if ("achievedArrivalRate".equals(entry.getKey()) && !("%".equals(unit) || "/s".equals(unit) || "/m".equals(unit)))
                throw failure(field, field + " must use %, /s, or /m");
            if (("p95".equals(entry.getKey()) || "p99".equals(entry.getKey())) && !"ms".equals(unit))
                throw failure(field, field + " must use ms");
            if ("minThroughput".equals(entry.getKey()) && !("/s".equals(unit) || "/m".equals(unit)))
                throw failure(field, field + " must use /s or /m");
            if ("%".equals(unit) && Double.parseDouble(matcher.group(2)) > 100.0)
                throw failure(field, field + " percentage must be between 0% and 100%");
        }
    }

    private static int integer(Object value, String field, int minimum) {
        if (!(value instanceof Number) || value instanceof Float || value instanceof Double) throw failure(field, field + " must be an integer");
        long result = ((Number) value).longValue();
        if (result < minimum || result > Integer.MAX_VALUE) throw failure(field, field + " must be between " + minimum + " and " + Integer.MAX_VALUE);
        return (int) result;
    }

    private static Long longInteger(Object value, String field) {
        if (value == null) return null;
        if (!(value instanceof Number) || value instanceof Float || value instanceof Double) throw failure(field, field + " must be a 64-bit integer");
        try { return Long.valueOf(new BigDecimal(String.valueOf(value)).longValueExact()); }
        catch (ArithmeticException | NumberFormatException e) { throw failure(field, field + " must be a 64-bit integer"); }
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) throw failure(field, field + " must be a non-blank string");
        return ((String) value).trim();
    }
    private static Map<String, Object> map(Object value, String field) {
        if (!(value instanceof Map)) throw failure(field, field + " must be a map");
        return objectMap((Map<?, ?>) value);
    }
    private static Map<String, Object> mapOptional(Object value, String field) { return value == null ? new LinkedHashMap<String, Object>() : map(value, field); }
    private static Map<String, Object> copyMap(Object value) { return value instanceof Map ? objectMap((Map<?, ?>) value) : new LinkedHashMap<String, Object>(); }
    private static Map<String, Object> objectMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : value.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }
    private static List<Object> list(Object value, String field) {
        if (!(value instanceof List)) throw failure(field, field + " must be a list");
        return new ArrayList<Object>((List<?>) value);
    }
    private DiagnosticException invalid(Path source, String summary, String field, String suggestion, Throwable cause) {
        return new DiagnosticException(DiagnosticCodes.LOAD_INVALID, summary, null, source.toString(), field, null, null, null, null, null, suggestion, cause);
    }
    private static SemanticFailure failure(String field, String message) { return new SemanticFailure(field, message); }
    private static String normalizeField(String field) { return field != null && field.startsWith("$.") ? field.substring(2) : field; }
    private static final class SemanticFailure extends IllegalArgumentException {
        private final String field;
        private SemanticFailure(String field, String message) { super(message); this.field = field; }
        private String field() { return field; }
    }
}
