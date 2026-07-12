package att.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validates logical identifiers before using their unchanged values as directory names. */
public final class IdentifierValidator {
    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final Pattern DEVICE = Pattern.compile("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$");

    private IdentifierValidator() {}

    public static String runId(String value) {
        return component(value, "Run ID", 128);
    }

    public static String workbookId(String value) {
        String id = component(value, "Workbook ID", 128);
        if (id.contains(".")) throw new IllegalArgumentException("Workbook ID must not contain '.': " + id);
        return id;
    }

    public static String caseId(String groupId, String rowCaseId) {
        String group = component(groupId, "Case group ID", 128);
        String row = component(rowCaseId, "Case row ID", 128);
        String full = group + "." + row;
        if (full.codePointCount(0, full.length()) > 255) throw new IllegalArgumentException("Full Case ID exceeds 255 Unicode code points");
        return full;
    }

    public static String caseId(String workbookId, String groupId, String rowCaseId) {
        String workbook = workbookId(workbookId);
        String group = component(groupId, "Case sheet ID", 128);
        if (group.contains(".")) throw new IllegalArgumentException("Case sheet ID must not contain '.': " + group);
        String row = component(rowCaseId, "Case row ID", 128);
        String full = workbook + "." + group + "." + row;
        if (full.codePointCount(0, full.length()) > 255) throw new IllegalArgumentException("Full Case ID exceeds 255 Unicode code points");
        return full;
    }

    public static Path strictChild(Path root, String validatedName, String owner) {
        Path normalizedRoot = canonicalPath(root, owner + " root");
        Path child = normalizedRoot.resolve(validatedName).normalize();
        if (child.equals(normalizedRoot) || !child.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(owner + " escapes its output root: " + validatedName);
        }
        return child;
    }

    /** Resolves an existing non-symlink child and verifies canonical root containment. */
    public static Path strictExistingChild(Path root, String validatedName, String owner) {
        Path canonicalRoot = canonicalPath(root, owner + " root");
        Path logical = canonicalRoot.resolve(validatedName).normalize();
        if (logical.equals(canonicalRoot) || !logical.startsWith(canonicalRoot) || java.nio.file.Files.isSymbolicLink(logical)) {
            throw new IllegalArgumentException(owner + " is an unsafe child: " + validatedName);
        }
        try {
            Path canonical = logical.toRealPath();
            if (!canonical.startsWith(canonicalRoot)) throw new IllegalArgumentException(owner + " escapes its root: " + validatedName);
            return canonical;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Unable to resolve " + owner + ": " + validatedName + ": " + e.getMessage(), e);
        }
    }

    public static Path canonicalPath(Path path, String owner) {
        Path absolute = path.toAbsolutePath().normalize(), existing = absolute;
        try {
            while (existing != null && !java.nio.file.Files.exists(existing)) existing = existing.getParent();
            if (existing == null) throw new IllegalArgumentException(owner + " has no existing filesystem ancestor: " + path);
            Path canonicalExisting = existing.toRealPath();
            return canonicalExisting.resolve(existing.relativize(absolute)).normalize();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Unable to resolve " + owner + ": " + path + ": " + e.getMessage(), e);
        }
    }

    public static Path relativePath(String value, String owner) {
        if (value == null || value.trim().isEmpty() || !value.equals(value.trim())) throw new IllegalArgumentException(owner + " must be a non-blank relative path");
        if (value.indexOf('\\') >= 0) throw new IllegalArgumentException(owner + " must use '/' separators");
        for (String segment : value.split("/", -1)) if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) throw new IllegalArgumentException(owner + " contains an empty/current/parent segment: " + value);
        Path path = java.nio.file.Paths.get(value);
        if (path.isAbsolute() || path.normalize().startsWith("..")) throw new IllegalArgumentException(owner + " must remain relative: " + value);
        return path.normalize();
    }

    private static String component(String value, String owner, int maximumCodePoints) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(owner + " must not be blank");
        if (!value.equals(value.trim())) throw new IllegalArgumentException(owner + " must not begin or end with whitespace: " + value);
        if (".".equals(value) || "..".equals(value)) throw new IllegalArgumentException(owner + " must not be '.' or '..'");
        if (ILLEGAL.matcher(value).find()) throw new IllegalArgumentException(owner + " contains an illegal character: " + value);
        if (value.endsWith(".")) throw new IllegalArgumentException(owner + " must not end with '.': " + value);
        if (DEVICE.matcher(value.toUpperCase(Locale.ROOT)).matches()) throw new IllegalArgumentException(owner + " is a reserved device name: " + value);
        if (value.codePointCount(0, value.length()) > maximumCodePoints) throw new IllegalArgumentException(owner + " exceeds " + maximumCodePoints + " Unicode code points");
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)) throw new IllegalArgumentException(owner + " contains a control character");
            offset += Character.charCount(codePoint);
        }
        return value;
    }
}
