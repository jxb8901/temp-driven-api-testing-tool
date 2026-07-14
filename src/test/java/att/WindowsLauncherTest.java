/* Author: Jeffrey + ChatGPT */
package att;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsLauncherTest {
    @Test void windowsLauncherSupportsPackagedAndSourceTreeModes() throws Exception {
        Path launcher = Paths.get("att.bat");
        assertTrue(Files.isRegularFile(launcher));
        String text = new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8);
        assertTrue(text.contains("cd /d \"%ROOT_DIR%\""));
        assertTrue(text.contains("lib\\att-*.jar"));
        assertTrue(text.contains("lib\\*\" att.FrameworkRunner %*"));
        assertTrue(text.contains("target\\classes"));
        assertTrue(text.contains(";%M2_REPO%"));
    }

    @Test void releaseBuildPackagesWindowsLauncher() throws Exception {
        String text = new String(Files.readAllBytes(Paths.get("build.sh")), StandardCharsets.UTF_8);
        assertTrue(text.contains("cp \"$ROOT_DIR/att.bat\" \"$PACKAGE_DIR/att.bat\""));
        assertTrue(text.contains("cp \"$ROOT_DIR/att.bat\" \"$SOURCE_PACKAGE_DIR/att.bat\""));
        assertTrue(text.contains("mainWindows: att.bat"));
    }
}
