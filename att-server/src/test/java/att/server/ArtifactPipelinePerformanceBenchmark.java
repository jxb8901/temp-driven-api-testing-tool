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

    @Test void measuresSmallAndLargeBytePreservingPublication() throws Exception {
        int samples=Integer.getInteger("att.archive.samples",20);
        assertTrue(samples>=5&&samples<=1000);
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int files:new int[]{24,100,500,1000,2048}) {
            Path output=Files.createDirectories(temp.resolve("output-"+files));
            // Fixed incompressible-ish safe text, deterministic across revisions.
            Random random=new Random(280);
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            long sourceBytes=0;
            for(int i=0;i<files;i++) {
                byte[] noise=new byte[files==24?2048:4096];random.nextBytes(noise);
                // Model the Worker protecting its output before it writes the file.
                String text=DiagnosticSanitizer.redactText("password: synthetic-secret\n"+Base64.getEncoder().encodeToString(noise)+"\n");
                byte[] bytes=text.getBytes(StandardCharsets.UTF_8);digest.update(bytes);sourceBytes+=bytes.length;
                Path file=output.resolve("load/run/samples/workload-"+(i%4)+"/"+i+".log");
                Files.createDirectories(file.getParent());Files.write(file,bytes);
            }
            byte[] structured=("time=2026-10-28T08:00:00Z method=GET path=/api/v1/jobs status=200 latency_ms=12\n").repeat(8192).getBytes(StandardCharsets.UTF_8);
            byte[] repetitive=("OK\n").repeat(32768).getBytes(StandardCharsets.UTF_8);
            for(Map.Entry<String,byte[]> fixture:Map.of("structured.log",structured,"repetitive.log",repetitive).entrySet()) {
                digest.update(fixture.getValue());sourceBytes+=fixture.getValue().length;
                Path file=output.resolve("load/run/samples/"+fixture.getKey());Files.createDirectories(file.getParent());Files.write(file,fixture.getValue());
            }
            Path html=output.resolve("load/run/report/index.html");Files.createDirectories(html.getParent());
            Files.writeString(html,"<html><a href=\"../samples/workload-0/0.log\">Evidence</a></html>");
            writeSuccessfulLoadBundleWithCheckpoints(output);
            long privateSourceBytesBeforePublication=sourceTreeBytes(output);
            List<Map<String,Object>> measurements=new ArrayList<>();
            for(int iteration=-2;iteration<samples;iteration++) {
                for(var pool:ManagementFactory.getMemoryPoolMXBeans())pool.resetPeakUsage();
                ArtifactMetricsRecorder metrics=new ArtifactMetricsRecorder(System.nanoTime(),1);
                Path archive=temp.resolve("published-"+files+"/archive.zip");
                ArtifactMetricsRecorder.CURRENT.set(metrics);
                try {
                    ArtifactArchive.build(output,archive);
                } finally {metrics.finish();ArtifactMetricsRecorder.CURRENT.remove();}
                assertEquals(files+8,((List<?>)ArtifactArchive.verify(archive,true).get("entries")).size());
                String text=new String(ArtifactArchive.read(archive,"load/run/samples/workload-0/0.log"),StandardCharsets.UTF_8);
                assertFalse(text.contains("synthetic-secret"));assertTrue(text.contains("[REDACTED_SECRET]"));
                String publishedHtml=new String(ArtifactArchive.read(archive,"load/run/report/index.html"),StandardCharsets.UTF_8);
                assertTrue(publishedHtml.contains("../samples/workload-0/0.log"));
                try(var paths=Files.list(archive.getParent())) {assertEquals(List.of(archive),paths.toList());}
                if(iteration>=0) {
                    Map<String,Object> row=ServerRuntime.JSON.convertValue(metrics.value,Map.class);
                    row.put("producerApprovedBytes",sourceBytes);
                    long heapPeak=ManagementFactory.getMemoryPoolMXBeans().stream()
                            .filter(p->p.getType()==java.lang.management.MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum();
                    row.put("heapPoolsPeakBytes",heapPeak);measurements.add(row);
                }
            }
            Map<String,Object> row=new LinkedHashMap<>();row.put("files",files+8);row.put("sourceBytesWithoutHtml",sourceBytes);
            row.put("fixtureBodySha256",HexFormat.of().formatHex(digest.digest()));row.put("samples",measurements);
            for(String metric:List.of("publicationWallMs","scanValidationPreservationMs","zipWriteMs","verificationFsyncMs","cleanupMs","writerCpuMs")) {
                double[] values=measurements.stream().map(m->m.get(metric)).filter(Objects::nonNull).mapToDouble(v->((Number)v).doubleValue()).sorted().toArray();
                if(values.length>0)row.put(metric,Map.of("p50",values[(values.length-1)/2],"p95",values[(int)Math.ceil(values.length*.95)-1]));
            }
            row.put("compressionModes",measureCompressionModes(output,files));
            // Keep the resource pass outside the timed sample series. The sampler observes
            // OS RSS for the benchmark JVM and descendants while a representative archive is built.
            ArtifactMetricsRecorder resourceMetrics=new ArtifactMetricsRecorder(System.nanoTime(),1,false);
            ProcessTreeResourceSampler resources=ProcessTreeResourceSampler.start();
            ArtifactMetricsRecorder.CURRENT.set(resourceMetrics);
            try {
                ArtifactArchive.build(output,temp.resolve("resource-"+files+"/archive.zip"));
            } finally {
                resourceMetrics.finish();ArtifactMetricsRecorder.CURRENT.remove();resources.close();
            }
            row.put("resourceSample",resources.snapshot());
            row.put("memoryBudgetPolicy","revision production default");
            Map<String,Object> ioVolume=new LinkedHashMap<>();
            ioVolume.put("sourceBytes",resourceMetrics.value.sourceBytes);
            ioVolume.put("producerBytesSpilled",optionalMetric(resourceMetrics.value,"temporaryExportBytes"));
            ioVolume.put("producerBytesReread",optionalMetric(resourceMetrics.value,"temporaryExportReadBytes"));
            ioVolume.put("zipEntryInputBytes",optionalMetric(resourceMetrics.value,"zipWriteInputBytes"));
            ioVolume.put("verificationPayloadBytesRead",optionalMetric(resourceMetrics.value,"verificationBytesRead"));
            ioVolume.put("zipBytesWritten",resourceMetrics.value.zipBytes==null?0:resourceMetrics.value.zipBytes);
            ioVolume.put("sourceTreeBytesBeforePublication",privateSourceBytesBeforePublication);
            ioVolume.put("sourceTreeBytesAfterPublication",sourceTreeBytes(output));
            ioVolume.put("verifiedCheckpointsExcludedCount",resourceMetrics.value.intentionallyExcludedRecoveryCheckpointFiles);
            ioVolume.put("verifiedCheckpointBytesExcluded",resourceMetrics.value.intentionallyExcludedRecoveryCheckpointBytes);
            ioVolume.put("archivePayloadBytesAfterClassification",resourceMetrics.value.includedBytes);
            ioVolume.put("measurement", "application-level bytes; not physical device I/O");
            ioVolume.put("nullCounterMeaning", "counter is not available in this revision's metrics API");
            row.put("ioVolume",ioVolume);
            rows.add(row);
        }
        Map<String,Object> report=new LinkedHashMap<>();report.put("schemaVersion","att-artifact-pipeline-benchmark/v1");
        report.put("java",System.getProperty("java.runtime.version"));report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.version")+" "+System.getProperty("os.arch"));
        report.put("processors",Runtime.getRuntime().availableProcessors());report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
        report.put("commit",System.getProperty("att.benchmark.commit","unspecified"));report.put("seed",280);report.put("warmup",2);report.put("measurements",rows);
        report.put("memoryBudgetPolicy","revision production default");
        Path destination=Path.of(System.getProperty("att.archive.output","target/artifact-pipeline-benchmark.json"));
        Files.createDirectories(destination.toAbsolutePath().getParent());
        ServerRuntime.JSON.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(),report);
        System.out.println("Artifact pipeline benchmark written to "+destination.toAbsolutePath());
    }

    private List<Map<String,Object>> measureCompressionModes(Path output,int files) throws Exception {
        int samples=Integer.getInteger("att.archive.compressionSamples",5);
        assertTrue(samples>=1&&samples<=1000);
        List<Map<String,Object>> modes=new ArrayList<>();
        for(String mode:List.of("store","deflate","auto")) {
            List<Map<String,Object>> observations=new ArrayList<>();
            for(int iteration=-1;iteration<samples;iteration++) {
                ArtifactMetricsRecorder metrics=new ArtifactMetricsRecorder(System.nanoTime(),1);
                Path archive=temp.resolve("compression-"+files+"-"+mode+"-"+iteration+"/archive.zip");
                ArtifactMetricsRecorder.CURRENT.set(metrics);
                try { ArtifactArchive.build(output,archive,null,new ServerConfig.ArchiveCompression(mode,3)); }
                finally { metrics.finish();ArtifactMetricsRecorder.CURRENT.remove(); }
                Map<String,Object> manifest=ArtifactArchive.verify(archive,true);
                assertEquals(files+8,((List<?>)manifest.get("entries")).size());
                if(iteration>=0)observations.add(ServerRuntime.JSON.convertValue(metrics.value,Map.class));
            }
            Map<String,Object> modeRow=new LinkedHashMap<>();modeRow.put("mode",mode);modeRow.put("level",3);modeRow.put("samples",observations);
            for(String metric:List.of("zipBytes","compressedPayloadBytes","compressionDurationMs","compressionSavingsPercent",
                    "storedEntries","deflatedEntries","compressionFallbackCount","writerCpuMs","writerAllocatedBytes")) {
                double[] values=observations.stream().map(item->item.get(metric)).filter(Number.class::isInstance)
                        .mapToDouble(item->((Number)item).doubleValue()).sorted().toArray();
                if(values.length>0)modeRow.put(metric,Map.of("p50",values[(values.length-1)/2],"p95",values[(int)Math.ceil(values.length*.95)-1]));
            }
            modes.add(modeRow);
        }
        return modes;
    }

    private static void writeSuccessfulLoadBundleWithCheckpoints(Path output) throws Exception {
        Path run=output.resolve("load/benchmark");Files.createDirectories(run.resolve("report"));Files.createDirectories(run.resolve("details/v1"));
        Map<String,Object> summary=new LinkedHashMap<>();summary.put("runId","benchmark");summary.put("durationMs",1000L);
        summary.put("executionStatus","PASS");summary.put("performanceVerdict","NOT_MET");summary.put("report","report/index.html");
        summary.put("reportManifest","load-report-manifest.json");
        Files.write(run.resolve("load-summary.json"),ServerRuntime.JSON.writeValueAsBytes(summary));
        Files.writeString(run.resolve("load-summary.yaml"),"runId: benchmark\nexecutionStatus: PASS\n",StandardCharsets.UTF_8);
        Files.writeString(run.resolve("report/index.html"),"<html>complete report</html>",StandardCharsets.UTF_8);
        Files.writeString(run.resolve("details/v1/manifest.json"),"{\"complete\":true}",StandardCharsets.UTF_8);
        List<Map<String,Object>> listed=new ArrayList<>();
        for(String relative:List.of("load-summary.json","load-summary.yaml","report/index.html","details/v1/manifest.json")) {
            byte[] bytes=Files.readAllBytes(run.resolve(relative));
            listed.add(Map.of("path",relative,"lengthBytes",bytes.length,"sha256",ArtifactArchive.sha256(bytes)));
        }
        Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("schemaVersion",ArtifactArchive.LOAD_REPORT_MANIFEST_SCHEMA);
        manifest.put("state","COMPLETE");manifest.put("complete",Boolean.TRUE);manifest.put("runId","benchmark");
        manifest.put("executionStatus","PASS");manifest.put("performanceVerdict","NOT_MET");manifest.put("durationMs",1000L);manifest.put("files",listed);
        Files.write(run.resolve(ArtifactArchive.LOAD_REPORT_MANIFEST),ServerRuntime.JSON.writeValueAsBytes(manifest));
        Path checkpointDirectory=run.resolve("checkpoints/aggregate");Files.createDirectories(checkpointDirectory);
        for(long sequence=1;sequence<=2;sequence++) {
            Map<String,Object> checkpoint=new LinkedHashMap<>();checkpoint.put("schemaVersion","att-load-metrics-checkpoint/v2");
            checkpoint.put("state",sequence==2?"FINAL_CHECKPOINT":"CHECKPOINT");checkpoint.put("runId","benchmark");
            checkpoint.put("scope","aggregate");checkpoint.put("sequence",sequence);checkpoint.put("capturedAt","2026-10-28T08:00:00Z");
            checkpoint.put("finalized",sequence==2);checkpoint.put("payload",Map.of("metrics",Map.of("completed",sequence)));
            checkpoint.put("integritySha256",ArtifactArchive.sha256(ServerRuntime.JSON.writeValueAsBytes(checkpoint)));
            Files.write(checkpointDirectory.resolve("checkpoint-"+String.format(Locale.ROOT,"%020d",sequence)+".json"),
                    ServerRuntime.JSON.writeValueAsBytes(checkpoint));
        }
    }

    private static long sourceTreeBytes(Path root) throws Exception {
        try(var paths=Files.walk(root)) {
            long total=0L;
            for(Path path:(Iterable<Path>)paths.filter(item->Files.isRegularFile(item,LinkOption.NOFOLLOW_LINKS))::iterator) {
                long size=Files.size(path);total=size>Long.MAX_VALUE-total?Long.MAX_VALUE:total+size;
            }
            return total;
        }
    }

    private static Object optionalMetric(Object metrics,String name) {
        try {
            Object value=metrics.getClass().getField(name).get(metrics);
            return value==null?0L:value;
        } catch (NoSuchFieldException unavailable) {
            return null;
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot read optional artifact metric "+name,error);
        }
    }

}
