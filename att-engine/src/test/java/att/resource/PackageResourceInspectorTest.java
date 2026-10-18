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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PackageResourceInspectorTest {
    @TempDir Path temp;

    @org.junit.jupiter.api.BeforeEach void enableServerPathPresentation() { att.core.PathPresentation.configure(true, true); }
    @org.junit.jupiter.api.AfterEach void restoreDefaultPathPresentation() { att.core.PathPresentation.configure(false, false); }

    private PackageResourceInspector inspector() throws Exception {
        Path home=Paths.get("").toRealPath();
        Path root=home.resolve("packages/default");
        Path config=home.resolve("config/config.yaml");
        assertTrue(Files.isRegularFile(config),"Tests run from the repository root");
        return new PackageResourceInspector(root,config,"SIT",Collections.<String>emptyList(),65536,262144);
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

    @Test void resourceSortingIsAppliedBeforeStableServerPagination() throws Exception {
        Path root=packageWithTemplate("server-sorted-resources","MID","description: middle");
        for(String name:java.util.Arrays.asList("AAA","ZZZ")) {
            Path template=Files.createDirectories(root.resolve("templates").resolve(name)).resolve("template.yaml");
            writeUtf8(template,templateYaml(name,"description: sorted"));
        }
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144);
        Map<String,Object> first=inspector.inspect("list","template",null,null,0,2,null,null,"name","asc");
        @SuppressWarnings("unchecked") List<Map<String,Object>> firstItems=(List<Map<String,Object>>)first.get("items");
        assertEquals(java.util.Arrays.asList("AAA","MID"),firstItems.stream().map(item->String.valueOf(item.get("name"))).collect(Collectors.toList()));
        Map<String,Object> next=inspector.inspect("list","template",null,null,2,2,null,null,"name","asc");
        @SuppressWarnings("unchecked") List<Map<String,Object>> nextItems=(List<Map<String,Object>>)next.get("items");
        assertEquals(Collections.singletonList("ZZZ"),nextItems.stream().map(item->String.valueOf(item.get("name"))).collect(Collectors.toList()));
        Map<String,Object> descending=inspector.inspect("list","template",null,null,0,3,null,null,"name","desc");
        @SuppressWarnings("unchecked") List<Map<String,Object>> descendingItems=(List<Map<String,Object>>)descending.get("items");
        assertEquals(java.util.Arrays.asList("ZZZ","MID","AAA"),descendingItems.stream().map(item->String.valueOf(item.get("name"))).collect(Collectors.toList()));
        assertThrows(IllegalArgumentException.class,()->inspector.inspect("list","template",null,null,0,3,null,null,"unknown","asc"));
    }

    @Test void executionRevisionIncludesEngineRegistrySharedConfigAndEnvironmentIdentity() throws Exception {
        Path root=packageWithTemplate("revision-context", "PAYMENT", "description: revision context");
        Path sharedConfig=Files.createDirectories(temp.resolve("shared-runtime/config")).resolve("config.yaml");
        writeUtf8(sharedConfig,"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n  UAT: {}\n"
                +"testcase: {root: testcase}\ntemplates: {root: templates}\n");
        String sit=new PackageResourceInspector(root,sharedConfig,"SIT",Collections.<String>emptyList(),65536,262144)
                .executionRevisionDigest();
        String uat=new PackageResourceInspector(root,sharedConfig,"UAT",Collections.<String>emptyList(),65536,262144)
                .executionRevisionDigest();
        assertTrue(sit.matches("[a-f0-9]{64}"));
        assertNotEquals(sit,uat,"An effective environment change must invalidate validated revisions");
        assertTrue(att.validation.SchemaFiles.registryDigest().matches("[a-f0-9]{64}"),
                "Revision identity includes the bundled registry fingerprint");
    }

    @Test void loadCatalogDistinguishesPolicyTemplatesAndInvalidYamlAndRestrictsSourcePaths() throws Exception {
        Path root=packageWithTemplate("load-catalog", "PAYMENT", "description: catalog test");
        Path load=Files.createDirectories(root.resolve("load/nested"));
        writeUtf8(load.resolve("policy.yaml"),"schemaVersion: att-load/v1.6\nload: {users: 2, duration: 10s}\n");
        writeUtf8(load.resolve("broken.yaml"),"schemaVersion: [not-valid\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144);
        @SuppressWarnings("unchecked") Map<String,Object> page=inspector.inspectLoadCatalog("",0,100,null);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)page.get("items");
        Map<String,Object> policy=items.stream().filter(item->"load/nested/policy.yaml".equals(item.get("path"))).findFirst().orElse(null);
        Map<String,Object> broken=items.stream().filter(item->"load/nested/broken.yaml".equals(item.get("path"))).findFirst().orElse(null);
        assertNotNull(policy);assertNotNull(broken);
        assertEquals("template",policy.get("kind"));assertEquals("valid",policy.get("validation"));
        assertEquals(Boolean.TRUE,policy.get("createQuickLoad"));assertEquals(Boolean.FALSE,policy.get("runNow"));
        assertEquals("invalid",broken.get("kind"));assertEquals(Boolean.FALSE,broken.get("schedule"));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,()->inspector.inspectLoadSource("templates/PAYMENT/template.yaml"));
    }

    @Test void loadCatalogDetailReturnsStructuredRedactedMetadataWithoutTestdataPathsOrTargetResolution() throws Exception {
        Path root=packageWithTemplate("load-detail-safe", "PAYMENT", "description: detail test");
        Path load=Files.createDirectories(root.resolve("load"));
        writeUtf8(load.resolve("scenario.yaml"), "schemaVersion: att-load/v1.7\nworkloads:\n"
                +"  - id: unresolved-target\n    target: {type: template, id: MISSING}\n"
                +"    inputs: {password: never-return-this-secret, note: safe-value}\n"
                +"    testdata: {generated: {scope: iteration}}\n"
                +"    load: {users: 2, duration: 10s}\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144,false,false,"visibility-policy",Collections.<String>emptyList(),
                true,Collections.<String>emptyList(),Collections.<String>emptyList());

        Map<String,Object> detail=inspector.inspectLoadCatalogDetail("load/scenario.yaml",null);
        assertEquals("scenario",detail.get("kind"));
        assertEquals("unverified",detail.get("validation"),"View must not depend on resolving execution targets");
        assertEquals(Boolean.FALSE,detail.get("sourceAvailable"),"Structured metadata remains available when source is hidden");
        assertEquals("att-load/v1.7",detail.get("schemaVersion"));
        @SuppressWarnings("unchecked") Map<String,Object> definition=(Map<String,Object>)detail.get("definition");
        @SuppressWarnings("unchecked") List<Map<String,Object>> workloads=(List<Map<String,Object>>)definition.get("workloads");
        assertEquals("MISSING",((Map<?,?>)workloads.get(0).get("target")).get("id"));
        assertFalse(definition.containsKey("testdata"));
        assertFalse(workloads.get(0).containsKey("testdata"));
        assertFalse(detail.toString().contains("never-return-this-secret"));
        assertFalse(detail.toString().contains("private/runtime"));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->inspector.inspectLoadCatalogDetail("templates/PAYMENT/template.yaml",null));
    }

    @Test void neverReadsToolSourceWithoutAnExplicitServerAllowlist() throws Exception {
        PackageResourceInspector inspector=inspector();
        @SuppressWarnings("unchecked") Map<String,Object> page=inspector.inspect("list","tool",null,null,0,100);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)page.get("items");
        assertFalse(items.isEmpty());
        for(Map<String,Object> item:items)assertEquals(Boolean.FALSE,item.get("sourceAvailable"));
    }

    @Test void readsToolScriptOnlyWhenItsPackageRelativePathIsAllowlisted() throws Exception {
        Path root=Paths.get("").toRealPath().resolve("packages/default");
        Path config=Paths.get("").toRealPath().resolve("config/config.yaml");
        PackageResourceInspector inspector=new PackageResourceInspector(root,config,"SIT",
                Collections.singletonList("tools/tool_group_dispatch.sh"),65536,262144);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","tool",null,null,0,100).get("items");
        Map<String,Object> permitted=items.stream().filter(item->Boolean.TRUE.equals(item.get("sourceAvailable"))).findFirst().get();
        Map<String,Object> source=inspector.inspect("source","tool",String.valueOf(permitted.get("resourceId")),null,0,1);
        assertEquals(Boolean.TRUE,source.get("available"));assertEquals("shell",source.get("format"));
        assertEquals("tools/tool_group_dispatch.sh",source.get("logicalPath"));
        assertFalse(String.valueOf(source.get("text")).contains(root.toString()));
    }

    @Test void opensNestedPackageToolScriptsByDefaultForRelativeAndAbsoluteCommands() throws Exception {
        Path root=packageWithTemplate("default-tool-source","FORM","description: safe");
        Path script=Files.createDirectories(root.resolve("tools/mq")).resolve("檢查佇列.sh");
        writeUtf8(script,"#!/bin/sh\n# café\necho safe\n");
        String config="schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n"
                +"testcase:\n  root: testcase\ntemplates:\n  root: templates\ntools:\n"
                +"  relative:\n    name: Relative script\n    description: Relative test script\n    command: [bash, 'tools/mq/檢查佇列.sh']\n    stdoutFormat: text\n    arguments: {}\n"
                +"  absolute:\n    name: Absolute script\n    description: Absolute test script\n    command: [bash, '"+script.toString()+"']\n    stdoutFormat: text\n    arguments: {}\n";
        writeUtf8(root.resolve("config/config.yaml"),config);
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),Collections.<String>emptyList(),Collections.<String>emptyList(),
                65536,262144,false,true,"safe-package-files",Collections.<String>emptyList(),false,
                Collections.<String>emptyList(),Collections.<String>emptyList());
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","tool",null,null,0,10).get("items");
        assertEquals(2,items.size());
        for(Map<String,Object> item:items) {
            assertEquals(Boolean.TRUE,item.get("sourceAvailable"),item.toString());
            Map<String,Object> source=inspector.inspect("source","tool",String.valueOf(item.get("resourceId")),null,0,1);
            assertEquals(Boolean.TRUE,source.get("available"),source.toString());
            assertEquals("tools/mq/檢查佇列.sh",source.get("logicalPath"));
            assertEquals("shell",source.get("format"));
            assertTrue(String.valueOf(source.get("text")).contains("café"));
            assertFalse(source.toString().contains(root.toString()));
        }
    }

    @Test void requiresAnExactExternalScriptAllowlistAndNeverReturnsItsFilesystemPath() throws Exception {
        Path root=packageWithTemplate("external-tool-source","FORM","description: safe");
        Path externalRoot=Files.createDirectories(temp.resolve("external-tool-root"));
        Path script=Files.createDirectories(externalRoot.resolve("mq")).resolve("check-queue.sh");writeUtf8(script,"#!/bin/sh\necho external\n");
        writeUtf8(root.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n"
                +"testcase:\n  root: testcase\ntemplates:\n  root: templates\ntools:\n  queue:\n    name: External queue script\n"
                +"    description: External queue test script\n    command: [bash, '"+script.toString()+"']\n    stdoutFormat: text\n    arguments: {}\n");
        PackageResourceInspector denied=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),Collections.<String>emptyList(),Collections.singletonList(externalRoot.toString()),
                65536,262144,false,true,"safe-package-files",Collections.<String>emptyList(),false,
                Collections.<String>emptyList(),Collections.<String>emptyList());
        @SuppressWarnings("unchecked") Map<String,Object> deniedItem=((List<Map<String,Object>>)denied.inspect("list","tool",null,null,0,10).get("items")).get(0);
        assertEquals(Boolean.FALSE,deniedItem.get("sourceAvailable"));assertEquals("not-allowlisted",deniedItem.get("sourceReason"));
        assertFalse(deniedItem.toString().contains(script.toString()));
        PackageResourceInspector allowed=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),Collections.singletonList(script.toRealPath().toString()),Collections.singletonList(externalRoot.toRealPath().toString()),
                65536,262144,false,true,"safe-package-files",Collections.<String>emptyList(),false,
                Collections.<String>emptyList(),Collections.<String>emptyList());
        @SuppressWarnings("unchecked") Map<String,Object> allowedItem=((List<Map<String,Object>>)allowed.inspect("list","tool",null,null,0,10).get("items")).get(0);
        assertEquals(Boolean.TRUE,allowedItem.get("sourceAvailable"));
        Map<String,Object> source=allowed.inspect("source","tool",String.valueOf(allowedItem.get("resourceId")),null,0,1);
        assertEquals(Boolean.TRUE,source.get("available"));assertEquals("shell",source.get("format"));
        assertTrue(String.valueOf(source.get("logicalPath")).matches("external/[0-9a-f]{64}\\.sh"));
        assertFalse(source.toString().contains(externalRoot.toString()));assertTrue(String.valueOf(source.get("text")).contains("echo external"));
        Files.delete(script);
        Map<String,Object> missing=allowed.inspect("source","tool",String.valueOf(allowedItem.get("resourceId")),null,0,1);
        assertEquals(Boolean.FALSE,missing.get("available"));assertEquals("missing",missing.get("reason"));
    }

    @Test void appliesDenyGlobsToCanonicalExternalRootRelativePathsBeforeAliasing() throws Exception {
        Path root=packageWithTemplate("external-source-deny-globs","FORM","description: safe");
        Path externalRoot=Files.createDirectories(temp.resolve("external-source-deny-root"));
        Path privateScript=Files.createDirectories(externalRoot.resolve("private")).resolve("check.sh");
        Path allowedScript=Files.createDirectories(externalRoot.resolve("mq")).resolve("check-queue.sh");
        writeUtf8(privateScript,"#!/bin/sh\necho private\n");
        writeUtf8(allowedScript,"#!/bin/sh\necho allowed\n");
        writeUtf8(root.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n"
                +"testcase:\n  root: testcase\ntemplates:\n  root: templates\ntools:\n"
                +"  privateCheck:\n    name: Private external script\n    description: Deny glob regression\n    command: [bash, '"+privateScript.toString()+"']\n    stdoutFormat: text\n    arguments: {}\n"
                +"  mqCheck:\n    name: Allowed external script\n    description: Deny glob regression\n    command: [bash, '"+allowedScript.toString()+"']\n    stdoutFormat: text\n    arguments: {}\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),java.util.Arrays.asList(privateScript.toRealPath().toString(),allowedScript.toRealPath().toString()),
                Collections.singletonList(externalRoot.toRealPath().toString()),65536,262144,false,true,"safe-package-files",
                Collections.singletonList("**/private/**"),false,Collections.<String>emptyList(),Collections.<String>emptyList());
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","tool",null,null,0,10).get("items");
        Map<String,Object> denied=items.stream().filter(item->"Private external script".equals(item.get("name"))).findFirst().get();
        assertEquals(Boolean.FALSE,denied.get("sourceAvailable"),denied.toString());
        assertEquals("forbidden",denied.get("sourceReason"),denied.toString());
        Map<String,Object> deniedSource=inspector.inspect("source","tool",String.valueOf(denied.get("resourceId")),null,0,1);
        assertEquals(Boolean.FALSE,deniedSource.get("available"),deniedSource.toString());
        assertEquals("forbidden",deniedSource.get("reason"),deniedSource.toString());
        assertFalse(denied.toString().contains(externalRoot.toString()));
        assertFalse(deniedSource.toString().contains(externalRoot.toString()));

        Map<String,Object> allowed=items.stream().filter(item->"Allowed external script".equals(item.get("name"))).findFirst().get();
        assertEquals(Boolean.TRUE,allowed.get("sourceAvailable"),allowed.toString());
        Map<String,Object> allowedSource=inspector.inspect("source","tool",String.valueOf(allowed.get("resourceId")),null,0,1);
        assertEquals(Boolean.TRUE,allowedSource.get("available"),allowedSource.toString());
        assertTrue(String.valueOf(allowedSource.get("logicalPath")).matches("external/[0-9a-f]{64}\\.sh"));
        assertTrue(String.valueOf(allowedSource.get("text")).contains("echo allowed"));
        assertFalse(allowedSource.toString().contains(externalRoot.toString()));
    }

    @Test void rejectsPackageAndExternalSymlinkEscapesForToolSource() throws Exception {
        Path root=packageWithTemplate("tool-source-symlink-escape","FORM","description: safe");
        Path external=Files.createDirectories(temp.resolve("symlink-external"));Path secret=writeUtf8(external.resolve("private.sh"),"echo private\n");
        Path packageLink=root.resolve("tools-link.sh");Path approvedRoot=Files.createDirectories(temp.resolve("symlink-approved-root"));
        Path externalLink=approvedRoot.resolve("alias.sh");
        try {Files.createSymbolicLink(packageLink,secret);Files.createSymbolicLink(externalLink,secret);}
        catch(UnsupportedOperationException|java.io.IOException|SecurityException unavailable){org.junit.jupiter.api.Assumptions.abort("Symbolic links are unavailable in this test environment");}
        writeUtf8(root.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n"
                +"testcase:\n  root: testcase\ntemplates:\n  root: templates\ntools:\n  packageEscape:\n    name: Package symlink\n    description: Package symlink escape\n    command: [bash, '"+packageLink.toString()+"']\n    stdoutFormat: text\n    arguments: {}\n"
                +"  externalEscape:\n    name: External symlink\n    description: External symlink escape\n    command: [bash, '"+externalLink.toString()+"']\n    stdoutFormat: text\n    arguments: {}\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),Collections.singletonList(secret.toRealPath().toString()),Collections.singletonList(approvedRoot.toRealPath().toString()),
                65536,262144,false,true,"safe-package-files",Collections.<String>emptyList(),false,
                Collections.<String>emptyList(),Collections.<String>emptyList());
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","tool",null,null,0,10).get("items");
        assertEquals(2,items.size());for(Map<String,Object> item:items){assertEquals(Boolean.FALSE,item.get("sourceAvailable"));assertEquals("out-of-root",item.get("sourceReason"));}
        assertFalse(items.toString().contains(secret.toString()));
    }

    @Test void navigatesNestedPackageLocalFileReferencesWithoutPerFileAllowlistEntries() throws Exception {
        Path root=packageWithTemplate("default-reference-source","SOURCE","description: nested source reference");
        Path payload=Files.createDirectories(root.resolve("templates/SOURCE/payload"));
        writeUtf8(payload.resolve("request.xml"),"<request>&{./nested.sql}</request>\n");
        writeUtf8(payload.resolve("nested.sql"),"select 'café';\n");
        writeUtf8(root.resolve("templates/SOURCE/template.yaml"),"schemaVersion: att-template/v3.6\nname: SOURCE\ndescription: nested source reference\n"
                +"actions:\n  note:\n    type: log\n    message: '&{./payload/request.xml}'\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),Collections.<String>emptyList(),Collections.<String>emptyList(),
                65536,262144,false,true,"safe-package-files",Collections.<String>emptyList(),false,
                Collections.<String>emptyList(),Collections.<String>emptyList());
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","template",null,null,0,10).get("items");
        Map<String,Object> template=items.get(0);String resourceId=String.valueOf(template.get("resourceId"));
        Map<String,Object> source=inspector.inspect("source","template",resourceId,null,0,1);
        assertEquals(Boolean.TRUE,source.get("available"),source.toString());
        @SuppressWarnings("unchecked") List<Map<String,Object>> firstReferences=(List<Map<String,Object>>)source.get("fileReferences");
        assertTrue(firstReferences.stream().anyMatch(ref->"templates/SOURCE/payload/request.xml".equals(ref.get("logicalPath"))),source.toString());
        String firstId=String.valueOf(firstReferences.stream().filter(ref->"templates/SOURCE/payload/request.xml".equals(ref.get("logicalPath"))).findFirst().get().get("referenceId"));
        Map<String,Object> firstFile=inspector.inspect("source-file","template",resourceId,firstId,0,1,null,null);
        assertEquals(Boolean.TRUE,firstFile.get("available"));assertEquals("text",firstFile.get("format"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> nested=(List<Map<String,Object>>)firstFile.get("fileReferences");
        assertTrue(nested.stream().anyMatch(ref->"templates/SOURCE/payload/nested.sql".equals(ref.get("logicalPath"))));
        String nestedId=String.valueOf(nested.stream().filter(ref->"templates/SOURCE/payload/nested.sql".equals(ref.get("logicalPath"))).findFirst().get().get("referenceId"));
        Map<String,Object> nestedFile=inspector.inspect("source-file","template",resourceId,nestedId,0,1,null,"templates/SOURCE/payload/request.xml");
        assertEquals(Boolean.TRUE,nestedFile.get("available"));assertEquals("text",nestedFile.get("format"));
        assertTrue(String.valueOf(nestedFile.get("text")).contains("café"));
    }

    @Test void marksAllowlistedBinaryToolSourceUnavailableWithAStableReason() throws Exception {
        Path root=packageWithTemplate("binary-tool-source","FORM","description: safe");
        Path script=Files.createDirectories(root.resolve("tools")).resolve("binary.sh");
        byte[] prefix="#!/bin/sh\necho safe\n# ".getBytes(StandardCharsets.UTF_8);
        byte[] binary=java.util.Arrays.copyOf(prefix,prefix.length+1);binary[prefix.length]=(byte)0xff;Files.write(script,binary);
        writeUtf8(root.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n"
                +"testcase:\n  root: testcase\ntemplates:\n  root: templates\ntools:\n  binary:\n    name: Binary script\n    description: Binary source test\n"
                +"    command: [tools/binary.sh]\n    stdoutFormat: text\n    arguments: {}\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.singletonList("tools/binary.sh"),65536,262144,false,true,"safe-package-files",
                Collections.<String>emptyList(),false,Collections.<String>emptyList(),Collections.<String>emptyList());
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","tool",null,null,0,10).get("items");
        Map<String,Object> item=items.stream().filter(value->"binary".equals(value.get("logicalId"))).findFirst()
                .orElseThrow(()->new AssertionError("binary Tool should be indexed"));
        assertEquals(Boolean.FALSE,item.get("sourceAvailable"));assertEquals("binary-file",item.get("sourceReason"));
        Map<String,Object> source=inspector.inspect("source","tool",String.valueOf(item.get("resourceId")),null,0,1);
        assertEquals(Boolean.FALSE,source.get("available"));assertEquals("binary-file",source.get("reason"));
        assertFalse(source.containsKey("text"));
    }

    @Test void resolvesDescriptorRelativeConfigurationSourcesAndEnforcesVisibilityLimits() throws Exception {
        Path root=packageWithTemplate("configuration-file-source", "SOURCE", "description: harmless");
        Path source=Files.createDirectories(root.resolve("config/queries")).resolve("lookup.sql");
        writeUtf8(source, "select '${vars.account}' as account; -- cafe\u00e9 &{./result.json}\npassword=top-secret Bearer abc123\n");
        writeUtf8(root.resolve("config/queries/result.json"), "{\"status\":\"${EXEC.RESULT.status}\"}");
        Path large=source.resolveSibling("large.yaml");
        writeUtf8(large, "0123456789abcdef-more-than-limit\n");
        String origin="templates/SOURCE/template.yaml";
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                java.util.Arrays.asList(origin,"config/queries/lookup.sql","config/queries/result.json","config/queries/large.yaml"),65536,262144);

        Map<String,Object> result=inspector.inspectPackageFileSource("config/queries/lookup.sql",null);
        assertEquals(Boolean.TRUE,result.get("available"),result.toString());
        assertEquals("config/queries/lookup.sql",result.get("logicalPath"));
        assertEquals("sql",result.get("format"));
        assertTrue(String.valueOf(result.get("text")).contains("${vars.account}"));
        assertTrue(String.valueOf(result.get("text")).contains("cafe\u00e9"));
        assertFalse(String.valueOf(result.get("text")).contains("top-secret"));
        assertFalse(String.valueOf(result.get("text")).contains("abc123"));
        assertEquals(Boolean.TRUE,result.get("redacted"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> fileReferences=(List<Map<String,Object>>)result.get("fileReferences");
        assertTrue(fileReferences.stream().anyMatch(item->"config/queries/result.json".equals(item.get("logicalPath"))),result.toString());
        assertTrue(String.valueOf(fileReferences.get(0).get("referenceId")).matches("[0-9a-f]{64}"));
        Map<String,Object> referenced=inspector.inspectPackageFileSource("./result.json","config/queries/lookup.sql","server-verified");
        assertEquals(Boolean.TRUE,referenced.get("available"));
        assertTrue(String.valueOf(referenced.get("text")).contains("${EXEC.RESULT.status}"));

        Map<String,Object> oversized=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                java.util.Arrays.asList(origin,"config/queries/lookup.sql","config/queries/result.json","config/queries/large.yaml"),16,262144)
                .inspectPackageFileSource("config/queries/large.yaml",null);
        assertEquals(Boolean.FALSE,oversized.get("available"));
        assertEquals("size-limit",oversized.get("reason"));
        Map<String,Object> denied=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144).inspectPackageFileSource("config/queries/lookup.sql",null);
        assertEquals(Boolean.FALSE,denied.get("available"));
        assertEquals("not-allowlisted",denied.get("reason"));
        Map<String,Object> denylisted=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                java.util.Arrays.asList(origin,"config/queries/lookup.sql","config/queries/result.json"),65536,262144,
                false,true,"safe-package-files",Collections.singletonList("**/result.json"),false,
                Collections.<String>emptyList(),Collections.<String>emptyList()).inspectPackageFileSource("config/queries/result.json",null);
        assertEquals(Boolean.FALSE,denylisted.get("available"));assertEquals("forbidden",denylisted.get("reason"));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->inspector.inspectPackageFileSource("../../../outside.yaml",origin));
    }

    @Test void configSourceKeepsHelperConnectionAndEvidenceValuesProtectedWhenDisplayMaskingIsOff() throws Exception {
        Path root = Files.createDirectories(temp.resolve("protected-config-source"));
        Files.createDirectories(root.resolve("config"));
        String yaml = "schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                + "mqhelpers:\n  broker:\n    evidencePayload: 'Authorization: Bearer mq-secret'\n    username: mq-user\n"
                + "sshhelpers:\n  backup:\n    instances:\n      primary:\n        host: worker.internal\n        user: att-user\n        port: 2222\n        identityFile: /home/att/.ssh/id_rsa\n"
                + "httphelpers:\n  api:\n    headers:\n      Authorization: 'Bearer http-secret'\n      Content-Type: application/json\n";
        writeUtf8(root.resolve("config/config.yaml"), yaml);
        PackageResourceInspector inspector = new PackageResourceInspector(root, Paths.get("config/config.yaml"), "SIT",
                Collections.singletonList("config/config.yaml"), 65536, 262144, false, true,
                "safe-package-files", Collections.<String>emptyList(), false,
                Collections.<String>emptyList(), Collections.<String>emptyList());

        Map<String,Object> source = inspector.inspectPackageFileSource("config/config.yaml", null);
        assertEquals(Boolean.TRUE, source.get("available"), source.toString());
        assertEquals(Boolean.TRUE, source.get("redacted"), source.toString());
        String text = String.valueOf(source.get("text"));
        for (String protectedValue : java.util.Arrays.asList("mq-secret",
                "/home/att/.ssh/id_rsa", "http-secret")) assertFalse(text.contains(protectedValue), text);
        assertTrue(text.contains("[REDACTED]"), text);
        for (String visible : java.util.Arrays.asList("mq-user", "worker.internal", "att-user", "2222", "application/json")) assertTrue(text.contains(visible), text);
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
        assertTrue(String.valueOf(items.get(0).get("description")).contains("Bearer [REDACTED]"));
        assertFalse(String.valueOf(items.get(0).get("description")).contains("top-secret"));
        assertFalse(detail.toString().contains("123456789"));assertFalse(detail.toString().contains("violet-123"));
    }

    @Test void doesNotExposeYamlSourceWithoutAnExplicitServerVisibilityPolicy() throws Exception {
        Path root=packageWithTemplate("source-policy", "POLICY", "description: package authored text");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","template",null,null,0,10).get("items");
        assertEquals(Boolean.FALSE,items.get(0).get("sourceAvailable"));
        assertEquals("not-allowlisted",items.get(0).get("sourceReason"));
        Map<String,Object> source=inspector.inspect("source","template",String.valueOf(items.get(0).get("resourceId")),null,0,1);
        assertEquals(Boolean.FALSE,source.get("available"));assertEquals("not-allowlisted",source.get("reason"));
    }

    @Test void exposesSafePackageYamlByDefaultWhileKeepingClassifiedSecretsHidden() throws Exception {
        Path root=packageWithTemplate("safe-package-yaml", "SAFE", "description: ordinary business description");
        writeUtf8(root.resolve("templates/SAFE/template.yaml"),"schemaVersion: att-template/v3.6\nname: SAFE\n"
                +"description: ordinary business description\nactions:\n  note:\n    type: log\n"
                +"    message: 'customer account 123456789; password=never-show-this'\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144,false,true,"safe-package-files",
                Collections.<String>emptyList(),false,Collections.<String>emptyList(),Collections.<String>emptyList());
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","template",null,null,0,10).get("items");
        Map<String,Object> source=inspector.inspect("source","template",String.valueOf(items.get(0).get("resourceId")),null,0,1);
        assertEquals(Boolean.TRUE,source.get("available"),source.toString());
        String text=String.valueOf(source.get("text"));
        assertTrue(text.contains("ordinary business description"));
        assertTrue(text.contains("customer account 123456789"));
        assertFalse(text.contains("never-show-this"));
        assertEquals(Boolean.TRUE,items.get(0).get("sourceAvailable"));
    }

    @Test void resolvesBoundedReferencedSourcesThroughOpaqueCanonicalTargets() throws Exception {
        Path root=packageWithTemplate("referenced-source", "REFS", "description: safe");
        Path payload=Files.createDirectories(root.resolve("payloads")).resolve("request.xml");
        writeUtf8(payload,"<request><accountId>A-1001</accountId><email>private@example.test</email>"
                +"<address>12 Main Street</address><password>payload-secret</password><child>&{nested/child.json}</child></request>");
        Files.createDirectories(root.resolve("nested"));
        writeUtf8(root.resolve("nested/child.json"),"{\"value\":\"${EXEC.INPUT.account}\",\"leaf\":\"&{nested/leaf.xml}\"}");
        writeUtf8(root.resolve("nested/leaf.xml"),"<leaf>safe nested content</leaf>");
        writeUtf8(root.resolve("payloads/請求.json"),"{\"reference\":\"交易-一\",\"phone\":\"555-0100\"}");
        writeUtf8(root.resolve("payloads/customers.csv"),"accountId,email,address\nA-1001,private@example.test,12 Main Street\n");
        writeUtf8(root.resolve("payloads/oversized.txt"),String.join("",Collections.nCopies(65537,"x")));
        Path outside=writeUtf8(temp.resolve("outside-request.xml"),"<request>outside</request>");
        try { Files.createSymbolicLink(root.resolve("payloads/escape.xml"),outside); }
        catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) { }
        writeUtf8(root.resolve("templates/REFS/template.yaml"),"schemaVersion: att-template/v3.6\nname: REFS\ndescription: safe\n"
                +"actions:\n  send:\n    type: log\n    message: '&{payloads/request.xml}'\n"
                +"  unicode:\n    type: log\n    message: '&{payloads/請求.json}'\n"
                +"  csv:\n    type: log\n    message: '&{payloads/customers.csv}'\n"
                +"  oversized:\n    type: log\n    message: '&{payloads/oversized.txt}'\n"
                +"  missing:\n    type: log\n    message: '&{payloads/missing.txt}'\n"
                +"  escaped:\n    type: log\n    message: '&{payloads/escape.xml}'\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144,false,true,"safe-package-files",
                Collections.<String>emptyList(),false,Collections.<String>emptyList(),Collections.<String>emptyList());
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","template",null,null,0,10).get("items");
        String resourceId=String.valueOf(items.get(0).get("resourceId"));
        Map<String,Object> source=inspector.inspect("source","template",resourceId,null,0,1);
        @SuppressWarnings("unchecked") List<Map<String,Object>> files=(List<Map<String,Object>>)source.get("fileReferences");
        assertEquals(3,files.size());assertEquals("payloads/request.xml",files.get(0).get("logicalPath"));
        Map<String,Object> unicodeFile=files.stream().filter(file->"payloads/請求.json".equals(file.get("logicalPath"))).findFirst().orElseThrow(AssertionError::new);
        Map<String,Object> csvFile=files.stream().filter(file->"payloads/customers.csv".equals(file.get("logicalPath"))).findFirst().orElseThrow(AssertionError::new);
        String referenceId=String.valueOf(files.get(0).get("referenceId"));
        assertTrue(referenceId.matches("[0-9a-f]{64}"));
        Map<String,Object> referenced=inspector.inspect("source-file","template",resourceId,referenceId,0,1);
        assertEquals(Boolean.TRUE,referenced.get("available"));
        assertEquals("payloads/request.xml",referenced.get("logicalPath"));
        assertTrue(String.valueOf(referenced.get("text")).contains("A-1001"));
        assertFalse(String.valueOf(referenced.get("text")).contains("payload-secret"));
        assertTrue(String.valueOf(referenced.get("text")).contains("private@example.test"),String.valueOf(referenced.get("text")));
        assertTrue(String.valueOf(referenced.get("text")).contains("12 Main Street"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> nestedReferences=(List<Map<String,Object>>)referenced.get("fileReferences");
        Map<String,Object> childReference=nestedReferences.stream().filter(file->"nested/child.json".equals(file.get("logicalPath"))).findFirst().orElseThrow(AssertionError::new);
        Map<String,Object> child=inspector.inspect("source-file","template",resourceId,String.valueOf(childReference.get("referenceId")),0,1,null,"payloads/request.xml");
        assertEquals("nested/child.json",child.get("logicalPath"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> leafReferences=(List<Map<String,Object>>)child.get("fileReferences");
        Map<String,Object> leafReference=leafReferences.stream().filter(file->"nested/leaf.xml".equals(file.get("logicalPath"))).findFirst().orElseThrow(AssertionError::new);
        Map<String,Object> leaf=inspector.inspect("source-file","template",resourceId,String.valueOf(leafReference.get("referenceId")),0,1,null,"nested/child.json");
        assertEquals("nested/leaf.xml",leaf.get("logicalPath"));
        assertTrue(String.valueOf(leaf.get("text")).contains("safe nested content"));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->inspector.inspect("source-file","template",resourceId,String.valueOf(leafReference.get("referenceId")),0,1,null,"config/config.yaml"));
        Map<String,Object> unicodeReference=inspector.inspect("source-file","template",resourceId,String.valueOf(unicodeFile.get("referenceId")),0,1);
        assertTrue(String.valueOf(unicodeReference.get("text")).contains("交易-一"));
        assertTrue(String.valueOf(unicodeReference.get("text")).contains("555-0100"));
        Map<String,Object> csvReference=inspector.inspect("source-file","template",resourceId,String.valueOf(csvFile.get("referenceId")),0,1);
        assertTrue(String.valueOf(csvReference.get("text")).contains("A-1001"));
        assertTrue(String.valueOf(csvReference.get("text")).contains("private@example.test"));
        assertTrue(String.valueOf(csvReference.get("text")).contains("12 Main Street"));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->inspector.inspect("source-file","template",resourceId,String.join("",Collections.nCopies(64,"0")),0,1));
        String revision=String.valueOf(source.get("revisionDigest"));
        writeUtf8(payload,"<request>changed</request>");
        assertThrows(PackageResourceInspector.StaleResourceCursorException.class,()->inspector.verifyRevision(revision));
    }

    @Test void resolvesOnlyConfiguredHelperOperationsWithoutCreatingToolTargets() throws Exception {
        Path root=packageWithTemplate("helper-inspection", "HELPERS", "description: safe");
        Files.createDirectories(root.resolve("config/tools"));
        writeUtf8(root.resolve("config/mqhelper.yaml"),"schemaVersion: att-mqhelper/v1.2\nid: fromeaimqhk\nname: EAI broker\n"
                +"description: test broker\ndefaults:\n  connection: {queueManager: QM1, host: localhost, port: 1414, channel: DEV}\n"
                +"instances: [{id: primary}]\n");
        writeUtf8(root.resolve("config/dbhelper.yaml"),"schemaVersion: att-dbhelper/v2.6\nid: fppdb\nname: FPP database\n"
                +"description: test database\nconnection: {url: 'jdbc:postgresql://localhost/fpp'}\n");
        writeUtf8(root.resolve("config/tools/fppdb.yaml"),"schemaVersion: att-tool-group/v2.9\nid: fppdb\nname: FPP helpers\n"
                +"description: Configured helper aliases\ntools:\n  findCtTxnBySrcRefNo:\n    name: Find transaction\n"
                +"    description: Resolve a transaction by source reference\n"
                +"    call: \"#{db.fppdb.query(sql='select 1 as TRANSACTION_ID')}\"\n"
                +"    arguments:\n      sourceRefNo: {name: Source reference, description: Source reference number, required: true}\n");
        writeUtf8(root.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments:\n  SIT: {}\n"
                +"dbhelpers: [config/dbhelper.yaml]\nmqhelpers: [config/mqhelper.yaml]\ntoolGroups: [config/tools/fppdb.yaml]\n"
                +"testcase:\n  root: testcase\ntemplates:\n  root: templates\n");
        writeUtf8(root.resolve("templates/HELPERS/template.yaml"),"schemaVersion: att-template/v3.6\nname: HELPERS\ndescription: helper calls\nactions:\n"
                +"  valid:\n    type: tool\n    call: \"#{mq.fromeaimqhk.request(payload='x')}\"\n"
                +"  validAgain:\n    type: tool\n    call: \"#{mq.fromeaimqhk.send(queue='Q', payload='y')}\"\n"
                +"  validConfiguredAlias:\n    type: tool\n    call: \"#{fppdb.findCtTxnBySrcRefNo(sourceRefNo='X')}\"\n"
                +"  invalidMethod:\n    type: tool\n    call: \"#{mq.fromeaimqhk.destroy(payload='x')}\"\n"
                +"  unknownHelper:\n    type: tool\n    call: \"#{mq.unknown.request(payload='x')}\"\n"
                +"  similarAlias:\n    type: tool\n    call: \"#{fppdb.findCtTxnBySrcRefNoExtra(sourceRefNo='X')}\"\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",Collections.<String>emptyList(),65536,262144);
        att.config.FrameworkConfig config=new att.config.FrameworkConfigLoader().load(root.resolve("config/config.yaml"),root,"SIT");
        assertTrue(PackageResourceInspector.configuredHelperCall("mq.fromeaimqhk.request",config));
        assertTrue(PackageResourceInspector.configuredHelperCall("fppdb.findCtTxnBySrcRefNo",config));
        assertFalse(PackageResourceInspector.configuredHelperCall("fppdb.findCtTxnBySrcRefNoExtra",config));
        assertFalse(PackageResourceInspector.configuredHelperCall("mq.fromeaimqhk.destroy",config));
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","template",null,null,0,10).get("items");
        Map<String,Object> detail=inspector.inspect("detail","template",String.valueOf(items.get(0).get("resourceId")),null,0,1);
        @SuppressWarnings("unchecked") List<Map<String,Object>> refs=(List<Map<String,Object>>)((Map<?,?>)detail.get("resource")).get("references");
        Map<String,Object> valid=refs.stream().filter(ref->"mq.fromeaimqhk.request".equals(ref.get("logicalId"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("resolved",valid.get("resolution"));assertEquals("helper",valid.get("targetKind"));assertNull(valid.get("resourceId"));
        @SuppressWarnings("unchecked") Map<String,Object> callSite=(Map<String,Object>)valid.get("callSite");
        assertEquals("valid",callSite.get("actionId"));
        assertEquals(2L,refs.stream().filter(ref->"mq.fromeaimqhk.request".equals(ref.get("logicalId"))||"mq.fromeaimqhk.send".equals(ref.get("logicalId"))).count());
        Map<String,Object> alias=refs.stream().filter(ref->"fppdb.findCtTxnBySrcRefNo".equals(ref.get("logicalId"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("resolved",alias.get("resolution"));assertNotNull(alias.get("resourceId"));
        assertEquals(3L,refs.stream().filter(ref->"unresolved".equals(ref.get("resolution"))).count());
        assertTrue(detail.toString().contains("ATT-RESOURCE-REFERENCE-UNRESOLVED"));
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
        assertEquals(Boolean.FALSE,form.get("debugLocalTestdataOmitted"));
        assertFalse(form.toString().contains("debug-data/debug.yaml"));
        assertFalse(form.toString().contains("debugAccounts"));

        Map<String,Object> draft=inspector.validateQuickLoadInput("template","FORM","virtualUsers",
                mapOf("inputs",mapOf("amount",7),"vars",mapOf("reference","REF001")),
                mapOf("users",3),mapOf(),Collections.singletonList("testdata/load.yaml"));
        assertEquals(Boolean.FALSE,draft.get("debugLocalTestdataOmitted"),"Submitted Load input contains no Debug-local imports");
        assertEquals(Collections.singletonList("testdata/load.yaml"),draft.get("loadTestdata"));
        assertEquals(Collections.singletonList("testdata/load.yaml"),((Map<?,?>)draft.get("normalizedScenario")).get("testdata"));
        assertTrue(String.valueOf(draft.get("previewYaml")).contains("users: 3"));
        assertArrayEquals(original,Files.readAllBytes(sidecar),"Quick Load must not modify the Debug sidecar");
    }

    @Test void quickLoadPolicyProjectionIncludesSafePackageAndBundledTemplateCatalog() throws Exception {
        Path root=packageWithTemplate("quick-load-template-catalog","FORM","description: catalog target");
        Files.createDirectories(root.resolve("load"));
        writeUtf8(root.resolve("load/custom-arrival.yaml"),"schemaVersion: att-load/v1.6\nload: {arrivalRate: '4/s', maxConcurrent: 6, overloadPolicy: drop, duration: 25s}\n");
        writeUtf8(root.resolve("load/custom-users.yml"),"schemaVersion: att-load/v1.6\nload: {users: 3, duration: 35s}\nexecution: {thinkTime: 90ms}\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144,false,true,"safe-package-files",
                Collections.<String>emptyList(),false,Collections.<String>emptyList(),Collections.<String>emptyList());
        Map<String,Object> response=inspector.inspectQuickLoadPolicy("arrivalRate","load/custom-arrival.yaml");
        @SuppressWarnings("unchecked") Map<String,Object> policy=(Map<String,Object>)response.get("policy");
        @SuppressWarnings("unchecked") Map<String,Object> load=(Map<String,Object>)policy.get("load");
        assertEquals("4/s",load.get("arrivalRate"));
        assertEquals("25s",load.get("duration"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> templates=(List<Map<String,Object>>)response.get("templates");
        assertTrue(templates.stream().anyMatch(item->"load/custom-arrival.yaml".equals(item.get("id"))&&"arrivalRate".equals(item.get("model"))));
        assertTrue(templates.stream().anyMatch(item->"load/custom-users.yml".equals(item.get("id"))&&"virtualUsers".equals(item.get("model"))));
        assertTrue(templates.stream().anyMatch(item->"bundled:quick-smoke".equals(item.get("id"))&&Boolean.TRUE.equals(item.get("bundled"))));
        assertEquals(Boolean.FALSE,response.get("templateCatalogTruncated"));
        assertThrows(Exception.class,()->inspector.inspectQuickLoadPolicy("arrivalRate","load/custom-users.yml"));
        assertThrows(IllegalArgumentException.class,()->inspector.inspectQuickLoadPolicy("arrivalRate","../outside.yaml"));
    }

    @Test void quickLoadTargetDiscoveryDoesNotDependOnLoadPolicyOrScenarioValidation() throws Exception {
        Path root=packageWithQuickLoadTargets("quick-load-discovery-isolated");
        Path policy=Files.createDirectories(root.resolve("load")).resolve("load.visualuser.yaml");
        writeUtf8(policy,"schemaVersion: [not, a, valid, policy\n");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144);
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=(List<Map<String,Object>>)inspector.inspect("list","flow",null,null,0,20).get("items");
        Map<String,Object> flow=items.stream().filter(item->"FLOW.v1".equals(item.get("logicalId"))).findFirst().orElseThrow(AssertionError::new);
        Map<String,Object> form=inspector.inspectQuickLoadForm("flow",String.valueOf(flow.get("resourceId")),"virtualUsers",null);
        assertEquals(Map.of("type","flow","id","FLOW.v1"),form.get("target"));
        assertEquals(Boolean.TRUE,form.get("debugLocalTestdataOmitted"));
        assertFalse(form.containsKey("preview"),"Target discovery must not build or validate a Load scenario");
        assertThrows(Exception.class,()->inspector.inspectQuickLoadPolicy("virtualUsers"),"The malformed policy remains a separate Load policy error");
    }

    @Test void quickLoadFormsSupportEveryTargetWithAndWithoutDebugSidecars() throws Exception {
        Path root=packageWithQuickLoadTargets("quick-load-targets");
        for(String type:java.util.Arrays.asList("template","flow","tool")) {
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
        for(String type:java.util.Arrays.asList("template","flow","tool")) {
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

    @Test void advancedLoadRestoresTypedToolInputsAndArgumentsFromTheRedactedSidecar() throws Exception {
        Path root=packageWithQuickLoadTargets("advanced-load-tool-values");
        writeUtf8(root.resolve("config/tools/echo.debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs: {correlationId: CORR-DEFAULT}\narguments: {value: ARG-DEFAULT}\n");
        Map<String,Object> keepInput=mapOf("$attDebugKeepDefault","/inputs/correlationId");
        Map<String,Object> keepArgument=mapOf("$attDebugKeepDefault","/arguments/value");
        Map<String,Object> request=mapOf("schemaVersion","att-load/v1.6","load",mapOf("users",2,"duration","5s"),
                "workloads",java.util.Arrays.asList(mapOf("id","tool","load",mapOf("users",2),
                        "target",mapOf("type","tool","id","echo","arguments",mapOf("value",keepArgument)),
                        "inputs",mapOf("correlationId",keepInput))));

        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",Collections.<String>emptyList(),65536,262144);
        Map<String,Object> result=inspector.validateLoadScenario(request);
        @SuppressWarnings("unchecked") Map<String,Object> normalized=(Map<String,Object>)result.get("normalizedScenario");
        @SuppressWarnings("unchecked") List<Map<String,Object>> workloads=(List<Map<String,Object>>)normalized.get("workloads");
        Map<String,Object> workload=workloads.get(0);
        @SuppressWarnings("unchecked") Map<String,Object> inputs=(Map<String,Object>)workload.get("inputs");
        @SuppressWarnings("unchecked") Map<String,Object> target=(Map<String,Object>)workload.get("target");
        @SuppressWarnings("unchecked") Map<String,Object> arguments=(Map<String,Object>)target.get("arguments");
        assertEquals("CORR-DEFAULT",inputs.get("correlationId"));
        assertEquals("ARG-DEFAULT",arguments.get("value"));
        assertTrue(result.toString().contains("$attDebugKeepDefault"),"The browser preview keeps a path-bound marker instead of exposing the restored values");
        assertFalse(String.valueOf(result.get("preview")).contains("CORR-DEFAULT"));
        assertFalse(String.valueOf(result.get("preview")).contains("ARG-DEFAULT"));
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

    @Test void watcherOverflowForcesACompleteAtomicRebuild() throws Exception {
        Path root=packageWithTemplate("overflow-index","PAYMENT","description: first");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",Collections.<String>emptyList(),65536,262144,true);
        try {
            Map<String,Object> first=inspector.inspect("list","template",null,null,0,1);
            long builds=inspector.indexBuildCount();
            java.lang.reflect.Field field=PackageResourceInspector.class.getDeclaredField("packageWatch");field.setAccessible(true);
            Object watch=field.get(inspector);
            java.nio.file.WatchKey overflow=new java.nio.file.WatchKey() {
                public boolean isValid(){return true;}
                public java.nio.file.Watchable watchable(){return root;}
                public void cancel(){}
                public boolean reset(){return true;}
                public List<java.nio.file.WatchEvent<?>> pollEvents(){return Collections.<java.nio.file.WatchEvent<?>>singletonList(new java.nio.file.WatchEvent<Object>() {
                    public java.nio.file.WatchEvent.Kind<Object> kind(){return java.nio.file.StandardWatchEventKinds.OVERFLOW;}
                    public int count(){return 1;}
                    public Object context(){return null;}
                });}
            };
            java.lang.reflect.Field directories=watch.getClass().getDeclaredField("directories");directories.setAccessible(true);
            java.lang.reflect.Method process=watch.getClass().getDeclaredMethod("process",java.nio.file.WatchKey.class);process.setAccessible(true);
            synchronized(watch){((Map<java.nio.file.WatchKey,Path>)directories.get(watch)).put(overflow,root);process.invoke(watch,overflow);}
            Map<String,Object> rebuilt=inspector.inspect("list","template",null,null,0,1);
            assertEquals(builds+1,inspector.indexBuildCount());
            assertEquals(first.get("revisionDigest"),rebuilt.get("revisionDigest"));
            assertEquals(first.get("items").toString(),rebuilt.get("items").toString());
        } finally {inspector.close();}
    }

    @Test void reusesTheBoundedIndexAndInvalidatesItWhenPackageFilesChange() throws Exception {
        Path root=packageWithTemplate("cached-index", "PAYMENT", "description: first");
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144,true);
        try {
            Map<String,Object> first=inspector.inspect("list","template",null,null,0,1);
            String revision=String.valueOf(first.get("revisionDigest"));
            String resourceId=String.valueOf(((List<Map<String,Object>>)first.get("items")).get(0).get("resourceId"));
            inspector.inspect("detail","template",resourceId,null,0,1);
            inspector.inspect("source","template",resourceId,null,0,1);
            Map<String,Object> debugForm=inspector.inspectDebugForm("template",resourceId,revision);
            @SuppressWarnings("unchecked") Map<String,Object> debugInput=(Map<String,Object>)debugForm.get("input");
            inspector.validateDebugInput("template","PAYMENT",debugInput);
            assertEquals(1,inspector.indexBuildCount(),
                    "page, detail, source, Debug form, and draft validation should share one parsed package index");

            long version=inspector.packageChangeVersion();
            writeUtf8(root.resolve("templates/PAYMENT/template.yaml"),templateYaml("PAYMENT","description: changed"));
            assertThrows(PackageResourceInspector.StaleResourceCursorException.class,
                    ()->inspector.verifyRevision(revision));
            assertTrue(inspector.indexBuildCount()>=2,
                    "authoritative revision checks must rebuild content even when the cached snapshot appears current");
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while(inspector.packageChangeVersion()==version&&System.nanoTime()<deadline)Thread.sleep(20);
            assertNotEquals(version,inspector.packageChangeVersion(),"package file changes should invalidate the watched snapshot");
            assertThrows(PackageResourceInspector.StaleResourceCursorException.class,
                    ()->inspector.inspect("list","template",null,null,1,1,revision));
            assertTrue(inspector.indexBuildCount()>=2,
                    "the cached package index must have been rebuilt before rejecting the stale cursor");
        } finally { inspector.close(); }
    }

    @Test void reusesOneIndexAcrossPagedAndSelectedLookupsForALargePackage() throws Exception {
        Path root=packageWithCaseWorkbook("large-cached-index",500);
        for(int i=1;i<200;i++) {
            String name=String.format(java.util.Locale.ROOT,"TEMPLATE_%03d",i);
            Path descriptor=Files.createDirectories(root.resolve("templates").resolve(name)).resolve("template.yaml");
            writeUtf8(descriptor,templateYaml(name,"description: representative large package"));
        }
        PackageResourceInspector inspector=new PackageResourceInspector(root,Paths.get("config/config.yaml"),"SIT",
                Collections.<String>emptyList(),65536,262144,true);
        try {
            @SuppressWarnings("unchecked") Map<String,Object> first=inspector.inspect("list","template",null,null,0,50);
            @SuppressWarnings("unchecked") Map<String,Object> pageN=inspector.inspect("list","template",null,null,50,50);
            @SuppressWarnings("unchecked") List<Map<String,Object>> templates=(List<Map<String,Object>>)first.get("items");
            String resourceId=String.valueOf(templates.get(0).get("resourceId"));
            inspector.inspect("detail","template",resourceId,null,0,1);
            inspector.inspect("source","template",resourceId,null,0,1);
            @SuppressWarnings("unchecked") Map<String,Object> cases=inspector.inspect("list","case",null,null,450,50);
            assertEquals(200,first.get("total"));
            assertEquals(200,pageN.get("total"));
            assertEquals(500,cases.get("total"));
            assertEquals(50,((List<?>)pageN.get("items")).size());
            assertEquals(50,((List<?>)cases.get("items")).size());
            assertEquals(1,inspector.indexBuildCount(),
                    "first page, page N, detail, source, and case pagination should reuse one large package index");
        } finally { inspector.close(); }
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

    private static Map<String,Object> mapOf(Object... entries) {
        Map<String,Object> values=new LinkedHashMap<>();
        for(int i=0;i<entries.length;i+=2) values.put((String)entries[i],entries[i+1]);
        return values;
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
