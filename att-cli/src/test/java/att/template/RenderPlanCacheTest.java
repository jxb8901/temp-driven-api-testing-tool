package att.template;

import att.core.CaseRuntimeContext;
import att.core.TestCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RenderPlanCacheTest {
    @TempDir Path tempDir;

    @Test void freezesPayloadAndCompiledStructureButEvaluatesEachIterationContext() throws Exception {
        Path templateRoot = Files.createDirectories(tempDir.resolve("templates/payment"));
        Path payload = templateRoot.resolve("request.txt");
        Files.write(payload, "id=${CASE.reference};optional=${CASE.missing?};upper=#{upper(${CASE.reference})}".getBytes(StandardCharsets.UTF_8));
        // The runner plan uses the authored payload pattern directly; build the equivalent loaded action.
        Map<String, Object> raw = new LinkedHashMap<String, Object>(); raw.put("type", "render"); raw.put("payload", "request.txt");
        StageTemplate template = new StageTemplate("payment", templateRoot,
                Collections.singletonList(new TemplateAction("request", raw)));

        RenderPlanCache cache = new RenderPlanCache();
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null);
        cache.freeze(template, null);
        RenderPlanCache.Plan frozen = cache.get(template, "request.txt", engine);
        Files.write(payload, "edited=${CASE.reference}".getBytes(StandardCharsets.UTF_8));
        assertSame(frozen, cache.get(template, "request.txt", engine));

        CaseRuntimeContext context = context("one");
        assertEquals("id=one;optional=;upper=ONE", engine.render(frozen.sources().get(0).compiled(), context, null));
        context.put("CASE.reference", "two");
        assertEquals("id=two;optional=;upper=TWO", engine.render(frozen.sources().get(0).compiled(), context, null));
        cache.recordEvaluation();
        cache.recordEvaluation();
        assertEquals(1L, cache.stats().plansCompiled());
        assertEquals(1L, cache.stats().payloadResolutions());
        assertEquals(2L, cache.stats().payloadResolutionCacheHits());
        assertEquals(2L, cache.stats().evaluations());
        assertEquals(0L, cache.stats().artifactWrites());
    }

    @Test void sharesCompiledPlanSafelyAcrossConcurrentIterationContexts() throws Exception {
        Path templateRoot = Files.createDirectories(tempDir.resolve("templates/concurrent"));
        Files.write(templateRoot.resolve("request.txt"), "${CASE.reference}:#{upper(${CASE.reference})}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> raw = new LinkedHashMap<String, Object>(); raw.put("type", "render"); raw.put("payload", "request.txt");
        StageTemplate template = new StageTemplate("concurrent", templateRoot,
                Collections.singletonList(new TemplateAction("request", raw)));
        RenderPlanCache cache = new RenderPlanCache(); cache.freeze(template, null);
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> rendered = new ArrayList<Future<String>>();
            for (int index = 0; index < 64; index++) {
                final int value = index;
                rendered.add(workers.submit(new Callable<String>() {
                    @Override public String call() throws Exception {
                        RenderPlanCache.Plan plan = cache.get(template, "request.txt", engine);
                        cache.recordEvaluation();
                        return engine.render(plan.sources().get(0).compiled(), context("item-" + value), null);
                    }
                }));
            }
            for (int index = 0; index < rendered.size(); index++)
                assertEquals("item-" + index + ":ITEM-" + index, rendered.get(index).get());
        } finally { workers.shutdownNow(); }
        assertEquals(1L, cache.stats().plansCompiled());
        assertEquals(1L, cache.stats().payloadResolutions());
        assertEquals(64L, cache.stats().evaluations());
    }

    private CaseRuntimeContext context(String reference) {
        Map<String, Object> data = new LinkedHashMap<String, Object>(); data.put("reference", reference);
        return new CaseRuntimeContext(new TestCase(2, "payment", "sheet", "TC001",
                Collections.<String>emptyList(), data, Collections.emptyMap(), null),
                tempDir, "RUN-1", tempDir, tempDir.resolve("case.log"));
    }
}
