package att.server;

import att.server.api.AttValidationReport;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerRuntimeValidationAggregationTest {
    @Test void remoteValidateAllPreservesIncompleteWorkerAndPackageReports() {
        Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();
        diagnostic.put("code","ATT-RESOURCE-HELPER-UNAVAILABLE");
        diagnostic.put("severity","ERROR");
        Map<String,Object> failedSection=new LinkedHashMap<String,Object>();
        failedSection.put("name","Shared Runtime Config");
        failedSection.put("source","config/config.yaml");
        failedSection.put("status","FAIL");
        failedSection.put("diagnostics",Collections.singletonList(diagnostic));
        Map<String,Object> workerReport=new LinkedHashMap<String,Object>();
        workerReport.put("schemaVersion","att-validation/v1");
        workerReport.put("complete",Boolean.FALSE);
        workerReport.put("exitCode",Integer.valueOf(2));
        workerReport.put("sections",Collections.<Map<String,Object>>singletonList(failedSection));

        AttValidationReport shared=new AttValidationReport("ALL","SERVER");
        ServerRuntime.appendValidationSections(shared,workerReport);
        assertInvalidAndIncomplete(shared.toMap());

        AttValidationReport packages=new AttValidationReport("ALL","SERVER");
        AttValidationReport.Section packageSection=packages.addSection("Package payments","packages/payments","FAIL");
        ServerRuntime.appendValidationSections(packageSection,workerReport);
        ServerRuntime.appendValidationOutcome(packages,workerReport);
        assertInvalidAndIncomplete(packages.toMap());
    }

    private static void assertInvalidAndIncomplete(Map<String,Object> report) {
        assertEquals("INVALID",report.get("overallStatus"));
        assertEquals(Boolean.FALSE,report.get("complete"));
        assertEquals(Integer.valueOf(2),report.get("exitCode"));
    }
}
