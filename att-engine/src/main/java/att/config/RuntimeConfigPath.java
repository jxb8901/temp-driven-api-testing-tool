package att.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Resolves the shared ATT runtime configuration independently of a Package root. */
public final class RuntimeConfigPath {
    private RuntimeConfigPath() { }

    public static Path resolve(Path requested, Path packageRoot) {
        Path value = requested == null ? Paths.get("config/config.yaml") : requested;
        if (value.isAbsolute()) return value.toAbsolutePath().normalize();
        String home = firstNonBlank(System.getProperty("att.home"), System.getenv("ATT_HOME"));
        Path root = home == null ? packageRoot : Paths.get(home);
        if (root == null) root = Paths.get("").toAbsolutePath();
        return root.toAbsolutePath().normalize().resolve(value).normalize();
    }

    public static Path root(Path configFile) {
        Path absolute = configFile.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        for (Path current = parent; current != null; current = current.getParent()) {
            if (current.getFileName() != null && "config".equals(current.getFileName().toString())
                    && current.getParent() != null) return current.getParent();
        }
        return parent == null ? Paths.get("").toAbsolutePath().normalize() : parent;
    }

    public static Path infer(Path packageRoot, FrameworkConfig config) {
        if (config != null) {
            for (ToolConfig tool : config.tools().values()) {
                Path source = tool.sourceFile();
                if (source == null) continue;
                Path current = source.toAbsolutePath().normalize().getParent();
                while (current != null) {
                    if (current.getFileName() != null && "config".equals(current.getFileName().toString())) {
                        Path shared = current.resolve("config.yaml");
                        if (java.nio.file.Files.isRegularFile(shared)) return shared;
                    }
                    current = current.getParent();
                }
            }
        }
        return resolve(Paths.get("config/config.yaml"), packageRoot);
    }

    public static Path serverDefault(Path serverConfigFile) {
        String home = firstNonBlank(System.getProperty("att.home"), System.getenv("ATT_HOME"));
        if (home != null) return Paths.get(home).toAbsolutePath().normalize().resolve("config/config.yaml");
        if (serverConfigFile != null) {
            Path path = serverConfigFile.toAbsolutePath().normalize();
            Path parent = path.getParent();
            if (parent != null && parent.getFileName() != null && "config".equals(parent.getFileName().toString())
                    && parent.getParent() != null) return parent.getParent().resolve("config/config.yaml");
        }
        return Paths.get("config/config.yaml").toAbsolutePath().normalize();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        if (second != null && !second.trim().isEmpty()) return second.trim();
        return null;
    }
}
