package att;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/** CLI test fixture helper. Kept local to avoid making the CLI depend on engine tests. */
public final class TestSchemas {
    private TestSchemas() { }

    public static void install(Path packageRoot) throws IOException {
        Path source = Paths.get("schemas").toAbsolutePath().normalize();
        Path destination = packageRoot.toAbsolutePath().normalize().resolve("schemas");
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isSymbolicLink(path)) throw new IOException("Unexpected symbolic link in test schemas: " + path);
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
