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
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ServerWarUiIT {
    @TempDir Path temp;

    private record PackagedExecutionCapture(String dispatch, String status, String resultStatus,
                                            List<String> artifactPaths, List<String> primaryReports,
                                            boolean loggedParityMarker) { }
    private record PackagedWarModeCapture(PackagedExecutionCapture run, PackagedExecutionCapture debug,
                                          PackagedExecutionCapture failedRun, PackagedExecutionCapture failedDebug,
                                          PackagedExecutionCapture cancelledRun, PackagedExecutionCapture cancelledDebug,
                                          PackagedExecutionCapture timedOutRun, PackagedExecutionCapture timedOutDebug) { }

    @Test void repeatedRedeployReleasesCompactorsAndJdbcWhilePreservingSchedulesAndJournals() throws Exception {
        Path war=Path.of(System.getProperty("att.server.war"));assertTrue(Files.isRegularFile(war));
        Path home=createScheduledLocalRuntime(),packages=Files.createDirectories(temp.resolve("redeploy-packages")),pkg=Files.createDirectory(packages.resolve("payments"));
        writeScheduledPackage(pkg,home);copySchemas(pkg);
        Path scenario=pkg.resolve("load/scenario.yaml");Files.writeString(scenario,Files.readString(scenario).replace("duration: 30s","duration: 1s"));
        Path config=Files.writeString(temp.resolve("redeploy-server.yaml"),"server:\n  runtimeConfigPath: "+yaml(home.resolve("config/config.yaml"))+"\n  dataDir: "+yaml(temp.resolve("redeploy-data"))+"\n  authenticationRequired: true\n  maxEventsPerJob: 100\n  inspection: {enabled: true}\n  scheduling: {enabled: true}\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  heapMaxMb: 128\n  executionPool: {enabled: true, minIdle: 1, maxIdle: 1, heapBudgetMb: 128, packageIds: [payments]}\npackages:\n  allowedRoots: ["+yaml(packages)+"]\n  entries: {payments: "+yaml(pkg)+"}\n");
        Path users=Files.writeString(temp.resolve("redeploy-users.xml"),"<tomcat-users><role rolename=\"ATT_USER\"/><user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/></tomcat-users>");
        String oldConfig=System.getProperty("att.server.config"),oldHome=System.getProperty("att.home");
        System.setProperty("att.server.config",config.toString());System.setProperty("att.home",home.toString());
        java.util.List<String> warnings=new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.logging.Logger logger=java.util.logging.Logger.getLogger("org.apache.catalina.loader.WebappClassLoaderBase");
        java.util.logging.Handler capture=new java.util.logging.Handler(){public void publish(java.util.logging.LogRecord r){warnings.add(r.getMessage()+" "+java.util.Arrays.toString(r.getParameters()));}public void flush(){}public void close(){}};
        logger.addHandler(capture);
        Tomcat tomcat=new Tomcat();tomcat.setBaseDir(temp.resolve("redeploy-tomcat").toString());tomcat.setPort(0);tomcat.getConnector();
        MemoryRealm realm=new MemoryRealm();realm.setPathname(users.toString());tomcat.getEngine().setRealm(realm);((StandardHost)tomcat.getHost()).setUnpackWARs(false);
        String jobId=null,scheduleId=null;
        try {
            tomcat.start();int port=tomcat.getConnector().getLocalPort();String api="/tools/att/api/v1",auth=basic("att"),origin="http://127.0.0.1:"+port;
            for(int cycle=0;cycle<3;cycle++) {
                org.apache.catalina.Context context=tomcat.addWebapp("/tools/att",war.toString());
                assertTrue(context.getState().isAvailable(),"Redeployed context must start in the same Tomcat JVM");
                Object runtime=context.getServletContext().getAttribute("att.server.runtime");
                List<Long> prewarmPids=awaitPrewarmedPids(runtime);
                assertEquals(1,prewarmPids.size(),"Each deployed WAR must own one READY prewarm Worker");
                if(jobId!=null) {
                    assertEquals("PASS",getJson(port,api+"/jobs/"+jobId,auth).path("status").asText());
                    assertEquals(scheduleId,getJson(port,api+"/schedules/"+scheduleId,auth).path("id").asText());
                    assertTrue(sseDataUntil(port,api+"/jobs/"+jobId+"/events",auth,"REDEPLOY_JOURNAL").contains("REDEPLOY_JOURNAL"));
                } else {
                    scheduleId=apiJsonResponse(port,"POST",api+"/schedules",auth,origin,Map.of("name","Redeploy schedule","packageId","payments","scenario","load/scenario.yaml","timezone","UTC","cron","0 2 * * *","enabled",false),201).path("id").asText();
                }
                jobId=apiJsonResponse(port,"POST",api+"/jobs/load",auth,origin,Map.of("packageId","payments","scenario","load/scenario.yaml"),202).path("jobId").asText();
                awaitArchive(port,api,jobId,auth);
                Class<?> runtimeClass=runtime.getClass();
                var eventsMethod=runtimeClass.getDeclaredMethod("events",String.class);eventsMethod.setAccessible(true);Object events=eventsMethod.invoke(runtime,jobId);
                var append=events.getClass().getDeclaredMethod("append",String.class,Map.class);append.setAccessible(true);
                var poolField=runtimeClass.getDeclaredField("eventCompactions");poolField.setAccessible(true);var pool=(java.util.concurrent.ThreadPoolExecutor)poolField.get(runtime);
                for(int event=0;event<256;event++)append.invoke(events,"log",Map.of("console","REDEPLOY_JOURNAL "+event));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(pool.getCompletedTaskCount()==0&&System.nanoTime()<deadline)Thread.sleep(10);
                assertTrue(pool.getCompletedTaskCount()>0,"Exercise the WAR-owned compactor before undeploy");
                ClassLoader oldLoader=context.getLoader().getClassLoader();
                tomcat.getHost().removeChild(context);
                assertTrue(pool.isTerminated(),"Compaction queue must drain before context destruction returns");
                long processDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(prewarmPids.stream().anyMatch(pid->ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                        &&System.nanoTime()<processDeadline)Thread.sleep(20);
                assertTrue(prewarmPids.stream().noneMatch(pid->ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)),
                        "Every prewarmed Worker must exit on WAR undeploy: "+prewarmPids);
                // Executor termination can precede the final thread exit by a scheduling turn.
                long threadDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                java.util.List<String> surviving;
                do {
                    surviving=Thread.getAllStackTraces().entrySet().stream()
                            .filter(entry->entry.getKey().isAlive()&&(entry.getKey().getContextClassLoader()==oldLoader||entry.getKey().getName().equals("att-server-event-compactor")))
                            .map(entry->entry.getKey().getName()+" "+java.util.Arrays.toString(entry.getValue())).toList();
                    if(surviving.isEmpty())break;
                    Thread.sleep(20);
                }while(System.nanoTime()<threadDeadline);
                assertTrue(surviving.isEmpty(),"Old WAR threads survived undeploy: "+surviving);
            }
            assertFalse(warnings.stream().anyMatch(message->message.contains("att-server-event-compactor")||message.contains("org.h2.Driver")),warnings.toString());
        } finally {
            try{tomcat.stop();tomcat.destroy();}finally{logger.removeHandler(capture);if(oldConfig==null)System.clearProperty("att.server.config");else System.setProperty("att.server.config",oldConfig);if(oldHome==null)System.clearProperty("att.home");else System.setProperty("att.home",oldHome);}
        }
    }

    @Test void packagedWarRunAndDebugKeepColdEquivalentResultsWhenLeasingOneShotWorkers() throws Exception {
        Path war=Path.of(System.getProperty("att.server.war"));assertTrue(Files.isRegularFile(war));
        Path home=createScheduledLocalRuntime();
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"environments: {SIT: {}}\ntestcase: {root: testcase}\ntemplates: {root: templates}\n");
        Path allowed=Files.createDirectories(temp.resolve("parity-allowed"));
        Path pkg=Files.createDirectories(allowed.resolve("parity"));
        Path template=Files.createDirectories(pkg.resolve("templates/PARITY"));
        Files.writeString(template.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: PARITY\n"
                +"description: Packaged WAR cold and warm execution parity\nactions:\n"
                +"  message: {type: log, message: packaged-war-parity}\n");
        Files.writeString(template.resolve("debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs: {}\n");
        Path failingTemplate=Files.createDirectories(pkg.resolve("templates/PARITY_FAIL"));
        Files.writeString(failingTemplate.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: PARITY_FAIL\n"
                +"description: Packaged WAR cold and warm failure parity\nactions:\n"
                +"  message: {type: log, message: packaged-war-parity}\n"
                +"  fail: {type: assert, assert: '#{1 == 2}'}\n");
        Files.writeString(failingTemplate.resolve("debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs: {}\n");
        for(String target:List.of("HOLD","TIMEOUT")) {
            Path targetTemplate=Files.createDirectories(pkg.resolve("templates").resolve(target));
            String timeout=target.equals("TIMEOUT")?", timeoutMs: 300":"";
            Files.writeString(targetTemplate.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: "+target+"\n"
                    +"description: Packaged WAR "+target.toLowerCase(java.util.Locale.ROOT)+" parity\nactions:\n"
                    +"  hold: {type: tool, call: '#{hold()}'"+timeout+"}\n");
            Files.writeString(targetTemplate.resolve("debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs: {}\n");
        }
        Path loadDirectory=Files.createDirectories(pkg.resolve("load"));
        Files.writeString(loadDirectory.resolve("smoke.yaml"),"schemaVersion: att-load/v1.6\nworkloads:\n"
                +"  - id: one-shot-control\n    target: {type: template, id: PARITY}\n"
                +"    load: {users: 1, duration: 1s}\n");
        Path holdScript=Files.createDirectories(pkg.resolve("tools")).resolve("hold.sh");
        Files.writeString(holdScript,"#!/bin/sh\nprintf '%s' \"$$\" > \"$ATT_ROOT_DIR/tools/child.pid\"\n"
                +"touch \"$ATT_ROOT_DIR/tools/started\"\nexec sleep 60\n");
        holdScript.toFile().setExecutable(true);
        Files.createDirectories(pkg.resolve("testcase"));
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=Files.newOutputStream(pkg.resolve("testcase/parity.xlsx"))) {
            var sheet=book.createSheet("Cases");var header=sheet.createRow(0);header.createCell(0).setCellValue("ID");
            header.createCell(1).setCellValue("Tags");header.createCell(2).setCellValue("Template");
            var row=sheet.createRow(1);row.createCell(0).setCellValue("C1");row.createCell(1).setCellValue("smoke");
            row.createCell(2).setCellValue("name: PARITY");book.write(out);
        }
        Files.writeString(pkg.resolve("testcase/parity.yaml"),"schemaVersion: att-sidecar/v2.2\nid: parity\nexcel:\n"
                +"  sheet: default=Cases\n  caseId: ID\n  tags: Tags\nstages:\n  - key: main\n    template: Template\n    required: true\n");
        Files.writeString(home.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"environments: {SIT: {}}\ntestcase: {root: testcase}\ntemplates: {root: templates}\n"
                +"tools:\n  hold:\n    name: Hold\n    description: Packaged WAR lifecycle parity fixture\n"
                +"    command: [./tools/hold.sh]\n    stdoutFormat: text\n    arguments: {}\n");
        copySchemas(pkg);
        new att.api.DefaultAttService().snapshot(new att.api.SnapshotRequest(pkg,home.resolve("config/config.yaml"),
                "SIT",List.of(Path.of("testcase/parity.xlsx")),null,Set.of(),false));
        Path users=Files.writeString(temp.resolve("parity-tomcat-users.xml"),
                "<tomcat-users><role rolename=\"ATT_USER\"/><user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/></tomcat-users>");
        String oldConfig=System.getProperty("att.server.config"),oldHome=System.getProperty("att.home");
        try {
            PackagedWarModeCapture cold=exercisePackagedWarMode(war,home,allowed,pkg,users,false);
            PackagedWarModeCapture warm=exercisePackagedWarMode(war,home,allowed,pkg,users,true);
            assertPackagedParity(cold.run(),warm.run(),"RUN","PASS");
            assertPackagedParity(cold.debug(),warm.debug(),"DEBUG","PASS");
            assertPackagedParity(cold.failedRun(),warm.failedRun(),"failed RUN","FAIL");
            assertPackagedParity(cold.failedDebug(),warm.failedDebug(),"failed DEBUG","FAIL");
            assertPackagedParity(cold.cancelledRun(),warm.cancelledRun(),"cancelled RUN","CANCELLED",false);
            assertPackagedParity(cold.cancelledDebug(),warm.cancelledDebug(),"cancelled DEBUG","CANCELLED",false);
            assertPackagedParity(cold.timedOutRun(),warm.timedOutRun(),"timed-out RUN",null,false);
            assertPackagedParity(cold.timedOutDebug(),warm.timedOutDebug(),"timed-out DEBUG",null,false);
            assertNotEquals("PASS",cold.timedOutRun().status(),"Cold RUN Tool timeout must be terminal failure");
            assertNotEquals("PASS",cold.timedOutDebug().status(),"Cold DEBUG Tool timeout must be terminal failure");
            assertEquals("cold",cold.run().dispatch());assertEquals("cold",cold.debug().dispatch());
            assertEquals("warm",warm.run().dispatch());assertEquals("warm",warm.debug().dispatch());
        } finally {
            if(oldConfig==null)System.clearProperty("att.server.config");else System.setProperty("att.server.config",oldConfig);
            if(oldHome==null)System.clearProperty("att.home");else System.setProperty("att.home",oldHome);
        }
    }

    private PackagedWarModeCapture exercisePackagedWarMode(Path war,Path home,Path allowed,Path pkg,Path users,
                                                           boolean prewarm) throws Exception {
        Path config=Files.writeString(temp.resolve("parity-server-"+prewarm+".yaml"),"server:\n  runtimeConfigPath: "+yaml(home.resolve("config/config.yaml"))
                +"\n  dataDir: "+yaml(temp.resolve("parity-data-"+prewarm))+"\n  javaExecutable: "+yaml(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java"))
                +"\n  authenticationRequired: true\n  inspection: {enabled: true, maxConcurrent: 1, queuedLimit: 4, timeoutMs: 30000, heapMaxMb: 256}\n"
                +"workers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  heapMaxMb: 128\n"
                +(prewarm?"  executionPool: {enabled: true, minIdle: 1, maxIdle: 1, heapBudgetMb: 128, packageIds: [parity]}\n":"")
                +"packages:\n  allowedRoots: ["+yaml(allowed)+"]\n  entries: {parity: "+yaml(pkg)+"}\n");
        System.setProperty("att.server.config",config.toString());System.setProperty("att.home",home.toString());
        Tomcat tomcat=new Tomcat();tomcat.setBaseDir(temp.resolve("parity-tomcat-"+prewarm).toString());tomcat.setPort(0);tomcat.getConnector();
        MemoryRealm realm=new MemoryRealm();realm.setPathname(users.toString());tomcat.getEngine().setRealm(realm);
        ((StandardHost)tomcat.getHost()).setUnpackWARs(false);
        org.apache.catalina.Context context=tomcat.addWebapp("/tools/att",war.toString());
        try {
            tomcat.start();assertTrue(context.getState().isAvailable());
            int port=tomcat.getConnector().getLocalPort();String api="/tools/att/api/v1",auth=basic("att"),origin="http://127.0.0.1:"+port;
            Object runtime=context.getServletContext().getAttribute("att.server.runtime");
            long runLease=prewarm?awaitPrewarmedPids(runtime).get(0):0L;
            PackagedExecutionCapture run=executePackagedDraft(port,api,auth,origin,"run","PARITY","PASS",prewarm);
            long debugLease=prewarm?awaitPrewarmedPids(runtime).get(0):0L;
            if(prewarm)assertNotEquals(runLease,debugLease,"A completed packaged RUN must be replaced before DEBUG dispatch");
            PackagedExecutionCapture debug=executePackagedDraft(port,api,auth,origin,"debug","PARITY","PASS",prewarm);
            long failedRunLease=prewarm?awaitPrewarmedPids(runtime).get(0):0L;
            if(prewarm)assertNotEquals(debugLease,failedRunLease,"A completed packaged DEBUG must be replaced before a failing RUN");
            PackagedExecutionCapture failedRun=executePackagedDraft(port,api,auth,origin,"run","PARITY_FAIL","FAIL",prewarm);
            long failedDebugLease=prewarm?awaitPrewarmedPids(runtime).get(0):0L;
            if(prewarm)assertNotEquals(failedRunLease,failedDebugLease,"A failed packaged RUN must still consume exactly one Worker Job");
            PackagedExecutionCapture failedDebug=executePackagedDraft(port,api,auth,origin,"debug","PARITY_FAIL","FAIL",prewarm);
            if(prewarm)assertNotEquals(failedDebugLease,awaitPrewarmedPids(runtime).get(0),
                    "A failed packaged DEBUG must also be replaced after its one Job");
            PackagedExecutionCapture cancelledRun=executePackagedInterruptedDraft(port,api,auth,origin,pkg,runtime,
                    "run","HOLD",true,prewarm);
            PackagedExecutionCapture cancelledDebug=executePackagedInterruptedDraft(port,api,auth,origin,pkg,runtime,
                    "debug","HOLD",true,prewarm);
            PackagedExecutionCapture timedOutRun=executePackagedInterruptedDraft(port,api,auth,origin,pkg,runtime,
                    "run","TIMEOUT",false,prewarm);
            PackagedExecutionCapture timedOutDebug=executePackagedInterruptedDraft(port,api,auth,origin,pkg,runtime,
                    "debug","TIMEOUT",false,prewarm);
            long loadLease=prewarm?awaitPrewarmedPids(runtime).get(0):0L;
            String loadDispatch=executePackagedOneShotControlJob(port,api,auth,origin,"load",
                    Map.of("packageId","parity","scenario","load/smoke.yaml","environment","SIT"),"PASS");
            assertEquals("cold",loadDispatch,"LOAD must stay on its cold one-shot path when prewarm is enabled");
            if(prewarm)assertEquals(loadLease,awaitPrewarmedPids(runtime).get(0),"LOAD must leave the READY RUN/DEBUG Worker untouched");
            long validateLease=prewarm?awaitPrewarmedPids(runtime).get(0):0L;
            Files.deleteIfExists(pkg.resolve("tools/started"));
            String validateDispatch=executePackagedOneShotControlJob(port,api,auth,origin,"validate",
                    Map.of("packageId","parity","validationScope","package"),"PASS");
            assertEquals("cold",validateDispatch,"Public Validate must retain its durable cold one-shot semantics");
            assertFalse(Files.exists(pkg.resolve("tools/started")),"Public Validate must not invoke package Tools");
            if(prewarm)assertEquals(validateLease,awaitPrewarmedPids(runtime).get(0),"Validate must leave the READY RUN/DEBUG Worker untouched");
            return new PackagedWarModeCapture(run,debug,failedRun,failedDebug,cancelledRun,cancelledDebug,timedOutRun,timedOutDebug);
        } finally {try{tomcat.stop();}finally{tomcat.destroy();}}
    }

    private String executePackagedOneShotControlJob(int port,String api,String auth,String origin,String command,
                                                    Map<String,Object> request,String expectedStatus) throws Exception {
        JsonNode accepted=apiJsonResponse(port,"POST",api+"/jobs/"+command,auth,origin,request,202);
        String id=accepted.path("jobId").asText();
        JsonNode job=awaitTerminalJobStatus(port,api+"/jobs/"+id,auth);
        assertEquals(expectedStatus,job.path("status").asText(),command+" terminal result: "+getJson(port,api+"/jobs/"+id+"/result",auth));
        return job.path("performance").path("workerDispatch").asText();
    }

    private PackagedExecutionCapture executePackagedDraft(int port,String api,String auth,String origin,
                                                          String type,String template,String expectedStatus,
                                                          boolean prewarm) throws Exception {
        Map<String,Object> definition=type.equals("run")
                ?Map.of("schemaVersion","att-execution/v1","executionType","run","packageId","parity","environment","SIT",
                        "target",Map.of("type","template","id",template),"input",Map.of(),"options",Map.of("debug",false))
                :Map.of("packageId","parity","target",Map.of("type","template","id",template),"input",Map.of());
        JsonNode draft=apiJsonResponse(port,"POST",api+(type.equals("run")?"/drafts/run":"/drafts/debug"),auth,origin,definition,201);
        JsonNode accepted=apiJsonResponse(port,"POST",api+"/jobs/"+type,auth,origin,
                Map.of("packageId","parity","draftId",draft.path("draftId").asText()),202);
        String id=accepted.path("jobId").asText(),jobPath=api+"/jobs/"+id;
        JsonNode job=awaitJobStatus(port,jobPath,auth,expectedStatus);
        String dispatch=job.path("performance").path("workerDispatch").asText();
        assertEquals(prewarm?"warm":"cold",dispatch,type+" must use the expected dispatch path");
        String logData=sseDataUntil(port,jobPath+"/events",auth,"packaged-war-parity");
        assertTrue(logData.contains("packaged-war-parity"),type+" must preserve the packaged Worker log event");
        JsonNode result=getJson(port,jobPath+"/result",auth);
        assertEquals(expectedStatus,result.path("job").path("status").asText(),result.toString());
        JsonNode archive=awaitArchive(port,api,id,auth);
        List<String> artifactPaths=java.util.stream.StreamSupport.stream(archive.path("items").spliterator(),false)
                .map(item->item.path("path").asText()).sorted().toList();
        List<String> primaryReports=java.util.stream.StreamSupport.stream(archive.path("primaryReports").spliterator(),false)
                .map(item->item.path("path").asText()).sorted().toList();
        assertFalse(artifactPaths.isEmpty(),"A completed packaged Job must publish its verified artifact archive");
        return new PackagedExecutionCapture(dispatch,job.path("status").asText(),result.path("job").path("status").asText(),
                artifactPaths,primaryReports,true);
    }

    private PackagedExecutionCapture executePackagedInterruptedDraft(int port,String api,String auth,String origin,
                                                                     Path pkg,Object runtime,String type,String template,
                                                                     boolean cancel,boolean prewarm) throws Exception {
        Path started=pkg.resolve("tools/started"),childPid=pkg.resolve("tools/child.pid");
        Files.deleteIfExists(started);Files.deleteIfExists(childPid);
        long previousLease=prewarm?awaitPrewarmedPids(runtime).get(0):0L;
        Map<String,Object> definition=type.equals("run")
                ?Map.of("schemaVersion","att-execution/v1","executionType","run","packageId","parity","environment","SIT",
                        "target",Map.of("type","template","id",template),"input",Map.of(),"options",Map.of("debug",false))
                :Map.of("packageId","parity","target",Map.of("type","template","id",template),"input",Map.of());
        JsonNode draft=apiJsonResponse(port,"POST",api+(type.equals("run")?"/drafts/run":"/drafts/debug"),auth,origin,definition,201);
        JsonNode accepted=apiJsonResponse(port,"POST",api+"/jobs/"+type,auth,origin,
                Map.of("packageId","parity","draftId",draft.path("draftId").asText()),202);
        String id=accepted.path("jobId").asText(),jobPath=api+"/jobs/"+id;
        long startDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while((!Files.exists(started)||!Files.exists(childPid))&&System.nanoTime()<startDeadline)Thread.sleep(10);
        assertTrue(Files.exists(started)&&Files.exists(childPid),type+" "+template+" Tool must start before cancellation/timeout evidence");
        long toolPid=Long.parseLong(Files.readString(childPid).trim());
        if(cancel) {
            JsonNode cancelled=apiJsonResponse(port,"DELETE",api+"/jobs/"+id,auth,origin,null,200);
            assertEquals("CANCELLED",cancelled.path("status").asText());
        }
        JsonNode job=awaitTerminalJobStatus(port,jobPath,auth);
        String dispatch=job.path("performance").path("workerDispatch").asText();
        assertEquals(prewarm?"warm":"cold",dispatch,type+" must use the expected "+template+" dispatch path");
        JsonNode result=getJson(port,jobPath+"/result",auth);
        assertEquals(job.path("status").asText(),result.path("job").path("status").asText(),result.toString());
        JsonNode archive=awaitArchive(port,api,id,auth);
        List<String> artifactPaths=java.util.stream.StreamSupport.stream(archive.path("items").spliterator(),false)
                .map(item->item.path("path").asText()).sorted().toList();
        List<String> primaryReports=java.util.stream.StreamSupport.stream(archive.path("primaryReports").spliterator(),false)
                .map(item->item.path("path").asText()).sorted().toList();
        long processDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(ProcessHandle.of(toolPid).map(ProcessHandle::isAlive).orElse(false)&&System.nanoTime()<processDeadline)Thread.sleep(20);
        assertFalse(ProcessHandle.of(toolPid).map(ProcessHandle::isAlive).orElse(false),type+" "+template+" Tool child must be reaped");
        if(prewarm)assertNotEquals(previousLease,awaitPrewarmedPids(runtime).get(0),
                "A completed or interrupted "+type+" must consume and replace exactly one Worker");
        return new PackagedExecutionCapture(dispatch,job.path("status").asText(),result.path("job").path("status").asText(),
                artifactPaths,primaryReports,false);
    }

    private static void assertPackagedParity(PackagedExecutionCapture cold,PackagedExecutionCapture warm,
                                             String type,String expectedStatus) {
        assertPackagedParity(cold,warm,type,expectedStatus,true);
    }

    private static void assertPackagedParity(PackagedExecutionCapture cold,PackagedExecutionCapture warm,
                                             String type,String expectedStatus,boolean requireLogMarker) {
        if(expectedStatus!=null) {
            assertEquals(expectedStatus,cold.status(),type+" cold Job status");
            assertEquals(expectedStatus,warm.status(),type+" warm Job status");
        }
        assertEquals(cold.status(),warm.status(),type+" terminal status");
        assertEquals(cold.resultStatus(),warm.resultStatus(),type+" result status");
        assertEquals(cold.artifactPaths(),warm.artifactPaths(),type+" artifact inventory");
        assertEquals(cold.primaryReports(),warm.primaryReports(),type+" primary report inventory");
        if(requireLogMarker)assertTrue(cold.loggedParityMarker()&&warm.loggedParityMarker(),type+" logs must remain visible across dispatch modes");
    }

    private static List<Long> awaitPrewarmedPids(Object runtime) throws Exception {
        var stats=runtime.getClass().getDeclaredMethod("executionPoolStatistics");stats.setAccessible(true);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline) {
            Map<?,?> values=(Map<?,?>)stats.invoke(runtime);
            Number ready=(Number)values.get("ready");
            if(ready!=null&&ready.intValue()>0) {
                var poolField=runtime.getClass().getDeclaredField("executionWorkers");poolField.setAccessible(true);
                Object pool=poolField.get(runtime);var ownedField=pool.getClass().getDeclaredField("owned");ownedField.setAccessible(true);
                List<Long> pids=new java.util.ArrayList<>();
                synchronized(pool) {
                    var owned=(List<?>)ownedField.get(pool);
                    for(Object idle:owned) {
                        var processField=idle.getClass().getDeclaredField("process");processField.setAccessible(true);
                        Process process=(Process)processField.get(idle);
                        if(process!=null&&process.isAlive())pids.add(process.pid());
                    }
                }
                if(!pids.isEmpty())return List.copyOf(pids);
            }
            Thread.sleep(25);
        }
        fail("The deployed WAR did not become ready with a prewarmed Worker");return List.of();
    }

    @Test
    void packagedWarPublishesRunAndMultiWorkloadLoadArchives() throws Exception {
        Path war=Path.of(System.getProperty("att.server.war"));
        assertTrue(Files.isRegularFile(war));
        Path home=createScheduledLocalRuntime();
        Path packages=Files.createDirectories(temp.resolve("packages"));
        Path pkg=Files.createDirectory(packages.resolve("payments"));
        writeScheduledPackage(pkg,home);
        copySchemas(pkg);
        String expandedMessage="EXPANDED_ENTRY_BEGIN "+"x".repeat(8000)+" EXPANDED_ENTRY_END";
        Path expandedTemplate=pkg.resolve("templates/SCHEDULED/template.yaml");
        Files.writeString(expandedTemplate,Files.readString(expandedTemplate).replace("scheduled Load integration",expandedMessage));
        Files.writeString(pkg.resolve("templates/SCHEDULED/debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs: {}\n");
        try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var out=Files.newOutputStream(pkg.resolve("testcase/archive.xlsx"))) {
            var sheet=book.createSheet("Cases");var header=sheet.createRow(0);header.createCell(0).setCellValue("ID");header.createCell(1).setCellValue("Template");header.createCell(2).setCellValue("Tags");
            var row=sheet.createRow(1);row.createCell(0).setCellValue("C1");row.createCell(1).setCellValue("name: SCHEDULED");row.createCell(2).setCellValue("smoke");book.write(out);
        }
        Files.writeString(pkg.resolve("testcase/archive.yaml"),"schemaVersion: att-sidecar/v2.2\nid: archive\nexcel:\n  sheet: default=Cases\n  caseId: ID\n  tags: Tags\nstages:\n  - key: main\n    template: Template\n    required: true\n");
        new att.api.DefaultAttService().snapshot(new att.api.SnapshotRequest(pkg,home.resolve("config/config.yaml"),"SIT",List.of(pkg.resolve("testcase/archive.xlsx")),null,Set.of(),false));
        Files.writeString(pkg.resolve("load/archive.yaml"),
                "schemaVersion: att-load/v1.6\nevidence:\n  mode: all\nworkloads:\n"
                +"  - id: first\n    target: {type: template, id: SCHEDULED}\n    load: {users: 2, warmup: 0ms, rampUp: 10s, duration: 10s, rampDown: 10s}\n    execution: {thinkTime: 1s}\n"
                +"  - id: second\n    target: {type: template, id: SCHEDULED}\n    load: {users: 2, warmup: 0ms, rampUp: 10s, duration: 10s, rampDown: 10s}\n    execution: {thinkTime: 1s}\n");
        Path config=Files.writeString(temp.resolve("server.yaml"),
                "server:\n  runtimeConfigPath: "+yaml(home.resolve("config/config.yaml"))+"\n  dataDir: "+yaml(temp.resolve("data"))+"\n  authenticationRequired: true\n"
                +"  liveOutput: {maxEntryChars: 16000, maxEntryLines: 100}\n"
                +"workers:\n  maxConcurrent: 1\n  queuedLimit: 1\npackages:\n  allowedRoots:\n    - "+yaml(packages)+"\n  entries:\n    payments: "+yaml(pkg)+"\n");
        Path users=Files.writeString(temp.resolve("users.xml"),"<tomcat-users><role rolename=\"ATT_USER\"/><role rolename=\"ATT_ADMIN\"/>"
                +"<user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/><user username=\"colleague\" password=\"secret\" roles=\"ATT_USER\"/>"
                +"<user username=\"admin\" password=\"secret\" roles=\"ATT_ADMIN\"/></tomcat-users>");
        String oldConfig=System.getProperty("att.server.config"),oldHome=System.getProperty("att.home");
        System.setProperty("att.server.config",config.toString());System.setProperty("att.home",home.toString());
        Tomcat tomcat=new Tomcat();tomcat.setBaseDir(temp.resolve("tomcat").toString());tomcat.setPort(0);tomcat.getConnector();
        MemoryRealm realm=new MemoryRealm();realm.setPathname(users.toString());tomcat.getEngine().setRealm(realm);
        ((StandardHost)tomcat.getHost()).setUnpackWARs(false);org.apache.catalina.Context deployedContext=tomcat.addWebapp("/tools/att",war.toString());
        try {
            tomcat.start();int port=tomcat.getConnector().getLocalPort();String origin="http://127.0.0.1:"+port,api="/tools/att/api/v1";
            String auth=basic("att"),peer=basic("colleague"),admin=basic("admin");
            JsonNode monitoring=getJson(port,api+"/monitoring",auth);
            assertEquals("att-monitoring/v1",monitoring.path("schemaVersion").asText(),monitoring.toString());
            assertTrue(monitoring.path("capturedAt").isTextual());assertTrue(monitoring.path("process").isObject());
            assertTrue(monitoring.path("journal").isObject());assertTrue(monitoring.path("storage").isObject());
            assertFalse(monitoring.toString().contains(temp.resolve("data").toString()),"Monitoring must not expose Server data paths");
            assertEquals(200,get(port,api+"/monitoring",admin),"Container ADMIN can read the safe Monitoring projection");
            assertEquals(401,get(port,api+"/monitoring",null));
            assertEquals(403,get(port,api+"/metrics",auth),"The raw metrics endpoint remains system-admin-only");
            assertEquals(200,get(port,api+"/metrics",admin));
            var fixtures=new java.util.ArrayList<Map<String,Object>>();
            for(String command:List.of("run","load")) {
                Object input="run".equals(command)?Map.of("packageId","payments","suites",List.of("testcase/archive.xlsx"),"liveOutputMaxEntryChars",1,"liveOutputMaxEntryLines",1)
                        :Map.of("packageId","payments","scenario","load/archive.yaml");
                String id=apiJsonResponse(port,"POST",api+"/jobs/"+command,auth,origin,input,202).path("jobId").asText();
                JsonNode index=awaitArchive(port,api,id,peer);
                JsonNode overview=getJson(port,api+"/jobs/"+id+"/artifacts/status",peer);
                assertEquals("READY",overview.path("archiveStatus").asText(),overview.toString());
                assertTrue(overview.path("summaryVerified").asBoolean(),overview.toString());
                if("load".equals(command)) {
                    assertTrue(overview.path("reportPath").isTextual(),overview.toString());
                    assertTrue(index.path("primaryReports").findValuesAsText("path").contains(overview.path("reportPath").asText()),overview.toString());
                } else assertTrue(overview.path("reportPath").isNull(),overview.toString());
                JsonNode journal=getJson(port,api+"/monitoring/jobs/"+id+"/journal",peer);
                assertEquals(id,journal.path("jobId").asText());assertTrue(journal.path("metricsAvailable").isBoolean());
                if(journal.path("metricsAvailable").asBoolean())assertTrue(journal.path("journalBytes").isIntegralNumber(),journal.toString());
                else assertTrue(journal.path("journalBytes").isNull(),journal.toString());
                if("run".equals(command)) {
                    String replay=sseDataUntil(port,api+"/jobs/"+id+"/events",peer,"EXPANDED_ENTRY_END");
                    assertTrue(replay.contains(expandedMessage),"Configured console preview must survive Worker, journal and SSE replay: "+replay);
                }
                assertEquals("PASS",getJson(port,api+"/jobs/"+id,peer).path("status").asText(),getJson(port,api+"/jobs/"+id+"/result",peer).toString());
                assertTrue(index.path("primaryReports").size()>0,index.toString());
                String archiveRoute=api+"/jobs/"+id+"/artifacts/archive.zip";
                HttpURLConnection download=open(port,archiveRoute,peer);byte[] bytes;
                try {
                    assertEquals(200,download.getResponseCode());assertEquals("application/zip",download.getContentType());
                    assertTrue(download.getHeaderField("Content-Disposition").contains("attachment"));
                    try(var in=download.getInputStream()){bytes=in.readAllBytes();}
                } finally {download.disconnect();}
                Path zip=Files.write(temp.resolve(id+".zip"),bytes);JsonNode manifest=ServerRuntime.JSON.valueToTree(ArtifactArchive.verify(zip,true));
                assertEquals(bytes.length,index.path("archiveSizeBytes").asLong());
                long metricsDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                JsonNode archiveMetrics;
                do {
                    archiveMetrics=getJson(port,api+"/jobs/"+id,peer).path("archiveMetrics");
                    if("READY".equals(archiveMetrics.path("outcome").asText()))break;
                    Thread.sleep(20);
                }while(System.nanoTime()<metricsDeadline);
                assertEquals("READY",archiveMetrics.path("outcome").asText(),archiveMetrics.toString());
                assertEquals(bytes.length,archiveMetrics.path("zipBytes").asLong());
                assertTrue(archiveMetrics.path("includedEntries").asLong()>0);
                assertFalse(archiveMetrics.toString().contains(pkg.toString()));
                Path extracted=Files.createDirectory(temp.resolve(id+"-offline"));
                Set<String> previewPaths=new java.util.LinkedHashSet<>();
                Set<String> previewExtensions=new java.util.HashSet<>();
                for(JsonNode entry:manifest.path("entries")) {
                    String logical=entry.path("path").asText();
                    String extension=logical.substring(logical.lastIndexOf('.')+1).toLowerCase(java.util.Locale.ROOT);
                    if(previewExtensions.add(extension)||entry.path("report").asBoolean())previewPaths.add(logical);
                }
                try(var archive=new java.util.zip.ZipFile(zip.toFile())) {
                    for(JsonNode entry:manifest.path("entries")) {
                        String logical=entry.path("path").asText();byte[] published;
                        try(var in=archive.getInputStream(archive.getEntry(logical))){published=in.readAllBytes();}
                        if(previewPaths.contains(logical)) {
                            HttpURLConnection preview=open(port,api+"/jobs/"+id+"/artifacts/view/"+encodeEntry(logical),peer);
                            try {
                                assertEquals(200,preview.getResponseCode(),logical);assertTrue(preview.getHeaderField("Cache-Control").contains("no-store"));
                                assertFalse(preview.getHeaderField("Content-Disposition").contains("attachment"));
                                try(var in=preview.getInputStream()){assertArrayEquals(published,in.readAllBytes(),logical);}
                                if(entry.path("report").asBoolean())assertTrue(preview.getHeaderField("Content-Security-Policy").contains("sandbox allow-scripts"));
                                else assertTrue(preview.getHeaderField("Content-Security-Policy").contains("sandbox; default-src 'none'"));
                                assertTrue(preview.getHeaderField("Content-Security-Policy").contains("frame-ancestors 'self'"));
                            } finally {preview.disconnect();}
                        }
                        Path target=extracted.resolve(logical).normalize();assertTrue(target.startsWith(extracted));
                        Files.createDirectories(target.getParent());Files.write(target,published);
                    }
                }
                for(JsonNode report:index.path("primaryReports")) {
                    Path html=extracted.resolve(report.path("path").asText());
                    for(var link:org.jsoup.Jsoup.parse(Files.readString(html)).select("a[href]")) {
                        java.net.URI href=new java.net.URI(link.attr("href"));assertFalse(href.isAbsolute());
                        Path destination=href.getPath().isEmpty()?html:html.getParent().resolve(href.getPath()).normalize();
                        assertTrue(Files.isRegularFile(destination),link.outerHtml());
                    }
                }
                for(String route:List.of(api+"/jobs/"+id+"/artifacts",archiveRoute,
                        api+"/jobs/"+id+"/artifacts/view/"+encodeEntry(index.path("primaryReports").get(0).path("path").asText()))) {
                    assertEquals(401,get(port,route,null));assertEquals(403,get(port,route,admin));assertEquals(200,get(port,route,auth));
                }
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                Path output=temp.resolve("data/jobs").resolve(id).resolve("output");
                while(Files.exists(output)&&System.nanoTime()<deadline)Thread.sleep(10);
                assertFalse(Files.exists(output),"Verified publication must remove loose output");
                assertEquals(404,get(port,api+"/jobs/"+id+"/artifacts/"+encodeEntry(index.path("primaryReports").get(0).path("path").asText()),peer));
                fixtures.add(Map.of("jobId",id,"executionType",command,"archivePath",zip.toString(),"offlineRoot",extracted.toString(),"reports",index.path("primaryReports"),"entries",manifest.path("entries")));
            }
            if("1".equals(System.getenv("ATT_UI_SERVER_PLAYWRIGHT"))) {
                Path fixture=Files.write(temp.resolve("archive-browser.json"),ServerRuntime.JSON.writeValueAsBytes(fixtures));
                Path log=temp.resolve("archive-browser.log");
                Process browser=new ProcessBuilder("node","artifact-archive-smoke.cjs",origin,fixture.toString())
                        .directory(Path.of("tools/ui-acceptance").toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                if(!browser.waitFor(120,TimeUnit.SECONDS)){browser.destroyForcibly();fail("Archive browser timeout: "+Files.readString(log));}
                assertEquals(0,browser.exitValue(),Files.readString(log));
            }
            for(Map<String,Object> fixture:fixtures) {
                String id=String.valueOf(fixture.get("jobId"));
                apiJsonResponse(port,"POST",api+"/jobs/"+id+"/pin",peer,origin,Map.of("pinned",true),200);
            }
            tomcat.stop();tomcat.start();port=tomcat.getConnector().getLocalPort();origin="http://127.0.0.1:"+port;
            JsonNode pinnedJobs=getJson(port,api+"/jobs?pinnedOnly=true",peer).path("items");
            assertEquals(2,pinnedJobs.size());
            for(JsonNode pinnedJob:pinnedJobs)assertTrue(pinnedJob.path("pinned").asBoolean());
            String selectedId=String.valueOf(fixtures.get(0).get("jobId"));
            String secondId=String.valueOf(fixtures.get(1).get("jobId"));
            apiJsonResponse(port,"POST",api+"/jobs/pin",peer,origin,Map.of("pinned",false,"jobIds",List.of(selectedId,"../bad")),400);
            assertTrue(getJson(port,api+"/jobs/"+selectedId,peer).path("pinned").asBoolean(),"Bulk validation must finish before changing any Job");
            JsonNode bulkUnpin=apiJsonResponse(port,"POST",api+"/jobs/pin",peer,origin,Map.of("pinned",false,"jobIds",List.of(selectedId,secondId,selectedId)),200).path("items");
            assertEquals(2,bulkUnpin.size());assertTrue(java.util.stream.StreamSupport.stream(bulkUnpin.spliterator(),false).allMatch(item->"UNPINNED".equals(item.path("outcome").asText())));
            JsonNode bulkPin=apiJsonResponse(port,"POST",api+"/jobs/pin",peer,origin,Map.of("pinned",true,"pinReason"," password=bulk-pin-secret ","jobIds",List.of(selectedId,secondId)),200).path("items");
            assertEquals(2,bulkPin.size());assertTrue(java.util.stream.StreamSupport.stream(bulkPin.spliterator(),false).allMatch(item->"PINNED".equals(item.path("outcome").asText())));
            assertEquals("password=[REDACTED_SECRET]",getJson(port,api+"/jobs/"+selectedId,peer).path("pinReason").asText());
            apiJsonResponse(port,"POST",api+"/jobs/delete",peer,origin,Map.of("requested","invalid","jobIds",List.of(selectedId)),400);
            apiJsonResponse(port,"POST",api+"/jobs/delete",peer,origin,Map.of("confirm",true,"jobIds",java.util.Collections.nCopies(101,selectedId)),400);
            for(Map<String,Object> fixture:fixtures) {
                String id=String.valueOf(fixture.get("jobId"));
                assertTrue(getJson(port,api+"/jobs/"+id,auth).path("pinned").asBoolean(),"Pin must survive Tomcat restart");
                assertEquals(200,get(port,api+"/jobs/"+id+"/artifacts/archive.zip",auth));

                JsonNode pinned=apiJsonResponse(port,"POST",api+"/jobs/"+id+"/pin",peer,origin,Map.of("pinned",true),200);
                assertTrue(pinned.path("pinned").asBoolean());
                assertEquals("PINNED_UNPIN_FIRST",apiJsonResponse(port,"POST",api+"/jobs/"+id+"/delete",auth,origin,Map.of("confirm",true),409).path("outcome").asText());
                apiJsonResponse(port,"POST",api+"/jobs/"+id+"/pin",admin,origin,Map.of("pinned",false),403);
                apiJsonResponse(port,"POST",api+"/jobs/"+id+"/pin",auth,origin,Map.of("pinned",false),200);
                assertEquals("MARKED",apiJsonResponse(port,"POST",api+"/jobs/"+id+"/delete",peer,origin,Map.of("requested",true),200).path("outcome").asText());
                assertEquals("DELETE_REQUESTED",getJson(port,api+"/jobs/"+id,peer).path("deletionState").asText());
                assertEquals("UNMARKED",apiJsonResponse(port,"POST",api+"/jobs/"+id+"/delete",peer,origin,Map.of("requested",false),200).path("outcome").asText());
                assertEquals("MARKED",apiJsonResponse(port,"POST",api+"/jobs/"+id+"/delete",peer,origin,Map.of("requested",true),200).path("outcome").asText());
                assertTrue(Files.exists(temp.resolve("data/jobs").resolve(id)),"HTTP marking must not remove files");
                Object deployedRuntime=deployedContext.getServletContext().getAttribute("att.server.runtime");
                var storeField=deployedRuntime.getClass().getDeclaredField("store");storeField.setAccessible(true);Object deployedStore=storeField.get(deployedRuntime);
                var claim=deployedStore.getClass().getDeclaredMethod("claimRequestedDeletion",String.class,long.class);claim.setAccessible(true);assertEquals(true,claim.invoke(deployedStore,id,Long.MAX_VALUE));
                assertEquals("ALREADY_CLAIMED",apiJsonResponse(port,"POST",api+"/jobs/"+id+"/delete",peer,origin,Map.of("requested",false),409).path("outcome").asText());
                var reconcile=deployedRuntime.getClass().getDeclaredMethod("reconcileDeletingJobs");reconcile.setAccessible(true);assertEquals(true,reconcile.invoke(deployedRuntime));
                assertEquals("ALREADY_UNAVAILABLE",apiJsonResponse(port,"POST",api+"/jobs/"+id+"/delete",peer,origin,Map.of("confirm",true),200).path("outcome").asText());
                for(String suffix:List.of("","/result","/events","/artifacts","/artifacts/archive.zip"))assertEquals(404,get(port,api+"/jobs/"+id+suffix,auth));
                assertFalse(Files.exists(temp.resolve("data/jobs").resolve(id)));
            }
        } finally {
            try{tomcat.stop();}finally{tomcat.destroy();}
            if(oldConfig==null)System.clearProperty("att.server.config");else System.setProperty("att.server.config",oldConfig);
            if(oldHome==null)System.clearProperty("att.home");else System.setProperty("att.home",oldHome);
        }
    }

    private static String basic(String username) {return "Basic "+Base64.getEncoder().encodeToString((username+":secret").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static String encodeEntry(String path) {return java.util.Arrays.stream(path.split("/")).map(p->java.net.URLEncoder.encode(p,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20")).collect(java.util.stream.Collectors.joining("/"));}
    private static JsonNode awaitArchive(int port,String api,String id,String auth) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);JsonNode index;
        do {index=getJson(port,api+"/jobs/"+id+"/artifacts",auth);if("READY".equals(index.path("archiveStatus").asText()))return index;
            assertNotEquals("FAILED",index.path("archiveStatus").asText(),index.toString());Thread.sleep(30);
        }while(System.nanoTime()<deadline);
        fail("Archive did not become READY: "+index);return index;
    }

    @Test
    void packagedWarServesAuthenticatedUiAssetsUnderNonRootContext() throws Exception {
        Path war = Path.of(System.getProperty("att.server.war"));
        assertTrue(Files.isRegularFile(war), "Packaged server WAR must exist: " + war);
        Path localHome = createScheduledLocalRuntime();

        assertServerApplicationJar(war);

        Path users = Files.writeString(temp.resolve("tomcat-users.xml"),
                "<tomcat-users><role rolename=\"ATT_USER\"/><role rolename=\"ATT_ADMIN\"/>"
                        + "<user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/>"
                        + "<user username=\"schedule-admin\" password=\"secret\" roles=\"ATT_USER\"/>"
                        + "<user username=\"system-admin\" password=\"secret\" roles=\"ATT_ADMIN\"/></tomcat-users>");
        Path packages = Files.createDirectories(temp.resolve("packages"));
        Path packageRoot = Files.createDirectories(packages.resolve("payments"));
        writeScheduledPackage(packageRoot, localHome);
        Files.writeString(packageRoot.resolve("templates/SCHEDULED/debug.yaml"), "schemaVersion: att-debug/v1.2\ninputs: {}\n");
        // Keep Flow-link inspection in this isolated browser fixture, away from the scheduler capacity fixture.
        writeReferenceFlowSource(packageRoot);
        Path emptyPackage=Files.createDirectory(packages.resolve("lilyzhou"));
        // Three package catalogs and environment discovery share this inspection queue.
        // Keep one worker, but admit the browser's normal parallel discovery burst.
        Path config = Files.writeString(temp.resolve("server.yaml"),
                "server:\n  runtimeConfigPath: " + yaml(localHome.resolve("config/config.yaml")) + "\n  dataDir: " + yaml(temp.resolve("data")) + "\n  authenticationRequired: true\n  security:\n    checkCrossOrigin: true\n"
                        + "  inlineLoad:\n    enabled: true\n  inspection:\n    enabled: true\n    maxConcurrent: 1\n    queuedLimit: 8\n    timeoutMs: 30000\n    heapMaxMb: 256"
                        + "\n    safeTextSources:\n      payments:\n        - config/tools/att-helper.sh\n"
                        + "  scheduling:\n    enabled: true\n    adminRole: ATT_SCHEDULE_ADMIN\n"
                        + "workers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\n"
                        + "packages:\n  allowedRoots:\n    - " + yaml(packages) + "\n  entries:\n    payments: " + yaml(packageRoot) + "\n    default: "+yaml(Files.createDirectory(packages.resolve("default")))+"\n    jeffreyjiang: "+yaml(Files.createDirectory(packages.resolve("jeffreyjiang")))+"\n    lilyzhou: "+yaml(emptyPackage)+"\n");
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
            String origin = "http://127.0.0.1:" + port;
            assertContextRootRedirect(port, "/tools/att", "/tools/att/");
            assertContextRootRedirect(port, "/tools/att/", "/tools/att/ui/");
            assertEquals(401, get(port, "/tools/att/ui/index.html", null));
            String auth = "Basic " + Base64.getEncoder().encodeToString(
                    "att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertSharedConfiguration(port,"/tools/att/api/v1",auth);
            String scheduleAdminAuth = "Basic " + Base64.getEncoder().encodeToString(
                    "schedule-admin:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String systemAdminAuth = "Basic " + Base64.getEncoder().encodeToString(
                    "system-admin:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(401, get(port, "/tools/att/api/v1/load-schedules", null));
            assertEquals(200, get(port, "/tools/att/api/v1/load-schedules", auth),
                    "Every active USER may manage shared schedules");
            assertEquals(200, get(port, "/tools/att/api/v1/load-schedules", scheduleAdminAuth),
                    "another active USER may list the same schedules");
            String previewPath = "/tools/att/api/v1/load-schedules/preview";
            String previewBody = "{\"timezone\":\"UTC\",\"cron\":\"0 2 * * *\"}";
            assertEquals(403, postJson(port, previewPath, scheduleAdminAuth, "https://attacker.example", previewBody),
                    "cross-origin schedule mutations must be rejected");
            assertEquals(403, postJson(port, previewPath, scheduleAdminAuth, "null", previewBody),
                    "opaque Origins must be rejected");
            assertEquals(200, postJson(port, previewPath, scheduleAdminAuth, "http://127.0.0.1:" + port, previewBody),
                    "same-origin schedule preview may proceed");
            String api = "/tools/att/api/v1";
            HttpURLConnection emptyCatalogResponse=open(port,api+"/packages/lilyzhou/load-catalog?limit=100",auth);
            JsonNode emptyCatalog;
            try(var input=emptyCatalogResponse.getInputStream()) { emptyCatalog=ServerRuntime.JSON.readTree(input.readAllBytes()); }
            assertEquals(200,emptyCatalogResponse.getResponseCode(),emptyCatalog.toString());
            assertEquals(0,emptyCatalog.path("items").size(),emptyCatalog.toString());
            assertEquals(0,emptyCatalog.path("total").asInt(),emptyCatalog.toString());
            assertTrue(emptyCatalog.path("nextCursor").isNull()||emptyCatalog.path("nextCursor").isMissingNode(),emptyCatalog.toString());
            assertTrue(emptyCatalogResponse.getHeaderField("X-ATT-Inspection-Revision").matches("[A-Za-z0-9_-]{43}"),emptyCatalogResponse.getHeaderFields().toString());
            emptyCatalogResponse.disconnect();
            JsonNode emptyExplorer=getJson(port,api+"/packages/lilyzhou/resources?type=template",auth);
            assertEquals(0,emptyExplorer.path("items").size(),emptyExplorer.toString());
            assertFalse(Files.exists(emptyPackage.resolve("templates"))||Files.exists(emptyPackage.resolve("testcase"))||Files.exists(emptyPackage.resolve("load")),
                    "Inspection must not create missing optional directories.");
            assertTrue(getJson(port,api+"/packages/payments/resources?type=flow",auth).path("items").size()>0,
                    "The authenticated Worker must continue serving another Package.");
            assertEquals(403,postJson(port,"/tools/att/api/v1/drafts/run",scheduleAdminAuth,"https://attacker.example","{}"),
                    "Canonical RUN drafts retain the configured same-origin boundary.");
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
            assertCliAndTomcatDiagnosticFidelity(port, api, auth, origin, packageRoot, localHome);
            JsonNode cacheCapabilities=getJson(port,api+"/auth/capabilities",auth);
            String cacheContext=cacheCapabilities.path("inspectionCacheContext").asText();
            assertTrue(cacheContext.matches("[A-Za-z0-9_-]{43}"));
            assertEquals(cacheContext,getJson(port,api+"/auth/capabilities",auth).path("inspectionCacheContext").asText());
            assertNotEquals(cacheContext,getJson(port,api+"/auth/capabilities",scheduleAdminAuth).path("inspectionCacheContext").asText());
            HttpURLConnection cachedPage=open(port,api+"/packages/payments/resources?type=flow",auth);
            assertEquals(200,cachedPage.getResponseCode());
            assertEquals("no-store",cachedPage.getHeaderField("Cache-Control"));
            assertTrue(cachedPage.getHeaderField("X-ATT-Inspection-Revision").matches("[A-Za-z0-9_-]{43}"));
            try(var input=cachedPage.getInputStream()){String body=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertFalse(body.contains("revisionDigest"));assertFalse(body.contains("_cacheRevision"));}
            cachedPage.disconnect();
            assertEquals(403,get(port,api+"/metrics",auth),"System metrics are restricted to ADMIN accounts.");
            JsonNode inspectionMetrics=getJson(port,api+"/metrics",systemAdminAuth).path("inspection");
            assertTrue(inspectionMetrics.path("server").path("HTTP").path("count").asLong()>0);
            assertTrue(inspectionMetrics.path("workers").isArray());
            assertFalse(inspectionMetrics.toString().contains(packageRoot.toString()));
            JsonNode flowResources = getJson(port, api + "/packages/payments/resources?type=flow", scheduleAdminAuth);
            JsonNode referenceFlow = null;
            for (JsonNode item : flowResources.path("items")) {
                if ("reference.flow.v1".equals(item.path("logicalId").asText())) referenceFlow = item;
            }
            assertNotNull(referenceFlow, flowResources.toString());
            JsonNode referenceFlowSource = getJson(port, api + "/packages/payments/resources/flow/"
                    + referenceFlow.path("resourceId").asText() + "/source", scheduleAdminAuth);
            assertTrue(referenceFlowSource.path("available").asBoolean(), referenceFlowSource.toString());
            assertEquals("templates/flows/reference/flow.yaml", referenceFlowSource.path("logicalPath").asText());
            assertTrue(referenceFlowSource.path("text").asText().contains("&{templates/SCHEDULED/payload/request.xml}"),
                    referenceFlowSource.toString());
            assertEquals("templates/SCHEDULED/payload/request.xml",
                    referenceFlowSource.path("fileReferences").get(0).path("logicalPath").asText(), referenceFlowSource.toString());
            JsonNode toolResources = getJson(port, api + "/packages/payments/resources?type=tool", auth);
            JsonNode checkQueue = null;
            for (JsonNode item : toolResources.path("items"))
                if ("checkQueue".equals(item.path("logicalId").asText())) checkQueue = item;
            assertNotNull(checkQueue, toolResources.toString());
            assertTrue(checkQueue.path("sourceAvailable").asBoolean(),
                    "A package-local nested script is browsable under the default policy without safeTextSources.");
            String toolSourcePath = api + "/packages/payments/resources/tool/" + checkQueue.path("resourceId").asText() + "/source";
            assertEquals(401, get(port, toolSourcePath, null), "Tool source retains the Tomcat authentication boundary.");
            JsonNode toolSource = getJson(port, toolSourcePath, auth);
            assertTrue(toolSource.path("available").asBoolean(), toolSource.toString());
            assertEquals("tools/mq/check-queue.sh", toolSource.path("logicalPath").asText());
            assertTrue(toolSource.path("text").asText().contains("package-local script source"));
            assertFalse(toolSource.toString().contains(packageRoot.toString()), "Package filesystem paths remain private.");
            apiJson(port, "POST", api + "/load-schedules", scheduleAdminAuth, origin,
                    scheduleRequest("Packaged UI schedule", "load/scenario.yaml"), 201);
            if ("1".equals(System.getenv("ATT_UI_SERVER_PLAYWRIGHT"))) runServerUiPlaywright(origin);
            assertDegradedDiscoveryWithoutProcessSecrets(port, api, auth, packageRoot, localHome);
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
            assertTrue(entries.contains("att/metrics/InspectionMetrics.class"),
                    "The combined Server jar must bundle the Inspector metrics classes the webapp instantiates");
            assertTrue(entries.contains("att/metrics/InspectionMetrics$Stage.class"));
            assertTrue(entries.contains("att/metrics/InspectionCacheKey.class"));
            assertTrue(entries.contains("att/security/SecretProtection.class"));
            assertTrue(entries.contains("META-INF/resources/ui/index.html"));
            assertTrue(entries.stream().anyMatch(name -> name.matches("META-INF/resources/ui/assets/index-[^/]+\\.js")),
                    "The active Vite bundle must be packaged under Servlet static resources.");
            assertTrue(entries.stream().anyMatch(name -> name.matches("META-INF/resources/ui/assets/index-[^/]+\\.css")),
                    "The generated stylesheet must be packaged under Servlet static resources.");
            assertTrue(entries.contains("META-INF/resources/ui/legacy.html"));
            assertTrue(entries.contains("att-build.properties"));
            assertFalse(entries.contains("att/worker/WorkerMain.class"),
                    "Worker classes belong only in the Local runtime jar");
            assertFalse(entries.stream().anyMatch(name -> name.startsWith("att/engine/") || name.startsWith("att/load/")),
                    "Engine and Load runtime classes belong only in the Local runtime jar");
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
        writeScheduledPackage(packageRoot, localHome);
        Path users = Files.writeString(temp.resolve("scheduled-tomcat-users.xml"),
                "<tomcat-users><role rolename=\"ATT_USER\"/><role rolename=\"ATT_ADMIN\"/>"
                        + "<user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/>"
                        + "<user username=\"schedule-admin\" password=\"secret\" roles=\"ATT_USER\"/>"
                        + "<user username=\"system-admin\" password=\"secret\" roles=\"ATT_ADMIN\"/></tomcat-users>");
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java");
        Path config = Files.writeString(temp.resolve("scheduled-war-server.yaml"),
                "server:\n  runtimeConfigPath: " + yaml(localHome.resolve("config/config.yaml")) + "\n  dataDir: " + yaml(temp.resolve("scheduled-war-data")) + "\n  javaExecutable: " + yaml(javaBin)
                        + "\n  authenticationRequired: true\n  inlineLoad:\n    enabled: true\n  inspection:\n    enabled: true\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 30000\n    heapMaxMb: 256"
                        + "\n    safeTextSources:\n      payments:\n        - config/tools/att-helper.sh"
                        + "\n  scheduling:\n    enabled: true\n    minimumIntervalMinutes: 1\n    pollIntervalSeconds: 5\n    maxConcurrentRuns: 1"
                        + "\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - " + yaml(packages)
                        + "\n  entries:\n    payments: " + yaml(packageRoot) + "\n");
        String oldConfig = System.getProperty("att.server.config");
        String oldHome = System.getProperty("att.home");
        System.setProperty("att.server.config", config.toString());
        System.setProperty("att.home", localHome.toString());
        Tomcat tomcat = null;
        String admin = "Basic " + Base64.getEncoder().encodeToString("schedule-admin:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String ordinary = "Basic " + Base64.getEncoder().encodeToString("att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String systemAdmin = "Basic " + Base64.getEncoder().encodeToString("system-admin:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String api = "/tools/att/api/v1";
        String firstScheduleId;
        String secondScheduleId;
        String inlineScheduleId;
        String jobId;
        try {
            tomcat = startScheduledWar(war, users);
            int port = tomcat.getConnector().getLocalPort();
            String origin = "http://127.0.0.1:" + port;
            assertEquals(401, get(port, api + "/load-schedules", null));
            assertEquals(200, get(port, api + "/load-schedules", ordinary), "Every active USER can read shared schedules");
            assertEquals(200, get(port, api + "/load-schedules", admin));
            String configurationFilesPath = api + "/packages/payments/configuration/files?path=config&limit=100";
            assertEquals(401, get(port, configurationFilesPath, null), "Configuration file listings retain the Tomcat authentication boundary.");
            JsonNode configurationFiles = getJson(port, configurationFilesPath, ordinary);
            assertEquals("ready", configurationFiles.path("state").asText());
            JsonNode listedConfigSource = null;
            JsonNode safeHelperFiles = getJson(port, api + "/packages/payments/configuration/files?path=config/tools&limit=100", ordinary);
            assertEquals("ready", safeHelperFiles.path("state").asText());
            JsonNode listedSafeHelper = null;
            for (JsonNode item : configurationFiles.path("items")) {
                if ("config/config.yaml".equals(item.path("path").asText())) listedConfigSource = item;
            }
            for (JsonNode item : safeHelperFiles.path("items")) {
                if ("config/tools/att-helper.sh".equals(item.path("path").asText())) listedSafeHelper = item;
            }
            assertNotNull(listedConfigSource);
            assertTrue(listedConfigSource.path("sourceAvailable").asBoolean(),
                    "The default safe-package-files policy permits bounded, sanitized package-local text.");
            assertNotNull(listedSafeHelper);
            assertTrue(listedSafeHelper.path("sourceAvailable").asBoolean());
            String configurationSourcePath = api + "/packages/payments/configuration/source?path="
                    + java.net.URLEncoder.encode("config/tools/att-helper.sh", java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(401, get(port, configurationSourcePath, null), "Configuration source retains the Tomcat authentication boundary.");
            JsonNode configurationSource = getJson(port, configurationSourcePath, ordinary);
            assertTrue(configurationSource.path("available").asBoolean());
            assertEquals("config/tools/att-helper.sh", configurationSource.path("logicalPath").asText());
            assertTrue(configurationSource.path("text").asText().contains("# Safe configuration helper source"));
            JsonNode safeConfigSource = getJson(port, api + "/packages/payments/configuration/source?path="
                    + java.net.URLEncoder.encode("config/config.yaml", java.nio.charset.StandardCharsets.UTF_8), ordinary);
            assertTrue(safeConfigSource.path("available").asBoolean());
            assertTrue(safeConfigSource.path("text").asText().contains("testcase:"));
            assertSharedConfiguration(port,api,ordinary);
            String recordsPath = api + "/packages/payments/configuration/testdata/generatedAccounts/records?environment=SIT&limit=5";
            assertEquals(401, get(port, recordsPath, null), "Testdata records remain behind the Tomcat authentication boundary.");
            JsonNode generatedMetadata = getJson(port, recordsPath, ordinary);
            assertEquals("preview-required", generatedMetadata.path("state").asText());
            assertEquals(121, generatedMetadata.path("recordCount").asInt());
            assertEquals(0, generatedMetadata.path("records").size(), "Generated rows must stay unloaded until explicitly requested.");
            JsonNode generatedPreview = getJson(port, recordsPath + "&previewGenerated=true", ordinary);
            assertEquals("ready", generatedPreview.path("state").asText());
            assertEquals(5, generatedPreview.path("records").size());
            assertEquals(1000, generatedPreview.path("records").get(0).path("sequence").asInt());

            JsonNode configurationOrigin = getJson(port, api + "/packages/payments/configuration/source?path="
                    + java.net.URLEncoder.encode("config/source-references.yaml", java.nio.charset.StandardCharsets.UTF_8), ordinary);
            assertTrue(configurationOrigin.path("available").asBoolean(), configurationOrigin.toString());
            JsonNode configurationReference = null;
            for (JsonNode item : configurationOrigin.path("fileReferences"))
                if ("config/tools/att-helper.sh".equals(item.path("logicalPath").asText())) configurationReference = item;
            assertNotNull(configurationReference, configurationOrigin.toString());
            String referenceId = configurationReference.path("referenceId").asText();
            assertTrue(referenceId.matches("[A-Za-z0-9_-]{20,4096}"), "The public reference is an opaque Server token.");
            String referencedSourcePath = api + "/packages/payments/configuration/source?path="
                    + java.net.URLEncoder.encode("config/tools/att-helper.sh", java.nio.charset.StandardCharsets.UTF_8)
                    + "&origin=" + java.net.URLEncoder.encode("config/source-references.yaml", java.nio.charset.StandardCharsets.UTF_8)
                    + "&referenceId=" + java.net.URLEncoder.encode(referenceId, java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(401, get(port, referencedSourcePath, null), "Referenced package files retain the Server authentication boundary.");
            JsonNode referencedSource = getJson(port, referencedSourcePath, ordinary);
            assertTrue(referencedSource.path("available").asBoolean());
            assertEquals("config/tools/att-helper.sh", referencedSource.path("logicalPath").asText());
            assertEquals("shell", referencedSource.path("format").asText());
            assertTrue(referencedSource.path("text").asText().contains("Safe configuration helper source"));
            assertFalse(referencedSource.path("text").asText().contains("helper-secret"),
                    "Safe source projection must scrub classified-looking password values before the UI receives them.");
            assertTrue(referencedSource.path("redacted").asBoolean());
            String unverifiedReferencePath = referencedSourcePath.substring(0, referencedSourcePath.indexOf("&referenceId="));
            assertEquals(404, get(port, unverifiedReferencePath, ordinary),
                    "Origin and path alone cannot authorize navigation to a referenced package file.");
            String deniedSourcePath = api + "/packages/payments/configuration/source?path="
                    + java.net.URLEncoder.encode("templates/SCHEDULED/template.yaml", java.nio.charset.StandardCharsets.UTF_8)
                    + "&origin=" + java.net.URLEncoder.encode("templates/SCHEDULED/template.yaml", java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(404, get(port, deniedSourcePath, ordinary),
                    "A caller-supplied origin does not authorize configuration navigation to arbitrary package files.");

            Map<String,Object> runDefinition=Map.of("schemaVersion","att-execution/v1","executionType","run",
                    "packageId","payments","environment","SIT","target",Map.of("type","template","id","SCHEDULED"),
                    "input",Map.of(),"options",Map.of("debug",false));
            assertEquals(401,apiStatus(port,"POST",api+"/drafts/run",null,origin,runDefinition));
            JsonNode runDraft=apiJson(port,"POST",api+"/drafts/run",admin,origin,runDefinition,201);
            String runDraftId=runDraft.path("draftId").asText();
            assertEquals("run",runDraft.path("executionType").asText());
            assertEquals(404,get(port,api+"/drafts/"+runDraftId,ordinary));
            Map<String,Object> runScheduleInput=Map.of("name","One-time functional RUN","packageId","payments",
                    "environment","SIT","executionType","run","draftId",runDraftId,"enabled",false,
                    "runAt",java.time.Instant.now().plusSeconds(3600).toString());
            assertEquals(404,apiStatus(port,"POST",api+"/schedules",ordinary,origin,runScheduleInput),
                    "A USER has schedule access, while validated drafts remain scoped to their creator");
            JsonNode runSchedule=apiJson(port,"POST",api+"/schedules",admin,origin,runScheduleInput,201);
            assertEquals("att-schedule/v2",runSchedule.path("schemaVersion").asText());
            assertFalse(runSchedule.has("runInput"));
            JsonNode runSchedules=getJson(port,api+"/schedules?executionType=run",admin);
            assertEquals(1,runSchedules.path("items").size(),runSchedules.toString());
            assertEquals(400,get(port,api+"/schedules?executionType=debug",admin));
            JsonNode acceptedRun=apiJson(port,"POST",api+"/jobs/run",admin,origin,
                    Map.of("packageId","payments","draftId",runDraftId),202);
            assertEquals("run",acceptedRun.path("executionType").asText());
            String runJobPath=api+"/jobs/"+acceptedRun.path("jobId").asText();
            awaitJobStatus(port,runJobPath,admin,"PASS");
            assertEquals("PASS",getJson(port,runJobPath+"/result",admin).path("job").path("status").asText());
            assertTrue(firstSseEventId(port,runJobPath+"/events",admin)>0);
            assertTrue(sseDataUntil(port,runJobPath+"/events",admin,"[RUN]").contains("[RUN]"),"RUN live output must include a shared console line");
            assertEquals(403,get(port,api+"/metrics",admin),"System metrics stay restricted to the system administrator role.");
            JsonNode metrics=getJson(port,api+"/metrics",systemAdmin);
            assertFalse(metrics.path("jobStorage").path("enabled").asBoolean());
            assertEquals("disabled",metrics.path("jobStorage").path("state").asText());
            assertTrue(metrics.path("jobStorage").path("totalBytes").isNull()||metrics.path("jobStorage").path("totalBytes").isMissingNode());
            assertEquals(404,apiStatus(port,"POST",api+"/jobs/run",admin,origin,Map.of("packageId","payments","draftId",runDraftId)));
            assertEquals(200,apiStatus(port,"DELETE",api+"/schedules/"+runSchedule.path("id").asText(),admin,origin,null));

            Map<String, Object> inlineScenario = Map.of(
                    "schemaVersion", "att-load/v1.6",
                    "workloads", List.of(Map.of("id", "scheduled-snapshot", "target", Map.of("type", "template", "id", "SCHEDULED"),
                            "load", Map.of("users", 1, "duration", "1s"))));
            JsonNode inlineDraft = apiJson(port, "POST", api + "/drafts/load", admin, origin,
                    Map.of("packageId", "payments", "environment", "SIT", "scenario", inlineScenario), 201);
            Map<String, Object> inlineScheduleInput = new java.util.LinkedHashMap<>();
            inlineScheduleInput.put("name", "Validated UI snapshot");inlineScheduleInput.put("packageId", "payments");
            inlineScheduleInput.put("draftId", inlineDraft.path("draftId").asText());inlineScheduleInput.put("environment", "SIT");
            inlineScheduleInput.put("timezone", "UTC");inlineScheduleInput.put("cron", "0 2 * * *");
            inlineScheduleInput.put("maxConcurrentRuns", 1);inlineScheduleInput.put("enabled", false);
            JsonNode inlineSchedule = apiJson(port, "POST", api + "/load-schedules", admin, origin, inlineScheduleInput, 201);
            inlineScheduleId = inlineSchedule.path("id").asText();
            assertTrue(inlineSchedule.path("inlineSnapshot").asBoolean());
            assertFalse(inlineSchedule.has("inlineScenario"), "Validated scenario contents must not be returned in schedule API views.");
            assertTrue(inlineSchedule.path("scenario").isNull());

            JsonNode preview = apiJson(port, "POST", api + "/load-schedules/preview", admin, origin,
                    Map.of("timezone", "UTC", "cron", "0 2 * * *"), 200);
            assertEquals("UTC", preview.path("timezone").asText());
            assertTrue(preview.path("nextRunAt").isTextual(), "The real Server preview must return its next occurrence.");

            Map<String, Object> invalid = new java.util.LinkedHashMap<>(scheduleRequest("Invalid scenario", "load/invalid.yaml"));
            invalid.put("environment", "SIT");
            assertEquals(401, apiStatus(port, "POST", api + "/load-schedules", null, origin, invalid));
            assertEquals(400, apiStatus(port, "POST", api + "/load-schedules", ordinary, origin, invalid),
                    "A USER reaches schedule validation instead of being denied by role");
            JsonNode invalidSchedule = apiJsonResponse(port, "POST", api + "/load-schedules", admin, origin, invalid, 400);
            JsonNode invalidDiagnostic = invalidSchedule.path("diagnostics").path(0);
            assertEquals("ATT-LOAD-001", invalidDiagnostic.path("code").asText());
            assertTrue(invalidDiagnostic.path("summary").asText().length() > 0);
            assertNotEquals("Load or Debug input failed schema or target validation",
                    invalidDiagnostic.path("summary").asText());
            assertEquals("load/invalid.yaml", invalidDiagnostic.path("file").asText());
            assertEquals("load/invalid.yaml", invalidDiagnostic.path("sourceFile").asText());
            assertTrue(invalidDiagnostic.path("line").asInt() > 0, invalidDiagnostic.toString());
            assertTrue(invalidDiagnostic.path("sourceColumn").asInt() > 0, invalidDiagnostic.toString());
            assertFalse(invalidDiagnostic.toString().contains(packageRoot.toString()), invalidDiagnostic.toString());
            assertFalse(invalidDiagnostic.toString().contains("$EXTERNAL"), invalidDiagnostic.toString());
            assertEquals("payments", invalidDiagnostic.path("packageId").asText());
            assertEquals("SIT", invalidDiagnostic.path("environment").asText());
            assertEquals("SCHEDULED", invalidDiagnostic.path("targets").path(0).path("id").asText());
            assertTrue(invalidSchedule.path("requestId").isTextual());
            assertEquals(invalidSchedule.path("requestId").asText(),
                    invalidDiagnostic.path("requestId").asText());

            JsonNode first = apiJson(port, "POST", api + "/load-schedules", admin, origin,
                    scheduleRequest("Nightly payments", "load/scenario.yaml"), 201);
            firstScheduleId = first.path("id").asText();
            assertFalse(first.path("enabled").asBoolean(), "New schedules must default to paused.");
            JsonNode second = apiJson(port, "POST", api + "/load-schedules", admin, origin,
                    scheduleRequest("Secondary payments", "load/scenario.yaml"), 201);
            secondScheduleId = second.path("id").asText();

            assertEquals(401, apiStatus(port, "POST", api + "/load-schedules/" + firstScheduleId + "/trigger", null, origin, null));
            assertEquals(200, get(port, api + "/load-schedules/" + firstScheduleId, ordinary),
                    "Schedules created by another USER remain visible");
            assertEquals(200, apiStatus(port, "POST", api + "/load-schedules/" + firstScheduleId + "/pause", admin, origin, null));
            assertFalse(getJson(port, api + "/load-schedules/" + firstScheduleId, admin).path("enabled").asBoolean());
            assertEquals(200, apiStatus(port, "POST", api + "/load-schedules/" + firstScheduleId + "/resume", admin, origin, null));
            assertTrue(getJson(port, api + "/load-schedules/" + firstScheduleId, admin).path("enabled").asBoolean());

            JsonNode trigger = apiJson(port, "POST", api + "/load-schedules/" + firstScheduleId + "/trigger", admin, origin, null, 202);
            assertEquals("submitted", trigger.path("status").asText());
            jobId = trigger.path("jobId").asText();
            assertTrue(jobId.matches("J[0-9A-F]{16}"));
            JsonNode activeJob = getJson(port, api + "/jobs/" + jobId, admin);
            JsonNode jobResult = getJson(port, api + "/jobs/" + jobId + "/result", admin);
            assertTrue(List.of("QUEUED", "PREPARING", "RUNNING").contains(activeJob.path("status").asText()),
                    "The real scheduled Load should remain active long enough to exercise capacity handling: "
                            + activeJob + " result=" + jobResult);

            JsonNode rejected = apiJson(port, "POST", api + "/load-schedules/" + secondScheduleId + "/trigger", admin, origin, null, 202);
            JsonNode firstJobResult = getJson(port, api + "/jobs/" + jobId + "/result", admin);
            assertEquals("skipped", rejected.path("status").asText(),
                    "A concurrent scheduled run must occupy global capacity; occurrence=" + rejected
                            + " job=" + getJson(port, api + "/jobs/" + jobId, admin)
                            + " result=" + firstJobResult);
            assertEquals("global-scheduled-load-capacity", rejected.path("reason").asText());
            JsonNode secondHistory = getJson(port, api + "/load-schedules/" + secondScheduleId + "/executions", admin);
            assertEquals("global-scheduled-load-capacity", secondHistory.path("items").get(0).path("reason").asText());

            long eventCursor = firstSseEventId(port, api + "/jobs/" + jobId + "/events", admin);
            assertTrue(sseDataUntil(port, api + "/jobs/" + jobId + "/events", admin, "[LOAD]").contains("[LOAD]"),
                    "LOAD live output must include a bounded console line");
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
            assertEquals(3, restored.path("items").size(), "Path-based schedules and the validated snapshot must persist across a Tomcat restart.");
            JsonNode restoredInlineSchedule = getJson(port, api + "/load-schedules/" + inlineScheduleId, admin);
            assertTrue(restoredInlineSchedule.path("inlineSnapshot").asBoolean());
            assertTrue(restoredInlineSchedule.path("scenario").isNull());
            assertFalse(restoredInlineSchedule.has("inlineScenario"));
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
            assertEquals(200, apiStatus(port, "DELETE", api + "/load-schedules/" + inlineScheduleId, admin, origin, null));
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
            for (String module : List.of("att-security", "att-cli", "att-engine", "att-remote", "att-server-api", "att-worker")) {
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

    private static void writeScheduledPackage(Path packageRoot, Path localHome) throws Exception {
        Path templates = Files.createDirectories(packageRoot.resolve("templates/SCHEDULED"));
        Path loads = Files.createDirectories(packageRoot.resolve("load"));
        Files.createDirectories(packageRoot.resolve("testcase"));
        Path nestedTool = Files.createDirectories(packageRoot.resolve("tools/mq")).resolve("check-queue.sh");
        Path configRoot = Files.createDirectories(localHome.resolve("config"));
        Files.writeString(configRoot.resolve("config.yaml"),
                "schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n  UAT: {}\n"
                        + "testcase:\n  root: testcase\ntemplates:\n  root: templates\ntools:\n  checkQueue:\n"
                        + "    name: Check queue\n    description: Nested Tool script default source\n"
                        + "    command: [bash, tools/mq/check-queue.sh]\n    stdoutFormat: text\n    arguments: {}\n");
        Files.writeString(configRoot.resolve("source-references.yaml"), "payload: '&{config/tools/att-helper.sh}'\n");
        Path tools = Files.createDirectories(configRoot.resolve("tools"));
        Files.writeString(tools.resolve("att-helper.sh"), "#!/bin/sh\n# Safe configuration helper source\necho password=helper-secret\n");
        Files.writeString(nestedTool, "#!/bin/sh\n# package-local script source\necho safe\n");
        Files.writeString(packageRoot.resolve("package.yaml"),"schemaVersion: att-package/v1\ntestdata: {descriptors: [testdata/generated.yaml]}\n");
        Path testdata = Files.createDirectories(packageRoot.resolve("testdata"));
        Files.writeString(testdata.resolve("generated.yaml"),
                "schemaVersion: att-testdata/v1.0\nid: generatedAccounts\nrecords:\n  generate:\n    seq:\n      from: 1000\n      to: 1120\n      format: '%06d'\n  record:\n    id: 'A-%{seq}'\n"
                        + "selection:\n  strategy: sequential\n  exhaustion: stop\n");
        Files.writeString(templates.resolve("template.yaml"),
                "schemaVersion: att-template/v3.6\nname: SCHEDULED\ndescription: stock Tomcat scheduler test\nactions:\n"
                        + "  message:\n    type: log\n    message: scheduled Load integration\n");
        Path payload = Files.createDirectories(templates.resolve("payload"));
        Files.writeString(payload.resolve("request.txt"), "request: payload\npassword: template-secret\n");
        Files.writeString(loads.resolve("scenario.yaml"),
                "schemaVersion: att-load/v1.6\nworkloads:\n  - id: scheduled-long-run\n    target:\n      type: template\n      id: SCHEDULED\n"
                        + "    load:\n      users: 1\n      duration: 30s\n    execution:\n      thinkTime: 1s\n");
        Files.writeString(loads.resolve("invalid.yaml"), "schemaVersion: att-load/invalid\nworkloads:\n"
                + "  - id: invalid-snapshot\n    target:\n      type: template\n      id: SCHEDULED\n"
                + "    load:\n      users: 1\n      duration: 1s\n");
    }

    private void assertCliAndTomcatDiagnosticFidelity(int port, String api, String auth, String origin,
                                                     Path packageRoot, Path localHome) throws Exception {
        String expression="&{./Pacs008.xml}";
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Datt.home="+localHome,"-cp",localHome.resolve("lib").toString()+"/*","att.FrameworkRunner",
                "debug","flow","diagnostic.fidelity.v1","--config",localHome.resolve("config/config.yaml").toString(),
                "--output-dir",temp.resolve("cli-diagnostic-output").toString(),"--format","json");
        builder.directory(packageRoot.toFile());
        builder.environment().put("ATT_PACKAGE_ROOT",packageRoot.toString());
        Path cliOutput=temp.resolve("cli-diagnostic.txt");builder.redirectErrorStream(true).redirectOutput(cliOutput.toFile());
        Process cli=builder.start();assertTrue(cli.waitFor(30,TimeUnit.SECONDS));
        assertNotEquals(0,cli.exitValue());String cliText=Files.readString(cliOutput);
        assertTrue(cliText.contains(expression),cliText);assertFalse(cliText.contains("&{.$EXTERNAL"),cliText);
        JsonNode submitted=apiJsonResponse(port,"POST",api+"/jobs/debug",auth,origin,
                Map.of("packageId","payments","target",Map.of("type","flow","id","diagnostic.fidelity.v1")),202);
        String jobId=submitted.path("jobId").asText();JsonNode job=null;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        do { job=getJson(port,api+"/jobs/"+jobId,auth);
            if (Set.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(job.path("status").asText())) break;
            Thread.sleep(30);
        } while(System.nanoTime()<deadline);
        assertTrue(Set.of("ERROR","INVALID").contains(job.path("status").asText()),job.toString());
        JsonNode result=getJson(port,api+"/jobs/"+jobId+"/result",auth);String publicText=result.toString();
        assertTrue(publicText.contains(expression),publicText);assertFalse(publicText.contains("&{.$EXTERNAL"),publicText);
        assertFalse(publicText.contains(packageRoot.toString()),publicText);
        assertTrue(publicText.contains("flow.yaml"),publicText);
        assertTrue(publicText.contains("line") || publicText.contains("sourceColumn"),publicText);
        JsonNode artifacts=awaitArchive(port,api,jobId,auth);
        boolean checked=false;
        for(JsonNode item:artifacts.path("items")) if(item.path("path").asText().endsWith("case.log")) {
            String path=item.path("path").asText();
            String log=getText(port,api+"/jobs/"+jobId+"/artifacts/view/"+encodeEntry(path),auth);
            assertTrue(log.contains(expression),log);assertFalse(log.contains(packageRoot.toString()),log);checked=true;
        }
        assertTrue(checked,"Failed Debug must retain its diagnostic Case Log");
        assertTrue(sseDataUntil(port,api+"/jobs/"+jobId+"/events",auth,"[DEBUG]").contains("[DEBUG]"),
                "DEBUG live output must include a shared console line");
    }

    private static void writeReferenceFlowSource(Path packageRoot) throws Exception {
        Path payload = Files.createDirectories(packageRoot.resolve("templates/SCHEDULED/payload"));
        Path referenceFlow = Files.createDirectories(packageRoot.resolve("templates/flows/reference"));
        Path diagnosticFlow=Files.createDirectories(packageRoot.resolve("templates/flows/diagnostic"));
        Files.writeString(diagnosticFlow.resolve("debug.yaml"), "schemaVersion: att-debug/v1.2\ninputs: {}\n");
        Files.writeString(diagnosticFlow.resolve("flow.yaml"),
                "schemaVersion: att-flow/v3.6\nid: diagnostic.fidelity.v1\nname: Diagnostic fidelity\ndescription: Missing payload regression\nactions:\n"
                +"  payload:\n    type: log\n    message: \"&{./Pacs008.xml}\"\n");
        Files.writeString(payload.resolve("request.xml"), "<request>safe payload</request>\n");
        Files.writeString(referenceFlow.resolve("flow.yaml"),
                "schemaVersion: att-flow/v3.6\nid: reference.flow.v1\nname: Reference Flow\n"
                        + "description: Exercises safe package file links in a Flow source.\nactions:\n"
                        + "  inspectPayload:\n    type: log\n    message: \"Request &{templates/SCHEDULED/payload/request.xml}\"\n");
    }

    private void runServerUiPlaywright(String origin) throws Exception {
        Path output = temp.resolve("server-ui-playwright.log");
        runServerUiPlaywrightScript(origin, "server-api-smoke.cjs", output);
        runServerUiPlaywrightScript(origin, "saved-scenario-server.cjs", temp.resolve("saved-scenario-playwright.log"));
    }

    private void runServerUiPlaywrightScript(String origin, String script, Path output) throws Exception {
        Path harness = Path.of("tools", "ui-acceptance").toAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder("node", script)
                .directory(harness.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("ATT_UI_SERVER_ORIGIN", origin);
        builder.environment().put("ATT_UI_SERVER_USERNAME", "schedule-admin");
        builder.environment().put("ATT_UI_SERVER_PASSWORD", "secret");
        Process process = builder.start();
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Playwright did not finish " + script + " within 120 seconds. "
                    + (Files.exists(output) ? Files.readString(output) : ""));
        }
        String log = Files.exists(output) ? Files.readString(output) : "";
        assertEquals(0, process.exitValue(), "Playwright against the authenticated non-root Tomcat WAR failed:\n" + log);
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

    private static JsonNode apiJsonResponse(int port, String method, String path, String authorization, String origin,
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
            try (var stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                assertNotNull(stream, "Expected a JSON response body");
                return ServerRuntime.JSON.readTree(stream);
            }
        } finally { connection.disconnect(); }
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

    private static JsonNode awaitTerminalJobStatus(int port,String path,String authorization) throws Exception {
        Set<String> terminal=Set.of("PASS","FAIL","ERROR","INVALID","CANCELLED","TIMEOUT","TIMED_OUT");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        JsonNode job;
        do {
            job=getJson(port,path,authorization);
            if(terminal.contains(job.path("status").asText()))return job;
            Thread.sleep(50);
        } while(System.nanoTime()<deadline);
        fail("Job did not reach a terminal state: "+job);
        return job;
    }

    private static String sseDataUntil(int port, String path, String authorization, String needle) throws Exception {
        HttpURLConnection connection = open(port, path, authorization);
        connection.setReadTimeout(5000);
        StringBuilder data = new StringBuilder();
        try {
            assertEquals(200, connection.getResponseCode(), "GET " + path);
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) data.append(line.substring(6));
                    if (data.indexOf(needle) >= 0) return data.toString();
                }
            }
        } finally { connection.disconnect(); }
        return data.toString();
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

    private static void assertContextRootRedirect(int port, String path, String expectedLocation) throws Exception {
        HttpURLConnection connection = open(port, path, null);
        connection.setInstanceFollowRedirects(false);
        try {
            assertEquals(302, connection.getResponseCode(), path);
            assertEquals(expectedLocation, connection.getHeaderField("Location"), path);
        } finally {
            connection.disconnect();
        }
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
        String selector = "src".equals(attribute) ? "script[src$=.js]" : "link[rel=stylesheet][href$=.css]";
        org.jsoup.nodes.Element asset = org.jsoup.Jsoup.parse(html).selectFirst(selector);
        assertNotNull(asset, "The active Vite index must reference a generated " + attribute + " asset.");
        return asset.attr(attribute);
    }

    private static void assertDegradedDiscoveryWithoutProcessSecrets(int port, String api, String auth,
                                                                     Path packageRoot, Path localHome) throws Exception {
        String variable = "ATT_244_WAR_ABSENT_71F29BE6";
        assertNull(System.getenv(variable));
        Files.writeString(localHome.resolve("config/db-discovery.yaml"),
                "schemaVersion: att-dbhelper/v2.6\nid: orders\nname: Orders\ndescription: Discovery fixture\n"
                        + "connection: {url: 'jdbc:unused', password: '${ENV:" + variable + "}'}\n");
        Files.writeString(localHome.resolve("config/db-invalid.yaml"), "[invalid: PRIVATE_HELPER_SOURCE_CANARY\n");
        Files.writeString(localHome.resolve("config/config.yaml"),
                "\ndbhelpers: [config/db-discovery.yaml, config/db-absent.yaml, config/db-invalid.yaml]\n",
                java.nio.file.StandardOpenOption.APPEND);
        Path target = Files.createDirectories(packageRoot.resolve("templates/DEGRADED"));
        Files.writeString(target.resolve("template.yaml"),
                "schemaVersion: att-template/v3.6\nname: DEGRADED\ndescription: Browse without credentials\nactions:\n"
                        + "  query:\n    type: tool\n    call: \"#{db.orders.query(sql='select 1')}\"\n");
        JsonNode resources;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            resources = getJson(port, api + "/packages/payments/resources?type=template&query=DEGRADED", auth);
            if (!resources.path("items").isEmpty()) break;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals(1, resources.path("items").size(), resources.toString());
        JsonNode item = resources.path("items").get(0);
        assertEquals("available", item.path("metadataState").asText(), item.toString());
        assertEquals("dependency-unresolved", item.path("executionReadiness").path("state").asText());
        assertTrue(item.path("executionReadiness").toString().contains(variable));
        assertTrue(resources.path("diagnostics").toString().contains("ATT-RESOURCE-HELPER-UNAVAILABLE"));
        assertTrue(resources.path("diagnostics").toString().contains("ATT-RESOURCE-HELPER-INVALID"));
        assertFalse(resources.toString().contains("PRIVATE_HELPER_SOURCE_CANARY"));
        assertFalse(resources.toString().contains(localHome.toString()));
        assertFalse(resources.toString().contains(packageRoot.toString()));
        assertEquals(401, get(port, api + "/packages/payments/resources?type=template&query=DEGRADED", null));
        String effectiveRoute = api + "/packages/payments/configuration/effective?environment=SIT&section=dbhelpers&limit=50";
        JsonNode effective = getJson(port, effectiveRoute, auth);
        assertEquals("degraded", effective.path("state").asText(), effective.toString());
        assertEquals("not-validated", effective.path("executionReadiness").asText());
        assertTrue(effective.path("diagnostics").toString().contains(variable));
        assertTrue(effective.path("diagnostics").toString().contains("ATT-RESOURCE-HELPER-UNAVAILABLE"));
        assertTrue(effective.path("diagnostics").toString().contains("ATT-RESOURCE-HELPER-INVALID"));
        assertFalse(effective.toString().contains("PRIVATE_HELPER_SOURCE_CANARY"));
        assertFalse(effective.toString().contains(localHome.toString()));
        assertFalse(effective.toString().contains(packageRoot.toString()));
        assertEquals(401, get(port, effectiveRoute, null));
    }

    private static void assertSharedConfiguration(int port,String api,String ordinary) throws Exception {
        assertEquals(401,get(port,api+"/configuration/declared",null));
        JsonNode globalDeclared=getJson(port,api+"/configuration/declared",ordinary);
        assertEquals("declared",globalDeclared.path("state").asText());
        assertEquals("ready",getJson(port,api+"/configuration/files?limit=100",ordinary).path("state").asText());
        JsonNode globalSource=getJson(port,api+"/configuration/source?path=config/config.yaml",ordinary);
        assertEquals(getJson(port,api+"/packages/payments/configuration/source?path=config/config.yaml",ordinary).path("text"),globalSource.path("text"));
        assertTrue(globalSource.path("available").asBoolean());
        assertFalse(globalSource.toString().contains("helper-secret"));
        assertNotEquals("invalid",getJson(port,api+"/configuration/effective?environment=SIT",ordinary).path("state").asText());
        assertEquals("ready",getJson(port,api+"/configuration/compare?left=SIT&right=UAT&section=dbhelpers",ordinary).path("state").asText());
        assertEquals(403,get(port,api+"/configuration/effective?includeAllFields=true",ordinary));
        assertEquals(404,get(port,api+"/configuration/testdata/generatedAccounts/records",ordinary));
        JsonNode origin=getJson(port,api+"/configuration/source?path=config/source-references.yaml",ordinary);
        JsonNode reference=null;
        for(JsonNode item:origin.path("fileReferences"))
            if("config/tools/att-helper.sh".equals(item.path("logicalPath").asText()))reference=item;
        assertNotNull(reference,origin.toString());
        String linked="/configuration/source?path=config/tools/att-helper.sh&origin=config/source-references.yaml&referenceId="+reference.path("referenceId").asText();
        assertTrue(getJson(port,api+linked,ordinary).path("available").asBoolean());
        assertEquals(404,get(port,api+"/packages/payments"+linked,ordinary),"Shared references cannot cross into Package-scoped routes");
        assertEquals(404,get(port,api+"/configuration/source?path=packages/default/private.yaml",ordinary));
        assertEquals(404,get(port,api+"/configuration/source?path=config/../packages/default/private.yaml",ordinary));

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
