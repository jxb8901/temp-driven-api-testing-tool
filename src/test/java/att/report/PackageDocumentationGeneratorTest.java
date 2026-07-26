/* Author: Jeffrey + ChatGPT */
package att.report;

import att.config.*;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PackageDocumentationGeneratorTest {
    @TempDir Path tempDir;
    @Test void generatesModernSinglePageAndUniqueUnicodeIds() throws Exception {
        Files.createDirectories(tempDir.resolve("testcase")); Files.createDirectories(tempDir.resolve("templates"));
        Files.createDirectories(tempDir.resolve("templates/flows/sample"));
        Files.write(tempDir.resolve("templates/flows/sample/flow.yaml"), (
                "schemaVersion: att-flow/v3.0\n" +
                "id: sample.echo.v1\nname: Sample Echo\ndescription: Documented Flow\n" +
                "actions:\n  copy: {type: assign, name: value, expression: '${CASE.caseId}'}\n").getBytes("UTF-8"));
        LinkedHashMap<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        LinkedHashMap<String,ToolArgumentConfig> echoArguments = new LinkedHashMap<String,ToolArgumentConfig>();
        echoArguments.put("value", new ToolArgumentConfig("value", "Value", "Optional values", false, ",", "--value", "once"));
        tools.put("echo", new ToolConfig("echo", "Echo", "Global echo", "echo ${value}", "txt", echoArguments));
        tools.put("sample.date", new ToolConfig("sample.date", "date", "sample", "Date", "Grouped date", Arrays.asList("date"), Arrays.asList("dispatch"), "txt", Collections.<String,ToolArgumentConfig>emptyMap(), null));
        tools.put("orders.count", new ToolConfig("orders.count", "count", "orders", "Count orders", "Typed count",
                Collections.<String>emptyList(), "#{db.orders.scalar(sql='select count(*)')}", "db",
                Collections.<String>emptyList(), "", Collections.<String,ToolArgumentConfig>emptyMap(), null, null));
        DbHelperConfig orders = new DbHelperConfig("orders", "Orders", "Order queries", "jdbc:test:orders",
                "", "", "", Collections.<String,String>emptyMap(), true, "driverDefault", 17,
                "case", "rollback", 50, 1024, 4096, "hash", "masked", null);
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), Paths.get("testcase"), tools, Collections.singletonMap("orders", orders),
                null, new RunConfig("timestamp", "yyyyMMdd"), null, "", "", null, null, 1,
                "ignore", "", false, ProcessOutputConfig.defaults());
        Path result = new PackageDocumentationGenerator().generate(tempDir, config);
        String html = new String(Files.readAllBytes(result), "UTF-8");
        assertTrue(result.endsWith("index.html"));
        assertTrue(html.contains("id=\"workbookFilter\""));
        assertTrue(html.contains("id=\"sheetFilter\""));
        assertTrue(html.contains("id=\"caseFilter\""));
        assertTrue(html.contains("id=\"toolFilter\""));
        assertTrue(html.contains("position:sticky;top:0"));
        assertTrue(html.contains("href=\"#testcases\""));
        assertTrue(html.contains("href=\"#flows\""));
        assertTrue(html.contains("sample.echo.v1"));
        assertTrue(html.contains("Documented Flow"));
        assertTrue(html.contains("href=\"#dbhelpers\""));
        assertTrue(html.contains("data-tool="));
        assertTrue(html.contains("<strong>Index</strong>"));
        assertTrue(html.contains("Built-in functions"));
        assertTrue(html.contains("Global tools"));
        assertTrue(html.contains("Tool package: sample"));
        assertTrue(html.contains("str.lpad"));
        assertTrue(html.contains("file.move"));
        assertTrue(html.contains("misc.nvl"));
        assertTrue(html.contains("sample.date"));
        assertTrue(html.contains("orders.count"));
        assertTrue(html.contains("call: <code>#{db.orders.scalar"));
        assertTrue(html.contains("cache.scope=db"));
        assertTrue(html.contains("db.orders"));
        assertTrue(html.contains("17 seconds"));
        assertFalse(html.contains("jdbc:test:orders"));
        assertTrue(html.contains("<th>argName</th>"));
        assertFalse(html.contains("<th>Delimiter</th>"));
        assertTrue(html.contains("<th>argNameMode</th>"));
        assertTrue(html.contains("<td>--value</td>"));
        assertTrue(html.contains("<td>once</td>"));
        assertTrue(html.contains("misc.nvl(value, defaultValue)"));
        assertTrue(html.contains("misc.iif(condition, trueValue, falseValue)"));
        assertTrue(html.contains("str.repeat(count, value)"));
        assertTrue(html.contains("str.substr(value, start[, length])"));
        assertTrue(html.contains("date.systimestamp([format])"));
        assertTrue(html.contains("date.add(value, amount, unit)"));
        assertTrue(html.contains("file.exists(path)"));
        assertTrue(html.contains("file.copy(source, target[, overwrite])"));
        assertTrue(html.contains("misc.randomChoice(first, ...)"));
        assertTrue(html.contains("misc.dbText(value)"));
        assertTrue(html.contains("<h2>str.indexOf</h2>"));
        assertNotEquals(HtmlSupport.id("中文一"), HtmlSupport.id("中文二"));
    }

    @Test void groupsTestcasesBySheetAndShowsStaticExpectedResultWithoutSheetColumn() throws Exception {
        Files.createDirectories(tempDir.resolve("testcase"));
        Files.createDirectories(tempDir.resolve("templates/VERIFY"));
        Files.write(tempDir.resolve("templates/VERIFY/template.yaml"), (
                "schemaVersion: att-template/v2.3\n" +
                "name: VERIFY\n" +
                "description: Verify expected status\n" +
                "actions:\n" +
                "  buildReference:\n" +
                "    type: assign\n" +
                "    name: reference\n" +
                "    expression: \"#{echo(${CASE.caseId})}\"\n" +
                "  check:\n" +
                "    type: assert\n" +
                "    description: Check ${CASE.caseId}\n" +
                "    assert: \"true\"\n" +
                "    expected: \"${CASE.expectedStatus}\\r\\n${CASE.VARS.reference}\"\n" +
                "    actual: \"${output.success}\"\n").getBytes("UTF-8"));
        Files.write(tempDir.resolve("testcase/suite.yaml"), (
                "schemaVersion: att-sidecar/v2.1\n" +
                "id: workbook\n" +
                "excel:\n" +
                "  sheet: first=Sheet A, second=Sheet B\n" +
                "  caseId: Case ID\n" +
                "  tags: Tags\n" +
                "  dataColumns: caseName=Case Name, expectedStatus=Expected Status\n" +
                "stages:\n" +
                "  - key: verify\n" +
                "    template: Verify Template\n" +
                "    required: true\n" +
                "    onFailure: stop\n" +
                "    runWhen: normal\n").getBytes("UTF-8"));
        writeWorkbook(tempDir.resolve("testcase/suite.xlsx"));
        LinkedHashMap<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("echo", new ToolConfig("echo","Echo","Echo","echo","txt",
                Collections.singletonMap("value",new ToolArgumentConfig("value","Value","Value",true,""))));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), tools, null, new RunConfig("timestamp", "yyyyMMdd"));

        String html = new String(Files.readAllBytes(new PackageDocumentationGenerator().generate(tempDir, config)), "UTF-8");

        assertTrue(html.contains("<h2>suite.xlsx</h2>"));
        assertTrue(html.contains("<h3>Sheet: Sheet A</h3>"));
        assertTrue(html.contains("<h3>Sheet: Sheet B</h3>"));
        assertTrue(html.contains("<th>Stages → Templates</th><th>Expected Result</th>"));
        assertFalse(html.contains("<th>Sheet</th>"));
        assertTrue(html.contains("<td class=\"multiline\">Check workbook.first.TC1\nSUCCESS\n${CASE.VARS.reference}</td>"));
        assertTrue(html.contains("data-tool=\"echo\""));
        assertTrue(html.contains("data-sheet=\"Sheet A\""));
        assertTrue(html.indexOf("workbook.first.TC1") < html.indexOf("<h3>Sheet: Sheet B</h3>"));
    }

    private void writeWorkbook(Path path) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            writeSheet(workbook.createSheet("Sheet A"), "TC1", "Case one", "SUCCESS");
            writeSheet(workbook.createSheet("Sheet B"), "TC2", "Case two", "REJECTED");
            try (OutputStream output = Files.newOutputStream(path)) { workbook.write(output); }
        }
    }

    private void writeSheet(Sheet sheet, String caseId, String name, String expected) {
        Row header = sheet.createRow(0);
        String[] names = {"Case ID", "Tags", "Case Name", "Expected Status", "Verify Template"};
        for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue(caseId);
        row.createCell(1).setCellValue("smoke");
        row.createCell(2).setCellValue(name);
        row.createCell(3).setCellValue(expected);
        row.createCell(4).setCellValue("VERIFY");
    }
}
