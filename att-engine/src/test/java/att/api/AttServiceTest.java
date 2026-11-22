package att.api;

import att.report.ReportRegenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AttServiceTest {
    @TempDir Path temp;

    @Test void nativeSuiteApiUsesTheSchemaTagLengthLimit() {
        String tooLongTag=String.join("",Collections.nCopies(129,"x"));
        assertThrows(IllegalArgumentException.class,()->new NativeCaseSuiteRunRequest(temp,null,null,null,null,
                "payments",Collections.singletonList("fpp.transfer"),Collections.<String>emptyList(),
                Collections.singletonList(tooLongTag),Collections.<String>emptyList(),false,false,false,null));
    }

    @Test void nativeSuiteApiQualifiedSelectorLimitMatchesExecutionV2() {
        List<String> atLimit=new java.util.ArrayList<String>();
        for(int index=0;index<att.testcase.TestCaseSuiteResolver.MAX_QUALIFIED_CASE_IDS;index++)
            atLimit.add("fpp.transfer.case"+index);
        NativeCaseSuiteRunRequest request=new NativeCaseSuiteRunRequest(temp,null,null,null,null,
                "payments",Collections.singletonList("fpp.transfer"),atLimit,Collections.<String>emptyList(),
                Collections.<String>emptyList(),false,false,false,null);
        assertEquals(att.testcase.TestCaseSuiteResolver.MAX_QUALIFIED_CASE_IDS,request.qualifiedCaseIds().size());

        atLimit.add("fpp.transfer.over-limit");
        assertThrows(IllegalArgumentException.class,()->new NativeCaseSuiteRunRequest(temp,null,null,null,null,
                "payments",Collections.singletonList("fpp.transfer"),atLimit,Collections.<String>emptyList(),
                Collections.<String>emptyList(),false,false,false,null));
    }

    @Test void validationIsInvokableThroughTypedRequestWithoutCliArgumentsOrConsoleOutput() throws Exception {
        Path root=temp.resolve("package"); Files.createDirectories(root.resolve("config")); Files.createDirectories(root.resolve("templates"));
        Files.createDirectories(root.resolve("testcase")); Files.createDirectories(root.resolve("tools"));
        att.TestSchemas.install(root);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.11\n"
                + "outputDirectory: output\nenvironment: SIT\ntimeoutMs: 10000\n"
                + "templates: {root: templates}\ntestcase: {root: testcase}\n").getBytes("UTF-8"));
        ByteArrayOutputStream out=new ByteArrayOutputStream(), err=new ByteArrayOutputStream();
        PrintStream oldOut=System.out, oldErr=System.err;
        ValidateResult result;
        try {
            System.setOut(new PrintStream(out)); System.setErr(new PrintStream(err));
            result=new DefaultAttService().validate(new ValidateRequest(root,Paths.get("config/config.yaml"),null,
                    Collections.<Path>emptyList(),null,Collections.<String>emptySet(),Collections.<String>emptySet(),
                    Collections.<String>emptySet(),false,"selected"));
        } finally { System.setOut(oldOut); System.setErr(oldErr); }
        assertNotNull(result.status());
        assertTrue(result.toMap().containsKey("summary"));
        assertEquals("INVALID",result.status());
        assertEquals(1,result.diagnostics().size());
        assertEquals("",out.toString("UTF-8"));
        assertEquals("",err.toString("UTF-8"));
    }

    @Test void nativeRunExecutesOnlySelectedResolvedCasesAndKeepsNativeIdentity() throws Exception {
        Path root = temp.resolve("native-run-package");
        Path config = root.resolve("config/config.yaml");
        Files.createDirectories(config.getParent());
        Files.createDirectories(root.resolve("testcase/fpp/transfer"));
        Files.createDirectories(root.resolve("templates/flows/payment/transfer/v1"));
        att.TestSchemas.install(root);
        Files.write(config, ("schemaVersion: att-config/v2.12\noutputDirectory: output\n"
                + "environment: SIT\ntemplates: {root: templates}\ntestcase: {root: testcase}\n")
                .getBytes(StandardCharsets.UTF_8));
        Path definition = root.resolve("testcase/fpp/transfer/testcase.yaml");
        Files.write(definition, ("schemaVersion: att-testcase/v1\nid: fpp.transfer\n"
                + "flow: payment.transfer.v1\n"
                + "dataSource:\n  type: inline\n  records:\n"
                + "    - {recordId: first, data: {amount: 7}}\n"
                + "    - {recordId: second, data: {amount: 13}}\n")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("templates/flows/payment/transfer/v1/flow.yaml"),
                ("schemaVersion: att-flow/v3.6\nid: payment.transfer.v1\nname: Transfer\n"
                        + "description: Native run fixture\nactions:\n"
                        + "  show: {type: log, message: 'amount=${EXEC.INPUT.amount}'}\n")
                        .getBytes(StandardCharsets.UTF_8));

        RunResult result = new DefaultAttService().runNativeCases(new NativeCaseRunRequest(root,
                Paths.get("config/config.yaml"), "SIT", null, "native-run-selection", "payments",
                "fpp.transfer", Collections.singletonList("second"), false, false, null));

        assertEquals("PASS", result.status());
        assertEquals(1L, result.summary().get("total"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> cases = (List<Map<String,Object>>) result.summary().get("cases");
        assertEquals(1, cases.size());
        Map<String,Object> row = cases.get(0);
        assertEquals("fpp.transfer", row.get("definitionId"));
        assertEquals("second", row.get("localCaseId"));
        assertEquals("fpp.transfer.second", row.get("qualifiedCaseId"));
        assertTrue(String.valueOf(row.get("execId")).startsWith("native-"));
        assertEquals("PASS", row.get("status"));
        Path runDirectory = root.resolve("output/native-run-selection");
        String caseLog = new String(Files.readAllBytes(runDirectory.resolve("executions")
                .resolve(String.valueOf(row.get("execId"))).resolve("case.log")), StandardCharsets.UTF_8);
        assertTrue(caseLog.contains("amount=13"), caseLog);
        assertFalse(caseLog.contains("amount=7"), caseLog);
        String caseTree = new String(Files.readAllBytes(runDirectory.resolve("executions")
                .resolve(String.valueOf(row.get("execId"))).resolve("case.yaml")), StandardCharsets.UTF_8);
        assertTrue(caseTree.contains("definitionId: fpp.transfer"), caseTree);
        assertTrue(caseTree.contains("qualifiedCaseId: fpp.transfer.second"), caseTree);
        assertFalse(caseTree.contains("workbookId:"), caseTree);
        assertFalse(caseTree.contains("groupId:"), caseTree);
        assertTrue(Files.isRegularFile(runDirectory.resolve("run.yaml")));
        assertTrue(Files.isRegularFile(runDirectory.resolve("ci/summary.json")));
        assertTrue(Files.isRegularFile(runDirectory.resolve("ci/junit.xml")));
        assertEquals(runDirectory.resolve("configuration.json").toRealPath().toString(),
                result.paths().get("configuration"));
    }

    @Test void implicitSuiteRunsEveryResolvedCaseFromOneDefinitionWithoutSuiteFile() throws Exception {
        Path root = nativeSuitePackage("single-suite-package");
        writeNativeDefinition(root, "fpp.transfer", "payment.transfer.v1", ""
                + "- {recordId: first, data: {caseId: first, amount: 7}}\n"
                + "- {recordId: second, data: {caseId: second, amount: 13}}\n"
                + "- {recordId: third, data: {caseId: third, amount: 17}}\n");

        RunResult result = new DefaultAttService().runNativeCaseSuite(new NativeCaseSuiteRunRequest(root,
                Paths.get("config/config.yaml"), "SIT", null, "native-suite-all", "payments",
                Collections.singletonList("fpp.transfer"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), true, false, false, null));

        assertEquals("PASS", result.status());
        assertEquals(3L, result.summary().get("total"));
        assertEquals(3, result.summary().get("resolved"));
        assertEquals(3, result.summary().get("selected"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> cases = (List<Map<String,Object>>) result.summary().get("cases");
        assertEquals(Arrays.asList("fpp.transfer.first", "fpp.transfer.second", "fpp.transfer.third"),
                cases.stream().map(item -> String.valueOf(item.get("qualifiedCaseId"))).collect(java.util.stream.Collectors.toList()));
        assertTrue(Files.isRegularFile(root.resolve("output/native-suite-all/run.yaml")));
        assertFalse(Files.exists(root.resolve("suite.yaml")), "An implicit Suite must not require a suite file");
    }

    @Test void failFastReportsSelectedButUnstartedCasesAsSkippedWithoutExecutionIds() throws Exception {
        Path root = nativeSuitePackage("native-suite-fail-fast-package");
        writeNativeDefinition(root, "fpp.transfer", "payment.transfer.v1", ""
                + "- {recordId: first, data: {caseId: first, amount: 7}}\n"
                + "- {recordId: second, data: {caseId: second, amount: 13}}\n"
                + "- {recordId: third, data: {caseId: third, amount: 17}}\n");
        Files.write(root.resolve("templates/flows/payment/transfer/v1/flow.yaml"),
                ("schemaVersion: att-flow/v3.6\nid: payment.transfer.v1\nname: Transfer\n"
                        + "description: Fail-fast aggregate fixture\nactions:\n"
                        + "  reject: {type: assert, assert: 'false'}\n").getBytes(StandardCharsets.UTF_8));

        List<ExecutionEvent> observed=new ArrayList<ExecutionEvent>();
        RunResult result = new DefaultAttService().runNativeCaseSuite(new NativeCaseSuiteRunRequest(root,
                Paths.get("config/config.yaml"), "SIT", null, "native-suite-fail-fast", "payments",
                Collections.singletonList("fpp.transfer"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), true, false, true, observed::add));

        assertEquals("FAIL", result.status());
        assertEquals(3L, result.summary().get("total"));
        assertEquals(3, result.summary().get("selected"));
        assertEquals(1L, result.summary().get("executionCount"));
        assertEquals(1L, result.summary().get("failed"));
        assertEquals(2L, result.summary().get("skipped"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> cases = (List<Map<String,Object>>) result.summary().get("cases");
        assertEquals(Arrays.asList("FAIL", "SKIPPED", "SKIPPED"), cases.stream()
                .map(item -> String.valueOf(item.get("status"))).collect(java.util.stream.Collectors.toList()));
        assertEquals(Boolean.TRUE, cases.get(0).get("executed"));
        for (int index = 1; index < cases.size(); index++) {
            assertEquals(Boolean.FALSE, cases.get(index).get("executed"));
            assertNull(cases.get(index).get("execId"));
            assertTrue(String.valueOf(cases.get(index).get("notStartedReason"))
                    .contains("Not started because failFast"));
        }

        Path runDirectory = root.resolve("output/native-suite-fail-fast");
        @SuppressWarnings("unchecked") Map<String,Object> manifest = (Map<String,Object>) new org.yaml.snakeyaml.Yaml()
                .load(new String(Files.readAllBytes(runDirectory.resolve("run.yaml")), StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked") Map<String,Object> run = (Map<String,Object>) manifest.get("run");
        assertEquals(3, run.get("selectedCaseCount"));
        assertEquals(1, ((Number) run.get("executionCount")).intValue());
        assertEquals(2, ((Number) run.get("skippedCaseCount")).intValue());
        @SuppressWarnings("unchecked") List<Map<String,Object>> manifestCases=(List<Map<String,Object>>)manifest.get("cases");
        assertEquals(Boolean.TRUE,manifestCases.get(0).get("executed"));
        for(int index=1;index<manifestCases.size();index++) {
            assertEquals(Boolean.FALSE,manifestCases.get(index).get("executed"));
            assertNull(manifestCases.get(index).get("execId"));
            assertEquals("",manifestCases.get(index).get("caseLog"));
        }
        List<String> events = Files.readAllLines(runDirectory.resolve("events.jsonl"), StandardCharsets.UTF_8);
        assertEquals(3, events.size());
        assertEquals(2L, events.stream().filter(line -> line.contains("\"executionStarted\":false"))
                .count());
        assertTrue(events.stream().filter(line -> line.contains("\"executionStarted\":false"))
                .allMatch(line -> line.contains("\"notStartedReason\":\"Not started because failFast")));
        List<ExecutionEvent> notStartedEvents=observed.stream().filter(event->
                event.type()==ExecutionEvent.Type.PROGRESS&&"CASE_FINISHED".equals(event.data().get("event"))
                        &&Boolean.FALSE.equals(event.data().get("executionStarted")))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2,notStartedEvents.size());
        assertTrue(notStartedEvents.stream().allMatch(event->"failFast".equals(event.data().get("notStartedReason"))));

        com.fasterxml.jackson.databind.JsonNode ci = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(Files.readAllBytes(runDirectory.resolve("ci/summary.json")));
        assertEquals(3, ci.path("summary").path("total").asInt());
        assertEquals(1, ci.path("summary").path("executionCount").asInt());
        assertEquals(2, ci.path("summary").path("skipped").asInt());
        assertFalse(ci.path("cases").get(1).path("executed").asBoolean());
        assertTrue(ci.path("cases").get(1).path("execId").isNull());
        String junit = new String(Files.readAllBytes(runDirectory.resolve("ci/junit.xml")), StandardCharsets.UTF_8);
        assertTrue(junit.contains("tests=\"3\""), junit);
        assertTrue(junit.contains("skipped=\"2\""), junit);
        assertTrue(junit.contains("name=\"ATT.EXECUTED\" value=\"false\""), junit);

        Path regenerated=new ReportRegenerator().regenerate(root.resolve("output"),"native-suite-fail-fast");
        String regeneratedHtml=new String(Files.readAllBytes(regenerated),StandardCharsets.UTF_8);
        assertTrue(regeneratedHtml.contains("data-caseid=\"fpp.transfer.second\" data-execid=\"\""),regeneratedHtml);
        assertTrue(regeneratedHtml.contains("data-status=\"SKIPPED\""),regeneratedHtml);
        String regeneratedJunit=new String(Files.readAllBytes(runDirectory.resolve("report/junit.html")),StandardCharsets.UTF_8);
        assertTrue(regeneratedJunit.contains("· Skipped 2"),regeneratedJunit);
        assertTrue(regeneratedJunit.contains("fpp.transfer.third"),regeneratedJunit);
    }

    @Test void nativeSuiteRerunFailedRetriesOnlyExecutedFailuresAndKeepsCountsSeparate() throws Exception {
        Path root = nativeSuitePackage("native-suite-retry-package");
        writeNativeDefinition(root, "fpp.transfer", "payment.transfer.v1",
                "- {recordId: first, data: {caseId: first, amount: 7}}\n"
              + "- {recordId: second, data: {caseId: second, amount: 13}}\n"
              + "- {recordId: third, data: {caseId: third, amount: 17}}\n");
        Files.write(root.resolve("templates/flows/payment/transfer/v1/flow.yaml"),
                String.join("\n", "schemaVersion: att-flow/v3.6", "id: payment.transfer.v1", "name: Transfer",
                        "description: Native Suite retry fixture", "actions:",
                        "  verify: {type: assert, assert: '#{${EXEC.INPUT.amount} <= 7}'}", "")
                        .getBytes(StandardCharsets.UTF_8));
        DefaultAttService service = new DefaultAttService();
        RunResult source = service.runNativeCaseSuite(new NativeCaseSuiteRunRequest(root,
                Paths.get("config/config.yaml"), "SIT", null, "native-retry-source", "payments",
                Collections.singletonList("fpp.transfer"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), true, false, true, null));
        assertEquals("FAIL", source.status());
        assertEquals(3L, source.summary().get("total"));
        assertEquals(2L, source.summary().get("executionCount"));
        assertEquals(1L, source.summary().get("skipped"));

        RunResult dryRun = service.runNativeCaseSuite(new NativeCaseSuiteRunRequest(root,
                Paths.get("config/config.yaml"), "SIT", null, "native-retry-dry-run", "payments",
                Collections.singletonList("fpp.transfer"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), true, true, false, null));
        assertEquals("SKIPPED", dryRun.status());
        RunResult otherPackage = service.runNativeCaseSuite(new NativeCaseSuiteRunRequest(root,
                Paths.get("config/config.yaml"), "SIT", null, "native-retry-other-package", "other-payments",
                Collections.singletonList("fpp.transfer"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), true, false, true, null));
        assertEquals("FAIL", otherPackage.status());
        @SuppressWarnings("unchecked") Map<String,Object> latest = (Map<String,Object>)new org.yaml.snakeyaml.Yaml()
                .load(new String(Files.readAllBytes(root.resolve("output/latest-run.yaml")),StandardCharsets.UTF_8));
        assertEquals("native-retry-other-package", latest.get("runId"),
                "The global latest pointer may advance independently of this Package's retry history");

        NativeCaseSuiteRunRequest retryRequest = new NativeCaseSuiteRunRequest(root,
                Paths.get("config/config.yaml"), "SIT", null, "native-retry-first", "payments",
                Collections.singletonList("fpp.transfer"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), false, true, false, false,
                null, null);
        RunResult retry = service.runNativeCaseSuite(retryRequest);
        assertEquals("FAIL", retry.status());
        assertEquals(1L, retry.summary().get("total"));
        assertEquals(1, ((Number) retry.summary().get("selected")).intValue());
        assertEquals(1L, retry.summary().get("executionCount"));
        assertEquals(0L, retry.summary().get("skipped"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> cases =
                (List<Map<String,Object>>) retry.summary().get("cases");
        assertEquals(Collections.singletonList("fpp.transfer.second"), cases.stream()
                .map(item -> String.valueOf(item.get("qualifiedCaseId")))
                .collect(java.util.stream.Collectors.toList()));

        @SuppressWarnings("unchecked") Map<String,Object> manifest = (Map<String,Object>)
                new org.yaml.snakeyaml.Yaml().load(new String(Files.readAllBytes(
                        root.resolve("output/native-retry-first/run.yaml")), StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked") Map<String,Object> run = (Map<String,Object>) manifest.get("run");
        @SuppressWarnings("unchecked") Map<String,Object> lineage = (Map<String,Object>) run.get("retryLineage");
        assertEquals("native-retry-source", lineage.get("sourceRunId"));
        assertEquals(3, lineage.get("sourceSelectedCaseCount"));
        assertEquals(1, lineage.get("retryAttemptNumber"));
        assertEquals(1, lineage.get("retryCaseCount"));
        assertEquals(1, ((Number) lineage.get("retryExecutionCount")).intValue());
        assertEquals(0, ((Number) lineage.get("retrySkippedCaseCount")).intValue());
        assertEquals(Collections.singletonList("fpp.transfer.second"), lineage.get("failedQualifiedCaseIds"));

        RunResult retryAgain = service.runNativeCaseSuite(new NativeCaseSuiteRunRequest(root,
                Paths.get("config/config.yaml"), "SIT", null, "native-retry-second", "payments",
                Collections.singletonList("fpp.transfer"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), false, true, false, false,
                null, null));
        assertEquals(1L, retryAgain.summary().get("total"));
        @SuppressWarnings("unchecked") Map<String,Object> secondManifest = (Map<String,Object>)
                new org.yaml.snakeyaml.Yaml().load(new String(Files.readAllBytes(
                        root.resolve("output/native-retry-second/run.yaml")), StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked") Map<String,Object> secondRun = (Map<String,Object>) secondManifest.get("run");
        @SuppressWarnings("unchecked") Map<String,Object> secondLineage = (Map<String,Object>) secondRun.get("retryLineage");
        assertEquals("native-retry-first", secondLineage.get("sourceRunId"));
        assertEquals(2, secondLineage.get("retryAttemptNumber"));
        assertEquals(1, secondLineage.get("sourceSelectedCaseCount"));
        assertEquals(Collections.singletonList("fpp.transfer.second"), secondLineage.get("failedQualifiedCaseIds"));
    }

    @Test void nativeSuiteRetryRejectsChangedFlowRevisionAndTamperedLatestManifest() throws Exception {
        Path root = nativeSuitePackage("native-suite-retry-stale-package");
        writeNativeDefinition(root, "fpp.transfer", "payment.transfer.v1",
                "- {recordId: first, data: {caseId: first, amount: 13}}\n");
        Path flow = root.resolve("templates/flows/payment/transfer/v1/flow.yaml");
        byte[] originalFlow = Files.readAllBytes(flow);
        Files.write(flow, String.join("\n", "schemaVersion: att-flow/v3.6", "id: payment.transfer.v1",
                "name: Transfer", "description: Native Suite retry stale fixture", "actions:",
                "  verify: {type: assert, assert: 'false'}", "").getBytes(StandardCharsets.UTF_8));
        DefaultAttService service = new DefaultAttService();
        RunResult source = service.runNativeCaseSuite(new NativeCaseSuiteRunRequest(root,
                Paths.get("config/config.yaml"), "SIT", null, "native-stale-source", "payments",
                Collections.singletonList("fpp.transfer"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), true, false, false, null));
        assertEquals("FAIL", source.status());

        NativeCaseSuiteRunRequest retryRequest = new NativeCaseSuiteRunRequest(root,
                Paths.get("config/config.yaml"), "SIT", null, "native-stale-retry", "payments",
                Collections.singletonList("fpp.transfer"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), false, true, false, false,
                null, null);
        Files.write(flow, originalFlow);
        IllegalArgumentException stale = assertThrows(IllegalArgumentException.class,
                () -> service.runNativeCaseSuite(retryRequest));
        assertTrue(stale.getMessage().contains("source changed"), stale.getMessage());

        Path manifest = root.resolve("output/native-stale-source/run.yaml");
        Files.write(manifest, (new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)
                + "# tampered after completion\n").getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException tampered = assertThrows(IllegalArgumentException.class,
                () -> service.runNativeCaseSuite(retryRequest));
        assertTrue(tampered.getMessage().contains("hash does not match"), tampered.getMessage());
        assertFalse(Files.exists(root.resolve("output/native-stale-retry/run.yaml")));
    }

    @Test void crossDefinitionSelectionRunsCasesFromDistinctFlowsAndKeysAggregateEvidence() throws Exception {
        Path root = nativeSuitePackage("cross-suite-package");
        writeNativeFlow(root, "payment.alpha.v1", "alpha=${EXEC.INPUT.amount}");
        writeNativeFlow(root, "payment.zeta.v1", "zeta=${EXEC.INPUT.amount}");
        writeNativeDefinition(root, "fpp.alpha", "payment.alpha.v1",
                "- {recordId: first, data: {caseId: first, amount: 7}}\n"
              + "- {recordId: spare, data: {caseId: spare, amount: 9}}\n");
        writeNativeDefinition(root, "fpp.zeta", "payment.zeta.v1",
                "- {recordId: third, data: {caseId: third, amount: 11}}\n");

        RunResult result = new DefaultAttService().runNativeCaseSuite(new NativeCaseSuiteRunRequest(root,
                Paths.get("config/config.yaml"), "SIT", null, "native-suite-cross", "payments",
                Arrays.asList("fpp.zeta", "fpp.alpha"), Arrays.asList("fpp.zeta.third", "fpp.alpha.first"),
                Collections.<String>emptyList(), Collections.<String>emptyList(), false, false, false, null));

        assertEquals("PASS", result.status());
        assertEquals(2L, result.summary().get("total"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> cases = (List<Map<String,Object>>) result.summary().get("cases");
        assertEquals(Arrays.asList("fpp.alpha.first", "fpp.zeta.third"),
                cases.stream().map(item -> String.valueOf(item.get("qualifiedCaseId"))).collect(java.util.stream.Collectors.toList()));
        assertEquals(Arrays.asList("payment.alpha.v1", "payment.zeta.v1"),
                cases.stream().map(item -> String.valueOf(item.get("flowId"))).collect(java.util.stream.Collectors.toList()));
        assertEquals("fpp.alpha", cases.get(0).get("definitionId"));
        assertEquals("fpp.zeta", cases.get(1).get("definitionId"));
        Path runDirectory = root.resolve("output/native-suite-cross");
        for (Map<String,Object> item : cases) {
            Path caseLog = runDirectory.resolve("executions").resolve(String.valueOf(item.get("execId"))).resolve("case.log");
            String content = new String(Files.readAllBytes(caseLog), StandardCharsets.UTF_8);
            if ("fpp.alpha".equals(item.get("definitionId"))) assertTrue(content.contains("alpha=7"), content);
            else assertTrue(content.contains("zeta=11"), content);
        }
        String manifest = new String(Files.readAllBytes(runDirectory.resolve("run.yaml")), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("targetType: nativeSuite"), manifest);
        assertTrue(manifest.contains("definitionId: fpp.alpha"), manifest);
        assertTrue(manifest.contains("definitionId: fpp.zeta"), manifest);
        assertTrue(Files.isRegularFile(runDirectory.resolve("ci/summary.json")));
        assertTrue(Files.isRegularFile(runDirectory.resolve("ci/junit.xml")));
    }

    private Path nativeSuitePackage(String name) throws Exception {
        Path root = temp.resolve(name);
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("templates"));
        Files.createDirectories(root.resolve("testcase"));
        att.TestSchemas.install(root);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.12\noutputDirectory: output\n"
                + "environment: SIT\ntemplates: {root: templates}\ntestcase: {root: testcase}\n")
                .getBytes(StandardCharsets.UTF_8));
        writeNativeFlow(root, "payment.transfer.v1", "transfer=${EXEC.INPUT.amount}");
        return root;
    }

    private void writeNativeDefinition(Path root, String id, String flowId, String records) throws Exception {
        Path definition = root.resolve("testcase").resolve(id.replace('.', '/')).resolve("testcase.yaml");
        Files.createDirectories(definition.getParent());
        StringBuilder yaml = new StringBuilder("schemaVersion: att-testcase/v1\nid: ").append(id)
                .append("\nflow: ").append(flowId).append("\ncaseIdExpression: \"${DATA.caseId}\"\n")
                .append("dataSource:\n  type: inline\n  records:\n");
        for (String line : records.split("\\n")) if (!line.isEmpty()) yaml.append("    ").append(line).append('\n');
        Files.write(definition, yaml.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeNativeFlow(Path root, String id, String message) throws Exception {
        Path flow = root.resolve("templates/flows").resolve(id.replace('.', '/')).resolve("flow.yaml");
        Files.createDirectories(flow.getParent());
        Files.write(flow, ("schemaVersion: att-flow/v3.6\nid: " + id + "\nname: " + id
                + "\ndescription: Native suite fixture\nactions:\n"
                + "  show:\n    type: log\n    message: '" + message + "'\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    @Test void validationChecksIdentityFormatSyntaxBeforeAnyRunStarts() throws Exception {
        Path root=temp.resolve("identity-validation-package");
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("templates"));
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("tools"));
        att.TestSchemas.install(root);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.12\n"
                + "templates: {root: templates}\ntestcase: {root: testcase}\n"
                + "execution: {runIdFormat: \"#{seq.next()}\"}\n").getBytes("UTF-8"));
        ValidateResult result=new DefaultAttService().validate(new ValidateRequest(root,Paths.get("config/config.yaml"),null,
                Collections.<Path>emptyList(),null,Collections.<String>emptySet(),Collections.<String>emptySet(),
                Collections.<String>emptySet(),false,"selected"));
        assertEquals("INVALID",result.status());
        assertTrue(result.diagnostics().stream().anyMatch(item -> item.code().equals("ATT-CFG-001")
                && item.message().contains("pure identity-format built-ins")), result.diagnostics().toString());
    }

    @Test void debugResultRetainsTheCompleteTypedDiagnostic() throws Exception {
        Path root=temp.resolve("debug-package"); Files.createDirectories(root.resolve("config")); Files.createDirectories(root.resolve("templates"));
        Files.createDirectories(root.resolve("testcase")); Files.createDirectories(root.resolve("tools"));
        att.TestSchemas.install(root);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.11\n"
                + "outputDirectory: output\nenvironment: SIT\n"
                + "templates: {root: templates}\ntestcase: {root: testcase}\n").getBytes("UTF-8"));
        DebugResult result=new DefaultAttService().debug(new DebugRequest(root,Paths.get("config/config.yaml"),null,
                null,null,"template","MISSING",null,false));
        assertEquals(1,result.diagnostics().size());
        assertNotNull(result.diagnostics().get(0).suggestion());
        assertEquals(result.diagnostics().get(0).toMap(),result.toMap().get("diagnostics") instanceof java.util.List
                ? ((java.util.List<java.util.Map<String,Object>>)result.toMap().get("diagnostics")).get(0) : null);
    }

    @Test void debugProfileWritesStartupAndExecutionPhases() throws Exception {
        Path root=temp.resolve("debug-profile-package");
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("templates/SIMPLE"));
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("tools"));
        att.TestSchemas.install(root);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.11\n"
                + "outputDirectory: output\nenvironment: SIT\n"
                + "templates: {root: templates}\ntestcase: {root: testcase}\n").getBytes("UTF-8"));
        Files.write(root.resolve("templates/SIMPLE/template.yaml"), ("schemaVersion: att-template/v3.4\n"
                + "name: SIMPLE\ndescription: debug profile test\nactions:\n"
                + "  say: {type: log, message: hello}\n").getBytes("UTF-8"));
        Files.write(root.resolve("templates/SIMPLE/debug.yaml"), "schemaVersion: att-debug/v1.2\n".getBytes("UTF-8"));
        long entry=System.nanoTime()-1000000000L;
        DebugStartupMetrics startup=new DebugStartupMetrics(entry,12500000L);
        ExecutionEventListener observer=event -> startup.markFirstConsoleEvent();

        DebugResult result=new DefaultAttService().debug(new DebugRequest(root,Paths.get("config/config.yaml"),null,
                null,"profiled-debug","template","SIMPLE",null,false,observer,"profiled-debug",
                Collections.<String>emptyList(),true,startup));

        Path performance=Paths.get(root.resolve(result.paths().get("performance")).toString());
        assertEquals("PASS",result.status());
        assertTrue(Files.isRegularFile(performance));
        String report=new String(Files.readAllBytes(performance),"UTF-8");
        assertTrue(report.contains("\"timingOrigin\":\"java-main-entry\""),report);
        assertTrue(report.contains("\"cliArgumentParseMs\":"),report);
        assertTrue(report.contains("\"processToFirstConsoleEventMs\":"),report);
        assertTrue(report.contains("\"processToFirstActionMs\":"),report);
        assertTrue(report.contains("\"debugInputLoadMs\":"),report);
    }

    @Test void concurrentLoadRequestsAtomicallyReserveTheSameOuterRunId() throws Exception {
        Path root=temp.resolve("load-collision-package");
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("templates/SIMPLE"));
        Files.createDirectories(root.resolve("load"));
        att.TestSchemas.install(root);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.11\n"
                + "outputDirectory: output\nenvironment: SIT\ntemplates: {root: templates}\n"
                + "testcase: {root: testcase}\n").getBytes("UTF-8"));
        Files.write(root.resolve("templates/SIMPLE/template.yaml"), ("schemaVersion: att-template/v3.4\n"
                + "name: SIMPLE\ndescription: collision regression\nactions:\n"
                + "  show: {type: log, message: load-started}\n").getBytes("UTF-8"));
        Path scenario=root.resolve("load/scenario.yaml");
        Files.write(scenario, ("schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: sample\n    target: {type: template, id: SIMPLE}\n"
                + "    load: {users: 1, duration: 500ms}\n    execution: {thinkTime: 0ms}\n"
                + "evidence: {mode: metrics}\n").getBytes("UTF-8"));

        CountDownLatch ready=new CountDownLatch(2), start=new CountDownLatch(1);
        AtomicInteger completed=new AtomicInteger();
        att.load.LoadEventListener listener=event -> { if(event.completed()) completed.incrementAndGet(); };
        LoadRequest request=new LoadRequest(root,Paths.get("config/config.yaml"),null,null,"same-load-id",
                scenario,null,null,null,null,null,null,null,null,null,null,null,Collections.<String>emptyList(),listener);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> submit=() -> {
                ready.countDown();
                if(!start.await(2,TimeUnit.SECONDS)) throw new AssertionError("load requests did not start together");
                try { new DefaultAttService().load(request); return "completed"; }
                catch(IllegalArgumentException collision) { return collision.getMessage(); }
            };
            Future<String> first=workers.submit(submit), second=workers.submit(submit);
            assertTrue(ready.await(2,TimeUnit.SECONDS));
            start.countDown();
            String left=first.get(5,TimeUnit.SECONDS), right=second.get(5,TimeUnit.SECONDS);
            assertTrue(("completed".equals(left) && right.startsWith("Load run ID already exists:"))
                    || ("completed".equals(right) && left.startsWith("Load run ID already exists:")), left + " / " + right);
            assertTrue(completed.get() > 0, "the winning reservation should be the only request that schedules work");
        } finally {
            start.countDown();
            workers.shutdownNow();
            workers.awaitTermination(2,TimeUnit.SECONDS);
        }
    }

    @Test void failedLoadReportPublicationDoesNotChangeExecutionOutcomeOrLegacyCliSummary() throws Exception {
        Path root=temp.resolve("load-report-publication-failure");
        Files.createDirectories(root.resolve("config")); Files.createDirectories(root.resolve("templates/SIMPLE"));
        Files.createDirectories(root.resolve("testcase")); Files.createDirectories(root.resolve("tools")); Files.createDirectories(root.resolve("load"));
        att.TestSchemas.install(root);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.11\n"
                + "outputDirectory: output\nenvironment: SIT\ntemplates: {root: templates}\n"
                + "testcase: {root: testcase}\n").getBytes("UTF-8"));
        Files.write(root.resolve("templates/SIMPLE/template.yaml"), ("schemaVersion: att-template/v3.4\n"
                + "name: SIMPLE\ndescription: report failure outcome test\nactions:\n"
                + "  show: {type: log, message: load-pass}\n").getBytes("UTF-8"));
        Path scenario=root.resolve("load/scenario.yaml");
        Files.write(scenario, ("schemaVersion: att-load/v1.9\nworkloads:\n"
                + "  - id: report-failure\n    target: {type: template, id: SIMPLE}\n"
                + "    load: {users: 1, duration: 100ms}\n    execution: {thinkTime: 0ms}\n"
                + "evidence: {mode: metrics}\n").getBytes("UTF-8"));

        DefaultAttService service=new DefaultAttService((outputRoot, loadResult) -> {
            throw new java.io.IOException("simulated artifact report write failure");
        });
        LoadResult result=service.load(new LoadRequest(root,Paths.get("config/config.yaml"),null,null,"report-failed",
                scenario,null,null,null,null,null,null,null,null,null,null,null,Collections.<String>emptyList()));

        assertEquals("PASS",result.status());
        assertEquals(0,result.exitCode());
        assertFalse(result.paths().containsKey("report"));
        assertEquals("att-load-summary/v1.3",result.summary().get("schemaVersion"),
                "direct Engine/CLI summary advertises its actual version");
        att.validation.JsonSchemaVerifier.verify(Paths.get("schemas/att-load-summary-v1.3.schema.json"),result.summary());
        Map<?,?> worker=(Map<?,?>)result.toWorkerMap();
        Map<?,?> compact=(Map<?,?>)worker.get("summary");
        Map<?,?> archive=(Map<?,?>)compact.get("archive");
        assertEquals("PASS",worker.get("status"));
        assertEquals(0,worker.get("exitCode"));
        assertEquals("FAILED",archive.get("status"));
        assertEquals("FAILED",archive.get("reportAvailability"));
        assertEquals("LOAD_REPORT_WRITE_FAILED",archive.get("omissionReason"));
        assertTrue(((Map<?,?>)archive.get("paths")).isEmpty(), "failed report writes must not advertise missing paths");
        assertFalse(((Map<?,?>)compact.get("diagnostics")).containsKey("primaryError"));
    }

    @Test void cleanupFailureKeepsCompletedLoadMetricsAndPublishesSafeTeardownDiagnostic() throws Exception {
        Path root=temp.resolve("load-cleanup-failure");
        Files.createDirectories(root.resolve("config"));Files.createDirectories(root.resolve("templates/SIMPLE"));
        Files.createDirectories(root.resolve("testcase"));Files.createDirectories(root.resolve("tools"));Files.createDirectories(root.resolve("load"));
        att.TestSchemas.install(root);
        Path mqHelper=Files.createDirectories(root.resolve("config/mqhelpers")).resolve("broker.yaml");
        Files.write(mqHelper,("schemaVersion: att-mqhelper/v1.3\nid: broker\nname: Broker\ndescription: teardown test\n"
                +"defaults:\n  connection: {queueManager: QM1, host: localhost, port: 1414, channel: CH}\n"
                +"  message: {requestQueues: [REQUEST.Q]}\ninstances: [{id: primary}]\n").getBytes("UTF-8"));
        Files.write(root.resolve("config/config.yaml"),("schemaVersion: att-config/v2.11\n"
                +"outputDirectory: output\nenvironment: SIT\ntemplates: {root: templates}\n"
                +"testcase: {root: testcase}\nmqhelpers: [config/mqhelpers/broker.yaml]\n").getBytes("UTF-8"));
        Files.write(root.resolve("templates/SIMPLE/template.yaml"),("schemaVersion: att-template/v3.4\n"
                +"name: SIMPLE\ndescription: cleanup failure result preservation\nactions:\n"
                +"  show: {type: log, message: completed-before-cleanup}\n"
                +"  send:\n    type: tool\n    call: \"#{mq.broker.send(queue='REQUEST.Q', payload='completed-before-cleanup')}\"\n").getBytes("UTF-8"));
        Path scenario=root.resolve("load/scenario.yaml");
        // Keep duration above MQ helper startup jitter so CI still records completed>0 before teardown fails.
        Files.write(scenario,("schemaVersion: att-load/v1.9\nworkloads:\n"
                +"  - id: cleanup-a\n    target: {type: template, id: SIMPLE}\n"
                +"    load: {users: 1, duration: 500ms}\n    execution: {thinkTime: 0ms}\n"
                +"    thresholds: {p95: '<= 5000ms'}\n"
                +"  - id: cleanup-b\n    target: {type: template, id: SIMPLE}\n"
                +"    load: {users: 1, duration: 500ms}\n    execution: {thinkTime: 0ms}\n"
                +"    thresholds: {p95: '<= 5000ms'}\n"
                +"evidence: {mode: metrics}\n").getBytes("UTF-8"));

        AtomicInteger disconnectAttempts=new AtomicInteger();
        att.exec.MqTransport.Factory mqTransport=helper -> new att.exec.MqTransport.Connection() {
            @Override public att.exec.MqTransport.Queue open(String queue,boolean input,boolean output) {
                return new att.exec.MqTransport.Queue() {
                    @Override public att.exec.MqTransport.Message put(byte[] payload,att.exec.MqTransport.PutRequest request) {
                        return new att.exec.MqTransport.Message(new byte[]{1},null,payload);
                    }
                    @Override public att.exec.MqTransport.Message get(att.exec.MqTransport.GetRequest request) {
                        return new att.exec.MqTransport.Message(new byte[]{1},null,new byte[0]);
                    }
                    @Override public void close() { }
                };
            }
            @Override public void disconnect() throws Exception {
                disconnectAttempts.incrementAndGet();
                throw new att.exec.MqTransport.Exception(
                        "MQRC_Q_MGR_NOT_AVAILABLE /Users/alice/private/connection",2,2059,
                        "MQRC_Q_MGR_NOT_AVAILABLE",new java.io.IOException("/Users/alice/private/connection"));
            }
            @Override public void close() throws Exception { disconnect(); }
        };
        DefaultAttService service=new DefaultAttService((outputRoot,loadResult) ->
                new att.load.LoadReportWriter().write(outputRoot,loadResult),
                (packageRoot,config) -> att.load.LoadRunResourcesTestSupport.withMqTransport(packageRoot,config,mqTransport));
        LoadResult result=service.load(new LoadRequest(root,Paths.get("config/config.yaml"),null,null,"cleanup-failed",
                scenario,null,null,null,null,null,null,null,null,null,null,null,Collections.<String>emptyList()));

        assertEquals("ERROR",result.status());
        assertEquals(3,result.exitCode());
        assertTrue(result.paths().containsKey("report"));
        assertTrue(Files.isRegularFile(Paths.get(result.paths().get("report"))));
        Map<?,?> summary=(Map<?,?>)result.toMap().get("summary");
        Map<?,?> metrics=(Map<?,?>)summary.get("metrics");
        assertTrue(((Number)metrics.get("completed")).longValue()>0L,"completed scheduler metrics must survive cleanup failure");
        assertEquals("ERROR",summary.get("executionStatus"));
        assertEquals("MET",summary.get("performanceVerdict"),"evaluated workload thresholds remain independent from teardown status");
        Map<?,?> workloads=(Map<?,?>)summary.get("workloads");
        assertEquals(2,workloads.size());
        for(String id:java.util.Arrays.asList("cleanup-a","cleanup-b")) {
            Map<?,?> workload=(Map<?,?>)workloads.get(id);
            assertTrue(((Number)((Map<?,?>)workload.get("metrics")).get("completed")).longValue()>0L,id);
            assertEquals("MET",workload.get("performanceVerdict"),id);
        }
        Map<?,?> diagnostics=(Map<?,?>)summary.get("diagnostics");
        Map<?,?> primary=(Map<?,?>)diagnostics.get("primaryError");
        assertEquals("MQ_RESOURCE_TEARDOWN_FAILED",primary.get("code"));
        assertEquals("TEARDOWN",primary.get("phase"));
        assertTrue(primary.toString().contains("broker:primary"),primary.toString());
        assertTrue(((Number)primary.get("failedCloseCount")).longValue()>=1L);
        assertTrue(disconnectAttempts.get()>0,"the fake physical MQ connection must fail during disconnect");
        Map<?,?> failedResource=(Map<?,?>)((java.util.List<?>)primary.get("resources")).get(0);
        assertEquals(2L,((Number)failedResource.get("mqCompletionCode")).longValue());
        assertEquals(2059L,((Number)failedResource.get("mqReasonCode")).longValue());
        assertTrue(failedResource.toString().contains("MQRC_Q_MGR_NOT_AVAILABLE"));
        assertFalse(result.toMap().toString().contains("/Users/alice/private"));
        String reportSummary=new String(Files.readAllBytes(Paths.get(result.paths().get("report")).getParent().getParent()
                .resolve("load-summary.json")),StandardCharsets.UTF_8);
        com.fasterxml.jackson.databind.JsonNode reportJson=new com.fasterxml.jackson.databind.ObjectMapper().readTree(reportSummary);
        assertEquals("TEARDOWN",reportJson.path("teardown").path("phase").asText());
        assertTrue(reportJson.path("workloads").path("cleanup-a").path("metrics").path("completed").asLong()>0L);
        assertTrue(reportJson.path("workloads").path("cleanup-b").path("metrics").path("completed").asLong()>0L);

        Map<?,?> worker=(Map<?,?>)result.toWorkerMap();
        assertEquals("ERROR",worker.get("status"));
        Map<?,?> workerSummary=(Map<?,?>)worker.get("summary");
        assertEquals("ERROR",((Map<?,?>)workerSummary.get("execution")).get("status"));
        assertEquals("MET",((Map<?,?>)workerSummary.get("performance")).get("verdict"));
        assertTrue(((Number)((Map<?,?>)workerSummary.get("metrics")).get("completed")).longValue()>0L);
        Map<?,?> workerWorkloads=(Map<?,?>)workerSummary.get("workloads");
        assertEquals(2,((Number)workerWorkloads.get("total")).intValue());
        assertEquals(0,((Number)workerWorkloads.get("omittedCount")).intValue());
        assertEquals(2,((java.util.List<?>)workerWorkloads.get("items")).size());
    }

    @Test void configuredLoadIdentityUsesTheSameEnclosingTimestampAsIterationContext() throws Exception {
        Path root=temp.resolve("load-identity-time-package");
        Files.createDirectories(root.resolve("config")); Files.createDirectories(root.resolve("templates/SIMPLE"));
        Files.createDirectories(root.resolve("testcase")); Files.createDirectories(root.resolve("load"));
        att.TestSchemas.install(root);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.12\n"
                + "outputDirectory: output\nenvironment: SIT\ntemplates: {root: templates}\n"
                + "testcase: {root: testcase}\n"
                + "execution: {runIdFormat: \"run-#{str.replace(value=${EXEC.RUN_STARTED_AT}, target=':', replacement='-')}\"}\n").getBytes("UTF-8"));
        Files.write(root.resolve("templates/SIMPLE/template.yaml"), ("schemaVersion: att-template/v3.4\n"
                + "name: SIMPLE\ndescription: load identity timestamp\nactions:\n"
                + "  show: {type: log, message: 'run=${EXEC.RUN_STARTED_AT}'}\n").getBytes("UTF-8"));
        Path scenario=root.resolve("load/scenario.yaml");
        Files.write(scenario, ("schemaVersion: att-load/v1.3\nworkloads:\n"
                + "  - id: time\n    target: {type: template, id: SIMPLE}\n"
                + "    load: {users: 1, duration: 1s}\n    execution: {thinkTime: 0ms}\n"
                + "evidence: {mode: all}\n").getBytes("UTF-8"));

        LoadResult result=new DefaultAttService().load(new LoadRequest(root,Paths.get("config/config.yaml"),null,
                null,null,scenario,null,null,null,null,null,null,null,null,null,null,null,
                Collections.<String>emptyList()));

        assertTrue(result.executionId().startsWith("run-"));
        Path runDirectory=root.resolve("output/load").resolve(result.executionId());
        Path caseYaml;
        try(java.util.stream.Stream<Path> files=Files.walk(runDirectory)) {
            caseYaml=files.filter(path -> path.getFileName().toString().equals("case.yaml")).findFirst()
                    .orElseThrow(() -> new AssertionError("Load did not retain its iteration Context"));
        }
        String caseLog=new String(Files.readAllBytes(caseYaml.getParent().resolve("case.log")),"UTF-8");
        String marker="run=";
        int start=caseLog.indexOf(marker);
        assertTrue(start>=0,caseLog);
        int end=caseLog.indexOf('\n',start);
        String runStartedAt=caseLog.substring(start+marker.length(),end<0?caseLog.length():end).trim();
        assertEquals(result.executionId(),"run-"+runStartedAt.replace(":","-"));
    }
}
