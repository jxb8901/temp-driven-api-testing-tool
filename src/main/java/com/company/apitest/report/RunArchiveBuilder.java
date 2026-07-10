/* Author: Jeffrey + ChatGPT */
package com.company.apitest.report;

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
        Object loaded = new Yaml().load(new String(Files.readAllBytes(latest), "UTF-8"));
        if (!(loaded instanceof Map) || !"COMPLETE".equals(String.valueOf(((Map<?, ?>) loaded).get("status")))) throw new IllegalArgumentException("Latest run is not COMPLETE");
        String runId = String.valueOf(((Map<?, ?>) loaded).get("runId"));
        Path runDir = Paths.get(String.valueOf(((Map<?, ?>) loaded).get("runDirectory")));
        if (!runDir.isAbsolute()) runDir = projectRoot.resolve(runDir).normalize();
        if (!Files.isDirectory(runDir)) throw new IllegalArgumentException("Run directory does not exist: " + runDir);
        snapshotPackage(projectRoot, runDir);
        Path dist = projectRoot.resolve("dist");
        Files.createDirectories(dist);
        Path archive = dist.resolve("att-" + runId + ".tar.gz");
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(runDir)) { stream.filter(Files::isRegularFile).sorted().forEach(files::add); }
        Map<String, String> hashes = new LinkedHashMap<String, String>();
        for (Path file : files) hashes.put(runDir.relativize(file).toString().replace('\\', '/'), sha256(file));
        Path manifest = runDir.resolve("MANIFEST.yaml");
        Map<String, Object> manifestData = new LinkedHashMap<String, Object>();
        manifestData.put("runId", runId); manifestData.put("files", hashes);
        Files.write(manifest, new Yaml().dump(manifestData).getBytes("UTF-8"));
        files.add(manifest);
        try (OutputStream raw = Files.newOutputStream(archive); GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
            for (Path file : files) writeTarEntry(gzip, runDir.relativize(file).toString().replace('\\', '/'), file);
            gzip.write(new byte[1024]);
        }
        return archive;
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) { byte[] b = new byte[8192]; int n; while ((n = input.read(b)) >= 0) digest.update(b, 0, n); }
        StringBuilder result = new StringBuilder(); for (byte b : digest.digest()) result.append(String.format("%02x", b & 0xff)); return result.toString();
    }

    private void snapshotPackage(Path projectRoot, Path runDir) throws Exception {
        Path snapshot = runDir.resolve("package-config");
        copyRedacted(projectRoot.resolve("config/config.yaml"), snapshot.resolve("config/config.yaml"));
        for (Path root : new Path[]{projectRoot.resolve("testcase"), projectRoot.resolve("templates")}) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                java.util.Iterator<Path> iterator = stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".yaml")).iterator();
                while (iterator.hasNext()) {
                    Path file = iterator.next();
                    copyRedacted(file, snapshot.resolve(projectRoot.relativize(file)));
                }
            }
        }
        Files.write(runDir.resolve("README.txt"), ("ATT V2 completed run package\nOpen report/index.html in a browser.\nVerify files against MANIFEST.yaml before use.\n").getBytes("UTF-8"));
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
