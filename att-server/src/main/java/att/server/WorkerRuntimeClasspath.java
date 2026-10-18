package att.server;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Resolves and validates the Local installation's shared Worker runtime before Server startup. */
final class WorkerRuntimeClasspath {
    private static final long MAX_SCHEMA_REGISTRY_BYTES=16L*1024L*1024L;
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
            String runtimeCommit=properties.getProperty("att.gitCommit","unknown").trim();
            if(!"unknown".equals(ServerBuildInfo.GIT_COMMIT)&&!ServerBuildInfo.GIT_COMMIT.equals(runtimeCommit))
                throw new IllegalStateException("ATT Server and Local Worker Runtime build identities do not match; install the matching Local archive");
            schemaRegistryDigest(jar);
            for(String required:new String[]{"att/security/SecretProtection.class","att/worker/WorkerMain.class","att/server/api/WorkerRequest.class","att/server/api/internal/DiagnosticSanitizer.class"})
                if(jar.getJarEntry(required)==null)throw new IllegalStateException("ATT Worker runtime is incomplete (missing "+required+"): "+bundle);
        } catch(IllegalStateException failure) {
            throw failure;
        } catch(Exception invalid) {
            throw new IllegalStateException("ATT Worker runtime is invalid or unreadable: "+bundle+"; reinstall the matching ATT Local archive",invalid);
        }
        return lib;
    }

    /** Computes and validates the catalog identity in a Local runtime JAR, when present. */
    static String schemaRegistryDigestIfPresent(Path lib) throws Exception {
        if(lib==null)return null;
        Path bundle=lib.resolve("att-local-"+ServerBuildInfo.PRODUCT+".jar");
        if(!Files.isRegularFile(bundle))return null;
        try(JarFile jar=new JarFile(bundle.toFile())) { return schemaRegistryDigest(jar); }
        catch(Exception invalid) {
            throw new IllegalStateException("ATT Worker runtime Schema Registry is invalid or unreadable; reinstall the matching ATT Local archive",invalid);
        }
    }

    private static String schemaRegistryDigest(JarFile jar) throws Exception {
        Map<String,byte[]> files=new TreeMap<>();
        long totalBytes=0;
        Enumeration<JarEntry> entries=jar.entries();
        while(entries.hasMoreElements()) {
            JarEntry entry=entries.nextElement();
            String name=entry.getName();
            if(!name.startsWith("att-schemas/")||entry.isDirectory())continue;
            String relative=name.substring("att-schemas/".length());
            if(!relative.matches("(?:history/)?[A-Za-z0-9._-]+\\.(?:json|xsd)|catalog\\.yaml"))
                throw new IllegalStateException("ATT Worker runtime Schema Registry contains an unsafe resource path");
            byte[] content=read(jar.getInputStream(entry),MAX_SCHEMA_REGISTRY_BYTES-totalBytes);
            totalBytes+=content.length;
            if(totalBytes>MAX_SCHEMA_REGISTRY_BYTES)
                throw new IllegalStateException("ATT Worker runtime Schema Registry exceeds its size limit");
            if(files.put(relative,content)!=null)
                throw new IllegalStateException("ATT Worker runtime Schema Registry contains duplicate resources");
        }
        byte[] catalogBytes=files.get("catalog.yaml");
        if(catalogBytes==null||catalogBytes.length>1024*1024)
            throw new IllegalStateException("ATT Worker runtime Schema Registry catalog is missing or oversized");
        LoaderOptions options=new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        Object parsed=new Yaml(new SafeConstructor(options)).load(new String(catalogBytes,StandardCharsets.UTF_8));
        if(!(parsed instanceof Map<?,?>)||!"att-schema-catalog/v3.0".equals(String.valueOf(((Map<?,?>)parsed).get("schemaVersion"))))
            throw new IllegalStateException("ATT Worker runtime Schema Registry catalog is invalid");
        Object rawSchemas=((Map<?,?>)parsed).get("schemas");
        if(!(rawSchemas instanceof Map<?,?>)||((Map<?,?>)rawSchemas).isEmpty())
            throw new IllegalStateException("ATT Worker runtime Schema Registry is empty");
        Set<String> registered=new HashSet<>(),filenames=new HashSet<>();
        registered.add("catalog.yaml");
        for(Map.Entry<?,?> registration:((Map<?,?>)rawSchemas).entrySet()) {
            if(!(registration.getKey() instanceof String)||!(registration.getValue() instanceof String))
                throw new IllegalStateException("ATT Worker runtime Schema Registry has an invalid registration");
            String relative=((String)registration.getValue()).replace('\\','/');
            if(!relative.matches("(?:history/)?[A-Za-z0-9._-]+\\.(?:json|xsd)"))
                throw new IllegalStateException("ATT Worker runtime Schema Registry contains an unsafe registration");
            String filename=relative.substring(relative.lastIndexOf('/')+1);
            if(!filenames.add(filename)||!files.containsKey(relative))
                throw new IllegalStateException("ATT Worker runtime Schema Registry has a missing or duplicate registered resource");
            registered.add(relative);
        }
        if(!registered.equals(files.keySet()))
            throw new IllegalStateException("ATT Worker runtime Schema Registry contains unregistered resources");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(Map.Entry<String,byte[]> file:files.entrySet()) {
            digest.update(file.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte)0);
            digest.update(file.getValue());
            digest.update((byte)0);
        }
        StringBuilder result=new StringBuilder();
        for(byte value:digest.digest())result.append(String.format(java.util.Locale.ROOT,"%02x",value&0xff));
        return result.toString();
    }

    private static byte[] read(InputStream input,long maximumBytes) throws IOException {
        try(InputStream stream=input;ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;
            while((count=stream.read(buffer))>=0) {
                if(output.size()+count>maximumBytes)throw new IOException("Schema Registry resource exceeds its size limit");
                output.write(buffer,0,count);
            }
            return output.toByteArray();
        }
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
