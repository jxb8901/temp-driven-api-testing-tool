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
    @Test void concurrentExpandedEntriesKeepReplayBoundedAndReportMeasuredCost() throws Exception {
        int jobs=4,count=200;
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(jobs);
        long started=System.nanoTime();
        try {
            List<java.util.concurrent.Future<Long>> tasks=new java.util.ArrayList<>();
            for(int j=0;j<jobs;j++) {
                Path file=temp.resolve("cost-"+j+".jsonl");
                tasks.add(pool.submit(()-> {
                    JobEvents journal=new JobEvents(file,100,Runnable::run);
                    String text="diagnostic "+"x".repeat(15900);
                    for(int i=0;i<count;i++)journal.append("log",Map.of("console",text));
                    JobEvents replay=new JobEvents(file,100,Runnable::run);
                    assertEquals(100,replay.after(0).size());
                    assertEquals(text,((Map<?,?>)replay.after(0).get(0).get("data")).get("console"));
                    return Files.size(file);
                }));
            }
            long bytes=0;for(var task:tasks)bytes+=task.get(30,TimeUnit.SECONDS);
            System.out.println("Expanded Live Output check: jobs="+jobs+", events="+(jobs*count)+", elapsedMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)+", retainedJournalBytes="+bytes);
            assertTrue(bytes<20_000_000,"Compacted journals must stay proportional to bounded history");
        } finally {pool.shutdownNow();}
    }
    @Test void largerConsoleSurvivesJournalAndReplayWithStrictEncodedByteLimit() throws Exception {
        Path file=temp.resolve("expanded.jsonl");
        JobEvents journal=new JobEvents(file,100,Runnable::run);
        String console="long diagnostic "+"x".repeat(15000);
        journal.append("log",Map.of("console",console,"largeOtherField","x".repeat(70000)));
        Map<?,?> retained=(Map<?,?>)journal.after(0).get(0).get("data");
        assertEquals(console,retained.get("console"));assertEquals(true,retained.get("truncated"));
        for(String text:List.of("界".repeat(32000),"\u0001".repeat(32000),"\uD83D\uDE00".repeat(16000))) {
            journal.append("log",Map.of("console",text));
            Map<?,?> payload=(Map<?,?>)journal.after(journal.latest()-1).get(0).get("data");
            assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(payload).length<=65536);
            if(!text.equals(payload.get("console"))) {
                assertEquals(true,payload.get("consoleTruncated"));
                assertTrue(payload.get("console").toString().endsWith("[truncated by journal]"));
            }
        }
        JobEvents replay=new JobEvents(file,100,Runnable::run);
        assertEquals(console,((Map<?,?>)replay.after(0).get(0).get("data")).get("console"));
    }

    @Test void oversizedStatusEventKeepsStructuredIdentityAndReportsDroppedFields() throws Exception {
        JobEvents journal=new JobEvents(temp.resolve("structured.jsonl"),10,Runnable::run);
        Map<String,Object> event=new java.util.LinkedHashMap<>();event.put("status","FAIL");event.put("executionEventType","CASE_FINISHED");
        event.put("caseId","case-17");event.put("executionId","run-9");event.put("stage","verify");event.put("code","ATT-CASE-FAILED");
        event.put("console","界".repeat(32000));event.put("largeDetails","x".repeat(70000));
        journal.append("status",event);Map<?,?> payload=(Map<?,?>)journal.after(0).get(0).get("data");
        assertEquals("FAIL",payload.get("status"));assertEquals("CASE_FINISHED",payload.get("executionEventType"));
        assertEquals("case-17",payload.get("caseId"));assertEquals("run-9",payload.get("executionId"));
        Map<?,?> limit=(Map<?,?>)payload.get("journalLimit");
        assertEquals("ATT-JOURNAL-EVENT-TRUNCATED",limit.get("code"));
        assertEquals("DATA_TRUNCATED",limit.get("outcome"));
        assertEquals("server.eventJournal.maxEventDataBytes",limit.get("limitName"));
        assertTrue(((Number)limit.get("observed")).longValue()>((Number)limit.get("configuredLimit")).longValue());
        assertTrue(((List<?>)payload.get("droppedFields")).contains("largeDetails"));
        assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(payload).length<=65536);
        JobEvents replay=new JobEvents(temp.resolve("structured.jsonl"),10,Runnable::run);Map<?,?> replayed=(Map<?,?>)replay.after(0).get(0).get("data");
        assertEquals("FAIL",replayed.get("status"));assertEquals("CASE_FINISHED",replayed.get("executionEventType"));
    }

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

    @Test void sparseSixGigabyteJournalLoadsOnlyTheConfiguredTailAndPreservesItsSequence() throws Exception {
        Path file=temp.resolve("sparse-oversized.jsonl");
        long sparseSize=6L*1024L*1024L*1024L;
        try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(sparseSize-1L);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{'\n'}));
        }
        Files.writeString(file,
                "{\"id\":900,\"event\":\"progress\",\"data\":{\"sequence\":900}}\n"
                        +"{\"id\":901,\"event\":\"result\",\"data\":{\"status\":\"ERROR\"}}\n",
                java.nio.charset.StandardCharsets.UTF_8,java.nio.file.StandardOpenOption.APPEND);

        JobEvents journal=new JobEvents(file,10,4096,Runnable::run);

        assertEquals(901,journal.latest());
        assertEquals(List.of(900L,901L),journal.after(0).stream()
                .map(event->((Number)event.get("id")).longValue()).toList());
        assertTrue(((Number)journal.metrics().get("journalBytes")).longValue()>sparseSize);
        assertTrue(((Number)journal.metrics().get("bytesScanned")).longValue()<=4096);
        assertEquals("ATT-JOURNAL-LIMIT-EXCEEDED",journal.diagnostic().get("code"));
        long originalSize=Files.size(file);
        Map<String,Object> inMemoryOnly=journal.append("diagnostic",Map.of("summary","history is read-only"));
        assertEquals(902L,inMemoryOnly.get("id"));
        assertEquals(originalSize,Files.size(file),"Oversized source evidence stays unchanged until operator repair");
    }

    @Test void unterminatedSparseJournalIsNotScannedOrAppendedPastAnUnknownSequence() throws Exception {
        Path file=temp.resolve("sparse-unterminated.jsonl");
        try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(6L*1024L*1024L*1024L-1L);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{'x'}));
        }
        JobEvents journal=new JobEvents(file,10,4096,Runnable::run);
        long originalSize=Files.size(file);

        assertTrue(journal.historyDegraded());
        assertTrue(journal.metrics().get("bytesScanned") instanceof Number);
        assertTrue(((Number)journal.metrics().get("bytesScanned")).longValue()<=4096);
        assertEquals("DEGRADED",journal.metrics().get("journalPersistence"));
        assertEquals(originalSize,Files.size(file));
        journal.append("diagnostic",Map.of("summary","sequence cannot be verified"));
        assertEquals(originalSize,Files.size(file),"an unknown prior ID must not be overwritten or reused");
    }

    @Test void malformedUnterminatedFinalRecordIsPreservedAndBlocksSequenceReuse() throws Exception {
        Path file=temp.resolve("malformed-unterminated.jsonl");
        Files.writeString(file,"{\"id\":1,\"event\":\"status\",\"data\":{}}\n{\"id\":900,\"event\":\"result\",\"data\":",java.nio.charset.StandardCharsets.UTF_8);
        long originalSize=Files.size(file);

        JobEvents journal=new JobEvents(file,10,1024*1024,Runnable::run);
        Map<String,Object> undeliverable=journal.append("diagnostic",Map.of("summary","do not reuse an unknown cursor ID"));

        assertTrue(journal.historyDegraded());
        assertEquals("DEGRADED",journal.metrics().get("journalPersistence"));
        assertFalse(undeliverable.containsKey("id"),"no cursor can be assigned safely after an unknown tail");
        assertEquals(1,journal.latest(),"the unverified trailing ID must not be reused");
        assertEquals(originalSize,Files.size(file),"the incomplete source record remains byte-for-byte intact");
    }

    @Test void missingTerminalMarkerIsRecoveredInMemoryFromJobMetadataWithoutRewritingHistory() throws Exception {
        Path file=temp.resolve("terminal-result-missing.jsonl");
        JobEvents journal=new JobEvents(file,10,1024*1024,Runnable::run);
        journal.append("status",Map.of("status","RUNNING"));
        long originalSize=Files.size(file);

        journal.recoverMissingTerminalResult(Map.of("jobId","job-1","status","FAIL","exitCode",1,
                "result",Map.of("status","FAIL")));

        assertTrue(journal.historyDegraded());
        assertTrue(journal.resultDeliveredThrough(journal.latest()));
        assertEquals("FAIL",((Map<?,?>)journal.after(1).get(0).get("data")).get("status"));
        assertEquals(originalSize,Files.size(file),"recovery from metadata must not rewrite source evidence");
        assertEquals("DEGRADED",journal.metrics().get("journalPersistence"));
    }

    @Test void oversizedAndMalformedLinesAreSkippedWithoutUnboundedReadLineAllocation() throws Exception {
        Path file=temp.resolve("oversized-line.jsonl");
        String giant="{\"id\":1,\"event\":\"log\",\"data\":{\"console\":\""+"x".repeat(JobEvents.MAX_EVENT_LINE_BYTES+2048)+"\"}}\n";
        Files.writeString(file,giant+"{\"id\":2,\"event\":\"result\",\"data\":{\"status\":\"PASS\"}}\n");

        JobEvents journal=new JobEvents(file,10,1024*1024,Runnable::run);

        assertEquals(2,journal.latest());
        assertEquals(List.of(2L),journal.after(0).stream()
                .map(event->((Number)event.get("id")).longValue()).toList());
        assertTrue(journal.historyDegraded());
        assertEquals("ATT-JOURNAL-HISTORY-TRUNCATED",journal.diagnostic().get("code"));
    }

    @Test void journalCompactionKeepsOnDiskGrowthInsideTheConfiguredByteBudget() throws Exception {
        Path file=temp.resolve("byte-bounded.jsonl");
        long byteBudget=2048;
        JobEvents journal=new JobEvents(file,100,byteBudget,Runnable::run);
        for(int id=1;id<=500;id++)journal.append("progress",Map.of("sequence",id,"value","x".repeat(20)));

        assertTrue(Files.size(file)<=byteBudget,"the durable journal must stay inside its byte budget");
        assertEquals(500,journal.latest());
        assertEquals(500L,((Number)journal.after(499).get(0).get("id")).longValue());
        assertTrue(((Number)journal.metrics().get("compactionAttempts")).longValue()>0);
        JobEvents reopened=new JobEvents(file,100,byteBudget,Runnable::run);
        assertEquals(500,reopened.latest());
        assertEquals(1,reopened.after(499).size());
    }

    @Test void concurrentSustainedAppendsConvergeWithoutCrossingTheJournalByteCap() throws Exception {
        Path file=temp.resolve("concurrent-byte-bounded.jsonl");
        long byteBudget=8192;
        JobEvents journal=new JobEvents(file,128,byteBudget,Runnable::run);
        java.util.concurrent.ExecutorService writers=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<?>> tasks=new java.util.ArrayList<>();
            for(int writer=0;writer<4;writer++) {
                int writerId=writer;
                tasks.add(writers.submit(()->{
                    for(int index=0;index<1000;index++) {
                        try {journal.append("progress",Map.of("writer",writerId,"sequence",index,"detail","x".repeat(32)));}
                        catch(Exception failure) {throw new IllegalStateException(failure);}
                    }
                }));
            }
            for(var task:tasks)task.get(20,TimeUnit.SECONDS);
        } finally {
            writers.shutdownNow();
        }
        assertEquals(4000,journal.latest());
        assertTrue(Files.size(file)<=byteBudget);
        List<Long> retained=journal.after(0).stream().map(event->((Number)event.get("id")).longValue()).toList();
        assertFalse(retained.isEmpty());
        assertEquals(4000L,retained.get(retained.size()-1));
        assertTrue(retained.size()<=128);
        assertEquals(4000,new JobEvents(file,128,byteBudget,Runnable::run).latest());
    }

    @Test void lowStorageHeadroomDegradesOnlyThisJournalAndRateLimitsWrites() throws Exception {
        Path file=temp.resolve("no-space.jsonl");
        JobEvents journal=new JobEvents(file,100,1024*1024,Runnable::run,()->{},()->0L);

        Map<String,Object> first=journal.append("result",Map.of("status","ERROR"));
        Map<String,Object> second=journal.append("diagnostic",Map.of("summary","still responsive"));

        assertEquals(1L,first.get("id"));
        assertEquals(2L,second.get("id"));
        assertEquals(2,journal.after(0).size(),"in-memory delivery remains available during ENOSPC");
        assertFalse(Files.exists(file),"failed persistence must not create an incomplete journal");
        assertEquals("DEGRADED",journal.metrics().get("journalPersistence"));
        assertEquals("ATT-JOURNAL-PERSISTENCE-DEGRADED",journal.diagnostic().get("code"));
    }

    @Test void memoryShareRetainsOneBoundedNewestEventAndReportsItsOverage() throws Exception {
        JobEvents journal=new JobEvents(temp.resolve("small-memory-share.jsonl"),100,1024*1024,1024,Runnable::run);
        journal.append("log",Map.of("console","x".repeat(5000)));
        journal.append("result",Map.of("status","FAIL","console","y".repeat(6000)));

        List<Map<String,Object>> retained=journal.after(0);
        assertEquals(1,retained.size(),"old records are evicted when this Job reaches its memory share");
        assertEquals(2L,retained.get(0).get("id"),"the newest event remains available for live/replay delivery");
        assertEquals(true,journal.metrics().get("newestRecordExceedsMemoryShare"));
        assertTrue(((Number)journal.metrics().get("retainedMemoryBytes")).longValue()>1024L);
    }

    @Test void idsRemainStrictlyIncreasingAcrossTrimCompactionReopenAndATornFinalLine() throws Exception {
        Path file=temp.resolve("monotonic-recovery.jsonl");
        JobEvents journal=new JobEvents(file,3,Runnable::run);
        List<Long> appended=new java.util.ArrayList<>(),delivered=new java.util.ArrayList<>();
        AutoCloseable subscription=journal.listen(event->delivered.add(((Number)event.get("id")).longValue()));
        for(int i=1;i<=140;i++)appended.add(((Number)journal.append("progress",Map.of("sequence",i)).get("id")).longValue());
        subscription.close();

        assertEquals(140,journal.latest());
        assertEquals(15,Files.readAllLines(file).size(),"ID 128 compacts to the retained three before later appends");
        JobEvents reopened=new JobEvents(file,3,Runnable::run);
        assertEquals(3,Files.readAllLines(file).size(),"Reopen compacts the trimmed retention window");
        AutoCloseable reopenedSubscription=reopened.listen(event->delivered.add(((Number)event.get("id")).longValue()));
        appended.add(((Number)reopened.append("log",Map.of("message","after reopen")).get("id")).longValue());
        reopenedSubscription.close();

        Files.write(file,"{\"id\":999".getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.APPEND);
        long malformedSize=Files.size(file);
        JobEvents recoveredFromTornTail=new JobEvents(file,3,Runnable::run);
        Map<String,Object> afterUnknownTail=recoveredFromTornTail.append("log",Map.of("message","after torn tail"));

        for(int index=1;index<appended.size();index++) {
            assertTrue(appended.get(index)>appended.get(index-1),"Every append ID must be strictly increasing");
        }
        assertFalse(afterUnknownTail.containsKey("id"),"An unknown torn record cannot be assigned a safe following cursor");
        assertEquals(141L,recoveredFromTornTail.latest(),"The torn record must not advance or reset the last complete cursor");
        assertTrue(recoveredFromTornTail.historyDegraded());
        assertTrue(delivered.stream().allMatch(id->id<=141L));

        JobEvents reopenedAgain=new JobEvents(file,3,Runnable::run);
        assertEquals(141,reopenedAgain.latest(),"No new bytes may be appended after an unknown torn tail");
        assertEquals(malformedSize,Files.size(file));
    }

    @Test void replayAfterCursorNeverReturnsThatCursorOrAnEarlierIdAfterReopen() throws Exception {
        Path file=temp.resolve("strict-after-reopen.jsonl");
        JobEvents journal=new JobEvents(file,4,Runnable::run);
        for(int id=1;id<=8;id++)journal.append("progress",Map.of("sequence",id));

        JobEvents reopened=new JobEvents(file,4,Runnable::run);
        for(long cursor:List.of(0L,4L,6L,8L)) {
            assertTrue(reopened.after(cursor).stream()
                    .map(event->((Number)event.get("id")).longValue())
                    .allMatch(id->id>cursor),"Replay must be strictly after cursor "+cursor);
        }
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

    @Test void failedCatchUpWaitsForNextAppendThresholdAndThenMakesProgress() throws Exception {
        Path file=temp.resolve("bounded-compaction-retry.jsonl");
        java.util.concurrent.atomic.AtomicInteger compactions=new java.util.concurrent.atomic.AtomicInteger();
        final JobEvents[] journal=new JobEvents[1];
        journal[0]=new JobEvents(file,128,Runnable::run,()->{
            if(compactions.getAndIncrement()==0) {
                try {
                    for(int i=1;i<=33;i++)journal[0].append("progress",Map.of("burst",i));
                } catch(Exception failure) {throw new IllegalStateException(failure);}
            }
        });

        for(int i=1;i<=128;i++)journal[0].append("progress",Map.of("sequence",i));
        assertEquals(1,compactions.get(),"An oversized catch-up must not immediately resubmit compaction");
        assertEquals(161,journal[0].latest());
        assertEquals(161,Files.readAllLines(file).size(),"A failed snapshot must leave the original journal intact");

        for(int i=162;i<=256;i++)journal[0].append("progress",Map.of("sequence",i));
        assertEquals(2,compactions.get(),"The next event threshold should retry compaction once");
        assertEquals(256,journal[0].latest());
        assertEquals(128,Files.readAllLines(file).size(),"A later snapshot should restore the configured disk window");
        JobEvents recovered=new JobEvents(file,128,Runnable::run);
        assertEquals(256,recovered.latest());
        assertEquals(128,recovered.after(128).size());
    }
}
