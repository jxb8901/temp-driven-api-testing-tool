package att.template;

import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

/** Engine-owned operation names for configured resource Helper namespaces. */
public final class HelperCallOperations {
    private static final Map<String, Set<String>> OPERATIONS;
    static {
        Map<String, Set<String>> values = new HashMap<String, Set<String>>();
        values.put("db", operations("query", "scalar", "update"));
        values.put("mq", operations("send", "receive", "request"));
        values.put("ssh", operations("execute", "upload", "stat", "mkdirs", "move", "delete"));
        values.put("http", operations("request", "get", "post", "put", "patch", "delete", "head", "options"));
        OPERATIONS = Collections.unmodifiableMap(values);
    }

    private HelperCallOperations() { }

    private static Set<String> operations(String... values) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }

    public static boolean supports(String namespace, String operation) {
        Set<String> operations = OPERATIONS.get(namespace);
        return operations != null && operations.contains(operation);
    }
}
