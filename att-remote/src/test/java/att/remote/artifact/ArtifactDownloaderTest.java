package att.remote.artifact;

import att.remote.RemoteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactDownloaderTest {
    @TempDir Path temp;
    @Test void rejectsTraversalAndExistingFilesBeforeNetworkAccess() throws Exception {
        Path output=temp.resolve("out");Files.createDirectories(output);Path existing=output.resolve("report.txt");Files.write(existing,new byte[]{1});
        assertThrows(RemoteException.class,()->ArtifactDownloader.download(null,"J0123456789ABCDEF","../escape",output));
        assertThrows(RemoteException.class,()->ArtifactDownloader.download(null,"J0123456789ABCDEF","report.txt",output));
        assertArrayEquals(new byte[]{1},Files.readAllBytes(existing));
    }
    @Test void rejectsSymlinkedDownloadParents() throws Exception {
        Path outside=temp.resolve("outside");Files.createDirectories(outside);Path output=temp.resolve("out");Files.createDirectories(output);
        try { Files.createSymbolicLink(output.resolve("linked"),outside); }
        catch(UnsupportedOperationException|java.io.IOException|SecurityException e){return;}
        assertThrows(RemoteException.class,()->ArtifactDownloader.download(null,"J0123456789ABCDEF","linked/result.bin",output));
        assertFalse(Files.exists(outside.resolve("result.bin")));
    }
}
