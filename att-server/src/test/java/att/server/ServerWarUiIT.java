package att.server;

import org.apache.catalina.core.StandardHost;
import org.apache.catalina.realm.MemoryRealm;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ServerWarUiIT {
    @TempDir Path temp;

    @Test
    void packagedWarServesAuthenticatedUiAssetsUnderNonRootContext() throws Exception {
        Path war = Path.of(System.getProperty("att.server.war"));
        assertTrue(Files.isRegularFile(war), "Packaged server WAR must exist: " + war);
        Path localHome=Files.createDirectories(temp.resolve("att-home"));
        Path localLib=Files.createDirectories(localHome.resolve("lib"));
        writeWorkerRuntime(localLib.resolve("att-local-"+ServerBuildInfo.PRODUCT+".jar"));

        assertServerApplicationJar(war);

        Path users = Files.writeString(temp.resolve("tomcat-users.xml"),
                "<tomcat-users><role rolename=\"ATT_USER\"/><role rolename=\"ATT_ADMIN\"/><user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/>"
                        +"<user username=\"schedule-admin\" password=\"secret\" roles=\"ATT_USER,ATT_ADMIN\"/></tomcat-users>");
        Path packages = Files.createDirectories(temp.resolve("packages"));
        Path dummyPackage = Files.createDirectories(packages.resolve("dummy"));
        Path config = Files.writeString(temp.resolve("server.yaml"),
                "server:\n  dataDir: " + temp.resolve("data") + "\n  authenticationRequired: true\n  scheduling:\n    enabled: true\n    adminRole: ATT_ADMIN\n"
                        + "workers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\n"
                        + "packages:\n  allowedRoots:\n    - " + packages + "\n  entries:\n    dummy: " + dummyPackage + "\n");
        String oldConfig = System.getProperty("att.server.config");
        String oldHome = System.getProperty("att.home");
        System.setProperty("att.server.config", config.toString());
        System.setProperty("att.home", localHome.toString());

        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(temp.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        MemoryRealm realm = new MemoryRealm();
        realm.setPathname(users.toString());
        tomcat.getEngine().setRealm(realm);
        ((StandardHost) tomcat.getHost()).setUnpackWARs(false);
        tomcat.addWebapp("/tools/att", war.toString());
        try {
            tomcat.start();
            int port = tomcat.getConnector().getLocalPort();
            assertEquals(401, get(port, "/tools/att/ui/index.html", null));
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    "att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String scheduleAdminAuth="Basic "+Base64.getEncoder().encodeToString("schedule-admin:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(401,get(port,"/tools/att/api/v1/load-schedules",null));
            assertEquals(403,get(port,"/tools/att/api/v1/load-schedules",auth),"ordinary ATT_USER role must not manage schedules");
            assertEquals(200,get(port,"/tools/att/api/v1/load-schedules",scheduleAdminAuth),"configured schedule admin role may list schedules");
            String previewPath="/tools/att/api/v1/load-schedules/preview";
            String previewBody="{\"timezone\":\"UTC\",\"cron\":\"0 2 * * *\"}";
            assertEquals(403,postJson(port,previewPath,scheduleAdminAuth,"https://attacker.example",previewBody),"cross-origin schedule mutations must be rejected");
            assertEquals(403,postJson(port,previewPath,scheduleAdminAuth,"null",previewBody),"opaque Origins must be rejected");
            assertEquals(200,postJson(port,previewPath,scheduleAdminAuth,"http://127.0.0.1:"+port,previewBody),"same-origin schedule preview may proceed");
            assertEquals(403,delete(port,"/tools/att/api/v1/load-schedules/unknown",scheduleAdminAuth,"https://attacker.example"),"cross-origin deletes must be rejected before schedule lookup");
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
            if (oldHome == null) System.clearProperty("att.home");
            else System.setProperty("att.home", oldHome);
        }
    }



    private static void assertServerApplicationJar(Path war) throws Exception {
        try (JarFile archive = new JarFile(war.toFile())) {
            JarEntry application = archive.getJarEntry("WEB-INF/lib/att-server-"+ServerBuildInfo.PRODUCT+".jar");
            assertNotNull(application, "WAR must contain the combined ATT Server application jar");
            assertNull(archive.getJarEntry("WEB-INF/classes/att/server/ApiServlet.class"),
                    "ATT application classes must not be duplicated in WEB-INF/classes");
            assertFalse(archive.stream().map(JarEntry::getName).anyMatch(name ->
                    name.matches("WEB-INF/lib/att-(server-api|web|worker|engine)-[^/]+\\.jar")),
                    "ATT module jars must be combined into the versioned att-server jar");
            Set<String> entries = new HashSet<>();
            try (JarInputStream app = new JarInputStream(archive.getInputStream(application))) {
                JarEntry entry;
                while ((entry = app.getNextJarEntry()) != null) entries.add(entry.getName());
            }
            assertTrue(entries.contains("att/server/ApiServlet.class"));
            assertTrue(entries.contains("att/server/api/ServerApi.class"));
            assertTrue(entries.contains("META-INF/resources/ui/index.html"));
            assertTrue(entries.contains("att-build.properties"));
            assertFalse(entries.contains("att/worker/WorkerMain.class"),
                    "Worker classes belong only in the Local runtime jar");
        }
    }

    private static void writeWorkerRuntime(Path jarPath) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (String name : new String[] {
                    "att/worker/WorkerMain.class",
                    "att/server/api/WorkerRequest.class",
                    "att/server/api/internal/DiagnosticSanitizer.class",
                    "att-build.properties"
            }) {
                jar.putNextEntry(new JarEntry(name));
                if (name.equals("att-build.properties"))
                    jar.write("att.version=4.1.2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                else
                    jar.write(0);
                jar.closeEntry();
            }
        }
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

    private static int postJson(int port,String path,String authorization,String origin,String body)throws Exception {
        return stateChange(port,path,authorization,origin,"POST",body);
    }

    private static int delete(int port,String path,String authorization,String origin)throws Exception {
        return stateChange(port,path,authorization,origin,"DELETE",null);
    }

    private static int stateChange(int port,String path,String authorization,String origin,String method,String body)throws Exception {
        byte[] payload=body==null?new byte[0]:body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(java.net.Socket socket=new java.net.Socket("127.0.0.1",port)) {
            socket.setSoTimeout(5000);StringBuilder request=new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: 127.0.0.1:").append(port).append("\r\nConnection: close\r\n");
            if(authorization!=null)request.append("Authorization: ").append(authorization).append("\r\n");
            if(origin!=null)request.append("Origin: ").append(origin).append("\r\n");
            if(body!=null)request.append("Content-Type: application/json\r\nContent-Length: ").append(payload.length).append("\r\n");
            request.append("\r\n");var output=socket.getOutputStream();output.write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));output.write(payload);output.flush();
            String status=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)).readLine();
            if(status==null)throw new java.io.IOException("Tomcat closed the state-change response without a status line");
            return Integer.parseInt(status.split(" ")[1]);
        }
    }

    private static HttpURLConnection open(int port, String path, String authorization) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        return connection;
    }
}
