package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @TempDir Path temp;

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
        assertFalse(config.inlineLoad.enabled);assertEquals(1000,config.inlineLoad.maxTotalConcurrent);
        assertThrows(UnsupportedOperationException.class,()->config.packages.put("other",pkg));
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

    @Test void loadsBoundedInspectionSettingsAndValidatesScriptAllowlist() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("inspection-packages"));Path pkg=Files.createDirectory(allowed.resolve("payments"));
        Path safe=Files.createDirectories(pkg.resolve("tools")).resolve("safe.sh");Files.writeString(safe,"#!/bin/sh\necho ok\n");
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path file=temp.resolve("inspection-server.yaml");
        Files.writeString(file,"server:\n  dataDir: "+yaml(temp.resolve("inspection-data"))+"\n  javaExecutable: "+yaml(java)+"\n  inspection:\n    maxConcurrent: 1\n    queuedLimit: 2\n    timeoutMs: 5000\n    heapMaxMb: 128\n    safeTextSources:\n      payments:\n        - tools/safe.sh\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    payments: "+yaml(pkg)+"\n");
        ServerConfig config=ServerConfig.load(file);
        assertTrue(config.inspection.enabled);assertEquals(1,config.inspection.maxConcurrent);assertEquals(2,config.inspection.queuedLimit);
        assertEquals(List.of("tools/safe.sh"),config.inspection.safeTextSources("payments"));
    }

    @Test void rejectsInspectionSourcePathsOutsideThePackage() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("inspection-escape-packages"));Path pkg=Files.createDirectory(allowed.resolve("payments"));
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name","").toLowerCase().contains("win")?"java.exe":"java");
        Path file=temp.resolve("inspection-escape-server.yaml");
        Files.writeString(file,"server:\n  dataDir: "+yaml(temp.resolve("inspection-escape-data"))+"\n  javaExecutable: "+yaml(java)+"\n  inspection:\n    safeTextSources:\n      payments:\n        - ../outside.sh\nworkers: {}\npackages:\n  allowedRoots:\n    - "+yaml(allowed)+"\n  entries:\n    payments: "+yaml(pkg)+"\n");
        assertThrows(IllegalArgumentException.class,()->ServerConfig.load(file));
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
