package att.config;

import att.core.IdentifierValidator;
import att.resource.PackageResourceResolver;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Isolates descriptor failures only after validating the complete authored path boundary. */
final class HelperDiscoveryLoader {
    interface Loader<T> { Map<String, T> load(Object paths, Path root) throws Exception; }

    static <T> Map<String, T> load(String type, Object configured, Path root,
                                 EnvironmentReferences references, Loader<T> loader) throws Exception {
        if (!references.isDiscovery()) return loader.load(configured, root);
        if (configured == null) return Collections.emptyMap();
        if (!(configured instanceof Iterable)) throw new IllegalArgumentException("Helper paths must be a list");
        String schema = "db".equals(type) ? "att-dbhelper-v2.6.schema.json"
                : "mq".equals(type) ? "att-mqhelper-v1.3.schema.json"
                : "ssh".equals(type) ? "att-sshhelper-v1.0.schema.json" : "att-httphelper-v1.1.schema.json";
        // Trusted infrastructure failure is not a malformed resource and must remain fatal.
        att.validation.JsonSchemaVerifier.compile(att.validation.SchemaFiles.resolve(root, schema));
        PackageResourceResolver resources = new PackageResourceResolver(root);
        Map<String, Path> paths = new LinkedHashMap<String, Path>();
        long bytes = 0;
        for (Object item : (Iterable<?>) configured) {
            if (!(item instanceof String)) throw new IllegalArgumentException("Helper paths must be strings");
            String name = ((String) item).trim();
            if (name.length() > 1024 || name.matches("(?s).*[\\p{Cntrl}].*"))
                throw new IllegalArgumentException("Invalid Helper descriptor name");
            Path relative = IdentifierValidator.relativePath(name, "Helper descriptor");
            Path logical = resources.packageRoot().resolve(relative).normalize();
            if (!name.matches(".+\\.ya?ml") || !logical.startsWith(resources.packageRoot().resolve("config")))
                throw new IllegalArgumentException("Helper descriptors must remain inside shared config/");
            // Check existing ancestors too, including when the final descriptor is absent.
            for (Path current = logical; !current.equals(resources.packageRoot()); current = current.getParent())
                if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Unsafe Helper descriptor path");
            resources.internalCandidate(logical);
            String key = resources.packageRoot().relativize(logical).toString().replace('\\', '/');
            if (paths.put(key, logical) != null) throw new IllegalArgumentException("Duplicate Helper descriptor path");
            if (paths.size() > 256) throw new IllegalArgumentException("Helper discovery descriptor limit exceeded");
            if (Files.isRegularFile(logical, LinkOption.NOFOLLOW_LINKS)) {
                long size = Files.size(logical);
                bytes += size;
                if (size > 3_000_000 || bytes > 16_777_216)
                    throw new IllegalArgumentException("Helper discovery byte limit exceeded");
            }
        }
        Map<String, T> result = new LinkedHashMap<String, T>();
        Set<String> ids = new HashSet<String>();
        for (Map.Entry<String, Path> path : paths.entrySet()) {
            if (!Files.isRegularFile(path.getValue(), LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path.getValue())) {
                references.helperFailure(type, path.getKey(), "unavailable");
                continue;
            }
            Map<String, T> parsed;
            int checkpoint = references.requirementCount();
            try {
                parsed = loader.load(Collections.singletonList(path.getKey()), root);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (Exception invalid) {
                references.discardRequirementsAfter(checkpoint);
                references.helperFailure(type, path.getKey(), "invalid");
                continue;
            }
            for (String id : parsed.keySet())
                if (!ids.add(id.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate Helper id");
            result.putAll(parsed);
        }
        return Collections.unmodifiableMap(result);
    }
}
