package att.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bounded Package-local script dependency closure; no new script execution/security policy. */
public final class PackageToolScripts {
    private static final int MAX_REFERENCES = 512;
    private static final long MAX_SOURCE_BYTES = 8L * 1024L * 1024L;

    private PackageToolScripts() { }

    /** Report all statically referenced Package script files from dispatcher AND member argv. */
    public static List<Path> referenced(Path packageRoot, ToolConfig tool) throws IOException {
        Set<Path> result = new LinkedHashSet<Path>();
        append(packageRoot, tool.groupScriptArgv(), result);
        append(packageRoot, tool.commandArgv(), result);
        List<Path> ordered = new ArrayList<Path>(result);
        Collections.sort(ordered);
        return Collections.unmodifiableList(ordered);
    }

    /** Also usable while hashing descriptor source, before resolving a full Tool config. */
    public static List<Path> referencedArgv(Path packageRoot, Object raw) throws IOException {
        if (raw == null) return Collections.emptyList();
        List<String> argv = new ArrayList<String>();
        if (raw instanceof String) {
            try { argv.addAll(att.exec.CommandRunner.parseCommand((String) raw)); }
            catch (RuntimeException error) { throw new IOException("Invalid Tool script argv", error); }
        } else if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (!(item instanceof String)) throw new IOException("Tool script argv is not textual");
                argv.add((String) item);
            }
        } else throw new IOException("Tool script argv must be a command or list");
        Set<Path> result = new LinkedHashSet<Path>();
        append(packageRoot, argv, result);
        List<Path> ordered = new ArrayList<Path>(result);
        Collections.sort(ordered);
        return Collections.unmodifiableList(ordered);
    }

    private static void append(Path packageRoot, List<String> argv, Set<Path> result) throws IOException {
        if (argv == null) return;
        Path root = packageRoot.toAbsolutePath().normalize();
        for (int i = 0; i < argv.size(); i++) {
            String token = argv.get(i);
            if (token == null || token.isEmpty() || token.contains("${")
                    || token.contains("#{") || token.contains("&{")) continue;
            final Path supplied;
            try { supplied = Paths.get(token); }
            catch (RuntimeException invalid) { continue; }
            boolean explicit = token.startsWith("./") || token.startsWith("../") || token.startsWith("tools/");
            boolean insideAbsolute = supplied.isAbsolute() && supplied.normalize().startsWith(root);
            if (!explicit && !insideAbsolute) continue;
            String lower = token.toLowerCase(java.util.Locale.ROOT);
            // An explicit executable at argv[0] need not have a suffix. Later argv are
            // considered script references only when their suffix identifies script code.
            boolean script = i == 0 || lower.endsWith(".sh") || lower.endsWith(".py")
                    || lower.endsWith(".bat") || lower.endsWith(".cmd") || lower.endsWith(".ps1")
                    || lower.endsWith(".rb") || lower.endsWith(".js") || lower.endsWith(".pl");
            if (!script) continue;
            Path candidate = (supplied.isAbsolute() ? supplied : root.resolve(supplied)).normalize();
            if (!candidate.startsWith(root))
                throw new IOException("Package Tool script dependency escapes its Package root");
            Path part = root;
            for (Path name : root.relativize(candidate)) {
                part = part.resolve(name);
                if (Files.isSymbolicLink(part))
                    throw new IOException("Symlink in Package Tool script dependency");
            }
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Package Tool script dependency is not a regular file");
                if (Files.size(candidate) > MAX_SOURCE_BYTES)
                    throw new IOException("Package Tool script dependency exceeds 8 MiB limit");
            }
            result.add(candidate);
            if (result.size() > MAX_REFERENCES)
                throw new IOException("Package Tool script dependency count exceeds " + MAX_REFERENCES);
        }
    }
}
