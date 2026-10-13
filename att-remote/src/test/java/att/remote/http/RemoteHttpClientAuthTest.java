package att.remote.http;

import att.remote.RemoteException;
import att.remote.config.ServerProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RemoteHttpClientAuthTest {
    private static final String AUTH="Basic "+Base64.getEncoder().encodeToString("att:secret".getBytes(StandardCharsets.UTF_8));

    @Test void autoAuthSendsCredentialsOnlyAfterAProtectedBasicChallenge() throws Exception {
        FakeServer server=new FakeServer(connection -> {
            assertFalse(connection.getInstanceFollowRedirects());
            if(AUTH.equals(connection.getRequestProperty("Authorization")))return response(200,"{\"items\":[]}");
            return response(401,"{\"error\":{\"summary\":\"login required\"}}","WWW-Authenticate","Basic realm=\"ATT\"");
        });
        AtomicInteger prompts=new AtomicInteger();
        RemoteHttpClient client=client("auto",server,()->{prompts.incrementAndGet();return new RemoteHttpClient.Credentials("att","secret");});
        JsonNode result=client.get("/packages");
        assertTrue(result.path("items").isArray());assertEquals(2,server.requests.size());assertEquals(1,prompts.get());
        assertNull(server.requests.get(0).getRequestProperty("Authorization"));assertEquals(AUTH,server.requests.get(1).getRequestProperty("Authorization"));
    }

    @Test void autoAuthDoesNotSendConfiguredCredentialsToAnAnonymousEndpoint() throws Exception {
        FakeServer server=new FakeServer(connection->{assertNull(connection.getRequestProperty("Authorization"));return response(200,"{\"items\":[]}");});
        AtomicInteger prompts=new AtomicInteger();
        RemoteHttpClient client=new RemoteHttpClient(new ServerProfile("sit","https://att.example/att","att", "ATT_PASSWORD","auto"),null,"att",
                ()->{prompts.incrementAndGet();return new RemoteHttpClient.Credentials("att","secret");},server::open);
        client.get("/packages");assertEquals(1,server.requests.size());assertEquals(0,prompts.get());
    }

    @Test void autoAuthHandlesAProtectedEndpointAfterAnAnonymousProbe() throws Exception {
        FakeServer server=new FakeServer(connection -> {
            if(connection.getURL().getPath().endsWith("/packages"))return response(200,"{\"items\":[]}");
            if(AUTH.equals(connection.getRequestProperty("Authorization")))return response(200,"{\"items\":[]}");
            return response(401,"{}","WWW-Authenticate","Basic realm=\"Explorer\"");
        });
        AtomicInteger prompts=new AtomicInteger();RemoteHttpClient client=client("auto",server,()->{prompts.incrementAndGet();return new RemoteHttpClient.Credentials("att","secret");});
        client.get("/packages");client.get("/packages/p/resources?type=template");
        assertEquals(1,prompts.get());assertEquals(3,server.requests.size());
        assertNull(server.requests.get(0).getRequestProperty("Authorization"));
        assertNull(server.requests.get(1).getRequestProperty("Authorization"));
        assertEquals(AUTH,server.requests.get(2).getRequestProperty("Authorization"));
    }

    @Test void autoAuthDoesNotRetryPostAfterALaterBasicChallenge() throws Exception {
        FakeServer server=new FakeServer(connection -> response(401,"{}","WWW-Authenticate","Basic realm=\"Jobs\""));
        AtomicInteger prompts=new AtomicInteger();RemoteHttpClient client=client("auto",server,()->{prompts.incrementAndGet();return new RemoteHttpClient.Credentials("att","secret");});
        ObjectNode body=RemoteHttpClient.JSON.createObjectNode();body.put("packageId","default");
        RemoteException failure=assertThrows(RemoteException.class,()->client.post("/jobs/run",body));
        assertTrue(failure.getMessage().contains("did not retry"));assertEquals(0,prompts.get());assertEquals(1,server.requests.size());
    }

    @Test void autoAuthRejectsUnsupportedChallengesWithoutPrompting() throws Exception {
        FakeServer server=new FakeServer(connection -> response(401,"{}","WWW-Authenticate","Negotiate"));
        AtomicInteger prompts=new AtomicInteger();RemoteHttpClient client=client("auto",server,()->{prompts.incrementAndGet();return new RemoteHttpClient.Credentials("att","secret");});
        RemoteException failure=assertThrows(RemoteException.class,()->client.get("/packages"));
        assertTrue(failure.getMessage().contains("unsupported authentication scheme"));assertEquals(0,prompts.get());assertEquals(1,server.requests.size());
    }

    @Test void autoAuthDoesNotGuessWhenAnApplicationReturnsUnauthorizedWithoutAChallenge() throws Exception {
        FakeServer server=new FakeServer(connection->response(401,"{}"));
        AtomicInteger prompts=new AtomicInteger();RemoteHttpClient client=client("auto",server,()->{prompts.incrementAndGet();return new RemoteHttpClient.Credentials("att","secret");});
        RemoteException failure=assertThrows(RemoteException.class,()->client.get("/packages"));
        assertTrue(failure.getMessage().contains("without a supported Basic challenge"));assertEquals(0,prompts.get());assertEquals(1,server.requests.size());
    }

    @Test void explicitNoneNeverSendsEvenConfiguredCredentials() throws Exception {
        FakeServer server=new FakeServer(connection->response(401,"{}","WWW-Authenticate","Basic realm=\"ATT\""));
        RemoteHttpClient client=new RemoteHttpClient(new ServerProfile("sit","https://att.example/att","att","ATT_PASSWORD","none"),"secret","att",null,server::open);
        RemoteException failure=assertThrows(RemoteException.class,()->client.get("/packages"));
        assertTrue(failure.getMessage().contains("anonymous access was selected"));assertNull(server.requests.get(0).getRequestProperty("Authorization"));
    }

    @Test void autoAuthNeverSendsCredentialsAfterAnHttpChallenge() throws Exception {
        FakeServer server=new FakeServer(connection -> response(401,"{}","WWW-Authenticate","Basic realm=\"ATT\""));
        AtomicInteger prompts=new AtomicInteger();RemoteHttpClient client=new RemoteHttpClient(new ServerProfile("direct","http://att.example/att",null,null,"auto"),null,null,
                ()->{prompts.incrementAndGet();return new RemoteHttpClient.Credentials("att","secret");},server::open);
        RemoteException failure=assertThrows(RemoteException.class,()->client.get("/packages"));
        assertTrue(failure.getMessage().contains("HTTPS is required"));assertEquals(0,prompts.get());
        assertNull(server.requests.get(0).getRequestProperty("Authorization"));assertEquals(1,server.requests.size());
    }

    @Test void redirectsAreNotFollowedForCredentialedRequests() throws Exception {
        FakeServer server=new FakeServer(connection -> response(302,"","Location","https://other.example/att/api/v1/packages"));
        RemoteHttpClient client=new RemoteHttpClient(new ServerProfile("sit","https://att.example/att",null,null,"basic"),"secret","att",null,server::open);
        RemoteException failure=assertThrows(RemoteException.class,()->client.get("/packages"));
        assertTrue(failure.getMessage().contains("HTTP 302"));assertEquals(1,server.requests.size());
        assertEquals(AUTH,server.requests.get(0).getRequestProperty("Authorization"));assertFalse(server.requests.get(0).getInstanceFollowRedirects());
    }

    private static RemoteHttpClient client(String type,FakeServer server,RemoteHttpClient.CredentialProvider provider)throws RemoteException {
        return new RemoteHttpClient(new ServerProfile("sit","https://att.example/att",null,null,type),null,null,provider,server::open);
    }
    private static Response response(int status,String body,String... headers) {
        Map<String,String> values=new LinkedHashMap<String,String>();for(int i=0;i+1<headers.length;i+=2)values.put(headers[i],headers[i+1]);
        return new Response(status,body,values);
    }
    private static final class FakeServer {
        final List<FakeConnection> requests=new ArrayList<FakeConnection>();final Responder responder;
        FakeServer(Responder responder){this.responder=responder;}
        HttpURLConnection open(URL url){FakeConnection connection=new FakeConnection(url,this);requests.add(connection);return connection;}
    }
    private interface Responder { Response respond(FakeConnection connection); }
    private static final class Response {
        final int status;final byte[] body;final Map<String,String> headers;
        Response(int status,String body,Map<String,String> headers){this.status=status;this.body=body.getBytes(StandardCharsets.UTF_8);this.headers=headers;}
    }
    private static final class FakeConnection extends HttpURLConnection {
        private final FakeServer server;private final ByteArrayOutputStream requestBody=new ByteArrayOutputStream();private Response response;
        FakeConnection(URL url,FakeServer server){super(url);this.server=server;}
        @Override public void connect(){if(!connected){response=server.responder.respond(this);connected=true;}}
        @Override public void disconnect(){connected=false;}
        @Override public boolean usingProxy(){return false;}
        @Override public OutputStream getOutputStream(){return requestBody;}
        @Override public int getResponseCode()throws IOException{connect();return response.status;}
        @Override public String getHeaderField(String name){connect();return response.headers.get(name);}
        @Override public InputStream getInputStream()throws IOException{connect();if(response.status>=400)throw new IOException("HTTP "+response.status);return new ByteArrayInputStream(response.body);}
        @Override public InputStream getErrorStream(){connect();return response.status>=400?new ByteArrayInputStream(response.body):null;}
    }
}
