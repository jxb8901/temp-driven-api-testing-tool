package att.exec;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Best-effort termination of a Tool process and, where supported, its descendants. */
final class ProcessTreeTerminator {
    private ProcessTreeTerminator() { }

    static void terminate(Process process) {
        if (process == null || !process.isAlive()) return;
        if (terminateWithProcessHandle(process)) return;
        long pid = pid(process);
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (pid > 0 && os.contains("win")) {
            runQuietly("taskkill", "/PID", String.valueOf(pid), "/T");
            waitBriefly(process, 500L);
            if (process.isAlive()) runQuietly("taskkill", "/PID", String.valueOf(pid), "/T", "/F");
        } else if (pid > 0) {
            Set<Long> descendants = unixDescendants(pid);
            process.destroy();
            signal(descendants, "-TERM");
            waitBriefly(process, 500L);
            Set<Long> remaining = unixDescendants(pid);
            remaining.addAll(descendants);
            signal(remaining, "-KILL");
            if (process.isAlive()) process.destroyForcibly();
        } else {
            process.destroy();
            waitBriefly(process, 500L);
            if (process.isAlive()) process.destroyForcibly();
        }
        waitBriefly(process, 2000L);
    }

    /** Uses ProcessHandle reflectively when running on Java 9 or later. */
    private static boolean terminateWithProcessHandle(Process process) {
        try {
            Class<?> handleType = Class.forName("java.lang.ProcessHandle");
            Object root = Process.class.getMethod("toHandle").invoke(process);
            Stream<?> stream = (Stream<?>) handleType.getMethod("descendants").invoke(root);
            Object[] descendants;
            try { descendants = stream.toArray(); }
            finally { stream.close(); }
            Method destroy = handleType.getMethod("destroy");
            Method force = handleType.getMethod("destroyForcibly");
            process.destroy();
            for (Object descendant : descendants) destroy.invoke(descendant);
            waitBriefly(process, 500L);
            if (process.isAlive()) {
                for (Object descendant : descendants) force.invoke(descendant);
                process.destroyForcibly();
            } else {
                for (Object descendant : descendants) {
                    try {
                        if (Boolean.TRUE.equals(handleType.getMethod("isAlive").invoke(descendant))) force.invoke(descendant);
                    } catch (Exception ignored) { }
                }
            }
            return true;
        } catch (ClassNotFoundException unavailableOnJava8) {
            return false;
        } catch (Exception unavailableOrRestricted) {
            return false;
        }
    }

    static long pid(Process process) {
        try {
            Object value = Process.class.getMethod("pid").invoke(process);
            return ((Number) value).longValue();
        } catch (Exception unavailableOnJava8) {
            long pid = numericField(process, "pid");
            if (pid > 0L) return pid;
            return -1L;
        }
    }

    private static long numericField(Object value, String name) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                Object found = field.get(value);
                return found instanceof Number ? ((Number) found).longValue() : -1L;
            } catch (Exception ignored) { }
        }
        return -1L;
    }

    private static Set<Long> unixDescendants(long rootPid) {
        Set<Long> found = new HashSet<Long>();
        Map<Long,List<Long>> children = new HashMap<Long,List<Long>>();
        Process listing = null;
        try {
            listing = new ProcessBuilder("ps", "-axo", "pid=,ppid=").redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(listing.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.trim().split("\\s+");
                if (values.length != 2) continue;
                long child = Long.parseLong(values[0]), parent = Long.parseLong(values[1]);
                List<Long> siblings = children.get(parent);
                if (siblings == null) { siblings = new ArrayList<Long>(); children.put(parent, siblings); }
                siblings.add(child);
            }
            listing.waitFor(1L, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            if (listing != null) listing.destroyForcibly();
            return found;
        }
        collectChildren(rootPid, children, found);
        return found;
    }

    private static void collectChildren(long parent, Map<Long,List<Long>> children, Set<Long> found) {
        List<Long> direct = children.get(parent);
        if (direct == null) return;
        for (Long child : direct) if (found.add(child)) collectChildren(child, children, found);
    }

    private static void signal(Set<Long> pids, String signal) {
        if (pids.isEmpty()) return;
        List<Long> ordered = new ArrayList<Long>(pids);
        Collections.sort(ordered);
        List<String> command = new ArrayList<String>();
        command.add("kill"); command.add(signal);
        for (Long pid : ordered) command.add(String.valueOf(pid));
        runQuietly(command.toArray(new String[command.size()]));
    }

    private static void runQuietly(String... command) {
        Process helper = null;
        try {
            helper = new ProcessBuilder(command).redirectErrorStream(true).start();
            helper.getInputStream().close();
            if (!helper.waitFor(2L, TimeUnit.SECONDS)) helper.destroyForcibly();
        } catch (Exception ignored) {
            if (helper != null) helper.destroyForcibly();
        }
    }

    private static void waitBriefly(Process process, long millis) {
        try { process.waitFor(millis, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
