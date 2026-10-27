package att.validation;

import att.config.RuntimeConfigPath;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Side-effect-free static validation for an installed ATT Server Config from the Local runtime. */
public final class ServerConfigurationValidator {
    private ServerConfigurationValidator() { }

    /** Returns the configured active file, or null when no Server Config is installed. */
    public static Path activeConfigPath() {
        String explicit=firstNonBlank(System.getProperty("att.server.config"),System.getenv("ATT_SERVER_CONFIG"));
        if(explicit!=null)return Paths.get(explicit).toAbsolutePath().normalize();
        String home=firstNonBlank(System.getProperty("att.home"),System.getenv("ATT_HOME"));
        if(home==null)return null;
        Path candidate=Paths.get(home,"config","att-server.yaml").toAbsolutePath().normalize();
        return Files.exists(candidate,LinkOption.NOFOLLOW_LINKS)?candidate:null;
    }

    public static Result validate(Path file) throws Exception {
        Path config=file.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(config)||!Files.isRegularFile(config,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(config))
            throw new IllegalArgumentException("Active ATT Server Config is missing, unreadable, or a symbolic link");
        if(Files.size(config)>1024*1024)throw new IllegalArgumentException("Active ATT Server Config exceeds the 1 MiB size limit");
        LoaderOptions options=new LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(20);options.setCodePointLimit(1024*1024);
        Object loaded;
        try(InputStream input=Files.newInputStream(config)){loaded=new Yaml(new SafeConstructor(options)).load(input);}
        Map<?,?> root=map(loaded,"root");unknown(root,"root",set("server","workers","packages"));
        Map<?,?> server=map(root.get("server"),"server");Map<?,?> workers=map(root.get("workers"),"workers");Map<?,?> packages=map(root.get("packages"),"packages");
        unknown(server,"server",set("dataDir","runtimeConfigPath","javaExecutable","authenticationRequired","auth","authorization","security","maxRequestBytes","maxEventsPerJob","maxArtifacts","jobRetentionDays","jobStorage","liveOutput","inspection","inlineLoad","scheduling","packageConfiguration"));
        unknown(workers,"workers",set("maxConcurrent","queuedLimit","maxConcurrentLoad","gracefulStopMs","heapInitialMb","heapMaxMb","libraryDirs","executionPool"));
        unknown(packages,"packages",set("allowedRoots","entries"));

        Path data=absolute(text(server.get("dataDir"),"server.dataDir"),"server.dataDir");
        if(Files.exists(data,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(data)||!Files.isDirectory(data,LinkOption.NOFOLLOW_LINKS)))
            throw invalid("server.dataDir","must be a non-symlink directory when it exists");
        Path runtimeConfig=server.get("runtimeConfigPath")==null?RuntimeConfigPath.serverDefault(config):absolute(text(server.get("runtimeConfigPath"),"server.runtimeConfigPath"),"server.runtimeConfigPath");
        if(Files.isSymbolicLink(runtimeConfig)||!Files.isRegularFile(runtimeConfig,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(runtimeConfig))
            throw invalid("server.runtimeConfigPath","must be a readable regular file");
        Path java=server.get("javaExecutable")==null?Paths.get(System.getProperty("java.home"),"bin",isWindows()?"java.exe":"java"):absolute(text(server.get("javaExecutable"),"server.javaExecutable"),"server.javaExecutable");
        if(!Files.isRegularFile(java,LinkOption.NOFOLLOW_LINKS)||!Files.isExecutable(java))throw invalid("server.javaExecutable","must be an executable Java runtime");
        bool(server,"authenticationRequired",true);
        Map<?,?> auth=optionalMap(server.get("auth"),"server.auth");unknown(auth,"server.auth",set("mode"));String mode=string(auth.get("mode"),"container");if(!set("container","local").contains(mode))throw invalid("server.auth.mode","must be container or local");
        if("local".equals(mode)&&!bool(server,"authenticationRequired",true))throw invalid("server.authenticationRequired","must be true in local auth mode");
        Map<?,?> authorization=optionalMap(server.get("authorization"),"server.authorization");unknown(authorization,"server.authorization",set("adminRole","userRole","allowAnonymousOperations"));
        String admin=string(authorization.get("adminRole"),"ATT_ADMIN"),user=string(authorization.get("userRole"),"ATT_USER");
        if(!role(admin)||!role(user)||admin.equals(user))throw invalid("server.authorization","role names must be valid and distinct");
        if(bool(authorization,"allowAnonymousOperations",false)&&bool(server,"authenticationRequired",true))throw invalid("server.authorization.allowAnonymousOperations","requires authenticationRequired=false");
        EnvironmentPolicy environmentPolicy=validatePackageConfiguration(server.get("packageConfiguration"));
        Map<?,?> security=optionalMap(server.get("security"),"server.security");unknown(security,"server.security",set("checkCrossOrigin","trustedOrigins"));bool(security,"checkCrossOrigin",false);stringList(security.get("trustedOrigins"),"server.security.trustedOrigins",100);

        List<?> rawAllowed=list(packages.get("allowedRoots"),"packages.allowedRoots");if(rawAllowed.isEmpty()||rawAllowed.size()>100)throw invalid("packages.allowedRoots","must contain 1 to 100 directories");
        List<Path> allowed=new ArrayList<Path>();for(Object item:rawAllowed)allowed.add(existingDirectory(text(item,"packages.allowedRoots entry"),"packages.allowedRoots entry"));
        Map<?,?> entries=optionalMap(packages.get("entries"),"packages.entries");if(entries.isEmpty()||entries.size()>200)throw invalid("packages.entries","must contain 1 to 200 registered Packages");
        Map<String,Path> resolved=new LinkedHashMap<String,Path>();
        for(Map.Entry<?,?> entry:entries.entrySet()) {
            String id=text(entry.getKey(),"package ID");if(!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw invalid("packages.entries","contains an invalid Package ID");
            Path packageRoot=existingDirectory(text(entry.getValue(),"packages.entries."+id),"packages.entries."+id);
            boolean inside=false;for(Path allowedRoot:allowed)if(packageRoot.startsWith(allowedRoot))inside=true;
            if(!inside)throw invalid("packages.entries."+id,"root is outside packages.allowedRoots");
            if(packageRoot.startsWith(data)||data.startsWith(packageRoot))throw invalid("server.dataDir","must be separate from registered Package roots");
            Path configDirectory=runtimeConfig.getParent();if(runtimeConfig.startsWith(packageRoot)||configDirectory.startsWith(packageRoot)||packageRoot.startsWith(configDirectory))throw invalid("server.runtimeConfigPath","and its config directory must be outside Package roots");
            resolved.put(id,packageRoot);
        }

        int workerMax=integer(workers,"maxConcurrent",8,1,256),loadMax=integer(workers,"maxConcurrentLoad",2,1,256);
        integer(workers,"queuedLimit",100,0,100000);integer(workers,"gracefulStopMs",10000,0,300000);
        int heapMax=integer(workers,"heapMaxMb",0,0,65536),heapInitial=integer(workers,"heapInitialMb",0,0,65536);
        if(heapInitial>0&&(heapMax==0||heapInitial>heapMax))throw invalid("workers.heapInitialMb","requires heapMaxMb and cannot exceed it");
        if(Math.min(workerMax,loadMax)>4)throw invalid("workers.maxConcurrentLoad","combined Worker concurrency exceeds the bounded preparation budget");
        List<?> libraries=workers.get("libraryDirs")==null?Collections.emptyList():list(workers.get("libraryDirs"),"workers.libraryDirs");for(Object item:libraries)existingDirectory(text(item,"workers.libraryDirs entry"),"workers.libraryDirs entry");
        validateWorkerPool(workers.get("executionPool"),resolved,heapMax);
        integer(server,"maxRequestBytes",1048576,1024,16777216);integer(server,"maxEventsPerJob",10000,100,1000000);integer(server,"maxArtifacts",1000,1,100000);integer(server,"jobRetentionDays",30,1,3650);
        validateLiveOutput(server.get("liveOutput"));validateJobStorage(server.get("jobStorage"));validateInspection(server.get("inspection"),resolved,admin);validateInlineLoad(server.get("inlineLoad"));validateScheduling(server.get("scheduling"),bool(server,"authenticationRequired",true),loadMax,admin);
        Path candidateData=canonicalFuture(data);for(Path packageRoot:resolved.values())if(packageRoot.startsWith(candidateData)||candidateData.startsWith(packageRoot))throw invalid("server.dataDir","must be separate from registered Package roots");
        return new Result(config,runtimeConfig,resolved,environmentPolicy.allowed.isEmpty()?null:environmentPolicy.allowed,environmentPolicy.requiredExplicit);
    }

    private static EnvironmentPolicy validatePackageConfiguration(Object raw) {
        Map<?,?> values=optionalMap(raw,"server.packageConfiguration");
        unknown(values,"server.packageConfiguration",set("sourceEditingEnabled","maxDrafts","maxDraftsPerUser","draftTtlSeconds","maxHistory","allowedEnvironments","requireExplicitEnvironments"));
        bool(values,"sourceEditingEnabled",false);
        int drafts=integer(values,"maxDrafts",128,1,1000);
        integer(values,"maxDraftsPerUser",Math.min(8,drafts),1,drafts);
        integer(values,"draftTtlSeconds",900,60,3600);integer(values,"maxHistory",50,2,1000);
        Set<String> allowed=environmentNames(values.get("allowedEnvironments"),"server.packageConfiguration.allowedEnvironments");
        Set<String> explicit=environmentNames(values.get("requireExplicitEnvironments"),"server.packageConfiguration.requireExplicitEnvironments");
        return new EnvironmentPolicy(allowed,explicit);
    }

    private static final class EnvironmentPolicy {
        final Set<String> allowed,requiredExplicit;
        EnvironmentPolicy(Set<String> allowed,Set<String> requiredExplicit){this.allowed=allowed;this.requiredExplicit=requiredExplicit;}
    }

    private static Set<String> environmentNames(Object raw,String field) {
        List<String> values=stringList(raw,field,100);Set<String> result=new LinkedHashSet<String>();
        for(String value:values) {
            if(!value.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")||!result.add(value.toLowerCase(java.util.Locale.ROOT)))
                throw invalid(field,"contains an invalid or duplicate Environment name");
        }
        return result;
    }

    private static void validateWorkerPool(Object raw,Map<String,Path> packages,int heapMax)throws Exception {
        Map<?,?> values=optionalMap(raw,"workers.executionPool");unknown(values,"workers.executionPool",set("enabled","strategy","minIdle","maxIdle","heapBudgetMb","idleTimeoutMs","startupTimeoutMs","packageIds"));
        boolean enabled=bool(values,"enabled",false);if(values.get("strategy")!=null&&!"prewarmed-one-shot".equals(text(values.get("strategy"),"workers.executionPool.strategy")))throw invalid("workers.executionPool.strategy","must be prewarmed-one-shot");
        int min=integer(values,"minIdle",1,0,16),max=integer(values,"maxIdle",2,1,16),budget=integer(values,"heapBudgetMb",512,64,65536);integer(values,"idleTimeoutMs",300000,1000,3600000);integer(values,"startupTimeoutMs",30000,1000,120000);
        List<?> ids=values.get("packageIds")==null?Collections.emptyList():list(values.get("packageIds"),"workers.executionPool.packageIds");Set<String> unique=new LinkedHashSet<String>();for(Object item:ids){String id=text(item,"workers.executionPool.packageIds entry");if(!packages.containsKey(id)||!unique.add(id))throw invalid("workers.executionPool.packageIds","must contain unique registered Package IDs");}
        if(min>max)throw invalid("workers.executionPool.minIdle","cannot exceed maxIdle");if(enabled&&(heapMax==0||((long)max*heapMax)>budget||unique.isEmpty()))throw invalid("workers.executionPool","enabled pool requires packageIds, heapMaxMb, and sufficient heapBudgetMb");
    }
    private static void validateLiveOutput(Object raw)throws Exception {Map<?,?> v=optionalMap(raw,"server.liveOutput");unknown(v,"server.liveOutput",set("maxEntryChars","maxEntryLines"));integer(v,"maxEntryChars",4000,1,32000);integer(v,"maxEntryLines",40,1,200);}
    private static void validateJobStorage(Object raw)throws Exception {Map<?,?> v=optionalMap(raw,"server.jobStorage");unknown(v,"server.jobStorage",set("enabled","maxTotalSizeMb","cleanupTargetSizeMb","minRetentionHours","cleanupIntervalMinutes"));boolean enabled=bool(v,"enabled",false);integer(v,"minRetentionHours",24,0,87600);integer(v,"cleanupIntervalMinutes",60,1,1440);if(enabled){long max=integer(v,"maxTotalSizeMb",0,1,10485760),target=integer(v,"cleanupTargetSizeMb",0,1,10485760);if(target>=max)throw invalid("server.jobStorage.cleanupTargetSizeMb","must be less than maxTotalSizeMb");}}
    private static void validateInspection(Object raw,Map<String,Path> packages,String admin)throws Exception {
        Map<?,?> v=optionalMap(raw,"server.inspection");unknown(v,"server.inspection",set("enabled","maxConcurrent","queuedLimit","timeoutMs","heapMaxMb","maxResponseBytes","maxSourceBytes","safeTextSources","sourceBrowsing","redaction","configurationAdminRole","warmup"));
        bool(v,"enabled",true);integer(v,"maxConcurrent",2,1,16);integer(v,"queuedLimit",16,0,1024);integer(v,"timeoutMs",30000,100,300000);integer(v,"heapMaxMb",512,64,4096);int response=integer(v,"maxResponseBytes",262144,1024,1048576),source=integer(v,"maxSourceBytes",65536,256,262144);if(source>=response)throw invalid("server.inspection.maxSourceBytes","must be less than maxResponseBytes");
        if(v.get("configurationAdminRole")!=null&&!admin.equals(text(v.get("configurationAdminRole"),"server.inspection.configurationAdminRole")))throw invalid("server.inspection.configurationAdminRole","must match server.authorization.adminRole");
        Map<?,?> browsing=optionalMap(v.get("sourceBrowsing"),"server.inspection.sourceBrowsing");unknown(browsing,"server.inspection.sourceBrowsing",set("enabled","policy","denyGlobs","maxSourceBytes","externalSourceRoots","externalTextSources"));bool(browsing,"enabled",true);String policy=string(browsing.get("policy"),"safe-package-files");if(!set("safe-package-files","allowlist-only").contains(policy))throw invalid("server.inspection.sourceBrowsing.policy","unsupported policy");stringList(browsing.get("denyGlobs"),"server.inspection.sourceBrowsing.denyGlobs",100);
        Map<?,?> redaction=optionalMap(v.get("redaction"),"server.inspection.redaction");unknown(redaction,"server.inspection.redaction",set("enabled","sensitivePaths","sensitiveFieldPatterns"));bool(redaction,"enabled",false);stringList(redaction.get("sensitivePaths"),"server.inspection.redaction.sensitivePaths",100);stringList(redaction.get("sensitiveFieldPatterns"),"server.inspection.redaction.sensitiveFieldPatterns",100);
        Map<?,?> warm=optionalMap(v.get("warmup"),"server.inspection.warmup");unknown(warm,"server.inspection.warmup",set("enabled","workers","keys"));bool(warm,"enabled",false);integer(warm,"workers",1,1,16);List<?> keys=warm.get("keys")==null?Collections.emptyList():list(warm.get("keys"),"server.inspection.warmup.keys");if(keys.size()>100)throw invalid("server.inspection.warmup.keys","contains too many entries");for(Object item:keys){Map<?,?> key=map(item,"server.inspection.warmup.keys entry");unknown(key,"server.inspection.warmup.keys entry",set("packageId","environment"));if(!packages.containsKey(text(key.get("packageId"),"packageId")))throw invalid("server.inspection.warmup.keys","references an unknown Package");}
    }
    private static void validateInlineLoad(Object raw)throws Exception {Map<?,?> v=optionalMap(raw,"server.inlineLoad");unknown(v,"server.inlineLoad",set("enabled","maxWorkloads","maxTargets","maxTotalUsers","maxAggregateArrivalRatePerSecond","maxConcurrentPerWorkload","maxTotalConcurrent","maxDurationSeconds"));bool(v,"enabled",false);integer(v,"maxWorkloads",10,1,128);integer(v,"maxTargets",20,1,256);integer(v,"maxTotalUsers",100,1,100000);integer(v,"maxConcurrentPerWorkload",100,1,1000000);integer(v,"maxTotalConcurrent",1000,1,1000000);inlineDuration(v);if(v.get("maxAggregateArrivalRatePerSecond")!=null){double value=number(v.get("maxAggregateArrivalRatePerSecond"),"server.inlineLoad.maxAggregateArrivalRatePerSecond");if(!Double.isFinite(value)||value<=0||value>1000000)throw invalid("server.inlineLoad.maxAggregateArrivalRatePerSecond","must be a finite number between 0 and 1000000");}}
    private static int inlineDuration(Map<?,?> values) {
        Object raw=values.get("maxDurationSeconds");if(raw==null)return 3600;
        if(!(raw instanceof Number))throw invalid("server.inlineLoad.maxDurationSeconds","must be an integer");
        double value=((Number)raw).doubleValue();if(value!=Math.rint(value)||value<1||value>864000)
            throw invalid("server.inlineLoad.maxDurationSeconds","must be an integer between 1 and 864000");
        return (int)value;
    }
    private static void validateScheduling(Object raw,boolean authenticated,int loadMax,String admin)throws Exception {Map<?,?> v=optionalMap(raw,"server.scheduling");unknown(v,"server.scheduling",set("enabled","allowAnonymousManagement","adminRole","maxSchedules","minimumIntervalMinutes","pollIntervalSeconds","maxConcurrentRuns","failurePolicy","maxExecutionsPerSchedule","packageChangePolicy"));boolean enabled=bool(v,"enabled",false);if(bool(v,"allowAnonymousManagement",!authenticated&&enabled)&&authenticated)throw invalid("server.scheduling.allowAnonymousManagement","requires authenticationRequired=false");if(v.get("adminRole")!=null&&!admin.equals(text(v.get("adminRole"),"server.scheduling.adminRole")))throw invalid("server.scheduling.adminRole","must match server.authorization.adminRole");integer(v,"maxSchedules",100,1,10000);integer(v,"minimumIntervalMinutes",15,1,10080);integer(v,"pollIntervalSeconds",10,1,300);if(integer(v,"maxConcurrentRuns",1,1,32)>loadMax)throw invalid("server.scheduling.maxConcurrentRuns","must not exceed workers.maxConcurrentLoad");integer(v,"maxExecutionsPerSchedule",10000,100,100000);if(!set("continue","pauseAfterSubmitFailure").contains(string(v.get("failurePolicy"),"continue")))throw invalid("server.scheduling.failurePolicy","unsupported policy");if(!set("strict","pause","ignore").contains(string(v.get("packageChangePolicy"),"strict")))throw invalid("server.scheduling.packageChangePolicy","unsupported policy");}

    private static Set<String> set(String... values){return new LinkedHashSet<String>(Arrays.asList(values));}
    private static void unknown(Map<?,?> map,String field,Set<String> allowed){for(Object key:map.keySet())if(!(key instanceof String)||!allowed.contains(key))throw invalid(field,"contains an unknown setting");}
    private static Map<?,?> map(Object value,String field){if(!(value instanceof Map))throw invalid(field,"must be a YAML mapping");return (Map<?,?>)value;}
    private static Map<?,?> optionalMap(Object value,String field){return value==null?Collections.emptyMap():map(value,field);}
    private static List<?> list(Object value,String field){if(!(value instanceof List))throw invalid(field,"must be a list");return (List<?>)value;}
    private static String text(Object value,String field){if(!(value instanceof String)||((String)value).trim().isEmpty())throw invalid(field,"must be a non-empty string");return ((String)value).trim();}
    private static String string(Object value,String fallback){return value==null?fallback:text(value,"value");}
    private static Path absolute(String value,String field){Path path=Paths.get(value);if(!path.isAbsolute())throw invalid(field,"must be an absolute path");return path.toAbsolutePath().normalize();}
    private static Path existingDirectory(String value,String field)throws Exception {Path path=absolute(value,field);if(Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(path))throw invalid(field,"must be a readable non-symlink directory");return path.toRealPath();}
    private static int integer(Map<?,?> map,String field,int fallback,int min,int max){Object value=map.get(field);if(value==null)return fallback;if(!(value instanceof Number))throw invalid(field,"must be an integer");double number=((Number)value).doubleValue();if(number!=Math.rint(number)||number<min||number>max)throw invalid(field,"must be an integer between "+min+" and "+max);return (int)number;}
    private static double number(Object value,String field){if(!(value instanceof Number))throw invalid(field,"must be numeric");return ((Number)value).doubleValue();}
    private static boolean bool(Map<?,?> map,String field,boolean fallback){Object value=map.get(field);if(value==null)return fallback;if(!(value instanceof Boolean))throw invalid(field,"must be true or false");return ((Boolean)value).booleanValue();}
    private static List<String> stringList(Object raw,String field,int max){if(raw==null)return Collections.emptyList();List<?> items=list(raw,field);if(items.size()>max)throw invalid(field,"contains too many entries");List<String> result=new ArrayList<String>();for(Object item:items)result.add(text(item,field+" entry"));return result;}
    private static boolean role(String value){return value.matches("[A-Za-z][A-Za-z0-9._-]{0,63}");}
    private static Path canonicalFuture(Path path)throws Exception {Path current=path;List<Path> tail=new ArrayList<Path>();while(current!=null&&!Files.exists(current,LinkOption.NOFOLLOW_LINKS)){tail.add(current.getFileName());current=current.getParent();}if(current==null)return path;Path result=current.toRealPath();for(int i=tail.size()-1;i>=0;i--)result=result.resolve(tail.get(i));return result.normalize();}
    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT).contains("win");}
    private static String firstNonBlank(String first,String second){return first!=null&&!first.trim().isEmpty()?first.trim():second!=null&&!second.trim().isEmpty()?second.trim():null;}
    private static IllegalArgumentException invalid(String field,String reason){return new IllegalArgumentException(field+": "+reason);}

    public static final class Result {
        private final Path source,runtimeConfig;private final Map<String,Path> packages;private final Set<String> allowedEnvironments,requiredExplicitEnvironments;
        private Result(Path source,Path runtimeConfig,Map<String,Path> packages,Set<String> allowedEnvironments,Set<String> requiredExplicitEnvironments){this.source=source;this.runtimeConfig=runtimeConfig;this.packages=Collections.unmodifiableMap(new LinkedHashMap<String,Path>(packages));this.allowedEnvironments=allowedEnvironments==null?null:Collections.unmodifiableSet(new LinkedHashSet<String>(allowedEnvironments));this.requiredExplicitEnvironments=Collections.unmodifiableSet(new LinkedHashSet<String>(requiredExplicitEnvironments));}
        public Path source(){return source;}public Path runtimeConfig(){return runtimeConfig;}public Map<String,Path> packages(){return packages;}public Set<String> allowedEnvironments(){return allowedEnvironments;}public Set<String> requiredExplicitEnvironments(){return requiredExplicitEnvironments;}
    }
}
