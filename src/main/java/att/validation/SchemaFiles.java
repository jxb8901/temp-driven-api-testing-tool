package att.validation;

import java.nio.file.Files;
import java.nio.file.Path;

/** Finds only an explicitly named current or historical schema in one package. */
public final class SchemaFiles {
    private SchemaFiles() {}
    public static Path resolve(Path projectRoot, String filename) {
        if (filename == null || !filename.matches("[A-Za-z0-9._-]+\\.(?:json|xsd)"))
            throw new IllegalArgumentException("Invalid schema filename");
        Path current = projectRoot.resolve("schemas").resolve(filename);
        Path historical = projectRoot.resolve("schemas/history").resolve(filename);
        if (Files.isRegularFile(current) && Files.isRegularFile(historical))
            throw new IllegalArgumentException("Duplicate current/historical schema: " + filename);
        return Files.isRegularFile(historical) ? historical : current;
    }
}
