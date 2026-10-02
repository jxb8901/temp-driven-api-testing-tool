package att.testdata;

import att.core.CaseRuntimeContext;
import att.template.UnifiedTemplateEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves @{testdata} values and bootstrap-safe ${...} values before EXEC.INPUT is published. */
public final class TestdataInputResolver {
    private static final Pattern REFERENCE = Pattern.compile("@\\{([^{}]+)}");
    private static final Pattern CONTEXT = Pattern.compile("\\$\\{([^{}]+)}");
    private static final Pattern CLOSED_VU_ID = Pattern.compile("VU-([1-9][0-9]*)");
    private static final Pattern ARRIVAL_ITERATION_ID = Pattern.compile("(?:^|-)arrival-([1-9][0-9]*)$");
    private static final Pattern ITERATION_NUMBER = Pattern.compile("(?:^|-)([1-9][0-9]*)$");
    private final TestdataRegistry registry;
    private final Map<String, Object> workloadPolicies;
    private final boolean load;
    private final String workloadId;
    private final String executionScope;
    private final String model;
    private final Long defaultSeed;
    private final int stableUserCount;
    private final ConcurrentHashMap<String, Selection> selections;
    private final ConcurrentHashMap<String, AtomicLong> counters;
    private final ThreadLocal<Map<String, Map<String, Object>>> currentEvidence = new ThreadLocal<Map<String, Map<String, Object>>>();
    private final ThreadLocal<String> currentEvidenceScope = new ThreadLocal<String>();

    public TestdataInputResolver(TestdataRegistry registry) {
        this(registry, Collections.<String, Object>emptyMap(), false, "", "", "", null, 0);
    }

    public TestdataInputResolver(TestdataRegistry registry, Map<String, Object> workloadPolicies,
                                 String workloadId, String model, Long defaultSeed) {
        this(registry, workloadPolicies, workloadId, model, defaultSeed, 0);
    }

    public TestdataInputResolver(TestdataRegistry registry, Map<String, Object> workloadPolicies,
                                 String workloadId, String model, Long defaultSeed, int stableUserCount) {
        this(registry, workloadPolicies, true, workloadId, "", model, defaultSeed, stableUserCount);
    }

    public TestdataInputResolver(TestdataRegistry registry, Map<String, Object> workloadPolicies,
                                 String workloadId, String executionScope, String model, Long defaultSeed) {
        this(registry, workloadPolicies, true, workloadId, executionScope, model, defaultSeed, 0);
    }

    private TestdataInputResolver(TestdataRegistry registry, Map<String, Object> workloadPolicies,
                                  boolean load, String workloadId, String executionScope,
                                  String model, Long defaultSeed, int stableUserCount) {
        this(registry, workloadPolicies, load, workloadId, executionScope, model, defaultSeed,
                stableUserCount, new ConcurrentHashMap<String, Selection>(), new ConcurrentHashMap<String, AtomicLong>());
    }

    private TestdataInputResolver(TestdataRegistry registry, Map<String, Object> workloadPolicies,
                                  boolean load, String workloadId, String executionScope,
                                  String model, Long defaultSeed, int stableUserCount,
                                  ConcurrentHashMap<String, Selection> selections,
                                  ConcurrentHashMap<String, AtomicLong> counters) {
        this.registry = registry;
        this.workloadPolicies = workloadPolicies == null ? Collections.<String, Object>emptyMap() : workloadPolicies;
        this.load = load;
        this.workloadId = workloadId == null ? "" : workloadId;
        this.executionScope = executionScope == null ? "" : executionScope;
        this.model = model == null ? "" : model;
        this.defaultSeed = defaultSeed;
        if (stableUserCount < 0) throw new IllegalArgumentException("Load testdata stable user count must not be negative");
        this.stableUserCount = stableUserCount;
        this.selections = selections;
        this.counters = counters;
    }

    /** Creates a Testcase-local view that shares this Run's selection allocator. */
    public TestdataInputResolver forExecutionScope(String scope) {
        if (load) throw new IllegalStateException("Execution scopes are only available for Run testdata selection");
        if (scope == null || scope.trim().isEmpty()) throw new IllegalArgumentException("Testdata execution scope must not be blank");
        return new TestdataInputResolver(registry, workloadPolicies, false, workloadId, scope, model, defaultSeed,
                stableUserCount,
                selections, counters);
    }

    public Map<String, Object> resolve(Map<String, Object> mapping, CaseRuntimeContext context,
                                       String userId, String iterationId) throws Exception {
        TestdataSyntax.references(mapping);
        String evidenceScope = load ? workloadId + "|" + String.valueOf(iterationId) : executionScope;
        if (!evidenceScope.equals(currentEvidenceScope.get())) {
            currentEvidenceScope.set(evidenceScope);
            currentEvidence.set(Collections.<String, Map<String, Object>>emptyMap());
        }
        if (mapping == null || mapping.isEmpty()) {
            return Collections.emptyMap();
        }
        if (!containsMarker(mapping)) {
            return immutableMap((Map<?, ?>) deepCopy(mapping));
        }
        Map<String, Selection> selected = new LinkedHashMap<String, Selection>();
        Object resolved = mapValue(mapping, context, userId, iterationId, selected);
        if (!(resolved instanceof Map)) throw new IllegalArgumentException("Input mapping must be an object");
        Map<String, Map<String, Object>> metadata = new LinkedHashMap<String, Map<String, Object>>();
        Map<String, Map<String, Object>> previous = currentEvidence.get();
        if (previous != null) metadata.putAll(previous);
        for (Map.Entry<String, Selection> entry : selected.entrySet()) metadata.put(entry.getKey(), entry.getValue().metadata);
        currentEvidence.set(Collections.unmodifiableMap(metadata));
        return immutableMap((Map<?, ?>) resolved);
    }

    public Map<String, Object> selectionEvidence() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Map<String, Object>> evidence = currentEvidence.get();
        if (evidence == null) return Collections.emptyMap();
        List<String> ids = new ArrayList<String>(evidence.keySet());
        Collections.sort(ids);
        for (String id : ids) result.put(id, evidence.get(id));
        return Collections.unmodifiableMap(result);
    }

    private Object mapValue(Object value, CaseRuntimeContext context, String userId, String iterationId,
                            Map<String, Selection> perMapping) throws Exception {
        if (value instanceof String) return mapString((String) value, context, userId, iterationId, perMapping);
        if (value instanceof Map) {
            Map<Object, Object> result = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
                result.put(entry.getKey(), mapValue(entry.getValue(), context, userId, iterationId, perMapping));
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) result.add(mapValue(item, context, userId, iterationId, perMapping));
            return result;
        }
        return value;
    }

    private Object mapString(String value, CaseRuntimeContext context, String userId, String iterationId,
                             Map<String, Selection> perMapping) throws Exception {
        String text = value.trim();
        Matcher exactData = REFERENCE.matcher(value);
        if (exactData.matches()) return valueAt(exactData.group(1), userId, iterationId, perMapping);
        if (text.startsWith("@{") && text.endsWith("}") && !text.equals(value))
            throw new IllegalArgumentException("Exact testdata references must not have surrounding whitespace");
        if (text.contains("%{")) throw new IllegalArgumentException("%{...} is reserved for generated testdata record templates");
        if (text.contains("#{") || text.contains("&{"))
            throw new IllegalArgumentException("Input mapping supports only literals, @{...}, and bootstrap-safe ${...} references");
        if (text.contains("@{") && !REFERENCE.matcher(text).find()) throw new IllegalArgumentException("Malformed @{testdata} reference in input mapping");
        if (text.contains("${") && !CONTEXT.matcher(text).find()) throw new IllegalArgumentException("Malformed ${...} reference in input mapping");

        Matcher exactContext = CONTEXT.matcher(text);
        if (exactContext.matches()) {
            String path = contextPath(exactContext.group(1));
            return new UnifiedTemplateEngine(null).evaluate("${" + path + "}", context, null);
        }

        StringBuilder result = new StringBuilder();
        int cursor = 0;
        while (cursor < value.length()) {
            int dataStart = value.indexOf("@{", cursor);
            int contextStart = value.indexOf("${", cursor);
            int start = dataStart < 0 ? contextStart : contextStart < 0 ? dataStart : Math.min(dataStart, contextStart);
            if (start < 0) { result.append(value.substring(cursor)); break; }
            result.append(value.substring(cursor, start));
            boolean data = start == dataStart;
            Matcher matcher = (data ? REFERENCE : CONTEXT).matcher(value);
            matcher.region(start, value.length());
            if (!matcher.lookingAt()) throw new IllegalArgumentException("Malformed input mapping reference");
            Object resolved;
            if (data) resolved = valueAt(matcher.group(1), userId, iterationId, perMapping);
            else {
                String path = contextPath(matcher.group(1));
                resolved = new UnifiedTemplateEngine(null).evaluate("${" + path + "}", context, null);
            }
            if (resolved == null || resolved instanceof Map || resolved instanceof Iterable || resolved.getClass().isArray())
                throw new IllegalArgumentException("Embedded input mapping references must resolve to a non-null scalar");
            result.append(String.valueOf(resolved));
            cursor = matcher.end();
        }
        return result.toString();
    }

    private String contextPath(String reference) {
        String path = reference.trim();
        if (path.isEmpty() || !path.equals(reference)) throw new IllegalArgumentException("Bootstrap Context reference must be a non-blank path");
        String normalized = path.toUpperCase(java.util.Locale.ROOT);
        if (normalized.equals("EXEC.INPUT") || normalized.startsWith("EXEC.INPUT.")
                || normalized.startsWith("EXEC.INPUT["))
            throw new IllegalArgumentException("Input mapping cannot depend on EXEC.INPUT while it is being constructed");
        return path;
    }

    private Object valueAt(String reference, String userId, String iterationId,
                           Map<String, Selection> perMapping) throws Exception {
        String value = reference.trim();
        if (!value.equals(reference)) throw new IllegalArgumentException("Testdata reference must not contain surrounding whitespace");
        int separator = firstPathSeparator(value);
        String id = separator < 0 ? value : value.substring(0, separator);
        String path = separator < 0 ? "" : value.substring(separator);
        if (!id.matches("[A-Za-z][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid logical testdata id in @{...}: " + id);
        Selection selection = perMapping.get(id);
        if (selection == null) {
            selection = select(id, userId, iterationId);
            perMapping.put(id, selection);
        }
        return path.isEmpty() ? selection.record : traverse(selection.record, path.charAt(0) == '.' ? path.substring(1) : path);
    }

    private Selection select(String id, String userId, String iterationId) throws Exception {
        if (registry == null) throw new IllegalArgumentException("No testdata registry is configured for reference: " + id);
        final TestdataDescriptor descriptor = registry.resolve(id);
        final String layer = registry.layer(id);
        final WorkloadPolicy override = workloadPolicy(id);
        if (descriptor.count() == 1 && override != null && override.selection != null)
            throw new IllegalArgumentException("Load workload selection override is meaningless for one-record testdata: " + id);
        final TestdataSelectionPolicy policy = override != null && override.selection != null
                ? override.selection : descriptor.selection();
        if (descriptor.count() > 1 && policy == null)
            throw new IllegalArgumentException("Testdata selection policy is required for multiple records: " + id);
        final String scope = load ? (override == null || override.scope == null ? "iteration" : override.scope) : "execution";
        if (load && "user".equals(scope) && "arrivalRate".equals(model))
            throw new IllegalArgumentException("scope: user is not supported for arrivalRate workloads in att-load/v1.5");
        if (descriptor.count() == 1) return selection(descriptor, id, 0L, layer, scope, policy);

        final String scopeKey;
        if (!load) scopeKey = executionScope + "|" + id + "|execution";
        else if ("workload".equals(scope)) scopeKey = workloadId + "|" + id + "|workload";
        else if ("user".equals(scope)) {
            if (userId == null || userId.trim().isEmpty()) throw new IllegalArgumentException("scope: user requires a stable closed-VU userId");
            scopeKey = workloadId + "|" + id + "|user|" + userId;
        } else {
            if (iterationId == null || iterationId.trim().isEmpty()) throw new IllegalArgumentException("scope: iteration requires an iterationId");
            scopeKey = workloadId + "|" + id + "|iteration|" + iterationId;
        }
        Selection selected = selections.computeIfAbsent(scopeKey,
                key -> choose(descriptor, id, layer, scope, policy, key, userId, iterationId));
        return selected;
    }

    private Selection choose(TestdataDescriptor descriptor, String id, String layer, String scope,
                             TestdataSelectionPolicy policy, String scopeKey,
                             String userId, String iterationId) {
        String strategy = policy == null ? "sequential" : policy.strategy();
        String exhaustion = policy == null ? "error" : policy.exhaustion();
        long ordinal;
        if (load) {
            ordinal = loadOrdinal(scope, userId, iterationId);
        } else {
            String counterKey = workloadId + "|" + id + "|" + strategy + "|" + exhaustion;
            AtomicLong counter = counters.computeIfAbsent(counterKey, key -> new AtomicLong());
            ordinal = counter.getAndIncrement();
        }
        long count = descriptor.count();
        long index;
        Long effectiveSeed = policy == null ? defaultSeed : policy.seed() == null ? defaultSeed : policy.seed();
        if ("random".equals(strategy)) {
            long seed = effectiveSeed == null ? System.nanoTime() : effectiveSeed.longValue();
            effectiveSeed = Long.valueOf(seed);
            long randomOrdinal = ordinal;
            if (ordinal >= count) {
                if ("error".equals(exhaustion)) throw exhausted(id, count, exhaustion);
                if ("stop".equals(exhaustion)) throw stop(id, count);
                randomOrdinal = ordinal % count;
            }
            index = randomIndex(count, seed ^ id.hashCode(), randomOrdinal);
        } else if ("roundRobin".equals(strategy)) {
            if (ordinal >= count && "error".equals(exhaustion)) throw exhausted(id, count, exhaustion);
            if (ordinal >= count && "stop".equals(exhaustion)) throw stop(id, count);
            index = ordinal % count;
        } else if (ordinal < count) {
            index = ordinal;
        } else if ("recycle".equals(exhaustion)) {
            index = ordinal % count;
        } else if ("stop".equals(exhaustion)) {
            throw stop(id, count);
        } else {
            throw exhausted(id, count, exhaustion);
        }
        return selection(descriptor, id, index, layer, scope, policy, effectiveSeed);
    }

    private long loadOrdinal(String scope, String userId, String iterationId) {
        if ("workload".equals(scope)) return 0L;
        if ("user".equals(scope)) return stableUserOrdinal(userId);
        if ("arrivalRate".equals(model)) {
            java.util.regex.Matcher planned = ARRIVAL_ITERATION_ID.matcher(iterationId);
            if (!planned.find()) throw new IllegalArgumentException("Arrival-rate iterationId must end with -arrival-<sequence>");
            return positiveOrdinal(planned.group(1), "arrival-rate iteration sequence");
        }
        long userOrdinal = stableUserOrdinal(userId);
        java.util.regex.Matcher perUserIteration = ITERATION_NUMBER.matcher(iterationId);
        if (!perUserIteration.find()) throw new IllegalArgumentException("Closed-VU iterationId must end with its positive iteration number");
        long iterationOrdinal = positiveOrdinal(perUserIteration.group(1), "closed-VU iteration number");
        if (stableUserCount > 0) {
            if (userOrdinal >= stableUserCount)
                throw new IllegalArgumentException("Closed-VU userId exceeds the configured Load workload user count");
            // Order closed-VU identities by iteration round, then VU number.
            // The configured user count makes this ordinal unique and independent
            // of which worker reaches the resolver first.
            if (iterationOrdinal > (Long.MAX_VALUE - userOrdinal) / stableUserCount) return Long.MAX_VALUE;
            return iterationOrdinal * stableUserCount + userOrdinal;
        }
        // Public resolver instances created without scheduler metadata still get
        // a stable, collision-free order from the VU/iteration pair.
        java.math.BigInteger rank = java.math.BigInteger.valueOf(iterationOrdinal)
                .add(java.math.BigInteger.valueOf(userOrdinal));
        rank = rank.multiply(rank.add(java.math.BigInteger.ONE)).divide(java.math.BigInteger.valueOf(2L))
                .add(java.math.BigInteger.valueOf(iterationOrdinal));
        return rank.compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0
                ? Long.MAX_VALUE : rank.longValue();
    }

    private static long stableUserOrdinal(String userId) {
        if (userId == null) throw new IllegalArgumentException("Stable VU userId is required for testdata selection");
        java.util.regex.Matcher match = CLOSED_VU_ID.matcher(userId);
        if (!match.matches()) throw new IllegalArgumentException("Closed-VU userId must use the stable VU-<number> form");
        return positiveOrdinal(match.group(1), "closed-VU user number");
    }

    private static long positiveOrdinal(String value, String description) {
        try {
            long number = Long.parseLong(value);
            if (number < 1L) throw new NumberFormatException();
            return number - 1L;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Testdata " + description + " is outside the supported range");
        }
    }

    private Selection selection(TestdataDescriptor descriptor, String id, long index, String layer,
                                String scope, TestdataSelectionPolicy policy) {
        Long effectiveSeed = policy == null ? defaultSeed : policy.seed() == null ? defaultSeed : policy.seed();
        return selection(descriptor, id, index, layer, scope, policy, effectiveSeed);
    }

    private Selection selection(TestdataDescriptor descriptor, String id, long index, String layer,
                                String scope, TestdataSelectionPolicy policy, Long seed) {
        Object record = descriptor.record(index);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("id", id);
        metadata.put("layer", layer);
        metadata.put("index", Long.valueOf(index));
        if (descriptor.generated()) metadata.put("seq", descriptor.sequence(index));
        metadata.put("scope", scope);
        if (policy != null) metadata.put("strategy", policy.strategy());
        if (seed != null && policy != null && "random".equals(policy.strategy())) metadata.put("seed", seed);
        Map<String, Object> immutable = Collections.unmodifiableMap(metadata);
        return new Selection(record, index, immutable);
    }

    private WorkloadPolicy workloadPolicy(String id) {
        Object raw = workloadPolicies.get(id);
        if (raw == null) return null;
        if (!(raw instanceof Map)) throw new IllegalArgumentException("Load workload testdata policy for '" + id + "' must be an object");
        Map<?, ?> map = (Map<?, ?>) raw;
        Object rawScope = map.get("scope");
        String scope = rawScope == null ? null : String.valueOf(rawScope);
        if (scope != null && !("workload".equals(scope) || "user".equals(scope) || "iteration".equals(scope)))
            throw new IllegalArgumentException("Load workload testdata scope must be workload, user, or iteration: " + id);
        Object rawSelection = map.get("selection");
        TestdataSelectionPolicy selection = rawSelection == null ? null
                : TestdataSelectionPolicy.parse((Map<?, ?>) rawSelection, "workload.testdata." + id + ".selection");
        return new WorkloadPolicy(scope, selection);
    }

    private static IllegalStateException exhausted(String id, long count, String policy) {
        return new IllegalStateException("Testdata selection exhausted for '" + id + "' after " + count + " records (exhaustion: " + policy + ")");
    }

    private static TestdataStopException stop(String id, long count) {
        return new TestdataStopException("Testdata selection exhausted for '" + id + "' after " + count + " records (exhaustion: stop)");
    }

    private static int firstPathSeparator(String value) {
        int dot = value.indexOf('.');
        int bracket = value.indexOf('[');
        if (dot < 0) return bracket;
        return bracket < 0 ? dot : Math.min(dot, bracket);
    }

    static Object traverse(Object value, String path) {
        if (path.isEmpty()) return value;
        int index = 0;
        while (index < path.length()) {
            if (path.charAt(index) == '.') { index++; continue; }
            if (path.charAt(index) == '[') {
                int end = path.indexOf(']', index + 1);
                if (end < 0) throw new IllegalArgumentException("Malformed testdata path index: " + path);
                String raw = path.substring(index + 1, end);
                if (!raw.matches("[0-9]+")) throw new IllegalArgumentException("Testdata list path index must be a non-negative integer");
                int item;
                try { item = Integer.parseInt(raw); } catch (NumberFormatException error) { throw new IllegalArgumentException("Testdata list path index is too large"); }
                if (!(value instanceof List) || item >= ((List<?>) value).size()) throw new IllegalArgumentException("Testdata path does not exist: " + path);
                value = ((List<?>) value).get(item); index = end + 1; continue;
            }
            int end = index;
            while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') end++;
            String key = path.substring(index, end);
            if (key.isEmpty() || !(value instanceof Map) || !((Map<?, ?>) value).containsKey(key))
                throw new IllegalArgumentException("Testdata path does not exist: " + path);
            value = ((Map<?, ?>) value).get(key); index = end;
        }
        return value;
    }

    /** Seeded affine permutation: deterministic, indexable, and O(1) for virtual ranges. */
    private static long randomIndex(long count, long seed, long ordinal) {
        if (count <= 1L) return 0L;
        Random random = new Random(seed);
        long multiplier = 1L + random.nextInt((int) (count - 1L));
        while (gcd(multiplier, count) != 1L) multiplier++;
        long offset = random.nextInt((int) count);
        return (multiplier * ordinal + offset) % count;
    }

    private static long gcd(long left, long right) {
        while (right != 0L) { long remainder = left % right; left = right; right = remainder; }
        return left;
    }

    private static Map<String, Object> immutableMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : source.entrySet()) result.put(String.valueOf(entry.getKey()), deepCopy(entry.getValue()));
        return Collections.unmodifiableMap(result);
    }

    private static boolean containsMarker(Object value) {
        if (value instanceof String) return ((String) value).contains("@{") || ((String) value).contains("${");
        if (value instanceof Map) for (Object item : ((Map<?, ?>) value).values()) if (containsMarker(item)) return true;
        if (value instanceof Iterable) for (Object item : (Iterable<?>) value) if (containsMarker(item)) return true;
        return false;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map) {
            Map<Object, Object> copy = new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) copy.put(entry.getKey(), deepCopy(entry.getValue()));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable) {
            List<Object> copy = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) copy.add(deepCopy(item));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    private static final class WorkloadPolicy {
        private final String scope;
        private final TestdataSelectionPolicy selection;
        private WorkloadPolicy(String scope, TestdataSelectionPolicy selection) { this.scope = scope; this.selection = selection; }
    }

    private static final class Selection {
        private final Object record;
        @SuppressWarnings("unused") private final long index;
        @SuppressWarnings("unused") private final Map<String, Object> metadata;
        private Selection(Object record, long index, Map<String, Object> metadata) {
            this.record = record; this.index = index; this.metadata = metadata;
        }
    }
}
