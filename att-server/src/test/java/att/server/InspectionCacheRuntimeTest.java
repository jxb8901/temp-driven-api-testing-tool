package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class InspectionCacheRuntimeTest {
    @TempDir Path temp;

    @Test void prefersWarmIdleWorkersButStartsAnEquivalentWorkerForConcurrentDemand() throws Exception {
        Path root=fixture("p");Path libs=libs();AtomicInteger starts=new AtomicInteger();
        try(ServerRuntime runtime=new ServerRuntime(config(root,2,""),libs.toString(),builder->{starts.incrementAndGet();return builder.start();})) {
            list(runtime,"p");list(runtime,"p");list(runtime,"p");
            assertEquals(1,starts.get(),"Serial requests must reuse the existing package inspector");
            assertEquals(1,indexBuilds(runtime));
            Map<?,?> retained=(Map<?,?>)((List<?>)runtime.inspectionStatistics().get("workers")).get(0);
            assertEquals(3L,((Number)((Map<?,?>)retained.get("PROCESSING")).get("count")).longValue(),
                    "Metric deltas must merge current counters without losing unchanged index statistics");
            ExecutorService clients=Executors.newFixedThreadPool(4);
            try {
                CountDownLatch start=new CountDownLatch(1);
                List<Future<?>> requests=new ArrayList<>();
                for(int i=0;i<4;i++)requests.add(clients.submit(()->{start.await();list(runtime,"p");return null;}));
                start.countDown();for(Future<?> request:requests)request.get(15,TimeUnit.SECONDS);
                assertEquals(2,starts.get(),"A busy affinity Worker must not serialize concurrent clients");
                assertEquals(2,indexBuilds(runtime));
            } finally {clients.shutdownNow();}
            list(runtime,"p");assertEquals(2,indexBuilds(runtime));
        }
    }

    @Test void startupWarmupIsNonblockingAndRetryBoundedForUnavailableWorkers() throws Exception {
        Path root=fixture("unavailable");AtomicInteger attempts=new AtomicInteger();
        ServerConfig config=config(root,1,"    warmup:\n      enabled: true\n      keys: [{packageId: p}]\n");
        long start=System.nanoTime();
        ServerRuntime runtime=new ServerRuntime(config,libs().toString(),builder->{attempts.incrementAndGet();throw new IOException("Unavailable");});
        try {
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000,"Warmup must not wait for a Worker during startup");
            await(()->attempts.get()==3);
            Thread.sleep(1200);assertEquals(3,attempts.get());
        } finally {runtime.close();}
        Thread.sleep(300);assertEquals(3,attempts.get(),"Shutdown cancels background work");
    }

    @Test void preloadsAtMostTwoKeysAndWarmsAgainAfterAProcessCrash() throws Exception {
        Path root=fixture("warm-p");Path q=fixture("warm-q");
        config(root,1,"    warmup:\n      enabled: true\n      keys: [{packageId: p}, {packageId: q}]\n");
        Path configFile=temp.resolve("server.yaml");
        Files.writeString(configFile,Files.readString(configFile).replace("    p: "+yaml(root),"    q: "+yaml(q)+"\n    p: "+yaml(root)));
        List<Process> processes=new CopyOnWriteArrayList<>();
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(configFile),libs().toString(),builder->{Process process=builder.start();processes.add(process);return process;})) {
            await(()->indexBuilds(runtime)>=2);
            list(runtime,"p");list(runtime,"q");
            assertEquals(2,indexBuilds(runtime),"Preloading two keys must not evict its own indexes");
            assertEquals(1,processes.size());
            Process first=processes.get(0);first.destroyForcibly();assertTrue(first.waitFor(5,TimeUnit.SECONDS));
            await(()->processes.size()==2&&indexBuilds(runtime)>=2);
            list(runtime,"p");list(runtime,"q");assertEquals(2,indexBuilds(runtime));
            assertEquals(2,processes.size());
        }
        for(Process process:processes)assertFalse(process.isAlive());
    }

    @Test void optionalTelemetryCannotConsumeTheConfiguredResponseBudget() throws Exception {
        Path root=fixture("small-response");config(root,1,"");
        Path file=temp.resolve("server.yaml");
        Files.writeString(file,Files.readString(file).replace("timeoutMs: 10000", "timeoutMs: 10000\n    maxResponseBytes: 2048\n    maxSourceBytes: 1024"));
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(file),libs().toString(),ProcessBuilder::start)) {
            list(runtime,"p");list(runtime,"p");
            assertTrue(((Map<?,?>)((List<?>)runtime.inspectionStatistics().get("workers")).get(0)).isEmpty(),
                    "Optional metrics are unavailable when the response budget cannot contain them");
        }
    }

    @Test void timeoutDiscardsAffinityAndTheNextInteractiveRequestUsesAReplacement() throws Exception {
        Path root=fixture("timeout");ServerConfig initial=config(root,1,"");
        Path configFile=temp.resolve("server.yaml");Files.writeString(configFile,Files.readString(configFile).replace("timeoutMs: 10000","timeoutMs: 2000"));
        AtomicInteger starts=new AtomicInteger();
        try(ServerRuntime runtime=new ServerRuntime(ServerConfig.load(configFile),libs().toString(),builder->{
            if(starts.getAndIncrement()==0)return new ProcessBuilder(initial.javaExecutable.toString(),"-cp",
                    System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                    SilentInspector.class.getName()).start();
            return builder.start();
        })) {
            assertThrows(ServerRuntime.InspectionTimeoutException.class,()->list(runtime,"p"));
            list(runtime,"p");assertEquals(2,starts.get());assertEquals(1,indexBuilds(runtime));
        }
    }

    public static class SilentInspector { public static void main(String[] args)throws Exception {Thread.sleep(30000);} }

    private void list(ServerRuntime runtime,String packageId)throws Exception {runtime.inspectResource(packageId,"list","template",null,null,1,null,"tester");}
    private long indexBuilds(ServerRuntime runtime) {
        long count=0;
        for(Object raw:(List<?>)runtime.inspectionStatistics().get("workers")) {
            Map<?,?> worker=(Map<?,?>)raw;Object metric=worker.get("INDEX_BUILD");
            if(metric instanceof Map)count+=((Number)((Map<?,?>)metric).get("count")).longValue();
        }
        return count;
    }
    private void await(java.util.function.BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(50);}
        fail("Timed out awaiting inspector state");
    }
    private ServerConfig config(Path root,int workers,String warm)throws Exception {
        Path file=temp.resolve("server.yaml");
        Path runtimeConfig=Files.createDirectories(temp.resolve("shared-config/config")).resolve("config.yaml");
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n  UAT: {}\n");
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Files.writeString(file,"server:\n  runtimeConfigPath: "+yaml(runtimeConfig)+"\n  dataDir: "+yaml(temp.resolve("data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  inspection:\n    maxConcurrent: "+workers+"\n    timeoutMs: 10000\n"+warm
                +"workers: {}\npackages:\n  allowedRoots: ["+yaml(temp)+"]\n  entries:\n    p: "+yaml(root)+"\n");
        return ServerConfig.load(file);
    }
    private Path fixture(String name)throws Exception {
        Path root=Files.createDirectories(temp.resolve(name));
        Files.createDirectories(root.resolve("testcase"));Files.createDirectories(root.resolve("templates/echo"));
        Files.writeString(root.resolve("templates/echo/template.yaml"),"schemaVersion: att-template/v3.6\nname: Echo\ndescription: Safe\nactions:\n  note:\n    type: log\n    message: safe\n");
        return root;
    }
    private Path libs()throws Exception {
        Path lib=Files.createDirectories(temp.resolve("lib"));
        for(String module:List.of("att-security","att-server-api","att-engine","att-worker")) {
            Path classes=Path.of(module,"target/classes");
            try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(lib.resolve(module+".jar")));var paths=Files.walk(classes)) {
                for(Path path:paths.filter(Files::isRegularFile).toList()){jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));Files.copy(path,jar);jar.closeEntry();}
            }
        }
        for(String value:System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")).split(java.io.File.pathSeparator)) {
            Path file=Path.of(value);if(Files.isRegularFile(file)&&value.endsWith(".jar")&&!file.getFileName().toString().startsWith("att-")) {
                Path target=lib.resolve(file.getFileName());if(!Files.exists(target))Files.createSymbolicLink(target,file.toAbsolutePath());
            }
        }
        return lib;
    }
    private static String yaml(Path value){return "'"+value.toAbsolutePath().toString().replace("'","''")+"'";}
}
