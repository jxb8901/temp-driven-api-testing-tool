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

    Path write(CaseRuntimeContext context, String configuredPath, String format, Object value,
               boolean overwrite) throws Exception {
        Path root = context.caseOutputDirectory().toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target = root.resolve(IdentifierValidator.relativePath(configuredPath, "action saveAs.path")).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("Action saveAs.path must stay under the Case artifact directory: " + configuredPath);
        }
        Files.createDirectories(target.getParent());
        if (Files.exists(target) && !overwrite) {
            throw new IllegalArgumentException("saveAs file already exists and overwrite is false: " + configuredPath);
        }
        byte[] bytes;
        if ("text".equalsIgnoreCase(format)) {
            bytes = (value == null ? "" : String.valueOf(value)).getBytes(StandardCharsets.UTF_8);
        } else {
            bytes = codec.encode(value, format).getBytes(StandardCharsets.UTF_8);
        }
        if (overwrite) Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        else Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        return target;
    }
}
