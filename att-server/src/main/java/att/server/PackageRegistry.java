package att.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Trusted, versioned Package Registry backed only by the selected Server Config file. */
final class PackageRegistry implements AutoCloseable {
    static final String CONTRACT = "att-package-registry/v1";
    static final String SCOPE_CONTRACT = "att-source-scope/v1";
    static final long POLL_MILLIS = 250;
    static final long DEBOUNCE_MILLIS = 750;
    static final long MAX_OBSERVATION_MILLIS = 5000;
    static final int MAX_OBSERVATIONS = 20;
    static final int MAX_CONFIG_BYTES = 1024 * 1024;

    interface Coordinator { void apply(Runnable atomicInstall); }
    interface ChangeListener { void beforeRegistryChange(Snapshot previous,Snapshot next); }

    record Diagnostic(String code,String summary) { }

    static final class Snapshot {
        final long generation;
        final String revision,status;
        final Instant loadedAt,lastAttemptAt,lastReloadAt;
        final Diagnostic diagnostic;
        final Map<String,Path> packages;
        final List<Path> allowedRoots;
        final Map<String,String> packageStates;

        private Snapshot(long generation,String revision,String status,Instant loadedAt,Instant lastAttemptAt,
                         Instant lastReloadAt,Diagnostic diagnostic,Map<String,Path> packages,
                         List<Path> allowedRoots,Map<String,String> packageStates) {
            this.generation=generation;this.revision=revision;this.status=status;this.loadedAt=loadedAt;
            this.lastAttemptAt=lastAttemptAt;this.lastReloadAt=lastReloadAt;this.diagnostic=diagnostic;
            this.packages=Collections.unmodifiableMap(new LinkedHashMap<>(packages));
            this.allowedRoots=List.copyOf(allowedRoots);
            this.packageStates=Collections.unmodifiableMap(new LinkedHashMap<>(packageStates));
        }

        Map<String,Object> view() {
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("schemaVersion",CONTRACT);result.put("revision",revision);result.put("generation",generation);
            result.put("status",status);result.put("loadedAt",loadedAt.toString());
            if(lastAttemptAt!=null)result.put("lastAttemptAt",lastAttemptAt.toString());
            if(lastReloadAt!=null)result.put("lastReloadAt",lastReloadAt.toString());
            if(diagnostic!=null)result.put("diagnostic",Map.of("code",diagnostic.code(),"summary",diagnostic.summary()));
            List<Map<String,Object>> entries=new ArrayList<>();
            packages.keySet().stream().sorted().forEach(id->entries.add(Map.of("packageId",id,
                    "scopeId","package:"+id,"scopeContract",SCOPE_CONTRACT,
                    "state",packageStates.getOrDefault(id,"Registered"))));
            result.put("packages",List.copyOf(entries));
            return result;
        }
    }

    private record Observation(byte[] bytes,String digest,Object fileKey,long size,long modifiedMillis) { }
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ServerConfig config;
    private final Path sourceFile;
    private final Clock clock;
    private final Duration debounce,maximumObservation;
    private final Coordinator coordinator;
    private final ChangeListener changeListener;
    private final String deploymentPolicyFingerprint;
    private final AtomicReference<Snapshot> active;
    private ScheduledExecutorService watcher;
    private String lastEvaluatedDigest,pendingDigest;
    private Instant pendingSince,firstObservedAt,unstableSince;
    private int observations;
    private boolean unstableReported;

    PackageRegistry(ServerConfig config,Clock clock) throws Exception {
        this(config,clock,Duration.ofMillis(DEBOUNCE_MILLIS),Duration.ofMillis(MAX_OBSERVATION_MILLIS),
                Runnable::run,(previous,next)->{ });
    }

    PackageRegistry(ServerConfig config,Clock clock,Duration debounce,Duration maximumObservation,
                    Coordinator coordinator,ChangeListener changeListener) throws Exception {
        this.config=java.util.Objects.requireNonNull(config);this.clock=java.util.Objects.requireNonNull(clock);
        this.sourceFile=java.util.Objects.requireNonNull(config.sourceFile).toAbsolutePath().normalize();
        this.debounce=java.util.Objects.requireNonNull(debounce);this.maximumObservation=java.util.Objects.requireNonNull(maximumObservation);
        this.coordinator=java.util.Objects.requireNonNull(coordinator);this.changeListener=java.util.Objects.requireNonNull(changeListener);
        Observation initial=readObservation();
        Object parsed=parseYaml(initial.bytes());
        Map<?,?> root=requireMap(parsed,"ATT Server configuration must be a YAML mapping");
        this.deploymentPolicyFingerprint=fingerprint(withoutPackages(root));
        lastEvaluatedDigest=initial.digest();
        String revision=revision(config.packages,config.allowedRoots);
        Map<String,String> states=new LinkedHashMap<>();config.packages.keySet().stream().sorted().forEach(id->states.put(id,"Registered"));
        Instant now=clock.instant();
        active=new AtomicReference<>(new Snapshot(1,revision,"Ready",now,now,now,null,
                config.packages,config.allowedRoots,states));
    }

    Snapshot snapshot() { return active.get(); }

    void start() {
        if(watcher!=null)return;
        watcher=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"att-server-package-registry");thread.setDaemon(true);return thread;});
        watcher.scheduleWithFixedDelay(this::pollSafely,POLL_MILLIS,POLL_MILLIS,TimeUnit.MILLISECONDS);
    }

    /** One file observation; exposed package-locally so reload behavior has deterministic tests. */
    synchronized void pollNow() {
        Instant now=clock.instant();
        Observation observation;
        try {
            observation=readObservation();
            unstableSince=null;unstableReported=false;
        } catch(UnstableConfigurationException changing) {
            if(unstableSince==null)unstableSince=now;
            if(Duration.between(unstableSince,now).compareTo(maximumObservation)>=0&&!unstableReported) {
                recordInvalid(now,"ATT-SOURCE-REGISTRY-UNSTABLE","The selected Server Config did not remain stable within the observation limit.");
                unstableReported=true;
            }
            return;
        } catch(Exception unavailable) {
            lastEvaluatedDigest=null;pendingDigest=null;pendingSince=null;firstObservedAt=null;observations=0;
            recordInvalid(now,"ATT-SOURCE-REGISTRY-UNAVAILABLE","The selected Server Config is unavailable, unreadable, or unsafe.");
            return;
        }

        String digest=observation.digest();
        if(digest.equals(lastEvaluatedDigest)) {
            pendingDigest=null;pendingSince=null;firstObservedAt=null;observations=0;
            return;
        }
        if(!digest.equals(pendingDigest)) {
            if(pendingDigest==null) {firstObservedAt=now;observations=1;}
            else observations++;
            pendingDigest=digest;pendingSince=now;
        }
        if(firstObservedAt!=null&&(Duration.between(firstObservedAt,now).compareTo(maximumObservation)>=0
                ||observations>MAX_OBSERVATIONS)&&!unstableReported) {
            lastEvaluatedDigest=digest;pendingDigest=null;pendingSince=null;firstObservedAt=null;observations=0;
            recordInvalid(now,"ATT-SOURCE-REGISTRY-UNSTABLE","The selected Server Config exceeded the bounded observation limit.");
            unstableReported=true;
            return;
        }
        if(pendingSince==null||Duration.between(pendingSince,now).compareTo(debounce)<0)return;

        try {
            Map<?,?> document=requireMap(parseYaml(observation.bytes()),"ATT Server configuration must be a YAML mapping");
            if(!deploymentPolicyFingerprint.equals(fingerprint(withoutPackages(document)))) {
                lastEvaluatedDigest=digest;pendingDigest=null;pendingSince=null;firstObservedAt=null;observations=0;
                recordInvalid(now,"ATT-DEPLOYMENT-POLICY-RESTART-REQUIRED",
                        "Deployment settings outside packages changed; restart is required before registry changes can apply.");
                return;
            }
            Object rawPackages=document.get("packages");
            ServerConfig.PackageRoots roots=ServerConfig.validatePackageRoots(rawPackages,config.dataDir,config.runtimeConfigPath,true);
            install(roots,now);
            lastEvaluatedDigest=digest;pendingDigest=null;pendingSince=null;firstObservedAt=null;observations=0;unstableReported=false;
        } catch(Exception invalid) {
            lastEvaluatedDigest=digest;pendingDigest=null;pendingSince=null;firstObservedAt=null;observations=0;
            recordInvalid(now,"ATT-SOURCE-REGISTRY-INVALID","The Package Registry candidate is invalid; the last-known-good registry remains active.");
        }
    }

    void markWarming(String packageId,String expectedRevision) { updatePackageState(packageId,expectedRevision,"Warming"); }
    void markReady(String packageId,String expectedRevision) { updatePackageState(packageId,expectedRevision,"Ready"); }
    void markRegistered(String packageId,String expectedRevision) { updatePackageState(packageId,expectedRevision,"Registered"); }

    private void updatePackageState(String packageId,String expectedRevision,String state) {
        while(true) {
            Snapshot old=active.get();
            if(!old.revision.equals(expectedRevision)||!old.packages.containsKey(packageId)
                    ||state.equals(old.packageStates.get(packageId)))return;
            Map<String,String> states=new LinkedHashMap<>(old.packageStates);states.put(packageId,state);
            Snapshot updated=new Snapshot(old.generation,old.revision,old.status,old.loadedAt,old.lastAttemptAt,
                    old.lastReloadAt,old.diagnostic,old.packages,old.allowedRoots,states);
            if(active.compareAndSet(old,updated))return;
        }
    }

    private void install(ServerConfig.PackageRoots roots,Instant now) {
        String revision=revision(roots.packages(),roots.allowedRoots());
        coordinator.apply(()->{
            Snapshot current=active.get();
            long generation=current.generation+(revision.equals(current.revision)?0:1);
            Map<String,String> states=new LinkedHashMap<>();
            roots.packages().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{
                String state=entry.getValue().equals(current.packages.get(entry.getKey()))
                        ?current.packageStates.getOrDefault(entry.getKey(),"Registered"):"Registered";
                states.put(entry.getKey(),state);
            });
            Snapshot next=new Snapshot(generation,revision,"Ready",now,now,now,null,
                    roots.packages(),roots.allowedRoots(),states);
            if(!current.revision.equals(next.revision))changeListener.beforeRegistryChange(current,next);
            active.set(next);
        });
    }

    private void recordInvalid(Instant now,String code,String summary) {
        while(true) {
            Snapshot old=active.get();
            if("Invalid".equals(old.status)&&old.diagnostic!=null&&old.diagnostic.code().equals(code))return;
            Snapshot invalid=new Snapshot(old.generation,old.revision,"Invalid",old.loadedAt,now,old.lastReloadAt,
                    new Diagnostic(code,summary),old.packages,old.allowedRoots,old.packageStates);
            if(active.compareAndSet(old,invalid))return;
        }
    }

    private void pollSafely() {
        try { pollNow(); }
        catch(Throwable failure) {
            if(watcher!=null&&!watcher.isShutdown())recordInvalid(clock.instant(),"ATT-SOURCE-REGISTRY-INVALID",
                    "The Package Registry candidate is invalid; the last-known-good registry remains active.");
        }
    }

    private Observation readObservation() throws Exception {
        if(Files.isSymbolicLink(sourceFile))throw new java.io.IOException("unsafe config file");
        BasicFileAttributes before=Files.readAttributes(sourceFile,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.size()>MAX_CONFIG_BYTES||!Files.isReadable(sourceFile))
            throw new java.io.IOException("config file unavailable");
        checkFileReadPermissions(sourceFile);
        byte[] bytes;
        try(InputStream input=Files.newInputStream(sourceFile)) { bytes=input.readNBytes(MAX_CONFIG_BYTES+1); }
        if(bytes.length>MAX_CONFIG_BYTES)throw new java.io.IOException("config file too large");
        BasicFileAttributes after=Files.readAttributes(sourceFile,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!after.isRegularFile()||!sameFileObservation(before,after))throw new UnstableConfigurationException();
        return new Observation(bytes,digest(bytes),after.fileKey(),after.size(),after.lastModifiedTime().toMillis());
    }

    private static boolean sameFileObservation(BasicFileAttributes left,BasicFileAttributes right) {
        return left.size()==right.size()&&left.lastModifiedTime().equals(right.lastModifiedTime())
                &&java.util.Objects.equals(left.fileKey(),right.fileKey());
    }

    private static void checkFileReadPermissions(Path file) throws Exception {
        try {
            Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS);
            if(!(permissions.contains(PosixFilePermission.OWNER_READ)||permissions.contains(PosixFilePermission.GROUP_READ)
                    ||permissions.contains(PosixFilePermission.OTHERS_READ)))throw new java.io.IOException("config file permissions");
        } catch(UnsupportedOperationException ignored) { }
    }

    private static Object parseYaml(byte[] bytes) {
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(MAX_CONFIG_BYTES);
        try { return new Yaml(new SafeConstructor(options)).load(new ByteArrayInputStream(bytes)); }
        catch(Exception invalid) { throw new IllegalArgumentException("invalid YAML"); }
    }

    private static Map<?,?> requireMap(Object value,String message) {
        if(!(value instanceof Map<?,?> map))throw new IllegalArgumentException(message);
        for(Object key:map.keySet())if(!(key instanceof String))throw new IllegalArgumentException(message);
        return map;
    }

    private static Map<String,Object> withoutPackages(Map<?,?> source) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(Map.Entry<?,?> entry:source.entrySet())if(!"packages".equals(entry.getKey()))result.put((String)entry.getKey(),entry.getValue());
        return result;
    }

    private static String fingerprint(Object value) throws Exception {
        return digest(JSON.writeValueAsBytes(canonical(value)));
    }

    private static Object canonical(Object value) {
        if(value instanceof Map<?,?> map) {
            TreeMap<String,Object> sorted=new TreeMap<>();
            for(Map.Entry<?,?> entry:map.entrySet()) {
                if(!(entry.getKey() instanceof String key))throw new IllegalArgumentException("config mapping keys must be strings");
                sorted.put(key,canonical(entry.getValue()));
            }
            return sorted;
        }
        if(value instanceof List<?> list) {
            List<Object> copy=new ArrayList<>(list.size());for(Object item:list)copy.add(canonical(item));return copy;
        }
        if(value instanceof java.util.Date date)return date.toInstant().toString();
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean)return value;
        throw new IllegalArgumentException("unsupported config value");
    }

    private static String revision(Map<String,Path> packages,List<Path> allowedRoots) {
        Map<String,Object> identity=new LinkedHashMap<>();
        Map<String,String> entries=new TreeMap<>();packages.forEach((id,path)->entries.put(id,path.toString()));
        List<String> roots=allowedRoots.stream().map(Path::toString).sorted(Comparator.naturalOrder()).toList();
        identity.put("allowedRoots",roots);identity.put("entries",entries);
        try { return fingerprint(identity); }
        catch(Exception failure) { throw new IllegalStateException("Unable to compute Package Registry revision",failure); }
    }

    private static String digest(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Override public void close() {
        ScheduledExecutorService activeWatcher=watcher;watcher=null;
        if(activeWatcher!=null) { activeWatcher.shutdownNow();ServerRuntime.terminateExecutor(activeWatcher,"Package Registry watcher"); }
    }

    private static final class UnstableConfigurationException extends Exception { }
}
