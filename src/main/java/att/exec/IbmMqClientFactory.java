/* Author: Jeffrey + ChatGPT */
package att.exec;

import att.config.MqHelperConfig;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Hashtable;

/**
 * IBM MQ classes-for-Java adapter. Reflection keeps IBM-specific classes out of
 * the ATT execution model and lets fake transports run without a Queue Manager.
 */
public final class IbmMqClientFactory implements MqTransport.Factory {
    @Override public MqTransport.Connection connect(MqHelperConfig config) throws Exception {
        try {
            Class<?> constants = Class.forName("com.ibm.mq.constants.MQConstants");
            Class<?> manager = Class.forName("com.ibm.mq.MQQueueManager");
            Hashtable<String, Object> properties = new Hashtable<String, Object>();
            properties.put(stringConstant(constants, "HOST_NAME_PROPERTY", "hostname"), config.host());
            properties.put(stringConstant(constants, "PORT_PROPERTY", "port"), Integer.valueOf(config.port()));
            properties.put(stringConstant(constants, "CHANNEL_PROPERTY", "channel"), config.channel());
            properties.put(stringConstant(constants, "TRANSPORT_PROPERTY", "transport"), constant(constants, "TRANSPORT_MQSERIES_CLIENT", 1));
            if (config.credentialsConfigured()) {
                if (!config.username().isEmpty()) properties.put(stringConstant(constants, "USER_ID_PROPERTY", "userID"), config.username());
                if (!config.password().isEmpty()) properties.put(stringConstant(constants, "PASSWORD_PROPERTY", "password"), config.password());
            }
            Object queueManager = manager.getConstructor(String.class, Hashtable.class)
                    .newInstance(config.queueManager(), properties);
            return new ReflectiveConnection(queueManager, constants);
        } catch (ClassNotFoundException missing) {
            throw new MqTransport.Exception("IBM MQ client classes are unavailable; add com.ibm.mq:com.ibm.mq.allclient to the runtime classpath", null, null, null, missing);
        } catch (InvocationTargetException target) {
            throw translate(target.getCause());
        } catch (Exception error) {
            throw translate(error);
        }
    }

    private static final class ReflectiveConnection implements MqTransport.Connection {
        private final Object manager;
        private final Class<?> constants;
        private ReflectiveConnection(Object manager, Class<?> constants) { this.manager = manager; this.constants = constants; }
        @Override public MqTransport.Queue open(String queue, boolean input, boolean output) throws Exception {
            int options = constant(constants, "MQOO_FAIL_IF_QUIESCING", 0x2000);
            if (input) options |= constant(constants, "MQOO_INPUT_SHARED", 0x0002);
            if (output) {
                options |= constant(constants, "MQOO_OUTPUT", 0x0010);
                options |= constant(constants, "MQOO_BIND_NOT_FIXED", 0x00080000);
            }
            try {
                Object destination = invoke(manager, "accessQueue", new Class<?>[]{String.class, Integer.TYPE}, queue, Integer.valueOf(options));
                return new ReflectiveQueue(destination, constants);
            } catch (Exception error) { throw translate(error); }
        }
        @Override public void disconnect() throws Exception { try { invoke(manager, "disconnect", new Class<?>[0]); } catch (Exception error) { throw translate(error); } }
        @Override public void close() throws Exception { disconnect(); }
    }

    private static final class ReflectiveQueue implements MqTransport.Queue {
        private final Object queue;
        private final Class<?> constants;
        private ReflectiveQueue(Object queue, Class<?> constants) { this.queue = queue; this.constants = constants; }
        @Override public MqTransport.Message put(byte[] payload, MqTransport.PutRequest request) throws Exception {
            try {
                Class<?> messageClass = Class.forName("com.ibm.mq.MQMessage");
                Object message = messageClass.getConstructor().newInstance();
                set(message, "version", Integer.valueOf(constant(constants, "MQMD_VERSION_1", 1)));
                if (request.expiry() != null) set(message, "expiry", request.expiry());
                set(message, "characterSet", Integer.valueOf(request.ccsid()));
                set(message, "format", format(request.format()));
                set(message, "persistence", persistence(request.persistence()));
                if (request.encoding() != null) set(message, "encoding", request.encoding());
                if (!request.replyQueueManager().isEmpty()) set(message, "replyToQueueManagerName", request.replyQueueManager());
                if (!request.replyQueue().isEmpty()) set(message, "replyToQueueName", request.replyQueue());
                invoke(message, "write", new Class<?>[]{byte[].class}, payload);
                Class<?> optionsClass = Class.forName("com.ibm.mq.MQPutMessageOptions");
                Object options = optionsClass.getConstructor().newInstance();
                int flags = constant(constants, "MQPMO_NO_SYNCPOINT", 0x00000002)
                        | constant(constants, "MQPMO_NEW_MSG_ID", 0x00000040);
                set(options, "options", Integer.valueOf(flags));
                invoke(queue, "put", new Class<?>[]{messageClass, optionsClass}, message, options);
                return new MqTransport.Message(bytes(message, "messageId"), bytes(message, "correlationId"), payload);
            } catch (Exception error) { throw translate(error); }
        }
        @Override public MqTransport.Message get(MqTransport.GetRequest request) throws Exception {
            try {
                Class<?> messageClass = Class.forName("com.ibm.mq.MQMessage");
                Object message = messageClass.getConstructor().newInstance();
                if (request.correlationId() != null) set(message, "correlationId", request.correlationId());
                Class<?> optionsClass = Class.forName("com.ibm.mq.MQGetMessageOptions");
                Object options = optionsClass.getConstructor().newInstance();
                int flags = constant(constants, "MQGMO_NO_SYNCPOINT", 0x00000004)
                        | constant(constants, "MQGMO_WAIT", 0x00000001);
                set(options, "options", Integer.valueOf(flags));
                set(options, "waitInterval", Integer.valueOf(request.waitMs()));
                if (request.correlationId() != null) set(options, "matchOptions", Integer.valueOf(constant(constants, "MQMO_MATCH_CORREL_ID", 0x00000002)));
                invoke(queue, "get", new Class<?>[]{messageClass, optionsClass}, message, options);
                int length = ((Number) invoke(message, "getDataLength", new Class<?>[0])).intValue();
                byte[] payload = new byte[length];
                invoke(message, "readFully", new Class<?>[]{byte[].class}, payload);
                return new MqTransport.Message(bytes(message, "messageId"), bytes(message, "correlationId"), payload);
            } catch (Exception error) { throw translate(error); }
        }
        @Override public void close() throws Exception { try { invoke(queue, "close", new Class<?>[0]); } catch (Exception error) { throw translate(error); } }

        private String format(String name) {
            if ("MQSTR".equals(name) || "MQFMT_STRING".equals(name)) return stringConstant(constants, "MQFMT_STRING", "MQSTR   ");
            if ("NONE".equals(name) || "MQFMT_NONE".equals(name)) return stringConstant(constants, "MQFMT_NONE", "        ");
            if ("MQHRF2".equals(name)) return stringConstant(constants, "MQFMT_RF_HEADER_2", "MQHRF2  ");
            return name;
        }
        private int persistence(String name) {
            if ("persistent".equals(name)) return constant(constants, "MQPER_PERSISTENT", 1);
            if ("notPersistent".equals(name) || "nonPersistent".equals(name)) return constant(constants, "MQPER_NOT_PERSISTENT", 2);
            if ("1".equals(name)) return constant(constants, "MQPER_PERSISTENT", 1);
            if ("2".equals(name)) return constant(constants, "MQPER_NOT_PERSISTENT", 2);
            return constant(constants, "MQPER_PERSISTENCE_AS_Q_DEF", 0);
        }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getMethod(name, types);
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error; }
    }
    private static void set(Object target, String field, Object value) throws Exception {
        try { Field declared = target.getClass().getField(field); declared.set(target, value); }
        catch (NoSuchFieldException ignored) { /* IBM client version may use a setter or a newer default. */ }
    }
    private static byte[] bytes(Object target, String field) throws Exception {
        try { Object value = target.getClass().getField(field).get(target); return value instanceof byte[] ? (byte[]) value : null; }
        catch (NoSuchFieldException ignored) { return null; }
    }
    private static int constant(Class<?> type, String name, int fallback) {
        try { return type.getField(name).getInt(null); } catch (Exception ignored) { return fallback; }
    }
    private static String stringConstant(Class<?> type, String name, String fallback) {
        try { return String.valueOf(type.getField(name).get(null)); } catch (Exception ignored) { return fallback; }
    }
    private static MqTransport.Exception translate(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException && ((InvocationTargetException) error).getCause() != null
                ? ((InvocationTargetException) error).getCause() : error;
        Integer completion = number(cause, "completionCode");
        Integer reason = number(cause, "reasonCode");
        String reasonName = reason == null ? null : reasonName(reason.intValue());
        return new MqTransport.Exception(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), completion, reason, reasonName, cause);
    }
    private static Integer number(Throwable error, String field) {
        try { Object value = error.getClass().getField(field).get(error); return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null; }
        catch (Exception ignored) { return null; }
    }
    private static String reasonName(int reason) {
        try {
            Class<?> constants = Class.forName("com.ibm.mq.constants.MQConstants");
            Method lookup = constants.getMethod("lookupReasonCode", Integer.TYPE);
            Object value = lookup.invoke(null, Integer.valueOf(reason));
            return value == null ? null : String.valueOf(value);
        } catch (Exception ignored) { return null; }
    }
}
