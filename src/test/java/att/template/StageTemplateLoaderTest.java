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
        Files.write(dir.resolve("template.yaml"), "schemaVersion: att-template/v2.3\nname: 中文模板\ndescription: test\nactions:\n  note: {type: log, message: ok}\n".getBytes("UTF-8"));
        StageTemplateLoader loader=new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertEquals("中文模板", loader.load("中文模板").name());
        assertEquals("中文模板", loader.load("付款/本地").name());
    }

    @Test void rejectsRemovedActionDefaultsAndInvalidActionFailureMode() throws Exception {
        Path defaults = tempDir.resolve("templates/defaults");
        Files.createDirectories(defaults);
        Files.write(defaults.resolve("template.yaml"), ("schemaVersion: att-template/v2.3\nname: defaults\ndescription: test\n"
                + "actionDefaults: {onFailure: stop}\n"
                + "actions:\n  note: {type: log, message: ok}\n").getBytes("UTF-8"));
        StageTemplateLoader loader = new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertThrows(IllegalArgumentException.class, () -> loader.load("defaults"));

        Path invalid = tempDir.resolve("templates/invalid");
        Files.createDirectories(invalid);
        Files.write(invalid.resolve("template.yaml"), ("schemaVersion: att-template/v2.3\nname: invalid\ndescription: test\nactions:\n"
                + "  note: {type: log, message: ok, onFailure: ignore}\n").getBytes("UTF-8"));
        StageTemplateLoader invalidLoader = new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertThrows(IllegalArgumentException.class, () -> invalidLoader.load("invalid"));
    }

    @Test void actionFailureDefaultsToStop() {
        assertEquals("stop", new TemplateAction("note", java.util.Collections.<String, Object>singletonMap("type", "log")).onFailure());
    }

    @Test void acceptsTimeoutOnlyForToolActionAtLoadBoundary() throws Exception {
        Path dir=tempDir.resolve("templates/timeout"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), "schemaVersion: att-template/v2.3\nname: timeout\ndescription: test\nactions:\n  call: {type: tool, call: '#{send()}', timeoutMs: 1234}\n".getBytes("UTF-8"));
        TemplateAction action = new StageTemplateLoader(tempDir, Paths.get("templates")).load("timeout").actions().get(0);
        assertEquals(Long.valueOf(1234), action.timeoutMs());
    }

    @Test void loadsAssignNameAndExpression() throws Exception {
        Path dir=tempDir.resolve("templates/assign"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), ("schemaVersion: att-template/v2.3\nname: assign\ndescription: test\nactions:\n"
                + "  build: {type: assign, name: txnSeq, expression: \"ATT#{sysdate('yyyyMMdd')}\"}\n").getBytes("UTF-8"));
        TemplateAction action = new StageTemplateLoader(tempDir, Paths.get("templates")).load("assign").actions().get(0);
        assertEquals("txnSeq", action.name());
        assertEquals("ATT#{sysdate('yyyyMMdd')}", action.expression());
    }
}
