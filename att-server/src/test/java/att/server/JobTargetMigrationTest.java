package att.server;

import att.server.api.WorkerRequest;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class JobTargetMigrationTest {
    @TempDir Path temp;
    private ServerConfig config() throws Exception {
        Path pkg=Files.createDirectories(temp.resolve("pkg"));
        return ServerConfig.load(Files.writeString(temp.resolve("server.yaml"),"server:\n  dataDir: '"+temp.resolve("data")+"'\nworkers: {}\npackages:\n  allowedRoots: ['"+pkg+"']\n  entries:\n    p: '"+pkg+"'\n"));
    }
    private void legacy(ServerConfig config) throws Exception {
        try(JobStore store=new JobStore(config)) {
            Job job=new Job("JLEGACY","run","p","alice",new WorkerRequest(),new JobEvents(config.dataDir.resolve("events"),1));
            store.insert(job,"{\"targetIdentity\":{\"type\":\"flow\",\"id\":\"legacy-flow\"}}");
            job.status="PASS";job.finishedAt=java.time.Instant.now();store.update(job);store.artifactRecoveryNeeded(job.id,false);
        }
        var props=new Properties();props.setProperty("user","sa");props.setProperty("password","");
        try(var c=new org.h2.Driver().connect("jdbc:h2:file:"+config.dataDir.resolve("db/att-server")+";DB_CLOSE_ON_EXIT=FALSE",props);var s=c.createStatement()){s.execute("DELETE FROM job_targets");s.execute("UPDATE jobs SET target_search_version=0");}
    }
    @Test void runtimeIndexesInBackgroundAndStopsItsExecutorOnClose() throws Exception {
        var config=config();legacy(config);
        ServerRuntime runtime=new ServerRuntime(config,Files.createDirectory(temp.resolve("libs")).toString());
        var field=ServerRuntime.class.getDeclaredField("targetIndexMaintenance");field.setAccessible(true);
        var executor=(ScheduledExecutorService)field.get(runtime);
        try {
            assertEquals(1L,runtime.store.queryJobs(JobQuery.parse(Map.of(),"alice")).get("total"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean ready=false;
            while(System.nanoTime()<deadline) {
                try {assertEquals(1L,runtime.store.queryJobs(JobQuery.parse(Map.of("search","legacy-flow"),"alice")).get("total"));ready=true;break;}
                catch(JobStore.TargetSearchPendingException pending){Thread.sleep(20);}
            }
            assertTrue(ready,"Historical target indexing should complete in background");
        } finally {runtime.close();}
        assertTrue(executor.isTerminated(),"No migration executor survives runtime close");
    }
    @Test void servletReturnsRetryable503WithoutPartialResultsAndBrowsingRemainsAvailable() throws Exception {
        var config=config();
        try(ServerRuntime runtime=new ServerRuntime(config,Files.createDirectory(temp.resolve("libs")).toString())) {
            var field=ServerRuntime.class.getDeclaredField("targetIndexMaintenance");field.setAccessible(true);
            var executor=(ScheduledExecutorService)field.get(runtime);executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));
            legacy(config);
            ServletContext context=proxy(ServletContext.class,(self,method,args)->method.getName().equals("getAttribute")?runtime:null);
            ApiServlet servlet=new ApiServlet();servlet.init(proxy(ServletConfig.class,(self,method,args)->method.getName().equals("getServletContext")?context:null));
            Map<String,Object> pending=get(servlet,context,runtime,"legacy-flow");
            assertEquals(503,pending.get("status"));assertEquals("2",pending.get("Retry-After"));
            assertTrue(pending.get("body").toString().contains("ATT-SERVER-TARGET-SEARCH-PENDING"));assertFalse(pending.get("body").toString().contains("items"));
            assertEquals(200,get(servlet,context,runtime,null).get("status"));
            runtime.store.backfillSearchTargetsBatch();
            Map<String,Object> ready=get(servlet,context,runtime,"legacy-flow");assertEquals(200,ready.get("status"));assertTrue(ready.get("body").toString().contains("JLEGACY"));
        }
    }
    private Map<String,Object> get(ApiServlet servlet,ServletContext context,ServerRuntime runtime,String search) throws Exception {
        var out=new HashMap<String,Object>();out.put("status",200);var body=new ByteArrayOutputStream();
        ServletOutputStream stream=new ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(WriteListener listener){}public void write(int value){body.write(value);}public void close(){}};
        HttpServletRequest request=proxy(HttpServletRequest.class,(self,method,args)->switch(method.getName()){
            case "getServletContext" -> context;case "getPathInfo" -> "/jobs";case "getUserPrincipal" -> (Principal)()->"alice";
            case "getParameter" -> "search".equals(args[0])?search:null;case "isUserInRole" -> runtime.config.authorization.userRole.equals(args[0]);default -> null;
        });
        HttpServletResponse response=proxy(HttpServletResponse.class,(self,method,args)->{
            switch(method.getName()){case "setStatus" -> out.put("status",args[0]);case "setHeader" -> out.put((String)args[0],args[1]);case "getOutputStream" -> {return stream;}}return null;
        });
        servlet.doGet(request,response);out.put("body",body.toString(java.nio.charset.StandardCharsets.UTF_8));return out;
    }
    private static <T>T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
}
