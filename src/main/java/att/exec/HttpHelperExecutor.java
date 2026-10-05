package att.exec;

import att.config.FrameworkConfig;
import att.config.HttpHelperConfig;
import att.core.CaseRuntimeContext;
import att.core.CaseExecutionLog;
import att.core.InternalExceptionLogger;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import att.template.TypedValueFormatter;

/** Run-owned, thread-safe HTTPHelper transport with bounded reusable connections. */
public final class HttpHelperExecutor implements AutoCloseable {
    /** Default response ceiling; prevents an untrusted body from becoming an unbounded byte array. */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private static final ScheduledExecutorService DEADLINE_ABORTER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "att-http-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private final FrameworkConfig config;
    private final Map<String, Client> clients = new ConcurrentHashMap<String, Client>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public HttpHelperExecutor(Path projectRoot, FrameworkConfig config) {
        this.config = config;
    }

    public ToolInvocationResult execute(String logicalId, String operation, Map<String, Object> arguments,
                                        CaseRuntimeContext context, Long actionTimeoutMs, String invocationId,
                                        String format) {
        // The common Action result.format is presentation-only. The HTTP
        // response's native type is inferred from its media type below.
        return execute(logicalId, operation, arguments, context, actionTimeoutMs, invocationId, format, null);
    }

    public ToolInvocationResult execute(String logicalId, String operation, Map<String, Object> arguments,
                                        CaseRuntimeContext context, Long actionTimeoutMs, String invocationId) {
        return execute(logicalId, operation, arguments, context, actionTimeoutMs, invocationId, null, null);
    }

    public ToolInvocationResult execute(String logicalId, String operation, Map<String, Object> arguments,
                                        CaseRuntimeContext context, Long actionTimeoutMs, String invocationId,
                                        String format, CaseExecutionLog log) {
        return execute(logicalId, operation, arguments, context, actionTimeoutMs, invocationId, format, log, null);
    }

    /** Executes against the helper identity bound by the Load execution plan. */
    public ToolInvocationResult execute(String logicalId, String operation, Map<String, Object> arguments,
                                        CaseRuntimeContext context, Long actionTimeoutMs, String invocationId,
                                        String format, CaseExecutionLog log, HttpHelperConfig resolvedHelper) {
        String name = "http." + logicalId + "." + operation;
        long started = System.nanoTime();
        long deadline = actionTimeoutMs == null ? Long.MAX_VALUE
                : started + TimeUnit.MILLISECONDS.toNanos(actionTimeoutMs.longValue());
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("httpHelper", logicalId);
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("id", invocationId); evidence.put("httpHelper", logicalId);
        AtomicBoolean deadlineExpired = new AtomicBoolean(false);
        HttpHelperConfig helperForDiagnostics = null;
        String phase = "http.resolve";
        try {
            if (closed.get()) throw new HttpFailure("HTTP_CLOSED", "HTTP resources are closed");
            HttpHelperConfig helper = resolvedHelper == null ? config.httpHelper(logicalId) : resolvedHelper;
            if (helper == null) throw new HttpFailure("HTTP_CONFIG", "Unknown HTTP helper: " + logicalId);
            helperForDiagnostics = helper;
            if (log != null) log.registerSecretRedactions(diagnosticSecrets(helper, arguments));
            Map<String, Object> args = arguments == null ? Collections.<String, Object>emptyMap() : arguments;
            phase = "http.request";
            Request request = request(helper, operation, args);
            metadata.put("responseFormat", request.responseFormat);
            evidence.put("responseFormat", request.responseFormat);
            metadata.put("method", request.method);
            metadata.put("url", safeUrl(request.url, helper));
            evidence.put("method", request.method); evidence.put("url", safeUrl(request.url, helper));
            evidence.put("requestBytes", request.body == null ? 0 : request.body.length);
            phase = "http.client";
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
                phase = "http.execute";
                try (CloseableHttpResponse response = client.http.execute(call)) {
                    client.observe(client.manager.getTotalStats());
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
                    metadata.put("statusCode", status);
                    metadata.put("requestBytes", request.body == null ? 0 : request.body.length);
                    evidence.put("statusCode", status);
                    evidence.put("requestBytes", request.body == null ? 0 : request.body.length);
                    int maxResponseBytes = helper.maxResponseBytes();
                    long declaredLength = entity == null ? 0L : entity.getContentLength();
                    if (declaredLength > maxResponseBytes)
                        throw new HttpFailure("HTTP_RESPONSE_TOO_LARGE", "HTTP response exceeds maxResponseBytes=" + maxResponseBytes);
                    byte[] bytes = entity == null ? new byte[0] : readBounded(entity.getContent(), maxResponseBytes);
                    ensureDeadline(deadline, "read");
                    String contentType = response.getFirstHeader("Content-Type") == null ? "" : response.getFirstHeader("Content-Type").getValue();
                    metadata.put("method", method); metadata.put("url", safeUrl(url, helper));
                    metadata.put("statusCode", status);
                    metadata.put("reasonPhrase", response.getStatusLine().getReasonPhrase());
                    metadata.put("contentType", contentType);
                    metadata.put("responseBytes", bytes.length);
                    metadata.put("requestBytes", request.body == null ? 0 : request.body.length);
                    metadata.put("headers", responseHeaders(response, request.headers, helper));
                    evidence.put("method", method); evidence.put("url", safeUrl(url, helper));
                    evidence.put("statusCode", status); evidence.put("responseBytes", bytes.length);
                    evidence.put("requestBytes", request.body == null ? 0 : request.body.length);
                    evidence.put("contentType", contentType);
                    evidence.put("durationMs", elapsed(started));
                    phase = "http.decode";
                    String resolvedResponseFormat = resolveResponseFormat(contentType, request.responseFormat);
                    metadata.put("resolvedResponseFormat", resolvedResponseFormat);
                    evidence.put("resolvedResponseFormat", resolvedResponseFormat);
                    Object result = decode(bytes, contentType, resolvedResponseFormat);
                    if (context != null) context.recordResourceOutput(helper.evidenceOutput(), result, evidence, diagnosticSecrets(helper, arguments));
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
            List<String> secrets = diagnosticSecrets(helperForDiagnostics, arguments);
            if (InternalExceptionLogger.isInternal(error)) {
                InternalExceptionLogger.logIfInternal(log, phase, error, secrets);
                diagnostic.put("internal", Boolean.TRUE);
                diagnostic.put("phase", phase);
                diagnostic.put("message", InternalExceptionLogger.sanitize(message, secrets));
            }
            metadata.put("error", diagnostic);
            evidence.put("error", diagnostic); evidence.put("durationMs", elapsed(started));
            return result(name, invocationId, null, false, metadata, evidence, diagnostic);
        }
    }

    private byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, limit + 1 - output.size()))) >= 0) {
            if (output.size() + count > limit)
                throw new HttpFailure("HTTP_RESPONSE_TOO_LARGE", "HTTP response exceeds maxResponseBytes=" + limit);
            output.write(buffer, 0, count);
            if (output.size() == limit) {
                if (input.read() >= 0) throw new HttpFailure("HTTP_RESPONSE_TOO_LARGE", "HTTP response exceeds maxResponseBytes=" + limit);
                break;
            }
        }
        return output.toByteArray();
    }

    private List<String> diagnosticSecrets(HttpHelperConfig helper, Map<String, Object> arguments) {
        List<String> values = new ArrayList<String>();
        if (helper != null) {
            addSecret(values, helper.password()); addSecret(values, helper.token());
            addSecret(values, helper.trustStorePassword());
            for (Map.Entry<String, String> header : helper.headers().entrySet())
                if (secretHeader(header.getKey())) addSecret(values, header.getValue());
        }
        if (arguments != null && arguments.get("headers") instanceof Map) {
            for (Map.Entry<?, ?> header : ((Map<?, ?>) arguments.get("headers")).entrySet())
                if (secretHeader(String.valueOf(header.getKey()))) addSecret(values, String.valueOf(header.getValue()));
        }
        return values;
    }

    private void addSecret(List<String> values, String value) {
        if (value != null && !value.isEmpty()) values.add(value);
    }

    private ToolInvocationResult result(String name, String id, Object body, boolean success,
                                        Map<String, Object> metadata, Map<String, Object> evidence,
                                        Map<String, Object> diagnostic) {
        Map<String, Object> invocation = new LinkedHashMap<String, Object>();
        invocation.put("id", id); invocation.put("type", "http"); invocation.put("name", name);
        invocation.put("status", success ? "PASS" : "ERROR");
        invocation.put("durationMs", evidence.get("durationMs"));
        for (String key : new String[]{"httpHelper", "method", "url", "statusCode", "responseBytes", "requestBytes", "contentType", "responseFormat", "resolvedResponseFormat"})
            if (metadata.containsKey(key)) invocation.put(key, metadata.get(key));
        if (diagnostic != null) invocation.put("error", diagnostic);
        invocation.put("HTTP", evidence);
        ActionExecutionResult operation = new ActionExecutionResult(body,
                ActionExecutionResult.evidence("http", evidence), success, diagnostic, elapsedFrom(evidence), metadata);
        return new ToolInvocationResult(name, id, body, invocation, success, operation);
    }

    private Request request(HttpHelperConfig helper, String operation, Map<String, Object> args) throws Exception {
        for (String key : args.keySet()) if (!("method".equals(key) || "path".equals(key) || "query".equals(key)
                || "headers".equals(key) || "body".equals(key) || "contentType".equals(key)
                || "connectTimeoutMs".equals(key) || "readTimeoutMs".equals(key)
                || "connectionRequestTimeoutMs".equals(key) || "followRedirects".equals(key)
                || "responseFormat".equals(key) || "requestFormat".equals(key)))
            throw new HttpFailure("HTTP_ARGUMENT", "Unknown HTTP request argument: " + key);
        Object requestedFormat = args.get("responseFormat");
        String responseFormat = requestedFormat == null ? helper.responseFormat() : string(requestedFormat, "responseFormat");
        if (!("auto".equals(responseFormat) || "text".equals(responseFormat) || "json".equals(responseFormat)
                || "yaml".equals(responseFormat) || "xml".equals(responseFormat)))
            throw new HttpFailure("HTTP_ARGUMENT", "responseFormat must be auto, text, json, yaml, or xml");
        String method = "request".equalsIgnoreCase(operation) ? string(args.get("method"), "method") : operation.toUpperCase(Locale.ROOT);
        method = method.toUpperCase(Locale.ROOT);
        if (!("GET".equals(method) || "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)
                || "DELETE".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)))
            throw new HttpFailure("HTTP_ARGUMENT", "Unsupported HTTP method: " + method);
        if (!"request".equalsIgnoreCase(operation) && args.containsKey("method"))
            throw new HttpFailure("HTTP_ARGUMENT", "method is only valid for http.<id>.request");
        if (args.containsKey("requestFormat") && !args.containsKey("body"))
            throw new HttpFailure("HTTP_ARGUMENT", "requestFormat requires a structured body");
        if (("GET".equals(method) || "HEAD".equals(method)) && args.containsKey("body"))
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
        if (args.containsKey("body")) {
            Object suppliedBody = args.get("body");
            boolean structured = suppliedBody instanceof Map || suppliedBody instanceof Iterable
                    || suppliedBody != null && suppliedBody.getClass().isArray() && !(suppliedBody instanceof byte[]);
            String requestFormat = null;
            if (args.containsKey("requestFormat")) {
                if (args.get("requestFormat") == null)
                    throw new HttpFailure("HTTP_ARGUMENT", "requestFormat must be text, json, yaml, or xml");
                requestFormat = string(args.get("requestFormat"), "requestFormat");
                if (requestFormat.trim().isEmpty()
                        || !java.util.Arrays.asList("text", "json", "yaml", "xml").contains(requestFormat))
                    throw new HttpFailure("HTTP_ARGUMENT", "requestFormat must be text, json, yaml, or xml");
            }
            if (structured && requestFormat == null)
                throw new HttpFailure("HTTP_ARGUMENT", "A Map/List HTTP body requires requestFormat");
            if (!structured && requestFormat != null)
                throw new HttpFailure("HTTP_ARGUMENT", "requestFormat is valid only for a Map/List HTTP body");
            String contentType = header(headers, "Content-Type");
            java.nio.charset.Charset charset = contentType == null ? StandardCharsets.UTF_8 : contentCharset(contentType);
            if (structured) {
                body = new TypedValueFormatter().format(suppliedBody, requestFormat).getBytes(charset);
                if (contentType == null) putHeader(headers, "Content-Type", mediaType(requestFormat) + "; charset=" + charset.name());
            } else {
                body = suppliedBody instanceof byte[] ? ((byte[]) suppliedBody).clone()
                        : String.valueOf(suppliedBody == null ? "" : suppliedBody).getBytes(charset);
                if (contentType == null && !(suppliedBody instanceof byte[])) putHeader(headers, "Content-Type", "text/plain; charset=" + charset.name());
            }
        }
        boolean redirects = args.get("followRedirects") == null ? helper.followRedirects() : bool(args.get("followRedirects"), "followRedirects");
        return new Request(method, url, headers, body, redirects, responseFormat,
                timeout(args.get("connectionRequestTimeoutMs")), timeout(args.get("connectTimeoutMs")), timeout(args.get("readTimeoutMs")));
    }

    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        return null;
    }
    private static java.nio.charset.Charset contentCharset(String contentType) {
        try {
            org.apache.http.entity.ContentType parsed = org.apache.http.entity.ContentType.parse(contentType);
            return parsed.getCharset() == null ? StandardCharsets.UTF_8 : parsed.getCharset();
        } catch (Exception invalid) { throw new HttpFailure("HTTP_ARGUMENT", "Invalid request Content-Type charset"); }
    }
    private static String mediaType(String format) {
        if ("json".equals(format)) return "application/json";
        if ("yaml".equals(format)) return "application/yaml";
        if ("xml".equals(format)) return "application/xml";
        return "text/plain";
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
    private String resolveResponseFormat(String contentType, String requestedFormat) {
        if (!"auto".equals(requestedFormat)) return requestedFormat;
        String mediaType = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if ("application/octet-stream".equals(mediaType))
            throw new HttpFailure("HTTP_FORMAT", "Binary HTTP response cannot be published as a common typed Action result");
        if ("application/json".equals(mediaType) || mediaType.endsWith("+json")) return "json";
        if ("application/yaml".equals(mediaType) || "text/yaml".equals(mediaType)
                || "application/x-yaml".equals(mediaType) || "text/x-yaml".equals(mediaType)) return "yaml";
        if ("application/xml".equals(mediaType) || "text/xml".equals(mediaType) || mediaType.endsWith("+xml")) return "xml";
        return "text";
    }

    private Object decode(byte[] bytes, String contentType, String format) throws Exception {
        Charset charset = StandardCharsets.UTF_8;
        if (!contentType.isEmpty()) {
            try {
                org.apache.http.entity.ContentType parsed = org.apache.http.entity.ContentType.parse(contentType);
                if (parsed.getCharset() != null) charset = parsed.getCharset();
            } catch (Exception invalidCharset) { throw new HttpFailure("HTTP_FORMAT", "Invalid HTTP response charset"); }
        }
        if ("text".equals(format)) return new String(bytes, charset);
        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(bytes), charset)) {
            return ToolInvoker.parseOutput(reader, format, config.xmlNamespaceMode());
        }
        catch (Exception invalidBody) { throw new HttpFailure("HTTP_RESULT_PARSE_ERROR", "HTTP response body is not valid " + format, invalidBody); }
    }
    private static Map<String, List<String>> responseHeaders(HttpResponse response,
                                                              Map<String, String> requestHeaders,
                                                              HttpHelperConfig helper) {
        Map<String, List<String>> headers = new LinkedHashMap<String, List<String>>();
        for (Header header : response.getAllHeaders()) {
            String name = header.getName();
            String normalized = name.toLowerCase(Locale.ROOT);
            boolean secret = name.equalsIgnoreCase("Set-Cookie") || name.equalsIgnoreCase("Authorization")
                    || name.equalsIgnoreCase("Proxy-Authorization") || name.toLowerCase(Locale.ROOT).contains("token")
                    || name.toLowerCase(Locale.ROOT).contains("secret") || name.toLowerCase(Locale.ROOT).contains("api-key")
                    || name.toLowerCase(Locale.ROOT).contains("cookie") || name.toLowerCase(Locale.ROOT).contains("password");
            String value = header.getValue();
            if (!helper.password().isEmpty() && value.contains(helper.password())) secret = true;
            if (!helper.token().isEmpty() && value.contains(helper.token())) secret = true;
            for (Map.Entry<String, String> sent : requestHeaders.entrySet()) {
                if (secretHeader(sent.getKey()) && !sent.getValue().isEmpty() && value.contains(sent.getValue())) secret = true;
            }
            headers.computeIfAbsent(normalized, ignored -> new ArrayList<String>())
                    .add(secret ? "<redacted>" : value);
        }
        return headers;
    }
    private static boolean secretHeader(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.equals("authorization") || normalized.equals("proxy-authorization")
                || normalized.contains("token") || normalized.contains("secret")
                || normalized.contains("api-key") || normalized.contains("apikey")
                || normalized.contains("password") || normalized.contains("cookie");
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
    /** Current pooled HTTP connection counts, grouped by configured helper. */
    public Map<String, Object> metrics() {
        observeMetrics();
        Map<String, Object> result = new java.util.TreeMap<String, Object>();
        for (Map.Entry<String, Client> entry : clients.entrySet()) {
            org.apache.http.pool.PoolStats stats = entry.getValue().manager.getTotalStats();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("active", stats.getLeased()); item.put("idle", stats.getAvailable());
            item.put("waiting", stats.getPending()); item.put("max", stats.getMax());
            item.put("peakActive", entry.getValue().peakActive.get());
            item.put("peakIdle", entry.getValue().peakIdle.get());
            item.put("peakWaiting", entry.getValue().peakWaiting.get());
            result.put(entry.getKey(), item);
        }
        return result;
    }
    /** Updates high-water marks without constructing the report snapshot maps. */
    public void observeMetrics() {
        for (Client client : clients.values()) client.observe(client.manager.getTotalStats());
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
        private final String method;
        private final URI url;
        private final Map<String, String> headers;
        private final byte[] body;
        private final boolean followRedirects;
        private final String responseFormat;
        private final Integer poolTimeoutMs, connectTimeoutMs, readTimeoutMs;
        private Request(String method, URI url, Map<String, String> headers, byte[] body,
                        boolean followRedirects, String responseFormat, Integer pool, Integer connect, Integer read) {
            this.method = method; this.url = url; this.headers = headers; this.body = body;
            this.followRedirects = followRedirects;
            this.responseFormat = responseFormat;
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
        private final java.util.concurrent.atomic.AtomicInteger peakActive = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger peakIdle = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger peakWaiting = new java.util.concurrent.atomic.AtomicInteger();
        private void observe(org.apache.http.pool.PoolStats stats) {
            peakActive.accumulateAndGet(stats.getLeased(), Math::max);
            peakIdle.accumulateAndGet(stats.getAvailable(), Math::max);
            peakWaiting.accumulateAndGet(stats.getPending(), Math::max);
        }
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
        private HttpFailure(String type, String message, Throwable cause) { super(message, cause); this.type = type; }
    }
}
