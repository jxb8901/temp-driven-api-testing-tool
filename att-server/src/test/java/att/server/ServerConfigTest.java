package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @TempDir Path temp;
    @Test void schedulePackagePolicyDefaultsToStrictAndRejectsUnknownValues() throws Exception {
        Path pkg=Files.createDirectory(temp.resolve("policy-package")),file=temp.resolve("policy-server.yaml");
        String base="server:\n  dataDir: "+yaml(temp.resolve("policy-data"))+"\n";
        String tail="workers: {}\npackages:\n  allowedRoots: ["+yaml(pkg)+"]\n  entries: {p: "+yaml(pkg)+"}\n";
        Files.writeString(file,base+tail);assertEquals("strict",ServerConfig.load(file).scheduling.packageChangePolicy);
        for(String policy:List.of("strict","pause","ignore")) {
            Files.writeString(file,base+"  scheduling: {packageChangePolicy: "+policy+"}\n"+tail);
            assertEquals(policy,ServerConfig.load(file).scheduling.packageChangePolicy);
        }
        Files.writeString(file,base+"  scheduling: {packageChangePolicy: skip}\n"+tail);
        assertThrows(IllegalArgumentException.class,()->ServerConfig.load(file));
    }

    @Test void validatesLiveOutputPolicyAndKeepsPublishedDefaults() throws Exception {
        Path pkg=Files.createDirectory(temp.resolve("live-package"));
        Path file=temp.resolve("live-server.yaml");
        String base="server:\n  dataDir: "+yaml(temp.resolve("live-data"))+"\n";
        String tail="workers: {}\npackages:\n  allowedRoots: ["+yaml(pkg)+"]\n  entries:\n    p: "+yaml(pkg)+"\n";
        Files.writeString(file,base+tail);
        assertEquals(4000,ServerConfig.load(file).liveOutput.maxEntryChars);
        assertEquals(40,ServerConfig.load(file).liveOutput.maxEntryLines);
        Files.writeString(file,base+"  liveOutput: {maxEntryChars: 16000, maxEntryLines: 100}\n"+tail);
        assertEquals(16000,ServerConfig.load(file).liveOutput.maxEntryChars);
        assertEquals(100,ServerConfig.load(file).liveOutput.maxEntryLines);
        for(String invalid:List.of("maxEntryChars: 0","maxEntryChars: 32001","maxEntryChars: 1.5", "maxEntryChars: '16000'", "maxEntryLines: 201", "maxEntryLines: true", "unknown: 5")) {
            Files.writeString(file,base+"  liveOutput: {"+invalid+"}\n"+tail);
            assertThrows(IllegalArgumentException.class,()->ServerConfig.load(file),invalid);
        }
    }


    @Test void boundsWarmupKeysToTwoEntriesPerWorkerAndRetainsLazyDefaults() throws Exception {
        Path pkg=Files.createDirectory(temp.resolve("warmup-package"));
        Path config=temp.resolve("warmup-server.yaml");
        String base="server:\n  dataDir: "+yaml(temp.resolve("warmup-data"))+"\n  inspection:\n    maxConcurrent: 2\n";
        String tail="workers: {}\npackages:\n  allowedRoots: ["+yaml(pkg)+"]\n  entries:\n    p: "+yaml(pkg)+"\n";
        Files.writeString(config,base+tail);
        assertFalse(ServerConfig.load(config).inspection.warmup.enabled());
        Files.writeString(config,base+"    warmup:\n      enabled: true\n      workers: 1\n      keys: [{packageId: p}, {packageId: absent, environment: UAT}]\n"+tail);
        ServerConfig.Warmup warm=ServerConfig.load(config).inspection.warmup;
        assertEquals(2,warm.keys().size());assertTrue(warm.enabled());
        for(String invalid:List.of("workers: 3", "workers: 0", "unknown: true",
                "keys: [{packageId: p}, {packageId: p}]", "keys: [{packageId: p, environment: '../escape'}]",
                "workers: 1\n      keys: [{packageId: p}, {packageId: q}, {packageId: r}]")) {
            Files.writeString(config,base+"    warmup:\n      "+invalid+"\n"+tail);
            assertThrows(IllegalArgumentException.class,()->ServerConfig.load(config),invalid);
        }
    }

    @Test void validatesTrustedOriginsAtStartup() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("origins/packages"));
        Path pkg=Files.createDirectory(packages.resolve("p"));
        Path config=temp.resolve("origins/server.yaml");
        String base="server:\n  dataDir: "+yaml(temp.resolve("origins/data"))+"\n  security:\n    trustedOrigins: ";
        String tail="\nworkers: {}\npackages:\n  allowedRoots: ["+yaml(packages)+"]\n  entries:\n    p: "+yaml(pkg)+"\n";
        Files.writeString(config,base+"['https://gateway.example']"+tail);
        assertEquals(List.of("https://gateway.example"),ServerConfig.load(config).security.trustedOrigins);
        for(String entry:List.of("['http://public.example']","['https://gateway.example/path']","['https://gateway.example', 'https://GATEWAY.example:443/']","'*'","[42]")) {
            Files.writeString(config,base+entry+tail);
            assertThrows(IllegalArgumentException.class,()->ServerConfig.load(config),entry);
        }
    }

    @Test void discoversServerConfigInDocumentedPrecedenceOrder() {
        Path systemConfig=temp.resolve("system.yaml");
        Path environmentConfig=temp.resolve("environment.yaml");
        Path systemHome=temp.resolve("system-home");
        Path environmentHome=temp.resolve("environment-home");
        assertEquals(systemConfig.toAbsolutePath().normalize(),ServerConfig.configPath(
                systemConfig.toString(),environmentConfig.toString(),systemHome.toString(),environmentHome.toString()));
        assertEquals(environmentConfig.toAbsolutePath().normalize(),ServerConfig.configPath(
                " ",environmentConfig.toString(),systemHome.toString(),environmentHome.toString()));
        assertEquals(systemHome.resolve("config/att-server.yaml").toAbsolutePath().normalize(),
                ServerConfig.configPath("","",systemHome.toString(),environmentHome.toString()));
        assertEquals(environmentHome.resolve("config/att-server.yaml").toAbsolutePath().normalize(),
                ServerConfig.configPath("","","",environmentHome.toString()));
        assertNull(ServerConfig.configPath("","","",""));
    }

    @Test void missingDefaultCandidateAndNoConfigFailClearly() throws Exception {
        Path home=Files.createDirectories(temp.resolve("default-config-home"));
        Path candidate=ServerConfig.configPath("","","",home.toString());
        assertEquals(home.resolve("config/att-server.yaml").toAbsolutePath().normalize(),candidate);
        IllegalStateException missing=assertThrows(IllegalStateException.class,()->ServerConfig.load(candidate));
        assertTrue(missing.getMessage().contains(candidate.toString()));
        IllegalStateException absent=assertThrows(IllegalStateException.class,()->ServerConfig.load(ServerConfig.configPath("","","","")));
        assertTrue(absent.getMessage().contains("ATT_SERVER_CONFIG"));
    }

    @Test void authModeDefaultsToContainerAndLocalRejectsLegacyAnonymousMode() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("auth-packages"));Path pkg=Files.createDirectory(allowed.resolve("default"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path base=temp.resolve("auth-base.yaml");Files.writeString(base,"server:\n  dataDir: "+yaml(temp.resolve("auth-data"))+"\n  javaExecutable: "+yaml(java)+"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    default: "+yaml(pkg)+"\n");
        assertEquals("container",ServerConfig.load(base).authMode);
        Path local=temp.resolve("auth-local.yaml");Files.writeString(local,Files.readString(base).replace("auth-data","auth-local-data").replace("server:\n","server:\n  auth:\n    mode: local\n"));
        assertEquals("local",ServerConfig.load(local).authMode);
        Path anonymous=temp.resolve("auth-anonymous.yaml");Files.writeString(anonymous,Files.readString(local).replace("auth-local-data","auth-anonymous-data").replace("  auth:\n    mode: local","  authenticationRequired: false\n  auth:\n    mode: local"));
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->ServerConfig.load(anonymous));
        assertTrue(error.getMessage().contains("must be true when server.auth.mode is local"));
    }

    @Test void missingExplicitConfigDoesNotFallBackToAttHomeCandidate() throws Exception {
        Path home=Files.createDirectories(temp.resolve("config-home"));
        Path fallback=Files.createDirectories(home.resolve("config")).resolve("att-server.yaml");
        Files.writeString(fallback,"server: {}\n");
        Path missing=temp.resolve("missing-explicit.yaml");
        Path selected=ServerConfig.configPath(missing.toString(),"",home.toString(),"");
        assertEquals(missing.toAbsolutePath().normalize(),selected);
        IllegalStateException error=assertThrows(IllegalStateException.class,()->ServerConfig.load(selected));
        assertTrue(error.getMessage().contains(missing.toString()));
    }

    @Test void invalidExplicitConfigDoesNotFallBackAndReportsItsPath() throws Exception {
        Path invalid=Files.writeString(temp.resolve("invalid-server.yaml"),"server: [\n");
        Path allowed=Files.createDirectory(temp.resolve("fallback-packages"));
        Path pkg=Files.createDirectory(allowed.resolve("default"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path fallback=temp.resolve("valid-fallback.yaml");
        Files.writeString(fallback,"server:\n  dataDir: "+yaml(temp.resolve("fallback-data"))+"\n  javaExecutable: "+yaml(java)
                +"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    default: "+yaml(pkg)+"\n");
        assertEquals(pkg.toRealPath(),ServerConfig.load(fallback).packages.get("default"));
        Path selected=ServerConfig.configPath(invalid.toString(),fallback.toString(),temp.toString(),"");
        assertEquals(invalid.toAbsolutePath().normalize(),selected);
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->ServerConfig.load(selected));
        assertTrue(error.getMessage().contains(invalid.toString()));
    }

    @Test void allowsServerDataUnderAttHomeWhenDisjointFromPackages() throws Exception {
        Path home=Files.createDirectory(temp.resolve("install-home"));
        Path allowed=Files.createDirectories(home.resolve("packages"));
        Path pkg=Files.createDirectory(allowed.resolve("default"));
        Path runtimeConfig=Files.createDirectories(home.resolve("config")).resolve("config.yaml");
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n");
        Path data=home.resolve("data");
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path file=temp.resolve("home-data-server.yaml");
        Files.writeString(file,"server:\n  dataDir: "+yaml(data)+"\n  javaExecutable: "+yaml(java)
                +"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    default: "+yaml(pkg)+"\n");
        String prior=System.getProperty("att.home");
        try {
            System.setProperty("att.home",home.toString());
            ServerConfig config=ServerConfig.load(file);
            assertEquals(data.toRealPath(),config.dataDir);
            assertTrue(config.dataDir.startsWith(home.toRealPath()));
        } finally {
            if(prior==null)System.clearProperty("att.home");else System.setProperty("att.home",prior);
        }
    }

    @Test void rejectsDataDirectoryEqualToOrOverlappingPackageRoot() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("overlap-allowed"));
        Path pkg=Files.createDirectory(allowed.resolve("default"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        List<Path> overlapping=List.of(allowed,pkg,pkg.resolve("server-state"));
        for(int i=0;i<overlapping.size();i++) {
            Path data=overlapping.get(i);
            Path file=temp.resolve("overlap-"+i+".yaml");
            Files.writeString(file,"server:\n  dataDir: "+yaml(data)+"\n  javaExecutable: "+yaml(java)
                    +"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    default: "+yaml(pkg)+"\n");
            IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->ServerConfig.load(file));
            assertTrue(error.getMessage().contains("separate from configured package roots"),error.getMessage());
            if(data.startsWith(pkg)&&!data.equals(pkg))assertFalse(Files.exists(data));
        }
    }
    @Test void loadsCanonicalReadOnlyPackageMappingsAndCreatesPrivateDataDirectory() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("packages"));Path pkg=Files.createDirectory(allowed.resolve("payments"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path data=temp.resolve("server-data");Path file=temp.resolve("server.yaml");
        Files.writeString(file,"server:\n  dataDir: "+data+"\n  javaExecutable: "+java+"\nworkers:\n  maxConcurrent: 2\n  queuedLimit: 3\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    payments: "+pkg+"\n");
        ServerConfig config=ServerConfig.load(file);
        assertEquals(pkg.toRealPath(),config.packages.get("payments"));assertTrue(Files.isDirectory(data.resolve("db")));assertTrue(Files.isDirectory(data.resolve("jobs")));
        assertFalse(config.security.checkCrossOrigin);assertTrue(config.inspection.sourceBrowsingEnabled);assertFalse(config.inspection.redactionEnabled);
        assertFalse(config.inlineLoad.enabled);assertEquals(1000,config.inlineLoad.maxTotalConcurrent);
        assertThrows(UnsupportedOperationException.class,()->config.packages.put("other",pkg));
    }

    @Test void acceptsStrictOriginCheckingAndBoundsInspectionPolicySettings() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("policy-packages"));Path pkg=Files.createDirectory(allowed.resolve("p"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path configFile=temp.resolve("policy-server.yaml");Files.writeString(configFile,"server:\n  dataDir: "+yaml(temp.resolve("policy-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  security:\n    checkCrossOrigin: true\n  inspection:\n    sourceBrowsing:\n      enabled: true\n      policy: safe-package-files\n      denyGlobs: ['**/secrets/**']\n    redaction:\n      enabled: true\n      sensitivePaths: ['inputs.cardNumber']\n      sensitiveFieldPatterns: ['(?i)account.*']\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
        ServerConfig config=ServerConfig.load(configFile);
        assertTrue(config.security.checkCrossOrigin);assertTrue(config.inspection.redactionEnabled);
        assertEquals(List.of("**/secrets/**"),config.inspection.sourceDenyGlobs);
        assertEquals(List.of("inputs.cardnumber"),config.inspection.sensitivePaths);
        assertEquals(List.of("(?i)account.*"),config.inspection.sensitiveFieldPatterns);
    }

    @Test void validatesAggregateInlineLoadConcurrencyBound() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("inline-packages"));Path pkg=Files.createDirectory(allowed.resolve("p"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path valid=temp.resolve("inline-valid.yaml");Files.writeString(valid,"server:\n  dataDir: "+yaml(temp.resolve("inline-valid-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  inlineLoad:\n    enabled: true\n    maxTotalConcurrent: 42\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
        ServerConfig loaded=ServerConfig.load(valid);assertTrue(loaded.inlineLoad.enabled);assertEquals(42,loaded.inlineLoad.maxTotalConcurrent);
        for(String value:List.of("0","1000001")) {
            Path invalid=temp.resolve("inline-invalid-"+value+".yaml");Files.writeString(invalid,"server:\n  dataDir: "+yaml(temp.resolve("inline-invalid-data-"+value))+"\n  javaExecutable: "+yaml(java)
                    +"\n  inlineLoad:\n    maxTotalConcurrent: "+value+"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
            assertThrows(IllegalArgumentException.class,()->ServerConfig.load(invalid),"maxTotalConcurrent="+value);
        }
        Path unknown=temp.resolve("inline-unknown.yaml");Files.writeString(unknown,"server:\n  dataDir: "+yaml(temp.resolve("inline-unknown-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  inlineLoad:\n    enabled: true\n    maxTotalConcurent: 42\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->ServerConfig.load(unknown));assertTrue(error.getMessage().contains("unknown setting"));
    }
    @Test void rejectsPackageRootsOutsideConfiguredAllowedRoots() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("allowed"));Path outside=Files.createDirectory(temp.resolve("outside"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path file=temp.resolve("server.yaml");Files.writeString(file,"server:\n  dataDir: "+temp.resolve("data")+"\n  javaExecutable: "+java+"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    outside: "+outside+"\n");
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->ServerConfig.load(file));assertTrue(error.getMessage().contains("outside"));
    }

    @Test void loadsBoundedInspectionSettingsAndValidatesSupportedTextAllowlist() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("inspection-packages"));Path pkg=Files.createDirectory(allowed.resolve("payments"));
        Path safe=Files.createDirectories(pkg.resolve("tools")).resolve("safe.sh");Files.writeString(safe,"#!/bin/sh\necho ok\n");
        Path incidentYaml=Files.createDirectories(pkg.resolve("runtime/sit")).resolve("fppdb.yaml");Files.writeString(incidentYaml,"enabled: true\n");
        Path nestedYaml=Files.createDirectories(pkg.resolve("runtime/nested")).resolve("settings.yml");Files.writeString(nestedYaml,"enabled: true\n");
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path file=temp.resolve("inspection-server.yaml");
        Files.writeString(file,"server:\n  dataDir: "+yaml(temp.resolve("inspection-data"))+"\n  javaExecutable: "+yaml(java)+"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 5000\n    heapMaxMb: 128\n    safeTextSources:\n      payments:\n        - tools/safe.sh\n        - runtime/sit/fppdb.yaml\n        - runtime/nested/settings.yml\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    payments: "+yaml(pkg)+"\n");
        ServerConfig config=ServerConfig.load(file);
        assertTrue(config.inspection.enabled);assertEquals(1,config.inspection.maxConcurrent);assertEquals(2,config.inspection.queuedLimit);
        assertEquals("ATT_ADMIN",config.inspection.configurationAdminRole);
        assertEquals(List.of("tools/safe.sh","runtime/sit/fppdb.yaml","runtime/nested/settings.yml"),config.inspection.safeTextSources("payments"));
    }

    @Test void acceptsAllowlistedFilesFromTheSharedRuntimeConfiguration() throws Exception {
        Path home=Files.createDirectory(temp.resolve("shared-inspection-home"));
        Path runtimeConfig=Files.createDirectories(home.resolve("config")).resolve("config.yaml");
        Files.writeString(runtimeConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\n");
        Path sharedSource=Files.createDirectories(home.resolve("config/tools")).resolve("shared.sh");
        Files.writeString(sharedSource,"#!/bin/sh\necho shared\n");
        Path packages=Files.createDirectory(temp.resolve("shared-inspection-packages"));
        Path pkg=Files.createDirectory(packages.resolve("payments"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path file=Files.writeString(temp.resolve("shared-inspection-server.yaml"),"server:\n  dataDir: "+yaml(temp.resolve("shared-inspection-data"))
                +"\n  runtimeConfigPath: "+yaml(runtimeConfig)+"\n  javaExecutable: "+yaml(java)
                +"\n  inspection:\n    safeTextSources:\n      payments:\n        - config/tools/shared.sh"
                +"\nworkers: {}\npackages:\n  allowedRoots: ["+yaml(packages)+"]\n  entries:\n    payments: "+yaml(pkg)+"\n");
        assertEquals(List.of("config/tools/shared.sh"),ServerConfig.load(file).inspection.safeTextSources("payments"));
        Path packageShadow=Files.createDirectories(pkg.resolve("config/tools")).resolve("package-only.sh");
        Files.writeString(packageShadow,"#!/bin/sh\necho package\n");
        Path packageOnlyConfig=Files.writeString(temp.resolve("shared-inspection-package-only.yaml"),Files.readString(file).replace("shared.sh","package-only.sh"));
        assertThrows(IllegalArgumentException.class,()->ServerConfig.load(packageOnlyConfig),
                "config/ allowlist paths name the shared config tree and must not resolve from a Package");
    }

    @Test void validatesConfigurationInspectorAdminRole() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("inspection-admin-allowed"));Path pkg=Files.createDirectory(allowed.resolve("default"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path valid=Files.writeString(temp.resolve("inspection-admin.yaml"),"server:\n  dataDir: "+yaml(temp.resolve("inspection-admin-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  authorization:\n    adminRole: ATT_CONFIG_ADMIN\n  inspection:\n    configurationAdminRole: ATT_CONFIG_ADMIN\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    default: "+yaml(pkg)+"\n");
        assertEquals("ATT_CONFIG_ADMIN",ServerConfig.load(valid).inspection.configurationAdminRole);
        Path invalid=Files.writeString(temp.resolve("inspection-admin-invalid.yaml"),Files.readString(valid).replace("ATT_CONFIG_ADMIN","bad role"));
        assertThrows(IllegalArgumentException.class,()->ServerConfig.load(invalid));
    }

    @Test void rejectsInspectionSourcePathsOutsideThePackage() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("inspection-escape-packages"));Path pkg=Files.createDirectory(allowed.resolve("payments"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path file=temp.resolve("inspection-escape-server.yaml");
        Files.writeString(file,"server:\n  dataDir: "+yaml(temp.resolve("inspection-escape-data"))+"\n  javaExecutable: "+yaml(java)+"\n  inspection:\n    safeTextSources:\n      payments:\n        - ../outside.sh\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    payments: "+yaml(pkg)+"\n");
        assertThrows(IllegalArgumentException.class,()->ServerConfig.load(file));
    }

    @Test void validatesExternalInspectionRootsAndExactCanonicalScriptEntries() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("external-inspection-packages"));
        Path pkg=Files.createDirectory(allowed.resolve("payments"));
        Path externalRoot=Files.createDirectories(temp.resolve("approved-external-scripts"));
        Path script=Files.createDirectories(externalRoot.resolve("mq")).resolve("檢查佇列.sh");Files.writeString(script,"#!/bin/sh\necho ok\n");
        Path canonicalExternalRoot=externalRoot.toRealPath(),canonicalScript=script.toRealPath();
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path valid=temp.resolve("external-inspection-valid.yaml");Files.writeString(valid,"server:\n  dataDir: "+yaml(temp.resolve("external-inspection-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  inspection:\n    sourceBrowsing:\n      externalSourceRoots:\n        - "+yaml(canonicalExternalRoot)+"\n      externalTextSources:\n        payments:\n          - "+yaml(canonicalScript)+"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    payments: "+yaml(pkg)+"\n");
        ServerConfig config=ServerConfig.load(valid);
        assertEquals(List.of(canonicalExternalRoot.toString()),config.inspection.externalSourceRoots);
        assertEquals(List.of(canonicalScript.toString()),config.inspection.externalTextSources("payments"));

        Path outside=Files.createDirectories(temp.resolve("unapproved-external")).resolve("outside.sh");Files.writeString(outside,"echo no\n");
        Path canonicalOutside=outside.toRealPath();
        Path outsideConfig=temp.resolve("external-inspection-outside.yaml");Files.writeString(outsideConfig,"server:\n  dataDir: "+yaml(temp.resolve("external-inspection-outside-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  inspection:\n    sourceBrowsing:\n      externalSourceRoots:\n        - "+yaml(canonicalExternalRoot)+"\n      externalTextSources:\n        payments:\n          - "+yaml(canonicalOutside)+"\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    payments: "+yaml(pkg)+"\n");
        IllegalArgumentException outOfRoot=assertThrows(IllegalArgumentException.class,()->ServerConfig.load(outsideConfig));
        assertTrue(outOfRoot.getMessage().contains("externalSourceRoots"));

        Path relativeConfig=temp.resolve("external-inspection-relative.yaml");Files.writeString(relativeConfig,"server:\n  dataDir: "+yaml(temp.resolve("external-inspection-relative-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  inspection:\n    sourceBrowsing:\n      externalSourceRoots:\n        - "+yaml(canonicalExternalRoot)+"\n      externalTextSources:\n        payments:\n          - scripts/tool.sh\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    payments: "+yaml(pkg)+"\n");
        assertThrows(IllegalArgumentException.class,()->ServerConfig.load(relativeConfig));
    }

    @Test void schedulingIsDisabledByDefaultAndAnonymousAdministrationDefaultsOnOnlyInAnonymousMode() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("schedule-packages"));Path pkg=Files.createDirectory(allowed.resolve("p"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path defaults=temp.resolve("schedule-defaults.yaml");Files.writeString(defaults,"server:\n  dataDir: "+yaml(temp.resolve("schedule-default-data"))+"\n  javaExecutable: "+yaml(java)
                +"\nworkers:\n  maxConcurrentLoad: 2\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
        assertFalse(ServerConfig.load(defaults).scheduling.enabled);

        Path anonymousDefault=temp.resolve("schedule-anonymous-default.yaml");Files.writeString(anonymousDefault,"server:\n  dataDir: "+yaml(temp.resolve("schedule-anonymous-default-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  authenticationRequired: false\n  scheduling:\n    enabled: true\nworkers:\n  maxConcurrentLoad: 2\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
        ServerConfig anonymousDefaultConfig=ServerConfig.load(anonymousDefault);
        assertTrue(anonymousDefaultConfig.scheduling.allowAnonymousManagement);

        Path allowedAnonymous=temp.resolve("schedule-anonymous-allowed.yaml");Files.writeString(allowedAnonymous,"server:\n  dataDir: "+yaml(temp.resolve("schedule-anonymous-allowed-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  authenticationRequired: false\n  scheduling:\n    enabled: true\n    allowAnonymousManagement: true\n    adminRole: SIT_OPERATORS\nworkers:\n  maxConcurrentLoad: 2\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
        ServerConfig anonymous=ServerConfig.load(allowedAnonymous);assertTrue(anonymous.scheduling.enabled);assertTrue(anonymous.scheduling.allowAnonymousManagement);assertEquals("SIT_OPERATORS",anonymous.scheduling.adminRole);

        Path disabledAnonymous=temp.resolve("schedule-anonymous-disabled.yaml");Files.writeString(disabledAnonymous,"server:\n  dataDir: "+yaml(temp.resolve("schedule-anonymous-disabled-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  authenticationRequired: false\n  scheduling:\n    enabled: true\n    allowAnonymousManagement: false\nworkers:\n  maxConcurrentLoad: 2\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
        assertFalse(ServerConfig.load(disabledAnonymous).scheduling.allowAnonymousManagement);

        Path disabledInspection=temp.resolve("schedule-inspection-disabled.yaml");Files.writeString(disabledInspection,"server:\n  dataDir: "+yaml(temp.resolve("schedule-inspection-disabled-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  inspection:\n    enabled: false\n  scheduling:\n    enabled: true\nworkers:\n  maxConcurrentLoad: 2\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
        assertThrows(IllegalArgumentException.class,()->ServerConfig.load(disabledInspection));
    }

    @Test void schedulerConcurrencyCapCannotExceedLoadAdmissionCapacity() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("schedule-cap-packages"));Path pkg=Files.createDirectory(allowed.resolve("p"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path file=temp.resolve("schedule-cap.yaml");Files.writeString(file,"server:\n  dataDir: "+yaml(temp.resolve("schedule-cap-data"))+"\n  javaExecutable: "+yaml(java)
                +"\n  scheduling:\n    enabled: true\n    maxConcurrentRuns: 2\nworkers:\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    p: "+yaml(pkg)+"\n");
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->ServerConfig.load(file));
        assertTrue(error.getMessage().contains("must not exceed workers.maxConcurrentLoad"));
    }

    private static String yaml(Path path){return "'"+path.toAbsolutePath().toString().replace("'","''")+"'";}

    @Test void validatesAndLoadsBoundedWorkerHeapSettings() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("heap-packages"));Path pkg=Files.createDirectory(allowed.resolve("package"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path valid=temp.resolve("heap-valid.yaml");Files.writeString(valid,"server:\n  dataDir: "+temp.resolve("heap-data")+"\n  javaExecutable: "+java+"\nworkers:\n  heapInitialMb: 128\n  heapMaxMb: 1024\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        ServerConfig loaded=ServerConfig.load(valid);
        assertEquals(128,loaded.workerHeapInitialMb);assertEquals(1024,loaded.workerHeapMaxMb);

        Path invalid=temp.resolve("heap-invalid.yaml");Files.writeString(invalid,"server:\n  dataDir: "+temp.resolve("heap-invalid-data")+"\n  javaExecutable: "+java+"\nworkers:\n  heapInitialMb: 2048\n  heapMaxMb: 1024\npackages:\n  allowedRoots:\n    - "+allowed+"\n  entries:\n    p: "+pkg+"\n");
        IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->ServerConfig.load(invalid));
        assertTrue(error.getMessage().contains("must not exceed"));
    }
}
