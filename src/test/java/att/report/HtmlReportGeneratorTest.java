package att.report;

import att.core.ResultStatus;
import att.core.RunSummary;
import att.core.TestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Author: Jeffrey + ChatGPT. */
class HtmlReportGeneratorTest {
    @TempDir Path tempDir;

    @Test void producesAStandaloneSinglePageReport() throws Exception {
        Path log = tempDir.resolve("payments.payment.TC001/case.log");
        Files.createDirectories(log.getParent()); Files.write(log, "ACTION callApi".getBytes("UTF-8"));
        TestResult result = new TestResult("payments.payment.TC001", "Payment <success>", ResultStatus.ERROR, Duration.ofMillis(12), "SUCCESS", "", log,
                Collections.singletonList(new att.core.ValidationResult("verify","callApi",ResultStatus.ERROR,"tool","","Visible failure message")), "payments", "payment", Arrays.asList("smoke", "critical"));
        Path report = new HtmlReportGenerator().generate(tempDir, "RUN-1", new RunSummary(Collections.singletonList(result), tempDir), Instant.now(), Instant.now());
        String html = new String(Files.readAllBytes(report), "UTF-8");
        assertTrue(html.contains("Case details"));
        assertTrue(html.contains("Action results"));
        assertTrue(html.contains("Visible failure message"));
        assertTrue(html.contains("Payment &lt;success&gt;"));
        assertTrue(html.contains("Minimum"));
        assertTrue(html.contains("Average"));
        assertTrue(html.contains("caseSearch"));
        assertTrue(html.contains("Workbook.Sheet"));
        assertTrue(html.contains("payments.payment"));
        assertTrue(html.contains("id=\"workbookFilter\""));
        assertTrue(html.contains("id=\"sheetFilter\""));
        assertTrue(html.contains("data-search=\"payments payment payments.payment.tc001 smoke, critical success "));
        assertTrue(html.contains("data-expected=\"SUCCESS\""));
        assertTrue(html.contains("data-sort=\"expected\""));
        assertTrue(html.contains("data-sort=\"caseid\""));
        assertTrue(html.contains("data-type=\"number\""));
        assertTrue(html.contains("addEventListener('input',filterCases)"));
        assertTrue(html.contains("localeCompare"));
        assertTrue(html.contains("aria-label=\"Report index\""));
        assertTrue(html.contains("href=\"#case-details\""));
        assertTrue(html.contains("href=\"junit.html\""));
        assertTrue(html.contains(HtmlSupport.id("payments.payment.TC001")));
        assertFalse(Files.exists(tempDir.resolve("report/cases")));
    }
}
