package att.server;

import att.metrics.InspectionMetrics;
import static att.metrics.InspectionMetrics.Stage.*;

import att.server.api.WorkerRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** Owns the single-node job plane and its bounded Worker, inspector, and SSE executors. */
final class ServerRuntime implements AutoCloseable {
    static final ObjectMapper JSON=new ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
    private static final ObjectMapper ARTIFACT_JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                    .maxNestingDepth(64).maxStringLength(16 * 1024 * 1024).maxNumberLength(1000).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    static final Pattern JOB_ID=Pattern.compile("J[0-9A-F]{16}");
    static final Pattern DRAFT_ID=Pattern.compile("D[0-9A-F]{32}");
    static final Pattern QUICK_LOAD_DRAFT_ID=Pattern.compile("L[0-9A-F]{32}");
    static final Pattern ADVANCED_LOAD_DRAFT_ID=Pattern.compile("A[0-9A-F]{32}");
    static final Pattern CATALOG_LOAD_DRAFT_ID=Pattern.compile("C[0-9A-F]{32}");
    private static final Pattern INLINE_LOAD_DURATION=Pattern.compile("^([0-9]+)(ms|s|m|h)$");
    private static final Pattern INLINE_LOAD_RATE=Pattern.compile("^([1-9][0-9]*(?:\\.[0-9]+)?)/(s|m)$");
    static final int MAX_ACTIVE_DRAFTS=128, MAX_DRAFTS_PER_PRINCIPAL=16;
    // Public inspection limits; keep aligned with PackageResourceInspector in the Local runtime.
    private static final int RESOURCE_DEFAULT_PAGE_SIZE=50, RESOURCE_MAX_PAGE_SIZE=100;
    private static final int TESTDATA_RECORD_MAX_PAGE_SIZE=100;
    static final int MAX_ARTIFACT_READ_SLOTS=8;
    private static final int MAX_VALIDATION_PACKAGE_ENTRIES=20000, MAX_VALIDATION_ENVIRONMENTS=100, MAX_VNEXT_VALIDATION_PACKAGES=200;
    private static final long MAX_TOTAL_VNEXT_VALIDATION_WORK_UNITS=10000000L;
    private static final long LOAD_PROGRESS_PERSISTENCE_RETRY_NANOS=TimeUnit.SECONDS.toNanos(5);
    static final long DRAFT_TTL_MILLIS=TimeUnit.MINUTES.toMillis(10);
    static final int MAX_STREAM_OBSERVERS=32;
    private static final int INSPECTION_EXPLORER_PRIORITY_BURST=3;
    private static final class ValidatedScheduledScenario {
        final Map<String,Object> scenario;final String revisionDigest,model;final boolean containsSensitiveValues,containsSensitiveAuthoredValues;
        ValidatedScheduledScenario(Map<String,Object> scenario,String revisionDigest,String model,boolean containsSensitiveValues,boolean containsSensitiveAuthoredValues){this.scenario=scenario;this.revisionDigest=revisionDigest;this.model=model;this.containsSensitiveValues=containsSensitiveValues;this.containsSensitiveAuthoredValues=containsSensitiveAuthoredValues;}
    }
    final ServerConfig config;
    final JobStore store;
    private final LoadProgressPersistence loadProgressPersistence;
    final AuthService auth;
    final ThreadPoolExecutor workers,streams,inspectors;
    private final ThreadPoolExecutor eventCompactions=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1024),r->{Thread t=new Thread(r,"att-server-event-compactor");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor eventNotifications=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4096),r->{Thread t=new Thread(r,"att-server-event-dispatch");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private static final int TERMINAL_EVENT_CACHE_MAX_ENTRIES=16;
    private static final long TERMINAL_EVENT_CACHE_MAX_WEIGHT=128L*1024L*1024L;
    private final Object terminalEventCacheLock=new Object();
    private final Object[] terminalEventCacheStripes=eventCacheStripes();
    private final java.util.LinkedHashMap<String,JobEvents> terminalEventCache=
            new java.util.LinkedHashMap<>(16,0.75f,true);
    private long terminalEventCacheWeight;
    private static Object[] eventCacheStripes() {
        Object[] stripes=new Object[16];
        for(int i=0;i<stripes.length;i++)stripes[i]=new Object();
        return stripes;
    }
    private final ThreadPoolExecutor artifactWriters;
    private final ArtifactPublisher artifactPublisher;
    private final Semaphore artifactReadSlots=new Semaphore(MAX_ARTIFACT_READ_SLOTS,true);
    private final Set<String> pendingArtifactJobs=ConcurrentHashMap.newKeySet();
    private final Set<String> requestedArtifactRetries=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.locks.ReentrantReadWriteLock artifactLifecycle=new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    final ConcurrentHashMap<String,Job> jobs=new ConcurrentHashMap<>();
    /** Pages of {@link JobStore#STORAGE_CANDIDATE_PAGE} examined in one size sweep. Tests lower this to hit the boundary. */
    int storageCandidateBatchLimit=50;
    /** Keyset resume point for a partial size sweep. Both null start at the oldest candidate. */
    String storageScanAfterFinished;
    String storageScanAfterId;
    /** True once the current pass has examined the oldest candidate. Persisted with the resume cursor. */
    boolean storageScanCoveredHead;
    /** Tests set this so a restarted runtime can restore a cursor before the startup sweep. */
    static boolean skipInitialStorageSweep;
    private boolean storageCursorRestored;
    private final ConcurrentHashMap<String,FutureTask<Void>> tasks=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,AdmissionLease> leases=new ConcurrentHashMap<>();
    private final AtomicLong completed=new AtomicLong();
    private final AtomicLong compactionFailuresSinceStart=new AtomicLong();
    private final AtomicLong loadCapacityRejections=new AtomicLong();
    private final AtomicLong queueCapacityRejections=new AtomicLong();
    private final AtomicLong archiveFailures=new AtomicLong();
    private final AtomicLong archiveRetries=new AtomicLong();
    private final Semaphore loadSlots,admissionSlots;
    private final Semaphore inspectionTaskSlots;
    final Semaphore streamSlots=new Semaphore(MAX_STREAM_OBSERVERS,true);
    private final ScheduledExecutorService retention;
    private final ScheduledExecutorService artifactMaintenance;
    private final ScheduledExecutorService targetIndexMaintenance;
    private final java.util.concurrent.ConcurrentLinkedQueue<Job> deferredWorkerReleases=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Set<String> deferredWorkerReleaseIds=ConcurrentHashMap.newKeySet();
    private String artifactRecoveryCursor="";
    private final ScheduledExecutorService inspectionWatchdogs;
    private LoadScheduleScheduler loadScheduleScheduler;
    private final WorkerProcessLauncher processLauncher;
    private final Clock clock;
    private final long startedAtNanos=System.nanoTime();
    private static final long MONITORING_SNAPSHOT_CACHE_NANOS=TimeUnit.SECONDS.toNanos(1);
    private final Object monitoringSnapshotLock=new Object();
    private volatile MonitoringSnapshot cachedMonitoringSnapshot;
    private volatile long cachedMonitoringSnapshotAtNanos;
    final MonitoringRequestMetrics monitoringRequests=new MonitoringRequestMetrics();
    final PackageManifestPublisher manifestPublisher;
    private volatile Map<String,Object> jobStorageReport;
    private final ConcurrentHashMap<Process,Boolean> inspectionProcesses=new ConcurrentHashMap<>();
    private final Object draftLock=new Object();
    private final ConcurrentHashMap<String,DebugDraft> debugDrafts=new ConcurrentHashMap<>();
    private record RunDraft(String id,String principal,String packageId,String revision,JsonNode workerInput,
                            Map<String,Object> preview,Instant expiresAt,DebugDraft resourceDebugDraft) { }
    private final ConcurrentHashMap<String,RunDraft> runDrafts=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,QuickLoadDraft> quickLoadDrafts=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,AdvancedLoadDraftState> advancedLoadDrafts=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,CatalogLoadDraftState> catalogLoadDrafts=new ConcurrentHashMap<>();
    private record ScheduleExecutionReview(String scheduleId,String principal,long definitionVersion,String updatedAt,
                                           String draftId,String packageId,String executionType,String revision,Instant expiresAt,JsonNode packageEdit) { }
    private final ConcurrentHashMap<String,ScheduleExecutionReview> scheduleExecutionReviews=new ConcurrentHashMap<>();
    private final List<InspectionWorker> inspectionWorkerPool=new ArrayList<>();
    private final ScheduledExecutorService inspectionWarmup;
    private volatile boolean closing;
    private final java.util.concurrent.locks.ReentrantReadWriteLock admissionLifecycle=new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    private final ConcurrentHashMap<Thread,Process> stderrDrainers=new ConcurrentHashMap<>();
    final InspectionMetrics inspectionMetrics=new InspectionMetrics();
    private final AtomicLong inspectionWorkerSelection=new AtomicLong();
    private final AtomicLong inspectionTaskSequence=new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger inspectionExplorerPriorityCredits=
            new java.util.concurrent.atomic.AtomicInteger(INSPECTION_EXPLORER_PRIORITY_BURST);
    private final byte[] cursorKey=new byte[32];
    private final SecureRandom cursorRandom=new SecureRandom();
    private final Path workerLibs;
    private final String workerSchemaRegistryDigest;
    ExecutionWorkerPool executionWorkers;
    ServerRuntime(ServerConfig config,String workerLibs) throws Exception {
        this(config,workerLibs,ProcessBuilder::start);
    }
    ServerRuntime(ServerConfig config,String workerLibs,WorkerProcessLauncher processLauncher) throws Exception {
        this(config,workerLibs,processLauncher,Clock.systemUTC());
    }
    ServerRuntime(ServerConfig config,String workerLibs,WorkerProcessLauncher processLauncher,Clock clock) throws Exception {
        this(config,workerLibs,processLauncher,clock,(output,archive,validator)->
                ArtifactArchive.build(output,archive,validator,config.archiveCompression));
    }
    ServerRuntime(ServerConfig config,String workerLibs,WorkerProcessLauncher processLauncher,Clock clock,ArtifactPublisher artifactPublisher) throws Exception {
        this(config,workerLibs,processLauncher,clock,artifactPublisher,null);
    }
    ServerRuntime(ServerConfig config,String workerLibs,WorkerProcessLauncher processLauncher,Clock clock,ArtifactPublisher artifactPublisher,
                  LoadProgressPersistence loadProgressPersistence) throws Exception {
        if(Runtime.version().feature()<17)throw new IllegalStateException("ATT Server requires Java 17 or later; detected Java "+Runtime.version().feature());
        this.config=config;this.artifactPublisher=java.util.Objects.requireNonNull(artifactPublisher);this.workerLibs=workerLibs==null?null:Paths.get(workerLibs).toAbsolutePath().normalize();this.workerSchemaRegistryDigest=WorkerRuntimeClasspath.schemaRegistryDigestIfPresent(this.workerLibs);this.processLauncher=processLauncher;this.clock=java.util.Objects.requireNonNull(clock);this.store=new JobStore(config);this.loadProgressPersistence=loadProgressPersistence==null?this.store::updateLoadProgress:loadProgressPersistence;try{this.auth=new AuthService(config,store);}catch(Exception failed){store.close();throw failed;}this.loadSlots=new Semaphore(config.maxConcurrentLoad,true);this.admissionSlots=new Semaphore(config.maxConcurrent+config.queuedLimit,true);
        manifestPublisher=new PackageManifestPublisher(config.dataDir,config.packageConfiguration,clock,
                this::manifestEditableRoot,this::validateManifestCandidate,
                (actor,action,packageId,detail)->store.audit(actor,action,null,packageId,detail));
        cursorRandom.nextBytes(cursorKey);
        java.util.concurrent.BlockingQueue<Runnable> queue=config.queuedLimit==0?new SynchronousQueue<>():new ArrayBlockingQueue<>(config.queuedLimit);
        workers=new ThreadPoolExecutor(config.maxConcurrent,config.maxConcurrent,0,TimeUnit.MILLISECONDS,queue,r->{Thread t=new Thread(r,"att-server-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        streams=new ThreadPoolExecutor(0,MAX_STREAM_OBSERVERS,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"att-server-sse");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        artifactWriters=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(Math.max(1,config.queuedLimit+config.maxConcurrent)),r->{Thread t=new Thread(r,"att-server-artifact-writer");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        inspectionTaskSlots=new Semaphore(config.inspection.maxConcurrent+config.inspection.queuedLimit,true);
        java.util.concurrent.BlockingQueue<Runnable> inspectionQueue=new PriorityBlockingQueue<>();
        inspectors=new ThreadPoolExecutor(config.inspection.maxConcurrent,config.inspection.maxConcurrent,0,TimeUnit.MILLISECONDS,inspectionQueue,r->{Thread t=new Thread(r,"att-server-inspector");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        for(int i=0;i<config.inspection.maxConcurrent;i++)inspectionWorkerPool.add(new InspectionWorker());
        retention=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-retention");t.setDaemon(true);return t;});
        artifactMaintenance=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-artifact-maintenance");t.setDaemon(true);return t;});
        targetIndexMaintenance=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-target-index");t.setDaemon(true);return t;});
        inspectionWatchdogs=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-inspector-watchdog");t.setDaemon(true);return t;});
        inspectionWarmup=config.inspection.enabled&&config.inspection.warmup.enabled()
                ?java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"att-server-inspector-warmup");t.setDaemon(true);return t;}):null;
        try {
        recover();reconcileDeletingJobsSafely();cleanupExpiredJobsSafely();recoverArtifactArchivesSafely();retention.scheduleWithFixedDelay(this::cleanupExpiredJobsSafely,1,1,TimeUnit.HOURS);
        if(config.jobStorage.enabled){if(!skipInitialStorageSweep)cleanupJobStorageSafely();retention.scheduleWithFixedDelay(this::cleanupJobStorageSafely,config.jobStorage.cleanupIntervalMinutes,config.jobStorage.cleanupIntervalMinutes,TimeUnit.MINUTES);}
        artifactMaintenance.scheduleWithFixedDelay(this::recoverArtifactArchivesSafely,5,5,TimeUnit.MINUTES);
        artifactMaintenance.scheduleWithFixedDelay(this::reconcileWorkerReleases,1,1,TimeUnit.SECONDS);
        retention.scheduleWithFixedDelay(this::expireDraftsSafely,1,1,TimeUnit.MINUTES);
        retention.scheduleWithFixedDelay(this::cleanupRequestedDeletionsSafely,5,5,TimeUnit.SECONDS);
        targetIndexMaintenance.scheduleWithFixedDelay(()->{
            if(closing)return;
            try {if(store.backfillSearchTargetsBatch()==0)targetIndexMaintenance.shutdown();}
            catch(InterruptedException stopped){Thread.currentThread().interrupt();}
            catch(Exception failed){if(!closing)java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).log(java.util.logging.Level.WARNING,"Historical target indexing will retry",failed);}
        },100,100,TimeUnit.MILLISECONDS);
        if(config.scheduling.enabled) {
            store.recoverScheduleExecutions();
            if(!config.authenticationRequired&&config.authorization.allowAnonymousOperations)java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("ATT operations are enabled without authentication; anonymous clients share operational access");
            loadScheduleScheduler=new LoadScheduleScheduler(config.scheduling,store,this::submitScheduledJob,clock);
            loadScheduleScheduler.start();
        } else loadScheduleScheduler=null;
        executionWorkers=new ExecutionWorkerPool(config,id->workerBuilder(true,id),this::startWorkerProcess,
                ()->ExecutionWorkerPool.fingerprint(this.workerLibs,config.workerLibraryDirs),workerSchemaRegistryDigest);
        if(inspectionWarmup!=null)inspectionWarmup.scheduleWithFixedDelay(this::warmInspectionSafely,250,250,TimeUnit.MILLISECONDS);
        if(config.inspection.enabled&&!config.inspection.redactionEnabled)java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Inspection masking is disabled; ordinary package values are visible to authorized callers, while classified secrets remain protected");
        if(!config.security.checkCrossOrigin)java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("server.security.checkCrossOrigin is disabled; configure trusted proxy origin handling and retain local-auth CSRF protection");
        } catch(Exception failed) {close();throw failed;}
    }
    private void recover() throws Exception {
        for(Map<String,Object> stale:store.staleWorkers()) {
            try {
                long pid=((Number)stale.get("pid")).longValue();ProcessHandle.of(pid).ifPresent(handle->{
                    String expected=(String)stale.get("start");String actual=handle.info().startInstant().map(Instant::toString).orElse("");
                    String command=handle.info().commandLine().orElse("");
                    if(expected!=null&&expected.equals(actual)&&command.contains("att.worker.WorkerMain"))handle.destroyForcibly();
                });
            } catch(Exception ignored) { }
        }
        for(String id:store.staleJobIds()) {
            Map<String,Object> row=store.get(id);
            String mode=executionMode(row==null?null:String.valueOf(row.get("command")));
            store.interruptStale(id);
            Path journal=config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl");
            JobEvents events=new JobEvents(journal,config.maxEventsPerJob,config.maxEventJournalBytes,
                    config.maxEventJournalMemoryBytes,eventCompactions,eventNotifications,this::recordCompactionFailure);
            String summary="Job was interrupted by Server restart and was not rerun";
            events.append("diagnostic",Map.of("code","ATT-SERVER-INTERRUPTED","summary",summary,"console",publishConsole(att.console.ExecutionConsoleFormatter.diagnostic(mode,"ATT-SERVER-INTERRUPTED",summary))));
            events.append("result",Map.of("jobId",id,"status","ERROR","console",publishConsole(att.console.ExecutionConsoleFormatter.lifecycle(mode,id,"ERROR",null))));
        }
    }
    /** Capture only validator-approved data; never serialize WorkerRequest or inspection previews. */
    private JobExecutionSpec captureExecutionSpec(String command,JsonNode input,String principal,String scheduleOccurrenceId) throws Exception {
        if(!config.inspection.enabled||!List.of("run","debug","load").contains(command))return null;
        if(input.path("dryRun").asBoolean(false))throw new IllegalArgumentException("Validation-only execution cannot be replayed as a runnable job");
        if(input.path("rerunFailed").asBoolean(false)||input.hasNonNull("overrides")&&input.path("overrides").size()>0)
            throw new IllegalArgumentException("Legacy history selectors or raw overrides require a validated draft before safe retention");
        String packageId=required(input,"packageId");Path root=packageRoot(packageId);
        String environment=optionalText(input,"environment");
        ObjectNode definition=JSON.createObjectNode();definition.put("executionType",Job.executionType(command));definition.put("packageId",packageId);
        if(input.has("environment"))definition.set("environment",input.get("environment").deepCopy());
        String revision;
        if("load".equals(command)) {
            LoadSchedule source=new LoadSchedule();source.packageId=packageId;source.environment=environment;
            source.scenario=input.path("scenario").isTextual()?safeRelative(input.path("scenario").asText(),"scenario"):null;
            if(input.has("inlineLoadScenario"))source.inlineScenario=JSON.convertValue(input.get("inlineLoadScenario"),Map.class);
            if(input.has("load"))source.load=JSON.convertValue(input.get("load"),Map.class);
            ValidatedScheduledScenario validated=validateScheduleScenario(root,source);
            if(validated.containsSensitiveValues)throw new IllegalArgumentException("Sensitive execution inputs require secure re-entry");
            definition.set("scenario",JSON.valueToTree(validated.scenario));revision=validated.revisionDigest;
        } else {
            definition.put("schemaVersion",RunDefinition.VERSION);
            ObjectNode options=definition.putObject("options");options.put("debug","debug".equals(command)||input.path("diagnosticMode").asBoolean(false));
            if(input.has("target")) {
                ObjectNode debug=JSON.createObjectNode();debug.put("packageId",packageId);
                if(environment!=null)debug.put("environment",environment);
                debug.set("target",input.get("target"));
                JsonNode submitted=input.get("inlineDebugInput");
                // Prefer the original validated override, preserving omission, null and expressions.
                for(RunDraft draft:runDrafts.values())if(draft.workerInput.equals(input)&&draft.resourceDebugDraft!=null)submitted=JSON.valueToTree(draft.resourceDebugDraft.scheduleInput);
                for(DebugDraft draft:debugDrafts.values())if(draft.revisionDigest.equals(input.path("expectedRevisionDigest").asText())&&draft.resourceId.equals(input.path("draftResourceId").asText())&&draft.principal.equals(principal))submitted=JSON.valueToTree(draft.scheduleInput);
                if(submitted==null)throw new IllegalArgumentException("Source inputs were not retained");
                debug.set("input",submitted);DebugDraft validated=buildDebugDraft(debug,principal);
                if(validated.containsSensitiveValues)throw new IllegalArgumentException("Sensitive execution inputs require secure re-entry");
                definition.set("target",input.get("target").deepCopy());definition.set("input",JSON.valueToTree(validated.scheduleInput));revision=validated.revisionDigest;
            } else {
                WorkerRequest request=applyWorkerPolicy(JSON.treeToValue(input,WorkerRequest.class));
                request.protocolVersion="att-worker/v1";request.command="inspect";request.packageRoot=root.toString();request.config=config.runtimeConfigPath.toString();
                request.jobId="I"+UUID.randomUUID().toString().replace("-","");request.inspectionAction="run-input";
                ObjectNode policy=JSON.createObjectNode();applyInspectionPolicy(policy,packageId);JSON.readerForUpdating(request).readValue(policy);
                Map<String,Object> inspected=executeInspection(root,request);
                Object selected=inspected.get("selectedCaseIds");if(!(selected instanceof List<?> ids)||ids.isEmpty())throw new IllegalArgumentException("Exact case selection is unavailable");
                ObjectNode selection=definition.putObject("selection");selection.set("caseIds",JSON.valueToTree(selected));
                for(String field:List.of("suites","suiteDirectory"))if(input.has(field))selection.set(field,input.get(field).deepCopy());
                if(input.has("failFast"))options.set("failFast",input.get("failFast").deepCopy());
                revision=String.valueOf(inspected.get("revisionDigest"));
            }
        }
        if(input.hasNonNull("expectedRevisionDigest")&&!input.path("expectedRevisionDigest").asText().equals(revision))throw new StaleDraftException();
        String sourcePath=input.path("scenario").isTextual()?input.path("scenario").asText():null;
        if(sourcePath==null&&scheduleOccurrenceId!=null&&"load".equals(command)) {
            Map<String,Object> occurrence=store.scheduleExecution(scheduleOccurrenceId);
            LoadSchedule source=occurrence==null?null:store.schedule(String.valueOf(occurrence.get("scheduleId")));
            if(source!=null) {
                sourcePath=source.scenario;
                if(sourcePath==null&&source.sourceJobId!=null) {
                    Map<String,String> parent=store.executionSpec(source.sourceJobId);
                    if(parent!=null&&parent.get("spec")!=null)sourcePath=JobExecutionSpec.parse(parent.get("spec")).value.path("sourcePath").asText(null);
                }
            }
        }
        return new JobExecutionSpec(definition,revision,sourcePath).withConfiguration(input.path("configurationProvenance").isObject()?configurationRevision(JSON.convertValue(input.get("configurationProvenance"),Map.class),revision):null);
    }

    /** Builds the retained snapshot from a server-validated draft without re-running its inspection Worker. */
    private JobExecutionSpec capturePrevalidatedExecutionSpec(String command,JsonNode input,String scheduleOccurrenceId) throws Exception {
        if(!config.inspection.enabled||!List.of("run","debug","load").contains(command))return null;
        String revision=input.path("expectedRevisionDigest").asText("");
        if(!revision.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Validated execution revision is unavailable");
        String packageId=required(input,"packageId");
        ObjectNode definition=JSON.createObjectNode();definition.put("executionType",Job.executionType(command));definition.put("packageId",packageId);
        if(input.has("environment"))definition.set("environment",input.get("environment").deepCopy());
        String sourcePath=input.path("scenario").isTextual()?safeRelative(input.path("scenario").asText(),"scenario"):null;
        if("load".equals(command)) {
            JsonNode scenario=input.get("inlineLoadScenario");
            if(scenario==null||!scenario.isObject())throw new IllegalArgumentException("Validated Load scenario is unavailable");
            definition.set("scenario",scenario.deepCopy());
        } else {
            definition.put("schemaVersion",RunDefinition.VERSION);
            ObjectNode options=definition.putObject("options");options.put("debug","debug".equals(command)||input.path("diagnosticMode").asBoolean(false));
            if(input.path("target").isObject()) {
                JsonNode debugInput=input.get("inlineDebugInput");
                if(debugInput==null||!debugInput.isObject())throw new IllegalArgumentException("Validated resource input is unavailable");
                definition.set("target",input.get("target").deepCopy());definition.set("input",debugInput.deepCopy());
            } else {
                JsonNode caseIds=input.get("caseIds");
                if(caseIds==null||!caseIds.isArray()||caseIds.isEmpty())throw new IllegalArgumentException("Validated exact Case selection is unavailable");
                ObjectNode selection=definition.putObject("selection");selection.set("caseIds",caseIds.deepCopy());
                for(String field:List.of("suites","suiteDirectory"))if(input.has(field))selection.set(field,input.get(field).deepCopy());
                if(input.has("failFast"))options.set("failFast",input.get("failFast").deepCopy());
            }
            RunDefinition.parseRetained(definition);
        }
        if(sourcePath==null&&scheduleOccurrenceId!=null&&"load".equals(command)) {
            Map<String,Object> occurrence=store.scheduleExecution(scheduleOccurrenceId);
            LoadSchedule source=occurrence==null?null:store.schedule(String.valueOf(occurrence.get("scheduleId")));
            if(source!=null) {
                sourcePath=source.scenario;
                if(sourcePath==null&&source.sourceJobId!=null) {
                    Map<String,String> parent=store.executionSpec(source.sourceJobId);
                    if(parent!=null&&parent.get("spec")!=null)sourcePath=JobExecutionSpec.parse(parent.get("spec")).value.path("sourcePath").asText(null);
                }
            }
        }
        return new JobExecutionSpec(definition,revision,sourcePath).withConfiguration(input.path("configurationProvenance").isObject()?configurationRevision(JSON.convertValue(input.get("configurationProvenance"),Map.class),revision):null);
    }

    private void verifyStrictReplayRevision(String packageId,String environment,String revision) throws Exception {
        try {verifyPackageRevision(packageId,environment,revision);}
        catch(StaleCursorException stale){throw new JobConflictException("ATT-SERVER-REPLAY-REVISION-CONFLICT","The original Package revision is unavailable. Strict replay is blocked; review with the current Package or restore the original source separately.");}
    }

    private final Object replayLock=new Object();
    private final Map<String,ReplayReview> replayReviews=new java.util.concurrent.ConcurrentHashMap<>();
    private record ReplayReview(String source,String principal,String draftId,String packageId,String executionType,String sourcePolicy,
                                String originalRevision,String selectedRevision,String configurationDigest,
                                String sourcePath,ObjectNode originalDefinition,ObjectNode definition,Map<String,Object> originalConfiguration,
                                Map<String,Object> configuration,String validationCode,List<Map<String,Object>> diagnostics,
                                Instant expiresAt) {}

    private Map<String,String> ownedExecutionSpec(String id,String principal) throws Exception {
        requireLocalOperator(principal);requireJobOwner(id,principal);
        Map<String,String> record=store.executionSpec(id);if(record==null)throw new NotFoundException();
        boolean owned=principal.equals(record.get("principal"));
        if(!owned&&record.get("occurrenceId")!=null) {
            Map<String,Object> occurrence=store.scheduleExecution(record.get("occurrenceId"));
            LoadSchedule schedule=occurrence==null?null:store.schedule(String.valueOf(occurrence.get("scheduleId")));
            owned=schedule!=null&&(principal.equals(schedule.createdBy)||principal.equals(schedule.executionPrincipal));
        }
        if(!owned)throw new NotFoundException();
        return record;
    }
    Map<String,Object> jobReplay(String id,String principal) throws Exception {
        Map<String,String> record=ownedExecutionSpec(id,principal);Map<String,Object> view=new LinkedHashMap<>();
        view.put("jobId",id);view.put("available",record.get("spec")!=null);
        if(record.get("spec")==null)view.put("reason",record.get("reason")==null?"Rerun unavailable for this historical job. Configure a new run from the current package.":record.get("reason"));
        else {
            JobExecutionSpec spec=JobExecutionSpec.parse(record.get("spec"));
            if(spec.configuration()!=null)view.put("configuration",spec.configuration());
            view.put("definition",spec.definition());view.put("revisionPolicy","reject-package-changes");view.put("revision",spec.revision());
            if(spec.value.has("sourcePath"))view.put("sourcePath",spec.value.get("sourcePath").asText());
        }
        if(record.get("rerunOfJobId")!=null)view.put("rerunOfJobId",record.get("rerunOfJobId"));
        if((record.get("originatingScheduleId")!=null||record.get("occurrenceId")!=null)&&config.scheduling.enabled) {
            Map<String,Object> occurrence=record.get("occurrenceId")==null?null:store.scheduleExecution(record.get("occurrenceId"));
            String scheduleId=record.get("originatingScheduleId");
            if(scheduleId==null&&occurrence!=null)scheduleId=String.valueOf(occurrence.get("scheduleId"));
            LoadSchedule schedule=scheduleId==null?null:store.schedule(scheduleId);
            if(schedule!=null&&(principal.equals(schedule.createdBy)||principal.equals(schedule.executionPrincipal)))view.put("originatingScheduleId",schedule.id);
        }
        return view;
    }
    Map<String,Object> createJobReplayDraft(String id,String principal) throws Exception {
        return createJobReplayDraft(id,principal,JSON.createObjectNode());
    }
    Map<String,Object> createJobReplayDraft(String id,String principal,JsonNode request) throws Exception {
        if(!config.inspection.enabled)throw new IllegalArgumentException("Replay requires package inspection to be enabled by the Server");
        synchronized(replayLock) {
            requireObject(request,"Replay draft options must be a JSON object");requireOnlyFields(request,"sourcePolicy","overrides");
            String sourcePolicy=request.has("sourcePolicy")?required(request,"sourcePolicy"):"strict";
            if(!Set.of("current","strict").contains(sourcePolicy))throw new IllegalArgumentException("sourcePolicy must be current or strict");
            Map<String,String> record=ownedExecutionSpec(id,principal);
            if(record.get("spec")==null)throw new ReplayInputMissingException("Rerun is unavailable because this Job has no safely retained execution definition. Re-enter the missing inputs in a new run.");
            JobExecutionSpec spec=JobExecutionSpec.parse(record.get("spec"));ObjectNode originalDefinition=spec.definition();
            requireReplayReviewCapacity(principal);
            ObjectNode definition=JobReplayOverrides.apply(originalDefinition,request.get("overrides"));
            boolean packageDefaultEnvironment=spec.configuration()!=null&&"package".equals(spec.configuration().get("environmentOrigin"));
            JsonNode environmentOverride=request.path("overrides").path("environment");
            if(packageDefaultEnvironment&&(!environmentOverride.isTextual()||environmentOverride.asText().isBlank()))definition.remove("environment");
            String packageId=required(definition,"packageId"),environment=optionalText(definition,"environment");
            if("strict".equals(sourcePolicy))verifyStrictReplayRevision(packageId,environment,spec.revision());
            boolean run="run".equals(definition.path("executionType").asText());
            Map<String,Object> draft=null;String validationCode=null;List<Map<String,Object>> diagnostics=Collections.emptyList();
            try {
                if(run)draft=createRunDraft(definition,principal,true,null);
                else if(spec.value.has("sourcePath")) {
                    String path=safeRelative(spec.value.path("sourcePath").asText(),"scenario");validatePackagePath(packageRoot(packageId),path,"scenario");
                    LoadSchedule source=new LoadSchedule();source.packageId=packageId;source.environment=environment;
                    source.inlineScenario=JSON.convertValue(definition.get("scenario"),Map.class);
                    ValidatedScheduledScenario validated=validateScheduledScenario(packageRoot(packageId),source,source.inlineScenario,false);
                    validatePackageBackedReplayLoadLimits(config.inlineLoad,validated.scenario);
                    if(validated.containsSensitiveValues)throw new ReplayInputMissingException("Sensitive LOAD inputs were not retained. Enter them again through the authorized input controls.");
                    definition.set("scenario",JSON.valueToTree(validated.scenario));
                    Map<String,Object> configuration=configurationRevision(resolveExecutionConfiguration(packageId,packageRoot(packageId),environment),validated.revisionDigest);
                    CatalogLoadDraftState retained=new CatalogLoadDraftState("C"+UUID.randomUUID().toString().replace("-","").toUpperCase(),principal,packageId,environment,path,validated.revisionDigest,validated.model,validated.scenario,null,configuration,clock.instant().plusMillis(DRAFT_TTL_MILLIS));
                    synchronized(draftLock){expireDrafts();checkDraftCapacity(principal);catalogLoadDrafts.put(retained.id,retained);}
                    draft=catalogLoadDraftView(retained);
                } else {
                    ObjectNode input=definition.deepCopy();input.remove("executionType");draft=createAdvancedLoadDraft(input,principal,null);
                    JsonNode normalized=JSON.valueToTree(draft.get("scenario"));if(normalized.isObject())definition.set("scenario",normalized);
                }
            } catch(ReplayInputMissingException missing){validationCode="ATT-SERVER-REPLAY-INPUT-MISSING";diagnostics=List.of(replayDiagnostic(validationCode,"inputs",missing.getMessage()));}
              catch(InlineLoadLimitException limit){validationCode="ATT-SERVER-INLINE-LOAD-LIMIT";diagnostics=List.of(replayDiagnostic(validationCode,"scenario.load",limit.getMessage()));}
              catch(InspectionConfigurationException invalid){validationCode="ATT-SERVER-REPLAY-VALIDATION-FAILED";diagnostics=safeReplayDiagnostics(invalid.diagnostics,validationCode);}
              catch(DraftValidationException invalid){
                if(replayDiagnosticsShowMissingResource(invalid.diagnostics)){validationCode="ATT-SERVER-REPLAY-RESOURCE-MISSING";diagnostics=safeReplayDiagnostics(invalid.diagnostics,validationCode);}
                else {validationCode="ATT-SERVER-REPLAY-VALIDATION-FAILED";diagnostics=safeReplayDiagnostics(invalid.diagnostics,validationCode);}
              } catch(NotFoundException missing){validationCode="ATT-SERVER-REPLAY-RESOURCE-MISSING";diagnostics=List.of(replayDiagnostic(validationCode,"target or scenario","The retained resource is missing or no longer available in this Package. Choose an available resource, then validate again."));}
              catch(StaleCursorException stale){validationCode="ATT-SERVER-REPLAY-DRAFT-STALE";diagnostics=List.of(replayDiagnostic(validationCode,"package","The Package changed during validation. Validate these edits again."));}
              catch(IllegalArgumentException invalid){validationCode="ATT-SERVER-REPLAY-VALIDATION-FAILED";diagnostics=List.of(replayDiagnostic(validationCode,run?"target or selection":"scenario",invalid.getMessage()));}
            if(validationCode!=null)return createInvalidReplayDraft(id,principal,spec,sourcePolicy,originalDefinition,definition,packageId,run,validationCode,diagnostics);
            String draftId=String.valueOf(draft.get("draftId"));
            String selectedRevision=String.valueOf(draft.get("revisionDigest"));
            if(!selectedRevision.matches("[a-f0-9]{64}"))throw new IllegalStateException("Replay validation did not produce a current package revision");
            Map<String,Object> configuration=JSON.convertValue(draft.get("configuration"),Map.class);
            if(configuration==null||configuration.isEmpty())throw new IllegalStateException("Replay validation did not resolve current configuration");
            String effectiveEnvironment=configuration.get("environment") instanceof String?(String)configuration.get("environment"):environment;
            if(effectiveEnvironment!=null)definition.put("environment",effectiveEnvironment);
            if("strict".equals(sourcePolicy)&&!selectedRevision.equals(spec.revision()))verifyStrictReplayRevision(packageId,effectiveEnvironment,spec.revision());
            requireReplayReviewCapacity(principal);
            String intent=UUID.randomUUID().toString();Instant expires=clock.instant().plusMillis(DRAFT_TTL_MILLIS);
            replayReviews.put(intent,new ReplayReview(id,principal,draftId,packageId,run?"run":"load",sourcePolicy,spec.revision(),selectedRevision,
                    replayConfigurationDigest(configuration),spec.value.path("sourcePath").asText(null),originalDefinition.deepCopy(),definition.deepCopy(),spec.configuration(),
                    att.server.api.ConfigurationSnapshot.copy(configuration),null,Collections.emptyList(),expires));
            Map<String,Object> result=new LinkedHashMap<>(jobReplay(id,principal));
            Object oldConfiguration=result.remove("configuration");if(oldConfiguration!=null)result.put("originalConfiguration",oldConfiguration);
            result.put("schemaVersion","att-job-replay-draft/v1");result.put("sourceJobId",id);result.put("definition",definition);result.put("originalDefinition",originalDefinition);result.put("sourcePolicy",sourcePolicy);
            result.put("originalRevision",spec.revision());result.put("currentRevision",selectedRevision);result.put("configuration",configuration);
            result.put("missingFields",replayMissingFields(definition));
            result.put("intentId",intent);result.put("draftId",draftId);result.put("draftRef",draftId);result.put("expiresAt",expires.toString());result.put("preview",draft);return result;
        }
    }
    private Map<String,Object> createInvalidReplayDraft(String id,String principal,JobExecutionSpec spec,String sourcePolicy,
            ObjectNode originalDefinition,ObjectNode definition,String packageId,boolean run,String validationCode,
            List<Map<String,Object>> diagnostics) throws Exception {
        String environment=optionalText(definition,"environment"),selectedRevision="";Map<String,Object> configuration=Collections.emptyMap();
        try {
            Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();
            WorkerRequest revisionRequest=inspectionRequest(packageId,root,environment);revisionRequest.inspectionAction="revision";
            Map<String,Object> inspected=executeInspection(root,revisionRequest);Object digest=inspected.get("revisionDigest");
            if(digest instanceof String&&((String)digest).matches("[a-f0-9]{64}"))selectedRevision=(String)digest;
        } catch(Exception ignored) { /* Keep the editor draft available when current Package inspection itself is unavailable. */ }
        try {
            Path root=packageRoot(packageId);if(root!=null)configuration=configurationRevision(resolveExecutionConfiguration(packageId,root,environment),selectedRevision.isEmpty()?null:selectedRevision);
        } catch(Exception ignored) { /* Diagnostics below explain why the retained definition still needs editing. */ }
        String effectiveEnvironment=configuration.get("environment") instanceof String?(String)configuration.get("environment"):environment;
        if(effectiveEnvironment!=null)definition.put("environment",effectiveEnvironment);
        String draftRef=(run?"R":"C")+UUID.randomUUID().toString().replace("-","").toUpperCase(java.util.Locale.ROOT);
        Instant expires=clock.instant().plusMillis(DRAFT_TTL_MILLIS);requireReplayReviewCapacity(principal);
        replayReviews.put(UUID.randomUUID().toString(),new ReplayReview(id,principal,draftRef,packageId,run?"run":"load",sourcePolicy,spec.revision(),selectedRevision,
                null,spec.value.path("sourcePath").asText(null),originalDefinition.deepCopy(),definition.deepCopy(),spec.configuration(),
                configuration,validationCode,safeReplayDiagnostics(diagnostics,validationCode),expires));
        Map<String,Object> result=new LinkedHashMap<>(jobReplay(id,principal));Object oldConfiguration=result.remove("configuration");
        if(oldConfiguration!=null)result.put("originalConfiguration",oldConfiguration);
        result.put("schemaVersion","att-job-replay-draft/v1");result.put("draftRef",draftRef);result.put("draftId",draftRef);result.put("sourceJobId",id);
        result.put("packageId",packageId);result.put("executionType",run?"run":"load");result.put("sourcePolicy",sourcePolicy);
        result.put("originalRevision",spec.revision());result.put("currentRevision",selectedRevision);result.put("originalDefinition",originalDefinition);result.put("definition",definition);
        result.put("sourcePath",spec.value.path("sourcePath").asText(null));result.put("configuration",configuration);result.put("expiresAt",expires.toString());
        result.put("validationState","invalid");result.put("errorCode",validationCode);result.put("diagnostics",safeReplayDiagnostics(diagnostics,validationCode));
        List<String> missing=new ArrayList<>(replayMissingFields(definition));if("ATT-SERVER-REPLAY-INPUT-MISSING".equals(validationCode)&&!missing.contains("sensitive inputs"))missing.add("sensitive inputs");
        result.put("missingFields",missing);return result;
    }
    private void requireReplayReviewCapacity(String principal) {
        Instant now=clock.instant();replayReviews.entrySet().removeIf(entry->!entry.getValue().expiresAt.isAfter(now));
        long owned=replayReviews.values().stream().filter(review->java.util.Objects.equals(principal,review.principal)).count();
        if(replayReviews.size()>=MAX_ACTIVE_DRAFTS||owned>=MAX_DRAFTS_PER_PRINCIPAL)throw new DraftCapacityException();
    }
    private static Map<String,Object> replayDiagnostic(String code,String field,String summary) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("code",code);result.put("field",field);result.put("summary",summary==null?"Replay validation failed against the current Package.":summary);return result;
    }
    private static List<Map<String,Object>> safeReplayDiagnostics(List<Map<String,Object>> diagnostics,String fallbackCode) {
        List<Map<String,Object>> safe=new ArrayList<>();
        if(diagnostics!=null)for(Map<String,Object> diagnostic:diagnostics) {
            Map<String,Object> item=new LinkedHashMap<>();
            for(String field:List.of("code","field","path","resourceId","severity","summary","message","suggestion")) {
                Object value=diagnostic.get(field);if(value instanceof String) {
                    String text=(String)value;if(text.length()>512||containsControl(text)||text.startsWith("/")||text.contains("..\\"))continue;
                    item.put(field,text);
                }
            }
            if(item.get("code")==null)item.put("code",fallbackCode);if(item.get("summary")==null)item.put("summary","Replay validation failed against the current Package.");safe.add(item);
        }
        if(safe.isEmpty())safe.add(replayDiagnostic(fallbackCode,"scenario","Replay validation failed against the current Package. Review the affected fields and validate again."));
        return Collections.unmodifiableList(safe);
    }
    private static List<String> replayMissingFields(ObjectNode definition) {
        List<String> missing=new ArrayList<>();
        if("run".equals(definition.path("executionType").asText())) {
            if(!definition.path("target").isObject()&&!definition.path("selection").isObject())missing.add("target or selection");
            return missing;
        }
        JsonNode scenario=definition.path("scenario"),rootLoad=scenario.path("load"),rows=scenario.path("workloads");
        if(!hasValue(rootLoad.path("duration"))&&!hasWorkloadPhase(rows,"duration"))missing.add("scenario.load.duration");
        if(rows.isArray())for(int i=0;i<rows.size();i++) {
            JsonNode workload=rows.get(i),load=workload.path("load");String prefix="scenario.workloads["+i+"]";
            if(!hasValue(workload.path("id")))missing.add(prefix+".id");
            boolean arrival=hasValue(load.path("arrivalRate"))||hasValue(rootLoad.path("arrivalRate"));
            if(arrival) {
                if(!hasValue(load.path("arrivalRate"))&&!hasValue(rootLoad.path("arrivalRate")))missing.add(prefix+".load.arrivalRate");
                if(!hasValue(load.path("maxConcurrent"))&&!hasValue(rootLoad.path("maxConcurrent")))missing.add(prefix+".load.maxConcurrent");
                if(!hasValue(load.path("overloadPolicy"))&&!hasValue(rootLoad.path("overloadPolicy")))missing.add(prefix+".load.overloadPolicy");
            } else if(!hasValue(load.path("users"))&&!hasValue(rootLoad.path("users")))missing.add(prefix+".load.users");
        }
        return missing;
    }
    private static boolean hasWorkloadPhase(JsonNode rows,String phase) {
        if(!rows.isArray())return false;
        for(JsonNode row:rows)if(hasValue(row.path("load").path(phase)))return true;
        return false;
    }
    private static boolean hasValue(JsonNode value) {return value!=null&&!value.isMissingNode()&&!value.isNull()&&(!value.isTextual()||!value.asText().isBlank());}
    private String replayConfigurationDigest(Map<String,Object> configuration) throws Exception {
        byte[] bytes=JSON.writeValueAsBytes(att.server.api.ConfigurationSnapshot.copy(configuration));
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder(64);
        for(byte value:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();
    }
    Map<String,Object> getJobReplayEditorDraft(String jobId,String draftId,String principal) throws Exception {
        synchronized(replayLock) {
            ownedExecutionSpec(jobId,principal);
            ReplayReview review=null;
            for(ReplayReview candidate:replayReviews.values())if(candidate.source.equals(jobId)&&candidate.principal.equals(principal)&&candidate.draftId.equals(draftId)){review=candidate;break;}
            if(review==null)throw new ReplayDraftExpiredException();
            if(!review.expiresAt.isAfter(clock.instant())){replayReviews.values().remove(review);throw new ReplayDraftExpiredException();}
            Map<String,Object> view=new LinkedHashMap<>();view.put("schemaVersion","att-job-replay-draft/v1");view.put("draftRef",draftId);view.put("sourceJobId",jobId);
            view.put("packageId",review.packageId);view.put("executionType",review.executionType);view.put("sourcePolicy",review.sourcePolicy);
            view.put("originalRevision",review.originalRevision);view.put("currentRevision",review.selectedRevision);
            ObjectNode definition=review.definition.deepCopy(),originalDefinition=review.originalDefinition.deepCopy();
            if(review.sourcePath!=null){definition.put("sourcePath",review.sourcePath);originalDefinition.put("sourcePath",review.sourcePath);}
            view.put("definition",definition);view.put("originalDefinition",originalDefinition);view.put("sourcePath",review.sourcePath);
            if(review.originalConfiguration!=null)view.put("originalConfiguration",att.server.api.ConfigurationSnapshot.copy(review.originalConfiguration));
            view.put("configuration",review.configuration.isEmpty()?Collections.emptyMap():att.server.api.ConfigurationSnapshot.copy(review.configuration));view.put("expiresAt",review.expiresAt.toString());
            view.put("missingFields",replayMissingFields(review.definition));view.put("validationState",review.validationCode==null?"valid":"invalid");
            if(review.validationCode!=null){view.put("errorCode",review.validationCode);view.put("diagnostics",review.diagnostics);}
            return view;
        }
    }
    Map<String,Object> rerunJob(String id,JsonNode body,String principal) throws Exception {
        requireOnlyFields(body,"intentId","confirm","sourcePolicy");
        if(!body.path("confirm").isBoolean()||!body.path("confirm").booleanValue())throw new IllegalArgumentException("Positive replay confirmation is required");
        String requestedPolicy=body.has("sourcePolicy")?required(body,"sourcePolicy"):null;
        if(requestedPolicy!=null&&!Set.of("current","strict").contains(requestedPolicy))throw new IllegalArgumentException("sourcePolicy must be current or strict");
        String intent=required(body,"intentId");if(!intent.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid replay intent");
        synchronized(replayLock) {
            ownedExecutionSpec(id,principal);
            String accepted=store.replayJob(principal,intent,id);if(accepted!=null){requireJobOwner(accepted,principal);if(requestedPolicy!=null){Map<String,String> acceptedSpec=store.executionSpec(accepted);if(acceptedSpec==null||acceptedSpec.get("spec")==null||!requestedPolicy.equals(JobExecutionSpec.parse(acceptedSpec.get("spec")).value.path("replay").path("sourcePolicy").asText()))throw new JobConflictException("ATT-SERVER-REPLAY-INTENT-MISMATCH","The confirmation intent belongs to a different source policy.");}return jobRecord(accepted);}
            ReplayReview review=replayReviews.get(intent);
            if(review==null||!review.source.equals(id)||!review.principal.equals(principal)) {if(requestedPolicy!=null)throw new ReplayDraftExpiredException();throw new NotFoundException();}
            if(!review.expiresAt.isAfter(clock.instant()))throw new ReplayDraftExpiredException();
            if(requestedPolicy!=null&&!requestedPolicy.equals(review.sourcePolicy))throw new JobConflictException("ATT-SERVER-REPLAY-INTENT-MISMATCH","The confirmation intent belongs to a different source policy.");
            Map<String,String> record=ownedExecutionSpec(id,principal);JobExecutionSpec spec=JobExecutionSpec.parse(record.get("spec"));
            if("strict".equals(review.sourcePolicy))verifyStrictReplayRevision(review.packageId,optionalText(review.definition,"environment"),review.originalRevision);
            else try {verifyPackageRevision(review.packageId,optionalText(review.definition,"environment"),review.selectedRevision);}
                catch(StaleCursorException stale){throw new JobConflictException("ATT-SERVER-REPLAY-DRAFT-STALE","The Package changed after replay validation. Your edits are still available; validate them again.");}
            if(!review.configurationDigest.equals(replayConfigurationDigest(review.configuration)))throw new JobConflictException("ATT-SERVER-REPLAY-DRAFT-STALE","The validated configuration changed. Review the draft again.");
            ObjectNode input;
            if("run".equals(review.executionType))input=runDraft(review.draftId,principal,review.packageId).workerInput.deepCopy();
            else {
                AdvancedLoadDraftState draft=advancedLoadDrafts.get(review.draftId);CatalogLoadDraftState catalog=catalogLoadDrafts.get(review.draftId);
                if(draft==null&&catalog==null)throw new NotFoundException();
                Instant expires=draft==null?catalog.expiresAt:draft.expiresAt;
                if(!expires.isAfter(clock.instant()))throw new ReplayDraftExpiredException();
                input=JSON.createObjectNode();input.put("packageId",review.packageId);
                String environment=draft==null?catalog.environment:draft.environment;
                if(environment!=null)input.put("environment",environment);
                input.set("inlineLoadScenario",JSON.valueToTree(draft==null?catalog.scenario:draft.scenario));input.put("expectedRevisionDigest",review.selectedRevision);
                if(draft==null&&catalog.resourceId!=null)input.put("scenario",catalog.resourceId);
                input.set("configurationProvenance",JSON.valueToTree(review.configuration));
            }
            input.put("expectedRevisionDigest",review.selectedRevision);
            input.set("configurationProvenance",JSON.valueToTree(review.configuration));
            applyInspectionPolicy(input,review.packageId);
            JobExecutionSpec retained=capturePrevalidatedExecutionSpec(review.executionType,input,null)
                    .withReplaySource(id,review.sourcePolicy,review.originalRevision,review.selectedRevision);
            Map<String,Object> result=submit(review.executionType,input,principal,true,null,"load".equals(review.executionType)&&review.definition.has("scenario")&&spec.value.has("sourcePath"),id,intent,retained,
                    "load".equals(review.executionType)&&spec.value.has("sourcePath"));
            store.audit(principal,"JOB_RERUN",String.valueOf(result.get("jobId")),review.packageId,"ACCEPTED");
            runDrafts.remove(review.draftId);advancedLoadDrafts.remove(review.draftId);catalogLoadDrafts.remove(review.draftId);replayReviews.remove(intent);
            return result;
        }
    }
    private static boolean replayDiagnosticsShowMissingResource(List<Map<String,Object>> diagnostics) {
        for(Map<String,Object> diagnostic:diagnostics) {
            String code=String.valueOf(diagnostic.getOrDefault("code","" )).toUpperCase(java.util.Locale.ROOT);
            String summary=String.valueOf(diagnostic.getOrDefault("summary","" )).toLowerCase(java.util.Locale.ROOT);
            String detail=String.valueOf(diagnostic.getOrDefault("detail","" )).toLowerCase(java.util.Locale.ROOT);
            if(code.contains("NOT-FOUND")||code.contains("RESOURCE-MISSING")||summary.contains("not found")||summary.contains("no longer exists")||detail.contains("not found"))return true;
        }
        return false;
    }
    static final class ReplayValidationException extends RuntimeException {
        final List<Map<String,Object>> diagnostics;
        ReplayValidationException(String summary){this(List.of(Map.of("code","ATT-SERVER-REPLAY-VALIDATION-FAILED","summary",summary==null?"Replay validation failed against the current Package.":summary)));}
        ReplayValidationException(List<Map<String,Object>> diagnostics){super(diagnostics.isEmpty()?"Replay validation failed against the current Package.":String.valueOf(diagnostics.get(0).getOrDefault("summary","Replay validation failed against the current Package.")));this.diagnostics=Collections.unmodifiableList(new ArrayList<>(diagnostics));}
    }
    static final class ReplayResourceMissingException extends RuntimeException {ReplayResourceMissingException(){super("A retained Scenario or Target no longer resolves in the current Package. Edit the affected field or choose an available resource, then validate again.");}}

    Map<String,Object> submit(String command,JsonNode input,String principal) throws Exception {
        return submit(command,input,principal,false);
    }
    private Map<String,Object> submit(String command,JsonNode input,String principal,boolean serverIssuedDraft) throws Exception {
        return submit(command,input,principal,serverIssuedDraft,null,false);
    }
    private Map<String,Object> submit(String command,JsonNode input,String principal,boolean serverIssuedDraft,String scheduleOccurrenceId) throws Exception {
        return submit(command,input,principal,serverIssuedDraft,scheduleOccurrenceId,false);
    }
    private Map<String,Object> submit(String command,JsonNode input,String principal,boolean serverIssuedDraft,String scheduleOccurrenceId,boolean scheduleIssued) throws Exception {
        return submit(command,input,principal,serverIssuedDraft,scheduleOccurrenceId,scheduleIssued,null,null);
    }
    private Map<String,Object> submit(String command,JsonNode input,String principal,boolean serverIssuedDraft,String scheduleOccurrenceId,boolean scheduleIssued,String rerunOfJobId,String replayIntent) throws Exception {
        return submit(command,input,principal,serverIssuedDraft,scheduleOccurrenceId,scheduleIssued,rerunOfJobId,replayIntent,null);
    }
    private Map<String,Object> submit(String command,JsonNode input,String principal,boolean serverIssuedDraft,String scheduleOccurrenceId,boolean scheduleIssued,String rerunOfJobId,String replayIntent,JobExecutionSpec prevalidatedSpec) throws Exception {
        return submit(command,input,principal,serverIssuedDraft,scheduleOccurrenceId,scheduleIssued,rerunOfJobId,replayIntent,prevalidatedSpec,false);
    }
    private Map<String,Object> submit(String command,JsonNode input,String principal,boolean serverIssuedDraft,String scheduleOccurrenceId,boolean scheduleIssued,String rerunOfJobId,String replayIntent,JobExecutionSpec prevalidatedSpec,boolean fileBackedReplay) throws Exception {
        return submit(command,input,principal,serverIssuedDraft,scheduleOccurrenceId,scheduleIssued,rerunOfJobId,replayIntent,prevalidatedSpec,fileBackedReplay,null);
    }
    private Map<String,Object> submit(String command,JsonNode input,String principal,boolean serverIssuedDraft,String scheduleOccurrenceId,boolean scheduleIssued,String rerunOfJobId,String replayIntent,JobExecutionSpec prevalidatedSpec,boolean fileBackedReplay,JobProvenance provenance) throws Exception {
        admissionLifecycle.readLock().lock();
        try {
        if(closing)throw new QueueFullException("Server is shutting down");
        long requestReceivedNanos=System.nanoTime();
        if(!List.of("run","debug","load","validate").contains(command))throw new IllegalArgumentException("Unsupported job command");
        String packageId=required(input,"packageId");
        boolean loadAdmission="load".equals(command);
        // Reject requests that already cannot be admitted before any legacy direct-request inspection runs.
        // The final semaphore acquisition below remains authoritative when concurrent submissions race this check.
        if(loadAdmission&&loadSlots.availablePermits()==0){loadCapacityRejections.incrementAndGet();store.audit(principal,"SUBMIT",null,packageId,"REJECTED_LOAD_CAPACITY");throw new QueueFullException("Load capacity is full; retry after a Load job completes");}
        if(admissionSlots.availablePermits()==0){queueCapacityRejections.incrementAndGet();store.audit(principal,"SUBMIT",null,packageId,"REJECTED_QUEUE_FULL");throw new QueueFullException("Worker capacity is full; retry after a job completes");}
        if(!serverIssuedDraft&&(input.has("draftId")||input.has("inlineDebugInput")||input.has("inlineLoadScenario")
                ||input.has("expectedRevisionDigest")||input.has("expectedSchemaRegistryDigest")||input.has("draftResourceId")
                ||input.has("configurationProvenance")||input.has("registeredPackageId")))
            throw new IllegalArgumentException("Inline execution fields require a server-issued draft");
        if(serverIssuedDraft&&"load".equals(command)) {
            if(!scheduleIssued&&!fileBackedReplay)requireInlineLoadEnabled();
            JsonNode scenarioNode=input.get("inlineLoadScenario");
            if(scenarioNode==null||!scenarioNode.isObject())throw new IllegalArgumentException("A validated inline Load scenario is required");
            @SuppressWarnings("unchecked") Map<String,Object> scenario=(Map<String,Object>)JSON.convertValue(scenarioNode,Map.class);
            if(fileBackedReplay) {
                String sourcePath=optionalText(input,"scenario");
                if(sourcePath==null)throw new IllegalArgumentException("A package-file-backed replay requires its retained source path");
                validatePackagePath(packageRoot(required(input,"packageId")),safeRelative(sourcePath,"scenario"),"scenario");
                validatePackageBackedReplayLoadLimits(config.inlineLoad,scenario);
            } else if(scheduleIssued)validateScheduledLoadLimits(config.inlineLoad,scenario);else enforceInlineLoadLimits(scenario);
        }
        Path root=packageRoot(packageId);
        if(root==null)throw new IllegalArgumentException("Unknown packageId");
        validatePackageRoot(root);
        ObjectNode pinned=((ObjectNode)input).deepCopy();
        String requestedEnvironment=optionalText(input,"environment");
        Map<String,Object> configuration;
        if(serverIssuedDraft&&input.path("configurationProvenance").isObject()) {
            configuration=att.server.api.ConfigurationSnapshot.copy(JSON.convertValue(input.get("configurationProvenance"),Map.class));
            if(!packageId.equals(configuration.get("packageId"))||!java.util.Objects.equals(requestedEnvironment,configuration.get("environment")))
                throw new IllegalArgumentException("Configuration evidence differs from the approved execution");
            authorizeConfiguration(configuration);
        } else configuration=resolveExecutionConfiguration(packageId,root,requestedEnvironment);
        pinned.put("environment",(String)configuration.get("environment"));
        pinned.set("configurationProvenance",JSON.valueToTree(configuration));
        input=pinned;
        WorkerRequest request=applyWorkerPolicy(JSON.treeToValue(input,WorkerRequest.class));
        request.configurationProvenance=configuration;request.registeredPackageId=packageId;
        request.protocolVersion="att-worker/v1";request.command=command;request.packageRoot=root.toString();
        request.outputDirectory=null;request.config=config.runtimeConfigPath.toString();request.environment=safeText(request.environment,128,"environment");
        request.expectedSchemaRegistryDigest=workerSchemaRegistryDigest;
        request.runId=safeText(request.runId,128,"runId");request.debugId=safeText(request.debugId,128,"debugId");request.suiteDirectory=safeRelative(request.suiteDirectory,"suiteDirectory");
        request.scenario=safeRelative(request.scenario,"scenario");request.debugInput=safeRelative(request.debugInput,"debugInput");
        request.unsafeFailureDetails=false;
        request.suites=safePaths(request.suites,"suites");
        validatePackagePath(root,request.suiteDirectory,"suiteDirectory");
        validatePackagePath(root,request.scenario,"scenario");validatePackagePath(root,request.debugInput,"debugInput");
        if(request.suites!=null)for(String suite:request.suites)validatePackagePath(root,suite,"suites");
        if("debug".equals(command)||("run".equals(command)&&request.target!=null))validateTarget(request.target);
        if("load".equals(command)&&request.target!=null)validateTarget(request.target);

        String replayOwner=config.authenticationRequired?principal:RequestIdentity.ANONYMOUS,originatingScheduleId=null;
        if(scheduleOccurrenceId!=null) {
            Map<String,Object> occurrence=store.scheduleExecution(scheduleOccurrenceId);
            LoadSchedule source=occurrence==null?null:store.schedule(String.valueOf(occurrence.get("scheduleId")));
            if(source!=null){replayOwner=source.executionPrincipal==null?source.createdBy:source.executionPrincipal;originatingScheduleId=source.id;}
        }
        String executionSpec=null,replayUnavailable=null;
        try {
            if(prevalidatedSpec!=null)executionSpec=prevalidatedSpec.json();
            else if(rerunOfJobId!=null)executionSpec=ownedExecutionSpec(rerunOfJobId,principal).get("spec");
            else if(serverIssuedDraft) {
                if(prevalidatedSpec!=null)executionSpec=prevalidatedSpec.json();
                else replayUnavailable="Exact rerun is unavailable: sensitive execution inputs require secure re-entry.";
            } else {JobExecutionSpec captured=captureExecutionSpec(command,input,replayOwner,scheduleOccurrenceId);if(captured!=null)executionSpec=captured.json();}
        }
        catch(Exception unavailable) {replayUnavailable="Exact rerun is unavailable: source validation or safe input retention failed. Configure a new run and supply missing inputs.";}
        if(rerunOfJobId!=null&&executionSpec==null)throw new IllegalArgumentException("Safe execution snapshot is no longer available");
        if(executionSpec!=null) {
            JobExecutionSpec captured=JobExecutionSpec.parse(executionSpec);request.expectedRevisionDigest=captured.revision();
            // Legacy direct submissions also need the policy used to compute the retained revision.
            ObjectNode policy=JSON.createObjectNode();applyInspectionPolicy(policy,packageId);
            JSON.readerForUpdating(request).readValue(policy);
            JsonNode exact=captured.definition().path("selection").get("caseIds");
            if(exact!=null) {request.caseIds=JSON.convertValue(exact,List.class);request.tags=null;request.excludeTags=null;request.all=false;request.rerunFailed=false;}
        }
        configuration=configurationRevision(configuration,request.expectedRevisionDigest);request.configurationProvenance=configuration;
        if(executionSpec==null&&replayUnavailable==null)replayUnavailable="This job type or Server capability does not support safe replay. Configure a new run.";
        if(rerunOfJobId!=null&&prevalidatedSpec!=null) {
            JsonNode replay=prevalidatedSpec.value.path("replay");
            String policy=replay.path("sourcePolicy").asText("strict");
            String expected="strict".equals(policy)?replay.path("originalRevision").asText():replay.path("effectiveRevision").asText();
            try {verifyPackageRevision(packageId,request.environment,expected);}
            catch(StaleCursorException stale) {
                if("strict".equals(policy))throw new JobConflictException("ATT-SERVER-REPLAY-REVISION-CONFLICT","The original Package revision is unavailable. Strict replay is blocked; review with the current Package or restore the original source separately.");
                throw new JobConflictException("ATT-SERVER-REPLAY-DRAFT-STALE","The Package changed after replay validation. Your edits are still available; validate them again.");
            }
        }
        if(loadAdmission&&!loadSlots.tryAcquire()){loadCapacityRejections.incrementAndGet();store.audit(principal,"SUBMIT",null,packageId,"REJECTED_LOAD_CAPACITY");throw new QueueFullException("Load capacity is full; retry after a Load job completes");}
        if(!admissionSlots.tryAcquire()){if(loadAdmission)loadSlots.release();queueCapacityRejections.incrementAndGet();store.audit(principal,"SUBMIT",null,packageId,"REJECTED_QUEUE_FULL");throw new QueueFullException("Worker capacity is full; retry after a job completes");}
        AdmissionLease lease=new AdmissionLease(admissionSlots,loadAdmission?loadSlots:null);
        String id=null;Path jobPath=null;boolean persisted=false,handedOff=false;
        try {
            id="J"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();request.jobId=id;
            jobPath=config.dataDir.resolve("jobs").resolve(id);Files.createDirectories(jobPath.resolve("output"));final Path activeJobPath=jobPath;
            JobEvents events=new JobEvents(jobPath.resolve("events.jsonl"),config.maxEventsPerJob,config.maxEventJournalBytes,
                    config.maxEventJournalMemoryBytes,eventCompactions,eventNotifications,this::recordCompactionFailure);
            Job job=new Job(id,command,packageId,principal,request,events);job.scheduleOccurrenceId=scheduleOccurrenceId;job.requestReceivedNanos=requestReceivedNanos;
            if(provenance==null) {
                job.submissionSource="manual";job.originatingScheduleId=originatingScheduleId;job.executionPrincipal=config.authenticationRequired?principal:null;job.submittedBy=config.authenticationRequired?principal:null;
            } else {
                job.submissionSource="scheduled";job.originatingScheduleId=provenance.scheduleId;job.scheduleOwner=config.authenticationRequired?provenance.scheduleOwner:null;
                job.executionPrincipal=config.authenticationRequired?provenance.executionPrincipal:null;job.submittedBy=config.authenticationRequired?provenance.submittedBy:null;
                job.triggeredBy=config.authenticationRequired?provenance.triggeredBy:null;
            }
            job.executionSpec=executionSpec;job.replayUnavailable=replayUnavailable;job.rerunOfJobId=rerunOfJobId;job.replayIntent=replayIntent;job.replayOwner=replayOwner;if(job.originatingScheduleId==null)job.originatingScheduleId=originatingScheduleId;
            Map<String,Object> summaryFields=new LinkedHashMap<>();summaryFields.put("command",command);summaryFields.put("packageId",packageId);
            summaryFields.put("configuration",configuration);
            Files.write(jobPath.resolve("output/configuration.json"),JSON.writeValueAsBytes(configuration));
            if(request.scenario!=null)summaryFields.put("scenario",request.scenario);
            if(request.environment!=null&&!request.environment.isBlank())summaryFields.put("environment",request.environment);
            if(request.loadModel!=null)summaryFields.put("model",request.loadModel);
            JsonNode listDefinition=executionSpec==null?null:JobExecutionSpec.parse(executionSpec).definition();
            if(listDefinition==null) {
                ObjectNode fallback=JSON.createObjectNode();fallback.put("executionType","load".equals(command)?"load":"run");
                if(request.inlineLoadScenario!=null)fallback.set("scenario",JSON.valueToTree(request.inlineLoadScenario));
                if(request.target!=null)fallback.set("target",JSON.valueToTree(request.target));
                else if(request.caseIds!=null)fallback.putObject("selection").set("caseIds",JSON.valueToTree(request.caseIds));
                listDefinition=fallback;
            }
            job.targetIdentity=JobListIdentity.from(listDefinition);
            summaryFields.put("targetIdentity",job.targetIdentity);
            String summary=JSON.writeValueAsString(summaryFields);
            store.insert(job,summary,JobListIdentity.searchTargets(listDefinition));persisted=true;jobs.put(id,job);append(job,"status",Map.of("jobId",id,"status","QUEUED"));
            FutureTask<Void> task=new FutureTask<>(()->{execute(job,activeJobPath,lease);return null;});tasks.put(id,task);leases.put(id,lease);
            job.admittedNanos=System.nanoTime();
            // Saved-revision submission holds the store monitor until admission is complete.
            // Snapshot the queued response before the Worker can acquire the Job monitor and
            // then wait for that store monitor while persisting its first transition.
            Map<String,Object> acceptedView=job.view();
            try { workers.execute(task); }
            catch(java.util.concurrent.RejectedExecutionException full){throw new QueueFullException("Worker capacity is full; retry after a job completes");}
            handedOff=true;
            try{store.audit(principal,"SUBMIT",id,packageId,"ACCEPTED");}catch(Exception auditFailure){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to persist accepted job audit record");}
            return acceptedView;
        } catch(Exception failure) {
            if(!handedOff){if(id!=null){FutureTask<Void> task=tasks.remove(id);if(task!=null)task.cancel(false);jobs.remove(id);leases.remove(id);if(persisted)try{store.deleteJob(id);}catch(Exception ignored){}if(jobPath!=null)try{deleteTree(jobPath);}catch(Exception ignored){}}
                lease.release();if(failure instanceof QueueFullException)try{store.audit(principal,"SUBMIT",id,packageId,"REJECTED_QUEUE_FULL");}catch(Exception ignored){} }
            throw failure;
        }
        } finally {admissionLifecycle.readLock().unlock();}
    }
    Process startWorkerProcess(ProcessBuilder builder) throws java.io.IOException {
        // JDK 17 process reapers inherit the spawning thread's context loader.
        // They belong to the JVM and may outlive this WAR even after every child exits.
        Thread caller=Thread.currentThread();ClassLoader previous=caller.getContextClassLoader();
        caller.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        try {return processLauncher.start(builder);}
        finally {caller.setContextClassLoader(previous);}
    }
    private void startStderrDrainer(Process process,String name) {
        Thread thread=new Thread(()->{
            try(var input=process.getErrorStream()){byte[] buffer=new byte[8192];while(input.read(buffer)>=0){} }
            catch(Exception ignored){}finally{stderrDrainers.remove(Thread.currentThread());}
        },name);
        thread.setDaemon(true);stderrDrainers.put(thread,process);thread.start();
    }
    private void execute(Job job,Path jobPath,AdmissionLease lease) {
        job.workerStartedNanos=System.nanoTime();
        ExecutionWorkerPool.Lease warm=null;
        try {
            // Inspector work must not hold the cancellation/process-publication lock.
            String warmRevision=prewarmRevision(job);
            Process process;
            OutputStream workerInput;
            synchronized(job) {
                // Publish the process while holding the same lock used by cancel(). A
                // cancellation that wins before launch prevents the Worker from starting;
                // one that follows launch always sees and terminates that exact process.
                if(job.terminal())return;
                if(closing){finishQuietly(job,"ERROR",143,"ATT-SERVER-INTERRUPTED","Server shutdown stopped the queued Worker");return;}
                transition(job,"PREPARING");
                validatePackageRoot(packageRoot(job.packageId));
                job.request.outputDirectory=jobPath.resolve("output").toRealPath().toString();
                if(warmRevision!=null) {
                    long leaseStart=System.nanoTime();warm=executionWorkers.lease(job.packageId);
                    job.prewarmLeaseMs=(System.nanoTime()-leaseStart)/1_000_000.0;
                    if(warm!=null)job.request.expectedRevisionDigest=warmRevision;
                    else job.prewarmFallbackReason="no_ready_worker";
                }
                ProcessBuilder coldBuilder=warm==null?workerBuilder(false,job.packageId):null;
                job.workerSpawnStartedNanos=System.nanoTime();
                process=warm==null?startWorkerProcess(coldBuilder):warm.process;
                job.workerSpawnedNanos=System.nanoTime();job.process=process;job.processFence=warm==null?new WorkerProcessFence(process):warm.fence;
                workerInput=process.getOutputStream();
                if(warm!=null){job.workerDispatch="warm";job.prewarmBootstrapMs=(warm.readyNanos-warm.startedNanos)/1_000_000.0;}
                job.workerPid=process.pid();job.workerStartTime=process.info().startInstant().orElse(Instant.now());job.startedAt=Instant.now();transition(job,"RUNNING");rememberWorkerProcesses(job,job.processFence);
                if(warm!=null)warm.transferred();
            }
            startStderrDrainer(process,"att-server-worker-stderr");
            workerInput.write(workerRequestBytes(job.request,warm!=null));workerInput.write('\n');workerInput.flush();
            if(!"load".equals(job.command))workerInput.close();
            else synchronized(job) {job.workerControlStream=workerInput;}
            try(var reader=new java.io.BufferedInputStream(process.getInputStream())) {
                att.server.api.internal.WorkerProtocol.Frame frame;
                while((frame=att.server.api.internal.WorkerProtocol.read(reader,att.server.api.internal.WorkerProtocol.MAX_FRAME_BYTES))!=null) {
                    if(frame.oversized()){recordProtocolLimit(job,frame);continue;}
                    handleWorkerEvent(job,frame.text());
                }
            }
            int exit=process.waitFor();job.workerTerminatedNanos=System.nanoTime();job.exitCode=exit;
            if(!job.resultReceived&&!job.terminal()) {
                if(job.cancelRequested)finish(job,"CANCELLED",143);
                else {recordWorkerExit(job,exit);finish(job,"ERROR",exit==0?3:exit);}
            }
            else if(!job.terminal()) {String state=exit==0?"PASS":"FAIL";finish(job,state,exit);}
        } catch(InterruptedException e){Thread.currentThread().interrupt();stopWorkerTree(job);if(!job.terminal())finishQuietly(job,"CANCELLED",143,"ATT-SERVER-CANCELLED","Worker was cancelled");}
          catch(Exception e){stopWorkerTree(job);if(!job.terminal()){
              if(job.cancelRequested)finishQuietly(job,"CANCELLED",143,"ATT-SERVER-CANCELLED","Worker was cancelled");
              else finishQuietly(job,"ERROR",3,"ATT-SERVER-WORKER-FAILED",safeMessage(e));
          }}
        finally {
            stopWorkerTree(job);
            OutputStream workerInput=job.workerControlStream;job.workerControlStream=null;
            if(workerInput!=null)try{workerInput.close();}catch(Exception ignored){}
            if(warm!=null&&job.processFence!=null&&job.processFence.stopped())try{warm.transferred();}catch(Exception ignored) { }
            boolean stopped=job.processFence==null ? job.process==null||!job.process.isAlive() : job.processFence.stopped();
            if(stopped&&job.process!=null){if(job.workerTerminatedNanos==0)job.workerTerminatedNanos=System.nanoTime();job.process=null;if(!closing)try{store.update(job);}catch(Exception ignored){}}
            tasks.remove(job.id);completed.incrementAndGet();
            // A terminal RESULT is not the execution cleanup fence. Publish only
            // after cleanup and admission release, never while this Job is active.
            if(!releaseStoppedWorker(job,lease)&&!closing&&deferredWorkerReleaseIds.add(job.id))deferredWorkerReleases.add(job);
        }
    }
    int reconcileWorkerReleases() {
        int budget=Math.min(64,deferredWorkerReleases.size());
        int attempted=0;
        for(int i=0;i<budget&&!closing;i++) {
            Job job=deferredWorkerReleases.poll();if(job==null)break;
            attempted++;
            AdmissionLease lease=leases.get(job.id);
            if(lease==null||releaseStoppedWorker(job,lease))deferredWorkerReleaseIds.remove(job.id);
            else deferredWorkerReleases.add(job);
        }
        return attempted;
    }
    private boolean releaseStoppedWorker(Job job,AdmissionLease lease) {
        try {if((job.processFence!=null&&!job.processFence.observedStopped())||(job.process!=null&&job.process.isAlive()))return false;}
        catch(RuntimeException unavailable){return false;}
        if(job.processFence!=null&&!job.processFence.verified()) {
            // Reclaim execution capacity only after known processes exit and a durable
            // quarantine independently prohibits publication AND retention across restart.
            try {store.quarantineWorker(job.id);}
            catch(Exception unavailable){return false;}
        }
        if(job.workerTerminatedNanos==0)job.workerTerminatedNanos=System.nanoTime();
        job.process=null;
        if(!closing)try{store.update(job);}catch(Exception ignored){}
        leases.remove(job.id,lease);lease.release();
        if(job.terminal()){jobs.remove(job.id,job);scheduleArtifactArchive(job.id);}
        return true;
    }
    private List<String> workerClasspath() {
        return WorkerRuntimeClasspath.entries(workerLibs,config.workerLibraryDirs);
    }
    private ProcessBuilder workerBuilder(boolean prewarm,String packageId) {
        List<String> command=new ArrayList<>();command.add(config.javaExecutable.toString());
        if(config.workerHeapInitialMb>0)command.add("-Xms"+config.workerHeapInitialMb+"m");
        if(config.workerHeapMaxMb>0)command.add("-Xmx"+config.workerHeapMaxMb+"m");
        command.add("-cp");command.add(String.join(java.io.File.pathSeparator,workerClasspath()));command.add("att.worker.WorkerMain");
        if(prewarm)command.add("--prewarmed-one-shot");
        return new ProcessBuilder(command).directory(packageRoot(packageId).toFile());
    }
    private boolean environmentFitsPrewarm() {
        // JVM bootstrap options cannot be applied after READY. Keep those
        // deployments on the existing cold path with their original semantics.
        for(String key:List.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS","JAVA_FONTS","FONTCONFIG_FILE","FONTCONFIG_PATH"))
            if(System.getenv(key)!=null&&!System.getenv(key).isBlank())return false;
        long chars=0;
        if(System.getenv().size()>4096)return false;
        for(var entry:System.getenv().entrySet())chars+=entry.getKey().length()+(long)entry.getValue().length();
        return chars<=131072;
    }
    String prewarmRevision(Job job) throws InterruptedException {
        if(!config.executionPool.enabled||!config.executionPool.packageIds.contains(job.packageId)
                ||!("run".equals(job.command)||"debug".equals(job.command)))return null;
        if(!environmentFitsPrewarm()) {job.prewarmFallbackReason="startup_environment";return null;}
        if(!config.workerLibraryDirs.isEmpty()) {job.prewarmFallbackReason="addon_libraries";return null;}
        if(!config.inspection.enabled) {job.prewarmFallbackReason="inspection_disabled";return null;}
        if(!executionWorkers.hasReadyCapacity(job.packageId)) {job.prewarmFallbackReason="no_ready_worker";return null;}
        long before=System.nanoTime();
        try {
            WorkerRequest request=inspectionRequest(job.packageId,packageRoot(job.packageId),job.request.environment);
            request.inspectionAction="prewarm-eligibility";
            Map<String,Object> eligibility=executeInspection(packageRoot(job.packageId),request);
            if(!Boolean.TRUE.equals(eligibility.get("eligible"))) {job.prewarmFallbackReason="native_resource_environment";return null;}
            Object raw=eligibility.get("revisionDigest");
            if(!(raw instanceof String revision)||!revision.matches("[a-f0-9]{64}")) {job.prewarmFallbackReason="unverified_revision";return null;}
            if(job.request.expectedRevisionDigest!=null&&!job.request.expectedRevisionDigest.equals(revision)) {
                job.prewarmFallbackReason="revision_changed";return null;
            }
            return revision;
        } catch(InterruptedException interrupted) {throw interrupted;}
        catch(Exception unavailable) {job.prewarmFallbackReason="inspection_unavailable";return null;}
        finally {job.prewarmEligibilityMs=(System.nanoTime()-before)/1_000_000.0;}
    }
    private void stopWorkerTree(Job job) {
        Process process=job.process;
        if(process==null)return;
        WorkerProcessFence fence=job.processFence;
        if(fence==null){fence=new WorkerProcessFence(process);job.processFence=fence;}
        fence.observe();
        if(!closing)rememberWorkerProcesses(job,fence);
        try {fence.stop(config.gracefulStopMs);}
        catch(InterruptedException interrupted){fence.force();Thread.currentThread().interrupt();}
        finally {if(!closing)rememberWorkerProcesses(job,fence);}
    }
    private void rememberWorkerProcesses(Job job,WorkerProcessFence fence) {
        try {store.rememberWorkerProcesses(job.id,fence.identities());if(!fence.verified())store.quarantineWorker(job.id);}
        catch(Exception unavailable){fence.unverified();}
    }
    void handleWorkerEvent(Job job,String line) throws Exception {
        JsonNode event=JSON.readTree(line);if(event==null||!event.isObject())return;
        String type=event.path("type").asText("").toUpperCase();Map<String,Object> data=JSON.convertValue(event,Map.class);data.remove("type");data.remove("jobId");Map<String,Object> safe=att.server.api.internal.DiagnosticSanitizer.sanitize(data);
        long eventNanos=System.nanoTime();
        if("STATUS".equals(type)&&job.workerReadyNanos==0)job.workerReadyNanos=eventNanos;
        if(("PROGRESS".equals(type)||"LOG".equals(type))&&job.executionReadyNanos==0)job.executionReadyNanos=eventNanos;
        switch(type){case "STATUS"->{String status=event.path("status").asText("");if("RUNNING".equals(status)&&"PREPARING".equals(job.status))transition(job,"RUNNING");}
            case "PROGRESS"->{Object progress=safe.get("loadProgress");if("load".equals(job.command)&&progress instanceof Map) {
                    Map<String,Object> sanitized=sanitizeLoadProgress((Map<?,?>)progress);
                    Map<String,Object> recorded=sanitized==null?null:recordLoadProgress(job,sanitized);
                    if(recorded==null)safe.remove("loadProgress");else safe.put("loadProgress",recorded);
                } else safe.remove("loadProgress");append(job,"progress",safe);}
            case "LOG"->append(job,"log",safe);case "DIAGNOSTIC"->{
                String eventDiagnostic=JSON.writeValueAsString(safe);
                if(hasClassifiedCause(eventDiagnostic)) {
                    if(job.primaryDiagnosticJson==null)job.primaryDiagnosticJson=eventDiagnostic;
                    if(job.primaryDiagnosticJson.equals(eventDiagnostic))job.diagnosticJson=eventDiagnostic;
                    else addSecondaryDiagnostic(job,safe);
                } else if(job.primaryDiagnosticJson!=null||job.protocolDiagnostic!=null) addSecondaryDiagnostic(job,safe);
                else job.diagnosticJson=eventDiagnostic;
                store.update(job);append(job,"diagnostic",safe);
            }
            case "RESULT"->{
                if(job.processFence!=null){job.processFence.observe();rememberWorkerProcesses(job,job.processFence);}
                job.resultReceivedNanos=eventNanos;
                Object workerMetrics=safe.get("workerMetrics");
                if(workerMetrics instanceof Map<?,?>)job.workerMetrics=new LinkedHashMap<>((Map<String,Object>)workerMetrics);
                if(safe.get("loadResultTransport") instanceof Map<?,?> transport) {
                    Map<String,Object> current=job.workerMetrics==null?new LinkedHashMap<>():new LinkedHashMap<>(job.workerMetrics);
                    current.put("loadResultTransport",new LinkedHashMap<>((Map<String,Object>)transport));
                    job.workerMetrics=current;
                }
                String status=event.path("status").asText("ERROR");
                int code=event.path("exitCode").asInt(3);
                if(job.cancelRequested){status="CANCELLED";code=143;}
                Object result=safe.get("result");
                if("load".equals(job.command)) {
                    Map<String,Object> original=result instanceof Map<?,?>?(Map<String,Object>)result:new LinkedHashMap<>();
                    original=loadResultWithProtocolWarning(original,job);
                    Map<String,Object> enriched=new LinkedHashMap<>(original);
                    if(job.request.configurationProvenance!=null) {
                        Map<String,Object> summary=enriched.get("summary") instanceof Map<?,?> existing
                                ?new LinkedHashMap<>((Map<String,Object>)existing):new LinkedHashMap<>();
                        summary.put("configuration",job.request.configurationProvenance);enriched.put("summary",summary);
                    }
                    result=att.server.api.LoadTerminalResult.projectOperationResult(enriched,status,code);
                    attachJobDiagnostics((Map<String,Object>)result,job);
                } else if(result instanceof Map<?,?> original&&job.request.configurationProvenance!=null) {
                    Map<String,Object> enriched=new LinkedHashMap<>((Map<String,Object>)original);
                    Map<String,Object> summary=enriched.get("summary") instanceof Map<?,?> existing?new LinkedHashMap<>((Map<String,Object>)existing):new LinkedHashMap<>();
                    summary.put("configuration",job.request.configurationProvenance);enriched.put("summary",summary);result=enriched;
                }
                job.resultJson=result==null?"{}":JSON.writeValueAsString(result);
                job.resultReceived=true;
                Object console=safe.get("console");
                finish(job,normalizeTerminal(status),code,console instanceof String consoleLine?consoleLine:null);
            }
            default->append(job,"log",Map.of("message","Worker emitted an unrecognized event"));}
    }
    void recordProtocolLimit(Job job,att.server.api.internal.WorkerProtocol.Frame frame) throws Exception {
        Map<String,Object> diagnostic=new LinkedHashMap<>(att.server.api.LimitDiagnostic.create(
                "ATT-SERVER-WORKER-EVENT-TOO-LARGE","Worker event exceeded the UTF-8 protocol byte limit",
                "worker.protocol.maxFrameBytes",frame.limitBytes,frame.sizeBytes,"worker.transport",
                att.server.api.LimitOutcome.DATA_TRUNCATED,"transport",
                "Reduce progress payloads or keep terminal results within the shared frame budget.",null));
        diagnostic.put("eventType",frame.eventType());diagnostic.put("sizeBytes",frame.sizeBytes);diagnostic.put("limitBytes",frame.limitBytes);
        job.protocolDiagnostic=diagnostic;
        if(!hasPrimaryForJob(job))job.diagnosticJson=JSON.writeValueAsString(diagnostic);
        store.update(job);append(job,"diagnostic",diagnostic);
    }
    void recordWorkerExit(Job job,int exit) throws Exception {
        Map<String,Object> fallback=new LinkedHashMap<>(att.server.api.LimitDiagnostic.create(
                "ATT-SERVER-WORKER-EXITED","Worker exited before delivering a terminal result",
                "worker.processTermination",null,exit,"worker.process",att.server.api.LimitOutcome.EXECUTION_ERROR,
                "execution","Review bounded worker diagnostics and heap pressure before changing process limits.",null));
        fallback.put("exitCode",exit);
        Map<String,Object> diagnostic=new LinkedHashMap<>(job.protocolDiagnostic==null?fallback:job.protocolDiagnostic);
        if(job.protocolDiagnostic!=null)diagnostic.put("workerExit",fallback);
        if(hasPrimaryForJob(job))append(job,"diagnostic",diagnostic);
        else {job.diagnosticJson=JSON.writeValueAsString(diagnostic);append(job,"diagnostic",diagnostic);}
    }
    private boolean hasPrimaryForJob(Job job) {
        return job.primaryDiagnosticJson!=null||(job.protocolDiagnostic==null&&hasPrimaryDiagnostic(job.diagnosticJson));
    }
    private void addSecondaryDiagnostic(Job job,Map<String,Object> diagnostic) {
        if(job.secondaryDiagnostics.size()>=4)return;
        Object code=diagnostic.get("code");
        if(code instanceof String)for(Map<String,Object> existing:job.secondaryDiagnostics)if(code.equals(existing.get("code")))return;
        job.secondaryDiagnostics.add(new LinkedHashMap<>(diagnostic));
    }
    private void attachJobDiagnostics(Map<String,Object> terminal,Job job) throws Exception {
        Object rawSummary=terminal.get("summary");
        if(!(rawSummary instanceof Map<?,?>))return;
        @SuppressWarnings("unchecked") Map<String,Object> summary=(Map<String,Object>)rawSummary;
        Object rawDiagnostics=summary.get("diagnostics");
        Map<String,Object> diagnostics=rawDiagnostics instanceof Map<?,?>
                ?new LinkedHashMap<>((Map<String,Object>)rawDiagnostics):new LinkedHashMap<String,Object>();
        if(job.primaryDiagnosticJson!=null)diagnostics.put("primaryError",JSON.readValue(job.primaryDiagnosticJson,Map.class));
        List<Map<String,Object>> notices=new ArrayList<>();
        Object previous=diagnostics.get("secondaryNotices");
        if(previous instanceof Iterable<?>)for(Object item:(Iterable<?>)previous)
            if(item instanceof Map<?,?>&&notices.size()<2)notices.add(new LinkedHashMap<>((Map<String,Object>)item));
        for(Map<String,Object> secondary:job.secondaryDiagnostics) {
            if(notices.size()>=2)break;
            Object code=secondary.get("code");boolean duplicate=false;
            if(code instanceof String)for(Map<String,Object> notice:notices)if(code.equals(notice.get("code")))duplicate=true;
            if(!duplicate)notices.add(new LinkedHashMap<>(secondary));
        }
        if(!notices.isEmpty())diagnostics.put("secondaryNotices",notices);
        summary.put("diagnostics",diagnostics);
    }
    private boolean hasPrimaryDiagnostic(String diagnosticJson) {
        if(diagnosticJson==null)return false;
        try {
            JsonNode value=JSON.readTree(diagnosticJson);String code=value.path("code").asText("");
            return !code.isBlank()&&!code.startsWith("ATT-SERVER-WORKER-EVENT-TOO-LARGE")
                    &&!code.startsWith("ATT-WORKER-EVENT-TRUNCATED")&&!code.startsWith("ATT-JOURNAL-EVENT-TRUNCATED");
        } catch(Exception invalid){return false;}
    }
    private boolean hasClassifiedCause(String diagnosticJson) {
        if(!hasPrimaryDiagnostic(diagnosticJson))return false;
        try {
            JsonNode value=JSON.readTree(diagnosticJson);
            String outcome=value.path("outcome").asText("");
            return att.server.api.LimitOutcome.EXECUTION_ERROR.name().equals(outcome)
                    ||att.server.api.LimitOutcome.SUBMISSION_REJECTED.name().equals(outcome);
        } catch(Exception invalid){return false;}
    }
    private Map<String,Object> loadResultWithProtocolWarning(Map<String,Object> original,Job job) throws Exception {
        if(job.protocolDiagnostic==null&&job.journalDiagnostic==null&&job.primaryDiagnosticJson==null&&job.secondaryDiagnostics.isEmpty())return original;
        Map<String,Object> result=new LinkedHashMap<String,Object>(original);
        List<Map<String,Object>> diagnostics=new ArrayList<Map<String,Object>>();
        Object existing=result.get("diagnostics");
        if(existing instanceof Iterable<?>)for(Object item:(Iterable<?>)existing)if(item instanceof Map<?,?>&&diagnostics.size()<4)
            diagnostics.add(new LinkedHashMap<String,Object>((Map<String,Object>)item));
        if(hasPrimaryForJob(job)) {
            String primary=job.primaryDiagnosticJson==null?job.diagnosticJson:job.primaryDiagnosticJson;
            Map<?,?> current=JSON.readValue(primary,Map.class);
            String code=String.valueOf(current.get("code"));
            boolean present=false;for(Map<String,Object> item:diagnostics)if(code.equals(item.get("code")))present=true;
            if(!present&&diagnostics.size()<4)diagnostics.add(new LinkedHashMap<String,Object>((Map<String,Object>)current));
        }
        for(Map<String,Object> secondary:job.secondaryDiagnostics)if(diagnostics.size()<4)diagnostics.add(new LinkedHashMap<>(secondary));
        boolean workerNotice=false;
        for(Map<String,Object> item:diagnostics)if("ATT-WORKER-EVENT-TRUNCATED".equals(item.get("code")))workerNotice=true;
        if(job.protocolDiagnostic!=null&&!workerNotice&&diagnostics.size()<4) {
            Number limit=job.protocolDiagnostic.get("limitBytes") instanceof Number?(Number)job.protocolDiagnostic.get("limitBytes"):null;
            Number observed=job.protocolDiagnostic.get("sizeBytes") instanceof Number?(Number)job.protocolDiagnostic.get("sizeBytes"):null;
            String eventType=job.protocolDiagnostic.get("eventType") instanceof String?(String)job.protocolDiagnostic.get("eventType"):"RESULT";
            Map<String,Object> notice=att.server.api.LimitDiagnostic.create("ATT-WORKER-EVENT-TRUNCATED",
                    "Large Worker event details were omitted from the protocol frame","worker.protocol.maxFrameBytes",limit,observed,
                    "worker.protocol",att.server.api.LimitOutcome.DATA_TRUNCATED,"transport",
                    "Inspect the retained report artifact for bounded details",null);
            notice.put("eventType",eventType);diagnostics.add(notice);
        }
        boolean journalNotice=false;
        for(Map<String,Object> item:diagnostics)if("ATT-JOURNAL-EVENT-TRUNCATED".equals(item.get("code")))journalNotice=true;
        if(job.journalDiagnostic!=null&&!journalNotice&&diagnostics.size()<4)
            diagnostics.add(new LinkedHashMap<String,Object>(job.journalDiagnostic));
        if(!diagnostics.isEmpty())result.put("diagnostics",diagnostics);
        return result;
    }
    private String normalizeTerminal(String s){return List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(s)?s:"ERROR";}
    String cancel(String id,String principal) throws Exception {
        if("local".equals(config.authMode))requireJobOwner(id,principal);
        Job j=jobs.get(id);if(j==null){Map<String,Object> stored=store.get(id);if(stored==null||!JOB_ID.matcher(id).matches())throw new NotFoundException();return String.valueOf(stored.get("status"));}
        boolean stopProcess=false;OutputStream controlStream=null;
        synchronized(j){if(j.terminal())return j.status;append(j,"status",Map.of("jobId",id,"status","CANCEL_REQUESTED"));
            Process p=j.process;if(p==null){FutureTask<Void> task=tasks.remove(id);if(task!=null){boolean cancelled=task.cancel(false);workers.remove(task);if(cancelled){AdmissionLease lease=leases.remove(id);if(lease!=null)lease.release();}}finish(j,"CANCELLED",143);jobs.remove(id,j);scheduleArtifactArchive(id);}
            else {j.cancelRequested=true;controlStream=j.workerControlStream;stopProcess=true;}}
        // Never hold the Job monitor while waiting for the Worker to exit. The
        // protocol reader needs that same monitor to accept the final live LOAD
        // snapshot and RESULT during graceful cancellation.
        if(stopProcess) {
            boolean workerStopped=false;
            if("load".equals(j.command)) {
                long readyDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(controlStream==null&&System.nanoTime()<readyDeadline) {
                    if(j.process==null||!j.process.isAlive())break;
                    Thread.sleep(5L);controlStream=j.workerControlStream;
                }
            }
            if("load".equals(j.command)&&controlStream!=null) {
                try {controlStream.write(JSON.writeValueAsBytes(Map.of("type","CANCEL","jobId",id)));controlStream.write('\n');controlStream.flush();}
                catch(Exception unavailable) { /* Fall back to process termination below. */ }
                try {
                    WorkerProcessFence fence=j.processFence;
                    if(fence!=null)workerStopped=fence.awaitStopped(config.gracefulStopMs);
                    else {Process process=j.process;workerStopped=process==null||process.waitFor(config.gracefulStopMs,TimeUnit.MILLISECONDS);}
                } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
            }
            if(!workerStopped)stopWorkerTree(j);
            // Let the protocol reader consume any RESULT frame already written
            // before the Worker exited. It owns terminal persistence for an
            // active process; this fallback is only for a Worker that closed
            // without a result.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(!j.terminal()&&System.nanoTime()<deadline)Thread.sleep(5L);
            synchronized(j){if(!j.terminal())finish(j,"CANCELLED",143);}
        }
        store.audit(principal,"CANCEL",id,j.packageId,"CANCELLED");return j.status;
    }
    Map<String,Object> pinJob(String id,boolean pinned,String actor) throws Exception {return pinJob(id,pinned,null,actor);}
    Map<String,Object> pinJob(String id,boolean pinned,String reason,String actor) throws Exception {
        if(id==null||!JOB_ID.matcher(id).matches())throw new NotFoundException();
        if("local".equals(config.authMode)&&!store.isActiveUser(actor))throw new NotFoundException();
        String safeReason=pinReason(pinned?reason:null);
        if(!store.setPinned(id,pinned,actor,safeReason)){
            if(!store.jobRowExists(id))throw new NotFoundException();
            store.audit(actor,"JOB_PIN",id,null,"REJECTED");
            throw new JobConflictException("ATT-SERVER-JOB-NOT-ELIGIBLE","Only terminal RUN/Debug/LOAD Jobs not being deleted can be pinned");
        }
        return jobRecord(id);
    }
    List<Map<String,Object>> pinJobs(List<String> ids,boolean pinned,String reason,String actor) throws Exception {
        if(ids==null||ids.isEmpty()||ids.size()>100)throw new IllegalArgumentException("Select 1 to 100 Job IDs");
        for(String id:ids)if(id==null||!JOB_ID.matcher(id).matches())throw new IllegalArgumentException("Invalid Job ID");
        String safeReason=pinReason(pinned?reason:null);List<Map<String,Object>> outcomes=new ArrayList<>();
        for(String id:new java.util.LinkedHashSet<>(ids)) {
            try {
                if("local".equals(config.authMode)&&!store.isActiveUser(actor))throw new NotFoundException();
                String outcome=store.setPinnedOutcome(id,pinned,actor,safeReason);
                if("NOT_ELIGIBLE".equals(outcome)&&!store.jobRowExists(id))outcome="UNAVAILABLE";
                if(List.of("NOT_ELIGIBLE","UNAVAILABLE").contains(outcome))store.audit(actor,"JOB_PIN",id,null,outcome);
                outcomes.add(Map.of("jobId",id,"outcome",outcome));
            }
            catch(NotFoundException missing){outcomes.add(Map.of("jobId",id,"outcome","UNAVAILABLE"));}
            catch(Exception failed){store.audit(actor,"JOB_PIN",id,null,"FAILED");outcomes.add(Map.of("jobId",id,"outcome","FAILED"));}
        }
        return outcomes;
    }
    private static String pinReason(String reason) {
        if(reason==null)return null;String trimmed=reason.trim();if(trimmed.isEmpty())return null;
        if(trimmed.length()>200)throw new IllegalArgumentException("pinReason must be at most 200 characters");
        for(int i=0;i<trimmed.length();i++)if(Character.isISOControl(trimmed.charAt(i)))throw new IllegalArgumentException("pinReason must not contain control characters");
        String safe=att.security.SecretProtection.text(trimmed);
        if(safe.length()>200)throw new IllegalArgumentException("pinReason must be at most 200 characters after secret redaction");
        return safe;
    }
    List<Map<String,Object>> deleteJobs(List<String> ids,String actor) throws Exception {
        if(ids==null||ids.isEmpty()||ids.size()>100)throw new IllegalArgumentException("Select 1 to 100 Job IDs");
        for(String id:ids)if(id==null||!JOB_ID.matcher(id).matches())throw new IllegalArgumentException("Invalid Job ID");
        List<Map<String,Object>> results=new ArrayList<>();
        for(String id:new java.util.LinkedHashSet<>(ids)) {
            try {results.add(deleteJob(id,actor));}
            catch(NotFoundException unavailable){results.add(deletionOutcome(id,actor,"UNAVAILABLE"));}
        }
        return results;
    }
    private Map<String,Object> deletionOutcome(String id,String actor,String outcome) throws Exception {
        store.audit(actor,"JOB_DELETE",id,null,outcome);
        return Map.of("jobId",id,"outcome",outcome);
    }
    Map<String,Object> deleteJob(String id,String actor) throws Exception {
        return setDeletionRequested(id,true,actor);
    }
    List<Map<String,Object>> setDeletionRequested(List<String> ids,boolean requested,String actor) throws Exception {
        if(ids==null||ids.isEmpty()||ids.size()>100)throw new IllegalArgumentException("Provide 1 to 100 Job IDs");
        for(String id:ids)if(id==null||!JOB_ID.matcher(id).matches())throw new IllegalArgumentException("Invalid Job ID");
        List<Map<String,Object>> outcomes=new ArrayList<>();
        for(String id:new java.util.LinkedHashSet<>(ids)) {
            try {outcomes.add(setDeletionRequested(id,requested,actor));}
            catch(NotFoundException unavailable){outcomes.add(deletionOutcome(id,actor,"UNAVAILABLE"));}
            catch(Exception failed){outcomes.add(deletionOutcome(id,actor,"FAILED"));}
        }return outcomes;
    }
    Map<String,Object> setDeletionRequested(String id,boolean requested,String actor) throws Exception {
        if(id==null||!JOB_ID.matcher(id).matches())throw new IllegalArgumentException("Invalid Job ID");
        if("local".equals(config.authMode)&&!store.isActiveUser(actor))throw new NotFoundException();
        if(!artifactLifecycle.writeLock().tryLock())return deletionOutcome(id,actor,"ARCHIVE_BUSY");
        try {
            if(requested&&(jobs.containsKey(id)||pendingArtifactJobs.contains(id)||store.workerMayBeAlive(id)))return deletionOutcome(id,actor,"JOB_BUSY");
            String outcome=store.setDeletionRequested(id,requested,actor,System.currentTimeMillis());
            Map<String,Object> response=new LinkedHashMap<>();response.put("jobId",id);response.put("outcome",outcome);
            Map<String,Object> current=store.get(id);response.put("deletionState",current==null?null:current.get("deletionState"));
            return response;
        } finally {artifactLifecycle.writeLock().unlock();}
    }
    /** Five-second bounded cadence, with at least thirty seconds to cancel a pending mark. */
    void cleanupRequestedDeletions() throws Exception {
        long before=System.currentTimeMillis()-30_000;
        for(String id:store.requestedDeletionIds(before)) {
            if(!artifactLifecycle.writeLock().tryLock())break;
            try {
                if(jobs.containsKey(id)||pendingArtifactJobs.contains(id)||store.workerMayBeAlive(id))continue;
                store.claimRequestedDeletion(id,before);
            } finally {artifactLifecycle.writeLock().unlock();}
        }
        reconcileDeletingJobs();
    }
    private void cleanupRequestedDeletionsSafely() {
        try {cleanupRequestedDeletions();}
        catch(Exception failed){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to clean pending deletions: "+safeMessage(failed));}
    }

    void requireJobOwner(String id,String principal) throws Exception {
        if(!JOB_ID.matcher(id).matches())throw new NotFoundException();
        if(store.get(id)==null)throw new NotFoundException();
        if("local".equals(config.authMode)&&!store.isActiveUser(principal))throw new NotFoundException();
    }
    List<Map<String,Object>> jobsFor(String principal,String search) throws Exception {
        return jobsFor(principal,search,false);
    }
    List<Map<String,Object>> jobsFor(String principal,String search,boolean pinnedOnly) throws Exception {
        return store.list(100,search,pinnedOnly);
    }
    List<Map<String,Object>> loadJobsFor(String principal,int offset,int limit) throws Exception {
        return loadJobsFor(principal,offset,limit,null,false);
    }
    List<Map<String,Object>> loadJobsFor(String principal,int offset,int limit,String search,boolean pinnedOnly) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(offset<0||offset>1000000||limit<1||limit>100)throw new IllegalArgumentException("Load job pagination is invalid");
        int fetch=limit==Integer.MAX_VALUE?limit:limit+1;
        if("local".equals(config.authMode)&&!store.isActiveUser(principal))throw new NotFoundException();
        return store.listLoad(fetch,offset,search,pinnedOnly);
    }
    Job job(String id){if(!JOB_ID.matcher(id).matches())throw new NotFoundException();Job j=jobs.get(id);if(j==null)throw new NotFoundException();return j;}
    Map<String,Object> jobRecord(String id) throws Exception {
        if(!JOB_ID.matcher(id).matches())throw new NotFoundException();
        Job active=jobs.get(id);
        if(active!=null) {
            // Terminal status must not become visible before finish() has persisted
            // the result, diagnostic, and final event. All of these share the Job monitor.
            synchronized(active) {
                Map<String,Object> view=active.view();
                view.put("pinned",false);
                // Only terminal jobs can be pinned. Keep active polling independent
                // of database connection/flush costs.
                if(active.terminal()) {
                    Map<String,Object> persisted=store.get(id);
                    if(persisted!=null)for(String key:List.of("pinned","pinnedAt","pinnedBy","pinReason"))view.put(key,persisted.get(key));
                }
                return view;
            }
        }
        Map<String,Object> record=store.get(id);if(record==null)throw new NotFoundException();
        Map<String,String> replayMetadata=store.executionSpec(id);
        if(replayMetadata!=null&&replayMetadata.get("rerunOfJobId")!=null)record.put("rerunOfJobId",replayMetadata.get("rerunOfJobId"));
        record.put("executionType",Job.executionType(String.valueOf(record.get("command"))));
        record.remove("resultJson");record.remove("diagnosticJson");
        String performance=(String)record.remove("performanceJson");
        if(performance!=null&&!performance.isBlank())record.put("performance",JSON.readValue(performance,Map.class));
        record.put("archiveMetrics",store.archiveMetrics(id));
        return record;
    }

    private static Map<String,Object> sanitizeLoadProgress(Map<?,?> raw) {
        if(!"att-load-live-progress/v1".equals(raw.get("schemaVersion")))return null;
        long elapsed=boundedLong(raw.get("elapsedMs"),0L,Long.MAX_VALUE);if(elapsed<0)return null;
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("schemaVersion","att-load-live-progress/v1");
        String runId=safeProgressName(raw.get("runId"));if(!runId.isBlank())snapshot.put("runId",runId);
        String captured=raw.get("capturedAt") instanceof String?(String)raw.get("capturedAt"):null;
        if(captured==null||captured.length()>40||!validTimestamp(captured))return null;
        snapshot.put("capturedAt",captured);snapshot.put("elapsedMs",elapsed);
        String phase=raw.get("phase") instanceof String?((String)raw.get("phase")).toUpperCase(java.util.Locale.ROOT):"UNKNOWN";
        if(!Set.of("PREPARING","RAMP_UP","WARMUP","STEADY","RAMP_DOWN","DRAIN","COMPLETE","UNKNOWN","PROGRESS").contains(phase))phase="UNKNOWN";
        snapshot.put("phase",phase);
        Map<String,Object> counts=progressCounts(raw.get("counts"));if(counts==null)return null;snapshot.put("counts",counts);
        Map<String,Object> measured=progressCounts(raw.get("measuredCounts"));if(measured==null)return null;snapshot.put("measuredCounts",measured);
        Map<String,Object> rates=progressNumbers(raw.get("rates"),Set.of("completedTps","successfulTps"));if(rates==null)return null;snapshot.put("rates",rates);
        Map<String,Object> latency=progressLatency(raw.get("latency"));if(latency==null)return null;snapshot.put("latency",latency);
        if(raw.get("latestFailure") instanceof Map<?,?>) {
            Map<?,?> failure=(Map<?,?>)raw.get("latestFailure");Object status=failure.get("status"),time=failure.get("capturedAt"),code=failure.get("code");
            if(status instanceof String && Set.of("DROPPED","FAIL","ERROR","INVALID","SKIPPED").contains(status) && time instanceof String && ((String)time).length()<=40 && validTimestamp((String)time)) {
                Map<String,Object> projected=new LinkedHashMap<>();projected.put("status",status);projected.put("capturedAt",time);
                if(code instanceof String && ((String)code).matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"))projected.put("code",code);
                snapshot.put("latestFailure",projected);
            }
        }
        Map<String,Object> checkpoint=progressCheckpoint(raw.get("checkpointInterval"));
        if(raw.containsKey("checkpointInterval") && checkpoint==null)checkpoint=Map.of("schemaVersion","att-load-checkpoint-interval/v1","state","DEGRADED");
        if(checkpoint!=null)snapshot.put("checkpointInterval",checkpoint);
        Map<String,Object> phases=new LinkedHashMap<>();Object rawPhases=raw.get("phases");
        if(rawPhases instanceof Map<?,?>) {
            Map<?,?> source=(Map<?,?>)rawPhases;
            for(String name:List.of("RAMP_UP","WARMUP","STEADY","RAMP_DOWN","DRAIN","COMPLETE","PREPARING","UNKNOWN")) {
                Map<String,Object> value=progressCounts(source.get(name));if(value!=null)phases.put(name,value);
            }
        }
        snapshot.put("phases",phases);
        Map<String,Object> workloads=new TreeMap<>();Object rawWorkloads=raw.get("workloads");
        if(rawWorkloads instanceof Map<?,?>)for(Map.Entry<?,?> entry:((Map<?,?>)rawWorkloads).entrySet()) {
            if(workloads.size()>=32)break;String name=safeProgressName(entry.getKey());if(name.isBlank())continue;
            Map<String,Object> value=progressCounts(entry.getValue());if(value!=null) {
                if(entry.getValue() instanceof Map<?,?>) {
                    Map<String,Object> workloadLatency=progressLatency(((Map<?,?>)entry.getValue()).get("latency"));
                    if(workloadLatency!=null)value.put("latency",workloadLatency);
                }
                workloads.put(name,value);
            }
        }
        snapshot.put("workloads",workloads);snapshot.put("workloadsTruncated",Boolean.TRUE.equals(raw.get("workloadsTruncated"))||workloads.size()<sizeOf(raw.get("workloads")));
        snapshot.put("serverReceivedAt",Instant.now().toString());
        return snapshot;
    }

    private static Map<String,Object> progressCounts(Object raw) {
        if(!(raw instanceof Map<?,?>))return null;Map<String,Object> result=new LinkedHashMap<>();Map<?,?> source=(Map<?,?>)raw;
        for(String key:List.of("scheduled","started","completed","passed","sutFailures","runtimeErrors","dropped","other","active","queued")) {
            Object value=source.get(key);if(value==null)continue;long count=boundedLong(value,0L,Long.MAX_VALUE);if(count<0)return null;result.put(key,count);
        }
        for(String key:List.of("schedulerLagMeanMs"))if(source.containsKey(key)) {
            Object value=source.get(key);if(value==null){result.put(key,null);continue;}double number=boundedDouble(value);if(!Double.isFinite(number)||number<0)return null;result.put(key,number);
        }
        return result;
    }

    private static Map<String,Object> progressCheckpoint(Object raw) {
        if(!(raw instanceof Map<?,?>))return null;Map<?,?> source=(Map<?,?>)raw;
        if(!"att-load-checkpoint-interval/v1".equals(source.get("schemaVersion")))return null;
        Object state=source.get("state");if(!(state instanceof String)||!Set.of("WAITING","DEGRADED","VERIFIED").contains(state))return null;
        Map<String,Object> value=new LinkedHashMap<>();value.put("schemaVersion","att-load-checkpoint-interval/v1");value.put("state",state);
        if(!"VERIFIED".equals(state))return value;
        long start=boundedLong(source.get("startSequence"),1L,Long.MAX_VALUE-1L),end=boundedLong(source.get("endSequence"),2L,Long.MAX_VALUE);
        long duration=boundedLong(source.get("durationMs"),1L,TimeUnit.DAYS.toMillis(11));
        if(start<1L||end!=start+1L||duration<1L||!(source.get("startAt") instanceof String)||!(source.get("endAt") instanceof String))return null;
        String startAt=(String)source.get("startAt"),endAt=(String)source.get("endAt");
        if(startAt.length()>40||endAt.length()>40||!validTimestamp(startAt)||!validTimestamp(endAt))return null;
        try {if(java.time.Duration.between(Instant.parse(startAt),Instant.parse(endAt)).toMillis()!=duration)return null;}catch(Exception invalid){return null;}
        if(!(source.get("counts") instanceof Map<?,?>))return null;Map<?,?> rawCounts=(Map<?,?>)source.get("counts");Map<String,Object> counts=new LinkedHashMap<>();
        for(String key:List.of("scheduled","started","completed","success","failure","runtimeError","otherOutcome","dropped","measuredScheduled","measuredStarted","measuredCompleted","measuredSuccess","measuredFailure","measuredRuntimeError","measuredOtherOutcome","measuredDropped")) {
            long count=boundedLong(rawCounts.get(key),0L,Long.MAX_VALUE);if(count<0L)return null;counts.put(key,count);
        }
        value.put("startSequence",start);value.put("endSequence",end);value.put("startAt",startAt);value.put("endAt",endAt);value.put("durationMs",duration);value.put("counts",counts);
        if(source.containsKey("latencySampleCount")) {
            long samples=boundedLong(source.get("latencySampleCount"),0L,Long.MAX_VALUE);if(samples<0L)return null;
            value.put("latencySampleCount",samples);
            if(samples>0L) {double mean=boundedDouble(source.get("latencyMeanMs"));if(!Double.isFinite(mean)||mean<0.0)return null;value.put("latencyMeanMs",mean);}
        }
        return value;
    }

    private static Map<String,Object> progressNumbers(Object raw,Set<String> allow) {
        if(!(raw instanceof Map<?,?>))return null;Map<String,Object> result=new LinkedHashMap<>();
        for(String key:allow) {Object value=((Map<?,?>)raw).get(key);if(value==null)continue;double number=boundedDouble(value);if(!Double.isFinite(number)||number<0)return null;result.put(key,number);}
        return result;
    }

    private static Map<String,Object> progressLatency(Object raw) {
        if(!(raw instanceof Map<?,?>))return null;Map<?,?> source=(Map<?,?>)raw;Map<String,Object> result=new LinkedHashMap<>();
        long samples=boundedLong(source.get("sampleCount"),0L,Long.MAX_VALUE);if(samples<0)return null;result.put("sampleCount",samples);
        for(String key:List.of("meanMs","p95Ms","p99Ms")) {
            Object value=source.get(key);if(value==null){result.put(key,null);continue;}
            double number=boundedDouble(value);if(!Double.isFinite(number)||number<0)return null;result.put(key,number);
        }
        result.put("percentileMethod","bounded log histogram estimate");return result;
    }

    private static String safeProgressName(Object raw) {
        if(!(raw instanceof String))return "";String value=((String)raw).trim();return value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")?value:"";
    }
    private static int sizeOf(Object value){return value instanceof Map<?,?>?((Map<?,?>)value).size():0;}
    private static long boundedLong(Object value,long minimum,long maximum) {
        if(!(value instanceof Number))return -1L;try{long number=new java.math.BigDecimal(String.valueOf(value)).longValueExact();return number<minimum||number>maximum?-1L:number;}catch(NumberFormatException|ArithmeticException invalid){return -1L;}
    }
    private static double boundedDouble(Object value) {if(!(value instanceof Number))return Double.NaN;try{return new java.math.BigDecimal(String.valueOf(value)).doubleValue();}catch(NumberFormatException invalid){return Double.NaN;}}
    private static boolean validTimestamp(String value){try{Instant.parse(value);return true;}catch(Exception invalid){return false;}}

    private Map<String,Object> recordLoadProgress(Job job,Map<String,Object> progress) {
        synchronized(job) {
            if(job.loadProgress!=null) {
                long previous=((Number)job.loadProgress.get("elapsedMs")).longValue();
                long next=((Number)progress.get("elapsedMs")).longValue();if(next<previous)return null;
                if(!monotonicProgressCounts(job.loadProgress.get("counts"),progress.get("counts")) ||
                        !monotonicProgressCounts(job.loadProgress.get("measuredCounts"),progress.get("measuredCounts")))return null;
            }
            Map<String,Object> saved=new LinkedHashMap<>(progress);
            Object incomingCheckpoint=saved.get("checkpointInterval");
            if(incomingCheckpoint instanceof Map<?,?> && "VERIFIED".equals(((Map<?,?>)incomingCheckpoint).get("state")) && !job.loadCheckpointHistory.isEmpty()) {
                long sequence=((Number)((Map<?,?>)incomingCheckpoint).get("endSequence")).longValue();
                long previous=((Number)job.loadCheckpointHistory.get(job.loadCheckpointHistory.size()-1).get("endSequence")).longValue();
                if(sequence<previous)saved.put("checkpointInterval",Map.of("schemaVersion","att-load-checkpoint-interval/v1","state","DEGRADED"));
            }
            job.loadProgress=saved;
            Object checkpoint=saved.get("checkpointInterval");
            if(checkpoint instanceof Map<?,?> && "VERIFIED".equals(((Map<?,?>)checkpoint).get("state"))) {
                @SuppressWarnings("unchecked") Map<String,Object> interval=(Map<String,Object>)checkpoint;
                long sequence=((Number)interval.get("endSequence")).longValue();
                long previous=job.loadCheckpointHistory.isEmpty()?0L:((Number)job.loadCheckpointHistory.get(job.loadCheckpointHistory.size()-1).get("endSequence")).longValue();
                if(sequence>previous) {
                    Map<String,Object> entry=new LinkedHashMap<>(interval);
                    if((previous==0L && sequence>2L)||(previous>0L && sequence>previous+1L))entry.put("coverageGap",true);
                    saved.put("checkpointInterval",entry);
                    job.loadCheckpointHistory.add(entry);if(job.loadCheckpointHistory.size()>64)job.loadCheckpointHistory.remove(0);
                }
            }
            Map<String,Object> point=progressHistoryPoint(saved);
            job.loadProgressHistory.add(point);if(job.loadProgressHistory.size()>60)job.loadProgressHistory.remove(0);
            long elapsed=((Number)saved.get("elapsedMs")).longValue();long planned=plannedLoadDurationMs(job);
            int bucket=(int)Math.max(0L,Math.min(255L,(long)(elapsed*256.0/Math.max(1L,planned))));
            if(bucket%4==0) {
                point=new LinkedHashMap<>(point);point.put("workloads",compactWorkloadTrend(saved.get("workloads")));
            }
            if(bucket==0)job.loadProgressTrend.putIfAbsent(bucket,point);else job.loadProgressTrend.put(bucket,point);
            Map<String,Object> state=new LinkedHashMap<>();state.put("latest",saved);state.put("recent",new ArrayList<>(job.loadProgressHistory));state.put("trend",new ArrayList<>(job.loadProgressTrend.values()));state.put("checkpoints",new ArrayList<>(job.loadCheckpointHistory));
            long now=System.nanoTime();
            boolean retryDue=!job.loadProgressPersistenceDegraded||job.lastLoadProgressPersistenceAttemptNanos==0L
                    ||now-job.lastLoadProgressPersistenceAttemptNanos>=LOAD_PROGRESS_PERSISTENCE_RETRY_NANOS;
            if(retryDue) {
                job.lastLoadProgressPersistenceAttemptNanos=now;
                saved.put("telemetryPersistence",Map.of("state","HEALTHY"));
                try {
                    String candidate=JSON.writeValueAsString(state);
                    loadProgressPersistence.update(job.id,candidate);
                    job.loadProgressPersistenceDegraded=false;
                    job.loadProgressJson=candidate;
                } catch(Exception unavailable) {
                    job.loadProgressPersistenceDegraded=true;
                    saved.put("telemetryPersistence",loadProgressPersistenceHealth(job.lastLoadProgressPersistenceAttemptNanos,now));
                    job.loadProgressJson=serializeLoadProgressState(state);
                }
            } else {
                saved.put("telemetryPersistence",loadProgressPersistenceHealth(job.lastLoadProgressPersistenceAttemptNanos,now));
                job.loadProgressJson=serializeLoadProgressState(state);
            }
            return new LinkedHashMap<>(saved);
        }
    }

    private static Map<String,Object> loadProgressPersistenceHealth(long lastAttemptNanos,long nowNanos) {
        long retryAfterMs=Math.max(0L,TimeUnit.NANOSECONDS.toMillis(LOAD_PROGRESS_PERSISTENCE_RETRY_NANOS-Math.max(0L,nowNanos-lastAttemptNanos)));
        return Map.of("state","DEGRADED","diagnostic","Optional live LOAD telemetry persistence is degraded; the latest in-memory snapshot remains available and retries continue.","retryAfterMs",retryAfterMs);
    }

    private static String serializeLoadProgressState(Map<String,Object> state) {
        try {return JSON.writeValueAsString(state);}
        catch(Exception unavailable) {return null;}
    }

    private static boolean monotonicProgressCounts(Object before,Object after) {
        if(!(before instanceof Map<?,?>)||!(after instanceof Map<?,?>))return false;
        Map<?,?> old=(Map<?,?>)before,current=(Map<?,?>)after;
        for(String key:List.of("scheduled","started","completed","passed","sutFailures","runtimeErrors","dropped","other")) {
            Object left=old.get(key),right=current.get(key);
            if(left instanceof Number && (!(right instanceof Number)||((Number)right).longValue()<((Number)left).longValue()))return false;
        }
        return true;
    }

    private static Map<String,Object> progressHistoryPoint(Map<String,Object> snapshot) {
        Map<String,Object> point=new LinkedHashMap<>();
        for(String key:List.of("capturedAt","serverReceivedAt","elapsedMs","phase","counts","measuredCounts","rates","latency"))
            if(snapshot.containsKey(key))point.put(key,snapshot.get(key));
        return point;
    }

    private static Map<String,Object> compactWorkloadTrend(Object raw) {
        Map<String,Object> output=new TreeMap<>();if(!(raw instanceof Map<?,?>))return output;
        for(Map.Entry<?,?> entry:((Map<?,?>)raw).entrySet()) {
            if(!(entry.getKey() instanceof String)||!(entry.getValue() instanceof Map<?,?>))continue;
            Map<?,?> source=(Map<?,?>)entry.getValue();Map<String,Object> values=new LinkedHashMap<>();
            for(String key:List.of("completed","passed","sutFailures","runtimeErrors","dropped","latency"))if(source.containsKey(key))values.put(key,source.get(key));
            output.put((String)entry.getKey(),values);
        }
        return output;
    }

    private static long plannedLoadDurationMs(Job job) {
        long maximum=0L;Object raw=job.targetIdentity==null?null:job.targetIdentity.get("workloads");
        if(raw instanceof List<?>)for(Object item:(List<?>)raw)if(item instanceof Map<?,?>) {
            Object duration=((Map<?,?>)item).get("totalDurationMs");if(duration instanceof Number)maximum=Math.max(maximum,((Number)duration).longValue());
        }
        return maximum>0?maximum:TimeUnit.DAYS.toMillis(10);
    }
    Map<String,Object> resultRecord(String id) throws Exception {
        if(!JOB_ID.matcher(id).matches())throw new NotFoundException();
        Job active=jobs.get(id);
        if(active!=null) {
            synchronized(active) {
                // Avoid mixing an uncommitted terminal Job view with an older H2 snapshot.
                // Reading while holding the Job lock also waits for a concurrent finish().
                Map<String,Object> record=store.get(id);
                if(record==null)throw new NotFoundException();
                String resultJson=active.resultJson!=null?active.resultJson:(String)record.get("resultJson");
                String diagnosticJson=active.diagnosticJson!=null?active.diagnosticJson:(String)record.get("diagnosticJson");
                Map<String,Object> out=new LinkedHashMap<>();
                out.put("job",active.view());
                out.put("result",resultJson==null?null:publicJobResult(id,active.command,active.status,
                        active.exitCode==null?3:active.exitCode,resultJson));
                out.put("diagnostic",diagnosticJson==null?null:publicJson(id,diagnosticJson));
                return out;
            }
        }
        Map<String,Object> record=store.get(id);if(record==null)throw new NotFoundException();
        Map<String,Object> out=new LinkedHashMap<>();out.put("job",jobRecord(id));
        String resultJson=(String)record.get("resultJson"),diagnosticJson=(String)record.get("diagnosticJson");
        out.put("result",resultJson==null?null:publicJobResult(id,String.valueOf(record.get("command")),String.valueOf(record.get("status")),
                record.get("exitCode") instanceof Number?((Number)record.get("exitCode")).intValue():3,resultJson));
        out.put("diagnostic",diagnosticJson==null?null:publicJson(id,diagnosticJson));
        return out;
    }
    List<Map<String,Object>> packages(String principal) throws Exception {
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map.Entry<String,Path> entry:packageRoots().entrySet())out.add(packageMetadata(entry.getKey(),entry.getValue(),principal));
        return out;
    }
    Map<String,Object> packageView(String id,String principal) throws Exception {
        Path root=packageRoot(id);if(root==null)throw new NotFoundException();return packageMetadata(id,root,principal);
    }
    private Map<String,Object> packageMetadata(String id,Path root,String principal) throws Exception {
        Map<String,Object> item=new LinkedHashMap<>();item.put("packageId",id);item.put("name",root.getFileName().toString());
        item.put("canExecute",isOperationalPrincipal(principal));
        return item;
    }
    private boolean isOperationalPrincipal(String principal) throws Exception {
        if(principal==null||principal.isBlank()||RequestIdentity.ANONYMOUS.equals(principal))return config.authorization.allowAnonymousOperations&&!config.authenticationRequired;
        if("local".equals(config.authMode))return store.isActiveUser(principal);
        return true; // The servlet layer has already verified the configured trusted USER role.
    }
    private Path packageRoot(String id) {
        return config.packages.get(id);
    }
    private Map<String,Path> packageRoots() {
        return config.packages;
    }
    Map<String,Object> inspectResource(String packageId,String action,String type,String resourceId,String query,int requestedLimit,String cursor,String principal) throws Exception {
        return inspectResource(packageId,action,type,resourceId,query,requestedLimit,cursor,"name","asc",null,principal);
    }
    Map<String,Object> inspectResource(String packageId,String action,String type,String resourceId,String query,int requestedLimit,String cursor,String sourceOriginPath,String principal) throws Exception {
        return inspectResource(packageId,action,type,resourceId,query,requestedLimit,cursor,"name","asc",sourceOriginPath,principal);
    }
    Map<String,Object> inspectResource(String packageId,String action,String type,String resourceId,String query,int requestedLimit,String cursor,
                                       String sort,String direction,String sourceOriginPath,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(!List.of("list","detail","source","source-file").contains(action))throw new IllegalArgumentException("Unsupported inspection action");
        if(type!=null&&!List.of("case","template","flow","tool").contains(type))throw new IllegalArgumentException("Unsupported resource type");
        String normalizedSort=sort==null||sort.isBlank()?"name":sort;
        String normalizedDirection=direction==null||direction.isBlank()?"asc":direction;
        if(!List.of("name","type","logicalId","state").contains(normalizedSort))throw new IllegalArgumentException("Unsupported resource sort field");
        if(!List.of("asc","desc").contains(normalizedDirection))throw new IllegalArgumentException("Resource sort direction must be asc or desc");
        if(!"list".equals(action)&&((sort!=null&& !"name".equals(sort))||(direction!=null&& !"asc".equals(direction))))
            throw new IllegalArgumentException("Resource sorting is only valid for list requests");
        if(sourceOriginPath!=null&&(sourceOriginPath.length()>1024||containsControl(sourceOriginPath)))throw new IllegalArgumentException("Source origin is invalid");
        if(sourceOriginPath!=null&& !"source-file".equals(action))throw new IllegalArgumentException("Source origin is only valid for a referenced source file");
        String normalizedQuery=query==null?"":query.trim();if(normalizedQuery.length()>200||containsControl(normalizedQuery))throw new IllegalArgumentException("query must contain at most 200 printable characters");
        int limit=requestedLimit==0?RESOURCE_DEFAULT_PAGE_SIZE:requestedLimit;
        if(limit<1||limit>RESOURCE_MAX_PAGE_SIZE)throw new IllegalArgumentException("limit must be between 1 and 100");
        Cursor prior=cursor==null||cursor.isEmpty()?null:decodeCursor(cursor,packageId,type,normalizedQuery,normalizedSort,normalizedDirection,principal);
        WorkerRequest request=inspectionRequest(packageId,root,null);
        request.inspectionAction=action;request.inspectionType=type;request.inspectionResourceId=resourceId;request.inspectionQuery=normalizedQuery;request.inspectionOffset=prior==null?0:prior.offset;request.inspectionLimit=limit;
        request.inspectionOrigin=sourceOriginPath;
        request.inspectionSort=normalizedSort;request.inspectionDirection=normalizedDirection;
        request.expectedRevisionDigest=prior==null?null:prior.revision;
        Map<String,Object> result=executeInspection(root,request);
        Object revision=result.remove("revisionDigest");
        if(revision instanceof String)result.put("_cacheRevision",inspectionCacheToken(List.of(packageId,principal,revision)));
        if("list".equals(action)) {
            Object next=result.remove("nextOffset");
            if(next instanceof Number&&revision instanceof String)result.put("nextCursor",encodeCursor(packageId,type,normalizedQuery,normalizedSort,normalizedDirection,((Number)next).intValue(),(String)revision,principal));
            else result.put("nextCursor",null);
        }
        return result;
    }
    Map<String,Object> inspectLoadCatalog(String packageId,String query,int requestedLimit,String cursor,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        String normalizedQuery=query==null?"":query.trim();
        if(normalizedQuery.length()>200||containsControl(normalizedQuery))throw new IllegalArgumentException("query must contain at most 200 printable characters");
        int limit=requestedLimit==0?50:requestedLimit;if(limit<1||limit>100)throw new IllegalArgumentException("limit must be between 1 and 100");
        Cursor prior=cursor==null||cursor.isEmpty()?null:decodeCursor(cursor,packageId,"load",normalizedQuery,"name","asc",principal);
        WorkerRequest request=inspectionRequest(packageId,root,null);request.inspectionAction="load-catalog";
        request.inspectionQuery=normalizedQuery;request.inspectionOffset=prior==null?0:prior.offset;request.inspectionLimit=limit;
        request.expectedRevisionDigest=prior==null?null:prior.revision;
        Map<String,Object> result=executeInspection(root,request);
        Object rawItems=result.get("items");
        if(rawItems instanceof List)for(Object rawItem:(List<?>)rawItems)if(rawItem instanceof Map) {
            @SuppressWarnings("unchecked") Map<String,Object> item=(Map<String,Object>)rawItem;
            boolean scenario="scenario".equals(item.get("kind"));
            boolean template="template".equals(item.get("kind"));
            item.put("runNow",scenario);
            item.put("schedule",scenario&&config.scheduling.enabled);
            item.put("createQuickLoad",template&&config.inlineLoad.enabled&&Boolean.TRUE.equals(item.get("createQuickLoad")));
        }
        Object revision=result.remove("revisionDigest");Object next=result.remove("nextOffset");
        if(revision instanceof String)result.put("_cacheRevision",inspectionCacheToken(List.of(packageId,principal,revision)));
        if(next instanceof Number&&revision instanceof String)
            result.put("nextCursor",encodeCursor(packageId,"load",normalizedQuery,"name","asc",((Number)next).intValue(),(String)revision,principal));
        else result.put("nextCursor",null);
        return result;
    }
    Map<String,Object> inspectLoadSource(String packageId,String resourceId,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(resourceId==null||resourceId.isBlank()||resourceId.length()>1024)throw new IllegalArgumentException("resourceId is invalid");
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        WorkerRequest request=inspectionRequest(packageId,root,null);request.inspectionAction="load-source";request.inspectionPath=resourceId;
        return executeInspection(root,request);
    }
    Map<String,Object> inspectLoadCatalogDetail(String packageId,String resourceId,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(resourceId==null||resourceId.isBlank()||resourceId.length()>1024)throw new IllegalArgumentException("resourceId is invalid");
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        WorkerRequest request=inspectionRequest(packageId,root,null);request.inspectionAction="load-detail";request.inspectionPath=resourceId;
        Map<String,Object> result=executeInspection(root,request);
        Object revision=result.remove("revisionDigest");
        if(revision instanceof String)result.put("_cacheRevision",inspectionCacheToken(List.of(packageId,principal,revision)));
        return result;
    }
    Map<String,Object> validateLoadCatalogScenario(String packageId,String resourceId,String environment,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(resourceId==null||resourceId.isBlank()||resourceId.length()>1024)throw new IllegalArgumentException("resourceId is invalid");
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        Map<String,Object> configuration=resolveExecutionConfiguration(packageId,root,environment);
        String selected=(String)configuration.get("environment");
        WorkerRequest request=inspectionRequest(packageId,root,selected);request.inspectionAction="load-scenario";request.inspectionPath=resourceId;
        Map<String,Object> result=executeInspection(root,request);
        Object rawScenario=result.remove("normalizedScenario"),rawRevision=result.remove("revisionDigest");
        Object rawSensitive=result.remove("containsSensitiveValues"),rawModel=result.get("model");
        if(!(rawScenario instanceof Map)||!(rawRevision instanceof String)||!((String)rawRevision).matches("[a-f0-9]{64}")
                ||!(rawSensitive instanceof Boolean)||!(rawModel instanceof String)
                ||!List.of("virtualUsers","arrivalRate").contains(rawModel))
            throw new IllegalStateException("Load validation Worker returned an invalid response");
        configuration=configurationRevision(configuration,(String)rawRevision);result.put("configuration",configuration);
        result.put("validation","valid");
        result.put("scheduleAvailable",Boolean.FALSE);
        if(!config.scheduling.enabled) result.put("scheduleReason","Scheduling is disabled by Server policy.");
        else if(((Boolean)rawSensitive).booleanValue())
            result.put("scheduleReason","This scenario contains sensitive values and cannot be persisted as an immutable Schedule snapshot. Keep those values in protected package defaults.");
        else {
            @SuppressWarnings("unchecked") Map<String,Object> scenario=(Map<String,Object>)JSON.convertValue(rawScenario,Map.class);
            CatalogLoadDraftState draft=new CatalogLoadDraftState("C"+UUID.randomUUID().toString().replace("-","").toUpperCase(),
                    principal,packageId,selected,resourceId,(String)rawRevision,(String)rawModel,scenario,
                    (String)result.get("previewYaml"),configuration,clock.instant().plusMillis(DRAFT_TTL_MILLIS));
            try {
                synchronized(draftLock) {
                    expireDrafts();checkDraftCapacity(principal);catalogLoadDrafts.put(draft.id,draft);
                }
                result.put("scheduleAvailable",Boolean.TRUE);result.put("draftId",draft.id);result.put("expiresAt",draft.expiresAt.toString());
            } catch(DraftCapacityException full) {
                result.put("scheduleReason","Temporary Schedule draft capacity is full. Retry validation to try again.");
            }
        }
        return result;
    }
    Map<String,Object> inspectDebugForm(String packageId,String type,String resourceId,String environment,String principal) throws Exception {
        Map<String,Object> result=inspectDebugFormInternal(packageId,type,resourceId,environment,principal);
        result.remove("revisionDigest");
        return result;
    }
    Map<String,Object> inspectQuickLoadForm(String packageId,String type,String resourceId,String model,String environment,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        requireInlineLoadEnabled();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(!"virtualUsers".equals(model)&&!"arrivalRate".equals(model))throw new IllegalArgumentException("model is invalid");
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(resourceId==null||resourceId.isBlank()||resourceId.length()>512)throw new IllegalArgumentException("resourceId is invalid");
        if(environment!=null)environment=inspectionEnvironment(environment,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="quick-load-form";request.inspectionType=type;request.inspectionResourceId=resourceId;request.loadModel=model;
        Map<String,Object> result=executeInspection(root,request);result.remove("revisionDigest");result.remove("normalizedScenario");return result;
    }
    Map<String,Object> inspectQuickLoadPolicy(String packageId,String model,String environment,String templateId,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        requireInlineLoadEnabled();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(!"virtualUsers".equals(model)&&!"arrivalRate".equals(model))throw new IllegalArgumentException("model is invalid");
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(environment!=null)environment=inspectionEnvironment(environment,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="quick-load-policy";request.loadModel=model;request.inspectionPath=templateId;
        Map<String,Object> result=executeInspection(root,request);result.remove("revisionDigest");result.put("limits",inlineLoadLimitView());return result;
    }
    Map<String,Object> inspectLoadTemplates(String packageId,String query,int offset,int limit,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        requireInlineLoadEnabled();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        WorkerRequest request=inspectionRequest(packageId,root,null);
        request.inspectionAction="load-templates";request.inspectionQuery=query;request.inspectionOffset=offset;request.inspectionLimit=limit;
        Map<String,Object> result=executeInspection(root,request);
        result.put("limits",inlineLoadLimitView());
        return result;
    }
    private Map<String,Object> inlineLoadLimitView() {
        return Map.of("maxWorkloads",config.inlineLoad.maxWorkloads,"maxTargets",config.inlineLoad.maxTargets,
                "maxTotalUsers",config.inlineLoad.maxTotalUsers,"maxAggregateArrivalRatePerSecond",config.inlineLoad.maxAggregateArrivalRatePerSecond,
                "maxConcurrentPerWorkload",config.inlineLoad.maxConcurrentPerWorkload,"maxTotalConcurrent",config.inlineLoad.maxTotalConcurrent,
                "maxDurationSeconds",config.inlineLoad.maxDurationSeconds);
    }
    private Map<String,Object> inspectDebugFormInternal(String packageId,String type,String resourceId,String environment,
                                                        String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(resourceId==null||resourceId.isBlank()||resourceId.length()>512)throw new IllegalArgumentException("resourceId is invalid");
        if(environment!=null)environment=inspectionEnvironment(environment,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="debug-form";request.inspectionType=type;request.inspectionResourceId=resourceId;
        return executeInspection(root,request);
    }
    Map<String,Object> createDebugDraft(JsonNode input,String principal) throws Exception {
        DebugDraft draft=buildDebugDraft(input,principal);
        synchronized(draftLock) {
            expireDrafts();
            checkDraftCapacity(principal);
            debugDrafts.put(draft.id,draft);
        }
        return debugDraftView(draft);
    }
    private DebugDraft buildDebugDraft(JsonNode input,String principal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(!config.inspection.enabled)throw new NotFoundException();
        requireObject(input,"A JSON object is required");
        requireOnlyFields(input,"packageId","environment","target","input");
        String packageId=required(input,"packageId");Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        Map<String,Object> configuration=resolveExecutionConfiguration(packageId,root,optionalText(input,"environment"));
        String environment=(String)configuration.get("environment");
        JsonNode targetNode=input.get("target");requireObject(targetNode,"target must be a JSON object");requireOnlyFields(targetNode,"type","id");
        String type=required(targetNode,"type"),logicalId=required(targetNode,"id");
        if(!List.of("template","flow","tool").contains(type)||logicalId.length()>512||containsControl(logicalId))
            throw new IllegalArgumentException("target is invalid");
        JsonNode inputNode=input.get("input");requireObject(inputNode,"input must be a JSON object");
        Map<String,Object> submitted=JSON.convertValue(inputNode,Map.class);
        if(!submitted.containsKey("schemaVersion"))submitted.put("schemaVersion","att-debug/v1.2");
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="debug-input";request.inspectionType=type;request.inspectionTargetId=logicalId;
        request.inlineDebugInput=submitted;
        Map<String,Object> inspected=executeInspection(root,request);
        Object rawNormalized=inspected.remove("normalizedInput");
        Object rawDigest=inspected.remove("revisionDigest");
        inspected.put("configuration",configurationRevision(configuration,(String)rawDigest));
        Object rawSensitive=inspected.remove("containsSensitiveValues");
        if(!(rawNormalized instanceof Map)||!(rawDigest instanceof String)||!((String)rawDigest).matches("[a-f0-9]{64}")
                ||!(rawSensitive instanceof Boolean))
            throw new IllegalStateException("Debug validation Worker returned an invalid response");
        @SuppressWarnings("unchecked") Map<String,Object> normalized=(Map<String,Object>)JSON.convertValue(rawNormalized,Map.class);
        Object rawResource=inspected.get("resource"),rawTarget=inspected.get("target");
        if(!(rawResource instanceof Map)||!(rawTarget instanceof Map))throw new IllegalStateException("Debug validation Worker returned an invalid target");
        @SuppressWarnings("unchecked") Map<String,Object> resource=(Map<String,Object>)rawResource;
        String resourceId=String.valueOf(resource.get("resourceId"));
        @SuppressWarnings("unchecked") Map<String,Object> resolvedTarget=(Map<String,Object>)rawTarget;
        String resolvedType=String.valueOf(resolvedTarget.get("type")),resolvedId=String.valueOf(resolvedTarget.get("id"));
        DebugDraft draft=new DebugDraft("D"+UUID.randomUUID().toString().replace("-","").toUpperCase(),principal,packageId,
                environment,resolvedType,resolvedId,resourceId,(String)rawDigest,normalized,submitted,((Boolean)rawSensitive).booleanValue(),inspected,
                clock.instant().plusMillis(DRAFT_TTL_MILLIS));
        return draft;
    }
    Map<String,Object> createQuickLoadDraft(JsonNode input,String principal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(!config.inspection.enabled)throw new NotFoundException();
        requireInlineLoadEnabled();
        requireObject(input,"A JSON object is required");
        requireOnlyFields(input,"packageId","environment","target","model","input","load","execution","testdata");
        String packageId=required(input,"packageId");Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        Map<String,Object> configuration=resolveExecutionConfiguration(packageId,root,optionalText(input,"environment"));
        String environment=(String)configuration.get("environment");
        JsonNode targetNode=input.get("target");requireObject(targetNode,"target must be a JSON object");requireOnlyFields(targetNode,"type","id");
        String type=required(targetNode,"type"),logicalId=required(targetNode,"id");
        if(!List.of("template","flow","tool").contains(type)||logicalId.length()>512||containsControl(logicalId))
            throw new IllegalArgumentException("target is invalid");
        String model=required(input,"model");
        if(!List.of("virtualUsers","arrivalRate").contains(model))throw new IllegalArgumentException("model is invalid");
        JsonNode inputNode=input.get("input");requireObject(inputNode,"input must be a JSON object");
        Map<String,Object> business=JSON.convertValue(inputNode,Map.class);
        Map<String,Object> load=objectMap(input.get("load"),"load must be a JSON object");
        Map<String,Object> execution=objectMap(input.get("execution"),"execution must be a JSON object");
        List<String> testdata=quickLoadTestdata(input.get("testdata"));
        WorkerRequest request=inspectionRequest(packageId,root,environment);
        request.inspectionAction="quick-load-input";request.inspectionType=type;request.inspectionTargetId=logicalId;
        request.loadModel=model;request.inlineLoadInput=business;request.loadOverrides=load;request.workloadExecution=execution;
        request.quickLoadTestdata=testdata;
        Map<String,Object> inspected=executeInspection(root,request);
        Object rawScenario=inspected.remove("normalizedScenario");
        Object rawDigest=inspected.remove("revisionDigest");
        inspected.put("configuration",configurationRevision(configuration,(String)rawDigest));
        Object rawSensitive=inspected.remove("containsSensitiveValues");
        if(!(rawScenario instanceof Map)||!(rawDigest instanceof String)||!((String)rawDigest).matches("[a-f0-9]{64}")||!(rawSensitive instanceof Boolean))
            throw new IllegalStateException("Quick Load validation Worker returned an invalid response");
        @SuppressWarnings("unchecked") Map<String,Object> scenario=(Map<String,Object>)JSON.convertValue(rawScenario,Map.class);
        enforceInlineLoadLimits(scenario);
        Object rawResource=inspected.get("resource"),rawTarget=inspected.get("target");
        if(!(rawResource instanceof Map)||!(rawTarget instanceof Map))throw new IllegalStateException("Quick Load validation Worker returned an invalid target");
        @SuppressWarnings("unchecked") Map<String,Object> resource=(Map<String,Object>)rawResource;
        String resourceId=String.valueOf(resource.get("resourceId"));
        @SuppressWarnings("unchecked") Map<String,Object> resolvedTarget=(Map<String,Object>)rawTarget;
        String resolvedType=String.valueOf(resolvedTarget.get("type")),resolvedId=String.valueOf(resolvedTarget.get("id"));
        QuickLoadDraft draft=new QuickLoadDraft("L"+UUID.randomUUID().toString().replace("-","").toUpperCase(),
                principal,packageId,environment,resolvedType,resolvedId,resourceId,model,(String)rawDigest,
                scenario,((Boolean)rawSensitive).booleanValue(),inspected,clock.instant().plusMillis(DRAFT_TTL_MILLIS));
        synchronized(draftLock) {
            expireDrafts();
            checkDraftCapacity(principal);
            quickLoadDrafts.put(draft.id,draft);
        }
        return quickLoadDraftView(draft);
    }
    Map<String,Object> createAdvancedLoadDraft(JsonNode input,String principal) throws Exception {
        return createAdvancedLoadDraft(input,principal,null);
    }
    private Map<String,Object> createAdvancedLoadDraft(JsonNode input,String principal,Map<String,Object> approvedConfiguration) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(!config.inspection.enabled)throw new NotFoundException();
        requireInlineLoadEnabled();
        requireObject(input,"A JSON object is required");requireOnlyFields(input,"packageId","environment","scenario","templateComposition");
        String packageId=required(input,"packageId");Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        Map<String,Object> configuration=resolveExecutionConfiguration(packageId,root,optionalText(input,"environment"));
        if(approvedConfiguration!=null)configuration=approvedConfiguration;
        String environment=(String)configuration.get("environment");
        if(input.has("scenario")&&input.has("templateComposition"))throw new IllegalArgumentException("Choose scenario or templateComposition");
        JsonNode scenarioNode=input.has("templateComposition")?input.get("templateComposition"):input.get("scenario");requireObject(scenarioNode,"scenario must be a JSON object");
        @SuppressWarnings("unchecked") Map<String,Object> scenarioInput=(Map<String,Object>)JSON.convertValue(scenarioNode,Map.class);
        WorkerRequest request=inspectionRequest(packageId,root,environment);request.inspectionAction=input.has("templateComposition")?"load-template-compose":"load-scenario";request.inlineLoadScenario=scenarioInput;
        Map<String,Object> inspected=executeInspection(root,request);
        Object rawScenario=inspected.remove("normalizedScenario"),rawDigest=inspected.remove("revisionDigest");
        inspected.put("configuration",configurationRevision(configuration,(String)rawDigest));
        Object rawSensitive=inspected.remove("containsSensitiveValues");
        Object rawAuthoredSensitive=inspected.remove("containsSensitiveAuthoredValues");
        Object rawModel=inspected.get("model");
        if(!(rawScenario instanceof Map)||!(rawDigest instanceof String)||!((String)rawDigest).matches("[a-f0-9]{64}")
                ||!(rawSensitive instanceof Boolean)||!(rawAuthoredSensitive instanceof Boolean)||!(rawModel instanceof String)||!List.of("virtualUsers","arrivalRate").contains(rawModel))
            throw new IllegalStateException("Load validation Worker returned an invalid response");
        @SuppressWarnings("unchecked") Map<String,Object> scenario=(Map<String,Object>)JSON.convertValue(rawScenario,Map.class);
        enforceInlineLoadLimits(scenario);
        AdvancedLoadDraftState draft=new AdvancedLoadDraftState("A"+UUID.randomUUID().toString().replace("-","").toUpperCase(),
                principal,packageId,environment,(String)rawModel,(String)rawDigest,scenario,((Boolean)rawSensitive).booleanValue(),inspected,
                clock.instant().plusMillis(DRAFT_TTL_MILLIS), scenarioInput, input.has("templateComposition"), ((Boolean)rawAuthoredSensitive).booleanValue());
        synchronized(draftLock) {
            expireDrafts();
            checkDraftCapacity(principal);
            advancedLoadDrafts.put(draft.id,draft);
        }
        return advancedLoadDraftView(draft);
    }
    /** Saves only the server-captured authored input of a fresh, validated draft. */
    Map<String,Object> saveLoadScenario(JsonNode body,String principal) throws Exception {
        requireLocalOperator(principal);requireInlineLoadEnabled();
        requireObject(body,"A JSON object is required");requireOnlyFields(body,"draftId","name","description","tags");
        String owner=savedScenarioOwner(principal),draftId=required(body,"draftId");
        String name=required(body,"name").trim();
        String description=fieldText(body,"description","",false);if(description==null)description="";
        if(name.isEmpty()||name.length()>120||containsControl(name)||description.length()>1000||containsControl(description))
            throw new IllegalArgumentException("Invalid Saved Scenario name or description");
        JsonNode tags=body.has("tags")?body.get("tags"):JSON.createArrayNode();
        if(!tags.isArray()||tags.size()>20)throw new IllegalArgumentException("Invalid Saved Scenario tags");
        for(JsonNode tag:tags)if(!tag.isTextual()||tag.asText().isBlank()||tag.asText().length()>64||containsControl(tag.asText()))
            throw new IllegalArgumentException("Invalid Saved Scenario tag");
        AdvancedLoadDraftState draft;
        synchronized(draftLock) {
            expireDrafts();draft=advancedLoadDrafts.get(draftId);
            if(draft==null||!draft.principal.equals(principal))throw new NotFoundException();
            requireLiveDraft(draft.expiresAt);
        }
        if(draft.containsSensitiveValues||draft.containsSensitiveAuthoredValues)
            throw new IllegalArgumentException("This definition contains protected values and cannot be saved; use safe references or remove them");
        verifyPackageRevision(draft.packageId,draft.environment,draft.revisionDigest);
        SavedLoadScenario record=new SavedLoadScenario();
        record.id="LS"+UUID.randomUUID().toString().replace("-","").toUpperCase(java.util.Locale.ROOT);
        record.ownerSubject=owner;record.packageId=draft.packageId;record.name=name;record.description=description;
        record.tagsJson=JSON.writeValueAsString(tags);record.model=draft.model;record.sourceRevision=draft.revisionDigest;
        Map<String,Object> authored=new LinkedHashMap<>();authored.put("packageId",draft.packageId);
        if(draft.environment!=null)authored.put("environment",draft.environment);
        authored.put(draft.templateComposition?"templateComposition":"scenario",draft.authored);
        record.authoredJson=JSON.writeValueAsString(authored);record.revision=1;record.createdAt=clock.instant().toString();record.updatedAt=record.createdAt;
        synchronized(draftLock) {
            requireLiveDraft(draft.expiresAt);
            if(advancedLoadDrafts.get(draftId)!=draft)throw new StaleDraftException();
            if(draft.savedScenarioId==null)store.createSavedLoadScenario(record,100);
            else {
                if(!owner.equals(draft.savedScenarioOwner))throw new NotFoundException();
                store.withSavedRevision(draft.savedScenarioId,owner,draft.savedScenarioRevision,draft.packageId,()->{store.createSavedLoadScenario(record,100);return null;});
            }
        }
        store.audit(principal,"SAVED_LOAD_CREATE",null,draft.packageId,"CREATED");
        Map<String,Object> view=new LinkedHashMap<>();view.put("id",record.id);view.put("revision",record.revision);
        view.put("name",record.name);view.put("packageId",record.packageId);view.put("model",record.model);
        view.put("needsValidation",Boolean.TRUE);return view;
    }
    private String savedScenarioOwner(String principal) throws Exception {
        if(principal==null||principal.isBlank()||RequestIdentity.ANONYMOUS.equals(principal)||principal.length()>256||containsControl(principal))throw new NotFoundException();
        if("local".equals(config.authMode)) {
            String subject=store.localPreferenceSubject(principal);if(subject==null)throw new NotFoundException();return subject;
        }
        return "container:"+principal;
    }

    Map<String,Object> getSavedLoadScenario(String id,String principal) throws Exception {
        requireLocalOperator(principal);SavedLoadScenario record=store.savedLoadScenario(id,savedScenarioOwner(principal));
        if(record==null)throw new NotFoundException();
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",record.id);result.put("packageId",record.packageId);
        result.put("name",record.name);result.put("description",record.description);result.put("tags",JSON.readTree(record.tagsJson));
        result.put("model",record.model);result.put("revision",record.revision);result.put("createdAt",record.createdAt);result.put("updatedAt",record.updatedAt);
        result.put("definition",JSON.readTree(record.authoredJson));result.put("needsValidation",Boolean.TRUE);return result;
    }
    List<Map<String,Object>> savedLoadScenarios(String principal,int limit,int offset) throws Exception {
        requireLocalOperator(principal);List<Map<String,Object>> result=store.savedLoadScenarioSummaries(savedScenarioOwner(principal),limit,offset);
        for(Map<String,Object> item:result)item.put("needsValidation",Boolean.TRUE);
        return result;
    }
    Map<String,Object> updateSavedLoadScenario(String id,JsonNode body,String principal) throws Exception {
        requireLocalOperator(principal);requireInlineLoadEnabled();requireObject(body,"A JSON object is required");
        requireOnlyFields(body,"draftId","expectedRevision","name","description","tags");
        long expected=savedScenarioRevision(body);String owner=savedScenarioOwner(principal);
        SavedLoadScenario record=store.savedLoadScenario(id,owner);if(record==null)throw new NotFoundException();
        if(record.revision!=expected)throw new SavedScenarioConflictException();
        String draftId=required(body,"draftId");AdvancedLoadDraftState draft;
        synchronized(draftLock) {expireDrafts();draft=advancedLoadDrafts.get(draftId);
            if(draft==null||!draft.principal.equals(principal)||!draft.packageId.equals(record.packageId))throw new NotFoundException();
            requireLiveDraft(draft.expiresAt);
            if(draft.savedScenarioId!=null&&(!id.equals(draft.savedScenarioId)||draft.savedScenarioRevision!=expected))throw new SavedScenarioConflictException();
        }
        if(draft.containsSensitiveValues||draft.containsSensitiveAuthoredValues)
            throw new IllegalArgumentException("This definition contains protected values and cannot be saved; use safe references or remove them");
        String name=required(body,"name").trim();String description=fieldText(body,"description","",false);if(description==null)description="";
        JsonNode tags=body.has("tags")?body.get("tags"):JSON.createArrayNode();
        if(name.isEmpty()||name.length()>120||containsControl(name)||description.length()>1000||containsControl(description)||!tags.isArray()||tags.size()>20)
            throw new IllegalArgumentException("Invalid Saved Scenario metadata");
        for(JsonNode tag:tags)if(!tag.isTextual()||tag.asText().isBlank()||tag.asText().length()>64||containsControl(tag.asText()))throw new IllegalArgumentException("Invalid Saved Scenario tag");
        verifyPackageRevision(draft.packageId,draft.environment,draft.revisionDigest);
        Map<String,Object> authored=new LinkedHashMap<>();authored.put("packageId",draft.packageId);
        if(draft.environment!=null)authored.put("environment",draft.environment);
        authored.put(draft.templateComposition?"templateComposition":"scenario",draft.authored);
        record.name=name;record.description=description;record.tagsJson=JSON.writeValueAsString(tags);record.authoredJson=JSON.writeValueAsString(authored);
        record.model=draft.model;record.sourceRevision=draft.revisionDigest;record.updatedAt=clock.instant().toString();record.revision=expected+1;
        synchronized(draftLock) {requireLiveDraft(draft.expiresAt);if(advancedLoadDrafts.get(draftId)!=draft)throw new StaleDraftException();
            if(!store.updateSavedLoadScenario(record,expected))throw new SavedScenarioConflictException();}
        store.audit(principal,"SAVED_LOAD_UPDATE",null,record.packageId,"UPDATED");return getSavedLoadScenario(id,principal);
    }
    Map<String,Object> duplicateSavedLoadScenario(String id,long expected,String principal) throws Exception {
        requireLocalOperator(principal);String owner=savedScenarioOwner(principal);
        SavedLoadScenario source=store.savedLoadScenario(id,owner);if(source==null)throw new NotFoundException();
        if(source.revision!=expected)throw new SavedScenarioConflictException();
        Path root=packageRoot(source.packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        SavedLoadScenario copy=new SavedLoadScenario();copy.id="LS"+UUID.randomUUID().toString().replace("-","").toUpperCase(java.util.Locale.ROOT);
        copy.ownerSubject=owner;copy.packageId=source.packageId;copy.name=source.name.length()>113?source.name.substring(0,113)+" (copy)":source.name+" (copy)";
        copy.description=source.description;copy.tagsJson=source.tagsJson;copy.authoredJson=source.authoredJson;copy.model=source.model;
        copy.sourceRevision=source.sourceRevision;copy.revision=1;copy.createdAt=clock.instant().toString();copy.updatedAt=copy.createdAt;
        store.withSavedRevision(id,owner,expected,source.packageId,()->{store.createSavedLoadScenario(copy,100);return null;});
        store.audit(principal,"SAVED_LOAD_COPY",null,copy.packageId,"CREATED");return getSavedLoadScenario(copy.id,principal);
    }
    void deleteSavedLoadScenario(String id,long expected,String principal) throws Exception {
        requireLocalOperator(principal);String owner=savedScenarioOwner(principal);SavedLoadScenario record=store.savedLoadScenario(id,owner);
        if(record==null)throw new NotFoundException();
        if(!store.deleteSavedLoadScenario(id,owner,expected))throw new SavedScenarioConflictException();
        store.audit(principal,"SAVED_LOAD_DELETE",null,record.packageId,"DELETED");
    }
    static long savedScenarioRevision(JsonNode input) {
        JsonNode revision=input.get("expectedRevision");
        if(revision==null||!revision.isIntegralNumber()||!revision.canConvertToLong()||revision.longValue()<1||revision.longValue()==Long.MAX_VALUE)
            throw new IllegalArgumentException("expectedRevision must be a positive integer");
        return revision.longValue();
    }

    Map<String,Object> validateSavedLoadScenario(String id,long expectedRevision,String principal) throws Exception {
        requireLocalOperator(principal);String owner=savedScenarioOwner(principal);
        SavedLoadScenario source=store.savedLoadScenario(id,owner);
        if(source==null)throw new NotFoundException();
        if(source.revision!=expectedRevision)throw new SavedScenarioConflictException();
        JsonNode authored=JSON.readTree(source.authoredJson);
        Map<String,Object> view=createAdvancedLoadDraft(authored,principal);
        String draftId=String.valueOf(view.get("draftId"));
        synchronized(draftLock) {
            SavedLoadScenario current=store.savedLoadScenario(id,owner);
            AdvancedLoadDraftState draft=advancedLoadDrafts.get(draftId);
            if(current==null||current.revision!=expectedRevision||draft==null) {
                advancedLoadDrafts.remove(draftId);throw new SavedScenarioConflictException();
            }
            draft.savedScenarioId=id;draft.savedScenarioRevision=expectedRevision;draft.savedScenarioOwner=owner;
        }
        view.put("savedScenarioId",id);view.put("savedScenarioRevision",expectedRevision);return view;
    }

    Map<String,Object> getDebugDraft(String id,String principal) {
        if(id==null||!DRAFT_ID.matcher(id).matches())throw new NotFoundException();
        synchronized(draftLock) {
            expireDrafts();DebugDraft draft=debugDrafts.get(id);
            if(draft==null||!draft.principal.equals(principal))throw new NotFoundException();
            return debugDraftView(draft);
        }
    }
    Map<String,Object> getQuickLoadDraft(String id,String principal) {
        requireInlineLoadEnabled();
        if(id==null||!QUICK_LOAD_DRAFT_ID.matcher(id).matches())throw new NotFoundException();
        synchronized(draftLock) {
            expireDrafts();QuickLoadDraft draft=quickLoadDrafts.get(id);
            if(draft==null||!draft.principal.equals(principal))throw new NotFoundException();
            return quickLoadDraftView(draft);
        }
    }
    Map<String,Object> getAdvancedLoadDraft(String id,String principal) {
        requireInlineLoadEnabled();
        if(id==null||!ADVANCED_LOAD_DRAFT_ID.matcher(id).matches())throw new NotFoundException();
        synchronized(draftLock) {
            expireDrafts();AdvancedLoadDraftState draft=advancedLoadDrafts.get(id);
            if(draft==null||!draft.principal.equals(principal))throw new NotFoundException();
            return advancedLoadDraftView(draft);
        }
    }
    Map<String,Object> getCatalogLoadDraft(String id,String principal) {
        synchronized(draftLock) {
            expireDrafts();CatalogLoadDraftState draft=catalogLoadDrafts.get(id);
            if(draft==null||!draft.principal.equals(principal))throw new NotFoundException();
            return catalogLoadDraftView(draft);
        }
    }
    Map<String,Object> createRunDraft(JsonNode body,String principal) throws Exception {return createRunDraft(body,principal,false);}
    private Map<String,Object> createRunDraft(JsonNode body,String principal,boolean retainedSelection) throws Exception {
        return createRunDraft(body,principal,retainedSelection,null);
    }
    private Map<String,Object> createRunDraft(JsonNode body,String principal,boolean retainedSelection,Map<String,Object> approvedConfiguration) throws Exception {
        RunDefinition run=retainedSelection?RunDefinition.parseRetained(body):RunDefinition.parse(body);
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(!config.inspection.enabled)throw new NotFoundException();
        String packageId=run.workerInput.path("packageId").asText();
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        ObjectNode input=run.workerInput.deepCopy();
        Map<String,Object> configuration=resolveExecutionConfiguration(packageId,root,optionalText(input,"environment"));
        if(approvedConfiguration!=null)configuration=approvedConfiguration;
        input.put("environment",(String)configuration.get("environment"));
        // The request omits config when using the Server's shared runtime configuration.
        // Persist its absolute canonical path so draft revision checks and scheduled RUNs
        // keep resolving the same file outside the package root.
        input.put("config",config.runtimeConfigPath.toString());
        if(input.path("rerunFailed").asBoolean(false))resolveFailedCases(input,principal,packageId);
        Map<String,Object> preview;DebugDraft resourceDebugDraft=null;String revision;
        if(run.resourceTarget) {
            ObjectNode debug=JSON.createObjectNode();debug.put("packageId",packageId);
            debug.set("environment",input.get("environment"));
            debug.set("target",body.get("target"));debug.set("input",body.get("input"));
            resourceDebugDraft=buildDebugDraft(debug,principal);
            preview=debugDraftView(resourceDebugDraft);preview.remove("draftId");
            input.set("inlineDebugInput",JSON.valueToTree(resourceDebugDraft.input));
            input.put("draftResourceId",resourceDebugDraft.resourceId);revision=resourceDebugDraft.revisionDigest;
        } else {
            WorkerRequest request=applyWorkerPolicy(JSON.treeToValue(input,WorkerRequest.class));
            request.protocolVersion="att-worker/v1";request.command="inspect";request.packageRoot=root.toString();
            request.jobId="I"+UUID.randomUUID().toString().replace("-","");request.inspectionAction="run-input";
            request.config=config.runtimeConfigPath.toString();
            request.suites=safePaths(request.suites,"suites");if(request.suites!=null)for(String suite:request.suites)validatePackagePath(root,suite,"suites");
            request.suiteDirectory=safeRelative(request.suiteDirectory,"suiteDirectory");validatePackagePath(root,request.suiteDirectory,"suiteDirectory");
            if(request.environment!=null)request.environment=inspectionEnvironment(request.environment,"environment");
            ObjectNode policy=JSON.createObjectNode();applyInspectionPolicy(policy,packageId);
            JSON.readerForUpdating(request).readValue(policy);
            preview=executeInspection(root,request);revision=(String)preview.remove("revisionDigest");
            Object ids=preview.get("selectedCaseIds");
            if(!(ids instanceof List<?> selected)||selected.isEmpty()||selected.size()>10000)throw new IllegalArgumentException("RUN must select between 1 and 10000 cases");
            // Freeze the resolved selection; never reevaluate tags or rerun history when a schedule fires.
            input.set("caseIds",JSON.valueToTree(selected));input.remove("tags");input.remove("excludeTags");input.remove("rerunFailed");
        }
        if(revision==null||!revision.matches("[a-f0-9]{64}"))throw new IllegalStateException("RUN validation did not return a package revision");
        configuration=configurationRevision(configuration,revision);
        preview.put("configuration",configuration);input.set("configurationProvenance",JSON.valueToTree(configuration));
        input.put("expectedRevisionDigest",revision);applyInspectionPolicy(input,packageId);
        RunDraft draft=new RunDraft("R"+UUID.randomUUID().toString().replace("-","").toUpperCase(),principal,packageId,revision,input,
                new LinkedHashMap<>(preview),clock.instant().plusMillis(DRAFT_TTL_MILLIS),resourceDebugDraft);
        synchronized(draftLock) {
            expireDrafts();
            checkDraftCapacity(principal);
            runDrafts.put(draft.id,draft);
        }
        return runDraftView(draft);
    }
    private void resolveFailedCases(ObjectNode input,String principal,String packageId) throws Exception {
        byte[] selectedManifest=null;
        for(Map<String,Object> previous:store.listOwned(100,principal)) {
            if(!principal.equals(previous.get("principal"))||!packageId.equals(previous.get("packageId"))||!"run".equals(previous.get("command"))||!List.of("PASS","FAIL","ERROR","INVALID").contains(previous.get("status")))continue;
            String previousJobId=String.valueOf(previous.get("jobId"));
            Path output=config.dataDir.resolve("jobs").resolve(previousJobId).resolve("output");
            if(Files.isDirectory(output)) {
                List<Path> manifests=List.of();
                try(var files=Files.walk(output,3)){manifests=files.filter(file->file.getFileName().toString().equals("run.yaml")&&Files.isRegularFile(file,java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList();}
                catch(java.nio.file.NoSuchFileException archivedDuringScan) {
                    // The archive writer can remove the source after the directory check.
                }
                if(manifests.size()>1)throw new IllegalArgumentException("Previous Case RUN manifest is unavailable or exceeds the selection limit");
                if(manifests.size()==1) {
                    Path manifest=manifests.get(0);
                    try {
                        if(Files.size(manifest)>8*1024*1024)throw new IllegalArgumentException("Previous Case RUN manifest is unavailable or exceeds the selection limit");
                        selectedManifest=Files.readAllBytes(manifest);
                    } catch(java.nio.file.NoSuchFileException archivedDuringRead) {
                        // Archive cleanup can remove the source between discovery and reading; use its verified ZIP copy below.
                    }
                }
            }
            if(selectedManifest==null)selectedManifest=archivedCaseRunManifest(previousJobId);
            if(selectedManifest!=null)break;
            // A Template/Flow/Tool RUN has a specialized report, not a Case manifest.
        }
        if(selectedManifest==null)throw new IllegalArgumentException("No completed owned Case RUN is available for rerunFailed");
        Object document=new Yaml().load(new String(selectedManifest,StandardCharsets.UTF_8));
        if(!(document instanceof Map<?,?> manifest)||!(manifest.get("cases") instanceof List<?> cases))throw new IllegalArgumentException("Previous Case RUN manifest is invalid");
        List<String> failed=new ArrayList<>();
        for(Object value:cases)if(value instanceof Map<?,?> row&&List.of("FAIL","ERROR","INVALID").contains(row.get("status"))&&row.get("caseId") instanceof String id) {
            if(!input.has("caseIds")||JSON.convertValue(input.get("caseIds"),List.class).contains(id))failed.add(id);
        }
        if(failed.isEmpty())throw new IllegalArgumentException("Previous Case RUN has no failed cases matching this selection");
        input.set("caseIds",JSON.valueToTree(failed));input.remove("rerunFailed");
    }
    private byte[] archivedCaseRunManifest(String jobId) throws Exception {
        try(ArtifactRead archive=openArtifactArchive(jobId,false)) {
            List<Map<?,?>> manifests=new ArrayList<>();
            Object entries=archive.verified.manifest.get("entries");
            if(entries instanceof List<?> rows)for(Object value:rows)if(value instanceof Map<?,?> row
                    &&row.get("path") instanceof String path&&(path.equals("run.yaml")||path.endsWith("/run.yaml")))manifests.add(row);
            if(manifests.isEmpty())return null;
            if(manifests.size()!=1||!(manifests.get(0).get("size") instanceof Number size)||size.longValue()>8*1024*1024)
                throw new IllegalArgumentException("Previous Case RUN manifest is unavailable or exceeds the selection limit");
            return archive.verified.read(String.valueOf(manifests.get(0).get("path")));
        } catch(ArchiveUnavailableException unavailable) {
            return null;
        }
    }
    private Map<String,Object> runDraftView(RunDraft draft) {
        Map<String,Object> view=new LinkedHashMap<>(draft.preview);
        view.put("draftId",draft.id);view.put("schemaVersion",RunDefinition.VERSION);view.put("executionType","run");
        view.put("packageId",draft.packageId);view.put("revisionDigest",draft.revision);view.put("expiresAt",draft.expiresAt.toString());
        view.put("options",Map.of("debug",draft.workerInput.path("diagnosticMode").asBoolean()));
        return view;
    }
    private RunDraft runDraft(String id,String principal,String packageId) {
        synchronized(draftLock) {
            expireDrafts();RunDraft draft=runDrafts.get(id);
            if(draft==null||!draft.principal.equals(principal)||!draft.packageId.equals(packageId))throw new NotFoundException();
            return draft;
        }
    }
    Map<String,Object> getRunDraft(String id,String principal) {
        RunDraft draft=runDrafts.get(id);if(draft==null)throw new NotFoundException();
        return runDraftView(runDraft(id,principal,draft.packageId));
    }
    Map<String,Object> submitRunDraft(JsonNode body,String principal,String persistedPrincipal) throws Exception {
        requireOnlyFields(body,"packageId","draftId","principal");
        String draftId=required(body,"draftId");requireReplayConfirmation(draftId,principal);
        RunDraft draft=runDraft(draftId,principal,required(body,"packageId"));
        synchronized(draft) {
            if(runDrafts.get(draft.id)!=draft)throw new NotFoundException();
            WorkerRequest revisionRequest=inspectionRequest(draft.packageId,packageRoot(draft.packageId),draft.workerInput.path("environment").isTextual()?draft.workerInput.path("environment").asText():null);
            if(draft.workerInput.path("config").isTextual())revisionRequest.config=draft.workerInput.path("config").asText();
            revisionRequest.inspectionAction="revision";revisionRequest.expectedRevisionDigest=draft.revision;
            try { executeInspection(packageRoot(draft.packageId),revisionRequest); }
            catch(StaleCursorException stale) {runDrafts.remove(draft.id,draft);throw new StaleDraftException();}
            JobExecutionSpec retained=draft.resourceDebugDraft!=null&&draft.resourceDebugDraft.containsSensitiveValues
                    ?null:capturePrevalidatedExecutionSpec("run",draft.workerInput,null);
            Map<String,Object> accepted=submit("run",draft.workerInput,persistedPrincipal,true,null,false,null,null,retained);
            runDrafts.remove(draft.id,draft);
            return accepted;
        }
    }
    Map<String,Object> getDraft(String id,String principal) {
        if(id!=null&&id.matches("R[0-9A-F]{32}"))return getRunDraft(id,principal);
        if(DRAFT_ID.matcher(id==null?"":id).matches())return getDebugDraft(id,principal);
        if(QUICK_LOAD_DRAFT_ID.matcher(id==null?"":id).matches())return getQuickLoadDraft(id,principal);
        if(ADVANCED_LOAD_DRAFT_ID.matcher(id==null?"":id).matches())return getAdvancedLoadDraft(id,principal);
        if(CATALOG_LOAD_DRAFT_ID.matcher(id==null?"":id).matches())return getCatalogLoadDraft(id,principal);
        throw new NotFoundException();
    }
    Map<String,Object> submitDebugDraft(JsonNode body,String principal) throws Exception {
        return submitDebugDraft(body,principal,principal);
    }
    Map<String,Object> submitDebugDraft(JsonNode body,String principal,String persistedPrincipal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        requireObject(body,"A JSON object is required");requireOnlyFields(body,"packageId","draftId","principal");
        String packageId=required(body,"packageId"),draftId=required(body,"draftId");
        DebugDraft draft;
        synchronized(draftLock) {
            expireDrafts();draft=debugDrafts.get(draftId);
            if(draft==null||!draft.principal.equals(principal)||!draft.packageId.equals(packageId))throw new NotFoundException();
            synchronized(draft){if(draft.submitting)throw new IllegalArgumentException("Draft submission is already in progress");draft.submitting=true;}
        }
        boolean submitted=false;
        try {
            requireLiveDraft(draft);
            verifyPackageRevision(draft.packageId,draft.environment,draft.revisionDigest);
            com.fasterxml.jackson.databind.node.ObjectNode internal=JSON.createObjectNode();
            internal.set("configurationProvenance",JSON.valueToTree(retainedConfiguration(draft.preview)));
            internal.put("packageId",draft.packageId);if(draft.environment!=null)internal.put("environment",draft.environment);
            internal.set("target",JSON.valueToTree(Map.of("type",draft.targetType,"id",draft.targetId)));
            internal.set("inlineDebugInput",JSON.valueToTree(draft.input));
            internal.put("expectedRevisionDigest",draft.revisionDigest);internal.put("draftResourceId",draft.resourceId);
            applyInspectionPolicy(internal,draft.packageId);
            requireLiveDraft(draft);
            JobExecutionSpec retained=draft.containsSensitiveValues?null:capturePrevalidatedExecutionSpec("debug",internal,null);
            Map<String,Object> accepted=submit("debug",internal,persistedPrincipal,true,null,false,null,null,retained);
            debugDrafts.remove(draft.id,draft);submitted=true;return accepted;
        } catch(StaleDraftException stale) {
            debugDrafts.remove(draft.id,draft);throw stale;
        } catch(StaleCursorException|NotFoundException stale) {
            debugDrafts.remove(draft.id,draft);throw new StaleDraftException();
        } finally {
            if(!submitted)synchronized(draft){draft.submitting=false;}
        }
    }
    Map<String,Object> submitQuickLoadDraft(JsonNode body,String principal) throws Exception {
        return submitQuickLoadDraft(body,principal,principal);
    }
    Map<String,Object> submitQuickLoadDraft(JsonNode body,String principal,String persistedPrincipal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        requireInlineLoadEnabled();
        requireObject(body,"A JSON object is required");requireOnlyFields(body,"packageId","draftId","principal");
        String packageId=required(body,"packageId"),draftId=required(body,"draftId");
        QuickLoadDraft draft;
        synchronized(draftLock) {
            expireDrafts();draft=quickLoadDrafts.get(draftId);
            if(draft==null||!draft.principal.equals(principal)||!draft.packageId.equals(packageId))throw new NotFoundException();
            synchronized(draft){if(draft.submitting)throw new IllegalArgumentException("Quick Load draft submission is already in progress");draft.submitting=true;}
        }
        boolean submitted=false;
        try {
            verifyQuickLoadDraftRevision(draft);
            requireLiveDraft(draft.expiresAt);
            com.fasterxml.jackson.databind.node.ObjectNode internal=JSON.createObjectNode();
            internal.set("configurationProvenance",JSON.valueToTree(retainedConfiguration(draft.preview)));
            internal.put("packageId",draft.packageId);if(draft.environment!=null)internal.put("environment",draft.environment);
            internal.set("target",JSON.valueToTree(Map.of("type",draft.targetType,"id",draft.targetId)));
            internal.put("loadModel",draft.model);internal.set("inlineLoadScenario",JSON.valueToTree(draft.scenario));
            internal.put("expectedRevisionDigest",draft.revisionDigest);internal.put("draftResourceId",draft.resourceId);
            applyInspectionPolicy(internal,draft.packageId);
            requireLiveDraft(draft.expiresAt);
            JobExecutionSpec retained=draft.containsSensitiveValues?null:capturePrevalidatedExecutionSpec("load",internal,null);
            Map<String,Object> accepted=submit("load",internal,persistedPrincipal,true,null,false,null,null,retained);
            quickLoadDrafts.remove(draft.id,draft);submitted=true;return accepted;
        } catch(StaleDraftException stale) {
            quickLoadDrafts.remove(draft.id,draft);throw stale;
        } catch(StaleCursorException|NotFoundException stale) {
            quickLoadDrafts.remove(draft.id,draft);throw new StaleDraftException();
        } finally {
            if(!submitted)synchronized(draft){draft.submitting=false;}
        }
    }
    Map<String,Object> submitAdvancedLoadDraft(JsonNode body,String principal) throws Exception {
        return submitAdvancedLoadDraft(body,principal,principal);
    }
    Map<String,Object> submitAdvancedLoadDraft(JsonNode body,String principal,String persistedPrincipal) throws Exception {
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        requireInlineLoadEnabled();
        requireObject(body,"A JSON object is required");requireOnlyFields(body,"packageId","draftId","principal");
        String packageId=required(body,"packageId"),draftId=required(body,"draftId");
        AdvancedLoadDraftState draft;
        synchronized(draftLock) {
            expireDrafts();draft=advancedLoadDrafts.get(draftId);
            if(draft==null||!draft.principal.equals(principal)||!draft.packageId.equals(packageId))throw new NotFoundException();
            synchronized(draft){if(draft.submitting)throw new IllegalArgumentException("Draft submission is already in progress");draft.submitting=true;}
        }
        boolean submitted=false;
        try {
            verifyPackageRevision(draft.packageId,draft.environment,draft.revisionDigest);
            requireLiveDraft(draft.expiresAt);
            com.fasterxml.jackson.databind.node.ObjectNode internal=JSON.createObjectNode();
            internal.set("configurationProvenance",JSON.valueToTree(retainedConfiguration(draft.preview)));
            internal.put("packageId",draft.packageId);if(draft.environment!=null)internal.put("environment",draft.environment);
            internal.put("loadModel",draft.model);internal.set("inlineLoadScenario",JSON.valueToTree(draft.scenario));
            internal.put("expectedRevisionDigest",draft.revisionDigest);
            applyInspectionPolicy(internal,draft.packageId);
            requireLiveDraft(draft.expiresAt);
            JobExecutionSpec retained=draft.containsSensitiveValues?null:capturePrevalidatedExecutionSpec("load",internal,null);
            Map<String,Object> accepted;
            if(draft.savedScenarioId==null)accepted=submit("load",internal,persistedPrincipal,true,null,false,null,null,retained);
            else {
                requireLocalOperator(principal);
                if(!draft.savedScenarioOwner.equals(savedScenarioOwner(principal)))throw new NotFoundException();
                accepted=store.withSavedRevision(draft.savedScenarioId,draft.savedScenarioOwner,draft.savedScenarioRevision,draft.packageId,()->{
                    requireLiveDraft(draft.expiresAt);
                    return submit("load",internal,persistedPrincipal,true,null,false,null,null,retained);
                });
            }
            advancedLoadDrafts.remove(draft.id,draft);submitted=true;return accepted;
        } catch(StaleDraftException stale) {
            advancedLoadDrafts.remove(draft.id,draft);throw stale;
        } catch(StaleCursorException|NotFoundException stale) {
            advancedLoadDrafts.remove(draft.id,draft);throw new StaleDraftException();
        } finally {
            if(!submitted)synchronized(draft){draft.submitting=false;}
        }
    }
    Map<String,Object> submitLoadDraft(JsonNode body,String principal) throws Exception {
        return submitLoadDraft(body,principal,principal);
    }
    Map<String,Object> submitLoadDraft(JsonNode body,String principal,String persistedPrincipal) throws Exception {
        requireObject(body,"A JSON object is required");
        String draftId=required(body,"draftId");
        requireReplayConfirmation(draftId,principal);
        if(QUICK_LOAD_DRAFT_ID.matcher(draftId).matches())return submitQuickLoadDraft(body,principal,persistedPrincipal);
        if(ADVANCED_LOAD_DRAFT_ID.matcher(draftId).matches())return submitAdvancedLoadDraft(body,principal,persistedPrincipal);
        throw new NotFoundException();
    }
    private void requireReplayConfirmation(String draftId,String principal) {
        synchronized(replayLock) {
            for(ReplayReview review:replayReviews.values())if(review.draftId.equals(draftId)&&review.principal.equals(principal))
                throw new JobConflictException("ATT-SERVER-REPLAY-CONFIRMATION-REQUIRED","Job-based editor drafts must be submitted through their source Job confirmation intent.");
        }
    }
    private void verifyQuickLoadDraftRevision(QuickLoadDraft draft) throws Exception {
        try { verifyPackageRevision(draft.packageId,draft.environment,draft.revisionDigest); }
        catch(StaleCursorException|NotFoundException changed){throw new StaleDraftException();}
    }
    private void verifyPackageRevision(String packageId,String environment,String revision) throws Exception {
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        WorkerRequest request=inspectionRequest(packageId,root,environment);request.inspectionAction="revision";request.expectedRevisionDigest=revision;
        executeInspection(root,request);
    }
    private void requireLiveDraft(DebugDraft draft) {
        if(!draft.expiresAt.isAfter(clock.instant()))throw new StaleDraftException();
    }
    private void requireLiveDraft(Instant expiresAt) {
        if(!expiresAt.isAfter(clock.instant()))throw new StaleDraftException();
    }
    WorkerRequest inspectionRequest(String packageId,Path root,String environment) {
        WorkerRequest request=new WorkerRequest();request.protocolVersion="att-worker/v1";request.jobId="I"+UUID.randomUUID().toString().replace("-","");request.command="inspect";
        request.packageRoot=root.toString();request.config=config.runtimeConfigPath.toString();request.environment=environment;request.registeredPackageId=packageId;
        request.maxResponseBytes=config.inspection.maxResponseBytes;request.maxSourceBytes=config.inspection.maxSourceBytes;
        request.safeTextSources=config.inspection.safeTextSources(packageId);
        request.externalTextSources=config.inspection.externalTextSources(packageId);
        request.externalSourceRoots=config.inspection.externalSourceRoots;
        request.sourceBrowsingEnabled=config.inspection.sourceBrowsingEnabled;request.sourceBrowsingPolicy=config.inspection.sourcePolicy;
        request.sourceDenyGlobs=config.inspection.sourceDenyGlobs;request.redactionEnabled=config.inspection.redactionEnabled;
        request.sensitivePaths=config.inspection.sensitivePaths;request.sensitiveFieldPatterns=config.inspection.sensitiveFieldPatterns;
        return applyWorkerPolicy(request);
    }

    Map<String,Object> validateVNext(JsonNode input,String principal) throws Exception {
        if(input==null||!input.isObject())throw new IllegalArgumentException("A JSON validation plan is required");
        Set<String> allowedFields=Set.of("command","targetKind","target","packageId","environment","all");
        java.util.Iterator<String> fields=input.fieldNames();while(fields.hasNext())if(!allowedFields.contains(fields.next()))
            throw new IllegalArgumentException("Remote validation accepts only typed targets and always uses Server-owned configuration");
        String command=optionalText(input,"command");
        if(!"validate".equals(command))throw new IllegalArgumentException("Only validate plans are accepted by this endpoint");
        boolean all=input.path("all").asBoolean(false);
        String targetKind=optionalText(input,"targetKind"),target=optionalText(input,"target"),packageId=optionalText(input,"packageId"),environment=optionalText(input,"environment");
        if(all) {
            if(targetKind!=null||target!=null||packageId!=null||environment!=null)
                throw new IllegalArgumentException("validate --all cannot be combined with a target, Package, or Environment");
            return validateAllVNext();
        }
        String scope=targetKind==null?"config":targetKind;
        if(!List.of("config","package","case","suite","template","flow","tool","load").contains(scope))
            throw new IllegalArgumentException("Unsupported validation scope");
        if("config".equals(scope)&&target!=null)throw new IllegalArgumentException("validate config does not take a target");
        if(!"config".equals(scope)&&packageId==null)throw new IllegalArgumentException("Remote validation requires a registered -p/--package ID");
        if(List.of("case","suite","template","flow","tool","load").contains(scope)&&(target==null||target.isBlank()))
            throw new IllegalArgumentException("Selected validation requires a target");
        if("config".equals(scope)&&target!=null)throw new IllegalArgumentException("validate config does not take a target");
        Path root=null;
        if(packageId!=null) {root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);}
        if(environment!=null) {
            if(root!=null)config.packageConfiguration.authorizeEnvironment(environment,true);
        } else if(root!=null) {
            try {Map<String,Object> effective=resolveExecutionConfiguration(packageId,root,null);environment=(String)effective.get("environment");}
            catch(InspectionConfigurationException invalid) { /* The validation Worker returns the detailed read-only report. */ }
        }
        Path workerRoot=root==null?ServerConfig.runtimeConfigRoot(config.runtimeConfigPath):root;
        WorkerRequest request=inspectionRequest(packageId,workerRoot,environment);
        request.inspectionAction="cli-validation";request.validationScope=scope;request.inspectionSharedConfiguration=Boolean.valueOf(root==null);
        request.validationAllowedEnvironments=null;
        if("suite".equals(scope)||"load".equals(scope))request.inspectionPath=safeRelative(target,scope);
        else if("case".equals(scope))request.inspectionResourceId=safeText(target,512,"target");
        else if(List.of("template","flow","tool").contains(scope)){request.inspectionType=scope;request.inspectionResourceId=safeText(target,512,"target");}
        Map<String,Object> result=validationWorkerReport(executeInspection(workerRoot,request));
        result.put("requestId",request.jobId);
        return result;
    }

    private Map<String,Object> validateAllVNext() throws Exception {
        Path runtimeRoot=ServerConfig.runtimeConfigRoot(config.runtimeConfigPath);
        att.server.api.AttValidationReport report=new att.server.api.AttValidationReport("ALL","SERVER");
        report.context("runtimeConfig","config/config.yaml");report.context("serverConfig","att-server.yaml");
        try {
            WorkerRequest central=inspectionRequest(null,runtimeRoot,null);central.inspectionSharedConfiguration=Boolean.TRUE;
            central.inspectionAction="cli-validation";central.validationScope="config";central.all=Boolean.TRUE;
            Map<String,Object> checked=validationWorkerReport(executeInspection(runtimeRoot,central));appendValidationSections(report,checked);
        } catch(Exception unavailable) {
            report.incomplete();AttValidationReportSection(report,"Shared Runtime Config","config/config.yaml","ATT-VALIDATION-INCOMPLETE",safeMessage(unavailable));
        }
        Path serverFile=config.sourceFile;
        if(serverFile==null) {
            report.addSection("ATT Server Config","att-server.yaml","SKIP").diagnostic(validationDiagnostic("ATT-SERVER-CONFIG-SKIPPED","INFO","att-server.yaml","serverConfig","No active ATT Server Config path is configured",null));
        } else {
            att.server.api.AttValidationReport.Section server=report.addSection("ATT Server Config","att-server.yaml","PASS");
            try {ServerConfig.validateReadOnly(serverFile);}
            catch(Exception invalid) {
                server.status("FAIL");server.diagnostic(validationDiagnostic("ATT-SERVER-CONFIG-INVALID","ERROR","att-server.yaml","server",
                        att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(att.server.api.internal.DiagnosticSanitizer.redactText(invalid.getMessage()),serverFile.getParent()),
                        "Correct the active ATT Server Config and retry read-only validation."));report.exitCode(2);
            }
        }
        List<Map.Entry<String,Path>> packages=new ArrayList<>(config.packages.entrySet());packages.sort(Map.Entry.comparingByKey());
        packages=boundValidationPackages(packages,report);
        long validationWorkUnits=0L;
        for(int packageIndex=0;packageIndex<packages.size();packageIndex++) {
            Map.Entry<String,Path> entry=packages.get(packageIndex);
            String packageId=entry.getKey();Path root=entry.getValue();
            try {
                validatePackageRoot(root);
                WorkerRequest request=inspectionRequest(packageId,root,null);request.inspectionAction="cli-validation";
                request.validationScope="package";request.all=Boolean.TRUE;
                if(!config.packageConfiguration.allowedEnvironments.isEmpty())
                    request.validationAllowedEnvironments=new ArrayList<>(config.packageConfiguration.allowedEnvironments);
                Map<String,Object> checked=validationWorkerReport(executeInspection(root,request));
                validationWorkUnits=saturatedAdd(validationWorkUnits,validationWorkUnits(checked));
                String status=reportExit(checked)==0?"PASS":reportExit(checked)==2?"FAIL":"WARN";
                att.server.api.AttValidationReport.Section packageSection=report.addSection("Package "+packageId,"packages/"+packageId,status);
                appendValidationSections(packageSection,checked);
                appendValidationOutcome(report,checked);
                if(validationWorkUnits>=MAX_TOTAL_VNEXT_VALIDATION_WORK_UNITS&&packageIndex+1<packages.size()) {
                    report.incomplete();att.server.api.AttValidationReport.Section limit=report.addSection("Package Validation Work Limit",null,"WARN");
                    Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();diagnostic.put("code","ATT-VALIDATION-LIMIT");
                    diagnostic.put("severity","WARNING");diagnostic.put("field","packages");
                    diagnostic.put("message","The installation-wide Package/Environment work budget was reached; remaining Packages were skipped");
                    limit.diagnostic(diagnostic);break;
                }
            } catch(Exception unavailable) {
                validationWorkUnits=saturatedAdd(validationWorkUnits,
                        (long)MAX_VALIDATION_PACKAGE_ENTRIES*(MAX_VALIDATION_ENVIRONMENTS+2L));
                report.incomplete();AttValidationReportSection(report,"Package "+packageId,"packages/"+packageId,"ATT-VALIDATION-INCOMPLETE",safeMessage(unavailable));
                if(validationWorkUnits>=MAX_TOTAL_VNEXT_VALIDATION_WORK_UNITS&&packageIndex+1<packages.size()) {
                    report.incomplete();att.server.api.AttValidationReport.Section limit=report.addSection("Package Validation Work Limit",null,"WARN");
                    Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();diagnostic.put("code","ATT-VALIDATION-LIMIT");
                    diagnostic.put("severity","WARNING");diagnostic.put("field","packages");
                    diagnostic.put("message","The installation-wide Package/Environment work budget was reached; remaining Packages were skipped");
                    limit.diagnostic(diagnostic);break;
                }
            }
        }
        report.context("validationWorkUnits",validationWorkUnits);
        report.limitOutputBytes(config.inspection.maxResponseBytes);
        Map<String,Object> result=report.toMap();result.put("requestId","validation");return result;
    }

    static List<Map.Entry<String,Path>> boundValidationPackages(List<Map.Entry<String,Path>> packages,
            att.server.api.AttValidationReport report) {
        int total=packages.size();
        report.context("packages",total);
        if(total<=MAX_VNEXT_VALIDATION_PACKAGES)return packages;
        int checked=MAX_VNEXT_VALIDATION_PACKAGES;
        int skipped=total-checked;
        List<Map.Entry<String,Path>> bounded=new ArrayList<Map.Entry<String,Path>>(packages.subList(0,checked));
        report.incomplete();
        att.server.api.AttValidationReport.Section limit=report.addSection("Package Scan Limit",null,"WARN");
        Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();
        diagnostic.put("code","ATT-VALIDATION-LIMIT");
        diagnostic.put("severity","WARNING");
        diagnostic.put("field","packages");
        diagnostic.put("message","Server Package scan limit of "+MAX_VNEXT_VALIDATION_PACKAGES
                +" was reached; checked "+checked+" of "+total+" registered Packages and skipped "
                +skipped+" remaining registered Packages");
        limit.diagnostic(diagnostic);
        return bounded;
    }

    private static Map<String,Object> validationWorkerReport(Map<String,Object> report) {
        if(report==null||!"att-validation/v1".equals(report.get("schemaVersion")))
            throw new IllegalStateException("Validation Worker returned no att-validation/v1 report");
        return new LinkedHashMap<String,Object>(report);
    }

    @SuppressWarnings("unchecked") static void appendValidationSections(att.server.api.AttValidationReport report,Map<String,Object> source) {
        Object raw=source.get("sections");
        if(raw instanceof List)for(Object value:(List<?>)raw)if(value instanceof Map)copyValidationSection(report,(Map<String,Object>)value);
        appendValidationOutcome(report,source);
    }
    @SuppressWarnings("unchecked") static void appendValidationSections(att.server.api.AttValidationReport.Section report,Map<String,Object> source) {
        Object raw=source.get("sections");if(!(raw instanceof List))return;
        for(Object value:(List<?>)raw)if(value instanceof Map)copyValidationSection(report,(Map<String,Object>)value);
    }
    @SuppressWarnings("unchecked") private static void copyValidationSection(att.server.api.AttValidationReport report,Map<String,Object> source) {
        att.server.api.AttValidationReport.Section section=report.addSection(String.valueOf(source.get("name")),(String)source.get("source"),String.valueOf(source.get("status")));
        copyValidationDetails(section,source);
    }
    @SuppressWarnings("unchecked") private static void copyValidationSection(att.server.api.AttValidationReport.Section report,Map<String,Object> source) {
        att.server.api.AttValidationReport.Section section=report.child(String.valueOf(source.get("name")),(String)source.get("source"),String.valueOf(source.get("status")));
        copyValidationDetails(section,source);
    }
    @SuppressWarnings("unchecked") private static void copyValidationDetails(att.server.api.AttValidationReport.Section section,Map<String,Object> source) {
        Object diagnostics=source.get("diagnostics");if(diagnostics instanceof List)for(Object item:(List<?>)diagnostics)if(item instanceof Map)section.diagnostic((Map<String,Object>)item);
        Object nested=source.get("sections");if(nested instanceof List)for(Object item:(List<?>)nested)if(item instanceof Map)copyValidationSection(section,(Map<String,Object>)item);
    }
    static void appendValidationOutcome(att.server.api.AttValidationReport report,Map<String,Object> source) {
        if(Boolean.FALSE.equals(source.get("complete")))report.incomplete();
        int exit=reportExit(source);if(exit==2)report.exitCode(2);else if(exit==3)report.incomplete();
    }
    private static int reportExit(Map<String,Object> report) {Object value=report.get("exitCode");return value instanceof Number?((Number)value).intValue():3;}
    private static void AttValidationReportSection(att.server.api.AttValidationReport report,String name,String source,String code,String message) {
        att.server.api.AttValidationReport.Section section=report.addSection(name,source,"WARN");
        section.diagnostic(validationDiagnostic(code,"ERROR",source,"validation",message,null));
    }
    private static Map<String,Object> validationDiagnostic(String code,String severity,String source,String field,String message,String suggestion) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("code",code);out.put("severity",severity);out.put("source",source);out.put("field",field);out.put("message",message==null?"Validation failed":message);if(suggestion!=null)out.put("suggestion",suggestion);return out;
    }
    WorkerRequest applyWorkerPolicy(WorkerRequest request) {
        request.executionEnvironment=null;
        if(!"inspect".equals(request.command))request.packageManifestSource=null;
        request.liveOutputMaxEntryChars=config.liveOutput.maxEntryChars;request.liveOutputMaxEntryLines=config.liveOutput.maxEntryLines;
        return request;
    }
    byte[] workerRequestBytes(WorkerRequest request) throws Exception {
        return JSON.writeValueAsBytes(applyWorkerPolicy(request));
    }
    private byte[] workerRequestBytes(WorkerRequest request,boolean warm) throws Exception {
        if(!warm)return workerRequestBytes(request);
        var value=JSON.valueToTree(applyWorkerPolicy(request));
        ((com.fasterxml.jackson.databind.node.ObjectNode)value).set("executionEnvironment",JSON.valueToTree(System.getenv()));
        return JSON.writeValueAsBytes(value);
    }
    private void applyInspectionPolicy(com.fasterxml.jackson.databind.node.ObjectNode request,String packageId) {
        request.set("safeTextSources",JSON.valueToTree(config.inspection.safeTextSources(packageId)));
        request.set("externalTextSources",JSON.valueToTree(config.inspection.externalTextSources(packageId)));
        request.set("externalSourceRoots",JSON.valueToTree(config.inspection.externalSourceRoots));
        request.put("maxSourceBytes",config.inspection.maxSourceBytes);request.put("maxResponseBytes",config.inspection.maxResponseBytes);
        request.put("sourceBrowsingEnabled",config.inspection.sourceBrowsingEnabled);request.put("sourceBrowsingPolicy",config.inspection.sourcePolicy);
        request.set("sourceDenyGlobs",JSON.valueToTree(config.inspection.sourceDenyGlobs));request.put("redactionEnabled",config.inspection.redactionEnabled);
        request.set("sensitivePaths",JSON.valueToTree(config.inspection.sensitivePaths));request.set("sensitiveFieldPatterns",JSON.valueToTree(config.inspection.sensitiveFieldPatterns));
    }
    private Map<String,Object> debugDraftView(DebugDraft draft) {
        Map<String,Object> view=new LinkedHashMap<String,Object>();view.put("draftId",draft.id);view.put("packageId",draft.packageId);
        view.put("configuration",draft.preview.get("configuration"));view.put("environment",draft.environment);view.put("target",Map.of("type",draft.targetType,"id",draft.targetId));
        view.put("preview",draft.preview.get("input"));view.put("redacted",Boolean.TRUE.equals(draft.preview.get("redacted")));
        try {DumperOptions options=new DumperOptions();options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);options.setPrettyFlow(true);options.setWidth(120);view.put("previewYaml",new Yaml(options).dump(draft.preview.get("input")));}catch(Exception ignored){view.put("previewYaml","{}");}
        view.put("diagnostics",Collections.emptyList());view.put("expiresAt",draft.expiresAt.toString());return view;
    }
    private Map<String,Object> quickLoadDraftView(QuickLoadDraft draft) {
        Map<String,Object> view=new LinkedHashMap<String,Object>();view.put("draftId",draft.id);view.put("packageId",draft.packageId);
        view.put("configuration",draft.preview.get("configuration"));view.put("environment",draft.environment);view.put("target",Map.of("type",draft.targetType,"id",draft.targetId));
        view.put("model",draft.model);view.put("preview",draft.preview.get("preview"));view.put("previewYaml",draft.preview.get("previewYaml"));
        view.put("redacted",Boolean.TRUE.equals(draft.preview.get("redacted")));view.put("diagnostics",Collections.emptyList());
        view.put("expiresAt",draft.expiresAt.toString());return view;
    }
    private Map<String,Object> advancedLoadDraftView(AdvancedLoadDraftState draft) {
        Map<String,Object> view=new LinkedHashMap<String,Object>();view.put("draftId",draft.id);view.put("packageId",draft.packageId);
        view.put("configuration",draft.preview.get("configuration"));view.put("environment",draft.environment);view.put("model",draft.model);view.put("revisionDigest",draft.revisionDigest);
        view.put("preview",draft.preview.get("preview"));view.put("previewYaml",draft.preview.get("previewYaml"));
        view.put("redacted",Boolean.TRUE.equals(draft.preview.get("redacted")));view.put("diagnostics",Collections.emptyList());
        view.put("expiresAt",draft.expiresAt.toString());return view;
    }
    private Map<String,Object> catalogLoadDraftView(CatalogLoadDraftState draft) {
        Map<String,Object> view=new LinkedHashMap<String,Object>();view.put("draftId",draft.id);view.put("packageId",draft.packageId);
        view.put("configuration",draft.configuration);view.put("environment",draft.environment);view.put("model",draft.model);view.put("revisionDigest",draft.revisionDigest);view.put("previewYaml",draft.previewYaml);
        view.put("redacted",Boolean.TRUE);view.put("diagnostics",Collections.emptyList());view.put("expiresAt",draft.expiresAt.toString());return view;
    }
    private void checkDraftCapacity(String principal) {
        long total=debugDrafts.size()+quickLoadDrafts.size()+advancedLoadDrafts.size()+catalogLoadDrafts.size()+runDrafts.size();
        long owned=debugDrafts.values().stream().filter(d->d.principal.equals(principal)).count()
                +quickLoadDrafts.values().stream().filter(d->d.principal.equals(principal)).count()
                +advancedLoadDrafts.values().stream().filter(d->d.principal.equals(principal)).count()
                +catalogLoadDrafts.values().stream().filter(d->d.principal.equals(principal)).count()
                +runDrafts.values().stream().filter(d->d.principal.equals(principal)).count();
        if(total>=MAX_ACTIVE_DRAFTS||owned>=MAX_DRAFTS_PER_PRINCIPAL)throw new DraftCapacityException();
    }
    private void expireDrafts() {
        Instant now=clock.instant();runDrafts.entrySet().removeIf(entry->!entry.getValue().expiresAt.isAfter(now));
        debugDrafts.entrySet().removeIf(entry->!entry.getValue().expiresAt.isAfter(now));
        quickLoadDrafts.entrySet().removeIf(entry->!entry.getValue().expiresAt.isAfter(now));
        advancedLoadDrafts.entrySet().removeIf(entry->!entry.getValue().expiresAt.isAfter(now));
        catalogLoadDrafts.entrySet().removeIf(entry->!entry.getValue().expiresAt.isAfter(now));
    }
    private void expireDraftsSafely() {
        synchronized(draftLock){expireDrafts();}
        try {store.expireReplayIntents(clock.instant().toEpochMilli());}
        catch(Exception failure){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to expire old replay intents");}
    }
    private static void requireObject(JsonNode value,String message) {if(value==null||!value.isObject())throw new IllegalArgumentException(message);}
    private static void requireOnlyFields(JsonNode object,String... allowed) {
        java.util.Set<String> names=new java.util.HashSet<>(java.util.Arrays.asList(allowed));
        java.util.Iterator<String> fields=object.fieldNames();while(fields.hasNext())if(!names.contains(fields.next()))throw new IllegalArgumentException("Unknown request field");
    }
    private void requireInlineLoadEnabled() {
        if(!config.inlineLoad.enabled)throw new InlineLoadDisabledException();
    }
    private void enforceInlineLoadLimits(Map<String,Object> scenario) {
        validateInlineLoadLimits(config.inlineLoad,scenario);
    }
    static void validateInlineLoadLimits(ServerConfig.InlineLoad limits,Map<String,Object> scenario) {
        if(!limits.enabled)throw new InlineLoadDisabledException();
        validateScheduledLoadLimits(limits,scenario);
    }
    static void validateScheduledLoadLimits(ServerConfig.InlineLoad limits,Map<String,Object> scenario) {
        Object rawWorkloads=scenario.get("workloads");
        if(!(rawWorkloads instanceof List)||((List<?>)rawWorkloads).isEmpty())
            throw new IllegalArgumentException("Validated Load scenario must contain workloads");
        List<?> workloads=(List<?>)rawWorkloads;
        if(workloads.size()>limits.maxWorkloads)
            throw new InlineLoadLimitException("Scenario exceeds server.inlineLoad.maxWorkloads");
        long totalUsers=0L,totalTargets=0L,totalArrivalConcurrency=0L;
        double totalArrivalRate=0.0;
        long maximumEnvelopeMs=0L;
        Map<?,?> rootLoad=scenario.get("load") instanceof Map?(Map<?,?>)scenario.get("load"):Collections.emptyMap();
        for(int i=0;i<workloads.size();i++) {
            Object raw=workloads.get(i);
            if(!(raw instanceof Map))throw new IllegalArgumentException("Validated Load workload is invalid");
            Map<?,?> workload=(Map<?,?>)raw;
            Object mix=workload.get("mix");
            if(mix instanceof List)totalTargets=addWithinLimit(totalTargets,((List<?>)mix).size(),limits.maxTargets,"Scenario exceeds server.inlineLoad.maxTargets");
            else if(workload.get("target") instanceof Map)totalTargets=addWithinLimit(totalTargets,1,limits.maxTargets,"Scenario exceeds server.inlineLoad.maxTargets");
            else throw new IllegalArgumentException("Validated Load target is invalid");

            Object rawLoad=workload.get("load");
            if(rawLoad!=null&&!(rawLoad instanceof Map))throw new IllegalArgumentException("Validated Load pacing is invalid");
            Map<Object,Object> load=new LinkedHashMap<>();load.putAll(rootLoad);if(rawLoad instanceof Map)load.putAll((Map<?,?>)rawLoad);
            if(load.get("users") instanceof Number) {
                totalUsers=addWithinLimit(totalUsers,validatedCount(load.get("users"),"users"),limits.maxTotalUsers,
                        "Scenario exceeds server.inlineLoad.maxTotalUsers");
            } else {
                double arrivalRate=validatedRatePerSecond(load.get("arrivalRate"));
                if(arrivalRate>limits.maxAggregateArrivalRatePerSecond-totalArrivalRate)
                    throw new InlineLoadLimitException("Scenario exceeds server.inlineLoad.maxAggregateArrivalRatePerSecond");
                totalArrivalRate+=arrivalRate;
                long concurrent=validatedCount(load.get("maxConcurrent"),"maxConcurrent");
                if(concurrent>limits.maxConcurrentPerWorkload)
                    throw new InlineLoadLimitException("Workload exceeds server.inlineLoad.maxConcurrentPerWorkload");
                totalArrivalConcurrency=addWithinLimit(totalArrivalConcurrency,concurrent,limits.maxTotalConcurrent,
                        "Scenario exceeds server.inlineLoad.maxTotalConcurrent");
            }
            long envelope=durationEnvelopeMillis(load);
            maximumEnvelopeMs=Math.max(maximumEnvelopeMs,envelope);
        }
        if(maximumEnvelopeMs>TimeUnit.SECONDS.toMillis(limits.maxDurationSeconds))
            throw new InlineLoadLimitException("Effective LOAD envelope is "+maximumEnvelopeMs+" ms; server.inlineLoad.maxDurationSeconds permits "+TimeUnit.SECONDS.toMillis(limits.maxDurationSeconds)+" ms");
    }
    static void validatePackageBackedReplayLoadLimits(ServerConfig.InlineLoad limits,Map<String,Object> scenario) {
        validateScheduledLoadLimits(limits,scenario);
    }
    private static long addWithinLimit(long total,long value,long maximum,String message) {
        if(value<0||total>maximum-value)throw new InlineLoadLimitException(message);
        return total+value;
    }
    private static long validatedCount(Object value,String field) {
        if(!(value instanceof Number))throw new IllegalArgumentException("Validated Load "+field+" is invalid");
        try {
            long count=new java.math.BigDecimal(String.valueOf(value)).longValueExact();
            if(count<1)throw new IllegalArgumentException("Validated Load "+field+" is invalid");
            return count;
        } catch(NumberFormatException|ArithmeticException invalid) {
            throw new IllegalArgumentException("Validated Load "+field+" is invalid");
        }
    }
    private static double validatedRatePerSecond(Object value) {
        if(!(value instanceof String))throw new IllegalArgumentException("Validated Load arrivalRate is invalid");
        java.util.regex.Matcher matcher=INLINE_LOAD_RATE.matcher(((String)value).trim());
        if(!matcher.matches())throw new IllegalArgumentException("Validated Load arrivalRate is invalid");
        double rate=Double.parseDouble(matcher.group(1));
        if("m".equals(matcher.group(2)))rate/=60.0;
        if(!Double.isFinite(rate)||rate<=0.0)throw new IllegalArgumentException("Validated Load arrivalRate is invalid");
        return rate;
    }
    private static long durationMillis(Object value,String field) {
        if(value==null)return 0L;
        if(value instanceof Number) {
            try {long milliseconds=new java.math.BigDecimal(String.valueOf(value)).longValueExact();if(milliseconds<0)throw new IllegalArgumentException("Validated Load "+field+" is invalid");return milliseconds;}
            catch(NumberFormatException|ArithmeticException invalid){throw new IllegalArgumentException("Validated Load "+field+" is invalid",invalid);}
        }
        if(!(value instanceof String))throw new IllegalArgumentException("Validated Load "+field+" is invalid");
        java.util.regex.Matcher matcher=INLINE_LOAD_DURATION.matcher(((String)value).trim());
        if(!matcher.matches())throw new IllegalArgumentException("Validated Load "+field+" is invalid");
        long amount=Long.parseLong(matcher.group(1));
        long multiplier="ms".equals(matcher.group(2))?1L:"s".equals(matcher.group(2))?1000L:"m".equals(matcher.group(2))?60000L:3600000L;
        try{return Math.multiplyExact(amount,multiplier);}
        catch(ArithmeticException overflow){throw new IllegalArgumentException("Validated Load "+field+" is too large");}
    }
    private static long durationEnvelopeMillis(Map<?,?> load) {
        try {
            return Math.addExact(Math.addExact(durationMillis(load.get("warmup"),"warmup"),durationMillis(load.get("rampUp"),"rampUp")),
                    Math.addExact(durationMillis(load.get("duration"),"duration"),durationMillis(load.get("rampDown"),"rampDown")));
        } catch(ArithmeticException overflow) { return Long.MAX_VALUE; }
    }
    private static String optionalText(JsonNode object,String name) {
        JsonNode value=object.get(name);if(value==null||value.isNull())return null;
        if(!value.isTextual()||value.asText().isBlank())throw new IllegalArgumentException(name+" must be a non-empty string");
        String text=value.asText();if(text.length()>128||containsControl(text))throw new IllegalArgumentException(name+" is invalid");return text.trim();
    }
    private static Map<String,Object> objectMap(JsonNode value,String message) {
        if(value==null||value.isNull())return Collections.emptyMap();
        requireObject(value,message);
        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)JSON.convertValue(value,Map.class);
        return result;
    }

    @SuppressWarnings("unchecked") private static long validationWorkUnits(Map<String,Object> report) {
        Object context=report.get("context");Object value=context instanceof Map?((Map<String,Object>)context).get("validationWorkUnits"):null;
        if(value instanceof Number)return Math.max(0L,((Number)value).longValue());
        return (long)MAX_VALIDATION_PACKAGE_ENTRIES*(MAX_VALIDATION_ENVIRONMENTS+2L);
    }
    private static List<String> quickLoadTestdata(JsonNode value) {
        if(value==null||value.isNull())return Collections.emptyList();
        if(!value.isArray()||value.size()>64)throw new IllegalArgumentException("testdata must be an array of at most 64 package-relative paths");
        List<String> paths=new ArrayList<>();
        for(JsonNode item:value) {
            if(!item.isTextual()||item.asText().isBlank()||item.asText().length()>512||containsControl(item.asText()))
                throw new IllegalArgumentException("testdata entries must be non-empty package-relative paths");
            paths.add(item.asText().trim());
        }
        return paths;
    }
    /** Resolve through the Engine before policy checks and persist the canonical selected profile. */
    private Map<String,Object> resolveExecutionConfiguration(String packageId,Path root,String requested) throws Exception {
        String selection=requested==null||requested.isBlank()?null:inspectionEnvironment(requested,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,selection);
        request.inspectionType="package-configuration";request.inspectionAction="resolve";request.registeredPackageId=packageId;
        Map<String,Object> effective=executeInspection(root,request);
        Map<String,Object> evidence=new LinkedHashMap<>(att.server.api.ConfigurationSnapshot.copy(effective));
        String canonical=inspectionEnvironment((String)evidence.get("environment"),"environment");
        config.packageConfiguration.authorizeEnvironment(canonical,selection!=null);
        evidence.put("packageId",packageId);
        return deepMap(evidence);
    }
    private void authorizeConfiguration(Map<String,Object> configuration) {
        String origin=(String)configuration.get("environmentOrigin");
        config.packageConfiguration.authorizeEnvironment((String)configuration.get("environment"),"request".equals(origin)||"context".equals(origin));
    }
    private static Map<String,Object> configurationRevision(Map<String,Object> evidence,String revision) {
        Map<String,Object> result=new LinkedHashMap<>(evidence);
        if(revision!=null)result.put("packageRevision",revision);
        return att.server.api.ConfigurationSnapshot.copy(result);
    }
    private static Map<String,Object> retainedConfiguration(Map<String,Object> preview) {
        return att.server.api.ConfigurationSnapshot.copy((Map<?,?>)preview.get("configuration"));
    }
    private Path manifestEditableRoot(String packageId,String principal) throws Exception {
        if(!config.authenticationRequired||!config.packageConfiguration.sourceEditingEnabled||principal==null||
                !principal.equals(packageId)||!isOperationalPrincipal(principal)) {
            store.audit(principal==null?"anonymous":principal,"PACKAGE_MANIFEST_DENIED",null,packageId,"source editing is not authorized");
            throw new PackageManifestPublisher.Denied();
        }
        Path root=packageRoot(packageId);if(root==null)throw new PackageManifestPublisher.Missing();
        validatePackageRoot(root);return root.toRealPath();
    }
    private Map<String,Object> validateManifestCandidate(String packageId,String source) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        WorkerRequest request=inspectionRequest(packageId,root,null);
        request.inspectionType="package-configuration";request.inspectionAction="validate";request.packageManifestSource=source;
        return executeInspection(root,request);
    }
    Map<String,Object> inspectPackageConfiguration(String packageId,String action,String environment,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(!List.of("declared","effective","compare","source").contains(action))throw new IllegalArgumentException("Unsupported Package Configuration view");
        String selected=environment==null||environment.isBlank()?null:inspectionEnvironment(environment,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,selected);
        request.inspectionType="package-configuration";request.inspectionAction=action;
        Map<String,Object> result=executeInspection(root,request);result.put("packageId",packageId);
        result.put("canEdit",config.authenticationRequired&&config.packageConfiguration.sourceEditingEnabled&&packageId.equals(principal)&&isOperationalPrincipal(principal));
        return result;
    }

    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,String principal) throws Exception {
        return inspectConfiguration(packageId,action,environment,otherEnvironment,null,0,0,principal);
    }
    Map<String,Object> inspectTestdataRecords(String packageId,String descriptorId,String environment,int offset,int limit,
                                              boolean previewGenerated,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        if(offset<0||limit<1||limit>TESTDATA_RECORD_MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Testdata pagination is invalid");
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        String selected=environment==null||environment.isBlank()?null:inspectionEnvironment(environment,"environment");
        WorkerRequest request=inspectionRequest(packageId,root,selected);request.inspectionType="configuration";
        request.inspectionEnvironment=selected;
        request.inspectionAction="testdata-records";request.inspectionResourceId=descriptorId;request.inspectionSection=descriptorId;
        request.inspectionOffset=offset;request.inspectionLimit=limit;
        request.inspectionQuery=previewGenerated?"preview-generated":null;
        return executeInspection(root,request);
    }
    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,
                                            String section,int offset,int limit,String principal) throws Exception {
        return inspectConfiguration(packageId,action,environment,otherEnvironment,section,offset,limit,null,null,null,principal);
    }
    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,
                                            String section,int offset,int limit,String inspectionPath,String inspectionOrigin,
                                            String inspectionQuery,String principal) throws Exception {
        return inspectConfiguration(packageId,action,environment,otherEnvironment,section,offset,limit,
                inspectionPath,inspectionOrigin,inspectionQuery,false,principal);
    }
    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,
                                            String section,int offset,int limit,String inspectionPath,String inspectionOrigin,
                                            String inspectionQuery,boolean includeInherited,String principal) throws Exception {
        return inspectConfiguration(packageId,action,environment,otherEnvironment,section,offset,limit,inspectionPath,
                inspectionOrigin,inspectionQuery,includeInherited,null,principal);
    }
    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,
                                            String section,int offset,int limit,String inspectionPath,String inspectionOrigin,
                                            String inspectionQuery,boolean includeInherited,String inspectionReferenceId,String principal) throws Exception {
        return inspectConfiguration(packageId,action,environment,otherEnvironment,section,offset,limit,inspectionPath,
                inspectionOrigin,inspectionQuery,includeInherited,inspectionReferenceId,false,principal);
    }
    Map<String,Object> inspectConfiguration(String packageId,String action,String environment,String otherEnvironment,
                                            String section,int offset,int limit,String inspectionPath,String inspectionOrigin,
                                            String inspectionQuery,boolean includeInherited,String inspectionReferenceId,
                                            boolean includeAllFields,String principal) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(principal==null||principal.isBlank())throw new IllegalArgumentException("A server-resolved request identity is required");
        boolean shared=packageId==null||packageId.isEmpty();
        if(shared&&"testdata-records".equals(action))throw new NotFoundException();
        Path root=shared?ServerConfig.runtimeConfigRoot(config.runtimeConfigPath):packageRoot(packageId);
        if(root==null)throw new NotFoundException();
        if(!shared)validatePackageRoot(root);
        String scope=shared?"":packageId;
        if(!List.of("declared","effective","compare","testdata-records","files","source").contains(action))
            throw new IllegalArgumentException("Unsupported configuration inspection action");
        if(offset<0||limit<0||limit>100||"testdata-records".equals(action)&&limit<1)
            throw new IllegalArgumentException("Configuration pagination is invalid");
        if(inspectionPath!=null&&(inspectionPath.length()>1024||containsControl(inspectionPath)))
            throw new IllegalArgumentException("Configuration path is invalid");
        if(inspectionOrigin!=null&&(inspectionOrigin.length()>1024||containsControl(inspectionOrigin)))
            throw new IllegalArgumentException("Configuration source origin is invalid");
        if(inspectionQuery!=null&&(inspectionQuery.length()>200||containsControl(inspectionQuery)))
            throw new IllegalArgumentException("Configuration query must contain at most 200 printable characters");
        if(("source".equals(action)||"files".equals(action))&&(inspectionPath!=null&&inspectionPath.isBlank()))
            throw new IllegalArgumentException("Configuration path is invalid");
        if("source".equals(action)&&(inspectionPath==null||inspectionPath.isBlank()))
            throw new IllegalArgumentException("A package-relative source path is required");
        if("source".equals(action)) {
            if(shared&&(!Path.of(inspectionPath).normalize().startsWith(Path.of("config"))
                    ||inspectionOrigin!=null&&!Path.of(inspectionOrigin).normalize().startsWith(Path.of("config"))))throw new NotFoundException();
            if(inspectionReferenceId==null||inspectionReferenceId.isBlank()) {
                if((inspectionOrigin!=null&&!inspectionOrigin.isBlank())||!inspectionPath.startsWith("config/"))throw new NotFoundException();
            } else {
                JsonNode claim=decodeConfigurationSourceReference(inspectionReferenceId);
                if(!scope.equals(claim.path("packageId").asText())
                        ||!java.util.Objects.equals(inspectionOrigin,claim.path("origin").asText())
                        ||!java.util.Objects.equals(inspectionPath,claim.path("target").asText()))throw new NotFoundException();
            }
        }
        String selected=null,other=null;
        if(("effective".equals(action)||"testdata-records".equals(action))&&environment!=null&&!environment.isBlank())selected=inspectionEnvironment(environment,"environment");
        if("compare".equals(action)){
            selected=inspectionEnvironment(environment,"left environment");
            other=inspectionEnvironment(otherEnvironment,"right environment");
            if(selected.equalsIgnoreCase(other))throw new IllegalArgumentException("left and right environments must differ");
        }
        WorkerRequest request=inspectionRequest(scope,root,selected);
        request.inspectionSharedConfiguration=shared;
        if(shared) {
            request.safeTextSources=config.inspection.safeTextSources.values().stream().flatMap(List::stream)
                    .filter(path->path.startsWith("config/")).distinct().sorted().toList();
            request.externalTextSources=List.of();request.externalSourceRoots=List.of();
        }
        request.inspectionAction=action;request.inspectionType="configuration";request.inspectionEnvironment=selected;request.inspectionOtherEnvironment=other;
        request.inspectionSection=section;request.inspectionIncludeInherited=Boolean.valueOf(includeInherited);
        request.inspectionIncludeAllFields=Boolean.valueOf(includeAllFields);
        request.inspectionOffset=offset;request.inspectionLimit=limit;
        request.redactionEnabled=config.inspection.redactionEnabled;request.sensitivePaths=config.inspection.sensitivePaths;request.sensitiveFieldPatterns=config.inspection.sensitiveFieldPatterns;
        request.inspectionPath=inspectionPath;request.inspectionOrigin=inspectionOrigin;request.inspectionQuery=inspectionQuery;
        request.inspectionReferenceId=inspectionReferenceId;
        Map<String,Object> inspected=executeInspection(root,request);
        if("source".equals(action))signConfigurationSourceReferences(scope,inspected);
        return inspected;
    }
    private Map<String,Object> executeInspection(Path root,WorkerRequest request) throws Exception {
        long queuedAt=System.nanoTime();
        if(!inspectionTaskSlots.tryAcquire())throw new InspectionCapacityException();
        InspectionTask task=new InspectionTask(request,inspectionTaskSequence.getAndIncrement(),queuedAt,root);
        try { inspectors.execute(task); }
        catch(RejectedExecutionException full){task.releaseSlot();throw new InspectionCapacityException();}
        Map<String,Object> envelope;
        try { envelope=task.get(config.inspection.timeoutMs,TimeUnit.MILLISECONDS); }
        catch(java.util.concurrent.TimeoutException timeout){cancelInspectionTask(task);throw new InspectionTimeoutException();}
        catch(InterruptedException interrupted){cancelInspectionTask(task);Thread.currentThread().interrupt();throw interrupted;}
        catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new IllegalStateException("Package inspection failed",cause);}
        finally {
            // FutureTask can publish completion to get() callers before run() reaches its
            // finally block. Release completed work here too, so an immediate next request
            // does not observe a stale full-capacity slot. Timed-out/cancelled work keeps its
            // slot until it is removed from the queue or its worker actually exits.
            if(!task.isCancelled())task.releaseSlot();
        }
        String workerError=(String)envelope.get("errorCode");
        if("package-configuration".equals(request.inspectionType)&&"ATT-CFG-001".equals(workerError)) {
            Object raw=envelope.get("diagnostics");
            throw new InspectionConfigurationException(safeInspectionDiagnostics(raw instanceof List?(List<?>)raw:List.of(),root));
        }
        if("ATT-RESOURCE-NOT-FOUND".equals(workerError))throw new NotFoundException();
        if("ATT-RESOURCE-CURSOR-STALE".equals(workerError))throw new StaleCursorException();
        if(List.of("load-catalog","load-detail","load-source").contains(request.inspectionAction)) {
            Object rawDiagnostics=envelope.get("diagnostics");
            List<Map<String,Object>> diagnostics=rawDiagnostics instanceof List
                    ?safeInspectionDiagnostics((List<?>)rawDiagnostics,root):List.of();
            if("ATT-CFG-001".equals(workerError)||diagnostics.stream().anyMatch(item -> "ATT-CFG-001".equals(item.get("code"))))
                throw new InspectionConfigurationException(diagnostics.isEmpty()
                        ?safeInspectionDiagnostics(List.of(),root):diagnostics);
        }
        boolean inspectorEndpoint=List.of("list","detail","source","source-file","debug-form","quick-load-form",
                "load-catalog","load-detail","load-source","load-templates","quick-load-policy").contains(request.inspectionAction);
        if(inspectorEndpoint&&("ATT-RESOURCE-ROOT-INVALID".equals(workerError)
                ||"WORKER_REQUEST_INVALID".equals(workerError))) {
            Object raw=envelope.get("diagnostics");
            List<Map<String,Object>> diagnostics=raw instanceof List
                    ?safeInspectionDiagnostics((List<?>)raw,root):new ArrayList<>();
            if(diagnostics.isEmpty()||"ATT-CFG-001".equals(diagnostics.get(0).get("code"))) {
                String code="ATT-RESOURCE-ROOT-INVALID".equals(workerError)
                        ?"ATT-RESOURCE-ROOT-INVALID":"ATT-RESOURCE-VALIDATION-FAILED";
                Object rawMessage=envelope.get("message");
                String summary=rawMessage instanceof String?(String)rawMessage:"Package inspection input or configuration is invalid.";
                summary=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(
                        att.server.api.internal.DiagnosticSanitizer.redactText(summary),root);
                diagnostics=List.of(Map.of("code",code,"summary",summary));
            } else if("WORKER_REQUEST_INVALID".equals(workerError)) {
                for(Map<String,Object> diagnostic:diagnostics)
                    if("WORKER_REQUEST_INVALID".equals(diagnostic.get("code")))diagnostic.put("code","ATT-RESOURCE-VALIDATION-FAILED");
            }
            String code="ATT-RESOURCE-ROOT-INVALID".equals(workerError)
                    ?"ATT-RESOURCE-ROOT-INVALID":"ATT-RESOURCE-VALIDATION-FAILED";
            java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning(
                    "ATT inspection validation rejected package="+String.valueOf(request.registeredPackageId)
                            +" action="+String.valueOf(request.inspectionAction)+" code="+code);
            throw new InspectionValidationException(code,diagnostics);
        }
        boolean draftInspection=("package-configuration".equals(request.inspectionType)&&"validate".equals(request.inspectionAction))||"run-input".equals(request.inspectionAction)||"debug-input".equals(request.inspectionAction)||"debug-form".equals(request.inspectionAction)
                ||"quick-load-input".equals(request.inspectionAction)||"quick-load-form".equals(request.inspectionAction)
                ||"load-templates".equals(request.inspectionAction)||"load-template-compose".equals(request.inspectionAction)
                ||"quick-load-policy".equals(request.inspectionAction)||"load-scenario".equals(request.inspectionAction)
                ||"load-detail".equals(request.inspectionAction);
        if(draftInspection&&("WORKER_REQUEST_INVALID".equals(workerError)
                ||workerError!=null&&(workerError.startsWith("ATT-DEBUG")||workerError.startsWith("ATT-LOAD"))
                ||envelope.get("diagnostics") instanceof List&&!((List<?>)envelope.get("diagnostics")).isEmpty())) {
            Object rawDiagnostics=envelope.get("diagnostics");
            List<Map<String,Object>> diagnostics=new ArrayList<Map<String,Object>>();
            if(rawDiagnostics instanceof List)for(Object raw:(List<?>)rawDiagnostics)if(raw instanceof Map) {
                @SuppressWarnings("unchecked") Map<String,Object> value=(Map<String,Object>)raw;
                Map<String,Object> safe=new LinkedHashMap<String,Object>();
                for(String key:List.of("code","summary","detail","field","file","suggestion","resourceId","sourceFile","packageId")) {
                    Object item=value.get(key);
                    if(item instanceof String) {
                        String text=att.server.api.internal.DiagnosticSanitizer.redactText((String)item);
                        text=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(text,root);
                        safe.put(key,text);
                    }
                }
                for(String key:List.of("line","column","sourceColumn"))if(value.get(key) instanceof Number)safe.put(key,value.get(key));
                if(!safe.isEmpty())diagnostics.add(safe);
            }
            if(diagnostics.isEmpty()) {
                Map<String,Object> fallback=new LinkedHashMap<>();
                fallback.put("code",workerError==null?"ATT-SERVER-DRAFT-INVALID":workerError);
                Object message=envelope.get("message");
                String summary=message instanceof String?String.valueOf(message):"Load or Debug input failed schema or target validation";
                fallback.put("summary",att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(att.server.api.internal.DiagnosticSanitizer.redactText(summary),root));
                String resourceId=request.inspectionTargetId==null?request.inspectionResourceId:request.inspectionTargetId;
                if(resourceId!=null)fallback.put("resourceId",resourceId);
                diagnostics.add(fallback);
            }
            String packageId=request.registeredPackageId==null?configuredPackageId(root):request.registeredPackageId;
            List<Map<String,String>> targets=diagnosticTargets(request);
            for(Map<String,Object> diagnostic:diagnostics) {
                if(packageId!=null)diagnostic.putIfAbsent("packageId",packageId);
                String environment=request.inspectionEnvironment==null?request.environment:request.inspectionEnvironment;
                if(environment!=null&&!environment.isBlank())diagnostic.putIfAbsent("environment",environment);
                if(!targets.isEmpty())diagnostic.putIfAbsent("targets",targets);
            }
            throw new DraftValidationException(diagnostics);
        }
        if("ATT-RESOURCE-RESPONSE-TOO-LARGE".equals(workerError))throw new InspectionResponseTooLargeException();
        if("ATT-RESOURCE-LIMIT".equals(workerError))
            throw new InspectionLimitException(safeResourceLimitDiagnostics(envelope.get("diagnostics")));
        if(workerError!=null)throw new IllegalStateException("Package inspection Worker failed: "+workerError);
        Object raw=envelope.get("inspection");if(!(raw instanceof Map))throw new IllegalStateException("Package inspection Worker returned an invalid response");
        JsonNode safe=projectInspectionPaths(JSON.valueToTree(att.server.api.internal.DiagnosticSanitizer.sanitizeValue(raw)),root,"");
        @SuppressWarnings("unchecked") Map<String,Object> result=JSON.convertValue(safe,Map.class);
        return result;
    }
    private List<Map<String,Object>> safeResourceLimitDiagnostics(Object raw) {
        List<Map<String,Object>> result=new ArrayList<Map<String,Object>>();
        if(raw instanceof List<?>)for(Object value:(List<?>)raw)if(value instanceof Map<?,?>) {
            Map<?,?> source=(Map<?,?>)value;
            if(!"ATT-RESOURCE-LIMIT".equals(source.get("code")))continue;
            Object summary=source.get("summary");
            if(!(summary instanceof String))summary=source.get("message");
            String safeSummary=summary instanceof String
                    ?att.server.api.internal.DiagnosticSanitizer.redactText((String)summary)
                    :"Package inspection exceeded a deterministic scan limit.";
            String name=source.get("limitName") instanceof String?(String)source.get("limitName"):null;
            Number configured=source.get("configuredLimit") instanceof Number?(Number)source.get("configuredLimit"):null;
            Number observed=source.get("observed") instanceof Number?(Number)source.get("observed"):null;
            Map<String,Object> diagnostic=att.server.api.LimitDiagnostic.create("ATT-RESOURCE-LIMIT",safeSummary,
                    name,configured,observed,"package",att.server.api.LimitOutcome.SUBMISSION_REJECTED,"inspection",
                    "Reduce the package scan scope or split the package into bounded units; this is a deterministic limit and does not need a retry.",null);
            if(!diagnostic.isEmpty())result.add(diagnostic);
            if(result.size()>=2)break;
        }
        if(result.isEmpty())result.add(att.server.api.LimitDiagnostic.create("ATT-RESOURCE-LIMIT",
                "Package inspection exceeded a deterministic scan limit.","resourceInspector.scan",null,null,
                "package",att.server.api.LimitOutcome.SUBMISSION_REJECTED,"inspection",
                "Reduce the package scan scope or split the package into bounded units; this is a deterministic limit and does not need a retry.",null));
        return result;
    }
    private void cancelInspectionTask(InspectionTask task) {
        task.cancel(true);
        if(inspectors.remove(task))task.releaseSlot();
    }
    private final class InspectionTask extends FutureTask<Map<String,Object>> implements Comparable<Runnable> {
        private final int priority;
        private final long sequence;
        private final boolean explorerList;
        private final java.util.concurrent.atomic.AtomicBoolean slotReleased=new java.util.concurrent.atomic.AtomicBoolean();
        InspectionTask(WorkerRequest request,long sequence,long queuedAt,Path root) {
            super(()->{inspectionMetrics.record(QUEUE,System.nanoTime()-queuedAt);return runInspectionWorker(root,request);});
            this.sequence=sequence;
            this.explorerList="list".equals(request.inspectionAction);
            // Give at most three short, read-only listings priority before normal work has a turn.
            int credits=explorerList?inspectionExplorerPriorityCredits.getAndUpdate(value->Math.max(0,value-1)):0;
            this.priority=credits>0?0:1;
        }
        @Override public int compareTo(Runnable other) {
            if(this==other)return 0;
            InspectionTask candidate=(InspectionTask)other;
            int byPriority=Integer.compare(priority,candidate.priority);
            return byPriority!=0?byPriority:Long.compare(sequence,candidate.sequence);
        }
        @Override public void run() {
            if(!explorerList)inspectionExplorerPriorityCredits.set(INSPECTION_EXPLORER_PRIORITY_BURST);
            try { super.run(); }
            finally { releaseSlot(); }
        }
        void releaseSlot() { if(slotReleased.compareAndSet(false,true))inspectionTaskSlots.release(); }
    }
    static List<Map<String,Object>> safeInspectionDiagnostics(List<?> rawDiagnostics,Path root) {
        List<Map<String,Object>> diagnostics=new ArrayList<>();
        for(Object raw:rawDiagnostics)if(raw instanceof Map) {
            @SuppressWarnings("unchecked") Map<String,Object> value=(Map<String,Object>)raw;
            Map<String,Object> safe=new LinkedHashMap<>();
            for(String key:List.of("code","summary","detail","field","file","sourceFile","resourceId","suggestion","packageId")) {
                Object item=value.get(key);
                if(item instanceof String) {
                    String text=att.server.api.internal.DiagnosticSanitizer.redactText((String)item);
                    safe.put(key,att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(text,root));
                }
            }
            for(String key:List.of("line","column","sourceColumn"))
                if(value.get(key) instanceof Number)safe.put(key,value.get(key));
            if(!safe.isEmpty())diagnostics.add(safe);
        }
        if(diagnostics.isEmpty())diagnostics.add(Map.of("code","ATT-CFG-001","summary","Package configuration is invalid or incomplete."));
        return diagnostics;
    }
    private JsonNode projectInspectionPaths(JsonNode value,Path root,String field) {
        if(value.isTextual() && "$attDebugKeepDefault".equals(field))return value;
        if(value.isTextual())return JSON.getNodeFactory().textNode(semanticLocation(field)?value.asText():att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(value.asText(),root));
        if(value.isObject()){var copy=JSON.createObjectNode();value.fields().forEachRemaining(e->copy.set(e.getKey(),projectInspectionPaths(e.getValue(),root,semanticLocation(field)?field:e.getKey())));return copy;}
        if(value.isArray()){var copy=JSON.createArrayNode();for(JsonNode item:value)copy.add(projectInspectionPaths(item,root,field));return copy;}
        return value;
    }
    private String configuredPackageId(Path root) {
        if(root==null)return null;
        Path normalized=root.toAbsolutePath().normalize();
        for(Map.Entry<String,Path> entry:packageRoots().entrySet())
            if(entry.getValue().toAbsolutePath().normalize().equals(normalized))return entry.getKey();
        return null;
    }
    private static List<Map<String,String>> diagnosticTargets(WorkerRequest request) {
        List<Map<String,String>> targets=new ArrayList<>();
        if(request.inspectionTargetId!=null) {
            Map<String,String> target=new LinkedHashMap<>();
            if(request.inspectionType!=null)target.put("type",request.inspectionType);
            target.put("id",request.inspectionTargetId);targets.add(target);return targets;
        }
        Object rawWorkloads=request.inlineLoadScenario==null?null:request.inlineLoadScenario.get("workloads");
        if(rawWorkloads instanceof List<?>)for(Object raw:(List<?>)rawWorkloads) {
            if(!(raw instanceof Map<?,?>))continue;
            Object rawTarget=((Map<?,?>)raw).get("target");if(!(rawTarget instanceof Map<?,?>))continue;
            Map<?,?> source=(Map<?,?>)rawTarget;Object type=source.get("type"),id=source.get("id");
            if(type instanceof String&&id instanceof String) {
                Map<String,String> target=new LinkedHashMap<>();target.put("type",(String)type);target.put("id",(String)id);
                if(!targets.contains(target))targets.add(target);
                if(targets.size()>=32)break;
            }
        }
        return targets;
    }
    private static String inspectionEnvironment(String value,String name) {
        if(value==null||!value.trim().matches("[A-Za-z][A-Za-z0-9_-]{0,31}"))throw new IllegalArgumentException(name+" must be a valid profile name");
        return value.trim();
    }
    private Map<String,Object> runInspectionWorker(Path root,WorkerRequest request) throws Exception {
        String affinity=inspectionAffinity(root,request);
        InspectionWorker worker=acquireInspectionWorker(affinity);
        long started=System.nanoTime();
        try {
            Map<String,Object> response=worker.request(root,request);
            return response;
        }
        finally { inspectionMetrics.record(DISPATCH,System.nanoTime()-started);worker.lock.unlock(); }
    }
    String inspectionCacheToken(Object context) throws Exception {
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(cursorKey,"HmacSHA256"));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(JSON.writeValueAsBytes(context)));
    }
    private void warmInspectionSafely() {
        if(closing||inspectors.getActiveCount()>0||!inspectors.getQueue().isEmpty())return;
        List<ServerConfig.WarmupKey> keys=config.inspection.warmup.keys();
        for(int slot=0;slot<config.inspection.warmup.workers();slot++) {
            InspectionWorker worker=inspectionWorkerPool.get(slot);
            if(!worker.lock.tryLock())continue;
            try {
                if(closing||inspectors.getActiveCount()>0||!inspectors.getQueue().isEmpty())return;
                if(worker.process!=null&&!worker.process.isAlive())worker.discard();
                List<ServerConfig.WarmupKey> assigned=new ArrayList<>();
                for(int i=slot;i<keys.size();i+=config.inspection.warmup.workers())assigned.add(keys.get(i));
                if(assigned.isEmpty()&&!packageRoots().isEmpty())assigned.add(new ServerConfig.WarmupKey(packageRoots().keySet().iterator().next(),null));
                for(ServerConfig.WarmupKey key:assigned) {
                    int attempts=worker.warmAttempts.getOrDefault(key,0);
                    if(worker.warmed.contains(key)||attempts>=3||System.nanoTime()<worker.warmRetryAt)continue;
                    worker.warmAttempts.put(key,attempts+1);
                    worker.warming=true;
                    try {
                        Path root=packageRoot(key.packageId());
                        if(root==null)continue; // Missing packages never affect Server startup.
                        WorkerRequest request=inspectionRequest(key.packageId(),root,key.environment());
                        request.inspectionAction="list";request.inspectionType="template";request.inspectionLimit=1;request.inspectionOffset=0;
                        Map<String,Object> response=worker.request(root,request);
                        if(!response.containsKey("errorCode"))worker.warmed.add(key);
                    } catch(Exception ignored) { /* No package contents or paths in background failure logs. */ }
                    finally {worker.warming=false;worker.warmRetryAt=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(250L<<attempts);}
                    return;
                }
            } finally {worker.lock.unlock();}
        }
    }
    private String inspectionAffinity(Path root,WorkerRequest request) throws Exception {
        return att.server.api.internal.InspectionCacheIdentity.requested(root,request);
    }
    private InspectionWorker acquireInspectionWorker(String affinity) throws InterruptedException {
        int size=inspectionWorkerPool.size();
        int first=(int)Math.floorMod(inspectionWorkerSelection.getAndIncrement(),(long)size);
        for(int offset=0;offset<size;offset++) {
            InspectionWorker worker=inspectionWorkerPool.get((first+offset)%size);
            if(worker.lock.tryLock()) {
                if(worker.process!=null&&worker.process.isAlive()&&worker.holds(affinity))return worker;
                worker.lock.unlock();
            }
        }
        for(int offset=0;offset<size;offset++) {
            InspectionWorker worker=inspectionWorkerPool.get((first+offset)%size);
            if(worker.lock.tryLock())return worker;
        }
        // Interactive demand may cancel a background preload when every Worker is occupied.
        for(InspectionWorker candidate:inspectionWorkerPool)if(candidate.warming)candidate.kill();
        InspectionWorker worker=inspectionWorkerPool.get(first);
        worker.lock.lockInterruptibly();
        return worker;
    }
    private final class InspectionWorker implements AutoCloseable {
        final ReentrantLock lock=new ReentrantLock();
        private volatile boolean warming;
        private final Map<ServerConfig.WarmupKey,Integer> warmAttempts=new LinkedHashMap<>();
        private final java.util.Set<ServerConfig.WarmupKey> warmed=new java.util.HashSet<>();
        private long warmRetryAt;
        private final java.util.LinkedHashSet<String> retained=new java.util.LinkedHashSet<>();
        boolean holds(String key){synchronized(retained){return retained.contains(key);}}
        void retainActualKeys(Map<String,Object> metrics) {
            synchronized(retained) {
                retained.clear();
                Object keys=metrics.get("retainedAffinityKeys");
                if(keys instanceof List)for(Object key:(List<?>)keys)
                    if(key instanceof String&&((String)key).matches("[A-Za-z0-9_-]{43}")&&retained.size()<6)retained.add((String)key);
            }
        }
        private volatile Map<String,Object> metrics=Collections.emptyMap();
        private volatile Process process;
        private volatile InspectionProtocolReader reader;
        private volatile OutputStream writer;

        Map<String,Object> request(Path root,WorkerRequest request) throws Exception {
            request.expectedSchemaRegistryDigest=workerSchemaRegistryDigest;
            ensureStarted(root,Boolean.TRUE.equals(request.inspectionSharedConfiguration));
            java.util.concurrent.atomic.AtomicBoolean timedOut=new java.util.concurrent.atomic.AtomicBoolean();
            ScheduledFuture<?> watchdog=inspectionWatchdogs.schedule(()->{
                timedOut.set(true);
                kill();
            },config.inspection.timeoutMs,TimeUnit.MILLISECONDS);
            try {
                writer.write(workerRequestBytes(request));
                writer.write('\n');
                writer.flush();
                JsonNode result=null;
                String errorCode=null;
                Map<String,Object> workerDiagnostic=null;
                long total=0;
                while(result==null) {
                    String line=reader.readLine(config.inspection.maxResponseBytes);
                    if(line==null)throw new IllegalStateException("Package inspection Worker exited before completing the request");
                    if("\u0000OVERSIZED".equals(line))throw new InspectionResponseTooLargeException();
                    total+=line.getBytes(StandardCharsets.UTF_8).length+1L;
                    if(total>config.inspection.maxResponseBytes)throw new InspectionResponseTooLargeException();
                    JsonNode event=JSON.readTree(line);
                    if(event==null||!event.isObject())continue;
                    String eventJob=event.path("jobId").asText(null);
                    if(eventJob!=null&&!eventJob.equals(request.jobId))
                        throw new IllegalStateException("Package inspection Worker returned an event for another request");
                    String kind=event.path("type").asText("");
                    if("DIAGNOSTIC".equalsIgnoreCase(kind)) {
                        errorCode=event.path("code").asText(null);
                        workerDiagnostic=JSON.convertValue(event,Map.class);
                    }
                    if("RESULT".equalsIgnoreCase(kind))result=event;
                }
                if(timedOut.get())throw new InspectionTimeoutException();
                if(result.path("exitCode").asInt(3)!=0&&errorCode==null)
                    throw new IllegalStateException("Package inspection Worker did not complete successfully");
                Map<String,Object> response=new LinkedHashMap<>();
                if(result.path("inspectionMetrics").isObject()) {
                    Map<String,Object> reported=JSON.convertValue(result.path("inspectionMetrics"),Map.class);
                    if(result.path("inspectionMetricsDelta").asBoolean(false)) {
                        Map<String,Object> merged=new LinkedHashMap<>(metrics);merged.putAll(reported);metrics=merged;
                    } else metrics=reported;
                    retainActualKeys(metrics);
                }
                else {metrics=Collections.emptyMap();synchronized(retained){retained.clear();}}
                inspectionMetrics.add(RESPONSE_BYTES,total);
                if(errorCode!=null) {
                    response.put("errorCode",errorCode);
                    if(workerDiagnostic!=null) {
                        Map<String,Object> diagnostic=new LinkedHashMap<>();
                        for(String key:List.of("code","summary","detail","field","file","suggestion","resourceId","sourceFile","packageId",
                                "limitName","scope","outcome","stage","remediationHint","artifactRef")) {
                            Object value=workerDiagnostic.get(key);
                            if(value instanceof String)diagnostic.put(key,value);
                        }
                        for(String key:List.of("line","column","sourceColumn","configuredLimit","limit","observed","actual","usage"))
                            if(workerDiagnostic.get(key) instanceof Number)diagnostic.put(key,workerDiagnostic.get(key));
                        Object summary=diagnostic.get("summary");
                        if(!(summary instanceof String)||((String)summary).isBlank()) {
                            Object message=workerDiagnostic.get("message");
                            if(message instanceof String&&!((String)message).isBlank())diagnostic.put("summary",message);
                        }
                        if(!diagnostic.isEmpty())response.put("diagnostics",Collections.singletonList(diagnostic));
                    }
                    return response;
                }
                JsonNode inspection=result.path("result").path("summary").path("inspection");
                if(!inspection.isObject())throw new IllegalStateException("Package inspection Worker returned no inspection data");
                response.put("inspection",JSON.convertValue(inspection,Map.class));
                return response;
            } catch(Exception error) {
                discard();
                if(timedOut.get())throw new InspectionTimeoutException();
                throw error;
            } finally {
                watchdog.cancel(false);
            }
        }

        private void ensureStarted(Path root,boolean shared) throws Exception {
            if(closing)throw new InterruptedException("Server is shutting down");
            Process current=process;
            if(current!=null&&current.isAlive())return;
            discard();
            if(shared) {
                if(!root.equals(ServerConfig.runtimeConfigRoot(config.runtimeConfigPath)))throw new NotFoundException();
            } else validatePackageRoot(root);
            List<String> classpathEntries=workerClasspath();
            String cp=String.join(java.io.File.pathSeparator,classpathEntries);
            ProcessBuilder builder=new ProcessBuilder(config.javaExecutable.toString(),"-Xmx"+config.inspection.heapMaxMb+"m",
                    "-cp",cp,"att.worker.WorkerMain","--inspection-daemon");
            builder.directory(root.toFile());
            long startupStarted=System.nanoTime();
            Process started=startWorkerProcess(builder);
            inspectionMetrics.record(STARTUP,System.nanoTime()-startupStarted);
            process=started;
            inspectionProcesses.put(started,Boolean.TRUE);
            writer=started.getOutputStream();
            reader=new InspectionProtocolReader(new InputStreamReader(started.getInputStream(),StandardCharsets.UTF_8));
            if(closing){discard();throw new InterruptedException("Server is shutting down");}
            startStderrDrainer(started,"att-server-inspector-stderr");
        }

        private void kill() {
            Process current=process;
            if(current!=null&&current.isAlive())forceTree(current);
        }

        private void discard() {
            Process current=process;
            process=null;
            metrics=Collections.emptyMap();
            synchronized(retained){retained.clear();}
            warmed.clear();
            if(!warming){warmAttempts.clear();warmRetryAt=0;}
            InspectionProtocolReader input=reader;reader=null;
            OutputStream output=writer;writer=null;
            if(current!=null) {
                if(current.isAlive())current.destroyForcibly();
                inspectionProcesses.remove(current);
            }
            try { if(input!=null)input.close(); } catch(Exception ignored) { }
            try { if(output!=null)output.close(); } catch(Exception ignored) { }
        }

        @Override public void close() {
            Process current=process;
            kill();
            try {if(!lock.tryLock(5,TimeUnit.SECONDS)){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Inspection Worker cleanup lock did not quiesce");return;}}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
            try {
                discard();
                if(current!=null)try{current.waitFor(config.gracefulStopMs,TimeUnit.MILLISECONDS);}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            } finally {lock.unlock();}
        }
    }
    private String encodeCursor(String packageId,String type,String query,String sort,String direction,int offset,String revision,String principal) throws Exception {
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("packageId",packageId);payload.put("type",type);payload.put("query",query);payload.put("sort",sort);payload.put("direction",direction);payload.put("offset",offset);payload.put("revision",revision);payload.put("principal",principal);
        byte[] nonce=new byte[12];cursorRandom.nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(cursorKey,"AES"),new GCMParameterSpec(128,nonce));
        byte[] ciphertext=cipher.doFinal(JSON.writeValueAsBytes(payload));byte[] token=new byte[nonce.length+ciphertext.length];
        System.arraycopy(nonce,0,token,0,nonce.length);System.arraycopy(ciphertext,0,token,nonce.length,ciphertext.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
    private void signConfigurationSourceReferences(String packageId,Map<String,Object> source) throws Exception {
        Object rawOrigin=source.get("logicalPath"),rawReferences=source.get("fileReferences");
        if(!(rawOrigin instanceof String)||!(rawReferences instanceof List))return;
        String origin=(String)rawOrigin;
        if(packageId.isEmpty()) {
            ((List<?>)rawReferences).removeIf(raw->!(raw instanceof Map)
                    ||!String.valueOf(((Map<?,?>)raw).get("logicalPath")).startsWith("config/"));
        }
        for(Object raw:(List<?>)rawReferences)if(raw instanceof Map) {
            @SuppressWarnings("unchecked") Map<String,Object> reference=(Map<String,Object>)raw;
            Object target=reference.get("logicalPath");
            if(target instanceof String)reference.put("referenceId",encodeConfigurationSourceReference(packageId,origin,(String)target));
        }
    }
    private String encodeConfigurationSourceReference(String packageId,String origin,String target) throws Exception {
        Map<String,Object> payload=new LinkedHashMap<>();payload.put("kind","configuration-source-reference");
        payload.put("packageId",packageId);payload.put("origin",origin);payload.put("target",target);
        if(packageId.isEmpty())payload.put("scope","shared-configuration");
        byte[] nonce=new byte[12];cursorRandom.nextBytes(nonce);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(cursorKey,"AES"),new GCMParameterSpec(128,nonce));
        byte[] encrypted=cipher.doFinal(JSON.writeValueAsBytes(payload));byte[] token=new byte[nonce.length+encrypted.length];
        System.arraycopy(nonce,0,token,0,nonce.length);System.arraycopy(encrypted,0,token,nonce.length,encrypted.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }
    private JsonNode decodeConfigurationSourceReference(String token) {
        try {
            if(token==null||token.length()>4096)throw new IllegalArgumentException();
            byte[] bytes=Base64.getUrlDecoder().decode(token);if(bytes.length<28)throw new IllegalArgumentException();
            byte[] nonce=java.util.Arrays.copyOfRange(bytes,0,12),encrypted=java.util.Arrays.copyOfRange(bytes,12,bytes.length);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(cursorKey,"AES"),new GCMParameterSpec(128,nonce));
            JsonNode claim=JSON.readTree(cipher.doFinal(encrypted));
            if(!"configuration-source-reference".equals(claim.path("kind").asText())
                    ||(claim.path("packageId").asText().isBlank()&&!"shared-configuration".equals(claim.path("scope").asText()))||claim.path("origin").asText().isBlank()
                    ||claim.path("target").asText().isBlank())throw new IllegalArgumentException();
            return claim;
        } catch(Exception invalid) { throw new NotFoundException(); }
    }
    private Cursor decodeCursor(String token,String packageId,String type,String query,String sort,String direction,String principal) throws Exception {
        if(token.length()>4096)throw new IllegalArgumentException("cursor is too long");byte[] bytes;
        try{bytes=Base64.getUrlDecoder().decode(token);}catch(IllegalArgumentException bad){throw new IllegalArgumentException("cursor is invalid");}
        if(bytes.length<28)throw new IllegalArgumentException("cursor is invalid");
        byte[] nonce=java.util.Arrays.copyOfRange(bytes,0,12),ciphertext=java.util.Arrays.copyOfRange(bytes,12,bytes.length),payload;
        try{Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(cursorKey,"AES"),new GCMParameterSpec(128,nonce));payload=cipher.doFinal(ciphertext);}
        catch(Exception bad){throw new IllegalArgumentException("cursor is invalid");}
        JsonNode value;try{value=JSON.readTree(payload);}catch(Exception bad){throw new IllegalArgumentException("cursor is invalid");}
        if(!packageId.equals(value.path("packageId").asText())||!java.util.Objects.equals(type,value.path("type").isNull()?null:value.path("type").asText())||!query.equals(value.path("query").asText())||!sort.equals(value.path("sort").asText())||!direction.equals(value.path("direction").asText())||!principal.equals(value.path("principal").asText()))throw new IllegalArgumentException("cursor does not match this resource query");
        int offset=value.path("offset").asInt(-1);String revision=value.path("revision").asText("");if(offset<1||offset>20000||!revision.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("cursor is invalid");return new Cursor(offset,revision);
    }
    private static boolean containsControl(String value){for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return true;return false;}
    List<LoadSchedule> loadSchedules() throws Exception {requireSchedulingEnabled();return store.schedules();}
    LoadSchedule loadSchedule(String id) throws Exception {requireSchedulingEnabled();LoadSchedule schedule=store.schedule(id);if(schedule==null)throw new NotFoundException();return schedule;}
    /** List responses stay summary-only; detail may add an owner-scoped editable projection. */
    Map<String,Object> loadScheduleListView(LoadSchedule schedule,String principal) {
        return loadScheduleView(schedule,principal,false);
    }
    Map<String,Object> loadScheduleView(LoadSchedule schedule,String principal) {
        return loadScheduleView(schedule,principal,true);
    }
    private Map<String,Object> loadScheduleView(LoadSchedule schedule,String principal,boolean detail) {
        Map<String,Object> view=loadScheduleView(schedule);
        if(schedule.sourceJobId!=null)try {ownedExecutionSpec(schedule.sourceJobId,principal);view.put("sourceJobId",schedule.sourceJobId);}catch(Exception unavailable){}
        if(schedule.savedScenarioId!=null)try {
            if(store.savedLoadScenario(schedule.savedScenarioId,savedScenarioOwner(principal))!=null) {
                view.put("savedScenarioId",schedule.savedScenarioId);view.put("savedScenarioRevision",schedule.savedScenarioRevision);
            }
        } catch(Exception unavailable) { /* provenance is private and optional */ }
        if(detail && canDiscloseExecutionSettings(schedule,principal)) putExecutionSettings(view,schedule);
        return view;
    }
    private static boolean canDiscloseExecutionSettings(LoadSchedule schedule,String principal) {
        if(principal==null || principal.isBlank()) return false;
        if(principal.equals(schedule.createdBy)) return true;
        return schedule.executionPrincipal!=null && !schedule.executionPrincipal.isBlank() && principal.equals(schedule.executionPrincipal);
    }
    private static void putExecutionSettings(Map<String,Object> view,LoadSchedule schedule) {
        if(schedule.inlineScenario==null) return;
        Map<String,Object> settings=new LinkedHashMap<>();
        for(String field:List.of("thresholds","evidence","testdata","execution","seed")) {
            Object value=schedule.inlineScenario.get(field);
            if(value!=null)settings.put(field,value);
        }
        if(!settings.isEmpty())view.put("executionSettings",settings);
    }
    Map<String,Object> loadScheduleView(LoadSchedule schedule) {
        Map<String,Object> view=JSON.convertValue(schedule,Map.class);
        view.remove("sourceJobId");view.remove("savedScenarioId");view.remove("savedScenarioRevision");view.remove("runInput");view.remove("listSummary");view.put("executionType","load".equals(schedule.effectiveJobType())?"load":"run");
        view.put("revisionPolicy",schedule.expectedRevisionDigest!=null?effectivePackageChangePolicy(schedule):"validate-current-package");
        view.put("effectivePackageChangePolicy",effectivePackageChangePolicy(schedule));view.put("serverPackageChangePolicy",config.scheduling.packageChangePolicy);
        view.put("storedRevisionDigest",schedule.expectedRevisionDigest);view.remove("packageRootIdentity");
        view.remove("inlineScenario");view.remove("inlineDebugInput");view.remove("debugResourceId");view.remove("expectedRevisionDigest");view.remove("catalogSnapshot");
        view.put("jobType",schedule.effectiveJobType());
        view.put("targetIdentity",schedule.inlineScenario==null?(schedule.listSummary==null?Map.of():schedule.listSummary):JobListIdentity.load(JSON.valueToTree(schedule.inlineScenario)));
        view.put("inlineSnapshot",Boolean.valueOf(schedule.inlineScenario!=null||schedule.inlineDebugInput!=null||schedule.runInput!=null));
        if(schedule.runInput!=null) {
            Object target=schedule.runInput.get("target");
            if(target instanceof Map)view.put("debugTarget",target);
            else view.put("selectedCaseIds",schedule.runInput.get("caseIds"));
            Map<String,Object> runOptions=new LinkedHashMap<>();
            Object options=schedule.runInput.get("options");
            if(options instanceof Map<?,?> map) {
                Object debug=map.get("debug"); if(debug instanceof Boolean)runOptions.put("debug",debug);
                Object failFast=map.get("failFast"); if(failFast instanceof Boolean)runOptions.put("failFast",failFast);
            }
            Object diagnostic=schedule.runInput.get("diagnosticMode");
            if(diagnostic instanceof Boolean && !runOptions.containsKey("debug"))runOptions.put("debug",diagnostic);
            if(!runOptions.containsKey("debug"))runOptions.put("debug",Boolean.FALSE);
            if(!runOptions.containsKey("failFast"))runOptions.put("failFast",Boolean.FALSE);
            view.put("runOptions",runOptions);
        }
        if("debug".equals(schedule.effectiveJobType()))view.put("debugTarget",Map.of("type",schedule.debugTargetType,"id",schedule.debugTargetId));
        return view;
    }
    List<Map<String,Object>> loadScheduleExecutions(String id,int limit) throws Exception {requireSchedulingEnabled();if(store.schedule(id)==null&&store.scheduleExecutions(id,1).isEmpty())throw new NotFoundException();return store.scheduleExecutions(id,limit);}
    LoadSchedule createLoadSchedule(JsonNode input,String principal) throws Exception {
        requireSchedulingEnabled();
        requireLocalOperator(principal);
        AdvancedLoadDraftState savedDraft=null;
        if(input.hasNonNull("draftId")) synchronized(draftLock) {
            expireDrafts();AdvancedLoadDraftState candidate=advancedLoadDrafts.get(input.path("draftId").asText());
            if(candidate!=null&&candidate.principal.equals(principal)&&candidate.savedScenarioId!=null)savedDraft=candidate;
        }
        LoadSchedule schedule=buildLoadSchedule(input,null,principal);schedule.id="S"+UUID.randomUUID().toString().replace("-","").toUpperCase();schedule.createdBy=principal;schedule.executionPrincipal=principal;schedule.createdAt=clock.instant().toString();
        if(input.hasNonNull("draftId"))for(ReplayReview review:replayReviews.values())if(review.draftId.equals(input.path("draftId").asText())&&review.principal.equals(principal)) {
            ownedExecutionSpec(review.source,principal);
            if(!review.expiresAt.isAfter(clock.instant()))throw new NotFoundException();
            schedule.sourceJobId=review.source;
            JobExecutionSpec spec=JobExecutionSpec.parse(store.executionSpec(review.source).get("spec"));
            verifyPackageRevision(schedule.packageId,schedule.environment,spec.revision());
            schedule.expectedRevisionDigest=spec.revision();
        }
        if(savedDraft==null)store.createSchedule(schedule,config.scheduling.maxSchedules);
        else synchronized(draftLock) {
            requireLiveDraft(savedDraft.expiresAt);
            if(advancedLoadDrafts.get(savedDraft.id)!=savedDraft)throw new StaleDraftException();
            schedule.savedScenarioId=savedDraft.savedScenarioId;schedule.savedScenarioRevision=savedDraft.savedScenarioRevision;
            store.createScheduleFromSaved(schedule,savedDraft.savedScenarioOwner,savedDraft.savedScenarioRevision,config.scheduling.maxSchedules);
        }
        store.audit(principal,"SCHEDULE_CREATE",null,schedule.packageId,"CREATED");return schedule;
    }
    Map<String,Object> previewLoadSchedule(JsonNode input) {
        if(input==null||!input.isObject())throw new IllegalArgumentException("A JSON object is required");
        input.fieldNames().forEachRemaining(key->{if(!Set.of("timezone","cron","runAt").contains(key))throw new IllegalArgumentException("Unknown preview field: "+key);});
        if(input.hasNonNull("runAt")) {
            if(input.hasNonNull("cron"))throw new IllegalArgumentException("Provide exactly one cron or runAt trigger");
            String fire=futureRunAt(fieldText(input,"runAt",null,true));
            String zone=fieldText(input,"timezone","UTC",false);scheduleZone(zone);
            return Map.of("timezone",zone,"runAt",fire,"nextRunAt",fire,"nextOccurrences",List.of(fire));
        }
        String timezone=fieldText(input,"timezone",null,true),cronText=fieldText(input,"cron",null,true);
        java.time.ZoneId zone=scheduleZone(timezone);CronExpression cron=CronExpression.parse(cronText,config.scheduling.minimumIntervalMinutes);
        cron.validateTimezoneMinimumInterval(zone,config.scheduling.minimumIntervalMinutes,clock.instant());
        List<String> nextOccurrences=new ArrayList<>();Instant cursor=clock.instant();
        for(int i=0;i<5;i++){cursor=cron.nextAfter(cursor,zone);nextOccurrences.add(cursor.toString());}
        return Map.of("timezone",timezone,"cron",cronText,"nextRunAt",nextOccurrences.get(0),"nextOccurrences",List.copyOf(nextOccurrences));
    }
    /** Validate a proposed execution change without starting a job or changing the schedule. */
    Map<String,Object> createScheduleExecutionDraft(String id,JsonNode input,String principal) throws Exception {
        requireSchedulingEnabled();requireLocalOperator(principal);requireObject(input,"A JSON object is required");
        requireOnlyFields(input,"expectedDefinitionVersion","overrides","packageEdit");
        JsonNode expected=input.get("expectedDefinitionVersion");
        if(expected==null||!expected.isIntegralNumber()||!expected.canConvertToLong()||expected.longValue()<0)
            throw new IllegalArgumentException("expectedDefinitionVersion must be a non-negative integer");
        LoadSchedule source=store.schedule(id);if(source==null)throw new NotFoundException();
        boolean shared=!principal.equals(source.createdBy)&&!principal.equals(source.executionPrincipal);
        try { return createScheduleExecutionDraftForSource(id,input,principal,expected.longValue(),source,shared); }
        catch(ScheduleSourceChangedException conflict) { throw conflict; }
        catch(InspectionConfigurationException | InspectionValidationException invalid) {
            if(!shared)throw invalid;
            throw new IllegalArgumentException("The proposed execution settings could not be validated. Check the edited fields and package configuration.");
        }
        catch(IllegalArgumentException invalid) {
            if(!shared)throw invalid;
            throw new IllegalArgumentException("The proposed execution settings could not be validated. Check the edited fields and package configuration.");
        }
    }
    private Map<String,Object> createScheduleExecutionDraftForSource(String id,JsonNode input,String principal,long expected,LoadSchedule source,boolean shared) throws Exception {
        if(source.definitionVersion!=expected)throw new ScheduleConflictException();
        if(!Set.of("run","load").contains(source.effectiveJobType()))throw new IllegalArgumentException("This legacy schedule type cannot be edited here");
        if("load".equals(source.effectiveJobType())&&source.inlineScenario==null) {
            if(input.has("overrides"))throw new IllegalArgumentException("Package-backed schedules use packageEdit, not snapshot overrides");
            JsonNode edit=input.get("packageEdit");requireObject(edit,"packageEdit must contain the package scenario settings");
            requireOnlyFields(edit,"scenario","load","environment");
            LoadSchedule candidate=buildLoadSchedule(edit,source,principal);
            String revision=candidate.expectedRevisionDigest;
            if(revision==null)throw new IllegalArgumentException("A legacy package-backed schedule must first establish a package revision");
            verifyPackageRevision(source.packageId,candidate.environment,revision);
            LoadSchedule latest=store.schedule(id);if(latest==null||latest.definitionVersion!=source.definitionVersion)throw new ScheduleConflictException();
            String reviewId="P"+UUID.randomUUID().toString().replace("-","").toUpperCase();Instant expires=clock.instant().plusMillis(DRAFT_TTL_MILLIS);
            ScheduleExecutionReview review=new ScheduleExecutionReview(id,principal,source.definitionVersion,source.updatedAt,reviewId,source.packageId,"load",revision,expires,edit.deepCopy());
            synchronized(draftLock) {scheduleExecutionReviews.entrySet().removeIf(entry->!entry.getValue().expiresAt().isAfter(clock.instant()));if(scheduleExecutionReviews.size()>=MAX_ACTIVE_DRAFTS||scheduleExecutionReviews.values().stream().filter(item->item.principal().equals(principal)).count()>=MAX_DRAFTS_PER_PRINCIPAL)throw new DraftCapacityException();scheduleExecutionReviews.put(reviewId,review);}
            Map<String,Object> result=new LinkedHashMap<>();result.put("draftId",reviewId);result.put("expiresAt",expires.toString());result.put("definitionVersion",source.definitionVersion);
            result.put("snapshotVersion",source.snapshotVersion);result.put("nextRunAt",source.nextRunAt);result.put("revisionDigest",revision);result.put("sourcePolicy",effectivePackageChangePolicy(source));
            result.put("originalPreview",Map.of("previewYaml",JSON.writeValueAsString(source.listSummary==null?Map.of():source.listSummary)));
            result.put("preview",Map.of("previewYaml",JSON.writeValueAsString(candidate.listSummary==null?Map.of():candidate.listSummary)));return result;
        }
        if(input.has("packageEdit"))throw new IllegalArgumentException("Snapshot schedules cannot switch to a package source");
        if(source.expectedRevisionDigest==null)throw new IllegalArgumentException("This legacy schedule has no retained revision; create a new schedule");
        ObjectNode definition;
        if("run".equals(source.effectiveJobType())) {
            ObjectNode retained=JSON.valueToTree(source.runInput);retained.put("expectedRevisionDigest",source.expectedRevisionDigest);
            JobExecutionSpec spec=capturePrevalidatedExecutionSpec("run",retained,null);if(spec==null)throw new NotFoundException();definition=spec.definition();
        } else {
            definition=JSON.createObjectNode();definition.put("executionType","load");definition.put("packageId",source.packageId);
            if(source.environment!=null)definition.put("environment",source.environment);definition.set("scenario",JSON.valueToTree(source.inlineScenario));
        }
        ObjectNode proposed=JobReplayOverrides.apply(definition,input.get("overrides"));
        Map<String,Object> originalPreview,validated;
        if("run".equals(source.effectiveJobType()))originalPreview=createRunDraft(definition,principal,true,null);
        else {ObjectNode originalInput=definition.deepCopy();originalInput.remove("executionType");originalPreview=source.catalogSnapshot?createCatalogScheduleReviewDraft(originalInput,source,principal):createAdvancedLoadDraft(originalInput,principal,null);}
        String originalDraftId=String.valueOf(originalPreview.remove("draftId"));
        synchronized(draftLock) {runDrafts.remove(originalDraftId);advancedLoadDrafts.remove(originalDraftId);catalogLoadDrafts.remove(originalDraftId);}
        if("run".equals(source.effectiveJobType()))validated=createRunDraft(proposed,principal,true,null);
        else {proposed.remove("executionType");validated=source.catalogSnapshot?createCatalogScheduleReviewDraft(proposed,source,principal):createAdvancedLoadDraft(proposed,principal,null);}
        String draftId=String.valueOf(validated.get("draftId"));String revision=String.valueOf(validated.get("revisionDigest"));
        if(!revision.equals(String.valueOf(originalPreview.get("revisionDigest"))))throw new StaleDraftException();
        if(!revision.matches("[a-f0-9]{64}"))throw new IllegalStateException("Schedule validation did not return a revision");
        if(!"ignore".equals(effectivePackageChangePolicy(source))&&!revision.equals(source.expectedRevisionDigest))throw new ScheduleSourceChangedException(source.expectedRevisionDigest,revision,effectivePackageChangePolicy(source));
        LoadSchedule latest=store.schedule(id);
        if(latest==null||latest.definitionVersion!=source.definitionVersion)throw new ScheduleConflictException();
        Instant expires=Instant.parse(String.valueOf(validated.get("expiresAt")));
        String reviewId="P"+UUID.randomUUID().toString().replace("-","").toUpperCase();
        ScheduleExecutionReview review=new ScheduleExecutionReview(id,principal,source.definitionVersion,source.updatedAt,draftId,source.packageId,source.effectiveJobType(),revision,expires,null);
        synchronized(draftLock) {scheduleExecutionReviews.entrySet().removeIf(entry->!entry.getValue().expiresAt().isAfter(clock.instant()));scheduleExecutionReviews.put(reviewId,review);}
        validated.remove("draftId");
        if(shared) {
            originalPreview=Map.of("previewYaml",JSON.writeValueAsString(JobListIdentity.from(definition)));
            JsonNode candidateDefinition=proposed.deepCopy();
            if("load".equals(source.effectiveJobType())) ((ObjectNode)candidateDefinition).put("executionType","load");
            validated=Map.of("previewYaml",JSON.writeValueAsString(JobListIdentity.from(candidateDefinition)));
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("draftId",reviewId);result.put("expiresAt",expires.toString());result.put("definitionVersion",source.definitionVersion);
        result.put("snapshotVersion",source.snapshotVersion);result.put("nextRunAt",source.nextRunAt);result.put("revisionDigest",revision);
        result.put("sourcePolicy",effectivePackageChangePolicy(source));result.put("originalPreview",originalPreview);result.put("preview",validated);return result;
    }

    /** Revalidates edits to an existing catalog snapshot under the package-backed replay limits. */
    private Map<String,Object> createCatalogScheduleReviewDraft(ObjectNode definition,LoadSchedule source,String principal) throws Exception {
        String packageId=required(definition,"packageId");
        if(!source.packageId.equals(packageId))throw new IllegalArgumentException("Schedule package cannot be changed here");
        JsonNode scenarioNode=definition.get("scenario");requireObject(scenarioNode,"A retained Load scenario is required");
        Path root=packageRoot(packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        Map<String,Object> configuration=resolveExecutionConfiguration(packageId,root,optionalText(definition,"environment"));
        String environment=(String)configuration.get("environment");
        LoadSchedule context=source.copy();context.environment=environment;
        @SuppressWarnings("unchecked") Map<String,Object> scenario=(Map<String,Object>)JSON.convertValue(scenarioNode,Map.class);
        ValidatedScheduledScenario validated=validateScheduledScenario(root,context,scenario,false);
        validatePackageBackedReplayLoadLimits(config.inlineLoad,validated.scenario);
        if(validated.containsSensitiveValues||validated.containsSensitiveAuthoredValues)throw new IllegalArgumentException("A schedule cannot store classified sensitive values");
        Map<String,Object> safeSummary=JobListIdentity.load(JSON.valueToTree(validated.scenario));
        configuration=configurationRevision(configuration,validated.revisionDigest);
        CatalogLoadDraftState draft=new CatalogLoadDraftState("C"+UUID.randomUUID().toString().replace("-","").toUpperCase(),
                principal,packageId,environment,null,validated.revisionDigest,validated.model,validated.scenario,
                JSON.writeValueAsString(safeSummary),configuration,clock.instant().plusMillis(DRAFT_TTL_MILLIS));
        synchronized(draftLock){expireDrafts();checkDraftCapacity(principal);catalogLoadDrafts.put(draft.id,draft);}
        return catalogLoadDraftView(draft);
    }

    LoadSchedule replaceScheduleExecution(String id,JsonNode input,String principal) throws Exception {
        requireSchedulingEnabled();requireLocalOperator(principal);requireObject(input,"A JSON object is required");
        requireOnlyFields(input,"draftId","expectedDefinitionVersion","confirm");
        if(!input.path("confirm").isBoolean()||!input.path("confirm").booleanValue())throw new IllegalArgumentException("Review confirmation is required");
        String draftId=required(input,"draftId");JsonNode expected=input.get("expectedDefinitionVersion");
        if(expected==null||!expected.isIntegralNumber()||!expected.canConvertToLong())throw new IllegalArgumentException("expectedDefinitionVersion must be an integer");
        LoadSchedule source=store.schedule(id);if(source==null)throw new NotFoundException();
        boolean shared=!principal.equals(source.createdBy)&&!principal.equals(source.executionPrincipal);
        try { return replaceScheduleExecutionReviewed(id,principal,draftId,expected.longValue()); }
        catch(ScheduleSourceChangedException conflict) { throw conflict; }
        catch(InspectionConfigurationException | InspectionValidationException invalid) {
            if(!shared)throw invalid;
            throw new IllegalArgumentException("The proposed execution settings could not be validated. Check the edited fields and package configuration.");
        }
        catch(IllegalArgumentException invalid) {
            if(!shared)throw invalid;
            throw new IllegalArgumentException("The proposed execution settings could not be validated. Check the edited fields and package configuration.");
        }
    }
    private LoadSchedule replaceScheduleExecutionReviewed(String id,String principal,String draftId,long expected) throws Exception {
        synchronized(draftLock) {
            ScheduleExecutionReview review=scheduleExecutionReviews.get(draftId);
            if(review==null||!review.scheduleId().equals(id)||!review.principal().equals(principal))throw new NotFoundException();
            requireLiveDraft(review.expiresAt());
            LoadSchedule prior=store.schedule(id);if(prior==null)throw new NotFoundException();
            if(expected!=review.definitionVersion()||prior.definitionVersion!=review.definitionVersion()||!java.util.Objects.equals(prior.updatedAt,review.updatedAt()))throw new ScheduleConflictException();
            if(review.packageEdit()!=null) {
                LoadSchedule replacement=buildLoadSchedule(review.packageEdit(),prior,principal);
                if(replacement.inlineScenario!=null||!review.revision().equals(replacement.expectedRevisionDigest))throw new StaleDraftException();
                String executionPrincipal=prior.executionPrincipal==null||prior.executionPrincipal.isBlank()?prior.createdBy:prior.executionPrincipal;
                validateScheduleForActivation(replacement,executionPrincipal);
                verifyPackageRevision(review.packageId(),replacement.environment,review.revision());
                replacement.snapshotVersion=prior.snapshotVersion+1;replacement.updatedAt=clock.instant().toString();replacement.updatedBy=principal;
                List<Map<String,Object>> history=new ArrayList<>(prior.snapshotHistory==null?List.of():prior.snapshotHistory);
                history.add(Map.of("previousVersion",prior.snapshotVersion,"version",replacement.snapshotVersion,"actor",principal,"at",replacement.updatedAt,"revisionDigest",review.revision(),"changeSummary","Package scenario settings replaced"));
                if(history.size()>20)history=new ArrayList<>(history.subList(history.size()-20,history.size()));replacement.snapshotHistory=history;
                requireLiveDraft(review.expiresAt());verifyPackageRevision(review.packageId(),replacement.environment,review.revision());
                LoadSchedule saved=store.updateScheduleAuthorized(replacement,prior.updatedAt,"local".equals(config.authMode)?principal:null);if(saved==null)throw new NotFoundException();
                scheduleExecutionReviews.remove(draftId);store.audit(principal,"SCHEDULE_EXECUTION_REPLACE",null,saved.packageId,"version="+prior.snapshotVersion+"->"+saved.snapshotVersion);return saved;
            }
            String executionDraftId=review.draftId();
            verifyPackageRevision(review.packageId(),retainedDraftEnvironment(executionDraftId,principal,review.packageId()),review.revision());
            ObjectNode build=JSON.createObjectNode();build.put("name",prior.name);build.put("packageId",prior.packageId);build.put("jobType",prior.effectiveJobType());
            build.put("draftId",executionDraftId);build.put("timezone",prior.timezone);build.put("runAt",clock.instant().plusSeconds(86400).toString());build.put("enabled",false);
            LoadSchedule execution=buildLoadSchedule(build,null,principal);
            if(!review.executionType().equals(execution.effectiveJobType())||!review.revision().equals(execution.expectedRevisionDigest))throw new StaleDraftException();
            LoadSchedule replacement=prior.copy();replacement.runInput=execution.runInput;replacement.inlineScenario=execution.inlineScenario;
            replacement.inlineDebugInput=execution.inlineDebugInput;replacement.debugTargetType=execution.debugTargetType;replacement.debugTargetId=execution.debugTargetId;replacement.debugResourceId=execution.debugResourceId;
            replacement.catalogSnapshot=execution.catalogSnapshot;replacement.loadModel=execution.loadModel;replacement.listSummary=execution.listSummary;
            replacement.expectedRevisionDigest=execution.expectedRevisionDigest;replacement.configuration=execution.configuration;replacement.environment=execution.environment;replacement.config=execution.config;
            replacement.savedScenarioId=null;replacement.savedScenarioRevision=null;replacement.sourceJobId=null;
            String executionPrincipal=prior.executionPrincipal==null||prior.executionPrincipal.isBlank()?prior.createdBy:prior.executionPrincipal;
            validateScheduleForActivation(replacement,executionPrincipal);
            replacement.snapshotVersion=prior.snapshotVersion+1;replacement.updatedAt=clock.instant().toString();replacement.updatedBy=principal;
            List<Map<String,Object>> history=new ArrayList<>(prior.snapshotHistory==null?List.of():prior.snapshotHistory);
            history.add(Map.of("previousVersion",prior.snapshotVersion,"version",replacement.snapshotVersion,"actor",principal,"at",replacement.updatedAt,"revisionDigest",review.revision(),"changeSummary",review.executionType().toUpperCase(java.util.Locale.ROOT)+" execution snapshot replaced"));
            if(history.size()>20)history=new ArrayList<>(history.subList(history.size()-20,history.size()));replacement.snapshotHistory=history;
            requireLiveDraft(review.expiresAt());verifyPackageRevision(review.packageId(),replacement.environment,review.revision());
            LoadSchedule saved=store.updateScheduleAuthorized(replacement,prior.updatedAt,"local".equals(config.authMode)?principal:null);if(saved==null)throw new NotFoundException();
            scheduleExecutionReviews.remove(draftId);store.audit(principal,"SCHEDULE_EXECUTION_REPLACE",null,saved.packageId,"version="+prior.snapshotVersion+"->"+saved.snapshotVersion);return saved;
        }
    }

    LoadSchedule updateLoadSchedule(String id,JsonNode input,String principal) throws Exception {
        requireLocalOperator(principal);
        requireSchedulingEnabled();LoadSchedule prior=store.schedule(id);if(prior==null)throw new NotFoundException();
        LoadSchedule schedule=buildLoadSchedule(input,prior,principal);schedule.id=id;schedule.createdBy=prior.createdBy;schedule.createdAt=prior.createdAt;
        schedule.updatedBy=principal;schedule.updatedAt=clock.instant().toString();
        if("load".equals(prior.effectiveJobType())&&prior.inlineScenario==null&&
                (!java.util.Objects.equals(prior.scenario,schedule.scenario)||!java.util.Objects.equals(prior.load,schedule.load)
                 ||!java.util.Objects.equals(prior.packageId,schedule.packageId)||!java.util.Objects.equals(prior.environment,schedule.environment))) {
            schedule.snapshotVersion=prior.snapshotVersion+1;
            List<Map<String,Object>> history=new ArrayList<>(prior.snapshotHistory==null?List.of():prior.snapshotHistory);
            Map<String,Object> event=new LinkedHashMap<>();event.put("previousVersion",prior.snapshotVersion);event.put("version",schedule.snapshotVersion);event.put("actor",principal);event.put("at",schedule.updatedAt);event.put("changeSummary","Package scenario settings replaced");
            if(schedule.expectedRevisionDigest!=null)event.put("revisionDigest",schedule.expectedRevisionDigest);history.add(event);
            if(history.size()>20)history=new ArrayList<>(history.subList(history.size()-20,history.size()));schedule.snapshotHistory=history;
        }
        if(!prior.enabled&&schedule.enabled||!java.util.Objects.equals(prior.cron,schedule.cron)||!LoadSchedule.sameInstant(prior.runAt,schedule.runAt)||!prior.timezone.equals(schedule.timezone))
            schedule.nextRunAt=schedule.runAt==null?nextRun(schedule.cron,schedule.timezone,clock.instant()).toString():futureRunAt(schedule.runAt);
        else schedule.nextRunAt=prior.nextRunAt;
        LoadSchedule updated=store.updateSchedule(schedule,prior.updatedAt);if(updated==null)throw new NotFoundException();
        store.audit(principal,"SCHEDULE_UPDATE",null,schedule.packageId,"UPDATED");return updated;
    }
    LoadSchedule setLoadScheduleEnabled(String id,boolean enabled,String principal) throws Exception {
        requireLocalOperator(principal);
        requireSchedulingEnabled();Instant now=clock.instant();LoadSchedule schedule;
        LoadSchedule current=store.schedule(id);if(current==null)throw new NotFoundException();
        if(enabled)validateScheduleForActivation(current,principal);
        String next=enabled?(current.runAt==null?nextRun(current.cron,current.timezone,now).toString():futureRunAt(current.runAt)):null;
        synchronized(store) {
            LoadSchedule latest=store.schedule(id);if(latest==null)throw new NotFoundException();
            if(!java.util.Objects.equals(current.updatedAt,latest.updatedAt)||current.definitionVersion!=latest.definitionVersion)throw new ScheduleConflictException();
            schedule=store.setScheduleEnabled(id,enabled,principal,now.toString(),next);
        }
        if(schedule==null)throw new NotFoundException();
        store.audit(principal,enabled?"SCHEDULE_RESUME":"SCHEDULE_PAUSE",null,schedule.packageId,enabled?"ENABLED":"PAUSED");
        return schedule;
    }
    void deleteLoadSchedule(String id,String principal) throws Exception {requireSchedulingEnabled();requireLocalOperator(principal);LoadSchedule schedule=store.schedule(id);if(schedule==null)throw new NotFoundException();store.deleteSchedule(id);store.audit(principal,"SCHEDULE_DELETE",null,schedule.packageId,"DELETED");}
    Map<String,Object> triggerLoadSchedule(String id,String principal) throws Exception {
        requireSchedulingEnabled();requireLocalOperator(principal);
        LoadSchedule schedule=store.authorizeManualScheduleExecution(id,principal,"local".equals(config.authMode),clock.instant().toString());
        if(schedule==null)throw new NotFoundException();
        String executionPrincipal=schedule.executionPrincipal==null||schedule.executionPrincipal.isBlank()?schedule.createdBy:schedule.executionPrincipal;
        Map<String,Object> occurrence=loadScheduleScheduler.trigger(schedule,executionPrincipal,principal);
        store.audit(principal,"SCHEDULE_TRIGGER",String.valueOf(occurrence.get("jobId")),schedule.packageId,String.valueOf(occurrence.get("status")));
        return occurrence;
    }

    private void requireLocalOperator(String principal) throws Exception {
        if("local".equals(config.authMode)&&!store.isActiveUser(principal))throw new NotFoundException();
    }

    private Map<String,Object> submitScheduledJob(LoadSchedule schedule,String occurrenceId,String executionPrincipal) throws Exception {
        Map<String,Object> occurrence=store.scheduleExecution(occurrenceId);
        String triggeredBy=occurrence==null?null:(String)occurrence.get("triggeredBy");
        String actualExecutionPrincipal=executionPrincipal==null||executionPrincipal.isBlank()
                ?(schedule.executionPrincipal==null||schedule.executionPrincipal.isBlank()?schedule.createdBy:schedule.executionPrincipal)
                :executionPrincipal;
        JobProvenance provenance=new JobProvenance(schedule.id,schedule.createdBy,actualExecutionPrincipal,triggeredBy,triggeredBy);
        schedule=prepareScheduledExecution(schedule,executionPrincipal);
        Map<String,Object> accepted="run".equals(schedule.effectiveJobType())
                ?submitScheduledRun(schedule,occurrenceId,actualExecutionPrincipal,provenance)
                :"debug".equals(schedule.effectiveJobType())
                ? submitScheduledDebug(schedule,occurrenceId,actualExecutionPrincipal,provenance)
                : submitScheduledLoad(schedule,occurrenceId,actualExecutionPrincipal,provenance);
        store.audit(executionPrincipal,"SCHEDULE_EXECUTE",String.valueOf(accepted.get("jobId")),schedule.packageId,"ACCEPTED");
        return accepted;
    }

    private Map<String,Object> submitScheduledRun(LoadSchedule schedule,String occurrenceId,String executionPrincipal,JobProvenance provenance) throws Exception {
        ObjectNode input=JSON.valueToTree(schedule.runInput);input.set("configurationProvenance",JSON.valueToTree(schedule.configuration));applyInspectionPolicy(input,schedule.packageId);
        JobExecutionSpec retained=capturePrevalidatedExecutionSpec("run",input,null);
        if(retained!=null)retained.withScheduleSnapshot(schedule.id,schedule.snapshotVersion);
        return submit("run",input,executionPrincipal,true,occurrenceId,true,null,null,retained,false,provenance);
    }
    private void validateScheduledRun(LoadSchedule schedule) throws Exception {
        Path root=packageRoot(schedule.packageId);if(root==null)throw new NotFoundException();validatePackageRoot(root);
        if(schedule.runInput==null||schedule.expectedRevisionDigest==null)throw new IllegalArgumentException("Scheduled RUN snapshot is incomplete");
        if(schedule.inlineDebugInput!=null){validateScheduledDebugInput(root,schedule);return;}
        WorkerRequest request=JSON.convertValue(schedule.runInput,WorkerRequest.class);
        request=applyWorkerPolicy(request);request.protocolVersion="att-worker/v1";request.command="inspect";
        request.jobId="I"+UUID.randomUUID().toString().replace("-","");request.packageRoot=root.toString();
        request.config=config.runtimeConfigPath.toString();request.inspectionAction="run-input";
        ObjectNode policy=JSON.createObjectNode();applyInspectionPolicy(policy,schedule.packageId);JSON.readerForUpdating(request).readValue(policy);
        Map<String,Object> inspected=executeInspection(root,request);
        if(!(inspected.get("selectedCaseIds") instanceof List<?> ids)||!(schedule.runInput.get("caseIds") instanceof List<?> saved)
                ||!new java.util.HashSet<>(ids).equals(new java.util.HashSet<>(saved)))throw new IllegalArgumentException("Scheduled RUN case selection changed; review a replacement schedule");
        requireScheduleRevision(schedule,String.valueOf(inspected.get("revisionDigest")));
    }

    private String effectivePackageChangePolicy(LoadSchedule schedule){return schedule.packageChangePolicy==null?config.scheduling.packageChangePolicy:schedule.packageChangePolicy;}
    private static String packageRootIdentity(Path root) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(root.toRealPath().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private void requireScheduleRevision(LoadSchedule schedule,String revision) {
        if(schedule.expectedRevisionDigest!=null&&!schedule.expectedRevisionDigest.equals(revision))
            throw new ScheduleSourceChangedException(schedule.expectedRevisionDigest,revision,effectivePackageChangePolicy(schedule));
    }
    private void validateScheduleForActivation(LoadSchedule schedule,String principal) throws Exception {LoadSchedule checked=prepareScheduledExecution(schedule,principal);schedule.listSummary=checked.listSummary;}

    /** Returns an execution-only copy. Stored inputs and their original revision remain immutable. */
    private LoadSchedule prepareScheduledExecution(LoadSchedule original,String principal) throws Exception {
        if("local".equals(config.authMode)) {
            if(!store.isActiveUser(principal))throw new IllegalArgumentException("Schedule owner is no longer authorized");
            String automaticPrincipal=original.executionPrincipal==null||original.executionPrincipal.isBlank()?original.createdBy:original.executionPrincipal;
            if(!store.isActiveUser(automaticPrincipal))
                throw new IllegalArgumentException("Schedule owner is no longer authorized");
            if(original.executionIdentityMigratedFrom==null&&!store.isActiveUser(original.createdBy))
                throw new IllegalArgumentException("Schedule owner is no longer authorized");
        }
        Path root=packageRoot(original.packageId);
        if(root==null||!Files.isDirectory(root,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Configured schedule package is no longer available");
        validatePackageRoot(root);
        if(original.packageRootIdentity!=null&&!original.packageRootIdentity.equals(packageRootIdentity(root)))throw new IllegalArgumentException("Schedule package registration changed; review a replacement schedule");
        LoadSchedule execution=original.copy();
        if(original.expectedRevisionDigest!=null) {
            WorkerRequest request=inspectionRequest(original.packageId,root,original.environment);
            request.config=config.runtimeConfigPath.toString();request.inspectionAction="revision";
            String current=String.valueOf(executeInspection(root,request).get("revisionDigest"));
            if(!current.matches("[a-f0-9]{64}"))throw new IllegalStateException("Schedule revision validation did not return a revision");
            if(!current.equals(original.expectedRevisionDigest)) {
                String policy=effectivePackageChangePolicy(original);
                if(!"ignore".equals(policy))throw new ScheduleSourceChangedException(original.expectedRevisionDigest,current,policy);
                // Legacy records lack the registration identity needed to safely opt into a different source.
                if(original.packageRootIdentity==null)throw new IllegalArgumentException("This legacy schedule requires a reviewed replacement before revalidating a changed package");
                execution.expectedRevisionDigest=current;
                if(execution.runInput!=null)execution.runInput.put("expectedRevisionDigest",current);
                ValidatedScheduledScenario validated=validateScheduleInputs(root,execution);
                if(validated!=null){execution.inlineScenario=deepMap(validated.scenario);execution.expectedRevisionDigest=validated.revisionDigest;execution.validatedSensitiveSource=validated.containsSensitiveValues;}
                store.recordScheduleSourceChange(original,current,false,clock.instant().toString());
                store.audit(principal,"SCHEDULE_SOURCE_REVALIDATED",null,original.packageId,"schedule="+original.id+";stored="+original.expectedRevisionDigest+";current="+current+";policy="+policy+";source="+(original.packageChangePolicy==null?"server":"schedule"));
                refreshScheduleConfiguration(execution,original,root);
                return execution;
            }
        }
        ValidatedScheduledScenario validated=validateScheduleInputs(root,execution);
        if(validated!=null){execution.inlineScenario=deepMap(validated.scenario);execution.expectedRevisionDigest=validated.revisionDigest;execution.validatedSensitiveSource=validated.containsSensitiveValues;}
        if("package-revision-changed".equals(original.sourceStatus))
            store.recordScheduleSourceRestored(original,original.expectedRevisionDigest,clock.instant().toString());
        if(execution.configuration==null)refreshScheduleConfiguration(execution,original,root);
        return execution;
    }
    private void refreshScheduleConfiguration(LoadSchedule execution,LoadSchedule approved,Path root) throws Exception {
        Map<String,Object> current=new LinkedHashMap<>(resolveExecutionConfiguration(execution.packageId,root,execution.environment));
        if(approved.configuration!=null){current.put("approvedConfigurationIdentity",approved.configuration.get("configurationIdentity"));current.put("environmentOrigin",approved.configuration.get("environmentOrigin"));Map<String,Object> origins=new LinkedHashMap<>((Map<String,Object>)current.get("origins"));origins.put("environment",approved.configuration.get("environmentOrigin"));current.put("origins",origins);Map<String,Object> paths=new LinkedHashMap<>((Map<String,Object>)current.get("authoredPaths"));paths.put("environment",((Map<?,?>)approved.configuration.get("authoredPaths")).get("environment"));current.put("authoredPaths",paths);}
        execution.configuration=configurationRevision(current,execution.expectedRevisionDigest);
    }
    private ValidatedScheduledScenario validateScheduleInputs(Path root,LoadSchedule schedule) throws Exception {
        if("run".equals(schedule.effectiveJobType()))validateScheduledRun(schedule);
        else if("debug".equals(schedule.effectiveJobType()))validateScheduledDebugInput(root,schedule);
        else return validateScheduleScenario(root,schedule);
        return null;
    }

    private Map<String,Object> submitScheduledLoad(LoadSchedule schedule,String occurrenceId,String executionPrincipal,JobProvenance provenance) throws Exception {
        Path root=packageRoot(schedule.packageId);
        if(root==null||!Files.isDirectory(root,java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Configured schedule package is no longer available");
        validatePackageRoot(root);
        Map<String,Object> scenario=schedule.inlineScenario;
        if(scenario==null||schedule.expectedRevisionDigest==null)throw new IllegalArgumentException("Validated scheduled Load snapshot is incomplete");
        com.fasterxml.jackson.databind.node.ObjectNode input=JSON.createObjectNode();input.put("packageId",schedule.packageId);
        input.set("configurationProvenance",JSON.valueToTree(schedule.configuration));
        if(schedule.config!=null)input.put("config",schedule.config);if(schedule.environment!=null)input.put("environment",schedule.environment);
        input.set("inlineLoadScenario",JSON.valueToTree(scenario));input.put("expectedRevisionDigest",schedule.expectedRevisionDigest);
        applyInspectionPolicy(input,schedule.packageId);
        JobExecutionSpec retained=schedule.validatedSensitiveSource?null:capturePrevalidatedExecutionSpec("load",input,occurrenceId);
        if(retained!=null)retained.withScheduleSnapshot(schedule.id,schedule.snapshotVersion);
        return submit("load",input,"load-schedule:"+schedule.id,true,occurrenceId,true,null,null,retained,false,provenance);
    }

    private Map<String,Object> submitScheduledDebug(LoadSchedule schedule,String occurrenceId,String executionPrincipal,JobProvenance provenance) throws Exception {
        Path root=packageRoot(schedule.packageId);
        if(root==null||!Files.isDirectory(root,java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Configured schedule package is no longer available");
        validatePackageRoot(root);
        String revisionDigest=schedule.expectedRevisionDigest;
        if(revisionDigest==null)throw new IllegalArgumentException("Validated scheduled Debug snapshot is incomplete");
        com.fasterxml.jackson.databind.node.ObjectNode input=JSON.createObjectNode();input.put("packageId",schedule.packageId);
        input.set("configurationProvenance",JSON.valueToTree(schedule.configuration));
        if(schedule.config!=null)input.put("config",schedule.config);if(schedule.environment!=null)input.put("environment",schedule.environment);
        input.set("target",JSON.valueToTree(Map.of("type",schedule.debugTargetType,"id",schedule.debugTargetId)));
        input.set("inlineDebugInput",JSON.valueToTree(schedule.inlineDebugInput));input.put("expectedRevisionDigest",revisionDigest);
        input.put("draftResourceId",schedule.debugResourceId);applyInspectionPolicy(input,schedule.packageId);
        JobExecutionSpec retained=capturePrevalidatedExecutionSpec("debug",input,null);
        return submit("debug",input,"debug-schedule:"+schedule.id,true,occurrenceId,true,null,null,retained,false,provenance);
    }

    private String validateScheduledDebugInput(Path root,LoadSchedule schedule) throws Exception {
        if(!config.inspection.enabled)throw new NotFoundException();
        if(schedule.inlineDebugInput==null||!List.of("template","flow","tool").contains(schedule.debugTargetType)
                ||schedule.debugTargetId==null||schedule.debugTargetId.isBlank()
                ||schedule.debugResourceId==null||schedule.expectedRevisionDigest==null
                ||!schedule.expectedRevisionDigest.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Scheduled Debug snapshot is incomplete");
        WorkerRequest request=inspectionRequest(schedule.packageId,root,schedule.environment);
        request.config=config.runtimeConfigPath.toString();
        request.inspectionAction="debug-input";request.inspectionType=schedule.debugTargetType;
        request.inspectionTargetId=schedule.debugTargetId;request.inlineDebugInput=deepMap(schedule.inlineDebugInput);
        Map<String,Object> inspected=executeInspection(root,request);
        Object rawDigest=inspected.get("revisionDigest"),rawSensitive=inspected.get("containsSensitiveValues");
        Object rawResource=inspected.get("resource"),rawTarget=inspected.get("target");
        if(!(rawDigest instanceof String)||!((String)rawDigest).matches("[a-f0-9]{64}")
                ||!(rawSensitive instanceof Boolean)||!(rawResource instanceof Map)||!(rawTarget instanceof Map))
            throw new IllegalStateException("Debug validation Worker returned an invalid response");
        @SuppressWarnings("unchecked") Map<String,Object> resource=(Map<String,Object>)rawResource;
        @SuppressWarnings("unchecked") Map<String,Object> target=(Map<String,Object>)rawTarget;
        if(!schedule.debugResourceId.equals(String.valueOf(resource.get("resourceId")))
                ||!schedule.debugTargetType.equals(String.valueOf(target.get("type")))
                ||!schedule.debugTargetId.equals(String.valueOf(target.get("id"))))
            throw new IllegalArgumentException("Scheduled Debug target changed; create a new schedule from a fresh preview");
        if(((Boolean)rawSensitive).booleanValue())
            throw new IllegalArgumentException("A Debug schedule cannot store submitted credentials, private values, or other classified sensitive fields. Keep classified package defaults protected or remove them from the override.");
        String digest=(String)rawDigest;
        requireScheduleRevision(schedule,digest);
        return digest;
    }

    private ValidatedScheduledScenario validateScheduleScenario(Path root,LoadSchedule schedule) throws Exception {
        Map<String,Object> requested=schedule.inlineScenario==null?readScheduledScenario(root,schedule.scenario):deepMap(schedule.inlineScenario);
        ValidatedScheduledScenario validated=validateScheduledScenario(root,schedule,requested,schedule.inlineScenario==null);
        if(schedule.load!=null&&!schedule.load.isEmpty()) {
            Map<String,Object> overridden=withScheduledLoadOptions(validated.scenario,schedule.load);
            validated=validateScheduledScenario(root,schedule,overridden,false);
        }
        if(schedule.inlineScenario!=null&&validated.containsSensitiveValues)
            throw new IllegalArgumentException("An inline schedule cannot store credentials, private values, or other classified sensitive fields. Remove them from the Load draft or keep them in package-managed configuration.");
        requireScheduleRevision(schedule,validated.revisionDigest);
        validateScheduledLoadLimits(config.inlineLoad,validated.scenario);schedule.loadModel=validated.model;
        schedule.listSummary=JobListIdentity.load(JSON.valueToTree(validated.scenario));return validated;
    }

    private Map<String,Object> readScheduledScenario(Path root,String relative) throws Exception {
        Path scenario=root.resolve(relative).normalize();validatePackagePath(root,relative,"scenario");
        if(!Files.isRegularFile(scenario)||!scenario.toRealPath().startsWith(root.toRealPath()))throw new IllegalArgumentException("Scheduled Load scenario must be a regular package file");
        if(Files.size(scenario)>config.maxRequestBytes)throw new IllegalArgumentException("Scheduled Load scenario exceeds server.maxRequestBytes");
        org.yaml.snakeyaml.LoaderOptions options=new org.yaml.snakeyaml.LoaderOptions();options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);options.setCodePointLimit(config.maxRequestBytes);
        Object raw;try(var stream=Files.newInputStream(scenario)){raw=new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options)).load(stream);}
        if(!(raw instanceof Map<?,?>))throw new IllegalArgumentException("Scheduled Load scenario must be a YAML mapping");
        @SuppressWarnings("unchecked") Map<String,Object> result=(Map<String,Object>)JSON.convertValue(raw,Map.class);
        return result;
    }

    private ValidatedScheduledScenario validateScheduledScenario(Path root,LoadSchedule schedule,Map<String,Object> scenario,
                                                                  boolean packageFile) throws Exception {
        WorkerRequest request=inspectionRequest(schedule.packageId,root,schedule.environment);
        request.config=config.runtimeConfigPath.toString();
        request.inspectionAction="load-scenario";request.inlineLoadScenario=scenario;
        if(packageFile)request.inspectionPath=schedule.scenario;
        Map<String,Object> inspected=executeInspection(root,request);
        Object rawScenario=inspected.get("normalizedScenario"),rawDigest=inspected.get("revisionDigest"),rawModel=inspected.get("model"),rawSensitive=inspected.get("containsSensitiveValues"),rawAuthoredSensitive=inspected.get("containsSensitiveAuthoredValues");
        if(!(rawScenario instanceof Map)||!(rawDigest instanceof String)||!((String)rawDigest).matches("[a-f0-9]{64}")
                ||!(rawModel instanceof String)||!List.of("virtualUsers","arrivalRate").contains(rawModel)||!(rawSensitive instanceof Boolean)||(!packageFile&&!(rawAuthoredSensitive instanceof Boolean)))
            throw new IllegalStateException("Load validation Worker returned an invalid response");
        @SuppressWarnings("unchecked") Map<String,Object> normalized=(Map<String,Object>)JSON.convertValue(rawScenario,Map.class);
        return new ValidatedScheduledScenario(normalized,(String)rawDigest,(String)rawModel,((Boolean)rawSensitive).booleanValue(),Boolean.TRUE.equals(rawAuthoredSensitive));
    }

    private static Map<String,Object> withScheduledLoadOptions(Map<String,Object> source,Map<String,String> options) {
        @SuppressWarnings("unchecked") Map<String,Object> scenario=(Map<String,Object>)JSON.convertValue(source,Map.class);
        Object raw=scenario.get("workloads");if(!(raw instanceof List<?> workloads)||workloads.isEmpty())throw new IllegalArgumentException("Scheduled Load scenario must contain workloads");
        List<Object> normalized=new ArrayList<>();
        for(Object value:workloads) {
            if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("Scheduled Load workload is invalid");
            @SuppressWarnings("unchecked") Map<String,Object> workload=(Map<String,Object>)JSON.convertValue(value,Map.class);
            Object prior=workload.get("load");if(!(prior instanceof Map<?,?>))throw new IllegalArgumentException("Scheduled Load pacing is invalid");
            @SuppressWarnings("unchecked") Map<String,Object> load=(Map<String,Object>)JSON.convertValue(prior,Map.class);load.putAll(options);workload.put("load",load);normalized.add(workload);
        }
        scenario.put("workloads",normalized);return scenario;
    }

    private LoadSchedule buildLoadSchedule(JsonNode input,LoadSchedule prior,String principal) throws Exception {
        if(input==null||!input.isObject())throw new IllegalArgumentException("A JSON object is required");
        Set<String> allowed=Set.of("name","description","tags","packageId","jobType","executionType","schemaVersion","scenario","draftId","config","environment","timezone","cron","runAt","load","maxConcurrentRuns","enabled","packageChangePolicy");
        input.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw new IllegalArgumentException("Unknown schedule field: "+key);});
        if(input.has("schemaVersion")&&!"att-schedule/v2".equals(fieldText(input,"schemaVersion",null,true)))throw new IllegalArgumentException("Unsupported schedule schemaVersion");
        LoadSchedule s=prior==null?new LoadSchedule():prior.copy();
        s.name=fieldText(input,"name",s.name,true);s.description=fieldText(input,"description",s.description,false);
        if(input.has("tags"))s.tags=scheduleTags(input.get("tags"));else if(s.tags==null)s.tags=List.of();
        s.packageId=fieldText(input,"packageId",s.packageId,true);
        if(input.has("packageChangePolicy")) {
            s.packageChangePolicy=fieldText(input,"packageChangePolicy",null,false);
            if(s.packageChangePolicy!=null&&!Set.of("strict","pause","ignore").contains(s.packageChangePolicy))throw new IllegalArgumentException("packageChangePolicy must be strict, pause, ignore or null for the server default");
        }
        boolean validateExecution=prior==null||!prior.enabled&&input.path("enabled").asBoolean(false)||prior!=null&&(
                input.has("scenario")&&!java.util.Objects.equals(input.path("scenario").asText(),prior.scenario)
                ||input.has("load")&&!input.get("load").equals(JSON.valueToTree(prior.load))
                ||!java.util.Objects.equals(s.packageId,prior.packageId)
                ||input.has("environment")&&!sameOptionalText(fieldText(input,"environment",null,false),prior.environment));
        String jobType=fieldText(input,"jobType",prior==null?"load":prior.effectiveJobType(),false);
        if(jobType==null||jobType.isBlank())jobType="load";
        if(input.has("executionType")) {
            String type=fieldText(input,"executionType",null,true);
            if(!List.of("run","load").contains(type))throw new IllegalArgumentException("executionType must be run or load");
            if(input.has("jobType")&&!type.equals(jobType))throw new IllegalArgumentException("executionType and jobType disagree");
            jobType=type;
        }
        if(!List.of("load","debug","run").contains(jobType))throw new IllegalArgumentException("jobType must be run, load or legacy debug");
        if(prior!=null&&!prior.effectiveJobType().equals(jobType))throw new IllegalArgumentException("A schedule job type is immutable; create a new schedule to change it");
        s.jobType=jobType;
        String draftId=fieldText(input,"draftId",null,false);
        if(draftId!=null) {
            if(prior!=null)throw new IllegalArgumentException("A validated draft can only create a new schedule");
            if(input.has("scenario")&&!input.get("scenario").isNull())throw new IllegalArgumentException("Provide either a validated draft or a package scenario path");
            String draftEnvironment=input.has("environment")?fieldText(input,"environment",null,false):null;
            draftEnvironment=draftEnvironment==null||draftEnvironment.isBlank()?retainedDraftEnvironment(draftId,principal,s.packageId):inspectionEnvironment(draftEnvironment,"environment");
            s.environment=draftEnvironment;
            if(runDrafts.containsKey(draftId))s.configuration=retainedConfiguration(runDrafts.get(draftId).preview);
            else if(debugDrafts.containsKey(draftId))s.configuration=retainedConfiguration(debugDrafts.get(draftId).preview);
            else if(quickLoadDrafts.containsKey(draftId))s.configuration=retainedConfiguration(quickLoadDrafts.get(draftId).preview);
            else if(advancedLoadDrafts.containsKey(draftId))s.configuration=retainedConfiguration(advancedLoadDrafts.get(draftId).preview);
            else if(catalogLoadDrafts.containsKey(draftId))s.configuration=catalogLoadDrafts.get(draftId).configuration;
            if("run".equals(jobType)) {
                RunDraft draft=runDraft(draftId,principal,s.packageId);
                if(!java.util.Objects.equals(draftEnvironment,draft.workerInput.path("environment").isTextual()?draft.workerInput.path("environment").asText():null))throw new IllegalArgumentException("Schedule environment differs from the validated RUN draft");
                s.runInput=JSON.convertValue(draft.workerInput,Map.class);s.expectedRevisionDigest=draft.revision;
                s.config=draft.workerInput.path("config").isTextual()?draft.workerInput.path("config").asText():null;
                s.scenario=null;s.inlineScenario=null;s.load=new LinkedHashMap<>();
                if(draft.resourceDebugDraft!=null) {
                    DebugDraft debug=draft.resourceDebugDraft;
                    if(debug.containsSensitiveValues)throw new IllegalArgumentException("A RUN schedule cannot persist submitted sensitive values");
                    s.inlineDebugInput=deepMap(debug.scheduleInput);s.runInput.put("inlineDebugInput",s.inlineDebugInput);
                    s.debugTargetType=debug.targetType;s.debugTargetId=debug.targetId;s.debugResourceId=debug.resourceId;
                }
            } else if("debug".equals(jobType)) {
                DebugDraft debug=scheduleDraftDebug(draftId,principal,s.packageId,draftEnvironment);
                if(debug.containsSensitiveValues)throw new IllegalArgumentException("A Debug schedule cannot persist submitted credentials, private values, or other classified sensitive fields. Keep classified package defaults protected or remove them from the override.");
                s.scenario=null;s.inlineScenario=null;s.inlineDebugInput=deepMap(debug.scheduleInput);
                s.debugTargetType=debug.targetType;s.debugTargetId=debug.targetId;s.debugResourceId=debug.resourceId;
                s.expectedRevisionDigest=debug.revisionDigest;s.loadModel=null;s.load=new LinkedHashMap<>();
            } else {
                s.scenario=null;s.inlineDebugInput=null;s.debugTargetType=null;s.debugTargetId=null;s.debugResourceId=null;s.expectedRevisionDigest=null;
                CatalogLoadDraftState catalogDraft=scheduleCatalogLoadDraft(draftId,principal,s.packageId,draftEnvironment);
                if(catalogDraft!=null) {
                    s.inlineScenario=deepMap(catalogDraft.scenario);s.catalogSnapshot=true;
                    s.expectedRevisionDigest=catalogDraft.revisionDigest;s.loadModel=catalogDraft.model;
                } else {
                    s.inlineScenario=scheduleDraftScenario(draftId,principal,s.packageId,draftEnvironment);
                    s.catalogSnapshot=false;
                }
            }
        } else if("run".equals(jobType)) {
            if(s.runInput==null)throw new IllegalArgumentException("A RUN schedule requires a validated RUN draft");
            if(input.has("scenario")||input.has("load"))throw new IllegalArgumentException("RUN schedules cannot use Load fields");
        } else if("debug".equals(jobType)) {
            if(s.inlineDebugInput==null||s.inlineScenario!=null)throw new IllegalArgumentException("A Debug schedule requires a validated Debug draft snapshot");
            if(input.has("scenario")&&!input.get("scenario").isNull()&&!input.get("scenario").asText().isBlank())
                throw new IllegalArgumentException("A Debug snapshot cannot be replaced by a Load scenario path");
            if(!s.load.isEmpty())throw new IllegalArgumentException("Debug schedules cannot use Load option overrides");
            s.scenario=null;
        } else if(s.inlineScenario==null) {
            if(s.inlineDebugInput!=null)throw new IllegalArgumentException("A Debug snapshot cannot be changed to a Load schedule");
            s.scenario=fieldText(input,"scenario",s.scenario,true);
        } else {
            if(input.has("scenario")&&!input.get("scenario").isNull()&&!input.get("scenario").asText().isBlank())
                throw new IllegalArgumentException("An inline scenario snapshot cannot be replaced by a package path");
            if(input.has("draftId"))throw new IllegalArgumentException("An inline scenario snapshot cannot be replaced");
        }
        s.config=fieldText(input,"config",s.config,false);s.environment=fieldText(input,"environment",s.environment,false);
        s.timezone=fieldText(input,"timezone",s.timezone, !input.hasNonNull("runAt")&&s.runAt==null);
        if(input.hasNonNull("runAt")&&input.hasNonNull("cron"))throw new IllegalArgumentException("Provide exactly one cron or runAt trigger");
        // A schedule holds exactly one trigger, so an update that supplies a new trigger replaces the stored one instead of retaining the field the request omits.
        if(input.hasNonNull("runAt")) {s.runAt=fieldText(input,"runAt",null,true);s.cron=null;}
        else if(input.hasNonNull("cron")) {s.cron=fieldText(input,"cron",null,true);s.runAt=null;}
        else {s.runAt=fieldText(input,"runAt",s.runAt,false);s.cron=fieldText(input,"cron",s.cron,s.runAt==null);}
        // Studio serializes timestamps with milliseconds; retain the persisted canonical value when it denotes the same instant.
        if(prior!=null&&LoadSchedule.sameInstant(s.runAt,prior.runAt))s.runAt=prior.runAt;
        if(s.runAt!=null&&s.cron!=null)throw new IllegalArgumentException("Provide exactly one cron or runAt trigger");
        if(s.timezone==null)s.timezone="UTC";
        if(input.has("enabled")){if(!input.get("enabled").isBoolean())throw new IllegalArgumentException("enabled must be true or false");s.enabled=input.get("enabled").booleanValue();}
        if(input.has("maxConcurrentRuns")){
            JsonNode limit=input.get("maxConcurrentRuns");if(!limit.isIntegralNumber()||!limit.canConvertToInt())throw new IllegalArgumentException("maxConcurrentRuns must be an integer");
            int maximum=limit.intValue();if(maximum<1||maximum>config.scheduling.maxConcurrentRuns)throw new IllegalArgumentException("maxConcurrentRuns must be between 1 and server.scheduling.maxConcurrentRuns");s.maxConcurrentRuns=maximum;
        }
        if(input.has("load"))s.load=loadOptions(input.get("load"));else if(s.load==null)s.load=new LinkedHashMap<>();
        if("debug".equals(s.effectiveJobType())&&s.load!=null&&!s.load.isEmpty())throw new IllegalArgumentException("Debug schedules cannot use Load option overrides");
        if(s.name.isBlank()||s.name.length()>120||containsControl(s.name))throw new IllegalArgumentException("name must be 1 to 120 characters without control characters");
        if(s.description!=null&&(s.description.length()>500||containsControl(s.description)))throw new IllegalArgumentException("description must be at most 500 characters without control characters");
        if(packageRoot(s.packageId)==null)throw new IllegalArgumentException("Unknown packageId");
        Path root=packageRoot(s.packageId);validatePackageRoot(root);
        if(s.config!=null&&!s.config.isBlank()&&!("config/config.yaml".equals(s.config)||config.runtimeConfigPath.toString().equals(s.config)))
            throw new IllegalArgumentException("A schedule must use the shared runtime configuration");
        s.config="config/config.yaml";
        s.environment=s.environment==null||s.environment.isBlank()?null:safeText(s.environment,128,"environment");
        if("run".equals(s.effectiveJobType())) {
            JsonNode snapshot=JSON.valueToTree(s.runInput);
            String expectedEnvironment=snapshot.path("environment").isTextual()?snapshot.path("environment").asText():null;
            String expectedConfig=snapshot.path("config").isTextual()?snapshot.path("config").asText():null;
            boolean sameRuntimeConfig="config/config.yaml".equals(expectedConfig)||config.runtimeConfigPath.toString().equals(expectedConfig);
            if(!s.packageId.equals(snapshot.path("packageId").asText())||!java.util.Objects.equals(s.environment,expectedEnvironment)||!sameRuntimeConfig)
                throw new IllegalArgumentException("A RUN snapshot is immutable; create a new schedule for changed values");
            if(input.has("scenario")||s.load!=null&&!s.load.isEmpty())throw new IllegalArgumentException("RUN schedules cannot use Load fields");
            if(validateExecution&&prior==null)validateScheduledRun(s);
        } else if("debug".equals(s.effectiveJobType())) {
            if(s.config!=null&&!"config/config.yaml".equals(s.config))throw new IllegalArgumentException("A Debug schedule must use the configuration validated by the Debug draft");
            if(prior!=null&&debugSnapshotInputChanged(prior,s,input))throw new IllegalArgumentException("A Debug snapshot is immutable; create a new schedule for changed values");
            if(validateExecution&&prior==null)validateScheduledDebugInput(root,s);
        } else if(s.inlineScenario==null) {
            s.scenario=safeRelative(s.scenario,"scenario");if(s.scenario==null)throw new IllegalArgumentException("scenario or draftId is required");
            if(validateExecution){validatePackagePath(root,s.scenario,"scenario");Path scenarioPath=root.resolve(s.scenario).normalize();
            if(!Files.isRegularFile(scenarioPath)||!scenarioPath.toRealPath().startsWith(root.toRealPath()))throw new IllegalArgumentException("scenario must be a regular file inside the configured package");}
            if(s.inlineDebugInput!=null)throw new IllegalArgumentException("A Debug snapshot cannot be changed to a Load schedule");
        } else {
            if(!config.inlineLoad.enabled&&!s.catalogSnapshot)throw new InlineLoadDisabledException();
            if(scenarioInputChanged(prior,s,input))throw new IllegalArgumentException("An inline scenario snapshot is immutable; create a new schedule for changed values");
            if(s.load!=null&&!s.load.isEmpty())throw new IllegalArgumentException("Inline scenario snapshots cannot use separate Load overrides");
        }
        if(validateExecution) {
            if(s.configuration==null||!java.util.Objects.equals(s.environment,s.configuration.get("environment"))||!java.util.Objects.equals(s.packageId,s.configuration.get("packageId")))
                s.configuration=resolveExecutionConfiguration(s.packageId,root,s.environment);
            s.environment=(String)s.configuration.get("environment");
            authorizeConfiguration(s.configuration);
        }
        java.time.ZoneId zone=scheduleZone(s.timezone);
        if(s.runAt==null) {
            CronExpression cron=CronExpression.parse(s.cron,config.scheduling.minimumIntervalMinutes);
            cron.validateTimezoneMinimumInterval(zone,config.scheduling.minimumIntervalMinutes,clock.instant());
        } else if(prior==null||!LoadSchedule.sameInstant(s.runAt,prior.runAt)||!prior.enabled&&s.enabled)
            s.runAt=futureRunAt(s.runAt);
        if(validateExecution&&prior==null&&"load".equals(s.effectiveJobType())) {
            ValidatedScheduledScenario validated=validateScheduleScenario(root,s);
            // New file-backed schedules need the same immutable baseline as draft-backed schedules.
            // Legacy rows without a digest keep their grandfathered validate-current behavior.
            s.expectedRevisionDigest=validated.revisionDigest;
            s.listSummary=JobListIdentity.load(JSON.valueToTree(validated.scenario));
            if(s.inlineScenario!=null)s.inlineScenario=deepMap(validated.scenario);
        }
        if(s.configuration!=null)s.configuration=configurationRevision(s.configuration,s.expectedRevisionDigest);
        if(prior==null)s.packageRootIdentity=packageRootIdentity(root);
        else if(validateExecution) {
            if("load".equals(s.effectiveJobType())&&s.inlineScenario==null&&!java.util.Objects.equals(prior.packageId,s.packageId))s.packageRootIdentity=packageRootIdentity(root);
            validateScheduleForActivation(s,principal);
        }
        s.nextRunAt=s.runAt==null?nextRun(s.cron,s.timezone,clock.instant()).toString():s.runAt;
        s.updatedBy=principal;s.updatedAt=clock.instant().toString();return s;
    }
    private String futureRunAt(String runAt) {
        Instant fire;
        try { fire=Instant.parse(runAt); } catch(java.time.format.DateTimeParseException invalid) {throw new IllegalArgumentException("runAt must be an ISO-8601 instant with UTC offset");}
        if(!fire.isAfter(clock.instant()))throw new IllegalArgumentException("runAt must be in the future");
        return fire.toString();
    }
    private String retainedDraftEnvironment(String id,String principal,String packageId) {
        if(id.startsWith("R"))return runDraft(id,principal,packageId).workerInput.path("environment").asText(null);
        DebugDraft debug=debugDrafts.get(id);
        if(debug!=null&&debug.principal.equals(principal)&&debug.packageId.equals(packageId))return debug.environment;
        QuickLoadDraft quick=quickLoadDrafts.get(id);
        if(quick!=null&&quick.principal.equals(principal)&&quick.packageId.equals(packageId))return quick.environment;
        AdvancedLoadDraftState advanced=advancedLoadDrafts.get(id);
        if(advanced!=null&&advanced.principal.equals(principal)&&advanced.packageId.equals(packageId))return advanced.environment;
        CatalogLoadDraftState catalog=catalogLoadDrafts.get(id);
        if(catalog!=null&&catalog.principal.equals(principal)&&catalog.packageId.equals(packageId))return catalog.environment;
        throw new NotFoundException();
    }
    private Map<String,Object> scheduleDraftScenario(String draftId,String principal,String packageId,String environment) {
        synchronized(draftLock) {
            expireDrafts();
            QuickLoadDraft quick=quickLoadDrafts.get(draftId);
            if(quick!=null) {
                requireLiveDraft(quick.expiresAt);
                if(!quick.principal.equals(principal)||!quick.packageId.equals(packageId)||!java.util.Objects.equals(quick.environment,environment))throw new NotFoundException();
                return deepMap(quick.scenario);
            }
            AdvancedLoadDraftState advanced=advancedLoadDrafts.get(draftId);
            if(advanced!=null) {
                requireLiveDraft(advanced.expiresAt);
                if(!advanced.principal.equals(principal)||!advanced.packageId.equals(packageId)||!java.util.Objects.equals(advanced.environment,environment))throw new NotFoundException();
                return deepMap(advanced.scenario);
            }
            throw new NotFoundException();
        }
    }
    private CatalogLoadDraftState scheduleCatalogLoadDraft(String draftId,String principal,String packageId,String environment) {
        if(!CATALOG_LOAD_DRAFT_ID.matcher(draftId==null?"":draftId).matches())return null;
        synchronized(draftLock) {
            expireDrafts();CatalogLoadDraftState draft=catalogLoadDrafts.get(draftId);
            if(draft==null)throw new NotFoundException();
            requireLiveDraft(draft.expiresAt);
            if(!draft.principal.equals(principal)||!draft.packageId.equals(packageId)
                    ||!java.util.Objects.equals(draft.environment,environment))throw new NotFoundException();
            return draft;
        }
    }
    private DebugDraft scheduleDraftDebug(String draftId,String principal,String packageId,String environment) {
        synchronized(draftLock) {
            expireDrafts();DebugDraft draft=debugDrafts.get(draftId);
            if(draft==null)throw new NotFoundException();
            requireLiveDraft(draft.expiresAt);
            if(!draft.principal.equals(principal)||!draft.packageId.equals(packageId)||!java.util.Objects.equals(draft.environment,environment))throw new NotFoundException();
            return draft;
        }
    }
    private static boolean scenarioInputChanged(LoadSchedule prior,LoadSchedule current,JsonNode input) {
        if(prior==null||prior.inlineScenario==null)return false;
        if(input.has("draftId")||input.has("inlineScenario"))return true;
        return !java.util.Objects.equals(prior.packageId,current.packageId)
                ||!sameOptionalText(prior.environment,current.environment)
                ||!sameOptionalText(prior.config,current.config);
    }
    private static boolean debugSnapshotInputChanged(LoadSchedule prior,LoadSchedule current,JsonNode input) {
        if(prior==null||prior.inlineDebugInput==null)return false;
        if(input.has("draftId")||input.has("inlineDebugInput"))return true;
        return !java.util.Objects.equals(prior.packageId,current.packageId)
                ||!sameOptionalText(prior.environment,current.environment)
                ||!sameOptionalText(prior.config,current.config);
    }
    private static boolean sameOptionalText(String left,String right) {
        String normalizedLeft=left==null||left.isBlank()?null:left.trim();
        String normalizedRight=right==null||right.isBlank()?null:right.trim();
        return java.util.Objects.equals(normalizedLeft,normalizedRight);
    }
    private static java.time.ZoneId scheduleZone(String timezone) {
        try{java.time.ZoneId zone=java.time.ZoneId.of(timezone);if(!java.time.ZoneId.getAvailableZoneIds().contains(timezone))throw new IllegalArgumentException();return zone;}
        catch(Exception invalid){throw new IllegalArgumentException("timezone must be a valid IANA timezone");}
    }
    private static String fieldText(JsonNode input,String name,String prior,boolean required) {
        if(!input.has(name)) {if(required&&(prior==null||prior.isBlank()))throw new IllegalArgumentException(name+" is required");return prior;}
        JsonNode node=input.get(name);if(node==null||node.isNull()){if(required)throw new IllegalArgumentException(name+" is required");return null;}
        if(!node.isTextual())throw new IllegalArgumentException(name+" must be a string");String value=node.asText().trim();
        if(value.length()>512||containsControl(value))throw new IllegalArgumentException(name+" is invalid");return value;
    }
    private static Map<String,String> loadOptions(JsonNode value) {
        if(!value.isObject())throw new IllegalArgumentException("load must be an object of Load option strings");
        Set<String> allowed=Set.of("users","arrivalRate","warmup","rampUp","duration","rampDown","thinkTime","maxConcurrent","overloadPolicy");
        Map<String,String> result=new LinkedHashMap<>();value.fields().forEachRemaining(entry->{
            if(!allowed.contains(entry.getKey()))throw new IllegalArgumentException("Unsupported Load option: "+entry.getKey());
            if(!entry.getValue().isTextual())throw new IllegalArgumentException("Load option values must be strings");
            String option=entry.getValue().asText();if(option.length()>128||containsControl(option))throw new IllegalArgumentException("Load option is invalid");result.put(entry.getKey(),option);
        });return result;
    }
    private static List<String> scheduleTags(JsonNode value) {
        if(!value.isArray()||value.size()>20)throw new IllegalArgumentException("tags must be an array of at most 20 labels");
        java.util.LinkedHashSet<String> tags=new java.util.LinkedHashSet<>();
        for(JsonNode tag:value){if(!tag.isTextual())throw new IllegalArgumentException("schedule tags must be strings");String label=tag.asText().trim();
            if(!label.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,47}"))throw new IllegalArgumentException("each tag must be 1 to 48 letters, numbers, or . _ : - characters");tags.add(label);}
        return List.copyOf(tags);
    }
    private static Instant nextRun(String cron,String timezone,Instant after) {return CronExpression.parse(cron,1).nextAfter(after,java.time.ZoneId.of(timezone));}
    private void requireSchedulingEnabled(){if(!config.scheduling.enabled||loadScheduleScheduler==null)throw new NotFoundException();}
    Map<String,Object> inspectionStatistics() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("server",inspectionMetrics.snapshot());
        List<Map<String,Object>> daemons=new ArrayList<>();
        for(InspectionWorker worker:inspectionWorkerPool)daemons.add(worker.metrics);
        result.put("workers",daemons);
        return result;
    }
    Map<String,Object> executionPoolStatistics() { return executionWorkers==null?Map.of("enabled",false):executionWorkers.metrics(); }
    Map<String,Object> counts() throws Exception {Map<String,Object> m=new LinkedHashMap<>();for(String s:List.of("QUEUED","PREPARING","RUNNING","PASS","FAIL","ERROR","INVALID","CANCELLED"))m.put(s.toLowerCase(),store.count(s));m.put("inspection",inspectionStatistics());m.put("executionPool",executionPoolStatistics());m.put("queueDepth",workers.getQueue().size());m.put("activeWorkers",workers.getActiveCount());m.put("completedSinceStart",completed.get());m.put("loadSchedulingEnabled",config.scheduling.enabled);if(config.scheduling.enabled){m.put("loadSchedules",store.scheduleCount());m.put("activeScheduledLoads",store.activeScheduledCount(null));m.put("loadScheduleMetrics",store.scheduleMetrics());}m.put("jobStorage",jobStorageSnapshot());m.put("eventJournal",eventJournalStatistics());return m;}
    MonitoringSnapshot monitoringSnapshot(String requestId) throws Exception {
        long now=System.nanoTime();MonitoringSnapshot cached=cachedMonitoringSnapshot;
        if(cached!=null&&now-cachedMonitoringSnapshotAtNanos<MONITORING_SNAPSHOT_CACHE_NANOS)return monitoringRequest(cached,requestId);
        synchronized(monitoringSnapshotLock) {
            now=System.nanoTime();cached=cachedMonitoringSnapshot;
            if(cached!=null&&now-cachedMonitoringSnapshotAtNanos<MONITORING_SNAPSHOT_CACHE_NANOS)return monitoringRequest(cached,requestId);
            MonitoringSnapshot fresh=captureMonitoringSnapshot(requestId);
            cachedMonitoringSnapshot=fresh;cachedMonitoringSnapshotAtNanos=System.nanoTime();return fresh;
        }
    }
    private static MonitoringSnapshot monitoringRequest(MonitoringSnapshot snapshot,String requestId) {
        return new MonitoringSnapshot(snapshot.schemaVersion(),snapshot.capturedAt(),snapshot.origin(),requestId,
                snapshot.health(),snapshot.process(),snapshot.workers(),snapshot.http(),snapshot.journal(),snapshot.storage(),
                snapshot.inspection(),snapshot.executionPool(),snapshot.scheduledLoad(),snapshot.jobs());
    }
    private MonitoringSnapshot captureMonitoringSnapshot(String requestId) throws Exception {
        Instant captured=clock.instant();
        java.lang.management.MemoryMXBean memory=java.lang.management.ManagementFactory.getMemoryMXBean();
        java.lang.management.MemoryUsage heap=memory.getHeapMemoryUsage(),nonHeap=memory.getNonHeapMemoryUsage();
        java.lang.management.ThreadMXBean threads=java.lang.management.ManagementFactory.getThreadMXBean();
        Long gcCount=0L,gcTime=0L;boolean gcCountAvailable=false,gcTimeAvailable=false;
        for(var gc:java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            if(gc.getCollectionCount()>=0L){gcCount+=gc.getCollectionCount();gcCountAvailable=true;}
            if(gc.getCollectionTime()>=0L){gcTime+=gc.getCollectionTime();gcTimeAvailable=true;}
        }
        if(!gcCountAvailable)gcCount=null;if(!gcTimeAvailable)gcTime=null;
        java.lang.management.OperatingSystemMXBean operating=java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        Double cpuLoad=null;Long cpuTime=null;
        if(operating instanceof com.sun.management.OperatingSystemMXBean extended) {
            double load=extended.getProcessCpuLoad();if(Double.isFinite(load)&&load>=0d)cpuLoad=load;
            long nanos=extended.getProcessCpuTime();if(nanos>=0L)cpuTime=nanos/1_000_000L;
        }
        int processors=Math.max(1,operating.getAvailableProcessors());
        MonitoringSnapshot.Process process=new MonitoringSnapshot.Process(
                nonnegative(heap.getUsed()),nonnegative(heap.getCommitted()),nonnegativeOrNull(heap.getMax()),
                nonnegative(nonHeap.getUsed()),nonnegative(nonHeap.getCommitted()),threads.getThreadCount(),
                threads.getDaemonThreadCount(),threads.getPeakThreadCount(),gcCount,gcTime,processors,cpuLoad,cpuTime,null);

        MonitoringSnapshot.Workers workerView=new MonitoringSnapshot.Workers(workers.getActiveCount(),config.maxConcurrent,
                workers.getQueue().size(),config.queuedLimit,config.maxConcurrent+config.queuedLimit,completed.get(),
                Math.max(0,config.maxConcurrent+config.queuedLimit-admissionSlots.availablePermits()),
                Math.max(0,config.maxConcurrentLoad-loadSlots.availablePermits()),store.activeLoadCount(),config.maxConcurrentLoad,
                loadCapacityRejections.get(),queueCapacityRejections.get(),null,null,
                Math.max(0,MAX_STREAM_OBSERVERS-streamSlots.availablePermits()),MAX_STREAM_OBSERVERS,null,null);
        MonitoringSnapshot.Http http=new MonitoringSnapshot.Http(monitoringRequests.requests(),monitoringRequests.successfulRequests(),
                monitoringRequests.redirectRequests(),monitoringRequests.clientErrors(),monitoringRequests.serverErrors(),
                monitoringRequests.percentile(.50),monitoringRequests.percentile(.95));

        Map<String,Object> journalData=eventJournalStatistics();
        MonitoringSnapshot.Journal journal=new MonitoringSnapshot.Journal(
                number(journalData,"activeJobs"),number(journalData,"degradedActiveJobs"),numberLong(journalData,"activeJournalBytes"),
                numberLong(journalData,"activeRetainedEvents"),numberLong(journalData,"activeRetainedMemoryBytes"),
                numberLong(journalData,"activeMemoryOverBudgetBytes"),numberLong(journalData,"activeCompactionAttempts"),
                numberLong(journalData,"activeCompactionFailures"),numberLong(journalData,"compactionFailuresSinceStart"),
                numberLong(journalData,"activeListenerDispatchFailures"),
                numberLong(journalData,"appendBytesWritten"),numberLong(journalData,"compactionBytesWritten"),
                nullableDouble(journalData,"writeAmplification"),
                nullableLong(journalData,"oldestActiveCompactionAgeMs"),numberLong(journalData,"maxJournalBytesPerJob"),
                numberLong(journalData,"memoryBudgetBytesPerJob"),nullableLong(journalData,"storageUsableBytes"),
                bool(journalData,"storageHeadroomLow"),number(journalData,"cachedTerminalJobs"),numberLong(journalData,"cachedTerminalWeightBytes"));

        Map<String,Object> quota=jobStorageSnapshot();
        Long storeUsable=null,storeTotal=null;
        try {var fileStore=Files.getFileStore(config.dataDir);storeUsable=fileStore.getUsableSpace();storeTotal=fileStore.getTotalSpace();}
        catch(Exception unavailable) { /* unavailable storage stays null in this read model */ }
        MonitoringSnapshot.Storage storage=new MonitoringSnapshot.Storage(
                text(quota,"state","unknown"),storeUsable,storeTotal,bool(quota,"enabled"),nullableLong(quota,"maxTotalBytes"),
                nullableLong(quota,"totalBytes"),nullableBoolean(quota,"measurementComplete"),nullableInt(quota,"eligibleJobs"),
                nullableInt(quota,"protectedJobs"),nullableInt(quota,"deletedJobs"),nullableLong(quota,"reclaimedBytes"),
                nullableBoolean(quota,"quotaUnmet"),nullableLong(quota,"cleanupTargetBytes"),nullableText(quota,"lastSweepAt"),
                nullableLong(quota,"maxSingleJobBytes"),artifactWriters.getActiveCount(),artifactWriters.getQueue().size(),
                artifactWriters.getQueue().size()+artifactWriters.getQueue().remainingCapacity(),pendingArtifactJobs.size(),
                archiveFailures.get(),archiveRetries.get(),null);

        Map<String,Object> inspectionData=inspectionMetrics.snapshot();
        long inspectionNanos=stageNanos(inspectionData,"DISPATCH")+stageNanos(inspectionData,"PROCESSING");
        int busy=0,live=0;
        List<MonitoringSnapshot.InspectionWorkerSnapshot> inspectionWorkers=new ArrayList<>();
        for(int slot=0;slot<inspectionWorkerPool.size();slot++) {
            InspectionWorker worker=inspectionWorkerPool.get(slot);
            if(worker.lock.isLocked())busy++;
            Process child=worker.process;if(child!=null&&child.isAlive())live++;
            boolean alive=child!=null&&child.isAlive();
            Long workerCpuNanos=nullableLong(worker.metrics,"processCpuNanos");
            inspectionWorkers.add(new MonitoringSnapshot.InspectionWorkerSnapshot(slot,worker.lock.isLocked(),alive,
                    nullableLong(worker.metrics,"heapUsedBytes"),workerCpuNanos==null?null:workerCpuNanos/1_000_000L,
                    workerMetricCount(worker.metrics,"INDEX_HIT"),workerMetricCount(worker.metrics,"INDEX_MISS"),
                    workerMetricCount(worker.metrics,"CONFIG_HIT"),workerMetricCount(worker.metrics,"CONFIG_MISS"),
                    workerMetricCount(worker.metrics,"INSPECTOR_HIT"),workerMetricCount(worker.metrics,"INSPECTOR_MISS")));
        }
        Map<String,MonitoringSnapshot.StageMetric> inspectionStages=new LinkedHashMap<>();
        for(att.metrics.InspectionMetrics.Stage stage:att.metrics.InspectionMetrics.Stage.values()) {
            String name=stage.name();inspectionStages.put(name,new MonitoringSnapshot.StageMetric(
                    stageCount(inspectionData,name),stageNanos(inspectionData,name)/1_000_000L));
        }
        MonitoringSnapshot.Inspection inspection=new MonitoringSnapshot.Inspection(config.inspection.enabled,
                inspectionWorkerPool.size(),busy,live,config.inspection.enabled?stageCount(inspectionData,"DISPATCH"):null,
                null,config.inspection.enabled?inspectionNanos/1_000_000L:null,
                config.inspection.enabled?stageCount(inspectionData,"STARTUP"):null,
                aggregateInspectionWorkerMetric("INDEX_HIT"),aggregateInspectionWorkerMetric("INDEX_MISS"),
                aggregateInspectionWorkerMetric("CONFIG_HIT"),aggregateInspectionWorkerMetric("CONFIG_MISS"),
                aggregateInspectionWorkerMetric("INSPECTOR_HIT"),aggregateInspectionWorkerMetric("INSPECTOR_MISS"),
                Map.copyOf(inspectionStages),List.copyOf(inspectionWorkers));

        Map<String,Object> executionData=executionPoolStatistics();
        MonitoringSnapshot.ExecutionPool executionPool=new MonitoringSnapshot.ExecutionPool(
                bool(executionData,"enabled"),nullableInt(executionData,"owned"),nullableInt(executionData,"ready"),nullableInt(executionData,"maxIdle"),
                nullableLong(executionData,"starts"),nullableLong(executionData,"leases"),nullableLong(executionData,"coldFallbacks"),
                nullableLong(executionData,"startupFailures"),nullableBoolean(executionData,"recoveryUncertain"),
                multiply(nullableLong(executionData,"idleHeapCapMb"),1024L*1024L),
                nullableBoolean(executionData,"idleCpuMetricsAvailable"),nullableDouble(executionData,"idleCpuMs"));

        Integer scheduleCount=null,activeSchedules=null;String nextDue=null;Map<String,Long> occurrenceCounts=null;
        if(config.scheduling.enabled) {
            scheduleCount=store.scheduleCount();activeSchedules=store.activeScheduledCount(null);
            Map<String,Object> scheduleData=store.scheduleMetrics();nextDue=scheduleData.get("nextDueAt") instanceof String value?value:null;
            occurrenceCounts=new LinkedHashMap<>();
            if(scheduleData.get("occurrences") instanceof Map<?,?> rows)for(String key:List.of("scheduled","submitted","skipped","failed-to-submit")) {
                Object value=rows.get(key);if(value instanceof Number count)occurrenceCounts.put(key,count.longValue());
            }
        }
        MonitoringSnapshot.ScheduledLoad scheduled=new MonitoringSnapshot.ScheduledLoad(config.scheduling.enabled,
                scheduleCount,activeSchedules,config.scheduling.enabled?config.scheduling.maxConcurrentRuns:null,
                nextDue,occurrenceCounts);
        Map<String,Long> jobStates=new LinkedHashMap<>();
        for(String state:List.of("QUEUED","PREPARING","RUNNING","PASS","FAIL","ERROR","INVALID","CANCELLED"))
            jobStates.put(state,store.count(state));
        return new MonitoringSnapshot("att-monitoring/v1",captured.toString(),"server-runtime",requestId,
                new MonitoringSnapshot.Health("UP",ServerBuildInfo.PRODUCT,
                        TimeUnit.NANOSECONDS.toMillis(Math.max(0L,System.nanoTime()-startedAtNanos))),
                process,workerView,http,journal,storage,inspection,executionPool,scheduled,Map.copyOf(jobStates));
    }
    MonitoringSnapshot.JobJournal monitoringJobJournal(String id) throws Exception {
        Job active=jobs.get(id);JobEvents source=active==null?cachedTerminalEvents(id):active.events;
        if(source==null)return new MonitoringSnapshot.JobJournal(id,false,null,null,null,null,null,null,null,
                null,null,null,null,null,null,null,null,null);
        Map<String,Object> values=source.metrics();
        return new MonitoringSnapshot.JobJournal(id,true,text(values,"journalPersistence","unknown"),
                nullableLong(values,"journalBytes"),nullableLong(values,"maxJournalBytes"),nullableLong(values,"retainedEventCount"),
                nullableLong(values,"retainedBytes"),nullableLong(values,"retainedMemoryBytes"),nullableLong(values,"compactionBacklogBytes"),
                nullableBoolean(values,"historyTruncated"),nullableBoolean(values,"sequenceGapDetected"),
                nullableLong(values,"compactionAttempts"),nullableLong(values,"compactionFailures"),
                nullableLong(values,"listenerDispatchFailures"),nullableDouble(values,"journalWriteAmplification"),
                nullableText(values,"lastCompactionAt"),nullableLong(values,"lastCompactionDurationMs"),
                nullableLong(values,"lastCompactionAgeMs"));
    }
    private JobEvents cachedTerminalEvents(String id) {
        synchronized(terminalEventCacheLock){return terminalEventCache.get(id);}
    }
    void requireMonitoringJob(String id,String principal,boolean systemAdmin) throws Exception {
        if(systemAdmin) {
            if(!JOB_ID.matcher(id).matches()||store.get(id)==null)throw new NotFoundException();
        } else requireJobOwner(id,principal);
    }
    void recordApiRequest(int status,long durationNanos) { monitoringRequests.record(status,durationNanos); }
    private static long stageCount(Map<String,Object> data,String stage) {Object value=data.get(stage);return value instanceof Map<?,?> row&&row.get("count") instanceof Number n?n.longValue():0L;}
    private static long stageNanos(Map<String,Object> data,String stage) {Object value=data.get(stage);return value instanceof Map<?,?> row&&row.get("totalNanos") instanceof Number n?n.longValue():0L;}
    private static int number(Map<String,Object> values,String key) {return (int)Math.max(0L,numberLong(values,key));}
    private static long numberLong(Map<String,Object> values,String key) {Object value=values.get(key);return value instanceof Number n?Math.max(0L,n.longValue()):0L;}
    private static Long nullableLong(Map<String,Object> values,String key) {Object value=values.get(key);return value instanceof Number n&&n.longValue()>=0L?n.longValue():null;}
    private static Double nullableDouble(Map<String,Object> values,String key) {Object value=values.get(key);return value instanceof Number n&&Double.isFinite(n.doubleValue())&&n.doubleValue()>=0d?n.doubleValue():null;}
    private static String nullableText(Map<String,Object> values,String key) {Object value=values.get(key);return value instanceof String s?s:null;}
    private static Long multiply(Long value,long factor) {return value==null||value>Long.MAX_VALUE/factor?null:value*factor;}
    private static Integer nullableInt(Map<String,Object> values,String key) {Object value=values.get(key);return value instanceof Number n&&n.longValue()>=0L?(int)Math.min(Integer.MAX_VALUE,n.longValue()):null;}
    private static Boolean nullableBoolean(Map<String,Object> values,String key) {Object value=values.get(key);return value instanceof Boolean b?b:null;}
    private static boolean bool(Map<String,Object> values,String key) {return Boolean.TRUE.equals(values.get(key));}
    private static String text(Map<String,Object> values,String key,String fallback) {Object value=values.get(key);return value instanceof String s?s:fallback;}
    private static Long nonnegative(long value) {return Math.max(0L,value);}
    private static Long nonnegativeOrNull(long value) {return value<0L?null:value;}
    private static Long workerMetricCount(Map<String,Object> values,String stage) {
        Object value=values.get(stage);
        return value instanceof Map<?,?> row&&row.get("count") instanceof Number count&&count.longValue()>=0L?count.longValue():null;
    }
    private Long aggregateInspectionWorkerMetric(String stage) {
        long total=0L;boolean observed=false;
        for(InspectionWorker worker:inspectionWorkerPool) {
            Long count=workerMetricCount(worker.metrics,stage);
            if(count!=null){observed=true;total=saturatedAdd(total,count);}
        }
        return observed?total:null;
    }
    private static Integer nodeInt(JsonNode node,String field) {JsonNode value=node.path(field);return value.isIntegralNumber()&&value.canConvertToInt()&&value.asInt()>=0?value.asInt():null;}
    Path artifact(Job job,String requested) throws Exception {
        if(requested==null||requested.isBlank())throw new IllegalArgumentException("artifact path is required");
        Path base=config.dataDir.resolve("jobs").resolve(job.id).resolve("output").toRealPath();Path relative=Paths.get(requested);
        if(relative.isAbsolute())throw new IllegalArgumentException("Artifact path must be relative to job output");
        Path candidate=base.resolve(relative).normalize();if(!candidate.startsWith(base)||!Files.exists(candidate,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new NotFoundException();
        Path walk=base;for(Path part:base.relativize(candidate)){walk=walk.resolve(part);if(Files.isSymbolicLink(walk))throw new NotFoundException();}
        Path real=candidate.toRealPath();if(!real.startsWith(base)||!Files.isRegularFile(real))throw new NotFoundException();return real;
    }
    private void transition(Job j,String next) throws Exception {synchronized(j){if(j.terminal())return;j.status=next;if("RUNNING".equals(next))j.startedAt=Instant.now();store.update(j);append(j,"status",Map.of("jobId",j.id,"status",next));}}
    // terminal() shares this monitor; SSE also waits for JobEvents' result marker before closing.
    void finish(Job j,String status,int code) throws Exception {finish(j,status,code,null);}
    void finish(Job j,String status,int code,String console) throws Exception {
        synchronized(j) {
            if(j.terminal())return;
            if("load".equals(j.command)&&j.resultJson==null) {
                List<Map<String,Object>> diagnostics=new ArrayList<>();
                if(j.diagnosticJson!=null) {
                    Object parsed=JSON.readValue(j.diagnosticJson,Object.class);
                    if(parsed instanceof Map<?,?> diagnostic)diagnostics.add((Map<String,Object>)diagnostic);
                }
                for(Map<String,Object> secondary:j.secondaryDiagnostics)if(diagnostics.size()<4)diagnostics.add(new LinkedHashMap<>(secondary));
                if(j.protocolDiagnostic!=null)diagnostics.add(new LinkedHashMap<String,Object>(j.protocolDiagnostic));
                if(j.journalDiagnostic!=null)diagnostics.add(new LinkedHashMap<String,Object>(j.journalDiagnostic));
                Map<String,Object> fallback=new LinkedHashMap<>();
                fallback.put("executionId",j.id);fallback.put("status",status);fallback.put("exitCode",code);
                fallback.put("diagnostics",diagnostics);fallback.put("summary",Map.of());
                Map<String,Object> projected=att.server.api.LoadTerminalResult.projectOperationResult(fallback,status,code);
                attachJobDiagnostics(projected,j);
                j.resultJson=JSON.writeValueAsString(projected);
            }
            j.status=status;j.exitCode=code;j.finishedAt=Instant.now();store.update(j);
            append(j,"status",Map.of("jobId",j.id,"status",status));
            enrichPersistedLoadResultWithJournalDiagnostic(j);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("jobId",j.id);result.put("status",status);result.put("exitCode",code);
            result.put("result",j.resultJson==null?null:publicJson(j.id,j.resultJson));
            if(console!=null&&!console.isBlank())result.put("console",console);
            Map<String,Object> terminalPayload=prepareEventPayload(j,"result",result);
            Map<String,Object> boundedTerminalPayload=j.events.projectPayload(terminalPayload);
            Object terminalLimit=boundedTerminalPayload.get("journalLimit");
            if(terminalLimit instanceof Map<?,?> notice&&"ATT-JOURNAL-EVENT-TRUNCATED".equals(notice.get("code"))) {
                j.journalDiagnostic=new LinkedHashMap<String,Object>((Map<String,Object>)notice);
                enrichPersistedLoadResultWithJournalDiagnostic(j);
            }
            Map<String,Object> terminalEvent=appendProjected(j,"result",boundedTerminalPayload);
            if("load".equals(j.command)) {
                Map<String,Object> worker=j.workerMetrics==null?new LinkedHashMap<>():new LinkedHashMap<>(j.workerMetrics);
                Map<String,Object> sizes=new LinkedHashMap<>();
                // Worker frame telemetry is carried outside the public result payload.
                if(worker.get("loadResultTransport") instanceof Map<?,?> transport) {
                    for(String key:new String[]{"workerFrameBytes","workerFrameLimitBytes","journalDataLimitBytes"})
                        if(transport.get(key) instanceof Number)sizes.put(key,transport.get(key));
                }
                Object eventData=terminalEvent.get("data");
                sizes.put("storedResultBytes",j.resultJson==null?0:j.resultJson.getBytes(StandardCharsets.UTF_8).length);
                sizes.put("journalDataBytes",JSON.writeValueAsBytes(eventData).length);
                sizes.put("journalEventBytes",JSON.writeValueAsBytes(terminalEvent).length);
                Object sseData=publicEventData(eventData,config.dataDir.resolve("jobs").resolve(j.id).resolve("output"),packageRootForJob(j.id));
                String ssePayload=JSON.writeValueAsString(sseData);
                String sseEvent="id: "+terminalEvent.get("id")+"\nevent: result\ndata: "+ssePayload+"\n\n";
                sizes.put("sseEventBytes",sseEvent.getBytes(StandardCharsets.UTF_8).length);
                sizes.put("sseEventLimitBytes",JobEvents.MAX_EVENT_DATA_BYTES);
                sizes.put("journalDataLimitBytes",JobEvents.MAX_EVENT_DATA_BYTES);
                sizes.put("workerFrameLimitBytes",att.server.api.internal.WorkerProtocol.MAX_FRAME_BYTES);
                worker.put("loadTerminalResult",sizes);
                j.workerMetrics=worker;
                store.update(j);
            }
        }
    }
    private void finishQuietly(Job j,String status,int code,String diagnostic,String message){try{j.diagnosticJson=JSON.writeValueAsString(Map.of("code",diagnostic,"summary",message));append(j,"diagnostic",JSON.readValue(j.diagnosticJson,Map.class));finish(j,status,code);}catch(Exception ignored){j.status=status;j.exitCode=code;j.finishedAt=Instant.now();}}
    private Map<String,Object> append(Job j,String type,Map<String,?> data) throws Exception {
        return recordAppendedEvent(j,type,j.events.append(type,prepareEventPayload(j,type,data)));
    }
    private Map<String,Object> appendProjected(Job j,String type,Map<String,Object> boundedPayload) throws Exception {
        return recordAppendedEvent(j,type,j.events.appendProjected(type,boundedPayload));
    }
    private Map<String,Object> prepareEventPayload(Job j,String type,Map<String,?> data) {
        Map<String,Object> payload=new LinkedHashMap<>();if(data!=null)data.forEach(payload::put);
        Object existing=payload.get("console");
        if(existing instanceof String line&&!line.isBlank()) payload.put("console",publishConsole(line));
        else {String line=lifecycleConsole(j,type,payload);if(line!=null&&!line.isBlank())payload.put("console",publishConsole(line));}
        return payload;
    }
    private Map<String,Object> recordAppendedEvent(Job j,String type,Map<String,Object> appended) {
        Object bounded=appended.get("data");
        if(bounded instanceof Map<?,?> eventData&&eventData.get("journalLimit") instanceof Map<?,?> notice
                &&"ATT-JOURNAL-EVENT-TRUNCATED".equals(notice.get("code")))
            j.journalDiagnostic=new LinkedHashMap<String,Object>((Map<String,Object>)notice);
        Map<String,Object> journalNotice=j.events.diagnostic();
        if(journalNotice!=null&&("ATT-JOURNAL-PERSISTENCE-DEGRADED".equals(journalNotice.get("code"))
                ||j.journalDiagnostic==null))
            j.journalDiagnostic=new LinkedHashMap<>(journalNotice);
        return appended;
    }
    private void enrichPersistedLoadResultWithJournalDiagnostic(Job job) {
        if(job.journalDiagnostic==null||job.resultJson==null)return;
        String originalResultJson=job.resultJson;
        try {
            Object parsed=JSON.readValue(job.resultJson,Object.class);
            if(parsed instanceof Map<?,?> priorResult) {
                Map<String,Object> enriched=loadResultWithProtocolWarning((Map<String,Object>)priorResult,job);
                job.resultJson=JSON.writeValueAsString(enriched);
                store.update(job);
            }
        } catch(Exception ignored) {
            // Preserve the original execution result if optional journal diagnostics cannot be projected.
            job.resultJson=originalResultJson;
        }
    }
    private static String lifecycleConsole(Job j,String type,Map<String,Object> payload) {
        String mode=executionMode(j==null?null:j.command);
        String jobId=payload.get("jobId") instanceof String id&&!id.isBlank()?id:j==null?"unknown":j.id;
        if("status".equals(type)) return att.console.ExecutionConsoleFormatter.lifecycle(mode,jobId,String.valueOf(payload.get("status")),null);
        if("result".equals(type)) return att.console.ExecutionConsoleFormatter.lifecycle(mode,jobId,String.valueOf(payload.get("status")),payload.get("exitCode") instanceof Number number?number.intValue():null);
        if("diagnostic".equals(type)) return att.console.ExecutionConsoleFormatter.diagnostic(mode,payload.get("code") instanceof String code?code:null,payload.get("summary") instanceof String summary?summary:null);
        return null;
    }
    private static String executionMode(String command) {
        if("debug".equals(command)) return "DEBUG";
        if("load".equals(command)) return "LOAD";
        return "RUN";
    }
    private static String publishConsole(String line) {
        return att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(att.server.api.internal.DiagnosticSanitizer.redactText(line));
    }
    private static String required(JsonNode node,String name){JsonNode v=node.get(name);if(v==null||!v.isTextual()||v.asText().isBlank())throw new IllegalArgumentException(name+" is required");return v.asText();}
    private static String safeText(String s,int max,String name){if(s!=null&&(s.length()>max||s.contains("\n")||s.contains("\r")))throw new IllegalArgumentException("Invalid "+name);return s;}
    private static String safeRelative(String s,String name){if(s==null||s.isBlank())return null;Path p=Paths.get(s);if(p.isAbsolute()||p.normalize().startsWith("..")||s.indexOf('\0')>=0)throw new IllegalArgumentException(name+" must stay inside PACKAGE_ROOT");return p.normalize().toString();}
    private static List<String> safePaths(List<String> paths,String name){if(paths==null)return null;List<String> out=new ArrayList<>();for(String p:paths){if(p==null)throw new IllegalArgumentException(name+" entries must be non-empty paths");out.add(safeRelative(p,name));}return out;}
    private void validatePackageRoot(Path root) throws Exception {
        Path current=root.toRealPath();
        if(!current.equals(root)||!config.allowedRoots.stream().anyMatch(current::startsWith))
            throw new IllegalArgumentException("Configured package root no longer resolves within its original allowed root");
    }
    private static void validatePackagePath(Path root,String relative,String name) throws Exception {if(relative==null)return;Path base=root.toRealPath();Path candidate=base.resolve(relative).normalize();if(!candidate.startsWith(base))throw new IllegalArgumentException(name+" escapes PACKAGE_ROOT");Path existing=candidate;while(existing!=null&&!Files.exists(existing))existing=existing.getParent();if(existing!=null&&!existing.toRealPath().startsWith(base))throw new IllegalArgumentException(name+" resolves outside PACKAGE_ROOT");}
    private static void validateTarget(Map<String,Object> target){if(target==null)throw new IllegalArgumentException("target is required");Object type=target.get("type"),id=target.get("id");if(!(type instanceof String)||!(id instanceof String))throw new IllegalArgumentException("target.type and target.id are required");}
    Path packageRootForJob(String id) throws Exception {
        Job active=jobs.get(id);
        if(active!=null)return packageRoot(active.packageId);
        Map<String,Object> row=store.get(id);
        if(row==null)throw new NotFoundException();
        return packageRoot(String.valueOf(row.get("packageId")));
    }
    Path outputDirectoryForJob(String id) {
        return config.dataDir.resolve("jobs").resolve(id).resolve("output").toAbsolutePath().normalize();
    }
    Path artifactArchivePath(String id) { return config.dataDir.resolve("jobs").resolve(id).resolve("artifacts.zip"); }
    boolean acquireArtifactReadSlot() { return artifactReadSlots.tryAcquire(); }
    void releaseArtifactReadSlot() { artifactReadSlots.release(); }
    Map<String,Object> artifactIndex(String id) throws Exception { return artifactIndex(id,0,100); }
    JobArtifactOverview artifactOverview(String id) throws Exception {
        if(store.get(id)==null)throw new NotFoundException();
        JsonNode publication=readArchiveStatus(artifactArchivePath(id).resolveSibling("archive-status.json"));
        String state=publication.path("state").asText("PENDING");
        if(store.workerQuarantined(id))state="FAILED";
        if("READY".equals(state)&&!Files.isRegularFile(artifactArchivePath(id)))state="FAILED";
        if(!"READY".equals(state))return new JobArtifactOverview(state,false,"UNVERIFIED",null,null,null,null,null,null,false);
        JsonNode value=publication.path("overview");
        if(publication.path("overviewVersion").asInt()!=1||!value.isObject())
            return new JobArtifactOverview(state,false,"UNVERIFIED",null,null,null,null,null,null,false);
        String report=value.path("approvedReportPath").isTextual()?value.path("approvedReportPath").asText():null;
        boolean complete=value.path("complete").asBoolean(false);
        long omitted=Math.max(0L,value.path("omittedCandidateCount").asLong(0L));
        List<JobArtifactOverview.OmissionCategory> omissions=new ArrayList<>();
        JsonNode rows=value.path("omissionCategories");
        if(rows.isArray())for(JsonNode row:rows) {
            String category=row.path("category").asText("");long count=row.path("count").asLong(-1L);
            if(count>0L&&Set.of("unsafe_path","unsafe_entry","incomplete_load_report","recovery_checkpoint","reserved_manifest","unsupported_type","duplicate_path","entry_limit","oversized","rejected_text","reason_unavailable").contains(category))
                omissions.add(new JobArtifactOverview.OmissionCategory(count,category));
        }
        boolean reportOmitted=omissions.stream().anyMatch(omission->"incomplete_load_report".equals(omission.category()));
        String availability=report!=null?"READY":reportOmitted?"OMITTED":"NOT_PRESENT";
        return new JobArtifactOverview(state,true,availability,report,nodeInt(value,"approvedArtifactCount"),
                nodeInt(value,"approvedReportCount"),omitted,complete,List.copyOf(omissions),true);
    }
    Map<String,Object> artifactIndex(String id,int offset,int limit) throws Exception {
        if(offset<0||offset>1_000_000||limit<1||limit>100)throw new IllegalArgumentException("Artifact listing pagination is invalid");
        if(!artifactReadSlots.tryAcquire())throw new ArtifactCapacityException();
        artifactLifecycle.readLock().lock();
        try {
            if(store.get(id)==null)throw new NotFoundException();
            Path archive=artifactArchivePath(id),statusFile=archive.resolveSibling("archive-status.json");
            Map<String,Object> out=new LinkedHashMap<>();
            JsonNode publication=readArchiveStatus(statusFile);
            String state=publication.path("state").asText("FAILED");
            out.put("reason",publication.path("reason").asText(""));
            if(publication.path("diagnostic").isObject())out.put("diagnostic",JSON.convertValue(publication.path("diagnostic"),Map.class));
            boolean quarantined=store.workerQuarantined(id);
            if(quarantined){state="FAILED";out.put("reason","worker_unverified");}
            if(Files.isRegularFile(archive)&&"READY".equals(state)) {
                try {
                    Map<String,Object> data=ArtifactArchive.verify(archive,false);
                    long archiveSize=Files.size(archive);
                    List<?> all=(List<?>)data.get("entries");
                    int start=Math.min(offset,all.size()),end=Math.min(all.size(),start+limit);
                    out.put("items",all.subList(start,end));out.put("nextOffset",end<all.size()?end:null);
                    out.put("primaryReports",all.stream().filter(item->item instanceof Map<?,?> row&&Boolean.TRUE.equals(row.get("report"))).toList());
                    Map<String,Map<String,Object>> categories=new LinkedHashMap<>();
                    long itemBytes=0L;
                    for(Object item:all)if(item instanceof Map<?,?> row) {
                        String path=String.valueOf(row.get("path"));
                        long size=row.get("size") instanceof Number number?Math.max(0L,number.longValue()):0L;
                        itemBytes=size>Long.MAX_VALUE-itemBytes?Long.MAX_VALUE:itemBytes+size;
                        String category=artifactCategory(path);
                        Map<String,Object> tally=categories.computeIfAbsent(category,key->{Map<String,Object> value=new LinkedHashMap<>();value.put("count",0L);value.put("bytes",0L);return value;});
                        tally.put("count",((Number)tally.get("count")).longValue()+1L);
                        long current=((Number)tally.get("bytes")).longValue();tally.put("bytes",size>Long.MAX_VALUE-current?Long.MAX_VALUE:current+size);
                    }
                    out.put("categorySummary",categories);out.put("archiveItemsCount",all.size());out.put("archiveItemsBytes",itemBytes);
                    out.put("archiveStatus","READY");out.put("omissions",data.get("omissions"));out.put("complete",data.get("complete"));
                    if(data.get("diagnostics") instanceof List<?> notices&&!notices.isEmpty())out.put("omissionDiagnostics",notices);
                    out.put("archiveSizeBytes",archiveSize);
                } catch(Exception corrupt) { state="FAILED"; }
            }
            if(!out.containsKey("archiveStatus")) {
                out.put("items",List.of());out.put("nextOffset",null);out.put("primaryReports",List.of());out.put("archiveStatus",state);
            }
            out.put("retryable",!quarantined&&"FAILED".equals(out.get("archiveStatus"))&&Files.isDirectory(outputDirectoryForJob(id)));
            out.put("archiveMetrics",store.archiveMetrics(id));
            out.put("downloadPath","archive.zip");return out;
        } finally {artifactLifecycle.readLock().unlock();artifactReadSlots.release();}
    }

    private static String artifactCategory(String path) {
        String lower=path.toLowerCase(java.util.Locale.ROOT);
        if(lower.contains("/checkpoints/")||lower.startsWith("checkpoints/"))return "recoveryCheckpoints";
        String leaf=lower.substring(lower.lastIndexOf('/')+1);
        if(lower.contains("/details/")||lower.contains("/timeseries/")||leaf.startsWith("series-")||leaf.contains("metric"))return "metricsTimeSeries";
        if(leaf.contains("summary")||leaf.contains("manifest")||lower.endsWith("/report/index.html"))return "summaryManifests";
        if(lower.contains("/evidence/")||lower.contains("/samples/")||lower.contains("/events/")
                ||lower.contains("/failures/")||lower.contains("/iterations/")||lower.contains("/executions/")
                ||leaf.contains("iteration"))return "executionEvidence";
        return "other";
    }

    byte[] archiveEntry(String id,String requested) throws Exception {
        if(!ArtifactArchive.safePath(requested)||ArtifactArchive.MANIFEST.equalsIgnoreCase(requested))throw new IllegalArgumentException("Invalid archive entry path");
        try(ArtifactRead read=openArtifactArchive(id,false)) { return read.verified.read(requested); }
        catch(java.io.FileNotFoundException missing){throw new NotFoundException();}
    }
    final class ArtifactRead implements AutoCloseable {
        final Path path;
        final ArtifactArchive.VerifiedRead verified;
        final ArtifactDownload download;
        ArtifactRead(Path path,ArtifactArchive.VerifiedRead verified,ArtifactDownload download){this.path=path;this.verified=verified;this.download=download;}
        long length(){return download.length;}
        void writeTo(java.io.OutputStream output) throws java.io.IOException {download.writeTo(output);}
        @Override public void close() throws java.io.IOException {try {if(download!=null)download.close();else verified.close();} finally {artifactLifecycle.readLock().unlock();artifactReadSlots.release();}}
    }
    ArtifactRead openArtifactArchive(String id,boolean digests) throws Exception {
        if(!artifactReadSlots.tryAcquire())throw new ArtifactCapacityException();
        artifactLifecycle.readLock().lock();
        try {
            if(store.get(id)==null)throw new NotFoundException();
            if(store.workerQuarantined(id))throw new ArchiveUnavailableException("FAILED");
            Path archive=artifactArchivePath(id),status=archive.resolveSibling("archive-status.json");
            String archiveState=readArchiveStatus(status).path("state").asText("FAILED");
            if(!"READY".equals(archiveState))throw new ArchiveUnavailableException(archiveState);
            ArtifactArchive.VerifiedRead verified=null;ArtifactDownload download=null;
            try { if(digests)download=ArtifactDownload.open(archive);else verified=ArtifactArchive.openVerified(archive,false); }
            catch(Exception invalid){throw new ArchiveUnavailableException("FAILED");}
            return new ArtifactRead(archive,verified,download);
        } catch(Exception failure){artifactLifecycle.readLock().unlock();artifactReadSlots.release();throw failure;}
    }
    private void archiveStatus(String id,String state,String reason) throws Exception { archiveStatus(id,state,reason,null); }
    private void archiveStatus(String id,String state,String reason,Map<String,Object> diagnostic) throws Exception {
        archiveStatus(id,state,reason,diagnostic,null);
    }
    private void archiveStatus(String id,String state,String reason,Map<String,Object> diagnostic,Map<String,Object> overview) throws Exception {
        Path target=artifactArchivePath(id).resolveSibling("archive-status.json");
        Files.createDirectories(target.getParent());
        Path temporary=Files.createTempFile(target.getParent(),".archive-status-",".tmp");
        try {
            Map<String,Object> value=new LinkedHashMap<>();value.put("state",state);value.put("reason",reason);value.put("manifestVersion",1);
            if(diagnostic!=null)value.put("diagnostic",diagnostic);
            if(overview!=null){value.put("overviewVersion",1);value.put("overview",overview);}
            Files.write(temporary,JSON.writeValueAsBytes(value));
            try(var channel=java.nio.channels.FileChannel.open(temporary,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(temporary,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static JsonNode readArchiveStatus(Path status) {
        try {
            if(!Files.isRegularFile(status))return JSON.valueToTree(Map.of("state","PENDING"));
            if(Files.size(status)>4096)throw new IllegalArgumentException();
            JsonNode value=JSON.readTree(status.toFile());
            if(value==null||!Set.of("PENDING","ARCHIVING","READY","FAILED").contains(value.path("state").asText()))throw new IllegalArgumentException();
            return value;
        } catch(Exception invalid){return JSON.valueToTree(Map.of("state","FAILED","reason","invalid_metadata"));}
    }
    void retryArtifactArchive(String id) throws Exception {
        if(!terminal(id))throw new IllegalArgumentException("Artifacts can be published after the Job has ended");
        if(store.workerQuarantined(id))throw new IllegalArgumentException("Artifacts require independent recovery after Worker quarantine");
        if(!Files.isDirectory(outputDirectoryForJob(id)))throw new IllegalArgumentException("Private source output is unavailable for retry");
        archiveRetries.incrementAndGet();
        store.artifactRecoveryNeeded(id,true);
        requestedArtifactRetries.add(id);
        scheduleArtifactArchive(id);
    }
    void scheduleArtifactArchive(String id) {
        if(closing||!pendingArtifactJobs.add(id))return;
        requestedArtifactRetries.remove(id);
        final long queuedAt=System.nanoTime();
        try { artifactWriters.execute(() -> {
            artifactLifecycle.readLock().lock();
            Path output=outputDirectoryForJob(id), archive=artifactArchivePath(id);
            ArtifactMetricsRecorder metrics=null;
            try {
                if(store.get(id)==null||!terminal(id))return;
                if(jobs.containsKey(id))return;
                var previous=store.archiveMetrics(id);
                metrics=new ArtifactMetricsRecorder(queuedAt,previous==null?1:Math.min(Long.MAX_VALUE-1,previous.attempts)+1);
                metrics.phase("worker_fence");
                ArtifactMetricsRecorder.CURRENT.set(metrics);
                store.archiveMetrics(id,metrics.value);
                store.artifactRecoveryNeeded(id,true);
                if(store.workerQuarantined(id)) {
                    metrics.fail("worker_unverified");
                    finishArtifactFailure(id,"worker_unverified","FAILED_IO");
                    return;
                }
                if(store.workerMayBeAlive(id)) {
                    metrics.fail("worker_active");
                    finishArtifactFailure(id,"worker_active","FAILED_IO");
                    return;
                }
                if(Files.isRegularFile(archive)) {
                    metrics.phase("verification_fsync");
                    try { ArtifactArchive.verify(archive,true); }
                    catch(Exception corrupt) {
                        if(!Files.isDirectory(output))throw corrupt;
                        Files.delete(archive);
                    }
                }
                if(!Files.isRegularFile(archive)) {
                    metrics.phase("publication");
                    archiveStatus(id,"ARCHIVING","");
                    if(!Files.isDirectory(output))throw new java.io.IOException("Worker output is unavailable");
                    cleanupArtifactStaging(archive.getParent());
                    // Preserve the exact bytes produced by the Engine/Worker; ZIP publication never rewrites them.
                    artifactPublisher.publish(output,archive,ServerRuntime::validateApprovedArtifactBytes);
                }
                metrics.phase("publication");
                Map<String,Object> overview=ArtifactArchive.overviewSummary(archive);
                archiveStatus(id,"READY","",null,overview);
                metrics.value.zipBytes=Files.size(archive);
                metrics.value.outcome="READY";
                metrics.phase("cleanup");
                try {deleteTree(output);cleanupArtifactStaging(archive.getParent());store.artifactRecoveryNeeded(id,false);}
                catch(Exception cleanupFailure){metrics.fail("cleanup_failed");metrics.value.outcome="CLEANUP_PENDING";java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Artifact cleanup will be retried for Job "+id);}
                try {store.audit("SYSTEM","ARTIFACT_PUBLICATION",id,null,"READY");}
                catch(Exception auditFailure){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to audit artifact publication for Job "+id);}
            } catch(Exception failure) {
                boolean policy=failure instanceof IllegalArgumentException;
                if(metrics!=null&&metrics.value.failurePhase==null)metrics.fail(policy?"policy_limit":"publication_failed");
                finishArtifactFailure(id,policy?"policy_limit":"publication_failed",policy?"REJECTED_POLICY":"FAILED_IO",failure);
                java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Artifact publication failed for Job "+id);
            } finally {
                ArtifactMetricsRecorder.CURRENT.remove();
                if(metrics!=null) {
                    metrics.finish();
                    try {store.archiveMetrics(id,metrics.value);}
                    catch(Exception unavailable){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to persist artifact metrics for Job "+id);}
                }
                pendingArtifactJobs.remove(id);
                // Transfer an accepted retry before releasing the retention
                // fence, so expiry cannot delete its source between writers.
                try {if(requestedArtifactRetries.remove(id))scheduleArtifactArchive(id);}
                finally {artifactLifecycle.readLock().unlock();}
            }
        }); } catch(RejectedExecutionException full) {
            pendingArtifactJobs.remove(id);
            // Recovery retries when writer capacity becomes available; a retry cannot replace a READY state.
        }
    }
    private void finishArtifactFailure(String id,String reason,String auditOutcome) {
        finishArtifactFailure(id,reason,auditOutcome,null);
    }
    private void finishArtifactFailure(String id,String reason,String auditOutcome,Exception failure) {
        archiveFailures.incrementAndGet();
        Map<String,Object> diagnostic=null;
        if(failure instanceof ArtifactArchive.LimitFailure){
            ArtifactArchive.LimitFailure limited=(ArtifactArchive.LimitFailure)failure;
            diagnostic=att.server.api.LimitDiagnostic.create("ATT-ARCHIVE-LIMIT-EXCEEDED","Artifact archive exceeded a configured publication limit",
                    limited.limitName,limited.limit,limited.observed,"server.artifactPublication",att.server.api.LimitOutcome.ARCHIVE_FAILED,
                    "publication","Reduce eligible artifact entry count or size and retry archive publication.","archive.zip");
        }else if("policy_limit".equals(reason)){
            diagnostic=att.server.api.LimitDiagnostic.create("ATT-ARCHIVE-POLICY-LIMIT","Artifact archive could not be published within its safe policy",
                    "artifact.archivePolicy",null,null,"server.artifactPublication",att.server.api.LimitOutcome.ARCHIVE_FAILED,
                    "publication","Reduce eligible artifact entry count or size and retry archive publication.","archive.zip");
        }
        try {archiveStatus(id,"FAILED",reason,diagnostic);}catch(Exception ignored){}
        try {store.artifactRecoveryNeeded(id,false);}catch(Exception ignored){}
        try {store.audit("SYSTEM","ARTIFACT_PUBLICATION",id,null,auditOutcome);}catch(Exception ignored){}
    }
    private static void cleanupArtifactStaging(Path jobDirectory) throws Exception {
        try(var files=Files.list(jobDirectory)) {
            for(Path file:files.filter(p->{String name=p.getFileName().toString();return name.startsWith(".artifact-export-")||name.startsWith(".artifact-")&&name.endsWith(".zip.tmp")||name.startsWith(".archive-status-")&&name.endsWith(".tmp");}).toList())deleteTree(file);
        }
    }
    synchronized int recoverArtifactArchives() throws Exception {
        List<String> ids=store.artifactRecoveryJobIds(artifactRecoveryCursor,64);
        if(ids.isEmpty()){artifactRecoveryCursor="";return 0;}
        for(String id:ids){artifactRecoveryCursor=id;scheduleArtifactArchive(id);}
        return ids.size();
    }
    private void recoverArtifactArchivesSafely() {
        try {recoverArtifactArchives();}
        catch(Exception failure){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to recover pending artifact publications");}
    }
    Object publicEventData(Object data,Path output,Path packageRoot) throws Exception {
        JsonNode node=JSON.valueToTree(att.server.api.internal.DiagnosticSanitizer.sanitizeValue(data));
        JsonNode sanitized=sanitize(node,output,packageRoot);
        if(sanitized instanceof ObjectNode object && !object.hasNonNull("console")) {
            Map<String,Object> payload=JSON.convertValue(object,Map.class);
            String line=att.console.ExecutionConsoleFormatter.formatPayload(payload,att.console.ExecutionConsoleFormatter.Options.published());
            if(line!=null&&!line.isBlank()) object.put("console",publishConsole(line));
        }
        return sanitized;
    }
    private JsonNode publicJson(String id,String json) throws Exception {
        JsonNode node=JSON.readTree(json);Object safe=att.server.api.internal.DiagnosticSanitizer.sanitizeValue(JSON.convertValue(node,Object.class));node=JSON.valueToTree(safe);Path output=config.dataDir.resolve("jobs").resolve(id).resolve("output").toAbsolutePath().normalize();
        return sanitize(node,output,packageRootForJob(id));
    }
    private JsonNode publicJobResult(String id,String command,String status,int exitCode,String json) throws Exception {
        if(!"load".equals(command))return publicJson(id,json);
        Map<?,?> stored=JSON.readValue(json,Map.class);
        Map<String,Object> compact=att.server.api.LoadTerminalResult.projectOperationResult(stored,status,exitCode);
        JsonNode node=JSON.valueToTree(att.server.api.internal.DiagnosticSanitizer.sanitizeValue(compact));
        Path output=config.dataDir.resolve("jobs").resolve(id).resolve("output").toAbsolutePath().normalize();
        return sanitize(node,output,packageRootForJob(id));
    }
    private static boolean semanticLocation(String field) {
        return Set.of("queue","requestQueue","requestQueues","requestQueueDefault","replyQueue","replyQueueDefault",
                "channel","queueManager","replyQueueManager","remotePath","remoteDirectory","remoteFile","host","hostname","url","baseUrl").contains(field);
    }
    private JsonNode sanitize(JsonNode node,Path output,Path packageRoot) {return sanitize(node,output,packageRoot,"");}
    private JsonNode sanitize(JsonNode node,Path output,Path packageRoot,String field) {
        return sanitize(node,output,packageRoot,field,true);
    }
    private JsonNode sanitize(JsonNode node,Path output,Path packageRoot,String field,boolean protocolReferences) {
        if(node==null)return null;
        if(node.isTextual()) {
            if(protocolReferences && ("$attDebugKeepDefault".equals(field) || semanticLocation(field)))return node;
            String value=node.asText();value=replacePathPrefix(value,output.toString(),"artifact");
            if(packageRoot!=null)value=replacePathPrefix(value,packageRoot.toString(),"package");
            return JSON.getNodeFactory().textNode(semanticLocation(field)?value:att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(value,packageRoot));
        }
        if(node.isObject()){
            com.fasterxml.jackson.databind.node.ObjectNode copy=JSON.createObjectNode();
            node.fields().forEachRemaining(e->{
                String key=e.getKey();
                if(!protocolReferences){
                    key=replacePathPrefix(key,output.toString(),"artifact");
                    if(packageRoot!=null)key=replacePathPrefix(key,packageRoot.toString(),"package");
                    key=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(key,packageRoot);
                    if(copy.has(key))throw new IllegalArgumentException("Export field names collide after path projection");
                }
                copy.set(key,sanitize(e.getValue(),output,packageRoot,protocolReferences && semanticLocation(field)?field:e.getKey(),protocolReferences));
            });return copy;
        }
        if(node.isArray()){com.fasterxml.jackson.databind.node.ArrayNode copy=JSON.createArrayNode();for(JsonNode item:node)copy.add(sanitize(item,output,packageRoot,field,protocolReferences));return copy;}
        return node.deepCopy();
    }
    private static String replacePathPrefix(String value,String prefix,String logical){if(value.equals(prefix))return logical+":";String separator=java.io.File.separator;value=value.replace(prefix+separator,logical+":");if("\\".equals(separator))value=value.replace(prefix+"/",logical+":");else value=value.replace(prefix+"\\",logical+":");return value;}
    static String safeMessage(Exception e){String m=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(att.server.api.internal.DiagnosticSanitizer.redactText(e.getMessage()));return m.isEmpty()?"ATT Worker execution failed":m.length()>500?m.substring(0,500):m;}
    JobEvents events(String id) throws Exception {
        Map<String,Object> storedJob=JOB_ID.matcher(id).matches()?store.get(id):null;
        if(storedJob==null)throw new NotFoundException();
        Job active=jobs.get(id);if(active!=null)return active.events;
        Object stripe=terminalEventCacheStripes[Math.floorMod(id.hashCode(),terminalEventCacheStripes.length)];
        synchronized(stripe) {
            synchronized(terminalEventCacheLock) {
                JobEvents cached=terminalEventCache.get(id);
                if(cached!=null)return cached;
            }
            JobEvents loaded=new JobEvents(config.dataDir.resolve("jobs").resolve(id).resolve("events.jsonl"),
                    config.maxEventsPerJob,config.maxEventJournalBytes,config.maxEventJournalMemoryBytes,
                    eventCompactions,eventNotifications,this::recordCompactionFailure);
            if(List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(storedJob.get("status"))) {
                Map<String,Object> recoveredResult=new LinkedHashMap<>();
                recoveredResult.put("jobId",id);
                recoveredResult.put("status",storedJob.get("status"));
                recoveredResult.put("exitCode",storedJob.get("exitCode"));
                Object projectedResult=null;
                if(storedJob.get("resultJson") instanceof String resultJson)
                    try {projectedResult=publicJson(id,resultJson);}catch(Exception invalidStoredResult) { /* keep the durable status and exit code */ }
                recoveredResult.put("result",projectedResult);
                loaded.recoverMissingTerminalResult(recoveredResult);
            }
            long weight=loaded.cacheWeightBytes();
            if(weight<=TERMINAL_EVENT_CACHE_MAX_WEIGHT) {
                synchronized(terminalEventCacheLock) {
                    JobEvents prior=terminalEventCache.put(id,loaded);
                    if(prior!=null)terminalEventCacheWeight-=prior.cacheWeightBytes();
                    terminalEventCacheWeight+=weight;
                    while(terminalEventCache.size()>TERMINAL_EVENT_CACHE_MAX_ENTRIES
                            ||terminalEventCacheWeight>TERMINAL_EVENT_CACHE_MAX_WEIGHT) {
                        Map.Entry<String,JobEvents> eldest=terminalEventCache.entrySet().iterator().next();
                        terminalEventCacheWeight-=eldest.getValue().cacheWeightBytes();
                        terminalEventCache.remove(eldest.getKey());
                    }
                }
            }
            return loaded;
        }
    }

    private Map<String,Object> eventJournalStatistics() {
        long activeBytes=0L,retainedEvents=0L,retainedMemoryBytes=0L,attempts=0L,failures=0L,lastCompactionAgeMs=0L;
        long dispatchFailures=0L,appendBytes=0L,compactionBytes=0L;
        int active=0,degraded=0;
        for(Job job:jobs.values()) {
            Map<String,Object> metrics=job.events.metrics();
            active++;
            activeBytes=saturatedAdd(activeBytes,((Number)metrics.get("journalBytes")).longValue());
            retainedEvents=saturatedAdd(retainedEvents,((Number)metrics.get("retainedEventCount")).longValue());
            retainedMemoryBytes=saturatedAdd(retainedMemoryBytes,((Number)metrics.get("retainedMemoryBytes")).longValue());
            attempts=saturatedAdd(attempts,((Number)metrics.get("compactionAttempts")).longValue());
            failures=saturatedAdd(failures,((Number)metrics.get("compactionFailures")).longValue());
            dispatchFailures=saturatedAdd(dispatchFailures,((Number)metrics.get("listenerDispatchFailures")).longValue());
            appendBytes=saturatedAdd(appendBytes,((Number)metrics.get("appendBytesWritten")).longValue());
            compactionBytes=saturatedAdd(compactionBytes,((Number)metrics.get("compactionBytesWritten")).longValue());
            if(metrics.get("lastCompactionAgeMs") instanceof Number age)
                lastCompactionAgeMs=Math.max(lastCompactionAgeMs,age.longValue());
            if("DEGRADED".equals(metrics.get("journalPersistence")))degraded++;
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("activeJobs",active);result.put("degradedActiveJobs",degraded);
        result.put("activeJournalBytes",activeBytes);result.put("activeRetainedEvents",retainedEvents);
        result.put("activeRetainedMemoryBytes",retainedMemoryBytes);
        result.put("activeMemoryBudgetBytes",active*(long)config.maxEventJournalMemoryBytes);
        result.put("activeMemoryOverBudgetBytes",Math.max(0L,retainedMemoryBytes-active*(long)config.maxEventJournalMemoryBytes));
        result.put("activeCompactionAttempts",attempts);result.put("activeCompactionFailures",failures);
        result.put("compactionFailuresSinceStart",compactionFailuresSinceStart.get());
        result.put("activeListenerDispatchFailures",dispatchFailures);result.put("appendBytesWritten",appendBytes);
        result.put("compactionBytesWritten",compactionBytes);
        result.put("writeAmplification",appendBytes==0L?null:1d+((double)compactionBytes)/appendBytes);
        result.put("oldestActiveCompactionAgeMs",lastCompactionAgeMs);
        result.put("maxJournalBytesPerJob",config.maxEventJournalBytes);
        result.put("memoryBudgetBytesPerJob",config.maxEventJournalMemoryBytes);
        long usable;
        try{usable=Files.getFileStore(config.dataDir).getUsableSpace();}
        catch(Exception unavailable){usable=-1L;}
        result.put("storageUsableBytes",usable);
        result.put("storageHeadroomLow",usable>=0L&&usable<JobEvents.MIN_STORAGE_HEADROOM_BYTES);
        synchronized(terminalEventCacheLock) {
            result.put("cachedTerminalJobs",terminalEventCache.size());
            result.put("cachedTerminalWeightBytes",terminalEventCacheWeight);
        }
        return result;
    }

    void recordCompactionFailure() { compactionFailuresSinceStart.updateAndGet(value->value==Long.MAX_VALUE?value:value+1L); }
    boolean terminal(String id) throws Exception {Job active=jobs.get(id);if(active!=null)return active.terminal();Map<String,Object> row=store.get(id);if(row==null)throw new NotFoundException();return List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(row.get("status"));}
    /** Validate bounded text artifacts without modifying any Engine/Worker-generated byte. */
    static byte[] approvedArtifactBytes(Path file) throws Exception {
        final int limit=16*1024*1024;
        byte[] bytes;
        try(var input=Files.newInputStream(file)){bytes=input.readNBytes(limit+1);}
        validateApprovedArtifactBytes(file,bytes);
        return bytes;
    }
    /** Reject unsafe captured output while leaving accepted bytes owned by ArtifactArchive unchanged. */
    static void validateApprovedArtifactBytes(Path file,byte[] bytes) {
        final int limit=16*1024*1024;
        if(bytes.length>limit)throw new IllegalArgumentException("Artifact exceeds the safe text download limit (16 MiB)");
        String text;
        try {text=java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("Binary artifacts cannot be exported");}
        if(text.indexOf(0)>=0)throw new IllegalArgumentException("Binary artifacts cannot be exported");
        // Never alter producer bytes when creating ZIPs. Plain-text evidence is checked for
        // credentials and host-local paths; generated HTML and structured JSON/YAML have their
        // own syntax/presentation rules. Applying free-text path rewriting to structured
        // payloads incorrectly classifies legitimate remotePath values and report scripts.
        String name=file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        boolean html=name.endsWith(".html")||name.endsWith(".htm");
        boolean structured=name.endsWith(".json")||name.endsWith(".yaml")||name.endsWith(".yml");
        // Structured snapshots are already protected by the typed exportValue check below.
        // Text heuristics are not idempotent on quoted YAML and may discard valid case.yaml.
        if(!html && !structured && !att.server.api.internal.DiagnosticSanitizer.redactText(text).equals(text))
            throw new IllegalArgumentException("Artifact contains unapproved sensitive text");
        if((name.endsWith(".log")||name.endsWith(".txt")||name.endsWith(".md"))
                && !att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(text).equals(text))
            throw new IllegalArgumentException("Artifact contains unapproved host-local paths");
        if(name.endsWith(".json") || name.endsWith(".yaml") || name.endsWith(".yml")) {
            try {
                Object source;
                if(name.endsWith(".json")) source=ARTIFACT_JSON.readValue(text,Object.class);
                else {
                    org.yaml.snakeyaml.LoaderOptions options=new org.yaml.snakeyaml.LoaderOptions();
                    options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);
                    options.setNestingDepthLimit(64);options.setCodePointLimit(limit);
                    source=new org.yaml.snakeyaml.Yaml(new ArtifactYamlConstructor(options)).load(text);
                }
                Object safe=att.security.SecretProtection.exportValue(source);
                if(!java.util.Objects.equals(source,safe))
                    throw new IllegalArgumentException("Artifact contains unapproved sensitive fields");
            } catch(IllegalArgumentException rejected) {throw rejected;}
              catch(Exception invalid) {throw new IllegalArgumentException("Invalid structured artifact cannot be exported");}
        }
    }
    /** Legacy sanitized export projection; retained only for compatibility tests and callers. */
    byte[] publicArtifact(String id, Path file) throws Exception {
        final int limit=16*1024*1024;
        byte[] bytes;
        try(var input=Files.newInputStream(file)){bytes=input.readNBytes(limit+1);}
        if(bytes.length>limit)throw new IllegalArgumentException("Artifact exceeds the safe text download limit (16 MiB)");
        String text;
        try { text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("Binary artifacts require trusted filesystem access");}
        if(text.indexOf('\0')>=0)throw new IllegalArgumentException("Binary artifacts require trusted filesystem access");
        String filename=file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if(filename.endsWith(".json")||filename.endsWith(".yaml")||filename.endsWith(".yml")) {
            try {
                boolean json=filename.endsWith(".json");
                Object raw;
                if(json)raw=ARTIFACT_JSON.readValue(text,Object.class);
                else {
                    org.yaml.snakeyaml.LoaderOptions options=new org.yaml.snakeyaml.LoaderOptions();
                    options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setNestingDepthLimit(64);options.setCodePointLimit(limit);
                    raw=new org.yaml.snakeyaml.Yaml(new ArtifactYamlConstructor(options)).load(text);
                }
                JsonNode projected=sanitize(JSON.valueToTree(att.security.SecretProtection.exportValue(raw)),outputDirectoryForJob(id),packageRootForJob(id),"",false);
                if(json)return JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(projected);
                org.yaml.snakeyaml.DumperOptions options=new org.yaml.snakeyaml.DumperOptions();options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
                return new org.yaml.snakeyaml.Yaml(options).dump(ARTIFACT_JSON.convertValue(projected,Object.class)).getBytes(StandardCharsets.UTF_8);
            } catch(Exception invalid){throw new IllegalArgumentException("Structured artifact cannot be safely published");}
        }
        if(filename.endsWith(".html")||filename.endsWith(".htm")) {
            text=redactArtifactHtml(text,outputDirectoryForJob(id).toString(),packageRootForJob(id));
        } else {
            text=att.server.api.internal.DiagnosticSanitizer.redactText(text);
            text=replacePathPrefix(text,outputDirectoryForJob(id).toString(),"artifact");
            text=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(text,packageRootForJob(id));
        }
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static String redactArtifactHtml(String html,String outputRoot,Path packageRoot) {
        java.util.regex.Pattern pattern=java.util.regex.Pattern.compile("(?is)<script\\b([^>]*)>(.*?)</script\\s*>");
        java.util.regex.Matcher matcher=pattern.matcher(html);StringBuffer masked=new StringBuffer();
        java.util.Map<String,String> executableScripts=new java.util.LinkedHashMap<>();int index=0;
        while(matcher.find()) {
            String attributes=matcher.group(1);
            boolean jsonData=java.util.regex.Pattern.compile("(?i)\\btype\\s*=\\s*['\\\"]?application/json\\b").matcher(attributes).find();
            if(jsonData)continue;
            String token="__ATT_SCRIPT_BLOCK_"+index+"_"+java.util.UUID.randomUUID().toString().replace("-","")+"__";
            executableScripts.put(token,matcher.group());
            matcher.appendReplacement(masked,java.util.regex.Matcher.quoteReplacement(token));index++;
        }
        matcher.appendTail(masked);
        String safe=att.server.api.internal.DiagnosticSanitizer.redactText(masked.toString());
        safe=replacePathPrefix(safe,outputRoot,"artifact");
        safe=att.server.api.internal.DiagnosticSanitizer.redactAbsolutePaths(safe,packageRoot);
        for(java.util.Map.Entry<String,String> entry:executableScripts.entrySet())safe=safe.replace(entry.getKey(),entry.getValue());
        return safe;
    }
    Path artifact(String id,String requested) throws Exception {Map<String,Object> row=store.get(id);if(row==null)throw new NotFoundException();Job j=jobs.get(id);if(j!=null)return artifact(j,requested);Path base=config.dataDir.resolve("jobs").resolve(id).resolve("output").toRealPath();Path relative=Paths.get(requested);if(relative.isAbsolute())throw new IllegalArgumentException("Artifact path must be relative to job output");Path candidate=base.resolve(relative).normalize();if(!candidate.startsWith(base)||!Files.exists(candidate,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new NotFoundException();Path walk=base;for(Path part:base.relativize(candidate)){walk=walk.resolve(part);if(Files.isSymbolicLink(walk))throw new NotFoundException();}Path real=candidate.toRealPath();if(!real.startsWith(base)||!Files.isRegularFile(real))throw new NotFoundException();return real;}
    void cleanupExpiredJobs() throws Exception {
        Instant cutoff=Instant.now().minus(java.time.Duration.ofDays(config.jobRetentionDays));
        try {reconcileDeletingJobs();}
        catch(Exception failure){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to reconcile deleting ATT Server jobs: "+safeMessage(failure));}
        if(!artifactLifecycle.writeLock().tryLock())return;
        try {
            for(String id:store.expiredJobIds(cutoff))if(!jobs.containsKey(id)&&!pendingArtifactJobs.contains(id)&&!store.workerMayBeAlive(id))try{
                if(store.markDeleting(id,cutoff)){deleteTree(config.dataDir.resolve("jobs").resolve(id));store.deleteJob(id);}
            }catch(Exception failure){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to remove expired ATT Server job "+id);}
        } finally {artifactLifecycle.writeLock().unlock();}
    }

    private void cleanupExpiredJobsSafely(){try{cleanupExpiredJobs();}catch(Exception e){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to clean expired ATT Server jobs: "+safeMessage(e));}}
    private static void deleteTree(Path root)throws java.io.IOException {
        if(!JobDirectoryUsage.deleteJobDirectory(root)) throw new java.io.IOException("Refusing to delete a symbolic link");
    }
    void cleanupJobStorage() throws Exception {
        if(!config.jobStorage.enabled) return;
        restoreStorageCursor();
        invalidateStorageCursorIfStale();
        if(!reconcileDeletingJobs()) { publishStorageReport("deferred",null,null,null,null,0,0L,false); return; }
        Path jobsRoot=config.dataDir.resolve("jobs");
        JobDirectoryUsage.Measurement measurement=JobDirectoryUsage.measure(jobsRoot);
        if(!measurement.complete) { publishStorageReport("measurement_failed",null,false,null,null,0,0L,false); return; }
        if(measurement.bytes<=config.jobStorage.maxTotalBytes) {
            storageScanAfterFinished=null; storageScanAfterId=null; storageScanCoveredHead=false;
            publishStorageReport("ok",measurement.bytes,true,0,0,0,0L,false); return;
        }
        if(storageScanAfterFinished==null) storageScanCoveredHead=true;
        Instant cutoff=clock.instant().minus(java.time.Duration.ofHours(config.jobStorage.minRetentionHours));
        long remaining=measurement.bytes;
        int deleted=0, eligible=0, protectedJobs=0, batches=0;
        long reclaimed=0L;
        boolean deferred=false, candidatesExhausted=false, batchLimitReached=false, tailFinished=false;
        String resumeFinished=storageScanAfterFinished, resumeId=storageScanAfterId;
        String afterFinished=resumeFinished, afterId=resumeId;
        boolean wrapped=false;
        while(remaining>config.jobStorage.cleanupTargetBytes && batches<storageCandidateBatchLimit) {
            List<Map<String,Object>> page=store.storageCandidates(afterFinished, afterId);
            if(page.isEmpty()) {
                if(!wrapped && afterFinished!=null) { wrapped=true; afterFinished=null; afterId=null; storageScanCoveredHead=true; continue; }
                candidatesExhausted=true; break;
            }
            batches++;
            int deletedAtStart=deleted;
            for(Map<String,Object> candidate:page) {
                String id=String.valueOf(candidate.get("jobId"));
                afterFinished=String.valueOf(candidate.get("finishedAt")); afterId=id;
                if(remaining<=config.jobStorage.cleanupTargetBytes) break;
                if(storageProtected(candidate, cutoff, id)) { protectedJobs++; continue; }
                eligible++;
                if(!artifactLifecycle.writeLock().tryLock()) { deferred=true; break; }
                try {
                    if(storageProtected(candidate, cutoff, id) || jobs.containsKey(id) || pendingArtifactJobs.contains(id) || store.workerMayBeAlive(id)) { protectedJobs++; eligible--; continue; }
                    Path directory=jobsRoot.resolve(id);
                    if(Files.isSymbolicLink(directory)) { protectedJobs++; eligible--; continue; }
                    Long measured=measurement.jobBytes.get(id);
                    long bytes=measured!=null?measured:JobDirectoryUsage.directoryBytes(directory);
                    if(!store.markDeleting(id, cutoff, bytes<0?null:bytes)) { protectedJobs++; eligible--; continue; }
                    if(bytes<0 || !JobDirectoryUsage.deleteJobDirectory(directory)) { java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Job "+id+" was marked for storage deletion and will be reconciled"); continue; }
                    store.deleteJobWithAudit(id,"system:job-retention","JOB_STORAGE_EVICT",deletedOutcome(bytes));
                    deleted++; reclaimed=saturatedAdd(reclaimed, Math.max(0L, bytes)); remaining=Math.max(0L, remaining-Math.max(0L, bytes));
                } finally { artifactLifecycle.writeLock().unlock(); }
            }
            if(deferred) break;
            if(deleted==deletedAtStart && page.size()<store.storageCandidatePage) {
                if(!wrapped && resumeFinished!=null) { wrapped=true; afterFinished=null; afterId=null; storageScanCoveredHead=true; continue; }
                candidatesExhausted=true; break;
            }
        }
        if(!deferred && !candidatesExhausted && batches>=storageCandidateBatchLimit && remaining>config.jobStorage.cleanupTargetBytes) {
            tailFinished=store.storageCandidates(afterFinished, afterId).isEmpty();
            if(tailFinished && storageScanCoveredHead) candidatesExhausted=true;
            else batchLimitReached=true;
        }
        if(!deferred && remaining>config.jobStorage.cleanupTargetBytes) {
            OrphanSweep orphans=deleteEligibleOrphans(jobsRoot, measurement, remaining);
            if(orphans.deferred) deferred=true;
            deleted+=orphans.deleted; reclaimed=saturatedAdd(reclaimed, orphans.reclaimed); remaining=orphans.remaining;
        }
        boolean stillOver=!deferred && remaining>config.jobStorage.cleanupTargetBytes;
        if(deferred) { storageScanAfterFinished=resumeFinished; storageScanAfterId=resumeId; }
        else if(stillOver && batchLimitReached && !tailFinished && afterFinished!=null && afterId!=null) { storageScanAfterFinished=afterFinished; storageScanAfterId=afterId; }
        else { storageScanAfterFinished=null; storageScanAfterId=null; storageScanCoveredHead=false; }
        boolean quotaUnmet=stillOver && !batchLimitReached;
        String state=deferred?"deferred":stillOver && batchLimitReached?"partial":quotaUnmet?"quota_unmet":"ok";
        publishStorageReport(state, measurement.complete?remaining:null, measurement.complete, eligible, protectedJobs, deleted, reclaimed, quotaUnmet);
        if(quotaUnmet) store.audit("system:job-retention","JOB_STORAGE_EVICT",null,null,"QUOTA_UNMET");
        else if(stillOver && batchLimitReached) store.audit("system:job-retention","JOB_STORAGE_EVICT",null,null,"SWEEP_PARTIAL");
    }
    private void restoreStorageCursor() {
        if(storageCursorRestored) return;
        storageCursorRestored=true;
        if(storageScanAfterFinished!=null || storageScanAfterId!=null) return;
        try {
            Path status=storageStatusPath();
            if(!Files.isRegularFile(status) || Files.size(status)>65536) return;
            JsonNode node=JSON.readTree(status.toFile());
            if(node==null || !node.isObject()) return;
            String finished=node.path("candidateAfterFinishedAt").asText("");
            String id=node.path("candidateAfterJobId").asText("");
            if(finished.isBlank() || id.isBlank()) return;
            storageScanAfterFinished=finished; storageScanAfterId=id;
            storageScanCoveredHead=node.path("candidateCycleCoveredHead").asBoolean(false);
        } catch(Exception ignored) { }
    }
    private void invalidateStorageCursorIfStale() throws Exception {
        if(storageScanAfterFinished==null && storageScanAfterId==null) return;
        if(storageScanAfterFinished==null || storageScanAfterId==null || storageScanAfterFinished.isBlank() || storageScanAfterId.isBlank()) {
            storageScanAfterFinished=null; storageScanAfterId=null; return;
        }
        Map<String,Object> anchor=store.get(storageScanAfterId);
        if(anchor==null) return;
        Object finished=anchor.get("finishedAt");
        if(!(finished instanceof String text) || !text.equals(storageScanAfterFinished)) { storageScanAfterFinished=null; storageScanAfterId=null; }
    }
    private boolean storageProtected(Map<String,Object> candidate, Instant cutoff, String id) {
        if(Boolean.TRUE.equals(candidate.get("pinned")) || Boolean.TRUE.equals(candidate.get("artifactRecovery")) || Boolean.TRUE.equals(candidate.get("quarantined"))) return true;
        if(jobs.containsKey(id) || pendingArtifactJobs.contains(id)) return true;
        Object finished=candidate.get("finishedAt");
        if(!(finished instanceof String text) || text.compareTo(cutoff.toString())>0) return true;
        return false;
    }
    private OrphanSweep deleteEligibleOrphans(Path jobsRoot, JobDirectoryUsage.Measurement measurement, long remaining) throws Exception {
        OrphanSweep sweep=new OrphanSweep(remaining);
        if(!Files.isDirectory(jobsRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return sweep;
        Instant staleBefore=clock.instant().minus(java.time.Duration.ofMinutes(15));
        try(var children=Files.newDirectoryStream(jobsRoot)) {
            for(Path child:children) {
                if(remaining<=config.jobStorage.cleanupTargetBytes) break;
                Path name=child.getFileName();
                if(name==null || !JOB_ID.matcher(name.toString()).matches()) continue;
                String id=name.toString();
                if(jobs.containsKey(id) || store.jobRowExists(id) || Files.isSymbolicLink(child)) continue;
                var attributes=Files.readAttributes(child, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if(attributes.isSymbolicLink() || attributes.lastModifiedTime().toInstant().isAfter(staleBefore)) continue;
                long bytes=measurement.jobBytes.getOrDefault(id, -1L);
                if(bytes<0) continue;
                if(!artifactLifecycle.writeLock().tryLock()) { sweep.deferred=true; return sweep; }
                try {
                    if(jobs.containsKey(id) || pendingArtifactJobs.contains(id) || store.jobRowExists(id) || store.workerMayBeAlive(id)) continue;
                    if(!JobDirectoryUsage.deleteJobDirectory(child)) continue;
                    sweep.deleted++; sweep.reclaimed=saturatedAdd(sweep.reclaimed, bytes); sweep.remaining=Math.max(0L, sweep.remaining-bytes); remaining=sweep.remaining;
                    store.auditStorageEviction(id, deletedOutcome(bytes));
                } finally { artifactLifecycle.writeLock().unlock(); }
            }
        }
        return sweep;
    }
    boolean reconcileDeletingJobs() throws Exception {
        boolean complete=true;
        for(String id:store.deletingJobIds()) {
            if(jobs.containsKey(id) || pendingArtifactJobs.contains(id)) {complete=false;continue;}
            if(!artifactLifecycle.writeLock().tryLock()) {complete=false;continue;}
            try {
                if(jobs.containsKey(id) || pendingArtifactJobs.contains(id) || store.workerMayBeAlive(id)) {complete=false;continue;}
                Path directory=config.dataDir.resolve("jobs").resolve(id);
                Long recorded=store.retentionBytes(id);
                long bytes=recorded!=null?recorded:JobDirectoryUsage.directoryBytes(directory);
                if(!JobDirectoryUsage.deleteJobDirectory(directory)) {complete=false;continue;}
                String actor=store.deletionActor(id);
                if(actor==null)store.deleteJobWithAudit(id,"system:job-retention","JOB_STORAGE_EVICT",deletedOutcome(bytes));
                else store.deleteJobWithAudit(id,actor,"JOB_DELETE","DELETED_AFTER_RECOVERY");
            } catch(Exception failure) {
                complete=false;
                java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to reconcile deleting ATT Server job "+id+": "+safeMessage(failure));
            } finally { artifactLifecycle.writeLock().unlock(); }
        }
        return complete;
    }
    private void reconcileDeletingJobsSafely(){try{reconcileDeletingJobs();}catch(Exception e){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to reconcile deleting ATT Server jobs: "+safeMessage(e));}}
    private void cleanupJobStorageSafely(){try{cleanupJobStorage();}catch(Exception e){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to apply ATT Server job storage limits: "+safeMessage(e));}}
    private void publishStorageReport(String state,Long totalBytes,Boolean complete,Integer eligible,Integer protectedJobs,int deletedJobs,long reclaimedBytes,boolean quotaUnmet) {
        Map<String,Object> report=storageSnapshot(state,totalBytes,complete,eligible,protectedJobs,clock.instant().toString(),deletedJobs,reclaimedBytes,quotaUnmet);
        jobStorageReport=report;
        try { writeStorageStatus(report); } catch(Exception failure) { java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("Unable to persist job storage status"); }
    }
    private Map<String,Object> jobStorageSnapshot() {
        if(!config.jobStorage.enabled) return storageSnapshot("disabled",null,null,null,null,null,null,null,false);
        Map<String,Object> current=jobStorageReport;
        if(current!=null) return current;
        Map<String,Object> loaded=readStorageStatus();
        if(loaded!=null) { jobStorageReport=loaded; return loaded; }
        return storageSnapshot("unknown",null,null,null,null,null,null,null,false);
    }
    private Map<String,Object> storageSnapshot(String state,Long totalBytes,Boolean complete,Integer eligible,Integer protectedJobs,String lastSweepAt,Integer deletedJobs,Long reclaimedBytes,boolean quotaUnmet) {
        Map<String,Object> report=new LinkedHashMap<>();
        ServerConfig.JobStorage quota=config.jobStorage;
        report.put("enabled",quota.enabled);
        report.put("maxTotalBytes",quota.enabled?quota.maxTotalBytes:null);
        report.put("cleanupTargetBytes",quota.enabled?quota.cleanupTargetBytes:null);
        report.put("minRetentionHours",quota.minRetentionHours);
        report.put("cleanupIntervalMinutes",quota.cleanupIntervalMinutes);
        report.put("maxSingleJobBytes",null);
        report.put("state",state);
        report.put("totalBytes",totalBytes);
        report.put("measurementComplete",complete);
        report.put("eligibleJobs",eligible);
        report.put("protectedJobs",protectedJobs);
        report.put("lastSweepAt",lastSweepAt);
        report.put("deletedJobs",deletedJobs);
        report.put("reclaimedBytes",reclaimedBytes);
        report.put("quotaUnmet",quotaUnmet);
        return report;
    }
    private Path storageStatusPath() { return config.dataDir.resolve("job-storage-status.json"); }
    private void writeStorageStatus(Map<String,Object> report) throws Exception {
        Path target=storageStatusPath();
        Files.createDirectories(target.getParent());
        Path temporary=Files.createTempFile(target.getParent(),".job-storage-status-",".tmp");
        try {
            Map<String,Object> persisted=new LinkedHashMap<>(report);
            if(storageScanAfterFinished!=null && storageScanAfterId!=null) {
                persisted.put("candidateAfterFinishedAt",storageScanAfterFinished);
                persisted.put("candidateAfterJobId",storageScanAfterId);
                persisted.put("candidateCycleCoveredHead",storageScanCoveredHead);
            }
            Files.write(temporary,JSON.writeValueAsBytes(persisted));
            try(var channel=java.nio.channels.FileChannel.open(temporary,java.nio.file.StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(temporary,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private Map<String,Object> readStorageStatus() {
        try {
            Path status=storageStatusPath();
            if(!Files.isRegularFile(status) || Files.size(status)>65536) return null;
            JsonNode node=JSON.readTree(status.toFile());
            if(node==null || !node.isObject() || !"ok".equals(node.path("state").asText()) && !"quota_unmet".equals(node.path("state").asText()) && !"partial".equals(node.path("state").asText()) && !"measurement_failed".equals(node.path("state").asText()) && !"deferred".equals(node.path("state").asText()) && !"unknown".equals(node.path("state").asText())) return null;
            Map<String,Object> loaded=JSON.convertValue(node,Map.class);
            loaded.remove("candidateAfterFinishedAt");
            loaded.remove("candidateAfterJobId");
            loaded.remove("candidateCycleCoveredHead");
            loaded.put("enabled",true);
            loaded.put("maxTotalBytes",config.jobStorage.maxTotalBytes);
            loaded.put("cleanupTargetBytes",config.jobStorage.cleanupTargetBytes);
            loaded.put("minRetentionHours",config.jobStorage.minRetentionHours);
            loaded.put("cleanupIntervalMinutes",config.jobStorage.cleanupIntervalMinutes);
            loaded.put("maxSingleJobBytes",null);
            if(loaded.get("totalBytes") instanceof Number number && number.longValue()<0) loaded.put("totalBytes",null);
            return loaded;
        } catch(Exception invalid) { return null; }
    }
    private static long saturatedAdd(long left,long right) { return right>0 && left>Long.MAX_VALUE-right ? Long.MAX_VALUE : left+Math.max(0L,right); }
    private static String deletedOutcome(long bytes) {
        if(bytes<0) return "DELETED";
        String outcome="DELETED:"+bytes;
        return outcome.length()<=32?outcome:"DELETED";
    }
    private static final class OrphanSweep {
        int deleted; long reclaimed; long remaining; boolean deferred;
        OrphanSweep(long remaining){this.remaining=remaining;}
    }
    @Override public synchronized void close() {
        admissionLifecycle.writeLock().lock();
        try {if(closing)return;closing=true;}finally{admissionLifecycle.writeLock().unlock();}
        if(inspectionWarmup!=null)inspectionWarmup.shutdownNow();
        manifestPublisher.close();
        if(executionWorkers!=null)executionWorkers.close();
        if(loadScheduleScheduler!=null)loadScheduleScheduler.close();
        retention.shutdownNow();artifactMaintenance.shutdownNow();targetIndexMaintenance.shutdownNow();inspectionWatchdogs.shutdownNow();
        synchronized(draftLock){runDrafts.clear();debugDrafts.clear();quickLoadDrafts.clear();advancedLoadDrafts.clear();catalogLoadDrafts.clear();scheduleExecutionReviews.clear();replayReviews.clear();}
        inspectors.shutdownNow();workers.shutdownNow();streams.shutdownNow();
        for(InspectionWorker worker:inspectionWorkerPool)worker.close();
        for(Job j:jobs.values())if(j.process!=null) {
            try {if(j.processFence!=null){j.processFence.observe();rememberWorkerProcesses(j,j.processFence);}stopWorkerTree(j);finishQuietly(j,"ERROR",143,"ATT-SERVER-INTERRUPTED","Server shutdown interrupted the Worker");}
            catch(Exception ignored){if(j.processFence!=null)j.processFence.force();else forceTree(j.process);}
        }
        for(Process process:inspectionProcesses.keySet())if(process.isAlive())forceTree(process);
        terminateExecutor(workers,"workers");terminateExecutor(inspectors,"inspectors");terminateExecutor(streams,"SSE streams");
        terminateExecutor(targetIndexMaintenance,"target indexing");terminateExecutor(retention,"retention");terminateExecutor(artifactMaintenance,"artifact maintenance");terminateExecutor(inspectionWatchdogs,"inspection watchdogs");
        if(inspectionWarmup!=null)terminateExecutor(inspectionWarmup,"inspection warmup");
        for(Job job:jobs.values())if(!job.terminal()) {
            FutureTask<Void> task=tasks.remove(job.id);if(task!=null)task.cancel(false);
            finishQuietly(job,"ERROR",143,"ATT-SERVER-INTERRUPTED","Server shutdown stopped the queued Worker");
            AdmissionLease lease=leases.remove(job.id);if(lease!=null)lease.release();
            jobs.remove(job.id,job);scheduleArtifactArchive(job.id);
        }
        long stderrDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        for(var entry:stderrDrainers.entrySet()) {
            try {entry.getValue().getErrorStream().close();long remaining=stderrDeadline-System.nanoTime();if(remaining>0)entry.getKey().join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}catch(Exception ignored){}
        }
        // Worker/SSE producers are quiescent before artifact writers and journal compactions drain.
        artifactWriters.shutdown();terminateExecutor(artifactWriters,"artifact writers");
        eventNotifications.shutdown();terminateExecutor(eventNotifications,"event notifications");
        eventCompactions.shutdown();terminateExecutor(eventCompactions,"event compactions");
        synchronized(terminalEventCacheLock){terminalEventCache.clear();terminalEventCacheWeight=0L;}
        try {store.close();}catch(Exception failed){java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).log(java.util.logging.Level.WARNING,"ATT database cleanup failed",failed);}
    }
    static void terminateExecutor(java.util.concurrent.ExecutorService executor,String name) {
        boolean interrupted=false;
        try {
            if(!executor.awaitTermination(30,TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if(!executor.awaitTermination(5,TimeUnit.SECONDS))java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("ATT executor did not terminate: "+name);
            }
        } catch(InterruptedException stopped) {
            interrupted=true;executor.shutdownNow();
            try {if(!executor.awaitTermination(5,TimeUnit.SECONDS))java.util.logging.Logger.getLogger(ServerRuntime.class.getName()).warning("ATT executor still active after interruption: "+name);}
            catch(InterruptedException again){interrupted=true;}
        } finally {if(interrupted)Thread.currentThread().interrupt();}
    }

    private static void terminateTree(Process process){process.toHandle().descendants().forEach(ProcessHandle::destroy);process.destroy();}
    private static void forceTree(Process process){process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
    @FunctionalInterface interface WorkerProcessLauncher { Process start(ProcessBuilder builder) throws java.io.IOException; }
    @FunctionalInterface interface ArtifactPublisher { Path publish(Path output,Path archive,ArtifactArchive.EntryValidator validator) throws Exception; }
    @FunctionalInterface interface LoadProgressPersistence { void update(String jobId,String progressJson) throws Exception; }
    private static final class AdmissionLease {
        private final Semaphore admission,load;private final java.util.concurrent.atomic.AtomicBoolean released=new java.util.concurrent.atomic.AtomicBoolean();
        AdmissionLease(Semaphore admission,Semaphore load){this.admission=admission;this.load=load;}
        void release(){if(released.compareAndSet(false,true)){admission.release();if(load!=null)load.release();}}
    }
    static final class QueueFullException extends RuntimeException {QueueFullException(String m){super(m);}}
    private static final class JobProvenance {
        final String scheduleId,scheduleOwner,executionPrincipal,submittedBy,triggeredBy;
        JobProvenance(String scheduleId,String scheduleOwner,String executionPrincipal,String submittedBy,String triggeredBy) {
            this.scheduleId=provenanceText(scheduleId,40);this.scheduleOwner=provenanceText(scheduleOwner,256);
            this.executionPrincipal=provenanceText(executionPrincipal,256);this.submittedBy=provenanceText(submittedBy,256);this.triggeredBy=provenanceText(triggeredBy,256);
        }
        private static String provenanceText(String value,int max) {
            if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(Character::isISOControl))return null;
            return value;
        }
    }
    static final class JobConflictException extends RuntimeException {final String code;JobConflictException(String code,String message){super(message);this.code=code;}}
    static final class NotFoundException extends RuntimeException {}
    static final class ReplayDraftExpiredException extends RuntimeException {}
    static final class ReplayInputMissingException extends IllegalArgumentException {ReplayInputMissingException(String message){super(message);}}
    static final class ScheduleSourceChangedException extends IllegalArgumentException {
        final String storedRevision,currentRevision,policy;
        ScheduleSourceChangedException(String stored,String current,String policy){super("package-revision-changed");this.storedRevision=stored;this.currentRevision=current;this.policy=policy;}
    }
    static final class ScheduleConflictException extends RuntimeException {}
    static final class SavedScenarioConflictException extends RuntimeException {}
    static final class InspectionCapacityException extends RuntimeException {}
    static final class InspectionLimitException extends RuntimeException {
        final List<Map<String,Object>> diagnostics;
        InspectionLimitException(List<Map<String,Object>> diagnostics) {
            this.diagnostics=Collections.unmodifiableList(new ArrayList<Map<String,Object>>(diagnostics));
        }
    }
    static final class InspectionConfigurationException extends RuntimeException {
        final List<Map<String,Object>> diagnostics;
        InspectionConfigurationException(List<Map<String,Object>> diagnostics){this.diagnostics=Collections.unmodifiableList(new ArrayList<>(diagnostics));}
        String summary(){Object summary=diagnostics.get(0).get("summary");return summary instanceof String?(String)summary:"Package configuration is invalid or incomplete.";}
    }
    static final class InspectionValidationException extends RuntimeException {
        final String code;
        final List<Map<String,Object>> diagnostics;
        InspectionValidationException(String code,List<Map<String,Object>> diagnostics) {
            super(diagnostics.isEmpty()?"Package inspection input or configuration is invalid.":String.valueOf(diagnostics.get(0).get("summary")));
            this.code=code;
            this.diagnostics=Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }
        String summary(){return getMessage();}
    }
    static final class InspectionTimeoutException extends RuntimeException {}
    static final class InspectionResponseTooLargeException extends RuntimeException {}
    static final class StaleCursorException extends RuntimeException {}
    static final class StaleDraftException extends RuntimeException {}
    static final class DraftCapacityException extends RuntimeException {}
    static final class InlineLoadDisabledException extends RuntimeException {}
    static final class ArtifactCapacityException extends RuntimeException {}
    static final class ArchiveUnavailableException extends RuntimeException {
        final String archiveState;
        ArchiveUnavailableException(String archiveState) {
            this.archiveState=Set.of("PENDING","ARCHIVING","READY","FAILED").contains(archiveState)?archiveState:"FAILED";
        }
    }
    static final class InlineLoadLimitException extends IllegalArgumentException {
        InlineLoadLimitException(String message){super(message);}
    }
    static final class DraftValidationException extends IllegalArgumentException {
        final List<Map<String,Object>> diagnostics;
        DraftValidationException(List<Map<String,Object>> diagnostics) {
            super(firstDiagnosticSummary(diagnostics));
            List<Map<String,Object>> copy=new ArrayList<Map<String,Object>>();
            for(Map<String,Object> diagnostic:diagnostics)copy.add(Collections.unmodifiableMap(new LinkedHashMap<String,Object>(diagnostic)));
            this.diagnostics=Collections.unmodifiableList(copy);
        }
        private static String firstDiagnosticSummary(List<Map<String,Object>> diagnostics) {
            Object summary=diagnostics==null||diagnostics.isEmpty()?null:diagnostics.get(0).get("summary");
            return summary instanceof String&&!((String)summary).isBlank()?(String)summary:"Load or Debug input failed schema or target validation";
        }
    }
    private static final class DebugDraft {
        final String id,principal,packageId,environment,targetType,targetId,resourceId,revisionDigest;
        final Map<String,Object> input,scheduleInput,preview;
        final boolean containsSensitiveValues;
        final Instant expiresAt;
        boolean submitting;
        DebugDraft(String id,String principal,String packageId,String environment,String targetType,String targetId,
                   String resourceId,String revisionDigest,Map<String,Object> input,Map<String,Object> scheduleInput,
                   boolean containsSensitiveValues,Map<String,Object> preview,Instant expiresAt) {
            this.id=id;this.principal=principal;this.packageId=packageId;this.environment=environment;
            this.targetType=targetType;this.targetId=targetId;this.resourceId=resourceId;this.revisionDigest=revisionDigest;
            this.input=deepMap(input);this.scheduleInput=deepMap(scheduleInput);this.containsSensitiveValues=containsSensitiveValues;
            this.preview=deepMap(preview);this.expiresAt=expiresAt;
        }
    }
    private static final class QuickLoadDraft {
        final String id,principal,packageId,environment,targetType,targetId,resourceId,model,revisionDigest;
        final Map<String,Object> scenario,preview;
        final boolean containsSensitiveValues;
        final Instant expiresAt;
        boolean submitting;
        QuickLoadDraft(String id,String principal,String packageId,String environment,String targetType,String targetId,
                       String resourceId,String model,String revisionDigest,Map<String,Object> scenario,
                       boolean containsSensitiveValues,Map<String,Object> preview,Instant expiresAt) {
            this.id=id;this.principal=principal;this.packageId=packageId;this.environment=environment;
            this.targetType=targetType;this.targetId=targetId;this.resourceId=resourceId;this.model=model;
            this.revisionDigest=revisionDigest;this.scenario=deepMap(scenario);this.containsSensitiveValues=containsSensitiveValues;
            this.preview=deepMap(preview);this.expiresAt=expiresAt;
        }
    }
    private static final class AdvancedLoadDraftState {
        final String id,principal,packageId,environment,model,revisionDigest;
        final Map<String,Object> scenario,preview;
        final boolean containsSensitiveValues,containsSensitiveAuthoredValues,templateComposition;
        final Map<String,Object> authored;
        final Instant expiresAt;
        boolean submitting;
        String savedScenarioId,savedScenarioOwner;
        long savedScenarioRevision;
        AdvancedLoadDraftState(String id,String principal,String packageId,String environment,String model,
                               String revisionDigest,Map<String,Object> scenario,boolean containsSensitiveValues,Map<String,Object> preview,
                               Instant expiresAt,Map<String,Object> authored,boolean templateComposition,boolean containsSensitiveAuthoredValues) {
            this.id=id;this.principal=principal;this.packageId=packageId;this.environment=environment;
            this.model=model;this.revisionDigest=revisionDigest;this.scenario=deepMap(scenario);
            this.containsSensitiveValues=containsSensitiveValues;this.preview=deepMap(preview);this.expiresAt=expiresAt;
            this.authored=deepMap(authored);this.templateComposition=templateComposition;this.containsSensitiveAuthoredValues=containsSensitiveAuthoredValues;
        }
    }
    private static final class CatalogLoadDraftState {
        final String id,principal,packageId,environment,resourceId,revisionDigest,model,previewYaml;
        final Map<String,Object> scenario,configuration;
        final Instant expiresAt;
        CatalogLoadDraftState(String id,String principal,String packageId,String environment,String resourceId,
                              String revisionDigest,String model,Map<String,Object> scenario,String previewYaml,Map<String,Object> configuration,Instant expiresAt) {
            this.id=id;this.principal=principal;this.packageId=packageId;this.environment=environment;
            this.resourceId=resourceId;this.revisionDigest=revisionDigest;this.model=model;
            this.scenario=deepMap(scenario);this.previewYaml=previewYaml;this.configuration=deepMap(configuration);this.expiresAt=expiresAt;
        }
    }
    private static Map<String,Object> deepMap(Map<String,Object> source) {
        Map<String,Object> copy=new LinkedHashMap<>();
        if(source!=null)for(Map.Entry<String,Object> entry:source.entrySet())copy.put(entry.getKey(),deepValue(entry.getValue()));
        return Collections.unmodifiableMap(copy);
    }
    private static Object deepValue(Object value) {
        if(value instanceof Map) {
            Map<String,Object> copy=new LinkedHashMap<>();
            for(Map.Entry<?,?> entry:((Map<?,?>)value).entrySet())copy.put(String.valueOf(entry.getKey()),deepValue(entry.getValue()));
            return Collections.unmodifiableMap(copy);
        }
        if(value instanceof List) {
            List<Object> copy=new ArrayList<>();for(Object item:(List<?>)value)copy.add(deepValue(item));return Collections.unmodifiableList(copy);
        }
        return value;
    }
    private static final class Cursor {final int offset;final String revision;Cursor(int offset,String revision){this.offset=offset;this.revision=revision;}}
}
