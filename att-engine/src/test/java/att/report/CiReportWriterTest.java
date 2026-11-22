package att.report;

import att.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CiReportWriterTest {
    @TempDir Path tempDir;
    @Test void preservesNativeIdentityAndAllCommonOutcomesInCiAndJunitReports() throws Exception {
        Path run = tempDir.resolve("native"); Files.createDirectories(run);
        Map<String,Object> provenance = new LinkedHashMap<String,Object>();
        provenance.put("sourceType", "inline");
        provenance.put("sourceReference", "testcase/payments/testcase.yaml");
        provenance.put("recordId", "transfer");
        List<TestResult> results = Arrays.asList(
                nativeResult("payments.transfer.pass", ResultStatus.PASS, "EXEC-PASS", provenance, true),
                nativeResult("payments.transfer.fail", ResultStatus.FAIL, "EXEC-FAIL", provenance, true),
                nativeResult("payments.transfer.error", ResultStatus.ERROR, "EXEC-ERROR", provenance, true),
                TestResult.notExecuted("payments.transfer.skipped", "Skipped", Collections.<String>emptyList(),
                        "payments.transfer", "payments.transfer.skipped", "failFast", "payment.transfer.v1",
                        "flow-revision-payments.transfer.skipped", "source-revision-payments.transfer.skipped", provenance));
        RunSummary summary = new RunSummary(results, run);
        new CiReportWriter().write(run, "R-NATIVE", "SIT", summary, Instant.EPOCH, Instant.EPOCH, 10,
                "abc", Collections.<att.validation.Diagnostic>emptyList(),
                new LinkedHashSet<String>(Arrays.asList("junit", "json")));

        String json = new String(Files.readAllBytes(run.resolve("ci/summary.json")), "UTF-8");
        assertTrue(json.contains("\"definitionId\":\"payments.transfer\""));
        assertTrue(json.contains("\"qualifiedCaseId\":\"payments.transfer.pass\""));
        assertTrue(json.contains("\"flowId\":\"payment.transfer.v1\""));
        assertTrue(json.contains("\"flowRevision\":\"flow-revision-"));
        assertTrue(json.contains("\"sourceRevision\":\"source-revision-"));
        assertTrue(json.contains("\"sourceReference\":\"testcase/payments/testcase.yaml\""));
        assertTrue(json.contains("\"status\":\"SKIPPED\""));
        assertTrue(json.contains("\"executed\":false"));

        String junit = new String(Files.readAllBytes(run.resolve("ci/junit.xml")), "UTF-8");
        assertTrue(junit.contains("classname=\"payments.transfer.payment.transfer.v1\""));
        assertTrue(junit.contains("name=\"payments.transfer.skipped\""));
        assertTrue(junit.contains("ATT.SOURCE_PROVENANCE"));
        assertTrue(junit.contains("ATT.FLOW_REVISION"));
        assertTrue(junit.contains("ATT.SOURCE_REVISION"));
        assertTrue(junit.contains("value=\"flow-revision-payments.transfer.pass\""));
        assertTrue(junit.contains("value=\"source-revision-payments.transfer.pass\""));
        assertTrue(junit.contains("<failure"));
        assertTrue(junit.contains("<error"));
        assertTrue(junit.contains("<skipped/>"));
        assertEquals(4, junit.split("<testcase ", -1).length - 1);
    }

    private TestResult nativeResult(String qualifiedCaseId, ResultStatus status, String executionId,
            Map<String,Object> provenance, boolean executed) {
        return new TestResult(qualifiedCaseId, qualifiedCaseId, status, Duration.ofMillis(10), "expected", "actual", null,
                Collections.<ValidationResult>emptyList(), "", "", Collections.<String>emptyList(), null, executionId,
                "payments.transfer", qualifiedCaseId, executed, "payment.transfer.v1", "flow-revision-" + qualifiedCaseId,
                "source-revision-" + qualifiedCaseId, provenance);
    }

    @Test void switchesBetweenEmbeddedAndExternalCaseLogByConfiguredSize() throws Exception {
        Path run = tempDir.resolve("run"); Files.createDirectories(run.resolve("g.TC1"));
        Path log = run.resolve("g.TC1/case.log"); Files.write(log,"0123456789ABCDEF".getBytes("UTF-8"));
        TestResult result = new TestResult("g.TC1","case",ResultStatus.ERROR,Duration.ZERO,"Expected line 1\nExpected line 2","Actual line 1\nActual line 2",log,Collections.singletonList(new ValidationResult("verify","tool",ResultStatus.ERROR,"tool","actual","Visible failure message")));
        RunSummary summary = new RunSummary(Collections.singletonList(result),run);
        new CiReportWriter().write(run,"R","SIT",summary,Instant.EPOCH,Instant.EPOCH,10,"abc",Collections.<att.validation.Diagnostic>emptyList(),new LinkedHashSet<String>(Arrays.asList("junit","json")));
        String external = new String(Files.readAllBytes(run.resolve("ci/junit.xml")),"UTF-8");
        assertTrue(external.contains("Case log: g.TC1/case.log")); assertFalse(external.contains("0123456789ABCDEF"));
        String htmlExternal = new String(Files.readAllBytes(run.resolve("report/junit.html")),"UTF-8");
        assertTrue(htmlExternal.contains("JUnit Report")); assertTrue(htmlExternal.contains("Open log"));
        new CiReportWriter().write(run,"R","SIT",summary,Instant.EPOCH,Instant.EPOCH,100,"abc",Collections.<att.validation.Diagnostic>emptyList(),new LinkedHashSet<String>(Arrays.asList("junit","json")));
        String embedded = new String(Files.readAllBytes(run.resolve("ci/junit.xml")),"UTF-8");
        assertTrue(embedded.contains("0123456789ABCDEF"));
        assertTrue(new String(Files.readAllBytes(run.resolve("report/junit.html")),"UTF-8").contains("0123456789ABCDEF"));
        String json = new String(Files.readAllBytes(run.resolve("ci/summary.json")),"UTF-8");
        assertTrue(json.contains("Visible failure message"));
        assertTrue(json.contains("Expected line 1\\nExpected line 2"));
        assertTrue(embedded.contains("expected=tool actual=actual"));
    }
}
