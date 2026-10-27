package att.load;

import att.core.ResultStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LoadConsoleSamplerTest {
    @Test void concurrentlyDiscoveredWorkloadsAndPhasesStayWithinTheirCaps() throws Exception {
        LoadConsoleSampler sampler=new LoadConsoleSampler("concurrent",5000L,1000L);
        int workers=64;CyclicBarrier start=new CyclicBarrier(workers);ExecutorService executor=Executors.newFixedThreadPool(workers);
        List<Future<?>> tasks=new ArrayList<>();
        try {
            for(int i=0;i<workers;i++) {final int id=i;tasks.add(executor.submit(()->{
                start.await();sampler.onEvent(LoadEvent.completed("concurrent","arrivalRate","phase-"+id,"iteration","VU",id,1L,2L,3L,ResultStatus.PASS)
                        .withWorkloadIdentity("workload-"+id,"template","TEST"));return null;
            }));}
            for(Future<?> task:tasks)task.get(10,TimeUnit.SECONDS);
            Map<String,Object> snapshot=sampler.snapshot();
            assertEquals(32,((Map<?,?>)snapshot.get("workloads")).size());
            assertEquals(8,((Map<?,?>)snapshot.get("phases")).size());
            assertEquals(Boolean.TRUE,snapshot.get("workloadsTruncated"));
            assertEquals((long)workers,((Map<?,?>)snapshot.get("counts")).get("completed"),"The global count includes observations beyond the detail cap");
        } finally {executor.shutdownNow();executor.awaitTermination(10,TimeUnit.SECONDS);sampler.finish("PASS");}
    }
    @Test void preparationDoesNotAdvanceSchedulerElapsedTime() throws Exception {
        LoadConsoleSampler sampler=new LoadConsoleSampler("preparing",5000L,1000L);
        Thread.sleep(20L);
        assertEquals(0L,sampler.snapshot().get("elapsedMs"));
        sampler.observePreparedStart("closed","preparing");
        Thread.sleep(5L);
        assertTrue(((Number)sampler.snapshot().get("elapsedMs")).longValue()>0L);
        sampler.finish("PASS");
    }
    @Test void exposesBoundedProgressCountsAndLatencyWithoutIterationIdentity() {
        LoadConsoleSampler sampler=new LoadConsoleSampler("live-progress",5000L,1000L);
        sampler.startLine("arrivalRate");
        sampler.onEvent(LoadEvent.completed("live-progress","arrivalRate","STEADY","private-iteration","VU-1",1,1L,2L,52L,ResultStatus.PASS)
                .withWorkloadIdentity("checkout","template","PAYMENT"));
        sampler.onEvent(LoadEvent.dropped("live-progress","arrivalRate","STEADY","private-iteration-2",2,1L,2L)
                .withWorkloadIdentity("checkout","template","PAYMENT"));
        Map<String,Object> snapshot=sampler.snapshot();
        @SuppressWarnings("unchecked") Map<String,Object> counts=(Map<String,Object>)snapshot.get("counts");
        assertEquals(2L,counts.get("scheduled"));assertEquals(1L,counts.get("completed"));assertEquals(1L,counts.get("passed"));
        assertEquals(1L,counts.get("dropped"));assertEquals(0L,counts.get("queued"));
        @SuppressWarnings("unchecked") Map<String,Object> latency=(Map<String,Object>)snapshot.get("latency");
        assertEquals(1L,latency.get("sampleCount"));assertEquals(50.0,latency.get("meanMs"));
        Map<?,?> workload=(Map<?,?>)((Map<?,?>)snapshot.get("workloads")).get("checkout");
        assertEquals(50.0,((Map<?,?>)workload.get("latency")).get("meanMs"));
        assertFalse(snapshot.toString().contains("private-iteration"));
        assertEquals("DROPPED",((Map<?,?>)snapshot.get("latestFailure")).get("status"));
        assertTrue(snapshot.containsKey("measuredCounts"));assertTrue(snapshot.containsKey("phases"));
        sampler.finish("PASS");
    }

    @Test void emitsOneStartPeriodicProgressAndNoSuccessfulIterationLine() throws Exception {
        LoadConsoleSampler sampler = new LoadConsoleSampler("load-1", 30L, 1000L);
        assertEquals("[LOAD] START runId=load-1 model=closed", sampler.startLine("closed"));
        assertNull(sampler.startLine("closed"));
        String progress = null;
        for (int i = 1; i <= 50; i++) {
            String line = sampler.onEvent(LoadEvent.completed("load-1", "closed", "STEADY", "secret-iteration-" + i,
                    "VU-1", i, 1L, 2L, 5L, ResultStatus.PASS));
            if (line != null) progress = line;
            if (line != null) assertFalse(line.contains("secret-iteration"));
        }
        Thread.sleep(40L);
        String later = sampler.onEvent(LoadEvent.completed("load-1", "closed", "STEADY", "secret-iteration-51",
                "VU-1", 51, 1L, 2L, 5L, ResultStatus.PASS));
        assertNotNull(later == null ? progress : later);
        String shown = later == null ? progress : later;
        assertTrue(shown.startsWith("[LOAD] PROGRESS runId=load-1"));
        assertFalse(shown.contains("secret-iteration"));
        String complete = sampler.finish("PASS");
        assertTrue(complete.startsWith("[LOAD] COMPLETE runId=load-1 status=PASS"));
        assertNull(sampler.finish("PASS"));
        assertNull(sampler.onEvent(LoadEvent.completed("load-1", "closed", "STEADY", "after", "VU-1", 99, 1L, 2L, 5L, ResultStatus.PASS)));
    }

    @Test void usesTheFirstEngineRunIdWhenTheRequestOmitsIt() throws Exception {
        LoadConsoleSampler sampler = new LoadConsoleSampler(null, 30L, 0L);
        assertEquals("[LOAD] START runId=load-generated model=closed", sampler.startLine("closed", "load-generated"));
        String error = sampler.onEvent(LoadEvent.completed("load-generated", "closed", "STEADY", "secret-iteration",
                "VU-1", 1, 1L, 2L, 5L, ResultStatus.FAIL, "timeout", null));
        assertNotNull(error);
        assertTrue(error.startsWith("[LOAD] ERROR runId=load-generated"));
        assertFalse(error.contains("secret-iteration"));
        Thread.sleep(40L);
        String progress = sampler.onEvent(LoadEvent.completed("load-other", "closed", "STEADY", "secret-iteration-2",
                "VU-1", 2, 1L, 2L, 5L, ResultStatus.PASS));
        assertNotNull(progress);
        assertTrue(progress.startsWith("[LOAD] PROGRESS runId=load-generated"));
        assertTrue(sampler.finish("FAIL").startsWith("[LOAD] COMPLETE runId=load-generated status=FAIL"));
    }

    @Test void keepsASuppliedRequestRunId() {
        LoadConsoleSampler sampler = new LoadConsoleSampler("requested-id", 5000L, 0L);
        assertEquals("[LOAD] START runId=requested-id model=closed", sampler.startLine("closed", "load-generated"));
        String error = sampler.onEvent(LoadEvent.dropped("load-generated", "closed", "STEADY", "iter", 1, 1L, 2L));
        assertNotNull(error);
        assertTrue(error.startsWith("[LOAD] ERROR runId=requested-id"));
        assertTrue(sampler.finish("PASS").startsWith("[LOAD] COMPLETE runId=requested-id status=PASS"));
    }

    @Test void finishBeforeAnyEventUsesTheRequestIdOrUnknown() {
        LoadConsoleSampler omitted = new LoadConsoleSampler("  ");
        String complete = omitted.finish("ERROR");
        assertTrue(complete.startsWith("[LOAD] COMPLETE runId=unknown status=ERROR"));
        assertNull(omitted.finish("ERROR"));
        assertNull(omitted.onEvent(LoadEvent.completed("load-too-late", "closed", "STEADY", "after", "VU-1", 1, 1L, 2L, 5L, ResultStatus.ERROR)));
        LoadConsoleSampler supplied = new LoadConsoleSampler("requested-id");
        assertTrue(supplied.finish("ERROR").startsWith("[LOAD] COMPLETE runId=requested-id status=ERROR"));
    }

    @Test void emitsPeriodicProgressWithoutEventsAndStopsWhenFinished() throws Exception {
        List<String> ticks = Collections.synchronizedList(new ArrayList<String>());
        LoadConsoleSampler sampler = new LoadConsoleSampler("load-idle", 40L, 1000L, ticks::add);
        assertEquals("[LOAD] START runId=load-idle model=closed", sampler.startLine("closed"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (ticks.size() < 2 && System.nanoTime() < deadline) Thread.sleep(5L);
        List<String> during = new ArrayList<String>(ticks);
        assertTrue(during.size() >= 2, during.toString());
        for (String line : during) {
            assertTrue(line.startsWith("[LOAD] PROGRESS runId=load-idle"), line);
            assertFalse(line.contains("COMPLETE"), line);
        }
        int beforeFinish = ticks.size();
        String complete = sampler.finish("PASS");
        assertTrue(complete.startsWith("[LOAD] COMPLETE runId=load-idle status=PASS"));
        Thread.sleep(120L);
        assertEquals(beforeFinish, ticks.size(), ticks.toString());
    }

    @Test void structuredOnlyProgressSinkPublishesWhileWorkerHasNoIterationEvents() throws Exception {
        List<Map<String,Object>> snapshots = Collections.synchronizedList(new ArrayList<>());
        LoadConsoleSampler sampler = new LoadConsoleSampler("structured-idle", 40L, 1000L, null,
                (line, snapshot) -> snapshots.add(snapshot));
        sampler.startLine("arrivalRate");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (snapshots.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5L);
        assertFalse(snapshots.isEmpty(), "The structured callback needs its own timer even without a console-line sink");
        assertEquals("att-load-live-progress/v1", snapshots.get(0).get("schemaVersion"));
        sampler.finish("PASS");
    }

    @Test void concurrentIterationsAndTheTimerShareOneProgressInterval() throws Exception {
        List<String> ticks = Collections.synchronizedList(new ArrayList<String>());
        LoadConsoleSampler sampler = new LoadConsoleSampler("load-race", 80L, 1000L, ticks::add);
        assertNotNull(sampler.startLine("closed"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (ticks.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5L);
        assertEquals(1, ticks.size(), ticks.toString());
        int eventProgress = 0;
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            String line = sampler.onEvent(LoadEvent.completed("load-race", "closed", "STEADY", "iter",
                    "VU-1", 1, 1L, 2L, 5L, ResultStatus.PASS));
            if (line != null && line.contains("[LOAD] PROGRESS")) eventProgress++;
        }
        assertEquals(0, eventProgress);
        assertTrue(ticks.size() <= 2, ticks.toString()); // A second interval may elapse if the test thread is descheduled.

        LoadConsoleSampler burst = new LoadConsoleSampler("load-burst", 30L, 1000L);
        assertNotNull(burst.startLine("closed"));
        Thread.sleep(40L);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger progress = new AtomicInteger();
        List<Future<?>> tasks = new ArrayList<Future<?>>();
        for (int i = 0; i < threads; i++) {
            final int id = i;
            tasks.add(pool.submit(new Runnable() {
                @Override public void run() {
                    try { barrier.await(2, TimeUnit.SECONDS); }
                    catch (Exception error) { throw new IllegalStateException(error); }
                    String line = burst.onEvent(LoadEvent.completed("load-burst", "closed", "STEADY", "iter-" + id,
                            "VU-" + id, id + 1L, 1L, 2L, 5L, ResultStatus.PASS));
                    if (line != null && line.contains("[LOAD] PROGRESS")) progress.incrementAndGet();
                }
            }));
        }
        for (Future<?> task : tasks) assertNull(task.get(5, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(1, progress.get());
        int ticksBeforeFinish = ticks.size();
        assertTrue(sampler.finish("PASS").startsWith("[LOAD] COMPLETE runId=load-race"));
        assertTrue(burst.finish("PASS").startsWith("[LOAD] COMPLETE runId=load-burst"));
        Thread.sleep(200L);
        assertEquals(ticksBeforeFinish, ticks.size(), ticks.toString());
    }
}
