package att.testcase;

import att.validation.DiagnosticCodes;
import att.validation.DiagnosticException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TestCaseDefinitionResolverTest {
    @TempDir Path temp;
    private Path packageRoot;
    private Path templatesRoot;
    private TestCaseDefinitionResolver resolver;

    @BeforeEach void setUp() throws Exception {
        packageRoot = Files.createDirectories(temp.resolve("package"));
        templatesRoot = Files.createDirectories(packageRoot.resolve("templates"));
        resolver = new TestCaseDefinitionResolver(repositoryRoot().resolve("schemas/proposed/att-testcase-v1.schema.json"));
        writeFlow("payment.transfer.v1", "");
    }

    @Test void resolvesOneTypedInlineDefinitionWithoutExecutingOrMutatingSources() throws Exception {
        String yaml = "schemaVersion: att-testcase/v1\n"
                + "id: fpp.transfer.success\nname: Transfer\ndescription: One direct Case\n"
                + "flow: payment.transfer.v1\ntags: [fpp, smoke]\ninputs:\n"
                + "  debitAccount: A001\n  amount: 100\n  enabled: true\n  optional: null\n"
                + "  payload: {numbers: [1, 2], flags: {active: false}}\n";
        byte[] beforeFlow = Files.readAllBytes(flowPath("payment.transfer.v1"));
        TestCaseResolutionResult result = resolver.resolve(yaml, "testcase/transfer.yaml", "payments",
                packageRoot, templatesRoot, TestCaseDefinitionResolver.Options.defaults());

        assertEquals(1, result.resolvedCount());
        assertEquals(1, result.selectedCount());
        assertEquals("fpp.transfer.success", result.definition().id());
        assertEquals("fpp.transfer.success.default", result.cases().get(0).qualifiedCaseId());
        assertEquals(100, result.cases().get(0).inputs().get("amount"));
        assertEquals(Boolean.TRUE, result.cases().get(0).inputs().get("enabled"));
        assertNull(result.cases().get(0).inputs().get("optional"));
        assertEquals(Arrays.asList(Integer.valueOf(1), Integer.valueOf(2)),
                ((Map<?,?>) result.cases().get(0).inputs().get("payload")).get("numbers"));
        assertEquals("ResolvedTestCase", result.cases().get(0).toMap().get("kind"));
        assertEquals("TestCaseDefinition", result.definition().toMap().get("kind"));
        assertEquals("CaseExecution", new CaseExecution("E-1", "fpp.transfer.success.default", "PENDING").toMap().get("kind"));
        assertEquals("payment.transfer.v1", result.cases().get(0).dependencies().get(0).id());
        assertEquals(64, result.cases().get(0).sourceRevision().length());
        assertEquals(64, result.cases().get(0).flowRevision().length());
        assertFalse(Files.exists(packageRoot.resolve("testcase")), "Resolution must not write or snapshot the Definition");
        assertArrayEquals(beforeFlow, Files.readAllBytes(flowPath("payment.transfer.v1")), "Resolution must not mutate Flow source");
    }

    @Test void resolvesInlineRecordsInSourceOrderWithTypedProjectionAndTags() throws Exception {
        Path example = repositoryRoot().resolve("docs/adr/examples/att-testcase-v1/inline-multi.yaml");
        String yaml = new String(Files.readAllBytes(example), java.nio.charset.StandardCharsets.UTF_8);
        TestCaseResolutionResult result = resolver.resolve(yaml, "testcase/regression.yaml", "payments",
                packageRoot, templatesRoot, TestCaseDefinitionResolver.Options.defaults());

        assertEquals(2, result.resolvedCount());
        assertEquals(Arrays.asList("fpp.transfer.regression.minimum", "fpp.transfer.regression.zero"),
                Arrays.asList(result.cases().get(0).qualifiedCaseId(), result.cases().get(1).qualifiedCaseId()));
        ResolvedTestCase minimum = result.cases().get(0);
        assertEquals("source-min", minimum.sourceRecordId());
        assertEquals(Integer.valueOf(0), minimum.recordIndex());
        assertEquals(1, minimum.inputs().get("amount"));
        assertEquals("A001", minimum.inputs().get("debitAccount"));
        assertEquals("standard", minimum.inputs().get("mode"));
        assertTrue(minimum.inputs().containsKey("optionalValue"));
        assertNull(minimum.inputs().get("optionalValue"));
        assertEquals(Arrays.asList("fpp", "regression", "smoke"), minimum.tags());
        assertEquals("minimum", minimum.data().get("caseKey"));
        assertEquals("zero", result.cases().get(1).caseId());
    }

    @Test void preservesExtensionFieldsAndTypedSourceProvenance() throws Exception {
        String yaml = "schemaVersion: att-testcase/v1\nid: fpp.extensions\nflow: payment.transfer.v1\n"
                + "x-owner: {team: qa}\n"
                + "report: {format: xlsx, x-footer: {team: qa}}\n"
                + "dataSource:\n  type: inline\n  x-reader: {mode: strict}\n  records:\n"
                + "    - recordId: row-1\n      data: {amount: 10}\n      x-origin: {import: fixture}\n";
        TestCaseResolutionResult result = resolve(yaml);

        Map<?,?> definitionMap = result.definition().toMap();
        assertEquals("qa", ((Map<?,?>) definitionMap.get("x-owner")).get("team"));
        Map<?,?> report = (Map<?,?>) definitionMap.get("report");
        assertEquals("xlsx", report.get("format"));
        assertEquals("qa", ((Map<?,?>) report.get("x-footer")).get("team"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map) report).put("format", "mutated"));
        Map<?,?> dataSource = (Map<?,?>) definitionMap.get("dataSource");
        assertEquals("strict", ((Map<?,?>) dataSource.get("x-reader")).get("mode"));
        Map<?,?> sourceRecord = (Map<?,?>) ((java.util.List<?>) dataSource.get("records")).get(0);
        assertEquals("fixture", ((Map<?,?>) sourceRecord.get("x-origin")).get("import"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<Object,Object>) definitionMap.get("x-owner")).put("team", "mutated"));

        Map<?,?> normalized = result.cases().get(0).toMap();
        Map<?,?> provenance = (Map<?,?>) normalized.get("provenance");
        Map<?,?> dataSourceProvenance = (Map<?,?>) provenance.get("dataSource");
        assertEquals("inline", dataSourceProvenance.get("sourceType"));
        assertEquals("testcase/test.yaml", dataSourceProvenance.get("sourceReference"));
        assertEquals("row-1", dataSourceProvenance.get("recordId"));
        assertEquals(Integer.valueOf(0), dataSourceProvenance.get("recordIndex"));

        Map<String,Object> excelMetadata = new LinkedHashMap<String,Object>();
        excelMetadata.put("columns", Collections.singletonMap("amount", Integer.valueOf(2)));
        CaseSourceProvenance excel = new CaseSourceProvenance("excel", "data/cases.xlsx", "Cases",
                Integer.valueOf(17), Integer.valueOf(0), "row-1", excelMetadata);
        Map<?,?> excelMap = excel.toMap();
        assertEquals("data/cases.xlsx", excelMap.get("sourceReference"));
        assertEquals("Cases", excelMap.get("sheet"));
        assertEquals(Integer.valueOf(17), excelMap.get("row"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map) excel.metadata()).put("mutated", Boolean.TRUE));
    }

    @Test void rejectsCaseFoldedDefinitionIdCollisionsAcrossThePackage() throws Exception {
        Path sibling = packageRoot.resolve("other/testcase.yaml");
        Files.createDirectories(sibling.getParent());
        Files.write(sibling, ("schemaVersion: att-testcase/v1\nid: FPP.INLINE\n"
                + "flow: payment.transfer.v1\ninputs: {}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String current = "schemaVersion: att-testcase/v1\nid: fpp.inline\n"
                + "flow: payment.transfer.v1\ninputs: {}\n";

        DiagnosticException failure = assertThrows(DiagnosticException.class, () -> resolve(current));

        assertEquals(DiagnosticCodes.TESTCASE_INVALID, failure.code());
        assertTrue(failure.detail().contains("FPP.INLINE"));
        assertTrue(failure.detail().contains("other/testcase.yaml"));
    }

    @Test void cancellationDuringFlowTreeScanStopsSingleCaseResolution() throws Exception {
        long packageEntries;
        try (Stream<Path> paths = Files.walk(packageRoot)) { packageEntries = paths.count(); }
        AtomicInteger checks = new AtomicInteger();
        int cancelAfterChecks = (int) packageEntries + 1; // initial check plus the package identity scan
        TestCaseDefinitionResolver.Options options = new TestCaseDefinitionResolver.Options(10, 10, null,
                () -> checks.incrementAndGet() > cancelAfterChecks);
        String single = "schemaVersion: att-testcase/v1\nid: fpp.cancel\n"
                + "flow: payment.transfer.v1\ninputs: {}\n";

        assertThrows(CancellationException.class, () -> resolver.resolve(single, "testcase/cancel.yaml", "payments",
                packageRoot, templatesRoot, options));
        assertEquals(cancelAfterChecks + 1, checks.get());
    }

    @Test void invalidYamlValuesAndFlowSchemaKeepStructuredDiagnostics() throws Exception {
        String unsupportedYamlValue = "schemaVersion: att-testcase/v1\nid: fpp.date\n"
                + "flow: payment.transfer.v1\ninputs: {created: 2026-11-20}\n";
        DiagnosticException valueFailure = assertThrows(DiagnosticException.class, () -> resolve(unsupportedYamlValue));
        assertEquals(DiagnosticCodes.TESTCASE_INVALID, valueFailure.code());
        assertTrue(valueFailure.detail().contains("created"));

        Files.write(flowPath("payment.transfer.v1"), ("schemaVersion: att-flow/v3.6\n"
                + "id: payment.transfer.v1\nname: Broken\ndescription: Invalid actions\nactions: []\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DiagnosticException flowFailure = assertThrows(DiagnosticException.class, () -> resolve(
                "schemaVersion: att-testcase/v1\nid: fpp.badFlow\nflow: payment.transfer.v1\ninputs: {}\n"));
        assertEquals(DiagnosticCodes.TEMPLATE_INVALID, flowFailure.code());
        assertNotNull(flowFailure.field());
        assertFalse(flowFailure.schemaViolations().isEmpty());
    }

    @Test void rejectsSourceAndResolvedIdsThatCollideUnderAsciiCaseFold() throws Exception {
        String duplicateSource = inline("dataSource:\n  type: inline\n  records:\n"
                + "    - {recordId: CaseA, data: {key: One}}\n"
                + "    - {recordId: casea, data: {key: Two}}\n");
        DiagnosticException sourceFailure = assertThrows(DiagnosticException.class, () -> resolve(duplicateSource));
        assertEquals(DiagnosticCodes.TESTCASE_INVALID, sourceFailure.code());
        assertTrue(sourceFailure.detail().toLowerCase(java.util.Locale.ROOT).contains("record ids"));

        String duplicateResolved = inline("caseIdExpression: \"${DATA.key}\"\ndataSource:\n  type: inline\n  records:\n"
                + "    - {recordId: rowA, data: {key: Same}}\n"
                + "    - {recordId: rowB, data: {key: same}}\n");
        DiagnosticException caseFailure = assertThrows(DiagnosticException.class, () -> resolve(duplicateResolved));
        assertTrue(caseFailure.detail().toLowerCase(java.util.Locale.ROOT).contains("resolved case ids"));
    }

    @Test void rejectsMissingMappingsInvalidCaseIdsAndUnsupportedRuntimeExpressions() throws Exception {
        String missingMapping = inline("inputs:\n  payload:\n    from: DATA.missing\ndataSource:\n  type: inline\n  records:\n"
                + "    - {recordId: row1, data: {key: Case1}}\n");
        assertTrue(assertThrows(DiagnosticException.class, () -> resolve(missingMapping)).detail().contains("mapped Data Source key"));

        String missingCaseKey = inline("caseIdExpression: \"${DATA.key}\"\ndataSource:\n  type: inline\n  records:\n"
                + "    - {recordId: row1, data: {other: value}}\n");
        assertTrue(assertThrows(DiagnosticException.class, () -> resolve(missingCaseKey)).detail().contains("Case ID source key"));

        String nonStringCaseKey = inline("caseIdExpression: \"${DATA.key}\"\ndataSource:\n  type: inline\n  records:\n"
                + "    - {recordId: row1, data: {key: 42}}\n");
        assertTrue(assertThrows(DiagnosticException.class, () -> resolve(nonStringCaseKey)).detail().contains("must be a string"));

        String nestedExpression = inline("caseIdExpression: \"${DATA['key']}\"\ndataSource:\n  type: inline\n  records:\n"
                + "    - {recordId: row1, data: {key: Case1}}\n");
        assertEquals(DiagnosticCodes.TESTCASE_INVALID,
                assertThrows(DiagnosticException.class, () -> resolve(nestedExpression)).code());
    }

    @Test void supportsExplicitEmptySelectionAndBoundedSourceOrderPreview() throws Exception {
        String source = inline("dataSource:\n  type: inline\n  records:\n"
                + "    - {recordId: first, data: {key: One}}\n"
                + "    - {recordId: second, data: {key: Two}}\n");
        TestCaseDefinitionResolver.Options preview = new TestCaseDefinitionResolver.Options(10, 1, null, null);
        TestCaseResolutionResult truncated = resolver.resolve(source, "testcase/multi.yaml", "payments",
                packageRoot, templatesRoot, preview);
        assertEquals(2, truncated.resolvedCount());
        assertEquals(2, truncated.selectedCount());
        assertTrue(truncated.truncated());
        assertEquals("fpp.inline.first", truncated.cases().get(0).qualifiedCaseId());

        TestCaseDefinitionResolver.Options empty = new TestCaseDefinitionResolver.Options(10, 10,
                Collections.<String>emptySet(), null);
        TestCaseResolutionResult none = resolver.resolve(source, "testcase/multi.yaml", "payments",
                packageRoot, templatesRoot, empty);
        assertTrue(none.emptySelection());
        assertEquals(0, none.cases().size());
        assertEquals(DiagnosticCodes.SELECTION_EMPTY, none.diagnostics().get(0).code());
        assertThrows(DiagnosticException.class, () -> resolver.resolve(source, "testcase/multi.yaml", "payments",
                packageRoot, templatesRoot, new TestCaseDefinitionResolver.Options(10, 10,
                        Collections.singleton("fpp.inline.not-found"), null)));
    }

    @Test void enforcesRecordAndCancellationBounds() throws Exception {
        String source = inline("dataSource:\n  type: inline\n  records:\n"
                + "    - {recordId: first, data: {key: One}}\n"
                + "    - {recordId: second, data: {key: Two}}\n");
        DiagnosticException bound = assertThrows(DiagnosticException.class, () -> resolver.resolve(source,
                "testcase/multi.yaml", "payments", packageRoot, templatesRoot,
                new TestCaseDefinitionResolver.Options(1, 1, null, null)));
        assertTrue(bound.detail().contains("record count"));
        AtomicInteger checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> resolver.resolve(source, "testcase/multi.yaml", "payments",
                packageRoot, templatesRoot, new TestCaseDefinitionResolver.Options(10, 10, null,
                        () -> checks.incrementAndGet() > 1)));
    }

    @Test void rejectsOversizedUtf8DefinitionBeforeMaterializingSourceBytes() throws Exception {
        StringBuilder nonAscii = new StringBuilder(TestCaseDefinitionResolver.MAX_SOURCE_BYTES / 2 + 4);
        for (int index = 0; index < TestCaseDefinitionResolver.MAX_SOURCE_BYTES / 2 + 4; index++) nonAscii.append('\u00e9');
        String source = "schemaVersion: att-testcase/v1\nid: fpp.large\nflow: payment.transfer.v1\n"
                + "inputs: {}\nx-padding: \"" + nonAscii + "\"\n";

        assertTrue(source.length() < TestCaseDefinitionResolver.MAX_SOURCE_BYTES,
                "UTF-16 character count alone is below the byte limit");
        assertTrue(TestCaseDefinitionResolver.utf8ByteLengthAtMost(source,
                TestCaseDefinitionResolver.MAX_SOURCE_BYTES) > TestCaseDefinitionResolver.MAX_SOURCE_BYTES);
        DiagnosticException failure = assertThrows(DiagnosticException.class, () -> resolver.resolve(source,
                "testcase/large.yaml", "payments", packageRoot, templatesRoot,
                TestCaseDefinitionResolver.Options.defaults()));
        assertEquals(DiagnosticCodes.TESTCASE_INVALID, failure.code());
        assertTrue(failure.detail().contains("size limit"));
    }

    @Test void boundsSelectionCountBeforeCopyAndRejectsOverlongQualifiedIds() {
        java.util.Set<String> oversized = new java.util.AbstractSet<String>() {
            @Override public int size() { return TestCaseDefinitionResolver.MAX_SELECTED_CASE_IDS + 1; }
            @Override public java.util.Iterator<String> iterator() {
                throw new AssertionError("selection must be size-checked before it is copied");
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new TestCaseDefinitionResolver.Options(
                10, 10, oversized, null));

        String tooLong = new String(new char[TestCaseDefinitionResolver.MAX_QUALIFIED_CASE_ID_LENGTH + 1]).replace('\0', 'a');
        assertThrows(IllegalArgumentException.class, () -> new TestCaseDefinitionResolver.Options(
                10, 10, Collections.singleton(tooLong), null));
        String atLimit = new String(new char[TestCaseDefinitionResolver.MAX_QUALIFIED_CASE_ID_LENGTH]).replace('\0', 'a');
        assertEquals(TestCaseDefinitionResolver.MAX_QUALIFIED_CASE_ID_LENGTH,
                new TestCaseDefinitionResolver.Options(10, 10, Collections.singleton(atLimit), null)
                        .selectedQualifiedCaseIds().iterator().next().length());
    }

    @Test void capturesStaticNestedFlowDependenciesAndRejectsMissingFlow() throws Exception {
        writeFlow("payment.transfer.v2", "  nested:\n    type: flow\n    use: shared.audit.v1\n");
        writeFlow("shared.audit.v1", "");
        String source = "schemaVersion: att-testcase/v1\nid: fpp.nested\nflow: payment.transfer.v2\ninputs: {amount: 3}\n";
        TestCaseResolutionResult result = resolver.resolve(source, "testcase/nested.yaml", "payments",
                packageRoot, templatesRoot, TestCaseDefinitionResolver.Options.defaults());
        assertEquals(Arrays.asList("payment.transfer.v2", "shared.audit.v1"),
                Arrays.asList(result.cases().get(0).dependencies().get(0).id(), result.cases().get(0).dependencies().get(1).id()));

        String missing = source.replace("payment.transfer.v2", "missing.transfer.v1");
        assertThrows(DiagnosticException.class, () -> resolve(missing));
    }

    private TestCaseResolutionResult resolve(String source) throws Exception {
        return resolver.resolve(source, "testcase/test.yaml", "payments", packageRoot,
                templatesRoot, TestCaseDefinitionResolver.Options.defaults());
    }

    private String inline(String sourceTail) {
        return "schemaVersion: att-testcase/v1\n"
                + "id: fpp.inline\nflow: payment.transfer.v1\n"
                + sourceTail;
    }

    private void writeFlow(String id, String extraActions) throws Exception {
        Path path = flowPath(id);
        Files.createDirectories(path.getParent());
        String flow = "schemaVersion: att-flow/v3.6\n"
                + "id: " + id + "\nname: " + id + "\ndescription: Resolver fixture\nactions:\n"
                + "  record:\n    type: log\n    message: Resolution must not execute this action\n"
                + extraActions;
        Files.write(path, flow.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test void rejectsReservedWindowsNamesWithoutCaseSensitivity() throws Exception {
        String lowerCaseDefinition = "schemaVersion: att-testcase/v1\nid: con.fixture\nflow: payment.transfer.v1\ninputs: {}\n";
        assertEquals(DiagnosticCodes.TESTCASE_INVALID,
                assertThrows(DiagnosticException.class, () -> resolve(lowerCaseDefinition)).code());
        String mixedCaseCaseId = inline("dataSource:\n  type: inline\n  records:\n"
                + "    - {recordId: Com1, data: {key: Case1}}\n");
        assertEquals(DiagnosticCodes.TESTCASE_INVALID,
                assertThrows(DiagnosticException.class, () -> resolve(mixedCaseCaseId)).code());
    }

    @Test void capturedFlowSourcesRemainTheValidatedAndFingerprintedRevisionAfterDiskMutation() throws Exception {
        Path originalPath = flowPath("payment.transfer.v1").toRealPath().toAbsolutePath().normalize();
        byte[] captured = Files.readAllBytes(originalPath);
        String expectedRevision = sha256(captured);
        Map<Path,byte[]> snapshots = Collections.singletonMap(originalPath, captured);
        String mutated = new String(captured, java.nio.charset.StandardCharsets.UTF_8).replace("name: payment.transfer.v1", "name: mutated.transfer.v1");
        Files.write(originalPath, mutated.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        att.flow.FlowRegistry registry = new att.flow.FlowRegistry(packageRoot, templatesRoot, false, snapshots,
                Collections.<Path>emptySet());
        assertEquals("payment.transfer.v1", registry.get("payment.transfer.v1").name());
        assertArrayEquals(captured, registry.descriptorBytes("payment.transfer.v1"));
        assertEquals(expectedRevision, sha256(registry.descriptorBytes("payment.transfer.v1")));
    }

    @Test void capturedFlowProvenanceSurvivesRemovalOfTheLiveDescriptorPath() throws Exception {
        Path descriptor = flowPath("payment.transfer.v1").toRealPath().toAbsolutePath().normalize();
        byte[] capturedBytes = Files.readAllBytes(descriptor);
        String expectedRevision = sha256(capturedBytes);
        String expectedLogicalName = new att.resource.PackageResourceResolver(packageRoot)
                .fromInternalPath(descriptor, att.resource.PackageResourceResolver.Kind.FILE).logicalName();
        TestCaseDefinitionResolver.FlowTreeSnapshot snapshot = resolver.preflightFlowTree(
                templatesRoot.resolve("flows"), new att.resource.PackageResourceResolver(packageRoot),
                "testcase/test.yaml");

        Files.delete(descriptor);
        java.util.List<ResolvedTestCase.FlowDependency> dependencies = resolver.resolveCapturedFlows(
                "payment.transfer.v1", packageRoot, templatesRoot, "testcase/test.yaml", snapshot);

        assertEquals(1, dependencies.size());
        assertEquals(expectedLogicalName, dependencies.get(0).sourceReference());
        assertEquals(expectedRevision, dependencies.get(0).revision());
    }

    @Test void snapshotFlowRegistryRequiresTemplateDescriptorMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new att.flow.FlowRegistry(packageRoot,
                templatesRoot, false, Collections.<Path,byte[]>emptyMap(), null));
    }

    @Test void rejectsAFlowDirectoryThatAlsoContainsATemplateDescriptorInSnapshotMode() throws Exception {
        Files.write(flowPath("payment.transfer.v1").getParent().resolve("template.yaml"),
                "schemaVersion: att-template/v3.6\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        DiagnosticException failure = assertThrows(DiagnosticException.class,
                () -> resolve("schemaVersion: att-testcase/v1\nid: fpp.flowConflict\n"
                        + "flow: payment.transfer.v1\ninputs: {}\n"));

        assertEquals(DiagnosticCodes.TEMPLATE_INVALID, failure.code());
        assertEquals("flow", failure.field());
        assertTrue(failure.summary().contains("both template.yaml and flow.yaml"));
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private Path flowPath(String id) {
        return templatesRoot.resolve("flows").resolve(id.replace('.', '/')).resolve("flow.yaml");
    }

    private static Path repositoryRoot() {
        Path directory = Paths.get("").toAbsolutePath().normalize();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("schemas/proposed/att-testcase-v1.schema.json"))) return directory;
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not locate repository root for proposed Test Case schema");
    }
}
