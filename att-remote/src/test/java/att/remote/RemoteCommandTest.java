package att.remote;

import att.remote.config.ServerProfile;
import att.remote.http.RemoteHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteCommandTest {
    private static final String JOB="J0123456789ABCDEF";

    @Test void serializesRunDebugLoadAndValidateAsLogicalPackageRequests() throws Exception {
        MockClient client=new MockClient();
        RemoteCommand.submit(client,Arrays.asList("run","payments","--suite","smoke.xlsx","--tag","smoke","--env","SIT","--detach"),"human","run");
        JsonNode run=client.lastBody;assertEquals("payments",run.path("packageId").asText());assertEquals("smoke.xlsx",run.path("suites").get(0).asText());assertEquals("SIT",run.path("environment").asText());
        RemoteCommand.submit(client,Arrays.asList("debug","payments","flow","PAYMENT.submit","--set","vars.Channel=WEB","--detach"),"human","debug");
        JsonNode debug=client.lastBody;assertEquals("flow",debug.path("target").path("type").asText());assertEquals("PAYMENT.submit",debug.path("target").path("id").asText());
        RemoteCommand.submit(client,Arrays.asList("load","payments","load/payment.yaml","--users","2","--duration","30s","--detach"),"human","load");
        JsonNode load=client.lastBody;assertEquals("load/payment.yaml",load.path("scenario").asText());assertEquals("2",load.path("load").path("users").asText());
        RemoteCommand.submit(client,Arrays.asList("validate","payments","--selected","--suite","smoke.xlsx","--detach"),"human","validate");
        JsonNode validate=client.lastBody;assertEquals("selected",validate.path("validationScope").asText());
        for(JsonNode request:client.requests){assertFalse(request.has("packageRoot"));assertFalse(request.has("outputDirectory"));assertFalse(request.has("SERVER_DATA_DIR"));}
    }

    @Test void resumesEventsWithLastIdAndNeverResubmitsJob() throws Exception {
        MockClient client=new MockClient();int code=RemoteCommand.follow(client,JOB,"json");
        assertEquals(0,code);assertEquals(Arrays.asList(null,"1"),client.cursors);assertEquals(0,client.posts);
    }

    @Test void rejectsClientFilesystemPathsBeforePosting() throws Exception {
        MockClient client=new MockClient();
        assertThrows(IllegalArgumentException.class,()->RemoteCommand.submit(client,Arrays.asList("run","payments","--suite","../secret.xlsx","--detach"),"human","run"));
        assertEquals(0,client.posts);
    }

    private static final class MockClient extends RemoteHttpClient {
        JsonNode lastBody;final java.util.ArrayList<JsonNode> requests=new java.util.ArrayList<JsonNode>();final java.util.ArrayList<String> cursors=new java.util.ArrayList<String>();
        int posts,eventOpens;MockClient()throws RemoteException{super(new ServerProfile("mock","https://example.invalid",null,null,false),null);}
        @Override public JsonNode post(String path,JsonNode body)throws RemoteException {posts++;lastBody=body;requests.add(body.deepCopy());return RemoteHttpClient.JSON.createObjectNode().put("jobId",JOB).put("status","QUEUED");}
        @Override public JsonNode get(String path)throws RemoteException {
            if(path.endsWith("/result")){ObjectNode result=RemoteHttpClient.JSON.createObjectNode();result.set("job",RemoteHttpClient.JSON.createObjectNode().put("jobId",JOB).put("status","PASS").put("exitCode",0));result.set("result",RemoteHttpClient.JSON.createObjectNode().put("status","PASS").put("exitCode",0));return result;}
            return RemoteHttpClient.JSON.createObjectNode().put("jobId",JOB).put("status","QUEUED");
        }
        @Override public HttpURLConnection openEvents(String path,String lastId)throws RemoteException {
            cursors.add(lastId);eventOpens++;String body=eventOpens==1?
                    "id: 1\nevent: status\ndata: {\"status\":\"RUNNING\"}\n\n":
                    "id: 2\nevent: result\ndata: {\"jobId\":\""+JOB+"\",\"status\":\"PASS\",\"exitCode\":0}\n\n";
            try{return new StreamConnection(body);}catch(Exception e){throw new RemoteException("mock stream setup failed",e);}
        }
    }
    private static final class StreamConnection extends HttpURLConnection {
        private final byte[] body;StreamConnection(String body)throws Exception{super(new URL("http://localhost/events"));this.body=body.getBytes(StandardCharsets.UTF_8);}
        @Override public void disconnect(){}
        @Override public boolean usingProxy(){return false;}
        @Override public void connect(){}
        @Override public int getResponseCode(){return 200;}
        @Override public java.io.InputStream getInputStream(){return new ByteArrayInputStream(body);}
    }
}
