package att.remote.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import att.remote.http.RemoteHttpClient;

public final class RemoteRenderer {
    private RemoteRenderer() { }
    public static void event(String format,String jobId,String type,JsonNode data) {
        if("json".equals(format))return;
        if("status".equals(type))System.out.println("Job "+jobId+": "+data.path("status").asText("UNKNOWN"));
        else if("progress".equals(type)||"log".equals(type)) {
            String message=data.path("message").asText("");String status=data.path("status").asText("");String caseId=data.path("caseId").asText("");
            if(!caseId.isEmpty()||!status.isEmpty()||!message.isEmpty())System.out.println((caseId.isEmpty()?"":caseId+" ")+(status.isEmpty()?"":status+" ")+message);
        } else if("diagnostic".equals(type))System.err.println(data.path("summary").asText(data.path("message").asText("ATT Server diagnostic")));
    }
    public static void result(String format,JsonNode value,int exitCode) throws Exception {
        ObjectNode output=RemoteHttpClient.JSON.createObjectNode();output.put("jobId",value.path("job").path("jobId").asText());output.put("status",value.path("job").path("status").asText());output.put("exitCode",exitCode);
        if(value.has("result"))output.set("result",value.get("result"));if(value.has("diagnostic"))output.set("diagnostic",value.get("diagnostic"));
        if("json".equals(format))System.out.println(RemoteHttpClient.JSON.writeValueAsString(output));
        else {
            String id=output.path("jobId").asText();String status=output.path("status").asText();JsonNode result=output.path("result");
            System.out.println("Result: "+status);
            String summary=result.path("summary").asText("");if(!summary.isEmpty())System.out.println(summary);
            System.out.println("Job: "+id);
            if(value.path("diagnostic").isObject())System.err.println(value.path("diagnostic").path("summary").asText("ATT Server diagnostic"));
        }
    }
}
