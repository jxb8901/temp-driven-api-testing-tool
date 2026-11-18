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
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

/** One bounded, revision-checked source workflow for the canonical Package Manifest. */
final class PackageManifestPublisher implements AutoCloseable {
    static final int MAX_SOURCE_BYTES=262144;
    interface Access { Path root(String packageId,String actor) throws Exception; }
    interface Validator { Map<String,Object> validate(String packageId,String source) throws Exception; }
    /**
     * Validates a staged resource as the authenticated actor against the complete Package/Runtime definition state.
     * Returns valid, resourceDigest (SHA-256 of candidate UTF-8 bytes), and a validationRevision
     * that changes when any definition capable of affecting this candidate changes.
     */
    interface ResourceValidator {
        Map<String,Object> validate(String packageId,String actor,String relativePath,String environment,String source) throws Exception;
        default Map<String,Object> validateCandidate(String packageId,String actor,String relativePath,String environment,
                                                     String source,String candidateManifestSource,
                                                     String expectedManifestDigest) throws Exception {
            if(candidateManifestSource!=null||expectedManifestDigest!=null)
                throw new IllegalArgumentException("Candidate Package Manifest validation is unavailable");
            return validate(packageId,actor,relativePath,environment,source);
        }
        default Map<String,Object> validate(String packageId,String relativePath,String environment,String source) throws Exception {
            return validate(packageId,null,relativePath,environment,source);
        }
        default Map<String,Object> validate(String packageId,String relativePath,String source) throws Exception {
            return validate(packageId,null,relativePath,null,source);
        }
    }
    interface Audit {
        void record(String actor,String action,String packageId,String detail) throws Exception;
        default void recordCommitted(SourceFileTransaction.AuditRecord record) throws Exception {
            record(record.actor,record.action,record.scopeId,"transactionId="+record.transactionId
                    +" before="+record.beforeRevision+" after="+record.afterRevision+" outcome="+record.outcome
                    +" requestId="+String.valueOf(record.requestId)+" resources="+String.join(",",record.resources));
        }
    }
    private record Draft(String id,String packageId,String actor,Path root,Path registeredRoot,String resourcePath,String environment,String source,String beforeDigest,
                         String packageSourceRevision,String candidatePackageSourceRevision,
                         String candidateManifestSource,String expectedManifestDigest,
                         Instant expires,Map<String,Object> validation,boolean reviewed) {
        private Draft(String id,String packageId,String actor,Path root,String resourcePath,String environment,String source,String beforeDigest,
                      Instant expires,Map<String,Object> validation,boolean reviewed) {
            this(id,packageId,actor,root,null,resourcePath,environment,source,beforeDigest,null,null,null,null,expires,validation,reviewed);
        }
    }
    private final PackageConfigurationPolicy policy;
    private final SourceFileTransaction sourceTransaction;
    private final Path historyRoot;
    private final Clock clock;
    private final Access access;
    private final Validator validator;
    private final ResourceValidator resourceValidator;
    private final Audit audit;
    private final Map<String,Draft> drafts=new LinkedHashMap<>();

    PackageManifestPublisher(Path data,PackageConfigurationPolicy policy,Clock clock,Access access,Validator validator,Audit audit) throws IOException {
        this(data,policy,clock,access,validator,null,audit,new SourceFileTransaction(data,new SourcePublicationBarrier()));
    }

    PackageManifestPublisher(Path data,PackageConfigurationPolicy policy,Clock clock,Access access,Validator validator,
                             ResourceValidator resourceValidator,Audit audit) throws IOException {
        this(data,policy,clock,access,validator,resourceValidator,audit,new SourceFileTransaction(data,new SourcePublicationBarrier()));
    }

    PackageManifestPublisher(Path data,PackageConfigurationPolicy policy,Clock clock,Access access,Validator validator,Audit audit,
                             SourceFileTransaction sourceTransaction) throws IOException {
        this(data,policy,clock,access,validator,null,audit,sourceTransaction);
    }

    PackageManifestPublisher(Path data,PackageConfigurationPolicy policy,Clock clock,Access access,Validator validator,
                             ResourceValidator resourceValidator,Audit audit,SourceFileTransaction sourceTransaction) throws IOException {
        this.policy=policy;this.clock=clock;this.access=access;this.validator=validator;
        this.resourceValidator=resourceValidator;this.audit=audit;
        if(sourceTransaction==null)throw new IllegalArgumentException("A source transaction is required");
        this.sourceTransaction=sourceTransaction;
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
        Draft draft=new Draft("M"+UUID.randomUUID().toString().replace("-","").toUpperCase(),packageId,actor,root,null,null,source,expectedDigest,
                clock.instant().plusSeconds(policy.draftTtlSeconds),null,false);
        drafts.put(draft.id,draft);audit.record(actor,"PACKAGE_MANIFEST_STAGE",packageId,expectedDigest);
        return view(draft);
    }

    synchronized Map<String,Object> validate(String packageId,String actor,String id) throws Exception {
        Draft old=manifestDraft(packageId,actor,id);unchanged(old);
        Map<String,Object> validated=validator.validate(packageId,old.source);
        if(!Boolean.TRUE.equals(validated.get("valid")))throw new IllegalArgumentException("Manifest validation did not succeed");
        if(!digest(old.source.getBytes(StandardCharsets.UTF_8)).equals(validated.get("manifestDigest")))throw new Conflict();
        requireDigest(String.valueOf(validated.get("validationRevision")));
        unchanged(old);
        Draft next=new Draft(old.id,old.packageId,old.actor,old.root,null,null,old.source,old.beforeDigest,old.expires,Map.copyOf(validated),false);
        drafts.put(id,next);audit.record(actor,"PACKAGE_MANIFEST_VALIDATE",packageId,String.valueOf(validated.get("validationRevision")));
        return view(next);
    }

    synchronized Map<String,Object> review(String packageId,String actor,String id) throws Exception {
        Draft old=manifestDraft(packageId,actor,id);unchanged(old);
        if(old.validation==null)throw new IllegalArgumentException("Validate the Manifest draft before review");
        Draft next=new Draft(old.id,old.packageId,old.actor,old.root,null,null,old.source,old.beforeDigest,old.expires,old.validation,true);
        drafts.put(id,next);audit.record(actor,"PACKAGE_MANIFEST_REVIEW",packageId,id);return view(next);
    }

    synchronized Map<String,Object> publish(String packageId,String actor,String id) throws Exception {
        return publish(packageId,actor,id,null);
    }
    synchronized Map<String,Object> publish(String packageId,String actor,String id,String requestId) throws Exception {
        Draft draft=manifestDraft(packageId,actor,id);unchanged(draft);
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
        unchanged(draft); // Recheck role, registered canonical root, and source immediately before the commit.
        Path commitRoot=authorizedRoot(packageId,actor);
        if(!root.equals(commitRoot)||!live.equals(commitRoot.resolve("package.yaml")))throw new Conflict();
        boolean manifestExists=Files.exists(commitRoot.resolve("package.yaml"),LinkOption.NOFOLLOW_LINKS);
        Map<String,SourceFileTransaction.Write> writes=new LinkedHashMap<>();
        writes.put("package.yaml",new SourceFileTransaction.Write("package.yaml",after,manifestExists?draft.beforeDigest:null));
        SourceFileTransaction.CommitResult commitResult;
        try {
            commitResult=sourceTransaction.commitWithAudit("PACKAGE_SOURCE",packageId,commitRoot,actor,draft.beforeDigest,
                    afterDigest,writes,()->{
                unchanged(draft);
                Path current=authorizedRoot(packageId,actor);
                if(!commitRoot.equals(current))throw new Conflict();
            },requestId,List.of("package.yaml"),"PACKAGE_MANIFEST_PUBLISH",audit::recordCommitted);
        } catch(SourceFileTransaction.RevisionConflict changed) {
            throw new Conflict();
        }
        drafts.remove(id);
        boolean auditConfirmed=commitResult.auditConfirmed;
        Map<String,Object> result=new LinkedHashMap<>();result.put("auditConfirmed",auditConfirmed);result.put("published",true);result.put("packageId",packageId);
        result.put("beforeDigest",draft.beforeDigest);result.put("manifestDigest",afterDigest);result.put("refreshRequired",true);
        result.put("environment",fresh.get("environment"));result.put("configurationIdentity",fresh.get("configurationIdentity"));
        return result;
    }

    synchronized Map<String,Object> stageResource(String packageId,String actor,String relativePath,String source,
                                                  String expectedResourceDigest) throws Exception {
        return stageResource(packageId,actor,relativePath,null,source,expectedResourceDigest);
    }

    synchronized Map<String,Object> stageResource(String packageId,String actor,String relativePath,String environment,String source,
                                                  String expectedResourceDigest) throws Exception {
        return stageResource(packageId,actor,relativePath,environment,source,expectedResourceDigest,null,null);
    }

    synchronized Map<String,Object> stageResource(String packageId,String actor,String relativePath,String environment,String source,
                                                  String expectedResourceDigest,String candidateManifestSource,
                                                  String expectedManifestDigest) throws Exception {
        requireResourceValidator();
        Path root=authorizedRoot(packageId,actor);expire();
        String canonicalPath=resourceRelativePath(root,relativePath);
        if(environment!=null&&!environment.matches("[A-Za-z][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Package resource Environment is invalid");
        validateSource(source,"Package resource");requireDigest(expectedResourceDigest);
        boolean manifestAddition=candidateManifestSource!=null||expectedManifestDigest!=null;
        String beforeResourceDigest=expectedResourceDigest;
        if(manifestAddition) {
            if(candidateManifestSource==null||expectedManifestDigest==null)
                throw new IllegalArgumentException("candidateManifestSource and expectedManifestDigest must be supplied together");
            validateSource(candidateManifestSource,"Candidate Package Manifest");requireDigest(expectedManifestDigest);
            if(!expectedManifestDigest.equals(currentDigest(root)))throw new Conflict();
            String emptyDigest=digest(new byte[0]);
            String candidateDigest=digest(source.getBytes(StandardCharsets.UTF_8));
            if(!emptyDigest.equals(expectedResourceDigest))throw new Conflict();
            Path target=resourcePath(root,canonicalPath);
            boolean targetExists=Files.exists(target,LinkOption.NOFOLLOW_LINKS);
            String currentResource=currentResourceDigest(root,canonicalPath);
            if(targetExists) {
                // A prior process may have written this exact inert file before failing to activate
                // its inventory entry. Permit only that digest-fenced recovery case.
                if(!candidateDigest.equals(currentResource))throw new Conflict();
                beforeResourceDigest=currentResource;
            } else {
                if(!emptyDigest.equals(currentResource))throw new Conflict();
                beforeResourceDigest=emptyDigest;
            }
        }
        if(!manifestAddition&&!expectedResourceDigest.equals(currentResourceDigest(root,canonicalPath)))throw new Conflict();
        enforceCapacity(actor);
        String packageSourceRevision=currentPackageSourceRevision(root);
        String candidatePackageSourceRevision=packageSourceRevision(root,canonicalPath,source.getBytes(StandardCharsets.UTF_8));
        Draft draft=new Draft("R"+UUID.randomUUID().toString().replace("-","").toUpperCase(),packageId,actor,root,registeredRoot(packageId),
                canonicalPath,environment,source,beforeResourceDigest,packageSourceRevision,candidatePackageSourceRevision,
                candidateManifestSource,expectedManifestDigest,
                clock.instant().plusSeconds(policy.draftTtlSeconds),null,false);
        drafts.put(draft.id,draft);audit.record(actor,"PKG_SUT_METRIC_RESOURCE_STAGE",packageId,canonicalPath+":"+expectedResourceDigest);
        return view(draft);
    }

    synchronized Map<String,Object> validateResource(String packageId,String actor,String id) throws Exception {
        Draft old=resourceDraft(packageId,actor,id);
        if(old.candidateManifestSource==null)unchanged(old);
        else unchangedForCreatedResource(old,digest(old.source.getBytes(StandardCharsets.UTF_8)));
        Map<String,Object> validated=resourceValidator.validateCandidate(packageId,old.actor,old.resourcePath,old.environment,
                old.source,old.candidateManifestSource,old.expectedManifestDigest);
        if(!Boolean.TRUE.equals(validated.get("valid")))throw new IllegalArgumentException("Package resource validation did not succeed");
        String candidateDigest=digest(old.source.getBytes(StandardCharsets.UTF_8));
        if(!candidateDigest.equals(validated.get("resourceDigest")))throw new Conflict();
        if(old.candidateManifestSource!=null) {
            String candidateManifestDigest=digest(old.candidateManifestSource.getBytes(StandardCharsets.UTF_8));
            if(!candidateManifestDigest.equals(validated.get("candidateManifestDigest")))throw new Conflict();
        }
        requireDigest(String.valueOf(validated.get("validationRevision")));
        if(old.candidateManifestSource==null)unchanged(old);
        else unchangedForCreatedResource(old,candidateDigest);
        Draft next=new Draft(old.id,old.packageId,old.actor,old.root,old.registeredRoot,old.resourcePath,old.environment,old.source,old.beforeDigest,
                old.packageSourceRevision,old.candidatePackageSourceRevision,old.candidateManifestSource,
                old.expectedManifestDigest,old.expires,Map.copyOf(validated),false);
        drafts.put(id,next);audit.record(actor,"PKG_SUT_METRIC_RESOURCE_VALIDATE",packageId,old.resourcePath+":"+validated.get("validationRevision"));
        return view(next);
    }

    synchronized Map<String,Object> reviewResource(String packageId,String actor,String id) throws Exception {
        Draft old=resourceDraft(packageId,actor,id);
        if(old.candidateManifestSource==null)unchanged(old);
        else unchangedForCreatedResource(old,digest(old.source.getBytes(StandardCharsets.UTF_8)));
        if(old.validation==null)throw new IllegalArgumentException("Validate the Package resource draft before review");
        Draft next=new Draft(old.id,old.packageId,old.actor,old.root,old.registeredRoot,old.resourcePath,old.environment,old.source,old.beforeDigest,
                old.packageSourceRevision,old.candidatePackageSourceRevision,old.candidateManifestSource,
                old.expectedManifestDigest,old.expires,old.validation,true);
        drafts.put(id,next);audit.record(actor,"PKG_SUT_METRIC_RESOURCE_REVIEW",packageId,old.resourcePath+":"+id);
        return view(next);
    }

    synchronized Map<String,Object> publishResource(String packageId,String actor,String id) throws Exception {
        Draft draft=resourceDraft(packageId,actor,id);
        if(draft.candidateManifestSource==null)unchanged(draft);
        else unchangedForCreatedResource(draft,digest(draft.source.getBytes(StandardCharsets.UTF_8)));
        if(!draft.reviewed||draft.validation==null)throw new IllegalArgumentException("Validate and review the Package resource draft before publication");
        if(draft.candidateManifestSource!=null)return publishCreatedResource(draft,actor);
        Map<String,Object> fresh=resourceValidator.validateCandidate(packageId,draft.actor,draft.resourcePath,draft.environment,
                draft.source,null,null);
        String candidateDigest=digest(draft.source.getBytes(StandardCharsets.UTF_8));
        if(!Boolean.TRUE.equals(fresh.get("valid"))||!candidateDigest.equals(fresh.get("resourceDigest"))
                ||!draft.validation.get("validationRevision").equals(fresh.get("validationRevision")))throw new Conflict();
        unchanged(draft); // Recheck edit authorization, path safety and the live resource revision after validation.
        Path root=authorizedRoot(packageId,actor);
        Path live=resourcePath(root,draft.resourcePath);
        byte[] before=readResourceOptional(root,draft.resourcePath);
        if(!digest(before).equals(draft.beforeDigest))throw new Conflict();
        byte[] after=draft.source.getBytes(StandardCharsets.UTF_8);
        String afterDigest=digest(after);
        Path history=resourceHistoryDirectory(packageId,draft.resourcePath);
        saveHistory(history,draft.beforeDigest,before);
        saveHistory(history,afterDigest,after);
        pruneHistory(history,draft.beforeDigest,afterDigest);
        audit.record(actor,"PKG_SUT_METRIC_PUBLISH_INTENT",packageId,draft.resourcePath+":"+draft.beforeDigest+" -> "+afterDigest);
        Map<String,SourceFileTransaction.Write> writes=new LinkedHashMap<>();
        boolean resourceExists=Files.exists(live,LinkOption.NOFOLLOW_LINKS);
        writes.put(draft.resourcePath,new SourceFileTransaction.Write(draft.resourcePath,after,
                resourceExists?draft.beforeDigest:null));
        SourceFileTransaction.CommitResult commitResult;
        try {
            commitResult=sourceTransaction.commitWithAudit(ChangeSetPublisher.PACKAGE_SOURCE,packageId,root,actor,
                    draft.packageSourceRevision,draft.candidatePackageSourceRevision,writes,()->{
                if(!root.equals(authorizedRoot(packageId,actor)))throw new Conflict();
                unchanged(draft);
                Map<String,Object> commitValidation=resourceValidator.validateCandidate(packageId,draft.actor,
                        draft.resourcePath,draft.environment,draft.source,null,null);
                if(!Boolean.TRUE.equals(commitValidation.get("valid"))
                        ||!candidateDigest.equals(commitValidation.get("resourceDigest"))
                        ||!draft.validation.get("validationRevision").equals(commitValidation.get("validationRevision")))
                    throw new Conflict();
                unchanged(draft);
            },null,List.of(draft.resourcePath),"PKG_SUT_METRIC_RESOURCE_PUBLISH",audit::recordCommitted);
        } catch(SourceFileTransaction.RevisionConflict changed) {
            throw new Conflict();
        }
        drafts.remove(id);
        Map<String,Object> result=new LinkedHashMap<>();result.put("auditConfirmed",commitResult.auditConfirmed);result.put("published",true);
        result.put("packageId",packageId);result.put("resourcePath",draft.resourcePath);
        result.put("beforeResourceDigest",draft.beforeDigest);result.put("resourceDigest",afterDigest);result.put("refreshRequired",true);
        result.put("validationRevision",fresh.get("validationRevision"));
        result.put("environment",fresh.get("environment"));result.put("configurationIdentity",fresh.get("configurationIdentity"));
        return result;
    }

    private Map<String,Object> publishCreatedResource(Draft draft,String actor) throws Exception {
        String resourceDigest=digest(draft.source.getBytes(StandardCharsets.UTF_8));
        byte[] resourceBytes=draft.source.getBytes(StandardCharsets.UTF_8);
        byte[] manifestBytes=draft.candidateManifestSource.getBytes(StandardCharsets.UTF_8);
        String candidateManifestDigest=digest(manifestBytes);
        unchangedForCreatedResource(draft,resourceDigest);
        Path root=authorizedRoot(draft.packageId,actor);
        Path liveResource=resourcePath(root,draft.resourcePath);
        byte[] beforeResource=readResourceOptional(root,draft.resourcePath);
        if(!draft.beforeDigest.equals(digest(beforeResource))
                &&!resourceDigest.equals(digest(beforeResource)))throw new Conflict();
        Path liveManifest=root.resolve("package.yaml");
        byte[] beforeManifest=readOptional(liveManifest);
        if(!draft.expectedManifestDigest.equals(digest(beforeManifest)))throw new Conflict();

        Path resourceHistory=resourceHistoryDirectory(draft.packageId,draft.resourcePath);
        Path manifestHistory=historyDirectory(draft.packageId);
        saveHistory(resourceHistory,draft.beforeDigest,draft.beforeDigest.equals(digest(beforeResource))?beforeResource:new byte[0]);
        saveHistory(resourceHistory,resourceDigest,resourceBytes);
        saveHistory(manifestHistory,draft.expectedManifestDigest,beforeManifest);
        saveHistory(manifestHistory,candidateManifestDigest,manifestBytes);
        pruneHistory(resourceHistory,draft.beforeDigest,resourceDigest);
        pruneHistory(manifestHistory,draft.expectedManifestDigest,candidateManifestDigest);

        String intent=draft.resourcePath+":"+draft.beforeDigest+" -> "+resourceDigest
                +"; package.yaml:"+draft.expectedManifestDigest+" -> "+candidateManifestDigest;
        audit.record(actor,"PKG_SUT_METRIC_PUBLISH_INTENT",draft.packageId,intent);
        Map<String,Object> beforeWrite=resourceValidator.validateCandidate(draft.packageId,draft.actor,draft.resourcePath,
                draft.environment,draft.source,draft.candidateManifestSource,draft.expectedManifestDigest);
        verifyCreatedResourceValidation(draft,beforeWrite,resourceDigest,candidateManifestDigest);
        unchangedForCreatedResource(draft,resourceDigest);

        byte[] currentResource=readResourceOptional(root,draft.resourcePath);
        boolean resourceAuditConfirmed=true;
        if(draft.beforeDigest.equals(digest(currentResource))) {
            boolean resourceExists=Files.exists(liveResource,LinkOption.NOFOLLOW_LINKS);
            Map<String,SourceFileTransaction.Write> resourceWrites=new LinkedHashMap<>();
            resourceWrites.put(draft.resourcePath,new SourceFileTransaction.Write(draft.resourcePath,resourceBytes,
                    resourceExists?draft.beforeDigest:null));
            try {
                SourceFileTransaction.CommitResult resourceCommit=sourceTransaction.commitWithAudit(
                        ChangeSetPublisher.PACKAGE_SOURCE,draft.packageId,root,actor,draft.packageSourceRevision,
                        draft.candidatePackageSourceRevision,resourceWrites,()->{
                    if(!root.equals(authorizedRoot(draft.packageId,actor)))throw new Conflict();
                    unchangedForCreatedResource(draft,resourceDigest);
                    Map<String,Object> latest=resourceValidator.validateCandidate(draft.packageId,draft.actor,
                            draft.resourcePath,draft.environment,draft.source,draft.candidateManifestSource,
                            draft.expectedManifestDigest);
                    verifyCreatedResourceValidation(draft,latest,resourceDigest,candidateManifestDigest);
                    unchangedForCreatedResource(draft,resourceDigest);
                },null,List.of(draft.resourcePath),"PKG_SUT_METRIC_RESOURCE_PREPARE",audit::recordCommitted);
                resourceAuditConfirmed=resourceCommit.auditConfirmed;
            } catch(SourceFileTransaction.RevisionConflict changed) {
                throw new Conflict();
            }
        } else if(!resourceDigest.equals(digest(currentResource)))throw new Conflict();

        Path activeRoot=authorizedRoot(draft.packageId,actor);
        if(!draft.registeredRoot.equals(registeredRoot(draft.packageId))
                ||!draft.expectedManifestDigest.equals(currentDigest(activeRoot))
                ||!resourceDigest.equals(currentResourceDigest(activeRoot,draft.resourcePath)))throw new Conflict();
        Map<String,Object> commitValidation=resourceValidator.validateCandidate(draft.packageId,draft.actor,draft.resourcePath,
                draft.environment,draft.source,draft.candidateManifestSource,draft.expectedManifestDigest);
        verifyCreatedResourceValidation(draft,commitValidation,resourceDigest,candidateManifestDigest);
        unchangedForCreatedResource(draft,resourceDigest);
        audit.record(actor,"PKG_SUT_MANIFEST_INTENT",draft.packageId,intent);
        // The resource stays unlisted until this last atomic replacement activates its inventory path.
        Map<String,Object> finalValidation=resourceValidator.validateCandidate(draft.packageId,draft.actor,draft.resourcePath,
                draft.environment,draft.source,draft.candidateManifestSource,draft.expectedManifestDigest);
        verifyCreatedResourceValidation(draft,finalValidation,resourceDigest,candidateManifestDigest);
        unchangedForCreatedResource(draft,resourceDigest);
        if(!draft.expectedManifestDigest.equals(currentDigest(activeRoot)))throw new Conflict();
        boolean manifestExists=Files.exists(activeRoot.resolve("package.yaml"),LinkOption.NOFOLLOW_LINKS);
        Map<String,SourceFileTransaction.Write> manifestWrites=new LinkedHashMap<>();
        manifestWrites.put("package.yaml",new SourceFileTransaction.Write("package.yaml",manifestBytes,
                manifestExists?draft.expectedManifestDigest:null));
        SourceFileTransaction.CommitResult manifestCommit;
        try {
            manifestCommit=sourceTransaction.commitWithAudit(ChangeSetPublisher.PACKAGE_SOURCE,draft.packageId,activeRoot,actor,
                    draft.expectedManifestDigest,candidateManifestDigest,manifestWrites,()->{
                if(!activeRoot.equals(authorizedRoot(draft.packageId,actor))
                        ||!draft.registeredRoot.equals(registeredRoot(draft.packageId)))throw new Conflict();
                unchangedForCreatedResource(draft,resourceDigest);
                if(!draft.expectedManifestDigest.equals(currentDigest(activeRoot)))throw new Conflict();
                Map<String,Object> latest=resourceValidator.validateCandidate(draft.packageId,draft.actor,
                        draft.resourcePath,draft.environment,draft.source,draft.candidateManifestSource,
                        draft.expectedManifestDigest);
                verifyCreatedResourceValidation(draft,latest,resourceDigest,candidateManifestDigest);
                unchangedForCreatedResource(draft,resourceDigest);
            },null,List.of("package.yaml"),"PKG_SUT_METRIC_RESOURCE_PUBLISH",audit::recordCommitted);
        } catch(SourceFileTransaction.RevisionConflict changed) {
            throw new Conflict();
        }

        drafts.remove(draft.id);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("auditConfirmed",resourceAuditConfirmed&&manifestCommit.auditConfirmed);
        result.put("published",true);result.put("packageId",draft.packageId);
        result.put("resourcePath",draft.resourcePath);result.put("beforeResourceDigest",draft.beforeDigest);
        result.put("resourceDigest",resourceDigest);result.put("expectedManifestDigest",draft.expectedManifestDigest);
        result.put("manifestDigest",candidateManifestDigest);result.put("candidateManifestDigest",candidateManifestDigest);
        result.put("recoveredExistingResource",!digest(new byte[0]).equals(draft.beforeDigest));
        result.put("candidateResourceDigest",resourceDigest);
        result.put("refreshRequired",true);result.put("validationRevision",finalValidation.get("validationRevision"));
        result.put("environment",finalValidation.get("environment"));
        result.put("configurationIdentity",finalValidation.get("configurationIdentity"));
        result.put("activationMode","resource-then-manifest");
        return result;
    }

    private void verifyCreatedResourceValidation(Draft draft,Map<String,Object> validated,
                                                 String resourceDigest,String candidateManifestDigest) {
        if(!Boolean.TRUE.equals(validated.get("valid"))
                ||!resourceDigest.equals(validated.get("resourceDigest"))
                ||!candidateManifestDigest.equals(validated.get("candidateManifestDigest"))
                ||!draft.validation.get("validationRevision").equals(validated.get("validationRevision")))
            throw new Conflict();
    }

    private void unchangedForCreatedResource(Draft draft,String resourceDigest) throws Exception {
        if(!draft.registeredRoot.equals(registeredRoot(draft.packageId)))throw new Conflict();
        Path activeRoot=authorizedRoot(draft.packageId,draft.actor);
        if(!draft.expectedManifestDigest.equals(currentDigest(activeRoot)))throw new Conflict();
        String currentResource=currentResourceDigest(activeRoot,draft.resourcePath);
        if(!draft.beforeDigest.equals(currentResource)&&!resourceDigest.equals(currentResource))throw new Conflict();
        String expectedPackageRevision=resourceDigest.equals(currentResource)
                ?draft.candidatePackageSourceRevision:draft.packageSourceRevision;
        if(expectedPackageRevision==null||!expectedPackageRevision.equals(currentPackageSourceRevision(activeRoot)))
            throw new Conflict();
    }

    private Path registeredRoot(String packageId) {
        Path root=sourceTransaction.registeredRoot(ChangeSetPublisher.PACKAGE_SOURCE,packageId);
        if(root==null)throw new Conflict();
        return root;
    }

    synchronized List<Map<String,Object>> historyResource(String packageId,String actor,String relativePath) throws Exception {
        Path root=authorizedRoot(packageId,actor);String canonicalPath=resourceRelativePath(root,relativePath);
        Path directory=resourceHistoryDirectory(packageId,canonicalPath);List<Map<String,Object>> result=new ArrayList<>();
        try(var files=Files.list(directory)) {
            for(Path file:files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.yaml")).toList()) {
                if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Source history is unavailable");
                result.add(Map.of("resourceDigest",file.getFileName().toString().substring(0,64),"modifiedAt",Files.getLastModifiedTime(file).toInstant().toString()));
            }
        }
        result.sort(Comparator.<Map<String,Object>,Instant>comparing(item->Instant.parse((String)item.get("modifiedAt")))
                .reversed().thenComparing(item->(String)item.get("resourceDigest")));
        return result;
    }

    synchronized void discardResource(String packageId,String actor,String id) throws Exception {
        Draft old=resourceDraft(packageId,actor,id);audit.record(actor,"PKG_SUT_METRIC_RESOURCE_DISCARD",packageId,old.resourcePath+":"+id);drafts.remove(id);
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
        Map<String,Object> result=stage(packageId,actor,reset?"schemaVersion: att-package/v2\n":new String(source,StandardCharsets.UTF_8),expectedDigest);
        result.put("rollbackRevision",revision);result.put("resetToEmptyManifest",reset);
        audit.record(actor,"PACKAGE_MANIFEST_ROLLBACK_STAGE",packageId,revision);return result;
    }

    synchronized void discard(String packageId,String actor,String id) throws Exception {manifestDraft(packageId,actor,id);audit.record(actor,"PACKAGE_MANIFEST_DISCARD",packageId,id);drafts.remove(id);}
    private Draft draft(String packageId,String actor,String id) throws Exception {
        authorizedRoot(packageId,actor);expire();Draft draft=drafts.get(id);
        if(draft==null||!draft.packageId.equals(packageId)||!draft.actor.equals(actor))throw new Missing();return draft;
    }
    private Draft manifestDraft(String packageId,String actor,String id) throws Exception {
        Draft draft=draft(packageId,actor,id);if(draft.resourcePath!=null)throw new Missing();return draft;
    }
    private Draft resourceDraft(String packageId,String actor,String id) throws Exception {
        Draft draft=draft(packageId,actor,id);if(draft.resourcePath==null)throw new Missing();return draft;
    }
    private void requireResourceValidator(){if(resourceValidator==null)throw new Denied();}
    private static void validateSource(String source,String owner) {
        if(source==null||source.getBytes(StandardCharsets.UTF_8).length>MAX_SOURCE_BYTES||source.indexOf('\0')>=0)
            throw new IllegalArgumentException(owner+" exceeds the UTF-8 source limit or is unavailable");
    }
    private void enforceCapacity(String actor) {
        if(drafts.size()>=policy.maxDrafts||drafts.values().stream().filter(d->d.actor.equals(actor)).count()>=policy.maxDraftsPerUser)
            throw new Capacity();
    }
    private Path authorizedRoot(String packageId,String actor) throws Exception {
        if(!policy.sourceEditingEnabled)throw new Denied();
        Path root=access.root(packageId,actor);
        if(root==null||Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!root.equals(root.toRealPath()))throw new Conflict();
        return root;
    }
    private void unchanged(Draft draft) throws Exception {
        if(!draft.root.equals(authorizedRoot(draft.packageId,draft.actor)))throw new Conflict();
        String current=draft.resourcePath==null?currentDigest(draft.root):currentResourceDigest(draft.root,draft.resourcePath);
        if(!draft.beforeDigest.equals(current))throw new Conflict();
        if(draft.packageSourceRevision!=null
                &&!draft.packageSourceRevision.equals(currentPackageSourceRevision(draft.root)))throw new Conflict();
        if(draft.expectedManifestDigest!=null&&!draft.expectedManifestDigest.equals(currentDigest(draft.root)))throw new Conflict();
    }
    private String currentDigest(Path root) throws Exception {return digest(readOptional(root.resolve("package.yaml")));}
    private String currentResourceDigest(Path root,String relativePath) throws Exception {
        return digest(readResourceOptional(root,relativePath));
    }
    private String currentPackageSourceRevision(Path root) throws IOException {
        Lock read=sourceTransaction.readLock();read.lock();
        try {return att.server.api.source.PackageSourceRevision.digest(root);}
        finally {read.unlock();}
    }
    private String packageSourceRevision(Path root,String relativePath,byte[] bytes) throws IOException {
        Lock read=sourceTransaction.readLock();read.lock();
        try {return att.server.api.source.PackageSourceRevision.digest(root,Map.of(relativePath,bytes));}
        finally {read.unlock();}
    }
    private static String resourceRelativePath(Path root,String authoredPath) throws IOException {
        if(authoredPath==null||authoredPath.length()>512)throw new IllegalArgumentException("Package resource path is invalid");
        Path relative=att.core.IdentifierValidator.relativePath(authoredPath,"Package SUT Metrics resource path");
        String normalized=relative.toString().replace('\\','/');
        String[] parts=normalized.split("/",-1);
        if(!normalized.equals(authoredPath)||parts.length<4||!"load".equals(parts[0])||!"sut-metrics".equals(parts[1])
                ||!("sources".equals(parts[2])||"groups".equals(parts[2])||"profiles".equals(parts[2]))
                ||!normalized.matches("load/sut-metrics/(?:sources|groups|profiles)/.+\\.ya?ml"))
            throw new IllegalArgumentException("Package SUT Metrics resource path must be a YAML file under load/sut-metrics/{sources,groups,profiles}/");
        Path canonicalRoot=root.toAbsolutePath().normalize();
        if(!root.equals(root.toRealPath())||!canonicalRoot.equals(root.toRealPath()))throw new IllegalArgumentException("Package root is not canonical");
        Path target=canonicalRoot.resolve(relative).normalize();
        if(!target.startsWith(canonicalRoot))throw new IllegalArgumentException("Package resource path escapes its Package root");
        for(Path current=target;current!=null&&current.startsWith(canonicalRoot);current=current.getParent()) {
            if(Files.isSymbolicLink(current))throw new IllegalArgumentException("Package resource path contains a symbolic link");
            if(!current.equals(target)&&Files.exists(current,LinkOption.NOFOLLOW_LINKS)
                    &&!Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Package resource path traverses a non-directory");
            if(current.equals(canonicalRoot))break;
        }
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Package resource path is not a regular file");
            Path real=target.toRealPath();
            if(!real.equals(target)||!real.startsWith(canonicalRoot))throw new IllegalArgumentException("Package resource path is unsafe");
        }
        return normalized;
    }
    private static Path resourcePath(Path root,String relativePath) throws IOException {
        String normalized=resourceRelativePath(root,relativePath);
        return root.resolve(att.core.IdentifierValidator.relativePath(normalized,"Package SUT Metrics resource path")).normalize();
    }
    private byte[] readResourceOptional(Path root,String relativePath) throws IOException {
        Lock read=sourceTransaction.readLock();read.lock();
        try {
            Path file=resourcePath(root,relativePath);
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new byte[0];
            byte[] first=readResourceOnce(root,relativePath,file);
            byte[] second=readResourceOnce(root,relativePath,file);
            if(!java.util.Arrays.equals(first,second))throw new IOException("Package resource changed while it was being read");
            return first;
        } finally {read.unlock();}
    }
    private static byte[] readResourceOnce(Path root,String relativePath,Path file) throws IOException {
        resourcePath(root,relativePath);
        BasicFileAttributes before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.size()>MAX_SOURCE_BYTES)throw new IOException("Package resource is unsafe or exceeds its limit");
        ByteBuffer buffer=ByteBuffer.allocate(8192);java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream((int)before.size());long total=0;
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            if(channel.size()!=before.size())throw new IOException("Package resource changed while it was opened");
            int read;while((read=channel.read(buffer))>=0) {
                if(read==0)continue;total+=read;if(total>MAX_SOURCE_BYTES)throw new IOException("Package resource exceeds its limit");
                output.write(buffer.array(),0,read);buffer.clear();
            }
            if(channel.size()!=before.size()||total!=before.size())throw new IOException("Package resource changed while it was read");
        }
        BasicFileAttributes after=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!after.isRegularFile()||after.size()!=before.size()||!after.lastModifiedTime().equals(before.lastModifiedTime())
                ||(before.fileKey()!=null&&after.fileKey()!=null&&!before.fileKey().equals(after.fileKey())))
            throw new IOException("Package resource changed while it was read");
        resourcePath(root,relativePath);
        return output.toByteArray();
    }
    private Path resourceHistoryDirectory(String packageId,String relativePath) throws Exception {
        Path resources=historyDirectory(packageId).resolve("resources");privateDirectory(resources);
        Path directory=resources.resolve(digest(relativePath.getBytes(StandardCharsets.UTF_8)));privateDirectory(directory);return directory;
    }
    private byte[] readOptional(Path file) throws IOException {
        Lock read=sourceTransaction.readLock();read.lock();
        try {
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new byte[0];
            if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(file)||Files.size(file)>MAX_SOURCE_BYTES)
                throw new IOException("Package Manifest source is unsafe or exceeds its limit");
            try(var input=Files.newInputStream(file)){byte[] value=input.readNBytes(MAX_SOURCE_BYTES+1);if(value.length>MAX_SOURCE_BYTES)throw new IOException("Manifest source exceeds its limit");return value;}
        } finally {read.unlock();}
    }
    private Map<String,Object> view(Draft draft) throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();result.put("draftId",draft.id);result.put("packageId",draft.packageId);
        if(draft.resourcePath==null)result.put("expectedManifestDigest",draft.beforeDigest);
        else {
            result.put("resourcePath",draft.resourcePath);result.put("expectedResourceDigest",draft.beforeDigest);
            if(draft.environment!=null)result.put("environment",draft.environment);
            if(draft.candidateManifestSource!=null) {
                result.put("activationMode","resource-then-manifest");
                result.put("expectedManifestDigest",draft.expectedManifestDigest);
                result.put("candidateManifestDigest",digest(draft.candidateManifestSource.getBytes(StandardCharsets.UTF_8)));
                result.put("recoveredExistingResource",!digest(new byte[0]).equals(draft.beforeDigest));
                result.put("candidateResourceDigest",digest(draft.source.getBytes(StandardCharsets.UTF_8)));
            }
        }
        result.put("expiresAt",draft.expires.toString());
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
    synchronized void invalidateDrafts(){drafts.clear();}
    @Override public synchronized void close(){invalidateDrafts();}
    static final class Conflict extends RuntimeException { }
    static final class Denied extends RuntimeException { }
    static final class Missing extends RuntimeException { }
    static final class Capacity extends RuntimeException { }
}
