package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RunDefinitionTest {
    private static RunDefinition parse(String body) throws Exception {return RunDefinition.parse(ServerRuntime.JSON.readTree(body));}
    private static String request(String body){return "{\"schemaVersion\":\"att-execution/v1\",\"executionType\":\"run\",\"packageId\":\"payments\","+body+"}";}
    private static String requestV2(String body){return "{\"schemaVersion\":\"att-execution/v2\",\"executionType\":\"run\",\"packageId\":\"payments\","+body+"}";}

    @Test void publishedSchemaAcceptsTheSameTargetAndSelectionShapes() throws Exception {
        java.nio.file.Path schema=java.nio.file.Path.of("schemas/history/att-execution-v1.schema.json");
        for(String body:List.of(request("\"target\":{\"type\":\"case\",\"id\":\"A\"}"),
                request("\"target\":{\"type\":\"flow\",\"id\":\"f\"},\"input\":{}"),
                request("\"selection\":{\"tags\":[\"smoke\"]}"))) {
            att.validation.JsonSchemaVerifier.verifyJson(schema,body);parse(body);
        }
        for(String body:List.of(request("\"target\":{\"type\":\"case\",\"id\":\"A\"},\"input\":{}"),
                request("\"selection\":{\"excludeTags\":[\"slow\"]}"),
                request("\"selection\":{\"all\":true},\"options\":{\"users\":2}"))) {
            assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,()->att.validation.JsonSchemaVerifier.verifyJson(schema,body));
            assertThrows(IllegalArgumentException.class,()->parse(body));
        }
    }
    @Test void nativeCaseV2UsesAnExplicitDefinitionAndLocalCaseSelector() throws Exception {
        String body=requestV2("\"target\":{\"type\":\"nativeCase\",\"definitionId\":\"payments.transfer\",\"selection\":{\"resolvedCaseIds\":[\"valid\",\"invalid\"]}},\"options\":{\"debug\":true,\"failFast\":true,\"runId\":\"native-smoke-2026\"}");
        java.nio.file.Path schema=java.nio.file.Path.of("schemas/att-execution-v2.schema.json");
        att.validation.JsonSchemaVerifier.verifyJson(schema,body);
        RunDefinition run=parse(body);
        assertTrue(run.nativeCaseTarget);assertFalse(run.resourceTarget);
        assertEquals("payments.transfer",run.nativeDefinitionId);
        assertEquals(List.of("valid","invalid"),run.resolvedCaseIds);
        assertTrue(run.debug);assertTrue(run.workerInput.path("failFast").asBoolean());
        assertEquals("native-smoke-2026",run.workerInput.path("runId").asText());
        assertFalse(run.workerInput.has("caseIds"),"Native local IDs must not become legacy Workbook Case IDs");

        // A dotted ID can independently exist in the legacy namespace; the explicit discriminator keeps both meanings stable.
        RunDefinition legacy=parse(request("\"target\":{\"type\":\"case\",\"id\":\"payments.transfer.valid\"}"));
        assertFalse(legacy.nativeCaseTarget);assertEquals("payments.transfer.valid",legacy.workerInput.path("caseIds").get(0).asText());
        RunDefinition v2Legacy=parse(requestV2("\"target\":{\"type\":\"case\",\"id\":\"payments.transfer.valid\"}"));
        assertFalse(v2Legacy.nativeCaseTarget);assertEquals("payments.transfer.valid",v2Legacy.workerInput.path("caseIds").get(0).asText());
        String flowBody=requestV2("\"target\":{\"type\":\"flow\",\"id\":\"payment.transfer.v1\"},\"input\":{},\"options\":{\"runId\":\"native-smoke-2026\"}");
        att.validation.JsonSchemaVerifier.verifyJson(schema,flowBody);
        assertTrue(parse(flowBody).resourceTarget,"v2 retains existing resource RUN targets");
        assertEquals("native-smoke-2026",parse(flowBody).workerInput.path("runId").asText());
        assertThrows(IllegalArgumentException.class,()->parse(request("\"target\":{\"type\":\"case\",\"id\":\"A\"},\"options\":{\"runId\":\"native-smoke-2026\"}")),
                "The v2 runId option must not expand the v1 contract");
        String selectionBody=requestV2("\"selection\":{\"caseIds\":[\"payments.transfer.valid\"]}");
        att.validation.JsonSchemaVerifier.verifyJson(schema,selectionBody);
        assertEquals("payments.transfer.valid",parse(selectionBody).workerInput.path("caseIds").get(0).asText());
        String c1ControlRunId="native-"+String.valueOf((char)0x85)+"run";
        for(String runId:List.of("../unsafe","CON","native-run."," native-run","native-run ",c1ControlRunId)) {
            String invalidRequestBody=requestV2("\"target\":{\"type\":\"nativeCase\",\"definitionId\":\"payments.transfer\",\"selection\":{\"resolvedCaseIds\":[\"valid\"]}},\"options\":{\"runId\":\""+runId+"\"}");
            assertThrows(IllegalArgumentException.class,()->parse(invalidRequestBody),runId);
            assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,
                    ()->att.validation.JsonSchemaVerifier.verifyJson(schema,invalidRequestBody),runId);
        }
    }
    @Test void nativeCaseV2RejectsMalformedOrAmbiguousSelections() throws Exception {
        java.nio.file.Path schema=java.nio.file.Path.of("schemas/att-execution-v2.schema.json");
        String validTarget="\"target\":{\"type\":\"nativeCase\",\"definitionId\":\"payments.transfer\",\"selection\":{\"resolvedCaseIds\":[\"valid\"]}}";
        att.validation.JsonSchemaVerifier.verifyJson(schema,requestV2(validTarget));
        assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,()->
                att.validation.JsonSchemaVerifier.verifyJson(java.nio.file.Path.of("schemas/history/att-execution-v1.schema.json"),request(validTarget)));
        assertThrows(IllegalArgumentException.class,()->parse(request(validTarget)),"v1 must not acquire native selector semantics");
        for(String body:List.of(
                "\"target\":{\"type\":\"nativeCase\",\"definitionId\":\"payments.transfer\",\"selection\":{\"resolvedCaseIds\":[]}}",
                "\"target\":{\"type\":\"nativeCase\",\"definitionId\":\"payments.transfer\",\"selection\":{\"resolvedCaseIds\":[\"valid\",\"valid\"]}}",
                "\"target\":{\"type\":\"nativeCase\",\"definitionId\":\"payments.transfer\",\"selection\":{\"resolvedCaseIds\":[\"bad.id\"]}}",
                "\"target\":{\"type\":\"nativeCase\",\"definitionId\":\"payments.transfer\",\"selection\":{\"resolvedCaseIds\":[\"CON\"]}}",
                "\"target\":{\"type\":\"nativeCase\",\"definitionId\":\"payments.con\",\"selection\":{\"resolvedCaseIds\":[\"valid\"]}}",
                validTarget+",\"selection\":{\"caseIds\":[\"payments.transfer.valid\"]}",
                validTarget+",\"input\":{}")) {
            String request=requestV2(body);
            assertThrows(IllegalArgumentException.class,()->parse(request),body);
            assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,()->att.validation.JsonSchemaVerifier.verifyJson(schema,request),body);
        }
        StringBuilder oversized=new StringBuilder("\"target\":{\"type\":\"nativeCase\",\"definitionId\":\"payments.transfer\",\"selection\":{\"resolvedCaseIds\":[");
        for(int i=0;i<1001;i++){if(i>0)oversized.append(',');oversized.append("\"C").append(i).append("\"");}
        oversized.append("]}}");
        String request=requestV2(oversized.toString());
        assertThrows(IllegalArgumentException.class,()->parse(request));
        assertThrows(IllegalArgumentException.class,()->RunDefinition.parseRetained(ServerRuntime.JSON.readTree(request)));
        assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,()->att.validation.JsonSchemaVerifier.verifyJson(schema,request));
    }

    @Test void nativeSuiteV2EnforcesSchemaTagLengthAndRejectsRerunHistoryIntent() throws Exception {
        java.nio.file.Path schema=java.nio.file.Path.of("schemas/att-execution-v2.schema.json");
        String tag128=String.join("",java.util.Collections.nCopies(128,"x"));
        String valid=requestV2("\"target\":{\"type\":\"nativeSuite\",\"definitionIds\":[\"fpp.transfer\"],\"selection\":{\"all\":true,\"tags\":[\""+tag128+"\"]}}}");
        att.validation.JsonSchemaVerifier.verifyJson(schema,valid);
        assertEquals(tag128,parse(valid).nativeSuiteTags.get(0));

        String tag129=tag128+"x";
        String tooLong=requestV2("\"target\":{\"type\":\"nativeSuite\",\"definitionIds\":[\"fpp.transfer\"],\"selection\":{\"all\":true,\"tags\":[\""+tag129+"\"]}}}");
        assertThrows(IllegalArgumentException.class,()->parse(tooLong));
        assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,()->att.validation.JsonSchemaVerifier.verifyJson(schema,tooLong));

        String history=requestV2("\"target\":{\"type\":\"nativeSuite\",\"definitionIds\":[\"fpp.transfer\"],\"selection\":{\"all\":true,\"rerunFailed\":true}}");
        assertThrows(IllegalArgumentException.class,()->parse(history));
        assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,()->att.validation.JsonSchemaVerifier.verifyJson(schema,history));
    }
    @Test void nativeSuiteV2SchemaAndParserRejectIneffectiveSelectionAndC1Tags() throws Exception {
        java.nio.file.Path schema=java.nio.file.Path.of("schemas/att-execution-v2.schema.json");
        String empty=requestV2("\"target\":{\"type\":\"nativeSuite\",\"definitionIds\":[\"fpp.transfer\"],"
                +"\"selection\":{\"all\":false}}}");
        assertThrows(IllegalArgumentException.class,()->parse(empty));
        assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,
                ()->att.validation.JsonSchemaVerifier.verifyJson(schema,empty));

        String c1Tag="smoke"+String.valueOf((char)0x85);
        String control=requestV2("\"target\":{\"type\":\"nativeSuite\",\"definitionIds\":[\"fpp.transfer\"],"
                +"\"selection\":{\"tags\":[\""+c1Tag+"\"]}}}");
        assertThrows(IllegalArgumentException.class,()->parse(control));
        assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,
                ()->att.validation.JsonSchemaVerifier.verifyJson(schema,control));
    }
    @Test void nativeSuiteV2QualifiedSelectorLimitMatchesThePublicSchema() throws Exception {
        java.nio.file.Path schema=java.nio.file.Path.of("schemas/att-execution-v2.schema.json");
        String atLimit=nativeSuiteQualifiedIdsRequest(att.testcase.TestCaseSuiteResolver.MAX_QUALIFIED_CASE_IDS);
        att.validation.JsonSchemaVerifier.verifyJson(schema,atLimit);
        assertEquals(att.testcase.TestCaseSuiteResolver.MAX_QUALIFIED_CASE_IDS,parse(atLimit).qualifiedCaseIds.size());

        String overLimit=nativeSuiteQualifiedIdsRequest(att.testcase.TestCaseSuiteResolver.MAX_QUALIFIED_CASE_IDS+1);
        assertThrows(IllegalArgumentException.class,()->parse(overLimit));
        assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,
                ()->att.validation.JsonSchemaVerifier.verifyJson(schema,overLimit));
    }
    @Test void nativeSuiteV2RejectsAllFalseAloneAndC1ControlTagsWithSchemaParity() throws Exception {
        java.nio.file.Path schema=java.nio.file.Path.of("schemas/att-execution-v2.schema.json");
        String allFalseAlone=requestV2("\"target\":{\"type\":\"nativeSuite\",\"definitionIds\":[\"fpp.transfer\"],\"selection\":{\"all\":false}}}");
        assertThrows(IllegalArgumentException.class,()->parse(allFalseAlone));
        assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,
                ()->att.validation.JsonSchemaVerifier.verifyJson(schema,allFalseAlone));

        String allTrue=requestV2("\"target\":{\"type\":\"nativeSuite\",\"definitionIds\":[\"fpp.transfer\"],\"selection\":{\"all\":true}}}");
        att.validation.JsonSchemaVerifier.verifyJson(schema,allTrue);
        assertTrue(parse(allTrue).nativeSuiteAll);

        String c1Tag=requestV2("\"target\":{\"type\":\"nativeSuite\",\"definitionIds\":[\"fpp.transfer\"],"
                +"\"selection\":{\"all\":true,\"tags\":[\"smoke\\u0080\"]}}}");
        assertThrows(IllegalArgumentException.class,()->parse(c1Tag));
        assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,
                ()->att.validation.JsonSchemaVerifier.verifyJson(schema,c1Tag));
    }
    private static String nativeSuiteQualifiedIdsRequest(int count) {
        StringBuilder ids=new StringBuilder();
        for(int index=0;index<count;index++) {
            if(index>0)ids.append(',');
            ids.append("\"fpp.transfer.case").append(index).append("\"");
        }
        return requestV2("\"target\":{\"type\":\"nativeSuite\",\"definitionIds\":[\"fpp.transfer\"],"
                +"\"selection\":{\"qualifiedCaseIds\":["+ids+"]}}");
    }
    @Test void nativeCaseV2SchemaIsRegisteredInSourceAndEmbeddedRuntimeCatalogs() throws Exception {
        java.nio.file.Path rootCatalog=java.nio.file.Path.of("schemas/catalog.yaml");
        java.nio.file.Path runtimeCatalog=java.nio.file.Path.of("att-engine/src/main/resources/att-schemas/catalog.yaml");
        assertEquals(java.nio.file.Files.readString(rootCatalog),java.nio.file.Files.readString(runtimeCatalog));
        assertTrue(java.nio.file.Files.readString(rootCatalog).contains("  att-execution/v2: att-execution-v2.schema.json"));
        assertTrue(java.nio.file.Files.readString(runtimeCatalog).contains("  att-execution/v2: att-execution-v2.schema.json"));
        assertEquals(java.nio.file.Files.readString(java.nio.file.Path.of("schemas/att-execution-v2.schema.json")),
                java.nio.file.Files.readString(java.nio.file.Path.of("att-engine/src/main/resources/att-schemas/att-execution-v2.schema.json")));
    }
    @Test void publishedScheduleSchemaDiscriminatesTriggersAndExecutionTypes() throws Exception {
        java.nio.file.Path schema=java.nio.file.Path.of("schemas/att-schedule-v2.schema.json");
        String base="{\"schemaVersion\":\"att-schedule/v2\",\"name\":\"Nightly\",\"packageId\":\"p\",";
        att.validation.JsonSchemaVerifier.verifyJson(schema,base+"\"executionType\":\"run\",\"draftId\":\"R123\",\"cron\":\"0 2 * * *\",\"timezone\":\"UTC\"}");
        att.validation.JsonSchemaVerifier.verifyJson(schema,base+"\"executionType\":\"load\",\"scenario\":\"load/nightly.yaml\",\"runAt\":\"2026-11-01T02:00:00Z\"}");
        for(String fields:List.of("\"executionType\":\"run\",\"scenario\":\"load/nightly.yaml\",\"runAt\":\"2026-11-01T02:00:00Z\"}",
                "\"executionType\":\"run\",\"draftId\":\"R123\",\"runAt\":\"2026-11-01T02:00:00Z\",\"cron\":\"0 2 * * *\",\"timezone\":\"UTC\"}"))
            assertThrows(att.validation.JsonSchemaVerifier.SchemaValidationException.class,()->att.validation.JsonSchemaVerifier.verifyJson(schema,base+fields));
    }
    @Test void legacySchedulesDefaultToLoadAndFutureDefinitionsFailClosed() throws Exception {
        LoadSchedule legacy=ServerRuntime.JSON.readValue("{\"id\":\"legacy\",\"scenario\":\"load/nightly.yaml\"}",LoadSchedule.class);
        assertEquals("load",legacy.effectiveJobType());
        legacy.jobType="future";assertThrows(IllegalArgumentException.class,legacy::effectiveJobType);
        legacy.jobType="run";legacy.schemaVersion="att-schedule/v3";
        assertThrows(IllegalArgumentException.class,legacy::effectiveJobType);
    }
    @Test void singleCaseUsesCaseBindingsAndNeverDebugInputs() throws Exception {
        RunDefinition caseRun=parse(request("\"target\":{\"type\":\"case\",\"id\":\"PAY-001\"},\"options\":{\"debug\":true}"));
        assertFalse(caseRun.resourceTarget);assertTrue(caseRun.debug);
        assertEquals("PAY-001",caseRun.workerInput.path("caseIds").get(0).asText());
        assertFalse(caseRun.workerInput.has("target"));assertFalse(caseRun.workerInput.has("inlineDebugInput"));
        assertThrows(IllegalArgumentException.class,()->parse(request("\"target\":{\"type\":\"case\",\"id\":\"PAY-001\"},\"input\":{}")));
    }
    @Test void preservesResourceValuesAndTheirPresenceWithoutCoercion() throws Exception {
        for(String type:List.of("template","flow","tool")) {
            String input="{\"schemaVersion\":\"att-debug/v1.2\",\"variables\":{\"nullValue\":null,\"literal\":\"${amount}\",\"amount\":100},\"args\":[\"--check\"]}";
            RunDefinition run=parse(request("\"target\":{\"type\":\""+type+"\",\"id\":\"payment\"},\"input\":"+input));
            assertTrue(run.resourceTarget);assertFalse(run.debug);
            assertEquals(ServerRuntime.JSON.readTree(input),run.definition.get("input"));
            assertFalse(run.definition.path("input").path("variables").has("omitted"));
        }
    }
    @Test void preservesExplicitBatchSelectorIntersection() throws Exception {
        String selection="{\"suites\":[\"testcase/payments.xlsx\"],\"caseIds\":[\"PAY-001\",\"PAY-002\"],\"tags\":[\"smoke\"],\"excludeTags\":[\"slow\"],\"rerunFailed\":true}";
        RunDefinition run=parse(request("\"selection\":"+selection));
        JsonNode expected=ServerRuntime.JSON.readTree(selection);
        expected.fieldNames().forEachRemaining(key->assertEquals(expected.get(key),run.workerInput.get(key)));
        assertFalse(run.workerInput.has("all"));
        assertEquals("testcase/sit",parse(request("\"selection\":{\"suiteDirectory\":\"testcase/sit\"}")).workerInput.path("suiteDirectory").asText());
    }
    @Test void rejectsAmbiguousOrIgnoredIntent() throws Exception {
        for(String body:List.of(
                "\"target\":{\"type\":\"case\",\"id\":\"A\"},\"selection\":{\"all\":true}",
                "\"selection\":{}", "\"selection\":{\"all\":\"true\"}",
                "\"selection\":{\"caseIds\":[\"A\",\"A\"]}",
                "\"selection\":{\"suites\":[\"a.xlsx\"],\"suiteDirectory\":\"testcase\"}",
                "\"selection\":{\"excludeTags\":[\"slow\"]}",
                "\"selection\":{\"all\":true},\"options\":{\"users\":10}",
                "\"selection\":{\"all\":true},\"principal\":\"admin\"",
                "\"target\":{\"type\":\"tool\",\"id\":\"t\"},\"input\":{},\"options\":{\"failFast\":true}"))
            assertThrows(IllegalArgumentException.class,()->parse(request(body)),body);
        RunDefinition v2Selection=parse(requestV2("\"selection\":{\"all\":true}"));
        assertFalse(v2Selection.nativeCaseTarget);assertTrue(v2Selection.workerInput.path("all").asBoolean());
    }

    @Test void retainedFrozenCasesUseTheResolvedBatchLimitWithoutChangingThePublicSelectorLimit() {
        var definition=ServerRuntime.JSON.createObjectNode();definition.put("schemaVersion",RunDefinition.VERSION);definition.put("executionType","run");definition.put("packageId","p");
        var cases=definition.putObject("selection").putArray("caseIds");for(int i=0;i<1001;i++)cases.add("suite.C"+i);
        assertThrows(IllegalArgumentException.class,()->RunDefinition.parse(definition));
        assertEquals(1001,RunDefinition.parseRetained(definition).workerInput.path("caseIds").size());
        for(int i=1001;i<=10000;i++)cases.add("suite.C"+i);
        assertThrows(IllegalArgumentException.class,()->RunDefinition.parseRetained(definition));
    }
}
