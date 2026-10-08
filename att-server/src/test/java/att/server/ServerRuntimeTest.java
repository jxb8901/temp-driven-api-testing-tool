package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ServerRuntimeTest {
    @TempDir Path temp;
    @Test void launchesOneStructuredWorkerProcessForAnAcceptedJob() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("packages"));Path pkg=Files.createDirectory(allowed.resolve("broken-package"));
        Path javaBin=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+temp.resolve("data")+"\n  javaExecutable: "+javaBin+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("WEB-INF-lib"));
        addModuleJar(libs,"att-worker",Path.of("att-worker/target/classes"));addModuleJar(libs,"att-engine",Path.of("att-engine/target/classes"));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate=Path.of(element);if(Files.isRegularFile(candidate)&&candidate.toString().endsWith(".jar")&&!candidate.getFileName().toString().startsWith("att-worker-")&&!candidate.getFileName().toString().startsWith("att-engine-")) {
                Path target=libs.resolve(candidate.getFileName());try{Files.createSymbolicLink(target,candidate);}catch(Exception unsupported){Files.copy(candidate,target);}
            }
        }
        ServerRuntime runtime=new ServerRuntime(config,libs.toString());
        try {
            JsonNode request=ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"target\":{\"type\":\"template\",\"id\":\"missing\"}}");
            Map<String,Object> submitted=runtime.submit("debug",request,"ci-test");String id=(String)submitted.get("jobId");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);Map<String,Object> record=runtime.jobRecord(id);while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(record.get("status"))&&System.nanoTime()<deadline){Thread.sleep(20);record=runtime.jobRecord(id);}
            assertTrue(List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(record.get("status")),"Worker must produce a terminal result");while(!runtime.jobs.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(20);assertTrue(runtime.jobs.isEmpty(),"Completed Job objects should be evicted from memory");assertTrue(runtime.events(id).after(0).stream().anyMatch(e->"result".equals(e.get("event"))));
            assertEquals("debug",record.get("command"));
        } finally {runtime.close();}
    }
    @Test void cancellationCannotSlipBetweenWorkerLaunchAndProcessPublication() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name","").toLowerCase().contains("win"),"Uses a POSIX test launcher");
        Path allowed=Files.createDirectory(temp.resolve("cancel-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));
        Path launcher=temp.resolve("slow-java");Files.writeString(launcher,"#!/bin/sh\nsleep 60\n");launcher.toFile().setExecutable(true);
        Path configFile=temp.resolve("cancel-server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+temp.resolve("cancel-data")+"\n  javaExecutable: "+launcher+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(configFile);Path libs=Files.createDirectory(temp.resolve("cancel-WEB-INF-lib"));
        CountDownLatch launchEntered=new CountDownLatch(1),allowLaunch=new CountDownLatch(1);
        AtomicReference<Process> launched=new AtomicReference<>();
        ServerRuntime runtime=new ServerRuntime(config,libs.toString(),builder->{
            launchEntered.countDown();
            try {if(!allowLaunch.await(5,TimeUnit.SECONDS))throw new java.io.IOException("test launch gate timed out");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException("test launch gate interrupted",e);}
            Process process=builder.start();launched.set(process);return process;
        });
        try {
            Map<String,Object> submitted=runtime.submit("validate",ServerRuntime.JSON.readTree("{\"packageId\":\"p\"}"),"ci-test");
            String id=(String)submitted.get("jobId");assertTrue(launchEntered.await(5,TimeUnit.SECONDS),"Worker launcher should reach the process-publication boundary");
            FutureTask<Void> cancel=new FutureTask<>(()->{runtime.cancel(id,"ci-test");return null;});new Thread(cancel,"test-job-cancel").start();
            Thread.sleep(100);assertFalse(cancel.isDone(),"Cancellation must wait while launch and process publication are atomic");
            allowLaunch.countDown();cancel.get(5,TimeUnit.SECONDS);
            assertEquals("CANCELLED",runtime.jobRecord(id).get("status"));
            assertNotNull(launched.get());assertFalse(launched.get().isAlive(),"Cancellation must terminate the published Worker process");
        } finally {allowLaunch.countDown();runtime.close();}
    }

    @Test void cancellationAndWorkerCompletionSerializeWithoutMonitorInversion() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("race-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));Path yaml=temp.resolve("race-server.yaml");
        Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("race-data")+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve("race-libs")).toString());
        try {
            for(int i=0;i<20;i++) {
                String id=String.format("J%016X",i+1);Job job=new Job(id,"run","p","test",new att.worker.WorkerRequest(),new JobEvents(runtime.config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl"),100));
                runtime.store.insert(job,"{}");runtime.jobs.put(id,job);CountDownLatch start=new CountDownLatch(1);
                FutureTask<Void> cancel=new FutureTask<>(()->{start.await();runtime.cancel(id,"test");return null;});FutureTask<Void> finish=new FutureTask<>(()->{start.await();runtime.finish(job,"PASS",0);return null;});
                Thread a=new Thread(cancel),b=new Thread(finish);a.start();b.start();start.countDown();cancel.get(3,TimeUnit.SECONDS);finish.get(3,TimeUnit.SECONDS);
                assertTrue(job.terminal());
            }
        } finally {runtime.close();}
    }

    @Test void publicResultsReplacePackageAndOutputRootsWithLogicalReferences() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("redact-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));Path yaml=temp.resolve("redact-server.yaml");
        Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("redact-data")+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve("redact-libs")).toString());
        try {
            String id="JABCDEF0123456789";Path output=runtime.config.dataDir.resolve("jobs").resolve(id).resolve("output");Files.createDirectories(output);
            Job job=new Job(id,"run","p","test",new att.worker.WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));job.resultJson=ServerRuntime.JSON.writeValueAsString(Map.of("summary",pkg.toRealPath().resolve("case.json").toString(),"artifact",output.resolve("report.xlsx").toString()));
            runtime.store.insert(job,"{}");runtime.jobs.put(id,job);runtime.finish(job,"PASS",0);var response=ServerRuntime.JSON.valueToTree(runtime.resultRecord(id));String encoded=response.toString();
            assertFalse(encoded.contains(pkg.toString()));assertFalse(encoded.contains(output.toString()));assertTrue(encoded.contains("package:case.json"));assertTrue(encoded.contains("artifact:report.xlsx"));
        } finally {runtime.close();}
    }

    @Test void rejectsPackageRootReplacedBySymlinkAfterServerInitialization() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name","").toLowerCase().contains("win"),"Uses POSIX symlink behavior");
        Path allowed=Files.createDirectory(temp.resolve("symlink-allowed"));Path outside=Files.createDirectory(temp.resolve("symlink-outside"));
        Path pkg=Files.createDirectory(allowed.resolve("package"));Path yaml=temp.resolve("symlink-server.yaml");
        Files.writeString(yaml,"server:\n  dataDir: "+temp.resolve("symlink-data")+"\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerRuntime runtime=new ServerRuntime(ServerConfig.load(yaml),Files.createDirectory(temp.resolve("symlink-libs")).toString());
        try {
            Files.move(pkg,allowed.resolve("package-original"));Files.createSymbolicLink(pkg,outside);
            JsonNode request=ServerRuntime.JSON.readTree("{\"packageId\":\"p\"}");
            IllegalArgumentException rejected=assertThrows(IllegalArgumentException.class,()->runtime.submit("validate",request,"test"));
            assertTrue(rejected.getMessage().contains("original allowed root"));assertTrue(runtime.store.list(100).isEmpty());
        } finally {runtime.close();}
    }

    private static void addModuleJar(Path lib,String name,Path classes)throws Exception {
        Path target=lib.resolve(name+".jar");try(OutputStream file=Files.newOutputStream(target);JarOutputStream jar=new JarOutputStream(file);var paths=Files.walk(classes)) {
            paths.filter(Files::isRegularFile).forEach(path->{try{jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));Files.copy(path,jar);jar.closeEntry();}catch(Exception e){throw new IllegalStateException(e);}});
        }
    }
}
