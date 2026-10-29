package att.load;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates a bounded, content-free diagnostic for failed LOAD resource cleanup. */
final class LoadTeardownDiagnostics {
    private static final Pattern CODE = Pattern.compile("^([A-Z][A-Z0-9_.:-]{2,95})(?:\\s|:|$)");
    private static final int MAX_RESOURCES = 16;
    private static final int MAX_CAUSES = 8;

    private LoadTeardownDiagnostics() { }

    static Map<String, Object> from(Throwable failure) {
        List<Map<String, Object>> resources = new ArrayList<>();
        collect(failure, resources, 0);
        if (resources.isEmpty()) resources.add(resource("UNKNOWN", null, 1L, failure));
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
        return result;
    }

    private static void collect(Throwable failure, List<Map<String, Object>> output, int depth) {
        if (failure == null || depth > 8 || output.size() >= MAX_RESOURCES) return;
        if (failure instanceof LoadResourceTeardownException) {
            LoadResourceTeardownException typed = (LoadResourceTeardownException) failure;
            output.add(resource(typed.resourceType(), typed.logicalId(), typed.failedCloseCount(), typed.getCause()));
        }
        collect(failure.getCause(), output, depth + 1);
        for (Throwable suppressed : failure.getSuppressed()) collect(suppressed, output, depth + 1);
    }

    private static Map<String, Object> resource(String type, String logicalId, long count, Throwable cause) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("resourceType", type);
        if (logicalId != null) item.put("logicalId", logicalId);
        item.put("failedCloseCount", Math.max(1L, Math.min(1000L, count)));
        List<String> codes = new ArrayList<>();
        Long completion = null, reason = null;
        Throwable current = cause;
        for (int depth = 0; current != null && depth < MAX_CAUSES && codes.size() < MAX_CAUSES; depth++, current = current.getCause()) {
            String code = messageCode(current.getMessage());
            if (code != null && !codes.contains(code)) codes.add(code);
            if (current.getClass().getName().equals("com.ibm.mq.MQException")) {
                completion = numericMethod(current, "getCompCode");
                reason = numericMethod(current, "getReason");
            }
        }
        if (!codes.isEmpty()) item.put("causeCodes", codes);
        if (completion != null) item.put("mqCompletionCode", completion);
        if (reason != null) item.put("mqReasonCode", reason);
        return item;
    }

    private static String messageCode(String message) {
        if (message == null || message.length() > 4096) return null;
        Matcher matcher = CODE.matcher(message.strip());
        return matcher.find() ? matcher.group(1) : null;
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
