/* Author: Jeffrey + ChatGPT */
package att.snapshot;

import att.config.DataColumnConfig;
import att.config.FrameworkConfig;
import att.config.ReportConfig;
import att.config.RunConfig;
import att.config.SheetGroupConfig;
import att.config.StageConfig;
import att.core.StageCaseData;
import att.core.TestCase;
import att.validation.DiagnosticException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestcaseSnapshotServiceTest {
    @TempDir Path tempDir;
    private final TestcaseSnapshotService service = new TestcaseSnapshotService();

    @Test void canonicalXmlRoundTripsChineseMultilineAndNestedYamlValues() throws Exception {
        Map<String, Object> snapshot = service.build(config(), Collections.singletonList(testCase("原始\r\n<&內容]]>末尾")));
        String xml = service.serialize(snapshot);
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testcases schemaVersion=\"att-testcases/v2.4\""));
        assertTrue(xml.contains("<field name=\"note\" type=\"string\"><![CDATA[原始\n<&內容]]]]><![CDATA[>末尾]]></field>"), xml);
        assertTrue(xml.contains("<entry name=\"count\" type=\"integer\">2</entry>"));
        assertTrue(xml.contains("<entry name=\"enabled\" type=\"boolean\">true</entry>"));
        assertEquals(snapshot, service.parse(xml));
        assertEquals(xml, service.serialize(service.parse(xml)));
        assertFalse(xml.contains("rowNumber"));
        assertFalse(xml.contains("workbook.xlsx"));
    }

    @Test void usesCdataOnlyWhenStringContentNeedsIt() throws Exception {
        Map<String, Object> snapshot = service.build(config(), Collections.singletonList(testCase("plain")));
        String plain = service.serialize(snapshot);
        assertTrue(plain.contains("<field name=\"note\" type=\"string\">plain</field>"));
        assertFalse(plain.contains("<![CDATA[plain]]>"));

        String special = service.serialize(service.build(config(), Collections.singletonList(testCase("A & B < C > D"))));
        assertTrue(special.contains("<![CDATA[A & B < C > D]]>"));
        assertEquals(special, service.serialize(service.parse(special)));

        String trailingWhitespace = service.serialize(service.build(config(), Collections.singletonList(testCase("line with space \nnext"))));
        assertTrue(trailingWhitespace.contains("<![CDATA[line with space]]>&#32;\n<![CDATA[next]]>"), trailingWhitespace);
        assertEquals(trailingWhitespace, service.serialize(service.parse(trailingWhitespace)));
    }

    @Test void verifiesCanonicalSnapshotAndReportsFieldLevelStaleness() throws Exception {
        Path workbook = tempDir.resolve("中文案例.xlsx"); Files.write(workbook, new byte[]{1});
        service.write(workbook, config(), Collections.singletonList(testCase("before")));
        assertEquals(tempDir.resolve("中文案例.xml"), service.snapshotPath(workbook));
        service.verify(workbook, config(), Collections.singletonList(testCase("before")));
        DiagnosticException error = assertThrows(DiagnosticException.class,
                () -> service.verify(workbook, config(), Collections.singletonList(testCase("after"))));
        assertEquals("ATT-TC-001", error.code());
        assertTrue(error.getMessage().contains("payment.TC001.data.note changed"), error.getMessage());
        assertTrue(error.suggestion().contains("snapshot --suite 中文案例.xlsx"));
    }

    @Test void rejectsMissingNonCanonicalMalformedAndDoctypeSnapshots() throws Exception {
        Path workbook = tempDir.resolve("payment.xlsx"); Files.write(workbook, new byte[]{1});
        assertTrue(assertThrows(DiagnosticException.class, () -> service.verify(workbook, config(), Collections.singletonList(testCase("x")))).getMessage().contains("missing"));
        Path snapshot = service.write(workbook, config(), Collections.singletonList(testCase("x")));
        String canonical = new String(Files.readAllBytes(snapshot), StandardCharsets.UTF_8);
        Files.write(snapshot, canonical.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(assertThrows(DiagnosticException.class, () -> service.verify(workbook, config(), Collections.singletonList(testCase("x")))).getMessage().contains("not canonical"));
        Files.write(snapshot, "<testcases>".getBytes(StandardCharsets.UTF_8));
        assertTrue(assertThrows(DiagnosticException.class, () -> service.verify(workbook, config(), Collections.singletonList(testCase("x")))).getMessage().contains("invalid"));
        Files.write(snapshot, "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><testcases schemaVersion=\"att-testcases/v2.4\" workbookId=\"x\"/>".getBytes(StandardCharsets.UTF_8));
        assertTrue(assertThrows(DiagnosticException.class, () -> service.verify(workbook, config(), Collections.singletonList(testCase("x")))).getMessage().contains("invalid"));
    }

    @Test void failedGenerationDoesNotReplaceExistingSnapshot() throws Exception {
        Path workbook = tempDir.resolve("payment.xlsx"); Files.write(workbook, new byte[]{1});
        Path snapshot = tempDir.resolve("payment.xml"); Files.write(snapshot, "keep".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> invalidData = new LinkedHashMap<String, Object>(); invalidData.put("caseName", new Object()); invalidData.put("note", "x");
        TestCase invalid = new TestCase(2, "payments", "payment", "付款", "TC001", Collections.<String>emptyList(), invalidData, Collections.<String, StageCaseData>emptyMap(), null);
        assertThrows(IllegalArgumentException.class, () -> service.write(workbook, config(), Collections.singletonList(invalid)));
        assertEquals("keep", new String(Files.readAllBytes(snapshot), StandardCharsets.UTF_8));
    }

    private FrameworkConfig config() {
        List<DataColumnConfig> data = Arrays.asList(new DataColumnConfig("caseName", "案例名稱", false), new DataColumnConfig("note", "備註", false));
        List<StageConfig> stages = Collections.singletonList(new StageConfig("invoke", "執行模板", Collections.<DataColumnConfig>emptyList(), true, "stop", "normal"));
        return new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.emptyMap(), new ReportConfig("append-to-copy", "${suiteName}.result.xlsx", new LinkedHashMap<String, String>()),
                new RunConfig("timestamp", "yyyyMMdd-HHmmss"), Collections.singletonList(new SheetGroupConfig("payment", "付款")),
                "案例編號", "標籤", data, stages, 1, "ignore", "payments");
    }

    private TestCase testCase(String note) {
        Map<String, Object> data = new LinkedHashMap<String, Object>(); data.put("caseName", "中文案例"); data.put("note", note); data.put("workbook", "workbook.xlsx");
        Map<String, Object> params = new LinkedHashMap<String, Object>(); params.put("count", 2); params.put("enabled", true); params.put("items", Arrays.asList("A", "B"));
        Map<String, Object> stageValues = new LinkedHashMap<String, Object>(); stageValues.put("name", "PAYMENT_INVOKE"); stageValues.put("params", params);
        Map<String, StageCaseData> stages = new LinkedHashMap<String, StageCaseData>(); stages.put("invoke", new StageCaseData("invoke", "PAYMENT_INVOKE", stageValues));
        return new TestCase(2, "payments", "payment", "付款", "TC001", Arrays.asList("smoke", "中文"), data, stages, null);
    }
}
