package att.server;

import att.server.api.source.SourceManagementContracts;
import att.server.api.source.SourceManagementContracts.CandidateValidation;
import att.server.api.source.SourceManagementContracts.RevisionIdentity;
import att.server.api.source.SourceManagementContracts.RevisionKind;
import att.server.api.source.SourceManagementContracts.RevisionSet;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Materializes a bounded, private whole-candidate tree for the production Engine validators. */
final class ChangeSetCandidateValidator implements ChangeSetPublisher.CandidateValidator, ChangeSetPublisher.SnapshotGenerator {
    private static final long MAX_MATERIALIZED_BYTES = 512L * 1024L * 1024L;
    private final Path runtimeLibs;
    private final List<Path> additionalLibraries;

    ChangeSetCandidateValidator() { this(null, Collections.emptyList()); }
    ChangeSetCandidateValidator(Path runtimeLibs, List<Path> additionalLibraries) {
        this.runtimeLibs=runtimeLibs;
        this.additionalLibraries=additionalLibraries==null?Collections.emptyList():List.copyOf(additionalLibraries);
    }

    @Override public ChangeSetPublisher.ValidationResult validate(ChangeSetPublisher.Candidate candidate) throws Exception {
        if (candidate == null) return ChangeSetPublisher.ValidationResult.rejected("ATT-CHANGESET-VALIDATION-UNAVAILABLE");
        Path workspace = privateDirectory();
        try {
            String candidateFingerprint = fingerprint(candidate);
            RevisionSet revisions = revisions(candidate, candidateFingerprint);
            if (ChangeSetPublisher.PACKAGE_SOURCE.equals(candidate.domain())) {
                Path packageRoot = Files.createDirectory(workspace.resolve("package"));
                Path runtimeRoot = Files.createDirectory(workspace.resolve("runtime"));
                materializeDirectories(candidate.root(), packageRoot);
                materialize(candidate.files(), candidate::read, packageRoot);
                materialize(candidate.centralFiles(), candidate::readCentral, runtimeRoot);
                Path config = runtimeRoot.resolve("config/config.yaml");
                CandidateValidation result = (CandidateValidation)invokeEngine("validatePackage",
                        new Class<?>[]{Path.class,Path.class,Path.class,String.class,RevisionSet.class,String.class},
                        packageRoot,config,runtimeRoot,candidate.scopeId(),revisions,candidateFingerprint);
                return result.executionEligible ? ChangeSetPublisher.ValidationResult.accepted(candidateFingerprint,result.revisions)
                        : ChangeSetPublisher.ValidationResult.rejected(validationCode(result));
            }
            if (ChangeSetPublisher.CENTRAL_RUNTIME_CONFIG.equals(candidate.domain())) {
                Path runtimeRoot = Files.createDirectory(workspace.resolve("runtime"));
                materialize(candidate.files(), candidate::read, runtimeRoot);
                byte[] baselineBytes = candidate.readBaseCentral("config/config.yaml");
                if (baselineBytes == null) return ChangeSetPublisher.ValidationResult.rejected("ATT-CONFIG-BASELINE-UNAVAILABLE");
                Path baseline = workspace.resolve("baseline/config.yaml");
                Files.createDirectories(baseline.getParent());
                Files.write(baseline, baselineBytes);
                CandidateValidation result = (CandidateValidation)invokeEngine("validateCentralConfig",
                        new Class<?>[]{Path.class,Path.class,Path.class,RevisionSet.class,String.class},
                        runtimeRoot.resolve("config/config.yaml"),runtimeRoot,baseline,revisions,candidateFingerprint);
                return result.executionEligible ? ChangeSetPublisher.ValidationResult.accepted(candidateFingerprint,result.revisions)
                        : ChangeSetPublisher.ValidationResult.rejected(validationCode(result));
            }
            return ChangeSetPublisher.ValidationResult.rejected("ATT-CHANGESET-DOMAIN-UNSUPPORTED");
        } finally {
            deleteTree(workspace);
        }
    }

    @Override public byte[] generate(ChangeSetPublisher.Candidate candidate, String workbookPath) throws Exception {
        if (candidate == null || !ChangeSetPublisher.PACKAGE_SOURCE.equals(candidate.domain()))
            throw new IllegalArgumentException("Testcase snapshot generation requires a Package candidate");
        Path workspace = privateDirectory();
        try {
            Path packageRoot = Files.createDirectory(workspace.resolve("package"));
            Path runtimeRoot = Files.createDirectory(workspace.resolve("runtime"));
            materializeDirectories(candidate.root(), packageRoot);
            materialize(candidate.files(), candidate::read, packageRoot);
            materialize(candidate.centralFiles(), candidate::readCentral, runtimeRoot);
            Path config = runtimeRoot.resolve("config/config.yaml");
            Path workbook = packageRoot.resolve(Path.of(workbookPath)).normalize();
            if (!workbook.startsWith(packageRoot)) throw new IOException("Workbook path escapes the candidate Package");
            return (byte[])invokeEngine("generateSnapshot",new Class<?>[]{Path.class,Path.class,Path.class,String.class},
                    packageRoot,config,runtimeRoot,workbookPath);
        } finally {
            deleteTree(workspace);
        }
    }

    private Object invokeEngine(String methodName,Class<?>[] parameterTypes,Object... arguments) throws Exception {
        List<URL> urls=new ArrayList<URL>();
        if(runtimeLibs!=null&&Files.isDirectory(runtimeLibs)) {
            Path bundled=runtimeLibs.resolve("att-local-"+ServerBuildInfo.PRODUCT+".jar");
            if(Files.isRegularFile(bundled))urls.add(bundled.toUri().toURL());
            try(var entries=Files.list(runtimeLibs)) {
                for(Path entry:entries.filter(path->path.getFileName().toString().endsWith(".jar"))
                        .filter(path->!path.equals(bundled)).sorted().toList())urls.add(entry.toUri().toURL());
            }
        }
        for(Path directory:additionalLibraries)if(directory!=null&&Files.isDirectory(directory)) {
            try(var entries=Files.list(directory)) {
                for(Path entry:entries.filter(path->path.getFileName().toString().endsWith(".jar")).sorted().toList())
                    urls.add(entry.toUri().toURL());
            }
        }
        ClassLoader parent=ChangeSetCandidateValidator.class.getClassLoader();
        try(URLClassLoader loader=new URLClassLoader(urls.toArray(URL[]::new),parent)) {
            Class<?> validatorType=Class.forName("att.validation.SourceCandidateValidator",true,loader);
            Object validator=validatorType.getConstructor().newInstance();
            try { return validatorType.getMethod(methodName,parameterTypes).invoke(validator,arguments); }
            catch(InvocationTargetException wrapped) {
                Throwable cause=wrapped.getCause();
                if(cause instanceof Exception exception)throw exception;
                if(cause instanceof Error error)throw error;
                throw new IllegalStateException("Installed Engine validation failed",cause);
            }
        }
    }

    private static String validationCode(CandidateValidation result) {
        for (SourceManagementContracts.ValidationDiagnostic diagnostic : result.diagnostics)
            if (diagnostic.severity == SourceManagementContracts.Severity.ERROR) return diagnostic.code;
        return result.state == SourceManagementContracts.ValidationState.INCOMPLETE
                ? "ATT-SOURCE-VALIDATION-INCOMPLETE" : "ATT-SOURCE-CANDIDATE-INVALID";
    }

    private static RevisionSet revisions(ChangeSetPublisher.Candidate candidate, String fingerprint) throws Exception {
        RevisionIdentity registration = candidate.registryRevision() == null ? null
                : RevisionIdentity.of(RevisionKind.PACKAGE_REGISTRATION, "package-registry", candidate.registryRevision());
        RevisionIdentity changeSet = RevisionIdentity.of(RevisionKind.CHANGE_SET, candidate.scopeId(), fingerprint);
        RevisionIdentity config = candidate.configRevision() == null ? null
                : RevisionIdentity.of(RevisionKind.CONFIG_SOURCE, "central-runtime-config", candidate.configRevision());
        RevisionIdentity schema = candidate.schemaRevision() == null ? null
                : RevisionIdentity.of(RevisionKind.ENGINE_SCHEMA, "installed-engine", candidate.schemaRevision());
        RevisionIdentity packageSource = candidate.packageSourceRevision() == null ? null
                : RevisionIdentity.of(RevisionKind.PACKAGE_SOURCE, candidate.scopeId(), candidate.packageSourceRevision());
        return new RevisionSet(registration, null, changeSet, null, config, null, null, schema, null,
                packageSource, null);
    }

    private static String fingerprint(ChangeSetPublisher.Candidate candidate) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(candidate.domain().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(candidate.scopeId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        Set<String> paths = new TreeSet<String>();
        paths.addAll(candidate.files());
        if (ChangeSetPublisher.PACKAGE_SOURCE.equals(candidate.domain())) {
            for (String path : candidate.centralFiles()) paths.add("central/" + path);
        }
        for (String path : paths) {
            boolean central = path.startsWith("central/");
            String logical = central ? path.substring("central/".length()) : path;
            byte[] bytes = central ? candidate.readCentral(logical) : candidate.read(logical);
            if (bytes == null) continue;
            digest.update(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(bytes);
            digest.update((byte) 0);
        }
        return hex(digest.digest());
    }

    private static void materialize(Set<String> logicalPaths, Reader reader, Path root) throws Exception {
        long total = 0L;
        if (logicalPaths.size() > ChangeSetPublisher.MAX_REVISION_FILES)
            throw new IOException("Candidate file count exceeds the validation limit");
        for (String logical : new TreeSet<String>(logicalPaths)) {
            Path relative = Path.of(logical).normalize();
            if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")
                    || !relative.toString().replace('\\', '/').equals(logical))
                throw new IOException("Candidate contains a non-canonical logical path");
            byte[] bytes = reader.read(logical);
            if (bytes == null) throw new IOException("Candidate source closure is incomplete");
            if (bytes.length > ChangeSetPublisher.MAX_REVISION_FILE_BYTES)
                throw new IOException("Candidate file exceeds the validation limit");
            total = Math.addExact(total, bytes.length);
            if (total > MAX_MATERIALIZED_BYTES) throw new IOException("Candidate closure exceeds the validation limit");
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) throw new IOException("Candidate path escapes its validation root");
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        }
    }

    /** Preserve empty Package directories: the immutable candidate overlays files, not directory entries. */
    private static void materializeDirectories(Path source, Path destination) throws IOException {
        Path canonical=source.toRealPath();
        final int[] entries={0};
        Files.walkFileTree(canonical,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attributes) throws IOException {
                if(attributes.isSymbolicLink()||Files.isSymbolicLink(directory))
                    throw new IOException("Candidate Package contains an unsafe directory link");
                if(++entries[0]>ChangeSetPublisher.MAX_REVISION_FILES*3)
                    throw new IOException("Candidate Package exceeds the validation directory limit");
                Path target=destination.resolve(canonical.relativize(directory)).normalize();
                if(!target.startsWith(destination))throw new IOException("Candidate Package directory escapes its validation root");
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) throws IOException {
                if(attributes.isSymbolicLink()||Files.isSymbolicLink(file))
                    throw new IOException("Candidate Package contains an unsafe file link");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file,IOException failure) throws IOException {throw failure;}
        });
    }

    private static Path privateDirectory() throws IOException {
        Path directory = Files.createTempDirectory("att-source-candidate-");
        try { Files.setPosixFilePermissions(directory, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)); }
        catch (UnsupportedOperationException ignored) { }
        return directory;
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(directory); return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(String.format("%02x", item & 255));
        return result.toString();
    }

    @FunctionalInterface private interface Reader { byte[] read(String logicalPath) throws IOException; }
}
