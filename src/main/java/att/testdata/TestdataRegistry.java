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

/** Immutable environment base plus optional Load-local whole-descriptor overlay. */
public final class TestdataRegistry {
    private final Path projectRoot;
    private final List<Path> environmentFiles;
    private final List<Path> loadFiles;
    private final TestdataDescriptorLoader loader;
    private final Map<Path, String> idsByPath = new ConcurrentHashMap<Path, String>();
    private final Map<Path, TestdataDescriptor> descriptorsByPath = new ConcurrentHashMap<Path, TestdataDescriptor>();
    private volatile Map<String, List<Path>> environmentIndex;
    private volatile Map<String, List<Path>> loadIndex;

    public TestdataRegistry(Path projectRoot, List<Path> environmentFiles, List<Path> loadFiles) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.environmentFiles = immutablePaths(environmentFiles);
        this.loadFiles = immutablePaths(loadFiles);
        this.loader = new TestdataDescriptorLoader(this.projectRoot);
    }

    public TestdataDescriptor resolve(String id) throws Exception {
        Path local = indexed(loadIndex(), id, "Load-local");
        Path environment = indexed(environmentIndex(), id, "Environment");
        Path file = local == null ? environment : local;
        if (file == null) throw new IllegalArgumentException("Testdata id is not configured in the effective registry: " + id);
        TestdataDescriptor descriptor = descriptorsByPath.get(file);
        if (descriptor == null) {
            descriptor = loader.load(file);
            TestdataDescriptor previous = descriptorsByPath.putIfAbsent(file, descriptor);
            if (previous != null) descriptor = previous;
        }
        if (!id.equals(descriptor.id())) throw new IllegalArgumentException("Testdata descriptor id changed while resolving: " + id);
        return descriptor;
    }

    public String layer(String id) throws Exception {
        Path local = indexed(loadIndex(), id, "Load-local");
        Path environment = indexed(environmentIndex(), id, "Environment");
        if (local != null) return "load-local";
        if (environment != null) return "environment";
        throw new IllegalArgumentException("Testdata id is not configured in the effective registry: " + id);
    }

    /** Full schema and duplicate-ID validation used by explicit package/Load validation. */
    public Map<String, TestdataDescriptor> validateAll() throws Exception {
        Map<String, TestdataDescriptor> environment = validateLayer(environmentFiles, "Environment");
        Map<String, TestdataDescriptor> local = validateLayer(loadFiles, "Load-local");
        Map<String, TestdataDescriptor> effective = new LinkedHashMap<String, TestdataDescriptor>(environment);
        effective.putAll(local);
        return Collections.unmodifiableMap(effective);
    }

    public List<Path> environmentFiles() { return environmentFiles; }
    public List<Path> loadFiles() { return loadFiles; }

    private Map<String, TestdataDescriptor> validateLayer(List<Path> files, String layer) throws Exception {
        Map<String, TestdataDescriptor> result = new LinkedHashMap<String, TestdataDescriptor>();
        Set<Path> uniqueFiles = new LinkedHashSet<Path>();
        for (Path path : files) {
            rejectSymlinkImport(path);
            Path canonical = path.toRealPath();
            if (!canonical.startsWith(projectRoot.toRealPath()))
                throw new IllegalArgumentException("Testdata descriptor path escapes package root");
            if (!uniqueFiles.add(canonical)) throw new IllegalArgumentException("Duplicate testdata path in " + layer + " layer");
            TestdataDescriptor descriptor = load(path);
            if (result.putIfAbsent(descriptor.id(), descriptor) != null)
                throw new IllegalArgumentException("Duplicate testdata id in " + layer + " layer: " + descriptor.id());
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
            if (result == null) environmentIndex = result = index(environmentFiles, "Environment");
        }
        return result;
    }

    private Map<String, List<Path>> loadIndex() throws Exception {
        Map<String, List<Path>> result = loadIndex;
        if (result == null) synchronized (this) {
            result = loadIndex;
            if (result == null) loadIndex = result = index(loadFiles, "Load-local");
        }
        return result;
    }

    private Map<String, List<Path>> index(List<Path> files, String layer) throws Exception {
        Map<String, List<Path>> result = new LinkedHashMap<String, List<Path>>();
        for (Path path : files) {
            rejectSymlinkImport(path);
            Path canonical = path.toRealPath();
            if (!canonical.startsWith(projectRoot.toRealPath()))
                throw new IllegalArgumentException("Testdata descriptor path escapes package root");
            String id = idsByPath.get(canonical);
            if (id == null) {
                id = loader.readId(canonical);
                idsByPath.put(canonical, id);
            }
            List<Path> paths = result.get(id);
            if (paths == null) { paths = new ArrayList<Path>(); result.put(id, paths); }
            paths.add(canonical);
        }
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

    private void rejectSymlinkImport(Path path) throws Exception {
        Path normalized = path.isAbsolute() ? path.normalize() : projectRoot.resolve(path).normalize();
        if (!normalized.startsWith(projectRoot) || Files.isSymbolicLink(normalized))
            throw new IllegalArgumentException("Testdata descriptor must be a package-contained non-symlink file");
    }

    private static List<Path> immutablePaths(List<Path> paths) {
        return paths == null || paths.isEmpty() ? Collections.<Path>emptyList()
                : Collections.unmodifiableList(new ArrayList<Path>(paths));
    }
}
