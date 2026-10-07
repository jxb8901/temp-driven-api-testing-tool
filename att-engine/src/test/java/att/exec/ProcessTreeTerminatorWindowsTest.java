package att.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTreeTerminatorWindowsTest {
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void resolvesJava8WindowsProcessHandleAndTerminatesTheProcess() throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/c", "ping -n 20 127.0.0.1 >NUL").start();
        try {
            assertTrue(ProcessTreeTerminator.pid(process) > 0L,
                    "Java 8 Windows process handle must resolve to a PID for taskkill /T");
            ProcessTreeTerminator.attachWindowsJob(process);
            assertTrue(ProcessTreeTerminator.hasWindowsJob(process),
                    "Tool process must be attached to a kill-on-close Windows Job Object");
        } finally {
            ProcessTreeTerminator.terminate(process);
        }
        assertFalse(process.isAlive(), "taskkill should terminate the Windows process");
    }
}
