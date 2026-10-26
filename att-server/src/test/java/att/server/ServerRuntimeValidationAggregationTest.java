package att.server;

import att.server.api.AttValidationReport;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test void serverValidateAllReportsPackageScanLimitAndCounts() {
        List<Map.Entry<String,Path>> registered=new ArrayList<Map.Entry<String,Path>>();
        for(int i=0;i<201;i++)registered.add(new AbstractMap.SimpleImmutableEntry<String,Path>(
                "package-"+i,Paths.get("packages","package-"+i)));
        AttValidationReport report=new AttValidationReport("ALL","SERVER");

        List<Map.Entry<String,Path>> checked=ServerRuntime.boundValidationPackages(registered,report);
        Map<String,Object> result=report.toMap();
        assertEquals(200,checked.size());
        assertEquals(Integer.valueOf(201),((Map<?,?>)result.get("context")).get("packages"));
        assertEquals("PARTIAL",result.get("overallStatus"));
        assertEquals(Boolean.FALSE,result.get("complete"));
        assertEquals(Integer.valueOf(3),result.get("exitCode"));
        List<?> sections=(List<?>)result.get("sections");
        assertEquals(1,sections.size());
        Map<?,?> limit=(Map<?,?>)sections.get(0);
        assertEquals("Package Scan Limit",limit.get("name"));
        assertEquals("WARN",limit.get("status"));
        Map<?,?> diagnostic=(Map<?,?>)((List<?>)limit.get("diagnostics")).get(0);
        assertEquals("ATT-VALIDATION-LIMIT",diagnostic.get("code"));
        assertEquals("WARNING",diagnostic.get("severity"));
        String message=String.valueOf(diagnostic.get("message"));
        assertTrue(message.contains("200 of 201"));
        assertTrue(message.contains("skipped 1 remaining registered Packages"));
    }

    private static void assertInvalidAndIncomplete(Map<String,Object> report) {
        assertEquals("INVALID",report.get("overallStatus"));
        assertEquals(Boolean.FALSE,report.get("complete"));
        assertEquals(Integer.valueOf(2),report.get("exitCode"));
    }
}
