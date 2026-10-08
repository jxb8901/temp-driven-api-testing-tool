package att.remote.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RemoteHttpClientTest {
    @TempDir Path temp;
    @Test void publishingDownloadedFileNeverOverwritesAConcurrentWriter() throws Exception {
        Path staged=Files.write(temp.resolve("staged.part"),"download".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path target=Files.write(temp.resolve("report.txt"),"other writer".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,()->RemoteHttpClient.publishNoReplace(staged,target));
        assertEquals("other writer",new String(Files.readAllBytes(target),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("download",new String(Files.readAllBytes(staged),java.nio.charset.StandardCharsets.UTF_8));
    }
}
