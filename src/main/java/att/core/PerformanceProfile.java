/* Author: Jeffrey + ChatGPT */
package att.core;

import att.Version;
import att.exec.CommandRunner;
import att.template.PayloadCache;
import att.template.StageTemplateLoader;
import att.validation.JsonSchemaVerifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-run phase timings and cache/I/O counters emitted by --profile. */
public final class PerformanceProfile {
    private final boolean enabled;
    private final long started = System.nanoTime();
    private final Map<String, Object> phases = new LinkedHashMap<String, Object>();
    private final Map<String, Object> counters = new LinkedHashMap<String, Object>();
    private final JsonSchemaVerifier.Stats schemaStart = JsonSchemaVerifier.stats();
    private final StageTemplateLoader.Stats templateStart = StageTemplateLoader.stats();
    private final PayloadCache.Stats payloadStart = PayloadCache.stats();
    private final CommandRunner.Stats processStart = CommandRunner.stats();

    public PerformanceProfile(boolean enabled) { this.enabled = enabled; }
    public long begin() { return System.nanoTime(); }
    public void end(String phase, long phaseStarted) { if (enabled) phases.put(phase, millis(System.nanoTime() - phaseStarted)); }
    public void counter(String name, long value) { if (enabled) counters.put(name, value); }

    public void write(Path runDirectory) throws Exception {
        if (!enabled) return;
        JsonSchemaVerifier.Stats schemas = JsonSchemaVerifier.stats();
        StageTemplateLoader.Stats templates = StageTemplateLoader.stats();
        PayloadCache.Stats payloads = PayloadCache.stats();
        CommandRunner.Stats process = CommandRunner.stats();
        counters.put("schemasCompiled", schemas.compiles() - schemaStart.compiles());
        counters.put("schemaCacheHits", schemas.hits() - schemaStart.hits());
        counters.put("templatesLoaded", templates.loads() - templateStart.loads());
        counters.put("templateCacheHits", templates.hits() - templateStart.hits());
        counters.put("payloadsLoaded", payloads.loads() - payloadStart.loads());
        counters.put("payloadCacheHits", payloads.hits() - payloadStart.hits());
        counters.put("processStdoutBytes", process.stdoutBytes() - processStart.stdoutBytes());
        counters.put("processStderrBytes", process.stderrBytes() - processStart.stderrBytes());
        counters.put("processTruncatedStreams", process.truncatedStreams() - processStart.truncatedStreams());
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", "att-performance/v2.4.3");
        root.put("attVersion", Version.PRODUCT);
        root.put("totalMs", millis(System.nanoTime() - started));
        root.put("phases", phases);
        root.put("counters", counters);
        Map<String, Object> memory = new LinkedHashMap<String, Object>();
        Runtime runtime = Runtime.getRuntime();
        memory.put("usedHeapBytes", runtime.totalMemory() - runtime.freeMemory());
        memory.put("committedHeapBytes", runtime.totalMemory());
        memory.put("maxHeapBytes", runtime.maxMemory());
        root.put("memorySnapshot", memory);
        Files.write(runDirectory.resolve("performance.json"), att.validation.JsonSupport.write(root).getBytes(StandardCharsets.UTF_8));
    }

    private long millis(long nanos) { return nanos / 1000000L; }
}
