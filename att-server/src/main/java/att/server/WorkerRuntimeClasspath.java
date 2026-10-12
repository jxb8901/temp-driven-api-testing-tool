package att.server;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Resolves and validates the Local installation's shared Worker runtime before Server startup. */
final class WorkerRuntimeClasspath {
    private WorkerRuntimeClasspath() { }

    static Path resolve() throws Exception {
        String home=firstNonBlank(System.getProperty("att.home"),System.getenv("ATT_HOME"));
        if(home==null)throw new IllegalStateException("ATT_HOME is required to launch Workers; set -Datt.home or the ATT_HOME environment variable to the matching Local installation");
        Path root=Paths.get(home).toAbsolutePath().normalize();
        Path lib=root.resolve("lib");
        if(!Files.isDirectory(lib)||!Files.isReadable(lib))
            throw new IllegalStateException("ATT Worker runtime library directory is missing or unreadable: "+lib);
        Path bundle=lib.resolve("att-"+ServerBuildInfo.PRODUCT+".jar");
        if(!Files.isRegularFile(bundle)||!Files.isReadable(bundle))
            throw new IllegalStateException("ATT Worker runtime for Server "+ServerBuildInfo.PRODUCT+" is missing: expected "+bundle+"; install the matching ATT Local archive");
        try(JarFile jar=new JarFile(bundle.toFile())) {
            JarEntry metadata=jar.getJarEntry("att-build.properties");
            if(metadata==null)throw new IllegalStateException("ATT Worker runtime is missing build metadata: "+bundle);
            Properties properties=new Properties();
            try(InputStream input=jar.getInputStream(metadata)){properties.load(input);}
            String runtimeVersion=properties.getProperty("att.version","").trim();
            if(!ServerBuildInfo.PRODUCT.equals(runtimeVersion))
                throw new IllegalStateException("ATT Server "+ServerBuildInfo.PRODUCT+" requires matching ATT Local Worker runtime; "+bundle+" reports version "+(runtimeVersion.isEmpty()?"unknown":runtimeVersion));
            for(String required:new String[]{"att/worker/WorkerMain.class","att/server/api/WorkerRequest.class","att/server/api/internal/DiagnosticSanitizer.class"})
                if(jar.getJarEntry(required)==null)throw new IllegalStateException("ATT Worker runtime is incomplete (missing "+required+"): "+bundle);
        } catch(IllegalStateException failure) {
            throw failure;
        } catch(Exception invalid) {
            throw new IllegalStateException("ATT Worker runtime is invalid or unreadable: "+bundle+"; reinstall the matching ATT Local archive",invalid);
        }
        return lib;
    }

    private static String firstNonBlank(String preferred,String fallback) {
        if(preferred!=null&&!preferred.trim().isEmpty())return preferred.trim();
        return fallback==null||fallback.trim().isEmpty()?null:fallback.trim();
    }
}
