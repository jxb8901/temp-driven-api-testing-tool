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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PackageDocumentationGeneratorTest {
    @TempDir Path tempDir;
    @org.junit.jupiter.api.BeforeEach void installSchemas() throws Exception { att.TestSchemas.install(tempDir); }
    @Test void generatesModernSinglePageAndUniqueUnicodeIds() throws Exception {
        Files.createDirectories(tempDir.resolve("testcase")); Files.createDirectories(tempDir.resolve("templates"));
        Files.createDirectories(tempDir.resolve("templates/flows/sample"));
        Files.write(tempDir.resolve("templates/flows/sample/flow.yaml"), (
                "schemaVersion: att-flow/v3.4\n" +
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
        assertTrue(html.contains("First-class reusable ordered Action sequences"));
        assertTrue(html.contains("For older packages, see Legacy documentation"));
        assertFalse(html.contains("Static reusable Template Action groups"));
        assertFalse(html.contains("Legacy Template invocation semantics"));
        assertTrue(html.contains("sample.echo.v1"));
        assertTrue(html.contains("Documented Flow"));
        assertTrue(html.contains("href=\"#dbhelpers\""));
        assertTrue(html.contains("data-tool="));
        assertTrue(html.contains("<strong>Index</strong>"));
        assertTrue(html.contains("Built-in functions"));
        assertTrue(html.contains("Global tools"));
        assertTrue(html.contains("Tool package: sample"));
        assertTrue(html.contains("str.lpad"));
        assertFalse(html.contains("file.move"));
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
        assertFalse(html.contains("file.exists(path)"));
        assertFalse(html.contains("file.copy(source, target[, overwrite])"));
        assertTrue(html.contains("misc.randomChoice(first, ...)"));
        assertFalse(html.contains("misc.dbText(value)"));
        assertTrue(html.contains("<h2>str.indexOf</h2>"));
        assertNotEquals(HtmlSupport.id("中文一"), HtmlSupport.id("中文二"));
    }

    @Test void groupsTestcasesBySheetAndShowsStaticExpectedResultWithoutSheetColumn() throws Exception {
        Files.createDirectories(tempDir.resolve("testcase"));
        Files.createDirectories(tempDir.resolve("templates/VERIFY"));
        Files.write(tempDir.resolve("templates/VERIFY/template.yaml"), (
                "schemaVersion: att-template/v3.6\n" +
                "name: VERIFY\n" +
                "description: Verify expected status\n" +
                "actions:\n" +
                "  buildReference:\n" +
                "    type: assign\n" +
                "    name: reference\n" +
                "    expression: \"${CASE.caseId}\"\n" +
                "  publishWithSet:\n" +
                "    type: set\n" +
                "    name: reference\n" +
                "    expression: \"#{echo(${EXEC.VARS.reference})}\"\n" +
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

        assertTrue(html.contains("<h3>suite.xlsx</h3>"));
        assertTrue(html.contains("<h4>Sheet: Sheet A</h4>"));
        assertTrue(html.contains("<h4>Sheet: Sheet B</h4>"));
        assertTrue(html.contains("<th>Stages → Templates</th><th>Expected Result</th>"));
        assertFalse(html.contains("<th>Sheet</th>"));
        assertTrue(html.contains("<td class=\"multiline\">Check workbook.first.TC1\nSUCCESS\n${CASE.VARS.reference}</td>"));
        assertTrue(html.contains("data-tool=\"echo\""));
        assertTrue(html.contains("data-sheet=\"Sheet A\""));
        assertTrue(html.indexOf("workbook.first.TC1") < html.indexOf("<h4>Sheet: Sheet B</h4>"));
    }

    @Test void indexesNativeDefinitionsFromDefaultPackageAndIgnoresOrphanWorkbooksWithoutTemplates() throws Exception {
        Path defaultPackage = Paths.get("").toAbsolutePath().normalize().resolve("packages/default");
        if (!Files.isDirectory(defaultPackage.resolve("testcase")))
            defaultPackage = Paths.get("").toAbsolutePath().normalize().getParent().resolve("packages/default");
        assumeTrue(Files.isDirectory(defaultPackage.resolve("testcase")), "Default Package fixture is present");
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), Collections.<String,ToolConfig>emptyMap(), null,
                new RunConfig("timestamp", "yyyyMMdd"));
        Path generated = new PackageDocumentationGenerator().generate(defaultPackage, config);
        String html = new String(Files.readAllBytes(generated), "UTF-8");
        assertTrue(html.contains("Native Case Definitions"));
        assertTrue(html.contains("quick.start.single"));
        assertTrue(html.contains("quick.start.inline"));
        assertTrue(html.contains("quick.start.excel"));
        assertTrue(html.contains("payments.offline.authorize"));
        assertTrue(html.contains("payments.offline.settle"));
        assertTrue(html.contains("href=\"#") && (html.contains("quick.start.v1") || html.contains("payments.offline")));
        assertFalse(html.contains("Workbook documentation requires a Package templates/ root"));

        Path nativeOnly = tempDir.resolve("native-only-docs");
        Files.createDirectories(nativeOnly.resolve("flows/demo"));
        Files.createDirectories(nativeOnly.resolve("testcase/demo"));
        Files.write(nativeOnly.resolve("flows/demo/flow.yaml"),
                ("schemaVersion: att-flow/v3.6\nid: demo.v1\nname: Demo\ndescription: Docs fixture.\n"
                        + "actions:\n  note: {type: log, message: hi}\n").getBytes("UTF-8"));
        Files.write(nativeOnly.resolve("testcase/demo/testcase.yaml"),
                ("schemaVersion: att-testcase/v1\nid: demo.case\nflow: demo.v1\nname: Demo\ninputs: {}\n")
                        .getBytes("UTF-8"));
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(nativeOnly.resolve("testcase/orphan.xlsx"))) {
            Sheet sheet = workbook.createSheet("Data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("ID");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("row-1");
            workbook.write(output);
        }
        String nativeHtml = new String(Files.readAllBytes(new PackageDocumentationGenerator().generate(nativeOnly, config)), "UTF-8");
        assertTrue(nativeHtml.contains("demo.case"));
        assertTrue(nativeHtml.contains("demo.v1"));
        assertFalse(nativeHtml.contains("orphan.xlsx"));
        assertFalse(nativeHtml.contains("href=\"#templates\""));
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
