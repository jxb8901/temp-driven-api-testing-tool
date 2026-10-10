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

    @Test void packageResourceRoutesAndResponsesAreTyped() throws Exception {
        assertEquals("/api/v1/packages/{packageId}/resources",ServerApi.PACKAGE_RESOURCES);
        assertEquals("/api/v1/packages/{packageId}/resources/{kind}/{resourceId}",ServerApi.PACKAGE_RESOURCE);
        assertEquals("/api/v1/packages/{packageId}/resources/{kind}/{resourceId}/source",ServerApi.PACKAGE_RESOURCE_SOURCE);
        ResourceInspection.Page page=new ResourceInspection.Page();page.total=1;page.nextCursor="signed-cursor";
        ResourceInspection.Diagnostic pageDiagnostic=new ResourceInspection.Diagnostic();pageDiagnostic.code="ATT-RESOURCE-INDEX-WARNING";pageDiagnostic.summary="Some resources could not be indexed";
        page.diagnostics=java.util.Collections.singletonList(pageDiagnostic);
        ResourceInspection.Resource resource=new ResourceInspection.Resource();resource.resourceId="case.AAA";resource.type="case";
        page.items=java.util.Collections.singletonList(resource);
        com.fasterxml.jackson.databind.JsonNode json=new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(page));
        assertEquals("case.AAA",json.path("items").get(0).path("resourceId").asText());
        assertEquals("signed-cursor",json.path("nextCursor").asText());
        assertEquals("ATT-RESOURCE-INDEX-WARNING",json.path("diagnostics").get(0).path("code").asText());
        assertFalse(json.has("revisionDigest"));
    }

    @Test void configurationInspectionRoutesReturnTypedSafeViewEnvelope() throws Exception {
        assertEquals("/api/v1/packages/{packageId}/configuration",ServerApi.PACKAGE_CONFIGURATION);
        assertEquals("/api/v1/packages/{packageId}/configuration/effective",ServerApi.PACKAGE_CONFIGURATION_EFFECTIVE);
        assertEquals("/api/v1/packages/{packageId}/configuration/compare",ServerApi.PACKAGE_CONFIGURATION_COMPARE);
        ConfigurationInspection.Response response=new ConfigurationInspection.Response();response.view="effective";response.state="ready";response.environment="SIT";
        response.globals=java.util.Collections.<String,Object>singletonMap("timeoutMs",java.util.Map.of("state","visible","value",5000));
        response.diagnostics=java.util.Collections.emptyList();
        com.fasterxml.jackson.databind.JsonNode json=new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(response));
        assertEquals("effective",json.path("view").asText());assertEquals("visible",json.path("globals").path("timeoutMs").path("state").asText());
        assertFalse(json.toString().contains("packageRoot"));assertFalse(json.toString().contains("serverDataDir"));
    }
}
