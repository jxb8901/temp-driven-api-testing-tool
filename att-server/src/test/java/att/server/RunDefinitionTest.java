package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RunDefinitionTest {
    private static RunDefinition parse(String body) throws Exception {return RunDefinition.parse(ServerRuntime.JSON.readTree(body));}
    private static String request(String body){return "{\"schemaVersion\":\"att-execution/v1\",\"executionType\":\"run\",\"packageId\":\"payments\","+body+"}";}

    @Test void publishedSchemaAcceptsTheSameTargetAndSelectionShapes() throws Exception {
        java.nio.file.Path schema=java.nio.file.Path.of("schemas/att-execution-v1.schema.json");
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
        assertThrows(IllegalArgumentException.class,()->parse(request("\"selection\":{\"all\":true}").replace("att-execution/v1","att-execution/v2")));
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
