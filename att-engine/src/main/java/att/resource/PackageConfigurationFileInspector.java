package att.resource;

import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Bounded metadata listing for the registered package config directory. */
public final class PackageConfigurationFileInspector {
    public static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_TREE_ENTRIES = 5000;
    private static final int MAX_TREE_DEPTH = 12;

    private final PackageResourceResolver resources;
    private final Path packageRoot;
    private final Set<String> safeTextSources;
    private final boolean sourceBrowsingEnabled;
    private final String sourceBrowsingPolicy;
    private final List<String> sourceDenyGlobs;
    private final int maxSourceBytes;
    private final int maxResponseBytes;

    public PackageConfigurationFileInspector(Path packageRoot, Set<String> safeTextSources,
                                             int maxSourceBytes, int maxResponseBytes) {
        this(packageRoot,safeTextSources,maxSourceBytes,maxResponseBytes,true,"allowlist-only",Collections.<String>emptyList());
    }

    public PackageConfigurationFileInspector(Path packageRoot, Set<String> safeTextSources,
                                             int maxSourceBytes,int maxResponseBytes,boolean sourceBrowsingEnabled,
                                             String sourceBrowsingPolicy,List<String> sourceDenyGlobs) {
        this.resources = new PackageResourceResolver(packageRoot);
        this.packageRoot = resources.packageRoot();
        this.safeTextSources = safeTextSources == null ? Collections.emptySet() : new HashSet<>(safeTextSources);
        this.sourceBrowsingEnabled=sourceBrowsingEnabled;
        this.sourceBrowsingPolicy=sourceBrowsingPolicy==null?"safe-package-files":sourceBrowsingPolicy;
        this.sourceDenyGlobs=sourceDenyGlobs==null?Collections.emptyList():new ArrayList<>(sourceDenyGlobs);
        if (maxSourceBytes < 1 || maxResponseBytes < 1024 || maxSourceBytes >= maxResponseBytes)
            throw new IllegalArgumentException("Invalid configuration source limits");
        this.maxSourceBytes = maxSourceBytes;
        this.maxResponseBytes = maxResponseBytes;
    }

    public Map<String,Object> list(String requestedPath, String query, int offset, int limit) throws Exception {
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (query != null && (query.length() > 200 || hasControl(query)))
            throw new IllegalArgumentException("query must contain at most 200 printable characters");
        Path configRoot;
        try {
            configRoot = resources.resolvePackageRelative("config", PackageResourceResolver.Kind.DIRECTORY).canonicalPath();
        } catch (IllegalArgumentException missing) {
            return response(requestedPath, Collections.emptyList(), 0, offset, limit, false, "unavailable");
        }
        Path directory = resolveDirectory(configRoot, requestedPath);
        boolean searching = query != null && !query.trim().isEmpty();
        String needle = searching ? query.trim().toLowerCase(Locale.ROOT) : "";
        List<Path> matches = new ArrayList<>();
        boolean truncated = false;
        if (searching) {
            try (Stream<Path> stream = Files.walk(configRoot, MAX_TREE_DEPTH)) {
                int visited = 0;
                for (Path candidate : (Iterable<Path>) stream::iterator) {
                    if (candidate.equals(configRoot) || Files.isSymbolicLink(candidate)) continue;
                    if (++visited > MAX_TREE_ENTRIES * 20) { truncated = true; break; }
                    boolean directoryNode = Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS);
                    int depth = configRoot.relativize(candidate).getNameCount();
                    if (directoryNode && depth == MAX_TREE_DEPTH && hasChildren(candidate)) truncated = true;
                    if (!directoryNode && !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                    String logical = resources.fromInternalPath(candidate, kind(candidate)).logicalName();
                    if (!logical.toLowerCase(Locale.ROOT).contains(needle)) continue;
                    if (matches.size() == MAX_TREE_ENTRIES) { truncated = true; break; }
                    matches.add(candidate);
                }
            }
        } else {
            try (Stream<Path> stream = Files.list(directory)) {
                for (Path candidate : (Iterable<Path>) stream::iterator) {
                    if (Files.isSymbolicLink(candidate)) continue;
                    if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)
                            && !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (matches.size() == MAX_TREE_ENTRIES) { truncated = true; break; }
                    matches.add(candidate);
                }
            }
        }
        matches.sort(Comparator.comparing(path -> logicalName(path).toLowerCase(Locale.ROOT)));
        int total = matches.size();
        if (offset > total) throw new IllegalArgumentException("offset exceeds the bounded configuration listing");
        int end = Math.min(total, offset + limit);
        List<Map<String,Object>> items = new ArrayList<>();
        for (int index = offset; index < end; index++) items.add(node(matches.get(index)));
        Map<String,Object> result = response(searching ? "config" : logicalName(directory), items, total, offset, limit, truncated, "ready");
        if (end < total) result.put("nextOffset", end);
        return bounded(result);
    }

    private Path resolveDirectory(Path configRoot, String requestedPath) {
        if (requestedPath == null || requestedPath.codePoints().allMatch(Character::isWhitespace)
                || "config".equals(requestedPath) || "config/".equals(requestedPath)) return configRoot;
        if (requestedPath.length() > 1024 || hasControl(requestedPath) || requestedPath.startsWith("/")
                || requestedPath.endsWith("/") || requestedPath.contains("//") || requestedPath.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Configuration path is invalid");
        String[] segments = requestedPath.split("/", -1);
        int depth = segments.length - ("config".equals(segments[0]) ? 1 : 0);
        if (depth > MAX_TREE_DEPTH) throw new IllegalArgumentException("Configuration directory exceeds the depth limit");
        for (String segment : segments) if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))
            throw new IllegalArgumentException("Configuration path is invalid");
        PackageResourceResolver.PackageResource resolved = resources.resolvePackageRelative(requestedPath, PackageResourceResolver.Kind.DIRECTORY);
        Path path = resolved.canonicalPath();
        if (!path.startsWith(configRoot)) throw new IllegalArgumentException("Configuration path is outside config/");
        return path;
    }

    private Map<String,Object> node(Path path) {
        String logical = logicalName(path);
        String name = path.getFileName() == null ? logical : path.getFileName().toString();
        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("path", logical);
        result.put("kind", directory ? "directory" : "file");
        if (directory && logical.split("/").length - 1 >= MAX_TREE_DEPTH) result.put("reason", "depth-limit");
        if (!directory) {
            try {
                long size = Files.size(path);
                result.put("sizeBytes", size);
                result.put("format", format(logical));
                boolean textFile = textExtension(logical);
                String reason=null;
                if(!sourceBrowsingEnabled)reason="source-disabled";
                else if(sensitiveSourcePath(logical))reason="sensitive-path";
                else if(!textFile)reason="unsupported-file-type";
                else if(sourceGlobDenied(logical))reason="forbidden";
                else if("allowlist-only".equals(sourceBrowsingPolicy)&&!safeTextSources.contains(logical))reason="not-allowlisted";
                else if(!"allowlist-only".equals(sourceBrowsingPolicy)&&!"safe-package-files".equals(sourceBrowsingPolicy))reason="forbidden";
                else if(size>maxSourceBytes)reason="size-limit";
                else if(!isUtf8Text(path))reason="binary-file";
                result.put("sourceAvailable",reason==null);
                if(reason!=null)result.put("reason",reason);
            } catch (Exception ignored) {
                result.put("sourceAvailable", Boolean.FALSE);
                result.put("reason", "source-unavailable");
            }
        }
        return result;
    }

    private static boolean hasChildren(Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            return children.findAny().isPresent();
        } catch (Exception error) {
            return true;
        }
    }

    private PackageResourceResolver.Kind kind(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                ? PackageResourceResolver.Kind.DIRECTORY : PackageResourceResolver.Kind.FILE;
    }

    private String logicalName(Path path) {
        try {
            Path canonical = path.toRealPath();
            if (!canonical.startsWith(packageRoot)) throw new IllegalArgumentException("Configuration path escapes the package root");
            return packageRoot.relativize(canonical).toString().replace('\\', '/');
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalArgumentException("Configuration path is unavailable");
        }
    }

    private static String format(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "yaml";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".sh") || lower.endsWith(".bash")) return "shell";
        return "text";
    }

    private static boolean textExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json")
                || lower.endsWith(".xml") || lower.endsWith(".sql") || lower.endsWith(".sh")
                || lower.endsWith(".bash") || lower.endsWith(".txt") || lower.endsWith(".js")
                || lower.endsWith(".ts") || lower.endsWith(".py");
    }

    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return true;
        return false;
    }

    private boolean sourceGlobDenied(String logical) {
        String lower=logical.toLowerCase(Locale.ROOT);
        for(String glob:sourceDenyGlobs) {
            StringBuilder regex=new StringBuilder("^");String pattern=glob.toLowerCase(Locale.ROOT);
            for(int i=0;i<pattern.length();i++) {
                char c=pattern.charAt(i);
                if(c=='*') {
                    boolean recursive=i+1<pattern.length()&&pattern.charAt(i+1)=='*';
                    if(recursive) { i++; if(i+1<pattern.length()&&pattern.charAt(i+1)=='/') {regex.append("(?:.*/)?");i++;} else regex.append(".*"); }
                    else regex.append("[^/]*");
                } else if(c=='?')regex.append("[^/]");
                else {if(".(){}+$^|[]\\".indexOf(c)>=0)regex.append('\\');regex.append(c);}
            }
            if(lower.matches(regex.append('$').toString()))return true;
        }
        return false;
    }

    private static boolean sensitiveSourcePath(String logical) {
        String lower=logical.toLowerCase(Locale.ROOT);String base=lower.substring(lower.lastIndexOf('/')+1);
        return base.startsWith(".env")||base.contains("secret")||base.contains("credential")||base.contains("password")
                ||base.contains("token")||base.endsWith(".pem")||base.endsWith(".key")||base.endsWith(".p12")
                ||base.endsWith(".pfx")||base.endsWith(".jks")||base.endsWith(".keystore");
    }

    private boolean isUtf8Text(Path path) {
        try {
            byte[] bytes;
            try(java.io.InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
                java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count,total=0;
                while((count=input.read(buffer))!=-1) {total+=count;if(total>maxSourceBytes)return false;output.write(buffer,0,count);}
                bytes=output.toByteArray();
            }
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));
            for(byte value:bytes)if(value==0)return false;
            return true;
        } catch(Exception invalid) { return false; }
    }

    private Map<String,Object> response(String path, List<Map<String,Object>> items, int total, int offset,
                                        int limit, boolean truncated, String state) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("state", state);
        if (path != null) result.put("path", path);
        result.put("items", items);
        result.put("total", total);
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("nextOffset", null);
        result.put("truncated", truncated);
        return result;
    }

    private Map<String,Object> bounded(Map<String,Object> response) {
        if (new Yaml().dump(response).getBytes(StandardCharsets.UTF_8).length > maxResponseBytes)
            throw new PackageConfigurationInspector.ResponseTooLargeException();
        return response;
    }
}
