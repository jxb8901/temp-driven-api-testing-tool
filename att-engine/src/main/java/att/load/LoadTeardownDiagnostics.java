package att.load;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates a bounded, content-free diagnostic for failed LOAD resource cleanup. */
final class LoadTeardownDiagnostics {
    private static final Pattern CODE = Pattern.compile("^([A-Z][A-Z0-9_.:-]{1,94}[A-Z0-9_])(?:\\s|:|$)");
    private static final int MAX_RESOURCES = 16;
    private static final int MAX_CAUSES = 8;

    private LoadTeardownDiagnostics() { }

    static Map<String, Object> from(Throwable failure) {
        List<Map<String, Object>> resources = new ArrayList<>();
        collect(failure, resources, 0);
        if (resources.isEmpty()) resources.add(resource("UNKNOWN", null, 1L, failure, Collections.<String, Object>emptyMap()));
        long total = 0L;
        boolean mq = false, db = false, http = false;
        for (Map<String, Object> item : resources) {
            total = saturatedAdd(total, ((Number) item.get("failedCloseCount")).longValue());
            mq |= "MQ".equals(item.get("resourceType"));
            db |= "DB".equals(item.get("resourceType"));
            http |= "HTTP".equals(item.get("resourceType"));
        }
        String primaryType = mq ? "MQ" : db ? "DB" : http ? "HTTP" : "resource";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", mq ? "MQ_RESOURCE_TEARDOWN_FAILED" : db ? "DB_RESOURCE_TEARDOWN_FAILED"
                : http ? "HTTP_RESOURCE_TEARDOWN_FAILED" : "LOAD_RESOURCE_TEARDOWN_FAILED");
        result.put("phase", "TEARDOWN");
        result.put("resourceType", primaryType);
        result.put("failedCloseCount", Math.max(1L, Math.min(1000L, total)));
        result.put("summary", "LOAD resource teardown failed; collected execution metrics remain available where produced.");
        result.put("resources", resources);
        List<String> classes = new ArrayList<String>();
        for (Map<String, Object> item : resources) {
            Object failureClass = item.get("failureClass");
            if (failureClass instanceof String && !classes.contains(failureClass)) classes.add((String) failureClass);
        }
        if (classes.size() == 1) result.put("failureClass", classes.get(0));
        else if (!classes.isEmpty()) result.put("failureClasses", classes);
        if (resources.size() == 1) copyIdentity(result, resources.get(0));
        return result;
    }

    private static void copyIdentity(Map<String, Object> target, Map<String, Object> resource) {
        for (String key : new String[] {"poolKey", "logicalId", "safeCloseClassification", "safeCloseReason",
                "confirmationState", "knownDeadBeforeTeardown", "independentOutage",
                "mqCompletionCode", "mqReasonCode"}) {
            if (resource.containsKey(key) && !target.containsKey(key)) target.put(key, resource.get(key));
        }
    }

    private static void collect(Throwable failure, List<Map<String, Object>> output, int depth) {
        if (failure == null || depth > 8 || output.size() >= MAX_RESOURCES) return;
        if (failure instanceof LoadResourceTeardownException) {
            LoadResourceTeardownException typed = (LoadResourceTeardownException) failure;
            output.add(resource(typed.resourceType(), typed.logicalId(), typed.failedCloseCount(), typed.getCause(), typed.details()));
        }
        collect(failure.getCause(), output, depth + 1);
        for (Throwable suppressed : failure.getSuppressed()) collect(suppressed, output, depth + 1);
    }

    private static Map<String, Object> resource(String type, String logicalId, long count, Throwable cause,
                                                Map<String, Object> details) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resourceType", type);
        if (logicalId != null) item.put("logicalId", logicalId);
        item.put("failedCloseCount", Math.max(1L, Math.min(1000L, count)));
        List<String> codes = new ArrayList<>();
        Long completion = null, reason = null;
        List<Throwable> pending = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        if (cause != null) pending.add(cause);
        for (int cursor = 0; cursor < pending.size() && visited.size() < MAX_CAUSES; cursor++) {
            Throwable current = pending.get(cursor);
            if (current == null || !visited.add(current)) continue;
            String code = messageCode(current.getMessage());
            if (code != null && !codes.contains(code) && codes.size() < MAX_CAUSES) codes.add(code);
            if (current instanceof att.exec.MqTransport.Exception) {
                att.exec.MqTransport.Exception mq = (att.exec.MqTransport.Exception) current;
                String reasonCode = messageCode(mq.reason());
                if (reasonCode != null && !codes.contains(reasonCode) && codes.size() < MAX_CAUSES) codes.add(reasonCode);
                if (mq.completionCode() != null) completion = mq.completionCode().longValue();
                if (mq.reasonCode() != null) reason = mq.reasonCode().longValue();
            }
            if (current.getClass().getName().equals("com.ibm.mq.MQException")) {
                completion = numericMethod(current, "getCompCode");
                reason = numericMethod(current, "getReason");
            }
            if (current.getCause() != null) pending.add(current.getCause());
            for (Throwable suppressed : current.getSuppressed()) if (suppressed != null) pending.add(suppressed);
        }
        if (!codes.isEmpty()) item.put("causeCodes", codes);
        if (completion != null) item.put("mqCompletionCode", completion);
        if (reason != null) item.put("mqReasonCode", reason);
        copyDetails(item, details);
        return item;
    }

    private static void copyDetails(Map<String, Object> item, Map<String, Object> details) {
        if (details == null || details.isEmpty()) return;
        putCode(item, "failureClass", details.get("failureClass"));
        putPoolKey(item, details.get("poolKey"));
        putToken(item, "confirmationState", details.get("confirmationState"),
                "CONFIRMED_CLOSED", "QUARANTINED", "UNCERTAIN", "TIMED_OUT", "IDLE", "LEASED", "CLOSING");
        putToken(item, "safeCloseClassification", details.get("safeCloseClassification"), "NOT_APPLIED", "CONFIRMED");
        putToken(item, "safeCloseReason", details.get("safeCloseReason"), "DISCONNECT_NOT_CONFIRMED");
        putToken(item, "observation", details.get("observation"), "PRE_TEARDOWN", "POST_TEARDOWN");
        putBoolean(item, "knownDeadBeforeTeardown", details.get("knownDeadBeforeTeardown"));
        putBoolean(item, "independentOutage", details.get("independentOutage"));
        putCount(item, "disconnectAttemptCount", details.get("disconnectAttemptCount"));
        putCount(item, "disconnectFailureCount", details.get("disconnectFailureCount"));
        putCount(item, "residualCreating", details.get("residualCreating"));
        putCount(item, "residualClosing", details.get("residualClosing"));
        putCount(item, "residualValidating", details.get("residualValidating"));
        putCount(item, "uncertainOwnershipCount", details.get("uncertainOwnershipCount"));
        putCount(item, "validationAttempts", details.get("validationAttempts"));
        putCount(item, "validationFailures", details.get("validationFailures"));
        Object classes = details.get("failureClassCounts");
        if (classes instanceof Map) {
            Map<String, Object> safe = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) classes).entrySet()) {
                if (!(entry.getKey() instanceof String) || safe.size() >= 8) continue;
                String code = code((String) entry.getKey());
                if (code != null && entry.getValue() instanceof Number) safe.put(code, Long.valueOf(((Number) entry.getValue()).longValue()));
            }
            if (!safe.isEmpty()) item.put("failureClassCounts", safe);
        }
        Object sessions = details.get("physicalSessions");
        if (sessions instanceof List) {
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
            for (Object value : (List<?>) sessions) {
                if (!(value instanceof Map) || rows.size() >= 8) continue;
                Map<String, Object> row = sessionRow((Map<?, ?>) value);
                if (!row.isEmpty()) rows.add(row);
            }
            if (!rows.isEmpty()) item.put("physicalSessions", rows);
        }
    }

    private static Map<String, Object> sessionRow(Map<?, ?> source) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        Object id = source.get("physicalId");
        if (id instanceof String && ((String) id).matches("p[0-9]{1,6}")) row.put("physicalId", id);
        putToken(row, "confirmationState", source.get("confirmationState"),
                "CONFIRMED_CLOSED", "QUARANTINED", "UNCERTAIN", "TIMED_OUT", "IDLE", "LEASED", "CLOSING", "VALIDATING", "CREATED");
        putCode(row, "failureClass", source.get("failureClass"));
        putCode(row, "failureCode", source.get("failureCode"));
        putCode(row, "knownDeadCode", source.get("knownDeadCode"));
        putCode(row, "independentOutageCode", source.get("independentOutageCode"));
        putToken(row, "lastValidationOutcome", source.get("lastValidationOutcome"),
                "PASSED", "FAILED", "UNSUPPORTED", "TIMED_OUT", "NOT_RUN");
        putToken(row, "firstObserved", source.get("firstObserved"), "BEFORE_TEARDOWN", "TEARDOWN", "NOT_FAILED");
        putCount(row, "disconnectAttemptCount", source.get("disconnectAttemptCount"));
        putCount(row, "disconnectFailureCount", source.get("disconnectFailureCount"));
        putCount(row, "idleAgeMs", source.get("idleAgeMs"));
        putCount(row, "lifetimeMs", source.get("lifetimeMs"));
        putCount(row, "lastSuccessfulUseAgeMs", source.get("lastSuccessfulUseAgeMs"));
        putCount(row, "mqCompletionCode", source.get("mqCompletionCode"));
        putCount(row, "mqReasonCode", source.get("mqReasonCode"));
        putBoolean(row, "knownDeadBeforeTeardown", source.get("knownDeadBeforeTeardown"));
        putBoolean(row, "independentOutage", source.get("independentOutage"));
        putBoolean(row, "ownershipUncertain", source.get("ownershipUncertain"));
        return row;
    }

    private static void putCode(Map<String, Object> target, String key, Object value) {
        if (!(value instanceof String)) return;
        String code = code((String) value);
        if (code != null) target.put(key, code);
    }

    private static void putPoolKey(Map<String, Object> target, Object value) {
        if (!(value instanceof String)) return;
        String key = (String) value;
        if (key.length() < 1 || key.length() > 128 || key.contains("..") || key.indexOf('/') >= 0 || key.indexOf('\\') >= 0) return;
        if (key.matches("[A-Za-z0-9_.:-]{1,128}")) target.put("poolKey", key);
    }

    private static void putToken(Map<String, Object> target, String key, Object value, String... allowed) {
        if (!(value instanceof String)) return;
        for (String candidate : allowed) if (candidate.equals(value)) { target.put(key, candidate); return; }
    }

    private static void putBoolean(Map<String, Object> target, String key, Object value) {
        if (value instanceof Boolean) target.put(key, value);
    }

    private static void putCount(Map<String, Object> target, String key, Object value) {
        if (!(value instanceof Number)) return;
        long number = ((Number) value).longValue();
        if (number < 0L || number > 864_000_000_000L) return;
        target.put(key, Long.valueOf(number));
    }

    private static String code(String value) {
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,63}") ? value : null;
    }

    private static String messageCode(String message) {
        if (message == null || message.length() > 4096) return null;
        Matcher matcher = CODE.matcher(strip(message));
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String strip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!Character.isWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!Character.isWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static Long numericMethod(Throwable failure, String methodName) {
        try {
            Method method = failure.getClass().getMethod(methodName);
            Object value = method.invoke(failure);
            return value instanceof Number ? ((Number) value).longValue() : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }
}
