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
        private final String replyQueueManager;
        private final String replyQueue;
        private final int ccsid;
        private final Integer encoding;
        private final String format;
        private final String persistence;
        private final Integer expiry;
        public PutRequest(String replyQueue, int ccsid, String format, String persistence) {
            this("", replyQueue, ccsid, null, format, persistence, null);
        }
        public PutRequest(String replyQueueManager, String replyQueue, int ccsid, Integer encoding,
                          String format, String persistence, Integer expiry) {
            this.replyQueueManager = replyQueueManager == null ? "" : replyQueueManager;
            this.replyQueue = replyQueue == null ? "" : replyQueue;
            this.ccsid = ccsid;
            this.encoding = encoding;
            this.format = format;
            this.persistence = persistence;
            this.expiry = expiry;
        }
        public String replyQueueManager() { return replyQueueManager; }
        public String replyQueue() { return replyQueue; }
        public int ccsid() { return ccsid; }
        public Integer encoding() { return encoding; }
        public String format() { return format; }
        public String persistence() { return persistence; }
        public Integer expiry() { return expiry; }
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
        public static Exception poolTimeout(Throwable cause) {
            return new Exception("MQ connection pool borrow timed out", null, null, "MQ_POOL_TIMEOUT", cause);
        }
        public Integer completionCode() { return completionCode; }
        public Integer reasonCode() { return reasonCode; }
        public String reason() { return reason; }

        /** True only when IBM MQ metadata identifies a physical connection failure. */
        public boolean connectionFailure() {
            if (Integer.valueOf(2033).equals(reasonCode) || "MQRC_NO_MSG_AVAILABLE".equals(reason)) return false;
            if (reasonCode != null) {
                switch (reasonCode.intValue()) {
                    case 2009: // MQRC_CONNECTION_BROKEN
                    case 2018: // MQRC_HCONN_ERROR
                    case 2059: // MQRC_Q_MGR_NOT_AVAILABLE
                    case 2161: // MQRC_Q_MGR_QUIESCING
                    case 2162: // MQRC_Q_MGR_STOPPING
                    case 2219: // MQRC_CONNECTION_NOT_AUTHORIZED
                    case 2537: // MQRC_CHANNEL_NOT_AVAILABLE
                    case 2538: // MQRC_HOST_NOT_AVAILABLE
                        return true;
                    default:
                        break;
                }
            }
            if (reason == null) return false;
            String normalized = reason.toUpperCase(java.util.Locale.ROOT);
            return normalized.contains("CONNECTION_BROKEN")
                    || normalized.contains("CONNECTION_ERROR")
                    || normalized.contains("HCONN_ERROR")
                    || normalized.contains("Q_MGR_NOT_AVAILABLE")
                    || normalized.contains("Q_MGR_QUIESCING")
                    || normalized.contains("Q_MGR_STOPPING")
                    || normalized.contains("CONNECTION_NOT_AUTHORIZED")
                    || normalized.contains("CHANNEL_NOT_AVAILABLE")
                    || normalized.contains("HOST_NOT_AVAILABLE");
        }
    }
}
