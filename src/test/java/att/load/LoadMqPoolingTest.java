package att.load;

import att.config.FrameworkConfig;
import att.config.MqHelperConfig;
import att.config.ProcessOutputConfig;
import att.core.ResultStatus;
import att.exec.MqTransport;
import att.flow.FlowRegistry;
import att.template.StageTemplate;
import att.template.TemplateAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadMqPoolingTest {
    @TempDir Path tempDir;

    @Test
    void concurrentIterationsUseOneExclusiveLeaseAndPublishPoolTimeout() throws Exception {
        BlockingFactory delegate = new BlockingFactory();
        FrameworkConfig config = config(1, 0, 50L);
        LoadTarget target = target();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (LoadRunResources resources = new LoadRunResources(tempDir, config, delegate)) {
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources, tempDir.resolve("output"));
            Future<IterationResult> first = workers.submit(() -> executor.execute(request("mq-1", "VU-1")));
            assertTrue(delegate.getEntered.await(2L, TimeUnit.SECONDS));
            assertEquals(1, resources.mqPool("broker").active());

            Future<IterationResult> second = workers.submit(() -> executor.execute(request("mq-2", "VU-2")));
            IterationResult timedOut = second.get(2L, TimeUnit.SECONDS);
            assertEquals(ResultStatus.ERROR, timedOut.status());
            assertEquals("MQ_POOL_TIMEOUT", timedOut.context().resolve("EXEC.ACTIONS.receive.output.error.type"));
            assertEquals(1, delegate.maxConcurrentGets.get());

            delegate.releaseGet.countDown();
            assertEquals(ResultStatus.PASS, first.get(2L, TimeUnit.SECONDS).status());
            assertEquals(0, resources.mqPool("broker").active());

            IterationResult recovered = executor.execute(request("mq-3", "VU-3"));
            assertEquals(ResultStatus.PASS, recovered.status());
            assertEquals(1, delegate.connections.get());
            assertEquals(1, delegate.maxConcurrentGets.get());
            assertEquals(0, resources.mqPool("broker").active());
            assertEquals(2, delegate.queueCloses.get());
        } finally {
            delegate.releaseGet.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2L, TimeUnit.SECONDS);
        }
        assertEquals(1, delegate.disconnects.get());
    }

    @Test
    void cancellingAnIterationWithAnActiveMqLeaseInvalidatesAndCleansItUp() throws Exception {
        BlockingFactory delegate = new BlockingFactory();
        FrameworkConfig config = config(1, 0, 500L);
        LoadTarget target = target();
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try (LoadRunResources resources = new LoadRunResources(tempDir, config, delegate)) {
            IterationExecutor executor = new IterationExecutor(tempDir, config, target, resources, tempDir.resolve("output"));
            Future<IterationResult> running = workers.submit(() -> executor.execute(request("mq-cancel", "VU-1")));
            assertTrue(delegate.getEntered.await(2L, TimeUnit.SECONDS));
            assertEquals(1, resources.mqPool("broker").active());

            assertTrue(running.cancel(true));
            assertTrue(delegate.getFinished.await(2L, TimeUnit.SECONDS));
            workers.shutdown();
            assertTrue(workers.awaitTermination(2L, TimeUnit.SECONDS));
            assertTrue(awaitActive(resources.mqPool("broker"), 2L), "cancelled MQ iteration leaked its lease");
            assertEquals(1, delegate.disconnects.get());
            assertEquals(1, delegate.queueCloses.get());
        } finally {
            delegate.releaseGet.countDown();
            workers.shutdownNow();
        }
    }

    private FrameworkConfig config(int maxSize, int minIdle, long timeoutMs) {
        Map<String, MqHelperConfig> helpers = new LinkedHashMap<String, MqHelperConfig>();
        helpers.put("broker", new MqHelperConfig("broker", "Broker", "test broker", "QM1", "localhost", 1414,
                "DEV.APP.SVRCONN", "user", "secret", 1208, "MQSTR", "asQueue", 1000,
                "metadata", maxSize, minIdle, timeoutMs, tempDir.resolve("mq.yaml")));
        return new FrameworkConfig(tempDir.resolve("output"), tempDir.resolve("report"), tempDir.resolve("logs"),
                "SIT", 10000, tempDir.resolve("project/templates"), tempDir.resolve("project/testcase"),
                Collections.emptyMap(), Collections.emptyMap(), helpers, null, null,
                null, "", "", null, null, 1, "ignore", "", false, ProcessOutputConfig.defaults());
    }

    private LoadTarget target() throws Exception {
        Path project = tempDir.resolve("project");
        Path directory = project.resolve("templates/MQ");
        Files.createDirectories(directory);
        TemplateAction action = new TemplateAction("receive", map(
                "type", "tool",
                "call", "#{mq.broker.receive(queue='REPLY.Q', waitMs=1000)}"), "att-template/v3.0");
        StageTemplate template = new StageTemplate("MQ", directory, Collections.singletonList(action),
                "att-template/v3.0", directory.resolve("template.yaml"));
        FlowRegistry flows = new FlowRegistry(project, project.resolve("templates"), false);
        return new LoadTarget("template", "MQ", template, flows, project.resolve("templates"), project.resolve("scenario.yaml"));
    }

    private IterationRequest request(String id, String userId) {
        return IterationRequest.closed("load-mq", id, 1, "STEADY", Instant.now(), userId, Collections.emptyMap());
    }

    private boolean awaitActive(LoadResourcePool<MqTransport.Connection> pool, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (pool.active() != 0 && System.nanoTime() < deadline) Thread.sleep(10L);
        return pool.active() == 0;
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private static final class BlockingFactory implements MqTransport.Factory {
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger disconnects = new AtomicInteger();
        final AtomicInteger queueCloses = new AtomicInteger();
        final AtomicInteger activeGets = new AtomicInteger();
        final AtomicInteger maxConcurrentGets = new AtomicInteger();
        final CountDownLatch getEntered = new CountDownLatch(1);
        final CountDownLatch releaseGet = new CountDownLatch(1);
        final CountDownLatch getFinished = new CountDownLatch(1);

        @Override public MqTransport.Connection connect(MqHelperConfig config) {
            connections.incrementAndGet();
            return new MqTransport.Connection() {
                @Override public MqTransport.Queue open(String queue, boolean input, boolean output) {
                    return new MqTransport.Queue() {
                        @Override public MqTransport.Message put(byte[] payload, MqTransport.PutRequest request) {
                            return new MqTransport.Message(new byte[]{1}, null, payload);
                        }

                        @Override public MqTransport.Message get(MqTransport.GetRequest request) throws Exception {
                            int active = activeGets.incrementAndGet();
                            for (;;) {
                                int previous = maxConcurrentGets.get();
                                if (active <= previous || maxConcurrentGets.compareAndSet(previous, active)) break;
                            }
                            getEntered.countDown();
                            try {
                                releaseGet.await();
                                return new MqTransport.Message(new byte[]{1}, null, new byte[]{7});
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new MqTransport.Exception("MQ get interrupted", null, 2009,
                                        "MQRC_CONNECTION_BROKEN", interrupted);
                            } finally {
                                activeGets.decrementAndGet();
                                getFinished.countDown();
                            }
                        }

                        @Override public void close() { queueCloses.incrementAndGet(); }
                    };
                }

                @Override public void disconnect() { disconnects.incrementAndGet(); }
                @Override public void close() { disconnect(); }
            };
        }
    }
}
