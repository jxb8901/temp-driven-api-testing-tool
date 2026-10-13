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
        assertTrue(html.contains("id=\"job-principal-search\""),"Recent jobs can search by the existing principal field");
        assertTrue(html.contains("id=\"job-principal\""),"Run, Case, Debug and Quick Load forms expose an optional attribution label");
        assertTrue(html.contains("id=\"advanced-load-principal\""),"Advanced Load exposes an optional attribution label");
        assertTrue(html.contains("Name(anonym)"),"The UI explains how the Server formats anonymous labels");
        assertTrue(js.contains("body.principal = value"),"The UI sends the raw optional label for server-side formatting");
        assertTrue(js.contains("jobs?principal=${encodeURIComponent(principalSearch)}"),"Job search uses the existing principal property");
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
