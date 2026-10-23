package att.server;

import att.server.api.internal.WorkerProtocol;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded unused JVMs. A successful lease permanently transfers process ownership. */
final class ExecutionWorkerPool implements AutoCloseable {
    @FunctionalInterface interface Builders { ProcessBuilder create(String packageId) throws Exception; }
    @FunctionalInterface interface Starter { Process start(ProcessBuilder builder) throws Exception; }
    @FunctionalInterface interface Fingerprint { String current() throws Exception; }
    static final class Lease {
        final Process process;
        final WorkerProcessFence fence;
        final long startedNanos,spawnedNanos,readyNanos;
        final Path marker;
        Lease(Idle idle) {
            process=idle.process;fence=idle.fence;marker=idle.marker;
            startedNanos=idle.startedNanos;spawnedNanos=idle.spawnedNanos;readyNanos=idle.readyNanos;
        }
        void transferred() throws Exception { Files.deleteIfExists(marker); }
    }
    private static final class Idle {
        final String packageId,fingerprint;
        final Path root,marker;
        final long startedNanos=System.nanoTime();
        volatile Process process;
        volatile WorkerProcessFence fence;
        volatile long spawnedNanos,readyNanos;
        int persistedIdentities;
        boolean retired;
        Idle(String id,String fingerprint,Path root,Path marker) {
            packageId=id;this.fingerprint=fingerprint;this.root=root;this.marker=marker;
        }
    }
    private record RecoveryProcess(ProcessHandle handle,Instant start) {
        boolean alive() {
            if(!handle.isAlive())return false;
            Instant actual=handle.info().startInstant().orElseThrow(()->new IllegalStateException("Prewarm recovery identity unavailable"));
            return start.equals(actual);
        }
    }
    private final ServerConfig config;
    private final Builders builders;
    private final Starter starter;
    private final Fingerprint fingerprint;
    private final String schemaDigest;
    private final Path registry;
    private final List<Idle> owned=new ArrayList<>();
    private final ScheduledExecutorService maintenance,watchdogs;
    private final AtomicLong starts=new AtomicLong(),leases=new AtomicLong(),fallbacks=new AtomicLong(),failures=new AtomicLong();
    private boolean closed;
    private int nextPackage,consecutiveFailures;
    private long retryAfter;

    ExecutionWorkerPool(ServerConfig config,Builders builders,Starter starter,Fingerprint fingerprint,String digest) throws Exception {
        this.config=config;this.builders=builders;this.starter=starter;this.fingerprint=fingerprint;this.schemaDigest=digest;
        registry=config.dataDir.resolve("prewarm");Files.createDirectories(registry);
        if(Files.isSymbolicLink(registry)||!registry.toRealPath().equals(registry))throw new IllegalArgumentException("Invalid private prewarm registry");
        recoverIdleProcesses();
        maintenance=Executors.newSingleThreadScheduledExecutor(r->thread(r,"att-server-execution-prewarm"));
        watchdogs=Executors.newSingleThreadScheduledExecutor(r->thread(r,"att-server-prewarm-watchdog"));
        if(config.executionPool.enabled)maintenance.scheduleWithFixedDelay(this::maintain,0,250,TimeUnit.MILLISECONDS);
    }
    private static Thread thread(Runnable task,String name) {
        Thread result=new Thread(task,name);result.setDaemon(true);
        result.setContextClassLoader(ClassLoader.getPlatformClassLoader());return result;
    }

    synchronized Lease lease(String packageId) {
        if(closed||!config.executionPool.enabled)return null;
        String current;
        try { current=fingerprint.current(); }
        catch(Exception unavailable) { fallbacks.incrementAndGet();return null; }
        for(Iterator<Idle> iterator=owned.iterator();iterator.hasNext();) {
            Idle idle=iterator.next();
            if(idle.retired||idle.readyNanos==0||!idle.packageId.equals(packageId))continue;
            idle.fence.observe();
            if(!idle.fence.verified()||idle.fence.identities().size()!=1||!idle.process.isAlive()||!idle.fingerprint.equals(current)
                    ||System.nanoTime()-idle.readyNanos>TimeUnit.MILLISECONDS.toNanos(config.executionPool.idleTimeoutMs)) {
                idle.retired=true;continue;
            }
            try { if(!idle.root.equals(config.packages.get(packageId).toRealPath())) {idle.retired=true;continue;} }
            catch(Exception changed) {idle.retired=true;continue;}
            iterator.remove();leases.incrementAndGet();return new Lease(idle);
        }
        fallbacks.incrementAndGet();return null;
    }

    private void maintain() {
        Idle create=null;
        try {
            String current=fingerprint.current();
            synchronized(this) {
                if(closed)return;
                for(Iterator<Idle> it=owned.iterator();it.hasNext();) {
                    Idle idle=it.next();
                    if(idle.fence!=null)idle.fence.observe();
                    if(idle.process!=null&&(!idle.fence.verified()||idle.fence.identities().size()!=1||!idle.process.isAlive()||!idle.fingerprint.equals(current)
                            ||(idle.readyNanos!=0&&System.nanoTime()-idle.readyNanos>TimeUnit.MILLISECONDS.toNanos(config.executionPool.idleTimeoutMs)))) idle.retired=true;
                    if(idle.retired&&stop(idle))it.remove();
                }
                if(owned.size()>=config.executionPool.minIdle||owned.size()>=config.executionPool.maxIdle||System.nanoTime()<retryAfter)return;
                String id=config.executionPool.packageIds.get(nextPackage++%config.executionPool.packageIds.size());
                create=new Idle(id,current,config.packages.get(id).toRealPath(),registry.resolve(UUID.randomUUID()+".json"));
                owned.add(create);
            }
            start(create);
        } catch(Exception failed) {
            failures.incrementAndGet();
            synchronized(this) {
                if(create!=null)create.retired=true;
                consecutiveFailures=Math.min(8,consecutiveFailures+1);
                retryAfter=System.nanoTime()+TimeUnit.SECONDS.toNanos(1L<<consecutiveFailures);
            }
        }
    }

    private void start(Idle idle) throws Exception {
        ProcessBuilder builder=builders.create(idle.packageId);
        // Native platform bootstrap variables only. All application environment
        // values are sent on stdin after assignment, never retained by idle JVMs.
        Map<String,String> bootstrap=new HashMap<>();
        for(String key:List.of("SystemRoot","WINDIR","TEMP","TMP","TMPDIR","LANG","LC_ALL"))
            if(builder.environment().containsKey(key))bootstrap.put(key,builder.environment().get(key));
        builder.environment().clear();builder.environment().putAll(bootstrap);
        builder.directory(idle.root.toFile());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        synchronized(this) {
            if(closed){idle.retired=true;return;}
            idle.process=starter.start(builder);idle.spawnedNanos=System.nanoTime();
            idle.fence=new WorkerProcessFence(idle.process);starts.incrementAndGet();
            persist(idle);
        }
        ScheduledFuture<?> watchdog=watchdogs.schedule(()->{
            idle.fence.force();
            try { idle.process.getInputStream().close(); }catch(Exception ignored) { }
        },config.executionPool.startupTimeoutMs,TimeUnit.MILLISECONDS);
        try {
            WorkerProtocol.Frame frame=WorkerProtocol.read(idle.process.getInputStream(),8192);
            if(frame==null||frame.oversized())throw new IllegalArgumentException("Prewarm READY missing");
            var ready=ServerRuntime.JSON.readTree(frame.text());
            if(!"READY".equals(ready.path("type").asText())||!"att-prewarm/v1".equals(ready.path("protocolVersion").asText())
                    ||!ServerBuildInfo.PRODUCT.equals(ready.path("productVersion").asText())
                    ||(!"unknown".equals(ServerBuildInfo.GIT_COMMIT)&&!ServerBuildInfo.GIT_COMMIT.equals(ready.path("gitCommit").asText()))
                    ||(schemaDigest!=null&&!schemaDigest.equals(ready.path("schemaRegistryDigest").asText()))
                    ||!idle.root.toString().equals(ready.path("cwd").asText()))
                throw new IllegalArgumentException("Prewarm READY identity mismatch");
            synchronized(this) {
                idle.fence.observe();
                if(!idle.fence.verified()||idle.fence.identities().size()!=1)throw new IllegalStateException("Idle Worker containment was not proven");
                if(closed||!idle.process.isAlive()||!idle.fingerprint.equals(fingerprint.current())) {idle.retired=true;return;}
                idle.readyNanos=System.nanoTime();consecutiveFailures=0;retryAfter=0;
            }
        } finally { watchdog.cancel(false); }
    }

    private void persist(Idle idle) throws Exception {
        List<Map<String,Object>> processes=new ArrayList<>();
        for(var identity:idle.fence.identities()) {
            if(identity.start()==null)throw new IllegalStateException("Idle Worker identity unavailable");
            processes.add(Map.of("pid",identity.pid(),"start",identity.start().toString()));
        }
        if(processes.isEmpty()) {
            Instant start=idle.process.info().startInstant().orElseThrow(()->new IllegalStateException("Idle Worker identity unavailable"));
            processes.add(Map.of("pid",idle.process.pid(),"start",start.toString()));
        }
        byte[] bytes=ServerRuntime.JSON.writeValueAsBytes(Map.of("version",1,"processes",processes));
        Path pending=Files.createTempFile(registry,".prewarm-",".pending");
        try {
            Files.write(pending,bytes,StandardOpenOption.TRUNCATE_EXISTING);
            try(var channel=java.nio.channels.FileChannel.open(pending,StandardOpenOption.WRITE)){channel.force(true);}
            Files.move(pending,idle.marker,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            idle.persistedIdentities=processes.size();
        } finally {Files.deleteIfExists(pending);}
    }

    /** Stop previously unassigned Workers even when the feature is now disabled. */
    private void recoverIdleProcesses() throws Exception {
        try(var files=Files.list(registry)) {
            List<Path> markers=files.limit(4097).toList();
            if(markers.size()>4096)throw new IllegalStateException("Prewarm recovery inventory exceeds its limit");
            for(Path marker:markers) {
                if(Files.isSymbolicLink(marker)||Files.size(marker)>65536)throw new IllegalStateException("Invalid prewarm recovery marker");
                var data=ServerRuntime.JSON.readTree(Files.readAllBytes(marker));
                if(data.path("version").asInt()!=1||!data.path("processes").isArray()||data.path("processes").size()>1024)
                    throw new IllegalStateException("Invalid prewarm recovery identity");
                Map<Long,RecoveryProcess> survivors=new LinkedHashMap<>();
                // Capture every known identity before signalling the parent: after
                // its exit a surviving child may have been reparented already.
                for(var row:data.path("processes")) {
                    long pid=row.path("pid").asLong();Instant start=Instant.parse(row.path("start").asText());
                    ProcessHandle handle=ProcessHandle.of(pid).orElse(null);
                    if(handle!=null&&handle.isAlive()) {
                        Instant actual=handle.info().startInstant().orElseThrow(()->new IllegalStateException("Prewarm recovery identity unavailable"));
                        if(!start.equals(actual))continue; // Recycled PID is never ours to signal.
                        survivors.put(pid,new RecoveryProcess(handle,start));
                        try(var descendants=handle.descendants()) {
                            var iterator=descendants.iterator();
                            while(iterator.hasNext()) {
                                ProcessHandle child=iterator.next();
                                if(!child.isAlive())continue;
                                Instant childStart=child.info().startInstant().orElseThrow(()->new IllegalStateException("Prewarm recovery descendant identity unavailable"));
                                survivors.put(child.pid(),new RecoveryProcess(child,childStart));
                                if(survivors.size()>1024)throw new IllegalStateException("Prewarm recovery process inventory exceeds its limit");
                            }
                        }
                    }
                }
                List<RecoveryProcess> targets=new ArrayList<>(survivors.values());
                Collections.reverse(targets);
                for(var target:targets)if(target.alive())target.handle().destroyForcibly();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                for(var target:targets) {
                    while(target.alive()&&System.nanoTime()<deadline)Thread.sleep(10);
                    if(target.alive())throw new IllegalStateException("Prewarm recovery cleanup is uncertain");
                }
                Files.delete(marker);
            }
        }
    }

    private boolean stop(Idle idle) {
        if(idle.process==null)return true;
        idle.fence.observe();
        boolean recorded=true;
        if(idle.fence.identities().size()>idle.persistedIdentities)
            try {persist(idle);}catch(Exception uncertain){recorded=false;}
        idle.fence.force();
        try {idle.process.getOutputStream().close();idle.process.getInputStream().close();}catch(Exception ignored) { }
        if(!recorded||!idle.fence.stopped())return false;
        try {Files.deleteIfExists(idle.marker);return true;}catch(Exception failed){return false;}
    }

    synchronized Map<String,Object> metrics() {
        long ready=owned.stream().filter(idle->idle.readyNanos!=0&&!idle.retired).count();
        Map<String,Object> metrics=new LinkedHashMap<>(Map.of("enabled",config.executionPool.enabled,"owned",owned.size(),"ready",ready,
                "starts",starts.get(),"leases",leases.get(),"coldFallbacks",fallbacks.get(),"startupFailures",failures.get(),
                "maxIdle",config.executionPool.maxIdle,"idleHeapCapMb",(long)config.executionPool.maxIdle*config.workerHeapMaxMb));
        long cpu=0;boolean available=true;
        for(Idle idle:owned)if(idle.process!=null) {
            var duration=idle.process.info().totalCpuDuration();
            if(duration.isPresent())cpu+=duration.get().toNanos();else available=false;
        }
        metrics.put("idleCpuMetricsAvailable",available);
        if(available)metrics.put("idleCpuMs",cpu/1_000_000.0);
        return metrics;
    }

    @Override public void close() {
        synchronized(this) {if(closed)return;closed=true;for(Idle idle:owned)idle.retired=true;}
        maintenance.shutdownNow();watchdogs.shutdownNow();
        synchronized(this) {for(Idle idle:owned)stop(idle);}
        ServerRuntime.terminateExecutor(maintenance,"execution prewarm");ServerRuntime.terminateExecutor(watchdogs,"prewarm watchdog");
        synchronized(this) {
            for(Idle idle:owned) {
                if(idle.fence!=null)try {idle.fence.stop(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();idle.fence.force();}
                stop(idle);
            }
        }
    }

    /** Metadata identity is refreshed on maintenance and immediately before each lease. */
    static String fingerprint(Path libraries,List<Path> additional) throws Exception {
        List<Path> dirs=new ArrayList<>();dirs.add(libraries);dirs.addAll(additional);
        StringBuilder value=new StringBuilder();
        for(Path dir:dirs) {
            try(var paths=Files.list(dir)) {
                List<Path> jars=paths.filter(p->p.getFileName().toString().endsWith(".jar")).sorted().limit(4097).toList();
                if(jars.size()>4096)throw new IllegalArgumentException("Prewarm runtime inventory exceeds limits");
                for(Path jar:jars) {
                    BasicFileAttributes a=Files.readAttributes(jar,BasicFileAttributes.class);
                    if(!a.isRegularFile())throw new IllegalArgumentException("Invalid prewarm runtime entry");
                    value.append(jar.toRealPath()).append(':').append(a.size()).append(':').append(a.lastModifiedTime()).append(':').append(a.fileKey()).append('\n');
                }
            }
        }
        return value.toString();
    }
}
