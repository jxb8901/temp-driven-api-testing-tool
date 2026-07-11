/* Author: Jeffrey + ChatGPT */
package att.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class StageTemplateLoaderTest {
    @TempDir Path tempDir;
    @Test void resolvesChineseSymbolicNameAndFullPath() throws Exception {
        Path dir=tempDir.resolve("templates/付款/本地"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), "schemaVersion: att-template/v2.1\nname: 中文模板\ndescription: test\nactions:\n  note: {type: log, message: ok}\n".getBytes("UTF-8"));
        StageTemplateLoader loader=new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertEquals("中文模板", loader.load("中文模板").name());
        assertEquals("中文模板", loader.load("付款/本地").name());
    }

    @Test void rejectsRemovedActionDefaultsAndInvalidActionFailureMode() throws Exception {
        Path defaults = tempDir.resolve("templates/defaults");
        Files.createDirectories(defaults);
        Files.write(defaults.resolve("template.yaml"), ("schemaVersion: att-template/v2.1\nname: defaults\ndescription: test\n"
                + "actionDefaults: {onFailure: stop}\n"
                + "actions:\n  note: {type: log, message: ok}\n").getBytes("UTF-8"));
        StageTemplateLoader loader = new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertThrows(IllegalArgumentException.class, () -> loader.load("defaults"));

        Path invalid = tempDir.resolve("templates/invalid");
        Files.createDirectories(invalid);
        Files.write(invalid.resolve("template.yaml"), ("schemaVersion: att-template/v2.1\nname: invalid\ndescription: test\nactions:\n"
                + "  note: {type: log, message: ok, onFailure: ignore}\n").getBytes("UTF-8"));
        StageTemplateLoader invalidLoader = new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertThrows(IllegalArgumentException.class, () -> invalidLoader.load("invalid"));
    }

    @Test void actionFailureDefaultsToStop() {
        assertEquals("stop", new TemplateAction("note", java.util.Collections.<String, Object>singletonMap("type", "log")).onFailure());
    }
}
