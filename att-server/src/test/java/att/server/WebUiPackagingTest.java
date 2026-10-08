package att.server;

import jakarta.servlet.annotation.WebFilter;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class WebUiPackagingTest {
    @Test void uiStaticResourcesAreOnTheServerClasspath() throws IOException {
        var loader=Thread.currentThread().getContextClassLoader();
        var page=loader.getResourceAsStream("META-INF/resources/ui/index.html");
        var script=loader.getResourceAsStream("META-INF/resources/ui/app.js");
        assertNotNull(page,"The att-web JAR must contribute its Servlet static resources to the Server WAR");
        assertNotNull(script);
        String html=new String(page.readAllBytes(),StandardCharsets.UTF_8);
        String js=new String(script.readAllBytes(),StandardCharsets.UTF_8);
        assertTrue(html.contains("ATT Server"));
        assertTrue(js.contains("new URL('../api/v1/', document.baseURI)"),"API URLs must be relative to the deployment context");
        assertFalse(js.contains("innerHTML"),"Untrusted API data must not be parsed as HTML");
    }

    @Test void uiSharesTheContainerAuthenticationFilter() {
        WebFilter mapping=AuthenticationFilter.class.getAnnotation(WebFilter.class);
        assertNotNull(mapping);
        assertTrue(Arrays.asList(mapping.urlPatterns()).contains("/ui/*"));
        assertTrue(Arrays.asList(mapping.urlPatterns()).contains("/api/v1/*"));
    }
}
