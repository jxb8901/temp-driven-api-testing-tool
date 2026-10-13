package att.server;

import jakarta.servlet.annotation.WebFilter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class WebUiPackagingTest {
    private static final Pattern SCRIPT = Pattern.compile("<script[^>]+src=\\\"([^\\\"]+\\.js)\\\"");
    private static final Pattern STYLESHEET = Pattern.compile("<link[^>]+href=\\\"([^\\\"]+\\.css)\\\"");

    @Test void activeReactAssetsAreOnTheServerClasspath() throws IOException {
        var loader = Thread.currentThread().getContextClassLoader();
        String page = read(loader, "META-INF/resources/ui/index.html");
        assertTrue(page.contains("id=\"root\""), "The active page must provide the single React mount.");
        assertFalse(page.contains("app.js"), "The legacy DOM-mutating application must not be loaded by React.");
        assertFalse(page.contains("legacy.html"), "The rollback page must not be linked from the active entry.");

        String scriptPath = match(SCRIPT, page, "Vite must reference its generated JavaScript bundle.");
        String stylePath = match(STYLESHEET, page, "Vite must reference its generated stylesheet.");
        String scriptResource = "META-INF/resources/ui/" + scriptPath.replaceFirst("^\\./", "");
        String styleResource = "META-INF/resources/ui/" + stylePath.replaceFirst("^\\./", "");
        String script = read(loader, scriptResource);
        String style = read(loader, styleResource);
        assertTrue(script.contains("../api/v1/"), "The bundle must resolve API requests relative to the UI context.");
        assertTrue(script.contains("Server managed") && script.contains("not exposed by API"),
                "The shell must not imply a principal that the API does not expose.");
        assertTrue(style.contains("--att-color-primary") && style.contains("--att-focus-ring"),
                "The generated stylesheet must include the shared tokens.");
        assertNotNull(loader.getResource("META-INF/resources/ui/legacy.html"), "Keep the explicit rollback page during migration.");
        assertNotNull(loader.getResource("META-INF/resources/ui/app.js"), "Legacy workflow assets remain available only to the rollback page.");
    }

    @Test void uiSharesTheContainerAuthenticationFilter() {
        WebFilter mapping = AuthenticationFilter.class.getAnnotation(WebFilter.class);
        assertNotNull(mapping);
        assertTrue(Arrays.asList(mapping.urlPatterns()).contains("/ui/*"));
        assertTrue(Arrays.asList(mapping.urlPatterns()).contains("/api/v1/*"));
    }

    private static String read(ClassLoader loader, String path) throws IOException {
        try (var stream = loader.getResourceAsStream(path)) {
            assertNotNull(stream, "Missing packaged UI resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String match(Pattern pattern, String text, String message) {
        Matcher matcher = pattern.matcher(text);
        assertTrue(matcher.find(), message);
        return matcher.group(1);
    }
}
