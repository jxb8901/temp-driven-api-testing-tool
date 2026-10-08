package att.remote.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RemoteHttpClientTest {
    @TempDir Path temp;
    @Test void publishingDownloadedFileNeverOverwritesAConcurrentWriter() throws Exception {
        Path staged=Files.writeString(temp.resolve("staged.part"),"download");
        Path target=Files.writeString(temp.resolve("report.txt"),"other writer");
        assertThrows(java.nio.file.FileAlreadyExistsException.class,()->RemoteHttpClient.publishNoReplace(staged,target));
        assertEquals("other writer",Files.readString(target));
        assertEquals("download",Files.readString(staged));
    }
}
