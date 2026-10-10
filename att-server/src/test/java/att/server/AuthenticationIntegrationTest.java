package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.catalina.realm.MemoryRealm;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.Context;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.OutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationIntegrationTest {
    @TempDir Path temp;

    @Test void tomcatBasicRealmProtectsServerApiAndSupportsConfiguredAnonymousMode() throws Exception {
        Path packages=Files.createDirectory(temp.resolve("packages"));Files.createDirectory(packages.resolve("p"));
        Path debugPackage=Files.createDirectory(packages.resolve("debug"));Path template=Files.createDirectories(debugPackage.resolve("templates/FORM"));
        Files.createDirectories(debugPackage.resolve("testcase"));Files.createDirectories(debugPackage.resolve("config"));copySchemas(debugPackage);
        Files.writeString(debugPackage.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\ntestcase:\n  root: testcase\ntemplates:\n  root: templates\n");
        Files.writeString(template.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: FORM\ndescription: authenticated debug form\nactions:\n"
                +"  log:\n    type: log\n    message: '${EXEC.INPUT.value}'\n");
        Path loadDirectory=Files.createDirectories(debugPackage.resolve("load"));
        Files.writeString(loadDirectory.resolve("path-based.yaml"),"schemaVersion: att-load/v1.6\nworkloads:\n- id: path-based\n  target:\n    type: template\n    id: FORM\n  load:\n    users: 1\n    duration: 1s\n");
        Files.writeString(template.resolve("debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs:\n  value: safe\n"
                +"  payload: {account: 'customer account 123456789', secret: 'temporary credential violet-123'}\n");
        Path users=Files.writeString(temp.resolve("tomcat-users.xml"),"<tomcat-users><role rolename=\"ATT_USER\"/><role rolename=\"OTHER\"/><user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/><user username=\"other\" password=\"secret\" roles=\"OTHER\"/></tomcat-users>");
        Path config=Files.writeString(temp.resolve("server.yaml"),"server:\n  dataDir: "+yaml(temp.resolve("data"))+"\n  authenticationRequired: true\n  inlineLoad:\n    enabled: true\n    maxWorkloads: 1\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+yaml(packages)+"\n  entries:\n    p: "+yaml(packages.resolve("p"))+"\n    debug: "+yaml(debugPackage)+"\n");
        String old=System.getProperty("att.server.config");System.setProperty("att.server.config",config.toString());
        Tomcat tomcat=start(users,appDirectory("authenticated"),true);
        try {
            int port=tomcat.getConnector().getLocalPort();
            assertEquals(401,request(port,null));
            assertEquals(401,get(port,"/att/ui/index.html",null));
            String basic="Basic "+Base64.getEncoder().encodeToString("att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals(200,request(port,basic));
            String other="Basic "+Base64.getEncoder().encodeToString("other:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals(200,request(port,other),"Any authenticated container principal has the same v1 API permissions");
            assertEquals(200,get(port,"/att/ui/index.html",basic));
            assertEquals(200,get(port,"/att/ui/app.js",basic));
            JsonNode resources=getJson(port,"/att/api/v1/packages/debug/resources?type=template",basic);
            String resourceId=resources.path("items").get(0).path("resourceId").asText();
            String formPath="/att/api/v1/packages/debug/resources/template/"+resourceId+"/debug-form";
            assertEquals(401,get(port,formPath,null),"Debug defaults must remain behind container authentication");
            JsonNode form=getJson(port,formPath,basic);
            assertFalse(form.toString().contains("123456789"));assertFalse(form.toString().contains("violet-123"));
            JsonNode draftBody=ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","target",Map.of("type","template","id","FORM"),"input",form.path("input")));
            JsonNode draft=postJson(port,"/att/api/v1/drafts/debug",basic,draftBody);
            assertFalse(draft.toString().contains("123456789"));assertFalse(draft.toString().contains("violet-123"));
            String draftId=draft.path("draftId").asText();
            assertEquals(200,get(port,"/att/api/v1/drafts/"+draftId,basic));
            assertEquals(404,get(port,"/att/api/v1/drafts/"+draftId,other),"Drafts must be isolated by the authenticated principal");
            JsonNode loadPolicy=getJson(port,"/att/api/v1/packages/debug/load-policy?model=virtualUsers",basic);
            assertEquals("virtualUsers",loadPolicy.path("model").asText());
            assertTrue(getJson(port,"/att/api/v1/version",basic).path("inlineLoadEnabled").asBoolean(false));
            JsonNode advancedScenario=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion","att-load/v1.6",
                    "load",Map.of("users",1,"duration","1s"),"workloads",List.of(Map.of("id","auth-load",
                            "load",Map.of("users",1),"target",Map.of("type","template","id","FORM"),
                            "inputs",Map.of("value","from-advanced-load")))));
            JsonNode advancedDraft=postJson(port,"/att/api/v1/drafts/load",basic,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","scenario",advancedScenario)));
            String advancedDraftId=advancedDraft.path("draftId").asText();assertTrue(advancedDraftId.startsWith("A"));
            assertEquals(200,get(port,"/att/api/v1/drafts/"+advancedDraftId,basic));
            assertEquals(404,get(port,"/att/api/v1/drafts/"+advancedDraftId,other),"Advanced Load drafts must be isolated by the authenticated principal");
            Map<String,Object> firstWorkload=Map.of("id","auth-load-one","load",Map.of("users",1),
                    "target",Map.of("type","template","id","FORM"),"inputs",Map.of("value","within-limit"));
            Map<String,Object> secondWorkload=Map.of("id","auth-load-two","load",Map.of("users",1),
                    "target",Map.of("type","template","id","FORM"),"inputs",Map.of("value","over-limit"));
            JsonNode overLimitScenario=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion","att-load/v1.6",
                    "load",Map.of("users",1,"duration","1s"),"workloads",List.of(firstWorkload,secondWorkload)));
            JsonNode limitError=postErrorJson(port,"/att/api/v1/drafts/load",basic,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","scenario",overLimitScenario)),400);
            assertEquals("ATT-SERVER-INLINE-LOAD-LIMIT",limitError.path("error").path("code").asText());
            assertTrue(limitError.path("error").path("summary").asText().contains("maxWorkloads"));
            JsonNode acceptedLoad=postJson(port,"/att/api/v1/jobs/load",basic,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","draftId",advancedDraftId)),202);
            String loadJobId=acceptedLoad.path("jobId").asText();long loadDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            JsonNode loadJob=getJson(port,"/att/api/v1/jobs/"+loadJobId,basic);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(loadJob.path("status").asText())&&System.nanoTime()<loadDeadline) {
                Thread.sleep(20);loadJob=getJson(port,"/att/api/v1/jobs/"+loadJobId,basic);
            }
            assertEquals("PASS",loadJob.path("status").asText(),"The authenticated Advanced Load REST route must validate and submit its immutable Worker scenario");
            // Exercise the real public API behind the authenticated browser console.
            String jobId=submitJob(port,basic);
            assertEquals(200,get(port,"/att/api/v1/jobs/"+jobId,basic));
            assertEquals(200,get(port,"/att/api/v1/jobs/"+jobId+"/artifacts",basic));
            assertRetainedSseEvent(port,basic,jobId);
            assertEquals(200,cancelJob(port,basic,jobId));
            assertEquals(200,get(port,"/att/api/v1/jobs/"+jobId+"/result",basic));
            assertEquals(415,post(port,basic,"application/x-www-form-urlencoded",null));
            assertEquals(403,post(port,basic,"application/json","https://attacker.example"));
            assertEquals(400,post(port,basic,"application/json","https://att.example.test",java.util.Map.of(
                    "X-Forwarded-For","203.0.113.10","X-Forwarded-Proto","https",
                    "X-Forwarded-Host","att.example.test","X-Forwarded-Port","443")),
                    "Trusted HTTPS proxy headers must make the external Origin match the Servlet request");
            assertEquals(400,postBody(port,basic,"application/json",null,"{not-json"));
            assertFiveSseObserversReceiveLiveEvents(tomcat,port,basic);
        } finally {tomcat.stop();tomcat.destroy();restore(old);}

        Path disabledConfig=Files.writeString(temp.resolve("inline-load-disabled.yaml"),"server:\n  dataDir: "+yaml(temp.resolve("inline-load-disabled-data"))+"\n  authenticationRequired: true\n  inspection:\n    enabled: true\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+yaml(packages)+"\n  entries:\n    debug: "+yaml(debugPackage)+"\n");
        System.setProperty("att.server.config",disabledConfig.toString());
        Tomcat inlineLoadDisabled=start(users,appDirectory("inline-load-disabled"));
        try {
            int port=inlineLoadDisabled.getConnector().getLocalPort();
            String basic="Basic "+Base64.getEncoder().encodeToString("att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(getJson(port,"/att/api/v1/version",basic).path("inlineLoadEnabled").asBoolean(true));
            assertEquals(403,get(port,"/att/api/v1/packages/debug/load-policy?model=virtualUsers",basic));
            assertEquals(403,get(port,"/att/api/v1/packages/debug/resources/template/not-real/quick-load-form?model=virtualUsers",basic));
            assertEquals(403,get(port,"/att/api/v1/drafts/L"+"0".repeat(32),basic));
            assertEquals(403,get(port,"/att/api/v1/drafts/A"+"0".repeat(32),basic));
            assertEquals(403,postStatus(port,basic,"/att/api/v1/drafts/quick-load",Map.of()));
            assertEquals(403,postStatus(port,basic,"/att/api/v1/drafts/load",Map.of()));
            assertEquals(403,postStatus(port,basic,"/att/api/v1/jobs/load",Map.of("packageId","debug","draftId","A"+"0".repeat(32))));
            JsonNode pathBased=postJson(port,"/att/api/v1/jobs/load",basic,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","scenario","load/path-based.yaml")),202);
            JsonNode pathBasedStatus=getJson(port,"/att/api/v1/jobs/"+pathBased.path("jobId").asText(),basic);
            assertEquals("load",pathBasedStatus.path("command").asText(),"Disabling browser drafts must still admit the existing path-based Load API");
        } finally {inlineLoadDisabled.stop();inlineLoadDisabled.destroy();restore(old);}

        Path openConfig=Files.writeString(temp.resolve("open-server.yaml"),"server:\n  dataDir: "+temp.resolve("open-data")+"\n  authenticationRequired: false\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+packages+"\n  entries:\n    p: "+packages.resolve("p")+"\n");System.setProperty("att.server.config",openConfig.toString());
        Tomcat open=start(users,appDirectory("anonymous"));
        try {
            int port=open.getConnector().getLocalPort();
            assertEquals(200,request(port,null));assertEquals(200,get(port,"/att/ui/index.html",null));
            assertAnonymousInspectionIsRejected(port);
        }
        finally {open.stop();open.destroy();restore(old);}
    }

    private Tomcat start(Path users,Path app) throws Exception {return start(users,app,false);}
    private Tomcat start(Path users,Path app,boolean trustedProxy) throws Exception {
        Tomcat tomcat=new Tomcat();tomcat.setBaseDir(temp.resolve("tomcat-"+System.nanoTime()).toString());tomcat.setPort(0);tomcat.getConnector();MemoryRealm realm=new MemoryRealm();realm.setPathname(users.toString());tomcat.getEngine().setRealm(realm);
        if(trustedProxy){RemoteIpValve valve=new RemoteIpValve();valve.setInternalProxies("127\\.0\\.0\\.1");valve.setRemoteIpHeader("X-Forwarded-For");valve.setProtocolHeader("X-Forwarded-Proto");valve.setHostHeader("X-Forwarded-Host");valve.setPortHeader("X-Forwarded-Port");tomcat.getEngine().getPipeline().addValve(valve);}
        tomcat.addWebapp("/att",app.toString());tomcat.start();return tomcat;
    }
    private Path appDirectory(String name)throws Exception {
        Path app=Files.createDirectory(temp.resolve(name));Path webInf=Files.createDirectories(app.resolve("WEB-INF"));Path classes=Files.createDirectories(webInf.resolve("classes"));Files.createDirectories(webInf.resolve("lib"));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));int index=0;
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))){Path entry=Path.of(element);if(Files.isDirectory(entry)){try(var paths=Files.walk(entry)){for(Path source:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator){Path target=classes.resolve(entry.relativize(source).toString());Files.createDirectories(target.getParent());if(!Files.exists(target))Files.copy(source,target);}}}else if(Files.isRegularFile(entry)&&entry.toString().endsWith(".jar")&&!entry.getFileName().toString().startsWith("tomcat-embed-")&&!entry.getFileName().toString().contains("servlet-api")){Path target=webInf.resolve("lib").resolve(entry.getFileName());Files.copy(entry,target,StandardCopyOption.REPLACE_EXISTING);}index++;}
        Path descriptor=Path.of("src/main/webapp/WEB-INF/web.xml");
        if(!Files.isRegularFile(descriptor))descriptor=Path.of("att-server/src/main/webapp/WEB-INF/web.xml");
        try(var input=Files.newInputStream(descriptor)){Files.copy(input,webInf.resolve("web.xml"));}
        Path ui=Files.createDirectories(app.resolve("ui"));
        for(String asset:java.util.List.of("index.html","app.js","app.css")) {
            try(var stream=Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/resources/ui/"+asset)) {
                assertNotNull(stream,"ATT Web UI asset must exist on test classpath: "+asset);
                Files.copy(stream,ui.resolve(asset));
            }
        }
        addModuleJar(webInf.resolve("lib"),"att-worker",Path.of("att-worker/target/classes"));
        addModuleJar(webInf.resolve("lib"),"att-engine",Path.of("att-engine/target/classes"));
        addModuleJar(webInf.resolve("lib"),"att-server-api",Path.of("att-server-api/target/classes"));
        return app;
    }

    private static String submitJob(int port,String authorization)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/att/api/v1/jobs/run").openConnection();
        c.setConnectTimeout(3000);c.setReadTimeout(5000);
        c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Authorization",authorization);
        c.setRequestProperty("Content-Type","application/json");
        try(var out=c.getOutputStream()) {out.write("{\"packageId\":\"p\",\"all\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        assertEquals(202,c.getResponseCode());
        String json=new String(c.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        c.disconnect();
        String id=ServerRuntime.JSON.readTree(json).path("jobId").asText();
        assertTrue(id.matches("J[0-9A-F]{16}"));
        return id;
    }
    private static void assertRetainedSseEvent(int port,String authorization,String id)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/att/api/v1/jobs/"+id+"/events").openConnection();
        c.setReadTimeout(5000);c.setRequestProperty("Authorization",authorization);
        assertEquals(200,c.getResponseCode());assertTrue(c.getHeaderField("Content-Type").startsWith("text/event-stream"));
        try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))) {
            assertTrue(reader.readLine().startsWith("id: "),"SSE must replay retained job events");
        } finally {c.disconnect();}
    }
    private static int cancelJob(int port,String authorization,String id)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/att/api/v1/jobs/"+id).openConnection();
        c.setRequestMethod("DELETE");c.setRequestProperty("Authorization",authorization);c.setReadTimeout(5000);
        try{return c.getResponseCode();}finally{c.disconnect();}
    }
    private static int post(int port,String authorization,String contentType,String origin)throws Exception {return post(port,authorization,contentType,origin,java.util.Map.of());}
    private static int post(int port,String authorization,String contentType,String origin,java.util.Map<String,String> extraHeaders)throws Exception {
        try(java.net.Socket socket=new java.net.Socket("127.0.0.1",port)) {socket.setSoTimeout(3000);java.io.OutputStream out=socket.getOutputStream();String body="{}";StringBuilder request=new StringBuilder("POST /att/api/v1/jobs/run HTTP/1.1\r\nHost: 127.0.0.1:").append(port).append("\r\nConnection: close\r\nAuthorization: ").append(authorization).append("\r\nContent-Type: ").append(contentType).append("\r\nContent-Length: ").append(body.length()).append("\r\n");if(origin!=null)request.append("Origin: ").append(origin).append("\r\n");extraHeaders.forEach((name,value)->request.append(name).append(": ").append(value).append("\r\n"));request.append("\r\n").append(body);out.write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();String status=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)).readLine();return Integer.parseInt(status.split(" ")[1]);}
    }
    private static int request(int port,String authorization)throws Exception {return get(port,"/att/api/v1/packages",authorization);}
    private static int get(int port,String uri,String authorization)throws Exception {HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+uri).openConnection();connection.setConnectTimeout(3000);connection.setReadTimeout(3000);if(authorization!=null)connection.setRequestProperty("Authorization",authorization);int code=connection.getResponseCode();if(code==401)assertTrue(connection.getHeaderField("WWW-Authenticate").startsWith("Basic"));
        if(code==200&&uri.startsWith("/att/ui/")) {assertEquals("nosniff",connection.getHeaderField("X-Content-Type-Options"));assertNotNull(connection.getHeaderField("Content-Security-Policy"));}
        connection.disconnect();return code;}
    private static JsonNode getJson(int port,String uri,String authorization)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+uri).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(30000);
        if(authorization!=null)connection.setRequestProperty("Authorization",authorization);
        assertEquals(200,connection.getResponseCode());try{return ServerRuntime.JSON.readTree(connection.getInputStream());}finally{connection.disconnect();}
    }
    private static JsonNode postJson(int port,String uri,String authorization,JsonNode body)throws Exception {
        return postJson(port,uri,authorization,body,201);
    }
    private static JsonNode postJson(int port,String uri,String authorization,JsonNode body,int expectedStatus)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+uri).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Authorization",authorization);connection.setRequestProperty("Content-Type","application/json");
        try(var output=connection.getOutputStream()){output.write(ServerRuntime.JSON.writeValueAsBytes(body));}
        assertEquals(expectedStatus,connection.getResponseCode());try{return ServerRuntime.JSON.readTree(connection.getInputStream());}finally{connection.disconnect();}
    }
    private static JsonNode postErrorJson(int port,String uri,String authorization,JsonNode body,int expectedStatus)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+uri).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Authorization",authorization);connection.setRequestProperty("Content-Type","application/json");
        try(var output=connection.getOutputStream()){output.write(ServerRuntime.JSON.writeValueAsBytes(body));}
        assertEquals(expectedStatus,connection.getResponseCode());try{return ServerRuntime.JSON.readTree(connection.getErrorStream());}finally{connection.disconnect();}
    }
    private static int postStatus(int port,String authorization,String uri,Map<String,Object> body)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+uri).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Authorization",authorization);connection.setRequestProperty("Content-Type","application/json");
        try(var output=connection.getOutputStream()){output.write(ServerRuntime.JSON.writeValueAsBytes(body));}
        try{return connection.getResponseCode();}finally{connection.disconnect();}
    }
    private static void assertAnonymousInspectionIsRejected(int port)throws Exception {
        for(String path:List.of("/att/api/v1/packages/p/resources?type=case","/att/api/v1/packages/p/configuration?view=declared")) {
            HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();
            connection.setConnectTimeout(3000);connection.setReadTimeout(3000);
            try {
                assertEquals(401,connection.getResponseCode(),"Inspection still requires a real Servlet Principal in anonymous legacy mode");
                com.fasterxml.jackson.databind.JsonNode body=ServerRuntime.JSON.readTree(connection.getErrorStream().readAllBytes());
                assertEquals("ATT-SERVER-AUTHENTICATION-REQUIRED",body.path("error").path("code").asText());
            } finally {connection.disconnect();}
        }
    }
    private static void restore(String old){if(old==null)System.clearProperty("att.server.config");else System.setProperty("att.server.config",old);}
    private static int postBody(int port,String authorization,String contentType,String origin,String body)throws Exception {
        try(java.net.Socket socket=new java.net.Socket("127.0.0.1",port)) {socket.setSoTimeout(3000);java.io.OutputStream out=socket.getOutputStream();StringBuilder request=new StringBuilder("POST /att/api/v1/jobs/run HTTP/1.1\r\nHost: 127.0.0.1:").append(port).append("\r\nConnection: close\r\nAuthorization: ").append(authorization).append("\r\nContent-Type: ").append(contentType).append("\r\nContent-Length: ").append(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append("\r\n");if(origin!=null)request.append("Origin: ").append(origin).append("\r\n");request.append("\r\n").append(body);out.write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();String status=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)).readLine();return Integer.parseInt(status.split(" ")[1]);}
    }
    private static void assertFiveSseObserversReceiveLiveEvents(Tomcat tomcat,int port,String authorization)throws Exception {
        Context context=(Context)tomcat.getHost().findChild("/att");Object runtime=context.getServletContext().getAttribute(ServerBootstrap.RUNTIME);ClassLoader appLoader=runtime.getClass().getClassLoader();
        Object config=field(runtime,"config");Path dataDir=(Path)field(config,"dataDir");int maxEvents=(Integer)field(config,"maxEventsPerJob");
        String id="J"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();Path directory=Files.createDirectories(dataDir.resolve("jobs").resolve(id));
        Class<?> journalType=Class.forName("att.server.JobEvents",true,appLoader);var journalConstructor=journalType.getDeclaredConstructor(Path.class,int.class);journalConstructor.setAccessible(true);Object journal=journalConstructor.newInstance(directory.resolve("events.jsonl"),maxEvents);
        Class<?> requestType=Class.forName("att.worker.WorkerRequest",true,appLoader);Object workerRequest=requestType.getConstructor().newInstance();
        Class<?> jobType=Class.forName("att.server.Job",true,appLoader);var jobConstructor=jobType.getDeclaredConstructor(String.class,String.class,String.class,String.class,requestType,journalType);jobConstructor.setAccessible(true);Object job=jobConstructor.newInstance(id,"run","p","observer-test",workerRequest,journal);
        Object store=field(runtime,"store");var insert=store.getClass().getDeclaredMethod("insert",jobType,String.class);insert.setAccessible(true);insert.invoke(store,job,"{}");
        @SuppressWarnings("unchecked") Map<String,Object> jobs=(Map<String,Object>)field(runtime,"jobs");jobs.put(id,job);
        var append=journalType.getDeclaredMethod("append",String.class,Map.class);append.setAccessible(true);append.invoke(journal,"status",Map.of("jobId",id,"status","RUNNING"));
        var clients=Executors.newFixedThreadPool(5);List<HttpURLConnection> connections=new ArrayList<>();
        try {
            List<Future<HttpURLConnection>> pending=new ArrayList<>();
            for(int i=0;i<5;i++)pending.add(clients.submit(()->{HttpURLConnection c=(HttpURLConnection)new java.net.URL("http://127.0.0.1:"+port+"/att/api/v1/jobs/"+id+"/events").openConnection();c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestProperty("Authorization",authorization);assertEquals(200,c.getResponseCode());return c;}));
            for(Future<HttpURLConnection> future:pending)connections.add(future.get(5,TimeUnit.SECONDS));
            // HTTP 200 can arrive before the asynchronous stream task subscribes to the journal.
            // Wait until every listener is registered so this checks live fan-out, not setup timing.
            awaitSseListeners(journal,connections.size());
            append.invoke(journal,"progress",Map.of("message","live observer event"));
            for(HttpURLConnection connection:connections) {
                try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;boolean received=false;while((line=reader.readLine())!=null){if(line.contains("live observer event")){received=true;break;}}
                    assertTrue(received,"Each concurrent observer should receive the live event");
                }
            }
        } finally {
            var finish=runtime.getClass().getDeclaredMethod("finish",jobType,String.class,int.class);finish.setAccessible(true);finish.invoke(runtime,job,"PASS",0);for(HttpURLConnection c:connections)c.disconnect();clients.shutdownNow();
        }
    }
    private static void awaitSseListeners(Object journal,int expected)throws Exception {
        var listeners=journal.getClass().getDeclaredField("listeners");listeners.setAccessible(true);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        int count=0;
        do {
            count=((java.util.Collection<?>)listeners.get(journal)).size();
            if(count==expected)return;
            Thread.sleep(10);
        } while(System.nanoTime()<deadline);
        assertEquals(expected,count,"All concurrent SSE streams should subscribe before the live event is appended");
    }
    private static Object field(Object target,String name)throws Exception {var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void copySchemas(Path packageRoot)throws Exception {
        Path source=Path.of("schemas").toRealPath(),destination=packageRoot.resolve("schemas");
        try(var paths=Files.walk(source)) {for(Path path:(Iterable<Path>)paths::iterator){Path target=destination.resolve(source.relativize(path));if(Files.isDirectory(path))Files.createDirectories(target);else{Files.createDirectories(target.getParent());Files.copy(path,target);}}}
    }
    private static void addModuleJar(Path lib,String name,Path classes)throws Exception {
        Path target=lib.resolve(name+".jar");try(OutputStream file=Files.newOutputStream(target);JarOutputStream jar=new JarOutputStream(file);var paths=Files.walk(classes)) {
            paths.filter(Files::isRegularFile).forEach(path->{try{jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));Files.copy(path,jar);jar.closeEntry();}catch(Exception error){throw new IllegalStateException(error);}});
        }
    }
    private static String yaml(Path path){return "'"+path.toAbsolutePath().toString().replace("'","''")+"'";}

}
