package att.server;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Exact browser Origin comparison. Forwarded headers are interpreted only by Tomcat. */
final class OriginPolicy {
    private final Set<Origin> trusted;

    OriginPolicy(Collection<String> configured) {
        Set<Origin> checked = new HashSet<>();
        if (configured.size() > 100) throw new IllegalArgumentException("server.security.trustedOrigins permits at most 100 origins");
        for (String value : configured) {
            Origin origin;
            try { origin = parse(value, true); }
            catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("server.security.trustedOrigins requires exact HTTPS origins (HTTP only for literal loopback or localhost)");
            }
            if (!origin.scheme.equals("https") && !loopback(origin.host)) {
                throw new IllegalArgumentException("server.security.trustedOrigins requires HTTPS except for loopback development");
            }
            if (!checked.add(origin)) throw new IllegalArgumentException("server.security.trustedOrigins contains duplicate equivalent origins");
        }
        trusted = Set.copyOf(checked);
    }

    boolean allows(HttpServletRequest request) {
        // REST/CLI callers may omit Origin; authentication and CSRF remain separate.
        var headers = request.getHeaders("Origin");
        if (headers == null || !headers.hasMoreElements()) return true;
        String value = headers.nextElement();
        if (headers.hasMoreElements()) return false;
        try {
            Origin origin = parse(value, false);
            if (trusted.contains(origin)) return true;
            String scheme = request.getScheme().toLowerCase(Locale.ROOT);
            int port = request.getServerPort();
            if (port < 0) port = defaultPort(scheme);
            return origin.equals(new Origin(scheme, request.getServerName().toLowerCase(Locale.ROOT), port));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static Origin parse(String value, boolean configuration) {
        if (value == null || value.length() > 2048 || !value.equals(value.trim())) throw new IllegalArgumentException();
        URI uri = URI.create(value);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath();
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getRawAuthority().endsWith(":" )
                || path != null && !path.isEmpty() && !(configuration && path.equals("/"))) throw new IllegalArgumentException();
        int port = uri.getPort() == -1 ? defaultPort(scheme) : uri.getPort();
        if (port < 1 || port > 65535) throw new IllegalArgumentException();
        return new Origin(scheme, uri.getHost().toLowerCase(Locale.ROOT), port);
    }

    private static int defaultPort(String scheme) { return scheme.equals("https") ? 443 : 80; }
    private static boolean loopback(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("[0:0:0:0:0:0:0:1]");
    }
    private record Origin(String scheme, String host, int port) {}
}
