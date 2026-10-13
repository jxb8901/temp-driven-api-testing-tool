package att.remote;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RemoteCommandAutoAuthTest {
    @Test void directUrlRunsAnonymousAndProbesProtectedApiBeforePublicVersion() throws Exception {
        List<String> requests=new ArrayList<String>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/att/api/v1/",exchange->{
            requests.add(exchange.getRequestURI().getPath());
            String body;
            if(exchange.getRequestURI().getPath().endsWith("/version"))body="{\"version\":\"4.1.2\",\"apiVersion\":\"1\"}";
            else if(exchange.getRequestURI().getPath().endsWith("/packages"))body="{\"items\":[]}";
            else body="{}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/att";
            assertEquals(0,RemoteCommand.run(new String[]{"--server",url,"packages"}));
            assertEquals("/att/api/v1/packages",requests.get(0),"Auto mode probes a protected, side-effect-free API before public endpoints");
            assertEquals("/att/api/v1/version",requests.get(1));
            assertTrue(requests.stream().allMatch(path->path.startsWith("/att/api/v1/")),"The direct URL context path is preserved");
        } finally {server.stop(0);}
    }

    @Test void publicVersionDoesNotSuppressAProtectedApiBasicChallenge() throws Exception {
        List<String> requests=new ArrayList<String>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/att/api/v1/",exchange->{
            requests.add(exchange.getRequestURI().getPath());
            if(exchange.getRequestURI().getPath().endsWith("/version")) {
                byte[] bytes="{\"version\":\"4.1.2\",\"apiVersion\":\"1\"}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } else {exchange.getResponseHeaders().set("WWW-Authenticate","Basic realm=\"ATT\"");exchange.sendResponseHeaders(401,-1);}
            exchange.close();
        });
        server.start();
        try {
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/att";
            assertEquals(4,RemoteCommand.run(new String[]{"--server",url,"version"}),"A Basic challenge over HTTP is rejected before credentials are requested");
            assertEquals(java.util.Arrays.asList("/att/api/v1/packages"),requests);
        } finally {server.stop(0);}
    }
}
