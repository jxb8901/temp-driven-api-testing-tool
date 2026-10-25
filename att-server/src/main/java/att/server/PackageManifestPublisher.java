package att.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One bounded, revision-checked source workflow for the canonical Package Manifest. */
final class PackageManifestPublisher implements AutoCloseable {
    static final int MAX_SOURCE_BYTES=262144;
    interface Access { Path root(String packageId,String actor) throws Exception; }
    interface Validator { Map<String,Object> validate(String packageId,String source) throws Exception; }
    interface Audit { void record(String actor,String action,String packageId,String detail) throws Exception; }
    private record Draft(String id,String packageId,String actor,Path root,String source,String beforeDigest,
                         Instant expires,Map<String,Object> validation,boolean reviewed) { }
    private final PackageConfigurationPolicy policy;
    private final Path historyRoot;
    private final Clock clock;
    private final Access access;
    private final Validator validator;
    private final Audit audit;
    private final Map<String,Draft> drafts=new LinkedHashMap<>();

    PackageManifestPublisher(Path data,PackageConfigurationPolicy policy,Clock clock,Access access,Validator validator,Audit audit) throws IOException {
        this.policy=policy;this.clock=clock;this.access=access;this.validator=validator;this.audit=audit;
        this.historyRoot=data.resolve("package-source-history");
        if(policy.sourceEditingEnabled)privateDirectory(historyRoot);
    }

    synchronized Map<String,Object> stage(String packageId,String actor,String source,String expectedDigest) throws Exception {
        Path root=authorizedRoot(packageId,actor);expire();
        if(source==null||source.getBytes(StandardCharsets.UTF_8).length>MAX_SOURCE_BYTES||source.indexOf('\0')>=0)
            throw new IllegalArgumentException("Manifest source exceeds the UTF-8 source limit or is unavailable");
        requireDigest(expectedDigest);
        if(!expectedDigest.equals(currentDigest(root)))throw new Conflict();
        if(drafts.size()>=policy.maxDrafts||drafts.values().stream().filter(d->d.actor.equals(actor)).count()>=policy.maxDraftsPerUser)
            throw new Capacity();
        Draft draft=new Draft("M"+UUID.randomUUID().toString().replace("-","").toUpperCase(),packageId,actor,root,source,expectedDigest,
                clock.instant().plusSeconds(policy.draftTtlSeconds),null,false);
        drafts.put(draft.id,draft);audit.record(actor,"PACKAGE_MANIFEST_STAGE",packageId,expectedDigest);
        return view(draft);
    }

    synchronized Map<String,Object> validate(String packageId,String actor,String id) throws Exception {
        Draft old=draft(packageId,actor,id);unchanged(old);
        Map<String,Object> validated=validator.validate(packageId,old.source);
        if(!Boolean.TRUE.equals(validated.get("valid")))throw new IllegalArgumentException("Manifest validation did not succeed");
        if(!digest(old.source.getBytes(StandardCharsets.UTF_8)).equals(validated.get("manifestDigest")))throw new Conflict();
        requireDigest(String.valueOf(validated.get("validationRevision")));
        unchanged(old);
        Draft next=new Draft(old.id,old.packageId,old.actor,old.root,old.source,old.beforeDigest,old.expires,Map.copyOf(validated),false);
        drafts.put(id,next);audit.record(actor,"PACKAGE_MANIFEST_VALIDATE",packageId,String.valueOf(validated.get("validationRevision")));
        return view(next);
    }

    synchronized Map<String,Object> review(String packageId,String actor,String id) throws Exception {
        Draft old=draft(packageId,actor,id);unchanged(old);
        if(old.validation==null)throw new IllegalArgumentException("Validate the Manifest draft before review");
        Draft next=new Draft(old.id,old.packageId,old.actor,old.root,old.source,old.beforeDigest,old.expires,old.validation,true);
        drafts.put(id,next);audit.record(actor,"PACKAGE_MANIFEST_REVIEW",packageId,id);return view(next);
    }

    synchronized Map<String,Object> publish(String packageId,String actor,String id) throws Exception {
        Draft draft=draft(packageId,actor,id);unchanged(draft);
        if(!draft.reviewed||draft.validation==null)throw new IllegalArgumentException("Validate and review the Manifest draft before publication");
        Map<String,Object> fresh=validator.validate(packageId,draft.source);
        if(!Boolean.TRUE.equals(fresh.get("valid"))||!draft.validation.get("validationRevision").equals(fresh.get("validationRevision")))throw new Conflict();
        unchanged(draft); // Recheck edit authorization and canonical registration after Worker validation.
        Path root=authorizedRoot(packageId,actor);
        Path history=historyDirectory(packageId);
        Path live=root.resolve("package.yaml");
        byte[] before=readOptional(live);
        if(!digest(before).equals(draft.beforeDigest))throw new Conflict();
        byte[] after=draft.source.getBytes(StandardCharsets.UTF_8);
        String afterDigest=digest(after);
        saveHistory(history,draft.beforeDigest,before);
        saveHistory(history,afterDigest,after);
        pruneHistory(history,draft.beforeDigest,afterDigest);
        // Auditing before replacement makes an accepted publication recoverable even if the process exits after rename.
        audit.record(actor,"PACKAGE_MANIFEST_PUBLISH_INTENT",packageId,draft.beforeDigest+" -> "+afterDigest);
        atomicWrite(live,after);
        drafts.remove(id);
        boolean auditConfirmed=true;
        try {audit.record(actor,"PACKAGE_MANIFEST_PUBLISH",packageId,draft.beforeDigest+" -> "+afterDigest);}
        catch(Exception pending) {auditConfirmed=false;} // The durable intent records the committed revision; never report a completed rename as a failed publication.
        Map<String,Object> result=new LinkedHashMap<>();result.put("auditConfirmed",auditConfirmed);result.put("published",true);result.put("packageId",packageId);
        result.put("beforeDigest",draft.beforeDigest);result.put("manifestDigest",afterDigest);result.put("refreshRequired",true);
        result.put("environment",fresh.get("environment"));result.put("configurationIdentity",fresh.get("configurationIdentity"));
        return result;
    }

    synchronized List<Map<String,Object>> history(String packageId,String actor) throws Exception {
        authorizedRoot(packageId,actor);Path directory=historyDirectory(packageId);
        List<Map<String,Object>> result=new ArrayList<>();
        try(var files=Files.list(directory)) {
            for(Path file:files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.yaml")).toList()) {
                if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Source history is unavailable");
                result.add(Map.of("manifestDigest",file.getFileName().toString().substring(0,64),"modifiedAt",Files.getLastModifiedTime(file).toInstant().toString()));
            }
        }
        result.sort(Comparator.<Map<String,Object>,Instant>comparing(item->Instant.parse((String)item.get("modifiedAt")))
                .reversed().thenComparing(item->(String)item.get("manifestDigest")));
        return result;
    }

    synchronized Map<String,Object> rollbackDraft(String packageId,String actor,String revision,String expectedDigest) throws Exception {
        authorizedRoot(packageId,actor);requireDigest(revision);
        Path history=historyDirectory(packageId).resolve(revision+".yaml");
        if(!Files.isRegularFile(history,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(history))throw new Missing();
        byte[] source=readOptional(history);
        if(!digest(source).equals(revision))throw new IOException("Source history failed its integrity check");
        // An absent optional Manifest has the same declared defaults as a canonical empty Manifest.
        // Keep publication an atomic file replacement, and expose this normalization in review.
        boolean reset=source.length==0;
        Map<String,Object> result=stage(packageId,actor,reset?"schemaVersion: att-package/v1\n":new String(source,StandardCharsets.UTF_8),expectedDigest);
        result.put("rollbackRevision",revision);result.put("resetToEmptyManifest",reset);
        audit.record(actor,"PACKAGE_MANIFEST_ROLLBACK_STAGE",packageId,revision);return result;
    }

    synchronized void discard(String packageId,String actor,String id) throws Exception {draft(packageId,actor,id);audit.record(actor,"PACKAGE_MANIFEST_DISCARD",packageId,id);drafts.remove(id);}
    private Draft draft(String packageId,String actor,String id) throws Exception {
        authorizedRoot(packageId,actor);expire();Draft draft=drafts.get(id);
        if(draft==null||!draft.packageId.equals(packageId)||!draft.actor.equals(actor))throw new Missing();return draft;
    }
    private Path authorizedRoot(String packageId,String actor) throws Exception {
        if(!policy.sourceEditingEnabled)throw new Denied();
        Path root=access.root(packageId,actor);
        if(root==null||Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!root.equals(root.toRealPath()))throw new Conflict();
        return root;
    }
    private void unchanged(Draft draft) throws Exception {
        if(!draft.root.equals(authorizedRoot(draft.packageId,draft.actor))||!draft.beforeDigest.equals(currentDigest(draft.root)))throw new Conflict();
    }
    private static String currentDigest(Path root) throws Exception {return digest(readOptional(root.resolve("package.yaml")));}
    private static byte[] readOptional(Path file) throws IOException {
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new byte[0];
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(file)||Files.size(file)>MAX_SOURCE_BYTES)
            throw new IOException("Package Manifest source is unsafe or exceeds its limit");
        try(var input=Files.newInputStream(file)){byte[] value=input.readNBytes(MAX_SOURCE_BYTES+1);if(value.length>MAX_SOURCE_BYTES)throw new IOException("Manifest source exceeds its limit");return value;}
    }
    private Map<String,Object> view(Draft draft) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("draftId",draft.id);result.put("packageId",draft.packageId);
        result.put("expectedManifestDigest",draft.beforeDigest);result.put("expiresAt",draft.expires.toString());
        result.put("state",draft.reviewed?"reviewed":draft.validation==null?"draft":"validated");
        if(draft.validation!=null)result.put("validation",draft.validation);
        return result; // Raw candidate source stays private and is never included in a response.
    }
    private void expire(){drafts.values().removeIf(d->!d.expires.isAfter(clock.instant()));}
    private Path historyDirectory(String id) throws Exception {
        Path directory=historyRoot.resolve(digest(id.getBytes(StandardCharsets.UTF_8)));privateDirectory(directory);return directory;
    }
    private void saveHistory(Path history,String revision,byte[] source) throws Exception {
        Path target=history.resolve(revision+".yaml");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            if(!digest(readOptional(target)).equals(revision))throw new IOException("Source history failed its integrity check");
        } else atomicWrite(target,source);
    }
    private void pruneHistory(Path directory,String before,String after) throws IOException {
        try(var files=Files.list(directory)) {
            List<Path> entries=files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.yaml")).sorted(Comparator.comparingLong(p->{try{return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return 0L;}})).toList();
            int remaining=entries.size();for(Path path:entries)if(remaining>policy.maxHistory&&!path.getFileName().toString().startsWith(before)&&!path.getFileName().toString().startsWith(after)){Files.delete(path);remaining--;}
        }
    }
    private static void privateDirectory(Path directory) throws IOException {
        if(Files.exists(directory,LinkOption.NOFOLLOW_LINKS)&&Files.isSymbolicLink(directory))throw new IOException("Private source history is unavailable");
        Files.createDirectories(directory);try{Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
    }
    private static void atomicWrite(Path destination,byte[] bytes) throws IOException {
        Path temporary=Files.createTempFile(destination.getParent(),".att-manifest-",".tmp");
        try {
            try{Files.setPosixFilePermissions(temporary,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temporary);}
    }
    static String digest(byte[] bytes) throws Exception {return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void requireDigest(String value){if(value==null||!value.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("A valid expected Manifest digest is required");}
    @Override public synchronized void close(){drafts.clear();}
    static final class Conflict extends RuntimeException { }
    static final class Denied extends RuntimeException { }
    static final class Missing extends RuntimeException { }
    static final class Capacity extends RuntimeException { }
}
