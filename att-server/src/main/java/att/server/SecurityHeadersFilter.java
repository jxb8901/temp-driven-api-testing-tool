package att.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/** Browser safety headers shared by the bundled UI, API, and downloaded artifacts. */
@WebFilter(urlPatterns="/*",asyncSupported=true)
public final class SecurityHeadersFilter implements Filter {
    private static final String NONCE_MARKER = "__ATT_CSP_NONCE__";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (!(response instanceof HttpServletResponse http)) {
            chain.doFilter(request, response);
            return;
        }

        http.setHeader("X-Content-Type-Options", "nosniff");
        http.setHeader("X-Frame-Options", "DENY");
        http.setHeader("Referrer-Policy", "no-referrer");

        if (request instanceof HttpServletRequest servletRequest) {
            String contextPath = contextPath(servletRequest);
            String appPath = appPath(servletRequest, contextPath);
            if ("GET".equalsIgnoreCase(servletRequest.getMethod()) && (appPath.isEmpty() || "/".equals(appPath))) {
                http.setHeader("Content-Security-Policy", policy(newNonce(), false));
                http.sendRedirect(contextPath + "/ui/");
                return;
            }

            boolean studioDocument = isStudioDocument(servletRequest, appPath);
            String nonce = newNonce();
            http.setHeader("Content-Security-Policy", policy(nonce, studioDocument));
            if (studioDocument) {
                NonceHtmlResponse captured = new NonceHtmlResponse(http);
                chain.doFilter(request, captured);
                captured.finish(nonce);
                return;
            }
        } else {
            http.setHeader("Content-Security-Policy", policy(newNonce(), false));
        }
        chain.doFilter(request, response);
    }

    private static String policy(String nonce, boolean studioDocument) {
        String source = "'nonce-" + nonce + "'";
        // CodeMirror uses nonce-bearing style elements for generated rules and inline style
        // attributes for editor geometry. Keep the attribute exception on the Studio document
        // only; API, login, downloads, and other responses keep inline styles disabled.
        String styleAttributes = studioDocument ? "'unsafe-inline'" : "'none'";
        return "default-src 'self'; script-src 'self'" + (studioDocument ? " " + source : "")
                + "; style-src 'self' " + source
                + "; style-src-elem 'self' " + source
                + "; style-src-attr " + styleAttributes
                + "; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'";
    }

    private static String newNonce() {
        byte[] value = new byte[24];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String contextPath(HttpServletRequest request) {
        String value = request.getContextPath();
        return value == null ? "" : value;
    }

    private static String appPath(HttpServletRequest request, String contextPath) {
        String uri = request.getRequestURI();
        return uri == null ? "" : uri.substring(Math.min(contextPath.length(), uri.length()));
    }

    private static boolean isStudioDocument(HttpServletRequest request, String appPath) {
        return "GET".equalsIgnoreCase(request.getMethod())
                && ("/ui/".equals(appPath) || "/ui/index.html".equals(appPath));
    }

    private static final class NonceHtmlResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private ServletOutputStream output;
        private PrintWriter writer;

        NonceHtmlResponse(HttpServletResponse response) { super(response); }

        @Override public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) throw new IllegalStateException("getWriter() has already been called");
            if (output == null) {
                output = new ServletOutputStream() {
                    @Override public void write(int value) { body.write(value); }
                    @Override public void write(byte[] value, int offset, int length) { body.write(value, offset, length); }
                    @Override public boolean isReady() { return true; }
                    @Override public void setWriteListener(WriteListener listener) {
                        if (listener != null) {
                            try { listener.onWritePossible(); }
                            catch (IOException failure) { throw new IllegalStateException(failure); }
                        }
                    }
                };
            }
            return output;
        }

        @Override public PrintWriter getWriter() throws IOException {
            if (output != null) throw new IllegalStateException("getOutputStream() has already been called");
            if (writer == null) writer = new PrintWriter(new OutputStreamWriter(body, charset()));
            return writer;
        }

        @Override public void setContentLength(int length) { }
        @Override public void setContentLengthLong(long length) { }
        @Override public void flushBuffer() throws IOException { if (writer != null) writer.flush(); }

        void finish(String nonce) throws IOException {
            if (writer != null) writer.flush();
            HttpServletResponse response = (HttpServletResponse) getResponse();
            if (response.isCommitted()) return;

            byte[] bytes = body.toByteArray();
            String contentType = getContentType();
            if (getStatus() >= 200 && getStatus() < 300 && contentType != null
                    && contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
                String html = new String(bytes, charset());
                if (!html.contains(NONCE_MARKER)) {
                    throw new IOException("ATT Studio entry HTML is missing its CSP nonce marker");
                }
                bytes = html.replace(NONCE_MARKER, nonce).getBytes(charset());
            }
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        }

        private Charset charset() {
            try {
                String name = getCharacterEncoding();
                return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
            } catch (RuntimeException invalid) {
                return StandardCharsets.UTF_8;
            }
        }
    }
}
