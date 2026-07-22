/* Author: Jeffrey + ChatGPT */
package att.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class StageTemplateLoaderTest {
    @TempDir Path tempDir;

    @Test void loadsV25DbActionAndNormalizesLegacySaveAs() throws Exception {
        StageTemplateLoader.clearForTests();
        Path current = tempDir.resolve("templates/current");
        Path legacy = tempDir.resolve("templates/legacy");
        Files.createDirectories(current);
        Files.createDirectories(legacy);
        Files.write(current.resolve("template.yaml"), ("schemaVersion: att-template/v2.5\n" +
                "name: current\ndescription: DB template\nactions:\n" +
                "  query:\n    type: db\n    db: orders\n    query: {sql: 'select 1'}\n" +
                "    saveAs: {path: result.json, format: json, overwrite: true}\n").getBytes("UTF-8"));
        Files.write(legacy.resolve("template.yaml"), ("schemaVersion: att-template/v2.3\n" +
                "name: legacy\ndescription: Legacy template\nactions:\n" +
                "  call: {type: tool, call: '#{sample()}', saveAs: result.txt, overwrite: true}\n")
                .getBytes("UTF-8"));

        StageTemplateLoader loader = new StageTemplateLoader(tempDir, Paths.get("templates"));
        TemplateAction db = loader.load("current").actions().get(0);
        assertEquals("db", db.type());
        assertEquals("orders", db.db());
        assertEquals("json", db.saveConfig().format());
        assertTrue(db.saveConfig().overwrite());
        TemplateAction old = loader.load("legacy").actions().get(0);
        assertTrue(old.saveConfig().legacy());
        assertEquals("raw", old.saveConfig().format());
        assertTrue(old.saveConfig().overwrite());
    }

    @Test void resolvesChineseSymbolicNameAndFullPath() throws Exception {
        StageTemplateLoader.clearForTests();
        Path dir=tempDir.resolve("templates/付款/本地"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), "schemaVersion: att-template/v2.3\nname: 中文模板\ndescription: test\nactions:\n  note: {type: log, message: ok}\n".getBytes("UTF-8"));
        StageTemplateLoader loader=new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertEquals("中文模板", loader.load("中文模板").name());
        assertEquals("中文模板", loader.load("付款/本地").name());
        assertEquals(1, StageTemplateLoader.stats().loads());
        assertEquals(1, StageTemplateLoader.stats().hits());
    }

    @Test void payloadCacheReusesContentAndInvalidatesAfterChange() throws Exception {
        PayloadCache.clearForTests();
        Path payload = tempDir.resolve("payload.txt"); Files.write(payload, "one".getBytes("UTF-8"));
        assertEquals("one", PayloadCache.readUtf8(payload));
        assertEquals("one", PayloadCache.readUtf8(payload));
        assertEquals(1, PayloadCache.stats().loads()); assertEquals(1, PayloadCache.stats().hits());
        Thread.sleep(5L); Files.write(payload, "changed".getBytes("UTF-8"));
        assertEquals("changed", PayloadCache.readUtf8(payload));
        assertEquals(2, PayloadCache.stats().loads());
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

    @Test void loadsFileOnlyLogAction() throws Exception {
        Path dir=tempDir.resolve("templates/file-log"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), ("schemaVersion: att-template/v2.3\nname: file-log\ndescription: test\nactions:\n"
                + "  response: {type: log, file: '${ACTIONS.call.output.targetFiles[0]}'}\n").getBytes("UTF-8"));
        TemplateAction action = new StageTemplateLoader(tempDir, Paths.get("templates")).load("file-log").actions().get(0);
        assertEquals("${ACTIONS.call.output.targetFiles[0]}", action.file());
        assertEquals("", action.message());
    }
}
