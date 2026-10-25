package att.server.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AttValidationReportTest {
    @Test void emitsVersionedHierarchicalReportAndFailureWinsOverPartialStatus() {
        AttValidationReport report=new AttValidationReport("ALL","LOCAL");
        report.context("runtimeConfig","config/config.yaml");
        AttValidationReport.Section packageSection=report.addSection("Package p1","packages/p1","FAIL");
        Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();
        diagnostic.put("code","ATT-TESTDATA-INVALID");diagnostic.put("severity","ERROR");diagnostic.put("source","testdata/uat.yaml");diagnostic.put("message","Testdata descriptor is invalid");
        packageSection.diagnostic(diagnostic);report.incomplete();

        Map<String,Object> value=report.toMap();
        assertEquals("att-validation/v1",value.get("schemaVersion"));assertEquals("INVALID",value.get("overallStatus"));
        assertEquals(2,value.get("exitCode"));assertEquals("config/config.yaml",((Map<?,?>)value.get("context")).get("runtimeConfig"));
        assertEquals(1,((Map<?,?>)value.get("counts")).get("errors"));
    }

    @Test void marksOversizedReportsIncompleteAndKeepsAnExplicitLimitDiagnostic() throws Exception {
        AttValidationReport report=new AttValidationReport("ALL","LOCAL");
        for(int i=0;i<100;i++) {
            AttValidationReport.Section section=report.addSection("Package p"+i,"packages/p"+i,"FAIL");
            Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();
            diagnostic.put("code","ATT-TESTDATA-INVALID");diagnostic.put("severity","ERROR");
            diagnostic.put("message",String.join("",Collections.nCopies(80,"unsafe-but-redacted ")));
            section.diagnostic(diagnostic);
        }
        report.limitOutputBytes(1024);

        Map<String,Object> value=report.toMap();
        assertEquals(Boolean.FALSE,value.get("complete"));assertEquals(3,value.get("exitCode"));
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(value).length<=1024);
        assertEquals("PARTIAL",value.get("overallStatus"));
        assertEquals(Boolean.TRUE,((Map<?,?>)value.get("context")).get("reportTruncated"));
        boolean marker=false;
        for(Object item:(List<?>)value.get("diagnostics"))if(item instanceof Map&&"ATT-VALIDATION-OUTPUT-LIMIT".equals(((Map<?,?>)item).get("code")))marker=true;
        assertTrue(marker);
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(value).length<=4096);
    }
}
