package att.resource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Resolves authored package resource names inside one canonical package namespace. */
public final class PackageResourceResolver {
    public enum Kind { ANY, FILE, DIRECTORY }

    private final Path packageRoot;

    public PackageResourceResolver(Path packageRoot) {
        if (packageRoot == null) throw new IllegalArgumentException("ATT package root is required");
        try {
            this.packageRoot = packageRoot.toRealPath();
        } catch (Exception error) {
            throw new IllegalArgumentException("ATT package root is unavailable", error);
        }
    }

    public Path packageRoot() { return packageRoot; }

    /** Creates an origin from a canonical package-relative descriptor directory. */
    public ResourceOrigin origin(Path descriptorDirectory) {
        if (descriptorDirectory == null) return new ResourceOrigin("");
        try {
            Path canonical = descriptorDirectory.toRealPath();
            if (!canonical.startsWith(packageRoot)) throw unsafe("<origin>");
            return new ResourceOrigin(logicalName(packageRoot.relativize(canonical)));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Package resource origin is unavailable", error);
        }
    }

    /** Resolves one package locator. Bare names start at the package root; ./ and ../ use origin. */
    public PackageResource resolve(ResourceLocator locator, ResourceOrigin origin, Kind kind) {
        if (locator == null) throw new IllegalArgumentException("Package resource locator is required");
        String authored = locator.value();
        Path relative;
        try {
            Path parsed = Paths.get(authored.replace('\\', '/').replace('/', File.separatorChar));
            if (parsed.isAbsolute() || authored.matches("^[A-Za-z]:.*") || authored.startsWith("\\\\")
                    || authored.startsWith("\\")) throw invalid(authored);
            boolean descriptorRelative = authored.startsWith("./") || authored.startsWith("../")
                    || authored.equals(".") || authored.equals("..");
            Path base = descriptorRelative && origin != null && !origin.logicalDirectory().isEmpty()
                    ? packageRoot.resolve(origin.logicalDirectory().replace('/', File.separatorChar)) : packageRoot;
            relative = base.resolve(parsed).normalize();
        } catch (RuntimeException error) {
            if (error instanceof IllegalArgumentException) throw error;
            throw invalid(authored);
        }
        if (!relative.startsWith(packageRoot)) throw unsafe(authored);
        try {
            if (!Files.exists(relative, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Package resource not found: " + authored);
            Path canonical = relative.toRealPath();
            if (!canonical.startsWith(packageRoot)) throw unsafe(authored);
            if (Files.isSymbolicLink(relative)) {
                // Symlinks are allowed only when their canonical targets stay in the package.
                Path linkTarget = relative.toRealPath();
                if (!linkTarget.startsWith(packageRoot)) throw unsafe(authored);
            }
            if (kind == Kind.FILE && !Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Package resource is not a regular file: " + authored);
            if (kind == Kind.DIRECTORY && !Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Package resource is not a directory: " + authored);
            return new PackageResource(logicalName(packageRoot.relativize(canonical)), canonical, kind);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to resolve package resource: " + authored, error);
        }
    }

    /** Converts a trusted internal path to a contained resource without accepting author locators. */
    public PackageResource fromInternalPath(Path path, Kind kind) {
        if (path == null) throw new IllegalArgumentException("Internal package path is required");
        try {
            Path canonical = path.toRealPath();
            if (!canonical.startsWith(packageRoot)) throw unsafe(logicalNameOrName(path));
            if (kind == Kind.FILE && !Files.isRegularFile(canonical, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Package resource is not a regular file: " + logicalName(packageRoot.relativize(canonical)));
            if (kind == Kind.DIRECTORY && !Files.isDirectory(canonical, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Package resource is not a directory: " + logicalName(packageRoot.relativize(canonical)));
            return new PackageResource(logicalName(packageRoot.relativize(canonical)), canonical, kind);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to resolve internal package resource", error);
        }
    }

    /**
     * Normalizes a trusted internal candidate that may not exist yet. Existing ancestors are
     * canonicalized so a symlink cannot make a future sidecar or optional directory escape.
     */
    public Path internalCandidate(Path path) {
        if (path == null) throw new IllegalArgumentException("Internal package path is required");
        Path normalized = path.toAbsolutePath().normalize();
        Path ancestor = normalized;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor == null) throw unsafe(logicalNameOrName(normalized));
        try {
            Path canonicalAncestor = ancestor.toRealPath();
            if (!canonicalAncestor.startsWith(packageRoot)) throw unsafe(logicalNameOrName(normalized));
            Path candidate = canonicalAncestor.resolve(ancestor.relativize(normalized)).normalize();
            if (!candidate.startsWith(packageRoot)) throw unsafe(logicalNameOrName(normalized));
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                Path canonical = normalized.toRealPath();
                if (!canonical.startsWith(packageRoot)) throw unsafe(logicalNameOrName(normalized));
                return canonical;
            }
            return candidate;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to resolve internal package path", error);
        }
    }

    private String logicalNameOrName(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(packageRoot) ? logicalName(packageRoot.relativize(normalized)) : "<outside-package>";
    }

    private static String logicalName(Path path) { return path.toString().replace('\\', '/'); }

    private static IllegalArgumentException invalid(String authored) {
        return new IllegalArgumentException("Invalid package resource locator: " + authored);
    }

    private static IllegalArgumentException unsafe(String authored) {
        return new IllegalArgumentException("Package resource escapes the package root: " + authored);
    }

    public static final class ResourceLocator {
        private final String value;
        public ResourceLocator(String value) {
            if (value == null || value.trim().isEmpty() || !value.equals(value.trim()))
                throw new IllegalArgumentException("Package resource locator must be non-blank without surrounding whitespace");
            this.value = value;
        }
        public String value() { return value; }
    }

    public static final class ResourceOrigin {
        private final String logicalDirectory;
        public ResourceOrigin(String logicalDirectory) {
            this.logicalDirectory = logicalDirectory == null ? "" : logicalDirectory;
        }
        public String logicalDirectory() { return logicalDirectory; }
    }

    public static final class PackageResource {
        private final String logicalName;
        private final Path canonicalPath;
        private final Kind kind;
        private PackageResource(String logicalName, Path canonicalPath, Kind kind) {
            this.logicalName = logicalName;
            this.canonicalPath = canonicalPath;
            this.kind = kind;
        }
        public String logicalName() { return logicalName; }
        /** Internal engine path; do not publish in ATT runtime Context. */
        public Path canonicalPath() { return canonicalPath; }
        public Kind kind() { return kind; }
    }
}
