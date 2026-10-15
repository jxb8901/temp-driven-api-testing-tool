package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPathPresentationTest {
    @TempDir Path temp;

    @Test void apiAndWorkerErrorsHideHostLocalPathPrefixes() {
        String path = temp.resolve("private").resolve("credentials.yaml").toAbsolutePath().toString();

        String apiDetail = ApiServlet.safeDetail(new IllegalArgumentException("Could not read " + path));
        String workerDetail = ServerRuntime.safeMessage(new IllegalStateException("Could not read " + path));

        assertEquals("Could not read $EXTERNAL/credentials.yaml", apiDetail);
        assertEquals("Could not read $EXTERNAL/credentials.yaml", workerDetail);
        assertFalse(apiDetail.contains(temp.toString()));
        assertFalse(workerDetail.contains(temp.toString()));
        assertEquals("See https://example.test/private/credentials.yaml for details", ServerRuntime.safeMessage(
                new IllegalStateException("See https://example.test/private/credentials.yaml for details")));
        assertTrue(ServerRuntime.safeMessage(new IllegalStateException("See '/var/lib/att/private file.yaml'"))
                .contains("'$EXTERNAL/private file.yaml'"));
    }
}
