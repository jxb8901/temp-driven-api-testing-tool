package att.server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** H2-backed bounded control-plane metadata store. Evidence remains on disk under each job directory. */
final class JobStore implements AutoCloseable {
    static final Duration REPLAY_INTENT_RETENTION=Duration.ofDays(30);
    private static final Object DRIVER_LIFECYCLE=new Object();
    private static int openStores;
    private volatile boolean closed;
    /** Keeps the embedded database open until the owning runtime closes. Query connections remain short-lived. */
    private Connection databaseAnchor;
    private final String url;
    private final int scheduleHistoryLimit;
    private static final String LOAD_SCHEDULE_PREFIX = "load-schedule:";
    private static final String DEBUG_SCHEDULE_PREFIX = "debug-schedule:";

    /** Returns the schedule id for a scheduled-job principal, or null for direct/other principals. */
    private static String scheduledScheduleId(String principal) {
        if (principal == null) return null;
        if (principal.startsWith(LOAD_SCHEDULE_PREFIX) && principal.length() > LOAD_SCHEDULE_PREFIX.length())
            return principal.substring(LOAD_SCHEDULE_PREFIX.length());
        if (principal.startsWith(DEBUG_SCHEDULE_PREFIX) && principal.length() > DEBUG_SCHEDULE_PREFIX.length())
            return principal.substring(DEBUG_SCHEDULE_PREFIX.length());
        return null;
    }

    boolean isActiveUser(String login) throws Exception {
        Map<String,Object> user = login == null ? null : authUser(login);
        return user != null && Boolean.TRUE.equals(user.get("active")) && "USER".equals(user.get("role"));
    }

    private boolean isLocalAdmin(String login) throws Exception {
        Map<String,Object> user = login == null ? null : authUser(login);
        return user != null && Boolean.TRUE.equals(user.get("active")) && "ADMIN".equals(user.get("role"));
    }

    JobStore(ServerConfig config) throws Exception {
        scheduleHistoryLimit=config.scheduling.maxExecutionsPerSchedule;
        url="jdbc:h2:file:"+config.dataDir.resolve("db/att-server").toString().replace('\\','/')+";DB_CLOSE_ON_EXIT=FALSE;AUTO_SERVER=FALSE;WRITE_DELAY=0";
        java.nio.file.Files.createDirectories(config.dataDir.resolve("db"));
        synchronized(DRIVER_LIFECYCLE){
            java.sql.Driver driver=org.h2.Driver.load();
            boolean registered=false;java.util.Enumeration<java.sql.Driver> drivers=DriverManager.getDrivers();
            while(drivers.hasMoreElements())if(drivers.nextElement()==driver)registered=true;
            if(!registered)DriverManager.registerDriver(driver);
            openStores++;
        }
        try(Connection c=connect(); Statement s=c.createStatement()) {
            databaseAnchor=connect();
            s.execute("CREATE TABLE IF NOT EXISTS jobs (job_id VARCHAR(80) PRIMARY KEY, command VARCHAR(16) NOT NULL, package_id VARCHAR(64) NOT NULL, principal VARCHAR(256) NOT NULL, status VARCHAR(24) NOT NULL, created_at VARCHAR(40) NOT NULL, started_at VARCHAR(40), finished_at VARCHAR(40), worker_pid BIGINT, worker_start_time VARCHAR(40), att_version VARCHAR(32) NOT NULL, exit_code INT, result_json CLOB, diagnostic_json CLOB, performance_json CLOB, request_summary CLOB NOT NULL)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS worker_start_time VARCHAR(40)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS att_version VARCHAR(32) DEFAULT 'unknown' NOT NULL");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS performance_json CLOB");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS archive_metrics_json CLOB");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS worker_quarantined BOOLEAN DEFAULT FALSE NOT NULL");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS artifact_recovery BOOLEAN DEFAULT TRUE NOT NULL");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS retention_state VARCHAR(16)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS retention_bytes BIGINT");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS pinned BOOLEAN DEFAULT FALSE NOT NULL");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS pinned_at VARCHAR(40)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS pinned_by VARCHAR(256)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS pin_reason VARCHAR(200)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS retention_actor VARCHAR(256)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS delete_requested_at BIGINT");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS target_identity CLOB");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS target_sort VARCHAR(600) DEFAULT 'Unknown target' NOT NULL");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS target_group VARCHAR(64) DEFAULT 'legacy' NOT NULL");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS environment VARCHAR(128) DEFAULT '' NOT NULL");
            s.execute("CREATE INDEX IF NOT EXISTS jobs_target_page_idx ON jobs(target_sort,target_group,created_at,job_id)");
            s.execute("CREATE INDEX IF NOT EXISTS jobs_created_page_idx ON jobs(created_at,job_id)");
            s.execute("CREATE INDEX IF NOT EXISTS jobs_delete_requested_idx ON jobs(retention_state,delete_requested_at,job_id)");
            s.execute("CREATE INDEX IF NOT EXISTS jobs_artifact_recovery_idx ON jobs(artifact_recovery,job_id)");
            s.execute("CREATE TABLE IF NOT EXISTS job_worker_processes (job_id VARCHAR(80) NOT NULL, process_pid BIGINT NOT NULL, process_start_time VARCHAR(40), PRIMARY KEY(job_id,process_pid), FOREIGN KEY(job_id) REFERENCES jobs(job_id) ON DELETE CASCADE)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS schedule_occurrence_id VARCHAR(160)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS jobs_schedule_occurrence_uq ON jobs(schedule_occurrence_id)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS execution_spec CLOB");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS replay_owner VARCHAR(256)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS originating_schedule_id VARCHAR(40)");
            s.execute("CREATE TABLE IF NOT EXISTS job_replay_intents (principal VARCHAR(256) NOT NULL, intent_id VARCHAR(80) NOT NULL, source_job_id VARCHAR(80) NOT NULL, job_id VARCHAR(80) NOT NULL, expires_at BIGINT, PRIMARY KEY(principal,intent_id))");
            s.execute("ALTER TABLE job_replay_intents ADD COLUMN IF NOT EXISTS expires_at BIGINT");
            s.execute("UPDATE job_replay_intents SET expires_at="+(System.currentTimeMillis()+REPLAY_INTENT_RETENTION.toMillis())+" WHERE expires_at IS NULL");
            s.execute("CREATE INDEX IF NOT EXISTS job_replay_intents_expiry_idx ON job_replay_intents(expires_at)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS replay_unavailable VARCHAR(240)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS rerun_of_job_id VARCHAR(80)");
            s.execute("ALTER TABLE jobs ADD COLUMN IF NOT EXISTS replay_intent VARCHAR(80)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS jobs_replay_intent_uq ON jobs(principal,replay_intent)");
            s.execute("CREATE TABLE IF NOT EXISTS audit (audit_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, principal VARCHAR(256) NOT NULL, action VARCHAR(32) NOT NULL, job_id VARCHAR(80), package_id VARCHAR(64), created_at VARCHAR(40) NOT NULL, outcome VARCHAR(32) NOT NULL)");
            s.execute("ALTER TABLE audit ALTER COLUMN outcome VARCHAR(2048)");
            s.execute("ALTER TABLE audit ADD COLUMN IF NOT EXISTS detail VARCHAR(200)");
            s.execute("CREATE TABLE IF NOT EXISTS load_schedules (schedule_id VARCHAR(40) PRIMARY KEY, schedule_json CLOB NOT NULL, enabled BOOLEAN NOT NULL, next_run_at VARCHAR(40), updated_at VARCHAR(40) NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS load_schedules_due_idx ON load_schedules(enabled,next_run_at)");
            s.execute("CREATE TABLE IF NOT EXISTS load_schedule_executions (occurrence_id VARCHAR(160) PRIMARY KEY, schedule_id VARCHAR(40) NOT NULL, scheduled_for VARCHAR(40) NOT NULL, status VARCHAR(32) NOT NULL, reason VARCHAR(240), job_id VARCHAR(80), trigger_type VARCHAR(16) NOT NULL, created_at VARCHAR(40) NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS auth_users (login_id VARCHAR(120) PRIMARY KEY, display_name VARCHAR(120) NOT NULL, password_hash VARCHAR(512) NOT NULL, role VARCHAR(16) NOT NULL, active BOOLEAN NOT NULL, updated_at VARCHAR(40) NOT NULL)");
            s.execute("ALTER TABLE auth_users ADD COLUMN IF NOT EXISTS csrf_token VARCHAR(64)");
            s.execute("CREATE TABLE IF NOT EXISTS auth_sessions (token_hash VARCHAR(64) PRIMARY KEY, login_id VARCHAR(120) NOT NULL, csrf_token VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL, last_seen BIGINT NOT NULL, absolute_expiry BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS auth_bootstrap_claim (claim_id INT PRIMARY KEY, claimed_at VARCHAR(40) NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS load_schedule_execution_history_idx ON load_schedule_executions(schedule_id,scheduled_for)");
        } catch(Exception failed){close();throw failed;}
    }
    synchronized void insert(Job job,String summary) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement p=c.prepareStatement("INSERT INTO jobs(job_id,command,package_id,principal,status,created_at,att_version,request_summary,schedule_occurrence_id,execution_spec,replay_unavailable,rerun_of_job_id,replay_intent,replay_owner,originating_schedule_id,target_identity,target_sort,target_group,environment) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    p.setString(1,job.id);p.setString(2,job.command);p.setString(3,job.packageId);p.setString(4,job.principal);p.setString(5,job.status);p.setString(6,job.createdAt.toString());p.setString(7,ServerBuildInfo.PRODUCT);p.setString(8,summary);p.setString(9,job.scheduleOccurrenceId);p.setString(10,job.executionSpec);p.setString(11,job.replayUnavailable);p.setString(12,job.rerunOfJobId);p.setString(13,job.replayIntent);p.setString(14,job.replayOwner);p.setString(15,job.originatingScheduleId);
                    var metadata=ServerRuntime.JSON.readTree(summary);var identity=metadata.path("targetIdentity");
                    p.setString(16,identity.isObject()?identity.toString():null);
                    p.setString(17,identity.hasNonNull("type")?identity.path("type").asText()+" · "+identity.path("id").asText():"Unknown target");
                    p.setString(18,identity.path("signature").asText("legacy"));p.setString(19,metadata.path("environment").asText(""));p.executeUpdate();
                }
                if(job.replayIntent!=null)try(PreparedStatement p=c.prepareStatement("INSERT INTO job_replay_intents(principal,intent_id,source_job_id,job_id,expires_at) VALUES(?,?,?,?,?)")) {
                    p.setString(1,job.principal);p.setString(2,job.replayIntent);p.setString(3,job.rerunOfJobId);p.setString(4,job.id);
                    p.setLong(5,job.createdAt.plus(REPLAY_INTENT_RETENTION).toEpochMilli());p.executeUpdate();
                }
                c.commit();
            } catch(Exception failure){c.rollback();throw failure;}
        }
    }

    /** Private execution metadata is never part of job list/result serialization. */
    synchronized Map<String,String> executionSpec(String id) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT execution_spec,replay_unavailable,rerun_of_job_id,schedule_occurrence_id,COALESCE(replay_owner,principal),originating_schedule_id FROM jobs WHERE job_id=? AND (retention_state IS NULL OR retention_state<>'DELETING')")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()) {
                if(!r.next())return null;
                Map<String,String> value=new LinkedHashMap<>();value.put("spec",r.getString(1));value.put("reason",r.getString(2));
                value.put("rerunOfJobId",r.getString(3));value.put("occurrenceId",r.getString(4));value.put("principal",r.getString(5));value.put("originatingScheduleId",r.getString(6));return value;
            }
        }
    }
    synchronized String replayJob(String principal,String intent,String source) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT job_id FROM job_replay_intents WHERE principal=? AND intent_id=? AND source_job_id=? AND expires_at>?")) {
            p.setString(1,principal);p.setString(2,intent);p.setString(3,source);p.setLong(4,System.currentTimeMillis());
            try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):null;}
        }
    }
    synchronized int expireReplayIntents(long nowMillis) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("DELETE FROM job_replay_intents WHERE expires_at<=?")) {
            p.setLong(1,nowMillis);return p.executeUpdate();
        }
    }

    synchronized int scheduleCount() throws Exception {try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM load_schedules")){r.next();return r.getInt(1);}}
    synchronized LoadSchedule schedule(String id) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT schedule_json FROM load_schedules WHERE schedule_id=?")){
            p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?ServerRuntime.JSON.readValue(r.getString(1),LoadSchedule.class):null;}
        }
    }
    synchronized List<LoadSchedule> schedules() throws Exception {
        List<LoadSchedule> out=new ArrayList<>();
        try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT schedule_json FROM load_schedules ORDER BY updated_at DESC")){
            while(r.next())out.add(ServerRuntime.JSON.readValue(r.getString(1),LoadSchedule.class));
        }return out;
    }
    synchronized List<LoadSchedule> dueSchedules(Instant now) throws Exception {
        List<LoadSchedule> out=new ArrayList<>();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT schedule_json FROM load_schedules WHERE enabled=TRUE AND next_run_at IS NOT NULL AND next_run_at<=? ORDER BY next_run_at")){
            p.setString(1,now.toString());try(ResultSet r=p.executeQuery()){while(r.next())out.add(ServerRuntime.JSON.readValue(r.getString(1),LoadSchedule.class));}
        }return out;
    }
    synchronized void saveSchedule(LoadSchedule schedule) throws Exception {
        String json=ServerRuntime.JSON.writeValueAsString(schedule);
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("MERGE INTO load_schedules(schedule_id,schedule_json,enabled,next_run_at,updated_at) KEY(schedule_id) VALUES(?,?,?,?,?)")){
            p.setString(1,schedule.id);p.setString(2,json);p.setBoolean(3,schedule.enabled);p.setString(4,schedule.nextRunAt);p.setString(5,schedule.updatedAt);p.executeUpdate();
        }
    }
    synchronized void createSchedule(LoadSchedule schedule,int maximum) throws Exception {
        if(scheduleCount()>=maximum)throw new IllegalArgumentException("The configured maximum number of Load schedules has been reached");
        saveSchedule(schedule);
    }
    synchronized LoadSchedule updateSchedule(LoadSchedule schedule,String expectedUpdatedAt) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("SELECT schedule_json FROM load_schedules WHERE schedule_id=? FOR UPDATE")) {
                p.setString(1,schedule.id);
                try(ResultSet r=p.executeQuery()) {
                    if(!r.next()){c.rollback();return null;}
                    LoadSchedule current=ServerRuntime.JSON.readValue(r.getString(1),LoadSchedule.class);
                    if(!java.util.Objects.equals(current.updatedAt,expectedUpdatedAt)) {
                        c.rollback();throw new ServerRuntime.ScheduleConflictException();
                    }
                    // The scheduler advances nextRunAt without changing the schedule definition timestamp.
                    // Preserve that newer value when this edit leaves recurrence and enabled state unchanged.
                    if(java.util.Objects.equals(current.cron,schedule.cron)
                            &&LoadSchedule.sameInstant(current.runAt,schedule.runAt)
                            &&java.util.Objects.equals(current.timezone,schedule.timezone)
                            &&current.enabled==schedule.enabled) schedule.nextRunAt=current.nextRunAt;
                    try(PreparedStatement update=c.prepareStatement("UPDATE load_schedules SET schedule_json=?,enabled=?,next_run_at=?,updated_at=? WHERE schedule_id=?")) {
                        update.setString(1,ServerRuntime.JSON.writeValueAsString(schedule));update.setBoolean(2,schedule.enabled);
                        update.setString(3,schedule.nextRunAt);update.setString(4,schedule.updatedAt);update.setString(5,schedule.id);update.executeUpdate();
                    }
                    c.commit();return schedule;
                }
            }
        }
    }

    /**
     * Resolves and persists the identity used by a manual trigger. New schedules already have
     * an executionPrincipal. For older schedules, an active USER owner is adopted as-is; an
     * active ADMIN owner may be explicitly transferred to the active USER who presses Run Now.
     * Missing or disabled owners stay fail-closed.
     */
    synchronized LoadSchedule authorizeManualScheduleExecution(String id,String principal,boolean localAuth,String now) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("SELECT schedule_json FROM load_schedules WHERE schedule_id=? FOR UPDATE")) {
                p.setString(1,id);
                try(ResultSet r=p.executeQuery()) {
                    if(!r.next()){c.rollback();return null;}
                    LoadSchedule schedule=ServerRuntime.JSON.readValue(r.getString(1),LoadSchedule.class);
                    boolean migrated=false,changed=false;
                    if(localAuth) {
                        if(!activeUser(c,principal))throw new IllegalArgumentException("Triggering user is no longer authorized");
                        if(schedule.executionIdentityMigratedFrom!=null) {
                            c.commit();return schedule;
                        } else if(activeUser(c,schedule.createdBy)) {
                            if(schedule.executionPrincipal==null||schedule.executionPrincipal.isBlank()) {
                                schedule.executionPrincipal=schedule.createdBy;changed=true;
                            }
                        } else if(activeAdmin(c,schedule.createdBy)) {
                            schedule.executionPrincipal=principal;
                            schedule.executionIdentityMigratedFrom=schedule.createdBy;
                            schedule.executionIdentityMigratedBy=principal;
                            schedule.executionIdentityMigratedAt=now;
                            migrated=true;changed=true;
                        } else {
                            // Preserve the occurrence history: submitScheduledJob records the
                            // authorization failure as failed-to-submit before any Worker starts.
                            c.commit();return schedule;
                        }
                    } else if(schedule.executionPrincipal==null||schedule.executionPrincipal.isBlank()) {
                        schedule.executionPrincipal=principal;changed=true;
                    }
                    if(!changed){c.commit();return schedule;}
                    schedule.updatedBy=principal;schedule.updatedAt=now;
                    String json=ServerRuntime.JSON.writeValueAsString(schedule);
                    try(PreparedStatement update=c.prepareStatement("UPDATE load_schedules SET schedule_json=?,enabled=?,next_run_at=?,updated_at=? WHERE schedule_id=?")) {
                        update.setString(1,json);update.setBoolean(2,schedule.enabled);update.setString(3,schedule.nextRunAt);
                        update.setString(4,schedule.updatedAt);update.setString(5,schedule.id);update.executeUpdate();
                    }
                    if(migrated)try(PreparedStatement audit=c.prepareStatement("INSERT INTO audit(principal,action,job_id,package_id,created_at,outcome) VALUES(?,? ,NULL,?,?,?)")) {
                        audit.setString(1,principal);audit.setString(2,"SCHEDULE_IDENTITY_MIGRATE");audit.setString(3,schedule.packageId);
                        audit.setString(4,now);audit.setString(5,"LEGACY_ADMIN_TO_USER");audit.executeUpdate();
                    }
                    c.commit();return schedule;
                }
            } catch(Exception failure) {c.rollback();throw failure;}
        }
    }

    private static boolean activeUser(Connection c,String login) throws Exception {return activeRole(c,login,"USER");}
    private static boolean activeAdmin(Connection c,String login) throws Exception {return activeRole(c,login,"ADMIN");}
    private static boolean activeRole(Connection c,String login,String role) throws Exception {
        if(login==null||login.isBlank())return false;
        try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM auth_users WHERE login_id=? AND role=? AND active=TRUE")) {
            p.setString(1,login);p.setString(2,role);try(ResultSet r=p.executeQuery()){return r.next();}
        }
    }
    synchronized LoadSchedule setScheduleEnabled(String id,boolean enabled,String principal,String updatedAt,String nextRunAt) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("SELECT schedule_json,next_run_at FROM load_schedules WHERE schedule_id=? FOR UPDATE")){
                p.setString(1,id);try(ResultSet r=p.executeQuery()){
                    if(!r.next()){c.rollback();return null;}
                    LoadSchedule schedule=ServerRuntime.JSON.readValue(r.getString(1),LoadSchedule.class);
                    schedule.enabled=enabled;schedule.updatedBy=principal;schedule.updatedAt=updatedAt;
                    if(enabled)schedule.nextRunAt=nextRunAt;else schedule.nextRunAt=r.getString(2);
                    String json=ServerRuntime.JSON.writeValueAsString(schedule);
                    try(PreparedStatement update=c.prepareStatement("UPDATE load_schedules SET schedule_json=?,enabled=?,next_run_at=?,updated_at=? WHERE schedule_id=?")){
                        update.setString(1,json);update.setBoolean(2,enabled);update.setString(3,schedule.nextRunAt);update.setString(4,schedule.updatedAt);update.setString(5,id);update.executeUpdate();
                    }
                    c.commit();return schedule;
                }
            }
        }
    }
    synchronized void recordScheduleSourceChange(LoadSchedule expected,String currentRevision,boolean pause,String at) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try(PreparedStatement select=c.prepareStatement("SELECT schedule_json FROM load_schedules WHERE schedule_id=? FOR UPDATE")) {
                select.setString(1,expected.id);
                try(ResultSet rows=select.executeQuery()) {
                    if(!rows.next()){c.rollback();return;}
                    LoadSchedule current=ServerRuntime.JSON.readValue(rows.getString(1),LoadSchedule.class);
                    if(!java.util.Objects.equals(current.updatedAt,expected.updatedAt)||!java.util.Objects.equals(current.expectedRevisionDigest,expected.expectedRevisionDigest)){c.rollback();return;}
                    boolean newlyObserved=!"package-revision-changed".equals(current.sourceStatus)||!java.util.Objects.equals(current.currentRevisionDigest,currentRevision);
                    boolean autoPause=pause&&current.enabled;
                    current.sourceStatus="package-revision-changed";current.currentRevisionDigest=currentRevision;
                    if(autoPause){current.enabled=false;current.autoPauseReason="package-revision-changed";current.autoPausedAt=at;current.updatedAt=at;current.updatedBy="system:load-scheduler";}
                    try(PreparedStatement update=c.prepareStatement("UPDATE load_schedules SET schedule_json=?,enabled=?,updated_at=? WHERE schedule_id=?")) {
                        update.setString(1,ServerRuntime.JSON.writeValueAsString(current));update.setBoolean(2,current.enabled);update.setString(3,current.updatedAt);update.setString(4,current.id);update.executeUpdate();
                    }
                    if(newlyObserved)try(PreparedStatement audit=c.prepareStatement("INSERT INTO audit(principal,action,package_id,created_at,outcome) VALUES('system:load-scheduler','SCHEDULE_SOURCE_CHANGED',?,?,?)")) {
                        audit.setString(1,current.packageId);audit.setString(2,at);audit.setString(3,"schedule="+current.id+";stored="+current.expectedRevisionDigest+";current="+currentRevision);audit.executeUpdate();
                    }
                    if(autoPause)try(PreparedStatement audit=c.prepareStatement("INSERT INTO audit(principal,action,package_id,created_at,outcome) VALUES('system:load-scheduler','SCHEDULE_AUTO_PAUSE',?,?,?)")) {
                        audit.setString(1,current.packageId);audit.setString(2,at);audit.setString(3,"package-revision-changed;schedule="+current.id);audit.executeUpdate();
                    }
                    c.commit();
                }
            }
        }
    }
    synchronized void recordScheduleSourceRestored(LoadSchedule expected,String currentRevision,String at) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try(PreparedStatement select=c.prepareStatement("SELECT schedule_json FROM load_schedules WHERE schedule_id=? FOR UPDATE")) {
                select.setString(1,expected.id);
                try(ResultSet rows=select.executeQuery()) {
                    if(!rows.next()){c.rollback();return;}
                    LoadSchedule current=ServerRuntime.JSON.readValue(rows.getString(1),LoadSchedule.class);
                    if(!java.util.Objects.equals(current.updatedAt,expected.updatedAt)||!java.util.Objects.equals(current.expectedRevisionDigest,expected.expectedRevisionDigest)){c.rollback();return;}
                    boolean restored="package-revision-changed".equals(current.sourceStatus)
                            ||!java.util.Objects.equals(current.currentRevisionDigest,currentRevision);
                    if(!restored){c.rollback();return;}
                    current.sourceStatus=null;current.currentRevisionDigest=currentRevision;
                    try(PreparedStatement update=c.prepareStatement("UPDATE load_schedules SET schedule_json=? WHERE schedule_id=?")) {
                        update.setString(1,ServerRuntime.JSON.writeValueAsString(current));update.setString(2,current.id);update.executeUpdate();
                    }
                    try(PreparedStatement audit=c.prepareStatement("INSERT INTO audit(principal,action,package_id,created_at,outcome) VALUES('system:load-scheduler','SCHEDULE_SOURCE_RESTORED',?,?,?)")) {
                        audit.setString(1,current.packageId);audit.setString(2,at);audit.setString(3,"schedule="+current.id+";revision="+currentRevision);audit.executeUpdate();
                    }
                    c.commit();
                }
            } catch(Exception failure){c.rollback();throw failure;}
        }
    }
    synchronized boolean advanceAndClaim(LoadSchedule expected,String occurrenceId,String nextFire) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("SELECT schedule_json,next_run_at,enabled FROM load_schedules WHERE schedule_id=? FOR UPDATE")){
                p.setString(1,expected.id);try(ResultSet r=p.executeQuery()) {
                    if(!r.next()||!r.getBoolean(3)||!expected.nextRunAt.equals(r.getString(2))){c.rollback();return false;}
                    LoadSchedule schedule=ServerRuntime.JSON.readValue(r.getString(1),LoadSchedule.class);schedule.nextRunAt=nextFire;
                    if(!java.util.Objects.equals(schedule.cron,expected.cron)||!java.util.Objects.equals(schedule.timezone,expected.timezone)
                            ||!java.util.Objects.equals(schedule.updatedAt,expected.updatedAt)){c.rollback();return false;}
                    try(PreparedStatement update=c.prepareStatement("UPDATE load_schedules SET schedule_json=?,next_run_at=?,updated_at=? WHERE schedule_id=?")){
                        update.setString(1,ServerRuntime.JSON.writeValueAsString(schedule));update.setString(2,nextFire);update.setString(3,schedule.updatedAt);update.setString(4,expected.id);update.executeUpdate();
                    }
                    try(PreparedStatement occurrence=c.prepareStatement("INSERT INTO load_schedule_executions(occurrence_id,schedule_id,scheduled_for,status,trigger_type,created_at) VALUES(?,?,?,'scheduled',?,?)")){
                        occurrence.setString(1,occurrenceId);occurrence.setString(2,expected.id);occurrence.setString(3,expected.nextRunAt);occurrence.setString(4,expected.runAt==null?"cron":"once");occurrence.setString(5,Instant.now().toString());occurrence.executeUpdate();
                    }
                }
            }
            c.commit();return true;
        }
    }
    synchronized boolean advanceAndRecordMisfire(LoadSchedule expected,String occurrenceId,Instant firstMissed,String nextFire,String reason) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("SELECT schedule_json,next_run_at,enabled FROM load_schedules WHERE schedule_id=? FOR UPDATE")) {
                p.setString(1,expected.id);try(ResultSet r=p.executeQuery()) {
                    if(!r.next()||!r.getBoolean(3)||!expected.nextRunAt.equals(r.getString(2))){c.rollback();return false;}
                    LoadSchedule schedule=ServerRuntime.JSON.readValue(r.getString(1),LoadSchedule.class);
                    if(!java.util.Objects.equals(schedule.cron,expected.cron)||!java.util.Objects.equals(schedule.timezone,expected.timezone)
                            ||!java.util.Objects.equals(schedule.updatedAt,expected.updatedAt)){c.rollback();return false;}
                    schedule.nextRunAt=nextFire;
                    try(PreparedStatement update=c.prepareStatement("UPDATE load_schedules SET schedule_json=?,next_run_at=?,updated_at=? WHERE schedule_id=?")) {
                        update.setString(1,ServerRuntime.JSON.writeValueAsString(schedule));update.setString(2,nextFire);update.setString(3,schedule.updatedAt);update.setString(4,expected.id);update.executeUpdate();
                    }
                    try(PreparedStatement occurrence=c.prepareStatement("INSERT INTO load_schedule_executions(occurrence_id,schedule_id,scheduled_for,status,reason,trigger_type,created_at) VALUES(?,?,?,'skipped',?,'cron',?)")) {
                        occurrence.setString(1,occurrenceId);occurrence.setString(2,expected.id);occurrence.setString(3,firstMissed.toString());occurrence.setString(4,reason);occurrence.setString(5,Instant.now().toString());occurrence.executeUpdate();
                    }
                }
            }
            c.commit();return true;
        }
    }
    synchronized void insertManualOccurrence(String scheduleId,String occurrenceId,Instant at) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("INSERT INTO load_schedule_executions(occurrence_id,schedule_id,scheduled_for,status,trigger_type,created_at) VALUES(?,?,?,'scheduled','manual',?)")){
            p.setString(1,occurrenceId);p.setString(2,scheduleId);p.setString(3,at.toString());p.setString(4,Instant.now().toString());p.executeUpdate();
        }
    }
    synchronized Instant lastManualTrigger(String scheduleId) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT scheduled_for FROM load_schedule_executions WHERE schedule_id=? AND trigger_type='manual' AND NOT (status='skipped' AND reason='manual-trigger-rate-limit') ORDER BY scheduled_for DESC LIMIT 1")){
            p.setString(1,scheduleId);try(ResultSet r=p.executeQuery()){return r.next()?Instant.parse(r.getString(1)):null;}
        }
    }
    synchronized void updateOccurrence(String occurrenceId,String status,String reason,String jobId) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE load_schedule_executions SET status=?,reason=?,job_id=? WHERE occurrence_id=?")){
            p.setString(1,status);p.setString(2,reason);p.setString(3,jobId);p.setString(4,occurrenceId);p.executeUpdate();
        }
        String scheduleId=null,packageId=null;
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT e.schedule_id,s.schedule_json FROM load_schedule_executions e LEFT JOIN load_schedules s ON s.schedule_id=e.schedule_id WHERE e.occurrence_id=?")){
            p.setString(1,occurrenceId);try(ResultSet r=p.executeQuery()){if(r.next()){scheduleId=r.getString(1);String json=r.getString(2);if(json!=null)packageId=ServerRuntime.JSON.readValue(json,LoadSchedule.class).packageId;}}
        }
        if(scheduleId!=null)try{audit("system:load-scheduler","SCHEDULE_OCCURRENCE",occurrenceId,packageId,status);}catch(Exception failure){java.util.logging.Logger.getLogger(JobStore.class.getName()).warning("Unable to persist schedule occurrence audit record");}
        if(scheduleId!=null)pruneScheduleExecutions(scheduleId);
    }
    private void pruneScheduleExecutions(String scheduleId) throws Exception {
        List<String> remove=new ArrayList<>();
        String sql="SELECT e.occurrence_id FROM load_schedule_executions e WHERE e.schedule_id=? AND e.status<>'scheduled' AND (e.job_id IS NULL OR NOT EXISTS (SELECT 1 FROM jobs j WHERE j.job_id=e.job_id AND j.status IN ('QUEUED','PREPARING','RUNNING'))) ORDER BY e.scheduled_for DESC,e.occurrence_id DESC LIMIT 100000 OFFSET ?";
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql)){p.setString(1,scheduleId);p.setInt(2,scheduleHistoryLimit);try(ResultSet r=p.executeQuery()){while(r.next())remove.add(r.getString(1));}}
        if(!remove.isEmpty())try(Connection c=connect();PreparedStatement p=c.prepareStatement("DELETE FROM load_schedule_executions WHERE occurrence_id=?")){for(String id:remove){p.setString(1,id);p.addBatch();}p.executeBatch();}
    }
    synchronized List<Map<String,Object>> scheduleExecutions(String scheduleId,int limit) throws Exception {
        List<Map<String,Object>> out=new ArrayList<>();
        String sql="SELECT e.occurrence_id,e.schedule_id,e.scheduled_for,e.status,e.reason,e.job_id,e.trigger_type,e.created_at,j.status "
                +"FROM load_schedule_executions e LEFT JOIN jobs j ON j.job_id=e.job_id AND (j.retention_state IS NULL OR j.retention_state<>'DELETING') WHERE e.schedule_id=? ORDER BY e.scheduled_for DESC LIMIT ?";
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,scheduleId);p.setInt(2,limit);
            try(ResultSet r=p.executeQuery()){
                while(r.next()){
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("occurrenceId",r.getString(1));row.put("scheduleId",r.getString(2));row.put("scheduledFor",r.getString(3));
                    row.put("status",r.getString(4));row.put("reason",r.getString(5));row.put("jobId",r.getString(6));
                    row.put("triggerType",r.getString(7));row.put("createdAt",r.getString(8));row.put("jobStatus",r.getString(9));
                    out.add(row);
                }
            }
        }return out;
    }
    synchronized Map<String,Object> scheduleExecution(String occurrenceId) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT e.occurrence_id,e.schedule_id,e.scheduled_for,e.status,e.reason,e.job_id,e.trigger_type,e.created_at,j.status FROM load_schedule_executions e LEFT JOIN jobs j ON j.job_id=e.job_id AND (j.retention_state IS NULL OR j.retention_state<>'DELETING') WHERE e.occurrence_id=?")){
            p.setString(1,occurrenceId);try(ResultSet r=p.executeQuery()){if(!r.next())return null;Map<String,Object> row=new LinkedHashMap<>();row.put("occurrenceId",r.getString(1));row.put("scheduleId",r.getString(2));row.put("scheduledFor",r.getString(3));row.put("status",r.getString(4));row.put("reason",r.getString(5));row.put("jobId",r.getString(6));row.put("triggerType",r.getString(7));row.put("createdAt",r.getString(8));row.put("jobStatus",r.getString(9));return row;}
        }
    }
    synchronized int activeScheduledCount(String scheduleId) throws Exception {
        String sql="SELECT COUNT(*) FROM jobs j JOIN load_schedule_executions e ON e.occurrence_id=j.schedule_occurrence_id WHERE j.status IN ('QUEUED','PREPARING','RUNNING')"+(scheduleId==null?"":" AND e.schedule_id=?");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql)){if(scheduleId!=null)p.setString(1,scheduleId);try(ResultSet r=p.executeQuery()){r.next();return r.getInt(1);}}
    }
    synchronized Map<String,Object> scheduleMetrics() throws Exception {
        Map<String,Object> counts=new LinkedHashMap<>();counts.put("scheduled",0L);counts.put("submitted",0L);counts.put("skipped",0L);counts.put("failed-to-submit",0L);
        try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT status,COUNT(*) FROM load_schedule_executions GROUP BY status")){
            while(r.next())counts.put(r.getString(1),r.getLong(2));
        }
        String nextDue=null;try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT MIN(next_run_at) FROM load_schedules WHERE enabled=TRUE AND next_run_at IS NOT NULL")){if(r.next())nextDue=r.getString(1);}
        Map<String,Object> metrics=new LinkedHashMap<>();metrics.put("nextDueAt",nextDue);metrics.put("occurrences",counts);return metrics;
    }
    synchronized void deleteSchedule(String id) throws Exception {try(Connection c=connect();PreparedStatement p=c.prepareStatement("DELETE FROM load_schedules WHERE schedule_id=?")){p.setString(1,id);p.executeUpdate();}}
    synchronized void recoverScheduleExecutions() throws Exception {
        List<String> pending=new ArrayList<>();
        try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT occurrence_id FROM load_schedule_executions WHERE status='scheduled'")){while(r.next())pending.add(r.getString(1));}
        for(String occurrence:pending){String job=null;try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT job_id FROM jobs WHERE schedule_occurrence_id=?")){p.setString(1,occurrence);try(ResultSet r=p.executeQuery()){if(r.next())job=r.getString(1);}}
            if(job==null)updateOccurrence(occurrence,"failed-to-submit","server-restarted-during-submission",null);
            else updateOccurrence(occurrence,"submitted",null,job);
        }
    }
    void update(Job j) throws Exception {
        // Read Job state before taking the store monitor: callers may hold the Job monitor while writing.
        String occurrenceId=j.scheduleOccurrenceId;boolean terminal=j.terminal();
        synchronized(this) {
            try(Connection c=connect(); PreparedStatement p=c.prepareStatement("UPDATE jobs SET status=?,started_at=?,finished_at=?,worker_pid=?,worker_start_time=?,att_version=?,exit_code=?,result_json=?,diagnostic_json=?,performance_json=? WHERE job_id=?")) {
                p.setString(1,j.status);p.setString(2,text(j.startedAt));p.setString(3,text(j.finishedAt));
                if(j.workerPid==null)p.setNull(4,java.sql.Types.BIGINT);else p.setLong(4,j.workerPid);
                p.setString(5,text(j.workerStartTime));p.setString(6,ServerBuildInfo.PRODUCT);
                if(j.exitCode==null)p.setNull(7,java.sql.Types.INTEGER);else p.setInt(7,j.exitCode);
                p.setString(8,j.resultJson);p.setString(9,j.diagnosticJson);p.setString(10,ServerRuntime.JSON.writeValueAsString(j.performance()));p.setString(11,j.id);p.executeUpdate();
            }
            if(terminal&&occurrenceId!=null) {
                String scheduleId=null;
                try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT schedule_id FROM load_schedule_executions WHERE occurrence_id=?")) {
                    p.setString(1,occurrenceId);try(ResultSet r=p.executeQuery()){if(r.next())scheduleId=r.getString(1);}
                }
                if(scheduleId!=null)pruneScheduleExecutions(scheduleId);
            }
        }
    }
    synchronized List<String> auditOutcomes(String action) throws Exception {
        List<String> outcomes=new ArrayList<>();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT outcome FROM audit WHERE action=? ORDER BY audit_id")) {
            p.setString(1,action);try(ResultSet r=p.executeQuery()){while(r.next())outcomes.add(r.getString(1));}
        }
        return outcomes;
    }
    synchronized void audit(String principal,String action,String jobId,String packageId,String outcome) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("INSERT INTO audit(principal,action,job_id,package_id,created_at,outcome) VALUES(?,?,?,?,?,?)")) {
            p.setString(1,bounded(principal,256));p.setString(2,action);p.setString(3,jobId);p.setString(4,packageId);p.setString(5,Instant.now().toString());p.setString(6,outcome);p.executeUpdate();
        }
        long latest=0;try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT MAX(audit_id) FROM audit")){if(r.next())latest=r.getLong(1);}
        if(latest>0&&latest%256==0)try(Connection c=connect();Statement s=c.createStatement()){s.execute("DELETE FROM audit WHERE audit_id NOT IN (SELECT audit_id FROM audit ORDER BY audit_id DESC LIMIT 100000)");}
    }
    synchronized int authUserCount() throws Exception {try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM auth_users")){r.next();return r.getInt(1);}}
    synchronized boolean bootstrapClaimed() throws Exception {try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM auth_bootstrap_claim WHERE claim_id=1")){r.next();return r.getInt(1)>0;}}
    synchronized Map<String,Object> authUser(String login) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT login_id,display_name,password_hash,role,active,csrf_token FROM auth_users WHERE login_id=?")){p.setString(1,login);try(ResultSet r=p.executeQuery()){if(!r.next())return null;Map<String,Object> m=new LinkedHashMap<>();m.put("loginId",r.getString(1));m.put("displayName",r.getString(2));m.put("passwordHash",r.getString(3));m.put("role",r.getString(4));m.put("active",r.getBoolean(5));m.put("csrfToken",r.getString(6));return m;}}
    }
    synchronized List<Map<String,Object>> authUsers() throws Exception {
        List<Map<String,Object>> users=new ArrayList<>();try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT login_id,display_name,role,active,updated_at FROM auth_users ORDER BY login_id")){while(r.next()){Map<String,Object> u=new LinkedHashMap<>();u.put("loginId",r.getString(1));u.put("displayName",r.getString(2));u.put("role",r.getString(3));u.put("active",r.getBoolean(4));u.put("updatedAt",r.getString(5));users.add(u);}}return users;
    }
    synchronized long activeAdminCount() throws Exception {try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM auth_users WHERE role='ADMIN' AND active=TRUE")){r.next();return r.getLong(1);}}
    boolean ownsJob(String id,String login) throws Exception {
        if(!isActiveUser(login))return false;
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT 1 FROM jobs WHERE job_id=?")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next();}
        }
    }
    synchronized List<Map<String,Object>> listOwned(int limit,String login) throws Exception {
        if(!isActiveUser(login))return List.of();
        return list(limit);
    }
    synchronized List<Map<String,Object>> listOwnedLoad(int limitPlusOne,int offset,String login) throws Exception {
        if(!isActiveUser(login))return List.of();
        return listLoad(limitPlusOne,offset);
    }
    synchronized List<Map<String,Object>> listLoad(int limitPlusOne,int offset) throws Exception {
        return listLoad(limitPlusOne,offset,null,false);
    }
    synchronized List<Map<String,Object>> listLoad(int limitPlusOne,int offset,String search,boolean pinnedOnly) throws Exception {
        List<Map<String,Object>> out=new ArrayList<>();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT job_id,command,package_id,principal,status,created_at,started_at,finished_at,exit_code,request_summary,pinned,pinned_at,pinned_by,pin_reason FROM jobs WHERE command='load' AND (retention_state IS NULL OR retention_state<>'DELETING') AND LOCATE(?,LOWER(COALESCE(principal,''))) > 0"+(pinnedOnly?" AND pinned=TRUE":"")+" ORDER BY created_at DESC,job_id DESC LIMIT ? OFFSET ?")){
            p.setString(1,search==null?"":search.trim().toLowerCase(java.util.Locale.ROOT));p.setInt(2,limitPlusOne);p.setInt(3,offset);try(ResultSet r=p.executeQuery()){while(r.next())out.add(jobSummary(r));}
        }return out;
    }
    /** One snapshot for page membership, authorized totals and group aggregates. Never reads evidence or private specs. */
    Map<String,Object> queryJobs(JobQuery query) throws Exception {
        List<Object> parameters=new ArrayList<>();String where=query.where(parameters);
        try(Connection c=connect()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);
            try {
                long total;
                try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM jobs WHERE "+where)) {
                    bind(p,parameters);try(ResultSet r=p.executeQuery()){r.next();total=r.getLong(1);}
                }
                List<Map<String,Object>> items=new ArrayList<>();
                String projection="job_id,command,package_id,principal,status,created_at,started_at,finished_at,exit_code,request_summary,pinned,pinned_at,pinned_by,pin_reason,retention_state,target_identity,target_group,environment,originating_schedule_id";
                try(PreparedStatement p=c.prepareStatement("SELECT "+projection+" FROM jobs WHERE "+where+" ORDER BY "+query.order()+" LIMIT ? OFFSET ?")) {
                    bind(p,parameters);p.setInt(parameters.size()+1,query.limit);p.setInt(parameters.size()+2,query.offset);
                    try(ResultSet r=p.executeQuery()){while(r.next()) {
                        Map<String,Object> item=jobSummary(r);item.put("deletionState",r.getString(15));
                        String identity=r.getString(16);if(identity!=null)item.put("targetIdentity",ServerRuntime.JSON.readValue(identity,Map.class));
                        item.put("scheduled",r.getString(19)!=null||scheduledScheduleId(r.getString(4))!=null);
                        item.put("targetGroup",r.getString(17));item.put("environment",r.getString(18));items.add(item);
                    }}
                }
                List<Map<String,Object>> groups=new ArrayList<>();
                if(query.grouped&&!items.isEmpty()) {
                    List<Object> groupParameters=new ArrayList<>(parameters);List<String> predicates=new ArrayList<>();Set<List<String>> seen=new java.util.HashSet<>();
                    for(var item:items) {
                        List<String> key=List.of(String.valueOf(item.get("packageId")),String.valueOf(item.get("environment")),String.valueOf(item.get("targetGroup")));
                        if(seen.add(key)){predicates.add("(package_id=? AND environment=? AND target_group=?)");groupParameters.addAll(key);}
                    }
                    try(PreparedStatement p=c.prepareStatement("SELECT package_id,environment,target_group,COUNT(*),MAX(created_at),SUM(CASE WHEN status='PASS' THEN 1 ELSE 0 END),SUM(CASE WHEN status='FAIL' THEN 1 ELSE 0 END),SUM(CASE WHEN status='ERROR' THEN 1 ELSE 0 END),SUM(CASE WHEN status='CANCELLED' THEN 1 ELSE 0 END),SUM(CASE WHEN status IN ('QUEUED','PREPARING','RUNNING','CANCEL_REQUESTED') THEN 1 ELSE 0 END),SUM(CASE WHEN status='INVALID' THEN 1 ELSE 0 END) FROM jobs WHERE "+where+" AND ("+String.join(" OR ",predicates)+") GROUP BY package_id,environment,target_group")) {
                        bind(p,groupParameters);try(ResultSet r=p.executeQuery()){while(r.next()) {
                            Map<String,Object> group=new LinkedHashMap<>();group.put("packageId",r.getString(1));group.put("environment",r.getString(2));group.put("targetGroup",r.getString(3));group.put("total",r.getLong(4));group.put("lastCreatedAt",r.getString(5));
                            group.put("statuses",Map.of("PASS",r.getLong(6),"FAIL",r.getLong(7),"ERROR",r.getLong(8),"CANCELLED",r.getLong(9),"inProgress",r.getLong(10),"INVALID",r.getLong(11)));groups.add(group);
                        }}
                    }
                }
                List<String> principals=new ArrayList<>(),packages=new ArrayList<>();
                try(PreparedStatement p=c.prepareStatement("SELECT DISTINCT principal FROM jobs WHERE (retention_state IS NULL OR retention_state<>'DELETING') AND principal NOT LIKE 'load-schedule:%' AND principal NOT LIKE 'debug-schedule:%' AND originating_schedule_id IS NULL AND LOCATE(LOWER(?),LOWER(principal))>0 ORDER BY principal LIMIT 30")) {
                    p.setString(1,"text".equals(query.principalMode)&&query.principal!=null?query.principal:"");
                    try(ResultSet r=p.executeQuery()){while(r.next())principals.add(r.getString(1));}
                }
                try(PreparedStatement p=c.prepareStatement("SELECT DISTINCT package_id FROM jobs WHERE (retention_state IS NULL OR retention_state<>'DELETING') ORDER BY package_id LIMIT 100");ResultSet r=p.executeQuery()){while(r.next())packages.add(r.getString(1));}
                c.commit();Map<String,Object> response=new LinkedHashMap<>();response.put("items",items);response.put("total",total);response.put("offset",query.offset);response.put("limit",query.limit);response.put("nextOffset",query.offset+(long)items.size()<total?query.offset+items.size():null);response.put("groups",groups);response.put("principalSuggestions",principals);response.put("packageSuggestions",packages);return response;
            } catch(Exception failure){c.rollback();throw failure;}
        }
    }
    private static void bind(PreparedStatement p,List<Object> parameters) throws Exception {for(int i=0;i<parameters.size();i++)p.setObject(i+1,parameters.get(i));}

    private static Map<String,Object> jobSummary(ResultSet r) throws Exception {
        Map<String,Object> m=new LinkedHashMap<>();m.put("jobId",r.getString(1));m.put("command",r.getString(2));m.put("executionType",Job.executionType(r.getString(2)));
        m.put("packageId",r.getString(3));m.put("principal",r.getString(4));m.put("status",r.getString(5));m.put("createdAt",r.getString(6));
        m.put("startedAt",r.getString(7));m.put("finishedAt",r.getString(8));m.put("exitCode",r.getObject(9));
        m.put("pinned",r.getBoolean(11));m.put("pinnedAt",r.getString(12));m.put("pinnedBy",r.getString(13));
        m.put("pinReason",r.getString(14));
        String summary=r.getString(10);
        if(summary!=null&&!summary.isBlank())try {
            @SuppressWarnings("unchecked") Map<String,Object> details=ServerRuntime.JSON.readValue(summary,Map.class);
            for(String key:List.of("scenario","environment","model"))if(details.get(key)!=null)m.put(key,details.get(key));
        } catch(Exception ignored) { /* Older or malformed summaries do not make job history unavailable. */ }
        return m;
    }
    synchronized void createAuthUser(String login,String display,String hash,String role,String csrf) throws Exception {try(Connection c=connect();PreparedStatement p=c.prepareStatement("INSERT INTO auth_users(login_id,display_name,password_hash,role,active,updated_at,csrf_token) VALUES(?,?,?,?,TRUE,?,?)")){p.setString(1,login);p.setString(2,display);p.setString(3,hash);p.setString(4,role);p.setString(5,Instant.now().toString());p.setString(6,csrf);p.executeUpdate();}}
    void createBootstrapAdmin(String login,String display,String hash,String csrf) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM auth_users")){r.next();if(r.getLong(1)>0)throw new AuthService.AuthFailure(409,"ATT-AUTH-BOOTSTRAP-CLOSED");}
                try(PreparedStatement claim=c.prepareStatement("INSERT INTO auth_bootstrap_claim(claim_id,claimed_at) VALUES(1,?)")){claim.setString(1,Instant.now().toString());claim.executeUpdate();}
                try(PreparedStatement user=c.prepareStatement("INSERT INTO auth_users(login_id,display_name,password_hash,role,active,updated_at,csrf_token) VALUES(?,?,?,'ADMIN',TRUE,?,?)")){user.setString(1,login);user.setString(2,display);user.setString(3,hash);user.setString(4,Instant.now().toString());user.setString(5,csrf);user.executeUpdate();}
                c.commit();
            } catch(Exception failure) {
                c.rollback();
                if(failure instanceof java.sql.SQLException sql&&"23505".equals(sql.getSQLState()))throw new AuthService.AuthFailure(409,"ATT-AUTH-BOOTSTRAP-CLOSED");
                throw failure;
            }
        }
    }
    synchronized void updateAuthPassword(String login,String hash,String csrf) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try(PreparedStatement update=c.prepareStatement("UPDATE auth_users SET password_hash=?,csrf_token=?,updated_at=? WHERE login_id=?")) {
                update.setString(1,hash);update.setString(2,csrf);update.setString(3,Instant.now().toString());update.setString(4,login);
                if(update.executeUpdate()==0)throw new AuthService.AuthFailure(404,"ATT-AUTH-USER-NOT-FOUND");
                try(PreparedStatement revoke=c.prepareStatement("DELETE FROM auth_sessions WHERE login_id=?")){revoke.setString(1,login);revoke.executeUpdate();}
                c.commit();
            } catch(Exception failure) {c.rollback();throw failure;}
        }
    }
    synchronized void recoverExistingAdmin(String login,String hash,String csrf) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                String role;
                try(PreparedStatement lookup=c.prepareStatement("SELECT role FROM auth_users WHERE login_id=? FOR UPDATE")) {
                    lookup.setString(1,login);try(ResultSet result=lookup.executeQuery()) {
                        if(!result.next())throw new IllegalArgumentException("The requested existing account was not found");
                        role=result.getString(1);
                    }
                }
                if(!"ADMIN".equals(role))throw new IllegalArgumentException("Recovery can update an existing ADMIN account only");
                try(PreparedStatement update=c.prepareStatement("UPDATE auth_users SET password_hash=?,csrf_token=?,active=TRUE,updated_at=? WHERE login_id=? AND role='ADMIN'")) {
                    update.setString(1,hash);update.setString(2,csrf);update.setString(3,Instant.now().toString());update.setString(4,login);update.executeUpdate();
                }
                try(PreparedStatement revoke=c.prepareStatement("DELETE FROM auth_sessions WHERE login_id=?")){revoke.setString(1,login);revoke.executeUpdate();}
                try(PreparedStatement audit=c.prepareStatement("INSERT INTO audit(principal,action,created_at,outcome) VALUES(?,?,?,?)")) {
                    audit.setString(1,"offline-admin-recovery:"+login);audit.setString(2,"ADMIN_RECOVERY");audit.setString(3,Instant.now().toString());audit.setString(4,"SUCCESS");audit.executeUpdate();
                }
                c.commit();
            } catch(Exception failure) {c.rollback();throw failure;}
        }
    }
    synchronized void setAuthActive(String login,boolean active) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                String role;boolean wasActive;
                try(PreparedStatement lookup=c.prepareStatement("SELECT role,active FROM auth_users WHERE login_id=?")) {
                    lookup.setString(1,login);try(ResultSet result=lookup.executeQuery()) {
                        if(!result.next())throw new IllegalArgumentException("Unknown user");
                        role=result.getString(1);wasActive=result.getBoolean(2);
                    }
                }
                if(!active&&wasActive&&"ADMIN".equals(role)) {
                    try(Statement lock=c.createStatement();ResultSet guard=lock.executeQuery("SELECT claim_id FROM auth_bootstrap_claim WHERE claim_id=1 FOR UPDATE")) {
                        if(!guard.next())throw new IllegalStateException("The local administrator guard is missing");
                    }
                    try(Statement count=c.createStatement();ResultSet admins=count.executeQuery("SELECT COUNT(*) FROM auth_users WHERE role='ADMIN' AND active=TRUE")) {
                        admins.next();if(admins.getLong(1)<=1)throw new AuthService.AuthFailure(409,"ATT-AUTH-LAST-ADMIN");
                    }
                }
                try(PreparedStatement update=c.prepareStatement("UPDATE auth_users SET active=?,updated_at=? WHERE login_id=?")) {
                    update.setBoolean(1,active);update.setString(2,Instant.now().toString());update.setString(3,login);update.executeUpdate();
                }
                if(!active)try(PreparedStatement revoke=c.prepareStatement("DELETE FROM auth_sessions WHERE login_id=?")){revoke.setString(1,login);revoke.executeUpdate();}
                c.commit();
            } catch(Exception failure) {c.rollback();throw failure;}
        }
    }
    synchronized void saveAuthSession(String tokenHash,String login,String csrf,long now,long expiry) throws Exception {try(Connection c=connect();PreparedStatement p=c.prepareStatement("INSERT INTO auth_sessions(token_hash,login_id,csrf_token,created_at,last_seen,absolute_expiry) VALUES(?,?,?,?,?,?)")){p.setString(1,tokenHash);p.setString(2,login);p.setString(3,csrf);p.setLong(4,now);p.setLong(5,now);p.setLong(6,expiry);p.executeUpdate();}}
    synchronized Map<String,String> authSession(String hash,long now,long idleMillis) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT s.login_id,s.csrf_token,s.last_seen,s.absolute_expiry,u.display_name,u.role,u.active FROM auth_sessions s JOIN auth_users u ON u.login_id=s.login_id WHERE s.token_hash=?")){p.setString(1,hash);try(ResultSet r=p.executeQuery()){if(!r.next())return null;long seen=r.getLong(3),expiry=r.getLong(4);if(!r.getBoolean(7)||now>expiry||now-seen>idleMillis){deleteAuthSession(hash);return null;}try(Connection touchConnection=connect();PreparedStatement touch=touchConnection.prepareStatement("UPDATE auth_sessions SET last_seen=? WHERE token_hash=?")){touch.setLong(1,now);touch.setString(2,hash);touch.executeUpdate();}Map<String,String> m=new LinkedHashMap<>();m.put("loginId",r.getString(1));m.put("csrfToken",r.getString(2));m.put("displayName",r.getString(5));m.put("role",r.getString(6));return m;}}
    }
    synchronized void deleteAuthSession(String hash) throws Exception {try(Connection c=connect();PreparedStatement p=c.prepareStatement("DELETE FROM auth_sessions WHERE token_hash=?")){p.setString(1,hash);p.executeUpdate();}}
    synchronized void revokeAuthSessions(String login) throws Exception {try(Connection c=connect();PreparedStatement p=c.prepareStatement("DELETE FROM auth_sessions WHERE login_id=?")){p.setString(1,login);p.executeUpdate();}}
    synchronized List<String> staleJobIds() throws Exception {
        List<String> ids=new ArrayList<>();
        try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT job_id FROM jobs WHERE status IN ('QUEUED','PREPARING','RUNNING')")) { while(r.next())ids.add(r.getString(1)); }
        return ids;
    }
    synchronized List<String> expiredJobIds(Instant before) throws Exception {
        List<String> ids=new ArrayList<>();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT job_id FROM jobs WHERE pinned=FALSE AND worker_quarantined=FALSE AND artifact_recovery=FALSE AND (retention_state IS NULL OR retention_state='') AND status IN ('PASS','FAIL','ERROR','INVALID','CANCELLED') AND finished_at IS NOT NULL AND finished_at < ? ORDER BY finished_at LIMIT 500")) {
            p.setString(1,before.toString());try(ResultSet r=p.executeQuery()){while(r.next())ids.add(r.getString(1));}
        }
        return ids;
    }
    synchronized att.server.api.ArtifactArchiveMetrics archiveMetrics(String id) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT archive_metrics_json FROM jobs WHERE job_id=?")) {
            p.setString(1,id);
            try(ResultSet r=p.executeQuery()) {
                if(!r.next())return null;
                String json=r.getString(1);
                return json==null?null:ServerRuntime.JSON.readValue(json,att.server.api.ArtifactArchiveMetrics.class);
            }
        }
    }
    synchronized void archiveMetrics(String id,att.server.api.ArtifactArchiveMetrics metrics) throws Exception {
        String json=ServerRuntime.JSON.writeValueAsString(metrics);
        if(json.length()>8192)throw new IllegalArgumentException("Archive metrics exceed limits");
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE jobs SET archive_metrics_json=? WHERE job_id=?")) {
            p.setString(1,json);p.setString(2,id);p.executeUpdate();
        }
    }

    synchronized List<String> artifactRecoveryJobIds(String after,int limit) throws Exception {
        if(limit<1||limit>64)throw new IllegalArgumentException("Invalid recovery batch size");
        List<String> ids=new ArrayList<>();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT job_id FROM jobs WHERE artifact_recovery=TRUE AND job_id>? AND status IN ('PASS','FAIL','ERROR','INVALID','CANCELLED') AND finished_at IS NOT NULL ORDER BY job_id LIMIT ?")) {
            p.setString(1,after);p.setInt(2,limit);try(ResultSet r=p.executeQuery()){while(r.next())ids.add(r.getString(1));}
        }
        return ids;
    }
    synchronized void artifactRecoveryNeeded(String id,boolean needed) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE jobs SET artifact_recovery=? WHERE job_id=?")) {
            p.setBoolean(1,needed);p.setString(2,id);p.executeUpdate();
        }
    }
    synchronized void quarantineWorker(String id) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE jobs SET worker_quarantined=TRUE WHERE job_id=?")) {
            p.setString(1,id);if(p.executeUpdate()!=1)throw new IllegalStateException("Job quarantine could not be persisted");
            // Do not depend on the database's delayed-write window before freeing admission.
            try(Statement checkpoint=c.createStatement()){checkpoint.execute("CHECKPOINT SYNC");}
        }
    }
    synchronized boolean workerQuarantined(String id) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT worker_quarantined FROM jobs WHERE job_id=?")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()&&r.getBoolean(1);}
        }
    }
    synchronized boolean markDeleting(String id, Instant finishedAtOrBefore) throws Exception {
        return markDeleting(id, finishedAtOrBefore, null);
    }
    synchronized boolean markDeleting(String id, Instant finishedAtOrBefore, Long bytes) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE jobs SET retention_state='DELETING', retention_bytes=? WHERE job_id=? AND pinned=FALSE AND worker_quarantined=FALSE AND artifact_recovery=FALSE AND status IN ('PASS','FAIL','ERROR','INVALID','CANCELLED') AND finished_at IS NOT NULL AND finished_at<=? AND (retention_state IS NULL OR retention_state='')")) {
            if(bytes==null||bytes<0)p.setNull(1,java.sql.Types.BIGINT);else p.setLong(1,bytes);
            p.setString(2,id);p.setString(3,finishedAtOrBefore.toString());return p.executeUpdate()==1;
        }
    }
    synchronized boolean setPinned(String id,boolean pinned,String actor) throws Exception {return setPinned(id,pinned,actor,null);}
    synchronized boolean setPinned(String id,boolean pinned,String actor,String reason) throws Exception {
        return !"NOT_ELIGIBLE".equals(setPinnedOutcome(id,pinned,actor,reason));
    }
    synchronized String setPinnedOutcome(String id,boolean pinned,String actor,String reason) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                boolean previous;
                try(PreparedStatement p=c.prepareStatement("SELECT pinned FROM jobs WHERE job_id=? AND command IN ('run','debug','load') AND status IN ('PASS','FAIL','ERROR','INVALID','CANCELLED') AND (retention_state IS NULL OR retention_state='') FOR UPDATE")) {
                    p.setString(1,id);try(ResultSet r=p.executeQuery()){if(!r.next())return "NOT_ELIGIBLE";previous=r.getBoolean(1);}
                }
                try(PreparedStatement p=c.prepareStatement("UPDATE jobs SET pinned=?,pinned_at=?,pinned_by=?,pin_reason=? WHERE job_id=? AND command IN ('run','debug','load') AND status IN ('PASS','FAIL','ERROR','INVALID','CANCELLED') AND (retention_state IS NULL OR retention_state='')")) {
                    p.setBoolean(1,pinned);p.setString(2,pinned?Instant.now().toString():null);p.setString(3,pinned?bounded(actor,256):null);p.setString(4,pinned?reason:null);p.setString(5,id);if(p.executeUpdate()!=1){c.rollback();return "NOT_ELIGIBLE";}
                }
                try(PreparedStatement p=c.prepareStatement("INSERT INTO audit(principal,action,job_id,created_at,outcome,detail) VALUES(?,?,?,?,?,?)")) {
                    p.setString(1,bounded(actor,256));p.setString(2,"JOB_PIN");p.setString(3,id);p.setString(4,Instant.now().toString());p.setString(5,previous+"->"+pinned);p.setString(6,pinned?reason:null);p.executeUpdate();
                }
                c.commit();return previous==pinned?"ALREADY_IN_STATE":pinned?"PINNED":"UNPINNED";
            } catch(Exception error){c.rollback();throw error;}
        }
    }
    synchronized boolean markManualDeleting(String id,String actor) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE jobs SET retention_state='DELETING',retention_actor=? WHERE job_id=? AND pinned=FALSE AND worker_quarantined=FALSE AND artifact_recovery=FALSE AND status IN ('PASS','FAIL','ERROR','INVALID','CANCELLED') AND finished_at IS NOT NULL AND (retention_state IS NULL OR retention_state='')")) {
            p.setString(1,bounded(actor,256));p.setString(2,id);return p.executeUpdate()==1;
        }
    }
    /** Durable reversible intent; the claim and pin transitions share the same row lock. */
    synchronized String setDeletionRequested(String id,boolean requested,String actor,long now) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                String outcome;
                try(PreparedStatement p=c.prepareStatement("SELECT retention_state,pinned,worker_quarantined,artifact_recovery,status,finished_at FROM jobs WHERE job_id=? FOR UPDATE")) {
                    p.setString(1,id);
                    try(ResultSet r=p.executeQuery()) {
                        if(!r.next())outcome="ALREADY_UNAVAILABLE";
                        else if("DELETING".equals(r.getString(1)))outcome="ALREADY_CLAIMED";
                        else if(requested=="DELETE_REQUESTED".equals(r.getString(1)))outcome="ALREADY_IN_STATE";
                        else if(requested&&r.getBoolean(2))outcome="PINNED_UNPIN_FIRST";
                        else if(requested&&(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(r.getString(5))||r.getString(6)==null))outcome="NOT_TERMINAL";
                        else if(requested&&(r.getBoolean(3)||r.getBoolean(4)))outcome="NOT_ELIGIBLE";
                        else {
                            try(PreparedStatement update=c.prepareStatement("UPDATE jobs SET retention_state=?,retention_actor=?,delete_requested_at=? WHERE job_id=?")) {
                                update.setString(1,requested?"DELETE_REQUESTED":null);update.setString(2,requested?bounded(actor,256):null);
                                if(requested)update.setLong(3,now);else update.setNull(3,java.sql.Types.BIGINT);
                                update.setString(4,id);update.executeUpdate();
                            }
                            outcome=requested?"MARKED":"UNMARKED";
                        }
                    }
                }
                try(PreparedStatement audit=c.prepareStatement("INSERT INTO audit(principal,action,job_id,created_at,outcome) VALUES(?,?,?,?,?)")) {
                    audit.setString(1,bounded(actor,256));audit.setString(2,requested?"JOB_DELETE_MARK":"JOB_DELETE_UNMARK");
                    audit.setString(3,id);audit.setString(4,Instant.ofEpochMilli(now).toString());audit.setString(5,outcome);audit.executeUpdate();
                }
                c.commit();return outcome;
            } catch(Exception failed){c.rollback();throw failed;}
        }
    }
    synchronized List<String> requestedDeletionIds(long before) throws Exception {
        List<String> ids=new ArrayList<>();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT job_id FROM jobs WHERE retention_state='DELETE_REQUESTED' AND delete_requested_at<=? ORDER BY delete_requested_at,job_id LIMIT 100")) {
            p.setLong(1,before);try(ResultSet r=p.executeQuery()){while(r.next())ids.add(r.getString(1));}
        }return ids;
    }
    synchronized boolean claimRequestedDeletion(String id,long before) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement p=c.prepareStatement("UPDATE jobs SET retention_state='DELETING' WHERE job_id=? AND retention_state='DELETE_REQUESTED' AND delete_requested_at<=? AND pinned=FALSE AND worker_quarantined=FALSE AND artifact_recovery=FALSE AND status IN ('PASS','FAIL','ERROR','INVALID','CANCELLED') AND finished_at IS NOT NULL")) {
                    p.setString(1,id);p.setLong(2,before);if(p.executeUpdate()!=1){c.rollback();return false;}
                }
                try(PreparedStatement p=c.prepareStatement("INSERT INTO audit(principal,action,job_id,created_at,outcome) SELECT COALESCE(retention_actor,'system:job-retention'),'JOB_DELETE_CLAIM',job_id,?,'CLAIMED' FROM jobs WHERE job_id=?")) {
                    p.setString(1,Instant.now().toString());p.setString(2,id);p.executeUpdate();
                }
                c.commit();return true;
            } catch(Exception failed){c.rollback();throw failed;}
        }
    }

    synchronized String deletionActor(String id) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT retention_actor FROM jobs WHERE job_id=?")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next()?r.getString(1):null;}
        }
    }

    synchronized Long retentionBytes(String id) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT retention_bytes FROM jobs WHERE job_id=?")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){if(!r.next()||r.getObject(1)==null)return null;return r.getLong(1);}
        }
    }
    /** Records one DELETED storage-eviction audit for a Job. A repeated call after a crash does not insert another row. */
    synchronized void auditStorageEviction(String jobId, String outcome) throws Exception {
        if(jobId==null||outcome==null||!outcome.startsWith("DELETED"))throw new IllegalArgumentException("Storage eviction audit is incomplete");
        try(Connection c=connect();PreparedStatement existing=c.prepareStatement("SELECT 1 FROM audit WHERE action='JOB_STORAGE_EVICT' AND job_id=? AND outcome LIKE 'DELETED%'")) {
            existing.setString(1,jobId);try(ResultSet r=existing.executeQuery()){if(r.next())return;}
        }
        audit("system:job-retention","JOB_STORAGE_EVICT",jobId,null,outcome);
    }
    synchronized List<String> deletingJobIds() throws Exception {
        List<String> ids=new ArrayList<>();
        try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT job_id FROM jobs WHERE retention_state='DELETING' ORDER BY job_id LIMIT 200")) {
            while(r.next()) ids.add(r.getString(1));
        }
        return ids;
    }
    static final int STORAGE_CANDIDATE_PAGE = 200;
    /** Tests lower this with {@code ServerRuntime.storageCandidateBatchLimit} to reach the sweep budget. */
    int storageCandidatePage = STORAGE_CANDIDATE_PAGE;
    synchronized List<Map<String,Object>> storageCandidates() throws Exception { return storageCandidates(null, null); }
    synchronized List<Map<String,Object>> storageCandidates(String afterFinishedAt, String afterJobId) throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        boolean page=afterFinishedAt!=null&&afterJobId!=null;
        String sql="SELECT job_id,finished_at,command,status,artifact_recovery,worker_quarantined,pinned FROM jobs WHERE status IN ('PASS','FAIL','ERROR','INVALID','CANCELLED') AND finished_at IS NOT NULL AND (retention_state IS NULL OR retention_state='') AND worker_quarantined=FALSE AND artifact_recovery=FALSE"
                +(page?" AND (finished_at>? OR (finished_at=? AND job_id>?))":"")
                +" ORDER BY finished_at ASC, job_id ASC LIMIT "+storageCandidatePage;
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql)) {
            if(page){p.setString(1,afterFinishedAt);p.setString(2,afterFinishedAt);p.setString(3,afterJobId);}
            try(ResultSet r=p.executeQuery()) {
                while(r.next()) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("jobId",r.getString(1));row.put("finishedAt",r.getString(2));row.put("command",r.getString(3));row.put("status",r.getString(4));
                    row.put("pinned",r.getBoolean(7));row.put("artifactRecovery",r.getBoolean(5));row.put("quarantined",r.getBoolean(6));
                    rows.add(row);
                }
            }
        }
        return rows;
    }
    synchronized boolean jobRowExists(String id) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT 1 FROM jobs WHERE job_id=?")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next();}
        }
    }
    synchronized List<String> jobIds(int limit) throws Exception {
        List<String> ids=new ArrayList<>();
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT job_id FROM jobs ORDER BY job_id LIMIT ?")) {
            p.setInt(1,limit);try(ResultSet r=p.executeQuery()){while(r.next())ids.add(r.getString(1));}
        }
        return ids;
    }
    synchronized void deleteJob(String id) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("DELETE FROM jobs WHERE job_id=?")){p.setString(1,id);p.executeUpdate();}
    }
    synchronized void deleteJobWithAudit(String id,String principal,String action,String outcome) throws Exception {
        try(Connection c=connect()){c.setAutoCommit(false);try{
            try(PreparedStatement p=c.prepareStatement("DELETE FROM jobs WHERE job_id=?")){p.setString(1,id);if(p.executeUpdate()!=1)throw new IllegalStateException("Job row was not deleted");}
            boolean alreadyAudited=false;
            if("JOB_STORAGE_EVICT".equals(action)&&outcome.startsWith("DELETED"))try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM audit WHERE action='JOB_STORAGE_EVICT' AND job_id=? AND outcome LIKE 'DELETED%'")){
                p.setString(1,id);try(ResultSet r=p.executeQuery()){alreadyAudited=r.next();}
            }
            if(!alreadyAudited)try(PreparedStatement p=c.prepareStatement("INSERT INTO audit(principal,action,job_id,created_at,outcome) VALUES(?,?,?,?,?)")){p.setString(1,bounded(principal,256));p.setString(2,action);p.setString(3,id);p.setString(4,Instant.now().toString());p.setString(5,outcome);p.executeUpdate();}
            c.commit();
        }catch(Exception failure){c.rollback();throw failure;}}
    }
    synchronized List<java.util.Map<String,Object>> staleWorkers() throws Exception {
        List<java.util.Map<String,Object>> rows=new ArrayList<>();
        try(Connection c=connect();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT job_id,worker_pid,worker_start_time FROM jobs WHERE status IN ('QUEUED','PREPARING','RUNNING') AND worker_pid IS NOT NULL")) {while(r.next()){var m=new java.util.LinkedHashMap<String,Object>();m.put("jobId",r.getString(1));m.put("pid",r.getLong(2));m.put("start",r.getString(3));rows.add(m);}}
        return rows;
    }
    synchronized void rememberWorkerProcesses(String id, List<WorkerProcessFence.ProcessIdentity> identities) throws Exception {
        try(Connection c=connect()) {
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement("MERGE INTO job_worker_processes(job_id,process_pid,process_start_time) KEY(job_id,process_pid) VALUES(?,?,?)")) {
                for(var identity:identities) {
                    p.setString(1,id);p.setLong(2,identity.pid());p.setString(3,text(identity.start()));p.addBatch();
                }
                p.executeBatch();c.commit();
            } catch(Exception failure){c.rollback();throw failure;}
        }
    }

    /** Terminal delivery/parent exit do not prove observed descendants have exited. */
    synchronized boolean workerMayBeAlive(String id) throws Exception {
        if(workerQuarantined(id))return true;
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT worker_pid,worker_start_time FROM jobs WHERE job_id=? UNION ALL SELECT process_pid,process_start_time FROM job_worker_processes WHERE job_id=?")) {
            p.setString(1,id);p.setString(2,id);
            try(ResultSet r=p.executeQuery()) {
                while(r.next()) {
                    if(r.getObject(1)==null)continue;
                    java.util.Optional<ProcessHandle> process=ProcessHandle.of(r.getLong(1));
                    if(process.isEmpty()||!process.get().isAlive())continue;
                    String expected=r.getString(2);
                    java.util.Optional<Instant> actual=process.get().info().startInstant();
                    // Unknown process identity cannot prove the old writer has stopped.
                    if(expected==null||actual.isEmpty()||expected.equals(actual.get().toString()))return true;
                }
                return false;
            }
        }
    }
    synchronized void interruptStale(String id) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("UPDATE jobs SET status='ERROR',finished_at=?,diagnostic_json=? WHERE job_id=? AND status IN ('QUEUED','PREPARING','RUNNING')")) {
            p.setString(1,Instant.now().toString());p.setString(2,"{\"code\":\"ATT-SERVER-INTERRUPTED\",\"summary\":\"Job was interrupted by Server restart and was not rerun\"}");p.setString(3,id);p.executeUpdate();
        }
    }
    // Status-count polling owns its connection and result data; do not serialize it on the JobStore writer monitor.
    long count(String status) throws Exception { try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM jobs WHERE status=?")){p.setString(1,status);try(ResultSet r=p.executeQuery()){r.next();return r.getLong(1);}} }
    synchronized List<java.util.Map<String,Object>> list(int limit) throws Exception {
        return list(limit,null);
    }
    synchronized List<java.util.Map<String,Object>> list(int limit,String principalSearch) throws Exception {
        return list(limit,principalSearch,false);
    }
    synchronized List<java.util.Map<String,Object>> list(int limit,String principalSearch,boolean pinnedOnly) throws Exception {
        List<java.util.Map<String,Object>> out=new ArrayList<>();
        String filter=principalSearch==null||principalSearch.isEmpty()?null:principalSearch;
        String sql="SELECT job_id,command,package_id,principal,status,created_at,started_at,finished_at,exit_code,result_json,diagnostic_json,pinned,pinned_at,pinned_by,pin_reason FROM jobs WHERE (retention_state IS NULL OR retention_state<>'DELETING') "+(pinnedOnly?"AND pinned=TRUE ":"")+(filter==null?"":"AND LOCATE(LOWER(?),LOWER(principal))>0 ")+"ORDER BY created_at DESC LIMIT ?";
        try(Connection c=connect();PreparedStatement p=c.prepareStatement(sql)) {
            int index=1;if(filter!=null)p.setString(index++,filter);p.setInt(index,limit);try(ResultSet r=p.executeQuery()){while(r.next()){var m=new java.util.LinkedHashMap<String,Object>();m.put("jobId",r.getString(1));m.put("command",r.getString(2));m.put("executionType",Job.executionType(r.getString(2)));m.put("packageId",r.getString(3));m.put("principal",r.getString(4));m.put("status",r.getString(5));m.put("createdAt",r.getString(6));m.put("startedAt",r.getString(7));m.put("finishedAt",r.getString(8));m.put("exitCode",r.getObject(9));m.put("pinned",r.getBoolean(12));m.put("pinnedAt",r.getString(13));m.put("pinnedBy",r.getString(14));m.put("pinReason",r.getString(15));out.add(m);}}
        } return out;
    }
    // Job reads own their connection and result data; allow API polling alongside writes.
    java.util.Map<String,Object> get(String id) throws Exception {
        try(Connection c=connect();PreparedStatement p=c.prepareStatement("SELECT job_id,command,package_id,principal,status,created_at,started_at,finished_at,worker_pid,exit_code,result_json,diagnostic_json,att_version,performance_json,retention_state,pinned,pinned_at,pinned_by,pin_reason FROM jobs WHERE job_id=?")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){if(!r.next()||"DELETING".equals(r.getString(15)))return null;var m=new java.util.LinkedHashMap<String,Object>();m.put("jobId",r.getString(1));m.put("command",r.getString(2));m.put("executionType",Job.executionType(r.getString(2)));m.put("packageId",r.getString(3));m.put("principal",r.getString(4));m.put("status",r.getString(5));m.put("createdAt",r.getString(6));m.put("startedAt",r.getString(7));m.put("finishedAt",r.getString(8));m.put("workerPid",r.getObject(9));m.put("exitCode",r.getObject(10));m.put("resultJson",r.getString(11));m.put("diagnosticJson",r.getString(12));m.put("attVersion",r.getString(13));m.put("performanceJson",r.getString(14));m.put("pinned",r.getBoolean(16));m.put("pinnedAt",r.getString(17));m.put("pinnedBy",r.getString(18));m.put("pinReason",r.getString(19));m.put("deletionState",r.getString(15));return m;}}
    }
    private Connection connect() throws Exception{if(closed)throw new IllegalStateException("Job store is closed");return DriverManager.getConnection(url,"sa","");}
    private static String text(Instant value){return value==null?null:value.toString();}
    private static String bounded(String s,int max){return s.length()<=max?s:s.substring(0,max);}
    @Override public void close() {
        synchronized(DRIVER_LIFECYCLE) {
            if(closed)return;closed=true;
            if(databaseAnchor!=null)try {databaseAnchor.close();}
                catch(java.sql.SQLException failed){java.util.logging.Logger.getLogger(JobStore.class.getName()).log(java.util.logging.Level.WARNING,"Unable to close ATT-owned database connection",failed);}
            if(--openStores!=0)return;
            // A parent/container-owned driver belongs to the container, never to this WAR.
            ClassLoader owner=JobStore.class.getClassLoader();
            java.util.Enumeration<java.sql.Driver> drivers=DriverManager.getDrivers();
            while(drivers.hasMoreElements()) {
                java.sql.Driver driver=drivers.nextElement();
                if(driver.getClass().getClassLoader()==owner&&driver.getClass().getName().equals("org.h2.Driver"))
                    try {DriverManager.deregisterDriver(driver);}
                    catch(java.sql.SQLException failed){java.util.logging.Logger.getLogger(JobStore.class.getName()).log(java.util.logging.Level.WARNING,"Unable to unregister ATT-owned H2 driver",failed);}
            }
        }
    }
}
