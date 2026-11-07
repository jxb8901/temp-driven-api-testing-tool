package att.template;

import att.core.CaseRuntimeContext;
import att.flow.FlowDefinition;
import att.flow.FlowRegistry;
import att.resource.PackageResourceResolver;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resolves and evaluates the v1 one-file/one-String {@code &{...}} value
 * source.  File plans are immutable after compilation; only dynamic nodes are
 * evaluated for each call.
 */
public final class FileExpressionResolver {
    private static final int MAX_PORTABLE_SNAPSHOT_BYTES = 128 * 1024 * 1024;
    private static final Map<Path, FileExpressionSnapshot> APPROVED_SNAPSHOTS = new ConcurrentHashMap<Path, FileExpressionSnapshot>();
    private final Path projectRoot;
    private final PackageResourceResolver packageResources;
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
        catch (Exception error) { throw new IllegalArgumentException("ATT package root is unavailable", error); }
        this.packageResources = new PackageResourceResolver(this.projectRoot);
        this.snapshot = snapshot == null ? APPROVED_SNAPSHOTS.get(this.projectRoot) : snapshot;
    }

    /** Installs a Server-approved immutable plan snapshot for this private Worker Package root. */
    public static AutoCloseable registerApprovedSnapshot(Path packageRoot, FileExpressionSnapshot snapshot) throws Exception {
        if (packageRoot == null || snapshot == null) throw new IllegalArgumentException("Approved File-expression snapshot is required");
        Path root = packageRoot.toRealPath().normalize();
        if (!root.equals(snapshot.projectRoot().toRealPath().normalize()))
            throw new IllegalArgumentException("Approved File-expression snapshot belongs to another Package root");
        FileExpressionSnapshot existing = APPROVED_SNAPSHOTS.putIfAbsent(root, snapshot);
        if (existing != null && existing != snapshot) throw new IllegalStateException("An approved File-expression snapshot is already registered for this Package root");
        return () -> APPROVED_SNAPSHOTS.remove(root, snapshot);
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
        String source = readUtf8(resolved.canonical, path);
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
        return captureSnapshot(template, flows, null);
    }

    /** Captures a synthetic direct-Flow Load wrapper and the actual Flow as distinct sources. */
    public FileExpressionSnapshot snapshotForLoadFlow(StageTemplate wrapper, FlowRegistry flows, String flowId) throws Exception {
        if (flowId == null || flowId.trim().isEmpty()) throw new IllegalArgumentException("Load Flow id is required");
        return captureSnapshot(wrapper, flows, "synthetic-load-flow:" + flowId);
    }

    private FileExpressionSnapshot captureSnapshot(StageTemplate template, FlowRegistry flows,
                                                    String rootSourceOverride) throws Exception {
        Map<ReferenceKey, CompiledFilePlan> captured = new LinkedHashMap<ReferenceKey, CompiledFilePlan>();
        Deque<Reference> pending = new ArrayDeque<Reference>();
        Set<String> visitedTemplates = new LinkedHashSet<String>();
        enqueueActions(template, pending, visitedTemplates, flows, rootSourceOverride);
        Set<ReferenceKey> visited = new LinkedHashSet<ReferenceKey>();
        while (!pending.isEmpty()) {
            Reference reference = pending.removeFirst();
            Path sourceDirectory = safeSourceDirectory(reference.sourceDirectory);
            ReferenceKey key = ReferenceKey.of(reference.authoredPath, sourceDirectory);
            if (!visited.add(key)) continue;
            CompiledFilePlan plan = compile(reference.authoredPath, sourceDirectory);
            captured.put(key, plan);
            for (String nested : plan.filePaths())
                pending.addLast(new Reference(nested, plan.file().getParent()));
        }
        return new FileExpressionSnapshot(projectRoot, captured);
    }

    private void enqueueActions(StageTemplate template, Deque<Reference> pending,
                                Set<String> visitedTemplates, FlowRegistry flows, String sourceOverride) throws Exception {
        if (template == null || !visitedTemplates.add(sourceOverride == null ? sourceIdentity(template) : sourceOverride)) return;
        for (TemplateAction action : template.actions()) {
            collectStrings(action.raw(), template.directory(), pending);
            if ("flow".equalsIgnoreCase(action.type()) && flows != null) {
                FlowDefinition flow = flows.get(action.use());
                if (flow != null) {
                    enqueueActions(new StageTemplate(flow.name(), flow.directory(), flow.actions(),
                            flow.templateSchemaVersion(), flow.directory().resolve("flow.yaml")), pending,
                            visitedTemplates, flows, null);
                }
            }
        }
    }

    private String sourceIdentity(StageTemplate template) throws Exception {
        Path source = template.sourceFile();
        if (source != null) {
            Path canonical = source.toAbsolutePath().normalize();
            if (Files.exists(canonical)) canonical = canonical.toRealPath();
            return "descriptor:" + canonical;
        }
        return "synthetic-object:" + System.identityHashCode(template);
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
            throw new IllegalArgumentException("Nested file-content expressions are not supported in v1: " + authoredPath);
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
        PackageResourceResolver.PackageResource resource = packageResources.resolve(
                new PackageResourceResolver.ResourceLocator(authoredPath), packageResources.origin(sourceDirectory),
                PackageResourceResolver.Kind.FILE);
        return new ResolvedFile(resource.canonicalPath());
    }

    private Path safeSourceDirectory(Path sourceDirectory) throws Exception {
        Path normalized;
        try { normalized = sourceDirectory.toRealPath().normalize(); }
        catch (java.io.IOException error) { throw new IllegalArgumentException("Project source directory is unavailable: " + sourceDirectory, error); }
        if (!normalized.startsWith(projectRoot)) throw unsafe("<descriptor-origin>", normalized, projectRoot);
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
        return new IllegalArgumentException("Package resource escapes the package root: " + authored);
    }

    private String readUtf8(Path file, String logicalName) throws Exception {
        byte[] bytes;
        try { bytes = Files.readAllBytes(file); }
        catch (java.io.IOException error) {
            throw new IllegalArgumentException("Unable to read package resource: " + logicalName, error);
        }
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("File-content target is not valid UTF-8: " + logicalName, error);
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

    private interface Segment extends java.io.Serializable { Object evaluate(Runtime runtime) throws Exception; }
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
        /** Returns value-free compiled dependency metadata without exposing authored file contents. */
        public List<CompiledFileDependency> dependencies() throws Exception {
            List<CompiledFileDependency> result = new ArrayList<CompiledFileDependency>();
            Path root = projectRoot.toRealPath();
            for (CompiledFilePlan plan : plans.values()) {
                Path file = plan.file().toRealPath();
                Path sourceDirectory = plan.sourceDirectory().toRealPath();
                if (!file.startsWith(root) || !sourceDirectory.startsWith(root))
                    throw new IllegalArgumentException("Compiled File-expression dependency escapes its Package");
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(plan.source().getBytes(StandardCharsets.UTF_8));
                result.add(new CompiledFileDependency(plan.authoredPath(),
                        root.relativize(sourceDirectory).toString().replace('\\', '/'),
                        root.relativize(file).toString().replace('\\', '/'), hex(hash)));
            }
            return Collections.unmodifiableList(result);
        }
        private CompiledFilePlan lookup(String authoredPath, Path sourceDirectory) {
            return plans.get(ReferenceKey.of(authoredPath, sourceDirectory));
        }

        public static FileExpressionSnapshot empty(Path projectRoot) throws Exception {
            if (projectRoot == null) throw new IllegalArgumentException("Package root is required");
            return new FileExpressionSnapshot(projectRoot.toRealPath(), Collections.<ReferenceKey, CompiledFilePlan>emptyMap());
        }

        /** Combines immutable plans while retaining each canonical action source directory. */
        public static FileExpressionSnapshot merge(Path projectRoot, Iterable<FileExpressionSnapshot> snapshots) throws Exception {
            if (projectRoot == null) throw new IllegalArgumentException("Package root is required");
            Path root = projectRoot.toRealPath().normalize();
            Map<ReferenceKey, CompiledFilePlan> merged = new LinkedHashMap<ReferenceKey, CompiledFilePlan>();
            if (snapshots != null) for (FileExpressionSnapshot snapshot : snapshots) {
                if (snapshot == null) continue;
                if (!root.equals(snapshot.projectRoot.toRealPath().normalize()))
                    throw new IllegalArgumentException("Compiled File-expression snapshots belong to different Packages");
                for (Map.Entry<ReferenceKey, CompiledFilePlan> entry : snapshot.plans.entrySet()) {
                    CompiledFilePlan previous = merged.putIfAbsent(entry.getKey(), entry.getValue());
                    if (previous != null && (!previous.source().equals(entry.getValue().source())
                            || !previous.file().equals(entry.getValue().file())))
                        throw new IllegalArgumentException("Compiled File-expression plan identity is ambiguous");
                }
            }
            return new FileExpressionSnapshot(root, merged);
        }

        /** Encodes immutable plan nodes for a private, integrity-checked Worker snapshot archive. */
        public byte[] serialize() throws Exception {
            Path root = projectRoot.toRealPath().normalize();
            List<PortablePlan> portable = new ArrayList<PortablePlan>();
            for (CompiledFilePlan plan : plans.values()) {
                Path sourceDirectory = plan.sourceDirectory().toRealPath().normalize();
                Path file = plan.file().toRealPath().normalize();
                if (!sourceDirectory.startsWith(root) || !file.startsWith(root))
                    throw new IllegalArgumentException("Compiled File-expression plan escapes its Package");
                portable.add(new PortablePlan(plan.authoredPath(), relative(root, sourceDirectory),
                        relative(root, file), plan.source(), new ArrayList<Segment>(plan.segments),
                        plan.staticConstant, new ArrayList<String>(plan.filePaths())));
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(portable); }
            if (bytes.size() > MAX_PORTABLE_SNAPSHOT_BYTES)
                throw new IOException("Compiled File-expression plans exceed the source snapshot limit");
            return bytes.toByteArray();
        }

        /** Restores compiled plan nodes against a separately verified, extracted Package tree. */
        public static FileExpressionSnapshot restore(Path packageRoot, byte[] bytes) throws Exception {
            if (packageRoot == null || bytes == null || bytes.length == 0 || bytes.length > MAX_PORTABLE_SNAPSHOT_BYTES)
                throw new IOException("Compiled File-expression snapshot is unavailable or oversized");
            Path root = packageRoot.toRealPath().normalize();
            Object decoded;
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes)) {
                @Override protected Class<?> resolveClass(ObjectStreamClass descriptor) throws IOException, ClassNotFoundException {
                    String name = descriptor.getName();
                    if (!portableClass(name)) throw new InvalidClassException("Unsupported compiled plan class", name);
                    return super.resolveClass(descriptor);
                }
            }) { decoded = input.readObject(); }
            if (!(decoded instanceof List<?>)) throw new IOException("Compiled File-expression snapshot has an invalid format");
            FileExpressionResolver validator = new FileExpressionResolver(root);
            Map<ReferenceKey, CompiledFilePlan> restored = new LinkedHashMap<ReferenceKey, CompiledFilePlan>();
            for (Object value : (List<?>) decoded) {
                if (!(value instanceof PortablePlan)) throw new IOException("Compiled File-expression snapshot has an invalid plan");
                PortablePlan plan = (PortablePlan) value;
                String authored = validator.validateAuthoredPath(plan.authoredPath);
                Path sourceDirectory = root.resolve(resolvePortablePath(plan.sourceDirectory, true)).normalize();
                Path file = root.resolve(resolvePortablePath(plan.file, false)).normalize();
                if (!sourceDirectory.startsWith(root) || !file.startsWith(root)
                        || !Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Compiled File-expression plan source is unavailable");
                sourceDirectory = sourceDirectory.toRealPath().normalize();
                file = file.toRealPath().normalize();
                if (!sourceDirectory.startsWith(root) || !file.startsWith(root)
                        || plan.source == null || !validator.readUtf8(file, authored).equals(plan.source))
                    throw new IOException("Compiled File-expression plan does not match the accepted source bytes");
                if (plan.segments == null || plan.filePaths == null)
                    throw new IOException("Compiled File-expression plan is incomplete");
                CompiledFilePlan compiled = new CompiledFilePlan(authored, sourceDirectory, file,
                        plan.source, plan.segments, plan.staticConstant, plan.filePaths);
                if (restored.put(ReferenceKey.of(authored, sourceDirectory), compiled) != null)
                    throw new IOException("Compiled File-expression snapshot contains a duplicate plan");
            }
            return new FileExpressionSnapshot(root, restored);
        }

        private static String relative(Path root, Path path) {
            return root.relativize(path).toString().replace('\\', '/');
        }

        private static Path resolvePortablePath(String value, boolean allowEmpty) throws IOException {
            if (value == null || (!allowEmpty && value.isEmpty()) || value.startsWith("/")
                    || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0 || value.indexOf('\0') >= 0)
                throw new IOException("Compiled File-expression snapshot contains an invalid path");
            if (value.isEmpty()) return Paths.get("");
            Path relative;
            try { relative = Paths.get(value).normalize(); }
            catch (RuntimeException invalid) { throw new IOException("Compiled File-expression snapshot contains an invalid path", invalid); }
            if (relative.isAbsolute() || relative.startsWith("..")
                    || !relative.toString().replace('\\', '/').equals(value))
                throw new IOException("Compiled File-expression snapshot contains an unsafe path");
            return relative;
        }

        private static boolean portableClass(String name) {
            return name.equals(PortablePlan.class.getName())
                    || name.startsWith("att.template.FileExpressionResolver$")
                    || name.startsWith("att.template.ExpressionBlockEvaluator$")
                    || name.equals("java.lang.String") || name.equals("java.lang.Boolean")
                    || name.equals("java.lang.Integer") || name.equals("java.lang.Long")
                    || name.equals("java.lang.Number") || name.equals("java.util.ArrayList")
                    || name.equals("java.util.LinkedHashMap") || name.equals("java.util.HashMap")
                    || name.equals("java.math.BigDecimal") || name.equals("java.math.BigInteger")
                    || name.startsWith("[Latt.template.ExpressionBlockEvaluator$")
                    || name.equals("[Ljava.lang.Object;");
        }
    }

    private static final class PortablePlan implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private final String authoredPath;
        private final String sourceDirectory;
        private final String file;
        private final String source;
        private final List<Segment> segments;
        private final boolean staticConstant;
        private final List<String> filePaths;
        private PortablePlan(String authoredPath, String sourceDirectory, String file, String source,
                             List<Segment> segments, boolean staticConstant, List<String> filePaths) {
            this.authoredPath = authoredPath; this.sourceDirectory = sourceDirectory; this.file = file;
            this.source = source; this.segments = segments; this.staticConstant = staticConstant; this.filePaths = filePaths;
        }
    }

    /** Value-free closure entry for dependency and freshness projections. */
    public static final class CompiledFileDependency {
        private final String authoredPath, sourceDirectory, logicalPath, sourceFingerprint;
        private CompiledFileDependency(String authoredPath, String sourceDirectory,
                                       String logicalPath, String sourceFingerprint) {
            this.authoredPath = authoredPath; this.sourceDirectory = sourceDirectory;
            this.logicalPath = logicalPath; this.sourceFingerprint = sourceFingerprint;
        }
        public String authoredPath() { return authoredPath; }
        public String sourceDirectory() { return sourceDirectory; }
        public String logicalPath() { return logicalPath; }
        public String sourceFingerprint() { return sourceFingerprint; }
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

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 255));
        return result.toString();
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
