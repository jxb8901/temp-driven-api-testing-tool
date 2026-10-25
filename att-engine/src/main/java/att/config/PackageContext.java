package att.config;

import java.nio.file.Path;

/** Explicit context supplied by an approved registry adapter; never inferred from a folder or principal. */
public final class PackageContext {
    private final Path root;
    private final String packageId, environment;
    public PackageContext(Path root, String registeredPackageId, String environment) {
        if (root == null) throw new IllegalArgumentException("Package context requires a root");
        if (registeredPackageId != null && !registeredPackageId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("Registered Package ID is invalid");
        if (environment != null && (environment.trim().isEmpty() || environment.length() > 128))
            throw new IllegalArgumentException("Context Environment is invalid");
        this.root = root.toAbsolutePath().normalize(); this.packageId = registeredPackageId;
        this.environment = environment == null ? null : environment.trim();
    }
    public Path root() { return root; }
    public String registeredPackageId() { return packageId; }
    public String environment() { return environment; }
}
