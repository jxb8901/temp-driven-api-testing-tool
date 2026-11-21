package att.testcase;

import att.validation.DiagnosticCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class TestCaseSuiteResolverTest {
    @TempDir Path temp;
    private Path packageRoot;
    private Path templatesRoot;
    private TestCaseSuiteResolver resolver;

    @BeforeEach void setUp() throws Exception {
        packageRoot = Files.createDirectories(temp.resolve("package"));
        templatesRoot = Files.createDirectories(packageRoot.resolve("templates"));
        resolver = new TestCaseSuiteResolver(repositoryRoot().resolve("schemas/att-testcase-v1.schema.json"));
        writeFlow("payment.alpha.v1");
        writeFlow("payment.zeta.v1");
        writeDefinition("fpp.alpha", "payment.alpha.v1",
                "- {recordId: first, tags: [smoke], data: {caseId: first, amount: 7}}\n"
              + "- {recordId: second, tags: [regression], data: {caseId: second, amount: 9}}\n");
        writeDefinition("fpp.zeta", "payment.zeta.v1",
                "- {recordId: third, tags: [smoke], data: {caseId: third, amount: 11}}\n");
    }

    @Test void allAndCrossDefinitionSelectionsHaveStableQualifiedOrder() throws Exception {
        TestCaseSuiteResolutionResult all = resolver.resolve(packageRoot, "payments", templatesRoot,
                Paths.get("testcase"), Arrays.asList("fpp.zeta", "fpp.alpha"), null,
                Collections.<String>emptyList(), Collections.<String>emptyList(), true, null);
        assertEquals(3, all.resolvedCount());
        assertEquals(Arrays.asList("fpp.alpha.first", "fpp.alpha.second", "fpp.zeta.third"),
                qualifiedIds(all));

        TestCaseSuiteResolutionResult selected = resolver.resolve(packageRoot, "payments", templatesRoot,
                Paths.get("testcase"), Arrays.asList("fpp.zeta", "fpp.alpha"),
                Arrays.asList("fpp.zeta.third", "fpp.alpha.first"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), false, null);
        assertEquals(Arrays.asList("fpp.alpha.first", "fpp.zeta.third"), qualifiedIds(selected));
        assertEquals("payment.alpha.v1", selected.cases().get(0).flowId());
        assertEquals("payment.zeta.v1", selected.cases().get(1).flowId());
        assertEquals(2, selected.definitions().size());
    }

    @Test void tagFiltersUseAnyMatchAndExcludedTagsWin() throws Exception {
        TestCaseSuiteResolutionResult smoke = resolver.resolve(packageRoot, "payments", templatesRoot,
                Paths.get("testcase"), Arrays.asList("fpp.alpha", "fpp.zeta"), null,
                Collections.singletonList("smoke"), Collections.<String>emptyList(), false, null);
        assertEquals(Arrays.asList("fpp.alpha.first", "fpp.zeta.third"), qualifiedIds(smoke));

        TestCaseSuiteResolutionResult excluded = resolver.resolve(packageRoot, "payments", templatesRoot,
                Paths.get("testcase"), Arrays.asList("fpp.alpha", "fpp.zeta"), null,
                Collections.<String>emptyList(), Collections.singletonList("smoke"), true, null);
        assertEquals(Collections.singletonList("fpp.alpha.second"), qualifiedIds(excluded));
    }

    @Test void duplicatesUnknownIdsAndZeroMatchesAreExplicit() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(packageRoot, "payments", templatesRoot,
                Paths.get("testcase"), Arrays.asList("fpp.alpha", "fpp.alpha"), null,
                Collections.<String>emptyList(), Collections.<String>emptyList(), true, null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(packageRoot, "payments", templatesRoot,
                Paths.get("testcase"), Collections.singletonList("fpp.alpha"),
                Arrays.asList("fpp.alpha.first", "fpp.alpha.first"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), false, null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(packageRoot, "payments", templatesRoot,
                Paths.get("testcase"), Collections.singletonList("fpp.alpha"),
                Collections.singletonList("fpp.alpha.missing"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), false, null));

        TestCaseSuiteResolutionResult none = resolver.resolve(packageRoot, "payments", templatesRoot,
                Paths.get("testcase"), Collections.singletonList("fpp.alpha"), null,
                Collections.singletonList("not-present"), Collections.<String>emptyList(), false, null);
        assertTrue(none.emptySelection());
        assertEquals(DiagnosticCodes.SELECTION_EMPTY, none.diagnostics().get(0).code());
    }

    @Test void selectorsUseTheCompleteBoundedPlanBeyondThePresentationPreview() throws Exception {
        StringBuilder records = new StringBuilder();
        for (int i = 0; i <= 1000; i++) {
            String id = "case" + i;
            String tag = i == 1000 ? "smoke" : "bulk";
            records.append("- {recordId: ").append(id).append(", tags: [").append(tag)
                    .append("], data: {caseId: ").append(id).append(", amount: ").append(i + 1).append("}}\n");
        }
        writeDefinition("fpp.large", "payment.alpha.v1", records.toString());

        TestCaseSuiteResolutionResult selectedById = resolver.resolve(packageRoot, "payments", templatesRoot,
                Paths.get("testcase"), Collections.singletonList("fpp.large"),
                Collections.singletonList("fpp.large.case1000"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), false, null);
        assertEquals(1001, selectedById.resolvedCount());
        assertEquals(Collections.singletonList("fpp.large.case1000"), qualifiedIds(selectedById));
        assertFalse(selectedById.truncated());

        TestCaseSuiteResolutionResult selectedByTag = resolver.resolve(packageRoot, "payments", templatesRoot,
                Paths.get("testcase"), Collections.singletonList("fpp.large"), null,
                Collections.singletonList("smoke"), Collections.<String>emptyList(), false, null);
        assertEquals(1001, selectedByTag.resolvedCount());
        assertEquals(Collections.singletonList("fpp.large.case1000"), qualifiedIds(selectedByTag));
        assertFalse(selectedByTag.truncated());
    }

    @Test void nativeSuiteTagsMatchTheV2SchemaLimit() {
        String tooLongTag = String.join("", Collections.nCopies(129, "x"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(packageRoot, "payments", templatesRoot,
                Paths.get("testcase"), Collections.singletonList("fpp.alpha"), null,
                Collections.singletonList(tooLongTag), Collections.<String>emptyList(), false, null));
    }

    private static java.util.List<String> qualifiedIds(TestCaseSuiteResolutionResult result) {
        java.util.List<String> values = new java.util.ArrayList<String>();
        for (ResolvedTestCase testCase : result.cases()) values.add(testCase.qualifiedCaseId());
        return values;
    }

    private void writeDefinition(String id, String flow, String records) throws Exception {
        Path path = packageRoot.resolve("testcase").resolve(id.replace('.', '/')).resolve("testcase.yaml");
        Files.createDirectories(path.getParent());
        String yaml = "schemaVersion: att-testcase/v1\nid: " + id + "\nflow: " + flow + "\n"
                + "caseIdExpression: \"${DATA.caseId}\"\n"
                + "dataSource:\n  type: inline\n  records:\n" + indent(records, "    ");
        Files.write(path, yaml.getBytes(StandardCharsets.UTF_8));
    }

    private void writeFlow(String id) throws Exception {
        Path path = templatesRoot.resolve("flows").resolve(id.replace('.', '/')).resolve("flow.yaml");
        Files.createDirectories(path.getParent());
        Files.write(path, ("schemaVersion: att-flow/v3.6\nid: " + id + "\nname: " + id
                + "\ndescription: Suite selection fixture\nactions:\n"
                + "  record:\n    type: log\n    message: suite\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String indent(String source, String prefix) {
        StringBuilder result = new StringBuilder();
        for (String line : source.split("\\n")) if (!line.isEmpty()) result.append(prefix).append(line).append('\n');
        return result.toString();
    }

    private static Path repositoryRoot() {
        Path directory = Paths.get("").toAbsolutePath().normalize();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("schemas/att-testcase-v1.schema.json"))) return directory;
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not locate repository root for active Test Case schema");
    }
}
