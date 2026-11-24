package att.flow;

import att.config.FrameworkConfig;
import att.config.PackageConfig;
import att.resource.PackageResourceResolver;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves the Package Flow roots used by Native Cases, Debug, LOAD, validation and inspection.
 * The canonical root is {@code resources.flowRoot} (default {@code flows}). Existing
 * {@code <templatesRoot>/flows} trees remain discoverable for migration; the same Flow ID in both
 * roots is rejected rather than silently preferring one descriptor.
 */
public final class PackageFlowRoots {
    public static final Path DEFAULT_FLOW_ROOT = Paths.get("flows");

    private PackageFlowRoots() {}

    public static Resolution resolve(Path packageRoot, FrameworkConfig config) throws Exception {
        if (config == null) return resolve(packageRoot, DEFAULT_FLOW_ROOT, Paths.get("templates"));
        return resolve(packageRoot, config.flowRoot(), config.templatesRoot());
    }

    public static Resolution resolve(Path packageRoot, PackageConfig pkg) throws Exception {
        if (pkg == null) return resolve(packageRoot, DEFAULT_FLOW_ROOT, Paths.get("templates"));
        return resolve(packageRoot, pkg.flowRoot(), pkg.templatesRoot());
    }

    public static Resolution resolve(Path packageRoot, Path flowRoot, Path templatesRoot) throws Exception {
        PackageResourceResolver resources = new PackageResourceResolver(packageRoot);
        Path packageCanonical = resources.packageRoot();
        Path configured = canonicalizeCandidate(resources, packageCanonical, flowRoot == null ? DEFAULT_FLOW_ROOT : flowRoot);
        Path templates = templatesRoot == null ? Paths.get("templates") : templatesRoot;
        Path legacyCompanion = canonicalizeCandidate(resources, packageCanonical, templates.resolve("flows"));
        rejectOverlappingRoots(configured, legacyCompanion);
        List<Path> existing = new ArrayList<Path>();
        Set<String> seen = new LinkedHashSet<String>();
        addExistingRoot(existing, seen, configured);
        if (!samePath(configured, legacyCompanion)) addExistingRoot(existing, seen, legacyCompanion);
        return new Resolution(configured, legacyCompanion, Collections.unmodifiableList(existing));
    }

    /**
     * Rejects nested/overlapping Flow roots such as {@code resources.flowRoot: templates} with the
     * migration companion {@code templates/flows}. Those would otherwise walk the same descriptor
     * twice and report a false duplicate Flow ID.
     */
    private static void rejectOverlappingRoots(Path configured, Path companion) {
        if (configured == null || companion == null || samePath(configured, companion)) return;
        Path left = configured.toAbsolutePath().normalize();
        Path right = companion.toAbsolutePath().normalize();
        if (right.startsWith(left) || left.startsWith(right))
            throw new IllegalArgumentException(
                    "resources.flowRoot must not overlap <templatesRoot>/flows ("
                            + left + " overlaps " + right
                            + "). Choose a distinct Flow root such as flows/, or set flowRoot to the companion path itself.");
    }

    private static void addExistingRoot(List<Path> existing, Set<String> seen, Path candidate) throws Exception {
        if (candidate == null || !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(candidate) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Flow root must be a non-symlink directory: " + candidate);
        Path canonical = candidate.toRealPath();
        String key = canonical.toString();
        if (seen.add(key)) existing.add(canonical);
    }

    private static Path canonicalizeCandidate(PackageResourceResolver resources, Path packageRoot, Path relativeOrAbsolute)
            throws Exception {
        Path absolute = relativeOrAbsolute.isAbsolute() ? relativeOrAbsolute : packageRoot.resolve(relativeOrAbsolute);
        Path internal = resources.internalCandidate(absolute.normalize());
        if (Files.exists(internal, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(internal) || !Files.isDirectory(internal, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Flow root must be a non-symlink directory: " + internal);
            return resources.fromInternalPath(internal, PackageResourceResolver.Kind.DIRECTORY).canonicalPath();
        }
        return internal.normalize();
    }

    private static boolean samePath(Path left, Path right) {
        if (left == null || right == null) return false;
        return left.toAbsolutePath().normalize().equals(right.toAbsolutePath().normalize());
    }

    /** Resolved Flow roots for one Package. */
    public static final class Resolution {
        private final Path configuredFlowRoot;
        private final Path legacyCompanion;
        private final List<Path> existingRoots;

        Resolution(Path configuredFlowRoot, Path legacyCompanion, List<Path> existingRoots) {
            this.configuredFlowRoot = configuredFlowRoot;
            this.legacyCompanion = legacyCompanion;
            this.existingRoots = existingRoots;
        }

        /** Configured canonical Flow root (default {@code flows}), even when the directory is absent. */
        public Path configuredFlowRoot() { return configuredFlowRoot; }

        /** Legacy companion {@code <templatesRoot>/flows}, even when absent. */
        public Path legacyCompanion() { return legacyCompanion; }

        /** Existing safe directories among the configured and legacy companion roots. */
        public List<Path> existingRoots() { return existingRoots; }

        public boolean isEmpty() { return existingRoots.isEmpty(); }
    }
}
