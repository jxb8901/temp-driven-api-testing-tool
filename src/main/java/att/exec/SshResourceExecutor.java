/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.FrameworkConfig;
import att.config.SshConfig;
import att.config.SshHelperConfig;
import att.core.CaseExecutionLog;
import att.core.CaseRuntimeContext;
import att.core.InternalExceptionLogger;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Executes the common SSH Resource Helper operations without changing Tool semantics. */
public final class SshResourceExecutor {
    private final Path projectRoot;
    private final FrameworkConfig config;
    private final SshCommandRunner commandRunner;
    private final SshTransferClient transferClient;
    private final ConcurrentMap<String, Semaphore> limits = new ConcurrentHashMap<String, Semaphore>();

    public SshResourceExecutor(Path projectRoot, FrameworkConfig config) {
        this(projectRoot, config, new SshCommandRunner(new CommandRunner()), new JschSshTransferClient());
    }

    SshResourceExecutor(Path projectRoot, FrameworkConfig config, SshCommandRunner commandRunner,
                        SshTransferClient transferClient) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.config = config;
        this.commandRunner = commandRunner;
        this.transferClient = transferClient;
    }

    /** Executes ssh.&lt;helper&gt;.&lt;operation&gt; and returns the native operation result. */
    public ToolInvocationResult execute(String helperId, String operation, Map<String, Object> arguments,
                                        CaseRuntimeContext context, Long requestedTimeoutMs,
                                        String invocationId, CaseExecutionLog log) {
        String name = "ssh." + helperId + "." + operation;
        String id = invocationId == null || invocationId.trim().isEmpty()
                ? context.nextInvocationId(name) : invocationId;
        Instant started = Instant.now();
        Map<String, Object> supplied = arguments == null ? Collections.<String, Object>emptyMap() : arguments;
        Map<String, Object> safeInput = safeInput(supplied);
        SshHelperConfig helper = config.sshHelper(helperId);
        SshConfig target = null;
        Semaphore permit = null;
        boolean acquired = false;
        try {
            if (helper == null) throw argument("Unknown SSH helper: " + helperId, "SSH_ARGUMENT");
            validateArguments(operation, supplied);
            target = helper.instances().get(helper.select(helper.strategy()));
            if (target == null) throw argument("SSH helper has no selectable instance: " + helperId, "SSH_ARGUMENT");
            long timeoutMs = operationTimeout(supplied, requestedTimeoutMs, helper.commandTimeoutMs());
            permit = limits.computeIfAbsent(helper.id().toLowerCase(Locale.ROOT), key -> new Semaphore(helper.maxConcurrency(), true));
            if (!permit.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS))
                throw argument("SSH helper concurrency limit timed out: " + helper.id(), "SSH_POOL_TIMEOUT");
            acquired = true;
            Map<String, Object> result;
            if ("execute".equals(operation)) result = executeCommand(name, helper, target, supplied, timeoutMs, context, log);
            else if ("upload".equals(operation)) result = upload(name, helper, target, supplied, timeoutMs, context);
            else if ("download".equals(operation)) result = download(name, helper, target, supplied, timeoutMs, context);
            else throw argument("Unknown SSH operation '" + operation + "'; use execute, upload, or download", "SSH_ARGUMENT");
            return success(name, id, result.get("result"), result, safeInput, started);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure(name, id, safeInput, started, helper, target, "SSH_INTERRUPTED", "SSH operation was interrupted", log, interrupted);
        } catch (Exception error) {
            String category = error instanceof SshOperationException ? ((SshOperationException) error).category : "SSH_ERROR";
            String message = safeError(error, target);
            if (log != null && !(error instanceof SshOperationException && "SSH_ARGUMENT".equals(category)))
                InternalExceptionLogger.logIfInternal(log, "ssh." + operation, error, identityRedactions(target));
            return failure(name, id, safeInput, started, helper, target, category, message, log, error);
        } finally {
            if (acquired && permit != null) permit.release();
        }
    }

    private Map<String, Object> executeCommand(String name, SshHelperConfig helper, SshConfig target,
                                               Map<String, Object> input, long timeoutMs,
                                               CaseRuntimeContext context, CaseExecutionLog log) throws Exception {
        String command = requiredString(input.get("command"), "command");
        String format = input.get("stdoutFormat") == null ? "text" : requiredString(input.get("stdoutFormat"), "stdoutFormat").toLowerCase(Locale.ROOT);
        if (!("text".equals(format) || "json".equals(format) || "yaml".equals(format) || "xml".equals(format)))
            throw argument("stdoutFormat must be text, json, yaml, or xml", "SSH_ARGUMENT");
        Instant started = Instant.now();
        SshCommandRunner.Execution execution = commandRunner.runRaw(target, command, Duration.ofMillis(timeoutMs), projectRoot);
        CommandResult commandResult = execution.result();
        String stdout = commandResult.stdout();
        String stderr = redact(commandResult.stderr(), target);
        if (log != null) {
            log.registerSecretRedactions(identityRedactions(target));
            if (commandResult.stdoutBytes() > 0) log.appendRaw("SSH " + name + " STDOUT", stdout);
            if (commandResult.stderrBytes() > 0) log.appendRaw("SSH " + name + " STDERR", stderr);
        }
        Map<String, Object> evidence = commonEvidence(helper, target, "execute", execution.transport(), started);
        evidence.put("exitCode", commandResult.exitCode());
        evidence.put("stdoutBytes", commandResult.stdoutBytes());
        evidence.put("stderrBytes", commandResult.stderrBytes());
        evidence.put("stdoutTruncated", commandResult.stdoutTruncated());
        evidence.put("stderrTruncated", commandResult.stderrTruncated());
        evidence.put("timeoutMs", timeoutMs);
        evidence.put("stderr", stderr);
        if (commandResult.timedOut()) throw operation("SSH command timed out", "SSH_TIMEOUT", evidence);
        if (commandResult.exitCode() != 0)
            throw operation("SSH command exited with code " + commandResult.exitCode(), "SSH_REMOTE_EXIT", evidence);
        Object parsed;
        try {
            parsed = new ToolInvoker(projectRoot, config).parseOutput(stdout, format);
        } catch (Exception parseFailure) {
            evidence.put("stdoutFormat", format);
            evidence.put("parserDiagnostic", safeError(parseFailure, target));
            throw operation("SSH stdout is not valid " + format, "SSH_RESULT_PARSE_ERROR", evidence, parseFailure);
        }
        evidence.put("stdoutFormat", format);
        evidence.put("durationMs", Duration.between(started, Instant.now()).toMillis());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("result", parsed);
        result.put("evidence", evidence);
        return result;
    }

    private void validateArguments(String operation, Map<String, Object> input) throws SshOperationException {
        Set<String> allowed = new LinkedHashSet<String>();
        if ("execute".equals(operation)) {
            allowed.add("command"); allowed.add("stdoutFormat"); allowed.add("timeoutMs");
            requiredString(input.get("command"), "command");
            if (input.get("stdoutFormat") != null) {
                String format = requiredString(input.get("stdoutFormat"), "stdoutFormat").toLowerCase(Locale.ROOT);
                if (!("text".equals(format) || "json".equals(format) || "yaml".equals(format) || "xml".equals(format)))
                    throw argument("stdoutFormat must be text, json, yaml, or xml", "SSH_ARGUMENT");
            }
        } else if ("upload".equals(operation)) {
            allowed.add("remotePath"); allowed.add("localPath"); allowed.add("payload");
            allowed.add("overwrite"); allowed.add("timeoutMs");
            requiredRemotePath(input.get("remotePath"));
            boolean hasLocal = input.containsKey("localPath");
            boolean hasPayload = input.containsKey("payload");
            if (hasLocal == hasPayload) throw argument("SSH upload requires exactly one of localPath or payload", "SSH_ARGUMENT");
            if (hasLocal) requiredString(input.get("localPath"), "localPath");
            if (hasPayload && !(input.get("payload") instanceof byte[]) && !(input.get("payload") instanceof CharSequence))
                throw argument("SSH upload payload must be a String or byte[]; Map/List requires an explicit representation", "SSH_ARGUMENT");
            bool(input.get("overwrite"), true, "overwrite");
        } else if ("download".equals(operation)) {
            allowed.add("remotePath"); allowed.add("localPath"); allowed.add("overwrite"); allowed.add("timeoutMs");
            requiredRemotePath(input.get("remotePath"));
            requiredString(input.get("localPath"), "localPath");
            bool(input.get("overwrite"), false, "overwrite");
        } else {
            throw argument("Unknown SSH operation '" + operation + "'; use execute, upload, or download", "SSH_ARGUMENT");
        }
        if (input.containsKey("timeoutMs")) operationTimeout(input, null, 60000);
        for (String key : input.keySet()) if (!allowed.contains(key))
            throw argument("Unknown SSH " + operation + " argument '" + key + "'", "SSH_ARGUMENT");
    }

    private Map<String, Object> upload(String name, SshHelperConfig helper, SshConfig target,
                                       Map<String, Object> input, long timeoutMs, CaseRuntimeContext context) throws Exception {
        String remotePath = requiredRemotePath(input.get("remotePath"));
        boolean hasLocal = input.containsKey("localPath");
        boolean hasPayload = input.containsKey("payload");
        if (hasLocal == hasPayload) throw argument("SSH upload requires exactly one of localPath or payload", "SSH_ARGUMENT");
        boolean overwrite = bool(input.get("overwrite"), true, "overwrite");
        Instant started = Instant.now();
        long bytes;
        String mode;
        if (hasLocal) {
            Path local = resolveExistingLocal(requiredString(input.get("localPath"), "localPath"), context);
            bytes = transferClient.upload(target, local, null, remotePath, overwrite, Duration.ofMillis(timeoutMs), projectRoot);
            mode = "local-file";
        } else {
            Object payload = input.get("payload");
            byte[] content;
            if (payload instanceof byte[]) content = ((byte[]) payload).clone();
            else if (payload instanceof CharSequence) content = String.valueOf(payload).getBytes(StandardCharsets.UTF_8);
            else throw argument("SSH upload payload must be a String or byte[]; Map/List requires an explicit representation", "SSH_ARGUMENT");
            bytes = transferClient.upload(target, null, content, remotePath, overwrite, Duration.ofMillis(timeoutMs), projectRoot);
            mode = "represented-payload";
        }
        Map<String, Object> evidence = commonEvidence(helper, target, "upload", "sftp", started);
        evidence.put("remotePath", remotePath);
        evidence.put("transferMode", mode);
        evidence.put("bytesTransferred", bytes);
        evidence.put("durationMs", Duration.between(started, Instant.now()).toMillis());
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("remotePath", remotePath);
        summary.put("bytesTransferred", bytes);
        summary.put("transferMode", mode);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("result", summary);
        result.put("evidence", evidence);
        return result;
    }

    private Map<String, Object> download(String name, SshHelperConfig helper, SshConfig target,
                                         Map<String, Object> input, long timeoutMs, CaseRuntimeContext context) throws Exception {
        String remotePath = requiredRemotePath(input.get("remotePath"));
        String localText = requiredString(input.get("localPath"), "localPath");
        boolean overwrite = bool(input.get("overwrite"), false, "overwrite");
        Path local = resolveDestination(localText, context, overwrite);
        Instant started = Instant.now();
        long bytes = transferClient.download(target, remotePath, local, overwrite, Duration.ofMillis(timeoutMs), projectRoot);
        Map<String, Object> evidence = commonEvidence(helper, target, "download", "sftp", started);
        evidence.put("remotePath", remotePath);
        evidence.put("localPath", portable(local));
        evidence.put("bytesTransferred", bytes);
        evidence.put("durationMs", Duration.between(started, Instant.now()).toMillis());
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("remotePath", remotePath);
        summary.put("localPath", portable(local));
        summary.put("bytesTransferred", bytes);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("result", summary);
        result.put("evidence", evidence);
        return result;
    }

    private ToolInvocationResult success(String name, String id, Object output, Map<String, Object> operation,
                                         Map<String, Object> input, Instant started) {
        @SuppressWarnings("unchecked") Map<String, Object> evidence = (Map<String, Object>) operation.get("evidence");
        Map<String, Object> invocation = invocation(name, id, output, input, true, evidence, null, started);
        return new ToolInvocationResult(name, id, output, invocation, true,
                new ActionExecutionResult(output, ActionExecutionResult.evidence("ssh", evidence), true,
                        null, elapsed(started), Collections.<String, Object>emptyMap()));
    }

    private ToolInvocationResult failure(String name, String id, Map<String, Object> input, Instant started,
                                         SshHelperConfig helper, SshConfig target, String category, String message,
                                         CaseExecutionLog log, Throwable cause) {
        Map<String, Object> evidence = commonEvidence(helper, target, operationName(name),
                target == null ? "" : commandRunner.transportName(), started);
        if (cause instanceof SshOperationException && ((SshOperationException) cause).evidence != null)
            evidence.putAll(((SshOperationException) cause).evidence);
        evidence.put("durationMs", elapsed(started));
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("category", category); error.put("message", message);
        evidence.put("error", error);
        Map<String, Object> invocation = invocation(name, id, null, input, false, evidence, error, started);
        return new ToolInvocationResult(name, id, null, invocation, false,
                new ActionExecutionResult(null, ActionExecutionResult.evidence("ssh", evidence), false,
                        error, elapsed(started), Collections.<String, Object>emptyMap()));
    }

    private Map<String, Object> invocation(String name, String id, Object output, Map<String, Object> input,
                                           boolean success, Map<String, Object> evidence,
                                           Map<String, Object> error, Instant started) {
        Map<String, Object> invocation = new LinkedHashMap<String, Object>();
        invocation.put("id", id); invocation.put("type", "ssh"); invocation.put("name", name);
        invocation.put("status", success ? "PASS" : "ERROR");
        invocation.put("durationMs", elapsed(started)); invocation.put("input", input); invocation.put("output", output);
        if (error != null) invocation.put("error", error);
        invocation.put("SSH", evidence);
        return invocation;
    }

    private Map<String, Object> commonEvidence(SshHelperConfig helper, SshConfig target, String operation,
                                                String transport, Instant started) {
        Map<String, Object> evidence = new LinkedHashMap<String, Object>();
        evidence.put("resource", "ssh");
        if (helper != null) evidence.put("helper", helper.id());
        if (target != null) {
            evidence.put("host", target.host()); evidence.put("port", target.port());
            evidence.put("instance", instanceId(helper, target));
        }
        evidence.put("operation", operation);
        if (transport != null && !transport.isEmpty()) evidence.put("transport", transport);
        evidence.put("startedAt", started.toString());
        return evidence;
    }

    private String instanceId(SshHelperConfig helper, SshConfig target) {
        if (helper == null) return "";
        for (Map.Entry<String, SshConfig> entry : helper.instances().entrySet()) if (entry.getValue() == target) return entry.getKey();
        return "";
    }

    private long operationTimeout(Map<String, Object> input, Long requested, int fallback) throws SshOperationException {
        Object value = input.get("timeoutMs");
        if (value == null) return requested == null ? fallback : requested.longValue();
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).longValue())
            throw argument("timeoutMs must be an integer from 1 to 3600000", "SSH_ARGUMENT");
        long timeout = ((Number) value).longValue();
        if (timeout < 1L || timeout > 3600000L) throw argument("timeoutMs must be an integer from 1 to 3600000", "SSH_ARGUMENT");
        return timeout;
    }

    private Path resolveExistingLocal(String value, CaseRuntimeContext context) throws IOException {
        List<Path> roots = allowedRoots(context);
        Path configured = Paths.get(value);
        List<Path> candidates = new ArrayList<Path>();
        if (configured.isAbsolute()) candidates.add(configured.normalize());
        else {
            candidates.add(projectRoot.resolve(configured).normalize());
            candidates.add(context.caseOutputDirectory().resolve(configured).normalize());
        }
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate)) continue;
            Path real = candidate.toRealPath();
            for (Path root : roots) if (contained(real, root)) return real;
        }
        throw argument("SSH localPath is missing or outside the ATT-controlled package/case output", "SSH_PATH");
    }

    private Path resolveDestination(String value, CaseRuntimeContext context, boolean overwrite) throws IOException {
        allowedRoots(context);
        Path configured = Paths.get(value);
        Path root = context.caseOutputDirectory().toAbsolutePath().normalize();
        Path candidate = configured.isAbsolute() ? configured.normalize() : root.resolve(configured).normalize();
        if (!candidate.startsWith(root)) throw argument("SSH download localPath must stay under the ATT-controlled case output", "SSH_PATH");
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(candidate))
            throw argument("SSH download localPath must not be a symbolic link", "SSH_PATH");
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) && !overwrite)
            throw argument("SSH download localPath exists; set overwrite=true to replace it", "SSH_PATH");
        Path parent = candidate.getParent();
        Path realRoot = root.toRealPath();
        Path existing = parent;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        if (existing == null || !existing.toRealPath().startsWith(realRoot))
            throw argument("SSH download localPath parent escapes the ATT-controlled root", "SSH_PATH");
        if (parent != null) {
            Files.createDirectories(parent);
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(realRoot))
                throw argument("SSH download localPath parent escapes the ATT-controlled root", "SSH_PATH");
        }
        return candidate;
    }

    private List<Path> allowedRoots(CaseRuntimeContext context) throws IOException {
        List<Path> roots = new ArrayList<Path>();
        roots.add(projectRoot.toRealPath());
        Path caseRoot = context.caseOutputDirectory().toAbsolutePath().normalize();
        Files.createDirectories(caseRoot);
        Path realCase = caseRoot.toRealPath();
        if (!roots.contains(realCase)) roots.add(realCase);
        return roots;
    }

    private boolean contained(Path value, Path root) { return value.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()); }
    private String portable(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        return absolute.startsWith(projectRoot) ? projectRoot.relativize(absolute).toString().replace('\\', '/') : absolute.toString();
    }

    private Map<String, Object> safeInput(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if ("payload".equals(entry.getKey())) result.put("payload", "<represented payload>");
            else result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private List<String> identityRedactions(SshConfig target) {
        if (target == null || !target.identityFileFromEnvironment() || target.identityFile().isEmpty()) return Collections.emptyList();
        List<String> values = new ArrayList<String>(); values.add(target.identityFile());
        try {
            Path path = Paths.get(target.identityFile());
            if (!path.isAbsolute()) path = projectRoot.resolve(path).normalize();
            if (!values.contains(path.toString())) values.add(path.toString());
        } catch (RuntimeException ignored) { }
        return values;
    }

    private String redact(String value, SshConfig target) {
        String result = value == null ? "" : value;
        for (String secret : identityRedactions(target)) result = result.replace(secret, "[REDACTED_SECRET]");
        return result;
    }

    private String safeError(Throwable error, SshConfig target) { return redact(error == null ? "SSH operation failed" : String.valueOf(error.getMessage()), target); }
    private long elapsed(Instant started) { return Duration.between(started, Instant.now()).toMillis(); }
    private String operationName(String name) { String[] parts = name.split("\\.", -1); return parts.length == 3 ? parts[2] : "unknown"; }
    private static boolean bool(Object value, boolean fallback, String field) throws SshOperationException {
        if (value == null) return fallback;
        if (!(value instanceof Boolean)) throw argument(field + " must be boolean", "SSH_ARGUMENT");
        return ((Boolean) value).booleanValue();
    }
    private static String requiredString(Object value, String field) throws SshOperationException {
        if (!(value instanceof CharSequence) || String.valueOf(value).trim().isEmpty()) throw argument(field + " must be a non-blank string", "SSH_ARGUMENT");
        return String.valueOf(value);
    }
    private static String requiredRemotePath(Object value) throws SshOperationException {
        String path = requiredString(value, "remotePath");
        for (int index = 0; index < path.length(); index++) if (Character.isISOControl(path.charAt(index))) throw argument("remotePath must not contain control characters", "SSH_ARGUMENT");
        return path;
    }
    private static SshOperationException argument(String message, String category) { return new SshOperationException(category, message, null); }
    private static SshOperationException operation(String message, String category, Map<String, Object> evidence) { return operation(message, category, evidence, null); }
    private static SshOperationException operation(String message, String category, Map<String, Object> evidence, Throwable cause) {
        return new SshOperationException(category, message, cause, evidence);
    }

    static final class SshOperationException extends IOException {
        private final String category;
        private final Map<String, Object> evidence;
        SshOperationException(String category, String message, Throwable cause) { this(category, message, cause, null); }
        SshOperationException(String category, String message, Throwable cause, Map<String, Object> evidence) {
            super(message, cause); this.category = category; this.evidence = evidence;
        }
    }
}

interface SshTransferClient {
    long upload(SshConfig target, Path source, byte[] payload, String remotePath, boolean overwrite,
                Duration timeout, Path projectRoot) throws Exception;
    long download(SshConfig target, String remotePath, Path localPath, boolean overwrite,
                  Duration timeout, Path projectRoot) throws Exception;
}

/** SFTP transfer implementation used for represented payloads and file transfers. */
final class JschSshTransferClient implements SshTransferClient {
    private static final class Connection {
        private final Session session;
        private final ChannelSftp channel;

        private Connection(Session session, ChannelSftp channel) {
            this.session = session;
            this.channel = channel;
        }
    }

    private final Path knownHosts;
    JschSshTransferClient() { this(Paths.get(System.getProperty("user.home", ""), ".ssh", "known_hosts")); }
    JschSshTransferClient(Path knownHosts) { this.knownHosts = knownHosts; }

    @Override public long upload(SshConfig ssh, Path source, byte[] payload, String remotePath, boolean overwrite,
                                 Duration timeout, Path projectRoot) throws Exception {
        Connection connection = null;
        try {
            connection = open(ssh, projectRoot, timeout);
            ChannelSftp channel = connection.channel;
            if (!overwrite && exists(channel, remotePath)) throw new IOException("Remote destination exists: " + remotePath);
            if (source != null) {
                channel.put(source.toString(), remotePath, ChannelSftp.OVERWRITE);
                return Files.size(source);
            }
            byte[] bytes = payload == null ? new byte[0] : payload;
            channel.put(new ByteArrayInputStream(bytes), remotePath, ChannelSftp.OVERWRITE);
            return bytes.length;
        } finally { if (connection != null) close(connection.channel, connection.session); }
    }

    @Override public long download(SshConfig ssh, String remotePath, Path localPath, boolean overwrite,
                                   Duration timeout, Path projectRoot) throws Exception {
        if (Files.exists(localPath) && !overwrite) throw new IOException("Local destination exists: " + localPath);
        Path parent = localPath.getParent();
        Path temporary = Files.createTempFile(parent == null ? Paths.get(".") : parent, ".att-ssh-", ".part");
        Connection connection = null;
        try {
            connection = open(ssh, projectRoot, timeout);
            ChannelSftp channel = connection.channel;
            channel.get(remotePath, temporary.toString());
            long size = Files.size(temporary);
            try {
                if (overwrite) Files.move(temporary, localPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(temporary, localPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                if (overwrite) Files.move(temporary, localPath, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(temporary, localPath);
            }
            return size;
        } finally {
            if (connection != null) close(connection.channel, connection.session);
            Files.deleteIfExists(temporary);
        }
    }

    private Connection open(SshConfig ssh, Path projectRoot, Duration timeout) throws Exception {
        if (!Files.isRegularFile(knownHosts) || Files.isSymbolicLink(knownHosts) || !Files.isReadable(knownHosts))
            throw new IOException("Java SSH transfer requires a readable non-symlink known_hosts file");
        JSch jsch = new JSch(); jsch.setKnownHosts(knownHosts.toString());
        if (!ssh.identityFile().isEmpty()) {
            Path identity = Paths.get(ssh.identityFile());
            if (!identity.isAbsolute()) identity = projectRoot.resolve(identity).normalize();
            jsch.addIdentity(identity.toString());
        }
        Session session = jsch.getSession(ssh.user(), ssh.host(), ssh.port());
        session.setConfig("StrictHostKeyChecking", "yes");
        session.setConfig("PreferredAuthentications", "publickey,gssapi-with-mic");
        session.connect((int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeout.toMillis())));
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect((int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeout.toMillis())));
        return new Connection(session, channel);
    }

    private boolean exists(ChannelSftp channel, String path) throws SftpException {
        try { channel.stat(path); return true; }
        catch (SftpException missing) { if (missing.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false; throw missing; }
    }

    private void close(ChannelSftp channel, Session session) {
        if (channel != null && channel.isConnected()) channel.disconnect();
        if (session != null && session.isConnected()) session.disconnect();
    }
}
