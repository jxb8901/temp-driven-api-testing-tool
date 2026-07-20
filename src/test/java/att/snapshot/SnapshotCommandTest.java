/* Author: Jeffrey + ChatGPT */
package att.snapshot;

import att.config.FrameworkConfig;
import att.core.ExecutionOptions;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotCommandTest {
    @TempDir Path projectRoot;

    @Test void generatesAllRecursivelyAndOneExplicitSuite() throws Exception {
        writeSuite("one", "ONE"); writeSuite("nested/two", "TWO");
        FrameworkConfig global = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.emptyMap(), null, null);
        List<Path> all = new SnapshotCommand().generate(projectRoot, global, ExecutionOptions.parse(new String[]{"snapshot", "--all"}));
        assertEquals(2, all.size());
        assertTrue(Files.isRegularFile(projectRoot.resolve("testcase/one.xml")));
        assertTrue(Files.isRegularFile(projectRoot.resolve("testcase/nested/two.xml")));
        byte[] beforeStyleChange = Files.readAllBytes(projectRoot.resolve("testcase/one.xml"));
        Path firstWorkbook = projectRoot.resolve("testcase/one.xlsx");
        try (InputStream input = Files.newInputStream(firstWorkbook); Workbook value = new XSSFWorkbook(input)) {
            value.getSheetAt(0).setColumnWidth(0, 9000);
            try (OutputStream output = Files.newOutputStream(firstWorkbook)) { value.write(output); }
        }
        new SnapshotCommand().generate(projectRoot, global,
                ExecutionOptions.parse(new String[]{"snapshot", "--suite", "testcase/one.xlsx"}));
        assertArrayEquals(beforeStyleChange, Files.readAllBytes(projectRoot.resolve("testcase/one.xml")));
        Files.delete(projectRoot.resolve("testcase/one.xml"));
        List<Path> one = new SnapshotCommand().generate(projectRoot, global,
                ExecutionOptions.parse(new String[]{"snapshot", "--suite", "testcase/one.xlsx"}));
        assertEquals(projectRoot.resolve("testcase/one.xml").toRealPath(), one.get(0).toRealPath());
    }

    @Test void runUpdateCreatesOnlyChangedCanonicalSnapshotsAndRejectsSymlinks() throws Exception {
        writeSuite("one", "ONE");
        FrameworkConfig global = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.emptyMap(), null, null);
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"run", "--suite", "testcase/one.xlsx", "--update-snapshot"});
        SnapshotCommand command = new SnapshotCommand();
        Path snapshot = projectRoot.resolve("testcase/one.xml");

        List<Path> created = command.updateForRun(projectRoot, global, options);
        assertEquals(1, created.size()); assertEquals(snapshot.toRealPath(), created.get(0).toRealPath());
        byte[] first = Files.readAllBytes(snapshot);
        FileTime fixed = FileTime.fromMillis(123456000L); Files.setLastModifiedTime(snapshot, fixed);
        assertTrue(command.updateForRun(projectRoot, global, options).isEmpty());
        assertEquals(fixed, Files.getLastModifiedTime(snapshot));

        Path workbook = projectRoot.resolve("testcase/one.xlsx");
        try (InputStream input = Files.newInputStream(workbook); Workbook value = new XSSFWorkbook(input)) {
            value.getSheetAt(0).getRow(1).getCell(1).setCellValue("regression");
            try (OutputStream output = Files.newOutputStream(workbook)) { value.write(output); }
        }
        assertEquals(1, command.updateForRun(projectRoot, global, options).size());
        assertTrue(!java.util.Arrays.equals(first, Files.readAllBytes(snapshot)));

        Path protectedFile = projectRoot.resolve("testcase/protected.xml"); Files.write(protectedFile, "keep".getBytes(StandardCharsets.UTF_8));
        Files.delete(snapshot); Files.createSymbolicLink(snapshot, protectedFile.getFileName());
        assertThrows(att.validation.DiagnosticException.class, () -> command.updateForRun(projectRoot, global, options));
        assertEquals("keep", new String(Files.readAllBytes(protectedFile), StandardCharsets.UTF_8));
    }

    @Test void runUpdatePreparesEveryWorkbookBeforeWritingAnySnapshot() throws Exception {
        writeSuite("one", "ONE"); writeSuite("two", "TWO");
        Files.write(projectRoot.resolve("testcase/two.yaml"), "invalid: true\n".getBytes(StandardCharsets.UTF_8));
        FrameworkConfig global = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 10000,
                Paths.get("templates"), Collections.emptyMap(), null, null);
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"run", "--all", "--update-snapshot"});

        assertThrows(att.validation.DiagnosticException.class, () -> new SnapshotCommand().updateForRun(projectRoot, global, options));
        assertTrue(!Files.exists(projectRoot.resolve("testcase/one.xml")));
        assertTrue(!Files.exists(projectRoot.resolve("testcase/two.xml")));
    }

    private void writeSuite(String relative, String id) throws Exception {
        Path workbook = projectRoot.resolve("testcase/" + relative + ".xlsx"); Files.createDirectories(workbook.getParent());
        try (Workbook value = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(workbook)) {
            Sheet sheet = value.createSheet("Cases"); Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Case ID"); header.createCell(1).setCellValue("Tags"); header.createCell(2).setCellValue("Template");
            Row row = sheet.createRow(1); row.createCell(0).setCellValue("TC001"); row.createCell(1).setCellValue("smoke"); row.createCell(2).setCellValue("PAYMENT_INVOKE"); value.write(output);
        }
        String sidecar = "schemaVersion: att-sidecar/v2.1\nid: " + id + "\nexcel:\n  sheet: Cases\n  caseId: Case ID\n  tags: Tags\nstages:\n  - key: invoke\n    template: Template\n    required: true\n";
        Files.write(projectRoot.resolve("testcase/" + relative + ".yaml"), sidecar.getBytes(StandardCharsets.UTF_8));
    }
}
