package att.exec;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Terminates a Tool process and descendants without requiring Java 9 at compile time. */
final class ProcessTreeTerminator {
    private static final Map<Process,Pointer> WINDOWS_JOBS = new ConcurrentHashMap<Process,Pointer>();
    private ProcessTreeTerminator() { }

    /** Assigns Windows Tool processes to a kill-on-close job owned by this JVM. */
    static void attachWindowsJob(Process process) {
        if (!isWindows() || process == null) return;
        Pointer job = null;
        try {
            long processHandle = numericField(process, "handle");
            if (processHandle <= 0L) return;
            NativeLibrary kernel32 = NativeLibrary.getInstance("kernel32");
            job = function(kernel32, "CreateJobObjectW").invokePointer(new Object[] { null, null });
            if (job == null || Pointer.nativeValue(job) == 0L) return;
            // Extended limits contain four SIZE_T values after the basic and I/O
            // counters. Keep the full native structure size for both bitnesses.
            Memory limits = new Memory(Native.POINTER_SIZE == 8 ? 144L : 112L);
            limits.clear();
            limits.setInt(16L, 0x2000); // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            int configured = function(kernel32, "SetInformationJobObject").invokeInt(
                    new Object[] { job, Integer.valueOf(9), limits, Integer.valueOf((int) limits.size()) });
            int assigned = configured == 0 ? 0 : function(kernel32, "AssignProcessToJobObject").invokeInt(
                    new Object[] { job, new Pointer(processHandle) });
            if (assigned != 0) {
                WINDOWS_JOBS.put(process, job);
                job = null;
            }
        } catch (RuntimeException unavailable) {
            // The normal process-tree fallback remains available if job assignment is unavailable.
        } catch (LinkageError unavailable) {
            // The normal process-tree fallback remains available if native access is unavailable.
        } finally {
            if (job != null) closeWindowsHandle(job);
        }
    }

    static void releaseWindowsJob(Process process) {
        Pointer job = WINDOWS_JOBS.remove(process);
        if (job != null) closeWindowsHandle(job);
    }

    static boolean hasWindowsJob(Process process) { return WINDOWS_JOBS.containsKey(process); }

    static void terminate(Process process) {
        if (process == null || !process.isAlive()) return;
        if (terminateWithProcessHandle(process)) {
            releaseWindowsJob(process);
            return;
        }
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
        releaseWindowsJob(process);
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
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
                return windowsPid(numericField(process, "handle"));
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

    /** Java 8 Windows exposes a native process handle instead of a PID. */
    private static long windowsPid(long handle) {
        if (handle <= 0L) return -1L;
        try {
            Function getProcessId = NativeLibrary.getInstance("kernel32").getFunction("GetProcessId");
            return getProcessId.invokeInt(new Object[] { new Pointer(handle) }) & 0xffffffffL;
        } catch (RuntimeException unavailable) {
            return -1L;
        } catch (LinkageError unavailable) {
            return -1L;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Function function(NativeLibrary library, String name) {
        return library.getFunction(name, Function.ALT_CONVENTION);
    }

    private static void closeWindowsHandle(Pointer handle) {
        try { function(NativeLibrary.getInstance("kernel32"), "CloseHandle").invokeInt(new Object[] { handle }); }
        catch (RuntimeException ignored) { }
        catch (LinkageError ignored) { }
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
