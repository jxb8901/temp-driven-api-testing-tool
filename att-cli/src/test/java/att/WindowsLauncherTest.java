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

    @Test void releaseAssemblyPackagesWindowsLauncher() throws Exception {
        String binary = new String(Files.readAllBytes(Paths.get("att-dist/src/assembly/binary.xml")), StandardCharsets.UTF_8);
        String source = new String(Files.readAllBytes(Paths.get("att-dist/src/assembly/source.xml")), StandardCharsets.UTF_8);
        String manifest = new String(Files.readAllBytes(Paths.get("att-dist/src/release/binary/RELEASE_MANIFEST.txt")), StandardCharsets.UTF_8);
        assertTrue(binary.contains("<source>${project.basedir}/../att.bat</source>"));
        assertTrue(binary.contains("<destName>att.bat</destName>"));
        assertTrue(source.contains("<include>att.bat</include>"));
        assertTrue(manifest.contains("mainWindows: att.bat"));
    }
}
