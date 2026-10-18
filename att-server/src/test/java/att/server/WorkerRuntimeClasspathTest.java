package att.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class WorkerRuntimeClasspathTest {
    @TempDir Path temp;

    @Test void resolvesSystemPropertyBeforeEnvironmentAndRequiresMatchingBundle() throws Exception {
        Path propertyHome=runtimeHome("property-home",ServerBuildInfo.PRODUCT);
        Path environmentHome=runtimeHome("environment-home",ServerBuildInfo.PRODUCT);
        assertEquals(propertyHome.resolve("lib"),WorkerRuntimeClasspath.resolve(propertyHome.toString(),environmentHome.toString()));
        assertEquals(att.validation.SchemaFiles.registryDigest(),
                WorkerRuntimeClasspath.schemaRegistryDigestIfPresent(propertyHome.resolve("lib")),
                "Server startup and Worker classpath must agree on the embedded registry identity");
    }

    @Test void reportsMissingHomeAndLibraryDirectoryActionably() throws Exception {
        IllegalStateException noHome=assertThrows(IllegalStateException.class,()->WorkerRuntimeClasspath.resolve(null," "));
        assertTrue(noHome.getMessage().contains("ATT_HOME"));
        Path home=Files.createDirectory(temp.resolve("missing-lib"));
        IllegalStateException noLibrary=assertThrows(IllegalStateException.class,()->WorkerRuntimeClasspath.resolve(home.toString(),null));
        assertTrue(noLibrary.getMessage().contains("library directory"));
    }

    @Test void rejectsInvalidAndMismatchedRuntimeBundles() throws Exception {
        Path invalidHome=Files.createDirectories(temp.resolve("invalid/lib"));
        Files.writeString(invalidHome.resolve(bundleName()),"not a jar");
        IllegalStateException invalid=assertThrows(IllegalStateException.class,
                ()->WorkerRuntimeClasspath.resolve(invalidHome.getParent().toString(),null));
        assertTrue(invalid.getMessage().contains("invalid or unreadable"));

        Path mismatchHome=runtimeHome("mismatch-home","0.0.0");
        IllegalStateException mismatch=assertThrows(IllegalStateException.class,
                ()->WorkerRuntimeClasspath.resolve(mismatchHome.toString(),null));
        assertTrue(mismatch.getMessage().contains("requires matching"));
    }

    @Test void buildsWorkerClasspathFromInstallAndThenConfiguredDirectories() throws Exception {
        Path localLib=Files.createDirectories(temp.resolve("att-home/lib"));
        Path jdbc=Files.createDirectories(temp.resolve("jdbc"));
        Path mq=Files.createDirectories(temp.resolve("mq"));
        Path runtime=Files.writeString(localLib.resolve(bundleName()),"runtime");
        Path staleRuntime=Files.writeString(localLib.resolve("att-local-4.0.0.jar"),"stale runtime");
        Path thirdParty=Files.writeString(localLib.resolve("jackson.jar"),"dependency");
        assertEquals(List.of(runtime.toString(),thirdParty.toString(),jdbc.resolve("*").toString(),mq.resolve("*").toString()),
                WorkerRuntimeClasspath.entries(localLib,List.of(jdbc,mq)));
        assertFalse(WorkerRuntimeClasspath.entries(localLib,List.of()).contains(staleRuntime.toString()));
    }

    private Path runtimeHome(String name,String version) throws Exception {
        Path home=Files.createDirectories(temp.resolve(name));
        Path lib=Files.createDirectories(home.resolve("lib"));
        Path bundle=lib.resolve(bundleName());
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(bundle))) {
            add(jar,"att-build.properties",("att.version="+version+"\n").getBytes(StandardCharsets.UTF_8));
            add(jar,"att/security/SecretProtection.class",new byte[]{0});
            add(jar,"att/worker/WorkerMain.class",new byte[]{0});
            add(jar,"att/server/api/WorkerRequest.class",new byte[]{0});
            add(jar,"att/server/api/internal/DiagnosticSanitizer.class",new byte[]{0});
            Path schemas=Paths.get("att-engine/src/main/resources/att-schemas");
            try(java.util.stream.Stream<Path> paths=Files.walk(schemas)) {
                for(Path source:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator) {
                    String entry="att-schemas/"+schemas.relativize(source).toString().replace(java.io.File.separatorChar,'/');
                    add(jar,entry,Files.readAllBytes(source));
                }
            }
        }
        return home;
    }

    private static String bundleName() { return "att-local-"+ServerBuildInfo.PRODUCT+".jar"; }

    private static void add(JarOutputStream jar,String name,byte[] content) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }
}
