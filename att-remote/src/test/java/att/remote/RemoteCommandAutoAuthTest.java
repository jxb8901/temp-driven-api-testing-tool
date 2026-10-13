package att.remote;

import att.remote.config.ServerProfile;
import att.remote.http.RemoteHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RemoteCommandAutoAuthTest {
    @TempDir Path temp;

    @Test void directUrlPackagesRunsAnonymousWithoutASeparateProbe() throws Exception {
        List<String> requests=new ArrayList<String>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/att/api/v1/",exchange->{
            requests.add(exchange.getRequestURI().getPath());
            String body=exchange.getRequestURI().getPath().endsWith("/version")
                    ?"{\"version\":\"4.1.3\",\"apiVersion\":\"1\"}":"{\"items\":[]}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/att";
            assertEquals(0,RemoteCommand.run(new String[]{"--server",url,"packages"}));
            assertEquals(java.util.Arrays.asList("/att/api/v1/version","/att/api/v1/packages"),requests);
            assertTrue(requests.stream().allMatch(path->path.startsWith("/att/api/v1/")),"The direct URL context path is preserved");
        } finally {server.stop(0);}
    }

    @Test void publicCommandsDoNotDependOnThePackagesEndpoint() throws Exception {
        List<String> requests=new ArrayList<String>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/att/api/v1/",exchange->{
            String path=exchange.getRequestURI().getPath();requests.add(path);
            if(path.endsWith("/version")) {
                byte[] bytes="{\"version\":\"4.1.3\",\"apiVersion\":\"1\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } else if(path.endsWith("/health")) {
                byte[] bytes="{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } else exchange.sendResponseHeaders(403,-1);
            exchange.close();
        });
        server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/att";
            assertEquals(0,RemoteCommand.run(new String[]{"--server",url,"version"}));
            assertEquals(0,RemoteCommand.run(new String[]{"--server",url,"ping"}));
            assertEquals(java.util.Arrays.asList("/att/api/v1/version","/att/api/v1/version","/att/api/v1/health"),requests);
        } finally {server.stop(0);}
    }

    @Test void autoAuthRunReadsNamedProfileSecretAfterCredentialChallenge() throws Exception {
        Path profiles=Files.write(temp.resolve("servers.yaml"),("servers:\n  ci:\n    url: https://att.example/att\n    auth:\n      type: auto\n      username: ci-user\n      passwordEnv: CI_SECRET\n").getBytes(StandardCharsets.UTF_8));
        Map<String,String> environment=new HashMap<String,String>();environment.put("CI_SECRET","secret-from-ci");
        RunState state=new RunState();
        int result=RemoteCommand.run(new String[]{"--server","ci","run","payments","--all","--detach"},environment,profiles,null,factory(state));
        assertEquals(0,result);
        assertEquals("ci-user",state.credentials.username);
        assertEquals("secret-from-ci",state.credentials.password);
        assertEquals(java.util.Arrays.asList("GET /packages","GET /version","POST /jobs/run"),state.requests);
    }

    @Test void directUrlAutoAuthPromptsForCredentialsAndReportsNonInteractiveFailure() throws Exception {
        RunState state=new RunState();final List<String> prompts=new ArrayList<String>();
        RemoteCommand.CredentialPrompt prompt=new RemoteCommand.CredentialPrompt() {
            @Override public String readLine(String value){prompts.add(value);return "url-user";}
            @Override public char[] readPassword(String value){prompts.add(value);return "url-secret".toCharArray();}
        };
        int result=RemoteCommand.run(new String[]{"--server","https://att.example/att","packages"},new HashMap<String,String>(),temp.resolve("absent.yaml"),prompt,factory(state));
        assertEquals(0,result);
        assertEquals(java.util.Arrays.asList("Username: ","Password: "),prompts);
        assertEquals("url-user",state.credentials.username);
        assertEquals("url-secret",state.credentials.password);

        ByteArrayOutputStream error=new ByteArrayOutputStream();PrintStream previous=System.err;
        try {
            System.setErr(new PrintStream(error,true,"UTF-8"));
            int noTerminal=RemoteCommand.run(new String[]{"--server","https://att.example/att","packages"},new HashMap<String,String>(),temp.resolve("absent.yaml"),null,factory(new RunState()));
            assertEquals(4,noTerminal);
        } finally {System.setErr(previous);}
        assertTrue(error.toString("UTF-8").contains("no secure terminal is available"),error.toString("UTF-8"));
        assertTrue(error.toString("UTF-8").contains("named profile with passwordEnv"),error.toString("UTF-8"));
    }

    @Test void helpExplainsHttpsForBasicAuthentication() throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream();PrintStream previous=System.out;
        try {
            System.setOut(new PrintStream(output,true,"UTF-8"));
            assertEquals(0,RemoteCommand.run(new String[]{"help"}));
        } finally {System.setOut(previous);}
        assertTrue(output.toString("UTF-8").contains("Basic authentication, including credentials entered for a direct URL, requires HTTPS"));
    }

    private static RemoteCommand.RemoteClientFactory factory(RunState state) {
        return (profile,password,username,provider)->new RemoteHttpClient(profile,password,username,provider) {
            @Override public JsonNode get(String path)throws RemoteException {
                state.requests.add("GET "+path);
                if("/packages".equals(path)&&provider!=null)state.credentials=provider.credentials();
                if("/version".equals(path))return JSON.createObjectNode().put("version","4.1.3").put("apiVersion","1");
                if("/health".equals(path))return JSON.createObjectNode().put("status","UP");
                if("/packages".equals(path))return JSON.createObjectNode().putArray("items");
                return JSON.createObjectNode();
            }
            @Override public JsonNode post(String path,JsonNode body) {
                state.requests.add("POST "+path);
                return JSON.createObjectNode().put("jobId","J0123456789ABCDEF").put("status","QUEUED");
            }
        };
    }

    private static final class RunState {
        final List<String> requests=new ArrayList<String>();
        RemoteHttpClient.Credentials credentials;
    }
}
