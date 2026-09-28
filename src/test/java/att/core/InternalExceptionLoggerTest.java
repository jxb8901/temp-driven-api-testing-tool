package att.core;

import att.exec.MqTransport;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InternalExceptionLoggerTest {
    @TempDir Path tempDir;

    @Test void expectedDomainAndAvailabilityErrorsStayConcise() {
        assertFalse(InternalExceptionLogger.isInternal(
                new IllegalStateException("db.orders.scalar requires exactly one row but received 2")));
        assertFalse(InternalExceptionLogger.isInternal(
                new IllegalStateException("HTTP invocation is unavailable: http.payment.get")));
        assertFalse(InternalExceptionLogger.isInternal(
                new IllegalStateException("DB query failed for orders: SQL timeout")));
        assertFalse(InternalExceptionLogger.isInternal(new IllegalArgumentException("invalid queue name")));
        assertFalse(InternalExceptionLogger.isInternal(new CancellationException("action cancelled")));
    }

    @Test void internalFailuresRemainVisibleThroughExpectedWrapperCauses() {
        IllegalStateException expectedWrapper = new IllegalStateException(
                "DB query failed for orders: adapter failed", new NullPointerException("unexpected adapter state"));
        assertTrue(InternalExceptionLogger.isInternal(expectedWrapper));
        assertTrue(InternalExceptionLogger.isInternal(new IllegalStateException("unexpected invariant failure")));
        assertTrue(InternalExceptionLogger.isInternal(new ClassCastException("unexpected value type")));
        assertTrue(InternalExceptionLogger.isInternal(new RuntimeException("unexpected adapter runtime failure")));
    }

    @Test void wrappedReflectionFailurePreservesAdapterCauseAndStackInCaseLog() throws Exception {
        Path path = tempDir.resolve("reflection-case.log");
        MqTransport.Exception adapterFailure = new MqTransport.Exception("IBM MQ adapter call failed",
                null, null, null, new InvocationTargetException(new NoSuchMethodException("MQQueue.get")));
        CaseExecutionLog log = new CaseExecutionLog(path);

        assertTrue(InternalExceptionLogger.logIfInternal(log, "mq.openReplyQueue", adapterFailure,
                Collections.<String>emptyList()));
        log.close();

        String content = new String(Files.readAllBytes(path), "UTF-8");
        assertTrue(content.contains("ATT INTERNAL ERROR"));
        assertTrue(content.contains("mq.openReplyQueue"));
        assertTrue(content.contains("java.lang.reflect.InvocationTargetException"));
        assertTrue(content.contains("java.lang.NoSuchMethodException"));
        assertTrue(content.contains("InternalExceptionLoggerTest"));
    }

    @Test void repeatedActionBoundaryDoesNotRelogUnsanitizedThrowableOrLeakRegisteredPath() throws Exception {
        Path path = tempDir.resolve("secret-case.log");
        String privateKeyPath = tempDir.resolve("env-private-key").toString();
        RuntimeException adapterFailure = new RuntimeException("SSH adapter could not read " + privateKeyPath);
        CaseExecutionLog log = new CaseExecutionLog(path);

        assertTrue(InternalExceptionLogger.logIfInternal(log, "ssh.execute", adapterFailure,
                Collections.singletonList(privateKeyPath)));
        assertFalse(InternalExceptionLogger.logIfInternal(log, "tool.call", adapterFailure,
                Collections.<String>emptyList()));
        log.appendRaw("ACTION ERROR", "message: " + adapterFailure.getMessage());
        log.close();

        String content = new String(Files.readAllBytes(path), "UTF-8");
        assertFalse(content.contains(privateKeyPath));
        assertTrue(content.contains("[REDACTED_SECRET]"));
        assertEquals(1, occurrences(content, "[ATT INTERNAL ERROR]"));
        assertTrue(content.contains("java.lang.RuntimeException"));
    }

    private int occurrences(String text, String needle) {
        int count = 0, index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) { count++; index += needle.length(); }
        return count;
    }
}
