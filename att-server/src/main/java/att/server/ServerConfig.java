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
import java.util.Map;

/** Validated, immutable deployment configuration for the single-node control plane. */
public final class ServerConfig {
    public final Path dataDir;
    public final Path javaExecutable;
    public final int maxConcurrent, queuedLimit, maxConcurrentLoad, gracefulStopMs, jobRetentionDays;
    public final int maxRequestBytes, maxEventsPerJob, maxArtifacts;
    public final boolean authenticationRequired;
    public final Map<String, Path> packages;
    public final List<Path> allowedRoots;
    public final List<Path> workerLibraryDirs;

    private ServerConfig(Path dataDir, Path javaExecutable, int maxConcurrent, int queuedLimit,
                         int maxConcurrentLoad, int gracefulStopMs, int jobRetentionDays, int maxRequestBytes,
                         int maxEventsPerJob, int maxArtifacts, boolean authenticationRequired, Map<String, Path> packages, List<Path> allowedRoots, List<Path> workerLibraryDirs) {
        this.dataDir=dataDir; this.javaExecutable=javaExecutable; this.maxConcurrent=maxConcurrent;
        this.queuedLimit=queuedLimit; this.maxConcurrentLoad=maxConcurrentLoad; this.gracefulStopMs=gracefulStopMs;this.jobRetentionDays=jobRetentionDays;
        this.maxRequestBytes=maxRequestBytes; this.maxEventsPerJob=maxEventsPerJob; this.maxArtifacts=maxArtifacts;this.authenticationRequired=authenticationRequired;
        this.packages=Collections.unmodifiableMap(new LinkedHashMap<>(packages));
        this.allowedRoots=List.copyOf(allowedRoots);
        this.workerLibraryDirs=List.copyOf(workerLibraryDirs);
    }

    public static ServerConfig load(Path file) throws Exception {
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file))
            throw new IllegalStateException("ATT Server configuration is required; set -Datt.server.config to a readable YAML file");
        LoaderOptions options=new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(20);
        Object raw;
        try (InputStream in=Files.newInputStream(file)) { raw=new Yaml(new SafeConstructor(options)).load(in); }
        if (!(raw instanceof Map<?,?> root)) throw new IllegalArgumentException("ATT Server configuration must be a YAML mapping");
        Map<?,?> server=map(root.get("server"),"server");
        Map<?,?> workers=map(root.get("workers"),"workers");
        Map<?,?> packagesNode=map(root.get("packages"),"packages");
        Path data=Paths.get(string(server.get("dataDir"),"server.dataDir"));
        if(!data.isAbsolute())throw new IllegalArgumentException("server.dataDir must be an absolute path");
        data=data.toAbsolutePath().normalize();
        if (Files.exists(data) && !Files.isDirectory(data)) throw new IllegalArgumentException("server.dataDir must be a directory");
        Path java=server.get("javaExecutable")==null?Paths.get(System.getProperty("java.home"),"bin",isWindows()?"java.exe":"java"):
                Paths.get(string(server.get("javaExecutable"),"server.javaExecutable"));
        java=java.toAbsolutePath().normalize();
        if (!Files.isRegularFile(java) || !Files.isExecutable(java)) throw new IllegalArgumentException("server.javaExecutable must be an executable Java runtime");
        List<?> roots=list(packagesNode.get("allowedRoots"),"packages.allowedRoots");
        List<?> libraryDirs=workers.get("libraryDirs")==null?List.of():list(workers.get("libraryDirs"),"workers.libraryDirs");
        Map<?,?> entries=map(packagesNode.get("entries"),"packages.entries");
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
            resolved.put(id,packageRoot);
        }
        if(resolved.isEmpty()) throw new IllegalArgumentException("packages.entries must define at least one package ID");
        java.util.ArrayList<Path> workerLibraries=new java.util.ArrayList<>();
        for(Object entry:libraryDirs){Path library=canonicalRequired(string(entry,"workers.libraryDirs entry"),"workers.libraryDirs entry",true);if(!Files.isReadable(library))throw new IllegalArgumentException("workers.libraryDirs entry must be readable: "+library);workerLibraries.add(library);}
        int max=intValue(workers,"maxConcurrent",8,1,256), queue=intValue(workers,"queuedLimit",100,0,100000),
            load=intValue(workers,"maxConcurrentLoad",2,1,256), stop=intValue(workers,"gracefulStopMs",10000,0,300000);
        int retention=intValue(server,"jobRetentionDays",30,1,3650);
        int request=intValue(server,"maxRequestBytes",1048576,1024,16777216), events=intValue(server,"maxEventsPerJob",10000,100,1000000),
            artifacts=intValue(server,"maxArtifacts",1000,1,100000);boolean authenticationRequired=boolValue(server,"authenticationRequired",true);
        Files.createDirectories(data);
        Path realData=data.toRealPath();
        String attHome=System.getProperty("att.home");if(attHome==null||attHome.isBlank())attHome=System.getenv("ATT_HOME");
        if(attHome!=null&&!attHome.isBlank()&&Files.exists(Paths.get(attHome))){Path realHome=Paths.get(attHome).toRealPath();if(realData.startsWith(realHome)||realHome.startsWith(realData))throw new IllegalArgumentException("server.dataDir must be separate from ATT_HOME");}
        for(Path packageRoot:resolved.values()) if(packageRoot.startsWith(realData)||realData.startsWith(packageRoot))
            throw new IllegalArgumentException("server.dataDir must be separate from configured package roots");
        privateDirectory(realData);privateDirectory(realData.resolve("db"));privateDirectory(realData.resolve("jobs"));
        return new ServerConfig(realData,java,max,queue,load,stop,retention,request,events,artifacts,authenticationRequired,resolved,allowed,workerLibraries);
    }

    public static Path configPath() {
        String configured=System.getProperty("att.server.config");
        if(configured==null||configured.isBlank()) configured=System.getenv("ATT_SERVER_CONFIG");
        return configured==null||configured.isBlank()?null:Paths.get(configured).toAbsolutePath().normalize();
    }
    private static boolean isWindows(){ return System.getProperty("os.name","").toLowerCase().contains("win"); }
    private static Path canonicalRequired(String value,String field,boolean directory) throws Exception {
        Path path=Paths.get(value); if(!path.isAbsolute()) throw new IllegalArgumentException(field+" must be an absolute path");
        if(!Files.exists(path)) throw new IllegalArgumentException(field+" does not exist: "+path);
        Path real=path.toRealPath(); if(directory&&!Files.isDirectory(real)) throw new IllegalArgumentException(field+" must be a directory: "+path); return real;
    }
    private static void privateDirectory(Path path){try{Files.createDirectories(path);Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}catch(Exception e){throw new IllegalStateException("Unable to secure ATT Server data directory",e);}}
    private static boolean boolValue(Map<?,?> map,String key,boolean fallback){Object value=map.get(key);if(value==null)return fallback;if(!(value instanceof Boolean))throw new IllegalArgumentException(key+" must be true or false");return (Boolean)value;}
    private static int intValue(Map<?,?> map,String key,int fallback,int min,int max) {
        Object v=map.get(key); if(v==null)return fallback; if(!(v instanceof Number n)) throw new IllegalArgumentException(key+" must be an integer");
        int value=n.intValue(); if(value<min||value>max) throw new IllegalArgumentException(key+" must be between "+min+" and "+max); return value;
    }
    private static String string(Object v,String field){ if(!(v instanceof String s)||s.isBlank()) throw new IllegalArgumentException(field+" is required"); return s.trim(); }
    private static Map<?,?> map(Object v,String field){ if(!(v instanceof Map<?,?> m)) throw new IllegalArgumentException(field+" must be a mapping"); return m; }
    private static List<?> list(Object v,String field){ if(!(v instanceof List<?> l)) throw new IllegalArgumentException(field+" must be a list"); return l; }
}
