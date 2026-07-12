/* Author: Jeffrey + ChatGPT */
package att.report;

import att.core.IdentifierValidator;
import att.config.YamlSupport;
import att.Version;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/** Builds a verified tar.gz for the latest completed V2 run. */
public final class RunArchiveBuilder {
    @SuppressWarnings("unchecked")
    public Path build(Path projectRoot, Path outputRoot) throws Exception {
        Path latest = outputRoot.resolve("latest-run.yaml");
        if (!Files.exists(latest)) throw new IllegalArgumentException("Latest run does not exist: " + latest);
        Object loaded = YamlSupport.parser().load(new String(Files.readAllBytes(latest), "UTF-8"));
        if (!(loaded instanceof Map) || !"att-latest-run/v2.1".equals(String.valueOf(((Map<?, ?>) loaded).get("schemaVersion")))) throw new IllegalArgumentException("Invalid latest run pointer");
        String runId = String.valueOf(((Map<?, ?>) loaded).get("runId"));
        IdentifierValidator.runId(runId);
        Path runDir = IdentifierValidator.strictExistingChild(outputRoot, runId, "Archive run directory");
        if (!Files.isDirectory(runDir)) throw new IllegalArgumentException("Run directory does not exist: " + runDir);
        Object manifestObject = YamlSupport.parser().load(new String(Files.readAllBytes(runDir.resolve("run.yaml")), "UTF-8"));
        if (!(manifestObject instanceof Map) || !"att-run/v2.1".equals(String.valueOf(((Map<?, ?>) manifestObject).get("schemaVersion")))) throw new IllegalArgumentException("Run manifest is not V2.1: " + runId);
        Object runNode = ((Map<?, ?>) manifestObject).get("run");
        if (!(runNode instanceof Map) || !"COMPLETE".equals(String.valueOf(((Map<?, ?>) runNode).get("state")))) throw new IllegalArgumentException("Latest run is not COMPLETE");
        String expectedManifestHash = String.valueOf(((Map<?, ?>) loaded).get("manifestSha256"));
        if (expectedManifestHash.length() != 64 || !expectedManifestHash.equals(sha256(runDir.resolve("run.yaml")))) throw new IllegalArgumentException("Latest run manifest hash does not match: " + runId);
        Path dist = projectRoot.resolve("build");
        Files.createDirectories(dist);
        Path stagingRoot = outputRoot.resolve(".in-progress"); Files.createDirectories(stagingRoot);
        Path stagedRun = stagingRoot.resolve("archive-" + runId + "-" + java.util.UUID.randomUUID().toString());
        copyTree(runDir, stagedRun);
        snapshotPackage(projectRoot, stagedRun, (Map<?, ?>) manifestObject);
        Path archive = dist.resolve("att-run-" + runId + ".tar.gz");
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(stagedRun)) { stream.filter(Files::isRegularFile).filter(path -> !Files.isSymbolicLink(path)).sorted().forEach(files::add); }
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        for (Path file : files) hashes.put(stagedRun.relativize(file).toString().replace('\\', '/'), sha256(file));
        Path manifest = stagedRun.resolve("MANIFEST.yaml");
        Map<String, Object> manifestData = new LinkedHashMap<String, Object>();
        manifestData.put("schemaVersion", "att-archive/v2.1"); manifestData.put("runId", runId); manifestData.put("files", hashes);
        Files.write(manifest, new Yaml().dump(manifestData).getBytes("UTF-8"));
        for (Map.Entry<String, String> expected : hashes.entrySet()) {
            Path file = stagedRun.resolve(expected.getKey()).normalize();
            if (!file.startsWith(stagedRun) || !expected.getValue().equals(sha256(file))) throw new IllegalStateException("Archive staging hash verification failed: " + expected.getKey());
        }
        files.add(manifest);
        try (OutputStream raw = Files.newOutputStream(archive); GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
            for (Path file : files) writeTarEntry(gzip, stagedRun.relativize(file).toString().replace('\\', '/'), file);
            gzip.write(new byte[1024]);
        }
        GeneratedOutputCleaner.deleteDirectory(stagedRun);
        return archive;
    }

    private void copyTree(Path source, Path target) throws Exception {
        try (Stream<Path> stream = Files.walk(source)) {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path item = iterator.next(), destination = target.resolve(source.relativize(item));
                if (Files.isSymbolicLink(item)) throw new IllegalArgumentException("Run evidence contains a symbolic link: " + source.relativize(item));
                if (Files.isDirectory(item)) Files.createDirectories(destination); else { Files.createDirectories(destination.getParent()); Files.copy(item, destination); }
            }
        }
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) { byte[] b = new byte[8192]; int n; while ((n = input.read(b)) >= 0) digest.update(b, 0, n); }
        StringBuilder result = new StringBuilder(); for (byte b : digest.digest()) result.append(String.format("%02x", b & 0xff)); return result.toString();
    }

    private void snapshotPackage(Path projectRoot, Path runDir, Map<?, ?> runManifest) throws Exception {
        Path canonicalProject = projectRoot.toRealPath(), snapshot = runDir.resolve("package-inputs");
        Object inputs = runManifest.get("inputs");
        if (!(inputs instanceof Iterable)) throw new IllegalArgumentException("Run manifest inputs are required for archive reproduction");
        for (Object input : (Iterable<?>) inputs) {
            if (!(input instanceof Map) || ((Map<?, ?>) input).get("path") == null) throw new IllegalArgumentException("Invalid run manifest input entry");
            String relative = String.valueOf(((Map<?, ?>) input).get("path"));
            Path relativePath = att.core.IdentifierValidator.relativePath(relative, "manifest input path");
            Path source = canonicalProject.resolve(relativePath).normalize();
            if (!source.startsWith(canonicalProject) || Files.isSymbolicLink(source) || !Files.isRegularFile(source)) throw new IllegalArgumentException("Missing/unsafe manifest input for archive: " + relative);
            String expected = String.valueOf(((Map<?, ?>) input).get("sha256"));
            if (expected.length() != 64 || !expected.equals(sha256(source))) throw new IllegalArgumentException("Manifest input hash no longer matches: " + relative);
            Path target = snapshot.resolve(relativePath).normalize();
            if (!target.startsWith(snapshot)) throw new IllegalArgumentException("Unsafe archive snapshot target: " + relative);
            String lower = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".txt") || lower.endsWith(".sh")) copyRedacted(source, target);
            else { Files.createDirectories(target.getParent()); Files.copy(source, target); }
        }
        Files.write(runDir.resolve("README.txt"), (Version.DISPLAY + " completed run package\nOpen report/index.html in a browser.\nVerify files against MANIFEST.yaml before use.\n").getBytes("UTF-8"));
    }

    private void copyRedacted(Path source, Path target) throws Exception {
        if (!Files.isRegularFile(source)) return;
        Files.createDirectories(target.getParent());
        StringBuilder output = new StringBuilder();
        for (String line : Files.readAllLines(source, java.nio.charset.StandardCharsets.UTF_8)) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.matches("^\\s*(password|token|secret|authorization)\\s*:.*$")) {
                output.append(line.substring(0, line.indexOf(':') + 1)).append(" '***REDACTED***'");
            } else output.append(line);
            output.append('\n');
        }
        Files.write(target, output.toString().getBytes("UTF-8"));
    }

    private void writeTarEntry(OutputStream output, String name, Path file) throws Exception {
        Path entry = Paths.get(name).normalize();
        if (entry.isAbsolute() || entry.startsWith("..") || name.indexOf('\\') >= 0) throw new IllegalArgumentException("Unsafe archive entry: " + name);
        byte[] content = Files.readAllBytes(file);
        byte[] header = new byte[512];
        put(header, 0, 100, name);
        putOctal(header, 100, 8, 0644);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, content.length);
        putOctal(header, 136, 12, Files.getLastModifiedTime(file).toMillis() / 1000);
        for (int i = 148; i < 156; i++) header[i] = 32;
        header[156] = '0';
        put(header, 257, 6, "ustar");
        put(header, 263, 2, "00");
        long sum = 0; for (byte b : header) sum += b & 0xff;
        putOctal(header, 148, 8, sum);
        output.write(header); output.write(content);
        int padding = (512 - (content.length % 512)) % 512;
        if (padding > 0) output.write(new byte[padding]);
    }
    private void put(byte[] target, int offset, int length, String value) throws Exception {
        byte[] bytes = value.getBytes("UTF-8");
        if (bytes.length > length) throw new IllegalArgumentException("Archive field too long: " + value);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }
    private void putOctal(byte[] target, int offset, int length, long value) throws Exception {
        String octal = Long.toOctalString(value);
        StringBuilder padded = new StringBuilder();
        for (int i = octal.length(); i < length - 1; i++) padded.append('0');
        padded.append(octal).append('\0');
        put(target, offset, length, padded.toString());
    }
}
