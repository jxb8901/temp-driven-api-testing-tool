package att;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import att.api.ValidateResult;
import att.server.api.AttValidationReport;
import java.util.Collections;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VNextCommandTest {
    @TempDir Path packageRoot;

    @Test void diagnosticProjectionRedactsHostPathsAndCredentialValues() {
        Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();
        diagnostic.put("detail","Unable to read /tmp/host/private/passwords.yaml; password=top-secret-value");
        Map<String,Object> source=new LinkedHashMap<String,Object>();
        source.put("file",packageRoot.resolve("testdata/uat.yaml").toString());
        diagnostic.put("source",source);

        @SuppressWarnings("unchecked") Map<String,Object> safe=(Map<String,Object>)VNextCommand.safeDiagnosticValue(diagnostic,packageRoot);
        String output=att.validation.JsonSupport.write(safe);

        assertFalse(output.contains("/tmp/host/private"));
        assertFalse(output.contains("top-secret-value"));
        assertTrue(output.contains("$EXTERNAL/passwords.yaml"));
        assertTrue(output.contains("testdata/uat.yaml"));
    }

    @Test void fullInventoryFailureIsReportedAndLaterEnvironmentStillRuns() {
        AttValidationReport report=new AttValidationReport("ALL","LOCAL");
        AttValidationReport.Section pkg=report.addSection("Package payments","packages/payments","PASS");

        VNextCommand.validateInventory(report,pkg,"payments","SIT",packageRoot,()->{
            throw new IllegalArgumentException("Unable to read "+packageRoot.resolve("private.yaml")+"; password=secret-value");
        });
        VNextCommand.validateInventory(report,pkg,"payments","UAT",packageRoot,()->
                new ValidateResult(null,"PASS",0,0,Collections.emptyList(),Collections.emptyMap(),Collections.emptyMap()));

        assertEquals(2,report.exitCode());
        assertEquals("INVALID",report.toMap().get("overallStatus"));
        String rendered=att.validation.JsonSupport.write(report.toMap());
        assertTrue(rendered.contains("SIT — Testcases / Templates / Flows / dependencies"));
        assertTrue(rendered.contains("UAT — Testcases / Templates / Flows / dependencies"));
        Map<?,?> packageSection=(Map<?,?>)((List<?>)report.toMap().get("sections")).get(0);
        assertEquals("FAIL",packageSection.get("status"));
        assertFalse(rendered.contains("secret-value"));
    }
}
