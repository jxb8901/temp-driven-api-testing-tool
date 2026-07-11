/* Author: Jeffrey + ChatGPT */
package att.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V2ConfigTest {
    @TempDir Path tempDir;

    @Test
    void parsesQuotedSheetsAndYamlDataColumns() {
        List<SheetGroupConfig> sheets = ColumnSpecParser.sheets("payment=\"支付,本地=案例\", batch=批量案例");
        assertEquals("支付,本地=案例", sheets.get(0).sheetName());
        List<DataColumnConfig> columns = ColumnSpecParser.dataColumns("amount=金額, payload=\"請求(yaml)\"(yaml)");
        assertEquals("請求(yaml)", columns.get(1).columnName());
        assertEquals(true, columns.get(1).yaml());
    }

    @Test
    void acceptsDelimitOnlyOnFinalArgumentAndRejectsArgv() throws Exception {
        Path valid = tempDir.resolve("valid.yaml");
        Files.write(valid, ("tools:\n  grep:\n    name: Grep\n    description: Search log text\n    command: grep ${TOOL.input.keywords}\n    output: txt\n    arguments:\n      file:\n        name: File\n        description: Input file\n        required: true\n      keywords:\n        name: Keywords\n        description: Search values\n        required: true\n        delimit: ','\n").getBytes("UTF-8"));
        FrameworkConfig config = new FrameworkConfigLoader().load(valid);
        assertEquals(",", config.tool("grep").arguments().get("keywords").delimit());

        Path invalid = tempDir.resolve("invalid.yaml");
        Files.write(invalid, ("tools:\n  old:\n    command: echo\n    argv: [x]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalid));
    }

    @Test
    void usesBuiltInStageRunWhenAndFailureDefaults() throws Exception {
        Path workbook = tempDir.resolve("payment.xlsx");
        Files.write(tempDir.resolve("payment.yaml"), ("excel:\n  sheet: cases\n  caseId: ID\n  tags: Tags\n"
                + "stages:\n  - key: invoke\n    template: Template\n").getBytes("UTF-8"));
        FrameworkConfig global = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), null, null, null);
        StageConfig stage = new SuiteConfigResolver(tempDir, global).resolve(workbook).stages().get(0);
        assertEquals("normal", stage.runWhen());
        assertEquals("stop", stage.onFailure());
    }

    @Test
    void rejectsInvalidEnumeratedAndBooleanConfigurationValues() throws Exception {
        Path invalidTool = tempDir.resolve("invalid-tool.yaml");
        Files.write(invalidTool, ("tools:\n  sample:\n    command: echo ok\n    output: json\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalidTool));

        Path missingToolMetadata = tempDir.resolve("missing-tool-metadata.yaml");
        Files.write(missingToolMetadata, ("tools:\n  sample:\n    command: echo ok\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(missingToolMetadata));

        Path workbook = tempDir.resolve("invalid-stage.xlsx");
        Files.write(tempDir.resolve("invalid-stage.yaml"), ("excel:\n  sheet: cases\n  caseId: ID\n  tags: Tags\n"
                + "stages:\n  - key: invoke\n    template: Template\n    required: maybe\n").getBytes("UTF-8"));
        FrameworkConfig global = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> new SuiteConfigResolver(tempDir, global).resolve(workbook));
    }
}
