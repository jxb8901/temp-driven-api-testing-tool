package att.remote.http;

import att.remote.RemoteException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RemoteHttpClientTest {
    @TempDir Path temp;
    @Test void classifiesSseConnectionRefusalAsRetryableTransportFailure() throws Exception {
        int port;try(java.net.ServerSocket unused=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){port=unused.getLocalPort();}
        att.remote.config.ServerProfile profile=new att.remote.config.ServerProfile("unavailable","http://127.0.0.1:"+port,null,null,false);
        RemoteException failure=assertThrows(RemoteException.class,()->new RemoteHttpClient(profile,null).openEvents("/jobs/J0123456789ABCDEF/events",null));
        assertTrue(failure.isRetryableTransport());
    }
    @Test void publishingDownloadedFileNeverOverwritesAConcurrentWriter() throws Exception {
        Path staged=Files.write(temp.resolve("staged.part"),"download".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path target=Files.write(temp.resolve("report.txt"),"other writer".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(java.nio.file.FileAlreadyExistsException.class,()->RemoteHttpClient.publishNoReplace(staged,target));
        assertEquals("other writer",new String(Files.readAllBytes(target),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("download",new String(Files.readAllBytes(staged),java.nio.charset.StandardCharsets.UTF_8));
    }
}
