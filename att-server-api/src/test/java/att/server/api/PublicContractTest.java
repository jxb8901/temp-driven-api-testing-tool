package att.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PublicContractTest {
    @TempDir Path temp;

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
        response.section="dbhelpers";response.offset=0;response.limit=50;response.total=120;response.nextOffset=50;
        Map<String,Object> timeoutView=new LinkedHashMap<>();timeoutView.put("state","visible");timeoutView.put("value",5000);
        response.globals=java.util.Collections.<String,Object>singletonMap("timeoutMs",timeoutView);
        response.diagnostics=java.util.Collections.emptyList();
        com.fasterxml.jackson.databind.JsonNode json=new ObjectMapper().readTree(new ObjectMapper().writeValueAsBytes(response));
        assertEquals("effective",json.path("view").asText());assertEquals("visible",json.path("globals").path("timeoutMs").path("state").asText());
        assertEquals(120,json.path("total").asInt());assertEquals(50,json.path("nextOffset").asInt());
        assertFalse(json.toString().contains("packageRoot"));assertFalse(json.toString().contains("serverDataDir"));
    }

    @Test void diagnosticPathRedactionPreservesPackageRelativeLocationsAndHidesExternalRoots() throws Exception {
        Path packageRoot=Files.createDirectories(temp.resolve("packages/payments"));
        Path source=packageRoot.resolve("load/invalid.yaml");
        Files.createDirectories(source.getParent());
        Files.write(source,"schemaVersion: invalid\n".getBytes(StandardCharsets.UTF_8));
        Path external=Files.createDirectories(temp.resolve("private" )).resolve("credentials.yaml");
        Files.write(external,"password: must-not-leak\n".getBytes(StandardCharsets.UTF_8));

        String diagnostic="file=\""+source+"\" line=12 sourceFile="+source+"; external="+external;
        String safe=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(diagnostic,packageRoot);
        assertTrue(safe.contains("load/invalid.yaml"),safe);
        assertTrue(safe.contains("line=12"),safe);
        assertTrue(safe.contains("$EXTERNAL/credentials.yaml"),safe);
        assertFalse(safe.contains(packageRoot.toString()),safe);
        assertFalse(safe.contains(external.toString()),safe);
    }
    @Test void publicProjectionPreservesRelativeTokensAndXmlAndIsIdempotent() throws Exception {
        for (String original : java.util.Arrays.asList("payload='&{./Pacs008.xml}'", "&{../request.xml}",
                "${map['../請求.xml']} #{tool('./a.xml')}", "./file.yaml ../file.yaml",
                "<request><child>ok</child></request>", "https://host/a/b", "user@host:/remote/file")) {
            String safe=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(original,temp);
            assertEquals(original,safe);
            assertEquals(safe,att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(safe,temp));
        }
        assertEquals("folder with spaces/bad.yaml", att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(
                temp.resolve("folder with spaces/bad.yaml").toString(),temp));
        assertEquals("$EXTERNAL/bad.yaml", att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(
                "/outside/private folder/bad.yaml",temp));
        String source="file='"+temp.resolve("folder with spaces/bad.yaml")+"' line=12 column=3 external=C:\\host\\private\\file.yaml";
        String safe=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(source,temp);
        assertTrue(safe.contains("folder with spaces/bad.yaml"),safe);
        assertTrue(safe.contains("line=12 column=3"),safe);
        assertFalse(safe.contains("C:\\host"),safe);
        assertEquals(safe,att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(safe,temp));
    }

    @Test void pathRedactionPreservesReportUnitsAndCompletionCounts() {
        String report="target generator rate /s; 0 / 120 measured completions; could not read /tmp/private/report.html";
        String safe=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(report,temp);
        assertTrue(safe.contains("target generator rate /s"),safe);
        assertTrue(safe.contains("0 / 120 measured completions"),safe);
        assertTrue(safe.contains("could not read $EXTERNAL/report.html"),safe);
        assertFalse(safe.contains("/tmp/private/report.html"),safe);
    }

}
