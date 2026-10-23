package att.server;

import att.server.api.internal.DiagnosticSanitizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit benchmark selection only; synthetic files, no external SUT. */
class ArtifactPipelinePerformanceBenchmark {
    @TempDir Path temp;

    @Test void measuresSmallAndLargeSanitizedPublication() throws Exception {
        int samples=Integer.getInteger("att.archive.samples",20);
        assertTrue(samples>=5&&samples<=1000);
        List<Map<String,Object>> rows=new ArrayList<>();
        long memoryBudget=Long.getLong("att.archive.memoryBytes",ArtifactArchive.MAX_MEMORY_EXPORT_BYTES);
        for(int files:new int[]{24,2048}) {
            Path output=Files.createDirectories(temp.resolve("output-"+files));
            // Fixed incompressible-ish safe text, deterministic across revisions.
            Random random=new Random(280);
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            long sourceBytes=0;
            for(int i=0;i<files;i++) {
                byte[] noise=new byte[files==24?2048:4096];random.nextBytes(noise);
                String text="password: synthetic-secret\n"+Base64.getEncoder().encodeToString(noise)+"\n";
                byte[] bytes=text.getBytes(StandardCharsets.UTF_8);digest.update(bytes);sourceBytes+=bytes.length;
                Path file=output.resolve("load/run/samples/workload-"+(i%4)+"/"+i+".log");
                Files.createDirectories(file.getParent());Files.write(file,bytes);
            }
            Path html=output.resolve("load/run/report/index.html");Files.createDirectories(html.getParent());
            Files.writeString(html,"<html><a href=\"../samples/workload-0/0.log\">Evidence</a></html>");
            List<Map<String,Object>> measurements=new ArrayList<>();
            for(int iteration=-2;iteration<samples;iteration++) {
                for(var pool:ManagementFactory.getMemoryPoolMXBeans())pool.resetPeakUsage();
                ArtifactMetricsRecorder metrics=new ArtifactMetricsRecorder(System.nanoTime(),1);
                long[] publishNanos={0},sanitizedBytes={0};
                Path archive=temp.resolve("published-"+files+"/archive.zip");
                ArtifactMetricsRecorder.CURRENT.set(metrics);
                try {
                    ArtifactArchive.build(output,archive,path->{
                        long start=System.nanoTime();
                        byte[] bytes=DiagnosticSanitizer.redactText(Files.readString(path)).getBytes(StandardCharsets.UTF_8);
                        publishNanos[0]+=System.nanoTime()-start;sanitizedBytes[0]+=bytes.length;return bytes;
                    },memoryBudget);
                } finally {metrics.finish();ArtifactMetricsRecorder.CURRENT.remove();}
                assertEquals(files+1,((List<?>)ArtifactArchive.verify(archive,true).get("entries")).size());
                String text=new String(ArtifactArchive.read(archive,"load/run/samples/workload-0/0.log"),StandardCharsets.UTF_8);
                assertFalse(text.contains("synthetic-secret"));assertTrue(text.contains("[REDACTED_SECRET]"));
                String publishedHtml=new String(ArtifactArchive.read(archive,"load/run/report/index.html"),StandardCharsets.UTF_8);
                assertTrue(publishedHtml.contains("../samples/workload-0/0.log"));
                try(var paths=Files.list(archive.getParent())) {assertEquals(List.of(archive),paths.toList());}
                if(iteration>=0) {
                    Map<String,Object> row=ServerRuntime.JSON.convertValue(metrics.value,Map.class);
                    row.put("publisherMs",publishNanos[0]/1e6);row.put("sanitizedBytes",sanitizedBytes[0]);
                    long heapPeak=ManagementFactory.getMemoryPoolMXBeans().stream()
                            .filter(p->p.getType()==java.lang.management.MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum();
                    row.put("heapPoolsPeakBytes",heapPeak);measurements.add(row);
                }
            }
            Map<String,Object> row=new LinkedHashMap<>();row.put("files",files+1);row.put("sourceBytesWithoutHtml",sourceBytes);
            row.put("fixtureBodySha256",HexFormat.of().formatHex(digest.digest()));row.put("samples",measurements);
            for(String metric:List.of("publicationWallMs","scanClassificationRedactionMs","zipWriteMs","verificationFsyncMs","cleanupMs","publisherMs","writerCpuMs")) {
                double[] values=measurements.stream().map(m->m.get(metric)).filter(Objects::nonNull).mapToDouble(v->((Number)v).doubleValue()).sorted().toArray();
                if(values.length>0)row.put(metric,Map.of("p50",values[(values.length-1)/2],"p95",values[(int)Math.ceil(values.length*.95)-1]));
            }
            rows.add(row);
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("schemaVersion","att-artifact-pipeline-benchmark/v1");
        report.put("java",System.getProperty("java.runtime.version"));report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version")+" "+System.getProperty("os.arch"));
        report.put("processors",Runtime.getRuntime().availableProcessors());report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
        report.put("commit",System.getProperty("att.benchmark.commit","unspecified"));report.put("seed",280);report.put("warmup",2);report.put("measurements",rows);
        report.put("memoryBudgetBytes",memoryBudget);
        Path destination=Path.of(System.getProperty("att.archive.output","target/artifact-pipeline-benchmark.json"));
        Files.createDirectories(destination.toAbsolutePath().getParent());
        ServerRuntime.JSON.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(),report);
        System.out.println("Artifact pipeline benchmark written to "+destination.toAbsolutePath());
    }
}
