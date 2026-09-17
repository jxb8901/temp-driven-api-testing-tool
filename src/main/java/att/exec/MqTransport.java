/* Author: Jeffrey + ChatGPT */
package att.exec;

import java.util.Arrays;

/** Small IBM MQ-neutral boundary used by MqHelperExecutor and its test doubles. */
public final class MqTransport {
    private MqTransport() {}

    public interface Factory {
        Connection connect(att.config.MqHelperConfig config) throws java.lang.Exception;
    }

    public interface Connection extends AutoCloseable {
        Queue open(String queue, boolean input, boolean output) throws java.lang.Exception;
        void disconnect() throws java.lang.Exception;
        @Override void close() throws java.lang.Exception;
    }

    public interface Queue extends AutoCloseable {
        Message put(byte[] payload, PutRequest request) throws java.lang.Exception;
        Message get(GetRequest request) throws java.lang.Exception;
        @Override void close() throws java.lang.Exception;
    }

    public static final class PutRequest {
        private final String replyQueue;
        private final int ccsid;
        private final String format;
        private final String persistence;
        public PutRequest(String replyQueue, int ccsid, String format, String persistence) {
            this.replyQueue = replyQueue == null ? "" : replyQueue;
            this.ccsid = ccsid;
            this.format = format;
            this.persistence = persistence;
        }
        public String replyQueue() { return replyQueue; }
        public int ccsid() { return ccsid; }
        public String format() { return format; }
        public String persistence() { return persistence; }
    }

    public static final class GetRequest {
        private final byte[] correlationId;
        private final int waitMs;
        public GetRequest(byte[] correlationId, int waitMs) {
            this.correlationId = correlationId == null ? null : Arrays.copyOf(correlationId, correlationId.length);
            this.waitMs = waitMs;
        }
        public byte[] correlationId() { return correlationId == null ? null : Arrays.copyOf(correlationId, correlationId.length); }
        public int waitMs() { return waitMs; }
    }

    public static final class Message {
        private final byte[] messageId;
        private final byte[] correlationId;
        private final byte[] payload;
        public Message(byte[] messageId, byte[] correlationId, byte[] payload) {
            this.messageId = copy(messageId); this.correlationId = copy(correlationId); this.payload = copy(payload);
        }
        public byte[] messageId() { return copy(messageId); }
        public byte[] correlationId() { return copy(correlationId); }
        public byte[] payload() { return copy(payload); }
        private static byte[] copy(byte[] value) { return value == null ? null : Arrays.copyOf(value, value.length); }
    }

    /** Preserves IBM completion/reason metadata without exposing IBM classes to ATT core code. */
    public static class Exception extends java.lang.Exception {
        private final Integer completionCode;
        private final Integer reasonCode;
        private final String reason;
        public Exception(String message, Integer completionCode, Integer reasonCode, String reason, Throwable cause) {
            super(message, cause); this.completionCode = completionCode; this.reasonCode = reasonCode; this.reason = reason;
        }
        public Integer completionCode() { return completionCode; }
        public Integer reasonCode() { return reasonCode; }
        public String reason() { return reason; }
    }
}
