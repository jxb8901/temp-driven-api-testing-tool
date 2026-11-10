package att.worker;

import att.server.api.WorkerRequest;
import att.template.FileExpressionResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileSystems;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verifies an acceptance-time source archive and stages it privately for one Worker process. */
final class WorkerSourceSnapshot implements AutoCloseable {
    static final String VERSION = "att-worker-source-snapshot/v2";
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_MANIFEST_BYTES = 1024L * 1024L;
    private static final int MAX_ARCHIVE_ENTRIES = 100000;
    private static final ObjectMapper JSON = new ObjectMapper();

    final Path directory;
    final Path packageRoot;
    final Path configRoot;
    final Path configPath;
    final String packageId;
    final JsonNode manifest;
    private final String executionTreeFingerprint;
    private final AutoCloseable planRegistration;
    private volatile WatchService mutationWatcher;
    private volatile Thread mutationMonitor;
    private volatile Thread guardedExecutionThread;
    private volatile boolean changedDuringExecution;
    private volatile boolean closed;

    private WorkerSourceSnapshot(Path directory, Path packageRoot, Path configRoot, Path configPath,
                                 String packageId, JsonNode manifest,String executionTreeFingerprint,
                                 AutoCloseable planRegistration) {
        this.directory=directory;this.packageRoot=packageRoot;this.configRoot=configRoot;
        this.configPath=configPath;this.packageId=packageId;this.manifest=manifest;
        this.executionTreeFingerprint=executionTreeFingerprint;this.planRegistration=planRegistration;
    }

    static WorkerSourceSnapshot stage(WorkerRequest request) throws IOException {
        boolean any=request.sourceSnapshotArchivePath!=null||request.sourceSnapshotArchiveSha256!=null
                ||request.sourceSnapshotConfigPath!=null;
        if(!any)return null;
        if(request.sourceSnapshotArchivePath==null||request.sourceSnapshotArchiveSha256==null
                ||request.sourceSnapshotConfigPath==null
                ||!request.sourceSnapshotArchiveSha256.matches("[a-f0-9]{64}"))
            throw new IOException("The accepted source snapshot request is incomplete");
        Path archive=Paths.get(request.sourceSnapshotArchivePath).toAbsolutePath().normalize();
        if(Files.isSymbolicLink(archive)||!Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS)
                ||Files.size(archive)>MAX_ARCHIVE_BYTES)throw new IOException("The accepted source snapshot archive is unavailable");
        Path directory=Files.createTempDirectory("att-source-snapshot-");
        setPrivateDirectory(directory);
        Path copy=directory.resolve("accepted-source.zip");
        AutoCloseable planRegistration=null;
        try {
            Files.copy(archive,copy,StandardCopyOption.COPY_ATTRIBUTES);
            if(!request.sourceSnapshotArchiveSha256.equals(digestFile(copy)))
                throw new IOException("The accepted source snapshot archive failed its integrity check");
            Path originalPackageRoot=Paths.get(request.packageRoot).toAbsolutePath().normalize();
            Path originalConfigFile=att.config.RuntimeConfigPath.resolve(
                    request.config==null?null:Paths.get(request.config),originalPackageRoot);
            Path originalConfigRoot=att.config.RuntimeConfigPath.root(originalConfigFile);
            boolean packageRootFirst=originalPackageRoot.compareTo(originalConfigRoot)<0;
            // Preserve the legacy absolute-Path ordering for older archives without an embedded
            // execution identity. Current archives carry the canonical identity in their manifest.
            Path firstRoot=directory.resolve("a-source"),secondRoot=directory.resolve("z-source");
            Path packageRoot=packageRootFirst?firstRoot:secondRoot;
            Path configRoot=packageRootFirst?secondRoot:firstRoot;
            Files.createDirectory(packageRoot);Files.createDirectory(configRoot);
            JsonNode manifest;String packageId;
            try(ZipFile zip=new ZipFile(copy.toFile())) {
                manifest=readManifest(zip);
                packageId=manifest.path("packageId").asText(null);
                if(packageId==null||!packageId.equals(request.registeredPackageId))
                    throw new IOException("The accepted source snapshot belongs to a different Package");
                String acceptedExecutionRevision=manifest.path("executionRevisionDigest").asText(null);
                if(acceptedExecutionRevision!=null
                        &&(request.expectedExecutionRevisionDigest==null
                        ||!acceptedExecutionRevision.equals(request.expectedExecutionRevisionDigest)))
                    throw new IOException("The accepted source snapshot execution revision does not match the Worker request");
                if(!request.sourceSnapshotConfigPath.equals(manifest.path("configurationPath").asText(null)))
                    throw new IOException("The accepted source snapshot Config path does not match the request");
                extract(zip,"package",packageRoot,manifest.path("packageExecutableFiles"));
                extract(zip,"config",configRoot,manifest.path("configExecutableFiles"));
            }
            createSymlinks(packageRoot,manifest.path("packageSymlinks"));
            createSymlinks(configRoot,manifest.path("configSymlinks"));
            if(!manifest.path("packageTreeDigest").asText("").equals(treeDigest(packageRoot))
                    ||!manifest.path("configTreeDigest").asText("").equals(treeDigest(configRoot))
                    ||!manifest.path("packageExecutableDigest").asText("").equals(executableDigestTree(packageRoot))
                    ||!manifest.path("configExecutableDigest").asText("").equals(executableDigestTree(configRoot)))
                throw new IOException("The accepted source snapshot tree failed its integrity check");
            String relative=safeLogicalPath(request.sourceSnapshotConfigPath);
            Path configPath=configRoot.resolve(relative).normalize();
            if(!configPath.startsWith(configRoot)||Files.isSymbolicLink(configPath)
                    ||!Files.isRegularFile(configPath,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("The accepted Worker Config file is unavailable in the source snapshot");
            verifyDependencies(packageRoot,configRoot,manifest.path("dependencies"));
            byte[] compiledPlans;
            try(ZipFile zip=new ZipFile(copy.toFile())) { compiledPlans=readCompiledFilePlans(zip,manifest); }
            FileExpressionResolver.FileExpressionSnapshot restoredPlans=
                    FileExpressionResolver.FileExpressionSnapshot.restore(packageRoot,compiledPlans);
            planRegistration=FileExpressionResolver.registerApprovedSnapshot(packageRoot,restoredPlans);
            Files.deleteIfExists(copy);
            freezeTree(directory);
            String executionTreeFingerprint=executionTreeFingerprint(directory);
            return new WorkerSourceSnapshot(directory,packageRoot,configRoot,configPath,packageId,manifest,
                    executionTreeFingerprint,planRegistration);
        } catch(Exception failure) {
            if(planRegistration!=null)try{planRegistration.close();}catch(Exception ignored){}
            thawTree(directory);
            deleteTree(directory);
            if(failure instanceof IOException)throw (IOException)failure;
            throw new IOException("Unable to restore the accepted source snapshot",failure);
        }
    }

    private static JsonNode readManifest(ZipFile zip) throws IOException {
        ZipEntry entry=zip.getEntry("manifest.json");
        if(entry==null||entry.isDirectory()||entry.getSize()<0||entry.getSize()>MAX_MANIFEST_BYTES)
            throw new IOException("The accepted source snapshot manifest is unavailable");
        try(InputStream input=zip.getInputStream(entry)) {
            byte[] bytes=readBounded(input,MAX_MANIFEST_BYTES);
            JsonNode manifest=JSON.readTree(bytes);
            if(manifest==null||!VERSION.equals(manifest.path("schemaVersion").asText())
                    ||!manifest.path("packageTreeDigest").asText("").matches("[a-f0-9]{64}")
                    ||!manifest.path("configTreeDigest").asText("").matches("[a-f0-9]{64}")
                    ||!manifest.path("packageExecutableDigest").asText("").matches("[a-f0-9]{64}")
                    ||!manifest.path("configExecutableDigest").asText("").matches("[a-f0-9]{64}")
                    ||(manifest.hasNonNull("executionRevisionDigest")
                    &&!manifest.path("executionRevisionDigest").asText("").matches("[a-f0-9]{64}"))
                    ||!manifest.path("packageExecutableDigest").asText().equals(executableDigestManifest(manifest.path("packageExecutableFiles")))
                    ||!manifest.path("configExecutableDigest").asText().equals(executableDigestManifest(manifest.path("configExecutableFiles"))))
                throw new IOException("The accepted source snapshot manifest is invalid");
            return manifest;
        }
    }

    private static void extract(ZipFile zip,String tree,Path root,JsonNode executableFiles) throws IOException {
        Enumeration<? extends ZipEntry> entries=zip.entries();
        Set<String> seen=new HashSet<String>();int count=0;long total=0;
        while(entries.hasMoreElements()) {
            ZipEntry entry=entries.nextElement();String name=entry.getName();
            if("manifest.json".equals(name)||"file-plans.bin".equals(name))continue;
            if(!name.startsWith(tree+"/")) {
                if(name.startsWith("package/")||name.startsWith("config/"))continue;
                throw new IOException("The accepted source snapshot contains an unknown entry");
            }
            String relative=name.substring(tree.length()+1);
            if(relative.length()==0)continue;
            String safe=safeLogicalPath(relative);
            if(!seen.add(safe))throw new IOException("The accepted source snapshot contains duplicate entries");
            if(++count>MAX_ARCHIVE_ENTRIES)throw new IOException("The accepted source snapshot contains too many entries");
            Path target=root.resolve(safe).normalize();
            if(!target.startsWith(root))throw new IOException("The accepted source snapshot contains an unsafe path");
            if(entry.isDirectory()) { createDirectories(root,target);continue; }
            if(entry.getSize()<0||entry.getSize()>MAX_ARCHIVE_BYTES)throw new IOException("The accepted source snapshot entry is oversized");
            total+=entry.getSize();if(total>MAX_ARCHIVE_BYTES)throw new IOException("The accepted source snapshot is oversized");
            createDirectories(root,target.getParent());
            try(InputStream input=zip.getInputStream(entry);OutputStream output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                byte[] buffer=new byte[8192];int read;long copied=0;
                while((read=input.read(buffer))>=0) {
                    copied+=read;if(copied>MAX_ARCHIVE_BYTES)throw new IOException("The accepted source snapshot entry is oversized");
                    output.write(buffer,0,read);
                }
                if(copied!=entry.getSize())throw new IOException("The accepted source snapshot entry is truncated");
            }
        }
        restoreExecutableFiles(root,executableFiles);
    }

    private static void createSymlinks(Path root,JsonNode links) throws IOException {
        if(links==null||!links.isObject())return;
        java.util.Iterator<java.util.Map.Entry<String,JsonNode>> fields=links.fields();
        while(fields.hasNext()) {
            java.util.Map.Entry<String,JsonNode> link=fields.next();String relative=safeLogicalPath(link.getKey());
            String targetValue=link.getValue().asText(null);
            if(targetValue==null||targetValue.length()==0)throw new IOException("The source snapshot contains an invalid symbolic link");
            Path target=Paths.get(targetValue),linkPath=root.resolve(relative).normalize();
            if(target.isAbsolute()||!linkPath.startsWith(root)||!linkPath.getParent().resolve(target).normalize().startsWith(root)
                    ||Files.exists(linkPath,LinkOption.NOFOLLOW_LINKS))throw new IOException("The source snapshot contains an unsafe symbolic link");
            createDirectories(root,linkPath.getParent());
            Files.createSymbolicLink(linkPath,target);
        }
    }

    private static void verifyDependencies(Path packageRoot,Path configRoot,JsonNode dependencies) throws IOException {
        if(dependencies==null||!dependencies.isArray())throw new IOException("The accepted source dependency closure is unavailable");
        for(JsonNode dependency:dependencies) {
            String path=dependency.path("canonicalPath").asText(null);
            String fingerprint=dependency.path("sourceFingerprint").asText(null);
            if(path==null||fingerprint==null)continue;
            String relative=safeLogicalPath(path);Path packageFile=packageRoot.resolve(relative).normalize();
            Path configFile=configRoot.resolve(relative).normalize();
            boolean matched=false;
            if(packageFile.startsWith(packageRoot)&&Files.isRegularFile(packageFile,LinkOption.NOFOLLOW_LINKS)
                    &&!Files.isSymbolicLink(packageFile))matched=fingerprint.equals(digestFile(packageFile));
            if(!matched&&configFile.startsWith(configRoot)&&Files.isRegularFile(configFile,LinkOption.NOFOLLOW_LINKS)
                    &&!Files.isSymbolicLink(configFile))matched=fingerprint.equals(digestFile(configFile));
            if(!matched)throw new IOException("An approved source dependency is unavailable or changed");
        }
    }

    private static byte[] readCompiledFilePlans(ZipFile zip,JsonNode manifest) throws IOException {
        ZipEntry entry=zip.getEntry("file-plans.bin");
        String expected=manifest.path("compiledFilePlansSha256").asText(null);
        if(entry==null||entry.isDirectory()||entry.getSize()<=0||entry.getSize()>128L*1024L*1024L
                ||expected==null||!expected.matches("[a-f0-9]{64}"))
            throw new IOException("The accepted compiled File-expression plans are unavailable");
        try(InputStream input=zip.getInputStream(entry)) {
            byte[] bytes=readBounded(input,128L*1024L*1024L);
            if(!expected.equals(digest(bytes)))throw new IOException("The accepted compiled File-expression plans failed their integrity check");
            return bytes;
        }
    }

    private static String treeDigest(Path root) throws IOException {
        final java.util.List<Path> entries=new ArrayList<Path>();
        Files.walkFileTree(root,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException {
                if(attributes.isSymbolicLink()||Files.isSymbolicLink(file)||attributes.isRegularFile()) {entries.add(file);return FileVisitResult.CONTINUE;}
                throw new IOException("The source snapshot contains an unsupported entry");
            }
        });
        Collections.sort(entries,new Comparator<Path>() {
            @Override public int compare(Path left,Path right) {
                return root.relativize(left).toString().replace(java.io.File.separatorChar,'/')
                        .compareTo(root.relativize(right).toString().replace(java.io.File.separatorChar,'/'));
            }
        });
        MessageDigest digest=sha256();
        for(Path entry:entries) {
            String relative=root.relativize(entry).toString().replace(java.io.File.separatorChar,'/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            if(Files.isSymbolicLink(entry)) {
                Path target=Files.readSymbolicLink(entry);
                if(target.isAbsolute()||!entry.getParent().resolve(target).normalize().startsWith(root))throw new IOException("The source snapshot contains an external symbolic link");
                digest.update((byte)'L');digest.update(target.toString().getBytes(StandardCharsets.UTF_8));
            } else {
                digest.update((byte)'F');digest.update(hexBytes(digestFile(entry)));
            }
            digest.update((byte)0xff);
        }
        return hex(digest.digest());
    }

    void verifyUnchanged() throws IOException {
        if(changedDuringExecution
                ||!manifest.path("packageTreeDigest").asText().equals(treeDigest(packageRoot))
                ||!manifest.path("configTreeDigest").asText().equals(treeDigest(configRoot))
                ||!manifest.path("packageExecutableDigest").asText().equals(executableDigestTree(packageRoot))
                ||!manifest.path("configExecutableDigest").asText().equals(executableDigestTree(configRoot))
                ||!executionTreeFingerprint.equals(executionTreeFingerprint(directory)))
            throw sourceChanged();
    }

    /** Returns true when the verified archive carries the canonical accepted execution identity. */
    boolean verifyExecutionRevision(String expected) throws IOException {
        String accepted=manifest.path("executionRevisionDigest").asText(null);
        if(accepted==null)return false; // Older v1 archives are checked from their restored tree.
        if(expected==null||!expected.matches("[a-f0-9]{64}")||!accepted.equals(expected))
            throw new IOException("The accepted source snapshot execution revision does not match the Worker request");
        return true;
    }

    /** Watch the private source trees for the entire execution and interrupt active external Tools on change. */
    void guardExecution(Thread executionThread) throws IOException {
        if(executionThread==null)throw new IllegalArgumentException("Worker execution thread is required");
        WatchService watcher=FileSystems.getDefault().newWatchService();
        try {
            registerDirectories(packageRoot,watcher);
            registerDirectories(configRoot,watcher);
        } catch(IOException|RuntimeException failure) {
            try { watcher.close(); } catch(IOException ignored) { }
            throw failure;
        }
        guardedExecutionThread=executionThread;
        mutationWatcher=watcher;
        Thread monitor=new Thread(()->monitorMutations(watcher),"att-worker-source-integrity");
        monitor.setDaemon(true);
        mutationMonitor=monitor;
        monitor.start();
    }

    private static void registerDirectories(Path root,WatchService watcher) throws IOException {
        Files.walkFileTree(root,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attributes)throws IOException {
                directory.register(watcher,StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,StandardWatchEventKinds.ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void monitorMutations(WatchService watcher) {
        try {
            while(!closed) {
                WatchKey key=watcher.take();
                boolean changed=false;
                for(WatchEvent<?> event:key.pollEvents()) {
                    if(event.kind()==StandardWatchEventKinds.OVERFLOW
                            ||event.kind()==StandardWatchEventKinds.ENTRY_CREATE
                            ||event.kind()==StandardWatchEventKinds.ENTRY_DELETE
                            ||event.kind()==StandardWatchEventKinds.ENTRY_MODIFY)changed=true;
                }
                if(changed||!key.reset()) {
                    changedDuringExecution=true;
                    Thread execution=guardedExecutionThread;
                    if(execution!=null)execution.interrupt();
                    return;
                }
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch(java.nio.file.ClosedWatchServiceException closedWatcher) {
            // Worker cleanup closed the watcher after the execution ended.
        }
    }

    static final class SourceChangedException extends IOException {
        SourceChangedException() { super("The accepted source snapshot changed during Worker execution"); }
    }

    private static SourceChangedException sourceChanged() { return new SourceChangedException(); }

    boolean changedDuringExecution() { return changedDuringExecution; }

    /** Detects transient same-identity writes even if the original content and mode are restored. */
    private static String executionTreeFingerprint(Path root) throws IOException {
        final List<String> entries=new ArrayList<String>();
        Files.walkFileTree(root,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attributes)throws IOException {
                entries.add(executionEntry(root,directory,"D",attributes));return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException {
                String type=attributes.isSymbolicLink()?"L":attributes.isRegularFile()?"F":"O";
                entries.add(executionEntry(root,file,type,attributes));return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(entries);MessageDigest digest=sha256();
        for(String entry:entries){digest.update(entry.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
        return hex(digest.digest());
    }

    private static String executionEntry(Path root,Path path,String type,BasicFileAttributes attributes) {
        String relative=root.relativize(path).toString().replace(java.io.File.separatorChar,'/');
        String changeTime="";
        try { changeTime=String.valueOf(Files.getAttribute(path,"unix:ctime",LinkOption.NOFOLLOW_LINKS)); }
        catch(UnsupportedOperationException|IllegalArgumentException|IOException unsupported) { }
        String permissions="";
        try { permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS).toString(); }
        catch(UnsupportedOperationException|IOException unsupported) { }
        return relative+"\u0000"+type+"\u0000"+String.valueOf(attributes.fileKey())+"\u0000"+attributes.size()
                +"\u0000"+attributes.lastModifiedTime().toMillis()+"\u0000"+attributes.creationTime().toMillis()
                +"\u0000"+changeTime+"\u0000"+permissions;
    }

    private static String executableDigestTree(Path root) throws IOException {
        final java.util.List<String> paths=new ArrayList<String>();
        Files.walkFileTree(root,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException {
                if(attributes.isRegularFile()&&!attributes.isSymbolicLink()&&!Files.isSymbolicLink(file)&&Files.isExecutable(file))
                    paths.add(root.relativize(file).toString().replace(java.io.File.separatorChar,'/'));
                return FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(paths);return executableDigest(paths);
    }

    private static String executableDigestManifest(JsonNode files) {
        if(files==null||!files.isArray())return null;
        java.util.List<String> paths=new ArrayList<String>();Set<String> seen=new HashSet<String>();
        try {
            for(JsonNode value:files) {
                String path=value.isTextual()?safeLogicalPath(value.asText()):null;
                if(path==null||!seen.add(path))return null;
                paths.add(path);
            }
        } catch(IOException invalid) { return null; }
        java.util.List<String> sorted=new ArrayList<String>(paths);Collections.sort(sorted);
        return sorted.equals(paths)?executableDigest(paths):null;
    }

    private static String executableDigest(java.util.List<String> paths) {
        MessageDigest digest=sha256();
        for(String path:paths){digest.update(path.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}
        return hex(digest.digest());
    }

    private static void restoreExecutableFiles(Path root,JsonNode files) throws IOException {
        if(files==null||!files.isArray())throw new IOException("The source snapshot executable-mode list is invalid");
        Set<String> seen=new HashSet<String>();
        for(JsonNode value:files) {
            String relative=value.isTextual()?safeLogicalPath(value.asText()):null;
            if(relative==null||!seen.add(relative))throw new IOException("The source snapshot executable-mode list is invalid");
            Path target=root.resolve(relative).normalize();
            if(!target.startsWith(root)||Files.isSymbolicLink(target)||!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("The source snapshot executable file is unavailable");
            try {
                java.util.Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(target,LinkOption.NOFOLLOW_LINKS);
                permissions.add(PosixFilePermission.OWNER_EXECUTE);Files.setPosixFilePermissions(target,permissions);
            } catch(UnsupportedOperationException unsupported) {
                if(!target.toFile().setExecutable(true,false))throw new IOException("Unable to restore executable source permission");
            }
            if(!Files.isExecutable(target))throw new IOException("Unable to restore executable source permission");
        }
    }

    private static void freezeTree(Path root) throws IOException {
        Files.walkFileTree(root,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException {
                if(!Files.isSymbolicLink(file)&&attributes.isRegularFile())freezePath(file,false,Files.isExecutable(file));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path directory,IOException error)throws IOException {
                if(error!=null)throw error;freezePath(directory,true,false);return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void freezePath(Path path,boolean directory,boolean executable) throws IOException {
        try {
            EnumSet<PosixFilePermission> permissions=EnumSet.of(PosixFilePermission.OWNER_READ);
            if(directory||executable)permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path,permissions);
        } catch(UnsupportedOperationException unsupported) {
            if(!path.toFile().setReadable(true,false)||!path.toFile().setWritable(false,false)
                    ||((directory||executable)&&!path.toFile().setExecutable(true,false)))
                throw new IOException("Unable to protect accepted Worker source bytes from writes");
        }
    }

    private static void thawTree(Path root) {
        if(root==null||!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        try { Files.walkFileTree(root,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attributes)throws IOException {
                thawPath(directory,true);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException {
                if(!Files.isSymbolicLink(file))thawPath(file,false);return FileVisitResult.CONTINUE;
            }
        }); } catch(IOException ignored) { }
    }

    private static void thawPath(Path path,boolean directory) throws IOException {
        try {
            EnumSet<PosixFilePermission> permissions=EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE);
            if(directory)permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(path,permissions);
        } catch(UnsupportedOperationException unsupported) {
            path.toFile().setWritable(true,false);path.toFile().setReadable(true,false);
            if(directory)path.toFile().setExecutable(true,false);
        }
    }

    private static void createDirectories(Path root,Path directory) throws IOException {
        if(directory==null||directory.equals(root))return;
        Path relative=root.relativize(directory.normalize());Path current=root;
        for(Path part:relative) {
            current=current.resolve(part);
            if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)) {
                if(Files.isSymbolicLink(current)||!Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS))throw new IOException("The source snapshot path is unsafe");
            } else Files.createDirectory(current);
        }
    }

    private static String safeLogicalPath(String value) throws IOException {
        if(value==null||value.length()==0||value.length()>1024||value.startsWith("/")||value.indexOf('\\')>=0
                ||value.indexOf(':')>=0||value.indexOf('\0')>=0)throw new IOException("The source snapshot contains an invalid logical path");
        Path path=Paths.get(value).normalize();
        if(path.isAbsolute()||path.startsWith("..")||path.toString().length()==0
                ||!path.toString().replace(java.io.File.separatorChar,'/').equals(value))throw new IOException("The source snapshot contains an invalid logical path");
        return value;
    }

    private static byte[] readBounded(InputStream input,long limit) throws IOException {
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[8192];int read;long total=0;
        while((read=input.read(buffer))>=0){total+=read;if(total>limit)throw new IOException("The source snapshot manifest is oversized");output.write(buffer,0,read);}
        return output.toByteArray();
    }

    private static String digestFile(Path path) throws IOException {
        MessageDigest digest=sha256();
        try(InputStream input=Files.newInputStream(path)) {
            byte[] buffer=new byte[8192];int read;while((read=input.read(buffer))>=0)digest.update(buffer,0,read);
        }
        return hex(digest.digest());
    }

    private static String digest(byte[] bytes) { return hex(sha256().digest(bytes)); }

    private static MessageDigest sha256() {
        try{return MessageDigest.getInstance("SHA-256");}catch(Exception unavailable){throw new IllegalStateException(unavailable);}
    }
    private static String hex(byte[] bytes) {
        StringBuilder result=new StringBuilder(bytes.length*2);for(byte value:bytes)result.append(String.format("%02x",value&255));return result.toString();
    }
    private static byte[] hexBytes(String value) {
        byte[] result=new byte[value.length()/2];for(int i=0;i<result.length;i++)result[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return result;
    }

    private static void setPrivateDirectory(Path directory) {
        try { Files.setPosixFilePermissions(directory,EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE)); }
        catch(Exception ignored) { }
    }

    private static void deleteTree(Path root) {
        if(root==null||!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        try { Files.walkFileTree(root,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes)throws IOException {Files.deleteIfExists(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path directory,IOException error)throws IOException {if(error!=null)throw error;Files.deleteIfExists(directory);return FileVisitResult.CONTINUE;}
        }); } catch(IOException ignored) { }
    }

    @Override public void close() {
        closed=true;
        WatchService watcher=mutationWatcher;mutationWatcher=null;
        if(watcher!=null)try{watcher.close();}catch(IOException ignored){}
        Thread monitor=mutationMonitor;mutationMonitor=null;
        if(monitor!=null)monitor.interrupt();
        if(planRegistration!=null)try{planRegistration.close();}catch(Exception ignored){}
        thawTree(directory);
        deleteTree(directory);
    }
}
