package att.server;

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
        Path users=Files.writeString(temp.resolve("tomcat-users.xml"),"<tomcat-users><role rolename=\"ATT_USER\"/><role rolename=\"OTHER\"/><user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/><user username=\"other\" password=\"secret\" roles=\"OTHER\"/></tomcat-users>");
        Path config=Files.writeString(temp.resolve("server.yaml"),"server:\n  dataDir: "+temp.resolve("data")+"\n  authenticationRequired: true\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+packages+"\n  entries:\n    p: "+packages.resolve("p")+"\n");
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

        Path openConfig=Files.writeString(temp.resolve("open-server.yaml"),"server:\n  dataDir: "+temp.resolve("open-data")+"\n  authenticationRequired: false\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+packages+"\n  entries:\n    p: "+packages.resolve("p")+"\n");System.setProperty("att.server.config",openConfig.toString());
        Tomcat open=start(users,appDirectory("anonymous"));
        try {assertEquals(200,request(open.getConnector().getLocalPort(),null));assertEquals(200,get(open.getConnector().getLocalPort(),"/att/ui/index.html",null));}
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
        Object streams=field(runtime,"streams");java.lang.reflect.Method activeCount=streams.getClass().getMethod("getActiveCount");
        var clients=Executors.newFixedThreadPool(5);List<HttpURLConnection> connections=new ArrayList<>();
        try {
            List<Future<HttpURLConnection>> pending=new ArrayList<>();
            for(int i=0;i<5;i++)pending.add(clients.submit(()->{HttpURLConnection c=(HttpURLConnection)new java.net.URL("http://127.0.0.1:"+port+"/att/api/v1/jobs/"+id+"/events").openConnection();c.setConnectTimeout(3000);c.setReadTimeout(5000);c.setRequestProperty("Authorization",authorization);assertEquals(200,c.getResponseCode());return c;}));
            for(Future<HttpURLConnection> future:pending)connections.add(future.get(5,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while((Integer)activeCount.invoke(streams)<5&&System.nanoTime()<deadline)Thread.sleep(10);
            assertEquals(5,activeCount.invoke(streams),"Five simultaneous observers must all own live stream threads");
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
    private static Object field(Object target,String name)throws Exception {var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void restore(String old){if(old==null)System.clearProperty("att.server.config");else System.setProperty("att.server.config",old);}
}
