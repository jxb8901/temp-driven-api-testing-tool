package att.load;

import java.util.regex.Pattern;

/** Shared path-segment safety for engine-managed Load execution identities. */
public final class LoadExecutionIdPattern {
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9_-]|\\.(?=[A-Za-z0-9_-])){0,239}$");
    private LoadExecutionIdPattern() { }
    public static String requireSafe(String value) {
        if (value == null || !ID.matcher(value).matches())
            throw new IllegalArgumentException("Load identity must be a safe path segment of at most 240 characters");
        return value;
    }
}
