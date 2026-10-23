package att.security;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One-shot Worker environment installed only after a Job is assigned. */
public final class ExecutionEnvironment {
    private static volatile Map<String,String> assigned;
    private ExecutionEnvironment() { }

    public static synchronized void assign(Map<String,String> environment) {
        if (assigned != null) throw new IllegalStateException("Execution environment is already assigned");
        if (environment == null || environment.size() > 4096) throw new IllegalArgumentException("Invalid execution environment");
        Map<String,String> copy = new LinkedHashMap<String,String>();
        long size = 0;
        for (Map.Entry<String,String> entry : environment.entrySet()) {
            String key=entry.getKey(), value=entry.getValue();
            if (key==null || value==null || key.isEmpty() || key.indexOf('=')>=0 || key.indexOf('\0')>=0 || value.indexOf('\0')>=0)
                throw new IllegalArgumentException("Invalid execution environment entry");
            size += key.length() + (long)value.length();
            if (size > 131072) throw new IllegalArgumentException("Execution environment exceeds its limit");
            copy.put(key,value);
        }
        assigned=Collections.unmodifiableMap(copy);
    }

    public static String get(String name) {
        Map<String,String> current=assigned;
        return current==null?System.getenv(name):current.get(name);
    }

    public static void apply(Map<String,String> target) {
        Map<String,String> current=assigned;
        if (current!=null) { target.clear(); target.putAll(current); }
    }
}
