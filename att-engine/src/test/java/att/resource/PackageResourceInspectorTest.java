package att.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PackageResourceInspectorTest {
    @TempDir Path temp;
    private PackageResourceInspector inspector() throws Exception {
        Path root=Paths.get("").toRealPath();
        assertTrue(Files.isRegularFile(root.resolve("config/config.yaml")),"Tests run from the repository root");
        return new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",Collections.<String>emptyList(),65536,262144);
    }

    @Test void listsConfiguredPackageResourcesWithLogicalIdsAndReferences() throws Exception {
        Map<String,Object> page=inspector().inspect("list",null,null,null,0,100);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)page.get("items");
        assertFalse(items.isEmpty());
        List<String> types=items.stream().map(item->String.valueOf(item.get("type"))).distinct().collect(Collectors.toList());
        assertTrue(types.contains("template"));
        assertTrue(types.contains("flow"));
        assertTrue(types.contains("tool"));
        assertTrue(types.contains("case"));
        Map<String,Object> testCase=items.stream().filter(item->"case".equals(item.get("type"))).findFirst().get();
        @SuppressWarnings("unchecked") Map<String,Object> provenance=(Map<String,Object>)testCase.get("provenance");
        assertNotNull(provenance.get("suite"));assertNotNull(provenance.get("groupId"));
        assertNotNull(provenance.get("sheet"));assertTrue(provenance.get("rowNumber") instanceof Number);
        @SuppressWarnings("unchecked") List<Map<String,Object>> references=(List<Map<String,Object>>)testCase.get("references");
        assertFalse(references.isEmpty(),"Case stages should resolve to their referenced Templates");
        for(Map<String,Object> item:items) {
            assertTrue(String.valueOf(item.get("resourceId")).matches("[A-Za-z0-9._-]+"));
            assertFalse(item.toString().contains(Paths.get("").toAbsolutePath().toString()));
        }
    }

    @Test void returnsSafeTemplateProjectionAndEnforcesRevisionBoundPagination() throws Exception {
        Path root=packageWithTemplate("safe-source", "TEST", "description: harmless");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.singletonList("templates/TEST/template.yaml"),65536,262144);
        @SuppressWarnings("unchecked") Map<String,Object> page=inspector.inspect("list","template",null,null,0,1);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)page.get("items");
        assertEquals(1,items.size());
        String resourceId=String.valueOf(items.get(0).get("resourceId"));
        Map<String,Object> detail=inspector.inspect("detail","template",resourceId,null,0,1);
        assertTrue(detail.containsKey("definition"));
        Map<String,Object> source=inspector.inspect("source","template",resourceId,null,0,1);
        assertEquals(Boolean.TRUE,source.get("available"));
        assertFalse(String.valueOf(source.get("text")).contains(Paths.get("").toAbsolutePath().toString()));
        assertThrows(PackageResourceInspector.StaleResourceCursorException.class,
                ()->inspector.inspect("list","template",null,null,1,1,"stale-revision"));
    }

    @Test void neverReadsToolSourceWithoutAnExplicitServerAllowlist() throws Exception {
        PackageResourceInspector inspector=inspector();
        @SuppressWarnings("unchecked") Map<String,Object> page=inspector.inspect("list","tool",null,null,0,100);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)page.get("items");
        assertFalse(items.isEmpty());
        for(Map<String,Object> item:items)assertEquals(Boolean.FALSE,item.get("sourceAvailable"));
    }

    @Test void readsToolScriptOnlyWhenItsPackageRelativePathIsAllowlisted() throws Exception {
        Path root=Paths.get("").toRealPath();
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.singletonList("tools/tool_group_dispatch.sh"),65536,262144);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","tool",null,null,0,100).get("items");
        Map<String,Object> permitted=items.stream().filter(item->Boolean.TRUE.equals(item.get("sourceAvailable"))).findFirst().get();
        Map<String,Object> source=inspector.inspect("source","tool",String.valueOf(permitted.get("resourceId")),null,0,1);
        assertEquals(Boolean.TRUE,source.get("available"));assertEquals("text",source.get("format"));
        assertEquals("tools/tool_group_dispatch.sh",source.get("logicalPath"));
        assertFalse(String.valueOf(source.get("text")).contains(root.toString()));
    }

    @Test void redactsSecretsAndExternalPathsFromProjectedTemplateSource() throws Exception {
        Path root=Files.createDirectories(temp.resolve("package"));
        Files.createDirectories(root.resolve("config"));Files.createDirectories(root.resolve("templates/SECRET"));Files.createDirectories(root.resolve("testcase"));
        writeUtf8(root.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\ntestcase:\n  root: testcase\ntemplates:\n  root: templates\n");
        writeUtf8(root.resolve("templates/SECRET/template.yaml"),"schemaVersion: att-template/v3.6\nname: SECRET\n"
                +"description: 'password=top-secret; Bearer abc123; https://user:pass@example.com; /outside/private; account 123456789'\n"
                +"x-business:\n  payload: 'customer account 123456789'\n  details:\n    message: 'temporary credential is violet-123'\n"
                +"actions:\n  note:\n    type: log\n    description: 'temporary credential is violet-123'\n"
                +"    message: |\n      benign looking text\n      account 123456789\n");
        att.TestSchemas.install(root);
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.singletonList("templates/SECRET/template.yaml"),65536,262144);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","template",null,null,0,10).get("items");
        assertEquals(1,items.size());
        Map<String,Object> source=inspector.inspect("source","template",String.valueOf(items.get(0).get("resourceId")),null,0,1);
        assertEquals(Boolean.TRUE,source.get("available"),source.toString());assertEquals(Boolean.TRUE,source.get("redacted"),source.toString());
        String text=String.valueOf(source.get("text"));
        assertFalse(text.contains("top-secret"));assertFalse(text.contains("abc123"));assertFalse(text.contains("user:pass"));
        assertFalse(text.contains("/outside/private"));assertFalse(text.contains(root.toString()));
        assertFalse(text.contains("123456789"));assertFalse(text.contains("violet-123"));
        Map<String,Object> detail=inspector.inspect("detail","template",String.valueOf(items.get(0).get("resourceId")),null,0,1);
        assertFalse(detail.toString().contains("123456789"));assertFalse(detail.toString().contains("violet-123"));
    }

    @Test void doesNotExposeYamlSourceWithoutAnExplicitServerVisibilityPolicy() throws Exception {
        Path root=packageWithTemplate("source-policy", "POLICY", "description: package authored text");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","template",null,null,0,10).get("items");
        assertEquals(Boolean.FALSE,items.get(0).get("sourceAvailable"));
        Map<String,Object> source=inspector.inspect("source","template",String.valueOf(items.get(0).get("resourceId")),null,0,1);
        assertEquals(Boolean.FALSE,source.get("available"));assertEquals("visibility-policy",source.get("reason"));
    }

    @Test void debugFormHidesAuthoredDefaultsAndDoesNotReturnTheNormalizedSidecar() throws Exception {
        Path root=packageWithTemplate("debug-form-policy", "FORM", "description: safe");
        writeUtf8(root.resolve("templates/FORM/debug.yaml"), "schemaVersion: att-debug/v1.2\ninputs:\n"
                + "  payload: {account: 'customer account 123456789', message: 'temporary credential violet-123'}\n"
                + "  values: ['nested business value']\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","template",null,null,0,10).get("items");
        Map<String,Object> form=inspector.inspectDebugForm("template",String.valueOf(items.get(0).get("resourceId")),null);
        assertFalse(form.containsKey("normalizedInput"));
        assertFalse(form.toString().contains("123456789"));
        assertFalse(form.toString().contains("violet-123"));
        assertFalse(form.toString().contains("nested business value"));
        @SuppressWarnings("unchecked") Map<String,Object> input=(Map<String,Object>)form.get("input");
        @SuppressWarnings("unchecked") Map<String,Object> inputs=(Map<String,Object>)input.get("inputs");
        @SuppressWarnings("unchecked") Map<String,Object> payload=(Map<String,Object>)inputs.get("payload");
        assertEquals("/inputs/payload/account",((Map<?,?>)payload.get("account")).get("$attDebugKeepDefault"));
    }

    @Test void quickLoadOmitsDebugLocalTestdataAndAcceptsSeparateLoadImports() throws Exception {
        Path root=packageWithTemplate("quick-load-testdata", "FORM", "description: safe");
        Path debugData=Files.createDirectories(root.resolve("debug-data")).resolve("debug.yaml");
        writeUtf8(debugData,"schemaVersion: att-testdata/v1.0\nid: debugAccounts\nrecords: [{id: 17}]\n");
        Path loadData=Files.createDirectories(root.resolve("testdata")).resolve("load.yaml");
        writeUtf8(loadData,"schemaVersion: att-testdata/v1.0\nid: loadAccounts\nrecords: [{id: 42}]\n");
        Path sidecar=root.resolve("templates/FORM/debug.yaml");
        writeUtf8(sidecar,"schemaVersion: att-debug/v1.2\ntestdata: [debug-data/debug.yaml]\ninputs: {amount: 7}\nvars: {reference: REF001}\n");
        byte[] original=Files.readAllBytes(sidecar);
        Files.createDirectories(root.resolve("load"));
        writeUtf8(root.resolve("load/load.visualuser.yaml"),"schemaVersion: att-load/v1.6\nload: {users: 2, duration: 5s}\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",Collections.<String>emptyList(),65536,262144);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","template",null,null,0,10).get("items");
        String resourceId=String.valueOf(items.get(0).get("resourceId"));
        Map<String,Object> form=inspector.inspectQuickLoadForm("template",resourceId,"virtualUsers",null);
        assertEquals(Boolean.TRUE,form.get("debugLocalTestdataOmitted"));
        assertFalse(form.toString().contains("debug-data/debug.yaml"));
        assertFalse(form.toString().contains("debugAccounts"));

        Map<String,Object> draft=inspector.validateQuickLoadInput("template","FORM","virtualUsers",
                Map.of("inputs",Map.of("amount",7),"vars",Map.of("reference","REF001")),
                Map.of("users",3),Map.of(),List.of("testdata/load.yaml"));
        assertEquals(Boolean.FALSE,draft.get("debugLocalTestdataOmitted"),"Submitted Load input contains no Debug-local imports");
        assertEquals(List.of("testdata/load.yaml"),draft.get("loadTestdata"));
        assertEquals(List.of("testdata/load.yaml"),((Map<?,?>)draft.get("normalizedScenario")).get("testdata"));
        assertTrue(String.valueOf(draft.get("previewYaml")).contains("users: 3"));
        assertArrayEquals(original,Files.readAllBytes(sidecar),"Quick Load must not modify the Debug sidecar");
    }

    @Test void quickLoadFormsSupportEveryTargetWithAndWithoutDebugSidecars() throws Exception {
        Path root=packageWithQuickLoadTargets("quick-load-targets");
        for(String type:List.of("template","flow","tool")) {
            PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",Collections.<String>emptyList(),65536,262144);
            @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list",type,null,null,0,20).get("items");
            String logicalId="tool".equals(type)?"echo":"template".equals(type)?"FORM":"FLOW.v1";
            String resourceId=String.valueOf(items.stream().filter(item->logicalId.equals(item.get("logicalId"))).findFirst().orElseThrow(()->new AssertionError(type+": "+items)).get("resourceId"));
            Map<String,Object> empty=inspector.inspectQuickLoadForm(type,resourceId,"virtualUsers",null);
            @SuppressWarnings("unchecked") Map<String,Object> emptyInput=(Map<String,Object>)empty.get("input");
            assertEquals(Boolean.FALSE,empty.get("debugLocalTestdataOmitted"));
            assertEquals(Collections.emptyMap(),emptyInput.get("inputs"));
            if("tool".equals(type))assertEquals(Collections.emptyMap(),emptyInput.get("arguments"));
            else assertEquals(Collections.emptyMap(),emptyInput.get("vars"));
        }

        writeUtf8(root.resolve("templates/FORM/debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs: {amount: 7}\nvars: {reference: REF001}\n");
        writeUtf8(root.resolve("templates/flows/demo/flow.yaml").getParent().resolve("debug.yaml"),
                "schemaVersion: att-debug/v1.2\ninputs: {flowValue: 9}\nvars: {flowRef: FLOW01}\n");
        writeUtf8(root.resolve("config/tools/echo.debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs: {toolInput: 11}\narguments: {value: typed-argument}\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",Collections.<String>emptyList(),65536,262144);
        for(String type:List.of("template","flow","tool")) {
            @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list",type,null,null,0,20).get("items");
            String logicalId="tool".equals(type)?"echo":"template".equals(type)?"FORM":"FLOW.v1";
            String resourceId=String.valueOf(items.stream().filter(item->logicalId.equals(item.get("logicalId"))).findFirst().orElseThrow(AssertionError::new).get("resourceId"));
            Map<String,Object> form=inspector.inspectQuickLoadForm(type,resourceId,"virtualUsers",null);
            @SuppressWarnings("unchecked") Map<String,Object> safeInput=(Map<String,Object>)form.get("input");
            assertTrue(safeInput.toString().contains("$attDebugKeepDefault"),safeInput.toString());
            if("tool".equals(type))assertTrue(safeInput.containsKey("arguments"));
            else assertTrue(safeInput.containsKey("vars"));
        }
    }

    @Test void hidesWorkbookBusinessValuesAndBoundsHighFanInSummaries() throws Exception {
        Path root=packageWithCaseWorkbook("case-data", 125);
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144);
        @SuppressWarnings("unchecked") List<Map<String,Object>> cases=(List<Map<String,Object>>)inspector.inspect("list","case",null,null,0,1).get("items");
        Map<String,Object> detail=inspector.inspect("detail","case",String.valueOf(cases.get(0).get("resourceId")),null,0,1);
        assertFalse(detail.toString().contains("customer account 123456789"));
        assertFalse(detail.toString().contains("temporary credential violet-123"));
        @SuppressWarnings("unchecked") Map<String,Object> definition=(Map<String,Object>)detail.get("definition");
        assertEquals("hidden",definition.get("caseDataState"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> stages=(List<Map<String,Object>>)definition.get("stages");
        assertEquals("hidden",stages.get(0).get("valuesState"));

        @SuppressWarnings("unchecked") List<Map<String,Object>> templates=(List<Map<String,Object>>)inspector.inspect("list","template",null,null,0,10).get("items");
        Map<String,Object> target=templates.get(0);
        @SuppressWarnings("unchecked") List<Map<String,Object>> referencedBy=(List<Map<String,Object>>)target.get("referencedBy");
        assertEquals(100,referencedBy.size());
        assertEquals(125,target.get("referencedByCount"));
        assertEquals(Boolean.TRUE,target.get("referencedByHasMore"));
    }

    @Test void rejectsTraversalAndDoesNotReadAnExternalSymlinkedTemplate() throws Exception {
        Path root=packageWithTemplate("contained", "SAFE", "description: safe");
        Path outside=writeUtf8(temp.resolve("outside-template.yaml"),
                "schemaVersion: att-template/v3.6\nname: EXTERNAL\ndescription: outside-secret\nactions:\n  note:\n    type: log\n    message: hidden\n");
        Path linked=Files.createDirectories(root.resolve("templates/ESCAPE")).resolve("template.yaml");
        try { Files.createSymbolicLink(linked, outside); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links are unavailable in this test environment");
        }
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",Collections.<String>emptyList(),65536,262144);
        Map<String,Object> page=inspector.inspect("list","template",null,null,0,100);
        assertFalse(page.toString().contains("outside-secret"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)page.get("items");
        assertEquals(1,items.size(),"The external symlink must not become a package Template");
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->inspector.inspect("source","template","../../outside-template.yaml",null,0,1));
    }

    @Test void reportsOversizedSourceAsUnavailableWithoutReturningPartialContent() throws Exception {
        Path root=packageWithTemplate("oversized", "LARGE", "description: '"+repeat('x',1024)+"'");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.singletonList("templates/LARGE/template.yaml"),256,4096);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","template",null,null,0,10).get("items");
        assertEquals(1,items.size());
        Map<String,Object> source=inspector.inspect("source","template",String.valueOf(items.get(0).get("resourceId")),null,0,1);
        assertEquals(Boolean.FALSE,source.get("available"));assertEquals("size-limit",source.get("reason"));
        assertFalse(source.containsKey("text"),"Oversized YAML must not be returned partially");
    }

    @Test void rejectsPaginationAfterAResourceDescriptorChanges() throws Exception {
        Path root=packageWithTemplate("stale", "PAYMENT", "description: first");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",Collections.<String>emptyList(),65536,262144);
        Map<String,Object> page=inspector.inspect("list","template",null,null,0,1);
        String revision=String.valueOf(page.get("revisionDigest"));
        writeUtf8(root.resolve("templates/PAYMENT/template.yaml"),templateYaml("PAYMENT", "description: changed"));
        assertThrows(PackageResourceInspector.StaleResourceCursorException.class,
                ()->inspector.inspect("list","template",null,null,1,1,revision));
    }

    @Test void enforcesTheSerializedPageResponseLimit() throws Exception {
        Path root=packageWithTemplate("bounded-response", "ITEM0", "description: item");
        for(int i=1;i<16;i++) {
            Path directory=Files.createDirectories(root.resolve("templates/ITEM"+i));
            writeUtf8(directory.resolve("template.yaml"),templateYaml("ITEM"+i,"description: item"));
        }
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",Collections.<String>emptyList(),256,1024);
        assertThrows(PackageResourceInspector.ResponseTooLargeException.class,
                ()->inspector.inspect("list","template",null,null,0,100));
    }

    @Test void returnsSafeDiagnosticsWhenOneConfiguredResourceRootCannotBeInspected() throws Exception {
        Path root=packageWithTemplate("missing-case-root", "PAYMENT", "description: item");
        Files.delete(root.resolve("testcase"));
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",Collections.<String>emptyList(),65536,262144);
        Map<String,Object> page=inspector.inspect("list","case",null,null,0,10);
        assertTrue(page.toString().contains("ATT-RESOURCE-CASE-ROOT-UNAVAILABLE"));
        assertFalse(page.toString().contains(root.toString()));
    }

    private Path packageWithTemplate(String directory,String name,String extraField) throws Exception {
        Path root=Files.createDirectories(temp.resolve(directory));
        Files.createDirectories(root.resolve("config"));Files.createDirectories(root.resolve("templates"));Files.createDirectories(root.resolve("testcase"));
        writeUtf8(root.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\ntestcase:\n  root: testcase\ntemplates:\n  root: templates\n");
        Path template=Files.createDirectories(root.resolve("templates").resolve(name)).resolve("template.yaml");
        writeUtf8(template,templateYaml(name,extraField));
        att.TestSchemas.install(root);
        return root;
    }

    private Path packageWithQuickLoadTargets(String directory) throws Exception {
        Path root=packageWithTemplate(directory,"FORM","description: Quick Load targets");
        writeUtf8(root.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n"
                +"testcase:\n  root: testcase\ntemplates:\n  root: templates\ntools:\n  echo:\n    name: Echo\n    description: Quick Load Tool target\n"
                +"    command: [echo, \"${input.value}\"]\n    stdoutFormat: text\n    arguments:\n      value:\n        name: Value\n        description: Value\n        required: false\n");
        Path flow=Files.createDirectories(root.resolve("templates/flows/demo")).resolve("flow.yaml");
        writeUtf8(flow,"schemaVersion: att-flow/v3.6\nid: FLOW.v1\nname: Flow target\ndescription: Quick Load Flow target\nactions:\n  log:\n"
                +"    type: log\n    message: load-flow-smoke\n");
        Files.createDirectories(root.resolve("config/tools"));
        return root;
    }

    private Path packageWithCaseWorkbook(String directory, int rows) throws Exception {
        Path root=packageWithTemplate(directory,"TARGET","description: harmless");
        Path suite=Files.createDirectories(root.resolve("testcase")).resolve("cases.xlsx");
        writeUtf8(root.resolve("testcase/cases.yaml"),"schemaVersion: att-sidecar/v2.2\nid: cases\nexcel:\n  sheet: Cases\n  caseId: Case ID\n  tags: Tags\n  dataColumns: payload=Payload\nstages:\n  - key: invoke\n    template: Template\n    dataColumns: request=Request\n");
        try (Workbook workbook=new XSSFWorkbook(); OutputStream output=Files.newOutputStream(suite)) {
            Sheet sheet=workbook.createSheet("Cases");
            Row header=sheet.createRow(0);
            String[] columns={"Case ID","Tags","Payload","Template","Request"};
            for(int i=0;i<columns.length;i++)header.createCell(i).setCellValue(columns[i]);
            for(int i=0;i<rows;i++) {
                Row row=sheet.createRow(i+1);
                row.createCell(0).setCellValue("CASE_"+i);row.createCell(1).setCellValue("smoke");
                row.createCell(2).setCellValue("customer account 123456789");row.createCell(3).setCellValue("TARGET");
                row.createCell(4).setCellValue("temporary credential violet-123");
            }
            workbook.write(output);
        }
        return root;
    }

    private static String templateYaml(String name,String extraField) {
        return "schemaVersion: att-template/v3.6\nname: "+name+"\n"+extraField+"\nactions:\n  note:\n    type: log\n    message: safe\n";
    }

    private static Path writeUtf8(Path path,String text) throws Exception {
        return Files.write(path,text.getBytes(StandardCharsets.UTF_8));
    }

    private static String repeat(char value,int count) {
        StringBuilder result=new StringBuilder(count);
        for(int i=0;i<count;i++)result.append(value);
        return result.toString();
    }
}
