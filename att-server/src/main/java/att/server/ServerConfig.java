package att.server;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validated, immutable deployment configuration for the single-node control plane. */
public final class ServerConfig {
    // Must stay aligned with att.load.LoadPreparationBudget in the Worker runtime.
    private static final int LOAD_PREPARATION_PER_WORKER_CONNECTIONS = 64;
    private static final int LOAD_PREPARATION_SERVER_CONNECTIONS = 256;
    private static final int MAX_SERVER_LOAD_WORKERS = LOAD_PREPARATION_SERVER_CONNECTIONS / LOAD_PREPARATION_PER_WORKER_CONNECTIONS;
    public final Path dataDir;
    public final Path runtimeConfigPath;
    public final Path javaExecutable;
    public final int maxConcurrent, queuedLimit, maxConcurrentLoad, gracefulStopMs, jobRetentionDays;
    public final int workerHeapInitialMb, workerHeapMaxMb;
    public final int maxRequestBytes, maxEventsPerJob, maxArtifacts;
    public final boolean authenticationRequired;
    public final String authMode;
    public final Security security;
    public final Map<String, Path> packages;
    public final List<Path> allowedRoots;
    public final List<Path> workerLibraryDirs;
    public final Inspection inspection;
    public final InlineLoad inlineLoad;
    public final Scheduling scheduling;
    public final Authorization authorization;
    public final JobStorage jobStorage;
    public final LiveOutput liveOutput;
    public final ExecutionPool executionPool;
    final PackageConfigurationPolicy packageConfiguration;

    private ServerConfig(Path dataDir, Path runtimeConfigPath, Path javaExecutable, int maxConcurrent, int queuedLimit,
                         int maxConcurrentLoad, int gracefulStopMs, int jobRetentionDays,
                         int workerHeapInitialMb, int workerHeapMaxMb, int maxRequestBytes,
                         int maxEventsPerJob, int maxArtifacts, boolean authenticationRequired, String authMode, Security security, Map<String, Path> packages, List<Path> allowedRoots, List<Path> workerLibraryDirs, Inspection inspection, InlineLoad inlineLoad, Scheduling scheduling, Authorization authorization, JobStorage jobStorage, LiveOutput liveOutput, ExecutionPool executionPool, PackageConfigurationPolicy packageConfiguration) {
        this.dataDir=dataDir; this.runtimeConfigPath=runtimeConfigPath; this.javaExecutable=javaExecutable; this.maxConcurrent=maxConcurrent;
        this.queuedLimit=queuedLimit; this.maxConcurrentLoad=maxConcurrentLoad; this.gracefulStopMs=gracefulStopMs;this.jobRetentionDays=jobRetentionDays;
        this.workerHeapInitialMb=workerHeapInitialMb;this.workerHeapMaxMb=workerHeapMaxMb;
        this.maxRequestBytes=maxRequestBytes; this.maxEventsPerJob=maxEventsPerJob; this.maxArtifacts=maxArtifacts;this.authenticationRequired=authenticationRequired;this.authMode=authMode;this.security=security;
        this.packages=Collections.unmodifiableMap(new LinkedHashMap<>(packages));
        this.allowedRoots=List.copyOf(allowedRoots);
        this.workerLibraryDirs=List.copyOf(workerLibraryDirs);
        this.inspection=inspection;
        this.inlineLoad=inlineLoad;
        this.scheduling=scheduling;
        this.authorization=authorization;
        this.jobStorage=jobStorage;this.liveOutput=liveOutput;
        this.executionPool=executionPool;this.packageConfiguration=packageConfiguration;
    }

    public static ServerConfig load(Path file) throws Exception {
        if (file == null)
            throw new IllegalStateException("ATT Server configuration was not found; set -Datt.server.config or ATT_SERVER_CONFIG, or provide ATT_HOME/config/att-server.yaml");
        Path configFile=file.toAbsolutePath().normalize();
        if (!Files.exists(configFile))
            throw new IllegalStateException("ATT Server configuration does not exist: "+configFile);
        if (!Files.isRegularFile(configFile))
            throw new IllegalStateException("ATT Server configuration is not a regular file: "+configFile);
        if (!Files.isReadable(configFile))
            throw new IllegalStateException("ATT Server configuration is not readable: "+configFile);
        LoaderOptions options=new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(20);
        Object raw;
        try (InputStream in=Files.newInputStream(configFile)) {
            raw=new Yaml(new SafeConstructor(options)).load(in);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Unable to read or parse ATT Server configuration at "+configFile+": "+invalid.getMessage(), invalid);
        }
        if (!(raw instanceof Map<?,?> root)) throw new IllegalArgumentException("ATT Server configuration must be a YAML mapping");
        Map<?,?> server=map(root.get("server"),"server");
        Map<?,?> workers=map(root.get("workers"),"workers");
        Map<?,?> packagesNode=map(root.get("packages"),"packages");
        Path data=Paths.get(string(server.get("dataDir"),"server.dataDir"));
        if(!data.isAbsolute())throw new IllegalArgumentException("server.dataDir must be an absolute path");
        data=data.toAbsolutePath().normalize();
        Path runtimeConfig=server.get("runtimeConfigPath")==null
                ?runtimeConfigDefault(configFile)
                :Paths.get(string(server.get("runtimeConfigPath"),"server.runtimeConfigPath"));
        if(!runtimeConfig.isAbsolute())throw new IllegalArgumentException("server.runtimeConfigPath must be an absolute path");
        runtimeConfig=runtimeConfig.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(runtimeConfig)||!Files.isRegularFile(runtimeConfig)||!Files.isReadable(runtimeConfig))
            throw new IllegalArgumentException("server.runtimeConfigPath must be a readable regular file: "+runtimeConfig);
        if (Files.exists(data) && !Files.isDirectory(data)) throw new IllegalArgumentException("server.dataDir must be a directory");
        Path java=server.get("javaExecutable")==null?Paths.get(System.getProperty("java.home"),"bin",isWindows()?"java.exe":"java"):
                Paths.get(string(server.get("javaExecutable"),"server.javaExecutable"));
        java=java.toAbsolutePath().normalize();
        if (!Files.isRegularFile(java) || !Files.isExecutable(java)) throw new IllegalArgumentException("server.javaExecutable must be an executable Java runtime");
        List<?> roots=list(packagesNode.get("allowedRoots"),"packages.allowedRoots");
        List<?> libraryDirs=workers.get("libraryDirs")==null?List.of():list(workers.get("libraryDirs"),"workers.libraryDirs");
        Map<?,?> entries=packagesNode.get("entries")==null?Collections.emptyMap():map(packagesNode.get("entries"),"packages.entries");
        java.util.ArrayList<Path> allowed=new java.util.ArrayList<>();
        for(Object r:roots) allowed.add(canonicalRequired(string(r,"packages.allowedRoots entry"),"packages.allowedRoots entry",true));
        if(allowed.isEmpty()) throw new IllegalArgumentException("packages.allowedRoots must contain at least one directory");
        Map<String,Path> resolved=new LinkedHashMap<>();
        for(Map.Entry<?,?> e:entries.entrySet()) {
            String id=string(e.getKey(),"package ID");
            if(!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw new IllegalArgumentException("Invalid package ID: "+id);
            Path packageRoot=canonicalRequired(string(e.getValue(),"packages.entries."+id),"packages.entries."+id,true);
            if(allowed.stream().noneMatch(packageRoot::startsWith)) throw new IllegalArgumentException("Package root is outside packages.allowedRoots: "+id);
            if(packageRoot.startsWith(data)||data.startsWith(packageRoot)) throw new IllegalArgumentException("server.dataDir must be separate from configured package roots");
            Path configDirectory=runtimeConfig.getParent();
            if(runtimeConfig.startsWith(packageRoot)||configDirectory.startsWith(packageRoot)||packageRoot.startsWith(configDirectory))
                throw new IllegalArgumentException("server.runtimeConfigPath and its config directory must be outside registered package roots");
            resolved.put(id,packageRoot);
        }
        if(resolved.isEmpty()) throw new IllegalArgumentException("packages.entries must define at least one package ID");
        java.util.ArrayList<Path> workerLibraries=new java.util.ArrayList<>();
        for(Object entry:libraryDirs){Path library=canonicalRequired(string(entry,"workers.libraryDirs entry"),"workers.libraryDirs entry",true);if(!Files.isReadable(library))throw new IllegalArgumentException("workers.libraryDirs entry must be readable: "+library);workerLibraries.add(library);}
        int max=intValue(workers,"maxConcurrent",8,1,256), queue=intValue(workers,"queuedLimit",100,0,100000),
            load=intValue(workers,"maxConcurrentLoad",2,1,256), stop=intValue(workers,"gracefulStopMs",10000,0,300000);
        if(Math.min(max,load)>MAX_SERVER_LOAD_WORKERS)
            throw new IllegalArgumentException("min(workers.maxConcurrent, workers.maxConcurrentLoad) must not exceed "
                    +MAX_SERVER_LOAD_WORKERS+" for the bounded LOAD preparation budget ("
                    +LOAD_PREPARATION_PER_WORKER_CONNECTIONS+" initial connections per Worker, "
                    +LOAD_PREPARATION_SERVER_CONNECTIONS+" per Server process)");
        int heapMax=intValue(workers,"heapMaxMb",0,64,65536), heapInitial=intValue(workers,"heapInitialMb",0,32,65536);
        if(heapInitial>0&&heapMax==0)throw new IllegalArgumentException("workers.heapInitialMb requires workers.heapMaxMb");
        if(heapInitial>heapMax)throw new IllegalArgumentException("workers.heapInitialMb must not exceed workers.heapMaxMb");
        ExecutionPool executionPool=executionPool(workers.get("executionPool"),resolved,heapMax);
        int retention=intValue(server,"jobRetentionDays",30,1,3650);
        JobStorage jobStorage=jobStorage(server.get("jobStorage"));
        LiveOutput liveOutput=liveOutput(server.get("liveOutput"));
        int request=intValue(server,"maxRequestBytes",1048576,1024,16777216), events=intValue(server,"maxEventsPerJob",10000,100,1000000),
            artifacts=intValue(server,"maxArtifacts",1000,1,100000);boolean authenticationRequired=boolValue(server,"authenticationRequired",true);
        Map<?,?> auth=server.get("auth")==null?Collections.emptyMap():map(server.get("auth"),"server.auth");
        String authMode=auth.get("mode")==null?"container":string(auth.get("mode"),"server.auth.mode").toLowerCase(Locale.ROOT);
        if(!Set.of("container","local").contains(authMode))throw new IllegalArgumentException("server.auth.mode must be container or local");
        if("local".equals(authMode)&&!authenticationRequired)throw new IllegalArgumentException("server.authenticationRequired must be true when server.auth.mode is local");
        Security security=security(server.get("security"));
        Authorization authorization=authorization(server.get("authorization"),authenticationRequired);
        Inspection inspection=inspection(server.get("inspection"),resolved,runtimeConfig,authorization.adminRole);
        InlineLoad inlineLoad=inlineLoad(server.get("inlineLoad"));
        Scheduling scheduling=scheduling(server.get("scheduling"),authenticationRequired,load,inspection.enabled,authorization.adminRole);
        Path candidateData=canonicalFuturePath(data);
        for(Path packageRoot:resolved.values()) if(packageRoot.startsWith(candidateData)||candidateData.startsWith(packageRoot))
            throw new IllegalArgumentException("server.dataDir must be separate from configured package roots");
        Files.createDirectories(data);
        Path realData=data.toRealPath();
        for(Path packageRoot:resolved.values()) if(packageRoot.startsWith(realData)||realData.startsWith(packageRoot))
            throw new IllegalArgumentException("server.dataDir must be separate from configured package roots");
        privateDirectory(realData);privateDirectory(realData.resolve("db"));privateDirectory(realData.resolve("jobs"));
        return new ServerConfig(realData,runtimeConfig,java,max,queue,load,stop,retention,heapInitial,heapMax,request,events,artifacts,authenticationRequired,authMode,security,resolved,allowed,workerLibraries,inspection,inlineLoad,scheduling,authorization,jobStorage,liveOutput,executionPool,PackageConfigurationPolicy.load(server.get("packageConfiguration")));
    }

    public static final class ExecutionPool {
        static final ExecutionPool DISABLED=new ExecutionPool(false,0,0,0,300000,30000,List.of());
        public final boolean enabled;
        public final int minIdle,maxIdle,heapBudgetMb,idleTimeoutMs,startupTimeoutMs;
        public final List<String> packageIds;
        ExecutionPool(boolean enabled,int minIdle,int maxIdle,int budget,int idleMs,int startupMs,List<String> packages) {
            this.enabled=enabled;this.minIdle=minIdle;this.maxIdle=maxIdle;this.heapBudgetMb=budget;
            this.idleTimeoutMs=idleMs;this.startupTimeoutMs=startupMs;this.packageIds=List.copyOf(packages);
        }
    }
    private static ExecutionPool executionPool(Object raw,Map<String,Path> packages,int heapMax) {
        if(raw==null)return ExecutionPool.DISABLED;
        Map<?,?> values=map(raw,"workers.executionPool");
        for(Object key:values.keySet())if(!Set.of("enabled","strategy","minIdle","maxIdle","heapBudgetMb","idleTimeoutMs","startupTimeoutMs","packageIds").contains(key))
            throw new IllegalArgumentException("workers.executionPool contains an unknown setting");
        boolean enabled=boolValue(values,"enabled",false);
        if(values.get("strategy")!=null&&!"prewarmed-one-shot".equals(values.get("strategy")))
            throw new IllegalArgumentException("workers.executionPool.strategy must be prewarmed-one-shot");
        int min=intValue(values,"minIdle",1,0,16),max=intValue(values,"maxIdle",2,1,16);
        int budget=intValue(values,"heapBudgetMb",512,64,65536);
        int idle=intValue(values,"idleTimeoutMs",300000,1000,3600000),startup=intValue(values,"startupTimeoutMs",30000,1000,120000);
        List<String> ids=new java.util.ArrayList<>();
        if(values.get("packageIds")!=null)for(Object value:list(values.get("packageIds"),"workers.executionPool.packageIds")) {
            String id=string(value,"workers.executionPool.packageIds entry");
            if(!packages.containsKey(id)||ids.contains(id))throw new IllegalArgumentException("workers.executionPool.packageIds must contain unique registered package IDs");
            ids.add(id);
        }
        if(min>max)throw new IllegalArgumentException("workers.executionPool.minIdle exceeds maxIdle");
        if(enabled&&(heapMax==0||(long)max*heapMax>budget||ids.isEmpty()))
            throw new IllegalArgumentException("Enabled workers.executionPool requires packageIds, workers.heapMaxMb and enough heapBudgetMb for maxIdle");
        return new ExecutionPool(enabled,min,max,budget,idle,startup,ids);
    }

    public static Path configPath() {
        return configPath(System.getProperty("att.server.config"), System.getenv("ATT_SERVER_CONFIG"),
                System.getProperty("att.home"), System.getenv("ATT_HOME"));
    }

    static Path configPath(String systemProperty, String environmentVariable,
                           String attHomeProperty, String attHomeEnvironment) {
        String configured=firstNonBlank(systemProperty,environmentVariable);
        if(configured!=null)return Paths.get(configured).toAbsolutePath().normalize();
        String attHome=firstNonBlank(attHomeProperty,attHomeEnvironment);
        return attHome==null?null:Paths.get(attHome,"config","att-server.yaml").toAbsolutePath().normalize();
    }

    private static Path runtimeConfigDefault(Path serverConfigFile) {
        String attHome=firstNonBlank(System.getProperty("att.home"),System.getenv("ATT_HOME"));
        if(attHome!=null)return Paths.get(attHome,"config","config.yaml").toAbsolutePath().normalize();
        if(serverConfigFile!=null) {
            Path parent=serverConfigFile.toAbsolutePath().normalize().getParent();
            if(parent!=null&&parent.getFileName()!=null&&"config".equals(parent.getFileName().toString())&&parent.getParent()!=null)
                return parent.getParent().resolve("config/config.yaml");
        }
        return Paths.get("config/config.yaml").toAbsolutePath().normalize();
    }

    static Path runtimeConfigRoot(Path runtimeConfigPath) {
        Path absolute=runtimeConfigPath.toAbsolutePath().normalize();
        Path parent=absolute.getParent();
        for(Path current=parent;current!=null;current=current.getParent())
            if(current.getFileName()!=null&&"config".equals(current.getFileName().toString())&&current.getParent()!=null)
                return current.getParent();
        return parent==null?Paths.get("").toAbsolutePath().normalize():parent;
    }

    private static String firstNonBlank(String first,String second) {
        if(first!=null&&!first.isBlank())return first.trim();
        if(second!=null&&!second.isBlank())return second.trim();
        return null;
    }
    private static boolean isWindows(){ return System.getProperty("os.name","").toLowerCase().contains("win"); }
    private static Path canonicalFuturePath(Path path) throws Exception {
        Path current=path.toAbsolutePath().normalize();
        java.util.ArrayDeque<Path> missing=new java.util.ArrayDeque<>();
        while(current!=null&&!Files.exists(current,java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if(current.getFileName()!=null)missing.addFirst(current.getFileName());
            current=current.getParent();
        }
        if(current==null)return path.toAbsolutePath().normalize();
        Path resolved=current.toRealPath();
        for(Path component:missing)resolved=resolved.resolve(component);
        return resolved.normalize();
    }

    private static Path canonicalRequired(String value,String field,boolean directory) throws Exception {
        Path path=Paths.get(value); if(!path.isAbsolute()) throw new IllegalArgumentException(field+" must be an absolute path");
        if(!Files.exists(path)) throw new IllegalArgumentException(field+" does not exist: "+path);
        Path real=path.toRealPath(); if(directory&&!Files.isDirectory(real)) throw new IllegalArgumentException(field+" must be a directory: "+path); return real;
    }
    private static void privateDirectory(Path path){try{Files.createDirectories(path);try{Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException unsupported){var view=Files.getFileAttributeView(path,java.nio.file.attribute.AclFileAttributeView.class);if(view==null)throw new IllegalStateException("Filesystem does not support private ATT Server data permissions");var owner=Files.getOwner(path);var entry=java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW).setPrincipal(owner).setPermissions(java.util.EnumSet.allOf(java.nio.file.attribute.AclEntryPermission.class)).setFlags(java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT,java.nio.file.attribute.AclEntryFlag.FILE_INHERIT).build();view.setAcl(java.util.List.of(entry));}}catch(Exception e){throw new IllegalStateException("Unable to secure ATT Server data directory",e);}}
    private static boolean boolValue(Map<?,?> map,String key,boolean fallback){Object value=map.get(key);if(value==null)return fallback;if(!(value instanceof Boolean))throw new IllegalArgumentException(key+" must be true or false");return (Boolean)value;}
    private static int intValue(Map<?,?> map,String key,int fallback,int min,int max) {
        Object v=map.get(key); if(v==null)return fallback; if(!(v instanceof Number n)) throw new IllegalArgumentException(key+" must be an integer");
        int value=n.intValue(); if(value<min||value>max) throw new IllegalArgumentException(key+" must be between "+min+" and "+max); return value;
    }
    private static String string(Object v,String field){ if(!(v instanceof String s)||s.isBlank()) throw new IllegalArgumentException(field+" is required"); return s.trim(); }
    private static Map<?,?> map(Object v,String field){ if(!(v instanceof Map<?,?> m)) throw new IllegalArgumentException(field+" must be a mapping"); return m; }
    private static List<?> list(Object v,String field){ if(!(v instanceof List<?> l)) throw new IllegalArgumentException(field+" must be a list"); return l; }

    private static Inspection inspection(Object raw,Map<String,Path> packages,Path runtimeConfigPath,String systemAdminRole) throws Exception {
        Map<?,?> values=raw==null?Collections.emptyMap():map(raw,"server.inspection");
        Set<String> allowed=Set.of("enabled","maxConcurrent","queuedLimit","timeoutMs","heapMaxMb","maxResponseBytes","maxSourceBytes","safeTextSources","sourceBrowsing","redaction","configurationAdminRole","warmup");
        for(Object key:values.keySet())if(!(key instanceof String)||!allowed.contains(key))throw new IllegalArgumentException("server.inspection contains an unknown setting");
        boolean enabled=boolValue(values,"enabled",true);
        String configurationAdminRole=values.get("configurationAdminRole")==null?systemAdminRole:string(values.get("configurationAdminRole"),"server.inspection.configurationAdminRole");
        if(!configurationAdminRole.matches("[A-Za-z][A-Za-z0-9._-]{0,63}"))throw new IllegalArgumentException("server.inspection.configurationAdminRole is invalid");
        if(!configurationAdminRole.equals(systemAdminRole))throw new IllegalArgumentException("server.inspection.configurationAdminRole must match server.authorization.adminRole");
        int concurrent=intValue(values,"maxConcurrent",2,1,16);
        int queued=intValue(values,"queuedLimit",16,0,1024);
        int timeout=intValue(values,"timeoutMs",30000,100,300000);
        int heap=intValue(values,"heapMaxMb",512,64,4096);
        int response=intValue(values,"maxResponseBytes",262144,1024,1048576);
        int source=intValue(values,"maxSourceBytes",65536,256,262144);
        Map<?,?> sourceBrowsing=values.get("sourceBrowsing")==null?Collections.emptyMap():map(values.get("sourceBrowsing"),"server.inspection.sourceBrowsing");
        for(Object key:sourceBrowsing.keySet())if(!(key instanceof String)||!Set.of("enabled","policy","denyGlobs","maxSourceBytes","externalSourceRoots","externalTextSources").contains(key))throw new IllegalArgumentException("server.inspection.sourceBrowsing contains an unknown setting");
        boolean sourceBrowsingEnabled=boolValue(sourceBrowsing,"enabled",true);
        String sourcePolicy=sourceBrowsing.get("policy")==null?"safe-package-files":string(sourceBrowsing.get("policy"),"server.inspection.sourceBrowsing.policy");
        if(!Set.of("safe-package-files","allowlist-only").contains(sourcePolicy))throw new IllegalArgumentException("server.inspection.sourceBrowsing.policy must be safe-package-files or allowlist-only");
        source=intValue(sourceBrowsing,"maxSourceBytes",source,256,262144);
        if(source>=response)throw new IllegalArgumentException("server.inspection.maxSourceBytes must be less than maxResponseBytes");
        List<String> denyGlobs=checkedGlobs(sourceBrowsing.get("denyGlobs"),"server.inspection.sourceBrowsing.denyGlobs");
        List<Path> externalRoots=new java.util.ArrayList<>();
        if(sourceBrowsing.get("externalSourceRoots")!=null) {
            List<?> configuredRoots=list(sourceBrowsing.get("externalSourceRoots"),"server.inspection.sourceBrowsing.externalSourceRoots");
            if(configuredRoots.size()>100)throw new IllegalArgumentException("server.inspection.sourceBrowsing.externalSourceRoots may contain at most 100 paths");
            java.util.HashSet<Path> uniqueRoots=new java.util.HashSet<>();
            for(Object entry:configuredRoots) {
                Path root=canonicalRequired(string(entry,"server.inspection.sourceBrowsing.externalSourceRoots entry"),"server.inspection.sourceBrowsing.externalSourceRoots entry",true);
                if(!Files.isReadable(root))throw new IllegalArgumentException("External source root must be readable");
                if(!uniqueRoots.add(root))throw new IllegalArgumentException("Duplicate external source root");
                externalRoots.add(root);
            }
        }
        Map<String,List<String>> externalSources=new LinkedHashMap<>();
        Map<?,?> rawExternalSources=sourceBrowsing.get("externalTextSources")==null?Collections.emptyMap():map(sourceBrowsing.get("externalTextSources"),"server.inspection.sourceBrowsing.externalTextSources");
        for(Map.Entry<?,?> entry:rawExternalSources.entrySet()) {
            String packageId=string(entry.getKey(),"server.inspection.sourceBrowsing.externalTextSources package ID");
            if(!packages.containsKey(packageId))throw new IllegalArgumentException("External inspection sources reference an unknown package: "+packageId);
            List<?> paths=list(entry.getValue(),"server.inspection.sourceBrowsing.externalTextSources."+packageId);
            if(paths.size()>100)throw new IllegalArgumentException("An external source allowlist may contain at most 100 files per package");
            java.util.ArrayList<String> checked=new java.util.ArrayList<>();
            java.util.HashSet<Path> unique=new java.util.HashSet<>();
            for(Object value:paths) {
                String authored=string(value,"server.inspection.sourceBrowsing.externalTextSources entry");
                Path configured=Paths.get(authored);
                if(!configured.isAbsolute()||!configured.normalize().equals(configured))
                    throw new IllegalArgumentException("External inspection sources must be normalized absolute file paths");
                Path canonical=configured.toRealPath();
                if(!canonical.equals(configured))throw new IllegalArgumentException("External inspection source paths must be canonical");
                if(!Files.isRegularFile(canonical,java.nio.file.LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(canonical))
                    throw new IllegalArgumentException("External inspection sources must be readable regular files");
                if(externalRoots.stream().noneMatch(canonical::startsWith))
                    throw new IllegalArgumentException("External inspection source is outside configured externalSourceRoots");
                if(packages.values().stream().anyMatch(canonical::startsWith))
                    throw new IllegalArgumentException("External inspection source must be outside every registered package root");
                String lower=canonical.getFileName().toString().toLowerCase(Locale.ROOT);
                if(!(lower.endsWith(".sh")||lower.endsWith(".bash")||lower.endsWith(".py")||lower.endsWith(".js")||lower.endsWith(".ts")||lower.endsWith(".groovy")||lower.endsWith(".rb")||lower.endsWith(".pl")||lower.endsWith(".ps1")||lower.endsWith(".bat")||lower.endsWith(".cmd")))
                    throw new IllegalArgumentException("External inspection sources must use a supported script extension");
                if(!unique.add(canonical))throw new IllegalArgumentException("Duplicate external inspection source");
                checked.add(canonical.toString());
            }
            externalSources.put(packageId,List.copyOf(checked));
        }
        Map<?,?> redaction=values.get("redaction")==null?Collections.emptyMap():map(values.get("redaction"),"server.inspection.redaction");
        for(Object key:redaction.keySet())if(!(key instanceof String)||!Set.of("enabled","sensitivePaths","sensitiveFieldPatterns").contains(key))throw new IllegalArgumentException("server.inspection.redaction contains an unknown setting");
        boolean redactionEnabled=boolValue(redaction,"enabled",false);
        List<String> sensitivePaths=checkedSensitivePaths(redaction.get("sensitivePaths"));
        List<String> sensitiveFieldPatterns=checkedRegexes(redaction.get("sensitiveFieldPatterns"));
        Map<?,?> rawSources=values.get("safeTextSources")==null?Collections.emptyMap():map(values.get("safeTextSources"),"server.inspection.safeTextSources");
        Path sharedConfigRoot=runtimeConfigRoot(runtimeConfigPath).resolve("config").normalize();
        if(Files.isDirectory(sharedConfigRoot))sharedConfigRoot=sharedConfigRoot.toRealPath();
        else sharedConfigRoot=null;
        Map<String,List<String>> safeSources=new LinkedHashMap<>();
        for(Map.Entry<?,?> entry:rawSources.entrySet()) {
            String packageId=string(entry.getKey(),"server.inspection.safeTextSources package ID");
            Path packageRoot=packages.get(packageId);
            if(packageRoot==null)throw new IllegalArgumentException("server.inspection.safeTextSources references an unknown package: "+packageId);
            List<?> paths=list(entry.getValue(),"server.inspection.safeTextSources."+packageId);
            java.util.ArrayList<String> checked=new java.util.ArrayList<>();
            java.util.HashSet<String> unique=new java.util.HashSet<>();
            for(Object value:paths) {
                String authored=string(value,"server.inspection.safeTextSources entry");
                Path relative=Paths.get(authored);
                if(relative.isAbsolute()||authored.indexOf('\\')>=0||authored.indexOf('\0')>=0||!relative.normalize().equals(relative)||relative.startsWith(".."))
                    throw new IllegalArgumentException("Inspection source paths must be normalized package-relative or shared-config-relative paths");
                String logical=relative.toString().replace('\\','/');
                String lower=logical.toLowerCase(java.util.Locale.ROOT);
                if(!(lower.endsWith(".sh")||lower.endsWith(".bash")||lower.endsWith(".py")||lower.endsWith(".js")||lower.endsWith(".ts")||lower.endsWith(".sql")||lower.endsWith(".groovy")||lower.endsWith(".rb")||lower.endsWith(".pl")||lower.endsWith(".ps1")||lower.endsWith(".bat")||lower.endsWith(".cmd")||lower.endsWith(".txt")||lower.endsWith(".yaml")||lower.endsWith(".yml")||lower.endsWith(".json")||lower.endsWith(".xml")))
                    throw new IllegalArgumentException("Inspection source allowlist accepts supported UTF-8 text files only (scripts, YAML, JSON, XML, SQL, or TXT)");
                boolean shared=logical.startsWith("config/");
                Path allowedRoot=shared?sharedConfigRoot:packageRoot;
                if(allowedRoot==null)throw new IllegalArgumentException("Inspection source must be an existing regular file inside its package or shared runtime configuration");
                Path candidate=(shared?sharedConfigRoot.getParent():packageRoot).resolve(relative).normalize();
                if(!candidate.startsWith(allowedRoot)||!Files.isRegularFile(candidate))
                    throw new IllegalArgumentException("Inspection source must be an existing regular file inside its package or shared runtime configuration");
                if(!candidate.toRealPath().startsWith(allowedRoot))
                    throw new IllegalArgumentException(shared?"Inspection source resolves outside the shared runtime configuration":"Inspection source resolves outside its package");
                if(!unique.add(logical))throw new IllegalArgumentException("Duplicate inspection source path: "+logical);
                checked.add(logical);
            }
            safeSources.put(packageId,List.copyOf(checked));
        }
        Map<?,?> warm=values.get("warmup")==null?Collections.emptyMap():map(values.get("warmup"),"server.inspection.warmup");
        for(Object key:warm.keySet())if(!Set.of("enabled","workers","keys").contains(key))throw new IllegalArgumentException("server.inspection.warmup contains an unknown setting");
        int warmWorkers=intValue(warm,"workers",1,1,concurrent);
        List<WarmupKey> warmKeys=new java.util.ArrayList<>();
        if(warm.get("keys")!=null)for(Object item:list(warm.get("keys"),"server.inspection.warmup.keys")) {
            Map<?,?> key=map(item,"warmup key");
            for(Object name:key.keySet())if(!Set.of("packageId","environment").contains(name))throw new IllegalArgumentException("Unknown warmup key setting");
            String packageId=string(key.get("packageId"),"warmup packageId");
            if(!packageId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))throw new IllegalArgumentException("Invalid warmup packageId");
            String environment=key.get("environment")==null?null:string(key.get("environment"),"warmup environment");
            if(environment!=null&&!environment.matches("[A-Za-z][A-Za-z0-9_-]{0,31}"))throw new IllegalArgumentException("Invalid warmup environment");
            WarmupKey selected=new WarmupKey(packageId,environment);
            if(warmKeys.contains(selected))throw new IllegalArgumentException("Duplicate warmup key");
            warmKeys.add(selected);
            if(warmKeys.size()>2*warmWorkers)throw new IllegalArgumentException("Warmup keys exceed two inspector entries per warmup Worker");
        }
        Warmup warmup=new Warmup(boolValue(warm,"enabled",false),warmWorkers,List.copyOf(warmKeys));
        return new Inspection(enabled,configurationAdminRole,concurrent,queued,timeout,heap,response,source,safeSources,externalRoots,externalSources,
                sourceBrowsingEnabled,sourcePolicy,denyGlobs,redactionEnabled,sensitivePaths,sensitiveFieldPatterns,warmup);
    }

    private static Security security(Object raw) {
        Map<?,?> values=raw==null?Collections.emptyMap():map(raw,"server.security");
        for(Object key:values.keySet())if(!(key instanceof String)||!Set.of("checkCrossOrigin","trustedOrigins").contains(key))throw new IllegalArgumentException("server.security contains an unknown setting");
        java.util.ArrayList<String> origins=new java.util.ArrayList<>();
        if(values.get("trustedOrigins")!=null)for(Object value:list(values.get("trustedOrigins"),"server.security.trustedOrigins"))
            origins.add(string(value,"server.security.trustedOrigins entry"));
        return new Security(boolValue(values,"checkCrossOrigin",false),origins);
    }

    private static List<String> checkedGlobs(Object raw,String field) {
        if(raw==null)return List.of();List<?> entries=list(raw,field);if(entries.size()>100)throw new IllegalArgumentException(field+" may contain at most 100 patterns");
        java.util.ArrayList<String> result=new java.util.ArrayList<>();java.util.HashSet<String> unique=new java.util.HashSet<>();
        for(Object item:entries){String value=string(item,field+" entry");Path path=Paths.get(value);
            if(value.indexOf('\\')>=0||value.indexOf('\0')>=0||path.isAbsolute()||path.startsWith("..")||value.contains("../")||value.equals("..")||value.length()>256)
                throw new IllegalArgumentException(field+" entries must be safe package-relative globs");
            if(!unique.add(value))throw new IllegalArgumentException(field+" contains a duplicate pattern");result.add(value);
        }
        return List.copyOf(result);
    }
    private static List<String> checkedSensitivePaths(Object raw) {
        if(raw==null)return List.of();List<?> entries=list(raw,"server.inspection.redaction.sensitivePaths");if(entries.size()>100)throw new IllegalArgumentException("sensitivePaths may contain at most 100 entries");
        java.util.ArrayList<String> result=new java.util.ArrayList<>();java.util.HashSet<String> unique=new java.util.HashSet<>();
        for(Object item:entries){String value=string(item,"server.inspection.redaction.sensitivePaths entry");if(value.length()>256||value.startsWith(".")||value.endsWith(".")||value.contains("..")||value.indexOf('\\')>=0)throw new IllegalArgumentException("sensitivePaths entries must be normalized field paths");String normalized=value.toLowerCase(Locale.ROOT);if(!unique.add(normalized))throw new IllegalArgumentException("sensitivePaths contains a duplicate entry");result.add(normalized);}
        return List.copyOf(result);
    }
    private static List<String> checkedRegexes(Object raw) {
        if(raw==null)return List.of();List<?> entries=list(raw,"server.inspection.redaction.sensitiveFieldPatterns");if(entries.size()>100)throw new IllegalArgumentException("sensitiveFieldPatterns may contain at most 100 entries");
        java.util.ArrayList<String> result=new java.util.ArrayList<>();java.util.HashSet<String> unique=new java.util.HashSet<>();
        for(Object item:entries){String value=string(item,"sensitiveFieldPatterns entry");if(value.length()>128||!unique.add(value))throw new IllegalArgumentException("sensitiveFieldPatterns entries must be unique and at most 128 characters");try{java.util.regex.Pattern.compile(value,java.util.regex.Pattern.CASE_INSENSITIVE);}catch(java.util.regex.PatternSyntaxException invalid){throw new IllegalArgumentException("sensitiveFieldPatterns contains an invalid regular expression",invalid);}result.add(value);}
        return List.copyOf(result);
    }

    private static InlineLoad inlineLoad(Object raw) {
        Map<?,?> values=raw==null?Collections.emptyMap():map(raw,"server.inlineLoad");
        Set<String> allowed=Set.of("enabled","maxWorkloads","maxTargets","maxTotalUsers",
                "maxAggregateArrivalRatePerSecond","maxConcurrentPerWorkload","maxTotalConcurrent","maxDurationSeconds");
        for(Object key:values.keySet())if(!(key instanceof String)||!allowed.contains(key))
            throw new IllegalArgumentException("server.inlineLoad contains an unknown setting");
        boolean enabled=boolValue(values,"enabled",false);
        int workloads=inlineIntValue(values,"maxWorkloads",10,1,128);
        int targets=inlineIntValue(values,"maxTargets",20,1,256);
        int users=inlineIntValue(values,"maxTotalUsers",100,1,100000);
        int concurrent=inlineIntValue(values,"maxConcurrentPerWorkload",100,1,1000000);
        int totalConcurrent=inlineIntValue(values,"maxTotalConcurrent",1000,1,1000000);
        int duration=inlineIntValue(values,"maxDurationSeconds",3600,1,86400);
        Object configuredRate=values.get("maxAggregateArrivalRatePerSecond");
        double rate=configuredRate==null?100.0:configuredRate instanceof Number?((Number)configuredRate).doubleValue():Double.NaN;
        if(!Double.isFinite(rate)||rate<=0.0||rate>1000000.0)
            throw new IllegalArgumentException("server.inlineLoad.maxAggregateArrivalRatePerSecond must be a finite number greater than 0 and at most 1000000");
        return new InlineLoad(enabled,workloads,targets,users,rate,concurrent,totalConcurrent,duration);
    }

    private static Scheduling scheduling(Object raw,boolean authenticationRequired,int maxConcurrentLoad,boolean inspectionEnabled,String systemAdminRole) {
        Map<?,?> values=raw==null?Collections.emptyMap():map(raw,"server.scheduling");
        Set<String> allowed=Set.of("enabled","allowAnonymousManagement","adminRole","maxSchedules",
                "minimumIntervalMinutes","pollIntervalSeconds","maxConcurrentRuns","failurePolicy","maxExecutionsPerSchedule","packageChangePolicy");
        for(Object key:values.keySet())if(!(key instanceof String)||!allowed.contains(key))
            throw new IllegalArgumentException("server.scheduling contains an unknown setting");
        boolean enabled=boolValue(values,"enabled",false);
        boolean allowAnonymous=boolValue(values,"allowAnonymousManagement",!authenticationRequired&&enabled);
        if(enabled&&!inspectionEnabled)throw new IllegalArgumentException("server.inspection.enabled must be true when server.scheduling.enabled is true");
        Object rawRole=values.get("adminRole");
        String role=rawRole==null?systemAdminRole:string(rawRole,"server.scheduling.adminRole");
        if(!role.matches("[A-Za-z][A-Za-z0-9._-]{0,63}"))throw new IllegalArgumentException("server.scheduling.adminRole is invalid");
        int schedules=intValue(values,"maxSchedules",100,1,10000);
        int minimum=intValue(values,"minimumIntervalMinutes",15,1,10080);
        int poll=intValue(values,"pollIntervalSeconds",10,1,300);
        int concurrent=intValue(values,"maxConcurrentRuns",1,1,32);
        int history=intValue(values,"maxExecutionsPerSchedule",10000,100,100000);
        if(concurrent>maxConcurrentLoad)throw new IllegalArgumentException("server.scheduling.maxConcurrentRuns must not exceed workers.maxConcurrentLoad");
        Object rawFailurePolicy=values.get("failurePolicy");String failurePolicy=rawFailurePolicy==null?"continue":string(rawFailurePolicy,"server.scheduling.failurePolicy");
        if(!Set.of("continue","pauseAfterSubmitFailure").contains(failurePolicy))throw new IllegalArgumentException("server.scheduling.failurePolicy must be continue or pauseAfterSubmitFailure");
        String packageChangePolicy=values.get("packageChangePolicy")==null?"strict":string(values.get("packageChangePolicy"),"server.scheduling.packageChangePolicy");
        if(!Set.of("strict","pause","ignore").contains(packageChangePolicy))throw new IllegalArgumentException("server.scheduling.packageChangePolicy must be strict, pause or ignore");
        return new Scheduling(enabled,allowAnonymous,role,schedules,minimum,poll,concurrent,failurePolicy,history,packageChangePolicy);
    }

    private static Authorization authorization(Object raw,boolean authenticationRequired) {
        Map<?,?> values=raw==null?Collections.emptyMap():map(raw,"server.authorization");
        Set<String> allowed=Set.of("adminRole","userRole","allowAnonymousOperations");
        for(Object key:values.keySet())if(!(key instanceof String)||!allowed.contains(key))
            throw new IllegalArgumentException("server.authorization contains an unknown setting");
        String adminRole=values.get("adminRole")==null?"ATT_ADMIN":string(values.get("adminRole"),"server.authorization.adminRole");
        String userRole=values.get("userRole")==null?"ATT_USER":string(values.get("userRole"),"server.authorization.userRole");
        if(!adminRole.matches("[A-Za-z][A-Za-z0-9._-]{0,63}"))throw new IllegalArgumentException("server.authorization.adminRole is invalid");
        if(!userRole.matches("[A-Za-z][A-Za-z0-9._-]{0,63}"))throw new IllegalArgumentException("server.authorization.userRole is invalid");
        if(adminRole.equals(userRole))throw new IllegalArgumentException("server.authorization.adminRole and userRole must be different");
        boolean anonymous=boolValue(values,"allowAnonymousOperations",false);
        if(anonymous&&authenticationRequired)throw new IllegalArgumentException("server.authorization.allowAnonymousOperations requires server.authenticationRequired=false");
        return new Authorization(adminRole,userRole,anonymous);
    }

    public static final class Authorization {
        public final String adminRole,userRole;
        public final boolean allowAnonymousOperations;
        private Authorization(String adminRole,String userRole,boolean allowAnonymousOperations) {
            this.adminRole=adminRole;this.userRole=userRole;this.allowAnonymousOperations=allowAnonymousOperations;
        }
    }

    private static int inlineIntValue(Map<?,?> values,String key,int fallback,int min,int max) {
        Object raw=values.get(key);
        if(raw==null)return fallback;
        if(!(raw instanceof Number))throw new IllegalArgumentException("server.inlineLoad."+key+" must be an integer");
        try {
            long value=new java.math.BigDecimal(String.valueOf(raw)).longValueExact();
            if(value<min||value>max)throw new IllegalArgumentException("server.inlineLoad."+key+" must be between "+min+" and "+max);
            return (int)value;
        } catch(NumberFormatException|ArithmeticException invalid) {
            throw new IllegalArgumentException("server.inlineLoad."+key+" must be an integer");
        }
    }

    /** Server-owned limits for browser-created Load drafts. Path-based Load remains compatible. */
    public static final class InlineLoad {
        public final boolean enabled;
        public final int maxWorkloads,maxTargets,maxTotalUsers,maxConcurrentPerWorkload,maxTotalConcurrent,maxDurationSeconds;
        public final double maxAggregateArrivalRatePerSecond;
        private InlineLoad(boolean enabled,int maxWorkloads,int maxTargets,int maxTotalUsers,
                           double maxAggregateArrivalRatePerSecond,int maxConcurrentPerWorkload,int maxTotalConcurrent,int maxDurationSeconds) {
            this.enabled=enabled;this.maxWorkloads=maxWorkloads;this.maxTargets=maxTargets;this.maxTotalUsers=maxTotalUsers;
            this.maxAggregateArrivalRatePerSecond=maxAggregateArrivalRatePerSecond;
            this.maxConcurrentPerWorkload=maxConcurrentPerWorkload;this.maxTotalConcurrent=maxTotalConcurrent;this.maxDurationSeconds=maxDurationSeconds;
        }
    }

    /** Explicit opt-in and bounded limits for persisted recurring Load schedules. */
    public static final class Scheduling {
        public final boolean enabled,allowAnonymousManagement;
        public final String adminRole;
        public final String failurePolicy,packageChangePolicy;
        public final int maxSchedules,minimumIntervalMinutes,pollIntervalSeconds,maxConcurrentRuns,maxExecutionsPerSchedule;
        private Scheduling(boolean enabled,boolean allowAnonymousManagement,String adminRole,int maxSchedules,
                           int minimumIntervalMinutes,int pollIntervalSeconds,int maxConcurrentRuns,String failurePolicy,int maxExecutionsPerSchedule,String packageChangePolicy) {
            this.enabled=enabled;this.allowAnonymousManagement=allowAnonymousManagement;this.adminRole=adminRole;
            this.maxSchedules=maxSchedules;this.minimumIntervalMinutes=minimumIntervalMinutes;
            this.pollIntervalSeconds=pollIntervalSeconds;this.maxConcurrentRuns=maxConcurrentRuns;this.failurePolicy=failurePolicy;
            this.maxExecutionsPerSchedule=maxExecutionsPerSchedule;this.packageChangePolicy=packageChangePolicy;
        }
    }

    public static final class Security {
        public final boolean checkCrossOrigin;
        public final List<String> trustedOrigins;
        final OriginPolicy originPolicy;
        private Security(boolean checkCrossOrigin,List<String> trustedOrigins){
            this.checkCrossOrigin=checkCrossOrigin;this.trustedOrigins=List.copyOf(trustedOrigins);
            this.originPolicy=new OriginPolicy(this.trustedOrigins);
        }
    }

    public record WarmupKey(String packageId,String environment) { }
    public record Warmup(boolean enabled,int workers,List<WarmupKey> keys) { }

    public static final class Inspection {
        public final boolean enabled;
        public final String configurationAdminRole;
        public final Warmup warmup;
        public final int maxConcurrent,queuedLimit,timeoutMs,heapMaxMb,maxResponseBytes,maxSourceBytes;
        public final Map<String,List<String>> safeTextSources;
        public final List<String> externalSourceRoots;
        public final Map<String,List<String>> externalTextSources;
        public final boolean sourceBrowsingEnabled,redactionEnabled;
        public final String sourcePolicy;
        public final List<String> sourceDenyGlobs,sensitivePaths,sensitiveFieldPatterns;
        private Inspection(boolean enabled,String configurationAdminRole,int maxConcurrent,int queuedLimit,int timeoutMs,int heapMaxMb,int maxResponseBytes,int maxSourceBytes,Map<String,List<String>> safeTextSources,List<Path> externalSourceRoots,Map<String,List<String>> externalTextSources,
                           boolean sourceBrowsingEnabled,String sourcePolicy,List<String> sourceDenyGlobs,boolean redactionEnabled,List<String> sensitivePaths,List<String> sensitiveFieldPatterns,Warmup warmup) {
            this.warmup=warmup;
            this.enabled=enabled;this.configurationAdminRole=configurationAdminRole;this.maxConcurrent=maxConcurrent;this.queuedLimit=queuedLimit;this.timeoutMs=timeoutMs;this.heapMaxMb=heapMaxMb;this.maxResponseBytes=maxResponseBytes;this.maxSourceBytes=maxSourceBytes;
            this.safeTextSources=Collections.unmodifiableMap(new LinkedHashMap<>(safeTextSources));
            this.externalSourceRoots=externalSourceRoots.stream().map(Path::toString).toList();
            this.externalTextSources=Collections.unmodifiableMap(new LinkedHashMap<>(externalTextSources));
            this.sourceBrowsingEnabled=sourceBrowsingEnabled;this.sourcePolicy=sourcePolicy;this.sourceDenyGlobs=List.copyOf(sourceDenyGlobs);this.redactionEnabled=redactionEnabled;
            this.sensitivePaths=List.copyOf(sensitivePaths);this.sensitiveFieldPatterns=List.copyOf(sensitiveFieldPatterns);
        }
        public List<String> safeTextSources(String packageId) { return safeTextSources.getOrDefault(packageId,List.of()); }
        public List<String> externalTextSources(String packageId) { return externalTextSources.getOrDefault(packageId,List.of()); }
    }

    /** Immutable published console projection limits for a Server runtime. */
    public static final class LiveOutput {
        public final int maxEntryChars, maxEntryLines;
        private LiveOutput(int chars, int lines) { maxEntryChars=chars;maxEntryLines=lines; }
    }

    private static LiveOutput liveOutput(Object node) {
        Map<?,?> values=node==null?Collections.emptyMap():map(node,"server.liveOutput");
        for(Object key:values.keySet()) if(!Set.of("maxEntryChars","maxEntryLines").contains(key))
            throw new IllegalArgumentException("server.liveOutput contains an unknown setting: "+key);
        return new LiveOutput(liveOutputLimit(values,"maxEntryChars",4000,32000),
                liveOutputLimit(values,"maxEntryLines",40,200));
    }

    private static int liveOutputLimit(Map<?,?> values,String key,int fallback,int maximum) {
        if(!values.containsKey(key))return fallback;
        Object value=values.get(key);
        if(!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number)value).longValue()<1 || ((Number)value).longValue()>maximum)
            throw new IllegalArgumentException("server.liveOutput."+key+" must be an integer between 1 and "+maximum);
        return ((Number)value).intValue();
    }

    /** Size quota for {@code server.dataDir/jobs}. Disabled unless explicitly configured. */
    public static final class JobStorage {
        public static final JobStorage DISABLED = new JobStorage(false, 0L, 0L, 24, 60);
        public final boolean enabled;
        public final long maxTotalBytes, cleanupTargetBytes;
        public final int minRetentionHours, cleanupIntervalMinutes;

        private JobStorage(boolean enabled, long maxTotalBytes, long cleanupTargetBytes, int minRetentionHours, int cleanupIntervalMinutes) {
            this.enabled=enabled; this.maxTotalBytes=maxTotalBytes; this.cleanupTargetBytes=cleanupTargetBytes;
            this.minRetentionHours=minRetentionHours; this.cleanupIntervalMinutes=cleanupIntervalMinutes;
        }
    }

    private static JobStorage jobStorage(Object node) {
        if (node == null) return JobStorage.DISABLED;
        Map<?,?> values = map(node, "server.jobStorage");
        Set<String> allowed = Set.of("enabled", "maxTotalSizeMb", "cleanupTargetSizeMb", "minRetentionHours", "cleanupIntervalMinutes");
        for (Object key : values.keySet()) if (!(key instanceof String name) || !allowed.contains(name)) {
            if ("maxSingleJobSizeMb".equals(String.valueOf(key)))
                throw new IllegalArgumentException("server.jobStorage.maxSingleJobSizeMb is not supported; an individual Job is not deleted only because it is large");
            throw new IllegalArgumentException("server.jobStorage contains an unknown setting");
        }
        boolean enabled = boolValue(values, "enabled", false);
        int minHours = intValue(values, "minRetentionHours", 24, 0, 87600);
        int interval = intValue(values, "cleanupIntervalMinutes", 60, 1, 1440);
        if (!enabled) return new JobStorage(false, 0L, 0L, minHours, interval);
        if (!values.containsKey("maxTotalSizeMb") || !values.containsKey("cleanupTargetSizeMb"))
            throw new IllegalArgumentException("server.jobStorage.maxTotalSizeMb and cleanupTargetSizeMb are required when size limits are enabled");
        long maxMb = longMb(values, "maxTotalSizeMb");
        long targetMb = longMb(values, "cleanupTargetSizeMb");
        if (targetMb >= maxMb) throw new IllegalArgumentException("server.jobStorage.cleanupTargetSizeMb must be less than maxTotalSizeMb");
        return new JobStorage(true, maxMb * 1024L * 1024L, targetMb * 1024L * 1024L, minHours, interval);
    }

    private static long longMb(Map<?,?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("server.jobStorage."+key+" must be an integer");
        double exact = number.doubleValue();
        if (exact != Math.rint(exact) || exact < 1 || exact > 10_485_760)
            throw new IllegalArgumentException("server.jobStorage."+key+" must be an integer number of MiB from 1 to 10485760");
        return number.longValue();
    }
}
