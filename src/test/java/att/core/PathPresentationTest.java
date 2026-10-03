package att.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PathPresentationTest {
    @TempDir Path temp;

    @Test void rendersRootAndPortableProjectRelativePaths() throws Exception {
        Path root = Files.createDirectories(temp.resolve("專案 folder"));
        assertEquals("$ATT_HOME", PathPresentation.displayPath(root, root));
        assertEquals("$ATT_HOME/templates/付款 request.xml",
                PathPresentation.displayPath(root.resolve("templates/付款 request.xml"), root));
        assertEquals("input: $ATT_HOME/templates/debug.yaml", PathPresentation.displayText(
                "input: " + root.resolve("templates/debug.yaml"), root));
        Path aliasedRoot = java.nio.file.Paths.get("/var/folders/example/project");
        assertEquals("input: /private/var/folders/example/project/templates/debug.yaml",
                PathPresentation.displayText("input: /private" + aliasedRoot.resolve("templates/debug.yaml"), aliasedRoot));
    }

    @Test void boundsExternalPathsAndRejectsSymlinkedEscapeForPresentation() throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Path outside = Files.createDirectories(temp.resolve("private"));
        Path secret = Files.write(outside.resolve("token.pem"), new byte[]{1});
        assertEquals("$EXTERNAL/token.pem", PathPresentation.displayPath(secret, root));
        Path link = root.resolve("linked");
        try { Files.createSymbolicLink(link, outside); }
        catch (UnsupportedOperationException | SecurityException unavailable) { return; }
        assertEquals("$EXTERNAL/token.pem", PathPresentation.displayPath(link.resolve("token.pem"), root));
        assertFalse(PathPresentation.displayPath(secret, root).contains(outside.toString()));
    }
}
