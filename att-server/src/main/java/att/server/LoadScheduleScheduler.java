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

/** Single-node, bounded scheduler. It records an occurrence before submitting its normal Load job. */
final class LoadScheduleScheduler implements AutoCloseable {
    interface Submitter { Map<String,Object> submit(LoadSchedule schedule,String occurrenceId) throws Exception; }
    private static final Logger LOG=Logger.getLogger(LoadScheduleScheduler.class.getName());
    private final ServerConfig.Scheduling config;
    private final JobStore store;
    private final Submitter submitter;
    private final Clock clock;
    private final Instant startedAt;
    private final ScheduledExecutorService executor;

    LoadScheduleScheduler(ServerConfig.Scheduling config,JobStore store,Submitter submitter,Clock clock) {
        this.config=config;this.store=store;this.submitter=submitter;this.clock=clock;this.startedAt=clock.instant();
        this.executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-load-scheduler");t.setDaemon(true);return t;});
    }

    void start() {
        executor.scheduleWithFixedDelay(this::tickSafely,1,config.pollIntervalSeconds,TimeUnit.SECONDS);
    }

    private void tickSafely() {
        try { processDue(); }
        catch(Exception failure) { LOG.log(Level.WARNING,"Scheduled Load polling failed",failure); }
    }

    private void processDue() throws Exception {
        Instant now=clock.instant();
        for(LoadSchedule original:store.dueSchedules(now)) {
            LoadSchedule schedule=original;
            int recorded=0;
            while(schedule.enabled&&schedule.nextRunAt!=null&&schedule.nextRunInstant().compareTo(now)<=0&&recorded<10000) {
                Instant fire=schedule.nextRunInstant();ZoneId zone=ZoneId.of(schedule.timezone);
                Instant next=CronExpression.parse(schedule.cron,config.minimumIntervalMinutes).nextAfter(fire,zone);
                String occurrenceId=occurrenceId(schedule.id,fire);
                boolean claimed=store.advanceAndClaim(schedule,occurrenceId,next.toString());
                if(!claimed)break;
                schedule.nextRunAt=next.toString();recorded++;
                long grace=Math.max(30L,config.pollIntervalSeconds*3L);
                if(fire.isBefore(startedAt)||Duration.between(fire,now).getSeconds()>grace) {
                    store.updateOccurrence(occurrenceId,"skipped","misfire-no-backfill",null);
                    continue;
                }
                submitOrSkip(schedule,occurrenceId);
                LoadSchedule latest=store.schedule(schedule.id);
                if(latest==null||!latest.enabled)break;
                schedule=latest;
            }
            if(recorded>=10000)LOG.warning("Scheduled Load misfire history catch-up reached its per-schedule safety limit");
        }
    }

    synchronized Map<String,Object> trigger(LoadSchedule schedule) throws Exception {
        Instant now=clock.instant();String occurrence= schedule.id+"-manual-"+UUID.randomUUID().toString().replace("-","");
        store.insertManualOccurrence(schedule.id,occurrence,now);
        submitOrSkip(schedule,occurrence);
        Map<String,Object> result=store.scheduleExecution(occurrence);return result==null?Map.of("occurrenceId",occurrence):result;
    }

    private synchronized void submitOrSkip(LoadSchedule schedule,String occurrenceId) throws Exception {
        if(store.activeScheduledCount(schedule.id)>0) {
            store.updateOccurrence(occurrenceId,"skipped","schedule-overlap-limit",null);return;
        }
        if(store.activeScheduledCount(null)>=config.maxConcurrentRuns) {
            store.updateOccurrence(occurrenceId,"skipped","global-scheduled-load-capacity",null);return;
        }
        try {
            Map<String,Object> job=submitter.submit(schedule,occurrenceId);
            store.updateOccurrence(occurrenceId,"submitted",null,String.valueOf(job.get("jobId")));
        } catch(Exception failure) {
            String reason=ServerRuntime.safeMessage(failure);
            store.updateOccurrence(occurrenceId,"failed-to-submit",reason.length()>240?reason.substring(0,240):reason,null);
            LOG.info("Scheduled Load submission failed for occurrence "+occurrenceId+": "+reason);
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

    private static String occurrenceId(String scheduleId,Instant fire) { return scheduleId+"-"+fire.toEpochMilli(); }
    @Override public void close(){executor.shutdownNow();}
}
