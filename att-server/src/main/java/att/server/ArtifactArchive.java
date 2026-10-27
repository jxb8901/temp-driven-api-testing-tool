package att.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import att.server.api.LimitDiagnostic;
import att.server.api.LimitOutcome;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Builds the sole public representation of a Job's output. Raw Worker output is never served. */
final class ArtifactArchive {
    static final String MANIFEST = "export-manifest-v1.json";
    static final String LOAD_REPORT_MANIFEST = "load-report-manifest.json";
    static final String LOAD_REPORT_MANIFEST_SCHEMA = "att-load-report-manifest/v1";
    static final int MAX_ENTRIES = 10_000;
    static final long MAX_ENTRY_BYTES = 16L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
    static final int MAX_CANDIDATES = 50_000;
    static final long MAX_MEMORY_EXPORT_BYTES = 8L * 1024 * 1024;
    static final long MAX_ARCHIVE_BYTES = MAX_TOTAL_BYTES + MAX_MANIFEST_BYTES + 4L * 1024 * 1024;
    private static final Set<String> SAFE_EXTENSIONS = Set.of("txt", "log", "json", "yaml", "yml", "xml", "html", "htm", "css", "csv", "md");
    private static final String OMISSION_REASON = "unsupported, unsafe, duplicate, binary, oversized, or unapproved output";
    private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                    .maxNestingDepth(16).maxStringLength(8192).maxNumberLength(20).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @FunctionalInterface interface EntryValidator { void validate(Path source,byte[] capturedBytes); }
    @FunctionalInterface interface ZipProgress { void entryWritten(String path) throws Exception; }
    record Entry(String path, String mediaType, long size, String sha256, String classification, String decision, boolean report, boolean preview) {}
    private record ReportFile(long size,String sha256) {}

    private ArtifactArchive() {}

    static Path build(Path output, Path archive) throws Exception {
        return build(output,archive,MAX_MEMORY_EXPORT_BYTES,null,MAX_CANDIDATES,null);
    }

    /** Validate a copy of captured producer bytes so the validator cannot mutate the ZIP payload. */
    static Path build(Path output,Path archive,EntryValidator validator) throws Exception {
        return build(output,archive,MAX_MEMORY_EXPORT_BYTES,null,MAX_CANDIDATES,validator);
    }

    /** The zero-budget path also provides a controlled disk-staging benchmark. */
    static Path build(Path output, Path archive,long memoryBudget) throws Exception {
        return build(output,archive,memoryBudget,null,MAX_CANDIDATES,null);
    }

    static Path build(Path output,Path archive,long memoryBudget,EntryValidator validator) throws Exception {
        return build(output,archive,memoryBudget,null,MAX_CANDIDATES,validator);
    }

    /** Package-private progress seam for deterministic process-crash lifecycle tests. */
    static Path build(Path output, Path archive,long memoryBudget,ZipProgress progress) throws Exception {
        return build(output,archive,memoryBudget,progress,MAX_CANDIDATES,null);
    }

    /** Test-only cap seam; production entry points always retain MAX_CANDIDATES. */
    static Path buildForTest(Path output,Path archive,int maxCandidates) throws Exception {
        if(maxCandidates<1||maxCandidates>MAX_CANDIDATES)throw new IllegalArgumentException("Invalid test candidate cap");
        return build(output,archive,MAX_MEMORY_EXPORT_BYTES,null,maxCandidates,null);
    }

    static Path buildForTest(Path output,Path archive,int maxCandidates,EntryValidator validator) throws Exception {
        if(maxCandidates<1||maxCandidates>MAX_CANDIDATES)throw new IllegalArgumentException("Invalid test candidate cap");
        return build(output,archive,MAX_MEMORY_EXPORT_BYTES,null,maxCandidates,validator);
    }

    private static Path build(Path output,Path archive,long memoryBudget,ZipProgress progress,int maxCandidates,EntryValidator validator) throws Exception {
        if(memoryBudget<0||memoryBudget>MAX_MEMORY_EXPORT_BYTES)throw new IllegalArgumentException("Artifact memory budget exceeds limits");
        ArtifactMetricsRecorder metrics = ArtifactMetricsRecorder.CURRENT.get();
        if (metrics != null) {
            metrics.phase("scan_validate_preserve");
            metrics.value.sourceFiles=0L;metrics.value.sourceBytes=0L;
            metrics.value.includedEntries=0L;metrics.value.omittedEntries=0L;metrics.value.includedBytes=0L;
            metrics.value.temporaryExportFiles=0L;metrics.value.temporaryExportBytes=0L;metrics.value.memoryExportPeakBytes=0L;
        }
        if (Files.isSymbolicLink(output)) throw new IllegalArgumentException("Job output root must not be a symbolic link");
        Path requestedRoot=output.toAbsolutePath().normalize(),root=output.toRealPath();
        if (!root.equals(requestedRoot.getParent().toRealPath().resolve(requestedRoot.getFileName()))) throw new IllegalArgumentException("Job output escaped its private directory");
        Path destination=archive.toAbsolutePath().normalize();Files.createDirectories(destination.getParent());
        Path staging=Files.createTempFile(destination.getParent(),".artifact-",".zip.tmp");
        Path exports=Files.createTempDirectory(destination.getParent(),".artifact-export-");
        boolean published=false;
        try {
            List<Path> candidates=inventory(root,maxCandidates);
            if(metrics!=null){metrics.value.candidateCount=(long)candidates.size();usage(metrics.value.limitUsage,"artifact.inventory.candidates",candidates.size(),maxCandidates);}
            Map<String,ReportFile> requiredLoadReportEntries=validateLoadReportBundles(root,candidates);
            Set<String> names=new HashSet<>();List<String> approved=new ArrayList<>();long total=0;int omitted=0;long oversizedOmission=0L;long largestEntryBytes=0L;int entryCapOmissions=0;
            Map<String,byte[]> memoryExports=new LinkedHashMap<>();long retained=0;
            for(Path candidate:candidates) {
                if(root.relativize(candidate).getNameCount()>=64&&Files.isDirectory(candidate,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Artifact directory depth exceeds limits");
                if(candidate.equals(root)||Files.isDirectory(candidate,LinkOption.NOFOLLOW_LINKS))continue;
                if(Files.isSymbolicLink(candidate)||!Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)){omitted++;if(metrics!=null)metrics.value.omittedEntries=(long)omitted;continue;}
                if(!candidate.toRealPath().startsWith(root))throw new IllegalArgumentException("Artifact escaped its private directory");
                if(metrics!=null)metrics.source(Files.size(candidate));
                String relative=root.relativize(candidate).toString().replace('\\','/'),lower=relative.toLowerCase(Locale.ROOT);
                if(!safePath(relative)||lower.equals(MANIFEST)||!SAFE_EXTENSIONS.contains(extension(relative))||names.contains(lower)){omitted++;if(metrics!=null)metrics.value.omittedEntries=(long)omitted;continue;}
                if(approved.size()>=MAX_ENTRIES){omitted++;entryCapOmissions++;if(metrics!=null)metrics.value.omittedEntries=(long)omitted;continue;}
                long sourceSize=Files.size(candidate);
                largestEntryBytes=Math.max(largestEntryBytes,sourceSize);
                if(sourceSize>MAX_ENTRY_BYTES){omitted++;oversizedOmission=Math.max(oversizedOmission,sourceSize);if(metrics!=null)metrics.value.omittedEntries=(long)omitted;continue;}
                byte[] bytes;
                try {
                    bytes=readProducerBytes(candidate);
                    if(validator!=null)validator.validate(candidate,bytes.clone());
                } catch(IllegalArgumentException rejected) {
                    omitted++;if(metrics!=null)metrics.value.omittedEntries=(long)omitted;continue;
                }
                if(bytes.length>MAX_ENTRY_BYTES){omitted++;oversizedOmission=Math.max(oversizedOmission,bytes.length);if(metrics!=null)metrics.value.omittedEntries=(long)omitted;continue;}
                ReportFile committed=requiredLoadReportEntries.get(relative);
                if(committed!=null&&(bytes.length!=committed.size()||!sha256(bytes).equals(committed.sha256())))
                    throw new java.io.IOException("A committed LOAD report file changed while it was captured.");
                largestEntryBytes=Math.max(largestEntryBytes,bytes.length);
                if(total+bytes.length>MAX_TOTAL_BYTES)throw new LimitFailure("Artifact total size exceeds limits","artifact.archive.totalBytes",MAX_TOTAL_BYTES,total+bytes.length);
                names.add(lower);approved.add(relative);total+=bytes.length;
                if(bytes.length<=memoryBudget-retained) {
                    memoryExports.put(relative,bytes);retained+=bytes.length;
                    if(metrics!=null)metrics.value.memoryExportPeakBytes=retained;
                } else {
                    Path target=exports.resolve(relative);Files.createDirectories(target.getParent());Files.write(target,bytes);
                    if(metrics!=null){metrics.value.temporaryExportFiles++;metrics.value.temporaryExportBytes+=bytes.length;}
                }
            }
            if(!approved.containsAll(requiredLoadReportEntries.keySet())) {
                Set<String> missing=new HashSet<>(requiredLoadReportEntries.keySet());
                missing.removeAll(approved);
                throw new java.io.IOException("A verified LOAD report bundle contains files that cannot be published: " + missing.size());
            }
            // ZIP bytes are exactly the validated producer bytes; publication must not rewrite HTML links or scripts.
            List<Entry> entries=new ArrayList<>();total=0;
            if(metrics!=null)metrics.phase("zip_write");
            try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(staging))) {
                for(String relative:approved) {
                    // Both paths contain the exact producer bytes captured in pass 1.
                    // Never reopen mutable Worker output while writing the ZIP.
                    byte[] bytes=memoryExports.remove(relative);
                    if(bytes==null) {
                        bytes=Files.readAllBytes(exports.resolve(relative));
                        if(metrics!=null)metrics.value.temporaryExportReadBytes=add(metrics.value.temporaryExportReadBytes,bytes.length);
                    }
                    boolean report=Set.of("html","htm").contains(extension(relative));
                    if(bytes.length>MAX_ENTRY_BYTES)throw new LimitFailure("Final artifact size exceeds limits","artifact.archive.entryBytes",MAX_ENTRY_BYTES,bytes.length);
                    if(total+bytes.length>MAX_TOTAL_BYTES)throw new LimitFailure("Final artifact size exceeds limits","artifact.archive.totalBytes",MAX_TOTAL_BYTES,total+bytes.length);
                    total+=bytes.length;
                    largestEntryBytes=Math.max(largestEntryBytes,bytes.length);
                    entries.add(new Entry(relative,mediaType(relative),bytes.length,sha256(bytes),"textual-output","preserved",report,true));
                    writeEntry(zip,relative,bytes);
                    if(metrics!=null){metrics.value.includedEntries++;metrics.value.includedBytes+=bytes.length;}
                    if(progress!=null)progress.entryWritten(relative);
                }
                Map<String,Object> manifest=new LinkedHashMap<>();
                manifest.put("version",1);manifest.put("policy","att-export-approved-bytes-v1");manifest.put("entries",entries);manifest.put("complete",omitted==0);
                manifest.put("omissions",omitted==0?List.of():List.of(Map.of("count",omitted,"reason",OMISSION_REASON)));
                if(omitted>0){
                    Map<String,Object> notice=entryCapOmissions>0
                            ? LimitDiagnostic.create("ATT-ARTIFACT-OMITTED","Some output entries exceeded the bounded ZIP entry budget",
                                    "artifact.archive.entries",MAX_ENTRIES,(long)MAX_ENTRIES+entryCapOmissions,
                                    "server.artifactPublication",LimitOutcome.ARTIFACT_OMITTED,"publication",
                                    "Limit retained evidence or split LOAD output into multiple runs.","archive.zip")
                            : oversizedOmission>0
                            ? LimitDiagnostic.create("ATT-ARTIFACT-OMITTED","Some output files exceeded the per-entry publication limit",
                                    "artifact.archive.entryBytes",MAX_ENTRY_BYTES,oversizedOmission,"server.artifactPublication",
                                    LimitOutcome.ARTIFACT_OMITTED,"publication","Use smaller evidence files or split detailed output across bounded artifacts.","archive.zip")
                            : LimitDiagnostic.create("ATT-ARTIFACT-OMITTED","Some output files were omitted by the safe publication policy",
                                    "artifact.archivePolicy",null,(long)omitted,"server.artifactPublication",LimitOutcome.ARTIFACT_OMITTED,
                                    "publication","Review artifact policy and retain only approved, bounded text evidence.","archive.zip");
                    manifest.put("diagnostics",List.of(notice));
                }else manifest.put("diagnostics",List.of());
                byte[] bytes=JSON.writeValueAsBytes(manifest);
                if(bytes.length>MAX_MANIFEST_BYTES)throw new LimitFailure("Artifact manifest exceeds limits","artifact.archive.manifestBytes",MAX_MANIFEST_BYTES,bytes.length);
                writeEntry(zip,MANIFEST,bytes);
            }
            if(metrics!=null)metrics.phase("verification_fsync");
            verify(staging,true);
            try(var channel=java.nio.channels.FileChannel.open(staging,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(staging,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            published=true;
            if(metrics!=null){metrics.value.zipBytes=Files.size(destination);usage(metrics.value.limitUsage,"artifact.archive.entries",entries.size(),MAX_ENTRIES);usage(metrics.value.limitUsage,"artifact.archive.totalBytes",total,MAX_TOTAL_BYTES);usage(metrics.value.limitUsage,"artifact.archive.entryBytes",largestEntryBytes,MAX_ENTRY_BYTES);}
            return destination;
        } catch(Exception failure) {
            if(metrics!=null&&failure instanceof LimitFailure){LimitFailure limited=(LimitFailure)failure;
                if("artifact.inventory.candidates".equals(limited.limitName))metrics.value.candidateCount=limited.observed;
                usage(metrics.value.limitUsage,limited.limitName,limited.observed,limited.limit);}
            if(metrics!=null)metrics.fail(failure instanceof IllegalArgumentException?"policy_limit":"publication_failed");
            throw failure;
        } finally {
            if(metrics!=null)metrics.phase("cleanup");
            if(!published)Files.deleteIfExists(staging);
            try(var paths=Files.walk(exports)){for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
        }
    }

    /** Prune only the established private LOAD bookkeeping namespace. Never follow links. */
    private static List<Path> inventory(Path root,int maxCandidates) throws java.io.IOException {
        List<Path> candidates = new ArrayList<>();
        Files.walkFileTree(root, Set.of(), 64, new java.nio.file.SimpleFileVisitor<Path>() {
            private void count(Path path) {
                if (candidates.size() >= maxCandidates)
                    throw new LimitFailure("Artifact inventory exceeds limits","artifact.inventory.candidates",maxCandidates,maxCandidates+1L);
                candidates.add(path);
            }
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path directory,
                    java.nio.file.attribute.BasicFileAttributes attributes) {
                Path relative = root.relativize(directory);
                // Exact spelling and position: aliases, arbitrary hidden folders
                // and symlinks remain untrusted candidates with the usual bounds.
                if (relative.getNameCount() == 3 && "load".equals(relative.getName(0).toString())
                        && ".exec-id-reservations".equals(relative.getName(2).toString()))
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                count(directory);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attributes) {
                if (attributes.isDirectory()) throw new IllegalArgumentException("Artifact directory depth exceeds limits");
                count(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        // Keep primary LOAD reports and their referenced detail sidecars ahead of optional evidence.
        // Surplus evidence is omitted rather than making the entire report ZIP unavailable.
        candidates.sort(Comparator.comparingInt((Path path) -> archivePriority(root.relativize(path).toString().replace('\\','/')))
                .thenComparing(path -> root.relativize(path).toString()));
        return candidates;
    }

    private static int archivePriority(String path) {
        if (path.endsWith("/load-summary.json") || path.endsWith("/load-summary.yaml")
                || path.endsWith("/report/index.html") || path.endsWith("/report-commit-manifest.json")
                || path.endsWith("/" + LOAD_REPORT_MANIFEST)) return 0;
        if (path.contains("/details/v1/")) return 1;
        if (path.endsWith("/configuration.json")) return 2;
        return 3;
    }

    /** Verify the Engine's committed LOAD bundle before any of its entries enter the ZIP. */
    private static Map<String,ReportFile> validateLoadReportBundles(Path root,List<Path> candidates) throws Exception {
        Set<String> candidateNames=new HashSet<>();
        Set<String> runIds=new HashSet<>();
        for(Path candidate:candidates) {
            String relative=root.relativize(candidate).toString().replace('\\','/');
            candidateNames.add(relative);
            String[] parts=relative.split("/");
            if(parts.length==3&&"load".equals(parts[0])
                    &&("load-summary.json".equals(parts[2])||LOAD_REPORT_MANIFEST.equals(parts[2])))runIds.add(parts[1]);
        }
        Map<String,ReportFile> required=new LinkedHashMap<>();
        for(String runId:runIds) {
            if(!safePath(runId))throw new java.io.IOException("Unsafe LOAD run identifier in report inventory.");
            String prefix="load/"+runId+"/";
            String manifestName=prefix+LOAD_REPORT_MANIFEST;
            if(!candidateNames.contains(manifestName))
                throw new java.io.IOException("LOAD report bundle is missing its completion manifest.");
            Path runDirectory=root.resolve("load").resolve(runId).normalize();
            Path manifestPath=runDirectory.resolve(LOAD_REPORT_MANIFEST);
            if(!manifestPath.startsWith(root)||Files.isSymbolicLink(manifestPath)
                    ||!Files.isRegularFile(manifestPath,LinkOption.NOFOLLOW_LINKS)||Files.size(manifestPath)>MAX_ENTRY_BYTES)
                throw new java.io.IOException("LOAD report completion manifest is not a bounded regular file.");
            byte[] manifestBytes=readProducerBytes(manifestPath);
            com.fasterxml.jackson.databind.JsonNode manifest=JSON.readTree(manifestBytes);
            if(!LOAD_REPORT_MANIFEST_SCHEMA.equals(manifest.path("schemaVersion").asText())
                    ||!"COMPLETE".equals(manifest.path("state").asText())
                    ||!manifest.path("complete").asBoolean(false)
                    ||!runId.equals(manifest.path("runId").asText()))
                throw new java.io.IOException("LOAD report completion manifest is invalid or incomplete.");
            com.fasterxml.jackson.databind.JsonNode fileRows=manifest.path("files");
            if(!fileRows.isArray()||fileRows.isEmpty()||fileRows.size()>9000)
                throw new java.io.IOException("LOAD report completion manifest has an invalid file inventory.");
            Set<String> listed=new HashSet<>();
            long total=0L;
            for(com.fasterxml.jackson.databind.JsonNode row:fileRows) {
                String name=row.path("path").asText("");
                long length=row.path("lengthBytes").asLong(-1L);
                String digest=row.path("sha256").asText("");
                if(!safePath(name)||!name.startsWith("details/")&&!name.startsWith("report/")
                        &&!Set.of("load-summary.json","load-summary.yaml").contains(name)
                        ||!listed.add(name)||!digest.matches("[0-9a-f]{64}")||length<0||length>MAX_ENTRY_BYTES)
                    throw new java.io.IOException("LOAD report completion manifest contains an unsafe or duplicate file entry.");
                if(total>MAX_TOTAL_BYTES-length)throw new java.io.IOException("LOAD report bundle exceeds the archive byte limit.");
                total+=length;
                Path file=runDirectory.resolve(name).normalize();
                String archiveName=prefix+name;
                if(!file.startsWith(runDirectory)||Files.isSymbolicLink(file)
                        ||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!candidateNames.contains(archiveName))
                    throw new java.io.IOException("A LOAD report file is missing from the bounded archive inventory.");
                byte[] producerBytes=readProducerBytes(file);
                if(producerBytes.length!=length||!digest.equals(sha256(producerBytes)))
                    throw new java.io.IOException("A LOAD report file does not match its completion manifest.");
                required.put(archiveName,new ReportFile(length,digest));
            }
            Set<String> essential=Set.of("load-summary.json","load-summary.yaml","report/index.html","details/v1/manifest.json");
            if(!listed.containsAll(essential))throw new java.io.IOException("LOAD report bundle is missing an essential report file.");
            com.fasterxml.jackson.databind.JsonNode summary=JSON.readTree(runDirectory.resolve("load-summary.json").toFile());
            if(!runId.equals(summary.path("runId").asText())
                    ||!summary.path("executionStatus").isTextual()
                    ||!summary.path("performanceVerdict").isTextual()
                    ||summary.path("durationMs").asLong(-1L)!=manifest.path("durationMs").asLong(-2L)
                    ||!summary.path("executionStatus").asText().equals(manifest.path("executionStatus").asText())
                    ||!summary.path("performanceVerdict").asText().equals(manifest.path("performanceVerdict").asText())
                    ||!LOAD_REPORT_MANIFEST.equals(summary.path("reportManifest").asText())
                    ||!"report/index.html".equals(summary.path("report").asText()))
                throw new java.io.IOException("LOAD summary does not reference its committed report bundle.");
            com.fasterxml.jackson.databind.JsonNode details=JSON.readTree(runDirectory.resolve("details/v1/manifest.json").toFile());
            if(!details.path("complete").asBoolean(false))
                throw new java.io.IOException("LOAD detailed metrics manifest is incomplete.");
            required.put(manifestName,new ReportFile(manifestBytes.length,sha256(manifestBytes)));
        }
        return required;
    }

    private static byte[] readProducerBytes(Path file) throws java.io.IOException {
        byte[] bytes;
        try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes((int)MAX_ENTRY_BYTES+1);}
        if(bytes.length>MAX_ENTRY_BYTES)throw new IllegalArgumentException("Artifact exceeds the per-entry text limit (16 MiB)");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if(text.indexOf('\0')>=0)throw new IllegalArgumentException("Binary artifacts cannot be exported");
        } catch(CharacterCodingException invalid) {
            throw new IllegalArgumentException("Binary artifacts cannot be exported");
        }
        return bytes;
    }

    private static void usage(Map<String,Map<String,Object>> target,String name,long value,long limit){
        Map<String,Object> row=new LinkedHashMap<>();row.put("usage",value);row.put("limit",limit);row.put("threshold",0.8d);target.put(name,row);
    }

    static final class LimitFailure extends IllegalArgumentException {
        final String limitName;final long limit,observed;
        LimitFailure(String message,String limitName,long limit,long observed){super(message);this.limitName=limitName;this.limit=limit;this.observed=observed;}
    }

    static byte[] read(Path archive, String requested) throws Exception {
        try (VerifiedRead read = openVerified(archive, false)) { return read.read(requested); }
    }

    /** Request-scoped handle: verify structure once, pin that ZIP, check every requested payload. */
    static final class VerifiedRead implements AutoCloseable {
        private final ZipFile zip;
        private final Map<String, Map<?,?>> entries;
        final Map<String,Object> manifest;
        private VerifiedRead(ZipFile zip, Map<String,Object> manifest) {
            this.zip = zip;
            Map<String,Map<?,?>> indexed = new java.util.HashMap<>();
            List<Map<?,?>> rows = new ArrayList<>();
            for (Object value : (List<?>) manifest.get("entries")) {
                Map<?,?> row = Map.copyOf((Map<?,?>) value);
                rows.add(row);
                indexed.put((String) row.get("path"), row);
            }
            entries = Map.copyOf(indexed);
            Map<String,Object> snapshot = new LinkedHashMap<>(manifest);
            snapshot.put("entries", List.copyOf(rows));
            snapshot.put("omissions", ((List<?>) manifest.get("omissions")).stream().map(value -> Map.copyOf((Map<?,?>) value)).toList());
            Object diagnostics = manifest.get("diagnostics");
            if (diagnostics instanceof List<?> notices)
                snapshot.put("diagnostics", notices.stream().map(value -> Map.copyOf((Map<?,?>) value)).toList());
            this.manifest = Map.copyOf(snapshot);
        }
        byte[] read(String requested) throws Exception {
            if (!safePath(requested) || MANIFEST.equals(requested)) throw new IllegalArgumentException("Invalid archive entry path");
            Map<?,?> approved = entries.get(requested);
            if (approved == null) throw new java.io.FileNotFoundException();
            ZipEntry entry = zip.getEntry(requested);
            if (entry == null || entry.isDirectory() || entry.getSize() < 0 || entry.getSize() > MAX_ENTRY_BYTES) throw new java.io.FileNotFoundException();
            try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes((int) MAX_ENTRY_BYTES + 1);
                if (bytes.length > MAX_ENTRY_BYTES || bytes.length != entry.getSize() || !sha256(bytes).equals(approved.get("sha256")) || !validCrc(entry,bytes)) throw new IllegalArgumentException("Archive entry exceeds limits or is corrupt");
                return bytes;
            }
        }
        @Override public void close() throws java.io.IOException { zip.close(); }
    }

    static VerifiedRead openVerified(Path archive, boolean digests) throws Exception {
        var before = Files.readAttributes(archive, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.isSymbolicLink() || !before.isRegularFile() || before.size() > MAX_ARCHIVE_BYTES)
            throw new IllegalArgumentException("Invalid artifact archive");
        ArtifactZipStructure.verify(archive);
        ZipFile zip = new ZipFile(archive.toFile());
        try {
            Map<String,Object> manifest = verifyManifest(zip, digests);
            var after = Files.readAttributes(archive, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey()))
                throw new IllegalArgumentException("Artifact archive changed during verification");
            return new VerifiedRead(zip, manifest);
        } catch (Exception failure) { zip.close(); throw failure; }
    }

    /** Validate the entire directory against the bounded manifest before trusting any entry. */
    static Map<String,Object> verify(Path archive, boolean digests) throws Exception {
        try (VerifiedRead read = openVerified(archive, digests)) { return read.manifest; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> verifyManifest(ZipFile zip, boolean digests) throws Exception {
        Set<String> names = new HashSet<>();
        long total = 0;
        int count = 0;
        var directory = zip.entries();
        while (directory.hasMoreElements()) {
            ZipEntry entry = directory.nextElement();
            long cap = MANIFEST.equals(entry.getName()) ? MAX_MANIFEST_BYTES : MAX_ENTRY_BYTES;
            if (++count > MAX_ENTRIES + 1 || !safePath(entry.getName()) || entry.isDirectory()
                    || !names.add(entry.getName().toLowerCase(Locale.ROOT)) || entry.getSize() < 0 || entry.getSize() > cap
                    || entry.getCompressedSize() < 0 || entry.getSize() > Math.max(1, entry.getCompressedSize()) * 200L)
                throw new IllegalArgumentException("Unsafe archive directory");
            if (!MANIFEST.equals(entry.getName())) total += entry.getSize();
            if (total > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Archive total exceeds limits");
        }
        ZipEntry manifestEntry = zip.getEntry(MANIFEST);
        if (manifestEntry == null) throw new IllegalArgumentException("Archive manifest is missing");
        Map<String,Object> manifest;
        try (InputStream input = zip.getInputStream(manifestEntry)) {
            byte[] bytes = input.readNBytes(MAX_MANIFEST_BYTES + 1);
            countVerificationRead(bytes.length);
            if (bytes.length != manifestEntry.getSize() || !validCrc(manifestEntry,bytes)) throw new IllegalArgumentException("Invalid manifest size");
            try { manifest = JSON.readValue(bytes, Map.class); }
            catch (com.fasterxml.jackson.core.JacksonException invalid) { throw new IllegalArgumentException("Invalid archive manifest"); }
        }
        Set<String> baseKeys = Set.of("version", "policy", "entries", "complete", "omissions");
        Set<String> diagnosticKeys = Set.of("version", "policy", "entries", "complete", "omissions", "diagnostics");
        if (manifest == null || !(manifest.keySet().equals(baseKeys) || manifest.keySet().equals(diagnosticKeys))
                || !Integer.valueOf(1).equals(manifest.get("version")) || !validOmissions(manifest)
                || !validDiagnostics(manifest.get("diagnostics"))
                || !Set.of("att-export-safe-text-v2","att-export-approved-bytes-v1").contains(manifest.get("policy")) || !(manifest.get("entries") instanceof List<?> entries)
                || entries.size() != count - 1) throw new IllegalArgumentException("Invalid archive manifest");
        Set<String> approved = new HashSet<>();
        for (Object value : entries) {
            if (!(value instanceof Map<?,?> row) || !row.keySet().equals(Set.of("path", "mediaType", "size", "sha256", "classification", "decision", "report", "preview"))
                    || !(row.get("path") instanceof String path) || !safePath(path)
                    || MANIFEST.equalsIgnoreCase(path) || !approved.add(path.toLowerCase(Locale.ROOT)) || !SAFE_EXTENSIONS.contains(extension(path)))
                throw new IllegalArgumentException("Invalid manifest entry");
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || !integral(row.get("size")) || ((Number) row.get("size")).longValue() != entry.getSize()
                    || !(row.get("sha256") instanceof String digest) || !digest.matches("[a-f0-9]{64}")
                    || !mediaType(path).equals(row.get("mediaType")) || !"textual-output".equals(row.get("classification"))
                    || !(("att-export-approved-bytes-v1".equals(manifest.get("policy")) && "preserved".equals(row.get("decision")))
                        || ("att-export-safe-text-v2".equals(manifest.get("policy")) && "sanitized-once".equals(row.get("decision"))))
                    || !Boolean.TRUE.equals(row.get("preview"))
                    || !Boolean.valueOf(Set.of("html","htm").contains(extension(path))).equals(row.get("report")))
                throw new IllegalArgumentException("Archive does not match manifest");
            if (digests) try (InputStream input = zip.getInputStream(entry)) {
                byte[] bytes = input.readNBytes((int)MAX_ENTRY_BYTES + 1);
                countVerificationRead(bytes.length);
                if (bytes.length != entry.getSize() || !sha256(bytes).equals(digest) || !validCrc(entry,bytes)) throw new IllegalArgumentException("Archive integrity check failed");
            }
        }
        return manifest;
    }

    private static boolean integral(Object value) { return value instanceof Integer || value instanceof Long; }

    private static boolean validOmissions(Map<String, Object> manifest) {
        if (!(manifest.get("complete") instanceof Boolean complete) || !(manifest.get("omissions") instanceof List<?> omissions)) return false;
        if (complete) return omissions.isEmpty();
        if (omissions.size() != 1 || !(omissions.get(0) instanceof Map<?, ?> omission)
                || !omission.keySet().equals(Set.of("count", "reason")) || !OMISSION_REASON.equals(omission.get("reason"))
                || !integral(omission.get("count"))) return false;
        long count = ((Number) omission.get("count")).longValue();
        return count > 0 && count <= MAX_CANDIDATES;
    }

    private static boolean validDiagnostics(Object value) {
        if (value == null) return true; // Read older v1 manifests written before notices were added.
        if (!(value instanceof List<?> notices) || notices.size() > 1) return false;
        if (notices.isEmpty()) return true;
        Object item = notices.get(0);
        if (!(item instanceof Map<?, ?> notice)) return false;
        Set<String> fields = Set.of("code", "summary", "limitName", "configuredLimit", "limit", "observed",
                "actual", "usage", "scope", "outcome", "stage", "remediationHint", "artifactRef");
        if (!fields.containsAll(notice.keySet()) || !(notice.get("code") instanceof String code)
                || !code.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || !(notice.get("summary") instanceof String summary) || summary.length() > 512
                || !"ARTIFACT_OMITTED".equals(notice.get("outcome"))) return false;
        for (String key : new String[] {"limitName", "scope", "stage"})
            if (notice.containsKey(key) && (!(notice.get(key) instanceof String text)
                    || !text.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}"))) return false;
        for (String key : new String[] {"configuredLimit", "limit", "observed", "actual", "usage"})
            if (notice.containsKey(key) && !(notice.get(key) instanceof Number)) return false;
        if (notice.containsKey("remediationHint")
                && (!(notice.get("remediationHint") instanceof String hint) || hint.length() > 256
                || hint.contains("/") || hint.contains("\\"))) return false;
        if (notice.containsKey("artifactRef") && (!(notice.get("artifactRef") instanceof String ref)
                || !ref.matches("[A-Za-z0-9._/-]{1,256}") || ref.contains(".."))) return false;
        return true;
    }

    private static void writeEntry(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
        // STORED makes size/ratio limits deterministic even for highly repetitive legitimate logs.
        var crc = new java.util.zip.CRC32(); crc.update(bytes);
        ZipEntry entry = new ZipEntry(path); entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length); entry.setCompressedSize(bytes.length); entry.setCrc(crc.getValue());
        zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
        ArtifactMetricsRecorder metrics=ArtifactMetricsRecorder.CURRENT.get();
        if(metrics!=null)metrics.value.zipWriteInputBytes=add(metrics.value.zipWriteInputBytes,bytes.length);
    }

    private static void countVerificationRead(long bytes) {
        ArtifactMetricsRecorder metrics=ArtifactMetricsRecorder.CURRENT.get();
        if(metrics!=null)metrics.value.verificationBytesRead=add(metrics.value.verificationBytesRead,bytes);
    }

    private static long add(Long current,long next) {
        long prior=current==null?0:current;
        return next>Long.MAX_VALUE-prior?Long.MAX_VALUE:prior+next;
    }

    private static boolean validCrc(ZipEntry entry,byte[] bytes) {
        var crc=new java.util.zip.CRC32();crc.update(bytes);return crc.getValue()==entry.getCrc();
    }

    static boolean safePath(String name) {
        if (name == null || name.isBlank() || name.length() > 2048 || name.startsWith("/") || !java.text.Normalizer.isNormalized(name,java.text.Normalizer.Form.NFC) || name.indexOf('\\') >= 0 || name.indexOf('%') >= 0 || name.indexOf(':') >= 0 || name.indexOf('?') >= 0 || name.indexOf('#') >= 0 || name.chars().anyMatch(c -> c < 32 || c == 127 || "<>\"|*".indexOf(c)>=0)) return false;
        for (String part : name.split("/", -1)) if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ") || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")) return false;
        return true;
    }
    private static String extension(String path) { int dot = path.lastIndexOf('.'); return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT); }
    private static String mediaType(String path) { return switch (extension(path)) { case "html", "htm" -> "text/html"; case "json" -> "application/json"; case "yaml", "yml" -> "application/yaml"; case "xml" -> "application/xml"; case "css" -> "text/css"; case "csv" -> "text/csv"; case "md" -> "text/markdown"; default -> "text/plain"; }; }
    static String sha256(byte[] bytes) throws Exception { byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format("%02x", b)); return out.toString(); }
}
