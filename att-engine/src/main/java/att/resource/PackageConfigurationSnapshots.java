package att.resource;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.config.YamlSupport;
import att.metrics.InspectionMetrics;
import static att.metrics.InspectionMetrics.Stage.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.*;

/** Trusted parsed state only. Every caller constructs a fresh permission-checked projection.
 * Content reconciliation on every access deliberately avoids WatchService reliability assumptions.
 */
public final class PackageConfigurationSnapshots {
    private static final int CAPACITY = 4;
    private static final long MAX_RETAINED_WEIGHT = 32L * 1024 * 1024;
    private static final int MAX_FILES = 1024;
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private final LinkedHashMap<Key,Snapshot> entries = new LinkedHashMap<Key,Snapshot>(8,.75f,true);
    private long retainedWeight;

    synchronized Snapshot get(Path root, Path config, String environment, Object context, boolean effective) throws Exception {
        PackageResourceResolver resolver = new PackageResourceResolver(root);
        Path file = resolver.fromInternalPath(config.isAbsolute()?config:root.resolve(config), PackageResourceResolver.Kind.FILE).canonicalPath();
        Key key = new Key(resolver.packageRoot(),file,environment,context,effective);
        Snapshot cached = entries.get(key);
        if(cached != null) {
            try {
                Fingerprint current = fingerprint(resolver,cached.dependencies,effective);
                if(Arrays.equals(current.digest,cached.revision) && (!effective || cached.config!=null)) {
                    InspectionMetrics.JVM.add(CONFIG_HIT,1);
                    return cached;
                }
            } catch(Exception changed) { /* A fresh read must validate missing, renamed or unsafe dependencies. */ }
            remove(key, cached);
            InspectionMetrics.JVM.add(INVALIDATION,1);
        }
        InspectionMetrics.JVM.add(CONFIG_MISS,1);
        for(int attempt=0;attempt<3;attempt++) {
            byte[] before = digestFile(resolver,file,new long[]{0});
            if(Files.size(file)>1024*1024)throw new PackageConfigurationInspector.ConfigurationLimitException();
            long parsedAt=System.nanoTime();
            Object loaded=YamlSupport.load(file);
            InspectionMetrics.JVM.record(CONFIG_PARSE,System.nanoTime()-parsedAt);
            if(!(loaded instanceof Map))throw new IllegalArgumentException("Configuration must be a YAML mapping");
            Map<?,?> raw=(Map<?,?>)loaded;
            Set<Path> dependencies=effective?dependencies(resolver,file,raw,environment):Collections.singleton(file);
            Fingerprint first=fingerprint(resolver,dependencies,effective);
            if(!Arrays.equals(before,digestFile(resolver,file,new long[]{0})))continue;
            FrameworkConfig parsed=effective?new FrameworkConfigLoader().loadParsed(file,resolver.packageRoot(),environment,raw):null;
            Fingerprint after=fingerprint(resolver,dependencies,effective);
            if(!Arrays.equals(first.digest,after.digest))continue;
            Snapshot snapshot=new Snapshot(raw,parsed,dependencies,after.digest,after.bytes*16L);
            if(snapshot.weight<=MAX_RETAINED_WEIGHT) {
                entries.put(key,snapshot);retainedWeight+=snapshot.weight;
                while(entries.size()>CAPACITY||retainedWeight>MAX_RETAINED_WEIGHT) {
                    Map.Entry<Key,Snapshot> eldest=entries.entrySet().iterator().next();
                    remove(eldest.getKey(),eldest.getValue());
                    InspectionMetrics.JVM.add(CONFIG_EVICTION,1);
                }
            }
            return snapshot;
        }
        throw new IllegalArgumentException("Configuration changed during inspection; retry");
    }
    private void remove(Key key,Snapshot snapshot){entries.remove(key);retainedWeight-=snapshot.weight;}
    public synchronized void clear(){entries.clear();retainedWeight=0;}
    public synchronized int size(){return entries.size();}
    public synchronized List<String> affinityKeys(){
        Set<String> keys=new LinkedHashSet<String>();
        for(Key key:entries.keySet()) {
            keys.add(att.metrics.InspectionCacheKey.environment(String.valueOf(key.context),key.environment,"configuration"));
        }
        return new ArrayList<String>(keys);
    }
    public synchronized long retainedWeight(){return retainedWeight;}

    private Set<Path> dependencies(PackageResourceResolver resolver,Path config,Map<?,?> raw,String environment) {
        Set<Path> paths=new TreeSet<Path>();paths.add(config);
        addPaths(resolver,paths,raw.get("toolGroups"));
        Map<?,?> profile=Collections.emptyMap();
        String selected=environment==null&&raw.get("environment") instanceof String?((String)raw.get("environment")).trim():environment;
        if(raw.get("environments") instanceof Map)for(Map.Entry<?,?> item:((Map<?,?>)raw.get("environments")).entrySet())
            if(String.valueOf(item.getKey()).equalsIgnoreCase(selected)&&item.getValue() instanceof Map)profile=(Map<?,?>)item.getValue();
        for(String name:Arrays.asList("dbhelpers","mqhelpers","sshhelpers","httphelpers","testdata"))
            addPaths(resolver,paths,profile.containsKey(name)?profile.get(name):raw.get(name));
        return paths;
    }
    private void addPaths(PackageResourceResolver resolver,Set<Path> paths,Object configured) {
        if(configured instanceof Iterable)for(Object path:(Iterable<?>)configured)if(path instanceof String) {
            Path canonical=resolver.resolvePackageRelative(((String)path).replace('\\','/'),PackageResourceResolver.Kind.FILE).canonicalPath();
            if(Files.isSymbolicLink(resolver.packageRoot().resolve((String)path)))throw new IllegalArgumentException("Unsafe configuration dependency");
            // Retain the authored path too: canonical re-resolution detects symlink and rename changes.
            paths.add(resolver.packageRoot().resolve((String)path).normalize());
            paths.add(canonical);
            if(paths.size()>MAX_FILES)throw new PackageConfigurationInspector.ConfigurationLimitException();
        }
    }
    private Fingerprint fingerprint(PackageResourceResolver resolver,Set<Path> dependencies,boolean includeSchemas) throws Exception {
        Set<Path> paths=new TreeSet<Path>(dependencies);
        Path schemas=resolver.packageRoot().resolve("schemas");
        if(includeSchemas && Files.exists(schemas,LinkOption.NOFOLLOW_LINKS)) {
            resolver.fromInternalPath(schemas,PackageResourceResolver.Kind.DIRECTORY);
            try(java.util.stream.Stream<Path> stream=Files.walk(schemas)) {
                Iterator<Path> iterator=stream.iterator();
                int count=0;
                while(iterator.hasNext()) {
                    Path path=iterator.next();
                    if(++count>MAX_FILES)throw new PackageConfigurationInspector.ConfigurationLimitException();
                    if(Files.isSymbolicLink(path))throw new IllegalArgumentException("Unsafe schema dependency");
                    if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))paths.add(path);
                }
            }
        }
        if(paths.size()>MAX_FILES)throw new PackageConfigurationInspector.ConfigurationLimitException();
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long[] bytes={0};
        for(Path path:paths) {
            digest.update(resolver.packageRoot().relativize(path).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte)0);digest.update(digestFile(resolver,path,bytes));
        }
        return new Fingerprint(digest.digest(),bytes[0]);
    }
    private byte[] digestFile(PackageResourceResolver resolver,Path path,long[] bytes) throws Exception {
        if(Files.isSymbolicLink(path))throw new IllegalArgumentException("Unsafe configuration dependency");
        Path file=resolver.fromInternalPath(path,PackageResourceResolver.Kind.FILE).canonicalPath();
        if(Files.size(file)>1024*1024)throw new PackageConfigurationInspector.ConfigurationLimitException();
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1){bytes[0]+=count;if(bytes[0]>MAX_BYTES)throw new PackageConfigurationInspector.ConfigurationLimitException();digest.update(buffer,0,count);InspectionMetrics.JVM.add(READ_BYTES,count);}
        }
        return digest.digest();
    }
    static final class Snapshot {
        final Map<?,?> raw;final FrameworkConfig config;final Set<Path> dependencies;final byte[] revision;final long weight;
        Snapshot(Map<?,?> raw,FrameworkConfig config,Set<Path> dependencies,byte[] revision,long weight){this.raw=raw;this.config=config;this.dependencies=dependencies;this.revision=revision;this.weight=weight;}
    }
    private static final class Fingerprint {final byte[] digest;final long bytes;Fingerprint(byte[] digest,long bytes){this.digest=digest;this.bytes=bytes;}}
    private static final class Key {
        final Path root,config;final String environment;final Object context;final boolean effective;
        Key(Path root,Path config,String environment,Object context,boolean effective){this.root=root;this.config=config;this.environment=environment;this.context=context;this.effective=effective;}
        @Override public boolean equals(Object other){if(!(other instanceof Key))return false;Key k=(Key)other;return effective==k.effective&&root.equals(k.root)&&config.equals(k.config)&&Objects.equals(environment,k.environment)&&Objects.equals(context,k.context);}
        @Override public int hashCode(){return Objects.hash(root,config,environment,context,effective);}
    }
}
