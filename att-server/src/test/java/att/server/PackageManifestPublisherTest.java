package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PackageManifestPublisherTest {
    @TempDir Path temporary;
    private static final String SOURCE="schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n";
    private static final String OLD="schemaVersion: att-package/v1\n";
    private static final String RESOURCE="schemaVersion: att-sut-metrics-source/v1\nid: prom-main\nkind: PROMETHEUS\n";
    private static String sha(String text) throws Exception { return PackageManifestPublisher.digest(text.getBytes(StandardCharsets.UTF_8)); }
    private static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-25T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    private final MutableClock clock=new MutableClock();
    private final AtomicBoolean authorized=new AtomicBoolean(true);
    private final AtomicBoolean resourceValid=new AtomicBoolean(true);
    private final AtomicReference<String> resourceEnvironment=new AtomicReference<>();
    private final AtomicReference<String> resourceActor=new AtomicReference<>();
    private final AtomicReference<String> dependencies=new AtomicReference<>("unchanged");
    private final AtomicBoolean failBeforeManifestActivation=new AtomicBoolean();
    private final ArrayList<String> audits=new ArrayList<>();
    private Path root;
    private SourceFileTransaction transaction;
    private PackageManifestPublisher publisher(Map<String,Object> options) throws Exception {
        root=Files.createDirectories(temporary.resolve("package")).toRealPath();
        transaction=new SourceFileTransaction(temporary.resolve("private"),new SourcePublicationBarrier());
        return new PackageManifestPublisher(temporary.resolve("private"),PackageConfigurationPolicy.load(options),clock,
                (id,actor)->{if(!authorized.get()||!"owner".equals(id)||!"owner".equals(actor))throw new PackageManifestPublisher.Denied();return transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE,id,root);},
                (id,source)->Map.of("valid",true,"manifestDigest",sha(source),"validationRevision",sha(source+dependencies.get()),
                        "environment","UAT","configurationIdentity",sha(source+"identity")),
                null,(actor,action,id,detail)->audits.add(action),
                transaction);
    }
    private PackageManifestPublisher resourcePublisher(Map<String,Object> options) throws Exception {
        root=Files.createDirectories(temporary.resolve("package")).toRealPath();
        transaction=new SourceFileTransaction(temporary.resolve("private"),new SourcePublicationBarrier());
        return new PackageManifestPublisher(temporary.resolve("private"),PackageConfigurationPolicy.load(options),clock,
                (id,actor)->{if(!authorized.get()||!"owner".equals(id)||!"owner".equals(actor))throw new PackageManifestPublisher.Denied();return transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE,id,root);},
                (id,source)->Map.of("valid",true,"manifestDigest",sha(source),"validationRevision",sha(source+dependencies.get()),
                        "environment","UAT","configurationIdentity",sha(source+"identity")),
                (id,actor,path,environment,source)->{
                    resourceActor.set(actor);resourceEnvironment.set(environment);
                    return Map.of("valid",resourceValid.get(),"resourceDigest",sha(source),
                            "validationRevision",sha(id+"\n"+actor+"\n"+path+"\n"+environment+"\n"+source+dependencies.get()),
                            "environment",environment==null?"UAT":environment,"configurationIdentity",sha(path+"identity"));
                },
                (actor,action,id,detail)->audits.add(action),transaction);
    }
    private PackageManifestPublisher resourceCreatePublisher(Map<String,Object> options,AtomicBoolean inertSeen) throws Exception {
        root=Files.createDirectories(temporary.resolve("package")).toRealPath();
        transaction=new SourceFileTransaction(temporary.resolve("private"),new SourcePublicationBarrier());
        PackageManifestPublisher.ResourceValidator validator=new PackageManifestPublisher.ResourceValidator() {
            public Map<String,Object> validate(String id,String actor,String path,String environment,String source) throws Exception {
                return resourceValidation(id,actor,path,environment,source,null,null);
            }
            @Override public Map<String,Object> validateCandidate(String id,String actor,String path,String environment,
                    String source,String candidateManifestSource,String expectedManifestDigest) throws Exception {
                if(candidateManifestSource!=null) {
                    assertEquals(expectedManifestDigest,sha(Files.readString(root.resolve("package.yaml"))));
                    Path live=root.resolve(path);
                    if(Files.exists(live)) {
                        assertFalse(Files.readString(root.resolve("package.yaml")).contains(path),
                                "The new resource must remain unlisted until package.yaml is replaced.");
                        inertSeen.set(true);
                    }
                }
                return resourceValidation(id,actor,path,environment,source,candidateManifestSource,expectedManifestDigest);
            }
            private Map<String,Object> resourceValidation(String id,String actor,String path,String environment,
                    String source,String candidateManifestSource,String expectedManifestDigest) throws Exception {
                resourceActor.set(actor);resourceEnvironment.set(environment);
                Map<String,Object> result=new java.util.LinkedHashMap<>();
                result.put("valid",resourceValid.get());result.put("resourceDigest",sha(source));
                result.put("validationRevision",sha(id+"\n"+actor+"\n"+path+"\n"+environment+"\n"+source
                        +String.valueOf(candidateManifestSource)+String.valueOf(expectedManifestDigest)+dependencies.get()));
                result.put("environment",environment==null?"UAT":environment);
                result.put("configurationIdentity",sha(path+"identity"));
                if(candidateManifestSource!=null)
                    result.put("candidateManifestDigest",sha(candidateManifestSource));
                return result;
            }
        };
        return new PackageManifestPublisher(temporary.resolve("private"),PackageConfigurationPolicy.load(options),clock,
                (id,actor)->{if(!authorized.get()||!"owner".equals(id)||!"owner".equals(actor))throw new PackageManifestPublisher.Denied();return transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE,id,root);},
                (id,source)->Map.of("valid",true,"manifestDigest",sha(source),"validationRevision",sha(source+dependencies.get()),
                        "environment","UAT","configurationIdentity",sha(source+"identity")),
                validator,(actor,action,id,detail)->{
                    audits.add(action);
                    if("PKG_SUT_MANIFEST_INTENT".equals(action)&&failBeforeManifestActivation.compareAndSet(true,false))
                        throw new java.io.IOException("Injected failure before Package Manifest activation");
                },transaction);
    }
    private String activeManifest() throws Exception {return Files.readString(transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE,"owner",root).resolve("package.yaml"));}
    private String staged(PackageManifestPublisher publisher,String source,String before) throws Exception {
        return (String)publisher.stage("owner","owner",source,sha(before)).get("draftId");
    }
    @Test void requiresValidationAndReviewThenAtomicallyPublishesAndRollsBack() throws Exception {
        try(var publisher=publisher(Map.of("sourceEditingEnabled",true))) {
            Files.writeString(root.resolve("package.yaml"),OLD);
            String id=staged(publisher,SOURCE,OLD);
            assertThrows(IllegalArgumentException.class,()->publisher.publish("owner","owner",id));
            Map<String,Object> validation=publisher.validate("owner","owner",id);
            assertFalse(validation.toString().contains(SOURCE));
            assertEquals(OLD,activeManifest());
            assertThrows(IllegalArgumentException.class,()->publisher.publish("owner","owner",id));
            publisher.review("owner","owner",id);
            assertEquals(true,publisher.publish("owner","owner",id).get("published"));
            assertEquals(SOURCE,activeManifest());
            assertThrows(PackageManifestPublisher.Missing.class,()->publisher.publish("owner","owner",id));
            assertEquals(2,publisher.history("owner","owner").size());
            String rollback=(String)publisher.rollbackDraft("owner","owner",sha(OLD),sha(SOURCE)).get("draftId");
            publisher.validate("owner","owner",rollback);publisher.review("owner","owner",rollback);publisher.publish("owner","owner",rollback);
            assertEquals(OLD,activeManifest());
            assertTrue(audits.contains("PACKAGE_MANIFEST_PUBLISH_INTENT"));
            try(var children=Files.list(root)){assertEquals(1,children.count());}
        }
    }
    @Test void rollbackOfAbsentManifestStagesCurrentSchemaVersion() throws Exception {
        try(var publisher=publisher(Map.of("sourceEditingEnabled",true))) {
            String emptyDigest=sha("");
            Path history=temporary.resolve("private/package-source-history").resolve(sha("owner"));
            Files.createDirectories(history);
            Files.writeString(history.resolve(emptyDigest+".yaml"),"");
            Files.writeString(root.resolve("package.yaml"),"schemaVersion: att-package/v2\n");
            String liveDigest=sha("schemaVersion: att-package/v2\n");

            Map<String,Object> rollback=publisher.rollbackDraft("owner","owner",emptyDigest,liveDigest);
            String id=(String)rollback.get("draftId");
            assertEquals(true,rollback.get("resetToEmptyManifest"));
            assertFalse(rollback.containsKey("source"),"Raw candidate Manifest text must stay private.");
            publisher.validate("owner","owner",id);
            publisher.review("owner","owner",id);
            publisher.publish("owner","owner",id);
            assertEquals("schemaVersion: att-package/v2\n",Files.readString(root.resolve("package.yaml")));
        }
    }
    @Test void historyUsesNewestModificationTimeThenDigestRatherThanFilenameOrder() throws Exception {
        try(var publisher=publisher(Map.of("sourceEditingEnabled",true))) {
            var sources=new ArrayList<>(java.util.List.of(OLD,SOURCE,"schemaVersion: att-package/v1\nmetadata: {name: Third}\n"));
            sources.sort(java.util.Comparator.comparing(text->{try{return sha(text);}catch(Exception e){throw new RuntimeException(e);}}));
            Path history=temporary.resolve("private/package-source-history").resolve(sha("owner"));
            String previous="";int index=0;
            for(String source:sources) {
                String id=staged(publisher,source,previous);
                publisher.validate("owner","owner",id);publisher.review("owner","owner",id);publisher.publish("owner","owner",id);
                Files.setLastModifiedTime(history.resolve(sha(source)+".yaml"),
                        java.nio.file.attribute.FileTime.from(clock.instant().plusSeconds(++index)));
                previous=source;
            }
            Files.setLastModifiedTime(history.resolve(sha("")+".yaml"),java.nio.file.attribute.FileTime.from(clock.instant()));
            var expected=new ArrayList<String>();
            for(int i=sources.size()-1;i>=0;i--)expected.add(sha(sources.get(i)));
            expected.add(sha(""));
            assertEquals(expected,publisher.history("owner","owner").stream().map(item->(String)item.get("manifestDigest")).toList());
            try(var files=Files.list(history)) {
                for(Path file:files.toList())Files.setLastModifiedTime(file,java.nio.file.attribute.FileTime.from(clock.instant()));
            }
            expected.sort(String::compareTo);
            assertEquals(expected,publisher.history("owner","owner").stream().map(item->(String)item.get("manifestDigest")).toList());
        }
    }
    @Test void rejectsStaleLiveOrDependencyRevisionAndRechecksRevokedAccess() throws Exception {
        try(var publisher=publisher(Map.of("sourceEditingEnabled",true))) {
            String id=staged(publisher,SOURCE,"");publisher.validate("owner","owner",id);publisher.review("owner","owner",id);
            dependencies.set("changed");
            assertThrows(PackageManifestPublisher.Conflict.class,()->publisher.publish("owner","owner",id));
            assertFalse(Files.exists(transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE,"owner",root).resolve("package.yaml")));
            dependencies.set("unchanged");authorized.set(false);
            assertThrows(PackageManifestPublisher.Denied.class,()->publisher.publish("owner","owner",id));
            authorized.set(true);
            Path active=transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE,"owner",root);
            transaction.commit(ChangeSetPublisher.PACKAGE_SOURCE,"owner",active,"external",sha(""),sha(OLD),
                    Map.of("package.yaml",new SourceFileTransaction.Write("package.yaml",OLD.getBytes(StandardCharsets.UTF_8),null)),()->{});
            assertThrows(PackageManifestPublisher.Conflict.class,()->publisher.publish("owner","owner",id));
            assertEquals(OLD,activeManifest());
        }
    }
    @Test void manifestCommitRetainsJournalUntilItsDurableAuditIsConfirmed() throws Exception {
        root=Files.createDirectories(temporary.resolve("package")).toRealPath();
        Files.writeString(root.resolve("package.yaml"),OLD);
        transaction=new SourceFileTransaction(temporary.resolve("private"),new SourcePublicationBarrier());
        PackageManifestPublisher publisher=new PackageManifestPublisher(temporary.resolve("private"),
                PackageConfigurationPolicy.load(Map.of("sourceEditingEnabled",true)),clock,
                (id,actor)->transaction.pin(ChangeSetPublisher.PACKAGE_SOURCE,id,root),
                (id,source)->Map.of("valid",true,"manifestDigest",sha(source),"validationRevision",sha(source+"stable"),
                        "environment","UAT","configurationIdentity",sha(source+"identity")),
                null,
                new PackageManifestPublisher.Audit() {
                    @Override public void record(String actor,String action,String packageId,String detail) { }
                    @Override public void recordCommitted(SourceFileTransaction.AuditRecord record) throws Exception {
                        throw new java.io.IOException("injected manifest audit failure");
                    }
                },transaction);
        try(publisher) {
            String id=staged(publisher,SOURCE,OLD);
            publisher.validate("owner","owner",id);publisher.review("owner","owner",id);
            Map<String,Object> result=publisher.publish("owner","owner",id,"manifest-request-252");
            assertEquals(Boolean.TRUE,result.get("published"));
            assertEquals(Boolean.FALSE,result.get("auditConfirmed"));
            assertEquals(SOURCE,activeManifest());
            AtomicReference<SourceFileTransaction.AuditRecord> replayed=new AtomicReference<>();
            assertEquals(1,transaction.recover(Map.of(ChangeSetPublisher.PACKAGE_SOURCE+":owner",root),replayed::set));
            assertEquals("PACKAGE_MANIFEST_PUBLISH",replayed.get().action);
            assertEquals("owner",replayed.get().actor);
            assertEquals(List.of("package.yaml"),replayed.get().resources);
            assertEquals(sha(OLD),replayed.get().beforeRevision);
            assertEquals(sha(SOURCE),replayed.get().afterRevision);
            assertEquals("manifest-request-252",replayed.get().requestId);
            assertEquals("COMMITTED",replayed.get().outcome);
        }
    }
    @Test void separatesOwnersBoundsDraftsAndExpiresPrivateCandidates() throws Exception {
        try(var publisher=publisher(Map.of("sourceEditingEnabled",true,"maxDrafts",1,"draftTtlSeconds",60))) {
            assertThrows(PackageManifestPublisher.Denied.class,()->publisher.stage("owner","other",SOURCE,sha("")));
            String id=staged(publisher,SOURCE,"");
            assertThrows(PackageManifestPublisher.Capacity.class,()->staged(publisher,SOURCE,""));
            clock.now=clock.now.plusSeconds(60);
            assertThrows(PackageManifestPublisher.Missing.class,()->publisher.validate("owner","owner",id));
            assertNotNull(staged(publisher,SOURCE,""));
        }
        try(var publisher=publisher(Map.of())) {
            assertThrows(PackageManifestPublisher.Denied.class,()->staged(publisher,SOURCE,""));
        }
    }
    @Test void blocksOversizedOrNulManifestSources() throws Exception {
        try(var publisher=publisher(Map.of("sourceEditingEnabled",true))) {
            assertThrows(IllegalArgumentException.class,()->staged(publisher,"a".repeat(262145),""));
            assertThrows(IllegalArgumentException.class,()->staged(publisher,"schemaVersion: att-package/v1\0",""));
        }
    }
    @Test void blocksManifestSymlinks() throws Exception {
        try(var publisher=publisher(Map.of("sourceEditingEnabled",true))) {
            Path outside=temporary.resolve("outside.yaml");Files.writeString(outside,OLD);
            Files.createSymbolicLink(root.resolve("package.yaml"),outside);
            assertThrows(java.io.IOException.class,()->staged(publisher,SOURCE,OLD));
            assertEquals(OLD,Files.readString(outside));
        }
    }
    @Test void resourceDraftRejectsTraversalAndSymlinkedParents() throws Exception {
        try(var publisher=resourcePublisher(Map.of("sourceEditingEnabled",true))) {
            assertThrows(IllegalArgumentException.class,()->publisher.stageResource("owner","owner",
                    "load/sut-metrics/sources/../escaped.yaml",RESOURCE,sha("")));
            Path outside=Files.createDirectories(temporary.resolve("outside"));
            Path metrics=Files.createDirectories(root.resolve("load/sut-metrics"));
            Files.createSymbolicLink(metrics.resolve("sources"),outside);
            assertThrows(IllegalArgumentException.class,()->publisher.stageResource("owner","owner",
                    "load/sut-metrics/sources/prom.yaml",RESOURCE,sha("")));
            assertFalse(Files.exists(outside.resolve("prom.yaml")));
        }
    }
    @Test void resourceDraftRequiresValidationAndReviewAndLeavesManifestInventoryUntouched() throws Exception {
        try(var publisher=resourcePublisher(Map.of("sourceEditingEnabled",true))) {
            Files.writeString(root.resolve("package.yaml"),"schemaVersion: att-package/v2\ndefaultEnvironment: UAT\n");
            String path="load/sut-metrics/sources/prom.yaml";
            String id=(String)publisher.stageResource("owner","owner",path,RESOURCE,sha("")).get("draftId");
            assertThrows(IllegalArgumentException.class,()->publisher.publishResource("owner","owner",id));
            Map<String,Object> validated=publisher.validateResource("owner","owner",id);
            assertFalse(validated.toString().contains(RESOURCE));
            assertThrows(IllegalArgumentException.class,()->publisher.publishResource("owner","owner",id));
            publisher.reviewResource("owner","owner",id);
            Map<String,Object> published=publisher.publishResource("owner","owner",id);
            assertEquals(true,published.get("published"));
            assertEquals(RESOURCE,Files.readString(root.resolve(path)));
            assertTrue(Files.readString(root.resolve("package.yaml")).contains("defaultEnvironment: UAT"));
            assertEquals(2,publisher.historyResource("owner","owner",path).size());
            assertTrue(audits.contains("PKG_SUT_METRIC_PUBLISH_INTENT"));
        }
    }
    @Test void createResourceWritesInertBytesBeforeActivatingCandidateManifestLast() throws Exception {
        AtomicBoolean inertResourceObserved=new AtomicBoolean();
        try(var publisher=resourceCreatePublisher(Map.of("sourceEditingEnabled",true),inertResourceObserved)) {
            String path="load/sut-metrics/sources/created.yaml";
            String oldManifest="schemaVersion: att-package/v2\n";
            String candidateManifest=oldManifest+"observability:\n  metricSources: ["+path+"]\n";
            Files.writeString(root.resolve("package.yaml"),oldManifest);
            String oldManifestDigest=sha(oldManifest);
            Map<String,Object> staged=publisher.stageResource("owner","owner",path,"UAT",RESOURCE,sha(""),
                    candidateManifest,oldManifestDigest);
            assertEquals("resource-then-manifest",staged.get("activationMode"));
            assertEquals(oldManifestDigest,staged.get("expectedManifestDigest"));
            assertEquals(false,staged.get("recoveredExistingResource"));
            assertEquals(sha(RESOURCE),staged.get("candidateResourceDigest"));
            String id=(String)staged.get("draftId");
            assertFalse(Files.exists(root.resolve(path)));
            Map<String,Object> validation=publisher.validateResource("owner","owner",id);
            assertEquals(sha(candidateManifest),validation.get("candidateManifestDigest"));
            assertFalse(Files.exists(root.resolve(path)));
            publisher.reviewResource("owner","owner",id);

            Map<String,Object> published=publisher.publishResource("owner","owner",id);
            assertTrue(inertResourceObserved.get(),"Validation must observe the file while it is still unlisted/inert.");
            assertEquals(true,published.get("published"));
            assertEquals(sha(""),published.get("beforeResourceDigest"));
            assertEquals(sha(RESOURCE),published.get("resourceDigest"));
            assertEquals(oldManifestDigest,published.get("expectedManifestDigest"));
            assertEquals(sha(candidateManifest),published.get("manifestDigest"));
            assertEquals(RESOURCE,Files.readString(root.resolve(path)));
            assertEquals(candidateManifest,Files.readString(root.resolve("package.yaml")));
            assertEquals(2,publisher.historyResource("owner","owner",path).size());
            assertEquals(2,publisher.history("owner","owner").size());
            assertTrue(audits.contains("PKG_SUT_MANIFEST_INTENT"));
        }
    }
    @Test void createdResourceRejectsIncompleteOrStaleManifestAndMissingSource() throws Exception {
        AtomicBoolean inertSeen=new AtomicBoolean();
        try(var publisher=resourceCreatePublisher(Map.of("sourceEditingEnabled",true),inertSeen)) {
            String path="load/sut-metrics/groups/created.yaml";
            String manifest="schemaVersion: att-package/v2\n";
            Files.writeString(root.resolve("package.yaml"),manifest);
            assertThrows(IllegalArgumentException.class,()->publisher.stageResource("owner","owner",path,"UAT",
                    null,sha(""),manifest,sha(manifest)));
            assertThrows(IllegalArgumentException.class,()->publisher.stageResource("owner","owner",path,"UAT",
                    RESOURCE,sha(""),manifest,null));
            assertThrows(PackageManifestPublisher.Conflict.class,()->publisher.stageResource("owner","owner",path,"UAT",
                    RESOURCE,sha("wrong"),manifest,sha(manifest)));

            String candidate=manifest+"observability:\n  groups: ["+path+"]\n";
            String id=(String)publisher.stageResource("owner","owner",path,"UAT",RESOURCE,sha(""),candidate,sha(manifest)).get("draftId");
            Files.writeString(root.resolve("package.yaml"),manifest+"metadata: {name: Concurrent edit}\n");
            assertThrows(PackageManifestPublisher.Conflict.class,()->publisher.publishResource("owner","owner",id));
            assertFalse(Files.exists(root.resolve(path)));
            assertFalse(inertSeen.get());
        }
    }
    @Test void createdResourceCanRecoverAndRetryWithAnAlreadyWrittenMatchingInertFile() throws Exception {
        AtomicBoolean inertSeen=new AtomicBoolean();
        try(var publisher=resourceCreatePublisher(Map.of("sourceEditingEnabled",true),inertSeen)) {
            String path="load/sut-metrics/profiles/created.yaml";
            String manifest="schemaVersion: att-package/v2\n";
            String candidate=manifest+"observability:\n  profiles: ["+path+"]\n";
            Files.writeString(root.resolve("package.yaml"),manifest);
            String id=(String)publisher.stageResource("owner","owner",path,"UAT",RESOURCE,sha(""),candidate,sha(manifest)).get("draftId");
            Path inert=root.resolve(path);Files.createDirectories(inert.getParent());Files.writeString(inert,RESOURCE);

            publisher.validateResource("owner","owner",id);
            publisher.reviewResource("owner","owner",id);
            Map<String,Object> result=publisher.publishResource("owner","owner",id);
            assertEquals(true,result.get("published"));
            assertTrue(inertSeen.get());
            assertEquals(candidate,Files.readString(root.resolve("package.yaml")));
            assertEquals(RESOURCE,Files.readString(inert));
        }
    }
    @Test void orphanedCreateCanBeRecoveredAfterRestartOnlyForMatchingContentAndManifest() throws Exception {
        AtomicBoolean inertSeen=new AtomicBoolean();
        String path="load/sut-metrics/profiles/recovered.yaml";
        String manifest="schemaVersion: att-package/v2\n";
        String candidate=manifest+"observability:\n  profiles: ["+path+"]\n";
        root=Files.createDirectories(temporary.resolve("package")).toRealPath();
        Files.writeString(root.resolve("package.yaml"),manifest);

        try(var first=resourceCreatePublisher(Map.of("sourceEditingEnabled",true),inertSeen)) {
            String draft=(String)first.stageResource("owner","owner",path,"UAT",RESOURCE,sha(""),candidate,sha(manifest)).get("draftId");
            first.validateResource("owner","owner",draft);
            first.reviewResource("owner","owner",draft);
            failBeforeManifestActivation.set(true);
            assertThrows(java.io.IOException.class,()->first.publishResource("owner","owner",draft));
            assertEquals(RESOURCE,Files.readString(root.resolve(path)));
            assertEquals(manifest,Files.readString(root.resolve("package.yaml")),"The failed transaction must leave the resource unlisted.");
        }

        try(var restarted=resourceCreatePublisher(Map.of("sourceEditingEnabled",true),inertSeen)) {
            assertThrows(PackageManifestPublisher.Conflict.class,()->restarted.stageResource("owner","owner",path,"UAT",
                    "different resource",sha(""),candidate,sha(manifest)));
            Map<String,Object> staged=restarted.stageResource("owner","owner",path,"UAT",RESOURCE,sha(""),candidate,sha(manifest));
            assertEquals(sha(RESOURCE),staged.get("expectedResourceDigest"),"Recovery is fenced to the orphan's exact digest.");
            assertEquals(true,staged.get("recoveredExistingResource"));
            assertEquals(sha(RESOURCE),staged.get("candidateResourceDigest"));
            String draft=(String)staged.get("draftId");
            restarted.validateResource("owner","owner",draft);
            restarted.reviewResource("owner","owner",draft);
            Map<String,Object> published=restarted.publishResource("owner","owner",draft);
            assertEquals(true,published.get("published"));
            assertEquals(candidate,Files.readString(root.resolve("package.yaml")));
            assertEquals(RESOURCE,Files.readString(root.resolve(path)));
        }
    }
    @Test void resourceDraftRetainsSelectedEnvironmentThroughValidationAndPublish() throws Exception {
        try(var publisher=resourcePublisher(Map.of("sourceEditingEnabled",true))) {
            String id=(String)publisher.stageResource("owner","owner","load/sut-metrics/profiles/sit.yaml","SIT",RESOURCE,sha("")).get("draftId");
            Map<String,Object> validation=publisher.validateResource("owner","owner",id);
            assertEquals("SIT",validation.get("environment"));
            assertEquals("SIT",resourceEnvironment.get());
            assertEquals("owner",resourceActor.get());
            publisher.reviewResource("owner","owner",id);
            Map<String,Object> result=publisher.publishResource("owner","owner",id);
            assertEquals("SIT",result.get("environment"));
            assertEquals("SIT",resourceEnvironment.get());
            assertEquals("owner",resourceActor.get());
        }
    }
    @Test void resourceValidationRejectsInvalidCandidateAndPublishRejectsStaleResourceRevision() throws Exception {
        try(var publisher=resourcePublisher(Map.of("sourceEditingEnabled",true))) {
            String path="load/sut-metrics/groups/smoke.yaml";
            resourceValid.set(false);
            String invalid=(String)publisher.stageResource("owner","owner",path,RESOURCE,sha("")).get("draftId");
            assertThrows(IllegalArgumentException.class,()->publisher.validateResource("owner","owner",invalid));
            assertFalse(Files.exists(root.resolve(path)));

            resourceValid.set(true);
            Path live=root.resolve(path);Files.createDirectories(live.getParent());Files.writeString(live,OLD);
            String id=(String)publisher.stageResource("owner","owner",path,RESOURCE,sha(OLD)).get("draftId");
            publisher.validateResource("owner","owner",id);publisher.reviewResource("owner","owner",id);
            Files.writeString(live,"concurrent edit\n");
            assertThrows(PackageManifestPublisher.Conflict.class,()->publisher.publishResource("owner","owner",id));
            assertEquals("concurrent edit\n",Files.readString(live));
        }
    }
    @Test void resourcePublishRejectsChangedPackageDefinitionRevisionWhenTargetIsUnchanged() throws Exception {
        try(var publisher=resourcePublisher(Map.of("sourceEditingEnabled",true))) {
            String path="load/sut-metrics/sources/prom.yaml";
            String id=(String)publisher.stageResource("owner","owner",path,RESOURCE,sha("")).get("draftId");
            publisher.validateResource("owner","owner",id);publisher.reviewResource("owner","owner",id);
            dependencies.set("another package definition changed");
            assertThrows(PackageManifestPublisher.Conflict.class,()->publisher.publishResource("owner","owner",id));
            assertFalse(Files.exists(root.resolve(path)));
        }
    }
    @Test void appliesTrustedEnvironmentPolicyAfterResolution() {
        var policy=PackageConfigurationPolicy.load(Map.of("allowedEnvironments",java.util.List.of("SIT","UAT"),"requireExplicitEnvironments",java.util.List.of("UAT")));
        policy.authorizeEnvironment("sit",false);policy.authorizeEnvironment("UAT",true);
        assertThrows(PackageConfigurationPolicy.EnvironmentDeniedException.class,()->policy.authorizeEnvironment("UAT",false));
        assertThrows(PackageConfigurationPolicy.EnvironmentDeniedException.class,()->policy.authorizeEnvironment("PROD",true));
        assertThrows(IllegalArgumentException.class,()->PackageConfigurationPolicy.load(Map.of("allowedEnvironments",java.util.List.of("SIT","sit"))));
    }
}
