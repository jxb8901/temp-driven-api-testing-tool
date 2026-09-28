package att.validation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Preserves declared-schema failures and adds evidence-based migration advice for older descriptors. */
public final class SchemaMigrationGuidance {
    private SchemaMigrationGuidance() {}

    public static void verify(Path declaredSchema, Path currentSchema, Map<?, ?> document,
                              String declaredVersion, String currentVersion) throws Exception {
        try {
            JsonSchemaVerifier.verify(declaredSchema, document);
        } catch (JsonSchemaVerifier.SchemaValidationException original) {
            if (!declaredVersion.equals(currentVersion) && Files.isRegularFile(currentSchema)) {
                Map<String, Object> candidate = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : document.entrySet()) candidate.put(String.valueOf(entry.getKey()), entry.getValue());
                candidate.put("schemaVersion", currentVersion);
                try {
                    JsonSchemaVerifier.verify(currentSchema, candidate);
                } catch (JsonSchemaVerifier.SchemaValidationException stillInvalid) {
                    throw new MigrationException(declaredVersion, currentVersion, original, false);
                }
                throw new MigrationException(declaredVersion, currentVersion, original, true);
            }
            throw original;
        }
    }

    public static final class MigrationException extends IllegalArgumentException {
        private final String declaredVersion;
        private final String currentVersion;
        private final String field;
        private MigrationException(String declaredVersion, String currentVersion,
                                   JsonSchemaVerifier.SchemaValidationException original, boolean versionOnly) {
            super(original.getMessage() + "\nschemaVersion: " + declaredVersion
                    + "\ncurrentSchemaVersion: " + currentVersion + "\nfield: " + original.field()
                    + (versionOnly
                        ? "\nMigration: This descriptor validates against the current schema after changing schemaVersion. "
                            + "Upgrade schemaVersion to " + currentVersion + " and revalidate; review dependent files before use."
                        : "\nMigration: The current-schema probe also failed. A version change alone is not sufficient; "
                            + "review the original violation and compare the current schema before editing."), original);
            this.declaredVersion = declaredVersion;
            this.currentVersion = currentVersion;
            this.field = original.field();
        }
        public String declaredVersion() { return declaredVersion; }
        public String currentVersion() { return currentVersion; }
        public String field() { return field; }
    }
}
