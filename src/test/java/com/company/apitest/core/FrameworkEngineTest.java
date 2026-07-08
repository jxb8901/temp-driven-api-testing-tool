/*
 * Author: Jeffrey + ChatGPT
 */

package com.company.apitest.core;

import com.company.apitest.config.FrameworkConfig;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the full V1 happy path from Excel row to report output.
 */
class FrameworkEngineTest {
    @TempDir
    Path projectRoot;

    @Test
    void runsFullHappyPath() throws Exception {
        writeText(projectRoot.resolve("templates/request/PAYMENT_TRANSFER/template.xml"),
                "<Request><Case>${Case ID}</Case><Channel>${channel}</Channel><Hidden>${expectedLedgerStatus}</Hidden></Request>");
        writeText(projectRoot.resolve("templates/expected/PAYMENT_SUCCESS/template.yaml"),
                "xml:\n"
                        + "  - name: Status\n"
                        + "    xpath: \"/Response/Status\"\n"
                        + "    equals: \"${Expected Status}\"\n"
                        + "database:\n"
                        + "  - name: LedgerStatus\n"
                        + "    command: \"sh " + projectRoot.resolve("scripts/check_db.sh") + "\"\n"
                        + "    expected: \"${expectedLedgerStatus}\"\n"
                        + "log:\n"
                        + "  - name: ErrorLog\n"
                        + "    command: \"sh " + projectRoot.resolve("scripts/check_log.sh") + "\"\n"
                        + "    expected: \"NOT_FOUND\"\n");
        writeText(projectRoot.resolve("scripts/executor.sh"),
                "while [ \"$#\" -gt 0 ]; do\n"
                        + "  if [ \"$1\" = \"--response\" ]; then response=\"$2\"; shift 2; else shift; fi\n"
                        + "done\n"
                        + "mkdir -p \"$(dirname \"$response\")\"\n"
                        + "printf '<Response><Status>SUCCESS</Status></Response>' > \"$response\"\n");
        writeText(projectRoot.resolve("scripts/check_db.sh"), "echo POSTED\n");
        writeText(projectRoot.resolve("scripts/check_log.sh"), "echo NOT_FOUND\n");
        writeWorkbook(projectRoot.resolve("testcase/payment_regression.xlsx"));

        FrameworkConfig config = new FrameworkConfig(
                Paths.get("output/xml"),
                Paths.get("report"),
                Paths.get("logs"),
                "SIT",
                30,
                "sh " + projectRoot.resolve("scripts/executor.sh") + " --request ${requestXml} --response ${responseXml}"
        );
        RunSummary summary = new FrameworkEngine(projectRoot, config).run(new ExecutionOptions(
                Paths.get("config/config.yaml"),
                Paths.get("testcase/payment_regression.xlsx"),
                new HashSet<String>(),
                new HashSet<String>()
        ));

        assertEquals(1, summary.passed());
        assertTrue(Files.exists(summary.reportPath()));
        String requestXml = new String(Files.readAllBytes(projectRoot.resolve("output/xml/TC001/request.xml")), "UTF-8");
        assertTrue(requestXml.contains("<Channel>ATM</Channel>"));
        assertTrue(requestXml.contains("<Hidden></Hidden>"));
    }

    private void writeWorkbook(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (Workbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(path)) {
            Sheet sheet = workbook.createSheet("TestCases");
            String[] columns = {"Enable", "Case ID", "Case Name", "Tags", "API", "Request Template", "Expected Template",
                    "Expected Status", "Request Data", "Expected Data"};
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.length; i++) {
                header.createCell(i).setCellValue(columns[i]);
            }
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("Y");
            row.createCell(1).setCellValue("TC001");
            row.createCell(2).setCellValue("Payment success");
            row.createCell(3).setCellValue("smoke");
            row.createCell(4).setCellValue("PAYMENT");
            row.createCell(5).setCellValue("PAYMENT_TRANSFER");
            row.createCell(6).setCellValue("PAYMENT_SUCCESS");
            row.createCell(7).setCellValue("SUCCESS");
            row.createCell(8).setCellValue("channel: ATM");
            row.createCell(9).setCellValue("expectedLedgerStatus: POSTED");
            workbook.write(output);
        }
    }

    private void writeText(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, text.getBytes("UTF-8"));
    }
}
