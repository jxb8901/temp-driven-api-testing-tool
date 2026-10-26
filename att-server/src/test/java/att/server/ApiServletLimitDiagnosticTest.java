package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

class ApiServletLimitDiagnosticTest {
    @Test void admissionAndInspectionHttpFailuresCarrySubmissionTaxonomy() throws Exception {
        assertContract(413,"ATT-SERVER-REQUEST-TOO-LARGE","server.maxRequestBytes");
        assertContract(413,"ATT-SERVER-INSPECTION-RESPONSE-TOO-LARGE","server.inspection.maxResponseBytes");
        assertContract(429,"ATT-SERVER-DRAFT-CAPACITY","server.drafts.capacity");
        assertContract(503,"ATT-SERVER-INSPECTION-CAPACITY","server.inspection.capacity");
        assertContract(504,"ATT-SERVER-INSPECTION-TIMEOUT","server.inspection.timeoutMs");
    }

    @Test void unavailableArchiveHasSeparateArchiveOutcomeAndKeepsHttpConflictStatus() throws Exception {
        CapturingResponse capture=new CapturingResponse();
        ApiServlet.error(capture.response(),409,"ATT-SERVER-ARCHIVE-UNAVAILABLE",
                "Artifact archive is pending, failed, or unavailable","request-safe");
        JsonNode response=ServerRuntime.JSON.readTree(capture.body());
        assertEquals(409,capture.status);
        assertTrue(response.path("diagnostics").isMissingNode(),
                "a state-less generic error must not guess that an archive failed");
        assertFalse(response.toString().contains("/private/"));
    }

    private static void assertContract(int status,String code,String limitName) throws Exception {
        CapturingResponse capture=new CapturingResponse();
        ApiServlet.error(capture.response(),status,code,"The bounded operation cannot be completed now","request-safe");
        JsonNode response=ServerRuntime.JSON.readTree(capture.body());
        JsonNode diagnostic=response.path("diagnostics").path(0);
        assertEquals(status,capture.status,code);
        assertEquals(code,response.path("error").path("code").asText());
        assertEquals("SUBMISSION_REJECTED",diagnostic.path("outcome").asText());
        assertEquals(limitName,diagnostic.path("limitName").asText());
        assertEquals("admission",diagnostic.path("stage").asText());
        assertFalse(response.toString().contains("/private/"));
    }

    private static final class CapturingResponse {
        private final ByteArrayOutputStream output=new ByteArrayOutputStream();
        private int status;
        private final ServletOutputStream stream=new ServletOutputStream() {
            @Override public boolean isReady(){return true;}
            @Override public void setWriteListener(WriteListener listener) { }
            @Override public void write(int value){output.write(value);}
        };
        HttpServletResponse response(){
            return (HttpServletResponse)Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class},(proxy,method,args)->{
                        if("setStatus".equals(method.getName())){status=(Integer)args[0];return null;}
                        if("getOutputStream".equals(method.getName()))return stream;
                        if(method.getReturnType()==boolean.class)return false;
                        if(method.getReturnType()==int.class)return 0;
                        if(method.getReturnType()==long.class)return 0L;
                        return null;
                    });
        }
        byte[] body(){return output.toByteArray();}
    }
}
