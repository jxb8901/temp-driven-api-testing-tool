package att.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

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

    @Test void boundsUnquotedSpacedAndWindowsUncDiagnosticPaths() throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Path spaced = Files.createDirectories(temp.resolve("Users/John Doe/private"))
                .resolve("token (final)!.pem");
        Files.write(spaced, new byte[]{1});
        assertEquals("Unable to read $EXTERNAL/token (final)!.pem!", PathPresentation.displayDiagnosticText(
                "Unable to read " + spaced + "!", root));
        assertEquals("Unable to read $EXTERNAL/token (final)!.pem: Permission denied", PathPresentation.displayDiagnosticText(
                "Unable to read " + spaced + ": Permission denied", root));

        String backslashUnc = "\\\\server\\share\\private\\token.pem";
        String slashUnc = "//server/share/private/token.pem";
        assertEquals("Remote destination $EXTERNAL/token.pem", PathPresentation.displayDiagnosticText(
                "Remote destination " + backslashUnc, root));
        assertEquals("Remote destination $EXTERNAL/token.pem", PathPresentation.displayDiagnosticText(
                "Remote destination " + slashUnc, root));
        assertEquals("$EXTERNAL/token.pem", PathPresentation.displayPath(java.nio.file.Paths.get(backslashUnc), root));

        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("identityFile", backslashUnc);
        Map<?, ?> shown = (Map<?, ?>) PathPresentation.displayStructure(fields, root);
        assertEquals("$EXTERNAL/token.pem", shown.get("identityFile"));
    }

    @Test void boundsExternalAbsolutePathsEmbeddedInDiagnosticText() throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Path outside = Files.createDirectories(temp.resolve("private")).resolve("token.pem");
        Files.write(outside, new byte[]{1});

        assertEquals("Unable to read $EXTERNAL/token.pem", PathPresentation.displayDiagnosticText(
                "Unable to read " + outside, root));
        assertEquals("Unable to read '$EXTERNAL/token.pem'!", PathPresentation.displayDiagnosticText(
                "Unable to read '" + outside + "'!", root));
        Path spacedOutside = Files.write(outside.getParent().resolve("token 秘密.pem"), new byte[]{2});
        assertEquals("Unable to read '$EXTERNAL/token 秘密.pem'", PathPresentation.displayDiagnosticText(
                "Unable to read '" + spacedOutside + "'", root));
        assertEquals("Request URL https://api.example.test/v1/resource", PathPresentation.displayText(
                "Request URL https://api.example.test/v1/resource", root));
        assertEquals("Remote destination /srv/app/request.xml", PathPresentation.displayText(
                "Remote destination /srv/app/request.xml", root));

        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("detail", "Unable to read " + outside);
        evidence.put("remotePath", "/srv/app/request.xml");
        Map<?, ?> displayed = (Map<?, ?>) PathPresentation.displayStructure(evidence, root);
        assertEquals("Unable to read $EXTERNAL/token.pem", displayed.get("detail"));
        assertEquals("/srv/app/request.xml", displayed.get("remotePath"));
    }
}
