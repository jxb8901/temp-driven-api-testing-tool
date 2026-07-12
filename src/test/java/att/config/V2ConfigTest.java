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
        Files.write(valid, ("schemaVersion: att-config/v2.1\ntools:\n  grep:\n    name: Grep\n    description: Search log text\n    command: grep ${TOOL.input.keywords}\n    output: txt\n    arguments:\n      file:\n        name: File\n        description: Input file\n        required: true\n      keywords:\n        name: Keywords\n        description: Search values\n        required: true\n        delimit: ','\n").getBytes("UTF-8"));
        FrameworkConfig config = new FrameworkConfigLoader().load(valid);
        assertEquals(",", config.tool("grep").arguments().get("keywords").delimit());

        Path invalid = tempDir.resolve("invalid.yaml");
        Files.write(invalid, ("schemaVersion: att-config/v2.1\ntools:\n  old:\n    command: echo\n    argv: [x]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalid));
    }

    @Test
    void usesBuiltInStageRunWhenAndFailureDefaults() throws Exception {
        Path workbook = tempDir.resolve("payment.xlsx");
        Files.write(tempDir.resolve("payment.yaml"), ("schemaVersion: att-sidecar/v2.1\nid: payment\nexcel:\n  sheet: cases\n  caseId: ID\n  tags: Tags\n"
                + "stages:\n  - key: invoke\n    template: Template\n").getBytes("UTF-8"));
        FrameworkConfig global = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), null, null, null);
        FrameworkConfig resolved = new SuiteConfigResolver(tempDir, global).resolve(workbook);
        StageConfig stage = resolved.stages().get(0);
        assertEquals("payment", resolved.workbookId());
        assertEquals("normal", stage.runWhen());
        assertEquals("stop", stage.onFailure());
        Files.write(tempDir.resolve("missing-id.yaml"), ("schemaVersion: att-sidecar/v2.1\nexcel:\n  sheet: cases\n  caseId: ID\n  tags: Tags\nstages:\n  - key: invoke\n    template: Template\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new SuiteConfigResolver(tempDir, global).resolve(tempDir.resolve("missing-id.xlsx")));
    }

    @Test
    void rejectsInvalidEnumeratedAndBooleanConfigurationValues() throws Exception {
        Path invalidTool = tempDir.resolve("invalid-tool.yaml");
        Files.write(invalidTool, ("schemaVersion: att-config/v2.1\ntools:\n  sample:\n    command: echo ok\n    output: json\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalidTool));

        Path missingToolMetadata = tempDir.resolve("missing-tool-metadata.yaml");
        Files.write(missingToolMetadata, ("schemaVersion: att-config/v2.1\ntools:\n  sample:\n    command: echo ok\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(missingToolMetadata));

        Path workbook = tempDir.resolve("invalid-stage.xlsx");
        Files.write(tempDir.resolve("invalid-stage.yaml"), ("schemaVersion: att-sidecar/v2.1\nid: invalid-stage\nexcel:\n  sheet: cases\n  caseId: ID\n  tags: Tags\n"
                + "stages:\n  - key: invoke\n    template: Template\n    required: maybe\n").getBytes("UTF-8"));
        FrameworkConfig global = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> new SuiteConfigResolver(tempDir, global).resolve(workbook));
    }
    @Test void rejectsUnknownFieldsAndIncorrectScalarTypes() throws Exception {
        Path unknown = tempDir.resolve("unknown.yaml");
        Files.write(unknown, "schemaVersion: att-config/v2.1\ntemplates: {root: templates, typo: true}\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(unknown));
        Path wrongType = tempDir.resolve("wrong-type.yaml");
        Files.write(wrongType, "schemaVersion: att-config/v2.1\ntimeoutMs: '120000'\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(wrongType));
        Path traversal = tempDir.resolve("traversal.yaml");
        Files.write(traversal, "schemaVersion: att-config/v2.1\noutputDirectory: ../outside\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(traversal));
    }
}
