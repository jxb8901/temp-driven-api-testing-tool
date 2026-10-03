package att.exec;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.ResultStatus;
import att.core.StageCaseData;
import att.core.TestCase;
import att.core.ValidationResult;
import att.template.StageTemplate;
import att.template.StageTemplateRunner;
import att.template.TemplateAction;
import att.template.UnifiedTemplateEngine;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class HttpHelperExecutorTest {
    @TempDir Path root;
    private HttpServer server;
    private ExecutorService workers;
    private final AtomicInteger hits = new AtomicInteger();
    private final CountDownLatch holding = new CountDownLatch(1);
    private final AtomicReference<String> observedAuthorization = new AtomicReference<String>();
    private final AtomicReference<String> observedChannel = new AtomicReference<String>();
    private final AtomicReference<String> observedQuery = new AtomicReference<String>();

    @AfterEach void close() {
        if (server != null) server.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    private String start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool();
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.incrementAndGet();
            observedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            observedChannel.set(exchange.getRequestHeaders().getFirst("X-Channel"));
            observedQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] request = read(exchange);
            if ("/drip".equals(path)) {
                try {
                    exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
                    exchange.sendResponseHeaders(200, 4);
                    for (byte part : new byte[]{'a', 'b', 'c', 'd'}) {
                        exchange.getResponseBody().write(part);
                        exchange.getResponseBody().flush();
                        Thread.sleep(120);
                    }
                } catch (Exception ignored) { /* The client may abort when its Action deadline expires. */ }
                exchange.close();
                return;
            }
            if ("/slow".equals(path) || "/hold".equals(path)) {
                if ("/hold".equals(path)) holding.countDown();
                try { Thread.sleep(250); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            if ("/redirect".equals(path)) {
                exchange.getResponseHeaders().add("Location", "/json");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            byte[] response;
            if ("/json".equals(path)) response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            else if ("/yaml".equals(path)) response = "ok: true\n".getBytes(StandardCharsets.UTF_8);
            else if ("/xml".equals(path)) response = "<root><ok>true</ok></root>".getBytes(StandardCharsets.UTF_8);
            else if ("/status".equals(path)) response = "missing".getBytes(StandardCharsets.UTF_8);
            else if ("/status500".equals(path)) response = "server error".getBytes(StandardCharsets.UTF_8);
            else if ("/oversize".equals(path)) response = new byte[HttpHelperExecutor.DEFAULT_MAX_RESPONSE_BYTES + 1];
            else if ("/boundary".equals(path)) response = new byte[HttpHelperExecutor.DEFAULT_MAX_RESPONSE_BYTES];
            else if ("/chunked-oversize".equals(path)) {
                exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(200, 0);
                try { exchange.getResponseBody().write(new byte[HttpHelperExecutor.DEFAULT_MAX_RESPONSE_BYTES + 1]); }
                catch (Exception ignored) { }
                exchange.close();
                return;
            }
            else response = request.length == 0 ? exchange.getRequestMethod().getBytes(StandardCharsets.UTF_8) : request;
            String responseType = "/json".equals(path) ? "application/json; charset=UTF-8"
                    : "/yaml".equals(path) ? "application/yaml; charset=UTF-8"
                    : "/xml".equals(path) ? "application/xml; charset=UTF-8"
                    : request.length > 0 ? "application/octet-stream" : "text/plain; charset=UTF-8";
            exchange.getResponseHeaders().add("Content-Type", responseType);
            exchange.getResponseHeaders().add("X-Multi", "one");
            exchange.getResponseHeaders().add("X-Multi", "two");
            exchange.getResponseHeaders().add("Set-Cookie", "private=secret");
            String echoedToken = exchange.getRequestHeaders().getFirst("X-Token");
            if (echoedToken != null) exchange.getResponseHeaders().add("X-Echo", echoedToken);
            exchange.sendResponseHeaders("/status".equals(path) ? 404 : "/status500".equals(path) ? 500 : 200,
                    "HEAD".equals(exchange.getRequestMethod()) ? -1 : response.length);
            if (!"HEAD".equals(exchange.getRequestMethod())) exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static byte[] read(HttpExchange exchange) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] part = new byte[1024]; int count;
        while ((count = exchange.getRequestBody().read(part)) >= 0) if (count > 0) buffer.write(part, 0, count);
        return buffer.toByteArray();
    }

    private FrameworkConfig configuration(String url, String pool) throws Exception {
        return configuration(url, pool, null);
    }

    private FrameworkConfig configuration(String url, String pool, String responseFormat) throws Exception {
        return configuration(url, pool, responseFormat, 1000);
    }

    private FrameworkConfig configuration(String url, String pool, String responseFormat, int readTimeoutMs) throws Exception {
        att.TestSchemas.install(root);
        Files.createDirectories(root.resolve("config/httphelpers"));
        Files.write(root.resolve("config/httphelpers/sit.yaml"), ("schemaVersion: att-httphelper/v1.1\nid: paymentApi\nbaseUrl: " + url
                + "\ndefaults:\n  headers: {Accept: application/json, X-Channel: default}\n  readTimeoutMs: " + readTimeoutMs + "\n"
                + (responseFormat == null ? "" : "  responseFormat: " + responseFormat + "\n")
                + (pool == null ? "" : "pool:\n" + pool)).getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("config/httphelpers/uat.yaml"), ("schemaVersion: att-httphelper/v1.1\nid: paymentApi\nbaseUrl: " + url
                + "/uat\n").getBytes(StandardCharsets.UTF_8));
        Path config = root.resolve("config/config.yaml");
        Files.write(config, ("schemaVersion: att-config/v2.10\nenvironment: SIT\nenvironments:\n"
                + "  SIT: {httphelpers: [config/httphelpers/sit.yaml]}\n"
                + "  UAT: {httphelpers: [config/httphelpers/uat.yaml]}\n").getBytes(StandardCharsets.UTF_8));
        return new FrameworkConfigLoader().load(config, root, "SIT");
    }

    @Test void resourceResponseFormatHasCallOverrideAndAutoPreservesContentTypeDetection() throws Exception {
        String url = start();
        FrameworkConfig config = configuration(url, null, "text");
        assertEquals("text", config.httpHelper("paymentApi").responseFormat());
        CaseRuntimeContext context = context();
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, config)) {
            ToolInvocationResult helperDefault = http.execute("paymentApi", "get", args("path", "/json"),
                    context, 1000L, "http-default-text", "xml");
            assertTrue(helperDefault.executionSuccess());
            assertEquals("{\"ok\":true}", helperDefault.output());
            assertEquals("text", helperDefault.operationResult().outputMetadata().get("responseFormat"));
            assertEquals("text", helperDefault.operationResult().outputMetadata().get("resolvedResponseFormat"));

            ToolInvocationResult callOverride = http.execute("paymentApi", "get",
                    args("path", "/json", "responseFormat", "json"), context, 1000L, "http-call-json", "text");
            assertTrue(callOverride.executionSuccess());
            assertEquals(Boolean.TRUE, ((Map<?, ?>) callOverride.output()).get("ok"));
            Map<?, ?> httpEvidence = (Map<?, ?>) callOverride.operationResult().evidence().get("http");
            Map<?, ?> invocationEvidence = (Map<?, ?>) ((java.util.List<?>) httpEvidence.get("invocations")).get(0);
            assertEquals("json", invocationEvidence.get("responseFormat"));
            assertEquals("json", invocationEvidence.get("resolvedResponseFormat"));

            ToolInvocationResult contentTypeOverride = http.execute("paymentApi", "post",
                    args("path", "/echo", "body", args("ok", true), "requestFormat", "json", "responseFormat", "json"),
                    context, 1000L, "http-octets-as-json", "yaml");
            assertTrue(contentTypeOverride.executionSuccess(), String.valueOf(contentTypeOverride.operationResult().diagnostic()));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) contentTypeOverride.output()).get("ok"));
            assertEquals("json", contentTypeOverride.operationResult().outputMetadata().get("resolvedResponseFormat"));
        }

        FrameworkConfig automatic = configuration(url, null);
        assertEquals("auto", automatic.httpHelper("paymentApi").responseFormat());
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, automatic)) {
            ToolInvocationResult auto = http.execute("paymentApi", "get", args("path", "/json"),
                    context, 1000L, "http-auto", "text");
            assertEquals(Boolean.TRUE, ((Map<?, ?>) auto.output()).get("ok"));
            assertEquals("auto", auto.operationResult().outputMetadata().get("responseFormat"));
            assertEquals("json", auto.operationResult().outputMetadata().get("resolvedResponseFormat"));
        }
    }

    @Test void responseFormatValidationAndParseFailuresAreSafeAndDistinct() throws Exception {
        String url = start();
        FrameworkConfig config = configuration(url, null, null, 5000);
        CaseRuntimeContext context = context();
        int before = hits.get();
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, config)) {
            ToolInvocationResult invalidFormat = http.execute("paymentApi", "get",
                    args("path", "/json", "responseFormat", "binary"), context, 5000L, "bad-response-format", "text");
            assertEquals("HTTP_ARGUMENT", ((Map<?, ?>) invalidFormat.operationResult().outputMetadata().get("error")).get("type"));
            assertEquals(before, hits.get());

            // This checks parsing, so allow scheduling headroom instead of testing a one-second deadline.
            ToolInvocationResult parseError = http.execute("paymentApi", "get",
                    args("path", "/status", "responseFormat", "json", "readTimeoutMs", 10000),
                    context, 10000L, "bad-json", "text");
            assertEquals("HTTP_RESULT_PARSE_ERROR", ((Map<?, ?>) parseError.operationResult().outputMetadata().get("error")).get("type"));
            assertFalse(parseError.operationResult().outputMetadata().toString().contains("missing"));
            assertEquals(404, parseError.operationResult().outputMetadata().get("statusCode"));
            assertEquals("text/plain; charset=UTF-8", parseError.operationResult().outputMetadata().get("contentType"));
            assertEquals(7, parseError.operationResult().outputMetadata().get("responseBytes"));
            Map<?, ?> httpEvidence = (Map<?, ?>) parseError.evidence().get("http");
            Map<?, ?> invocation = (Map<?, ?>) ((java.util.List<?>) httpEvidence.get("invocations")).get(0);
            assertEquals(404, invocation.get("statusCode"));
            assertEquals("text/plain; charset=UTF-8", invocation.get("contentType"));
            assertEquals(7, invocation.get("responseBytes"));
        }
    }

    @Test void rejectsDeclaredOversizedResponseAndRetainsTransportStatus() throws Exception {
        String url = start();
        FrameworkConfig config = configuration(url, null, null, 5000);
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, config)) {
            ToolInvocationResult result = http.execute("paymentApi", "get", args("path", "/oversize"),
                    context(), 5000L, "large-response", "text");
            Map<?, ?> error = (Map<?, ?>) result.operationResult().outputMetadata().get("error");
            assertEquals("HTTP_RESPONSE_TOO_LARGE", error.get("type"));
            Map<?, ?> invocation = (Map<?, ?>) ((java.util.List<?>) ((Map<?, ?>) result.evidence().get("http")).get("invocations")).get(0);
            assertEquals(200, invocation.get("statusCode"));
        }
    }

    @Test void acceptsExactResponseBoundaryAndRejectsChunkedOversize() throws Exception {
        String url = start();
        FrameworkConfig config = configuration(url, null, null, 10000);
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, config)) {
            ToolInvocationResult boundary = http.execute("paymentApi", "get", args("path", "/boundary"),
                    context(), 15000L, "response-boundary", "text");
            assertTrue(boundary.executionSuccess());
            assertEquals(HttpHelperExecutor.DEFAULT_MAX_RESPONSE_BYTES,
                    boundary.operationResult().outputMetadata().get("responseBytes"));
            ToolInvocationResult unknownLength = http.execute("paymentApi", "get", args("path", "/chunked-oversize"),
                    context(), 15000L, "chunked-oversize", "text");
            assertEquals("HTTP_RESPONSE_TOO_LARGE",
                    ((Map<?, ?>) unknownLength.operationResult().outputMetadata().get("error")).get("type"));
            Future<ToolInvocationResult> first = workers.submit(() -> http.execute("paymentApi", "get",
                    args("path", "/chunked-oversize"), context(), 15000L, "chunked-oversize-1", "text"));
            Future<ToolInvocationResult> second = workers.submit(() -> http.execute("paymentApi", "get",
                    args("path", "/chunked-oversize"), context(), 15000L, "chunked-oversize-2", "text"));
            assertEquals("HTTP_RESPONSE_TOO_LARGE", ((Map<?, ?>) first.get(20L, TimeUnit.SECONDS)
                    .operationResult().outputMetadata().get("error")).get("type"));
            assertEquals("HTTP_RESPONSE_TOO_LARGE", ((Map<?, ?>) second.get(20L, TimeUnit.SECONDS)
                    .operationResult().outputMetadata().get("error")).get("type"));
        }
    }

    @Test void structuredRequestRejectsBlankOrNullRequestFormatBeforeNetworkCall() throws Exception {
        String url = start();
        FrameworkConfig config = configuration(url, null);
        CaseRuntimeContext context = context();
        int before = hits.get();
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, config)) {
            for (Object format : Arrays.<Object>asList("", null)) {
                ToolInvocationResult invalid = http.execute("paymentApi", "post",
                        args("path", "/echo", "body", args("ok", true), "requestFormat", format),
                        context, 1000L, "blank-request-format", "text");
                assertFalse(invalid.executionSuccess());
                assertEquals("HTTP_ARGUMENT", ((Map<?, ?>) invalid.operationResult().outputMetadata().get("error")).get("type"));
            }
        }
        assertEquals(before, hits.get(), "invalid requestFormat must fail before opening an HTTP connection");
    }

    private CaseRuntimeContext context() throws Exception {
        Path caseDir = root.resolve("case-output"); Files.createDirectories(caseDir);
        TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
        return new CaseRuntimeContext(test, caseDir, "R", root, caseDir.resolve("case.log"));
    }

    private static Map<String, Object> args(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }

    @Test void environmentBindingAndMethodBodyResultContracts() throws Exception {
        String url = start();
        FrameworkConfig sit = configuration(url, null);
        FrameworkConfig uat = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root, "UAT");
        assertEquals("paymentApi", sit.httpHelper("PAYMENTAPI").id());
        assertEquals(url, sit.httpHelper("paymentApi").baseUrl().toString());
        assertEquals(url + "/uat", uat.httpHelper("paymentApi").baseUrl().toString());
        CaseRuntimeContext context = context();
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, sit)) {
            for (String method : Arrays.asList("get", "post", "put", "patch", "delete", "head", "options")) {
                ToolInvocationResult call = http.execute("paymentApi", method, args("path", "/echo"), context, 1000L, method, "text");
                assertTrue(call.executionSuccess(), String.valueOf(call.operationResult().diagnostic()));
                assertEquals(200, call.operationResult().outputMetadata().get("statusCode"));
            }
            byte[] payload = new byte[]{0, 1, 2, (byte) 255};
            Path file = context.caseOutputDirectory().resolve("payload.bin");
            Files.write(file, payload);
            ToolInvocationResult binary = http.execute("paymentApi", "post", args("path", "/echo", "file", file.toString()), context, 1000L, "file", "text");
            assertFalse(binary.executionSuccess());
            assertEquals("HTTP_FORMAT", ((Map<?, ?>) binary.operationResult().outputMetadata().get("error")).get("type"));
            ToolInvocationResult json = http.execute("paymentApi", "get", args("path", "/json", "query", args("a b", "c&d")), context, 1000L, "json", "json");
            assertTrue(json.executionSuccess(), String.valueOf(json.operationResult().diagnostic()));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) json.output()).get("ok"));
            assertEquals("a+b=c%26d", observedQuery.get());
            assertFalse(String.valueOf(json.operationResult().outputMetadata().get("url")).contains("?"));
            Map<?, ?> responseHeaders = (Map<?, ?>) json.operationResult().outputMetadata().get("headers");
            assertEquals("<redacted>", ((java.util.List<?>) responseHeaders.get("set-cookie")).get(0));
            assertEquals("application/json; charset=UTF-8", ((java.util.List<?>) responseHeaders.get("content-type")).get(0));
            assertEquals(java.util.Arrays.asList("one", "two"), responseHeaders.get("x-multi"));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) http.execute("paymentApi", "get", args("path", "/yaml"), context, 1000L, "yaml", "yaml").output()).get("ok"));
            Map<?, ?> xml = (Map<?, ?>) http.execute("paymentApi", "get", args("path", "/xml"), context, 1000L, "xml", "xml").output();
            assertEquals("root", xml.get("name"));
            assertEquals("true", xml.get("ok"));
            assertEquals(404, http.execute("paymentApi", "get", args("path", "/status"), context, 1000L, "status", "text").operationResult().outputMetadata().get("statusCode"));
            ToolInvocationResult serverError = http.execute("paymentApi", "get", args("path", "/status500"), context, 1000L, "server-error", "text");
            assertTrue(serverError.executionSuccess());
            assertEquals(500, serverError.operationResult().outputMetadata().get("statusCode"));
            assertEquals("HTTP_ARGUMENT", ((Map<?, ?>) http.execute("paymentApi", "post", args("path", "http://outside/"), context, 1000L, "bad", "text").operationResult().outputMetadata().get("error")).get("type"));
            assertEquals("HTTP_ARGUMENT", ((Map<?, ?>) http.execute("paymentApi", "get", args("path", "/echo", "body", "x"), context, 1000L, "bad-body", "text").operationResult().outputMetadata().get("error")).get("type"));
            ToolInvocationResult typed = http.execute("paymentApi", "post", args("path", "/json", "body", args("ok", true), "requestFormat", "json"), context, 1000L, "typed", "text");
            assertTrue(typed.executionSuccess(), String.valueOf(typed.operationResult().diagnostic()));
            assertEquals(Boolean.TRUE, ((Map<?, ?>) typed.output()).get("ok"));
            ToolInvocationResult redirected = http.execute("paymentApi", "get", args("path", "/redirect", "followRedirects", true), context, 1000L, "redirect", "json");
            assertEquals(Boolean.TRUE, ((Map<?, ?>) redirected.output()).get("ok"));
            assertEquals(1, redirected.operationResult().evidence().toString().contains("redirectCount=1") ? 1 : 0);
            ToolInvocationResult notRedirected = http.execute("paymentApi", "get", args("path", "/redirect"), context, 1000L, "no-redirect", "text");
            assertEquals(302, notRedirected.operationResult().outputMetadata().get("statusCode"));
        }
    }

    @Test void configuredHttpOutputFormatsTypedResponsesAndRedactsCredentialEchoes() throws Exception {
        String origin = start(); configuration(origin, null);
        Path descriptor = root.resolve("config/httphelpers/sit.yaml");
        String original = new String(Files.readAllBytes(descriptor), StandardCharsets.UTF_8);
        Files.write(descriptor, (original + "auth: {type: bearer, token: private-http-token}\n"
                + "evidence: {output: {format: json, maxChars: 10000}}\n").getBytes(StandardCharsets.UTF_8));
        FrameworkConfig configured = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root, "SIT");
        Map<String, Object> value = args("count", 3, "credentialEcho", "private-http-token");
        CaseRuntimeContext runtime = context();
        runtime.beginStage(new StageCaseData("invoke", "T", args("body", value)), "T", root);
        TemplateAction action = new TemplateAction("echo", args("type", "tool", "timeoutMs", 10000,
                "call", "#{http.paymentApi.post(path='/echo', body=${EXEC.INPUT.body}, requestFormat='json', responseFormat='json', readTimeoutMs=10000)}"));
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, configured);
             CaseExecutionLog log = new CaseExecutionLog(root.resolve("output.log"))) {
            UnifiedTemplateEngine engine = new UnifiedTemplateEngine(new ToolInvoker(root, configured),
                    null, null, http, new att.template.DefaultBuiltInProvider());
            ValidationResult result = new StageTemplateRunner(engine).execute("invoke",
                    new StageTemplate("T", root, Collections.singletonList(action)), runtime, log).get(0);
            assertEquals(ResultStatus.PASS, result.status(), result.message());
            assertEquals(3, ((Number) runtime.resolve("ACTIONS.echo.output.result.count")).intValue());
            assertEquals("private-http-token", runtime.resolve("ACTIONS.echo.output.result.credentialEcho"));
            Map<?, ?> output = (Map<?, ?>) runtime.resolve("ACTIONS.echo.output.evidence.http.invocations[0].output");
            assertEquals("json", output.get("format")); assertEquals(false, output.get("truncated"));
            assertFalse(String.valueOf(output.get("text")).contains("private-http-token"));
            assertTrue(String.valueOf(output.get("text")).contains("[REDACTED_SECRET]"));
        }
        String log = new String(Files.readAllBytes(root.resolve("output.log")), StandardCharsets.UTF_8);
        assertTrue(log.contains("[REDACTED_SECRET]")); assertFalse(log.contains("private-http-token"));
    }

    @Test void bearerAndBasicAuthenticationAreSentButNeverRecorded() throws Exception {
        String url = start();
        configuration(url, null);
        Path descriptor = root.resolve("config/httphelpers/sit.yaml");
        String original = new String(Files.readAllBytes(descriptor), StandardCharsets.UTF_8);
        Files.write(descriptor, (original + "auth: {type: bearer, token: top-secret-token}\n").getBytes(StandardCharsets.UTF_8));
        FrameworkConfig bearer = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root, "SIT");
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, bearer)) {
            ToolInvocationResult response = http.execute("paymentApi", "get", args("path", "/top-secret-token",
                    "headers", args("x-channel", "override", "X-Token", "top-secret-token")), context(), 1000L, "bearer", "text");
            assertTrue(response.executionSuccess());
            assertEquals("Bearer top-secret-token", observedAuthorization.get());
            assertEquals("override", observedChannel.get());
            assertFalse(response.invocation().toString().contains("top-secret-token"));
            assertFalse(response.operationResult().evidence().toString().contains("top-secret-token"));
            assertFalse(response.operationResult().outputMetadata().toString().contains("top-secret-token"));
        }
        Files.write(descriptor, (original + "auth: {type: basic, username: user, password: top-secret-password}\n").getBytes(StandardCharsets.UTF_8));
        FrameworkConfig basic = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root, "SIT");
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, basic)) {
            ToolInvocationResult response = http.execute("paymentApi", "get", args("path", "/echo"), context(), 1000L, "basic", "text");
            assertTrue(response.executionSuccess());
            assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("user:top-secret-password".getBytes(StandardCharsets.UTF_8)), observedAuthorization.get());
            assertFalse(response.operationResult().evidence().toString().contains("top-secret-password"));
        }
    }

    @Test void failedHttpCollectorOmitsLiteralAndEncodedPrivatePathWithoutPublicInputs() throws Exception {
        String origin = start();
        FrameworkConfig config = configuration(origin, null);
        String secret = "private-token-739";
        for (String path : Arrays.asList("/orders/" + secret, "/orders/private%2Dtoken%2D739")) {
            for (String mode : Arrays.asList("continue", "stop")) {
                Path caseDir = root.resolve("collector-private-path-" + path.hashCode() + "-" + mode);
                Files.createDirectories(caseDir);
                TestCase test = new TestCase(2, "g", "s", "TC1", Collections.<String>emptyList(),
                        Collections.<String, Object>emptyMap(), Collections.emptyMap(), null);
                CaseRuntimeContext runtime = new CaseRuntimeContext(test, caseDir, "R", root, caseDir.resolve("case.log"));
                runtime.beginStage(new StageCaseData("invoke", "T", args("privatePath", path)), "T", root);
                TemplateAction action = new TemplateAction("call", args("type", "tool", "call", "#{upper('ok')}",
                        "evidence", args("httpFailure", args("call",
                                "#{http.paymentApi.get(path=${EXEC.INPUT.privatePath}, responseFormat='json', readTimeoutMs=10000)}",
                                "timeoutMs", 10000, "onFailure", mode))));
                java.util.List<ValidationResult> results;
                try (HttpHelperExecutor http = new HttpHelperExecutor(root, config);
                     CaseExecutionLog log = new CaseExecutionLog(caseDir.resolve("case.log"))) {
                    UnifiedTemplateEngine engine = new UnifiedTemplateEngine(new ToolInvoker(root, config),
                            null, null, http, new att.template.DefaultBuiltInProvider());
                    results = new StageTemplateRunner(engine).execute("invoke",
                            new StageTemplate("T", root, Collections.singletonList(action)), runtime, log);
                }
                assertEquals("continue".equals(mode) ? ResultStatus.PASS : ResultStatus.ERROR, results.get(0).status(),
                        results.get(0).message());
                String collector = "ACTIONS.call.output.evidence.collectors.httpFailure";
                assertEquals("ERROR", runtime.resolve(collector + ".status"));
                assertNull(runtime.resolve(collector + ".result"));
                String node = collector + ".evidence.http.invocations[0]";
                assertEquals("GET", runtime.resolve(node + ".method"));
                assertEquals("paymentApi", runtime.resolve(node + ".httpHelper"));
                assertEquals(origin, runtime.resolve(node + ".url"));
                assertEquals(Boolean.TRUE, runtime.resolve(node + ".urlPathOmitted"));
                assertNull(runtime.resolve(node + ".input"));
                assertTrue(String.valueOf(runtime.resolve(collector + ".error.message")).contains("not valid json"));
                String published = att.validation.JsonSupport.write(runtime.resolve(collector));
                String caseLog = new String(Files.readAllBytes(caseDir.resolve("case.log")), "UTF-8");
                for (String privateRepresentation : Arrays.asList(secret, "private%2Dtoken%2D739", path)) {
                    assertFalse(published.contains(privateRepresentation), privateRepresentation);
                    assertFalse(caseLog.contains(privateRepresentation), privateRepresentation);
                    assertFalse(results.get(0).message().contains(privateRepresentation), privateRepresentation);
                }
                assertTrue(caseLog.contains(origin));
                assertTrue(caseLog.contains("GET"));
            }
        }
        assertEquals(4, hits.get(), "All returned failures must exercise the real HTTP transport");
    }

    @Test void poolAndActionDeadlinesAreBounded() throws Exception {
        String url = start();
        FrameworkConfig config = configuration(url,
                "  maxConnections: 1\n  maxConnectionsPerRoute: 1\n  connectionRequestTimeoutMs: 40\n");
        CaseRuntimeContext context = context();
        ExecutorService tasks = Executors.newFixedThreadPool(2);
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, config)) {
            Future<ToolInvocationResult> first = tasks.submit(() -> http.execute("paymentApi", "get", args("path", "/hold"), context, 1000L, "first", "text"));
            assertTrue(holding.await(1, TimeUnit.SECONDS));
            ToolInvocationResult second = http.execute("paymentApi", "get", args("path", "/json"), context, 1000L, "second", "text");
            assertEquals("HTTP_POOL_TIMEOUT", ((Map<?, ?>) second.operationResult().outputMetadata().get("error")).get("type"));
            assertTrue(first.get(2, TimeUnit.SECONDS).executionSuccess());
            ToolInvocationResult timeout = http.execute("paymentApi", "get", args("path", "/slow"), context, 25L, "timeout", "text");
            assertEquals("HTTP_TIMEOUT", ((Map<?, ?>) timeout.operationResult().outputMetadata().get("error")).get("type"));
            long started = System.nanoTime();
            ToolInvocationResult slowDrip = http.execute("paymentApi", "get", args("path", "/drip"), context, 180L, "slow-drip", "text");
            assertEquals("HTTP_TIMEOUT", ((Map<?, ?>) slowDrip.operationResult().outputMetadata().get("error")).get("type"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 700,
                    "Action deadline must abort a response that keeps making progress");
        } finally { tasks.shutdownNow(); }
    }

    @Test void actionPublishesTypedResultAndSafeMetadata() throws Exception {
        String url = start();
        FrameworkConfig config = configuration(url, null);
        CaseRuntimeContext context = context();
        context.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()), "T", root);
        TemplateAction action = new TemplateAction("fetch", args("type", "tool", "call", "#{http.paymentApi.get(path='/json', query={status:'OPEN', limit:50})}",
                "assert", "${output.statusCode} == 200"), "att-template/v3.4");
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, config);
             CaseExecutionLog log = new CaseExecutionLog(context.caseOutputDirectory().resolve("case.log"))) {
            UnifiedTemplateEngine engine = new UnifiedTemplateEngine(new ToolInvoker(root, config), null, null, http,
                    new att.template.DefaultBuiltInProvider());
            ValidationResult result = new StageTemplateRunner(engine).execute("invoke",
                    new StageTemplate("T", root, Collections.singletonList(action), "att-template/v3.4"), context, log).get(0);
            assertEquals(ResultStatus.PASS, result.status(), result.message());
            assertEquals(Boolean.TRUE, context.resolve("ACTIONS.fetch.output.result.ok"));
            assertEquals(200, context.resolve("ACTIONS.fetch.output.statusCode"));
            assertEquals("status=OPEN&limit=50", observedQuery.get());
            assertFalse(Files.isRegularFile(context.caseOutputDirectory().resolve("response.json")));
        }
    }

    @Test void actionTimeoutRetryIssuesANewHttpAttempt() throws Exception {
        String url = start();
        FrameworkConfig config = configuration(url, null);
        CaseRuntimeContext context = context();
        context.beginStage(new StageCaseData("invoke", "T", Collections.<String, Object>emptyMap()), "T", root);
        TemplateAction action = new TemplateAction("retryFetch", args("type", "tool",
                "call", "#{http.paymentApi.get(path='/slow')}", "timeoutMs", 30,
                "retry", args("maxAttempts", 2, "intervalMs", 0, "retryOn", Collections.singletonList("TIMEOUT"))),
                "att-template/v3.4");
        try (HttpHelperExecutor http = new HttpHelperExecutor(root, config);
             CaseExecutionLog log = new CaseExecutionLog(context.caseOutputDirectory().resolve("retry.log"))) {
            UnifiedTemplateEngine engine = new UnifiedTemplateEngine(new ToolInvoker(root, config), null, null, http,
                    new att.template.DefaultBuiltInProvider());
            ValidationResult result = new StageTemplateRunner(engine).execute("invoke",
                    new StageTemplate("T", root, Collections.singletonList(action), "att-template/v3.4"), context, log).get(0);
            assertEquals(ResultStatus.ERROR, result.status());
            assertEquals(2, hits.get(), "Each author-configured retry is a new HTTP request");
        }
    }
}
