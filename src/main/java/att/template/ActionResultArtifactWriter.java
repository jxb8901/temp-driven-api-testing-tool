/* Author: Jeffrey + ChatGPT */
package att.template;

import att.core.CaseRuntimeContext;
import att.core.IdentifierValidator;
import att.core.PathSafety;
import att.exec.ObjectOutputCodec;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Writes a typed Tool/DB result to a safe Case-contained artifact path. */
final class ActionResultArtifactWriter {
    private final ObjectOutputCodec codec = new ObjectOutputCodec();
    private final DbTextResultFormatter dbText = new DbTextResultFormatter();

    Path write(CaseRuntimeContext context, String actionId, String configuredPath, String format, Object value,
               boolean overwrite) throws Exception {
        return write(context, actionId, configuredPath, format, value, overwrite, false);
    }

    Path writeDb(CaseRuntimeContext context, String actionId, String configuredPath, String format, Object value,
                 boolean overwrite) throws Exception {
        return write(context, actionId, configuredPath, format, value, overwrite, true);
    }

    String render(String format, Object value) throws Exception { return render(format, value, false); }
    String renderDb(String format, Object value) throws Exception { return render(format, value, true); }

    private Path write(CaseRuntimeContext context, String actionId, String configuredPath, String format, Object value,
                       boolean overwrite, boolean dbResult) throws Exception {
        Path root = (context.inFlow() ? context.actionOutputDir(actionId) : context.caseOutputDirectory()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target;
        try {
            target = root.resolve(IdentifierValidator.relativePath(configuredPath, "action saveAs.path")).normalize();
        } catch (RuntimeException error) {
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.PATH_INVALID,
                    "Invalid action saveAs path",
                    "configuredPath=" + configuredPath + ", resolvedRoot=" + root + ", reason=" + error.getMessage(),
                    null, "saveAs.path", null, null, null, null, actionId,
                    "Use a safe relative path below the Case artifact directory.", error);
        }
        if (!target.startsWith(root) || target.equals(root)) {
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.PATH_INVALID,
                    "Action saveAs path escapes the Case artifact directory",
                    "configuredPath=" + configuredPath + ", resolvedPath=" + target + ", allowedRoot=" + root,
                    null, "saveAs.path", null, null, null, null, actionId,
                    "Use a safe relative path below the Case artifact directory.", null);
        }
        try {
            PathSafety.ensureContained(root, target, "Action saveAs path");
            Files.createDirectories(target.getParent());
            PathSafety.ensureContained(root, target, "Action saveAs path");
        } catch (java.io.IOException unsafePath) {
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.PATH_INVALID,
                    "Action saveAs path is not safe",
                    "configuredPath=" + configuredPath + ", resolvedPath=" + target + ", allowedRoot=" + root
                            + ", reason=" + unsafePath.getMessage(),
                    null, "saveAs.path", null, null, null, null, actionId,
                    "Use a safe relative path below the Case artifact directory and avoid symbolic links.", unsafePath);
        }
        if (Files.exists(target) && !overwrite) {
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.PATH_INVALID,
                    "Action saveAs target already exists",
                    "configuredPath=" + configuredPath + ", resolvedPath=" + target + ", overwrite=false",
                    null, "saveAs.path", null, null, null, null, actionId,
                    "Choose a unique target or set overwrite: true.", null);
        }
        byte[] bytes = render(format, value, dbResult).getBytes(StandardCharsets.UTF_8);
        if (overwrite) Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        else Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        return target;
    }


    private String render(String format, Object value, boolean dbResult) throws Exception {
        if ("raw".equalsIgnoreCase(format) || "text".equalsIgnoreCase(format)) {
            return dbResult ? dbText.format(value) : (value == null ? "" : String.valueOf(value));
        }
        return codec.encode(value, format);
    }
}
