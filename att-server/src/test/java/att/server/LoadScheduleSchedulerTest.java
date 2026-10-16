package att.server;

import att.server.api.WorkerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LoadScheduleSchedulerTest {
    @TempDir Path temp;

    @Test void oneTimeRunAndLoadOccurrencesAreClaimedOnceAndMissesAreNotReplayed() throws Exception {
        MutableClock clock=new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ServerConfig config=config("one-time",1,"continue",2);
        AtomicInteger submitted=new AtomicInteger();
        try(JobStore store=new JobStore(config);LoadScheduleScheduler scheduler=new LoadScheduleScheduler(config.scheduling,store,(schedule,id)->{
            submitted.incrementAndGet();return Map.of("jobId","J0000000000000001");
        },clock)) {
            for(String type:List.of("run","load")) {
                LoadSchedule once=schedule("SONCE"+type,clock.instant());once.jobType=type;once.cron=null;once.runAt=once.nextRunAt;store.saveSchedule(once);
            }
            scheduler.processDue();scheduler.processDue();
            assertEquals(2,submitted.get());
            for(String type:List.of("run","load")) {
                LoadSchedule once=store.schedule("SONCE"+type);assertNull(once.nextRunAt);
                assertEquals("once",store.scheduleExecutions(once.id,10).get(0).get("triggerType"));
                assertEquals(1,store.scheduleExecutions(once.id,10).size());
            }
            LoadSchedule missed=schedule("SONCEMISSED",clock.instant().minusSeconds(60));missed.cron=null;missed.runAt=missed.nextRunAt;store.saveSchedule(missed);
            scheduler.processDue();assertEquals(2,submitted.get());assertNull(store.schedule(missed.id).nextRunAt);
            assertEquals("misfire-no-backfill",store.scheduleExecutions(missed.id,10).get(0).get("reason"));
        }
        try(JobStore store=new JobStore(config);LoadScheduleScheduler restarted=new LoadScheduleScheduler(config.scheduling,store,(schedule,id)->{submitted.incrementAndGet();return Map.of("jobId","unexpected");},clock)) {
            restarted.processDue();assertEquals(2,submitted.get());
        }
    }

    @Test void skipsOverlappingRunsPerScheduleAndAcrossTheGlobalSchedulerCap() throws Exception {
        MutableClock clock=new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ServerConfig config=config("overlap",1,"continue",1);
        try(JobStore store=new JobStore(config);LoadScheduleScheduler scheduler=new LoadScheduleScheduler(config.scheduling,store,recordingSubmitter(config,store,new AtomicInteger(),new ArrayList<>()),clock)) {
            LoadSchedule one=schedule("SOVERLAP",clock.instant());store.saveSchedule(one);
            scheduler.processDue();
            clock.advance(Duration.ofMinutes(1));scheduler.processDue();
            List<Map<String,Object>> history=store.scheduleExecutions(one.id,10);
            assertEquals(2,history.size());
            assertTrue(history.stream().anyMatch(row->"submitted".equals(row.get("status"))));
            Map<String,Object> skipped=history.stream().filter(row->"skipped".equals(row.get("status"))).findFirst().orElseThrow();
            assertEquals("schedule-overlap-limit",skipped.get("reason"));
        }

        MutableClock perScheduleClock=new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));ServerConfig perScheduleConfig=config("per-schedule-cap",1,"continue",2);
        AtomicInteger perScheduleSubmitted=new AtomicInteger();List<Job> perScheduleActive=new ArrayList<>();
        try(JobStore store=new JobStore(perScheduleConfig);LoadScheduleScheduler scheduler=new LoadScheduleScheduler(perScheduleConfig.scheduling,store,
                recordingSubmitter(perScheduleConfig,store,perScheduleSubmitted,perScheduleActive),perScheduleClock)) {
            LoadSchedule two=schedule("SPER-SCHEDULE",perScheduleClock.instant());two.maxConcurrentRuns=2;store.saveSchedule(two);
            scheduler.processDue();perScheduleClock.advance(Duration.ofMinutes(1));scheduler.processDue();
            perScheduleClock.advance(Duration.ofMinutes(1));scheduler.processDue();
            List<Map<String,Object>> history=store.scheduleExecutions(two.id,10);
            assertEquals(2,perScheduleSubmitted.get());assertEquals(3,history.size());
            assertEquals(2,history.stream().filter(row->"submitted".equals(row.get("status"))).count());
            Map<String,Object> skipped=history.stream().filter(row->"skipped".equals(row.get("status"))).findFirst().orElseThrow();
            assertEquals("schedule-overlap-limit",skipped.get("reason"));
        }

        MutableClock globalClock=new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ServerConfig globalConfig=config("global",1,"continue",1);
        AtomicInteger submitted=new AtomicInteger();List<Job> active=new ArrayList<>();
        try(JobStore store=new JobStore(globalConfig);LoadScheduleScheduler scheduler=new LoadScheduleScheduler(globalConfig.scheduling,store,recordingSubmitter(globalConfig,store,submitted,active),globalClock)) {
            store.saveSchedule(schedule("SGLOBAL1",globalClock.instant()));store.saveSchedule(schedule("SGLOBAL2",globalClock.instant()));
            scheduler.processDue();
            List<Map<String,Object>> first=store.scheduleExecutions("SGLOBAL1",10),second=store.scheduleExecutions("SGLOBAL2",10);
            List<Map<String,Object>> all=new ArrayList<>(first);all.addAll(second);
            assertEquals(1,submitted.get());assertEquals(1,all.stream().filter(row->"submitted".equals(row.get("status"))).count());
            Map<String,Object> rejected=all.stream().filter(row->"skipped".equals(row.get("status"))).findFirst().orElseThrow();
            assertEquals("global-scheduled-load-capacity",rejected.get("reason"));
        }
    }

    @Test void recordsDowntimeMisfiresWithoutBackfillingAndRecoversOccurrenceJobLinks() throws Exception {
        MutableClock clock=new MutableClock(Instant.parse("2026-01-01T00:02:00Z"));ServerConfig config=config("misfire",1,"continue",1);
        AtomicInteger submitted=new AtomicInteger();List<Job> active=new ArrayList<>();
        try(JobStore store=new JobStore(config);LoadScheduleScheduler scheduler=new LoadScheduleScheduler(config.scheduling,store,recordingSubmitter(config,store,submitted,active),clock)) {
            LoadSchedule late=schedule("SMISFIRE",Instant.parse("2016-01-01T00:00:00Z"));store.saveSchedule(late);
            scheduler.processDue();
            List<Map<String,Object>> rows=store.scheduleExecutions(late.id,10);
            assertEquals(1,rows.size(),"long downtime should produce bounded history work rather than one DB entry per missed minute");assertEquals(0,submitted.get());
            assertEquals("skipped",rows.get(0).get("status"));assertTrue(String.valueOf(rows.get(0).get("reason")).startsWith("misfire-no-backfill; coalesced-through="));
            assertEquals(Instant.parse("2026-01-01T00:03:00Z").toString(),store.schedule(late.id).nextRunAt,"recovery advances directly past all missed firings");
            scheduler.processDue();assertEquals(1,store.scheduleExecutions(late.id,10).size(),"a subsequent tick must not record the same missed range again");

            String orphan="SMISFIRE-manual-orphan";store.insertManualOccurrence(late.id,orphan,clock.instant());
            String jobOccurrence="SMISFIRE-manual-linked";store.insertManualOccurrence(late.id,jobOccurrence,clock.instant());
        Job linked=job(config,jobOccurrence,"J0000000000000099");store.insert(linked,"{}");
            store.close();
            try(JobStore recovered=new JobStore(config)) {
                recovered.recoverScheduleExecutions();
                assertEquals("failed-to-submit",recovered.scheduleExecution(orphan).get("status"));
                assertEquals("submitted",recovered.scheduleExecution(jobOccurrence).get("status"));
                assertEquals(linked.id,recovered.scheduleExecution(jobOccurrence).get("jobId"));
                assertEquals(store.schedule(late.id).nextRunAt,recovered.schedule(late.id).nextRunAt,"the next planned occurrence persists across store restart");
            }
        }
    }

    @Test void recordsSubmitFailureAndPausesOnlyUnderConfiguredPolicy() throws Exception {
        MutableClock clock=new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));ServerConfig config=config("submit-failure",1,"pauseAfterSubmitFailure",1);
        try(JobStore store=new JobStore(config);LoadScheduleScheduler scheduler=new LoadScheduleScheduler(config.scheduling,store,(schedule,occurrence)->{throw new IllegalStateException("package inspection rejected scenario");},clock)) {
            LoadSchedule schedule=schedule("SFAIL",clock.instant());store.saveSchedule(schedule);scheduler.processDue();
            Map<String,Object> row=store.scheduleExecutions(schedule.id,1).get(0);
            assertEquals("failed-to-submit",row.get("status"));assertTrue(String.valueOf(row.get("reason")).contains("package inspection rejected"));
            assertFalse(store.schedule(schedule.id).enabled);
            assertEquals(1L,((Number)((Map<?,?>)store.scheduleMetrics().get("occurrences")).get("failed-to-submit")).longValue());
        }
    }

    @Test void manualTriggersAreRateLimitedPerScheduleAndCanRunAgainAfterTheConfiguredInterval() throws Exception {
        MutableClock clock=new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));ServerConfig config=config("manual-limit",1,"continue",1);
        AtomicInteger submitted=new AtomicInteger();List<Job> active=new ArrayList<>();
        try(JobStore store=new JobStore(config);LoadScheduleScheduler scheduler=new LoadScheduleScheduler(config.scheduling,store,recordingSubmitter(config,store,submitted,active),clock)) {
            LoadSchedule schedule=schedule("SMANUAL",clock.instant());schedule.enabled=false;store.saveSchedule(schedule);
            Map<String,Object> first=scheduler.trigger(schedule);Map<String,Object> second=scheduler.trigger(schedule);
            assertEquals("submitted",first.get("status"));assertEquals("skipped",second.get("status"));assertEquals("manual-trigger-rate-limit",second.get("reason"));
            Job prior=active.get(0);prior.status="PASS";store.update(prior);
            clock.advance(Duration.ofMinutes(1));Map<String,Object> third=scheduler.trigger(schedule);
            assertEquals("submitted",third.get("status"));assertEquals(2,submitted.get());
        }
    }

    @Test void scheduleCountIsCappedAndUpdatesRejectStaleDefinitions() throws Exception {
        MutableClock clock=new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));ServerConfig config=config("schedule-cap",1,"continue",1);
        try(JobStore store=new JobStore(config)) {
            LoadSchedule first=schedule("SCAP1",clock.instant());store.createSchedule(first,1);
            assertThrows(IllegalArgumentException.class,()->store.createSchedule(schedule("SCAP2",clock.instant()),1));
            LoadSchedule edited=first.copy();edited.description="Updated";edited.updatedAt=clock.instant().plusSeconds(1).toString();
            LoadSchedule updated=store.updateSchedule(edited,first.updatedAt);assertEquals("Updated",updated.description);
            assertThrows(ServerRuntime.ScheduleConflictException.class,()->store.updateSchedule(first,first.updatedAt));
            assertEquals("Updated",store.schedule(first.id).description);
        }
    }

    @Test void concurrentStoreInstancesClaimOneScheduledOccurrenceAtMostOnce() throws Exception {
        Instant fire=Instant.parse("2026-01-01T00:00:00Z");ServerConfig config=config("concurrent-claim",1,"continue",1);
        try(JobStore first=new JobStore(config);JobStore second=new JobStore(config)) {
            LoadSchedule schedule=schedule("SCLAIM",fire);first.saveSchedule(schedule);LoadSchedule expected=first.schedule(schedule.id);
            ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
            try {
                Future<Boolean> one=pool.submit(()->{ready.countDown();start.await();return first.advanceAndClaim(expected,"SCLAIM-CLAIM",fire.plusSeconds(60).toString());});
                Future<Boolean> two=pool.submit(()->{ready.countDown();start.await();return second.advanceAndClaim(expected,"SCLAIM-CLAIM",fire.plusSeconds(60).toString());});
                assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
                assertEquals(1,(one.get(10,TimeUnit.SECONDS)?1:0)+(two.get(10,TimeUnit.SECONDS)?1:0));
            } finally {pool.shutdownNow();}
            assertEquals(1,first.scheduleExecutions(schedule.id,10).size());
            assertEquals(fire.plusSeconds(60).toString(),second.schedule(schedule.id).nextRunAt);
        }
    }

    @Test void pauseResumeDeleteAndHistoryRetentionPreserveScheduleContract() throws Exception {
        Instant now=Instant.parse("2026-01-01T00:00:00Z");ServerConfig config=config("lifecycle-retention",1,"continue",1,100);
        try(JobStore store=new JobStore(config)) {
            LoadSchedule schedule=schedule("SRETENTION",now);store.createSchedule(schedule,100);
            LoadSchedule paused=store.setScheduleEnabled(schedule.id,false,"admin",now.plusSeconds(1).toString(),null);
            assertFalse(paused.enabled);assertEquals(schedule.nextRunAt,paused.nextRunAt);
            LoadSchedule resumed=store.setScheduleEnabled(schedule.id,true,"admin",now.plusSeconds(2).toString(),now.plusSeconds(60).toString());
            assertTrue(resumed.enabled);assertEquals(now.plusSeconds(60).toString(),resumed.nextRunAt);

            String url="jdbc:h2:file:"+config.dataDir.resolve("db/att-server").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE";
            try(var connection=java.sql.DriverManager.getConnection(url,"sa","");var insert=connection.prepareStatement(
                    "INSERT INTO load_schedule_executions(occurrence_id,schedule_id,scheduled_for,status,reason,trigger_type,created_at) VALUES(?,?,?,'skipped','seed','cron',?)")) {
                for(int i=0;i<101;i++) {
                    insert.setString(1,"SRETENTION-seed-"+i);insert.setString(2,schedule.id);
                    insert.setString(3,Instant.parse("2020-01-01T00:00:00Z").plusSeconds(i*60L).toString());insert.setString(4,now.toString());insert.addBatch();
                }
                insert.executeBatch();
            }
            String newest="SRETENTION-manual-retained";store.insertManualOccurrence(schedule.id,newest,now.plusSeconds(120));
            store.updateOccurrence(newest,"skipped","retention-check",null);
            List<Map<String,Object>> retained=store.scheduleExecutions(schedule.id,200);
            assertEquals(100,retained.size());assertTrue(retained.stream().anyMatch(row->newest.equals(row.get("occurrenceId"))));
            assertFalse(retained.stream().anyMatch(row->"SRETENTION-seed-0".equals(row.get("occurrenceId"))));

            String activeOccurrence="SRETENTION-active-after-delete";store.insertManualOccurrence(schedule.id,activeOccurrence,now.plusSeconds(180));
            Job active=job(config,activeOccurrence,"J0000000000000100");store.insert(active,"{}");
            store.updateOccurrence(activeOccurrence,"submitted",null,active.id);
            assertEquals(101,store.scheduleExecutions(schedule.id,200).size(),"an active linked job may temporarily exceed the terminal history cap");
            store.deleteSchedule(schedule.id);assertNull(store.schedule(schedule.id));
            assertEquals(101,store.scheduleExecutions(schedule.id,200).size(),"administrators can read history by the saved schedule ID after deleting its definition");
            active.status="PASS";store.update(active);
            List<Map<String,Object>> afterCompletion=store.scheduleExecutions(schedule.id,200);
            assertEquals(100,afterCompletion.size(),"finishing a linked job prunes deleted-schedule history back to its configured bound");
            assertTrue(afterCompletion.stream().anyMatch(row->activeOccurrence.equals(row.get("occurrenceId"))));
        }
    }

    private ServerConfig config(String name,int minimumInterval,String failurePolicy,int globalRuns) throws Exception {
        return config(name,minimumInterval,failurePolicy,globalRuns,10000);
    }

    private ServerConfig config(String name,int minimumInterval,String failurePolicy,int globalRuns,int historyLimit) throws Exception {
        Path allowed=Files.createDirectories(temp.resolve(name+"-packages"));Path pkg=Files.createDirectories(allowed.resolve("p"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path file=temp.resolve(name+"-server.yaml");
        Files.writeString(file,"server:\n  dataDir: "+yaml(temp.resolve(name+"-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  inspection:\n    enabled: true\n  scheduling:\n    enabled: true\n    minimumIntervalMinutes: "+minimumInterval
                +"\n    maxConcurrentRuns: "+globalRuns+"\n    failurePolicy: "+failurePolicy
                +"\n    maxExecutionsPerSchedule: "+historyLimit
                +"\nworkers:\n  maxConcurrentLoad: "+globalRuns+"\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
        return ServerConfig.load(file);
    }

    private static LoadSchedule schedule(String id,Instant next) {
        LoadSchedule schedule=new LoadSchedule();schedule.id=id;schedule.name=id;schedule.packageId="p";schedule.scenario="load/scenario.yaml";
        schedule.timezone="UTC";schedule.cron="* * * * *";schedule.enabled=true;schedule.nextRunAt=next.toString();
        schedule.createdAt=next.toString();schedule.updatedAt=next.toString();schedule.createdBy="operator";schedule.updatedBy="operator";
        return schedule;
    }

    private static LoadScheduleScheduler.Submitter recordingSubmitter(ServerConfig config,JobStore store,AtomicInteger submitted,List<Job> active) {
        return (schedule,occurrence)->{
            int sequence=submitted.incrementAndGet();String id=String.format("J%016X",sequence);
            Job job=job(config,occurrence,id,"load-schedule:"+schedule.id);store.insert(job,"{\"command\":\"load\"}");active.add(job);
            return Map.of("jobId",id);
        };
    }

    private static Job job(ServerConfig config,String occurrence,String id) throws Exception {
        return job(config,occurrence,id,"load-schedule:test");
    }
    private static Job job(ServerConfig config,String occurrence,String id,String principal) throws Exception {
        Path directory=Files.createDirectories(config.dataDir.resolve("jobs").resolve(id));
        Job job=new Job(id,"load","p",principal,new WorkerRequest(),new JobEvents(directory.resolve("events.jsonl"),100));
        job.status="RUNNING";job.scheduleOccurrenceId=occurrence;return job;
    }

    private static String yaml(Path value){return "'"+value.toAbsolutePath().toString().replace("'","''")+"'";}

    private static final class MutableClock extends Clock {
        private Instant now;MutableClock(Instant now){this.now=now;}
        @Override public ZoneId getZone(){return ZoneId.of("UTC");}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
        void advance(Duration duration){now=now.plus(duration);}
    }
}
