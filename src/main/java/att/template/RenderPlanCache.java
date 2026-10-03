package att.template;

import att.flow.FlowDefinition;
import att.flow.FlowRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Run-scoped immutable Render payload and expression plans. */
public final class RenderPlanCache {
    private final ConcurrentHashMap<Key, Plan> plans = new ConcurrentHashMap<Key, Plan>();
    private final LongAdder compilations = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder payloadResolutions = new LongAdder();
    private final LongAdder payloadCacheHits = new LongAdder();
    private final LongAdder evaluations = new LongAdder();
    private final LongAdder sourceBytes = new LongAdder();
    private final RenderPayloadResolver resolver = new RenderPayloadResolver();

    /** Preloads all Render actions in the selected Template and its resolved Flow closure. */
    public void freeze(StageTemplate template, FlowRegistry flows) throws Exception {
        UnifiedTemplateEngine compiler = new UnifiedTemplateEngine(null);
        Set<String> visited = new LinkedHashSet<String>();
        freeze(template, flows, compiler, visited);
    }

    private void freeze(StageTemplate template, FlowRegistry flows, UnifiedTemplateEngine compiler,
                        Set<String> visited) throws Exception {
        if (template == null) return;
        for (TemplateAction action : template.actions()) {
            if ("render".equalsIgnoreCase(action.type())) get(template, action.payload(), compiler);
            else if ("flow".equalsIgnoreCase(action.type()) && flows != null && visited.add(action.use())) {
                FlowDefinition flow = flows.get(action.use());
                if (flow != null) {
                    StageTemplate flowTemplate = asTemplate(flow);
                    for (TemplateAction nested : flow.actions()) {
                        if ("render".equalsIgnoreCase(nested.type())) get(flowTemplate, nested.payload(), compiler);
                        else if ("flow".equalsIgnoreCase(nested.type())) {
                            // Resolve nested Flow Render plans using the nested definition's source directory.
                            freezeFlow(nested.use(), flows, compiler, visited);
                        }
                    }
                }
            }
        }
    }

    private void freezeFlow(String id, FlowRegistry flows, UnifiedTemplateEngine compiler, Set<String> visited) throws Exception {
        if (!visited.add(id)) return;
        FlowDefinition flow = flows.get(id);
        if (flow == null) return;
        for (TemplateAction action : flow.actions()) {
            if ("render".equalsIgnoreCase(action.type())) get(asTemplate(flow), action.payload(), compiler);
            else if ("flow".equalsIgnoreCase(action.type())) freezeFlow(action.use(), flows, compiler, visited);
        }
    }

    private StageTemplate asTemplate(FlowDefinition flow) {
        return new StageTemplate(flow.name(), flow.directory(), flow.actions(), flow.templateSchemaVersion(),
                flow.directory().resolve("flow.yaml"));
    }

    public Plan get(StageTemplate template, String pattern, UnifiedTemplateEngine compiler) throws Exception {
        Path root = template.directory().toRealPath();
        Key key = new Key(root, pattern);
        Plan existing = plans.get(key);
        if (existing != null) { cacheHits.increment(); payloadCacheHits.increment(); return existing; }
        synchronized (plans) {
            existing = plans.get(key);
            if (existing != null) { cacheHits.increment(); payloadCacheHits.increment(); return existing; }
            List<Path> paths = resolver.resolve(root, pattern);
            List<Source> sources = new ArrayList<Source>(paths.size());
            for (Path path : paths) {
                String content = PayloadCache.readUtf8(path);
                long bytes = content.getBytes(StandardCharsets.UTF_8).length;
                UnifiedTemplateEngine.CompiledTemplate compiled;
                try { compiled = compiler.compileTemplate(content); }
                catch (Exception error) {
                    throw att.config.YamlSupport.locateText(att.validation.DiagnosticException.wrap(
                            att.validation.DiagnosticCodes.TEMPLATE_INVALID, "Unable to compile Render payload", error,
                            null, null, "Check the payload expression syntax."), path, "payload");
                }
                sources.add(new Source(path, RenderPayloadResolver.portable(root.relativize(path)), content,
                        bytes, compiled));
                sourceBytes.add(bytes);
            }
            Plan created = new Plan(sources);
            plans.put(key, created);
            compilations.increment();
            payloadResolutions.increment();
            return created;
        }
    }

    public void recordEvaluation() { evaluations.increment(); }

    public Stats stats() {
        return new Stats(compilations.sum(), cacheHits.sum(), payloadResolutions.sum(), payloadCacheHits.sum(),
                evaluations.sum(), sourceBytes.sum(), 0L);
    }

    public static final class Stats {
        private final long plansCompiled, planCacheHits, payloadResolutions, payloadResolutionCacheHits;
        private final long evaluations, sourceBytes, artifactWrites;
        Stats(long plansCompiled, long planCacheHits, long payloadResolutions, long payloadResolutionCacheHits,
              long evaluations, long sourceBytes, long artifactWrites) {
            this.plansCompiled = plansCompiled; this.planCacheHits = planCacheHits;
            this.payloadResolutions = payloadResolutions; this.payloadResolutionCacheHits = payloadResolutionCacheHits;
            this.evaluations = evaluations; this.sourceBytes = sourceBytes; this.artifactWrites = artifactWrites;
        }
        public long plansCompiled() { return plansCompiled; }
        public long planCacheHits() { return planCacheHits; }
        public long payloadResolutions() { return payloadResolutions; }
        public long payloadResolutionCacheHits() { return payloadResolutionCacheHits; }
        public long evaluations() { return evaluations; }
        public long sourceBytes() { return sourceBytes; }
        public long artifactWrites() { return artifactWrites; }
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("renderPlansCompiled", plansCompiled); result.put("renderPlanCacheHits", planCacheHits);
            result.put("renderPayloadResolutions", payloadResolutions);
            result.put("renderPayloadResolutionCacheHits", payloadResolutionCacheHits);
            result.put("renderEvaluations", evaluations); result.put("renderArtifactWrites", artifactWrites);
            result.put("renderSourceBytes", sourceBytes);
            return Collections.unmodifiableMap(result);
        }
    }

    public static final class Plan {
        private final List<Source> sources;
        private Plan(List<Source> sources) { this.sources = Collections.unmodifiableList(new ArrayList<Source>(sources)); }
        public List<Source> sources() { return sources; }
    }

    public static final class Source {
        private final Path path; private final String relative, content; private final long bytes;
        private final UnifiedTemplateEngine.CompiledTemplate compiled;
        private Source(Path path, String relative, String content, long bytes, UnifiedTemplateEngine.CompiledTemplate compiled) {
            this.path = path; this.relative = relative; this.content = content; this.bytes = bytes; this.compiled = compiled;
        }
        public Path path() { return path; }
        public String relative() { return relative; }
        public String content() { return content; }
        public long bytes() { return bytes; }
        public UnifiedTemplateEngine.CompiledTemplate compiled() { return compiled; }
    }

    private static final class Key {
        private final Path root; private final String pattern;
        private Key(Path root, String pattern) { this.root = root; this.pattern = pattern; }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return root.equals(other.root) && java.util.Objects.equals(pattern, other.pattern);
        }
        @Override public int hashCode() { return 31 * root.hashCode() + (pattern == null ? 0 : pattern.hashCode()); }
    }
}
