package att.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JobExecutionSpecTest {
    private com.fasterxml.jackson.databind.node.ObjectNode run() {
        var value=ServerRuntime.JSON.createObjectNode();value.put("schemaVersion",RunDefinition.VERSION);value.put("executionType","run");value.put("packageId","p");
        value.putObject("selection").putArray("caseIds").add("suite.C1");return value;
    }
    @Test void schemaAndPackageRelativeSourceAreStrict() throws Exception {
        for(String path:new String[]{"/srv/private/file.yaml","../file.yaml","C:/private/file.yaml","dir/../file.yaml","dir\\file.yaml"})
            assertThrows(IllegalArgumentException.class,()->new JobExecutionSpec(run(),"a".repeat(64),path));
        JobExecutionSpec spec=new JobExecutionSpec(run(),"a".repeat(64),"testcase/cases.xlsx");
        var unknown=spec.value.deepCopy();unknown.put("schemaVersion","future");
        String unsupported=unknown.toString();assertThrows(IllegalArgumentException.class,()->JobExecutionSpec.parse(unsupported));
        unknown=spec.value.deepCopy();unknown.put("packageRoot","/private");String unexpected=unknown.toString();
        assertThrows(IllegalArgumentException.class,()->JobExecutionSpec.parse(unexpected));
    }
    @Test void workerTransportCannotEnterTheBrowserDefinition() {
        var badRun=run();badRun.put("packageRoot","/private");
        assertThrows(IllegalArgumentException.class,()->new JobExecutionSpec(badRun,"a".repeat(64),null));
        var definition=ServerRuntime.JSON.createObjectNode();definition.put("executionType","load");definition.put("packageId","p");definition.putObject("scenario");definition.put("config","/private/config.yaml");
        var load=definition;assertThrows(IllegalArgumentException.class,()->new JobExecutionSpec(load,"a".repeat(64),null));
    }
    @Test void definitionsAreCopiedAndRetentionIsByteBounded() throws Exception {
        var definition=run();JobExecutionSpec spec=new JobExecutionSpec(definition,"a".repeat(64),null);
        definition.put("packageId","changed");spec.definition().put("packageId","changed");
        assertEquals("p",JobExecutionSpec.parse(spec.json()).definition().path("packageId").asText());
        assertThrows(IllegalArgumentException.class,()->JobExecutionSpec.parse(" ".repeat(JobExecutionSpec.MAX_BYTES+1)));
        var large=ServerRuntime.JSON.createObjectNode();large.put("executionType","load");large.put("packageId","p");large.putObject("scenario").put("description","漢".repeat(JobExecutionSpec.MAX_BYTES/2));
        JobExecutionSpec overLimit=new JobExecutionSpec(large,"a".repeat(64),null);
        assertThrows(IllegalArgumentException.class,overLimit::json);
    }
}
