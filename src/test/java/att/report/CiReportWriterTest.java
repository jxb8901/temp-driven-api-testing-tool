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
