package att.server;

import org.apache.catalina.realm.MemoryRealm;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class ServerWarUiIT {
    @TempDir Path temp;

    @Test
    void packagedWarServesAuthenticatedUiAssetsUnderNonRootContext() throws Exception {
        Path war = Path.of(System.getProperty("att.server.war"));
        assertTrue(Files.isRegularFile(war), "Packaged server WAR must exist: " + war);

        Path users = Files.writeString(temp.resolve("tomcat-users.xml"),
                "<tomcat-users><role rolename=\"ATT_USER\"/><user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/></tomcat-users>");
        Path packages = Files.createDirectories(temp.resolve("packages"));
        Path config = Files.writeString(temp.resolve("server.yaml"),
                "server:\n  dataDir: " + temp.resolve("data") + "\n  authenticationRequired: true\n"
                        + "workers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\n"
                        + "packages:\n  allowedRoots:\n    - " + packages + "\n  entries: {}\n");
        String oldConfig = System.getProperty("att.server.config");
        System.setProperty("att.server.config", config.toString());

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(temp.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        MemoryRealm realm = new MemoryRealm();
        realm.setPathname(users.toString());
        tomcat.getEngine().setRealm(realm);
        tomcat.addWebapp("/tools/att", war.toString());
        try {
            tomcat.start();
            int port = tomcat.getConnector().getLocalPort();
            assertEquals(401, get(port, "/tools/att/ui/index.html", null));
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    "att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (String asset : new String[]{"index.html", "app.js", "app.css"}) {
                HttpURLConnection connection = open(port, "/tools/att/ui/" + asset, auth);
                try {
                    assertEquals(200, connection.getResponseCode(), asset);
                    assertEquals("nosniff", connection.getHeaderField("X-Content-Type-Options"));
                    assertNotNull(connection.getHeaderField("Content-Security-Policy"));
                    String body = new String(connection.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (asset.equals("app.js")) {
                        assertTrue(body.contains("new URL('../api/v1/', document.baseURI)"),
                                "API URL must work below a non-root context path");
                    }
                } finally {
                    connection.disconnect();
                }
            }
        } finally {
            try { tomcat.stop(); } finally { tomcat.destroy(); }
            if (oldConfig == null) System.clearProperty("att.server.config");
            else System.setProperty("att.server.config", oldConfig);
        }
    }

    private static int get(int port, String path, String authorization) throws Exception {
        HttpURLConnection connection = open(port, path, authorization);
        try { return connection.getResponseCode(); }
        finally { connection.disconnect(); }
    }

    private static HttpURLConnection open(int port, String path, String authorization) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        return connection;
    }
}
