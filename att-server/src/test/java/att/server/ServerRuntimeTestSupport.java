package att.server;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.fail;

/** Shared fixtures for the ServerRuntime test classes. Each class still runs in its own JVM. */
final class ServerRuntimeTestSupport {
    private ServerRuntimeTestSupport() {}

    /** Execution-only fake launchers still need a valid configuration-resolution protocol. */
    static ServerRuntime.WorkerProcessLauncher resolvingConfiguration(ServerRuntime.WorkerProcessLauncher execution) {
        return builder -> {
            if (!builder.command().contains("--inspection-daemon")) return execution.start(builder);
            return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    ConfigurationProcess.class.getName()).start();
        };
    }

    public static class ConfigurationProcess {
        public static void main(String[] args) throws Exception {
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                var request = ServerRuntime.JSON.readTree(line);
                if (!"package-configuration".equals(request.path("inspectionType").asText()) || !"resolve".equals(request.path("inspectionAction").asText()))
                    throw new IllegalArgumentException("Execution fixture only supports configuration resolution");
                String environment = request.path("environment").asText("SIT");
                Map<String,Object> evidence=new java.util.LinkedHashMap<>();evidence.put("environment",environment);
                evidence.put("environmentOrigin",request.hasNonNull("environment")?"request":"global");
                for(String key:List.of("configurationIdentity","manifestDigest","globalDigest","schemaRegistryDigest"))evidence.put(key,"a".repeat(64));
                evidence.put("engineVersion","fixture");evidence.put("engineRevision","fixture");
                evidence.put("origins",Map.of("environment",evidence.get("environmentOrigin")));
                evidence.put("authoredPaths",Map.of("environment",request.hasNonNull("environment")?"request.environment":"config/config.yaml"));
                System.out.println(ServerRuntime.JSON.writeValueAsString(Map.of("type", "RESULT", "status", "PASS", "exitCode", 0,
                        "result", Map.of("summary", Map.of("inspection", evidence)))));
                System.out.flush();
            }
        }
    }

    static Map<String,Object> awaitUnifiedJob(ServerRuntime runtime,String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline) {
            Map<String,Object> job=runtime.jobRecord(id);
            if(List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(job.get("status"))) {
                while(runtime.workers.getActiveCount()>0&&System.nanoTime()<deadline)Thread.sleep(10);
                return runtime.resultRecord(id);
            }
            Thread.sleep(20);
        }
        fail("RUN did not terminate: "+runtime.jobRecord(id));return Map.of();
    }

    static void addModuleJar(Path lib,String name,Path classes)throws Exception {
        Path target=lib.resolve(name+".jar");try(OutputStream file=Files.newOutputStream(target);JarOutputStream jar=new JarOutputStream(file);var paths=Files.walk(classes)) {
            paths.filter(Files::isRegularFile).forEach(path->{try{jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));Files.copy(path,jar);jar.closeEntry();}catch(Exception e){throw new IllegalStateException(e);}});
        }
    }

    static void addWorkerClasspathJars(Path libs)throws Exception {
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path candidate=Path.of(element);
            if(Files.isRegularFile(candidate)&&candidate.toString().endsWith(".jar")&&!candidate.getFileName().toString().startsWith("att-worker-")&&!candidate.getFileName().toString().startsWith("att-engine-")) {
                Path target=libs.resolve(candidate.getFileName());if(!Files.exists(target))try{Files.createSymbolicLink(target,candidate);}catch(Exception unsupported){Files.copy(candidate,target);}
            }
        }
    }

    static void copySchemas(Path packageRoot)throws Exception {
        Path source=Path.of("schemas").toRealPath(),destination=packageRoot.resolve("schemas");
        try(var paths=Files.walk(source)) {
            for(Path path:(Iterable<Path>)paths::iterator) {
                Path target=destination.resolve(source.relativize(path));
                if(Files.isDirectory(path))Files.createDirectories(target);
                else {Files.createDirectories(target.getParent());Files.copy(path,target);}
            }
        }
    }

    static String yaml(Path path){return "'"+path.toAbsolutePath().toString().replace("'","''")+"'";}

    static final class MutableClock extends Clock {
        volatile Instant current=Instant.now();
        MutableClock(){}
        MutableClock(Instant initial){current=initial;}
        @Override public ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return current;}
        void advance(Duration duration){current=current.plus(duration);}
    }
}
