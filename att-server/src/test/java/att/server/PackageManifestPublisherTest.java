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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PackageManifestPublisherTest {
    @TempDir Path temporary;
    private static final String SOURCE="schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n";
    private static final String OLD="schemaVersion: att-package/v1\n";
    private static String sha(String text) throws Exception { return PackageManifestPublisher.digest(text.getBytes(StandardCharsets.UTF_8)); }
    private static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-25T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    private final MutableClock clock=new MutableClock();
    private final AtomicBoolean authorized=new AtomicBoolean(true);
    private final AtomicReference<String> dependencies=new AtomicReference<>("unchanged");
    private final ArrayList<String> audits=new ArrayList<>();
    private Path root;
    private PackageManifestPublisher publisher(Map<String,Object> options) throws Exception {
        root=Files.createDirectories(temporary.resolve("package")).toRealPath();
        return new PackageManifestPublisher(temporary.resolve("private"),PackageConfigurationPolicy.load(options),clock,
                (id,actor)->{if(!authorized.get()||!"owner".equals(id)||!"owner".equals(actor))throw new PackageManifestPublisher.Denied();return root;},
                (id,source)->Map.of("valid",true,"manifestDigest",sha(source),"validationRevision",sha(source+dependencies.get()),
                        "environment","UAT","configurationIdentity",sha(source+"identity")),
                (actor,action,id,detail)->audits.add(action));
    }
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
            assertEquals(OLD,Files.readString(root.resolve("package.yaml")));
            assertThrows(IllegalArgumentException.class,()->publisher.publish("owner","owner",id));
            publisher.review("owner","owner",id);
            assertEquals(true,publisher.publish("owner","owner",id).get("published"));
            assertEquals(SOURCE,Files.readString(root.resolve("package.yaml")));
            assertThrows(PackageManifestPublisher.Missing.class,()->publisher.publish("owner","owner",id));
            assertEquals(2,publisher.history("owner","owner").size());
            String rollback=(String)publisher.rollbackDraft("owner","owner",sha(OLD),sha(SOURCE)).get("draftId");
            publisher.validate("owner","owner",rollback);publisher.review("owner","owner",rollback);publisher.publish("owner","owner",rollback);
            assertEquals(OLD,Files.readString(root.resolve("package.yaml")));
            assertTrue(audits.contains("PACKAGE_MANIFEST_PUBLISH_INTENT"));
            try(var children=Files.list(root)){assertEquals(1,children.count());}
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
            assertFalse(Files.exists(root.resolve("package.yaml")));
            dependencies.set("unchanged");authorized.set(false);
            assertThrows(PackageManifestPublisher.Denied.class,()->publisher.publish("owner","owner",id));
            authorized.set(true);Files.writeString(root.resolve("package.yaml"),OLD);
            assertThrows(PackageManifestPublisher.Conflict.class,()->publisher.publish("owner","owner",id));
            assertEquals(OLD,Files.readString(root.resolve("package.yaml")));
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
    @Test void blocksManifestSymlinksAndOversizedOrNulSources() throws Exception {
        try(var publisher=publisher(Map.of("sourceEditingEnabled",true))) {
            assertThrows(IllegalArgumentException.class,()->staged(publisher,"a".repeat(262145),""));
            assertThrows(IllegalArgumentException.class,()->staged(publisher,"schemaVersion: att-package/v1\0",""));
            Path outside=temporary.resolve("outside.yaml");Files.writeString(outside,OLD);
            Files.createSymbolicLink(root.resolve("package.yaml"),outside);
            assertThrows(java.io.IOException.class,()->staged(publisher,SOURCE,OLD));
            assertEquals(OLD,Files.readString(outside));
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
