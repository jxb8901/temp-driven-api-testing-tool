package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real child Workers + disk H2, opt-in only. No mock scheduler or simulated TPS. */
@EnabledIfSystemProperty(named="att.jobs.concurrentBenchmark",matches="true")
class JobTargetConcurrencyBenchmarkTest {
    @TempDir Path temp;
    private Connection connect(ServerConfig config) throws Exception {
        var p=new Properties();p.setProperty("user","sa");p.setProperty("password","");
        return new org.h2.Driver().connect("jdbc:h2:file:"+config.dataDir.resolve("db/att-server")+";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0",p);
    }
    private long pending(ServerConfig config) throws Exception {
        try(var c=connect(config);var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM jobs WHERE target_search_version=0")){r.next();return r.getLong(1);}
    }
    private void seed(ServerConfig config,int size) throws Exception {
        var run=ServerRuntime.JSON.createObjectNode();run.put("schemaVersion",RunDefinition.VERSION);run.put("executionType","run");run.put("packageId","p");
        var cases=run.putObject("selection").putArray("caseIds");for(int i=0;i<2000;i++)cases.add("suite-case-"+i);
        var load=ServerRuntime.JSON.createObjectNode();load.put("executionType","load");load.put("packageId","p");
        var workloads=load.putObject("scenario").putArray("workloads");for(int w=0;w<10;w++){var mix=workloads.addObject().put("id","w"+w).putArray("mix");for(int i=0;i<20;i++)mix.addObject().putObject("target").put("type","flow").put("id","target-"+w+"-"+i);}
        String[] specs={null,new JobExecutionSpec(run,"a".repeat(64),null).json(),new JobExecutionSpec(load,"a".repeat(64),null).json()};
        try(var c=connect(config);var p=c.prepareStatement("INSERT INTO jobs(job_id,command,package_id,principal,status,created_at,att_version,request_summary,execution_spec,target_identity,target_sort,target_group) VALUES(?,'run','p','alice','PASS','2026-10-23T00:00:00Z','4.2.4','{}',?,'{\"type\":\"flow\",\"id\":\"ordinary\"}','ordinary','ordinary')")) {
            c.setAutoCommit(false);
            for(int i=0;i<size;i++){p.setString(1,String.format("J%016X",i));p.setString(2,specs[i%100==0?1:i%100==1?2:0]);p.addBatch();if(i%100==99){p.executeBatch();c.commit();}}p.executeBatch();c.commit();
        }
    }
    private void jobsApi(ServerRuntime runtime) throws Exception {
        jakarta.servlet.ServletContext context=proxy(jakarta.servlet.ServletContext.class,(self,method,args)->method.getName().equals("getAttribute")?runtime:null);
        ApiServlet servlet=new ApiServlet();servlet.init(proxy(jakarta.servlet.ServletConfig.class,(self,method,args)->method.getName().equals("getServletContext")?context:null));
        var body=new java.io.ByteArrayOutputStream();var status=new AtomicInteger(200);
        var stream=new jakarta.servlet.ServletOutputStream(){public boolean isReady(){return true;}public void setWriteListener(jakarta.servlet.WriteListener listener){}public void write(int value){body.write(value);}public void close(){}};
        var request=proxy(jakarta.servlet.http.HttpServletRequest.class,(self,method,args)->switch(method.getName()){
            case "getServletContext" -> context;case "getPathInfo" -> "/jobs";case "getUserPrincipal" -> (java.security.Principal)()->"alice";
            case "getParameter" -> "groupBy".equals(args[0])?"target":null;case "isUserInRole" -> runtime.config.authorization.userRole.equals(args[0]);default -> null;
        });
        var response=proxy(jakarta.servlet.http.HttpServletResponse.class,(self,method,args)->{switch(method.getName()){case "setStatus" -> status.set((Integer)args[0]);case "getOutputStream" -> {return stream;}}return null;});
        servlet.doGet(request,response);assertEquals(200,status.get());assertEquals(100,ServerRuntime.JSON.readTree(body.toByteArray()).path("items").size());
    }
    private static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
    private void awaitJob(ServerRuntime runtime,String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(runtime.jobRecord(id).get("status"))&&System.nanoTime()<deadline)Thread.sleep(10);
        assertEquals("PASS",runtime.jobRecord(id).get("status"),String.valueOf(runtime.resultRecord(id)));
    }
    private static double p95(List<Double> values){var sorted=new ArrayList<>(values);Collections.sort(sorted);return sorted.get(Math.min(sorted.size()-1,(int)Math.ceil(sorted.size()*.95)-1));}
    private static double ms(long nanos){return nanos/1e6;}
    @Test void indexingWithActiveLoadAndRun() throws Exception {
        Path libs=Files.createDirectories(temp.resolve("libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);
        int size=Integer.getInteger("att.jobs.concurrentBenchmark.size",10000),rounds=Integer.getInteger("att.jobs.concurrentBenchmark.rounds",2);
        System.out.println("CONCURRENT environment="+System.getProperty("os.name")+" "+System.getProperty("os.arch")+" Java="+System.getProperty("java.version")+" cpus="+Runtime.getRuntime().availableProcessors()+" history="+size);
        for(int round=0;round<rounds;round++)for(String mode:(round%2==0?List.of("disabled","indexing"):List.of("indexing","disabled"))) {
            Path root=Files.createDirectories(temp.resolve(mode+round)),pkg=Files.createDirectories(root.resolve("pkg"));
            Files.createDirectories(pkg.resolve("testcase"));Files.createDirectories(pkg.resolve("templates/BENCH"));Files.createDirectories(pkg.resolve("load"));
            Files.writeString(pkg.resolve("templates/BENCH/template.yaml"),"schemaVersion: att-template/v3.6\nname: BENCH\ndescription: Concurrent target migration benchmark\nactions:\n  event:\n    type: log\n    message: benchmark\n");
            Files.writeString(pkg.resolve("templates/BENCH/debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs: {}\n");
            Files.writeString(pkg.resolve("load/bench.yaml"),"schemaVersion: att-load/v1.9\nworkloads:\n  - id: benchmark\n    target: {type: template, id: BENCH}\n    load:\n      arrivalRate: 100/s\n      duration: 20s\n      maxConcurrent: 32\n      overloadPolicy: drop\nevidence:\n  mode: metrics\n  maxSamples: 10\n");
            Path runtimeConfig=Files.createDirectories(root.resolve("config")).resolve("config.yaml");
            Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\ntestcase:\n  root: testcase\ntemplates:\n  root: templates\n");
            Path yaml=root.resolve("server.yaml");Files.writeString(yaml,"server:\n  dataDir: "+root.resolve("data")+"\n  runtimeConfigPath: "+runtimeConfig+"\nworkers:\n  maxConcurrent: 3\n  queuedLimit: 4\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots: ["+pkg+"]\n  entries:\n    p: "+pkg+"\n");
            ServerConfig config=ServerConfig.load(yaml);
            try(ServerRuntime runtime=new ServerRuntime(config,libs.toString())) {
                var field=ServerRuntime.class.getDeclaredField("targetIndexMaintenance");field.setAccessible(true);var production=(ScheduledExecutorService)field.get(runtime);production.shutdownNow();assertTrue(production.awaitTermination(5,TimeUnit.SECONDS));
                seed(config,size);
                // Start measured migration with admissions, rather than consuming history during fixture setup.
                var indexer=Executors.newSingleThreadScheduledExecutor();var readers=Executors.newSingleThreadExecutor();var stop=new AtomicBoolean();var error=new AtomicReference<Throwable>();
                var api=Collections.synchronizedList(new ArrayList<Double>());var progress=Collections.synchronizedList(new ArrayList<Long>());
                var ids=new ArrayList<String>();var admission=new ArrayList<Double>();
                try {
                    if(mode.equals("indexing"))indexer.scheduleWithFixedDelay(()->{try{runtime.store.backfillSearchTargetsBatch();}catch(Throwable e){error.compareAndSet(null,e);}},100,100,TimeUnit.MILLISECONDS);
                    var reader=readers.submit(()->{try{while(!stop.get()){long start=System.nanoTime();jobsApi(runtime);api.add(ms(System.nanoTime()-start));progress.add(size-pending(config));Thread.sleep(100);}}catch(Throwable e){error.compareAndSet(null,e);}});
                    long start=System.nanoTime();String loadId=(String)runtime.submit("load",ServerRuntime.JSON.valueToTree(Map.of("packageId","p","runId","concurrent-load","scenario","load/bench.yaml")),"alice").get("jobId");admission.add(ms(System.nanoTime()-start));ids.add(loadId);
                    for(int i=0;i<6;i++) {
                        start=System.nanoTime();String id=(String)runtime.submit("run",ServerRuntime.JSON.valueToTree(Map.of("packageId","p","runId","concurrent-run-"+i,"target",Map.of("type","template","id","BENCH"))),"alice").get("jobId");admission.add(ms(System.nanoTime()-start));ids.add(id);
                        awaitJob(runtime,id);Thread.sleep(500);
                    }
                    awaitJob(runtime,loadId);
                    var first=new ArrayList<Double>();for(String id:ids){var timings=ServerRuntime.JSON.valueToTree(runtime.jobRecord(id)).path("performance").path("timings");assertTrue(timings.has("firstMeaningfulEventMs"));first.add(timings.path("firstMeaningfulEventMs").asDouble());}
                    Path summary;try(var paths=Files.walk(runtime.outputDirectoryForJob(loadId))){summary=paths.filter(p->p.getFileName().toString().equals("load-summary.json")).findFirst().orElseThrow();}
                    var metrics=ServerRuntime.JSON.readTree(Files.readString(summary)).path("metrics");
                    stop.set(true);reader.get(10,TimeUnit.SECONDS);assertNull(error.get(),String.valueOf(error.get()));
                    System.out.println("CONCURRENT result "+ServerRuntime.JSON.writeValueAsString(Map.of("mode",mode,"round",round,"admissionMs",admission,"admissionP95Ms",p95(admission),"firstMeaningfulEventMs",first,"firstEventP95Ms",p95(first),"jobsQueryP95Ms",p95(api),"jobsQuerySamples",api.size(),"migrationProgress",progress,"loadMetrics",metrics)));
                } finally {stop.set(true);indexer.shutdownNow();readers.shutdownNow();assertTrue(indexer.awaitTermination(10,TimeUnit.SECONDS));assertTrue(readers.awaitTermination(10,TimeUnit.SECONDS));}
            }
        }
    }
}
