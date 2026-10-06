/* Author: Jeffrey + ChatGPT */
package att.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Small, shared guard for user-controlled paths written below an ATT artifact
 * root.  Normalized path checks alone do not protect against a symlink in an
 * already-created parent directory, so callers must run this check before
 * creating directories and before writing the final target.
 */
public final class PathSafety {
    private PathSafety() { }

    public static void ensureContained(Path root, Path target, String description) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot) || normalizedTarget.equals(normalizedRoot)) {
            throw new IOException(description + " escapes allowed root: " + normalizedTarget);
        }
        if (Files.isSymbolicLink(normalizedRoot)) {
            throw new IOException(description + " allowed root must not be a symbolic link: " + normalizedRoot);
        }
        rejectSymlinkComponents(normalizedRoot, normalizedTarget, description);
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(normalizedTarget)) {
            throw new IOException(description + " target must not be a symbolic link: " + normalizedTarget);
        }

        // Resolve the existing filesystem boundary after directory creation as
        // well. This is a pre-write check, not an atomic defense against a
        // concurrent filesystem mutation.
        Path realRoot = normalizedRoot.toRealPath();
        Path parent = normalizedTarget.getParent();
        if (parent != null && Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(realRoot)) {
                throw new IOException(description + " parent escapes allowed root: " + realParent);
            }
        }
    }

    private static void rejectSymlinkComponents(Path root, Path target, String description) throws IOException {
        Path relative = root.relativize(target);
        Path current = root;
        int count = relative.getNameCount();
        // The final target is checked separately; this loop covers every
        // parent component through the path that is about to be created.
        for (int index = 0; index < count - 1; index++) {
            current = current.resolve(relative.getName(index));
            if (Files.isSymbolicLink(current)) {
                throw new IOException(description + " parent must not be a symbolic link: " + current);
            }
        }
    }
}
