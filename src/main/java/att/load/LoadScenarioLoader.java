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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads and semantically validates current att-load/v1.4 descriptors and historical v1.3 scenarios. */
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
            boolean normalizeHistoricalV13 = false;
            if (Version.LOAD_SCHEMA_CURRENT.equals(version)) {
                applyCurrentOverrides(map, effectiveOverrides);
                schema = att.validation.SchemaFiles.resolve(projectRoot, "att-load-v1.4.schema.json");
            } else if (Version.LOAD_SCHEMA_V1_3.equals(version)) {
                applyCurrentOverrides(map, effectiveOverrides);
                schema = att.validation.SchemaFiles.resolveVersion(projectRoot, Version.LOAD_SCHEMA_V1_3);
                normalizeHistoricalV13 = true;
            } else if (Version.LOAD_SCHEMA_V1_2.equals(version)) {
                schema = att.validation.SchemaFiles.resolveVersion(projectRoot, Version.LOAD_SCHEMA_V1_2);
            } else {
                throw failure("schemaVersion", "Unsupported load scenario schemaVersion '" + version + "'; current schema is "
                        + Version.LOAD_SCHEMA_CURRENT + " (historical workloads schema " + Version.LOAD_SCHEMA_V1_3 + "). Update to workloads-based syntax and use execution.execIdFormat; see docs/reference/appendices/migrations.md.");
            }
            Path currentSchema = att.validation.SchemaFiles.resolve(projectRoot, "att-load-v1.4.schema.json");
            if (normalizeHistoricalV13) {
                SchemaSupport.requireVersion(map, Version.LOAD_SCHEMA_V1_3, "historical load scenario");
                JsonSchemaVerifier.verify(schema, map);
                map.put("schemaVersion", Version.LOAD_SCHEMA_CURRENT);
                JsonSchemaVerifier.verify(currentSchema, map);
            } else {
                att.validation.SchemaMigrationGuidance.verify(schema, currentSchema, map, version, Version.LOAD_SCHEMA_CURRENT);
            }
            SchemaSupport.requireVersion(map, Version.LOAD_SCHEMA_CURRENT, "load scenario");
            return semanticCurrent(source, map, true);
        } catch (DiagnosticException e) {
            throw e;
        } catch (SemanticFailure e) {
            DiagnosticException diagnostic = new DiagnosticException(DiagnosticCodes.LOAD_INVALID,
                    "Invalid load scenario", e.getMessage(), source.toString(), e.field(), null, null, null,
                    null, null, "Correct the referenced load scenario field and rerun att load.", e);
            throw YamlSupport.locate(diagnostic, source, e.field());
        } catch (att.validation.SchemaMigrationGuidance.MigrationException e) {
            String field = normalizeField(e.field());
            DiagnosticException diagnostic = new DiagnosticException(DiagnosticCodes.LOAD_INVALID,
                    "Invalid load scenario", e.getMessage(), source.toString(), field, null, null, null,
                    null, null, "Review the schema migration guidance and correct the reported scenario field.", e);
            JsonSchemaVerifier.SchemaValidationException schemaError = JsonSchemaVerifier.SchemaValidationException.find(e);
            if (schemaError != null) throw YamlSupport.locateSchema(diagnostic, source, schemaError.structuredViolations());
            throw YamlSupport.locate(diagnostic, source, field);
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

    /** Returns the optional policy-only load/load.yaml descriptor for Quick Load and discovery. */
    public Map<String, Object> loadDefaultPolicy() throws Exception {
        Path source = projectRoot.resolve("load/load.yaml").normalize();
        if (!Files.exists(source)) return null;
        if (!Files.isRegularFile(source) || Files.isSymbolicLink(source))
            throw invalid(source, "Load policy must be a regular non-symlink file", "policy", "Create a regular att-load/v1.4 YAML policy descriptor.", null);
        Object loaded = YamlSupport.load(source);
        if (!(loaded instanceof Map)) throw invalid(source, "Load policy must be a YAML map", "policy", "Create a valid att-load/v1.4 YAML policy descriptor.", null);
        Map<String, Object> map = objectMap((Map<?, ?>) loaded);
        String version = string(map.get("schemaVersion"), "schemaVersion");
        if (Version.LOAD_SCHEMA_CURRENT.equals(version)) {
            JsonSchemaVerifier.verify(att.validation.SchemaFiles.resolve(projectRoot, "att-load-v1.4.schema.json"), map);
            LoadScenario scenario = semanticCurrent(source, map, false);
            if (!scenario.policyOnly()) throw quickLoadFailure(source, "workloads", "load/load.yaml is a policy-only descriptor and must not declare workloads");
            return map;
        }
        if (!LoadProfileLoader.SCHEMA_VERSION.equals(version))
            throw quickLoadFailure(source, "schemaVersion", "Unsupported load policy schemaVersion '" + version + "'; migrate it to " + Version.LOAD_SCHEMA_CURRENT);
        JsonSchemaVerifier.verify(att.validation.SchemaFiles.resolveVersion(projectRoot, LoadProfileLoader.SCHEMA_VERSION), map);
        SchemaSupport.requireVersion(map, LoadProfileLoader.SCHEMA_VERSION, "historical load policy");
        Map<String, Object> migrated = new LinkedHashMap<String, Object>();
        migrated.put("schemaVersion", Version.LOAD_SCHEMA_CURRENT);
        for (String field : new String[]{"load", "execution", "thresholds", "evidence", "seed"})
            if (map.containsKey(field)) migrated.put(field, map.get(field));
        JsonSchemaVerifier.verify(att.validation.SchemaFiles.resolve(projectRoot, "att-load-v1.4.schema.json"), migrated);
        semanticCurrent(source, migrated, false);
        return migrated;
    }

    /** Builds the transient one-workload Load scenario used by `load --debug`. */
    public LoadScenario fromDebugInput(Path source, String targetType, String targetId,
                                       Map<String, Object> inputs, Map<String, Object> vars,
                                       att.core.ExecutionOptions options) {
        return fromDebugInput(source, targetType, targetId, inputs, vars, Collections.<String, Object>emptyMap(), null, options);
    }

    public LoadScenario fromDebugInput(Path source, String targetType, String targetId,
                                       Map<String, Object> inputs, Map<String, Object> vars,
                                       Map<String, Object> arguments, Map<String, Object> profile,
                                       att.core.ExecutionOptions options) {
        if (!("template".equals(targetType) || "flow".equals(targetType) || "tool".equals(targetType)))
            throw failure("target.type", "load --debug target must be Template, Flow, or Tool");
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", Version.LOAD_SCHEMA_CURRENT);
        if (profile != null) {
            for (String field : new String[]{"seed", "thresholds", "evidence"}) if (profile.containsKey(field)) root.put(field, profile.get(field));
        }
        Map<String, Object> workload = new LinkedHashMap<String, Object>();
        workload.put("id", "debug-" + targetId.replaceAll("[^A-Za-z0-9._-]", "-"));
        Map<String, Object> target = mapOf("type", targetType, "id", targetId);
        if ("tool".equals(targetType) && arguments != null && !arguments.isEmpty()) target.put("arguments", LoadIsolation.deepCopyMap(arguments));
        workload.put("target", target);
        if (inputs != null && !inputs.isEmpty()) workload.put("inputs", LoadIsolation.deepCopyMap(inputs));
        if (vars != null && !vars.isEmpty()) workload.put("vars", LoadIsolation.deepCopyMap(vars));
        Map<String, Object> load = profile == null ? new LinkedHashMap<String, Object>() : copyMap(profile.get("load"));
        Map<String, Object> execution = profile == null ? new LinkedHashMap<String, Object>() : copyMap(profile.get("execution"));
        LoadOverrides intensity = new LoadOverrides(options.loadUsers(), options.loadArrivalRate(), options.loadWarmup(),
                options.loadRampUp(), options.loadDuration(), options.loadRampDown(), options.loadThinkTime(),
                options.loadMaxConcurrent(), options.loadOverloadPolicy());
        applyOverrideMaps(load, execution, intensity);
        if (load.get("users") == null && load.get("arrivalRate") == null)
            throw quickLoadFailure(source, "load", "Quick Load needs a policy: add load/load.yaml or provide --users/--arrival-rate with the required --duration (arrival rate also needs --max-concurrent and --overload-policy)");
        if (load.get("duration") == null)
            throw quickLoadFailure(source, "load.duration", "Quick Load needs duration from load/load.yaml or --duration");
        if (!load.isEmpty()) root.put("load", load);
        if (!execution.isEmpty()) root.put("execution", execution);
        root.put("workloads", java.util.Collections.<Object>singletonList(workload));
        try { JsonSchemaVerifier.verify(att.validation.SchemaFiles.resolve(projectRoot, "att-load-v1.4.schema.json"), root); }
        catch (Exception error) { throw quickLoadFailure(source, "load", "Invalid composed Quick Load policy: " + error.getMessage()); }
        return semanticCurrent(source, root, false);
    }

    private DiagnosticException quickLoadFailure(Path source, String field, String detail) {
        return new DiagnosticException(DiagnosticCodes.LOAD_INVALID, "Invalid Quick Load policy", detail,
                source == null ? null : source.toString(), field, null, null, null, null, null,
                "Add a valid load/load.yaml policy or provide compatible CLI pacing options, then retry.", null);
    }

    private static Map<String, Object> mapOf(String key1, Object value1, String key2, Object value2) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put(key1, value1); result.put(key2, value2); return result;
    }
    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put(key, value); return result;
    }

    private LoadScenario semanticCurrent(Path source, Map<String, Object> root, boolean sourceIsScenario) {
        removeDisabledKeys(root);
        List<Object> raw = root.containsKey("workloads") ? list(root.get("workloads"), "workloads") : Collections.<Object>emptyList();
        Map<String, Object> rootLoad = configMapOptional(root.get("load"), "load");
        Map<String, Object> rootExecution = configMapOptional(root.get("execution"), "execution");
        Map<String, Object> workloadExecutionDefaults = new LinkedHashMap<String, Object>(rootExecution);
        workloadExecutionDefaults.remove("execIdFormat");
        Map<String, Object> rootThresholds = configMapOptional(root.get("thresholds"), "thresholds");
        if (raw.isEmpty()) {
            if (rootLoad.isEmpty()) throw failure("load", "policy-only load descriptor requires a root load policy");
            parsePolicy("policy", rootLoad, workloadExecutionDefaults, rootThresholds);
            Long policySeed = longInteger(root.get("seed"), "seed");
            Map<String, Object> policyEvidence = evidenceMap(root.get("evidence"), "evidence");
            String policyFormat = rootExecution.containsKey("execIdFormat") ? string(rootExecution.get("execIdFormat"), "execution.execIdFormat") : "";
            try { LoadExecutionIdPattern.validate(policyFormat, parsePolicy("policy", rootLoad, workloadExecutionDefaults, rootThresholds).model()); }
            catch (IllegalArgumentException error) { throw failure("execution.execIdFormat", error.getMessage()); }
            return new LoadScenario(source, String.valueOf(root.get("schemaVersion")), Collections.<LoadWorkload>emptyList(),
                    policySeed, rootThresholds, policyEvidence, rootLoad, rootExecution, true).withExecIdFormat(policyFormat);
        }
        List<LoadWorkload> workloads = new ArrayList<LoadWorkload>();
        Set<String> ids = new HashSet<String>();
        LoadScenario.Model model = null;
        Duration warmup = null, rampUp = null, duration = null, rampDown = null;
        for (int i = 0; i < raw.size(); i++) {
            String prefix = "workloads[" + i + "]";
            Map<String, Object> map = map(raw.get(i), prefix);
            removeDisabledKeys(map);
            String id = string(map.get("id"), prefix + ".id");
            if (!WORKLOAD_ID.matcher(id).matches()) throw failure(prefix + ".id", "workload id must match [A-Za-z0-9][A-Za-z0-9._-]*");
            if (!ids.add(id)) throw failure(prefix + ".id", "duplicate workload id '" + id + "'");
            Map<String, Object> localThresholds = configMapOptional(map.get("thresholds"), prefix + ".thresholds");
            Map<String, Object> thresholds = localThresholds;
            Map<String, Object> effective = new LinkedHashMap<String, Object>(map);
            effective.put("load", mergeMaps(rootLoad, configMapOptional(map.get("load"), prefix + ".load")));
            effective.put("execution", mergeMaps(workloadExecutionDefaults, configMapOptional(map.get("execution"), prefix + ".execution")));
            LoadWorkload workload = parseWorkload(id, effective, prefix + ".", thresholds, sourceIsScenario ? i : -1, false);
            if (model == null) {
                model = workload.model();
                validateThresholds(model, rootThresholds, "thresholds");
            }
            else if (model != workload.model()) throw failure(prefix + ".load", "mixed closed and arrivalRate workload models are not supported in " + Version.LOAD_SCHEMA_CURRENT);
            validateThresholds(model, localThresholds, prefix + ".thresholds");
            if (warmup == null) {
                warmup = workload.warmup(); rampUp = workload.rampUp(); duration = workload.duration(); rampDown = workload.rampDown();
            } else if (!warmup.equals(workload.warmup()) || !rampUp.equals(workload.rampUp())
                    || !duration.equals(workload.duration()) || !rampDown.equals(workload.rampDown())) {
                throw failure(prefix + ".load", "all workloads must use the same warmup/rampUp/duration/rampDown timing envelope");
            }
            workloads.add(workload);
        }
        Map<String, Object> thresholds = rootThresholds;
        validateThresholds(model, thresholds, "thresholds");
        Map<String, Object> evidence = evidenceMap(root.get("evidence"), "evidence");
        Long seed = longInteger(root.get("seed"), "seed");
        Map<String, Object> execution = rootExecution;
        String format = execution.containsKey("execIdFormat") ? string(execution.get("execIdFormat"), "execution.execIdFormat") : "";
        try { LoadExecutionIdPattern.validate(format, model); }
        catch (IllegalArgumentException error) { throw failure("execution.execIdFormat", error.getMessage()); }
        return new LoadScenario(source, String.valueOf(root.get("schemaVersion")), workloads, seed, thresholds, evidence,
                rootLoad, rootExecution, false)
                .withExecIdFormat(format);
    }

    private LoadWorkload parsePolicy(String id, Map<String, Object> load, Map<String, Object> execution,
                                     Map<String, Object> thresholds) {
        Map<String, Object> policy = new LinkedHashMap<String, Object>();
        policy.put("target", mapOf("type", "tool", "id", "policy"));
        policy.put("load", load);
        if (!execution.isEmpty()) policy.put("execution", execution);
        LoadWorkload workload = parseWorkload(id, policy, "load.", thresholds, -1, false);
        validateThresholds(workload.model(), thresholds, "thresholds");
        return workload;
    }

    private static Map<String, Object> mergeMaps(Map<String, Object> defaults, Map<String, Object> override) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (defaults != null) result.putAll(defaults);
        if (override != null) result.putAll(override);
        return result;
    }

    private LoadWorkload parseWorkload(String id, Map<String, Object> root, String prefix,
                                       Map<String, Object> thresholds, int sourceIndex) {
        return parseWorkload(id, root, prefix, thresholds, sourceIndex, true);
    }

    private LoadWorkload parseWorkload(String id, Map<String, Object> root, String prefix,
                                       Map<String, Object> thresholds, int sourceIndex, boolean validateThresholds) {
        Map<String, Object> target = configMap(root.get("target"), prefix + "target");
        String type = string(target.get("type"), prefix + "target.type");
        String targetId = string(target.get("id"), prefix + "target.id");
        Map<String, Object> arguments = mapOptional(target.get("arguments"), prefix + "target.arguments");
        Map<String, Object> inputs = mapOptional(root.get("inputs"), prefix + "inputs");
        Map<String, Object> vars = mapOptional(root.get("vars"), prefix + "vars");
        Map<String, Object> load = configMap(root.get("load"), prefix + "load");
        Map<String, Object> execution = configMapOptional(root.get("execution"), prefix + "execution");
        if (("template".equals(type) || "flow".equals(type)) && !arguments.isEmpty())
            throw failure(prefix + "target.arguments", "target.arguments is supported only for Tool targets");
        if ("tool".equals(type) && !vars.isEmpty())
            throw failure(prefix + "vars", "workload.vars is supported only for Template and Flow targets; Tool arguments remain a separate contract");

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
        if (validateThresholds) validateThresholds(model, thresholds, prefix + "thresholds");
        return new LoadWorkload(id, type, targetId, arguments, inputs, vars, model, users, rate, rateText,
                warmup, rampUp, duration, rampDown, thinkTime, maxConcurrent, overload, thresholds, sourceIndex);
    }

    private ThinkTimePolicy thinkTime(Object value, String field) {
        if (value == null) return ThinkTimePolicy.fixed(Duration.ZERO);
        if (value instanceof String) return ThinkTimePolicy.fixed(duration(value, field, false));
        if (!(value instanceof Map)) throw failure(field, field + " must be a duration or a {min, max} map");
        Map<String, Object> range = configMap(value, field);
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

    private void applyCurrentOverrides(Map<String, Object> root, LoadOverrides overrides) {
        if (!overrides.any()) return;
        if (!root.containsKey("workloads")) throw failure("workloads", "load-model CLI overrides require an executable workload; use load --debug with a policy-only descriptor");
        List<Object> values = list(root.get("workloads"), "workloads");
        if (values.size() != 1) throw failure("workloads", "load-model CLI overrides are ambiguous for multi-workload scenarios; configure each workload in YAML");
        Map<String, Object> workload = map(values.get(0), "workloads[0]");
        Map<String, Object> load = mergeMaps(copyMap(root.get("load")), copyMap(workload.get("load")));
        Map<String, Object> execution = mergeMaps(copyMap(root.get("execution")), copyMap(workload.get("execution")));
        execution.remove("execIdFormat");
        Map<String, Object> inputs = copyMap(workload.get("inputs"));
        Map<String, Object> vars = copyMap(workload.get("vars"));
        Map<String, Object> target = copyMap(workload.get("target"));
        String targetType = String.valueOf(target.get("type"));
        if (att.core.CliSetOverrides.hasNamespace(overrides.setOverrides(), "arg") && !"tool".equals(targetType))
            throw failure("target.arguments", "--set arg.* is valid only for a Tool workload");
        if (att.core.CliSetOverrides.hasNamespace(overrides.setOverrides(), "vars") && "tool".equals(targetType))
            throw failure("vars", "--set vars.* is not supported for Tool workloads; use --set arg.* for Tool arguments");
        inputs = att.core.CliSetOverrides.apply(inputs, overrides.setOverrides(), "input");
        vars = att.core.CliSetOverrides.apply(vars, overrides.setOverrides(), "vars");
        if (att.core.CliSetOverrides.hasNamespace(overrides.setOverrides(), "input") || workload.containsKey("inputs")) workload.put("inputs", inputs);
        if (att.core.CliSetOverrides.hasNamespace(overrides.setOverrides(), "vars") || workload.containsKey("vars")) workload.put("vars", vars);
        if (att.core.CliSetOverrides.hasNamespace(overrides.setOverrides(), "arg")) {
            Map<String, Object> arguments = copyMap(target.get("arguments"));
            target.put("arguments", att.core.CliSetOverrides.apply(arguments, overrides.setOverrides(), "arg"));
            workload.put("target", target);
        }
        applyOverrideMaps(load, execution, overrides);
        workload.put("load", load); if (!execution.isEmpty()) workload.put("execution", execution);
        List<Object> replaced = new ArrayList<Object>(); replaced.add(workload); root.put("workloads", replaced);
    }

    private void applyOverrideMaps(Map<String, Object> load, Map<String, Object> execution, LoadOverrides overrides) {
        if (overrides.users() != null) {
            load.remove("arrivalRate"); load.remove("maxConcurrent"); load.remove("overloadPolicy");
            putInteger(load, "users", overrides.users());
        }
        if (overrides.arrivalRate() != null) {
            load.remove("users");
            put(load, "arrivalRate", overrides.arrivalRate());
        }
        put(load, "warmup", overrides.warmup()); put(load, "rampUp", overrides.rampUp());
        put(load, "duration", overrides.duration()); put(load, "rampDown", overrides.rampDown());
        putInteger(load, "maxConcurrent", overrides.maxConcurrent()); put(load, "overloadPolicy", overrides.overloadPolicy());
        if (overrides.arrivalRate() != null && overrides.thinkTime() == null) execution.remove("thinkTime");
        else put(execution, "thinkTime", overrides.thinkTime());
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
            if (SchemaSupport.isDisabledKey(entry.getKey())) continue;
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
    private static Map<String, Object> configMapOptional(Object value, String field) {
        Map<String, Object> result = mapOptional(value, field);
        removeDisabledKeys(result);
        return result;
    }
    private static Map<String, Object> configMap(Object value, String field) {
        Map<String, Object> result = map(value, field);
        removeDisabledKeys(result);
        return result;
    }
    private static Map<String, Object> evidenceMap(Object value, String field) {
        Map<String, Object> result = configMapOptional(value, field);
        if (result.get("resources") instanceof Map) result.put("resources", configMap(result.get("resources"), field + ".resources"));
        return result;
    }
    private static void removeDisabledKeys(Map<?, ?> value) {
        java.util.Iterator<?> keys = value.keySet().iterator();
        while (keys.hasNext()) if (SchemaSupport.isDisabledKey(keys.next())) keys.remove();
    }
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
