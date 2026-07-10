/* Author: Jeffrey + ChatGPT */
package com.company.apitest.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
        Files.write(valid, ("tools:\n  grep:\n    command: grep ${TOOL.input.keywords}\n    output: txt\n    arguments:\n      file:\n        name: File\n        description: Input file\n        required: true\n      keywords:\n        name: Keywords\n        description: Search values\n        required: true\n        delimit: ','\n").getBytes("UTF-8"));
        FrameworkConfig config = new FrameworkConfigLoader().load(valid);
        assertEquals(",", config.tool("grep").arguments().get("keywords").delimit());

        Path invalid = tempDir.resolve("invalid.yaml");
        Files.write(invalid, ("tools:\n  old:\n    command: echo\n    argv: [x]\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class, () -> new FrameworkConfigLoader().load(invalid));
    }
}
