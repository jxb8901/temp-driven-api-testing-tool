package att.load;

import java.util.regex.Pattern;

/** Shared path-segment safety for engine-managed Load execution identities. */
public final class LoadExecutionIdPattern {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9_-]|\\.(?=[A-Za-z0-9_-])){0,239}$");
    private LoadExecutionIdPattern() { }
    /** Preserve ordinary ASCII IDs; hash other valid Run IDs into a bounded ASCII segment. */
    public static String executionPrefix(String runId) {
        att.core.IdentifierValidator.runId(runId);
        if (ID.matcher(runId).matches()) return runId;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(runId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder("run-");
            for (byte value : digest) {
                encoded.append(Character.forDigit((value & 255) >>> 4, 16));
                encoded.append(Character.forDigit(value & 15, 16));
            }
            return encoded.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    public static String requireSafe(String value) {
        if (value == null || !ID.matcher(value).matches())
            throw new IllegalArgumentException("Load identity must be a safe path segment of at most 240 characters");
        return value;
    }
}
