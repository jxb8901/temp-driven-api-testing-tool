package att.testdata;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import att.resource.PackageResourceResolver;

/** Immutable environment base plus an optional execution-local whole-descriptor overlay. */
public final class TestdataRegistry {
    private final Path projectRoot;
    private final Path authoredRoot;
    private final PackageResourceResolver packageResources;
    private final List<Path> environmentFiles;
    private final List<Path> loadFiles;
    private final String localLayerName;
    private final TestdataDescriptorLoader loader;
    private final Map<Path, String> idsByPath = new ConcurrentHashMap<Path, String>();
    private final Map<Path, TestdataDescriptor> descriptorsByPath = new ConcurrentHashMap<Path, TestdataDescriptor>();
    private volatile Map<String, List<Path>> environmentIndex;
    private volatile Map<String, List<Path>> loadIndex;
    private volatile List<String> inactiveBaseFailures = Collections.emptyList();

    public TestdataRegistry(Path projectRoot, List<Path> environmentFiles, List<Path> loadFiles) {
        this(projectRoot, environmentFiles, loadFiles, "load-local");
    }

    public TestdataRegistry(Path projectRoot, List<Path> environmentFiles, List<Path> loadFiles,
                            String localLayerName) {
        this.authoredRoot = projectRoot.toAbsolutePath().normalize();
        this.packageResources = new PackageResourceResolver(projectRoot);
        this.projectRoot = packageResources.packageRoot();
        this.environmentFiles = immutablePaths(environmentFiles);
        this.loadFiles = immutablePaths(loadFiles);
        if (!"load-local".equals(localLayerName) && !"debug-local".equals(localLayerName))
            throw new IllegalArgumentException("Unsupported local testdata layer: " + localLayerName);
        this.localLayerName = localLayerName;
        this.loader = new TestdataDescriptorLoader(this.projectRoot);
    }

    public String localLayerName() { return localLayerName; }

    public TestdataDescriptor resolve(String id) throws Exception {
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]*")) throw new IllegalArgumentException("Invalid Testdata descriptor ID");
        Path local = indexed(loadIndex(), id, displayLayerName());
        Path environment = indexed(environmentIndex(), id, "Environment");
        Path file = local == null ? environment : local;
        if (file == null) throw new IllegalArgumentException("Testdata id is not configured in the effective registry: " + id
                + (inactiveBaseFailures.isEmpty() ? "" : "; Manifest/env declarations: " + inactiveBaseFailures));
        TestdataDescriptor descriptor = descriptorsByPath.get(file);
        if (descriptor == null) {
            try { descriptor = loader.load(file); }
            catch (Exception error) { throw unavailable(file, local == null ? "Manifest/env" : localLayerName, id); }
            TestdataDescriptor previous = descriptorsByPath.putIfAbsent(file, descriptor);
            if (previous != null) descriptor = previous;
        }
        if (!id.equals(descriptor.id())) throw new IllegalArgumentException("Testdata descriptor id changed while resolving: " + id);
        return descriptor;
    }

    public String layer(String id) throws Exception {
        Path local = indexed(loadIndex(), id, displayLayerName());
        Path environment = indexed(environmentIndex(), id, "Environment");
        if (local != null) return localLayerName;
        if (environment != null) return "environment";
        throw new IllegalArgumentException("Testdata id is not configured in the effective registry: " + id);
    }

    /** Full schema and duplicate-ID validation used by explicit package/Load validation. */
    public Map<String, TestdataDescriptor> validateAll() throws Exception {
        Map<String, TestdataDescriptor> environment = validateLayer(environmentFiles, "Environment");
        Map<String, TestdataDescriptor> local = validateLayer(loadFiles, displayLayerName());
        Map<String, TestdataDescriptor> effective = new LinkedHashMap<String, TestdataDescriptor>(environment);
        effective.putAll(local);
        return Collections.unmodifiableMap(effective);
    }

    /** Explicit imports are strict even when the invocation does not reference their IDs. */
    public void validateInvocation() throws Exception { validateLayer(loadFiles, displayLayerName()); }

    public List<Path> environmentFiles() { return environmentFiles; }
    public List<Path> loadFiles() { return loadFiles; }

    private Map<String, TestdataDescriptor> validateLayer(List<Path> files, String layer) throws Exception {
        Map<String, TestdataDescriptor> result = new LinkedHashMap<String, TestdataDescriptor>();
        Set<Path> uniqueFiles = new LinkedHashSet<Path>();
        for (Path path : files) {
            Path canonical;
            TestdataDescriptor descriptor;
            try { canonical = canonicalImport(path); descriptor = load(canonical); }
            catch (Exception error) { throw unavailable(path, "Environment".equals(layer) ? "Manifest/env" : layer, idsByPath.get(path)); }
            if (!uniqueFiles.add(canonical)) throw new IllegalArgumentException("Duplicate testdata path in " + layer + " layer: " + logicalPath(path));
            if (result.putIfAbsent(descriptor.id(), descriptor) != null)
                throw new IllegalArgumentException("Duplicate testdata id in " + layer + " layer: " + descriptor.id() + " declaration=" + logicalPath(path));
        }
        return result;
    }

    private TestdataDescriptor load(Path path) throws Exception {
        Path canonical = path.toRealPath();
        TestdataDescriptor descriptor = descriptorsByPath.get(canonical);
        if (descriptor == null) {
            descriptor = loader.load(canonical);
            TestdataDescriptor previous = descriptorsByPath.putIfAbsent(canonical, descriptor);
            if (previous != null) descriptor = previous;
        }
        return descriptor;
    }

    private Map<String, List<Path>> environmentIndex() throws Exception {
        Map<String, List<Path>> result = environmentIndex;
        if (result == null) synchronized (this) {
            result = environmentIndex;
            if (result == null) environmentIndex = result = index(environmentFiles, "Environment", true);
        }
        return result;
    }

    private Map<String, List<Path>> loadIndex() throws Exception {
        Map<String, List<Path>> result = loadIndex;
        if (result == null) synchronized (this) {
            result = loadIndex;
            if (result == null) loadIndex = result = index(loadFiles, displayLayerName(), false);
        }
        return result;
    }

    private Map<String, List<Path>> index(List<Path> files, String layer, boolean lazy) throws Exception {
        Map<String, List<Path>> result = new LinkedHashMap<String, List<Path>>();
        List<String> failures = new ArrayList<String>();
        for (Path path : files) {
            Path canonical;
            String id;
            try {
                canonical = canonicalImport(path);
                id = idsByPath.get(canonical);
                if (id == null) {
                    id = loader.readId(canonical);
                    idsByPath.put(canonical, id);
                }
            } catch (Exception error) {
                if (lazy) {
                    if (failures.size() < 3) failures.add(unavailable(path, "Manifest/env", null).getMessage());
                    continue; // Full Package validation reports inactive malformed/missing base entries.
                }
                throw unavailable(path, layer, null);
            }
            List<Path> paths = result.get(id);
            if (paths == null) { paths = new ArrayList<Path>(); result.put(id, paths); }
            paths.add(canonical);
        }
        if (lazy) inactiveBaseFailures = Collections.unmodifiableList(failures);
        Map<String, List<Path>> immutable = new LinkedHashMap<String, List<Path>>();
        for (Map.Entry<String, List<Path>> entry : result.entrySet())
            immutable.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<Path>(entry.getValue())));
        return Collections.unmodifiableMap(immutable);
    }

    private static Path indexed(Map<String, List<Path>> index, String id, String layer) {
        List<Path> paths = index.get(id);
        if (paths == null || paths.isEmpty()) return null;
        if (paths.size() > 1) throw new IllegalArgumentException("Duplicate testdata id in " + layer + " layer: " + id);
        return paths.get(0);
    }

    private String displayLayerName() {
        return Character.toUpperCase(localLayerName.charAt(0)) + localLayerName.substring(1);
    }

    private void rejectSymlinkImport(Path path) throws Exception {
        Path normalized = path.isAbsolute() ? path.normalize() : projectRoot.resolve(path).normalize();
        if (Files.isSymbolicLink(normalized))
            throw new IllegalArgumentException("Testdata descriptor must be a package-contained non-symlink file");
        packageResources.fromInternalPath(normalized, PackageResourceResolver.Kind.FILE);
    }

    private Path canonicalImport(Path path) throws Exception {
        rejectSymlinkImport(path);
        Path normalized = path.isAbsolute() ? path.normalize() : projectRoot.resolve(path).normalize();
        return packageResources.fromInternalPath(normalized, PackageResourceResolver.Kind.FILE).canonicalPath();
    }

    private Path diagnosticPath(Path path) {
        Path normalized = path.isAbsolute() ? path.normalize() : projectRoot.resolve(path).normalize();
        return normalized.startsWith(authoredRoot) ? projectRoot.resolve(authoredRoot.relativize(normalized)) : normalized;
    }

    private String logicalPath(Path path) {
        Path normalized = diagnosticPath(path);
        return normalized.startsWith(projectRoot) ? projectRoot.relativize(normalized).toString().replace('\\', '/') : "[outside-package]";
    }

    private IllegalArgumentException unavailable(Path path, String layer, String id) {
        Path normalized = diagnosticPath(path);
        String reason = !normalized.startsWith(projectRoot) || Files.isSymbolicLink(normalized) ? "outside-package"
                : !Files.exists(normalized) ? "missing" : !Files.isReadable(normalized) ? "unreadable" : "invalid-schema";
        return new IllegalArgumentException("Selected Package Testdata [" + layer + "] declaration=" + logicalPath(path)
                + (id == null ? "" : " descriptor=" + id) + " reason=" + reason
                + "; correct the Package-relative descriptor and validate this Package.");
    }

    private static List<Path> immutablePaths(List<Path> paths) {
        return paths == null || paths.isEmpty() ? Collections.<Path>emptyList()
                : Collections.unmodifiableList(new ArrayList<Path>(paths));
    }
}
