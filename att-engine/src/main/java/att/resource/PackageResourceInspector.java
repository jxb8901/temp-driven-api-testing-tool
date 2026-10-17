package att.resource;

import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.config.RuntimeConfigPath;
import att.config.SuiteConfigResolver;
import att.config.ToolArgumentConfig;
import att.config.ToolConfig;
import att.core.StageCaseData;
import att.core.TestCase;
import att.excel.ExcelTestSuiteLoader;
import att.flow.FlowDefinition;
import att.flow.FlowRegistry;
import att.template.StageTemplate;
import att.template.StageTemplateLoader;
import att.template.TemplateAction;
import att.template.HelperCallOperations;
import att.template.FileExpressionResolver;
import att.debug.DebugEngine;
import att.load.LoadScenario;
import att.load.LoadScenarioLoader;
import att.config.YamlSupport;
import att.metrics.InspectionMetrics;
import static att.metrics.InspectionMetrics.Stage.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-only logical package index used by the authenticated Server inspector Worker.
 * It only opens resources reachable through the package configuration and never executes Tools.
 */
public final class PackageResourceInspector implements AutoCloseable {
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_INLINE_BACK_REFERENCES = 100;
    private static final int MAX_SOURCE_REFERENCES = 100;
    private static final int MAX_NESTED_SOURCE_DEPTH = 8;
    private static final int MAX_NESTED_SOURCE_FILES = 256;
    private static final int MAX_RESOURCES = 20000;
    private static final int MAX_WORKBOOKS = 500;
    private static final long MAX_TOTAL_WORKBOOK_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;
    private static final long MAX_WORKBOOK_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_REVISION_BYTES = 256L * 1024L * 1024L;
    private static final Pattern TOOL_CALL = Pattern.compile("#\\{\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s*\\(");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)([\"']?(?:password|passwd|passphrase|token|secret|authorization|credential|api[_-]?key|access[_-]?key|private[_-]?key|client[_-]?secret|refresh[_-]?token)[\"']?\\s*(?::|=|\\bis\\b)\\s*[\"']?)([^\\s,'\";}<>]+)");
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9+/=_-]+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(\\b(?:jdbc:)?[a-z][a-z0-9+.-]*://)[^/@\\s:]+:[^/@\\s]+@");
    private static final Pattern XML_SECRET = Pattern.compile(
            "(?is)(<(password|passwd|passphrase|token|secret|authorization|credential|api[_-]?key|access[_-]?key|private[_-]?key|client[_-]?secret)(?:\\s+[^>]*)?>)[^<]*(</\\2\\s*>)");
    private static final Pattern SOURCE_PII_VALUE = Pattern.compile(
            "(?i)([\\\"']?(?:e-?mail|phone(?:number)?|mobile(?:number)?|address|account(?:number|id)?|ssn|socialsecuritynumber|nationalid|passport|dateofbirth|birthdate|taxid|creditcard|cardnumber|cvv|iban|routingnumber)[\\\"']?\\s*(?::|=)\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^,\\r\\n}]+)");
    private static final Pattern ACCOUNT_IDENTIFIER = Pattern.compile("(?i)(\\baccount(?:\\s+(?:number|id))?\\s+)([A-Za-z0-9][A-Za-z0-9-]{5,})");
    private static final Pattern XML_CLOSE_TAG = Pattern.compile("</[A-Za-z][A-Za-z0-9:_.-]*\\s*>");
    private static final Pattern XML_PII = Pattern.compile(
            "(?is)(<(email|e-?mail|phone(?:number)?|mobile(?:number)?|address|ssn|socialsecuritynumber|nationalid|passport|dateofbirth|birthdate|taxid|creditcard|cardnumber|cvv|iban|routingnumber)(?:\\s+[^>]*)?>)[^<]*(</\\2\\s*>)");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----");

    private final Path packageRoot;
    private final Path packageInputRoot;
    private final PackageResourceResolver resources;
    private final Path runtimeConfigRoot;
    private final PackageResourceResolver configResources;
    private final Path configPath;
    private final String environment;
    private final Set<String> safeTextSources;
    private final int maxSourceBytes;
    private final int maxResponseBytes;
    private final boolean sourceBrowsingEnabled,redactionEnabled;
    private final String sourceBrowsingPolicy;
    private final List<String> sourceDenyGlobs,sensitivePaths,sensitiveFieldPatterns;
    private final Set<String> externalTextSources;
    private final List<Path> externalSourceRoots;
    private final boolean cacheEnabled;
    private volatile Index cachedIndex;
    private volatile long cachedIndexVersion = Long.MIN_VALUE;
    private volatile PackageWatch packageWatch;
    private volatile boolean cacheUnavailable;
    private volatile long indexBuildCount;

    public PackageResourceInspector(Path packageRoot, Path configPath, String environment,
                                    Collection<String> safeTextSources,
                                    int maxSourceBytes, int maxResponseBytes) {
        this(packageRoot,configPath,environment,safeTextSources,maxSourceBytes,maxResponseBytes,false);
    }

    public PackageResourceInspector(Path packageRoot, Path configPath, String environment,
                                    Collection<String> safeTextSources,
                                    int maxSourceBytes, int maxResponseBytes, boolean cacheEnabled) {
        this(packageRoot,configPath,environment,safeTextSources,maxSourceBytes,maxResponseBytes,cacheEnabled,
                true,"allowlist-only",Collections.<String>emptyList(),true,
                Collections.<String>emptyList(),Collections.<String>emptyList());
    }

    public PackageResourceInspector(Path packageRoot, Path configPath, String environment,
                                    Collection<String> safeTextSources,int maxSourceBytes,int maxResponseBytes,
                                    boolean cacheEnabled,boolean sourceBrowsingEnabled,String sourceBrowsingPolicy,
                                    Collection<String> sourceDenyGlobs,boolean redactionEnabled,
                                    Collection<String> sensitivePaths,Collection<String> sensitiveFieldPatterns) {
        this(packageRoot,configPath,environment,safeTextSources,Collections.<String>emptyList(),Collections.<String>emptyList(),
                maxSourceBytes,maxResponseBytes,cacheEnabled,sourceBrowsingEnabled,sourceBrowsingPolicy,sourceDenyGlobs,
                redactionEnabled,sensitivePaths,sensitiveFieldPatterns);
    }

    public PackageResourceInspector(Path packageRoot, Path configPath, String environment,
                                    Collection<String> safeTextSources,Collection<String> externalTextSources,
                                    Collection<String> externalSourceRoots,int maxSourceBytes,int maxResponseBytes,
                                    boolean cacheEnabled,boolean sourceBrowsingEnabled,String sourceBrowsingPolicy,
                                    Collection<String> sourceDenyGlobs,boolean redactionEnabled,
                                    Collection<String> sensitivePaths,Collection<String> sensitiveFieldPatterns) {
        this.resources = new PackageResourceResolver(packageRoot);
        this.packageRoot = resources.packageRoot();
        this.packageInputRoot = packageRoot.toAbsolutePath().normalize();
        this.configPath = configPath == null ? Paths.get("config/config.yaml") : configPath;
        Path selectedConfig = RuntimeConfigPath.resolve(this.configPath, this.packageRoot);
        this.runtimeConfigRoot = RuntimeConfigPath.root(selectedConfig);
        this.configResources = new PackageResourceResolver(this.runtimeConfigRoot);
        this.environment = environment;
        this.safeTextSources = new HashSet<String>(
                safeTextSources == null ? Collections.<String>emptySet() : safeTextSources);
        this.externalTextSources = new HashSet<String>(externalTextSources == null ? Collections.<String>emptySet() : externalTextSources);
        List<Path> roots=new ArrayList<Path>();
        if(externalSourceRoots!=null)for(String root:externalSourceRoots)try {
            Path path=Paths.get(root).toRealPath();
            roots.add(path);
        } catch(Exception ignored) { /* Invalid Worker-supplied roots fail closed at access time. */ }
        this.externalSourceRoots=Collections.unmodifiableList(roots);
        this.maxSourceBytes = positive(maxSourceBytes, 65536, "maxSourceBytes");
        this.maxResponseBytes = positive(maxResponseBytes, 262144, "maxResponseBytes");
        this.cacheEnabled = cacheEnabled;
        this.sourceBrowsingEnabled=sourceBrowsingEnabled;
        this.sourceBrowsingPolicy=sourceBrowsingPolicy==null?"safe-package-files":sourceBrowsingPolicy;
        this.sourceDenyGlobs=sourceDenyGlobs==null?Collections.<String>emptyList():new ArrayList<String>(sourceDenyGlobs);
        this.redactionEnabled=redactionEnabled;
        this.sensitivePaths=sensitivePaths==null?Collections.<String>emptyList():new ArrayList<String>(sensitivePaths);
        this.sensitiveFieldPatterns=sensitiveFieldPatterns==null?Collections.<String>emptyList():new ArrayList<String>(sensitiveFieldPatterns);
    }


    /** Projects a config-tree file or a file referenced by a previously authorized source projection. */
    public Map<String,Object> inspectPackageFileSource(String locator, String originPath) throws Exception {
        return inspectPackageFileSource(locator,originPath,null);
    }

    public Map<String,Object> inspectPackageFileSource(String locator, String originPath, String referenceId) throws Exception {
        if (locator == null || locator.length() > 1024) throw new IllegalArgumentException("Package file locator is invalid");
        try {
            boolean linked=originPath!=null&&!originPath.codePoints().allMatch(Character::isWhitespace);
            if(linked!=(referenceId!=null&&!referenceId.trim().isEmpty()))throw new ResourceNotFoundException();
            PackageResourceResolver.ResourceOrigin origin=resources.origin(packageRoot);
            PackageResourceResolver.PackageResource sourceFile=null;
            if(linked) {
                sourceFile=resources.resolvePackageRelative(originPath,PackageResourceResolver.Kind.FILE);
                if(!packageSourceAllowed(sourceFile.logicalName(),sourceFile.canonicalPath()))throw new ResourceNotFoundException();
                origin=resources.origin(sourceFile.canonicalPath().getParent());
            }
            PackageResourceResolver.PackageResource file = resources.resolve(
                    new PackageResourceResolver.ResourceLocator(locator), origin, PackageResourceResolver.Kind.FILE);
            String logicalPath = file.logicalName();
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("available", Boolean.FALSE);
            if (protectedRuntimeConfigPath(logicalPath)) {
                result.put("reason", "forbidden");
                return bounded(result);
            }
            if (!linked&&!logicalPath.startsWith("config/")) {
                result.put("reason","forbidden");
                return bounded(result);
            }
            if (!textExtension(logicalPath)) {
                result.put("reason", "unsupported-file-type");
                return bounded(result);
            }
            if (!packageSourceAllowed(logicalPath,file.canonicalPath())) {
                result.put("reason", sourceDisabledOrDenied(logicalPath));
                return bounded(result);
            }
            if(linked&&!directlyReferenced(sourceFile.canonicalPath(),file.canonicalPath()))throw new ResourceNotFoundException();
            if (Files.size(file.canonicalPath()) > maxSourceBytes) {
                result.put("reason", "size-limit");
                return bounded(result);
            }
            String text = decodeUtf8(readBounded(file.canonicalPath(), maxSourceBytes));
            if (text.indexOf('\0') >= 0) {
                result.put("reason", "binary-file");
                return bounded(result);
            }
            boolean yamlConfiguration = logicalPath.startsWith("config/")
                    && (logicalPath.toLowerCase(Locale.ROOT).endsWith(".yaml") || logicalPath.toLowerCase(Locale.ROOT).endsWith(".yml"));
            String safe;
            boolean redacted;
            if (yamlConfiguration) {
                Object authored = loadYaml(file.canonicalPath(), maxSourceBytes);
                Object projected = sourceYamlProjection(authored, "", logicalPath);
                safe = dumpYaml(projected);
                redacted = !deepEquals(authored, projected);
            } else {
                safe = scrubSourceText(text, logicalPath);
                redacted = !safe.equals(text);
            }
            if (utf8(safe).length > maxSourceBytes) {
                result.put("reason", "size-limit");
                return bounded(result);
            }
            result.put("available", Boolean.TRUE);
            result.put("logicalPath", logicalPath);
            result.put("format", sourceFormat(logicalPath));
            result.put("text", safe);
            result.put("redacted", Boolean.valueOf(redacted));
            SourceFileReferences references = packageFileSourceReferences(file.canonicalPath(), logicalPath, safe);
            result.put("fileReferences", references.items);
            result.put("fileReferencesTruncated", Boolean.valueOf(references.truncated));
            return bounded(result);
        } catch (ResourceNotFoundException error) {
            throw error;
        } catch (ResponseTooLargeException error) {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("available", Boolean.FALSE);
            result.put("reason", "size-limit");
            return bounded(result);
        } catch (IllegalArgumentException error) {
            throw new ResourceNotFoundException();
        }
    }

    private boolean directlyReferenced(Path sourcePath,Path targetPath) {
        try {
            String source=decodeUtf8(readBounded(sourcePath,maxSourceBytes));
            FileExpressionResolver resolver=new FileExpressionResolver(packageRoot);
            for(String authored:resolver.extractReferences(source)) {
                try {
                    Path resolved=resolver.resolve(authored,sourcePath.getParent());
                    if(resources.fromInternalPath(resolved,PackageResourceResolver.Kind.FILE).canonicalPath().equals(targetPath))return true;
                }
                catch(Exception ignored) { }
            }
            return false;
        } catch(Exception invalid) { return false; }
    }

    private static String sourceFormat(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "yaml";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".sh") || lower.endsWith(".bash")) return "shell";
        return "text";
    }

    public Map<String, Object> inspect(String action, String type, String resourceId,
                                       String query, int offset, int limit) throws Exception {
        return inspect(action,type,resourceId,query,offset,limit,null);
    }

    public Map<String, Object> inspect(String action, String type, String resourceId,
                                       String query, int offset, int limit, String expectedRevisionDigest) throws Exception {
        return inspect(action, type, resourceId, query, offset, limit, expectedRevisionDigest, null, "name", "asc");
    }

    /** Resolves nested package-file references only when their origin is reachable from the safe resource source. */
    public Map<String, Object> inspect(String action, String type, String resourceId,
                                       String query, int offset, int limit, String expectedRevisionDigest,
                                       String sourceOriginPath) throws Exception {
        return inspect(action, type, resourceId, query, offset, limit, expectedRevisionDigest, sourceOriginPath, "name", "asc");
    }

    /** Applies stable sorting before pagination so every page shares one server-defined order. */
    public Map<String, Object> inspect(String action, String type, String resourceId,
                                       String query, int offset, int limit, String expectedRevisionDigest,
                                       String sourceOriginPath, String sort, String direction) throws Exception {
        if (!("list".equals(action) || "detail".equals(action) || "source".equals(action)
                || "source-file".equals(action)))
            throw new IllegalArgumentException("Unsupported inspection action");
        if (offset < 0) throw new IllegalArgumentException("offset must be non-negative");
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("limit must be between 1 and 100");
        if (query != null && query.length() > 200) throw new IllegalArgumentException("query is too long");
        if (type != null && !supportedType(type)) throw new IllegalArgumentException("Unsupported resource type");
        if (!java.util.Arrays.asList("name", "type", "logicalId", "state").contains(sort)) throw new IllegalArgumentException("Unsupported resource sort field");
        if (!"asc".equals(direction) && !"desc".equals(direction)) throw new IllegalArgumentException("Resource sort direction must be asc or desc");
        Index index = index();
        if (expectedRevisionDigest != null && !expectedRevisionDigest.equals(index.revisionDigest))
            throw new StaleResourceCursorException();
        if ("list".equals(action)) return page(index, type, query, offset, limit, sort, direction);
        if (resourceId == null || resourceId.length() > 512) throw new IllegalArgumentException("resourceId is invalid");
        Resource resource = index.byId.get(resourceId);
        if (resource == null || type != null && !type.equals(resource.type)) throw new ResourceNotFoundException();
        if ("source".equals(action)) return source(index, resource);
        if ("source-file".equals(action)) return sourceFile(index, resource, query, sourceOriginPath);
        return detail(index, resource);
    }

    private Map<String, Object> page(Index index, String type, String query, int offset, int limit, String sort, String direction) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Resource> matches = new ArrayList<Resource>();
        for (Resource resource : index.ordered) {
            if (type != null && !type.equals(resource.type)) continue;
            if (!needle.isEmpty() && !resource.matches(needle)) continue;
            matches.add(resource);
        }
        final boolean reverse = "desc".equals(direction);
        Collections.sort(matches, new Comparator<Resource>() {
            @Override public int compare(Resource left, Resource right) {
                String leftValue = sortValue(left, sort), rightValue = sortValue(right, sort);
                int compared = compareResourceText(leftValue, rightValue);
                if (compared == 0) compared = compareResourceText(left.type, right.type);
                if (compared == 0) compared = compareResourceText(left.resourceId, right.resourceId);
                return reverse ? -compared : compared;
            }
        });
        if (offset > matches.size()) throw new IllegalArgumentException("offset exceeds the current resource list");
        int end = Math.min(matches.size(), offset + limit);
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        for (int i = offset; i < end; i++) items.add(summary(matches.get(i), index));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("items", items);
        result.put("total", matches.size());
        result.put("nextOffset", end < matches.size() ? Integer.valueOf(end) : null);
        result.put("diagnostics", index.diagnostics);
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    private static String sortValue(Resource resource, String sort) {
        if ("type".equals(sort)) return resource.type;
        if ("logicalId".equals(sort)) return resource.logicalId;
        if ("state".equals(sort)) return resource.state;
        return resource.name == null || resource.name.isEmpty()
                ? (resource.logicalId == null || resource.logicalId.isEmpty() ? resource.resourceId : resource.logicalId)
                : resource.name;
    }

    private static int compareResourceText(String left, String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        int compared = String.CASE_INSENSITIVE_ORDER.compare(a, b);
        return compared == 0 ? a.compareTo(b) : compared;
    }

    private Map<String, Object> detail(Index index, Resource resource) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resource", summary(resource, index));
        result.put("definition", resource.definition);
        result.put("diagnostics", resource.diagnostics);
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    private Map<String, Object> source(Index index, Resource resource) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resource", summary(resource, index));
        result.put("revisionDigest", index.revisionDigest);
        if (resource.yamlSource != null) {
            if (!sourceAllowed(resource)) {
                result.put("available", Boolean.FALSE);
                result.put("reason", sourceUnavailableReason(resource));
                return bounded(result);
            }
            try {
                String logicalPath = resources.fromInternalPath(resource.yamlSource, PackageResourceResolver.Kind.FILE).logicalName();
                Object authored = loadYaml(resource.yamlSource, maxSourceBytes);
                Object safe = sourceYamlProjection(yamlMapping(authored),"",logicalPath);
                String text = dumpYaml(safe);
                if (utf8(text).length > maxSourceBytes) {
                    result.put("available", Boolean.FALSE);
                    result.put("reason", "size-limit");
                    return bounded(result);
                }
                result.put("available", Boolean.TRUE);
                result.put("logicalPath", logicalPath);
                result.put("format", "yaml");
                result.put("text", text);
                result.put("redacted", Boolean.valueOf(!deepEquals(authored, safe)));
                SourceFileReferences references = sourceFileReferences(resource, safe);
                result.put("fileReferences", references.items);
                result.put("fileReferencesTruncated", Boolean.valueOf(references.truncated));
                return bounded(result);
            } catch (ResponseTooLargeException oversized) {
                result.put("available", Boolean.FALSE);
                result.put("reason", "size-limit");
                return bounded(result);
            } catch (CharacterCodingException binary) {
                result.put("available", Boolean.FALSE);
                result.put("reason", "binary");
                return bounded(result);
            } catch (Exception ignored) {
                result.put("available", Boolean.FALSE);
                result.put("reason", sourceUnavailableReason(resource));
                return bounded(result);
            }
        }
        if (resource.textSource != null) {
            try {
                String logicalPath = resource.textSourceLogicalPath == null
                        ? resources.fromInternalPath(resource.textSource, PackageResourceResolver.Kind.FILE).logicalName()
                        : resource.textSourceLogicalPath;
                String reason=textSourceReason(resource,resource.textSource);
                if (reason != null) {
                    result.put("available", Boolean.FALSE);
                    result.put("reason", reason);
                    return bounded(result);
                }
                String text = decodeUtf8(readBounded(resource.textSource, maxSourceBytes));
                result.put("available", Boolean.TRUE);
                result.put("format", sourceFormat(logicalPath));
                result.put("logicalPath", logicalPath);
                String projected = scrubSourceText(text, logicalPath);
                result.put("text", projected);
                result.put("redacted", Boolean.valueOf(!text.equals(projected)));
                SourceFileReferences references = resource.textSourceExternal
                        ? sourceFileReferences(Collections.<String,SourceFileReference>emptyMap())
                        : packageFileSourceReferences(resource.textSource, logicalPath, projected);
                result.put("fileReferences", references.items);
                result.put("fileReferencesTruncated", Boolean.valueOf(references.truncated));
                return bounded(result);
            } catch (ResponseTooLargeException oversized) {
                result.put("available", Boolean.FALSE);
                result.put("reason", "size-limit");
                return bounded(result);
            } catch (CharacterCodingException binary) {
                result.put("available", Boolean.FALSE);
                result.put("reason", "binary");
                return bounded(result);
            } catch (Exception ignored) {
                result.put("available", Boolean.FALSE);
                result.put("reason", sourceUnavailableReason(resource));
                return bounded(result);
            }
        }
        if (resource.textSourceCandidate != null) {
            String reason = textSourceUnavailableReason(resource);
            result.put("available", Boolean.FALSE);
            result.put("reason", reason);
            return bounded(result);
        }
        result.put("available", Boolean.FALSE);
        result.put("reason", "source-unavailable");
        return bounded(result);
    }

    private Map<String, Object> sourceFile(Index index, Resource resource, String referenceId, String sourceOriginPath) {
        if (referenceId == null || !referenceId.matches("[0-9a-f]{64}")
                || resource.yamlSource == null || !sourceAllowed(resource)) throw new ResourceNotFoundException();
        try {
            Object authored = loadYaml(resource.yamlSource, maxSourceBytes);
            String parentLogicalPath=resources.fromInternalPath(resource.yamlSource,PackageResourceResolver.Kind.FILE).logicalName();
            Object safe = sourceYamlProjection(yamlMapping(authored), "",parentLogicalPath);
            SourceFileReference selected = null;
            Map<String, SourceFileReference> references;
            if (sourceOriginPath == null || whitespaceOnly(sourceOriginPath)
                    || resources.fromInternalPath(resource.yamlSource, PackageResourceResolver.Kind.FILE).logicalName().equals(sourceOriginPath)) {
                references = resolveSourceFileReferences(resource, safe);
            } else {
                PackageResourceResolver.PackageResource origin = resources.resolvePackageRelative(sourceOriginPath, PackageResourceResolver.Kind.FILE);
                if (!isReachableSourceFile(resource, origin.canonicalPath())) throw new ResourceNotFoundException();
                if (!referencedTextPathAllowed(origin.logicalName()) || deniedSourcePath(origin.logicalName())) throw new ResourceNotFoundException();
                String originText = decodeUtf8(readBounded(origin.canonicalPath(), maxSourceBytes));
                references = resolveSourceFileReferences(resource, origin.canonicalPath(), scrubSourceText(originText, origin.logicalName()));
            }
            for (SourceFileReference candidate : references.values()) {
                if (referenceId.equals(candidate.id)) { selected = candidate; break; }
            }
            if (selected == null) throw new ResourceNotFoundException();
            String text = decodeUtf8(readBounded(selected.path, maxSourceBytes));
            String projected = scrubSourceText(text, selected.logicalPath);
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("resource", summary(resource, index));
            result.put("available", Boolean.TRUE);
            result.put("format", "text");
            result.put("logicalPath", selected.logicalPath);
            result.put("text", projected);
            result.put("redacted", Boolean.valueOf(!text.equals(projected)));
            SourceFileReferences nested = sourceFileReferences(resource, selected.path, projected);
            result.put("fileReferences", nested.items);
            result.put("fileReferencesTruncated", Boolean.valueOf(nested.truncated));
            result.put("revisionDigest", index.revisionDigest);
            return bounded(result);
        } catch (ResourceNotFoundException missing) {
            throw missing;
        } catch (ResponseTooLargeException oversized) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("resource", summary(resource, index));
            result.put("available", Boolean.FALSE);
            result.put("reason", "size-limit");
            result.put("revisionDigest", index.revisionDigest);
            return bounded(result);
        } catch (CharacterCodingException binary) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("resource", summary(resource, index));
            result.put("available", Boolean.FALSE);
            result.put("reason", "binary");
            result.put("revisionDigest", index.revisionDigest);
            return bounded(result);
        } catch (Exception unavailable) {
            throw new ResourceNotFoundException();
        }
    }

    private SourceFileReferences sourceFileReferences(Resource parent, Object projectedYaml) {
        Map<String, SourceFileReference> resolved = resolveSourceFileReferences(parent, projectedYaml);
        return sourceFileReferences(resolved);
    }

    private SourceFileReferences sourceFileReferences(Resource parent, Path sourcePath, String projectedText) {
        return sourceFileReferences(resolveSourceFileReferences(parent, sourcePath, projectedText));
    }

    private SourceFileReferences sourceFileReferences(Map<String, SourceFileReference> resolved) {
        List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
        int count = 0;
        for (SourceFileReference reference : resolved.values()) {
            if (count++ >= MAX_SOURCE_REFERENCES) break;
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("referenceId", reference.id);
            item.put("logicalPath", reference.logicalPath);
            item.put("available", Boolean.TRUE);
            items.add(item);
        }
        return new SourceFileReferences(items, resolved.size() > MAX_SOURCE_REFERENCES);
    }

    private SourceFileReferences packageFileSourceReferences(Path sourcePath, String sourceLogicalPath, String projectedText) {
        Map<String, SourceFileReference> resolved = new LinkedHashMap<String, SourceFileReference>();
        FileExpressionResolver resolver = new FileExpressionResolver(packageRoot);
        List<String> authoredPaths;
        try { authoredPaths = resolver.extractReferences(projectedText); }
        catch (Exception invalidExpression) { return sourceFileReferences(resolved); }
        for (String authoredPath : authoredPaths) {
            if (resolved.size() >= MAX_SOURCE_REFERENCES + 1) break;
            try {
                Path canonical = resolver.resolve(authoredPath, sourcePath.getParent());
                PackageResourceResolver.PackageResource target = resources.fromInternalPath(canonical, PackageResourceResolver.Kind.FILE);
                String logical = target.logicalName();
                if (!packageSourceAllowed(logical,target.canonicalPath()) || !referencedTextPathAllowed(logical) || sourceGlobDenied(logical)
                        || Files.size(target.canonicalPath()) > maxSourceBytes || !isUtf8Text(target.canonicalPath())) continue;
                String id = sourceReferenceId(sourceLogicalPath, logical);
                if (!resolved.containsKey(id)) resolved.put(id, new SourceFileReference(id, logical, target.canonicalPath()));
            } catch (Exception unavailable) { /* Unlisted, denied, missing, binary and oversized files are not navigable. */ }
        }
        return sourceFileReferences(resolved);
    }

    private Map<String, SourceFileReference> resolveSourceFileReferences(Resource parent, Object projectedYaml) {
        Map<String, SourceFileReference> result = new LinkedHashMap<String, SourceFileReference>();
        if (parent == null || parent.yamlSource == null || !("template".equals(parent.type) || "flow".equals(parent.type))) return result;
        FileExpressionResolver resolver = new FileExpressionResolver(packageRoot);
        collectSourceFileReferences(parent, projectedYaml, resolver, result);
        return result;
    }

    private Map<String, SourceFileReference> resolveSourceFileReferences(Resource parent, Path sourcePath, String projectedText) {
        Map<String, SourceFileReference> result = new LinkedHashMap<String, SourceFileReference>();
        if (parent == null || sourcePath == null || !sourceAllowed(parent)) return result;
        FileExpressionResolver resolver = new FileExpressionResolver(packageRoot);
        collectSourceFileReferences(parent, projectedText, sourcePath.getParent(), resolver, result);
        return result;
    }

    private void collectSourceFileReferences(Resource parent, Object value, FileExpressionResolver resolver,
                                             Map<String, SourceFileReference> result) {
        if (result.size() >= MAX_SOURCE_REFERENCES + 1) return;
        if (value instanceof Map) {
            for (Object child : ((Map<?, ?>) value).values()) collectSourceFileReferences(parent, child, resolver, result);
            return;
        }
        if (value instanceof Iterable) {
            for (Object child : (Iterable<?>) value) collectSourceFileReferences(parent, child, resolver, result);
            return;
        }
        if (!(value instanceof String)) return;
        collectSourceFileReferences(parent, (String) value, parent.yamlSource.getParent(), resolver, result);
    }

    private void collectSourceFileReferences(Resource parent, String text, Path originDirectory,
                                             FileExpressionResolver resolver, Map<String, SourceFileReference> result) {
        if (result.size() >= MAX_SOURCE_REFERENCES + 1) return;
        List<String> paths;
        try { paths = resolver.extractReferences(text); }
        catch (Exception invalidExpression) { return; }
        for (String authoredPath : paths) {
            if (result.size() >= MAX_SOURCE_REFERENCES + 1) return;
            try {
                Path canonical = resolver.resolve(authoredPath, originDirectory);
                PackageResourceResolver.PackageResource target = resources.fromInternalPath(canonical, PackageResourceResolver.Kind.FILE);
                String logical = target.logicalName();
                if (!referencedTextPathAllowed(logical) || deniedSourcePath(logical)
                        || Files.size(target.canonicalPath()) > maxSourceBytes
                        || !isUtf8Text(target.canonicalPath())) continue;
                String id = sourceReferenceId(parent.resourceId, logical);
                if (!result.containsKey(id)) result.put(id, new SourceFileReference(id, logical, target.canonicalPath()));
            } catch (Exception unavailable) { /* Missing, unsafe, binary, and oversize targets are not projected. */ }
        }
    }

    private boolean isReachableSourceFile(Resource resource, Path requested) throws Exception {
        Path target = resources.fromInternalPath(requested, PackageResourceResolver.Kind.FILE).canonicalPath();
        if (target.equals(resources.fromInternalPath(resource.yamlSource, PackageResourceResolver.Kind.FILE).canonicalPath())) return true;
        Map<String, SourceFileReference> direct = resolveSourceFileReferences(resource, resource.definition);
        java.util.ArrayDeque<Path> pending = new java.util.ArrayDeque<Path>();
        Map<Path, Integer> depth = new HashMap<Path, Integer>();
        for (SourceFileReference reference : direct.values()) {
            Path canonical = reference.path.toRealPath();
            if (!depth.containsKey(canonical)) { pending.add(canonical); depth.put(canonical, Integer.valueOf(1)); }
        }
        int visited = 0;
        while (!pending.isEmpty() && visited++ < MAX_NESTED_SOURCE_FILES) {
            Path current = pending.remove();
            if (current.equals(target)) return true;
            int currentDepth = depth.get(current).intValue();
            if (currentDepth >= MAX_NESTED_SOURCE_DEPTH) continue;
            try {
                String logical = resources.fromInternalPath(current, PackageResourceResolver.Kind.FILE).logicalName();
                String content = scrubSourceText(decodeUtf8(readBounded(current, maxSourceBytes)), logical);
                for (SourceFileReference nested : resolveSourceFileReferences(resource, current, content).values()) {
                    Path canonical = nested.path.toRealPath();
                    if (!depth.containsKey(canonical)) { pending.add(canonical); depth.put(canonical, Integer.valueOf(currentDepth + 1)); }
                }
            } catch (Exception unavailable) { /* Invalid or unreadable nested sources cannot authorize further navigation. */ }
        }
        return false;
    }

    private void indexReferencedSources(Index index, Resource resource) {
        if (!sourceAllowed(resource)) return;
        for (SourceFileReference reference : resolveSourceFileReferences(resource, resource.definition).values())
            index.files.add(reference.path);
    }

    private boolean referencedTextPathAllowed(String logicalPath) {
        return textExtension(logicalPath);
    }

    private Set<String> safeFileExpressionTokens(String text, String sourceLogicalPath) {
        Set<String> preserved = new LinkedHashSet<String>();
        if (text == null || sourceLogicalPath == null || sourceLogicalPath.isEmpty()) return preserved;
        try {
            Path source = resources.resolvePackageRelative(sourceLogicalPath, PackageResourceResolver.Kind.FILE).canonicalPath();
            FileExpressionResolver resolver = new FileExpressionResolver(packageRoot);
            for (String authoredPath : resolver.extractReferences(text)) {
                try {
                    Path canonical = resolver.resolve(authoredPath, source.getParent());
                    PackageResourceResolver.PackageResource target = resources.fromInternalPath(canonical, PackageResourceResolver.Kind.FILE);
                    String logical = target.logicalName();
                    boolean configurationSource = sourceLogicalPath.startsWith("config/");
                    boolean allowed = configurationSource
                            ? packageSourceAllowed(logical,target.canonicalPath()) && !sourceGlobDenied(logical) && !sensitiveSourcePath(logical)
                            : packageSourceAllowed(logical,target.canonicalPath()) && !deniedSourcePath(logical);
                    if (!allowed || !referencedTextPathAllowed(logical)
                            || Files.size(target.canonicalPath()) > maxSourceBytes || !isUtf8Text(target.canonicalPath())) continue;
                    preserved.add("&{" + authoredPath + "}");
                } catch (Exception unavailable) { /* Unsafe and unavailable expressions are still scrubbed as ordinary text. */ }
            }
        } catch (Exception unavailable) { /* Sources without a canonical package origin have no preserved file tokens. */ }
        return preserved;
    }

    private String sourceReferenceId(String resourceId, String logicalPath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(utf8(resourceId));
            digest.update((byte) 0);
            digest.update(utf8(logicalPath));
            return hex(digest.digest());
        } catch (Exception unavailable) { throw new IllegalStateException("SHA-256 is unavailable", unavailable); }
    }

    private Map<String, Object> bounded(Map<String, Object> value) {
        int bytes = utf8(new Yaml().dump(value)).length;
        if (bytes > maxResponseBytes) throw new ResponseTooLargeException();
        return value;
    }

    private Index index() throws Exception {
        if (!cacheEnabled || cacheUnavailable) return buildIndex();
        PackageWatch watch;
        try { watch = packageWatch(); }
        catch (Exception unavailable) { cacheUnavailable = true; return buildIndex(); }
        for (int attempt = 0; attempt < 3; attempt++) {
            long version = watch.version();
            Index cached = cachedIndex;
            if (cached != null && cachedIndexVersion == version) { InspectionMetrics.JVM.add(INDEX_HIT, 1); return cached; }
            InspectionMetrics.JVM.add(INDEX_MISS, 1);
            Index built = buildIndex();
            try { watch.registerFileParents(built.files); }
            catch (Exception unavailable) {
                cacheUnavailable = true;
                close();
                return built;
            }
            long after = watch.version();
            if (after == version) {
                cachedIndex = built;
                cachedIndexVersion = after;
                return built;
            }
        }
        throw new ResourceLimitException();
    }

    private Index buildIndex() throws Exception {
        indexBuildCount++;
        long buildStarted = System.nanoTime();
        try { return buildIndexContents(); }
        finally { InspectionMetrics.JVM.record(INDEX_BUILD,System.nanoTime()-buildStarted); }
    }

    private Index buildIndexContents() throws Exception {
        Index index = new Index();
        Path configFile = resolveConfigFile();
        if (Files.size(configFile) > MAX_CONFIG_BYTES) throw new ResourceLimitException();
        index.files.add(configFile);
        FrameworkConfig config = new FrameworkConfigLoader().load(configFile, runtimeConfigRoot, packageRoot, environment);
        Path templatesRoot = config.templatesRoot().isAbsolute()
                ? config.templatesRoot() : packageRoot.resolve(config.templatesRoot());
        templatesRoot = resources.fromInternalPath(templatesRoot, PackageResourceResolver.Kind.DIRECTORY).canonicalPath();

        indexTemplates(index, config, templatesRoot);
        indexFlows(index, config, templatesRoot);
        indexTools(index, config);
        indexRuntimeConfigFiles(index);
        indexCases(index, config);
        indexDebugSidecars(index, config);
        indexQuickLoadPolicies(index);
        resolveReferences(index, config);
        index.revisionDigest = digestFiles(index.files, index.debugSidecars, index.quickLoadPolicies);
        Collections.sort(index.ordered, new Comparator<Resource>() {
            @Override public int compare(Resource left, Resource right) {
                return left.resourceId.compareTo(right.resourceId);
            }
        });
        return index;
    }

    /** Returns a safe, fixed-target Debug form projection and its private package revision. */
    public Map<String, Object> inspectDebugForm(String type, String resourceId, String expectedRevisionDigest) throws Exception {
        Index index = index();
        requireExpectedRevision(index, expectedRevisionDigest);
        Resource resource = index.byId.get(resourceId);
        if (resource == null || !debuggable(resource.type) || !resource.type.equals(type) || !"ready".equals(resource.state))
            throw new ResourceNotFoundException();
        DebugEngine engine = debugEngine(frameworkConfig());
        Map<String, Object> projection = engine.projectDiscoverableInput(resource.type, resource.logicalId);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resource", summary(resource, index));
        result.put("target", target(resource));
        result.put("input", projection.get("input"));
        result.put("redacted", projection.get("redacted"));
        result.put("revisionDigest", index.revisionDigest);
        result.put("redactionEnabled", Boolean.valueOf(redactionEnabled));
        return bounded(result);
    }

    /** Returns safe business defaults and a validated one-workload Quick Load preview. */
    public Map<String, Object> inspectQuickLoadForm(String type, String resourceId, String model,
                                                    String expectedRevisionDigest) throws Exception {
        Index index = index();
        requireExpectedRevision(index, expectedRevisionDigest);
        Resource resource = index.byId.get(resourceId);
        if (resource == null || !debuggable(resource.type) || !resource.type.equals(type) || !"ready".equals(resource.state))
            throw new ResourceNotFoundException();
        return quickLoadProjection(index, resource, model, null, null, null);
    }

    /** Validates business defaults and policy overrides, returning a private typed scenario for draft creation. */
    public Map<String, Object> validateQuickLoadInput(String type, String logicalId, String model,
                                                      Map<String, Object> businessInput,
                                                      Map<String, Object> loadOverrides,
                                                      Map<String, Object> workloadExecution) throws Exception {
        return validateQuickLoadInput(type, logicalId, model, businessInput, loadOverrides, workloadExecution,
                Collections.<String>emptyList());
    }

    /** Validates business inputs, pacing, and explicit Load-level Testdata descriptor paths. */
    public Map<String, Object> validateQuickLoadInput(String type, String logicalId, String model,
                                                      Map<String, Object> businessInput,
                                                      Map<String, Object> loadOverrides,
                                                      Map<String, Object> workloadExecution,
                                                      List<String> testdata) throws Exception {
        if (!debuggable(type) || logicalId == null || logicalId.trim().isEmpty() || logicalId.length() > 512)
            throw new ResourceNotFoundException();
        Index index = index();
        Resource resource = index.resolve(type, logicalId);
        if (resource == null || !"ready".equals(resource.state)) throw new ResourceNotFoundException();
        return quickLoadProjection(index, resource, model, businessInput, loadOverrides, workloadExecution, testdata);
    }

    private Map<String, Object> quickLoadProjection(Index index, Resource resource, String model,
                                                    Map<String, Object> businessInput,
                                                    Map<String, Object> loadOverrides,
                                                    Map<String, Object> workloadExecution) throws Exception {
        return quickLoadProjection(index, resource, model, businessInput, loadOverrides, workloadExecution,
                Collections.<String>emptyList());
    }

    private Map<String, Object> quickLoadProjection(Index index, Resource resource, String model,
                                                    Map<String, Object> businessInput,
                                                    Map<String, Object> loadOverrides,
                                                    Map<String, Object> workloadExecution,
                                                    List<String> testdata) throws Exception {
        FrameworkConfig config = frameworkConfig();
        DebugEngine engine = debugEngine(config);
        Map<String, Object> projectedInput = engine.projectLoadBusinessInput(resource.type, resource.logicalId, businessInput);
        @SuppressWarnings("unchecked") Map<String, Object> normalizedInput =
                (Map<String, Object>) projectedInput.get("normalizedInput");
        att.load.LoadScenarioLoader loader = new att.load.LoadScenarioLoader(packageRoot);
        Map<String, Object> policy = loader.loadQuickLoadPolicy(model);
        att.load.LoadScenario scenario = new att.load.LoadScenarioBuilder(loader).buildQuickLoad(model,
                resource.type, resource.logicalId, normalizedInput, policy, loadOverrides, workloadExecution, testdata);
        att.load.LoadTarget target = new att.load.LoadTargetResolver(packageRoot, config).resolve(scenario);
        new att.load.LoadTargetValidator(packageRoot, config).validate(scenario, target);
        Map<String, Object> scenarioMap = scenario.toMap();
        Map<String, Object> normalizedScenario = new LinkedHashMap<String, Object>(scenarioMap);
        List<Object> scenarioTestdata = new ArrayList<Object>();
        Object policyTestdata = policy.get("testdata");
        if (policyTestdata instanceof List) scenarioTestdata.addAll((List<?>) policyTestdata);
        else if (policyTestdata != null) scenarioTestdata.add(policyTestdata);
        if (testdata != null) scenarioTestdata.addAll(testdata);
        if (!scenarioTestdata.isEmpty()) normalizedScenario.put("testdata", scenarioTestdata);
        Map<String, Object> safeScenarioProjection = projectSafeLoadScenario(scenarioMap, engine);
        @SuppressWarnings("unchecked") Map<String, Object> safeScenario =
                (Map<String, Object>) safeScenarioProjection.get("value");
        boolean redacted = Boolean.TRUE.equals(projectedInput.get("redacted"))
                || Boolean.TRUE.equals(safeScenarioProjection.get("redacted"));
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(120);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resource", summary(resource, index));
        result.put("target", target(resource));
        result.put("model", model);
        result.put("input", projectedInput.get("input"));
        result.put("debugLocalTestdataOmitted", projectedInput.get("debugLocalTestdataOmitted"));
        result.put("loadTestdata", testdata == null ? Collections.emptyList() : new ArrayList<String>(testdata));
        result.put("redacted", Boolean.valueOf(redacted));
        result.put("preview", safeScenario);
        result.put("previewYaml", new Yaml(options).dump(safeScenario));
        result.put("normalizedScenario", normalizedScenario);
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    /** Returns the safe model-specific policy defaults used to initialize Advanced Load. */
    public Map<String, Object> inspectQuickLoadPolicy(String model) throws Exception {
        return inspectQuickLoadPolicy(model, null);
    }

    /** Returns one selected safe policy and the bounded compatible package/bundled template catalog. */
    public Map<String, Object> inspectQuickLoadPolicy(String model, String templateId) throws Exception {
        Index index = index();
        att.load.LoadScenarioLoader loader = new att.load.LoadScenarioLoader(packageRoot);
        if (templateId != null && templateId.length() > 1024) throw new IllegalArgumentException("Load template id is invalid");
        Map<String, Object> policy = loader.loadQuickLoadPolicy(model, templateId);
        Map<String, Object> projection = debugEngine(frameworkConfig()).projectSafeValue(policy);
        @SuppressWarnings("unchecked") Map<String, Object> safePolicy = (Map<String, Object>) projection.get("value");
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(120);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("model", model);
        result.put("policy", safePolicy);
        result.put("previewYaml", new Yaml(options).dump(safePolicy));
        result.put("redacted", Boolean.valueOf(Boolean.TRUE.equals(projection.get("redacted"))));
        att.load.LoadScenarioLoader.QuickLoadTemplateCatalog catalog = loader.quickLoadTemplates();
        result.put("templates", catalog.toMaps());
        result.put("templateCatalogTruncated", Boolean.valueOf(catalog.truncated));
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    /** Validates a full inline Load scenario and every target closure without scheduling traffic. */
    public Map<String, Object> validateLoadScenario(Map<String, Object> requested) throws Exception {
        if (requested == null) throw new IllegalArgumentException("Load scenario is required");
        Index index = index();
        Map<String, Object> normalizedRequest = att.load.LoadIsolation.deepCopyMap(requested);
        FrameworkConfig config = frameworkConfig();
        DebugEngine engine = debugEngine(config);
        restoreAdvancedBusinessDefaults(normalizedRequest, engine);
        Map<String, Object> policy = new LinkedHashMap<String, Object>(normalizedRequest);
        Object rawWorkloads = policy.remove("workloads");
        if (!(rawWorkloads instanceof List)) throw new IllegalArgumentException("workloads must be an array");
        List<Map<String, Object>> workloads = new ArrayList<Map<String, Object>>();
        for (Object raw : (List<?>) rawWorkloads) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("Every workload must be an object");
            @SuppressWarnings("unchecked") Map<String, Object> workload = (Map<String, Object>) raw;
            workloads.add(workload);
        }
        Map<String, Object> composed = new att.load.LoadScenarioBuilder(new att.load.LoadScenarioLoader(packageRoot))
                .composeScenario(policy, workloads);
        att.load.LoadScenario scenario = new att.load.LoadScenarioLoader(packageRoot).loadInline(composed);
        return validatedLoadScenario(index, config, engine, scenario);
    }

    /** Validates a package-backed Load scenario while retaining source locations from its YAML file. */
    public Map<String, Object> validateLoadScenarioFile(String relativePath) throws Exception {
        if (relativePath == null || relativePath.trim().isEmpty()) throw new IllegalArgumentException("Load scenario path is required");
        Index index = index();
        FrameworkConfig config = frameworkConfig();
        DebugEngine engine = debugEngine(config);
        PackageResourceResolver.PackageResource source = resources.resolvePackageRelative(relativePath, PackageResourceResolver.Kind.FILE);
        att.load.LoadScenario scenario = new att.load.LoadScenarioLoader(packageRoot).load(source.canonicalPath());
        return validatedLoadScenario(index, config, engine, scenario);
    }

    /** Lists bounded, validated YAML descriptors under load/ without exposing their authored contents. */
    public Map<String, Object> inspectLoadCatalog(String query, int offset, int limit,
                                                   String expectedRevisionDigest) throws Exception {
        if (offset < 0 || limit < 1 || limit > MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Load catalog pagination is invalid");
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.length() > 200) throw new IllegalArgumentException("Load catalog query is too long");
        Index index = index();
        if (expectedRevisionDigest != null && !expectedRevisionDigest.equals(index.revisionDigest))
            throw new StaleResourceCursorException();
        Path loadRoot = packageRoot.resolve("load").normalize();
        List<Path> candidates = new ArrayList<Path>();
        if (Files.isDirectory(loadRoot, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(loadRoot)) {
            PackageResourceResolver.PackageResource directory = resources.fromInternalPath(loadRoot, PackageResourceResolver.Kind.DIRECTORY);
            try (Stream<Path> paths = Files.walk(directory.canonicalPath(), 32)) {
                paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                            return name.endsWith(".yaml") || name.endsWith(".yml");
                        }).limit(20001).forEach(candidates::add);
            }
        }
        candidates.sort(Comparator.comparing(path -> path.toString().toLowerCase(Locale.ROOT)));
        if (candidates.size() > 20000) throw new ResourceLimitException();
        List<Path> matches = new ArrayList<Path>();
        for (Path candidate : candidates) {
            String logical = packageRoot.relativize(candidate).toString().replace('\\', '/');
            if (needle.isEmpty() || logical.toLowerCase(Locale.ROOT).contains(needle)) matches.add(candidate);
        }
        if (offset > matches.size()) throw new IllegalArgumentException("Load catalog cursor is stale");
        int end = Math.min(matches.size(), offset + limit);
        List<Map<String,Object>> pageItems = new ArrayList<Map<String,Object>>();
        LoadScenarioLoader loader = new LoadScenarioLoader(packageRoot);
        for (Path candidate : matches.subList(offset, end)) {
            PackageResourceResolver.PackageResource source;
            try { source = resources.fromInternalPath(candidate, PackageResourceResolver.Kind.FILE); }
            catch (Exception unsafe) { continue; }
            String relative = source.logicalName();
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("resourceId", relative);
            item.put("path", relative);
            item.put("workloadCount", 0);
            item.put("sourceAvailable", loadSourceAllowed(source.canonicalPath(), relative));
            try {
                if (Files.size(source.canonicalPath()) > maxSourceBytes) throw new IllegalArgumentException("size-limit");
                LoadScenario scenario = loader.load(source.canonicalPath());
                boolean template = scenario.policyOnly() || !scenario.hasWorkloads();
                String model = scenario.hasWorkloads()
                        ? (scenario.model() == att.load.LoadScenario.Model.CLOSED ? "virtualUsers" : "arrivalRate")
                        : policyModel(scenario.toMap());
                item.put("kind", template ? "template" : "scenario");
                // Target resolution depends on the selected environment and is performed by Validate/Run/Schedule.
                item.put("validation", template ? "valid" : "unverified");
                item.put("model", model);
                item.put("workloadCount", scenario.workloads().size());
                item.put("runNow", !template);
                item.put("schedule", !template);
                item.put("createQuickLoad", template && model != null);
            } catch (Exception invalid) {
                item.put("kind", "invalid");
                item.put("validation", "invalid");
                item.put("runNow", false); item.put("schedule", false); item.put("createQuickLoad", false);
                item.put("diagnostic", safeLoadCatalogDiagnostic(invalid));
            }
            pageItems.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("items", pageItems);
        response.put("total", matches.size());
        response.put("nextOffset", end < matches.size() ? end : null);
        response.put("revisionDigest", index.revisionDigest);
        return bounded(response);
    }

    /** Returns a policy-redacted read-only YAML projection for an authorized catalog entry. */
    public Map<String, Object> inspectLoadSource(String relativePath) throws Exception {
        PackageResourceResolver.PackageResource source = resources.resolvePackageRelative(relativePath, PackageResourceResolver.Kind.FILE);
        if (!source.logicalName().startsWith("load/")) throw new ResourceNotFoundException();
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("logicalPath", source.logicalName());
        if (!loadSourceAllowed(source.canonicalPath(), source.logicalName())) {
            result.put("available", Boolean.FALSE); result.put("reason", sourceBrowsingEnabled ? "visibility-policy" : "source-disabled");
            return bounded(result);
        }
        try {
            Object authored = loadYaml(source.canonicalPath(), maxSourceBytes);
            Object safe = sourceYamlProjection(authored, "", source.logicalName());
            result.put("available", Boolean.TRUE); result.put("format", "yaml");
            result.put("text", dumpYaml(safe)); result.put("redacted", Boolean.valueOf(!deepEquals(authored, safe)));
        } catch (Exception unavailable) {
            result.put("available", Boolean.FALSE); result.put("reason", "unavailable");
        }
        return bounded(result);
    }

    private boolean loadSourceAllowed(Path path, String logicalPath) {
        try { return sourceBrowsingEnabled && packageSourceAllowed(logicalPath, path) && !deniedSourcePath(logicalPath)
                    && Files.size(path) <= maxSourceBytes && isUtf8Text(path); }
        catch (Exception unavailable) { return false; }
    }

    private String safeLoadCatalogDiagnostic(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) return "Load YAML is invalid or unavailable.";
        return scrubSensitiveValues(message).replaceAll("(?i)(password|token|secret|credential)(\\s*[:=]\\s*)[^\\s,}]+", "$1$2[REDACTED]")
                .replaceAll("(?:[A-Za-z]:)?[/\\\\][^\\s:]+", "[package path]");
    }

    private String policyModel(Map<String, Object> scenario) {
        Object raw = scenario.get("load");
        if (!(raw instanceof Map)) return null;
        Map<?, ?> load = (Map<?, ?>) raw;
        boolean users = load.containsKey("users"), arrival = load.containsKey("arrivalRate");
        return users == arrival ? null : users ? "virtualUsers" : "arrivalRate";
    }

    private Map<String, Object> validatedLoadScenario(Index index, FrameworkConfig config, DebugEngine engine,
                                                       att.load.LoadScenario scenario) throws Exception {
        att.load.LoadRunCoordinator.resolveAndValidateTargets(packageRoot, config, scenario);
        Map<String, Object> scenarioMap = scenario.toMap();
        Map<String, Object> projection = projectSafeLoadScenario(scenarioMap, engine);
        @SuppressWarnings("unchecked") Map<String, Object> safeScenario = (Map<String, Object>) projection.get("value");
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(120);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("model", scenario.model() == att.load.LoadScenario.Model.CLOSED ? "virtualUsers" : "arrivalRate");
        result.put("preview", safeScenario);
        result.put("previewYaml", new Yaml(options).dump(safeScenario));
        result.put("redacted", Boolean.valueOf(Boolean.TRUE.equals(projection.get("redacted"))));
        result.put("containsSensitiveValues", Boolean.valueOf(engine.containsSensitiveValue(scenarioMap)));
        result.put("normalizedScenario", scenarioMap);
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    /** Restores only redacted sidecar defaults while keeping user-entered Load values intact. */
    private void restoreAdvancedBusinessDefaults(Map<String, Object> scenario, DebugEngine engine) throws Exception {
        Object rawWorkloads = scenario.get("workloads");
        if (!(rawWorkloads instanceof List)) return;
        for (Object rawWorkload : (List<?>) rawWorkloads) {
            if (!(rawWorkload instanceof Map)) continue;
            @SuppressWarnings("unchecked") Map<String, Object> workload = (Map<String, Object>) rawWorkload;
            Object rawMix = workload.get("mix");
            if (rawMix instanceof List) {
                for (Object rawEntry : (List<?>) rawMix) {
                    if (!(rawEntry instanceof Map)) continue;
                    @SuppressWarnings("unchecked") Map<String, Object> entry = (Map<String, Object>) rawEntry;
                    if (entry.get("target") instanceof Map) {
                        @SuppressWarnings("unchecked") Map<String, Object> target = (Map<String, Object>) entry.get("target");
                        restoreTargetBusinessDefaults(engine, target, entry);
                    }
                }
            } else if (workload.get("target") instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> target = (Map<String, Object>) workload.get("target");
                restoreTargetBusinessDefaults(engine, target, workload);
            }
        }
    }

    private void restoreTargetBusinessDefaults(DebugEngine engine, Map<String, Object> target,
                                               Map<String, Object> businessOwner) throws Exception {
        Object rawType = target.get("type"), rawId = target.get("id");
        if (!(rawType instanceof String) || !(rawId instanceof String)) return;
        String type = (String) rawType, id = (String) rawId;
        Map<String, Object> submitted = new LinkedHashMap<String, Object>();
        submitted.put("schemaVersion", att.Version.DEBUG_SCHEMA);
        submitted.put("inputs", mapValue(businessOwner.get("inputs")));
        if ("tool".equals(type)) submitted.put("arguments", mapValue(target.get("arguments")));
        else submitted.put("vars", mapValue(businessOwner.get("vars")));
        Map<String, Object> projection = engine.projectLoadBusinessInput(type, id, submitted);
        Object rawNormalized = projection.get("normalizedInput");
        if (!(rawNormalized instanceof Map)) throw new IllegalStateException("Load sidecar projection returned invalid business values");
        @SuppressWarnings("unchecked") Map<String, Object> normalized = (Map<String, Object>) rawNormalized;
        Object inputs = normalized.get("inputs");
        if (inputs instanceof Map && (!((Map<?, ?>) inputs).isEmpty() || businessOwner.containsKey("inputs")))
            businessOwner.put("inputs", inputs);
        if ("tool".equals(type)) {
            Object arguments = normalized.get("arguments");
            if (arguments instanceof Map && (!((Map<?, ?>) arguments).isEmpty() || target.containsKey("arguments")))
                target.put("arguments", arguments);
        } else {
            Object vars = normalized.get("vars");
            if (vars instanceof Map && (!((Map<?, ?>) vars).isEmpty() || businessOwner.containsKey("vars")))
                businessOwner.put("vars", vars);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) value)
                : new LinkedHashMap<String, Object>();
    }

    private Map<String, Object> projectSafeLoadScenario(Map<String, Object> scenario, DebugEngine engine) {
        Map<String, Object> safe = new LinkedHashMap<String, Object>(scenario);
        List<Object> workloads = new ArrayList<Object>();
        boolean[] redacted = new boolean[] { false };
        Object rawWorkloads = scenario.get("workloads");
        if (rawWorkloads instanceof List) for (Object rawWorkload : (List<?>) rawWorkloads) {
            if (!(rawWorkload instanceof Map)) { workloads.add(rawWorkload); continue; }
            @SuppressWarnings("unchecked") Map<String, Object> workload = new LinkedHashMap<String, Object>((Map<String, Object>) rawWorkload);
            Map<String, Object> business = new LinkedHashMap<String, Object>();
            if (workload.containsKey("inputs")) business.put("inputs", workload.get("inputs"));
            if (workload.containsKey("vars")) business.put("vars", workload.get("vars"));
            Object rawTarget = workload.get("target");
            Map<String, Object> target = null;
            if (rawTarget instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, Object> copiedTarget = new LinkedHashMap<String, Object>((Map<String, Object>) rawTarget);
                target = copiedTarget;
                if (target.containsKey("arguments")) business.put("arguments", target.get("arguments"));
            }
            Map<String, Object> projected = engine.projectSafeValue(business);
            @SuppressWarnings("unchecked") Map<String, Object> safeBusiness = (Map<String, Object>) projected.get("value");
            redacted[0] |= Boolean.TRUE.equals(projected.get("redacted"));
            if (safeBusiness.containsKey("inputs")) workload.put("inputs", safeBusiness.get("inputs"));
            if (safeBusiness.containsKey("vars")) workload.put("vars", safeBusiness.get("vars"));
            if (target != null) {
                if (safeBusiness.containsKey("arguments")) target.put("arguments", safeBusiness.get("arguments"));
                workload.put("target", target);
            }
            Object rawMix = workload.get("mix");
            if (rawMix instanceof List) {
                List<Object> mix = new ArrayList<Object>();
                for (Object rawEntry : (List<?>) rawMix) {
                    if (!(rawEntry instanceof Map)) { mix.add(rawEntry); continue; }
                    @SuppressWarnings("unchecked") Map<String, Object> entry = new LinkedHashMap<String, Object>((Map<String, Object>) rawEntry);
                    Map<String, Object> entryBusiness = new LinkedHashMap<String, Object>();
                    if (entry.containsKey("inputs")) entryBusiness.put("inputs", entry.get("inputs"));
                    if (entry.containsKey("vars")) entryBusiness.put("vars", entry.get("vars"));
                    Object entryTarget = entry.get("target");
                    Map<String, Object> copiedEntryTarget = null;
                    if (entryTarget instanceof Map) {
                        @SuppressWarnings("unchecked") Map<String, Object> copied = new LinkedHashMap<String, Object>((Map<String, Object>) entryTarget);
                        copiedEntryTarget = copied;
                        if (copied.containsKey("arguments")) entryBusiness.put("arguments", copied.get("arguments"));
                    }
                    Map<String, Object> entryProjection = engine.projectSafeValue(entryBusiness);
                    @SuppressWarnings("unchecked") Map<String, Object> safeEntryBusiness = (Map<String, Object>) entryProjection.get("value");
                    redacted[0] |= Boolean.TRUE.equals(entryProjection.get("redacted"));
                    if (safeEntryBusiness.containsKey("inputs")) entry.put("inputs", safeEntryBusiness.get("inputs"));
                    if (safeEntryBusiness.containsKey("vars")) entry.put("vars", safeEntryBusiness.get("vars"));
                    if (copiedEntryTarget != null) {
                        if (safeEntryBusiness.containsKey("arguments")) copiedEntryTarget.put("arguments", safeEntryBusiness.get("arguments"));
                        entry.put("target", copiedEntryTarget);
                    }
                    mix.add(entry);
                }
                workload.put("mix", mix);
            }
            workloads.add(workload);
        }
        if (rawWorkloads instanceof List) safe.put("workloads", workloads);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("value", safe); result.put("redacted", Boolean.valueOf(redacted[0]));
        return result;
    }

    /** Validates typed inline Debug values against a resolvable logical target. */
    public Map<String, Object> validateDebugInput(String type, String logicalId,
                                                  Map<String, Object> input) throws Exception {
        if (!debuggable(type) || logicalId == null || logicalId.trim().isEmpty() || logicalId.length() > 512)
            throw new ResourceNotFoundException();
        Index index = index();
        Resource resource = index.resolve(type, logicalId);
        if (resource == null || !"ready".equals(resource.state)) throw new ResourceNotFoundException();
        DebugEngine engine = debugEngine(frameworkConfig());
        Map<String, Object> projection = engine.validateInlineInput(type, resource.logicalId, input);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resource", summary(resource, index));
        result.put("target", target(resource));
        result.put("input", projection.get("input"));
        result.put("redacted", projection.get("redacted"));
        result.put("normalizedInput", projection.get("normalizedInput"));
        result.put("containsSensitiveValues", Boolean.valueOf(engine.containsSensitiveValue(input)));
        result.put("revisionDigest", index.revisionDigest);
        return bounded(result);
    }

    /** Revision used to bind a validated functional selection to package content. */
    public String executionRevisionDigest() throws Exception { return refreshedIndexForVerification().revisionDigest; }

    /** Checks a Server-issued draft revision without returning the digest to the caller. */
    public void verifyRevision(String expectedRevisionDigest) throws Exception {
        requireExpectedRevision(refreshedIndexForVerification(), expectedRevisionDigest);
    }

    private Index refreshedIndexForVerification() throws Exception {
        if (!cacheEnabled || cacheUnavailable) return buildIndex();
        PackageWatch watch;
        try { watch = packageWatch(); }
        catch (Exception unavailable) { cacheUnavailable = true; return buildIndex(); }
        for (int attempt = 0; attempt < 3; attempt++) {
            long version = watch.version();
            Index fresh = buildIndex();
            try { watch.registerFileParents(fresh.files); }
            catch (Exception unavailable) {
                cacheUnavailable = true;
                close();
                return fresh;
            }
            long after = watch.version();
            if (after == version) {
                cachedIndex = fresh;
                cachedIndexVersion = after;
                return fresh;
            }
        }
        throw new ResourceLimitException();
    }

    private void requireExpectedRevision(Index index, String expectedRevisionDigest) {
        if (expectedRevisionDigest != null && !expectedRevisionDigest.equals(index.revisionDigest))
            throw new StaleResourceCursorException();
    }

    private FrameworkConfig frameworkConfig() throws Exception {
        Path file = resolveConfigFile();
        return new FrameworkConfigLoader().load(file, runtimeConfigRoot, packageRoot, environment);
    }

    private DebugEngine debugEngine(FrameworkConfig config) {
        return new DebugEngine(packageRoot, resolveConfigFileUnchecked(), config, new att.exec.IbmMqClientFactory(),
                redactionEnabled, sensitivePaths, sensitiveFieldPatterns);
    }

    private Path resolveConfigFileUnchecked() {
        try { return resolveConfigFile(); }
        catch (Exception unavailable) { return RuntimeConfigPath.resolve(configPath, packageRoot); }
    }

    private static boolean debuggable(String type) {
        return "template".equals(type) || "flow".equals(type) || "tool".equals(type);
    }

    private Map<String, Object> target(Resource resource) {
        Map<String, Object> target = new LinkedHashMap<String, Object>();
        target.put("type", resource.type);
        target.put("id", scrub(resource.logicalId));
        return target;
    }

    private void indexDebugSidecars(Index index, FrameworkConfig config) {
        for (Resource resource : index.ordered) {
            if ("template".equals(resource.type) || "flow".equals(resource.type)) {
                if (resource.yamlSource != null) indexDebugSidecar(index, resource.yamlSource.getParent().resolve("debug.yaml"));
            } else if ("tool".equals(resource.type)) {
                ToolConfig tool = config.tools().get(resource.logicalId);
                if (tool == null) continue;
                String group = tool.groupId().isEmpty() ? tool.localKey() : tool.groupId();
                indexDebugSidecar(index, runtimeConfigRoot.resolve("config/tools").resolve(group + ".debug.yaml"));
            }
        }
    }

    private void indexDebugSidecar(Index index, Path file) {
        Path candidate = file.toAbsolutePath().normalize();
        if (!candidate.startsWith(packageRoot) && !candidate.startsWith(runtimeConfigRoot)) return;
        String logicalName = candidate.startsWith(packageRoot)
                ? packageRoot.relativize(candidate).toString().replace('\\', '/')
                : runtimeConfigRoot.relativize(candidate).toString().replace('\\', '/');
        try {
            PackageResourceResolver.PackageResource resource = resolveInternal(file, PackageResourceResolver.Kind.FILE);
            index.files.add(resource.canonicalPath());
            logicalName = resource.logicalName();
            index.debugSidecars.put(logicalName, resource.canonicalPath());
        } catch (Exception ignored) {
            index.debugSidecars.put(logicalName, null);
        }
    }

    long indexBuildCount() { return indexBuildCount; }

    public int watchedDirectoryCount() {
        PackageWatch watch = packageWatch;
        if (watch == null) return 0;
        synchronized (watch) { return watch.watchedDirectories.size(); }
    }
    long packageChangeVersion() throws Exception { return packageWatch().version(); }

    private PackageWatch packageWatch() throws Exception {
        PackageWatch current = packageWatch;
        if (current != null) return current;
        synchronized (this) {
            current = packageWatch;
            if (current == null) packageWatch = current = new PackageWatch(packageRoot, packageWatchRoots());
        }
        return current;
    }

    private WatchRoots packageWatchRoots() throws Exception {
        WatchRoots roots = new WatchRoots();
        Path configFile = resolveConfigFile();
        Path configDirectory = runtimeConfigRoot.resolve("config");
        if (!Files.isSymbolicLink(configDirectory) && Files.isDirectory(configDirectory, LinkOption.NOFOLLOW_LINKS))
            roots.recursive.add(configDirectory.toRealPath());
        Path loadDirectory = packageRoot.resolve("load").normalize();
        if (Files.isDirectory(loadDirectory, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(loadDirectory))
            addWatchRoot(loadDirectory, roots);
        else roots.direct.add(packageRoot);
        FrameworkConfig config = new FrameworkConfigLoader().load(configFile, runtimeConfigRoot, packageRoot, environment);
        Path templatesRoot = config.templatesRoot().isAbsolute()
                ? config.templatesRoot() : packageRoot.resolve(config.templatesRoot());
        Path casesRoot = config.testcasesRoot().isAbsolute()
                ? config.testcasesRoot() : packageRoot.resolve(config.testcasesRoot());
        addWatchRoot(templatesRoot, roots);
        addWatchRoot(casesRoot, roots);
        for (ToolConfig tool : config.tools().values()) {
            if (tool.sourceFile() == null) continue;
            try {
                Path source = tool.sourceFile().isAbsolute() ? tool.sourceFile() : packageRoot.resolve(tool.sourceFile());
                Path canonical = resolveInternal(source, PackageResourceResolver.Kind.FILE).canonicalPath();
                if (canonical.getParent() != null) roots.direct.add(canonical.getParent());
            } catch (Exception ignored) { /* invalid or external Tool descriptors are not watchable */ }
        }
        return roots;
    }

    private void addWatchRoot(Path candidate, WatchRoots roots) {
        try {
            roots.recursive.add(resolveInternal(candidate, PackageResourceResolver.Kind.DIRECTORY).canonicalPath());
        } catch (Exception unavailable) {
            Path absolute = candidate.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null && parent.startsWith(packageRoot) && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
                roots.direct.add(parent);
        }
    }

    @Override public void close() {
        PackageWatch watch = packageWatch;
        packageWatch = null;
        cachedIndex = null;
        if (watch != null) watch.close();
    }

    private void indexTemplates(Index index, FrameworkConfig config, Path templatesRoot) {
        try {
            StageTemplateLoader loader = new StageTemplateLoader(packageRoot, templatesRoot, false);
            for (String relative : loader.paths()) {
                if (index.ordered.size() >= MAX_RESOURCES) throw new ResourceLimitException();
                Path descriptor = templatesRoot.resolve(relative).resolve("template.yaml");
                StageTemplate template;
                Resource resource = new Resource("template", resourceId("template", relative), relative);
                try {
                    descriptor = resources.fromInternalPath(descriptor, PackageResourceResolver.Kind.FILE).canonicalPath();
                    indexFile(index, descriptor);
                    template = loader.load(relative);
                    resource.name = template.name();
                    Map<String,Object> authored=yamlMap(descriptor);
                    resource.description = stringValue(authored.get("description"));
                    resource.state = "ready";
                    resource.yamlSource = descriptor;
                    resource.definition = sourceYamlMap(authored,resources.fromInternalPath(descriptor,PackageResourceResolver.Kind.FILE).logicalName());
                    indexReferencedSources(index, resource);
                    index.templateAliases.put(alias("template", relative), resource);
                    index.templateAliases.put(alias("template", template.name()), resource);
                    index.templateAliases.put(alias("template", resource.name), resource);
                    addActions(index, resource, template.actions());
                } catch (Exception error) {
                    resource.state = "invalid";
                    resource.diagnostics.add(diagnostic("ATT-RESOURCE-TEMPLATE-INVALID", "Template definition is invalid"));
                    if (Files.isRegularFile(descriptor)) {
                        try { indexFile(index, descriptor); } catch (Exception ignored) { }
                    }
                }
                index.add(resource);
            }
        } catch (ResourceLimitException error) {
            throw error;
        } catch (Exception error) {
            index.diagnostics.add(diagnostic("ATT-RESOURCE-TEMPLATE-INDEX-FAILED", "Template resources could not be indexed"));
        }
    }

    private void indexFlows(Index index, FrameworkConfig config, Path templatesRoot) {
        try {
            FlowRegistry registry = new FlowRegistry(packageRoot, templatesRoot, false);
            for (String id : registry.ids()) {
                if (index.ordered.size() >= MAX_RESOURCES) throw new ResourceLimitException();
                Resource resource = new Resource("flow", resourceId("flow", id), id);
                try {
                    FlowDefinition flow = registry.get(id);
                    Path descriptor = resources.fromInternalPath(
                            flow.directory().resolve("flow.yaml"), PackageResourceResolver.Kind.FILE).canonicalPath();
                    indexFile(index, descriptor);
                    resource.name = flow.name();
                    resource.description = flow.description();
                    resource.state = "ready";
                    resource.yamlSource = descriptor;
                    resource.definition = sourceYamlMap(yamlMap(descriptor),resources.fromInternalPath(descriptor,PackageResourceResolver.Kind.FILE).logicalName());
                    indexReferencedSources(index, resource);
                    index.flowAliases.put(alias("flow", id), resource);
                    index.flowAliases.put(alias("flow", flow.name()), resource);
                    addActions(index, resource, flow.actions());
                } catch (Exception error) {
                    resource.state = "invalid";
                    resource.diagnostics.add(diagnostic("ATT-RESOURCE-FLOW-INVALID", "Flow definition is invalid"));
                }
                index.add(resource);
            }
        } catch (ResourceLimitException error) {
            throw error;
        } catch (Exception error) {
            index.diagnostics.add(diagnostic("ATT-RESOURCE-FLOW-INDEX-FAILED", "Flow resources could not be indexed"));
        }
    }

    private void indexTools(Index index, FrameworkConfig config) {
        List<String> keys = new ArrayList<String>(config.tools().keySet());
        Collections.sort(keys);
        for (String key : keys) {
            if (index.ordered.size() >= MAX_RESOURCES) throw new ResourceLimitException();
            ToolConfig tool = config.tools().get(key);
            Resource resource = new Resource("tool", resourceId("tool", key), key);
            resource.name = tool.name();
            resource.description = scrub(tool.description());
            resource.state = "ready";
            resource.definition = toolDefinition(tool);
            Path script = permittedScript(resource, tool);
            if (script != null) {
                resource.textSource = script;
                indexFileIfPackage(index, script);
            }
            if (tool.sourceFile() != null) indexFileIfPackage(index, tool.sourceFile());
            index.toolAliases.put(alias("tool", key), resource);
            index.add(resource);
        }
    }

    private void indexCases(Index index, FrameworkConfig config) {
        Path testcasesRoot = config.testcasesRoot().isAbsolute()
                ? config.testcasesRoot() : packageRoot.resolve(config.testcasesRoot());
        try {
            testcasesRoot = resources.fromInternalPath(testcasesRoot, PackageResourceResolver.Kind.DIRECTORY).canonicalPath();
        } catch (Exception error) {
            index.diagnostics.add(diagnostic("ATT-RESOURCE-CASE-ROOT-UNAVAILABLE", "Configured testcase root is unavailable"));
            return;
        }
        List<Path> workbooks = new ArrayList<Path>();
        try (Stream<Path> paths = Files.walk(testcasesRoot)) {
            paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                    .forEach(path -> { if (workbooks.size() >= MAX_WORKBOOKS) throw new ResourceLimitException(); workbooks.add(path); });
        } catch (ResourceLimitException limit) {
            throw limit;
        } catch (Exception error) {
            index.diagnostics.add(diagnostic("ATT-RESOURCE-CASE-INDEX-FAILED", "Test Case resources could not be indexed"));
            return;
        }
        Collections.sort(workbooks);
        long totalWorkbookBytes = 0;
        for (Path workbook : workbooks) {
            if (index.ordered.size() >= MAX_RESOURCES) throw new ResourceLimitException();
            try {
                Path canonical = resources.fromInternalPath(workbook, PackageResourceResolver.Kind.FILE).canonicalPath();
                String suite = resources.fromInternalPath(canonical, PackageResourceResolver.Kind.FILE).logicalName();
                long workbookBytes=Files.size(canonical);totalWorkbookBytes+=workbookBytes;
                if (workbookBytes > MAX_WORKBOOK_BYTES || totalWorkbookBytes > MAX_TOTAL_WORKBOOK_BYTES) {
                    index.add(invalidSuite(suite, "Workbook exceeds the inspection size limit"));
                    continue;
                }
                indexFile(index, canonical);
                FrameworkConfig suiteConfig = new SuiteConfigResolver(packageRoot, config).resolve(canonical);
                Path sidecar = sidecar(canonical);
                indexFile(index, resources.fromInternalPath(sidecar, PackageResourceResolver.Kind.FILE).canonicalPath());
                long workbookStarted = System.nanoTime();
                List<TestCase> cases = new ExcelTestSuiteLoader(suiteConfig).load(canonical);
                InspectionMetrics.JVM.record(WORKBOOK_PARSE, System.nanoTime() - workbookStarted);
                for (TestCase testCase : cases) {
                    if (index.ordered.size() >= MAX_RESOURCES) throw new ResourceLimitException();
                    Resource resource = new Resource("case",
                            resourceId("case", suite + "\n" + testCase.caseId()), testCase.caseId());
                    resource.name = testCase.caseName();
                    resource.state = "ready";
                    for (String tag : testCase.tags()) resource.tags.add(scrub(tag));
                    resource.provenance.put("suite", suite);
                    resource.provenance.put("workbookId", testCase.workbookId());
                    resource.provenance.put("groupId", testCase.groupId());
                    resource.provenance.put("sheet", testCase.sheetName());
                    resource.provenance.put("rowNumber", Integer.valueOf(testCase.rowNumber()));
                    resource.definition.put("caseDataState", "hidden");
                    resource.definition.put("caseId", testCase.caseId());
                    resource.definition.put("tags", new ArrayList<String>(resource.tags));
                    List<Map<String, Object>> stages = new ArrayList<Map<String, Object>>();
                    for (Map.Entry<String, StageCaseData> stageEntry : testCase.stages().entrySet()) {
                        StageCaseData stage = stageEntry.getValue();
                        Map<String, Object> value = new LinkedHashMap<String, Object>();
                        value.put("key", stage.key());
                        value.put("template", scrub(stage.templateName()));
                        value.put("valuesState", "hidden");
                        stages.add(value);
                        Map<String,Object> stageSite = new LinkedHashMap<String,Object>();
                        stageSite.put("kind", "stage"); stageSite.put("resourceId", resource.resourceId);
                        stageSite.put("stageKey", scrub(stage.key()));
                        resource.relations.add(new Relation("template", stage.templateName(), stageSite));
                    }
                    resource.definition.put("stages", stages);
                    index.add(resource);
                }
            } catch (ResourceLimitException error) {
                throw error;
            } catch (Exception error) {
                String logical;
                try { logical = resources.fromInternalPath(workbook, PackageResourceResolver.Kind.FILE).logicalName(); }
                catch (Exception ignored) { continue; }
                index.add(invalidSuite(logical, "Workbook or sidecar could not be inspected"));
            }
        }
    }

    private Resource invalidSuite(String logicalSuite, String message) {
        Resource resource = new Resource("case", resourceId("case-suite", logicalSuite), logicalSuite);
        resource.name = Paths.get(logicalSuite).getFileName().toString();
        resource.state = "invalid";
        resource.provenance.put("suite", logicalSuite);
        resource.diagnostics.add(diagnostic("ATT-RESOURCE-CASE-INVALID", message));
        return resource;
    }

    private void addActions(Index index, Resource resource, List<TemplateAction> actions) {
        for (TemplateAction action : actions) {
            if ("flow".equalsIgnoreCase(action.type()) && action.use() != null && !action.use().isEmpty()) {
                Map<String,Object> callSite = new LinkedHashMap<String,Object>();
                callSite.put("kind", "action"); callSite.put("resourceId", resource.resourceId);
                callSite.put("actionId", scrub(action.id()));
                resource.relations.add(new Relation("flow", action.use(), callSite));
            }
            List<String> calledTools = toolCalls(action.call());
            for (int callIndex = 0; callIndex < calledTools.size(); callIndex++) {
                Map<String,Object> callSite = new LinkedHashMap<String,Object>();
                callSite.put("kind", "action"); callSite.put("resourceId", resource.resourceId);
                callSite.put("actionId", scrub(action.id())); callSite.put("callIndex", Integer.valueOf(callIndex));
                resource.relations.add(new Relation("tool", calledTools.get(callIndex), callSite));
            }
        }
    }

    private void resolveReferences(Index index, FrameworkConfig config) {
        for (Resource resource : index.ordered) {
            List<Map<String, Object>> resolved = new ArrayList<Map<String, Object>>();
            for (Relation relation : resource.relations) {
                Resource target = index.resolve(relation.type, relation.logicalId);
                boolean helper = target == null && "tool".equals(relation.type)
                        && configuredHelperCall(relation.logicalId, config);
                Map<String, Object> value = new LinkedHashMap<String, Object>();
                value.put("type", relation.type);
                value.put("logicalId", relation.logicalId);
                value.put("resourceId", target == null ? null : target.resourceId);
                if (relation.callSite != null) value.put("callSite", relation.callSite);
                if (helper) value.put("targetKind", "helper");
                value.put("resolution", target != null || helper ? "resolved" : "unresolved");
                resolved.add(value);
                if (target == null && !helper) {
                    resource.diagnostics.add(diagnostic("ATT-RESOURCE-REFERENCE-UNRESOLVED",
                            "Reference to " + relation.type + " '" + scrub(relation.logicalId) + "' is unresolved"));
                } else if (target != null) {
                    target.backReferenceCount++;
                    if (target.backReferences.size() < MAX_INLINE_BACK_REFERENCES)
                        target.backReferences.add(referenceOf(resource));
                }
            }
            resource.references = resolved;
        }
    }

    static boolean configuredHelperCall(String expression, FrameworkConfig config) {
        if (expression == null || config == null) return false;
        String[] parts = expression.split("\\.", -1);
        if (parts.length == 2) {
            ToolConfig alias = config.tool(expression);
            if (alias == null || !alias.callBacked()) return false;
            try {
                String target = new att.template.ToolCallParser().parse(alias.call()).name();
                return configuredNamespacedHelperCall(target, config);
            } catch (RuntimeException invalidAlias) { return false; }
        }
        return configuredNamespacedHelperCall(expression, config);
    }

    private static boolean configuredNamespacedHelperCall(String expression, FrameworkConfig config) {
        String[] parts = expression.split("\\.", -1);
        if (parts.length != 3 || parts[1].isEmpty()) return false;
        String namespace = parts[0], id = parts[1], method = parts[2];
        if (!HelperCallOperations.supports(namespace, method)) return false;
        if ("db".equals(namespace)) return config.dbHelper(id) != null;
        if ("mq".equals(namespace)) return config.mqHelper(id) != null;
        if ("ssh".equals(namespace)) return config.sshHelper(id) != null;
        if ("http".equals(namespace)) return config.httpHelper(id) != null;
        return false;
    }

    private Map<String, Object> summary(Resource resource, Index index) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("resourceId", resource.resourceId);
        result.put("type", resource.type);
        result.put("logicalId", scrub(resource.logicalId));
        result.put("name", scrub(resource.name));
        result.put("description", resource.description == null ? "" : scrubDescription(resource.description));
        result.put("tags", new ArrayList<String>(resource.tags));
        result.put("state", resource.state);
        result.put("sourceAvailable", Boolean.valueOf(sourceAvailable(resource)));
        result.put("sourceReason", sourceAvailable(resource) ? null : sourceUnavailableReason(resource));
        result.put("redactionEnabled", Boolean.valueOf(redactionEnabled));
        try {
            Path sourcePath = resource.yamlSource != null ? resource.yamlSource : resource.textSource;
            if (sourcePath != null && sourceAvailable(resource)) result.put("sourcePath", resource.yamlSource == null && resource.textSourceLogicalPath != null
                    ? resource.textSourceLogicalPath : resources.fromInternalPath(sourcePath, PackageResourceResolver.Kind.FILE).logicalName());
        } catch (Exception ignored) { }
        result.put("provenance", sanitize(resource.provenance, null));
        result.put("references", resource.references);
        result.put("referencedBy", resource.backReferences);
        result.put("referencedByCount", Integer.valueOf(resource.backReferenceCount));
        result.put("referencedByHasMore", Boolean.valueOf(resource.backReferenceCount > resource.backReferences.size()));
        result.put("diagnostics", resource.diagnostics);
        return result;
    }

    private Map<String, Object> toolDefinition(ToolConfig tool) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("toolId", tool.key());
        result.put("groupId", tool.groupId());
        result.put("name", tool.name());
        result.put("description", scrubDescription(tool.description()));
        result.put("executionKind", tool.commandBacked() ? "command" : "call");
        result.put("timeoutMs", tool.timeoutMs());
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ToolArgumentConfig> entry : tool.arguments().entrySet()) {
            ToolArgumentConfig argument = entry.getValue();
            Map<String, Object> field = new LinkedHashMap<String, Object>();
            field.put("name", argument.name());
            field.put("description", scrubDescription(argument.description()));
            field.put("required", Boolean.valueOf(argument.required()));
            field.put("type", argument.type());
            field.put("enum", argument.enumValues());
            field.put("multiValue", Boolean.valueOf(argument.multiValue()));
            arguments.put(entry.getKey(), field);
        }
        result.put("arguments", arguments);
        return safeMap(result);
    }

    private Path permittedScript(Resource resource, ToolConfig tool) {
        List<String> argv = new ArrayList<String>();
        argv.addAll(tool.groupScriptArgv());
        argv.addAll(tool.commandArgv());
        for (String token : argv) {
            if (token == null || token.isEmpty() || token.contains("$" + "{") || token.contains("#" + "{")
                    || token.contains("&" + "{")) continue;
            try {
                Path candidate = Paths.get(token);
                if (!candidate.isAbsolute()) candidate = packageRoot.resolve(candidate);
                candidate=candidate.toAbsolutePath().normalize();
                String filename=candidate.getFileName()==null?candidate.toString():candidate.getFileName().toString();
                if(!textExtension(filename))continue;
                boolean packageCandidate=candidate.startsWith(packageInputRoot)||candidate.startsWith(packageRoot);
                if(!packageCandidate&&Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))try {
                    packageCandidate=candidate.toRealPath().startsWith(packageRoot);
                } catch(Exception ignored) { }
                if(packageCandidate) {
                    String logical=packageRoot.relativize(candidate).toString().replace('\\','/');
                    resource.textSourceCandidate=candidate;resource.textSourceCandidateLogicalPath=logical;
                    resource.textSourceCandidateExternal=false;
                    resource.textSourceCandidateReason=packageSourceReason(logical,candidate);
                    if(!Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))resource.textSourceCandidateReason="missing";
                    else try {
                        PackageResourceResolver.PackageResource resolved=resources.fromInternalPath(candidate,PackageResourceResolver.Kind.FILE);
                        String canonicalLogical=resolved.logicalName();
                        resource.textSourceCandidate=resolved.canonicalPath();resource.textSourceCandidateLogicalPath=canonicalLogical;
                        resource.textSourceCandidateReason=packageSourceReason(canonicalLogical,resolved.canonicalPath());
                        if(resource.textSourceCandidateReason==null&&Files.isRegularFile(resolved.canonicalPath(),LinkOption.NOFOLLOW_LINKS)) {
                            if(Files.size(resolved.canonicalPath())>maxSourceBytes)resource.textSourceCandidateReason="size-limit";
                            else if(!isUtf8Text(resolved.canonicalPath()))resource.textSourceCandidateReason="binary-file";
                            else {resource.textSourceLogicalPath=canonicalLogical;resource.textSourceExternal=false;return resolved.canonicalPath();}
                        }
                    } catch(Exception denied) { resource.textSourceCandidateReason="out-of-root"; }
                } else {
                    ExternalSourceCheck check=externalSourceCheck(candidate);
                    resource.textSourceCandidate=check.canonicalPath==null?candidate:check.canonicalPath;
                    resource.textSourceCandidateLogicalPath=externalAlias(resource.textSourceCandidate);
                    resource.textSourceCandidateExternal=true;resource.textSourceCandidateReason=check.reason;
                    if(check.allowed) {
                        resource.textSourceLogicalPath=externalAlias(check.canonicalPath);resource.textSourceExternal=true;
                        return check.canonicalPath;
                    }
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    private ExternalSourceCheck externalSourceCheck(Path authoredPath) {
        Path normalized=authoredPath.toAbsolutePath().normalize();
        boolean exists=Files.exists(normalized,LinkOption.NOFOLLOW_LINKS);
        Path canonical;
        try { canonical=exists?normalized.toRealPath():canonicalMissingCandidate(normalized); }
        catch(Exception invalid) { return new ExternalSourceCheck(null,false,"forbidden"); }
        boolean inside=false;
        for(Path root:externalSourceRoots) if(canonical.startsWith(root)) {inside=true;break;}
        if(!inside)return new ExternalSourceCheck(canonical,false,"out-of-root");
        if(!externalTextSources.contains(canonical.toString()))return new ExternalSourceCheck(canonical,false,"not-allowlisted");
        if(!exists)return new ExternalSourceCheck(canonical,false,"missing");
        if(!Files.isRegularFile(canonical,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(canonical))
            return new ExternalSourceCheck(canonical,false,"forbidden");
        if(sensitiveSourcePath(canonical.toString()))return new ExternalSourceCheck(canonical,false,"sensitive-path");
        String denyPath=externalSourceDenyPath(canonical);
        if(denyPath==null||sourceGlobDenied(denyPath))return new ExternalSourceCheck(canonical,false,"forbidden");
        try {
            if(Files.size(canonical)>maxSourceBytes)return new ExternalSourceCheck(canonical,false,"size-limit");
            if(!isUtf8Text(canonical))return new ExternalSourceCheck(canonical,false,"binary-file");
        } catch(Exception ignored) { return new ExternalSourceCheck(canonical,false,"missing"); }
        return new ExternalSourceCheck(canonical,true,null);
    }

    private String externalSourceDenyPath(Path canonical) {
        Path matchingRoot=null;
        for(Path root:externalSourceRoots) {
            if(canonical.startsWith(root)&&(matchingRoot==null||root.getNameCount()>matchingRoot.getNameCount()))
                matchingRoot=root;
        }
        return matchingRoot==null?null:matchingRoot.relativize(canonical).toString().replace('\\','/');
    }

    private static Path canonicalMissingCandidate(Path normalized) throws Exception {
        Path ancestor=normalized;
        while(ancestor!=null&&!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS))ancestor=ancestor.getParent();
        if(ancestor==null)throw new IllegalArgumentException("External source has no existing ancestor");
        Path canonicalAncestor=ancestor.toRealPath();
        return canonicalAncestor.resolve(ancestor.relativize(normalized)).normalize();
    }

    private static boolean canonicalPathStartsWith(Path path,Path root) {
        try { return path.toRealPath().startsWith(root.toRealPath()); }
        catch(Exception ignored) { return false; }
    }

    private String externalAlias(Path path) {
        String filename=path.getFileName()==null?"":path.getFileName().toString();
        int dot=filename.lastIndexOf('.');String extension=dot<0?"":filename.substring(dot).toLowerCase(Locale.ROOT);
        try { return "external/"+hex(MessageDigest.getInstance("SHA-256").digest(utf8(path.toAbsolutePath().normalize().toString())))+extension; }
        catch(Exception unavailable) { return "external/source"+extension; }
    }

    private String packageSourceReason(String logical,Path path) {
        if(!sourceBrowsingEnabled)return "source-disabled";
        if(sensitiveSourcePath(logical))return "sensitive-path";
        if(sourceGlobDenied(logical))return "forbidden";
        if(!textExtension(logical))return "unsupported-file-type";
        if("allowlist-only".equals(sourceBrowsingPolicy)&&!safeTextSources.contains(logical))return "not-allowlisted";
        if(!"safe-package-files".equals(sourceBrowsingPolicy)&&!"allowlist-only".equals(sourceBrowsingPolicy))return "forbidden";
        return null;
    }

    private boolean packageSourceAllowed(String logical,Path path) { return packageSourceReason(logical,path)==null; }

    private static boolean protectedRuntimeConfigPath(String logical) {
        if (logical == null) return false;
        for (String segment : logical.split("/"))
            if (segment.toLowerCase(Locale.ROOT).startsWith("att-server")) return true;
        return false;
    }

    private String sourceDisabledOrDenied(String logical) {
        if(!sourceBrowsingEnabled)return "source-disabled";
        if(sensitiveSourcePath(logical))return "sensitive-path";
        if(sourceGlobDenied(logical))return "forbidden";
        if("allowlist-only".equals(sourceBrowsingPolicy)&&!safeTextSources.contains(logical))return "not-allowlisted";
        return "forbidden";
    }

    private String textSourceReason(Resource resource,Path path) {
        if(!sourceBrowsingEnabled)return "source-disabled";
        if(resource.textSourceExternal) {
            ExternalSourceCheck check=externalSourceCheck(path);
            return check.allowed?null:check.reason;
        }
        try {
            String logical=resource.textSourceLogicalPath==null?resources.fromInternalPath(path,PackageResourceResolver.Kind.FILE).logicalName():resource.textSourceLogicalPath;
            String reason=packageSourceReason(logical,path);
            if(reason!=null)return reason;
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return "missing";
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))return "forbidden";
            if(Files.size(path)>maxSourceBytes)return "size-limit";
            if(!isUtf8Text(path))return "binary-file";
            return null;
        } catch(Exception denied) { return "out-of-root"; }
    }

    private static final class ExternalSourceCheck {
        final Path canonicalPath;final boolean allowed;final String reason;
        ExternalSourceCheck(Path canonicalPath,boolean allowed,String reason){this.canonicalPath=canonicalPath;this.allowed=allowed;this.reason=reason;}
    }

    private static boolean textExtension(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        return value.endsWith(".sh") || value.endsWith(".bash") || value.endsWith(".py")
                || value.endsWith(".js") || value.endsWith(".ts") || value.endsWith(".sql")
                || value.endsWith(".yaml") || value.endsWith(".yml") || value.endsWith(".json") || value.endsWith(".xml")
                || value.endsWith(".groovy") || value.endsWith(".rb") || value.endsWith(".pl")
                || value.endsWith(".ps1") || value.endsWith(".bat") || value.endsWith(".cmd")
                || value.endsWith(".txt") || value.endsWith(".csv") || value.endsWith(".properties")
                || value.endsWith(".xsd") || value.endsWith(".wsdl") || value.endsWith(".html")
                || value.endsWith(".graphql") || value.endsWith(".gql");
    }

    private Map<String, Object> referenceOf(Resource resource) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", resource.type);
        result.put("resourceId", resource.resourceId);
        result.put("logicalId", scrub(resource.logicalId));
        result.put("name", scrub(resource.name));
        return result;
    }

    private Map<String, Object> safeMap(Map<?, ?> source) {
        Set<String> allowed = new HashSet<String>(java.util.Arrays.asList(
                "schemaVersion", "id", "name", "description", "actions", "parameters", "params", "inputs",
                "outputs", "variables", "environment", "timeoutMs", "timeoutSec", "retry", "retryOn", "assert",
                "assertions", "expected", "expect", "flow", "tool", "type", "use", "call", "when", "condition",
                "if", "foreach", "report", "config", "caseId", "groupId", "workbookId", "tags", "template",
                "stage", "stages", "executionKind", "toolId", "logicalId", "arguments", "required", "enum",
                "multiValue", "value", "default", "format", "properties", "items", "schema", "additionalProperties",
                "title", "version"));
        Object value = safeProjection(source, null, allowed);
        if (value instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) value;
            return result;
        }
        return new LinkedHashMap<String, Object>();
    }

    /** Recursively keep descriptor structure while excluding arbitrary authored scalar payloads. */
    private Object safeProjection(Object value, String key, Set<String> allowed) {
        if (key != null && (sensitiveKey(key) || redactionEnabled && personalField(key) || configuredSensitiveField(key))) return "[REDACTED]";
        if (key != null && redactionEnabled && freeFormKey(key)) return "[HIDDEN]";
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String name = String.valueOf(entry.getKey());
                Object child = entry.getValue();
                if (allowed.contains(name) || sensitiveKey(name) || freeFormKey(name)) {
                    result.put(name, safeProjection(child, name, allowed));
                } else if (child instanceof Map || child instanceof Iterable) {
                    Object projected = safeProjection(child, name, allowed);
                    if (projected instanceof Map && !((Map<?, ?>) projected).isEmpty()) result.put(name, projected);
                    else if (projected instanceof Iterable && ((Iterable<?>) projected).iterator().hasNext()) result.put(name, projected);
                }
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) {
                Object projected = safeProjection(item, null, allowed);
                if (projected instanceof Map && ((Map<?, ?>) projected).isEmpty()) continue;
                if (projected != null) result.add(projected);
            }
            return result;
        }
        if (value instanceof String) return key != null && allowed.contains(key) ? scrub((String) value) : null;
        return key != null && allowed.contains(key) ? value : null;
    }

    private static boolean freeFormKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.equals("description") || normalized.equals("message") || normalized.equals("note")
                || normalized.equals("text") || normalized.equals("body") || normalized.equals("payload")
                || normalized.equals("command") || normalized.equals("script") || normalized.equals("value")
                || normalized.equals("default") || normalized.equals("expected") || normalized.equals("expect")
                || normalized.equals("values") || normalized.equals("casedata") || normalized.equals("outputs")
                || normalized.equals("call") || normalized.equals("expression") || normalized.equals("assert")
                || normalized.equals("actual") || normalized.equals("when") || normalized.equals("condition")
                || normalized.equals("if") || normalized.equals("runwhen");
    }

    private boolean sourceAllowed(Resource resource) {
        try {
            if (!sourceBrowsingEnabled || resource == null || resource.yamlSource == null) return false;
            if (!("template".equals(resource.type) || "flow".equals(resource.type))) return false;
            PackageResourceResolver.PackageResource resolved = resources.fromInternalPath(resource.yamlSource, PackageResourceResolver.Kind.FILE);
            String logical = resolved.logicalName();
            String lower = logical.toLowerCase(Locale.ROOT);
            if (!(lower.endsWith("/template.yaml") || lower.endsWith("/flow.yaml"))) return false;
            if (deniedSourcePath(logical)) return false;
            if (!packageSourceAllowed(logical,resolved.canonicalPath())) return false;
            long size = Files.size(resolved.canonicalPath());
            return size <= maxSourceBytes && size > 0 && Files.isRegularFile(resolved.canonicalPath(), LinkOption.NOFOLLOW_LINKS)
                    && isUtf8Text(resolved.canonicalPath());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean sourceAvailable(Resource resource) {
        if (resource == null) return false;
        if (resource.yamlSource != null) return sourceAllowed(resource);
        return resource.textSource != null && textSourceReason(resource,resource.textSource)==null;
    }

    private String sourceUnavailableReason(Resource resource) {
        if (!sourceBrowsingEnabled) return "source-disabled";
        if (resource != null && resource.textSource != null) return textSourceReason(resource,resource.textSource);
        if (resource != null && resource.textSourceCandidate != null) return textSourceUnavailableReason(resource);
        if (resource == null || resource.yamlSource == null)
            return resource != null && ("case".equals(resource.type) || "tool".equals(resource.type))
                    ? "visibility-policy" : "source-unavailable";
        try {
            if (!Files.exists(resource.yamlSource, LinkOption.NOFOLLOW_LINKS)) return "missing";
            String logical = resources.fromInternalPath(resource.yamlSource, PackageResourceResolver.Kind.FILE).logicalName();
            if (sensitiveSourcePath(logical)) return "sensitive-path";
            if (!("template".equals(resource.type) || "flow".equals(resource.type))) return "visibility-policy";
            if (!packageSourceAllowed(logical,resource.yamlSource)) return sourceDisabledOrDenied(logical);
            if (Files.size(resource.yamlSource) > maxSourceBytes) return "size-limit";
            if (!Files.isRegularFile(resource.yamlSource, LinkOption.NOFOLLOW_LINKS)) return "forbidden";
            if (!isUtf8Text(resource.yamlSource)) return "binary-file";
            if (!sourceAllowed(resource)) return "source-unavailable";
        } catch (Exception ignored) { return "path-denied"; }
        return "source-unavailable";
    }

    private String textSourceUnavailableReason(Resource resource) {
        if (!sourceBrowsingEnabled) return "source-disabled";
        if(resource.textSourceCandidateExternal)return externalSourceCheck(resource.textSourceCandidate).reason;
        if(resource.textSourceCandidateReason!=null)return resource.textSourceCandidateReason;
        try {
            if (!Files.exists(resource.textSourceCandidate, LinkOption.NOFOLLOW_LINKS)) return "missing";
            String logical = resource.textSourceCandidateLogicalPath==null
                    ? resources.fromInternalPath(resource.textSourceCandidate, PackageResourceResolver.Kind.FILE).logicalName()
                    : resource.textSourceCandidateLogicalPath;
            String policy=packageSourceReason(logical,resource.textSourceCandidate);if(policy!=null)return policy;
            if (Files.size(resource.textSourceCandidate) > maxSourceBytes) return "size-limit";
            if (!Files.isRegularFile(resource.textSourceCandidate, LinkOption.NOFOLLOW_LINKS)) return "forbidden";
            if (!isUtf8Text(resource.textSourceCandidate)) return "binary-file";
            return "forbidden";
        } catch (Exception ignored) { return "out-of-root"; }
    }

    private boolean deniedSourcePath(String logical) {
        return sensitiveSourcePath(logical)||sourceGlobDenied(logical);
    }

    private boolean sensitiveSourcePath(String logical) {
        String lower = logical.toLowerCase(Locale.ROOT);
        String base = lower.substring(lower.lastIndexOf('/') + 1);
        return base.startsWith(".env") || base.contains("secret")
                || base.contains("credential") || base.contains("password") || base.contains("token")
                || base.endsWith(".pem") || base.endsWith(".key") || base.endsWith(".p12")
                || base.endsWith(".pfx") || base.endsWith(".jks") || base.endsWith(".keystore");
    }

    private boolean sourceGlobDenied(String logical) {
        String lower = logical.toLowerCase(Locale.ROOT);
        for (String glob : sourceDenyGlobs) if (matchesPathGlob(glob, lower)) return true;
        return false;
    }

    private static boolean matchesPathGlob(String glob, String path) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = Character.toLowerCase(glob.charAt(i));
            if (c == '*') {
                boolean recursive = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (recursive) {
                    i++;
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') { regex.append("(?:.*/)?"); i++; }
                    else regex.append(".*");
                }
                else regex.append("[^/]*");
            } else if (c == '?') regex.append("[^/]");
            else {
                if (".(){}+$^|[]\\".indexOf(c) >= 0) regex.append('\\');
                regex.append(c);
            }
        }
        return path.matches(regex.append('$').toString());
    }

    private Object sourceYamlProjection(Object value, String path) {
        return sourceYamlProjection(value,path,null);
    }

    private Object sourceYamlProjection(Object value, String path, String sourceLogicalPath) {
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                String childPath = path.isEmpty() ? key : path + "." + key;
                if (sensitiveKey(key) || redactionEnabled && (personalField(key) || protectedSshConnectionField(childPath)) || configuredSensitivePath(childPath) || configuredSensitiveField(key)
                        || redactionEnabled && (freeFormKey(key) || isSensitivePath(childPath))) result.put(key, "[REDACTED]");
                else result.put(key, sourceYamlProjection(entry.getValue(), childPath,sourceLogicalPath));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>(); int i = 0;
            for (Object item : (Iterable<?>) value) result.add(sourceYamlProjection(item, path + "[" + i++ + "]",sourceLogicalPath));
            return result;
        }
        if (value instanceof String) return sourceLogicalPath==null?scrub((String)value):scrubSourceText((String)value,sourceLogicalPath);
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sourceYamlMap(Map<?, ?> source) {
        return (Map<String, Object>) sourceYamlProjection(source, "");
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> sourceYamlMap(Map<?,?> source,String sourceLogicalPath) {
        return (Map<String,Object>)sourceYamlProjection(source,"",sourceLogicalPath);
    }

    private boolean configuredSensitivePath(String path) {
        if (!redactionEnabled) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        for (String configured : sensitivePaths) {
            String candidate = configured.toLowerCase(Locale.ROOT);
            if (candidate.equals(lower) || candidate.indexOf('*') >= 0 && matchesPathGlob(candidate, lower)) return true;
        }
        return false;
    }

    private boolean configuredSensitiveField(String key) {
        if (!redactionEnabled) return false;
        for (String expression : sensitiveFieldPatterns) {
            try { if (java.util.regex.Pattern.compile(expression, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(key).matches()) return true; }
            catch (RuntimeException ignored) { /* configuration validation rejects invalid expressions */ }
        }
        return false;
    }

    private Object sanitize(Object value, String key) {
        if (key != null && (sensitiveKey(key) || redactionEnabled && personalField(key))) return "[REDACTED]";
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String name = String.valueOf(entry.getKey());
                result.put(name, sanitize(entry.getValue(), name));
            }
            return result;
        }
        if (value instanceof Iterable) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (Iterable<?>) value) result.add(sanitize(item, null));
            return result;
        }
        if (value instanceof String) return scrub((String) value);
        return value;
    }

    private static boolean sensitiveKey(String key) {
        return att.security.SecretProtection.sensitiveName(key);
    }

    private static boolean personalField(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").matches("username|email|phone|address|ssn|nationalid|passport|dateofbirth|birthdate|creditcard|cardnumber|cvv|iban|routingnumber");
    }

    private static boolean protectedSshConnectionField(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int section = lower.lastIndexOf("sshhelpers.");
        if (section < 0) return false;
        String[] parts = lower.substring(section + "sshhelpers.".length()).split("\\.");
        if (parts.length < 2) return false;
        String field = parts[parts.length - 1];
        return "host".equals(field) || "hostname".equals(field) || "port".equals(field) || "user".equals(field);
    }

    private static boolean isSensitivePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".url") || lower.endsWith(".baseurl") || lower.endsWith(".headers")
                || lower.endsWith(".host") || lower.endsWith(".hostname") || lower.endsWith(".port")
                || lower.endsWith(".endpoint") || lower.endsWith(".queuemanager") || lower.endsWith(".channel")
                || lower.endsWith(".requestqueue") || lower.endsWith(".replyqueue");
    }

    private String scrub(String value) {
        if (value == null) return null;
        return att.core.PathPresentation.displayDiagnosticText(scrubSensitiveValues(value), packageRoot);
    }

    private String scrubDescription(String value) {
        if (value == null) return null;
        String result = scrub(value);
        if (redactionEnabled) result = SOURCE_PII_VALUE.matcher(result).replaceAll("$1[REDACTED]");
        if (redactionEnabled) result = XML_PII.matcher(result).replaceAll("$1[REDACTED]$3");
        return redactionEnabled ? ACCOUNT_IDENTIFIER.matcher(result).replaceAll("$1[REDACTED]") : result;
    }

    private String scrubSensitiveValues(String value) {
        String result = value.replace(packageRoot.toString(), "[package]");
        result = att.security.SecretProtection.text(result).replace(att.security.SecretProtection.REDACTED, "[REDACTED]");
        return result;
    }

    private String scrubSourceText(String value, String logicalPath) {
        if (value == null) return null;
        String result = sourcePathSafe(scrubSensitiveValues(value), safeFileExpressionTokens(value, logicalPath));
        if (redactionEnabled) result = SOURCE_PII_VALUE.matcher(result).replaceAll("$1[REDACTED]");
        if (redactionEnabled) result = XML_PII.matcher(result).replaceAll("$1[REDACTED]$3");
        if (logicalPath != null && logicalPath.toLowerCase(Locale.ROOT).endsWith(".csv")) result = scrubCsvPersonalData(result);
        return result;
    }

    private String scrubCsvPersonalData(String text) {
        List<List<int[]>> rows = new ArrayList<List<int[]>>();
        List<int[]> row = new ArrayList<int[]>();
        int start = 0; boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (value == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') { i++; continue; }
                quoted = !quoted;
            } else if (!quoted && (value == ',' || value == '\n' || value == '\r')) {
                row.add(new int[]{start, i});
                if (value != ',') {
                    rows.add(row); row = new ArrayList<int[]>();
                    if (value == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                }
                start = i + 1;
            }
        }
        if (start < text.length() || !row.isEmpty()) { row.add(new int[]{start, text.length()}); rows.add(row); }
        if (rows.size() < 2 || rows.get(0).isEmpty()) return text;
        Set<Integer> protectedColumns = new LinkedHashSet<Integer>();
        List<int[]> header = rows.get(0);
        for (int column = 0; column < header.size(); column++)
            if (sensitiveKey(csvCell(text, header.get(column))) || redactionEnabled && personalField(csvCell(text, header.get(column)))) protectedColumns.add(Integer.valueOf(column));
        if (protectedColumns.isEmpty()) return text;
        StringBuilder safe = new StringBuilder(text);
        for (int record = rows.size() - 1; record >= 1; record--) {
            List<int[]> fields = rows.get(record);
            for (int column = header.size() - 1; column >= 0; column--) if (protectedColumns.contains(Integer.valueOf(column)) && column < fields.size()) {
                int[] span = fields.get(column);
                safe.replace(span[0], span[1], "\"[REDACTED]\"");
            }
        }
        return safe.toString();
    }

    private static String csvCell(String text, int[] span) {
        String value = text.substring(span[0], span[1]).trim();
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
            value = value.substring(1, value.length() - 1).replace("\"\"", "\"");
        return value;
    }

    private String sourcePathSafe(String value) {
        return sourcePathSafe(value, Collections.<String>emptySet());
    }

    private String sourcePathSafe(String value, Set<String> preservedFileTokens) {
        String masked = value;
        List<String> restore = new ArrayList<String>();
        List<String> ordered = new ArrayList<String>(preservedFileTokens);
        Collections.sort(ordered, new Comparator<String>() {
            @Override public int compare(String left, String right) { return Integer.compare(right.length(), left.length()); }
        });
        for (String token : ordered) {
            if (token == null || token.isEmpty() || !masked.contains(token)) continue;
            String placeholder = "\u0001ATT_SAFE_FILE_TOKEN_" + restore.size() + "\u0002";
            masked = masked.replace(token, placeholder);
            restore.add(token);
        }
        Matcher matcher = XML_CLOSE_TAG.matcher(masked);
        StringBuffer xmlMasked = new StringBuffer();
        List<String> tags = new ArrayList<String>();
        while (matcher.find()) {
            String token = "\u0001ATT_XML_CLOSE_" + tags.size() + "\u0002";
            tags.add(matcher.group());
            matcher.appendReplacement(xmlMasked, Matcher.quoteReplacement(token));
        }
        matcher.appendTail(xmlMasked);
        String result = att.core.PathPresentation.displayDiagnosticText(xmlMasked.toString(), packageRoot);
        for (int i = 0; i < tags.size(); i++) result = result.replace("\u0001ATT_XML_CLOSE_" + i + "\u0002", tags.get(i));
        for (int i = 0; i < restore.size(); i++) result = result.replace("\u0001ATT_SAFE_FILE_TOKEN_" + i + "\u0002", restore.get(i));
        return result;
    }

    private static String dumpYaml(Object value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setWidth(120);
        return new Yaml(options).dump(value);
    }

    private static Map<String, Object> yamlMap(Path file) throws Exception {
        Object loaded = loadYaml(file, 1024 * 1024);
        return yamlMapping(loaded);
    }

    private static Map<String,Object> yamlMapping(Object loaded) {
        if (!(loaded instanceof Map)) throw new IllegalArgumentException("Resource YAML must be a mapping");
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) loaded).entrySet())
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        return result;
    }

    private static Object loadYaml(Path file,int byteLimit) throws Exception {
        byte[] bytes=readBounded(file,byteLimit);
        String source=decodeUtf8(bytes);
        long started = System.nanoTime();
        try { return YamlSupport.parser().load(new StringReader(source)); }
        finally { InspectionMetrics.JVM.record(YAML_PARSE, System.nanoTime() - started); }
    }

    private static byte[] readBounded(Path file,int byteLimit) throws Exception {
        ByteArrayOutputStream output=new ByteArrayOutputStream(Math.min(byteLimit,8192));
        try(InputStream input=Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer=new byte[8192];int count,total=0;
            while((count=input.read(buffer))!=-1) {
                InspectionMetrics.JVM.add(READ_BYTES,count);
                total+=count;if(total>byteLimit)throw new ResponseTooLargeException();
                output.write(buffer,0,count);
            }
        }
        return output.toByteArray();
    }

    private boolean isUtf8Text(Path file) {
        try { decodeUtf8(readBounded(file,maxSourceBytes));return true; }
        catch (Exception invalidOrOversized) { return false; }
    }

    private Path sidecar(Path workbook) {
        String name = workbook.getFileName().toString().replaceFirst("(?i)\\.xlsx$", ".yaml");
        return workbook.resolveSibling(name);
    }

    private String digestFiles(Set<Path> files, Map<String, Path> debugSidecars,
                               Map<String, Path> quickLoadPolicies) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(utf8("ATT_ENGINE=" + att.Version.PRODUCT));
        digest.update((byte) 0);
        List<Path> ordered = new ArrayList<Path>(files);
        Collections.sort(ordered);
        long total = 0;
        byte[] buffer = new byte[8192];
        for (Path file : ordered) {
            PackageResourceResolver.PackageResource resource = resolveInternal(file, PackageResourceResolver.Kind.FILE);
            byte[] name = utf8(resource.logicalName());
            digest.update(name);
            digest.update((byte) 0);
            try (InputStream input = Files.newInputStream(resource.canonicalPath())) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    InspectionMetrics.JVM.add(READ_BYTES,read);
                    total += read;
                    if (total > MAX_REVISION_BYTES) throw new ResourceLimitException();
                    digest.update(buffer, 0, read);
                }
            }
            digest.update((byte) 0xff);
        }
        for (Map.Entry<String, Path> sidecar : debugSidecars.entrySet()) {
            digest.update(utf8("debug-sidecar"));
            digest.update((byte) 0);
            digest.update(utf8(sidecar.getKey()));
            digest.update((byte) 0);
            digest.update((byte) (sidecar.getValue() == null ? 0 : 1));
            digest.update((byte) 0xff);
        }
        for (Map.Entry<String, Path> policy : quickLoadPolicies.entrySet()) {
            digest.update(utf8("quick-load-policy"));
            digest.update((byte) 0);
            digest.update(utf8(policy.getKey()));
            digest.update((byte) 0);
            digest.update((byte) (policy.getValue() == null ? 0 : 1));
            digest.update((byte) 0xff);
        }
        return hex(digest.digest());
    }

    private void indexQuickLoadPolicies(Index index) {
        try {
            Path loadRoot=packageRoot.resolve("load").normalize();
            if(Files.isDirectory(loadRoot,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(loadRoot)) {
                Path canonical=resources.fromInternalPath(loadRoot,PackageResourceResolver.Kind.DIRECTORY).canonicalPath();
                try(Stream<Path> paths=Files.walk(canonical,32)) {
                    paths.filter(path->Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path))
                            .filter(path->{String name=path.getFileName().toString().toLowerCase(Locale.ROOT);return name.endsWith(".yaml")||name.endsWith(".yml");})
                            .limit(20001).forEach(path->{
                                try {index.files.add(resources.fromInternalPath(path,PackageResourceResolver.Kind.FILE).canonicalPath());}
                                catch(Exception ignored) { }
                            });
                }
            }
            att.load.LoadScenarioLoader.QuickLoadTemplateCatalog catalog = new att.load.LoadScenarioLoader(packageRoot).quickLoadTemplates();
            for (att.load.LoadScenarioLoader.QuickLoadTemplate template : catalog.items) {
                if (template.bundled) continue;
                PackageResourceResolver.PackageResource resource = resources.resolvePackageRelative(template.id, PackageResourceResolver.Kind.FILE);
                index.files.add(resource.canonicalPath());
                index.quickLoadPolicies.put(resource.logicalName(), resource.canonicalPath());
            }
        } catch (Exception unavailable) { /* An unavailable catalog contributes no selectable policy files. */ }
        for (String filename : new String[]{"load.visualuser.yaml", "load.arrivalrate.yaml"}) {
            String logicalName = "load/" + filename;
            if (!index.quickLoadPolicies.containsKey(logicalName)) index.quickLoadPolicies.put(logicalName, null);
        }
    }

    private void indexFile(Index index, Path file) throws Exception {
        PackageResourceResolver.PackageResource resource = resolveInternal(file, PackageResourceResolver.Kind.FILE);
        index.files.add(resource.canonicalPath());
    }

    private Path resolveConfigFile() throws Exception {
        Path selected = RuntimeConfigPath.resolve(configPath, packageRoot);
        return configResources.fromInternalPath(selected, PackageResourceResolver.Kind.FILE).canonicalPath();
    }

    private PackageResourceResolver.PackageResource resolveInternal(Path path, PackageResourceResolver.Kind kind) {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.startsWith(packageRoot)) return resources.fromInternalPath(absolute, kind);
        if (absolute.startsWith(runtimeConfigRoot.resolve("config"))) return configResources.fromInternalPath(absolute, kind);
        throw new IllegalArgumentException("ATT resource is outside the selected Package and shared config roots");
    }

    private void indexRuntimeConfigFiles(Index index) throws Exception {
        Path configDirectory = runtimeConfigRoot.resolve("config");
        if (Files.isSymbolicLink(configDirectory) || !Files.isDirectory(configDirectory, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Shared runtime config directory is unavailable");
        int count = 0;
        long bytes = 0;
        try (java.util.stream.Stream<Path> paths = Files.walk(configDirectory, 12)) {
            for (Path candidate : (Iterable<Path>) paths::iterator) {
                if (candidate.equals(configDirectory) || Files.isSymbolicLink(candidate) || protectedRuntimeConfigPath(
                        runtimeConfigRoot.relativize(candidate).toString().replace('\\', '/'))) continue;
                if (++count > 5000) throw new ResourceLimitException();
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                bytes += Files.size(candidate);
                if (bytes > 268435456L) throw new ResourceLimitException();
                index.files.add(configResources.fromInternalPath(candidate, PackageResourceResolver.Kind.FILE).canonicalPath());
            }
        }
    }

    private void indexFileIfPackage(Index index, Path file) {
        try { indexFile(index, file); } catch (Exception ignored) { }
    }

    private List<String> toolCalls(String call) {
        if (call == null) return Collections.emptyList();
        Matcher matcher = TOOL_CALL.matcher(call);
        List<String> result = new ArrayList<String>();
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    private Map<String, Object> diagnostic(String code, String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("code", code);
        result.put("summary", scrub(message));
        return result;
    }

    private String resourceId(String type, String logicalId) {
        String key = type + "\n" + logicalId;
        return type + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(utf8(key));
    }

    private static String alias(String type, String logicalId) {
        return type + "|" + (logicalId == null ? "" : logicalId.trim());
    }

    private static boolean supportedType(String value) {
        return "case".equals(value) || "template".equals(value) || "flow".equals(value) || "tool".equals(value);
    }

    private static String stringValue(Object value) { return value == null ? "" : String.valueOf(value); }
    private static int positive(int value, int fallback, String field) {
        if (value == 0) return fallback;
        if (value < 0) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    private static boolean whitespaceOnly(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
    private static boolean deepEquals(Object left, Object right) { return left == null ? right == null : left.equals(right); }

    /**
     * Watches package metadata so a long-lived inspection Worker can reuse its parsed index.
     * Any create, edit, deletion, or watcher overflow invalidates the snapshot conservatively.
     */
    private static final class PackageWatch implements AutoCloseable {
        private static final int MAX_WATCHED_DIRECTORIES = 20000;
        private final Path root;
        private final WatchService service;
        private final Map<WatchKey, Path> directories = new HashMap<WatchKey, Path>();
        private final Set<Path> watchedDirectories = new HashSet<Path>();
        private final AtomicLong version = new AtomicLong();
        private final Thread thread;
        private volatile boolean closed;

        PackageWatch(Path root, WatchRoots roots) throws Exception {
            this.root = root.toRealPath();
            this.service = FileSystems.getDefault().newWatchService();
            try {
                for (Path candidate : roots.recursive) {
                    if (candidate == null || !candidate.startsWith(this.root)) continue;
                    if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) registerTree(candidate);
                    else if (candidate.getParent() != null) registerTree(candidate.getParent());
                }
                for (Path candidate : roots.direct) registerDirectory(candidate);
            } catch (Exception failure) {
                try { service.close(); } catch (Exception ignored) { }
                throw failure;
            }
            this.thread = new Thread(new Runnable() {
                @Override public void run() { watchLoop(); }
            }, "att-package-inspection-watch");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        synchronized long version() {
            drainPendingEvents();
            return version.get();
        }

        private void invalidate(boolean overflow) {
            version.incrementAndGet();
            InspectionMetrics.JVM.add(overflow ? WATCH_OVERFLOW : INVALIDATION, 1);
        }

        private void watchLoop() {
            while (!closed) {
                try {
                    WatchKey key = service.take();
                    synchronized (this) { process(key); }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                    invalidate(false);
                }
            }
        }

        private void drainPendingEvents() {
            WatchKey key;
            while ((key = service.poll()) != null) process(key);
        }

        private void process(WatchKey key) {
            Path directory = directories.get(key);
            if (directory == null) {
                key.cancel();
                invalidate(false);
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    invalidate(true);
                    continue;
                }
                Object context = event.context();
                if (context instanceof Path && event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    Path child = directory.resolve((Path) context);
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        try { registerTree(child); }
                        catch (Exception ignored) { /* the create event still invalidates the old snapshot */ }
                    }
                }
                invalidate(false);
            }
            if (!key.reset()) {
                directories.remove(key);
                if (directory != null) watchedDirectories.remove(directory);
                invalidate(false);
            }
        }

        private synchronized void registerTree(Path start) throws Exception {
            if (!start.startsWith(root) || Files.isSymbolicLink(start)) return;
            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws java.io.IOException {
                    if (Files.isSymbolicLink(directory) || watchedDirectories.contains(directory)) return FileVisitResult.SKIP_SUBTREE;
                    if (directories.size() >= MAX_WATCHED_DIRECTORIES) throw new java.io.IOException("Package has too many directories to watch");
                    WatchKey key = directory.register(service,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                    directories.put(key, directory);
                    watchedDirectories.add(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        synchronized void registerFileParents(Collection<Path> files) throws Exception {
            for (Path file : files) {
                Path parent = file.getParent();
                if (parent == null || !parent.startsWith(root) || Files.isSymbolicLink(parent)) continue;
                registerDirectory(parent);
            }
        }

        private void registerDirectory(Path directory) throws Exception {
            if (!directory.startsWith(root) || Files.isSymbolicLink(directory)
                    || watchedDirectories.contains(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return;
            if (directories.size() >= MAX_WATCHED_DIRECTORIES) throw new java.io.IOException("Package has too many directories to watch");
            WatchKey key = directory.register(service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            directories.put(key, directory);
            watchedDirectories.add(directory);
        }

        @Override public void close() {
            closed = true;
            try { service.close(); } catch (Exception ignored) { }
            thread.interrupt();
            synchronized (this) { directories.clear(); watchedDirectories.clear(); }
        }
    }

    private static final class WatchRoots {
        final Set<Path> recursive = new TreeSet<Path>();
        final Set<Path> direct = new TreeSet<Path>();
    }

    private static final class Index {
        final Map<String, Resource> byId = new LinkedHashMap<String, Resource>();
        final Map<String, Resource> templateAliases = new HashMap<String, Resource>();
        final Map<String, Resource> flowAliases = new HashMap<String, Resource>();
        final Map<String, Resource> toolAliases = new HashMap<String, Resource>();
        final List<Resource> ordered = new ArrayList<Resource>();
        final List<Map<String, Object>> diagnostics = new ArrayList<Map<String, Object>>();
        final Set<Path> files = new TreeSet<Path>();
        final Map<String, Path> debugSidecars = new TreeMap<String, Path>();
        final Map<String, Path> quickLoadPolicies = new TreeMap<String, Path>();
        String revisionDigest;

        void add(Resource resource) {
            Resource prior = byId.put(resource.resourceId, resource);
            if (prior != null) throw new IllegalArgumentException("Duplicate logical resource identity");
            ordered.add(resource);
        }

        Resource resolve(String type, String logicalId) {
            String key = alias(type, logicalId);
            if ("template".equals(type)) return templateAliases.get(key);
            if ("flow".equals(type)) return flowAliases.get(key);
            if ("tool".equals(type)) return toolAliases.get(key);
            return null;
        }
    }

    private static final class Resource {
        final String type;
        final String resourceId;
        final String logicalId;
        String name = "";
        String description = "";
        String state = "ready";
        Path yamlSource;
        Path textSource;
        Path textSourceCandidate;
        String textSourceLogicalPath;
        String textSourceCandidateLogicalPath;
        String textSourceCandidateReason;
        boolean textSourceExternal;
        boolean textSourceCandidateExternal;
        Map<String, Object> definition = new LinkedHashMap<String, Object>();
        final Map<String, Object> provenance = new LinkedHashMap<String, Object>();
        final List<String> tags = new ArrayList<String>();
        final List<Relation> relations = new ArrayList<Relation>();
        List<Map<String, Object>> references = new ArrayList<Map<String, Object>>();
        final List<Map<String, Object>> backReferences = new ArrayList<Map<String, Object>>();
        int backReferenceCount;
        final List<Map<String, Object>> diagnostics = new ArrayList<Map<String, Object>>();

        Resource(String type, String resourceId, String logicalId) {
            this.type = type; this.resourceId = resourceId; this.logicalId = logicalId;
        }

        boolean matches(String query) {
            if (contains(logicalId, query) || contains(name, query) || contains(description, query)) return true;
            for (String tag : tags) if (contains(tag, query)) return true;
            return false;
        }

        private static boolean contains(String value, String query) {
            return value != null && value.toLowerCase(Locale.ROOT).contains(query);
        }
    }

    private static final class SourceFileReference {
        final String id, logicalPath;
        final Path path;
        SourceFileReference(String id, String logicalPath, Path path) {
            this.id = id; this.logicalPath = logicalPath; this.path = path;
        }
    }

    private static final class SourceFileReferences {
        final List<Map<String, Object>> items;
        final boolean truncated;
        SourceFileReferences(List<Map<String, Object>> items, boolean truncated) {
            this.items = items; this.truncated = truncated;
        }
    }

    private static final class Relation {
        final String type;
        final String logicalId;
        final Map<String,Object> callSite;
        Relation(String type, String logicalId) { this(type,logicalId,null); }
        Relation(String type, String logicalId, Map<String,Object> callSite) { this.type = type; this.logicalId = logicalId; this.callSite=callSite; }
    }

    public static final class ResourceNotFoundException extends RuntimeException { }
    public static final class ResourceLimitException extends RuntimeException { }
    public static final class ResponseTooLargeException extends RuntimeException { }
    public static final class StaleResourceCursorException extends RuntimeException { }
}

