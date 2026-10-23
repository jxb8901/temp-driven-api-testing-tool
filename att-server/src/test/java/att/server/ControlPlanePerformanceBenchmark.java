package att.server;

import att.server.api.WorkerRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import static org.junit.jupiter.api.Assertions.*;

/** Explicitly selected benchmark, excluded from ordinary Surefire test discovery. */
class ControlPlanePerformanceBenchmark {
    @TempDir Path temp;
    private final List<Map<String,Object>> measurements = new ArrayList<>();

    @Test void writesReproducibleMetadataJournalAndClasspathMeasurements() throws Exception {
        var bean = ManagementFactory.getThreadMXBean();
        if (bean.isThreadContentionMonitoringSupported()) bean.setThreadContentionMonitoringEnabled(true);
        Path pkg = Files.createDirectories(temp.resolve("packages/p"));
        Path runtime = Path.of("config/config.yaml").toAbsolutePath();
        Path file = temp.resolve("server.yaml");
        Files.writeString(file, "server:\n  dataDir: " + temp.resolve("data") + "\n  runtimeConfigPath: " + runtime
                + "\nworkers: {}\npackages:\n  allowedRoots: [" + pkg.getParent() + "]\n  entries:\n    p: " + pkg + "\n");
        ServerConfig config = ServerConfig.load(file);
        int samples = Integer.getInteger("att.backend.samples", 64);
        assertTrue(samples >= 20 && samples <= 10000);
        try (JobStore store = new JobStore(config)) {
            for (int clients : new int[]{1,4,8,16}) {
                ExecutorService threads = Executors.newFixedThreadPool(clients);
                try {
                    CountDownLatch start = new CountDownLatch(1);
                    List<Future<Map<String,Object>>> futures = new ArrayList<>();
                    for (int client = 0; client < clients; client++) {
                        final int index = client;
                        futures.add(threads.submit(() -> {
                            String id = String.format("J%016x", clients * 100L + index);
                            Job job = new Job(id,"run","p","benchmark",new WorkerRequest(),
                                    new JobEvents(config.dataDir.resolve("jobs/" + id + "/events.jsonl"), 100));
                            job.status = "PASS";
                            start.await();
                            long blockedBefore = bean.getThreadInfo(Thread.currentThread().getId()).getBlockedTime();
                            long cpuBefore = bean.getCurrentThreadCpuTime();
                            long[] times = new long[samples];
                            for (int i = -10; i < samples; i++) {
                                long before = System.nanoTime();
                                if (i == -10) store.insert(job,"{}");
                                store.update(job);
                                assertNotNull(store.get(id));
                                store.list(20);
                                store.authUser("missing-benchmark-user");
                                store.scheduleCount();
                                assertTrue(store.setPinned(id, true, "benchmark"));
                                assertTrue(store.setPinned(id, false, "benchmark"));
                                if (i >= 0) times[i] = System.nanoTime() - before;
                            }
                            Map<String,Object> row = summary("jobstore", times);
                            row.put("clients", clients);
                            row.put("threadCpuMs", (bean.getCurrentThreadCpuTime() - cpuBefore) / 1e6);
                            row.put("monitorBlockedMs", bean.getThreadInfo(Thread.currentThread().getId()).getBlockedTime() - blockedBefore);
                            return row;
                        }));
                    }
                    start.countDown();
                    for (var future : futures) measurements.add(future.get(120, TimeUnit.SECONDS));
                } finally { threads.shutdownNow(); assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS)); }
            }
        }
        for (int rate : new int[]{10,100,1000}) for (String observer : List.of("none","fast","slow","throwing")) {
            Path journal = temp.resolve("journal/" + rate + "-" + observer + ".jsonl");
            JobEvents events = new JobEvents(journal, 32);
            List<Long> delivered = new ArrayList<>();
            try (AutoCloseable listener = events.listen(event -> {
                delivered.add(((Number)event.get("id")).longValue());
                if (observer.equals("slow")) LockSupport.parkNanos(2_000_000);
                if (observer.equals("throwing")) throw new IllegalStateException("synthetic observer failure");
            })) {
                // none uses an inert listener; fast also verifies delivery below.
                if (observer.equals("none")) listener.close();
                long[] times = new long[samples];
                long started = System.nanoTime();
                for (int i = 0; i < samples; i++) {
                    long target = started + (long)i * 1_000_000_000L / rate;
                    while (System.nanoTime() < target) LockSupport.parkNanos(target - System.nanoTime());
                    long before = System.nanoTime();
                    events.append("log", Map.of("message", "x".repeat(128)));
                    times[i] = System.nanoTime() - before;
                }
                events.append("result", Map.of("status","PASS"));
                JobEvents recovered = new JobEvents(journal,32);
                assertEquals(events.latest(),recovered.latest());
                assertTrue(recovered.resultDeliveredThrough(recovered.latest()));
                long cursor = 0;
                for (var event : recovered.after(0)) {
                    long id = ((Number)event.get("id")).longValue();
                    assertTrue(id > cursor); cursor = id;
                }
                if (!observer.equals("none")) assertEquals(samples + 1, delivered.size());
                Map<String,Object> row = summary("journal",times);
                row.put("targetEventsPerSecond",rate); row.put("observer",observer);
                row.put("elapsedMs",(System.nanoTime()-started)/1e6);
                row.put("retainedBytes",Files.size(journal));
                measurements.add(row);
            }
        }
        Path libs = Files.createDirectories(temp.resolve("lib"));
        for (int i=0;i<50;i++) Files.createFile(libs.resolve("fixture-"+i+".jar"));
        long[] times = new long[samples];
        for (int i=-10;i<samples;i++) {
            long before=System.nanoTime(); assertEquals(50,WorkerRuntimeClasspath.entries(libs,List.of()).size());
            if(i>=0) times[i]=System.nanoTime()-before;
        }
        measurements.add(summary("classpath-50-jars",times));
        Map<String,Object> report = new LinkedHashMap<>();
        report.put("schemaVersion","att-control-plane-benchmark/v1");
        report.put("java",System.getProperty("java.runtime.version"));
        report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version")+" "+System.getProperty("os.arch"));
        report.put("processors",Runtime.getRuntime().availableProcessors());
        report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
        report.put("samples",samples); report.put("storeWarmups",10);
        report.put("commit",System.getProperty("att.benchmark.commit","unspecified"));
        report.put("measurements",measurements);
        Path output=Path.of(System.getProperty("att.backend.output","target/control-plane-benchmark.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
    }

    private static Map<String,Object> summary(String name,long[] times) {
        List<Double> raw=Arrays.stream(times).mapToDouble(value->value/1e6).boxed().toList();
        double[] sorted=raw.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        Map<String,Object> row=new LinkedHashMap<>(); row.put("operation",name); row.put("rawMs",raw);
        row.put("p50Ms",sorted[(int)Math.ceil(sorted.length*.50)-1]);
        row.put("p95Ms",sorted[(int)Math.ceil(sorted.length*.95)-1]); return row;
    }
}
