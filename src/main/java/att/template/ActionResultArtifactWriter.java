/* Author: Jeffrey + ChatGPT */
package att.template;

import att.core.CaseRuntimeContext;
import att.core.IdentifierValidator;
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
        Path target = root.resolve(IdentifierValidator.relativePath(configuredPath, "action saveAs.path")).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Action saveAs.path must stay under the Case artifact directory: " + configuredPath);
        }
        Files.createDirectories(target.getParent());
        if (Files.exists(target) && !overwrite) {
            throw new IllegalArgumentException("saveAs file already exists and overwrite is false: " + configuredPath);
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
