package att.server;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Browser safety headers shared by the bundled UI, API, and downloaded artifacts. */
@WebFilter(urlPatterns="/*",asyncSupported=true)
public final class SecurityHeadersFilter implements Filter {
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if(response instanceof HttpServletResponse http) {
            http.setHeader("X-Content-Type-Options", "nosniff");
            http.setHeader("X-Frame-Options", "DENY");
            http.setHeader("Referrer-Policy", "no-referrer");
            http.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        }
        chain.doFilter(request, response);
    }
}
