package att.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class SecurityHeadersFilterTest {
    @Test void injectsMatchingNonceIntoStudioHtmlAndScopesInlineStyleAttributes() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpServletResponse response = response(headers, body, new int[]{200}, new boolean[]{false}, new String[]{null});
        HttpServletRequest request = request("GET", "/att", "/att/ui/index.html");

        new SecurityHeadersFilter().doFilter(request, response, (req, res) -> {
            HttpServletResponse http = (HttpServletResponse) res;
            http.setContentType("text/html;charset=UTF-8");
            http.getWriter().write("<html><head></head><body><script nonce=\"__ATT_CSP_NONCE__\"></script></body></html>");
        });

        String policy = headers.get("Content-Security-Policy");
        Matcher policyNonce = Pattern.compile("style-src 'self' 'nonce-([^']+)'[^;]*; style-src-elem 'self' 'nonce-([^']+)'[^;]*; style-src-attr '([^']+)'").matcher(policy);
        assertTrue(policyNonce.find(), policy);
        assertEquals(policyNonce.group(1), policyNonce.group(2), "Fallback and explicit element rules must share one nonce.");
        assertEquals("unsafe-inline", policyNonce.group(3), "Only the Studio document needs CodeMirror inline geometry styles.");
        assertFalse(policy.contains("script-src 'self' 'unsafe-inline'"), "Script restrictions must remain strict.");
        Matcher htmlNonce = Pattern.compile("<script nonce=\"([^\"]+)\"").matcher(body.toString(StandardCharsets.UTF_8));
        assertTrue(htmlNonce.find(), body.toString(StandardCharsets.UTF_8));
        assertEquals(policyNonce.group(1), htmlNonce.group(1));
        assertTrue(policy.contains("script-src 'self' 'nonce-" + htmlNonce.group(1) + "'"),
                "Only the nonce-bearing Studio document may run the isolated report navigation bridge.");

    }

    @Test void redirectsOnlyGetRequestsAtTheContextRootToTheContextRelativeStudio() throws Exception {
        assertRootRedirect("GET", "/att", "/att", "/att/ui/");
        assertRootRedirect("GET", "/att", "/att/", "/att/ui/");
        assertRootRedirect("GET", "", "/", "/ui/");

        Map<String, String> headers = new LinkedHashMap<>();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] status = {200};
        boolean[] chained = {false};
        HttpServletResponse response = response(headers, body, status, new boolean[]{false}, new String[]{null});
        new SecurityHeadersFilter().doFilter(request("POST", "/att", "/att/"), response, (req, res) -> chained[0] = true);
        assertTrue(chained[0], "Non-GET requests at the context root must not be redirected to Studio.");
        assertEquals(200, status[0]);
        assertNull(headers.get("Location"));
    }

    @Test void keepsInlineStyleAttributesDisabledOutsideTheStudioDocument() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        HttpServletResponse response = response(headers, new ByteArrayOutputStream(), new int[]{200}, new boolean[]{false}, new String[]{null});
        new SecurityHeadersFilter().doFilter(request("GET", "/att", "/att/api/v1/version"), response, (req, res) -> { });
        assertTrue(headers.get("Content-Security-Policy").contains("style-src-attr 'none'"));
        assertTrue(headers.get("Content-Security-Policy").contains("script-src 'self';"),
                "Non-Studio pages must not receive a script nonce allowance.");

    }

    private static void assertRootRedirect(String method, String contextPath, String requestUri, String expected) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        int[] status = {200};
        boolean[] chained = {false};
        HttpServletResponse response = response(headers, new ByteArrayOutputStream(), status, new boolean[]{false}, new String[]{null});
        new SecurityHeadersFilter().doFilter(request(method, contextPath, requestUri), response, (req, res) -> chained[0] = true);
        assertEquals(302, status[0]);
        assertEquals(expected, headers.get("Location"));
        assertFalse(chained[0]);
    }

    private static HttpServletRequest request(String method, String contextPath, String requestUri) {
        return (HttpServletRequest) Proxy.newProxyInstance(SecurityHeadersFilterTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, methodRef, args) -> switch (methodRef.getName()) {
                    case "getMethod" -> method;
                    case "getContextPath" -> contextPath;
                    case "getRequestURI" -> requestUri;
                    case "toString" -> "request";
                    default -> defaultValue(methodRef.getReturnType());
                });
    }

    private static HttpServletResponse response(Map<String, String> headers, ByteArrayOutputStream body,
                                                int[] status, boolean[] committed, String[] contentType) {
        ServletOutputStream output = new ServletOutputStream() {
            @Override public void write(int value) { body.write(value); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
        };
        return (HttpServletResponse) Proxy.newProxyInstance(SecurityHeadersFilterTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (proxy, methodRef, args) -> switch (methodRef.getName()) {
                    case "setHeader" -> { headers.put((String) args[0], (String) args[1]); yield null; }
                    case "getHeader" -> headers.get((String) args[0]);
                    case "sendRedirect" -> { status[0] = 302; headers.put("Location", (String) args[0]); yield null; }
                    case "setStatus" -> { status[0] = (Integer) args[0]; yield null; }
                    case "getStatus" -> status[0];
                    case "setContentLength" -> null;
                    case "setContentType" -> { contentType[0] = (String) args[0]; yield null; }
                    case "getContentType" -> contentType[0];
                    case "getCharacterEncoding" -> "UTF-8";
                    case "setCharacterEncoding" -> null;
                    case "getOutputStream" -> output;
                    case "getWriter" -> new PrintWriter(new java.io.OutputStreamWriter(body, StandardCharsets.UTF_8));
                    case "isCommitted" -> committed[0];
                    case "toString" -> "response";
                    default -> defaultValue(methodRef.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
