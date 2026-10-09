package att.server;

import org.apache.catalina.realm.MemoryRealm;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class ServerWarUiIT {
    @TempDir Path temp;

    @Test
    void packagedWarServesAuthenticatedUiAssetsUnderNonRootContext() throws Exception {
        Path war = Path.of(System.getProperty("att.server.war"));
        assertTrue(Files.isRegularFile(war), "Packaged server WAR must exist: " + war);
        Path explodedWar = unpackWar(war, temp.resolve("exploded-war"));

        Path users = Files.writeString(temp.resolve("tomcat-users.xml"),
                "<tomcat-users><role rolename=\"ATT_USER\"/><user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/></tomcat-users>");
        Path packages = Files.createDirectories(temp.resolve("packages"));
        Path dummyPackage = Files.createDirectories(packages.resolve("dummy"));
        Path config = Files.writeString(temp.resolve("server.yaml"),
                "server:\n  dataDir: " + temp.resolve("data") + "\n  authenticationRequired: true\n"
                        + "workers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\n"
                        + "packages:\n  allowedRoots:\n    - " + packages + "\n  entries:\n    dummy: " + dummyPackage + "\n");
        String oldConfig = System.getProperty("att.server.config");
        System.setProperty("att.server.config", config.toString());

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(temp.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        MemoryRealm realm = new MemoryRealm();
        realm.setPathname(users.toString());
        tomcat.getEngine().setRealm(realm);
        tomcat.addWebapp("/tools/att", explodedWar.toString());
        try {
            tomcat.start();
            int port = tomcat.getConnector().getLocalPort();
            assertEquals(401, get(port, "/tools/att/ui/index.html", null));
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    "att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertJobDeepLink(port, auth);

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



    private static Path unpackWar(Path war, Path destination) throws Exception {
        Path root = Files.createDirectories(destination).toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(war))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                assertTrue(target.startsWith(root), "WAR entry must stay inside the exploded directory");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
        return root;
    }

    private static void assertJobDeepLink(int port, String authorization) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(401, get(port, "/tools/att/ui/jobs/J10045", null),
                    "Deep links must use the same container authentication as the UI");
            HttpURLConnection route = open(port, "/tools/att/ui/jobs/J10045", authorization);
            route.setInstanceFollowRedirects(false);
            try {
                assertEquals(302, route.getResponseCode());
                assertEquals("/tools/att/ui/index.html#/jobs/J10045", route.getHeaderField("Location"));
            } finally {
                route.disconnect();
            }
            assertEquals(200, get(port, "/tools/att/ui/index.html", authorization),
                    "Reopening the canonical job route must load the UI shell");
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
