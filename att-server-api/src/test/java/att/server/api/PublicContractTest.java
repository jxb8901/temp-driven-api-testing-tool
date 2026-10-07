package att.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublicContractTest {
    @Test void submissionUsesOnlyLogicalServerFields() throws Exception {
        JobSubmission request=new JobSubmission();request.packageId="payments";request.environment="SIT";request.all=true;
        String json=new ObjectMapper().writeValueAsString(request);
        assertTrue(json.contains("\"packageId\":\"payments\""));
        assertFalse(json.contains("packageRoot"));assertFalse(json.contains("outputDirectory"));assertFalse(json.contains("SERVER_DATA_DIR"));
    }
    @Test void onlyDeclaredStatusesAreAccepted() {
        assertTrue(ApiStatus.parse("PASS").isTerminal());assertFalse(ApiStatus.parse("RUNNING").isTerminal());
        assertThrows(IllegalArgumentException.class,()->ApiStatus.parse("FUTURE_SUCCESS"));
        assertEquals("1",ServerApi.VERSION);
    }
}
