package att.server;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                "<tomcat-users><role rolename=\"ATT_USER\"/><role rolename=\"ATT_SCHEDULE_ADMIN\"/>"
                        + "<user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/>"
                        + "<user username=\"schedule-admin\" password=\"secret\" roles=\"ATT_USER,ATT_SCHEDULE_ADMIN\"/></tomcat-users>");
        Path packages = Files.createDirectories(temp.resolve("packages"));
        Path dummyPackage = Files.createDirectories(packages.resolve("dummy"));
        Path config = Files.writeString(temp.resolve("server.yaml"),
                "server:\n  dataDir: " + temp.resolve("data") + "\n  authenticationRequired: true\n  security:\n    checkCrossOrigin: true\n"
                        + "  scheduling:\n    enabled: true\n    adminRole: ATT_SCHEDULE_ADMIN\n"
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
            String scheduleAdminAuth = "Basic " + Base64.getEncoder().encodeToString(
                    "schedule-admin:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(401, get(port, "/tools/att/api/v1/load-schedules", null));
            assertEquals(403, get(port, "/tools/att/api/v1/load-schedules", auth),
                    "ordinary ATT_USER must not manage schedules");
            assertEquals(200, get(port, "/tools/att/api/v1/load-schedules", scheduleAdminAuth),
                    "the configured schedule-admin role may list schedules");
            String previewPath = "/tools/att/api/v1/load-schedules/preview";
            String previewBody = "{\"timezone\":\"UTC\",\"cron\":\"0 2 * * *\"}";
            assertEquals(403, postJson(port, previewPath, scheduleAdminAuth, "https://attacker.example", previewBody),
                    "cross-origin schedule mutations must be rejected");
            assertEquals(403, postJson(port, previewPath, scheduleAdminAuth, "null", previewBody),
                    "opaque Origins must be rejected");
            assertEquals(200, postJson(port, previewPath, scheduleAdminAuth, "http://127.0.0.1:" + port, previewBody),
                    "same-origin schedule preview may proceed");
            assertEquals(403, delete(port, "/tools/att/api/v1/load-schedules/unknown", scheduleAdminAuth, "https://attacker.example"),
                    "cross-origin deletes must be rejected before schedule lookup");
            assertJobDeepLink(port, auth);

            String index = getText(port, "/tools/att/ui/index.html", auth);
            assertTrue(index.contains("id=\"root\""), "The active page must mount the React application.");
            assertFalse(index.contains("app.js"), "The active page must not load the legacy DOM-mutating script.");
            assertFalse(index.contains("legacy.html"), "The rollback page must not be part of the active entry.");
            String script = findAsset(index, "src");
            String stylesheet = findAsset(index, "href");
            String scriptBody = getText(port, "/tools/att/ui/" + script.replaceFirst("^\\./", ""), auth);
            String styleBody = getText(port, "/tools/att/ui/" + stylesheet.replaceFirst("^\\./", ""), auth);
            assertTrue(scriptBody.contains("../api/v1/"), "API URL must work below a non-root context path.");
            assertTrue(scriptBody.contains("Server managed") && scriptBody.contains("not exposed by API"),
                    "The shell must not imply a principal that the API does not expose.");
            assertTrue(styleBody.contains("--att-color-primary") && styleBody.contains("--att-focus-ring"),
                    "The generated stylesheet must include the shared design tokens.");
            for (String asset : new String[]{"legacy.html", "app.js", "app-shell.js", "app.css"}) {
                HttpURLConnection connection = open(port, "/tools/att/ui/" + asset, auth);
                try {
                    assertEquals(200, connection.getResponseCode(), asset + " rollback asset");
                    assertEquals("nosniff", connection.getHeaderField("X-Content-Type-Options"));
                    assertNotNull(connection.getHeaderField("Content-Security-Policy"));
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
            assertTrue(entries.stream().anyMatch(name -> name.matches("META-INF/resources/ui/assets/index-[^/]+\\.js")),
                    "The active Vite bundle must be packaged under Servlet static resources.");
            assertTrue(entries.stream().anyMatch(name -> name.matches("META-INF/resources/ui/assets/index-[^/]+\\.css")),
                    "The generated stylesheet must be packaged under Servlet static resources.");
            assertTrue(entries.contains("META-INF/resources/ui/legacy.html"));
            assertTrue(entries.contains("att-build.properties"));
            assertFalse(entries.contains("att/worker/WorkerMain.class"),
                    "Worker classes belong only in the Local runtime jar");
        }
    }

    @Test
    void packagedWarExecutesScheduledLoadLifecycleAcrossRestartUnderNonRootContext() throws Exception {
        Path war = Path.of(System.getProperty("att.server.war"));
        assertTrue(Files.isRegularFile(war), "Packaged server WAR must exist: " + war);
        Path localHome = createScheduledLocalRuntime();
        Files.createDirectories(localHome.resolve("lib"));
        Path packages = Files.createDirectories(temp.resolve("scheduled-packages"));
        Path packageRoot = Files.createDirectories(packages.resolve("payments"));
        writeScheduledPackage(packageRoot);
        Path users = Files.writeString(temp.resolve("scheduled-tomcat-users.xml"),
                "<tomcat-users><role rolename=\"ATT_USER\"/><role rolename=\"ATT_SCHEDULE_ADMIN\"/>"
                        + "<user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/>"
                        + "<user username=\"schedule-admin\" password=\"secret\" roles=\"ATT_USER,ATT_SCHEDULE_ADMIN\"/></tomcat-users>");
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        Path config = Files.writeString(temp.resolve("scheduled-war-server.yaml"),
                "server:\n  dataDir: " + yaml(temp.resolve("scheduled-war-data")) + "\n  javaExecutable: " + yaml(javaBin)
                        + "\n  authenticationRequired: true\n  inspection:\n    enabled: true\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256"
                        + "\n  scheduling:\n    enabled: true\n    adminRole: ATT_SCHEDULE_ADMIN\n    minimumIntervalMinutes: 1\n    pollIntervalSeconds: 5\n    maxConcurrentRuns: 1"
                        + "\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - " + yaml(packages)
                        + "\n  entries:\n    payments: " + yaml(packageRoot) + "\n");
        String oldConfig = System.getProperty("att.server.config");
        String oldHome = System.getProperty("att.home");
        System.setProperty("att.server.config", config.toString());
        System.setProperty("att.home", localHome.toString());
        Tomcat tomcat = null;
        String admin = "Basic " + Base64.getEncoder().encodeToString("schedule-admin:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String ordinary = "Basic " + Base64.getEncoder().encodeToString("att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String api = "/tools/att/api/v1";
        String firstScheduleId;
        String secondScheduleId;
        String jobId;
        try {
            tomcat = startScheduledWar(war, users);
            int port = tomcat.getConnector().getLocalPort();
            String origin = "http://127.0.0.1:" + port;
            assertEquals(401, get(port, api + "/load-schedules", null));
            assertEquals(403, get(port, api + "/load-schedules", ordinary));
            assertEquals(200, get(port, api + "/load-schedules", admin));

            JsonNode preview = apiJson(port, "POST", api + "/load-schedules/preview", admin, origin,
                    Map.of("timezone", "UTC", "cron", "0 2 * * *"), 200);
            assertEquals("UTC", preview.path("timezone").asText());
            assertTrue(preview.path("nextRunAt").isTextual(), "The real Server preview must return its next occurrence.");

            Map<String, Object> invalid = scheduleRequest("Invalid scenario", "load/invalid.yaml");
            assertEquals(401, apiStatus(port, "POST", api + "/load-schedules", null, origin, invalid));
            assertEquals(403, apiStatus(port, "POST", api + "/load-schedules", ordinary, origin, invalid));
            assertEquals(400, apiStatus(port, "POST", api + "/load-schedules", admin, origin, invalid),
                    "Saving must validate the full package scenario even though preview only checks timezone and cron.");

            JsonNode first = apiJson(port, "POST", api + "/load-schedules", admin, origin,
                    scheduleRequest("Nightly payments", "load/scenario.yaml"), 201);
            firstScheduleId = first.path("id").asText();
            assertFalse(first.path("enabled").asBoolean(), "New schedules must default to paused.");
            JsonNode second = apiJson(port, "POST", api + "/load-schedules", admin, origin,
                    scheduleRequest("Secondary payments", "load/scenario.yaml"), 201);
            secondScheduleId = second.path("id").asText();

            assertEquals(401, apiStatus(port, "POST", api + "/load-schedules/" + firstScheduleId + "/trigger", null, origin, null));
            assertEquals(403, apiStatus(port, "POST", api + "/load-schedules/" + firstScheduleId + "/trigger", ordinary, origin, null));
            assertEquals(200, apiStatus(port, "POST", api + "/load-schedules/" + firstScheduleId + "/pause", admin, origin, null));
            assertFalse(getJson(port, api + "/load-schedules/" + firstScheduleId, admin).path("enabled").asBoolean());
            assertEquals(200, apiStatus(port, "POST", api + "/load-schedules/" + firstScheduleId + "/resume", admin, origin, null));
            assertTrue(getJson(port, api + "/load-schedules/" + firstScheduleId, admin).path("enabled").asBoolean());

            JsonNode trigger = apiJson(port, "POST", api + "/load-schedules/" + firstScheduleId + "/trigger", admin, origin, null, 202);
            assertEquals("submitted", trigger.path("status").asText());
            jobId = trigger.path("jobId").asText();
            assertTrue(jobId.matches("J[0-9A-F]{16}"));
            JsonNode activeJob = getJson(port, api + "/jobs/" + jobId, admin);
            assertTrue(List.of("QUEUED", "PREPARING", "RUNNING").contains(activeJob.path("status").asText()),
                    "The real scheduled Load should remain active long enough to exercise capacity handling.");

            JsonNode rejected = apiJson(port, "POST", api + "/load-schedules/" + secondScheduleId + "/trigger", admin, origin, null, 202);
            assertEquals("skipped", rejected.path("status").asText());
            assertEquals("global-scheduled-load-capacity", rejected.path("reason").asText());
            JsonNode secondHistory = getJson(port, api + "/load-schedules/" + secondScheduleId + "/executions", admin);
            assertEquals("global-scheduled-load-capacity", secondHistory.path("items").get(0).path("reason").asText());

            long eventCursor = firstSseEventId(port, api + "/jobs/" + jobId + "/events", admin);
            assertEquals(200, apiStatus(port, "DELETE", api + "/jobs/" + jobId, admin, origin, null));
            awaitJobStatus(port, api + "/jobs/" + jobId, admin, "CANCELLED");
            assertLastEventIdResume(port, api + "/jobs/" + jobId + "/events", admin, eventCursor);
            JsonNode firstHistory = getJson(port, api + "/load-schedules/" + firstScheduleId + "/executions", admin);
            assertEquals(jobId, firstHistory.path("items").get(0).path("jobId").asText());
            assertJobDeepLink(port, admin, jobId);

            tomcat.stop();
            tomcat.destroy();
            tomcat = null;

            tomcat = startScheduledWar(war, users);
            port = tomcat.getConnector().getLocalPort();
            origin = "http://127.0.0.1:" + port;
            JsonNode restored = getJson(port, api + "/load-schedules", admin);
            assertEquals(2, restored.path("items").size(), "Schedules must persist across a Tomcat restart.");
            assertTrue(getJson(port, api + "/load-schedules/" + firstScheduleId, admin).path("enabled").asBoolean(),
                    "The resumed schedule state must persist across restart.");
            assertEquals(jobId, getJson(port, api + "/load-schedules/" + firstScheduleId + "/executions", admin)
                    .path("items").get(0).path("jobId").asText(), "Occurrence history and job links must persist across restart.");
            assertEquals("CANCELLED", getJson(port, api + "/jobs/" + jobId, admin).path("status").asText());

            assertEquals(200, apiStatus(port, "DELETE", api + "/load-schedules/" + secondScheduleId, admin, origin, null));
            assertEquals("global-scheduled-load-capacity", getJson(port, api + "/load-schedules/" + secondScheduleId + "/executions", admin)
                    .path("items").get(0).path("reason").asText(), "Deleting a schedule must retain its occurrence history.");
            assertEquals(200, apiStatus(port, "DELETE", api + "/load-schedules/" + firstScheduleId, admin, origin, null));
            assertEquals(404, get(port, api + "/load-schedules/" + firstScheduleId, admin));
        } finally {
            if (tomcat != null) {
                try { tomcat.stop(); } finally { tomcat.destroy(); }
            }
            if (oldConfig == null) System.clearProperty("att.server.config");
            else System.setProperty("att.server.config", oldConfig);
            if (oldHome == null) System.clearProperty("att.home");
            else System.setProperty("att.home", oldHome);
        }
    }

    private Path createScheduledLocalRuntime() throws Exception {
        Path home = Files.createDirectory(temp.resolve("scheduled-local-runtime"));
        Path lib = Files.createDirectories(home.resolve("lib"));
        Set<String> added = new HashSet<>();
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(lib.resolve("att-local-" + ServerBuildInfo.PRODUCT + ".jar")))) {
            for (String module : List.of("att-cli", "att-engine", "att-remote", "att-server-api", "att-worker")) {
                Path classes = Path.of(module, "target/classes");
                try (var paths = Files.walk(classes)) {
                    for (Path source : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator) {
                        String name = classes.relativize(source).toString().replace(java.io.File.separatorChar, '/');
                        if (!added.add(name)) continue;
                        jar.putNextEntry(new JarEntry(name));
                        Files.copy(source, jar);
                        jar.closeEntry();
                    }
                }
            }
        }
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String element : classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path dependency = Path.of(element);
            if (!Files.isRegularFile(dependency) || !dependency.getFileName().toString().endsWith(".jar")) continue;
            String name = dependency.getFileName().toString();
            if (name.startsWith("att-") || name.startsWith("tomcat-") || name.contains("servlet-api")) continue;
            Path target = lib.resolve(name);
            if (!Files.exists(target)) Files.copy(dependency, target);
        }
        return home;
    }

    private static void writeScheduledPackage(Path packageRoot) throws Exception {
        Path templates = Files.createDirectories(packageRoot.resolve("templates/SCHEDULED"));
        Path loads = Files.createDirectories(packageRoot.resolve("load"));
        Files.createDirectories(packageRoot.resolve("testcase"));
        Files.createDirectories(packageRoot.resolve("config"));
        copySchemas(packageRoot);
        Files.writeString(packageRoot.resolve("config/config.yaml"),
                "schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n"
                        + "testcase:\n  root: testcase\ntemplates:\n  root: templates\n");
        Files.writeString(templates.resolve("template.yaml"),
                "schemaVersion: att-template/v3.6\nname: SCHEDULED\ndescription: stock Tomcat scheduler test\nactions:\n"
                        + "  message:\n    type: log\n    message: scheduled Load integration\n");
        Files.writeString(loads.resolve("scenario.yaml"),
                "schemaVersion: att-load/v1.6\nworkloads:\n  - id: scheduled-long-run\n    target:\n      type: template\n      id: SCHEDULED\n"
                        + "    load:\n      users: 1\n      duration: 30s\n    execution:\n      thinkTime: 1s\n");
        Files.writeString(loads.resolve("invalid.yaml"), "schemaVersion: att-load/invalid\nworkloads: []\n");
    }

    private static void copySchemas(Path packageRoot) throws Exception {
        Path source = Path.of("schemas").toRealPath();
        Path destination = packageRoot.resolve("schemas");
        try (var paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }

    private static Map<String, Object> scheduleRequest(String name, String scenario) {
        return Map.of("name", name, "packageId", "payments", "scenario", scenario,
                "timezone", "UTC", "cron", "0 2 * * *", "maxConcurrentRuns", 1, "enabled", false);
    }

    private static JsonNode apiJson(int port, String method, String path, String authorization, String origin,
                                    Object body, int expectedStatus) throws Exception {
        HttpURLConnection connection = open(port, path, authorization);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod(method);
        if (origin != null) connection.setRequestProperty("Origin", origin);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (var output = connection.getOutputStream()) {
                output.write(ServerRuntime.JSON.writeValueAsBytes(body));
            }
        }
        try {
            int status = connection.getResponseCode();
            assertEquals(expectedStatus, status, method + " " + path);
            return ServerRuntime.JSON.readTree(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static int apiStatus(int port, String method, String path, String authorization, String origin, Object body) throws Exception {
        HttpURLConnection connection = open(port, path, authorization);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(30000);
        connection.setRequestMethod(method);
        if (origin != null) connection.setRequestProperty("Origin", origin);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (var output = connection.getOutputStream()) {
                output.write(ServerRuntime.JSON.writeValueAsBytes(body));
            }
        }
        try { return connection.getResponseCode(); }
        finally { connection.disconnect(); }
    }

    private static JsonNode getJson(int port, String path, String authorization) throws Exception {
        HttpURLConnection connection = open(port, path, authorization);
        connection.setReadTimeout(30000);
        try {
            assertEquals(200, connection.getResponseCode(), "GET " + path);
            return ServerRuntime.JSON.readTree(connection.getInputStream());
        } finally { connection.disconnect(); }
    }

    private static JsonNode awaitJobStatus(int port, String path, String authorization, String expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        JsonNode job;
        do {
            job = getJson(port, path, authorization);
            if (expected.equals(job.path("status").asText())) return job;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, job.path("status").asText(), "Job did not reach its expected terminal state.");
        return job;
    }

    private static long firstSseEventId(int port, String path, String authorization) throws Exception {
        HttpURLConnection connection = open(port, path, authorization);
        connection.setReadTimeout(5000);
        try {
            assertEquals(200, connection.getResponseCode(), "GET " + path);
            assertTrue(connection.getHeaderField("Content-Type").startsWith("text/event-stream"));
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("id: ")) return Long.parseLong(line.substring(4));
                }
            }
        } finally { connection.disconnect(); }
        throw new AssertionError("The Server did not replay a retained SSE event.");
    }

    private static void assertLastEventIdResume(int port, String path, String authorization, long cursor) throws Exception {
        HttpURLConnection connection = open(port, path, authorization);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Last-Event-ID", Long.toString(cursor));
        try {
            assertEquals(200, connection.getResponseCode(), "The Tomcat event endpoint must accept Last-Event-ID.");
            long previous = cursor;
            boolean receivedNewEvent = false;
            boolean receivedCancellation = false;
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("id: ")) {
                        long eventId = Long.parseLong(line.substring(4));
                        assertTrue(eventId > previous, "A resumed stream must not replay events at or before Last-Event-ID.");
                        previous = eventId;
                        receivedNewEvent = true;
                    } else if (line.startsWith("data: ")) {
                        JsonNode data = ServerRuntime.JSON.readTree(line.substring(6));
                        if ("CANCELLED".equals(data.path("status").asText())) receivedCancellation = true;
                    }
                }
            }
            assertTrue(receivedNewEvent, "A resumed stream must deliver events newer than Last-Event-ID.");
            assertTrue(receivedCancellation, "The resumed Tomcat stream must include the terminal CANCELLED status.");
        } finally { connection.disconnect(); }
    }

    private Tomcat startScheduledWar(Path war, Path users) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(temp.resolve("scheduled-tomcat-" + System.nanoTime()).toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        MemoryRealm realm = new MemoryRealm();
        realm.setPathname(users.toString());
        tomcat.getEngine().setRealm(realm);
        ((StandardHost) tomcat.getHost()).setUnpackWARs(false);
        tomcat.addWebapp("/tools/att", war.toString());
        tomcat.start();
        return tomcat;
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
                    jar.write("att.version=4.1.4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    private static void assertJobDeepLink(int port, String authorization, String jobId) throws Exception {
        HttpURLConnection route = open(port, "/tools/att/ui/jobs/" + jobId, authorization);
        route.setInstanceFollowRedirects(false);
        try {
            assertEquals(302, route.getResponseCode());
            assertEquals("/tools/att/ui/index.html#/jobs/" + jobId, route.getHeaderField("Location"));
        } finally {
            route.disconnect();
        }
        assertEquals(200, get(port, "/tools/att/ui/index.html", authorization),
                "The job deep link must land in the UI below the non-root context path");
    }

    private static int get(int port, String path, String authorization) throws Exception {
        HttpURLConnection connection = open(port, path, authorization);
        try { return connection.getResponseCode(); }
        finally { connection.disconnect(); }
    }

    private static String getText(int port, String path, String authorization) throws Exception {
        HttpURLConnection connection = open(port, path, authorization);
        try {
            assertEquals(200, connection.getResponseCode(), path);
            assertEquals("nosniff", connection.getHeaderField("X-Content-Type-Options"));
            assertNotNull(connection.getHeaderField("Content-Security-Policy"));
            return new String(connection.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static String findAsset(String html, String attribute) {
        Matcher matcher = Pattern.compile("<(?:script|link)[^>]+\\b" + attribute + "=\\\"([^\\\"]+\\.(?:js|css))\\\"").matcher(html);
        assertTrue(matcher.find(), "The active Vite index must reference a generated " + attribute + " asset.");
        return matcher.group(1);
    }

    private static int postJson(int port, String path, String authorization, String origin, String body) throws Exception {
        return stateChange(port, path, authorization, origin, "POST", body);
    }

    private static int delete(int port, String path, String authorization, String origin) throws Exception {
        return stateChange(port, path, authorization, origin, "DELETE", null);
    }

    private static int stateChange(int port, String path, String authorization, String origin, String method, String body) throws Exception {
        byte[] payload = body == null ? new byte[0] : body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            StringBuilder request = new StringBuilder(method).append(' ').append(path)
                    .append(" HTTP/1.1\r\nHost: 127.0.0.1:").append(port).append("\r\nConnection: close\r\n");
            if (authorization != null) request.append("Authorization: ").append(authorization).append("\r\n");
            if (origin != null) request.append("Origin: ").append(origin).append("\r\n");
            if (body != null) request.append("Content-Type: application/json\r\nContent-Length: ").append(payload.length).append("\r\n");
            request.append("\r\n");
            var output = socket.getOutputStream();
            output.write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.write(payload);
            output.flush();
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)).readLine();
            if (status == null) throw new java.io.IOException("Tomcat closed the state-change response without a status line");
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

    private static String yaml(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'";
    }
}
