/* Author: Jeffrey + ChatGPT */
package att.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class StageTemplateLoaderTest {
    @TempDir Path tempDir;

    @org.junit.jupiter.api.BeforeEach void installSchemas() throws Exception { att.TestSchemas.install(tempDir); }

    @Test void loadsCurrentResultConfigAndRejectsLegacyFieldsWithMigrationGuidance() throws Exception {
        StageTemplateLoader.clearForTests();
        Path current = tempDir.resolve("templates/current");
        Path legacy = tempDir.resolve("templates/legacy");
        Path legacySave = tempDir.resolve("templates/legacy-save");
        Path legacySaveNoFormat = tempDir.resolve("templates/legacy-save-no-format");
        Path legacyFile = tempDir.resolve("templates/legacy-file");
        Files.createDirectories(current);
        Files.createDirectories(legacy);
        Files.createDirectories(legacySave);
        Files.createDirectories(legacySaveNoFormat);
        Files.createDirectories(legacyFile);
        Files.write(current.resolve("template.yaml"), ("schemaVersion: att-template/v3.3\n" +
                "name: current\ndescription: Typed output template\nactions:\n" +
                "  render:\n    type: render\n    payload: request.json\n    templateFormat: json\n").getBytes("UTF-8"));
        Files.write(legacy.resolve("template.yaml"), ("schemaVersion: att-template/v3.0\n" +
                "name: legacy\ndescription: Legacy template\nactions:\n" +
                "  render: {type: render, payload: request.json, renderAs: json}\n")
                .getBytes("UTF-8"));
        Files.write(legacySave.resolve("template.yaml"), ("schemaVersion: att-template/v3.0\n" +
                "name: legacy-save\ndescription: Legacy save template\nactions:\n" +
                "  call: {type: tool, call: '#{upper(\"ok\")}', saveAs: {path: response.json, format: json, overwrite: true}}\n")
                .getBytes("UTF-8"));
        Files.write(legacySaveNoFormat.resolve("template.yaml"), ("schemaVersion: att-template/v3.0\n" +
                "name: legacy-save-no-format\ndescription: Legacy save template\nactions:\n" +
                "  call: {type: tool, call: '#{upper(\"ok\")}', saveAs: response.json}\n")
                .getBytes("UTF-8"));
        Files.write(legacyFile.resolve("template.yaml"), ("schemaVersion: att-template/v3.0\n" +
                "name: legacy-file\ndescription: Legacy file template\nactions:\n" +
                "  render: {type: render, payload: request.xml, renderAs: file}\n")
                .getBytes("UTF-8"));

        StageTemplateLoader loader = new StageTemplateLoader(tempDir, Paths.get("templates"));
        TemplateAction render = loader.load("current").actions().get(0);
        assertEquals("render", render.type());
        assertEquals("json", render.templateFormat());
        assertNull(render.resultConfig().format());
        att.validation.DiagnosticException legacyError = assertThrows(att.validation.DiagnosticException.class, () -> loader.load("legacy"));
        assertTrue(legacyError.getMessage().contains("renderAs"));
        assertTrue(legacyError.suggestion().contains("DocumentValue"));
        assertTrue(att.validation.DiagnosticRenderer.exception(legacyError.toDiagnostic()).contains("actions.render.renderAs"));
        assertTrue(att.validation.DiagnosticRenderer.jsonError(legacyError.toDiagnostic()).contains("format: json"));
        att.validation.DiagnosticException saveError = assertThrows(att.validation.DiagnosticException.class, () -> loader.load("legacy-save"));
        assertTrue(saveError.field().endsWith("saveAs"));
        assertTrue(saveError.suggestion().contains("removed"));
        att.validation.DiagnosticException missingFormat = assertThrows(att.validation.DiagnosticException.class,
                () -> loader.load("legacy-save-no-format"));
        assertTrue(missingFormat.suggestion().contains("removed"));
        att.validation.DiagnosticException fileError = assertThrows(att.validation.DiagnosticException.class, () -> loader.load("legacy-file"));
        assertTrue(fileError.suggestion().contains("DocumentValue"));
        assertTrue(att.validation.DiagnosticRenderer.jsonError(fileError.toDiagnostic()).contains("templateFormat"));
    }

    @Test void resolvesChineseSymbolicNameAndFullPath() throws Exception {
        StageTemplateLoader.clearForTests();
        Path dir=tempDir.resolve("templates/付款/本地"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), "schemaVersion: att-template/v3.3\nname: 中文模板\ndescription: test\nactions:\n  note: {type: log, message: ok}\n".getBytes("UTF-8"));
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
        Files.write(defaults.resolve("template.yaml"), ("schemaVersion: att-template/v3.3\nname: defaults\ndescription: test\n"
                + "actionDefaults: {onFailure: stop}\n"
                + "actions:\n  note: {type: log, message: ok}\n").getBytes("UTF-8"));
        StageTemplateLoader loader = new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertThrows(IllegalArgumentException.class, () -> loader.load("defaults"));

        Path invalid = tempDir.resolve("templates/invalid");
        Files.createDirectories(invalid);
        Files.write(invalid.resolve("template.yaml"), ("schemaVersion: att-template/v3.3\nname: invalid\ndescription: test\nactions:\n"
                + "  note: {type: log, message: ok, onFailure: ignore}\n").getBytes("UTF-8"));
        StageTemplateLoader invalidLoader = new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertThrows(IllegalArgumentException.class, () -> invalidLoader.load("invalid"));
    }

    @Test void actionFailureDefaultsToStop() {
        assertEquals("stop", new TemplateAction("note", java.util.Collections.<String, Object>singletonMap("type", "log")).onFailure());
    }

    @Test void acceptsTimeoutOnlyForToolActionAtLoadBoundary() throws Exception {
        Path dir=tempDir.resolve("templates/timeout"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), "schemaVersion: att-template/v3.3\nname: timeout\ndescription: test\nactions:\n  call: {type: tool, call: '#{send()}', timeoutMs: 1234}\n".getBytes("UTF-8"));
        TemplateAction action = new StageTemplateLoader(tempDir, Paths.get("templates")).load("timeout").actions().get(0);
        assertEquals(Long.valueOf(1234), action.timeoutMs());
    }

    @Test void loadsAssignNameAndExpression() throws Exception {
        Path dir=tempDir.resolve("templates/assign"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), ("schemaVersion: att-template/v3.3\nname: assign\ndescription: test\nactions:\n"
                + "  build: {type: assign, name: txnSeq, expression: \"ATT#{sysdate('yyyyMMdd')}\"}\n").getBytes("UTF-8"));
        TemplateAction action = new StageTemplateLoader(tempDir, Paths.get("templates")).load("assign").actions().get(0);
        assertEquals("txnSeq", action.name());
        assertEquals("ATT#{sysdate('yyyyMMdd')}", action.expression());
    }

    @Test void rejectsRemovedFileOnlyLogAction() throws Exception {
        Path dir=tempDir.resolve("templates/file-log"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), ("schemaVersion: att-template/v3.3\nname: file-log\ndescription: test\nactions:\n"
                + "  response: {type: log, file: '${ACTIONS.call.output.targetFiles[0]}'}\n").getBytes("UTF-8"));
        assertThrows(Exception.class, () -> new StageTemplateLoader(tempDir, Paths.get("templates")).load("file-log"));
    }

    @Test void rejectsDynamicV3FlowUseAndRemovedWithAtLoadBoundary() throws Exception {
        StageTemplateLoader.clearForTests();
        Path dynamic = tempDir.resolve("templates/dynamic");
        Path array = tempDir.resolve("templates/array");
        Files.createDirectories(dynamic);
        Files.createDirectories(array);
        Files.write(dynamic.resolve("template.yaml"), ("schemaVersion: att-template/v3.3\nname: dynamic\ndescription: test\nactions:\n"
                + "  call: {type: flow, use: '${CASE.flow}'}\n").getBytes("UTF-8"));
        Files.write(array.resolve("template.yaml"), ("schemaVersion: att-template/v3.3\nname: array\ndescription: test\nactions:\n"
                + "  call: {type: flow, use: common.copy.v1, with: [one]}\n").getBytes("UTF-8"));
        StageTemplateLoader loader = new StageTemplateLoader(tempDir, Paths.get("templates"));

        IllegalArgumentException invalidUse = assertThrows(IllegalArgumentException.class, () -> loader.load("dynamic"));
        assertTrue(invalidUse.getMessage().contains("actions.call.use") || invalidUse.getMessage().contains("canonical"), invalidUse.getMessage());
        IllegalArgumentException invalidWith = assertThrows(IllegalArgumentException.class, () -> loader.load("array"));
        assertTrue(invalidWith.getMessage().contains("Unknown field") || invalidWith.getMessage().contains("with"), invalidWith.getMessage());
    }
}
