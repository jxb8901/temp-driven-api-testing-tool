package att.worker;

import java.nio.file.Paths;

/** Child-JVM probe for the shared trusted-schema cache lifecycle tests. */
public final class TrustedSchemaCacheProcess {
    private TrustedSchemaCacheProcess() { }

    public static void main(String[] args) {
        att.validation.SchemaFiles.validateCatalog(Paths.get("."));
    }
}
