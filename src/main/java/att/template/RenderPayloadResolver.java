package att.template;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Resolves one safe template-relative render glob into deterministic regular files. */
public final class RenderPayloadResolver {
    public List<Path> resolve(Path templateDirectory, String pattern) throws Exception {
        if (pattern == null || pattern.trim().isEmpty()) throw new IllegalArgumentException("Render payload glob must not be blank");
        Path declared = java.nio.file.Paths.get(pattern.replace('/', java.io.File.separatorChar));
        if (declared.isAbsolute()) throw new IllegalArgumentException("Render payload glob must be relative: " + pattern);
        for (Path part : declared) if ("..".equals(part.toString())) throw new IllegalArgumentException("Render payload glob must not contain '..': " + pattern);
        Path canonicalTemplate = templateDirectory.toRealPath();
        String nativePattern = pattern.replace('/', java.io.File.separatorChar);
        PathMatcher matcher;
        try { matcher = FileSystems.getDefault().getPathMatcher("glob:" + nativePattern); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid render payload glob: " + pattern + ": " + e.getMessage(), e); }
        List<Path> matches = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.walk(canonicalTemplate)) {
            java.util.Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext()) {
                Path candidate = iterator.next();
                if (!Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate)) continue;
                Path relative = canonicalTemplate.relativize(candidate);
                if (!matcher.matches(relative)) continue;
                Path canonical = candidate.toRealPath();
                if (!canonical.startsWith(canonicalTemplate)) throw new IllegalArgumentException("Render payload escapes template directory: " + pattern);
                matches.add(canonical);
            }
        }
        Collections.sort(matches, (left, right) -> portable(canonicalTemplate.relativize(left)).compareTo(portable(canonicalTemplate.relativize(right))));
        if (matches.isEmpty()) throw new IllegalArgumentException("Render payload glob matched no files: " + pattern);
        return matches;
    }

    public static String portable(Path path) { return path.toString().replace('\\', '/'); }
}
