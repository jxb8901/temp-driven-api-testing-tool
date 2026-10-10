package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class JobEventsTest {
    @TempDir Path temp;
    @Test void retainedEventsUseIncreasingJobLocalIdsAndReplayAfterLastEventId() throws Exception {
        Path file=temp.resolve("events.jsonl");JobEvents events=new JobEvents(file,3);
        events.append("status",Map.of("status","QUEUED"));events.append("status",Map.of("status","RUNNING"));events.append("result",Map.of("status","PASS"));
        JobEvents reopened=new JobEvents(file,3);
        assertEquals(3,reopened.latest());assertEquals(2,reopened.after(1).size());assertEquals(3L,((Number)reopened.after(1).get(1).get("id")).longValue());
        assertFalse(reopened.resultDeliveredThrough(2));assertTrue(reopened.resultDeliveredThrough(3));
        reopened.append("log",Map.of("message","x".repeat(70000)));
        assertEquals(true,reopened.after(3).get(0).get("data").toString().contains("truncated"));
        assertTrue(Files.size(file)>0);
    }
    @Test void slowObserverNotificationsAreBoundedWhileJournalRetainsEveryEvent() throws Exception {
        JobEvents events=new JobEvents(temp.resolve("fanout.jsonl"),10);java.util.concurrent.ArrayBlockingQueue<Boolean> wakeup=new java.util.concurrent.ArrayBlockingQueue<>(1);
        AutoCloseable subscription=events.listen(event->wakeup.offer(Boolean.TRUE));
        events.append("progress",Map.of("sequence",1));events.append("progress",Map.of("sequence",2));
        assertEquals(1,wakeup.size());assertEquals(2,events.after(0).size());subscription.close();
    }

    @Test void replayUsesJobLocalCursorAndHonorsBatchLimit() throws Exception {
        JobEvents events=new JobEvents(temp.resolve("cursor.jsonl"),32,Runnable::run);
        for(int i=1;i<=256;i++)events.append("progress",Map.of("sequence",i));

        assertEquals(List.of(250L,251L,252L,253L),events.after(249,4).stream()
                .map(event->((Number)event.get("id")).longValue()).toList());
        assertTrue(events.hasMore(255));
        assertFalse(events.hasMore(256));
        assertTrue(events.after(256,0).isEmpty());
    }

    @Test void concurrentJobJournalsKeepIndependentOrderedSequences() throws Exception {
        int jobs=4, eventsPerJob=256;
        java.util.concurrent.ExecutorService writers=java.util.concurrent.Executors.newFixedThreadPool(jobs);
        try {
            List<JobEvents> journals=new java.util.ArrayList<>();
            List<java.util.concurrent.Future<?>> writes=new java.util.ArrayList<>();
            for(int job=0;job<jobs;job++) {
                final int jobIndex=job;
                JobEvents journal=new JobEvents(temp.resolve("job-"+job+".jsonl"),64,Runnable::run);
                journals.add(journal);
                writes.add(writers.submit(()->{
                    for(int i=1;i<=eventsPerJob;i++) {
                        try {journal.append("progress",Map.of("job",jobIndex,"sequence",i));}
                        catch(Exception failure) {throw new IllegalStateException(failure);}
                    }
                }));
            }
            for(java.util.concurrent.Future<?> write:writes)write.get(10,TimeUnit.SECONDS);
            for(int job=0;job<jobs;job++) {
                JobEvents journal=journals.get(job);
                assertEquals(eventsPerJob,journal.latest());
                assertEquals(List.of(253L,254L,255L,256L),journal.after(252).stream()
                        .map(event->((Number)event.get("id")).longValue()).toList());
            }
        } finally {
            writers.shutdownNow();
        }
    }

    @Test void concurrentReadersCanCompactTheSameRecoveredJournalSafely() throws Exception {
        Path file=temp.resolve("recovered-shared.jsonl");
        List<String> rows=new java.util.ArrayList<>();
        for(int id=1;id<=128;id++)rows.add("{\"id\":"+id+",\"event\":\"progress\",\"data\":{\"sequence\":"+id+"}}");
        Files.write(file,rows,java.nio.charset.StandardCharsets.UTF_8);

        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CyclicBarrier bothCompactions=new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.Executor executor=command->pool.execute(()->{
            try {bothCompactions.await(5,TimeUnit.SECONDS);command.run();}
            catch(Exception failure) {throw new IllegalStateException(failure);}
        });
        try {
            new JobEvents(file,32,executor);
            new JobEvents(file,32,executor);
            pool.shutdown();
            assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS),"Compactions should complete");

            JobEvents reopened=new JobEvents(file,32,Runnable::run);
            assertEquals(128,reopened.latest());
            assertEquals(java.util.stream.IntStream.rangeClosed(97,128).mapToObj(id->(long)id).toList(),
                    reopened.after(96).stream().map(event->((Number)event.get("id")).longValue()).toList());
            assertEquals(32,Files.readAllLines(file).size());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void compactionPreservesConfiguredOnDiskRetentionWindow() throws Exception {
        Path file=temp.resolve("retained-window.jsonl");
        JobEvents events=new JobEvents(file,3,Runnable::run);
        for(int i=1;i<=384;i++)events.append("log",Map.of("sequence",i));
        JobEvents reopened=new JobEvents(file,3,Runnable::run);

        assertEquals(384,reopened.latest());
        assertEquals(3,Files.readAllLines(file).size());
        assertEquals(List.of(382L,383L,384L),reopened.after(381).stream()
                .map(event->((Number)event.get("id")).longValue()).toList());
    }

    @Test void compactionRunsOutsideAppendPathAndRestartCanReplayWhileSnapshotIsPending() throws Exception {
        Path file=temp.resolve("async-compaction.jsonl");
        CountDownLatch snapshotWritten=new CountDownLatch(1),allowReplace=new CountDownLatch(1);
        java.util.concurrent.Executor executor=command->{Thread worker=new Thread(command,"test-event-compactor");worker.setDaemon(true);worker.start();};
        JobEvents events=new JobEvents(file,128,executor,()->{
            snapshotWritten.countDown();
            try {if(!allowReplace.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test compaction gate timed out");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
        });
        for(int i=1;i<=128;i++)events.append("progress",Map.of("sequence",i));
        assertTrue(snapshotWritten.await(5,TimeUnit.SECONDS),"Compaction should run asynchronously after the append returns");
        JobEvents restartedDuringCompaction=new JobEvents(file,128,Runnable::run);
        assertEquals(128,restartedDuringCompaction.latest());
        assertEquals(128,restartedDuringCompaction.after(0).size());

        for(int i=129;i<=144;i++)events.append("log",Map.of("sequence",i));
        allowReplace.countDown();

        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(Files.readAllLines(file).size()!=128&&System.nanoTime()<deadline)Thread.sleep(5);
        JobEvents recovered=new JobEvents(file,128,Runnable::run);
        assertEquals(128,Files.readAllLines(file).size(),"Compaction should retry from a fresh snapshot when the tail exceeds retention");
        assertEquals(144,recovered.latest());
        assertEquals(16,recovered.after(128).size());
        assertEquals(128,Files.readAllLines(file).size());
    }
}
