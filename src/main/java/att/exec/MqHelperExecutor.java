/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.FrameworkConfig;
import att.config.MqHelperConfig;
import att.core.CaseRuntimeContext;
import att.core.CaseExecutionLog;
import att.core.IdentifierValidator;
import att.core.PathSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/** Executes one invocation-scoped, non-transactional IBM MQ operation. */
public final class MqHelperExecutor {
    private final Path projectRoot;
    private final FrameworkConfig config;
    private final MqTransport.Factory factory;
    private final Map<String, AtomicLong> roundRobinCounters = new ConcurrentHashMap<String, AtomicLong>();

    public MqHelperExecutor(Path projectRoot, FrameworkConfig config) {
        this(projectRoot, config, new IbmMqClientFactory());
    }

    public MqHelperExecutor(Path projectRoot, FrameworkConfig config, MqTransport.Factory factory) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.config = config;
        this.factory = factory == null ? new IbmMqClientFactory() : factory;
    }

    public MqHelperConfig helper(String instance) { return config.mqHelper(instance); }

    public MqInvocationResult execute(String instance, String operation, Map<String, Object> arguments,
                                      CaseRuntimeContext context, Long timeoutMs, String invocationId) {
        return execute(instance, operation, arguments, context, timeoutMs, invocationId, null, null, "raw", false);
    }

    /** Executes an MQ call with the common Action result contract. */
    public MqInvocationResult execute(String instance, String operation, Map<String, Object> arguments,
                                      CaseRuntimeContext context, Long timeoutMs, String invocationId,
                                      String actionId, String savePath, String saveFormat, boolean overwrite) {
        return execute(instance, operation, arguments, context, timeoutMs, invocationId, actionId, savePath,
                saveFormat, overwrite, null);
    }

    /** Action-aware execution overload that can append path: console output to the active Case log. */
    public MqInvocationResult execute(String instance, String operation, Map<String, Object> arguments,
                                      CaseRuntimeContext context, Long timeoutMs, String invocationId,
                                      String actionId, String savePath, String saveFormat, boolean overwrite,
                                      CaseExecutionLog log) {
        MqHelperConfig logical = config.mqHelper(instance);
        if (logical == null) return failure(instance, operation, invocationId, "MQ_CONFIG", "Unknown MQ helper instance '" + instance + "'", null);
        Map<String, Object> args = arguments == null ? Collections.<String, Object>emptyMap() : arguments;
        MqHelperConfig helper;
        try {
            helper = select(logical, args.get("instance"));
            validateArguments(instance, operation, args, helper);
            if ("send".equals(operation) && savePath != null && !savePath.trim().isEmpty()) {
                throw new IllegalArgumentException("MQ send does not produce a business payload and does not support result persistence");
            }
        } catch (Exception error) {
            return failure(instance, operation, invocationId, "MQ_ARGUMENT", error.getMessage(), error);
        }
        Instant started = Instant.now();
        final long deadlineNanos = timeoutMs == null ? Long.MAX_VALUE
                : System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs.longValue());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        result.put("mqHelper", logical.logicalId()); result.put("instance", helper.instanceId());
        result.put("queueManager", helper.queueManager()); result.put("selectionStrategy", logical.selectionStrategy());
        result.put("result", null);
        evidence.put("instance", helper.instanceId()); evidence.put("helperId", logical.logicalId()); evidence.put("physicalInstance", helper.instanceId());
        evidence.put("selectionStrategy", logical.selectionStrategy()); evidence.put("operation", operation);
        evidence.put("queueManager", helper.queueManager());
        evidence.put("host", helper.host()); evidence.put("port", helper.port()); evidence.put("channel", helper.channel());
        evidence.put("charset", helper.charset()); evidence.put("ccsid", helper.ccsid());
        if (helper.encoding() != null) evidence.put("encoding", helper.encoding());
        if (helper.expiry() != null) evidence.put("expiry", helper.expiry());
        evidence.put("format", helper.format()); evidence.put("persistence", helper.persistence());
        if (!helper.requestQueue().isEmpty()) evidence.put("requestQueueDefault", helper.requestQueue());
        if (!helper.replyQueue().isEmpty()) evidence.put("replyQueueDefault", helper.replyQueue());
        evidence.put("syncpoint", "none");
        if ("metadata".equals(helper.evidencePayload())) evidence.put("payloadEvidence", helper.evidencePayload());
        String representation = normalizeFormat(saveFormat);
        boolean success = false;
        MqTransport.Connection connection = null;
        MqTransport.Queue requestQueue = null;
        MqTransport.Queue replyQueue = null;
        try {
            if ("send".equals(operation) || "request".equals(operation)) {
                String file = string(args.get("file"), "file");
                Path payloadFile = payloadFile(file, context);
                byte[] payload = Files.readAllBytes(payloadFile);
                String queue = "send".equals(operation)
                        ? string(args.get("queue"), "queue")
                        : effectiveQueue(args.get("requestQueue"), helper.requestQueue(), "requestQueue");
                result.put("queue", queue); result.put("payloadFile", payloadFile.toString()); result.put("bytes", payload.length);
                evidence.put("queue", queue); evidence.put("payloadFile", portable(payloadFile)); evidence.put("bytes", payload.length);
                connection = factory.connect(helper);
                ensureWithinDeadline(deadlineNanos, "connect");
                requestQueue = connection.open(queue, false, true, "request".equals(operation));
                ensureWithinDeadline(deadlineNanos, "open request queue");
                String replyName = "request".equals(operation)
                        ? effectiveQueue(args.get("replyQueue"), helper.replyQueue(), "replyQueue") : "";
                MqTransport.Message sent = requestQueue.put(payload, new MqTransport.PutRequest(
                        "request".equals(operation) ? helper.queueManager() : "", replyName,
                        helper.charset(), helper.encoding(), helper.format(), helper.persistence(), helper.expiry()));
                ensureWithinDeadline(deadlineNanos, "put");
                String messageId = id(sent == null ? null : sent.messageId());
                result.put("messageId", messageId); result.put("correlationId", null);
                evidence.put("messageId", messageId); evidence.put("correlationId", null);
                result.put("sent", true); evidence.put("sent", true);
                if ("send".equals(operation)) {
                    success = true;
                } else {
                    closeQueue(requestQueue); requestQueue = null;
                    String reply = effectiveQueue(args.get("replyQueue"), helper.replyQueue(), "replyQueue");
                    int waitMs = effectiveWait(args.get("waitMs"), helper.requestReplyWaitMs(), deadlineNanos);
                    result.put("replyQueue", reply); result.put("waitMs", waitMs);
                    evidence.put("replyQueue", reply); evidence.put("waitMs", waitMs);
                    replyQueue = connection.open(reply, true, false);
                    ensureWithinDeadline(deadlineNanos, "open reply queue");
                    waitMs = effectiveWait(args.get("waitMs"), helper.requestReplyWaitMs(), deadlineNanos);
                    result.put("waitMs", waitMs); evidence.put("waitMs", waitMs);
                    MqTransport.Message received;
                    try {
                        received = replyQueue.get(new MqTransport.GetRequest(sent == null ? null : sent.messageId(), waitMs));
                        ensureWithinDeadline(deadlineNanos, "get reply");
                    } catch (MqTransport.Exception noReply) {
                        if (!isNoMessage(noReply)) throw noReply;
                        result.put("replyReceived", false); evidence.put("replyReceived", false);
                        addReason(result, evidence, noReply);
                        success = !deadlineExceeded(deadlineNanos);
                        if (!success) addDeadlineError(result, evidence);
                        received = null;
                    }
                    if (received != null) {
                        String replyMessageId = id(received.messageId());
                        String correlationId = id(received.correlationId());
                        result.put("replyReceived", true);
                        result.put("replyMessageId", replyMessageId); result.put("replyCorrelationId", correlationId);
                        result.put("replyBytes", received.payload() == null ? 0 : received.payload().length);
                        result.put("waitMs", waitMs);
                        evidence.put("replyReceived", true);
                        evidence.put("replyMessageId", replyMessageId); evidence.put("replyCorrelationId", correlationId);
                        evidence.put("replyBytes", received.payload() == null ? 0 : received.payload().length);
                        addReplyMetadata(result, evidence, received);
                        Object business = represent(received, representation, helper);
                        result.put("result", business);
                        saveIfRequested(result, context, log, actionId, savePath, representation, received.payload(), business, overwrite);
                        success = true;
                    }
                }
            } else if ("receive".equals(operation)) {
                String queue = string(args.get("queue"), "queue");
                byte[] correlation = args.get("correlationId") == null ? null : messageId(String.valueOf(args.get("correlationId")));
                int waitMs = effectiveWait(args.get("waitMs"), helper.requestReplyWaitMs(), deadlineNanos);
                result.put("queue", queue); result.put("correlationId", id(correlation)); result.put("waitMs", waitMs);
                evidence.put("queue", queue); evidence.put("correlationId", id(correlation)); evidence.put("waitMs", waitMs);
                connection = factory.connect(helper);
                ensureWithinDeadline(deadlineNanos, "connect");
                replyQueue = connection.open(queue, true, false);
                ensureWithinDeadline(deadlineNanos, "open queue");
                waitMs = effectiveWait(args.get("waitMs"), helper.requestReplyWaitMs(), deadlineNanos);
                result.put("waitMs", waitMs); evidence.put("waitMs", waitMs);
                try {
                    MqTransport.Message received = replyQueue.get(new MqTransport.GetRequest(correlation, waitMs));
                    ensureWithinDeadline(deadlineNanos, "get message");
                    result.put("received", true);
                    result.put("messageId", id(received == null ? null : received.messageId()));
                    result.put("receivedCorrelationId", id(received == null ? null : received.correlationId()));
                    result.put("bytes", received == null || received.payload() == null ? 0 : received.payload().length);
                    evidence.put("received", true);
                    evidence.put("messageId", id(received == null ? null : received.messageId()));
                    evidence.put("receivedCorrelationId", id(received == null ? null : received.correlationId()));
                    evidence.put("bytes", received == null || received.payload() == null ? 0 : received.payload().length);
                    addReplyMetadata(result, evidence, received);
                    Object business = represent(received, representation, helper);
                    result.put("result", business);
                    saveIfRequested(result, context, log, actionId, savePath, representation,
                            received == null ? null : received.payload(), business, overwrite);
                    success = true;
                } catch (MqTransport.Exception noReply) {
                    if (!isNoMessage(noReply)) throw noReply;
                    result.put("received", false); evidence.put("received", false); addReason(result, evidence, noReply);
                    success = !deadlineExceeded(deadlineNanos);
                    if (!success) addDeadlineError(result, evidence);
                }
            } else throw new IllegalArgumentException("Unknown MQ operation: " + operation);
        } catch (MqTransport.Exception error) {
            success = false; addError(result, evidence, error, helper);
        } catch (MqActionDeadlineException error) {
            success = false; addDeadlineError(result, evidence, error.getMessage());
        } catch (Exception error) {
            success = false; addError(result, evidence, error, helper);
        } finally {
            String cleanup = closeQueue(requestQueue);
            if (cleanup == null) cleanup = closeQueue(replyQueue);
            else { String next = closeQueue(replyQueue); if (next != null) cleanup += "; " + next; }
            if (connection != null) {
                try { connection.disconnect(); } catch (Exception error) { cleanup = append(cleanup, "disconnect=" + safe(error, helper)); }
            }
            if (cleanup != null) { evidence.put("cleanupWarning", cleanup); if (success) success = false; }
        }
        long duration = Duration.between(started, Instant.now()).toMillis();
        result.put("durationMs", duration); evidence.put("durationMs", duration);
        if (result.get("outputFile") != null) evidence.put("outputFile", portable(Paths.get(String.valueOf(result.get("outputFile")))));
        if (savePath != null && !savePath.trim().isEmpty()) evidence.put("resultPath", savePath);
        evidence.put("resultFormat", representation);
        evidence.put("status", success ? "PASS" : "ERROR");
        return new MqInvocationResult(result, evidence, success);
    }

    private MqInvocationResult failure(String instance, String operation, String invocationId, String type, String message, Throwable cause) {
        Map<String, Object> result = new LinkedHashMap<String, Object>(); result.put("instance", instance); result.put("operation", operation); result.put("result", null);
        Map<String, Object> evidence = new LinkedHashMap<String, Object>(); evidence.put("instance", instance); evidence.put("operation", operation); evidence.put("status", "ERROR");
        Map<String, Object> error = new LinkedHashMap<String, Object>(); error.put("type", type); error.put("message", message); result.put("error", error); evidence.put("error", error);
        return new MqInvocationResult(result, evidence, false);
    }

    private MqHelperConfig select(MqHelperConfig logical, Object requested) {
        if (requested != null) {
            String requestedId = string(requested, "instance");
            MqHelperConfig selected = logical.instance(requestedId);
            if (selected == null) throw new IllegalArgumentException("Unknown physical MQ instance '" + requestedId + "' for mq." + logical.logicalId());
            return selected;
        }
        if (!logical.isMultiInstance()) return logical.instances().values().iterator().next();
        List<MqHelperConfig> candidates = new ArrayList<MqHelperConfig>(logical.instances().values());
        if ("random".equals(logical.selectionStrategy())) return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        AtomicLong counter = roundRobinCounters.get(logical.logicalId().toLowerCase(Locale.ROOT));
        if (counter == null) {
            AtomicLong created = new AtomicLong();
            AtomicLong previous = roundRobinCounters.putIfAbsent(logical.logicalId().toLowerCase(Locale.ROOT), created);
            counter = previous == null ? created : previous;
        }
        return candidates.get((int) Math.floorMod(counter.getAndIncrement(), (long) candidates.size()));
    }

    private void validateArguments(String instance, String operation, Map<String, Object> args,
                                   MqHelperConfig helper) {
        if (!("send".equals(operation) || "receive".equals(operation) || "request".equals(operation))) throw new IllegalArgumentException("Unknown MQ operation: " + operation);
        for (String key : args.keySet()) if (!allowed(operation, key)) throw new IllegalArgumentException("Unknown MQ " + operation + " argument '" + key + "' for mq." + instance);
        if (("send".equals(operation) || "request".equals(operation)) && args.get("file") == null) throw new IllegalArgumentException("mq." + instance + "." + operation + " requires file");
        if ("request".equals(operation) && args.get("requestQueue") == null && helper.requestQueue().isEmpty()) throw new IllegalArgumentException("mq." + instance + ".request requires requestQueue or mqhelper.message.requestQueue");
        if ("request".equals(operation) && args.get("replyQueue") == null && helper.replyQueue().isEmpty()) throw new IllegalArgumentException("mq." + instance + ".request requires replyQueue or mqhelper.message.replyQueue");
        if (("send".equals(operation) || "receive".equals(operation)) && args.get("queue") == null) throw new IllegalArgumentException("mq." + instance + "." + operation + " requires queue");
        for (String key : new String[]{"queue", "requestQueue", "replyQueue"}) if (args.containsKey(key)) validQueue(string(args.get(key), key));
        if (args.containsKey("waitMs")) integer(args.get("waitMs"), "waitMs", 0, 3600000);
        if ("receive".equals(operation) && args.containsKey("correlationId") && String.valueOf(args.get("correlationId")).trim().isEmpty()) throw new IllegalArgumentException("correlationId must not be blank");
        if (args.get("instance") != null) string(args.get("instance"), "instance");
    }

    private boolean allowed(String operation, String key) {
        if ("send".equals(operation)) return "queue".equals(key) || "file".equals(key) || "instance".equals(key);
        if ("receive".equals(operation)) return "queue".equals(key) || "waitMs".equals(key) || "correlationId".equals(key) || "instance".equals(key);
        return "requestQueue".equals(key) || "replyQueue".equals(key) || "file".equals(key) || "waitMs".equals(key) || "instance".equals(key);
    }

    private Path payloadFile(String value, CaseRuntimeContext context) throws IOException {
        Path path = Paths.get(value);
        Path logical = path.isAbsolute() ? path.normalize() : context.caseOutputDirectory().resolve(path).normalize();
        if (!path.isAbsolute() && !logical.startsWith(context.caseOutputDirectory().normalize())) throw new IOException("MQ payload path escapes the Case output directory: " + value);
        if (Files.isSymbolicLink(logical) || !Files.isRegularFile(logical, LinkOption.NOFOLLOW_LINKS)) throw new IOException("MQ payload file does not exist or is unsafe: " + value);
        Path real = logical.toRealPath();
        Path caseRoot = context.caseOutputDirectory().toRealPath();
        Path packageRoot = projectRoot.toRealPath();
        if (!real.startsWith(caseRoot) && !real.startsWith(packageRoot)) throw new IOException("MQ payload file escapes the ATT package: " + value);
        return real;
    }

    private String effectiveQueue(Object value, String fallback, String name) {
        return value == null ? string(fallback, name) : string(value, name);
    }

    private String normalizeFormat(String value) {
        String result = value == null || value.trim().isEmpty() ? "raw" : value.trim().toLowerCase(Locale.ROOT);
        if (!("raw".equals(result) || "text".equals(result) || "json".equals(result)
                || "yaml".equals(result) || "xml".equals(result))) {
            throw new IllegalArgumentException("MQ result.format must be raw, text, json, yaml, or xml");
        }
        return result;
    }

    private Object represent(MqTransport.Message message, String format, MqHelperConfig helper) throws Exception {
        byte[] payload = message == null ? null : message.payload();
        if ("raw".equals(format)) return payload == null ? null : Arrays.copyOf(payload, payload.length);
        int ccsid = message == null || message.ccsid() == null || message.ccsid().intValue() <= 0
                ? helper.charset() : message.ccsid().intValue();
        String text = new String(payload == null ? new byte[0] : payload, mqCharset(ccsid));
        if ("text".equals(format)) return text;
        return new ToolInvoker(projectRoot, config).parseOutput(text, format);
    }

    private void saveIfRequested(Map<String, Object> result, CaseRuntimeContext context, CaseExecutionLog log, String actionId,
                                 String savePath, String format, byte[] raw, Object value, boolean overwrite) throws Exception {
        if (savePath == null || savePath.trim().isEmpty()) return;
        if ("console".equalsIgnoreCase(savePath.trim())) {
            if (log == null) throw new IOException("MQ result.path=console requires the active Case execution log");
            log.appendRaw("ACTION " + (actionId == null || actionId.trim().isEmpty() ? "MQ" : actionId) + " RESULT",
                    consoleValue(format, raw, value));
            return;
        }
        Path root = (context.inFlow() ? context.actionOutputDir(actionId) : context.caseOutputDirectory())
                .toAbsolutePath().normalize();
        Path target = root.resolve(IdentifierValidator.relativePath(savePath, "action result.path")).normalize();
        if (!target.startsWith(root) || target.equals(root)) throw new IOException("MQ result path escapes the Action artifact directory: " + savePath);
        Files.createDirectories(root);
        PathSafety.ensureContained(root, target, "MQ result.path");
        Files.createDirectories(target.getParent());
        PathSafety.ensureContained(root, target, "MQ result.path");
        if (Files.exists(target) && !overwrite) throw new IOException("result file already exists and overwrite is false: " + savePath);
        if ("raw".equals(format)) {
            Files.write(target, raw == null ? new byte[0] : raw,
                    overwrite ? new java.nio.file.StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING}
                            : new java.nio.file.StandardOpenOption[]{StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE});
        } else {
            String encoded = "text".equals(format) ? String.valueOf(value) : new ObjectOutputCodec().encode(value, format);
            Files.write(target, encoded.getBytes(StandardCharsets.UTF_8),
                    overwrite ? new java.nio.file.StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING}
                            : new java.nio.file.StandardOpenOption[]{StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE});
        }
        result.put("outputFile", target.toString());
    }

    private String consoleValue(String format, byte[] raw, Object value) throws Exception {
        if ("raw".equals(format)) return new String(raw == null ? new byte[0] : raw, Charset.defaultCharset());
        if ("text".equals(format)) return value == null ? "" : String.valueOf(value);
        return new ObjectOutputCodec().encode(value, format);
    }

    private Charset mqCharset(int ccsid) {
        return MqCcsid.charset(ccsid);
    }

    private void addReplyMetadata(Map<String, Object> result, Map<String, Object> evidence, MqTransport.Message message) {
        if (message == null) return;
        if (message.ccsid() != null) { result.put("replyCcsid", message.ccsid()); evidence.put("replyCcsid", message.ccsid()); }
        if (message.encoding() != null) { result.put("replyEncoding", message.encoding()); evidence.put("replyEncoding", message.encoding()); }
        if (message.format() != null) { result.put("replyFormat", message.format()); evidence.put("replyFormat", message.format()); }
    }

    private int effectiveWait(Object value, int fallback, long deadlineNanos) {
        int requested = value == null ? fallback : integer(value, "waitMs", 0, 3600000);
        if (deadlineNanos == Long.MAX_VALUE) return requested;
        long remainingNanos = Math.max(0L, deadlineNanos - System.nanoTime());
        long remainingMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        if (remainingNanos > 0L && remainingMs == 0L) remainingMs = 1L;
        return (int) Math.min((long) requested, remainingMs);
    }

    private boolean deadlineExceeded(long deadlineNanos) {
        return deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos;
    }

    private void addDeadlineError(Map<String, Object> result, Map<String, Object> evidence) {
        addDeadlineError(result, evidence, "Action timeout expired while waiting for an MQ message");
    }

    private void addDeadlineError(Map<String, Object> result, Map<String, Object> evidence, String message) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("type", "MQ_TIMEOUT"); error.put("message", message);
        result.put("error", error); evidence.put("error", error);
    }

    private void ensureWithinDeadline(long deadlineNanos, String operation) throws MqActionDeadlineException {
        if (deadlineExceeded(deadlineNanos)) {
            throw new MqActionDeadlineException("Action timeout expired during MQ " + operation);
        }
    }

    private static final class MqActionDeadlineException extends Exception {
        private MqActionDeadlineException(String message) { super(message); }
    }

    private int integer(Object value, String name, int min, int max) {
        if (!(value instanceof Number) && !(value instanceof String)) throw new IllegalArgumentException(name + " must be an integer");
        long number;
        try { number = Long.parseLong(String.valueOf(value)); } catch (NumberFormatException error) { throw new IllegalArgumentException(name + " must be an integer", error); }
        if (number < min || number > max) throw new IllegalArgumentException(name + " must be from " + min + " to " + max);
        return (int) number;
    }

    private String string(Object value, String name) {
        if (value == null || String.valueOf(value).trim().isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return String.valueOf(value);
    }

    public static boolean isValidQueueName(String value) { return value != null && value.length() <= 48 && value.matches("[A-Za-z0-9_.%/-]+"); }
    private void validQueue(String value) { if (!isValidQueueName(value)) throw new IllegalArgumentException("Invalid MQ queue name: " + value); }

    private byte[] messageId(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String text = value.trim();
        if (text.matches("(?i)[0-9a-f]+") && text.length() % 2 == 0) {
            byte[] result = new byte[text.length() / 2];
            for (int index = 0; index < result.length; index++) result[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
            return result;
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 24) throw new IllegalArgumentException("MQ correlationId must be at most 24 bytes or an even-length hexadecimal value");
        return Arrays.copyOf(bytes, 24);
    }

    private String id(byte[] value) {
        if (value == null) return null;
        StringBuilder result = new StringBuilder(); for (byte item : value) result.append(String.format("%02x", item & 255)); return result.toString();
    }

    private boolean isNoMessage(MqTransport.Exception error) { return Integer.valueOf(2033).equals(error.reasonCode()) || "MQRC_NO_MSG_AVAILABLE".equals(error.reason()); }

    private void addReason(Map<String, Object> result, Map<String, Object> evidence, MqTransport.Exception error) {
        if (error.completionCode() != null) { result.put("completionCode", error.completionCode()); evidence.put("completionCode", error.completionCode()); }
        if (error.reasonCode() != null) { result.put("reasonCode", error.reasonCode()); evidence.put("reasonCode", error.reasonCode()); }
        if (error.reason() != null) { result.put("reason", error.reason()); evidence.put("reason", error.reason()); }
    }

    private void addError(Map<String, Object> result, Map<String, Object> evidence, Exception error, MqHelperConfig helper) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        if (error instanceof MqTransport.Exception) {
            MqTransport.Exception mq = (MqTransport.Exception) error;
            detail.put("type", "MQ_POOL_TIMEOUT".equals(mq.reason()) ? "MQ_POOL_TIMEOUT" : "MQ_ERROR"); detail.put("message", safe(error, helper));
            if (mq.completionCode() != null) detail.put("completionCode", mq.completionCode());
            if (mq.reasonCode() != null) detail.put("reasonCode", mq.reasonCode());
            if (mq.reason() != null) detail.put("reason", mq.reason());
        } else { detail.put("type", error.getClass().getName()); detail.put("message", safe(error, helper)); }
        result.put("error", detail); evidence.put("error", detail);
    }

    private String closeQueue(MqTransport.Queue queue) {
        if (queue == null) return null;
        try { queue.close(); return null; } catch (Exception error) { return "queueClose=" + safe(error, null); }
    }
    private String append(String first, String second) { return first == null ? second : first + "; " + second; }
    private String safe(Exception error, MqHelperConfig helper) {
        String message = error.getMessage() == null || error.getMessage().trim().isEmpty() ? error.getClass().getSimpleName() : error.getMessage();
        if (helper != null && !helper.password().isEmpty()) message = message.replace(helper.password(), "<redacted>");
        return message;
    }
    private String portable(Path path) { try { return projectRoot.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/'); } catch (Exception error) { return path.toString(); } }
}
