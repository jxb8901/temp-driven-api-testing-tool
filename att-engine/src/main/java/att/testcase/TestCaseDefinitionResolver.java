package att.testcase;

import att.config.YamlSupport;
import att.excel.ExcelDataSourceAdapter;
import att.flow.FlowDefinition;
import att.flow.FlowRegistry;
import att.resource.PackageResourceResolver;
import att.template.TemplateAction;
import att.validation.Diagnostic;
import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;
import att.validation.JsonSchemaVerifier;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bounded, deterministic resolver for the supported att-testcase/v1 source types.
 * It validates against the supplied active schema file and never executes a Flow.
 */
public final class TestCaseDefinitionResolver {
    public static final int MAX_SOURCE_BYTES = 1024 * 1024;
    public static final int MAX_RECORDS = 10000;
    public static final int MAX_PREVIEW_CASES = 1000;
    public static final int MAX_SELECTED_CASE_IDS = MAX_RECORDS;
    public static final int MAX_QUALIFIED_CASE_ID_LENGTH = 255;
    public static final int MAX_FLOW_DESCRIPTORS = 1000;
    public static final int MAX_FLOW_TREE_ENTRIES = 20000;
    public static final long MAX_FLOW_DESCRIPTOR_BYTES = 1024L * 1024L;
    public static final long MAX_FLOW_TREE_BYTES = 16L * 1024L * 1024L;
    public static final int MAX_PACKAGE_IDENTITY_ENTRIES = 50000;
    public static final int MAX_PACKAGE_TEST_CASE_DEFINITIONS = 2000;
    public static final long MAX_PACKAGE_IDENTITY_BYTES = 32L * 1024L * 1024L;

    private static final Pattern CASE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final Pattern RESERVED_DEVICE_NAME = Pattern.compile("^(?:[Cc][Oo][Nn]|[Pp][Rr][Nn]|[Aa][Uu][Xx]|[Nn][Uu][Ll]|[Cc][Oo][Mm][1-9]|[Ll][Pp][Tt][1-9])$");
    private static final Pattern CASE_ID_EXPRESSION = Pattern.compile("^\\$\\{DATA\\.([A-Za-z_][A-Za-z0-9_-]*)\\}$");
    private static final Pattern DATA_REFERENCE = Pattern.compile("^DATA\\.([A-Za-z_][A-Za-z0-9_-]*)$");
    private static final Pattern DEFINITION_ID = Pattern.compile("^[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*$");

    private final Path schemaFile;

    public TestCaseDefinitionResolver(Path activeSchemaFile) {
        if (activeSchemaFile == null) throw new IllegalArgumentException("The active Test Case schema file is required");
        this.schemaFile = activeSchemaFile.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(this.schemaFile)
                || !Files.isRegularFile(this.schemaFile, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("The active Test Case schema file is unavailable");
    }

    /** Resolves an existing package-relative Definition after proving its path remains package-local. */
    public TestCaseResolutionResult resolve(Path definitionPath, String packageId, Path packageRoot,
                                            Path templatesRoot, Options options) throws Exception {
        return resolve(definitionPath, packageId, packageRoot, att.flow.PackageFlowRoots.DEFAULT_FLOW_ROOT,
                templatesRoot, options);
    }

    public TestCaseResolutionResult resolve(Path definitionPath, String packageId, Path packageRoot,
                                            Path flowRoot, Path templatesRoot, Options options) throws Exception {
        if (definitionPath == null) throw new IllegalArgumentException("Test Case Definition path is required");
        PackageResourceResolver resources = new PackageResourceResolver(packageRoot);
        PackageResourceResolver.PackageResource resource;
        if (definitionPath.isAbsolute()) resource = resources.fromInternalPath(definitionPath, PackageResourceResolver.Kind.FILE);
        else resource = resources.resolvePackageRelative(definitionPath.toString().replace('\\', '/'), PackageResourceResolver.Kind.FILE);
        if (Files.isSymbolicLink(resource.canonicalPath())
                || !Files.isRegularFile(resource.canonicalPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw invalid(resource.logicalName(), "definition", "The Test Case Definition source is unavailable");
        }
        byte[] bytes = readBounded(resource.canonicalPath(), MAX_SOURCE_BYTES, "Test Case Definition source");
        return resolve(decodeUtf8(bytes, resource.logicalName()), resource.logicalName(), packageId,
                packageRoot, flowRoot, templatesRoot, options);
    }

    /**
     * Discovers one native Definition by its case-preserving ID under the configured
     * Test Case root, then resolves that contained source.
     * The bounded scan validates package-wide ID uniqueness before returning a
     * path, so callers never turn a Definition ID into an untrusted path.
     */
    public TestCaseResolutionResult resolveByDefinitionId(Path packageRoot, String packageId,
            Path templatesRoot, Path testcasesRoot, String definitionId, Options suppliedOptions) throws Exception {
        return resolveByDefinitionId(packageRoot, packageId, att.flow.PackageFlowRoots.DEFAULT_FLOW_ROOT,
                templatesRoot, testcasesRoot, definitionId, suppliedOptions);
    }

    public TestCaseResolutionResult resolveByDefinitionId(Path packageRoot, String packageId,
            Path flowRoot, Path templatesRoot, Path testcasesRoot, String definitionId, Options suppliedOptions) throws Exception {
        Options options = suppliedOptions == null ? Options.defaults() : suppliedOptions;
        validatePackageId(packageId, definitionId);
        validateDefinitionId(definitionId, definitionId);

        PackageResourceResolver resources = new PackageResourceResolver(packageRoot);
        Path configuredTestcaseRoot = testcasesRoot == null ? Paths.get("testcase") : testcasesRoot;
        Path testcaseRootPath = configuredTestcaseRoot.isAbsolute()
                ? configuredTestcaseRoot : resources.packageRoot().resolve(configuredTestcaseRoot);
        Path testcaseCandidate = resources.internalCandidate(testcaseRootPath);
        if (!Files.exists(testcaseCandidate, LinkOption.NOFOLLOW_LINKS))
            throw invalid(definitionId, "id", "Definition ID was not found under the configured testcase.root");
        if (Files.isSymbolicLink(testcaseCandidate)
                || !Files.isDirectory(testcaseCandidate, LinkOption.NOFOLLOW_LINKS))
            throw invalid(definitionId, "id", "Package testcase root must be a regular non-symlink directory");
        Path testcaseRoot = resources.fromInternalPath(testcaseCandidate, PackageResourceResolver.Kind.DIRECTORY).canonicalPath();

        Path match = null;
        Map<String,String> idsByFoldedValue = new LinkedHashMap<String,String>();
        int entries = 0;
        int definitions = 0;
        long definitionBytes = 0L;
        try (Stream<Path> paths = Files.walk(testcaseRoot)) {
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                checkCancelled(options);
                Path path = iterator.next();
                if (++entries > MAX_PACKAGE_IDENTITY_ENTRIES)
                    throw invalid(definitionId, "id", "Package Test Case discovery exceeds its entry limit");
                if (Files.isSymbolicLink(path))
                    throw invalid(definitionId, "id", "Package testcase resources must not contain symbolic links");
                if (!"testcase.yaml".equals(path.getFileName().toString())) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw invalid(definitionId, "id", "Package testcase.yaml resources must be regular files");
                PackageResourceResolver.PackageResource resource;
                try { resource = resources.fromInternalPath(path, PackageResourceResolver.Kind.FILE); }
                catch (Exception failure) { throw invalid(definitionId, "id", "A Package testcase.yaml resource is outside the Package"); }
                if (++definitions > MAX_PACKAGE_TEST_CASE_DEFINITIONS)
                    throw invalid(definitionId, "id", "Package has too many native Test Case Definitions to discover");
                long sourceSize = Files.size(resource.canonicalPath());
                if (sourceSize > MAX_SOURCE_BYTES)
                    throw invalid(resource.logicalName(), "definition", "Package Test Case Definition source exceeds its size limit");
                definitionBytes += sourceSize;
                if (definitionBytes > MAX_PACKAGE_IDENTITY_BYTES)
                    throw invalid(definitionId, "id", "Package Test Case discovery exceeds its source-size limit");
                String candidateId = readDefinitionId(resource.canonicalPath(), resource.logicalName(), options);
                validateDefinitionId(candidateId, resource.logicalName());
                String folded = asciiFold(candidateId);
                String previous = idsByFoldedValue.put(folded, resource.logicalName());
                if (previous != null) {
                    throw DiagnosticException.of(DiagnosticCodes.TESTCASE_INVALID,
                            "Package Test Case Definition IDs are ambiguous",
                            "Definition ID '" + candidateId + "' collides under ASCII case folding between '"
                                    + previous + "' and '" + resource.logicalName() + "'.",
                            "Assign each testcase.yaml a unique case-preserving ID within this Package.")
                            .withLocation(resource.logicalName(), "id", null, null, null, null, null);
                }
                if (definitionId.equals(candidateId)) {
                    match = resource.canonicalPath();
                }
            }
        }
        checkCancelled(options);
        if (match == null)
            throw invalid(definitionId, "id", "Definition ID was not found under the configured testcase.root");
        return resolve(match, packageId, resources.packageRoot(), flowRoot, templatesRoot, options);
    }

    /** Resolves a source snapshot, which can come from an immutable Package revision or an editor draft. */
    public TestCaseResolutionResult resolve(String yaml, String sourceReference, String packageId,
                                            Path packageRoot, Path templatesRoot, Options suppliedOptions) throws Exception {
        return resolve(yaml, sourceReference, packageId, packageRoot, att.flow.PackageFlowRoots.DEFAULT_FLOW_ROOT,
                templatesRoot, suppliedOptions);
    }

    public TestCaseResolutionResult resolve(String yaml, String sourceReference, String packageId,
                                            Path packageRoot, Path flowRoot, Path templatesRoot,
                                            Options suppliedOptions) throws Exception {
        Options options = suppliedOptions == null ? Options.defaults() : suppliedOptions;
        validatePackageId(packageId, sourceReference);
        validateSourceReference(sourceReference);
        if (yaml == null) throw invalid(sourceReference, "definition", "Test Case Definition source is required");
        if (utf8ByteLengthAtMost(yaml, MAX_SOURCE_BYTES) > MAX_SOURCE_BYTES)
            throw invalid(sourceReference, "definition", "Test Case Definition source exceeds its size limit");
        byte[] sourceBytes = yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (Thread.currentThread().isInterrupted() || options.cancellation().getAsBoolean())
            throw new CancellationException("Test Case Resolution was cancelled");

        Object parsed;
        try { parsed = YamlSupport.parse(yaml); }
        catch (RuntimeException failure) {
            throw invalid(sourceReference, "definition", "Test Case Definition YAML could not be parsed");
        }
        Object immutable;
        try { immutable = TestCaseValues.immutableValue(parsed, "definition"); }
        catch (DiagnosticException failure) { throw failure; }
        catch (RuntimeException failure) { throw invalid(sourceReference, "definition", safeMessage(failure, "Definition contains an unsupported YAML value")); }
        if (!(immutable instanceof Map<?,?>)) throw invalid(sourceReference, "definition", "Test Case Definition must be a YAML object");
        Map<String,Object> document = TestCaseValues.object(immutable, "definition");
        try { JsonSchemaVerifier.verify(schemaFile, document); }
        catch (Exception failure) { throw schemaFailure(sourceReference, failure); }

        TestCaseDefinition definition;
        try { definition = parseDefinition(document, sourceReference, options.maxRecords()); }
        catch (DiagnosticException failure) { throw failure; }
        catch (RuntimeException failure) { throw invalid(sourceReference, "definition", safeMessage(failure, "Test Case Definition could not be normalized")); }
        String sourceRevision = sha256(sourceBytes);
        PackageResourceResolver resources = new PackageResourceResolver(packageRoot);
        Path packageDirectory = resources.packageRoot();
        validatePackageDefinitionIdentity(definition.id(), sourceReference, packageDirectory, resources, options);
        List<ResolvedTestCase.FlowDependency> dependencies = validateAndCaptureFlow(definition.flowId(), packageDirectory,
                flowRoot, templatesRoot, resources, sourceReference, options);

        List<ResolvedTestCase> resolved = new ArrayList<ResolvedTestCase>();
        Set<String> sourceIds = new HashSet<String>();
        Set<String> caseIds = new HashSet<String>();
        if (definition.dataSourceType() == null) {
            Map<String,Object> inputs = definition.directInputs();
            resolved.add(new ResolvedTestCase(packageId, definition.id(), "default", definition.flowId(),
                    definition.name(), definition.description(), sourceReference, sourceRevision, null, null,
                    Collections.<String,Object>emptyMap(), inputs, definition.tags(), dependencies,
                    CaseSourceProvenance.inline(sourceReference, null, null)));
        } else {
            List<TestCaseDefinition.SourceRecord> sourceRecords = sourceRecords(definition, packageDirectory, sourceReference, options);
            for (TestCaseDefinition.SourceRecord record : sourceRecords) {
                checkCancelled(options);
                String sourceId = record.recordId();
                String sourceKey = asciiFold(sourceId);
                if (!sourceIds.add(sourceKey)) throw sourceInvalid(sourceReference, record, "dataSource.caseId",
                        "Source record IDs collide under ASCII case folding", "caseId");
                String caseId = resolveCaseId(definition.caseIdExpression(), sourceId, record.data(), sourceReference, record);
                if (!validCaseId(caseId)) throw sourceInvalid(sourceReference, record, "dataSource.caseId",
                        "Resolved Case ID must be a path-safe segment", caseIdColumn(record, definition.caseIdExpression()),
                        definition.caseIdExpression() != null);
                if (!caseIds.add(asciiFold(caseId))) throw sourceInvalid(sourceReference, record, "dataSource.caseId",
                        "Resolved Case IDs collide under ASCII case folding", caseIdColumn(record, definition.caseIdExpression()),
                        definition.caseIdExpression() != null);
                String qualifiedId = definition.id() + "." + caseId;
                if (qualifiedId.length() > 255) throw invalid(sourceReference, "id", "Qualified Case ID exceeds 255 ASCII characters");
                Map<String,Object> inputs = projectInputs(definition.inputProjection(), record.data(), sourceReference, record);
                List<String> tags = combineTags(definition.tags(), record.tags());
                resolved.add(new ResolvedTestCase(packageId, definition.id(), caseId, definition.flowId(),
                        definition.name(), definition.description(), sourceReference, sourceRevision, sourceId,
                        Integer.valueOf(record.index()), record.data(), inputs, tags, dependencies,
                        record.sourceProvenance()));
            }
        }

        checkCancelled(options);
        return selectAndLimit(definition, sourceReference, sourceRevision, resolved, options);
    }

    private TestCaseDefinition parseDefinition(Map<String,Object> document, String sourceReference, int maxRecords) {
        String id = string(document.get("id"), "id");
        if (id.length() > 126 || !DEFINITION_ID.matcher(id).matches() || hasReservedDefinitionSegment(id))
            throw invalid(sourceReference, "id", "Definition ID is not a valid case-preserving dotted ID");
        String flow = string(document.get("flow"), "flow");
        String name = optionalString(document.get("name"));
        String description = optionalString(document.get("description"));
        List<String> tags = stringList(document.get("tags"), "tags");
        String caseIdExpression = optionalString(document.get("caseIdExpression"));
        Map<String,Object> report = document.containsKey("report")
                ? TestCaseValues.immutableObject(document.get("report"), "report") : null;
        if (caseIdExpression != null && !CASE_ID_EXPRESSION.matcher(caseIdExpression).matches())
            throw invalid(sourceReference, "caseIdExpression", "Only a direct ${DATA.key} lookup is supported");

        Object rawDataSource = document.get("dataSource");
        if (rawDataSource == null) {
            Map<String,Object> inputs = TestCaseValues.immutableObject(document.get("inputs"), "inputs");
            return new TestCaseDefinition(id, name, description, flow, null, null, tags, inputs, null,
                    Collections.<TestCaseDefinition.SourceRecord>emptyList(),
                    report, TestCaseValues.extensionFields(document), Collections.<String,Object>emptyMap());
        }
        Map<String,Object> dataSource = TestCaseValues.object(rawDataSource, "dataSource");
        String type = string(dataSource.get("type"), "dataSource.type");
        if (!"inline".equals(type) && !"excel".equals(type)) throw invalid(sourceReference, "dataSource.type",
                "Unsupported Data Source type");
        Map<String,Object> projection = document.containsKey("inputs")
                ? TestCaseValues.immutableObject(document.get("inputs"), "inputs") : null;
        Map<String,Object> dataSourceFields = new LinkedHashMap<String,Object>();
        for (Map.Entry<String,Object> entry : dataSource.entrySet())
            if (!"records".equals(entry.getKey())) dataSourceFields.put(entry.getKey(), entry.getValue());
        if ("excel".equals(type)) {
            // The Definition keeps its authored Excel mapping; resolved workbook rows are ephemeral source records.
            return new TestCaseDefinition(id, name, description, flow, caseIdExpression, type,
                    tags, null, projection, Collections.<TestCaseDefinition.SourceRecord>emptyList(),
                    report, TestCaseValues.extensionFields(document), dataSourceFields);
        }
        Object rawRecords = dataSource.get("records");
        if (!(rawRecords instanceof List<?>)) throw invalid(sourceReference, "dataSource.records", "Inline records must be a list");
        List<?> values = (List<?>) rawRecords;
        if (values.size() > maxRecords) throw invalid(sourceReference, "dataSource.records", "Inline record count exceeds the configured resolution limit");
        List<TestCaseDefinition.SourceRecord> records = new ArrayList<TestCaseDefinition.SourceRecord>();
        for (int index = 0; index < values.size(); index++) {
            Map<String,Object> record = TestCaseValues.object(values.get(index), "dataSource.records[" + index + "]");
            String recordId = string(record.get("recordId"), "dataSource.records[" + index + "].recordId");
            Map<String,Object> data = TestCaseValues.immutableObject(record.get("data"), "dataSource.records[" + index + "].data");
            records.add(new TestCaseDefinition.SourceRecord(recordId,
                    stringList(record.get("tags"), "dataSource.records[" + index + "].tags"), data, index,
                    TestCaseValues.extensionFields(record), CaseSourceProvenance.inline(sourceReference,
                    Integer.valueOf(index), recordId)));
        }
        return new TestCaseDefinition(id, name, description, flow, caseIdExpression, type,
                tags, null, projection, records, report, TestCaseValues.extensionFields(document), dataSourceFields);
    }

    private List<TestCaseDefinition.SourceRecord> sourceRecords(TestCaseDefinition definition, Path packageRoot,
            String sourceReference, Options options) throws Exception {
        if (!"excel".equals(definition.dataSourceType())) return definition.records();
        Map<String,Object> source = definition.dataSourceFields();
        String path = string(source.get("path"), "dataSource.path");
        String sheet = string(source.get("sheet"), "dataSource.sheet");
        String caseId = string(source.get("caseId"), "dataSource.caseId");
        String tags = optionalString(source.get("tags"));
        String dataColumns = optionalString(source.get("dataColumns"));
        int headerRows = 1;
        Object configuredHeaderRows = source.get("headerRows");
        if (configuredHeaderRows != null) {
            if (!(configuredHeaderRows instanceof Number))
                throw invalid(sourceReference, "dataSource.headerRows", "Excel headerRows must be an integer");
            double number = ((Number) configuredHeaderRows).doubleValue();
            if (number != Math.rint(number) || number < 1 || number > Integer.MAX_VALUE)
                throw invalid(sourceReference, "dataSource.headerRows", "Excel headerRows must be a positive integer");
            headerRows = (int) number;
        }
        List<ExcelDataSourceAdapter.Record> loaded = ExcelDataSourceAdapter.load(new PackageResourceResolver(packageRoot),
                sourceReference, path, sheet, headerRows, caseId, tags, dataColumns, options.maxRecords(),
                options.cancellation());
        List<TestCaseDefinition.SourceRecord> records = new ArrayList<TestCaseDefinition.SourceRecord>();
        for (ExcelDataSourceAdapter.Record record : loaded) {
            try {
                records.add(new TestCaseDefinition.SourceRecord(record.recordId(), record.tags(), record.data(),
                        record.index(), Collections.<String,Object>emptyMap(), record.provenance()));
            } catch (RuntimeException failure) {
                throw invalid(record.provenance().sourceReference(), "dataSource.dataColumns",
                        "Excel row contains a value unsupported by att-testcase/v1: " + safeMessage(failure, "invalid value"))
                        .withLocation(record.provenance().sourceReference(), "dataSource.dataColumns",
                                record.provenance().sheet(), record.provenance().row(), null, null, null);
            }
        }
        return records;
    }

    /** Ensures the Definition identity is unique across the Package's native testcase.yaml resources. */
    private void validatePackageDefinitionIdentity(String currentId, String currentSourceReference,
            Path packageRoot, PackageResourceResolver resources, Options options) throws Exception {
        Map<String,String> idsByFoldedValue = new LinkedHashMap<String,String>();
        idsByFoldedValue.put(asciiFold(currentId), currentSourceReference);
        int entries = 0;
        int definitions = 0;
        long definitionBytes = 0L;
        try (Stream<Path> paths = Files.walk(packageRoot)) {
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                checkCancelled(options);
                Path path = iterator.next();
                if (++entries > MAX_PACKAGE_IDENTITY_ENTRIES)
                    throw invalid(currentSourceReference, "id", "Package Test Case identity scan exceeds its entry limit");
                if (!"testcase.yaml".equals(path.getFileName().toString())) continue;
                if (path.toAbsolutePath().normalize().equals(packageRoot.toAbsolutePath().normalize())) continue;
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw invalid(currentSourceReference, "id", "Package testcase.yaml resources must be regular non-symlink files");
                PackageResourceResolver.PackageResource resource;
                try { resource = resources.fromInternalPath(path, PackageResourceResolver.Kind.FILE); }
                catch (Exception failure) { throw invalid(currentSourceReference, "id", "A Package testcase.yaml resource is outside the Package"); }
                if (currentSourceReference.equals(resource.logicalName())) continue;
                if (++definitions > MAX_PACKAGE_TEST_CASE_DEFINITIONS)
                    throw invalid(currentSourceReference, "id", "Package has too many native Test Case Definitions to validate identities");
                long sourceSize = Files.size(resource.canonicalPath());
                if (sourceSize > MAX_SOURCE_BYTES)
                    throw invalid(resource.logicalName(), "definition", "Package Test Case Definition source exceeds its size limit");
                definitionBytes += sourceSize;
                if (definitionBytes > MAX_PACKAGE_IDENTITY_BYTES)
                    throw invalid(currentSourceReference, "id", "Package Test Case identity scan exceeds its source-size limit");
                String otherId = readDefinitionId(resource.canonicalPath(), resource.logicalName(), options);
                validateDefinitionId(otherId, resource.logicalName());
                String folded = asciiFold(otherId);
                String previous = idsByFoldedValue.put(folded, resource.logicalName());
                if (previous != null) {
                    throw DiagnosticException.of(DiagnosticCodes.TESTCASE_INVALID,
                            "Package Test Case Definition IDs are ambiguous",
                            "Definition ID '" + otherId + "' collides under ASCII case folding between '"
                                    + previous + "' and '" + resource.logicalName() + "'.",
                            "Assign each testcase.yaml a unique case-preserving ID within this Package.")
                            .withLocation(resource.logicalName(), "id", null, null, null, null, null);
                }
            }
        }
    }

    private String readDefinitionId(Path file, String logicalName, Options options) throws Exception {
        checkCancelled(options);
        byte[] bytes = readBounded(file, MAX_SOURCE_BYTES, "Package Test Case Definition source");
        String yaml = decodeUtf8(bytes, logicalName);
        Object parsed;
        try { parsed = YamlSupport.parse(yaml); }
        catch (RuntimeException failure) { throw invalid(logicalName, "definition", "Package Test Case Definition YAML could not be parsed"); }
        try {
            Object immutable = TestCaseValues.immutableValue(parsed, "definition");
            if (!(immutable instanceof Map<?,?>)) throw invalid(logicalName, "definition", "Package Test Case Definition must be a YAML object");
            return string(TestCaseValues.object(immutable, "definition").get("id"), "id");
        } catch (DiagnosticException failure) { throw failure; }
        catch (RuntimeException failure) { throw invalid(logicalName, "id", safeMessage(failure, "Package Test Case Definition ID is unavailable")); }
    }

    private static void validateDefinitionId(String id, String sourceReference) {
        if (id.length() > 126 || !DEFINITION_ID.matcher(id).matches() || hasReservedDefinitionSegment(id))
            throw invalid(sourceReference, "id", "Package Test Case Definition ID is not a valid case-preserving dotted ID");
    }

    private static boolean hasReservedDefinitionSegment(String id) {
        for (String segment : id.split("\\.")) if (isReservedDeviceName(segment)) return true;
        return false;
    }

    private List<ResolvedTestCase.FlowDependency> validateAndCaptureFlow(String flowId, Path packageRoot,
            Path flowRoot, Path templatesRoot, PackageResourceResolver resources, String sourceReference,
            Options options) throws Exception {
        Path configuredFlowRoot = flowRoot == null ? att.flow.PackageFlowRoots.DEFAULT_FLOW_ROOT : flowRoot;
        att.flow.PackageFlowRoots.Resolution resolution = att.flow.PackageFlowRoots.resolve(
                packageRoot, configuredFlowRoot, templatesRoot);
        FlowTreeSnapshot flowSnapshots = preflightFlowTrees(resolution.existingRoots(), resources, sourceReference, options);
        return resolveCapturedFlows(flowId, packageRoot, resolution, sourceReference, flowSnapshots, options);
    }

    private FlowTreeSnapshot preflightFlowTrees(List<Path> flowRoots, PackageResourceResolver resources,
            String sourceReference, Options options) throws Exception {
        Map<Path,byte[]> snapshots = new LinkedHashMap<Path,byte[]>();
        Map<Path,String> logicalNames = new LinkedHashMap<Path,String>();
        Set<Path> templateDescriptors = new LinkedHashSet<Path>();
        for (Path flowRoot : flowRoots) {
            FlowTreeSnapshot part = preflightFlowTree(flowRoot, resources, sourceReference, options);
            snapshots.putAll(part.sources);
            logicalNames.putAll(part.logicalNames);
            templateDescriptors.addAll(part.templateDescriptors);
        }
        return new FlowTreeSnapshot(snapshots, logicalNames, templateDescriptors);
    }

    List<ResolvedTestCase.FlowDependency> resolveCapturedFlows(String flowId, Path packageRoot, Path templates,
            String sourceReference, FlowTreeSnapshot flowSnapshots) throws Exception {
        return resolveCapturedFlows(flowId, packageRoot,
                att.flow.PackageFlowRoots.resolve(packageRoot, att.flow.PackageFlowRoots.DEFAULT_FLOW_ROOT, templates),
                sourceReference, flowSnapshots, Options.defaults());
    }

    List<ResolvedTestCase.FlowDependency> resolveCapturedFlows(String flowId, Path packageRoot,
            att.flow.PackageFlowRoots.Resolution flowRoots, String sourceReference, FlowTreeSnapshot flowSnapshots,
            Options options) throws Exception {
        checkCancelled(options);
        PackageResourceResolver resources = new PackageResourceResolver(packageRoot);
        FlowRegistry registry;
        try { registry = new FlowRegistry(packageRoot, flowRoots, false,
                flowSnapshots.sources, flowSnapshots.templateDescriptors); }
        catch (Exception failure) { throw flowFailure(sourceReference, packageRoot, failure, "Package Flow registry is invalid"); }
        checkCancelled(options);
        if (!FlowRegistry.isCanonicalId(flowId)) throw invalid(sourceReference, "flow", "Flow ID must be static and canonical");
        FlowDefinition selected;
        try { selected = registry.get(flowId); }
        catch (Exception failure) { throw flowFailure(sourceReference, packageRoot, failure, "Selected Flow is invalid"); }
        if (selected == null) throw invalid(sourceReference, "flow", "Selected Flow does not exist in this Package");
        checkCancelled(options);

        LinkedHashMap<String,ResolvedTestCase.FlowDependency> dependencies = new LinkedHashMap<String,ResolvedTestCase.FlowDependency>();
        collectFlow(selected, registry, flowSnapshots.logicalNames, sourceReference,
                new LinkedHashSet<String>(), dependencies, 1, packageRoot, options);
        return Collections.unmodifiableList(new ArrayList<ResolvedTestCase.FlowDependency>(dependencies.values()));
    }

    private void collectFlow(FlowDefinition flow, FlowRegistry registry, Map<Path,String> logicalNames,
            String sourceReference, Set<String> path,
            Map<String,ResolvedTestCase.FlowDependency> dependencies, int depth,
            Path packageRoot, Options options) throws Exception {
        checkCancelled(options);
        if (depth > FlowRegistry.MAX_DEPTH) throw invalid(sourceReference, "flow", "Flow dependency depth exceeds the supported bound");
        if (path.contains(flow.id())) throw invalid(sourceReference, "flow", "Flow dependency cycle detected");
        if (dependencies.containsKey(flow.id())) return;
        Path descriptor = registry.descriptorPath(flow.id());
        if (descriptor == null) throw invalid(sourceReference, "flow", "A referenced Flow descriptor is unavailable");
        String logicalName = logicalNames.get(descriptor.toAbsolutePath().normalize());
        if (logicalName == null) throw invalid(sourceReference, "flow", "A referenced Flow source snapshot is unavailable");
        byte[] bytes = registry.descriptorBytes(flow.id());
        if (bytes == null) throw invalid(sourceReference, "flow", "A referenced Flow source snapshot is unavailable");
        ResolvedTestCase.FlowDependency dependency = new ResolvedTestCase.FlowDependency(flow.id(),
                logicalName, sha256(bytes), flow.schemaVersion());
        dependencies.put(flow.id(), dependency);
        path.add(flow.id());
        try {
            for (TemplateAction action : flow.actions()) {
                checkCancelled(options);
                if ("flow".equalsIgnoreCase(action.type())) {
                    String targetId = action.use();
                    if (!FlowRegistry.isCanonicalId(targetId)) throw invalid(sourceReference, "flow", "Flow invocation ID is not static and canonical");
                    FlowDefinition target;
                    try { target = registry.get(targetId); }
                    catch (Exception failure) { throw flowFailure(sourceReference, packageRoot, failure, "A referenced Flow is invalid"); }
                    if (target == null) throw invalid(sourceReference, "flow", "A referenced Flow does not exist");
                    collectFlow(target, registry, logicalNames, sourceReference, path, dependencies, depth + 1,
                            packageRoot, options);
                }
            }
        } finally { path.remove(flow.id()); }
    }

    FlowTreeSnapshot preflightFlowTree(Path flowRoot, PackageResourceResolver resources, String sourceReference) throws Exception {
        return preflightFlowTree(flowRoot, resources, sourceReference, Options.defaults());
    }

    FlowTreeSnapshot preflightFlowTree(Path flowRoot, PackageResourceResolver resources, String sourceReference,
                                       Options options) throws Exception {
        Map<Path,byte[]> snapshots = new LinkedHashMap<Path,byte[]>();
        Map<Path,String> logicalNames = new LinkedHashMap<Path,String>();
        Set<Path> templateDescriptors = new LinkedHashSet<Path>();
        if (!Files.exists(flowRoot, LinkOption.NOFOLLOW_LINKS))
            return new FlowTreeSnapshot(snapshots, logicalNames, templateDescriptors);
        if (Files.isSymbolicLink(flowRoot) || !Files.isDirectory(flowRoot, LinkOption.NOFOLLOW_LINKS))
            throw invalid(sourceReference, "flow", "Package Flow root must be a regular directory");
        Path canonical = resources.fromInternalPath(flowRoot, PackageResourceResolver.Kind.DIRECTORY).canonicalPath();
        int entries = 0;
        int descriptors = 0;
        long totalBytes = 0L;
        try (Stream<Path> paths = Files.walk(canonical)) {
            java.util.Iterator<Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                checkCancelled(options);
                Path path = iterator.next();
                if (++entries > MAX_FLOW_TREE_ENTRIES) throw invalid(sourceReference, "flow", "Package Flow tree has too many entries");
                if ("template.yaml".equals(path.getFileName().toString())) {
                    if (Files.isRegularFile(path)) {
                        try { resources.fromInternalPath(path, PackageResourceResolver.Kind.FILE); }
                        catch (Exception failure) { throw invalid(sourceReference, "flow", "Package Template descriptor is outside the Package"); }
                        templateDescriptors.add(path.toAbsolutePath().normalize());
                    }
                    continue;
                }
                if (!"flow.yaml".equals(path.getFileName().toString())) continue;
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw invalid(sourceReference, "flow", "Flow descriptors must be regular files");
                checkCancelled(options);
                long size = Files.size(path);
                if (size > MAX_FLOW_DESCRIPTOR_BYTES) throw invalid(sourceReference, "flow", "A Flow descriptor exceeds its size limit");
                if (++descriptors > MAX_FLOW_DESCRIPTORS) throw invalid(sourceReference, "flow", "Package Flow tree has too many descriptors");
                PackageResourceResolver.PackageResource resource = resources.fromInternalPath(path, PackageResourceResolver.Kind.FILE);
                byte[] bytes = readBounded(resource.canonicalPath(), (int) MAX_FLOW_DESCRIPTOR_BYTES, "Flow descriptor");
                checkCancelled(options);
                totalBytes += bytes.length;
                if (totalBytes > MAX_FLOW_TREE_BYTES) throw invalid(sourceReference, "flow", "Package Flow source exceeds its total size limit");
                Path canonicalPath = resource.canonicalPath().toAbsolutePath().normalize();
                snapshots.put(canonicalPath, bytes);
                logicalNames.put(canonicalPath, resource.logicalName());
            }
        }
        checkCancelled(options);
        return new FlowTreeSnapshot(snapshots, logicalNames, templateDescriptors);
    }

    static final class FlowTreeSnapshot {
        final Map<Path,byte[]> sources;
        final Map<Path,String> logicalNames;
        final Set<Path> templateDescriptors;

        FlowTreeSnapshot(Map<Path,byte[]> sources, Map<Path,String> logicalNames, Set<Path> templateDescriptors) {
            Map<Path,byte[]> sourceCopies = new LinkedHashMap<Path,byte[]>();
            for (Map.Entry<Path,byte[]> entry : sources.entrySet())
                sourceCopies.put(entry.getKey(), entry.getValue().clone());
            this.sources = Collections.unmodifiableMap(sourceCopies);
            this.logicalNames = Collections.unmodifiableMap(new LinkedHashMap<Path,String>(logicalNames));
            this.templateDescriptors = Collections.unmodifiableSet(new LinkedHashSet<Path>(templateDescriptors));
        }
    }

    private TestCaseResolutionResult selectAndLimit(TestCaseDefinition definition, String sourceReference,
            String sourceRevision, List<ResolvedTestCase> resolved, Options options) {
        Set<String> selectors = options.selectedQualifiedCaseIds();
        List<ResolvedTestCase> selected = new ArrayList<ResolvedTestCase>();
        if (selectors == null) selected.addAll(resolved);
        else {
            Set<String> remaining = new LinkedHashSet<String>(selectors);
            for (ResolvedTestCase testCase : resolved) if (remaining.remove(testCase.qualifiedCaseId())) selected.add(testCase);
            if (!remaining.isEmpty()) throw invalid(sourceReference, "selection", "Selection contains an unknown Qualified Case ID");
        }
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>();
        if (selected.isEmpty()) diagnostics.add(new Diagnostic(DiagnosticCodes.SELECTION_EMPTY,
                Diagnostic.Severity.ERROR, "Case selection is empty", sourceReference, "selection", null,
                null, null, null, null, "Select at least one resolved Case before execution."));
        int previewCount = Math.min(selected.size(), options.previewLimit());
        List<ResolvedTestCase> preview = selected.subList(0, previewCount);
        // selected remains the complete bounded execution plan; preview is presentation-only.
        return new TestCaseResolutionResult(definition, sourceReference, sourceRevision, selected, preview,
                resolved.size(), selected.size(), selected.size() > previewCount, diagnostics);
    }

    private Map<String,Object> projectInputs(Map<String,Object> projection, Map<String,Object> data,
            String sourceReference, TestCaseDefinition.SourceRecord record) {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        if (projection == null) {
            result.putAll(data);
            return result;
        }
        for (Map.Entry<String,Object> entry : projection.entrySet()) {
            String inputName = entry.getKey();
            Map<String,Object> binding = TestCaseValues.object(entry.getValue(), "inputs." + inputName);
            if (binding.containsKey("from")) {
                String reference = string(binding.get("from"), "inputs." + inputName + ".from");
                Matcher matcher = DATA_REFERENCE.matcher(reference);
                if (!matcher.matches()) throw invalid(sourceReference, "inputs." + inputName + ".from",
                        "Only a direct DATA.key input binding is supported");
                String key = matcher.group(1);
                if (!data.containsKey(key)) {
                    if ("excel".equals(record.sourceProvenance().sourceType()))
                        throw invalid(sourceReference, "inputs." + inputName + ".from", "A mapped Data Source key is missing");
                    throw invalid(sourceReference, "dataSource.records[" + record.index() + "].data." + key,
                            "A mapped Data Source key is missing");
                }
                result.put(inputName, data.get(key));
            } else if (binding.containsKey("value")) result.put(inputName, binding.get("value"));
            else throw invalid(sourceReference, "inputs." + inputName, "Input binding must contain from or value");
        }
        return result;
    }

    private String resolveCaseId(String expression, String sourceId, Map<String,Object> data,
            String sourceReference, TestCaseDefinition.SourceRecord record) {
        if (expression == null) return sourceId;
        Matcher expressionMatcher = CASE_ID_EXPRESSION.matcher(expression);
        if (!expressionMatcher.matches()) throw invalid(sourceReference, "caseIdExpression", "Only a direct ${DATA.key} lookup is supported");
        String key = expressionMatcher.group(1);
        if (!data.containsKey(key)) {
            if ("excel".equals(record.sourceProvenance().sourceType()))
                throw invalid(sourceReference, "caseIdExpression", "Case ID source key is not mapped by dataColumns");
            throw invalid(sourceReference, "dataSource.records[" + record.index() + "].data." + key,
                    "Case ID source key is missing");
        }
        Object value = data.get(key);
        if (!(value instanceof String)) throw sourceInvalid(sourceReference, record,
                "dataSource.dataColumns." + key, "Case ID source value must be a string", key, true);
        String caseId = (String) value;
        if (!validCaseId(caseId)) throw sourceInvalid(sourceReference, record,
                "dataSource.dataColumns." + key, "Case ID source value is invalid", key, true);
        return caseId;
    }

    private String caseIdColumn(TestCaseDefinition.SourceRecord record, String expression) {
        if (expression == null) return "caseId";
        Matcher matcher = CASE_ID_EXPRESSION.matcher(expression);
        return matcher.matches() ? matcher.group(1) : "caseId";
    }

    private DiagnosticException sourceInvalid(String definitionReference, TestCaseDefinition.SourceRecord record,
            String field, String detail, String columnAlias) {
        return sourceInvalid(definitionReference, record, field, detail, columnAlias, false);
    }

    private DiagnosticException sourceInvalid(String definitionReference, TestCaseDefinition.SourceRecord record,
            String field, String detail, String columnAlias, boolean mappedDataColumn) {
        CaseSourceProvenance provenance = record.sourceProvenance();
        if (provenance != null && "excel".equals(provenance.sourceType())) {
            Integer column = provenanceColumn(provenance, columnAlias, mappedDataColumn);
            return new DiagnosticException(DiagnosticCodes.TESTCASE_INVALID, "Test Case Resolution failed", detail,
                    provenance.sourceReference(), field, provenance.sheet(), provenance.row(), column,
                    null, null, "Correct the indicated Excel cell and retry Resolution.", null);
        }
        return invalid(definitionReference, "dataSource.records[" + record.index() + "].recordId", detail);
    }

    private Integer provenanceColumn(CaseSourceProvenance provenance, String alias, boolean mappedDataColumn) {
        Object rawColumns = provenance.metadata().get("columns");
        if (!(rawColumns instanceof Map<?,?>)) return null;
        Map<?,?> columns = (Map<?,?>) rawColumns;
        Object raw = mappedDataColumn
                ? ((columns.get("data") instanceof Map<?,?>) ? ((Map<?,?>) columns.get("data")).get(alias) : null)
                : ("caseId".equals(alias) ? columns.get("caseId")
                        : ((columns.get("data") instanceof Map<?,?>) ? ((Map<?,?>) columns.get("data")).get(alias) : null));
        return raw instanceof Number ? Integer.valueOf(((Number) raw).intValue()) : null;
    }

    private static String asciiFold(String value) {
        StringBuilder folded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            folded.append(current >= 'A' && current <= 'Z' ? (char) (current + ('a' - 'A')) : current);
        }
        return folded.toString();
    }

    private static boolean validCaseId(String value) {
        return CASE_ID.matcher(value).matches() && !isReservedDeviceName(value);
    }

    private static boolean isReservedDeviceName(String value) {
        return RESERVED_DEVICE_NAME.matcher(value).matches();
    }

    private static List<String> combineTags(List<String> definitionTags, List<String> recordTags) {
        LinkedHashSet<String> result = new LinkedHashSet<String>(definitionTags);
        result.addAll(recordTags);
        return Collections.unmodifiableList(new ArrayList<String>(result));
    }

    private static List<String> stringList(Object value, String field) {
        if (value == null) return Collections.emptyList();
        if (!(value instanceof List<?>)) throw new IllegalArgumentException(field + " must be a list");
        List<String> result = new ArrayList<String>();
        for (Object item : (List<?>) value) result.add(string(item, field + " entry"));
        return result;
    }

    private static String optionalString(Object value) {
        if (value == null) return null;
        if (!(value instanceof String)) throw new IllegalArgumentException("Expected a string value");
        return (String) value;
    }

    private static String string(Object value, String field) {
        if (!(value instanceof String)) throw new IllegalArgumentException(field + " must be a string");
        return (String) value;
    }

    private static void validatePackageId(String packageId, String sourceReference) {
        if (packageId == null || packageId.trim().isEmpty()) throw invalid(sourceReference, "packageId", "Package ID is required for Case Resolution");
    }

    private static void validateSourceReference(String sourceReference) {
        if (sourceReference == null || sourceReference.trim().isEmpty() || sourceReference.length() > 512
                || sourceReference.startsWith("/") || sourceReference.indexOf('\\') >= 0
                || sourceReference.matches("^[A-Za-z]:.*")) {
            throw invalid("<definition>", "definition", "Definition source reference must be a package-relative path");
        }
        for (String segment : sourceReference.split("/", -1)) if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))
            throw invalid("<definition>", "definition", "Definition source reference must be a package-relative path");
    }

    private static DiagnosticException schemaFailure(String sourceReference, Exception failure) {
        JsonSchemaVerifier.SchemaValidationException validation = JsonSchemaVerifier.SchemaValidationException.find(failure);
        DiagnosticException diagnostic = DiagnosticException.of(DiagnosticCodes.TESTCASE_INVALID,
                "Test Case Definition is invalid", validation == null ? "Definition does not match att-testcase/v1" : validation.getMessage(),
                "Correct the reported Definition field and retry Resolution.").withLocation(sourceReference, "definition", null, null, null, null, null);
        return validation == null ? diagnostic : diagnostic.withSchemaViolations(validation.structuredViolations());
    }

    private static DiagnosticException flowFailure(String sourceReference, Path packageRoot,
            Exception failure, String fallback) {
        DiagnosticException typed = DiagnosticException.find(failure);
        if (typed != null) return typed.forPackage(packageRoot).withLocation(sourceReference, "flow", null, null, null, null, null);
        String detail = safeMessage(failure, fallback);
        detail = att.core.PathPresentation.displayDiagnosticText(detail, packageRoot);
        return invalid(sourceReference, "flow", fallback + ": " + detail);
    }

    private static String safeMessage(Throwable failure, String fallback) {
        if (failure == null || failure.getMessage() == null || failure.getMessage().trim().isEmpty()) return fallback;
        String message = att.security.SecretProtection.text(failure.getMessage());
        return message == null || message.trim().isEmpty() ? fallback : message;
    }

    private static DiagnosticException invalid(String sourceReference, String field, String detail) {
        return DiagnosticException.of(DiagnosticCodes.TESTCASE_INVALID, "Test Case Resolution failed", detail,
                "Correct the indicated Test Case Definition, Data Source, or Flow reference.")
                .withLocation(sourceReference, field, null, null, null, null, null);
    }

    private static String decodeUtf8(byte[] bytes, String sourceReference) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw invalid(sourceReference, "definition", "Test Case Definition source is not valid UTF-8");
        }
    }

    private static void checkCancelled(Options options) {
        if (Thread.currentThread().isInterrupted() || options.cancellation().getAsBoolean())
            throw new CancellationException("Test Case Resolution was cancelled");
    }

    private static byte[] readBounded(Path file, int maximum, String label) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > maximum) throw new IllegalArgumentException(label + " exceeds its size limit");
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        final char[] digits = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) {
            int unsigned = value & 0xff;
            result.append(digits[unsigned >>> 4]).append(digits[unsigned & 0x0f]);
        }
        return result.toString();
    }

    /** Counts UTF-8 bytes without copying the complete source String into a byte array. */
    static long utf8ByteLengthAtMost(String value, long maximum) {
        java.nio.charset.CharsetEncoder encoder = java.nio.charset.StandardCharsets.UTF_8.newEncoder();
        long replacementBytes = encoder.replacement().length;
        long count = 0L;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current <= 0x7f) count++;
            else if (current <= 0x7ff) count += 2L;
            else if (Character.isHighSurrogate(current) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                count += 4L;
                index++;
            } else if (Character.isSurrogate(current)) count += replacementBytes;
            else count += 3L;
            if (count > maximum) return count;
        }
        return count;
    }

    /**
     * Selection and preview controls. Values are copied at construction and never alter source order.
     * {@code previewLimit} only truncates the presentation preview; {@link TestCaseResolutionResult#selectedCases()}
     * always retains the complete bounded selection up to {@code maxRecords}.
     */
    public static final class Options {
        private final int maxRecords;
        private final int previewLimit;
        private final Set<String> selectedQualifiedCaseIds;
        private final BooleanSupplier cancellation;

        public Options() { this(MAX_RECORDS, 100, null, new BooleanSupplier() {
            @Override public boolean getAsBoolean() { return false; }
        }); }

        public Options(int maxRecords, int previewLimit, Set<String> selectedQualifiedCaseIds,
                       BooleanSupplier cancellation) {
            if (maxRecords < 1 || maxRecords > MAX_RECORDS) throw new IllegalArgumentException("maxRecords must be between 1 and " + MAX_RECORDS);
            if (previewLimit < 0 || previewLimit > MAX_PREVIEW_CASES) throw new IllegalArgumentException("previewLimit must be between 0 and " + MAX_PREVIEW_CASES);
            this.maxRecords = maxRecords;
            this.previewLimit = previewLimit;
            if (selectedQualifiedCaseIds == null) this.selectedQualifiedCaseIds = null;
            else {
                if (selectedQualifiedCaseIds.size() > MAX_SELECTED_CASE_IDS)
                    throw new IllegalArgumentException("selected Qualified Case IDs must not exceed " + MAX_SELECTED_CASE_IDS);
                LinkedHashSet<String> copy = new LinkedHashSet<String>();
                for (String id : selectedQualifiedCaseIds) {
                    if (id == null) throw new IllegalArgumentException("selected Qualified Case IDs must be nonblank");
                    if (id.length() > MAX_QUALIFIED_CASE_ID_LENGTH)
                        throw new IllegalArgumentException("selected Qualified Case IDs must not exceed " + MAX_QUALIFIED_CASE_ID_LENGTH + " characters");
                    if (id.trim().isEmpty()) throw new IllegalArgumentException("selected Qualified Case IDs must be nonblank");
                    copy.add(id);
                }
                this.selectedQualifiedCaseIds = Collections.unmodifiableSet(copy);
            }
            this.cancellation = cancellation == null ? new BooleanSupplier() {
                @Override public boolean getAsBoolean() { return false; }
            } : cancellation;
        }

        public static Options defaults() { return new Options(); }
        public int maxRecords() { return maxRecords; }
        public int previewLimit() { return previewLimit; }
        public Set<String> selectedQualifiedCaseIds() { return selectedQualifiedCaseIds; }
        public BooleanSupplier cancellation() { return cancellation; }
    }
}
