package att.server;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Single-node, bounded scheduler. It records an occurrence before submitting its typed job. */
final class LoadScheduleScheduler implements AutoCloseable {
    interface Submitter { Map<String,Object> submit(LoadSchedule schedule,String occurrenceId) throws Exception; }
    interface ExecutionSubmitter { Map<String,Object> submit(LoadSchedule schedule,String occurrenceId,String executionPrincipal) throws Exception; }
    private static final Logger LOG=Logger.getLogger(LoadScheduleScheduler.class.getName());
    private final ServerConfig.Scheduling config;
    private final JobStore store;
    private final ExecutionSubmitter submitter;
    private final Clock clock;
    private final Instant startedAt;
    private final ScheduledExecutorService executor;

    LoadScheduleScheduler(ServerConfig.Scheduling config,JobStore store,Submitter submitter,Clock clock) {
        this(config,store,(schedule,occurrenceId,executionPrincipal)->submitter.submit(schedule,occurrenceId),clock);
    }

    LoadScheduleScheduler(ServerConfig.Scheduling config,JobStore store,ExecutionSubmitter submitter,Clock clock) {
        this.config=config;this.store=store;this.submitter=submitter;this.clock=clock;this.startedAt=clock.instant();
        this.executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-scheduler");t.setDaemon(true);return t;});
    }

    void start() {
        executor.scheduleWithFixedDelay(this::tickSafely,1,config.pollIntervalSeconds,TimeUnit.SECONDS);
    }

    private void tickSafely() {
        try { processDue(); }
        catch(Exception failure) { LOG.log(Level.WARNING,"Scheduled Load polling failed",failure); }
    }

    void processDue() throws Exception {
        Instant now=clock.instant();
        for(LoadSchedule original:store.dueSchedules(now)) {
            LoadSchedule schedule=original;
            if(!schedule.enabled||schedule.nextRunAt==null||schedule.nextRunInstant().isAfter(now))continue;
            Instant fire=schedule.nextRunInstant();ZoneId zone=ZoneId.of(schedule.timezone);
            if(schedule.runAt!=null) {
                String occurrenceId=occurrenceId(schedule.id,fire);
                if(!store.advanceAndClaim(schedule,occurrenceId,null))continue;
                long grace=Math.max(30L,config.pollIntervalSeconds*3L);
                if(fire.isBefore(startedAt)||Duration.between(fire,now).getSeconds()>grace)
                    store.updateOccurrence(occurrenceId,"skipped","misfire-no-backfill",null);
                else submitOrSkip(schedule,occurrenceId,automaticExecutionPrincipal(schedule));
                continue;
            }
            CronExpression cron=CronExpression.parse(schedule.cron,config.minimumIntervalMinutes);
            long grace=Math.max(30L,config.pollIntervalSeconds*3L);
            if(fire.isBefore(startedAt)||Duration.between(fire,now).getSeconds()>grace) {
                skipOverdue(schedule,fire,now,cron,zone);
                continue;
            }
            Instant next=cron.nextAfter(fire,zone);String occurrenceId=occurrenceId(schedule.id,fire);
            if(!store.advanceAndClaim(schedule,occurrenceId,next.toString()))continue;
            schedule.nextRunAt=next.toString();
            submitOrSkip(schedule,occurrenceId,automaticExecutionPrincipal(schedule));

            // One polling tick submits at most one occurrence per schedule. If a delayed poll
            // leaves more firings due, move directly to the next future instant and retain a
            // single audit/history row for the coalesced misses.
            LoadSchedule latest=store.schedule(schedule.id);
            if(latest!=null&&latest.enabled&&latest.nextRunAt!=null&&latest.nextRunInstant().compareTo(now)<=0) {
                Instant firstMissed=latest.nextRunInstant();
                skipOverdue(latest,firstMissed,now,CronExpression.parse(latest.cron,config.minimumIntervalMinutes),ZoneId.of(latest.timezone));
            }
        }
    }

    private void skipOverdue(LoadSchedule schedule,Instant firstFire,Instant now,CronExpression cron,ZoneId zone)throws Exception {
        Instant next=cron.nextAfter(now,zone);
        String occurrenceId=occurrenceId(schedule.id,firstFire);
        String reason="misfire-no-backfill; coalesced-through="+now;
        if(store.advanceAndRecordMisfire(schedule,occurrenceId,firstFire,next.toString(),reason)) {
            schedule.nextRunAt=next.toString();
            store.updateOccurrence(occurrenceId,"skipped",reason,null);
        }
    }

    synchronized Map<String,Object> trigger(LoadSchedule schedule,String executionPrincipal) throws Exception {
        return trigger(schedule,executionPrincipal,null);
    }

    synchronized Map<String,Object> trigger(LoadSchedule schedule,String executionPrincipal,String triggeredBy) throws Exception {
        Instant now=clock.instant();String occurrence= schedule.id+"-manual-"+UUID.randomUUID().toString().replace("-","");
        Instant previous=store.lastManualTrigger(schedule.id);
        if(!store.claimManualOccurrence(schedule,occurrence,now,triggeredBy))throw new ServerRuntime.ScheduleConflictException();
        if(previous!=null&&Duration.between(previous,now).compareTo(Duration.ofMinutes(config.minimumIntervalMinutes))<0) {
            store.updateOccurrence(occurrence,"skipped","manual-trigger-rate-limit",null);
            return store.scheduleExecution(occurrence);
        }
        submitOrSkip(schedule,occurrence,executionPrincipal);
        Map<String,Object> result=store.scheduleExecution(occurrence);return result==null?Map.of("occurrenceId",occurrence):result;
    }

    synchronized Map<String,Object> trigger(LoadSchedule schedule) throws Exception {
        return trigger(schedule,automaticExecutionPrincipal(schedule));
    }

    private synchronized void submitOrSkip(LoadSchedule schedule,String occurrenceId,String executionPrincipal) throws Exception {
        if(store.activeScheduledCount(schedule.id)>=Math.max(1,schedule.maxConcurrentRuns)) {
            store.updateOccurrence(occurrenceId,"skipped","schedule-overlap-limit",null);return;
        }
        if(store.activeScheduledCount(null)>=config.maxConcurrentRuns) {
            // Preserve the established occurrence reason code for existing API consumers.
            store.updateOccurrence(occurrenceId,"skipped","global-scheduled-load-capacity",null);return;
        }
        try {
            Map<String,Object> job=submitter.submit(schedule,occurrenceId,executionPrincipal);
            store.updateOccurrence(occurrenceId,"submitted",null,String.valueOf(job.get("jobId")));
        } catch(ServerRuntime.ScheduleSourceChangedException changed) {
            store.updateOccurrence(occurrenceId,"failed-to-submit","package-revision-changed",null);
            store.recordScheduleSourceChange(schedule,changed.currentRevision,"pause".equals(changed.policy),clock.instant().toString());
        } catch(Exception failure) {
            String reason=ServerRuntime.safeMessage(failure);
            store.updateOccurrence(occurrenceId,"failed-to-submit",reason.length()>240?reason.substring(0,240):reason,null);
            LOG.info("Scheduled job submission failed for occurrence "+occurrenceId+": "+reason);
            if("pauseAfterSubmitFailure".equals(config.failurePolicy))try {
                LoadSchedule paused=null;
                synchronized(store) {
                    LoadSchedule latest=store.schedule(schedule.id);
                    if(latest!=null&&latest.enabled)paused=store.setScheduleEnabled(latest.id,false,"scheduler",clock.instant().toString(),null);
                }
                if(paused!=null)store.audit("system:load-scheduler","SCHEDULE_AUTO_PAUSE",null,paused.packageId,"SUBMISSION_FAILURE");
            } catch(Exception pauseFailure) { LOG.log(Level.WARNING,"Unable to pause a schedule after its failed submission",pauseFailure); }
        }
    }

    private static String automaticExecutionPrincipal(LoadSchedule schedule) {
        return schedule.executionPrincipal==null||schedule.executionPrincipal.isBlank()?schedule.createdBy:schedule.executionPrincipal;
    }

    private static String occurrenceId(String scheduleId,Instant fire) { return scheduleId+"-"+fire.toEpochMilli(); }
    @Override public void close(){
        executor.shutdown();
        try {
            long waitSeconds=Math.max(1,Math.min(30,config.pollIntervalSeconds));
            if(!executor.awaitTermination(waitSeconds,TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if(!executor.awaitTermination(1,TimeUnit.SECONDS))LOG.warning("Scheduled Load polling did not stop before the shutdown wait elapsed");
            }
        } catch(InterruptedException interrupted) {executor.shutdownNow();Thread.currentThread().interrupt();}
    }
}
