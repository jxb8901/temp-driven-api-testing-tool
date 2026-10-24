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
            var exact=get(servlet,context,runtime,"job:JLEGACY");assertEquals(200,exact.get("status"));assertTrue(exact.get("body").toString().contains("JLEGACY"));
            assertEquals(503,get(servlet,context,runtime,"JLEG").get("status"));
            runtime.store.backfillSearchTargetsBatch();
            Map<String,Object> ready=get(servlet,context,runtime,"legacy-flow");assertEquals(200,ready.get("status"));assertTrue(ready.get("body").toString().contains("JLEGACY"));
        }
    }
    private java.sql.Connection connect(ServerConfig config) throws Exception {
        var props=new Properties();props.setProperty("user","sa");props.setProperty("password","");
        return new org.h2.Driver().connect("jdbc:h2:file:"+config.dataDir.resolve("db/att-server")+";DB_CLOSE_ON_EXIT=FALSE",props);
    }
    @Test void malformedRowsAreAuditedOnceAndDoNotBlockMigrationAfterRestart() throws Exception {
        var config=config();
        try(JobStore store=new JobStore(config)) {
            for(int i=0;i<205;i++) {
                Job job=new Job("J"+String.format("%016X",i),"run","p","alice",new WorkerRequest(),new JobEvents(config.dataDir.resolve("events/"+i),1));
                store.insert(job,"{\"targetIdentity\":{\"type\":\"flow\",\"id\":\"valid-flow\"}}");
            }
        }
        try(var c=connect(config);var s=c.createStatement()) {
            s.execute("DELETE FROM job_targets");s.execute("UPDATE jobs SET target_search_version=0");
            s.execute("UPDATE jobs SET target_sort='Unknown target',request_summary='{}',target_identity='{bad-private-content',execution_spec='{invalid' WHERE job_id='J0000000000000032'");
            s.execute("UPDATE jobs SET execution_spec='{invalid' WHERE job_id='J0000000000000033'");
        }
        try(JobStore first=new JobStore(config)) {assertEquals(100,first.backfillSearchTargetsBatch());}
        try(JobStore resumed=new JobStore(config)) {
            assertEquals(100,resumed.backfillSearchTargetsBatch());assertEquals(5,resumed.backfillSearchTargetsBatch());assertEquals(0,resumed.backfillSearchTargetsBatch());
            assertEquals(204L,resumed.queryJobs(JobQuery.parse(Map.of("search","valid-flow"),"alice")).get("total"));
            assertEquals(205L,resumed.queryJobs(JobQuery.parse(Map.of(),"alice")).get("total"));
            assertEquals(1L,resumed.queryJobs(JobQuery.parse(Map.of("search","job:J0000000000000032"),"alice")).get("total"));
        }
        try(JobStore ignored=new JobStore(config);var c=connect(config);var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT outcome,detail FROM audit WHERE action='JOB_TARGET_INDEX' ORDER BY job_id")) {
                assertTrue(r.next());assertEquals("INVALID_METADATA_EMPTY",r.getString(1));assertEquals("invalidSnapshot=true;invalidIdentity=true",r.getString(2));
                assertTrue(r.next());assertEquals("INVALID_METADATA_FALLBACK",r.getString(1));assertFalse(r.next());
            }
        }
    }
    @Test void storageFailureRollsBackEntireBatchAndRetriesWithoutDuplicateDiagnostics() throws Exception {
        var config=config();legacy(config);
        try(JobStore store=new JobStore(config);var c=connect(config);var s=c.createStatement()) {
            s.execute("UPDATE jobs SET execution_spec='{invalid'");
            s.execute("ALTER TABLE audit ADD CONSTRAINT simulate_storage_failure CHECK (action<>'JOB_TARGET_INDEX')");
            assertThrows(java.sql.SQLException.class,store::backfillSearchTargetsBatch);
            try(var r=s.executeQuery("SELECT target_search_version,(SELECT COUNT(*) FROM job_targets) FROM jobs")){assertTrue(r.next());assertEquals(0,r.getInt(1));assertEquals(0,r.getInt(2));}
            s.execute("ALTER TABLE audit DROP CONSTRAINT simulate_storage_failure");
            assertEquals(1,store.backfillSearchTargetsBatch());assertEquals(0,store.backfillSearchTargetsBatch());
            assertEquals(1L,store.queryJobs(JobQuery.parse(Map.of("search","legacy-flow"),"alice")).get("total"));
            try(var r=s.executeQuery("SELECT COUNT(*) FROM audit WHERE action='JOB_TARGET_INDEX'")){r.next();assertEquals(1,r.getInt(1));}
        }
    }
    @Test void explicitExactIdKeepsFiltersCountsAndPaginationDuringPendingMigration() throws Exception {
        var config=config();legacy(config);
        try(JobStore store=new JobStore(config)) {
            for(String search:List.of("JLEGACY","JLEG","legacy-flow"))assertThrows(JobStore.TargetSearchPendingException.class,()->store.queryJobs(JobQuery.parse(Map.of("search",search),"alice")));
            var exact=Map.of("search","job:JLEGACY","groupBy","target","packageId","p","principalMode","mine","status","PASS","limit","1");
            var found=store.queryJobs(JobQuery.parse(exact,"alice"));assertEquals(1L,found.get("total"));assertNull(found.get("nextOffset"));assertEquals(1,((List<?>)found.get("items")).size());
            assertEquals(1L,((Map<?,?>)((List<?>)found.get("groups")).get(0)).get("total"));
            assertEquals(0L,store.queryJobs(JobQuery.parse(exact,"bob")).get("total"));
            for(var entry:Map.of("packageId","other","status","FAIL","search","job:JLEG","pinnedOnly","true").entrySet()) {
                var filtered=new HashMap<>(exact);filtered.put(entry.getKey(),entry.getValue());assertEquals(0L,store.queryJobs(JobQuery.parse(filtered,"alice")).get("total"));
            }
            var next=new HashMap<>(exact);next.put("offset","1");var page=store.queryJobs(JobQuery.parse(next,"alice"));assertEquals(1L,page.get("total"));assertTrue(((List<?>)page.get("items")).isEmpty());
            store.backfillSearchTargetsBatch();assertEquals(1L,store.queryJobs(JobQuery.parse(exact,"alice")).get("total"));
            assertThrows(IllegalArgumentException.class,()->JobQuery.parse(Map.of("search","job:"),"alice"));
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
