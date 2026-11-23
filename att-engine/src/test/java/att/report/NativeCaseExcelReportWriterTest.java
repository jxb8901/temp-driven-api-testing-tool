package att.report;

import att.core.ResultStatus;
import att.core.TestResult;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeCaseExcelReportWriterTest {
    @TempDir Path temp;

    @Test void writesBoundedStandardWorkbookForEveryNativeCaseOutcomeWithoutAWorkbookInput() throws Exception {
        Path runDirectory = temp.resolve("run");
        Files.createDirectories(runDirectory);
        List<TestResult> results = Arrays.asList(
                result("def.pass", ResultStatus.PASS, "EXEC-PASS", "=1+1"),
                result("def.fail", ResultStatus.FAIL, "EXEC-FAIL", "assertion failed"),
                result("def.error", ResultStatus.ERROR, "EXEC-ERROR", "runtime error"),
                TestResult.notExecuted("def.skip", "Skipped", Collections.<String>emptyList(), "def",
                        "def.skip", "failFast", "flow.v1", "flow-revision-def.skip", "source-revision-def.skip",
                        provenance("inline", "testcase/def/testcase.yaml", "Cases", 4, "skip")));

        Path workbookPath = new NativeCaseExcelReportWriter().write(runDirectory, "RUN-NATIVE", results);
        assertEquals(runDirectory.resolve(NativeCaseExcelReportWriter.OUTPUT_PATH), workbookPath);
        assertTrue(Files.isRegularFile(workbookPath));
        try (java.io.InputStream input = Files.newInputStream(workbookPath);
             Workbook workbook = WorkbookFactory.create(input)) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheet("Cases");
            assertEquals("Qualified Case ID", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals(4, sheet.getLastRowNum());
            assertRow(sheet.getRow(1), "def.pass", "PASS", "EXEC-PASS", "inline");
            assertRow(sheet.getRow(2), "def.fail", "FAIL", "EXEC-FAIL", "inline");
            assertRow(sheet.getRow(3), "def.error", "ERROR", "EXEC-ERROR", "inline");
            assertRow(sheet.getRow(4), "def.skip", "SKIPPED", "", "inline");
            assertEquals("=1+1", sheet.getRow(1).getCell(17).getStringCellValue());
            assertEquals(CellType.STRING, sheet.getRow(1).getCell(17).getCellType(),
                    "Untrusted result text must remain a string cell, not an executable formula");
            assertTrue(sheet.getRow(1).getCell(19).getStringCellValue().contains("sourceReference"));
            assertEquals("flow-revision-def.pass", sheet.getRow(1).getCell(5).getStringCellValue());
            assertEquals("source-revision-def.pass", sheet.getRow(1).getCell(6).getStringCellValue());
            assertEquals(1, sheet.getRow(1).getCell(10).getNumericCellValue(), 0.0);
            assertEquals(1, sheet.getRow(1).getCell(12).getNumericCellValue(), 0.0);
            assertEquals("", sheet.getRow(4).getCell(13).getStringCellValue());
            assertEquals(0, sheet.getRow(4).getCell(15).getNumericCellValue(), 0.0);
        }
    }

    @Test void rejectsReportsAboveTheNativeSuiteSelectionBound() throws Exception {
        Path runDirectory = temp.resolve("too-many-cases");
        TestResult sample = result("def.pass", ResultStatus.PASS, "EXEC-PASS", "actual");
        assertThrows(java.io.IOException.class, () -> new NativeCaseExcelReportWriter().write(
                runDirectory, "RUN-NATIVE", Collections.nCopies(1001, sample)));
        assertTrue(Files.notExists(runDirectory.resolve(NativeCaseExcelReportWriter.OUTPUT_PATH)));
    }

    @Test void rejectsSymlinkedReportDirectoriesWithoutWritingOutsideTheRun() throws Exception {
        Path runDirectory = temp.resolve("symlinked-run");
        Path outside = temp.resolve("outside-workbooks");
        Files.createDirectories(runDirectory);
        Files.createDirectories(outside);
        try { Files.createSymbolicLink(runDirectory.resolve("workbooks"), outside); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) { return; }

        assertThrows(java.io.IOException.class, () -> new NativeCaseExcelReportWriter().write(
                runDirectory, "RUN-NATIVE", Collections.singletonList(result("def.pass", ResultStatus.PASS, "EXEC-PASS", "actual"))));
        try (Stream<Path> entries = Files.list(outside)) { assertFalse(entries.findAny().isPresent()); }
    }

    @Test void truncatesLongTextWithoutSplittingSurrogatePairs() throws Exception {
        Path runDirectory = temp.resolve("unicode-run");
        Files.createDirectories(runDirectory);
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < 32752; index++) value.append('x');
        value.append("\uD83D\uDE00").append("0123456789abcdef");
        Path report = new NativeCaseExcelReportWriter().write(runDirectory, "RUN-NATIVE",
                Collections.singletonList(result("def.pass", ResultStatus.PASS, "EXEC-PASS", value.toString())));

        try (java.io.InputStream input = Files.newInputStream(report);
             Workbook workbook = WorkbookFactory.create(input)) {
            String stored = workbook.getSheet("Cases").getRow(1).getCell(17).getStringCellValue();
            assertTrue(stored.endsWith(" [truncated]"));
            assertTrue(stored.length() <= 32767);
            for (int index = 0; index < stored.length(); index++) {
                char character = stored.charAt(index);
                if (Character.isHighSurrogate(character)) {
                    assertTrue(index + 1 < stored.length() && Character.isLowSurrogate(stored.charAt(index + 1)));
                    index++;
                } else assertTrue(!Character.isLowSurrogate(character));
            }
        }
    }

    private void assertRow(Row row, String caseId, String status, String execId, String sourceType) {
        assertEquals(caseId, row.getCell(2).getStringCellValue());
        assertEquals(caseId.substring(caseId.indexOf('.') + 1), row.getCell(3).getStringCellValue());
        assertEquals("flow.v1", row.getCell(4).getStringCellValue());
        assertEquals(sourceType, row.getCell(7).getStringCellValue());
        assertEquals(execId, row.getCell(13).getStringCellValue());
        assertEquals(status, row.getCell(14).getStringCellValue());
    }

    private TestResult result(String caseId, ResultStatus status, String executionId, String actual) {
        return new TestResult(caseId, caseId, status, Duration.ofMillis(12), "expected", actual, null,
                Collections.<att.core.ValidationResult>emptyList(), "", "", Collections.<String>emptyList(), null,
                executionId, "def", caseId, true, "flow.v1",
                "flow-revision-" + caseId, "source-revision-" + caseId,
                provenance("inline", "testcase/def/testcase.yaml", "Cases", 1,
                        caseId.substring(caseId.indexOf('.') + 1)));
    }

    private Map<String, Object> provenance(String type, String reference, String sheet, Integer row, String recordId) {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("sourceType", type);
        source.put("sourceReference", reference);
        if (sheet != null) source.put("sheet", sheet);
        if (row != null) { source.put("row", row); source.put("recordIndex", row); }
        source.put("recordId", recordId);
        return source;
    }
}
