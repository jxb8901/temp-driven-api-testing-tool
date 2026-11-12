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

    /**
     * Rebase statically-authored Package-local script operands so execution does
     * not depend on the process working directory. {@code sourceTokens} maps
     * each expanded argv value back to its authored Tool token; this prevents a
     * runtime argument that happens to look like a Package path from being
     * rewritten.
     */
    public static List<String> resolveLocalScriptOperands(Path packageRoot, List<String> argv,
            List<String> sourceTokens) throws IOException {
        if (argv == null || argv.isEmpty()) return Collections.emptyList();
        if (sourceTokens == null || sourceTokens.size() != argv.size())
            throw new IllegalArgumentException("Expanded Tool argv source mapping is inconsistent");
        Path root = packageRoot.toAbsolutePath().normalize();
        List<String> resolved = new ArrayList<String>(argv);
        int interpreterOperand = interpreterScriptOperand(argv);
        for (int index : new int[] {0, interpreterOperand}) {
            if (index < 0 || index >= argv.size()) continue;
            String source = sourceTokens.get(index);
            String value = argv.get(index);
            if (source == null || value == null || source.isEmpty() || !source.equals(value)
                    || source.contains("${") || source.contains("#{") || source.contains("&{")) continue;
            Path authored;
            try { authored = Paths.get(source); }
            catch (RuntimeException invalid) { continue; }
            if (authored.isAbsolute()) {
                // Absolute Package-local paths already work from any cwd.
                if (authored.normalize().startsWith(root)) continue;
                continue;
            }
            if (!(source.startsWith("./") || source.startsWith("../") || source.startsWith("tools/"))) continue;
            Path candidate = root.resolve(authored).normalize();
            if (!candidate.startsWith(root))
                throw new IOException("Package Tool script operand escapes its Package root: " + source);
            resolved.set(index, candidate.toString());
        }
        return Collections.unmodifiableList(resolved);
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

    /**
     * Only ordinary Package files can be read for a revision. Reject every symlink
     * component, including a link in a parent directory, without following its target.
     */
    public static boolean isSafeRegularScript(Path packageRoot, Path candidate) throws IOException {
        Path root = packageRoot.toAbsolutePath().normalize();
        Path path = candidate.toAbsolutePath().normalize();
        if (!path.startsWith(root))
            throw new IOException("Package Tool script dependency escapes its Package root");
        Path current = root;
        for (Path name : root.relativize(path)) {
            current = current.resolve(name);
            if (Files.isSymbolicLink(current)) return false;
        }
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * For known interpreters the first non-option operand is a script file even
     * when it has no extension (for example: bash ./tools/task).
     * Inline-code modes such as bash -c or python -m have no script operand.
     */
    private static int interpreterScriptOperand(List<String> argv) {
        if (argv == null || argv.isEmpty()) return -1;
        int firstArg = 1;
        String executable = interpreterName(argv.get(0));
        if ("env".equals(executable) && argv.size() > 1) {
            executable = interpreterName(argv.get(1));
            firstArg = 2;
        }
        if (!isInterpreter(executable)) return -1;
        for (int i = firstArg; i < argv.size(); i++) {
            String argument = argv.get(i);
            if (argument == null || argument.isEmpty()) continue;
            if ("-c".equals(argument) || "-m".equals(argument)
                    || "--eval".equals(argument) || "--command".equals(argument)
                    || ("-e".equals(argument) && ("node".equals(executable)
                        || "nodejs".equals(executable) || "perl".equals(executable)))
                    || argument.matches("-[A-Za-z]*[cm][A-Za-z]*")) return -1;
            if ("--".equals(argument) || argument.startsWith("-")) continue;
            return i;
        }
        return -1;
    }

    private static String interpreterName(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            String name = Paths.get(raw).getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    private static boolean isInterpreter(String name) {
        return "bash".equals(name) || "sh".equals(name) || "dash".equals(name)
                || "zsh".equals(name) || "ksh".equals(name) || "fish".equals(name)
                || "perl".equals(name) || "ruby".equals(name)
                || "node".equals(name) || "nodejs".equals(name)
                || "pwsh".equals(name) || "powershell".equals(name)
                || name.matches("python[0-9]*(?:\\.[0-9]+)?");
    }

    private static void append(Path packageRoot, List<String> argv, Set<Path> result) throws IOException {
        if (argv == null) return;
        Path root = packageRoot.toAbsolutePath().normalize();
        int interpreterOperand = interpreterScriptOperand(argv);
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
            // An explicit executable at argv[0] or a known interpreter's script
            // operand need not have a suffix. Preserve suffix detection elsewhere.
            boolean script = i == 0 || i == interpreterOperand
                    || lower.endsWith(".sh") || lower.endsWith(".py")
                    || lower.endsWith(".bat") || lower.endsWith(".cmd") || lower.endsWith(".ps1")
                    || lower.endsWith(".rb") || lower.endsWith(".js") || lower.endsWith(".pl");
            if (!script) continue;
            Path candidate = (supplied.isAbsolute() ? supplied : root.resolve(supplied)).normalize();
            if (!candidate.startsWith(root))
                throw new IOException("Package Tool script dependency escapes its Package root");
            // Preserve the logical reference without following a symlink. Inspector
            // must still be able to show the Tool YAML as a safe source projection;
            // execution validation separately rejects unsafe local script targets.
            if (isSafeRegularScript(root, candidate)
                    && Files.size(candidate) > MAX_SOURCE_BYTES)
                throw new IOException("Package Tool script dependency exceeds 8 MiB limit");
            result.add(candidate);
            if (result.size() > MAX_REFERENCES)
                throw new IOException("Package Tool script dependency count exceeds " + MAX_REFERENCES);
        }
    }
}
