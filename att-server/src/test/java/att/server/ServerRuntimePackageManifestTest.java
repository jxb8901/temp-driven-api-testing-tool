package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ServerRuntimePackageManifestTest {
    @TempDir Path temp;
    private Path pkg;
    private ServerRuntime runtime(String policy) throws Exception {
        Path allowed=Files.createDirectories(temp.resolve("packages"));pkg=Files.createDirectories(allowed.resolve("owner"));
        Path config=Files.createDirectories(temp.resolve("central/config")).resolve("config.yaml");
        Files.writeString(config,"schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments: {SIT: {}, UAT: {}}\n");
        Path template=Files.createDirectories(pkg.resolve("templates/FORM")).resolve("template.yaml");
        Files.writeString(template,"schemaVersion: att-template/v3.6\nname: FORM\ndescription: Manifest integration\nactions:\n  log:\n    type: log\n    message: hello\n");
        Files.createDirectories(pkg.resolve("testcase"));
        Path server=temp.resolve("server.yaml");
        Files.writeString(server,"server:\n  dataDir: "+ServerRuntimeTestSupport.yaml(temp.resolve("data"))+"\n  runtimeConfigPath: "+ServerRuntimeTestSupport.yaml(config)+"\n"
                +"  packageConfiguration:\n    sourceEditingEnabled: true\n"+policy
                +"  inlineLoad: {enabled: true}\nworkers: {}\npackages:\n  allowedRoots: ["+ServerRuntimeTestSupport.yaml(allowed)+"]\n  entries: {owner: "+ServerRuntimeTestSupport.yaml(pkg)+"}\n");
        Path libs=Files.createDirectories(temp.resolve("libs"));
        for(String module:List.of("att-worker","att-engine","att-server-api","att-security"))ServerRuntimeTestSupport.addModuleJar(libs,module,Path.of(module+"/target/classes"));
        ServerRuntimeTestSupport.addWorkerClasspathJars(libs);
        return new ServerRuntime(ServerConfig.load(server),libs.toString());
    }
    @Test void stagesPrivateWorkerCandidateAndPublishesOnlyForRegisteredOwner() throws Exception {
        try(var runtime=runtime("")) {
            String source="schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n";
            String before=PackageManifestPublisher.digest(new byte[0]);
            assertThrows(PackageManifestPublisher.Denied.class,()->runtime.manifestPublisher.stage("owner","alice",source,before));
            assertThrows(PackageManifestPublisher.Missing.class,()->runtime.manifestPublisher.stage("unknown","unknown",source,before));
            String id=(String)runtime.manifestPublisher.stage("owner","owner",source,before).get("draftId");
            assertFalse(Files.exists(pkg.resolve("package.yaml")));
            Map<String,Object> validated=runtime.manifestPublisher.validate("owner","owner",id);
            assertFalse(Files.exists(pkg.resolve("package.yaml")));
            assertEquals("validated",validated.get("state"));
            runtime.manifestPublisher.review("owner","owner",id);runtime.manifestPublisher.publish("owner","owner",id);
            assertEquals(source,Files.readString(pkg.resolve("package.yaml")));
            assertEquals("UAT",runtime.inspectPackageConfiguration("owner","effective",null,"alice").get("environment"));
            assertEquals(false,runtime.inspectPackageConfiguration("owner","declared",null,"alice").get("canEdit"));
            assertEquals(true,runtime.inspectPackageConfiguration("owner","declared",null,"owner").get("canEdit"));
        }
    }
    @Test void pinsResolvedEnvironmentAndRejectsImplicitRestrictedDefaultsBeforeTargetValidation() throws Exception {
        try(var runtime=runtime("    allowedEnvironments: [SIT, UAT]\n    requireExplicitEnvironments: [UAT]\n")) {
            Files.writeString(pkg.resolve("package.yaml"),"schemaVersion: att-package/v1\ndefaultEnvironment: SIT\n");
            var body=ServerRuntime.JSON.createObjectNode();body.put("packageId","owner");body.put("model","virtualUsers");
            body.set("target",ServerRuntime.JSON.valueToTree(Map.of("type","template","id","FORM")));
            body.set("input",ServerRuntime.JSON.valueToTree(Map.of()));body.set("load",ServerRuntime.JSON.valueToTree(Map.of("users",1,"duration","1s")));
            Map<String,Object> draft=runtime.createQuickLoadDraft(body,"alice");
            assertEquals("SIT",draft.get("environment"));
            Files.writeString(pkg.resolve("package.yaml"),"schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n");
            assertEquals("SIT",runtime.getQuickLoadDraft((String)draft.get("draftId"),"alice").get("environment"));
            assertThrows(PackageConfigurationPolicy.EnvironmentDeniedException.class,()->runtime.createQuickLoadDraft(body,"alice"));
            body.put("environment","uat");assertEquals("UAT",runtime.createQuickLoadDraft(body,"alice").get("environment"));
        }
    }
}
