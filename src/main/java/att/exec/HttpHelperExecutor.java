package att.exec;

import att.config.FrameworkConfig;
import att.config.HttpHelperConfig;
import att.core.CaseRuntimeContext;
import att.validation.JsonSupport;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.util.EntityUtils;

/** Run-owned, thread-safe HTTPHelper transport with bounded reusable connections. */
public final class HttpHelperExecutor implements AutoCloseable {
    private static final ScheduledExecutorService DEADLINE_ABORTER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "att-http-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private final Path projectRoot;
    private final FrameworkConfig config;
    private final Map<String, Client> clients = new ConcurrentHashMap<String, Client>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public HttpHelperExecutor(Path projectRoot, FrameworkConfig config) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.config = config;
    }

    public ToolInvocationResult execute(String logicalId, String operation, Map<String, Object> arguments,
                                        CaseRuntimeContext context, Long actionTimeoutMs, String invocationId,
                                        String format) {
        String name = "http." + logicalId + "." + operation;
        long started = System.nanoTime();
        long deadline = actionTimeoutMs == null ? Long.MAX_VALUE
                : started + TimeUnit.MILLISECONDS.toNanos(actionTimeoutMs.longValue());
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("httpHelper", logicalId);
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("id", invocationId); evidence.put("httpHelper", logicalId);
        AtomicBoolean deadlineExpired = new AtomicBoolean(false);
        try {
            if (closed.get()) throw new HttpFailure("HTTP_CLOSED", "HTTP resources are closed");
            HttpHelperConfig helper = config.httpHelper(logicalId);
            if (helper == null) throw new HttpFailure("HTTP_CONFIG", "Unknown HTTP helper: " + logicalId);
            Map<String, Object> args = arguments == null ? Collections.<String, Object>emptyMap() : arguments;
            Request request = request(helper, operation, args, context, format);
            metadata.put("method", request.method);
            metadata.put("url", safeUrl(request.url, helper));
            evidence.put("method", request.method); evidence.put("url", safeUrl(request.url, helper));
            evidence.put("requestBytes", request.body == null ? 0 : request.body.length);
            Client client = client(helper);
            URI url = request.url;
            String method = request.method;
            byte[] body = request.body;
            for (int redirect = 0; redirect <= 5; redirect++) {
                ensureDeadline(deadline, "request");
                client.manager.closeIdleConnections(helper.idleEvictMs(), TimeUnit.MILLISECONDS);
                AnyMethod call = new AnyMethod(method, url);
                if (body != null) call.setEntity(new ByteArrayEntity(body));
                for (Map.Entry<String, String> header : request.headers.entrySet()) call.setHeader(header.getKey(), header.getValue());
                call.setConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(capped(helper.connectionRequestTimeoutMs(), request.poolTimeoutMs, deadline))
                        .setConnectTimeout(capped(helper.connectTimeoutMs(), request.connectTimeoutMs, deadline))
                        .setSocketTimeout(capped(helper.readTimeoutMs(), request.readTimeoutMs, deadline))
                        .setRedirectsEnabled(false).build());
                ScheduledFuture<?> deadlineTask = null;
                if (deadline != Long.MAX_VALUE) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new HttpFailure("HTTP_TIMEOUT", "HTTP Action deadline expired before request");
                    deadlineExpired.set(false);
                    deadlineTask = DEADLINE_ABORTER.schedule(() -> {
                        deadlineExpired.set(true);
                        call.abort();
                    }, remaining, TimeUnit.NANOSECONDS);
                }
                try (CloseableHttpResponse response = client.http.execute(call)) {
                    ensureDeadline(deadline, "response");
                    int status = response.getStatusLine().getStatusCode();
                    if (request.followRedirects && redirectStatus(status) && response.getFirstHeader("Location") != null) {
                        if (redirect == 5) throw new HttpFailure("HTTP_REDIRECT", "HTTP redirect limit exceeded");
                        URI target = url.resolve(response.getFirstHeader("Location").getValue());
                        if (!sameOrigin(helper.baseUrl(), target) || target.getUserInfo() != null)
                            throw new HttpFailure("HTTP_REDIRECT", "Cross-origin HTTP redirect is forbidden");
                        EntityUtils.consumeQuietly(response.getEntity());
                        url = target;
                        if (status == 303 && !"HEAD".equals(method)) { method = "GET"; body = null; }
                        evidence.put("redirectCount", redirect + 1);
                        continue;
                    }
                    HttpEntity entity = response.getEntity();
                    byte[] bytes = entity == null ? new byte[0] : EntityUtils.toByteArray(entity);
                    ensureDeadline(deadline, "read");
                    String contentType = response.getFirstHeader("Content-Type") == null ? "" : response.getFirstHeader("Content-Type").getValue();
                    Object result = decode(bytes, request.format, contentType);
                    metadata.put("method", method); metadata.put("url", safeUrl(url, helper));
                    metadata.put("statusCode", status);
                    metadata.put("reasonPhrase", response.getStatusLine().getReasonPhrase());
                    metadata.put("contentType", contentType);
                    metadata.put("responseBytes", bytes.length);
                    metadata.put("requestBytes", request.body == null ? 0 : request.body.length);
                    metadata.put("headers", responseHeaders(response, request.headers, helper));
                    evidence.put("statusCode", status); evidence.put("responseBytes", bytes.length);
                    evidence.put("contentType", contentType);
                    evidence.put("durationMs", elapsed(started));
                    return result(name, invocationId, result, true, metadata, evidence, null);
                } catch (org.apache.http.conn.ConnectionPoolTimeoutException exhausted) {
                    throw new HttpFailure("HTTP_POOL_TIMEOUT", "HTTP connection pool borrow timed out");
                } catch (java.net.SocketTimeoutException timeout) {
                    throw new HttpFailure("HTTP_TIMEOUT", "HTTP connect/read timed out");
                } catch (org.apache.http.conn.ConnectTimeoutException timeout) {
                    throw new HttpFailure("HTTP_TIMEOUT", "HTTP connect timed out");
                } finally {
                    if (deadlineTask != null) deadlineTask.cancel(false);
                }
            }
            throw new HttpFailure("HTTP_REDIRECT", "HTTP redirect limit exceeded");
        } catch (Exception error) {
            String type = deadlineExpired.get() ? "HTTP_TIMEOUT" : error instanceof HttpFailure ? ((HttpFailure) error).type
                    : error instanceof IllegalArgumentException ? "HTTP_ARGUMENT" : "HTTP_TRANSPORT";
            String message = deadlineExpired.get() ? "HTTP Action deadline expired during request"
                    : error instanceof HttpFailure || error instanceof IllegalArgumentException
                    ? safeMessage(error, type) : "HTTP request failed (" + error.getClass().getSimpleName() + ")";
            Map<String, Object> diagnostic = new LinkedHashMap<String, Object>();
            diagnostic.put("type", type); diagnostic.put("message", message);
            metadata.put("error", diagnostic);
            evidence.put("error", diagnostic); evidence.put("durationMs", elapsed(started));
            return result(name, invocationId, null, false, metadata, evidence, diagnostic);
        }
    }

    private ToolInvocationResult result(String name, String id, Object body, boolean success,
                                        Map<String, Object> metadata, Map<String, Object> evidence,
                                        Map<String, Object> diagnostic) {
        Map<String, Object> invocation = new LinkedHashMap<String, Object>();
        invocation.put("id", id); invocation.put("type", "http"); invocation.put("name", name);
        invocation.put("status", success ? "PASS" : "ERROR");
        invocation.put("durationMs", evidence.get("durationMs"));
        for (String key : new String[]{"httpHelper", "method", "url", "statusCode", "responseBytes", "requestBytes", "contentType"})
            if (metadata.containsKey(key)) invocation.put(key, metadata.get(key));
        if (diagnostic != null) invocation.put("error", diagnostic);
        invocation.put("HTTP", evidence);
        ActionExecutionResult operation = new ActionExecutionResult(body,
                ActionExecutionResult.evidence("http", evidence), success, diagnostic, elapsedFrom(evidence), metadata);
        return new ToolInvocationResult(name, id, body, invocation, success, operation);
    }

    private Request request(HttpHelperConfig helper, String operation, Map<String, Object> args,
                            CaseRuntimeContext context, String format) throws Exception {
        for (String key : args.keySet()) if (!("method".equals(key) || "path".equals(key) || "query".equals(key)
                || "headers".equals(key) || "file".equals(key) || "body".equals(key) || "contentType".equals(key)
                || "connectTimeoutMs".equals(key) || "readTimeoutMs".equals(key)
                || "connectionRequestTimeoutMs".equals(key) || "followRedirects".equals(key)))
            throw new HttpFailure("HTTP_ARGUMENT", "Unknown HTTP request argument: " + key);
        String method = "request".equalsIgnoreCase(operation) ? string(args.get("method"), "method") : operation.toUpperCase(Locale.ROOT);
        method = method.toUpperCase(Locale.ROOT);
        if (!("GET".equals(method) || "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "DELETE".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)))
            throw new HttpFailure("HTTP_ARGUMENT", "Unsupported HTTP method: " + method);
        if (!"request".equalsIgnoreCase(operation) && args.containsKey("method"))
            throw new HttpFailure("HTTP_ARGUMENT", "method is only valid for http.<id>.request");
        if (args.containsKey("body") && args.containsKey("file"))
            throw new HttpFailure("HTTP_ARGUMENT", "HTTP body and file are mutually exclusive");
        if (("GET".equals(method) || "HEAD".equals(method)) && (args.containsKey("body") || args.containsKey("file")))
            throw new HttpFailure("HTTP_ARGUMENT", "HTTP GET/HEAD do not accept a request body");
        String path = args.get("path") == null ? "" : string(args.get("path"), "path");
        if (path.startsWith("//") || path.contains("?") || path.contains("#"))
            throw new HttpFailure("HTTP_ARGUMENT", "HTTP path must be relative and query/fragment-free");
        URI relative;
        try { relative = new URI(path); }
        catch (URISyntaxException invalid) { throw new HttpFailure("HTTP_ARGUMENT", "Invalid HTTP request path"); }
        if (relative.isAbsolute() || relative.getRawAuthority() != null)
            throw new HttpFailure("HTTP_ARGUMENT", "Absolute per-call HTTP URLs are forbidden");
        URI url = helper.baseUrl().resolve(relative);
        if (!sameOrigin(helper.baseUrl(), url)) throw new HttpFailure("HTTP_ARGUMENT", "HTTP path escapes helper origin");
        org.apache.http.client.utils.URIBuilder builder = new org.apache.http.client.utils.URIBuilder(url);
        Object query = args.get("query");
        if (query != null) {
            if (!(query instanceof Map)) throw new HttpFailure("HTTP_ARGUMENT", "HTTP query must be a map");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) query).entrySet()) {
                if (entry.getValue() instanceof Iterable) for (Object value : (Iterable<?>) entry.getValue())
                    builder.addParameter(String.valueOf(entry.getKey()), String.valueOf(value));
                else builder.addParameter(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        try { url = builder.build(); }
        catch (URISyntaxException invalid) { throw new HttpFailure("HTTP_ARGUMENT", "Invalid HTTP query parameters"); }
        Map<String, String> headers = new LinkedHashMap<String, String>(helper.headers());
        Object supplied = args.get("headers");
        if (supplied != null) {
            if (!(supplied instanceof Map)) throw new HttpFailure("HTTP_ARGUMENT", "HTTP headers must be a map");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) supplied).entrySet())
                putHeader(headers, String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        if ("basic".equals(helper.authType())) {
            String credentials = helper.username() + ":" + helper.password();
            putHeader(headers, "Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        } else if ("bearer".equals(helper.authType())) putHeader(headers, "Authorization", "Bearer " + helper.token());
        if (args.get("contentType") != null) putHeader(headers, "Content-Type", string(args.get("contentType"), "contentType"));
        byte[] body = null;
        if (args.containsKey("file")) {
            try { body = Files.readAllBytes(payloadFile(string(args.get("file"), "file"), context)); }
            catch (IOException invalid) { throw new HttpFailure("HTTP_ARGUMENT", "HTTP request file is missing or unsafe"); }
        }
        else if (args.containsKey("body")) {
            Object suppliedBody = args.get("body");
            body = suppliedBody instanceof byte[] ? ((byte[]) suppliedBody).clone()
                    : (suppliedBody instanceof String ? (String) suppliedBody : JsonSupport.write(suppliedBody)).getBytes(StandardCharsets.UTF_8);
            if (!(suppliedBody instanceof String) && !(suppliedBody instanceof byte[]) && !containsHeader(headers, "Content-Type"))
                putHeader(headers, "Content-Type", "application/json; charset=UTF-8");
        }
        String selected = format == null || format.trim().isEmpty() ? "text" : format.toLowerCase(Locale.ROOT);
        if (!("raw".equals(selected) || "text".equals(selected) || "json".equals(selected)
                || "yaml".equals(selected) || "xml".equals(selected)))
            throw new HttpFailure("HTTP_ARGUMENT", "Unsupported HTTP result format");
        boolean redirects = args.get("followRedirects") == null ? helper.followRedirects() : bool(args.get("followRedirects"), "followRedirects");
        return new Request(method, url, headers, body, selected, redirects,
                timeout(args.get("connectionRequestTimeoutMs")), timeout(args.get("connectTimeoutMs")), timeout(args.get("readTimeoutMs")));
    }

    private static void putHeader(Map<String, String> headers, String name, String value) {
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value.contains("\r") || value.contains("\n"))
            throw new HttpFailure("HTTP_ARGUMENT", "Invalid HTTP header name or value");
        String previous = null;
        for (String key : headers.keySet()) if (key.equalsIgnoreCase(name)) { previous = key; break; }
        if (previous != null) headers.remove(previous);
        headers.put(name, value);
    }
    private static boolean containsHeader(Map<String, String> headers, String name) {
        for (String key : headers.keySet()) if (key.equalsIgnoreCase(name)) return true;
        return false;
    }
    private Path payloadFile(String value, CaseRuntimeContext context) throws IOException {
        Path file = Paths.get(value);
        Path logical = file.isAbsolute() ? file.normalize() : context.caseOutputDirectory().resolve(file).normalize();
        if (Files.isSymbolicLink(logical) || !Files.isRegularFile(logical, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("HTTP request file is missing or unsafe");
        Path real = logical.toRealPath();
        Path caseRoot = context.caseOutputDirectory().toRealPath();
        Path project = projectRoot.toRealPath();
        if (!real.startsWith(caseRoot) && !real.startsWith(project)) throw new IOException("HTTP request file escapes ATT package");
        return real;
    }
    private Object decode(byte[] bytes, String format, String contentType) throws Exception {
        if ("raw".equals(format)) return bytes;
        Charset charset = StandardCharsets.UTF_8;
        if (!contentType.isEmpty()) {
            try {
                org.apache.http.entity.ContentType parsed = org.apache.http.entity.ContentType.parse(contentType);
                if (parsed.getCharset() != null) charset = parsed.getCharset();
            } catch (Exception invalidCharset) { throw new HttpFailure("HTTP_FORMAT", "Invalid HTTP response charset"); }
        }
        String text = new String(bytes, charset);
        if ("text".equals(format)) return text;
        try { return new ToolInvoker(projectRoot, config).parseOutput(text, format); }
        catch (Exception invalidBody) { throw new HttpFailure("HTTP_FORMAT", "HTTP response is not valid " + format); }
    }
    private static Map<String, List<String>> responseHeaders(HttpResponse response,
                                                              Map<String, String> requestHeaders,
                                                              HttpHelperConfig helper) {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        for (Header header : response.getAllHeaders()) {
            String name = header.getName();
            String canonical = null;
            for (String key : headers.keySet()) if (key.equalsIgnoreCase(name)) { canonical = key; break; }
            if (canonical == null) { canonical = name; headers.put(name, new ArrayList<String>()); }
            boolean secret = name.equalsIgnoreCase("Set-Cookie") || name.equalsIgnoreCase("Authorization")
                    || name.equalsIgnoreCase("Proxy-Authorization") || name.toLowerCase(Locale.ROOT).contains("token")
                    || name.toLowerCase(Locale.ROOT).contains("secret") || name.toLowerCase(Locale.ROOT).contains("api-key")
                    || name.toLowerCase(Locale.ROOT).contains("cookie") || name.toLowerCase(Locale.ROOT).contains("password");
            String value = header.getValue();
            if (!helper.password().isEmpty() && value.contains(helper.password())) secret = true;
            if (!helper.token().isEmpty() && value.contains(helper.token())) secret = true;
            for (Map.Entry<String, String> sent : requestHeaders.entrySet()) {
                if (!sent.getValue().isEmpty() && value.contains(sent.getValue())) secret = true;
            }
            headers.get(canonical).add(secret ? "<redacted>" : value);
        }
        return headers;
    }
    private Client client(HttpHelperConfig helper) throws Exception {
        Client existing = clients.get(helper.id().toLowerCase(Locale.ROOT));
        if (existing != null) return existing;
        Client created = new Client(helper);
        Client raced = clients.putIfAbsent(helper.id().toLowerCase(Locale.ROOT), created);
        if (raced != null) { created.close(); return raced; }
        if (closed.get()) { created.close(); throw new HttpFailure("HTTP_CLOSED", "HTTP resources are closed"); }
        return created;
    }
    private static int capped(int configured, Integer override, long deadline) {
        int requested = override == null ? configured : override.intValue();
        if (deadline == Long.MAX_VALUE) return requested;
        long remaining = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
        return (int) Math.max(1L, Math.min(requested, remaining));
    }
    private static void ensureDeadline(long deadline, String phase) {
        if (deadline != Long.MAX_VALUE && System.nanoTime() >= deadline)
            throw new HttpFailure("HTTP_TIMEOUT", "HTTP Action deadline expired during " + phase);
    }
    private static Integer timeout(Object value) {
        if (value == null) return null;
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()
                || ((Number) value).intValue() < 1 || ((Number) value).intValue() > 3600000)
            throw new HttpFailure("HTTP_ARGUMENT", "HTTP timeout override must be 1..3600000 ms");
        return ((Number) value).intValue();
    }
    private static boolean bool(Object value, String field) {
        if (!(value instanceof Boolean)) throw new HttpFailure("HTTP_ARGUMENT", "HTTP " + field + " must be boolean");
        return (Boolean) value;
    }
    private static String string(Object value, String field) {
        if (!(value instanceof String)) throw new HttpFailure("HTTP_ARGUMENT", "HTTP " + field + " must be a string");
        return (String) value;
    }
    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && b.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost() != null && b.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }
    private static int effectivePort(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80); }
    private static String safeUrl(URI uri, HttpHelperConfig helper) {
        String value = uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath();
        if (!helper.password().isEmpty()) value = value.replace(helper.password(), "<redacted>");
        if (!helper.token().isEmpty()) value = value.replace(helper.token(), "<redacted>");
        return value;
    }
    private static boolean redirectStatus(int status) { return status == 301 || status == 302 || status == 303 || status == 307 || status == 308; }
    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static long elapsedFrom(Map<String, Object> evidence) { return ((Number) evidence.get("durationMs")).longValue(); }
    private static String safeMessage(Exception error, String type) {
        return "HTTP_ARGUMENT".equals(type) || error instanceof HttpFailure ? error.getMessage() : type;
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Client client : clients.values()) client.close();
        clients.clear();
    }
    private static final class Request {
        private final String method, format;
        private final URI url;
        private final Map<String, String> headers;
        private final byte[] body;
        private final boolean followRedirects;
        private final Integer poolTimeoutMs, connectTimeoutMs, readTimeoutMs;
        private Request(String method, URI url, Map<String, String> headers, byte[] body, String format,
                        boolean followRedirects, Integer pool, Integer connect, Integer read) {
            this.method = method; this.url = url; this.headers = headers; this.body = body;
            this.format = format; this.followRedirects = followRedirects;
            this.poolTimeoutMs = pool; this.connectTimeoutMs = connect; this.readTimeoutMs = read;
        }
    }
    private static final class AnyMethod extends HttpEntityEnclosingRequestBase {
        private final String method;
        private AnyMethod(String method, URI url) { this.method = method; setURI(url); }
        @Override public String getMethod() { return method; }
    }
    private static final class Client implements AutoCloseable {
        private final PoolingHttpClientConnectionManager manager;
        private final CloseableHttpClient http;
        private Client(HttpHelperConfig helper) throws Exception {
            SSLContextBuilder builder = SSLContextBuilder.create();
            if (helper.trustStore() != null)
                builder.loadTrustMaterial(helper.trustStore().toFile(), helper.trustStorePassword().toCharArray());
            Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("http", PlainConnectionSocketFactory.getSocketFactory())
                    .register("https", new SSLConnectionSocketFactory(builder.build())).build();
            manager = new PoolingHttpClientConnectionManager(registry);
            manager.setMaxTotal(helper.maxConnections());
            manager.setDefaultMaxPerRoute(helper.maxConnectionsPerRoute());
            http = HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries()
                    .disableCookieManagement().disableRedirectHandling()
                    .setKeepAliveStrategy((response, context) -> helper.keepAliveMs()).build();
        }
        @Override public void close() {
            try { http.close(); } catch (IOException ignored) { }
            manager.shutdown();
        }
    }
    private static final class HttpFailure extends IllegalArgumentException {
        private final String type;
        private HttpFailure(String type, String message) { super(message); this.type = type; }
    }
}
