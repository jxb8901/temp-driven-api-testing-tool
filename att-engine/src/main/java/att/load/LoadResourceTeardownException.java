package att.load;

/** A resource owner could not confirm that its LOAD-scoped resources were closed. */
public final class LoadResourceTeardownException extends RuntimeException {
    private final String resourceType;
    private final String logicalId;
    private final long failedCloseCount;

    public LoadResourceTeardownException(String resourceType, String logicalId, long failedCloseCount, Throwable cause) {
        super("LOAD_RESOURCE_TEARDOWN_FAILED", cause);
        if (!"MQ".equals(resourceType) && !"DB".equals(resourceType) && !"HTTP".equals(resourceType)
                && !"SCHEDULER".equals(resourceType) && !"UNKNOWN".equals(resourceType)) {
            throw new IllegalArgumentException("Unsupported LOAD teardown resource type");
        }
        this.resourceType = resourceType;
        this.logicalId = safeLogicalId(logicalId);
        this.failedCloseCount = Math.max(1L, Math.min(1000L, failedCloseCount));
    }

    public String resourceType() { return resourceType; }
    public String logicalId() { return logicalId; }
    public long failedCloseCount() { return failedCloseCount; }

    private static String safeLogicalId(String value) {
        if (value == null || isBlank(value)) return null;
        String sanitized = value.replaceAll("[^A-Za-z0-9._:-]", "_");
        return sanitized.length() <= 64 ? sanitized : sanitized.substring(0, 64);
    }

    private static boolean isBlank(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
