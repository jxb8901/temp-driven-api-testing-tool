package att.exec;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Literal-path SFTP operations; no local staging, shell commands or recursive deletion. */
final class SftpFilesystemOperations {
    private SftpFilesystemOperations() { }

    static Map<String, Object> execute(ChannelSftp channel, String operation, Map<String, Object> input) throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if ("move".equals(operation)) {
            String source = String.valueOf(input.get("sourcePath"));
            String target = String.valueOf(input.get("targetPath"));
            boolean overwrite = Boolean.TRUE.equals(input.get("overwrite"));
            if (source.equals(target)) throw new IOException("SSH move requires different sourcePath and targetPath");
            SftpATTRS sourceAttributes = attributes(channel, source);
            if (sourceAttributes == null) throw new IOException("SSH move source does not exist");
            SftpATTRS targetAttributes = attributes(channel, target);
            if (targetAttributes != null) {
                if (!overwrite) throw new IOException("SSH move target exists; set overwrite=true");
                if (channel.realpath(source).equals(channel.realpath(target)))
                    throw new IOException("SSH move source and target resolve to the same path");
                if (sourceAttributes.isReg() && targetAttributes.isReg()) channel.rm(target);
                else if (sourceAttributes.isDir() && targetAttributes.isDir()) channel.rmdir(target);
                else throw new IOException("SSH move overwrite requires matching regular files or directories");
            }
            // Explicit replacement works with SFTP v3 servers that refuse rename onto an existing path.
            // Removal + rename is not atomic: if rename fails, source remains and target may be absent.
            channel.rename(source, target);
            result.put("sourcePath", source); result.put("targetPath", target); result.put("moved", true);
            return result;
        }
        String path = String.valueOf(input.get("remotePath"));
        result.put("path", path);
        SftpATTRS attributes = attributes(channel, path);
        if ("stat".equals(operation)) {
            result.put("exists", attributes != null);
            if (attributes != null) {
                result.put("type", attributes.isReg() ? "file" : attributes.isDir() ? "directory" : "other");
                if (attributes.isReg()) result.put("size", attributes.getSize());
                result.put("modifiedAt", Instant.ofEpochSecond(Integer.toUnsignedLong(attributes.getMTime())).toString());
            }
        } else if ("mkdirs".equals(operation)) {
            String current = path.startsWith("/") ? "/" : "";
            for (String part : path.split("/")) {
                if (part.isEmpty() || ".".equals(part)) continue;
                current += (current.isEmpty() || current.endsWith("/") ? "" : "/") + part;
                SftpATTRS existing = attributes(channel, current);
                if (existing == null) {
                    try { channel.mkdir(current); }
                    catch (SftpException race) {
                        existing = attributes(channel, current);
                        if (existing == null || !existing.isDir()) throw race;
                    }
                } else if (!existing.isDir()) throw new IOException("SSH mkdirs path exists as a non-directory");
            }
            result.put("created", attributes == null);
        } else if ("delete".equals(operation)) {
            String normalized = path.replaceAll("/+$", "");
            if (normalized.isEmpty() || ".".equals(normalized) || "..".equals(normalized)
                    || normalized.endsWith("/.") || normalized.endsWith("/.."))
                throw new IOException("SSH delete refuses a root or parent directory path");
            if (attributes == null) {
                if (!Boolean.TRUE.equals(input.get("missingOk"))) throw new IOException("SSH delete path does not exist; set missingOk=true");
                result.put("deleted", false);
            } else {
                if (attributes.isReg()) channel.rm(path);
                else if (attributes.isDir()) channel.rmdir(path); // Server rejects non-empty directories.
                else throw new IOException("SSH delete supports regular files and empty directories only");
                result.put("deleted", true);
            }
        } else throw new IOException("Unsupported SFTP filesystem operation: " + operation);
        return result;
    }

    private static SftpATTRS attributes(ChannelSftp channel, String path) throws SftpException {
        try { return channel.lstat(path); }
        catch (SftpException missing) {
            if (missing.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
            throw missing;
        }
    }
}
