package com.company.apitest.report;

import com.company.apitest.core.ResultStatus;
import com.company.apitest.core.RunSummary;
import com.company.apitest.core.TestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Author: Jeffrey + ChatGPT. */
class HtmlReportGeneratorTest {
    @TempDir Path tempDir;

    @Test void producesAStandaloneSinglePageReport() throws Exception {
        Path log = tempDir.resolve("payment.TC001/case.log");
        Files.createDirectories(log.getParent()); Files.write(log, "ACTION callApi".getBytes("UTF-8"));
        TestResult result = new TestResult("payment.TC001", "Payment <success>", ResultStatus.PASS, Duration.ofMillis(12), "SUCCESS", "SUCCESS", log, Collections.emptyList());
        Path report = new HtmlReportGenerator().generate(tempDir, "RUN-1", new RunSummary(Collections.singletonList(result), tempDir), Instant.now(), Instant.now());
        String html = new String(Files.readAllBytes(report), "UTF-8");
        assertTrue(html.contains("Case details"));
        assertTrue(html.contains("Payment &lt;success&gt;"));
        assertTrue(html.contains("Minimum"));
        assertTrue(html.contains("Average"));
        assertTrue(html.contains("caseSearch"));
        assertTrue(html.contains(HtmlSupport.id("payment.TC001")));
        assertFalse(Files.exists(tempDir.resolve("report/cases")));
    }
}
