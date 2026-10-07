package att.exec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTreeTerminatorWindowsTest {
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void bestEffortTerminationStopsTheDirectWindowsProcess() throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/c", "ping -n 20 127.0.0.1 >NUL").start();
        try {
            assertTrue(process.isAlive());
        } finally {
            ProcessTreeTerminator.terminate(process);
        }
        assertFalse(process.isAlive(), "best-effort termination should stop the direct Windows process");
    }
}
