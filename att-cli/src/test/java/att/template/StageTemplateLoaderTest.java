/* Author: Jeffrey + ChatGPT */
package att.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Map;
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
        Files.write(current.resolve("template.yaml"), ("schemaVersion: att-template/v3.4\n" +
                "name: current\ndescription: Typed output template\nactions:\n" +
                "  render:\n    type: render\n    payload: request.json\n").getBytes("UTF-8"));
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
        assertEquals("", render.resultConfig().format());
        att.validation.DiagnosticException legacyError = assertThrows(att.validation.DiagnosticException.class, () -> loader.load("legacy"));
        assertTrue(legacyError.getMessage().contains("renderAs"));
        assertTrue(legacyError.suggestion().contains("String"));
        assertTrue(att.validation.DiagnosticRenderer.exception(legacyError.toDiagnostic()).contains("actions.render.renderAs"));
        assertTrue(att.validation.DiagnosticRenderer.jsonError(legacyError.toDiagnostic()).contains("renderAs"));
        att.validation.DiagnosticException saveError = assertThrows(att.validation.DiagnosticException.class, () -> loader.load("legacy-save"));
        assertTrue(saveError.field().endsWith("saveAs"));
        assertTrue(saveError.suggestion().contains("removed"));
        att.validation.DiagnosticException missingFormat = assertThrows(att.validation.DiagnosticException.class,
                () -> loader.load("legacy-save-no-format"));
        assertTrue(missingFormat.suggestion().contains("removed"));
        att.validation.DiagnosticException fileError = assertThrows(att.validation.DiagnosticException.class, () -> loader.load("legacy-file"));
        assertTrue(fileError.suggestion().contains("String"));
        assertTrue(att.validation.DiagnosticRenderer.jsonError(fileError.toDiagnostic()).contains("renderAs"));
    }

    @Test void resolvesChineseSymbolicNameAndFullPath() throws Exception {
        StageTemplateLoader.clearForTests();
        Path dir=tempDir.resolve("templates/付款/本地"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), "schemaVersion: att-template/v3.4\nname: 中文模板\ndescription: test\nactions:\n  note: {type: log, message: ok}\n".getBytes("UTF-8"));
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
        Files.write(defaults.resolve("template.yaml"), ("schemaVersion: att-template/v3.4\nname: defaults\ndescription: test\n"
                + "actionDefaults: {onFailure: stop}\n"
                + "actions:\n  note: {type: log, message: ok}\n").getBytes("UTF-8"));
        StageTemplateLoader loader = new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertThrows(IllegalArgumentException.class, () -> loader.load("defaults"));

        Path invalid = tempDir.resolve("templates/invalid");
        Files.createDirectories(invalid);
        Files.write(invalid.resolve("template.yaml"), ("schemaVersion: att-template/v3.4\nname: invalid\ndescription: test\nactions:\n"
                + "  note: {type: log, message: ok, onFailure: ignore}\n").getBytes("UTF-8"));
        StageTemplateLoader invalidLoader = new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertThrows(IllegalArgumentException.class, () -> invalidLoader.load("invalid"));
    }

    @Test void actionFailureDefaultsToStop() {
        assertEquals("stop", new TemplateAction("note", java.util.Collections.<String, Object>singletonMap("type", "log")).onFailure());
    }

    @Test void lowercaseXEntriesAreAbsentBeforeActionAndCollectorValidationOrDiscovery() throws Exception {
        Path directory = tempDir.resolve("templates/x-prefix");
        Files.createDirectories(directory);
        Files.write(directory.resolve("template.yaml"), ("schemaVersion: att-template/v3.6\nname: x-prefix\ndescription: extensions\n"
                + "actions:\n"
                + "  x-disabled.with.dot: {type: log, level: INVALID, expression: '&{missing.txt}'}\n"
                + "  call:\n    type: tool\n    call: \"#{upper('ok')}\"\n"
                + "    retry: {maxAttempts: 2, intervalMs: 0, retryOn: [TIMEOUT], x-disabled: invalid}\n"
                + "    evidence: {x-collector.with.dot: not-a-collector}\n"
                + "    x-retry: {notes: [temporarily, disabled]}\n"
                + "    x-evidence: [incomplete, disabled, block]\n"
                + "    x-expression: '&{also-missing.txt}'\n"
                + "  data: {type: log, value: {x-correlation-id: preserved}, format: json, runWhen: '#{false}'}\n")
                .getBytes("UTF-8"));

        StageTemplate template = new StageTemplateLoader(tempDir, Paths.get("templates")).load("x-prefix");
        assertEquals(Arrays.asList("call", "data"), Arrays.asList(template.actions().get(0).id(), template.actions().get(1).id()));
        assertTrue(template.actions().get(0).evidence().isEmpty());
        assertFalse(template.actions().get(0).retry().containsKey("x-disabled"));
        assertFalse(template.actions().get(0).raw().containsKey("x-retry"));
        assertFalse(template.actions().get(0).raw().containsKey("x-evidence"));
        assertFalse(template.actions().get(0).raw().containsKey("x-expression"));
        assertEquals("#{false}", template.actions().get(1).runWhen());
        assertEquals("preserved", ((Map<?, ?>) template.actions().get(1).value()).get("x-correlation-id"));
        assertEquals(0, new FileExpressionResolver(tempDir).snapshotFor(template, null).size());
    }

    @Test void disabledKeysDoNotReplaceRequiredFieldsAndPrefixIsCaseSensitive() throws Exception {
        Path missing = tempDir.resolve("templates/x-required");
        Path uppercase = tempDir.resolve("templates/uppercase-key");
        Files.createDirectories(missing);
        Files.createDirectories(uppercase);
        Files.write(missing.resolve("template.yaml"), ("schemaVersion: att-template/v3.6\nname: missing\ndescription: missing required action type\n"
                + "actions: {call: {x-type: log, x-message: hidden}}\n").getBytes("UTF-8"));
        Files.write(uppercase.resolve("template.yaml"), ("schemaVersion: att-template/v3.6\nname: uppercase\ndescription: active uppercase key\n"
                + "actions: {X-disabled: {}}\n").getBytes("UTF-8"));
        StageTemplateLoader loader = new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertThrows(Exception.class, () -> loader.load("x-required"));
        assertThrows(Exception.class, () -> loader.load("uppercase-key"));
    }

    @Test void acceptsTimeoutOnlyForToolActionAtLoadBoundary() throws Exception {
        Path dir=tempDir.resolve("templates/timeout"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), "schemaVersion: att-template/v3.4\nname: timeout\ndescription: test\nactions:\n  call: {type: tool, call: '#{send()}', timeoutMs: 1234}\n".getBytes("UTF-8"));
        TemplateAction action = new StageTemplateLoader(tempDir, Paths.get("templates")).load("timeout").actions().get(0);
        assertEquals(Long.valueOf(1234), action.timeoutMs());
    }

    @Test void loadsAssignNameAndExpression() throws Exception {
        Path dir=tempDir.resolve("templates/assign"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), ("schemaVersion: att-template/v3.4\nname: assign\ndescription: test\nactions:\n"
                + "  build: {type: assign, name: txnSeq, expression: \"ATT#{sysdate('yyyyMMdd')}\"}\n").getBytes("UTF-8"));
        TemplateAction action = new StageTemplateLoader(tempDir, Paths.get("templates")).load("assign").actions().get(0);
        assertEquals("txnSeq", action.name());
        assertEquals("ATT#{sysdate('yyyyMMdd')}", action.expression());
    }

    @Test void rejectsRemovedFileOnlyLogAction() throws Exception {
        Path dir=tempDir.resolve("templates/file-log"); Files.createDirectories(dir);
        Files.write(dir.resolve("template.yaml"), ("schemaVersion: att-template/v3.4\nname: file-log\ndescription: test\nactions:\n"
                + "  response: {type: log, file: '${ACTIONS.call.output.targetFiles[0]}'}\n").getBytes("UTF-8"));
        assertThrows(Exception.class, () -> new StageTemplateLoader(tempDir, Paths.get("templates")).load("file-log"));
    }

    @Test void rejectsDynamicV3FlowUseAndRemovedWithAtLoadBoundary() throws Exception {
        StageTemplateLoader.clearForTests();
        Path dynamic = tempDir.resolve("templates/dynamic");
        Path array = tempDir.resolve("templates/array");
        Files.createDirectories(dynamic);
        Files.createDirectories(array);
        Files.write(dynamic.resolve("template.yaml"), ("schemaVersion: att-template/v3.4\nname: dynamic\ndescription: test\nactions:\n"
                + "  call: {type: flow, use: '${CASE.flow}'}\n").getBytes("UTF-8"));
        Files.write(array.resolve("template.yaml"), ("schemaVersion: att-template/v3.4\nname: array\ndescription: test\nactions:\n"
                + "  call: {type: flow, use: common.copy.v1, with: [one]}\n").getBytes("UTF-8"));
        StageTemplateLoader loader = new StageTemplateLoader(tempDir, Paths.get("templates"));

        IllegalArgumentException invalidUse = assertThrows(IllegalArgumentException.class, () -> loader.load("dynamic"));
        assertTrue(invalidUse.getMessage().contains("actions.call.use") || invalidUse.getMessage().contains("canonical"), invalidUse.getMessage());
        IllegalArgumentException invalidWith = assertThrows(IllegalArgumentException.class, () -> loader.load("array"));
        assertTrue(invalidWith.getMessage().contains("Unknown field") || invalidWith.getMessage().contains("with"), invalidWith.getMessage());
    }

    @Test void currentV36RetiresRenderWhileHistoricalV35AndV34RemainLoadable() throws Exception {
        Path current = tempDir.resolve("templates/current-v36");
        Path historicalV35 = tempDir.resolve("templates/historical-v35");
        Path historical = tempDir.resolve("templates/historical-v34");
        Files.createDirectories(current);
        Files.createDirectories(historicalV35);
        Files.createDirectories(historical);
        Files.write(current.resolve("template.yaml"), ("schemaVersion: att-template/v3.6\nname: current\ndescription: current\nactions:\n"
                + "  request: {type: render, payload: request.txt}\n").getBytes("UTF-8"));
        Files.write(historicalV35.resolve("template.yaml"), ("schemaVersion: att-template/v3.5\nname: historical-v35\ndescription: historical\nactions:\n"
                + "  request: {type: db, db: orders, query: {sql: 'select 1'}}\n").getBytes("UTF-8"));
        Files.write(historical.resolve("template.yaml"), ("schemaVersion: att-template/v3.4\nname: historical-v34\ndescription: historical\nactions:\n"
                + "  request: {type: render, payload: request.txt}\n").getBytes("UTF-8"));
        Files.write(historical.resolve("request.txt"), "historical".getBytes("UTF-8"));

        StageTemplateLoader loader = new StageTemplateLoader(tempDir, Paths.get("templates"));
        assertThrows(Exception.class, () -> loader.load("current-v36"));
        assertEquals("db", loader.load("historical-v35").actions().get(0).type());
        assertEquals("render", loader.load("historical-v34").actions().get(0).type());
    }

    @Test void historicalTemplateAndFlowLogLevelsRemainLoadableButCurrentLevelsReject() throws Exception {
        for (String version : new String[]{"3.4", "3.5"}) {
            String directory = "legacy-" + version;
            Path template = tempDir.resolve("templates/" + directory);
            Path flow = tempDir.resolve("templates/flows/" + directory);
            Files.createDirectories(template); Files.createDirectories(flow);
            Files.write(template.resolve("template.yaml"), ("schemaVersion: att-template/v" + version
                    + "\nname: Legacy-" + version + "\ndescription: Legacy Log\nactions:\n"
                    + "  note: {type: log, level: WARN, message: historical}\n").getBytes("UTF-8"));
            Files.write(flow.resolve("flow.yaml"), ("schemaVersion: att-flow/v" + version
                    + "\nid: common.legacy" + version.replace(".", "") + ".v1\nname: Legacy\ndescription: Legacy Log\nactions:\n"
                    + "  note: {type: log, level: DEBUG, message: historical}\n").getBytes("UTF-8"));
            TemplateAction action = new StageTemplateLoader(tempDir, Paths.get("templates")).load(directory).actions().get(0);
            assertEquals("WARN", action.raw().get("level")); assertEquals("historical", action.message());
        }
        att.flow.FlowRegistry registry = new att.flow.FlowRegistry(tempDir, tempDir.resolve("templates"));
        for (String version : new String[]{"34", "35"})
            assertEquals("DEBUG", registry.get("common.legacy" + version + ".v1").actions().get(0).raw().get("level"));
        Path current = tempDir.resolve("templates/current-level");
        Files.createDirectories(current);
        Files.write(current.resolve("template.yaml"), ("schemaVersion: att-template/v3.6\nname: Current\ndescription: Current Log\nactions:\n"
                + "  note: {type: log, level: WARN, message: current}\n").getBytes("UTF-8"));
        assertTrue(assertThrows(att.validation.DiagnosticException.class,
                () -> new StageTemplateLoader(tempDir, Paths.get("templates")).load("current-level"))
                .detail().contains("Log.level was removed"));
        Path currentFlow = tempDir.resolve("templates/flows/current-level");
        Files.createDirectories(currentFlow);
        Files.write(currentFlow.resolve("flow.yaml"), ("schemaVersion: att-flow/v3.6\nid: common.current.v1\nname: Current\ndescription: Current Log\nactions:\n"
                + "  note: {type: log, level: WARN, message: current}\n").getBytes("UTF-8"));
        assertThrows(Exception.class, () -> new att.flow.FlowRegistry(tempDir, tempDir.resolve("templates")));
    }
}
