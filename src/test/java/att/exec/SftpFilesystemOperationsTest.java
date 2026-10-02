package att.exec;

import att.config.SshConfig;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SftpFilesystemOperationsTest {
    static final class MemorySftp extends ChannelSftp {
        final Map<String, SftpATTRS> nodes = new LinkedHashMap<String, SftpATTRS>();
        int calls;
        boolean refuseRemoval, refuseRename;
        MemorySftp() throws Exception { nodes.put("/", attr(true, 0)); }
        @Override public SftpATTRS lstat(String path) throws SftpException {
            calls++;
            if ("/denied".equals(path)) throw new SftpException(SSH_FX_PERMISSION_DENIED, "denied");
            SftpATTRS value = nodes.get(path);
            if (value == null) throw new SftpException(SSH_FX_NO_SUCH_FILE, "missing");
            return value;
        }
        @Override public String realpath(String path) { return "/alias".equals(path) ? "/from" : path; }
        @Override public void mkdir(String path) throws SftpException {
            if (nodes.containsKey(path)) throw new SftpException(SSH_FX_FAILURE, "exists");
            try { nodes.put(path, attr(true, 0)); } catch (Exception error) { throw new AssertionError(error); }
        }
        @Override public void rename(String source, String target) throws SftpException {
            if (refuseRename || nodes.containsKey(target)) throw new SftpException(SSH_FX_FAILURE, "target exists or rename denied");
            nodes.put(target, nodes.remove(source));
        }
        @Override public void rm(String path) throws SftpException {
            if (refuseRemoval) throw new SftpException(SSH_FX_PERMISSION_DENIED, "removal denied");
            nodes.remove(path);
        }
        @Override public void rmdir(String path) throws SftpException {
            for (String child : nodes.keySet()) if (child.startsWith(path + "/"))
                throw new SftpException(SSH_FX_FAILURE, "not empty");
            nodes.remove(path);
        }
    }
    static SftpATTRS attr(boolean directory, long size) throws Exception {
        java.lang.reflect.Constructor<SftpATTRS> constructor = SftpATTRS.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        SftpATTRS value = constructor.newInstance();
        value.setPERMISSIONS(0755);
        java.lang.reflect.Field permissions = SftpATTRS.class.getDeclaredField("permissions"); permissions.setAccessible(true);
        permissions.setInt(value, directory ? 0040755 : 0100644); value.setSIZE(size); value.setACMODTIME(0, 1790850000);
        return value;
    }
    static Map<String, Object> args(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
    @Test void statDistinguishesMissingTypedMetadataAndPermissionFailures() throws Exception {
        MemorySftp remote = new MemorySftp(); remote.nodes.put("/file", attr(false, 123));
        assertEquals(false, SftpFilesystemOperations.execute(remote, "stat", args("remotePath", "/missing")).get("exists"));
        Map<String, Object> file = SftpFilesystemOperations.execute(remote, "stat", args("remotePath", "/file"));
        assertEquals(true, file.get("exists")); assertEquals("file", file.get("type")); assertEquals(123L, file.get("size"));
        assertTrue(file.get("modifiedAt") instanceof String);
        assertEquals("directory", SftpFilesystemOperations.execute(remote, "stat", args("remotePath", "/")).get("type"));
        assertThrows(SftpException.class, () -> SftpFilesystemOperations.execute(remote, "stat", args("remotePath", "/denied")));
    }
    @Test void mkdirsCreatesParentsAndIsIdempotentButRejectsFiles() throws Exception {
        MemorySftp remote = new MemorySftp();
        assertEquals(true, SftpFilesystemOperations.execute(remote, "mkdirs", args("remotePath", "/a/b")).get("created"));
        assertTrue(remote.nodes.get("/a").isDir()); assertTrue(remote.nodes.get("/a/b").isDir());
        assertEquals(false, SftpFilesystemOperations.execute(remote, "mkdirs", args("remotePath", "/a/b")).get("created"));
        remote.nodes.put("/file", attr(false, 2));
        assertThrows(java.io.IOException.class, () -> SftpFilesystemOperations.execute(remote, "mkdirs", args("remotePath", "/file/child")));
    }
    @Test void moveRequiresSourceAndExplicitOverwrite() throws Exception {
        MemorySftp remote = new MemorySftp(); remote.nodes.put("/from", attr(false, 7)); remote.nodes.put("/to", attr(false, 2));
        assertThrows(java.io.IOException.class, () -> SftpFilesystemOperations.execute(remote, "move", args("sourcePath", "/from", "targetPath", "/to")));
        assertEquals(2, remote.nodes.get("/to").getSize());
        assertThrows(SftpException.class, () -> remote.rename("/from", "/to"), "Simulate SFTP v3 without rename-overwrite");
        assertEquals(true, SftpFilesystemOperations.execute(remote, "move", args("sourcePath", "/from", "targetPath", "/to", "overwrite", true)).get("moved"));
        assertFalse(remote.nodes.containsKey("/from")); assertEquals(7, remote.nodes.get("/to").getSize());
        assertThrows(java.io.IOException.class, () -> SftpFilesystemOperations.execute(remote, "move", args("sourcePath", "/absent", "targetPath", "/new")));
    }
    @Test void overwriteFailureAndDirectoryReplacementAreExplicitAndNonRecursive() throws Exception {
        MemorySftp remote = new MemorySftp();
        remote.nodes.put("/from", attr(false, 7)); remote.nodes.put("/to", attr(false, 2));
        remote.nodes.put("/alias", remote.nodes.get("/from"));
        assertThrows(java.io.IOException.class, () -> SftpFilesystemOperations.execute(remote, "move",
                args("sourcePath", "/from", "targetPath", "/alias", "overwrite", true)));
        assertTrue(remote.nodes.containsKey("/from")); remote.nodes.remove("/alias");
        remote.refuseRemoval = true;
        assertThrows(SftpException.class, () -> SftpFilesystemOperations.execute(remote, "move",
                args("sourcePath", "/from", "targetPath", "/to", "overwrite", true)));
        assertEquals(7, remote.nodes.get("/from").getSize()); assertEquals(2, remote.nodes.get("/to").getSize());
        remote.refuseRemoval = false; remote.refuseRename = true;
        assertThrows(SftpException.class, () -> SftpFilesystemOperations.execute(remote, "move",
                args("sourcePath", "/from", "targetPath", "/to", "overwrite", true)));
        assertTrue(remote.nodes.containsKey("/from")); assertFalse(remote.nodes.containsKey("/to"));
        remote.refuseRename = false;
        remote.nodes.put("/sourceDir", attr(true, 0)); remote.nodes.put("/targetDir", attr(true, 0));
        remote.nodes.put("/targetDir/child", attr(false, 1));
        assertThrows(SftpException.class, () -> SftpFilesystemOperations.execute(remote, "move",
                args("sourcePath", "/sourceDir", "targetPath", "/targetDir", "overwrite", true)));
        assertTrue(remote.nodes.containsKey("/targetDir/child")); assertTrue(remote.nodes.containsKey("/sourceDir"));
        remote.nodes.remove("/targetDir/child");
        assertEquals(true, SftpFilesystemOperations.execute(remote, "move",
                args("sourcePath", "/sourceDir", "targetPath", "/targetDir", "overwrite", true)).get("moved"));
        assertFalse(remote.nodes.containsKey("/sourceDir")); assertTrue(remote.nodes.get("/targetDir").isDir());
    }

    @Test void deleteIsNonRecursiveAndMissingOkIsExplicit() throws Exception {
        MemorySftp remote = new MemorySftp(); remote.nodes.put("/a", attr(true, 0)); remote.nodes.put("/a/file", attr(false, 7));
        assertThrows(SftpException.class, () -> SftpFilesystemOperations.execute(remote, "delete", args("remotePath", "/a")));
        assertTrue(remote.nodes.containsKey("/a/file"));
        assertEquals(true, SftpFilesystemOperations.execute(remote, "delete", args("remotePath", "/a/file")).get("deleted"));
        assertEquals(true, SftpFilesystemOperations.execute(remote, "delete", args("remotePath", "/a")).get("deleted"));
        assertThrows(java.io.IOException.class, () -> SftpFilesystemOperations.execute(remote, "delete", args("remotePath", "/absent")));
        assertEquals(false, SftpFilesystemOperations.execute(remote, "delete", args("remotePath", "/absent", "missingOk", true)).get("deleted"));
        assertThrows(java.io.IOException.class, () -> SftpFilesystemOperations.execute(remote, "delete", args("remotePath", "/")));
    }
}
