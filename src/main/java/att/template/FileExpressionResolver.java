package att.template;

import att.core.CaseRuntimeContext;
import att.flow.FlowDefinition;
import att.flow.FlowRegistry;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resolves and evaluates the v1 one-file/one-String {@code &{...}} value
 * source.  File plans are immutable after compilation; only dynamic nodes are
 * evaluated for each call.
 */
public final class FileExpressionResolver {
    private final Path projectRoot;
    private final FileExpressionSnapshot snapshot;
    private final Map<PlanKey, CompiledFilePlan> plans = new LinkedHashMap<PlanKey, CompiledFilePlan>();
    private final AtomicLong compiled = new AtomicLong();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong staticHits = new AtomicLong();
    private final AtomicLong evaluations = new AtomicLong();
    private final AtomicLong sourceBytes = new AtomicLong();
    private final AtomicLong renderedChars = new AtomicLong();

    public FileExpressionResolver(Path projectRoot) {
        this(projectRoot, null);
    }

    public FileExpressionResolver(Path projectRoot, FileExpressionSnapshot snapshot) {
        if (projectRoot == null) throw new IllegalArgumentException("ATT package root is required for &{...}");
        try { this.projectRoot = projectRoot.toRealPath(); }
        catch (Exception error) { throw new IllegalArgumentException("ATT package root is unavailable: " + projectRoot, error); }
        this.snapshot = snapshot;
    }

    /** Runtime bridge used by immutable file plans. */
    public interface Runtime {
        Object context(String path, boolean optional) throws Exception;
        Object call(String name, Map<String, Object> arguments) throws Exception;
        String interpolate(String value) throws Exception;
        boolean hasContext(String path);
        String file(String authoredPath) throws Exception;
    }

    public String evaluate(String authoredPath, Path sourceDirectory, Runtime runtime) throws Exception {
        if (runtime == null) throw new IllegalArgumentException("File-expression runtime is required");
        CompiledFilePlan plan = compile(authoredPath, sourceDirectory);
        evaluations.incrementAndGet();
        if (plan.isStatic()) staticHits.incrementAndGet();
        String result = plan.evaluate(runtime);
        renderedChars.addAndGet(result.length());
        return result;
    }

    public CompiledFilePlan compile(String authoredPath, Path sourceDirectory) throws Exception {
        String path = validateAuthoredPath(authoredPath);
        Path base = sourceDirectory == null ? projectRoot : safeSourceDirectory(sourceDirectory);
        if (snapshot != null) {
            CompiledFilePlan frozen = snapshot.lookup(path, base);
            if (frozen != null) { hits.incrementAndGet(); return frozen; }
            throw new IllegalArgumentException("File content was not captured in the active Load snapshot: " + path);
        }

        ResolvedFile resolved = resolveFile(path, base);
        PlanKey key = PlanKey.of(resolved.canonical);
        synchronized (plans) {
            CompiledFilePlan cached = plans.get(key);
            if (cached != null) { hits.incrementAndGet(); return cached; }
            misses.incrementAndGet();
        }
        String source = readUtf8(resolved.canonical);
        CompiledFilePlan loaded = compilePlan(path, base, resolved.canonical, source);
        synchronized (plans) {
            // A writer may have won the race while this thread decoded the
            // source.  Reusing that plan keeps stats and identity deterministic.
            CompiledFilePlan existing = plans.get(key);
            if (existing != null) { hits.incrementAndGet(); return existing; }
            removeOlder(resolved.canonical);
            plans.put(key, loaded);
            compiled.incrementAndGet();
            sourceBytes.addAndGet(loaded.sourceBytes());
            return loaded;
        }
    }

    public Stats stats() {
        return new Stats(compiled.get(), hits.get(), misses.get(), staticHits.get(),
                evaluations.get(), sourceBytes.get(), renderedChars.get());
    }

    /** Validates a standalone {@code &{...}} locator without reading it. */
    public Path resolve(String authoredPath, Path sourceDirectory) throws Exception {
        String path = validateAuthoredPath(authoredPath);
        Path base = sourceDirectory == null ? projectRoot : safeSourceDirectory(sourceDirectory);
        return resolveFile(path, base).canonical;
    }

    /** Finds file locators in action text for dependency discovery. */
    public List<String> extractReferences(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<String>();
        int cursor = 0;
        while (cursor < text.length()) {
            int start = text.indexOf("&{", cursor);
            if (start < 0) break;
            int end = matchingBrace(text, start + 2);
            if (end < 0) throw new ExpressionSyntaxException(start, text.length(), "'}' to close file-content expression", "end of text");
            result.add(validateAuthoredPath(text.substring(start + 2, end)));
            cursor = end + 1;
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Captures all file plans in a selected Template/Flow dependency closure.
     * The returned snapshot is safe to share across concurrent Load iterations.
     */
    public FileExpressionSnapshot snapshotFor(StageTemplate template, FlowRegistry flows) throws Exception {
        Map<ReferenceKey, CompiledFilePlan> captured = new LinkedHashMap<ReferenceKey, CompiledFilePlan>();
        Deque<Reference> pending = new ArrayDeque<Reference>();
        Set<Path> visitedTemplates = new LinkedHashSet<Path>();
        enqueueActions(template, pending, visitedTemplates, flows);
        Set<ReferenceKey> visited = new LinkedHashSet<ReferenceKey>();
        while (!pending.isEmpty()) {
            Reference reference = pending.removeFirst();
            Path sourceDirectory = safeSourceDirectory(reference.sourceDirectory);
            ReferenceKey key = ReferenceKey.of(reference.authoredPath, sourceDirectory);
            if (!visited.add(key)) continue;
            CompiledFilePlan plan = compile(reference.authoredPath, sourceDirectory);
            captured.put(key, plan);
            for (String nested : plan.filePaths()) {
                pending.addLast(new Reference(nested, plan.file().getParent()));
            }
        }
        return new FileExpressionSnapshot(projectRoot, captured);
    }

    private void enqueueActions(StageTemplate template, Deque<Reference> pending,
                                Set<Path> visitedTemplates, FlowRegistry flows) throws Exception {
        if (template == null || !visitedTemplates.add(template.directory().toAbsolutePath().normalize())) return;
        for (TemplateAction action : template.actions()) {
            collectStrings(action.raw(), template.directory(), pending);
            if ("flow".equalsIgnoreCase(action.type()) && flows != null) {
                FlowDefinition flow = flows.get(action.use());
                if (flow != null) {
                    enqueueActions(new StageTemplate(flow.name(), flow.directory(), flow.actions(),
                            flow.templateSchemaVersion(), flow.directory().resolve("flow.yaml")), pending,
                            visitedTemplates, flows);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void collectStrings(Object value, Path sourceDirectory, Deque<Reference> pending) {
        if (value instanceof String) {
            for (String path : extractReferences((String) value)) pending.addLast(new Reference(path, sourceDirectory));
        } else if (value instanceof Map) {
            for (Object child : ((Map<?, ?>) value).values()) collectStrings(child, sourceDirectory, pending);
        } else if (value instanceof Iterable) {
            for (Object child : (Iterable<?>) value) collectStrings(child, sourceDirectory, pending);
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) collectStrings(java.lang.reflect.Array.get(value, index), sourceDirectory, pending);
        }
    }

    private CompiledFilePlan compilePlan(String authoredPath, Path sourceDirectory, Path canonical, String source) {
        if (source.contains("&{"))
            throw new IllegalArgumentException("Nested file-content expressions are not supported in v1: " + canonical);
        List<Segment> segments = new ArrayList<Segment>();
        int cursor = 0;
        while (cursor < source.length()) {
            int context = source.indexOf("${", cursor);
            int call = source.indexOf("#{", cursor);
            int start = first(context, call);
            if (start < 0) { segments.add(new LiteralSegment(source.substring(cursor))); break; }
            if (start > cursor) segments.add(new LiteralSegment(source.substring(cursor, start)));
            if (start == context) {
                int end = matchingBrace(source, start + 2);
                if (end < 0) throw new ExpressionSyntaxException(start, source.length(), "'}' to close Context expression", "end of text");
                String raw = source.substring(start + 2, end);
                if (raw.trim().isEmpty() || !raw.equals(raw.trim()))
                    throw new ExpressionSyntaxException(start, end + 1, "a non-blank Context path", "invalid Context path");
                boolean optional = raw.endsWith("?");
                String path = optional ? raw.substring(0, raw.length() - 1) : raw;
                CaseRuntimeContext.validateReferencePath(path);
                segments.add(new ContextSegment(path, optional));
                cursor = end + 1;
            } else {
                int end = matchingBrace(source, start + 2);
                if (end < 0) throw new ExpressionSyntaxException(start, source.length(), "'}' to close expression block", "end of text");
                String expression = source.substring(start, end + 1);
                segments.add(new CallSegment(new ExpressionBlockEvaluator().compile(expression)));
                cursor = end + 1;
            }
        }
        if (source.isEmpty()) segments.add(new LiteralSegment(""));
        boolean dynamic = false;
        for (Segment segment : segments) {
            if (!(segment instanceof LiteralSegment)) dynamic = true;
        }
        return new CompiledFilePlan(authoredPath, sourceDirectory, canonical, source,
                segments, !dynamic, Collections.<String>emptyList());
    }

    private ResolvedFile resolveFile(String authoredPath, Path sourceDirectory) throws Exception {
        Path root = projectRoot;
        Path rootCandidate = root.resolve(authoredPath.replace('/', java.io.File.separatorChar)).normalize();
        Path sourceCandidate = sourceDirectory.resolve(authoredPath.replace('/', java.io.File.separatorChar)).normalize();
        boolean sourcePreferred = authoredPath.startsWith("./") || authoredPath.startsWith("../")
                || authoredPath.equals(".") || authoredPath.equals("..");
        Path candidate = sourcePreferred ? sourceCandidate
                : (Files.exists(rootCandidate, LinkOption.NOFOLLOW_LINKS) ? rootCandidate : sourceCandidate);
        if (!candidate.startsWith(root)) throw unsafe(authoredPath, candidate, root);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("File-content target does not exist: " + authoredPath + " (resolved=" + candidate + ")");
        Path canonical = candidate.toRealPath();
        if (!canonical.startsWith(root)) throw unsafe(authoredPath, canonical, root);
        if (!Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("File-content target is not a regular file: " + authoredPath + " (resolved=" + canonical + ")");
        return new ResolvedFile(canonical);
    }

    private Path safeSourceDirectory(Path sourceDirectory) throws Exception {
        Path normalized;
        try { normalized = sourceDirectory.toRealPath().normalize(); }
        catch (java.io.IOException error) { throw new IllegalArgumentException("Project source directory is unavailable: " + sourceDirectory, error); }
        if (!normalized.startsWith(projectRoot)) throw unsafe(sourceDirectory.toString(), normalized, projectRoot);
        return normalized;
    }

    private String validateAuthoredPath(String authoredPath) {
        if (authoredPath == null || authoredPath.trim().isEmpty() || !authoredPath.equals(authoredPath.trim()))
            throw new IllegalArgumentException("File locator must be non-blank and must not have surrounding whitespace: " + authoredPath);
        Path path;
        try { path = Paths.get(authoredPath.replace('/', java.io.File.separatorChar)); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Invalid file locator: " + authoredPath, error); }
        if (path.isAbsolute() || authoredPath.matches("^[A-Za-z]:.*") || authoredPath.startsWith("\\\\") || authoredPath.startsWith("\\"))
            throw new IllegalArgumentException("File locator must be package-relative: " + authoredPath);
        for (char token : new char[]{'*', '?', '[', ']', '{', '}'}) {
            if (authoredPath.indexOf(token) >= 0)
                throw new IllegalArgumentException("Glob syntax is not supported in &{...}: " + authoredPath);
        }
        if (authoredPath.contains("${") || authoredPath.contains("#{") || authoredPath.contains("&{"))
            throw new IllegalArgumentException("File-content expression locator must be static: " + authoredPath);
        return authoredPath;
    }

    private IllegalArgumentException unsafe(String authored, Path resolved, Path root) {
        return new IllegalArgumentException("File-content target escapes META.PACKAGE_ROOT: authoredPath=" + authored
                + ", resolvedPath=" + resolved + ", projectRoot=" + root);
    }

    private String readUtf8(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("File-content target is not valid UTF-8: " + file, error);
        }
    }

    private void removeOlder(Path canonical) {
        java.util.Iterator<PlanKey> keys = plans.keySet().iterator();
        while (keys.hasNext()) if (keys.next().path.equals(canonical)) keys.remove();
    }

    private int first(int... values) {
        int result = -1;
        for (int value : values) if (value >= 0 && (result < 0 || value < result)) result = value;
        return result;
    }

    private static int matchingBrace(String text, int bodyStart) {
        int depth = 1;
        char quote = 0;
        for (int index = bodyStart; index < text.length(); index++) {
            char value = text.charAt(index);
            if (quote != 0) {
                if (value == quote && (index == 0 || text.charAt(index - 1) != '\\')) quote = 0;
                continue;
            }
            if (value == '\'' || value == '"') { quote = value; continue; }
            if (value == '{') depth++;
            else if (value == '}' && --depth == 0) return index;
        }
        return -1;
    }

    private interface Segment { Object evaluate(Runtime runtime) throws Exception; }
    private static final class LiteralSegment implements Segment {
        private final String value; private LiteralSegment(String value) { this.value = value; }
        @Override public Object evaluate(Runtime runtime) { return value; }
    }
    private static final class ContextSegment implements Segment {
        private final String path; private final boolean optional;
        private ContextSegment(String path, boolean optional) { this.path = path; this.optional = optional; }
        @Override public Object evaluate(Runtime runtime) throws Exception { return runtime.context(path, optional); }
    }
    private static final class CallSegment implements Segment {
        private final ExpressionBlockEvaluator.CompiledExpression expression;
        private CallSegment(ExpressionBlockEvaluator.CompiledExpression expression) { this.expression = expression; }
        @Override public Object evaluate(final Runtime runtime) throws Exception {
            return expression.evaluate(new ExpressionBlockEvaluator.Resolver() {
                @Override public Object context(String path) throws Exception { return runtime.context(path, false); }
                @Override public Object contextOptional(String path) throws Exception { return runtime.context(path, true); }
                @Override public Object call(String name, Map<String, Object> arguments) throws Exception { return runtime.call(name, arguments); }
                @Override public String interpolate(String value) throws Exception { return runtime.interpolate(value); }
                @Override public boolean hasContext(String path) { return runtime.hasContext(path); }
                @Override public String file(String path) throws Exception { return runtime.file(path); }
            });
        }
    }
    public static final class CompiledFilePlan {
        private final String authoredPath;
        private final Path sourceDirectory;
        private final Path file;
        private final String source;
        private final List<Segment> segments;
        private final boolean staticConstant;
        private final List<String> filePaths;
        private CompiledFilePlan(String authoredPath, Path sourceDirectory, Path file, String source,
                                 List<Segment> segments, boolean staticConstant, List<String> filePaths) {
            this.authoredPath = authoredPath;
            this.sourceDirectory = sourceDirectory;
            this.file = file;
            this.source = source;
            this.segments = Collections.unmodifiableList(new ArrayList<Segment>(segments));
            this.staticConstant = staticConstant;
            this.filePaths = Collections.unmodifiableList(new ArrayList<String>(filePaths));
        }
        public String authoredPath() { return authoredPath; }
        public Path sourceDirectory() { return sourceDirectory; }
        public Path file() { return file; }
        /** Authored source for normal Context scope/order and call-contract validation. */
        public String source() { return source; }
        public long sourceBytes() { return source.getBytes(StandardCharsets.UTF_8).length; }
        public boolean isStatic() { return staticConstant; }
        public List<String> filePaths() { return filePaths; }
        public String evaluate(Runtime runtime) throws Exception {
            if (staticConstant) return source;
            StringBuilder result = new StringBuilder(source.length());
            for (Segment segment : segments) {
                Object value = segment.evaluate(runtime);
                if (value instanceof Map) throw new IllegalArgumentException("A typed Map cannot be interpolated into a file-content String");
                result.append(value == null ? "" : String.valueOf(value));
            }
            return result.toString();
        }
    }

    public static final class FileExpressionSnapshot {
        private final Path projectRoot;
        private final Map<ReferenceKey, CompiledFilePlan> plans;
        private FileExpressionSnapshot(Path projectRoot, Map<ReferenceKey, CompiledFilePlan> plans) {
            this.projectRoot = projectRoot;
            this.plans = Collections.unmodifiableMap(new LinkedHashMap<ReferenceKey, CompiledFilePlan>(plans));
        }
        public Path projectRoot() { return projectRoot; }
        public int size() { return plans.size(); }
        private CompiledFilePlan lookup(String authoredPath, Path sourceDirectory) {
            return plans.get(ReferenceKey.of(authoredPath, sourceDirectory));
        }
    }

    public static final class Stats {
        private final long compiled, hits, misses, staticHits, evaluations, sourceBytes, renderedChars;
        public Stats(long compiled, long hits, long misses, long staticHits, long evaluations, long sourceBytes, long renderedChars) {
            this.compiled = compiled; this.hits = hits; this.misses = misses; this.staticHits = staticHits;
            this.evaluations = evaluations; this.sourceBytes = sourceBytes; this.renderedChars = renderedChars;
        }
        public long compiled() { return compiled; }
        public long hits() { return hits; }
        public long misses() { return misses; }
        public long staticHits() { return staticHits; }
        public long evaluations() { return evaluations; }
        public long sourceBytes() { return sourceBytes; }
        public long renderedChars() { return renderedChars; }
    }

    private static final class ResolvedFile { private final Path canonical; private ResolvedFile(Path canonical) { this.canonical = canonical; } }
    private static final class Reference {
        private final String authoredPath; private final Path sourceDirectory;
        private Reference(String authoredPath, Path sourceDirectory) { this.authoredPath = authoredPath; this.sourceDirectory = sourceDirectory; }
    }
    private static final class ReferenceKey {
        private final String authoredPath; private final Path sourceDirectory;
        private ReferenceKey(String authoredPath, Path sourceDirectory) { this.authoredPath = authoredPath; this.sourceDirectory = sourceDirectory.toAbsolutePath().normalize(); }
        private static ReferenceKey of(String authoredPath, Path sourceDirectory) { return new ReferenceKey(authoredPath, sourceDirectory); }
        @Override public boolean equals(Object value) { if (!(value instanceof ReferenceKey)) return false; ReferenceKey other = (ReferenceKey) value; return authoredPath.equals(other.authoredPath) && sourceDirectory.equals(other.sourceDirectory); }
        @Override public int hashCode() { return 31 * authoredPath.hashCode() + sourceDirectory.hashCode(); }
    }
    private static final class PlanKey {
        private final Path path; private final long size; private final long modified;
        private PlanKey(Path path, long size, long modified) { this.path = path; this.size = size; this.modified = modified; }
        private static PlanKey of(Path path) throws Exception { return new PlanKey(path, Files.size(path), Files.getLastModifiedTime(path).toMillis()); }
        @Override public boolean equals(Object value) { if (!(value instanceof PlanKey)) return false; PlanKey other = (PlanKey) value; return size == other.size && modified == other.modified && path.equals(other.path); }
        @Override public int hashCode() { int result = path.hashCode(); result = 31 * result + Long.valueOf(size).hashCode(); return 31 * result + Long.valueOf(modified).hashCode(); }
    }
}
