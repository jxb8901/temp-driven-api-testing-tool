package att.load;

import att.Version;
import att.config.SchemaSupport;
import att.config.YamlSupport;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;
import att.validation.JsonSchemaVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads and semantically validates one att-load/v1.0 YAML scenario. */
public final class LoadScenarioLoader {
    private static final Pattern DURATION = Pattern.compile("^([0-9]+)(ms|s|m|h)$");
    private static final Pattern RATE = Pattern.compile("^([1-9][0-9]*(?:\\.[0-9]+)?)/(s|m)$");
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
            applyOverrides(map, overrides == null ? LoadOverrides.none() : overrides);
            Path schema = projectRoot.resolve("schemas/att-load-v1.0.schema.json");
            if (Files.isRegularFile(schema)) JsonSchemaVerifier.verify(schema, map);
            SchemaSupport.requireVersion(map, Version.LOAD_SCHEMA, "load scenario");
            return semantic(source, map);
        } catch (DiagnosticException e) {
            throw e;
        } catch (JsonSchemaVerifier.SchemaValidationException e) {
            DiagnosticException diagnostic = new DiagnosticException(DiagnosticCodes.LOAD_INVALID,
                    "Invalid load scenario", e.getMessage(), source.toString(), e.field(), null, null, null,
                    null, null, "Correct the scenario field and validate it against schemas/att-load-v1.0.schema.json.", e);
            throw YamlSupport.locateSchema(diagnostic, source, e.structuredViolations());
        } catch (Exception e) {
            DiagnosticException diagnostic = new DiagnosticException(DiagnosticCodes.LOAD_INVALID,
                    "Invalid load scenario", e.getMessage(), source.toString(), "scenario", null, null, null,
                    null, null, "Correct the YAML, workload model, duration/rate syntax, or target fields.", e);
            throw YamlSupport.locate(diagnostic, source, diagnostic.field());
        }
    }

    @SuppressWarnings("unchecked")
    private LoadScenario semantic(Path source, Map<String, Object> root) {
        Map<String, Object> target = map(root.get("target"), "target");
        String type = string(target.get("type"), "target.type");
        String id = string(target.get("id"), "target.id");
        Map<String, Object> arguments = mapOptional(target.get("arguments"), "target.arguments");
        Map<String, Object> inputs = mapOptional(root.get("inputs"), "inputs");
        Map<String, Object> load = map(root.get("load"), "load");
        Map<String, Object> execution = mapOptional(root.get("execution"), "execution");
        Map<String, Object> thresholds = mapOptional(root.get("thresholds"), "thresholds");
        Map<String, Object> evidence = mapOptional(root.get("evidence"), "evidence");
        if (("template".equals(type) || "flow".equals(type)) && !arguments.isEmpty())
            throw new IllegalArgumentException("target.arguments is supported only for Tool targets in V1");

        Object usersValue = load.get("users");
        Object rateValue = load.get("arrivalRate");
        LoadScenario.Model model;
        int users = 0; double rate = 0.0; String rateText = null; int maxConcurrent = 0; String overload = "";
        if (usersValue != null) {
            model = LoadScenario.Model.CLOSED;
            users = integer(usersValue, "load.users", 1);
        } else {
            model = LoadScenario.Model.ARRIVAL_RATE;
            rateText = string(rateValue, "load.arrivalRate");
            rate = parseRate(rateText, "load.arrivalRate");
            maxConcurrent = integer(load.get("maxConcurrent"), "load.maxConcurrent", 1);
            overload = string(load.get("overloadPolicy"), "load.overloadPolicy").toLowerCase(java.util.Locale.ROOT);
            if (!"drop".equals(overload)) throw new IllegalArgumentException("load.overloadPolicy supports only drop in V1");
            if (execution.get("thinkTime") != null) throw new IllegalArgumentException("execution.thinkTime is valid only for closed users workloads");
        }
        Duration warmup = duration(load.get("warmup"), "load.warmup", false);
        Duration rampUp = duration(load.get("rampUp"), "load.rampUp", false);
        Duration duration = duration(load.get("duration"), "load.duration", true);
        Duration rampDown = duration(load.get("rampDown"), "load.rampDown", false);
        Duration thinkTime = duration(execution.get("thinkTime"), "execution.thinkTime", false);
        validateThresholds(model, thresholds);
        return new LoadScenario(source, type, id, arguments, inputs, model, users, rate, rateText,
                warmup, rampUp, duration, rampDown, thinkTime, maxConcurrent, overload, thresholds, evidence);
    }

    private void applyOverrides(Map<String, Object> root, LoadOverrides overrides) {
        Map<String, Object> load = copyMap(root.get("load"));
        Map<String, Object> execution = copyMap(root.get("execution"));
        putInteger(load, "users", overrides.users()); put(load, "arrivalRate", overrides.arrivalRate());
        put(load, "warmup", overrides.warmup()); put(load, "rampUp", overrides.rampUp());
        put(load, "duration", overrides.duration()); put(load, "rampDown", overrides.rampDown());
        putInteger(load, "maxConcurrent", overrides.maxConcurrent()); put(load, "overloadPolicy", overrides.overloadPolicy());
        put(execution, "thinkTime", overrides.thinkTime());
        root.put("load", load); if (!execution.isEmpty()) root.put("execution", execution);
    }

    private static void put(Map<String, Object> map, String key, String value) { if (value != null) map.put(key, value); }
    private static void putInteger(Map<String, Object> map, String key, String value) {
        if (value == null) return;
        try { map.put(key, Integer.valueOf(value)); }
        catch (NumberFormatException e) { map.put(key, value); }
    }

    private Duration duration(Object value, String field, boolean positive) {
        if (value == null) return Duration.ZERO;
        if (!(value instanceof String)) throw new IllegalArgumentException(field + " must use a duration such as 500ms, 30s, or 5m");
        Matcher matcher = DURATION.matcher(((String) value).trim());
        if (!matcher.matches()) throw new IllegalArgumentException(field + " must use <integer><ms|s|m|h>");
        long amount;
        try { amount = Long.parseLong(matcher.group(1)); } catch (NumberFormatException e) { throw new IllegalArgumentException(field + " is too large", e); }
        if (positive && amount == 0) throw new IllegalArgumentException(field + " must be greater than zero");
        long multiplier = "ms".equals(matcher.group(2)) ? 1L : "s".equals(matcher.group(2)) ? 1000L : "m".equals(matcher.group(2)) ? 60000L : 3600000L;
        try { return Duration.ofMillis(Math.multiplyExact(amount, multiplier)); }
        catch (ArithmeticException e) { throw new IllegalArgumentException(field + " is too large", e); }
    }

    private double parseRate(String value, String field) {
        Matcher matcher = RATE.matcher(value.trim());
        if (!matcher.matches()) throw new IllegalArgumentException(field + " must use a documented rate such as 100/s or 6000/m");
        double amount = Double.parseDouble(matcher.group(1));
        double perSecond = "m".equals(matcher.group(2)) ? amount / 60.0 : amount;
        if (Double.isInfinite(perSecond) || Double.isNaN(perSecond) || perSecond <= 0.0)
            throw new IllegalArgumentException(field + " is too large or is not positive");
        return perSecond;
    }

    private void validateThresholds(LoadScenario.Model model, Map<String, Object> thresholds) {
        for (Map.Entry<String, Object> entry : thresholds.entrySet()) {
            if (model == LoadScenario.Model.CLOSED && ("droppedRate".equals(entry.getKey()) || "achievedArrivalRate".equals(entry.getKey())))
                throw new IllegalArgumentException("thresholds." + entry.getKey() + " is valid only for arrivalRate workloads");
            if (model == LoadScenario.Model.ARRIVAL_RATE && "minThroughput".equals(entry.getKey()))
                throw new IllegalArgumentException("thresholds.minThroughput is valid only for closed workloads");
            String value = String.valueOf(entry.getValue()).trim();
            if (!(value.matches("^(<|<=|>|>=|==)\\s*[0-9]+(?:\\.[0-9]+)?(%|ms|/s|/m)$")))
                throw new IllegalArgumentException("thresholds." + entry.getKey() + " must use an operator and %, ms, /s, or /m");
            if (value.endsWith("%")) {
                String numeric = value.replaceFirst("^(<|<=|>|>=|==)\\s*", "").replace("%", "").trim();
                if (Double.parseDouble(numeric) > 100.0) throw new IllegalArgumentException("thresholds." + entry.getKey() + " percentage must be between 0% and 100%");
            }
        }
    }

    private static int integer(Object value, String field, int minimum) {
        if (!(value instanceof Number) || value instanceof Float || value instanceof Double)
            throw new IllegalArgumentException(field + " must be an integer");
        long result = ((Number) value).longValue();
        if (result < minimum || result > Integer.MAX_VALUE)
            throw new IllegalArgumentException(field + " must be between " + minimum + " and " + Integer.MAX_VALUE);
        return (int) result;
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) throw new IllegalArgumentException(field + " must be a non-blank string");
        return ((String) value).trim();
    }
    private static Map<String, Object> map(Object value, String field) {
        if (!(value instanceof Map)) throw new IllegalArgumentException(field + " must be a map");
        return objectMap((Map<?, ?>) value);
    }
    private static Map<String, Object> mapOptional(Object value, String field) {
        return value == null ? new LinkedHashMap<String, Object>() : map(value, field);
    }
    private static Map<String, Object> copyMap(Object value) { return value instanceof Map ? objectMap((Map<?, ?>) value) : new LinkedHashMap<String, Object>(); }
    private static Map<String, Object> objectMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : value.entrySet()) result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }
    private DiagnosticException invalid(Path source, String summary, String field, String suggestion, Throwable cause) {
        return new DiagnosticException(DiagnosticCodes.LOAD_INVALID, summary, null, source.toString(), field, null, null, null, null, null, suggestion, cause);
    }
}
