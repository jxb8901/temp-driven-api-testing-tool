package att.server;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Resolves and validates the Local installation's shared Worker runtime before Server startup. */
final class WorkerRuntimeClasspath {
    private WorkerRuntimeClasspath() { }

    static Path resolve() throws Exception {
        return resolve(System.getProperty("att.home"),System.getenv("ATT_HOME"));
    }

    static Path resolve(String attHomeProperty,String attHomeEnvironment) throws Exception {
        String home=firstNonBlank(attHomeProperty,attHomeEnvironment);
        if(home==null)throw new IllegalStateException("ATT_HOME is required to launch Workers; set -Datt.home or the ATT_HOME environment variable to the matching Local installation");
        Path root=Paths.get(home).toAbsolutePath().normalize();
        Path lib=root.resolve("lib");
        if(!Files.isDirectory(lib)||!Files.isReadable(lib))
            throw new IllegalStateException("ATT Worker runtime library directory is missing or unreadable: "+lib);
        Path bundle=lib.resolve("att-local-"+ServerBuildInfo.PRODUCT+".jar");
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

    static java.util.List<String> entries(Path lib,List<Path> additionalDirectories) {
        if(lib==null||!Files.isDirectory(lib))
            throw new IllegalStateException("ATT Worker runtime library directory is unavailable; set -Datt.home or ATT_HOME to the matching Local installation");
        java.util.List<String> result=new java.util.ArrayList<>();
        Path runtime=lib.resolve("att-local-"+ServerBuildInfo.PRODUCT+".jar");
        if(Files.isRegularFile(runtime))result.add(runtime.toString());
        try(java.util.stream.Stream<Path> files=Files.list(lib)) {
            files.filter(Files::isRegularFile)
                    .filter(path->path.getFileName().toString().endsWith(".jar"))
                    .filter(path->!path.getFileName().toString().startsWith("att-local-"))
                    .sorted()
                    .map(Path::toString)
                    .forEach(result::add);
        } catch(java.io.IOException error) {
            throw new IllegalStateException("Unable to read ATT Worker runtime libraries: "+lib,error);
        }
        for(Path directory:additionalDirectories)result.add(directory.resolve("*").toString());
        return result;
    }

    private static String firstNonBlank(String preferred,String fallback) {
        if(preferred!=null&&!preferred.trim().isEmpty())return preferred.trim();
        return fallback==null||fallback.trim().isEmpty()?null:fallback.trim();
    }
}
