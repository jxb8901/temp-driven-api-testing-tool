package att.resource;

import att.TestSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class PackageConfigurationInspectorTest {
    @TempDir Path temp;

    @org.junit.jupiter.api.BeforeEach void enableServerPathPresentation() { att.core.PathPresentation.configure(true, true); }
    @org.junit.jupiter.api.AfterEach void restoreDefaultPathPresentation() { att.core.PathPresentation.configure(false, false); }

    @Test void effectiveAndCompareAreValueFreeDiscoveryNotExecutionValidation() throws Exception {
        Path root = packageRoot("degraded-config");
        write(root.resolve("config/dbhelpers/sit.yaml"), helper("jdbc:postgresql://localhost/orders",
                "${ENV:ORDERS_DB_USERNAME}", "${ENV:ATT_CONFIGURATION_MISSING_SECRET_244}", true));
        PackageConfigurationSnapshots cache = new PackageConfigurationSnapshots();
        PackageConfigurationInspector inspector = inspector(root).withSnapshots(cache, "discovery");
        Map<String,Object> effective = inspector.inspect("effective", "SIT", null);
        assertEquals("degraded", effective.get("state"));
        assertEquals("not-validated", effective.get("executionReadiness"));
        assertTrue(effective.toString().contains("ATT_CONFIGURATION_MISSING_SECRET_244"));
        assertTrue(effective.toString().contains("ORDERS_DB_USERNAME"));
        assertFalse(effective.toString().contains("profile-test"));
        assertFalse(effective.toString().contains(root.toString()));
        assertTrue(cache.get(root, Paths.get("config/config.yaml"), "SIT", "discovery", true).config.discoveryOnly());
        assertThrows(IllegalStateException.class, () -> cache.get(root, Paths.get("config/config.yaml"),
                "SIT", "discovery", true).config.requireExecutionConfig());
        Map<String,Object> compared = inspector.inspect("compare", "SIT", "UAT", "dbhelpers", 0, 50);
        assertEquals("degraded", compared.get("state"));
        assertFalse(((List<?>)compared.get("fields")).isEmpty());
        assertFalse(compared.toString().contains("profile-test"));
        assertEquals("ready", inspector.inspect("effective", "UAT", null).get("state"));
        assertThrows(Exception.class, () -> new att.config.FrameworkConfigLoader().load(
                root.resolve("config/config.yaml"), root, "SIT"));
    }

    @Test void unavailableAndMalformedHelpersDegradeWithoutRemovingUnrelatedMetadata() throws Exception {
        Path root = packageRoot("broken-helper-config");
        Path helper = root.resolve("config/dbhelpers/sit.yaml");
        Files.delete(helper);
        PackageConfigurationSnapshots cache = new PackageConfigurationSnapshots();
        PackageConfigurationInspector inspector = inspector(root).withSnapshots(cache, "repair");
        Map<String,Object> missing = inspector.inspect("effective", "SIT", null);
        assertEquals("degraded", missing.get("state"));
        assertTrue(missing.toString().contains("ATT-RESOURCE-HELPER-UNAVAILABLE"));
        assertFalse(((Map<?,?>)missing.get("globals")).isEmpty());
        PackageConfigurationSnapshots.Snapshot absent = cache.get(root, Paths.get("config/config.yaml"), "SIT", "repair", true);
        assertSame(absent, cache.get(root, Paths.get("config/config.yaml"), "SIT", "repair", true));
        write(helper, "not: [valid\nsecret: do-not-disclose\n");
        Map<String,Object> malformed = inspector.inspect("effective", "SIT", null);
        assertEquals("degraded", malformed.get("state"));
        assertTrue(malformed.toString().contains("ATT-RESOURCE-HELPER-INVALID"));
        assertFalse(malformed.toString().contains("do-not-disclose"));
        assertFalse(malformed.toString().contains(root.toString()));
        write(helper, helper("jdbc:postgresql://localhost/orders", "fixed-user", "fixed-secret", true));
        assertEquals("ready", inspector.inspect("effective", "SIT", null).get("state"));
        assertNotSame(absent, cache.get(root, Paths.get("config/config.yaml"), "SIT", "repair", true));
    }

    @Test void snapshotsReuseParsedProfilesAndReconcileDependencyContentWithoutMtimeHints() throws Exception {
        Path root=packageRoot("cached-profiles");
        PackageConfigurationSnapshots cache=new PackageConfigurationSnapshots();
        Object context="policy-a";
        PackageConfigurationSnapshots.Snapshot sit=cache.get(root,Paths.get("config/config.yaml"),"SIT",context,true);
        assertSame(sit,cache.get(root,Paths.get("config/config.yaml"),"SIT",context,true));
        PackageConfigurationSnapshots.Snapshot defaultProfile=cache.get(root,Paths.get("config/config.yaml"),null,context,true);
        PackageConfigurationSnapshots.Snapshot uat=cache.get(root,Paths.get("config/config.yaml"),"UAT",context,true);
        assertNotSame(sit,uat);
        Path dependency=root.resolve("config/dbhelpers/sit.yaml");
        java.nio.file.attribute.FileTime time=Files.getLastModifiedTime(dependency);
        String original=new String(Files.readAllBytes(dependency),StandardCharsets.UTF_8);
        Files.write(dependency,original.replace("sit-user","new-user").getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(dependency,time);
        PackageConfigurationSnapshots.Snapshot updated=cache.get(root,Paths.get("config/config.yaml"),"SIT",context,true);
        assertNotSame(sit,updated);
        assertNotSame(defaultProfile,cache.get(root,Paths.get("config/config.yaml"),null,context,true));
        assertSame(uat,cache.get(root,Paths.get("config/config.yaml"),"UAT",context,true));
        assertNotSame(updated,cache.get(root,Paths.get("config/config.yaml"),"SIT","policy-b",true));
        Files.move(dependency,dependency.resolveSibling("renamed.yaml"));
        PackageConfigurationSnapshots.Snapshot absent=cache.get(root,Paths.get("config/config.yaml"),"SIT",context,true);
        assertFalse(absent.config.helperDiscoveryFailures().isEmpty());
        Files.move(dependency.resolveSibling("renamed.yaml"),dependency);
        assertNotSame(updated,cache.get(root,Paths.get("config/config.yaml"),"SIT",context,true));
    }

    @Test void sharedRawSnapshotsAlwaysProduceFreshPrivilegeCheckedProjectionsAndRemainBounded() throws Exception {
        Path root=packageRoot("cached-privileges");
        PackageConfigurationSnapshots cache=new PackageConfigurationSnapshots();
        PackageConfigurationInspector admin=new PackageConfigurationInspector(root,Paths.get("config/config.yaml"),262144,true,
                Collections.emptyList(),Collections.emptyList(),true).withSnapshots(cache,"policy");
        PackageConfigurationInspector ordinary=inspector(root).withSnapshots(cache,"policy");
        Map<String,Object> elevated=admin.inspect("effective","SIT",null,"dbhelpers",0,50);
        assertEquals("visible",fieldState(dbFields(elevated).get("url")));
        Map<String,Object> normal=ordinary.inspect("effective","SIT",null,"dbhelpers",0,50);
        assertEquals("hidden",fieldState(dbFields(normal).get("url")));
        assertFalse(normal.toString().contains("sit-secret"));
        assertFalse(elevated.toString().contains("sit-secret"));
        normal.clear();
        assertEquals("ready",ordinary.inspect("effective","SIT",null).get("state"));
        for(int i=0;i<6;i++)cache.get(packageRoot("churn-"+i),Paths.get("config/config.yaml"),null,"policy",false);
        assertEquals(4,cache.size());
        assertTrue(cache.retainedWeight()<=32L*1024*1024);
        cache.clear();assertEquals(0,cache.size());assertEquals(0,cache.retainedWeight());
    }

    @Test void exposesDeclaredProfilesAndEngineResolvedSafeEffectiveConfiguration() throws Exception {
        Path root = packageRoot("safe-config");
        Map<String, Object> declared = inspector(root).inspect("declared", null, null);
        assertEquals("declared", declared.get("state"));
        assertTrue(declared.toString().contains("SIT"));
        assertTrue(declared.toString().contains("overridden"));
        assertFalse(declared.toString().contains("jdbc:postgresql"));
        assertFalse(declared.toString().contains("sit-secret"));

        Map<String, Object> effective = inspector(root).inspect("effective", "SIT", null);
        assertEquals("ready", effective.get("state"));
        assertEquals("SIT", effective.get("environment"));
        String serialized = effective.toString();
        assertTrue(serialized.contains("orders"));
        assertTrue(serialized.contains("config/dbhelpers/sit.yaml"));
        assertTrue(serialized.contains("readOnly"));
        assertTrue(serialized.contains("hidden"));
        assertFalse(serialized.contains("jdbc:postgresql"));
        assertFalse(serialized.contains("sit-secret"));
        assertFalse(serialized.contains("sit-user"));
        assertFalse(serialized.contains(root.toString()));
    }

    @Test void revealsOrdinaryNestedDbFieldsOnlyInAdminProjectionAndKeepsSecretsRedacted() throws Exception {
        Path root = packageRoot("admin-db-fields");
        PackageConfigurationInspector ordinary = inspector(root);
        Map<String,Object> standard = ordinary.inspect("effective", "SIT", null, "dbhelpers", 0, 50);
        Map<String,Object> standardFields = dbFields(standard);
        assertEquals("hidden", fieldState(standardFields.get("evidence.sql")));
        assertEquals("hidden", fieldState(standardFields.get("properties")));

        PackageConfigurationInspector admin = new PackageConfigurationInspector(root, Paths.get("config/config.yaml"),
                262144, true, Collections.emptyList(), Collections.emptyList(), true);
        Map<String,Object> expanded = admin.inspect("effective", "SIT", null, "dbhelpers", 0, 50);
        Map<String,Object> expandedFields = dbFields(expanded);
        assertEquals("visible", fieldState(expandedFields.get("properties")), expanded.toString());
        assertEquals("visible", fieldState(expandedFields.get("url")), expanded.toString());
        @SuppressWarnings("unchecked") Map<String,Object> properties=(Map<String,Object>)((Map<?,?>)expandedFields.get("properties")).get("value");
        assertEquals("require", properties.get("sslMode"));
        assertEquals("[REDACTED]", properties.get("password"));
        assertEquals("visible", fieldState(expandedFields.get("evidence.sql")));
        assertEquals("visible", fieldState(expandedFields.get("evidence.parameters")));
        assertFalse(expanded.toString().contains("property-secret"));
        assertEquals("visible", fieldState(expandedFields.get("username")));
        assertEquals("hidden", fieldState(expandedFields.get("password")));

        Map<String,Object> comparison=admin.inspect("compare","SIT","UAT","dbhelpers",0,100);
        @SuppressWarnings("unchecked") List<Map<String,Object>> compared=(List<Map<String,Object>>)comparison.get("fields");
        Map<String,Object> evidence=compared.stream().filter(item->"dbhelpers.orders.evidence.sql".equals(item.get("path"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("visible",fieldState(evidence.get("left")),comparison.toString());
        assertEquals("visible",fieldState(evidence.get("right")),comparison.toString());
        Map<String,Object> username=compared.stream().filter(item->"dbhelpers.orders.username".equals(item.get("path"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("changed",username.get("change"),comparison.toString());
        assertTrue(username.toString().contains("sit-user"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> dbFields(Map<String,Object> response) {
        List<Map<String,Object>> sections=(List<Map<String,Object>>)response.get("sections");
        Map<String,Object> section=sections.stream().filter(item->"dbhelpers".equals(item.get("id"))).findFirst().orElseThrow(AssertionError::new);
        List<Map<String,Object>> entries=(List<Map<String,Object>>)section.get("entries");
        return (Map<String,Object>)entries.get(0).get("fields");
    }

    private static String fieldState(Object field) { return String.valueOf(((Map<?,?>)field).get("state")); }

    @Test void comparesOnlyVisibleValuesAndNeverReportsHiddenEqualityOrChanges() throws Exception {
        Path root = packageRoot("compare-config");
        Map<String, Object> comparison = inspector(root).inspect("compare", "SIT", "UAT", "dbhelpers", 0, 100);
        assertEquals("ready", comparison.get("state"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> fields = (List<Map<String, Object>>) comparison.get("fields");
        Map<String, Object> visible = fields.stream().filter(item -> "dbhelpers.orders.readOnly".equals(item.get("path"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("changed", visible.get("change"));
        Map<String, Object> hidden = fields.stream().filter(item -> "dbhelpers.orders.url".equals(item.get("path"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("hidden", hidden.get("change"));
        assertTrue(hidden.toString().contains("[HIDDEN]"));
        assertTrue(hidden.toString().contains("UNAVAILABLE_BY_POLICY"));
        String serialized = comparison.toString();
        assertFalse(serialized.contains("jdbc:postgresql"));
        assertFalse(serialized.contains("sit-secret"));
        assertFalse(serialized.contains("uat-secret"));
    }

    @Test void compareDefaultsToProfileScopedFieldsAndCanIncludeInheritedGlobalValues() throws Exception {
        Path root=packageRoot("compare-profile-scope");
        Map<String,Object> helperScoped=inspector(root).inspect("compare","SIT","UAT","dbhelpers",0,0,false);
        @SuppressWarnings("unchecked") List<Map<String,Object>> helperFields=(List<Map<String,Object>>)helperScoped.get("fields");
        assertTrue(helperFields.stream().anyMatch(item->"dbhelpers.orders.readOnly".equals(item.get("path"))));
        assertTrue(helperFields.stream().allMatch(item->String.valueOf(item.get("path")).startsWith("dbhelpers.")));

        Map<String,Object> globalScoped=inspector(root).inspect("compare","SIT","UAT","globals",0,0,false);
        @SuppressWarnings("unchecked") List<Map<String,Object>> scopedGlobals=(List<Map<String,Object>>)globalScoped.get("fields");
        assertFalse(scopedGlobals.stream().anyMatch(item->"globals.timeoutMs".equals(item.get("path"))));

        Map<String,Object> expanded=inspector(root).inspect("compare","SIT","UAT","globals",0,0,true);
        @SuppressWarnings("unchecked") List<Map<String,Object>> expandedFields=(List<Map<String,Object>>)expanded.get("fields");
        assertTrue(expandedFields.stream().anyMatch(item->"globals.timeoutMs".equals(item.get("path"))));
    }

    @Test void compareHidesUnchangedDescriptorsSharedByBothEnvironmentsByDefault() throws Exception {
        Path root=packageRoot("compare-shared-descriptor");
        write(root.resolve("config/dbhelpers/shared.yaml"),helper("jdbc:postgresql://shared.internal/orders","shared-user","shared-secret",false)
                .replace("id: orders","id: shared"));
        write(root.resolve("config/dbhelpers/sit-only.yaml"),helper("jdbc:postgresql://sit.internal/orders","sit-user","sit-secret",false)
                .replace("id: orders","id: sitOnly"));
        write(root.resolve("config/dbhelpers/uat-only.yaml"),helper("jdbc:postgresql://uat.internal/orders","uat-user","uat-secret",true)
                .replace("id: orders","id: uatOnly"));
        write(root.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"templates:\n  root: templates\ntestcase:\n  root: testcase\n"
                +"environments:\n  SIT:\n    dbhelpers: [config/dbhelpers/shared.yaml, config/dbhelpers/sit-only.yaml]\n"
                +"  UAT:\n    dbhelpers: [config/dbhelpers/shared.yaml, config/dbhelpers/uat-only.yaml]\n");

        PackageConfigurationInspector inspector=inspector(root);
        Map<String,Object> scoped=inspector.inspect("compare","SIT","UAT","dbhelpers",0,100,false);
        @SuppressWarnings("unchecked") List<Map<String,Object>> scopedFields=(List<Map<String,Object>>)scoped.get("fields");
        assertTrue(scopedFields.stream().noneMatch(item->"dbhelpers.shared.readOnly".equals(item.get("path"))),
                "An unchanged descriptor selected from the same package file in both profiles is shared, not an environment difference.");
        assertTrue(scopedFields.stream().anyMatch(item->"dbhelpers.sitOnly.readOnly".equals(item.get("path"))));
        assertTrue(scopedFields.stream().anyMatch(item->"dbhelpers.uatOnly.readOnly".equals(item.get("path"))));

        Map<String,Object> expanded=inspector.inspect("compare","SIT","UAT","dbhelpers",0,100,true);
        @SuppressWarnings("unchecked") List<Map<String,Object>> expandedFields=(List<Map<String,Object>>)expanded.get("fields");
        Map<String,Object> shared=expandedFields.stream().filter(item->"dbhelpers.shared.readOnly".equals(item.get("path")))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("config/dbhelpers/shared.yaml",shared.get("source"),
                "The expanded comparison identifies the actual shared descriptor file.");
    }

    @Test void supportsRootOnlyEffectiveConfigurationAndReportsSelectedProfileOrigin() throws Exception {
        Path root=packageRoot("root-only-config");
        write(root.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: LOCAL\ntimeoutMs: 5000\ntemplates:\n  root: templates\ntestcase:\n  root: testcase\n");
        Map<String,Object> effective=inspector(root).inspect("effective",null,null);
        assertEquals("ready",effective.get("state"));assertEquals("LOCAL",effective.get("environment"));
        @SuppressWarnings("unchecked") Map<String,Map<String,Object>> globals=(Map<String,Map<String,Object>>)effective.get("globals");
        assertEquals("global",globals.get("environment").get("origin"));

        Path profiled=packageRoot("profile-origin");
        Map<String,Object> selected=inspector(profiled).inspect("effective","UAT",null);
        @SuppressWarnings("unchecked") Map<String,Map<String,Object>> selectedGlobals=(Map<String,Map<String,Object>>)selected.get("globals");
        assertEquals("profile",selectedGlobals.get("environment").get("origin"));
    }

    @Test void pagesLargeEffectiveSectionsAndComparisonFields() throws Exception {
        Path root=packageRoot("paged-config");
        write(root.resolve("config/dbhelpers/sit-extra.yaml"),helper("jdbc:postgresql://sit.internal/extra","sit-extra","secret",false).replace("id: orders", "id: extra"));
        write(root.resolve("config/config.yaml"),baseConfig("config/dbhelpers/sit.yaml, config/dbhelpers/sit-extra.yaml","config/dbhelpers/uat.yaml"));

        Map<String,Object> first=inspector(root).inspect("effective","SIT",null,"dbhelpers",0,1);
        assertEquals(2,first.get("total"));assertEquals(1,first.get("nextOffset"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> firstSections=(List<Map<String,Object>>)first.get("sections");
        @SuppressWarnings("unchecked") List<Map<String,Object>> firstEntries=(List<Map<String,Object>>)firstSections.get(0).get("entries");
        assertEquals(1,firstEntries.size());

        Map<String,Object> second=inspector(root).inspect("effective","SIT",null,"dbhelpers",1,1);
        @SuppressWarnings("unchecked") List<Map<String,Object>> secondSections=(List<Map<String,Object>>)second.get("sections");
        @SuppressWarnings("unchecked") List<Map<String,Object>> secondEntries=(List<Map<String,Object>>)secondSections.get(0).get("entries");
        assertEquals(1,secondEntries.size());assertNull(second.get("nextOffset"));

        Map<String,Object> comparison=inspector(root).inspect("compare","SIT","UAT","dbhelpers",0,1);
        @SuppressWarnings("unchecked") List<Map<String,Object>> fields=(List<Map<String,Object>>)comparison.get("fields");
        assertEquals(1,fields.size());assertTrue(((Number)comparison.get("total")).intValue()>1);
        assertNotNull(comparison.get("nextOffset"));
    }

    @Test void filtersMixedConfigurationSectionsBeforePagingMoreThanFiftyComparedFields() throws Exception {
        Path root=packageRoot("mixed-section-compare");
        StringBuilder sitPaths=new StringBuilder();StringBuilder uatPaths=new StringBuilder();
        for(int i=0;i<8;i++) {
            String id="orders"+i;
            String sitPath="config/dbhelpers/sit-"+id+".yaml";
            String uatPath="config/dbhelpers/uat-"+id+".yaml";
            write(root.resolve(sitPath),helper("jdbc:postgresql://sit.internal/"+id,"sit-user","sit-secret",false).replace("id: orders","id: "+id));
            write(root.resolve(uatPath),helper("jdbc:postgresql://uat.internal/"+id,"uat-user","uat-secret",true).replace("id: orders","id: "+id));
            if(i>0){sitPaths.append(", ");uatPaths.append(", ");}
            sitPaths.append(sitPath);uatPaths.append(uatPath);
        }
        Files.createDirectories(root.resolve("config"));
        write(root.resolve("config/mqhelper.yaml"),"schemaVersion: att-mqhelper/v1.2\nid: mste\nname: SIT MQ\n"
                +"description: test MQ\ndefaults:\n  connection: {queueManager: QM1, host: mq.sit.internal, port: 1414, channel: DEV}\n"
                +"instances: [{id: primary}]\n");
        String config="schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"templates:\n  root: templates\ntestcase:\n  root: testcase\n"
                +"environments:\n  SIT:\n    dbhelpers: ["+sitPaths+"]\n    mqhelpers: [config/mqhelper.yaml]\n"
                +"  UAT:\n    dbhelpers: ["+uatPaths+"]\n";
        write(root.resolve("config/config.yaml"),config);

        PackageConfigurationInspector inspector=inspector(root);
        Map<String,Object> first=inspector.inspect("compare","SIT","UAT","dbhelpers",0,50);
        @SuppressWarnings("unchecked") List<Map<String,Object>> firstFields=(List<Map<String,Object>>)first.get("fields");
        assertEquals(50,firstFields.size());assertEquals(50,first.get("nextOffset"));
        int total=((Number)first.get("total")).intValue();assertTrue(total>50);
        for(int offset=0;offset<total;offset+=50) {
            Map<String,Object> page=inspector.inspect("compare","SIT","UAT","dbhelpers",offset,50);
            @SuppressWarnings("unchecked") List<Map<String,Object>> fields=(List<Map<String,Object>>)page.get("fields");
            assertEquals("dbhelpers",page.get("section"));
            assertTrue(fields.stream().allMatch(field->String.valueOf(field.get("path")).startsWith("dbhelpers.")));
            if(offset+fields.size()<total)assertEquals(offset+fields.size(),page.get("nextOffset"));
            else assertNull(page.get("nextOffset"));
        }
    }

    @Test void pagesAConfigurationWhoseUnpagedResponseExceedsTheResponseLimit() throws Exception {
        Path root=packageRoot("many-helper-config");
        StringBuilder paths=new StringBuilder();
        for(int i=0;i<100;i++) {
            String id="db"+i;String path="config/dbhelpers/"+id+".yaml";
            write(root.resolve(path),helper("jdbc:postgresql://sit.internal/"+id,id,"secret-"+i,false).replace("id: orders","id: "+id));
            if(i>0)paths.append(", ");paths.append(path);
        }
        write(root.resolve("config/config.yaml"),baseConfig(paths.toString(),"config/dbhelpers/uat.yaml"));
        PackageConfigurationInspector bounded=new PackageConfigurationInspector(root,Paths.get("config/config.yaml"),16384);
        assertThrows(PackageConfigurationInspector.ResponseTooLargeException.class,
                ()->bounded.inspect("effective","SIT",null));
        Map<String,Object> page=bounded.inspect("effective","SIT",null,"dbhelpers",0,5);
        assertEquals("ready",page.get("state"));assertEquals(100,page.get("total"));assertEquals(5,page.get("limit"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> sections=(List<Map<String,Object>>)page.get("sections");
        @SuppressWarnings("unchecked") List<Map<String,Object>> entries=(List<Map<String,Object>>)sections.get(0).get("entries");
        assertEquals(5,entries.size());
        Map<String,Object> compared=bounded.inspect("compare","SIT","UAT","dbhelpers",0,5);
        @SuppressWarnings("unchecked") List<Map<String,Object>> comparedFields=(List<Map<String,Object>>)compared.get("fields");
        assertEquals(5,comparedFields.size());assertTrue(((Number)compared.get("total")).intValue()>50);
        assertTrue(comparedFields.stream().allMatch(field -> String.valueOf(field.get("path")).startsWith("dbhelpers.")));
        assertThrows(IllegalArgumentException.class,()->bounded.inspect("compare","SIT","UAT","dbhelpersX",0,5));
    }

    @Test void pagesTestdataPreviewAndKeepsClassifiedFieldsProtectedWhenMaskingIsOff() throws Exception {
        Path root=packageRoot("testdata-preview");
        Files.createDirectories(root.resolve("testdata"));
        write(root.resolve("testdata/customers.yaml"),"schemaVersion: att-testdata/v1.0\nid: customers\nrecords:\n"
                +"  - accountId: A-1001\n    status: ACTIVE\n    email: customer@example.test\n    address: '12 Main Street'\n"
                +"    host: sit.internal\n    port: 1414\n    url: 'https://service.example/api'\n    ordinary: visible-value\n"
                +"    note: 'Bearer abc123; password=inline-secret'\n"
                +"  - accountId: A-1002\n    status: CLOSED\n    email: other@example.test\nselection:\n  strategy: sequential\n");
        String configured=baseConfig("config/dbhelpers/sit.yaml","config/dbhelpers/uat.yaml")
                .replace("  SIT:\n    dbhelpers:","  SIT:\n    testdata: [testdata/customers.yaml]\n    dbhelpers:");
        write(root.resolve("config/config.yaml"),configured);

        PackageConfigurationInspector visible=new PackageConfigurationInspector(root,Paths.get("config/config.yaml"),262144,
                false,Collections.emptyList(),Collections.emptyList());
        Map<String,Object> page=visible.inspect("testdata-records","SIT",null,"customers",0,1);
        assertEquals(Boolean.FALSE,page.get("redactionEnabled"));assertEquals(2L,page.get("total"));assertEquals(1,page.get("nextOffset"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> records=(List<Map<String,Object>>)page.get("records");
        @SuppressWarnings("unchecked") Map<String,Object> value=(Map<String,Object>)records.get(0).get("value");
        assertEquals("A-1001",value.get("accountId"));assertEquals("ACTIVE",value.get("status"));
        assertEquals("sit.internal",value.get("host"));assertEquals(1414,value.get("port"));
        assertEquals("https://service.example/api",value.get("url"));assertEquals("visible-value",value.get("ordinary"));
        assertEquals("12 Main Street",value.get("address"));
        assertEquals("customer@example.test",value.get("email"));
        assertFalse(page.toString().contains("abc123"));assertFalse(page.toString().contains("inline-secret"));
        assertEquals(Boolean.TRUE,records.get(0).get("redacted"));

        PackageConfigurationInspector masked=new PackageConfigurationInspector(root,Paths.get("config/config.yaml"),262144,
                true,Collections.emptyList(),Collections.singletonList("accountId"));
        Map<String,Object> maskedPage=masked.inspect("testdata-records","SIT",null,"customers",1,1);
        @SuppressWarnings("unchecked") List<Map<String,Object>> maskedRecords=(List<Map<String,Object>>)maskedPage.get("records");
        @SuppressWarnings("unchecked") Map<String,Object> maskedValue=(Map<String,Object>)maskedRecords.get(0).get("value");
        assertEquals("[REDACTED]",maskedValue.get("accountId"));assertEquals("CLOSED",maskedValue.get("status"));
        assertNull(maskedPage.get("nextOffset"));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->visible.inspect("testdata-records","SIT",null,"missing",0,1));
    }

    @Test void filtersAndPaginatesTheTestdataCatalogBySafeDescriptorIdentity() throws Exception {
        Path root=packageRoot("testdata-search");
        Files.createDirectories(root.resolve("testdata"));
        write(root.resolve("testdata/customers.yaml"),"schemaVersion: att-testdata/v1.0\nid: customers\nrecords:\n"
                +"  - accountId: A-1001\n    note: do-not-search\nselection:\n  strategy: sequential\n");
        String configured=baseConfig("config/dbhelpers/sit.yaml","config/dbhelpers/uat.yaml")
                .replace("  SIT:\n    dbhelpers:","  SIT:\n    testdata: [testdata/customers.yaml]\n    dbhelpers:");
        write(root.resolve("config/config.yaml"),configured);
        PackageConfigurationInspector inspector=inspector(root);
        Map<String,Object> bySource=inspector.inspect("effective","SIT",null,"testdata",0,1,false,false,"CUSTOMERS.YAML");
        assertEquals(1,bySource.get("total"),bySource.toString());
        @SuppressWarnings("unchecked") List<Map<String,Object>> sections=(List<Map<String,Object>>)bySource.get("sections");
        @SuppressWarnings("unchecked") List<Map<String,Object>> entries=(List<Map<String,Object>>)sections.stream()
                .filter(section->"testdata".equals(section.get("id"))).findFirst().orElseThrow(AssertionError::new).get("entries");
        assertEquals("customers",entries.get(0).get("id"));

        Map<String,Object> secretQuery=inspector.inspect("effective","SIT",null,"testdata",0,1,false,false,"do-not-search");
        assertEquals(0,secretQuery.get("total"));
        assertNull(secretQuery.get("nextOffset"));
    }

    @Test void generatedTestdataRequiresExplicitPreviewAndUsesTheSharedRedactionPolicy() throws Exception {
        Path root=packageRoot("generated-testdata-preview");
        Files.createDirectories(root.resolve("testdata"));
        String configured=baseConfig("config/dbhelpers/sit.yaml","config/dbhelpers/uat.yaml")
                .replace("  SIT:\n    dbhelpers:","  SIT:\n    testdata: [testdata/generated.yaml]\n    dbhelpers:");
        write(root.resolve("config/config.yaml"),configured);
        write(root.resolve("testdata/generated.yaml"),"schemaVersion: att-testdata/v1.0\nid: generatedAccounts\n"
                +"records:\n  generate:\n    seq:\n      from: 1000\n      to: 1120\n      format: \"%06d\"\n"
                +"  record:\n    id: \"A-%{seq}\"\n    status: ACTIVE\n    email: customer@example.test\n"
                +"selection:\n  strategy: sequential\n  exhaustion: stop\n");
        PackageConfigurationInspector inspector=inspector(root);

        Map<String,Object> required=inspector.inspect("testdata-records","SIT",null,"generatedAccounts",0,50,false,false);
        assertEquals("preview-required",required.get("state"));
        assertEquals(Boolean.TRUE,required.get("generated"));
        assertEquals(Boolean.TRUE,required.get("previewRequired"));
        assertEquals(121L,required.get("recordCount"));
        assertEquals("testdata/generated.yaml",required.get("source"));
        assertTrue(((List<?>)required.get("records")).isEmpty());
        assertFalse(required.toString().contains("customer@example.test"));

        Map<String,Object> page=inspector.inspect("testdata-records","SIT",null,"generatedAccounts",0,50,false,true);
        assertEquals("ready",page.get("state"));
        assertEquals(Boolean.TRUE,page.get("previewGenerated"));
        assertEquals(50,page.get("nextOffset"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> records=(List<Map<String,Object>>)page.get("records");
        assertEquals(50,records.size());
        assertEquals(1000L,records.get(0).get("sequence"));
        @SuppressWarnings("unchecked") Map<String,Object> value=(Map<String,Object>)records.get(0).get("value");
        assertEquals("A-001000",value.get("id"));
        assertEquals("[REDACTED]",value.get("email"));
        assertFalse(page.toString().contains("customer@example.test"));
        assertThrows(IllegalArgumentException.class,
                ()->inspector.inspect("testdata-records","SIT",null,"generatedAccounts",0,101,false,true));
    }

    @Test void maskingOffShowsOperationalConnectionValuesButKeepsCredentialFieldsHidden() throws Exception {
        Path root=packageRoot("visible-operational-config");
        Files.createDirectories(root.resolve("config"));
        write(root.resolve("config/mqhelper.yaml"),"schemaVersion: att-mqhelper/v1.2\nid: mste\nname: SIT MQ\n"
                +"description: test MQ\ndefaults:\n  connection: {queueManager: QM1, host: mq.sit.internal, port: 1414, channel: DEV}\n"
                +"instances: [{id: primary}]\n");
        write(root.resolve("config/config.yaml"),baseConfig("config/dbhelpers/sit.yaml","config/dbhelpers/uat.yaml")
                .replace("  SIT:\n    dbhelpers:","  SIT:\n    mqhelpers: [config/mqhelper.yaml]\n    dbhelpers:"));
        PackageConfigurationInspector visible=new PackageConfigurationInspector(root,Paths.get("config/config.yaml"),262144,
                false,Collections.emptyList(),Collections.emptyList());
        Map<String,Object> effective=visible.inspect("effective","SIT",null);
        @SuppressWarnings("unchecked") List<Map<String,Object>> sections=(List<Map<String,Object>>)effective.get("sections");
        @SuppressWarnings("unchecked") Map<String,Object> dbSection=sections.stream().filter(s->"dbhelpers".equals(s.get("id"))).findFirst().get();
        @SuppressWarnings("unchecked") List<Map<String,Object>> dbEntries=(List<Map<String,Object>>)dbSection.get("entries");
        Map<String,Object> dbEntry=dbEntries.get(0);
        @SuppressWarnings("unchecked") Map<String,Map<String,Object>> dbFields=(Map<String,Map<String,Object>>)dbEntry.get("fields");
        assertEquals("visible",dbFields.get("url").get("state"));
        assertEquals("jdbc:postgresql://sit.internal/orders",dbFields.get("url").get("value"));
        assertEquals("hidden",dbFields.get("password").get("state"));
        @SuppressWarnings("unchecked") Map<String,Object> mqSection=sections.stream().filter(s->"mqhelpers".equals(s.get("id"))).findFirst().get();
        @SuppressWarnings("unchecked") Map<String,Object> mqEntry=((List<Map<String,Object>>)mqSection.get("entries")).get(0);
        @SuppressWarnings("unchecked") Map<String,Map<String,Object>> mqFields=(Map<String,Map<String,Object>>)mqEntry.get("fields");
        assertEquals("mq.sit.internal",mqFields.get("host").get("value"));
        assertEquals(1414,mqFields.get("port").get("value"));
        assertEquals("hidden",mqFields.get("password").get("state"));
        Map<String,Object> comparison=visible.inspect("compare","SIT","UAT","dbhelpers",0,100);
        @SuppressWarnings("unchecked") List<Map<String,Object>> compared=(List<Map<String,Object>>)comparison.get("fields");
        Map<String,Object> comparedUrl=compared.stream().filter(f->String.valueOf(f.get("path")).endsWith(".url")).findFirst().orElseThrow(AssertionError::new);
        @SuppressWarnings("unchecked") Map<String,Object> leftUrl=(Map<String,Object>)comparedUrl.get("left");
        assertEquals("visible",leftUrl.get("state"));assertEquals("jdbc:postgresql://sit.internal/orders",leftUrl.get("value"));
        Map<String,Object> comparedPassword=compared.stream().filter(f->String.valueOf(f.get("path")).endsWith(".password")).findFirst().orElseThrow(AssertionError::new);
        assertEquals("hidden",((Map<?,?>)comparedPassword.get("left")).get("state"));
    }

    @Test void returnsOnlyStableDiagnosticsForInvalidProfilesAndEscapingDescriptors() throws Exception {
        Path root = packageRoot("invalid-config");
        Map<String, Object> invalidEnvironment = inspector(root).inspect("effective", "MISSING", null);
        assertEquals("invalid", invalidEnvironment.get("state"));
        assertFalse(invalidEnvironment.toString().contains(root.toString()));

        write(root.resolve("config/config.yaml"), baseConfig("../../outside.yaml", "../../outside.yaml"));
        Map<String, Object> invalidPath = inspector(root).inspect("effective", "SIT", null);
        assertEquals("invalid", invalidPath.get("state"));
        assertTrue(invalidPath.toString().contains("ATT-CONFIG-INVALID"));
        assertFalse(invalidPath.toString().contains("outside.yaml"));
        assertFalse(invalidPath.toString().contains(root.toString()));
    }

    @Test void stopsBeforeOpeningDescriptorBeyondTheConfiguredFileLimit() throws Exception {
        Path root = packageRoot("descriptor-limit");
        StringBuilder config = new StringBuilder("schemaVersion: att-config/v2.12\nenvironment: SIT\ntoolGroups:\n");
        for (int i = 0; i < 512; i++) {
            String name = "config/group-" + i + ".yaml";
            Files.createFile(root.resolve(name));
            config.append("  - ").append(name).append('\n');
        }
        config.append("  - config/must-not-be-opened.yaml\n");
        write(root.resolve("config/config.yaml"), config.toString());
        assertThrows(PackageConfigurationInspector.ConfigurationLimitException.class,
                () -> inspector(root).inspect("effective", "SIT", null));
    }

    private PackageConfigurationInspector inspector(Path root) {
        return new PackageConfigurationInspector(root, Paths.get("config/config.yaml"), 262144);
    }

    private Path packageRoot(String name) throws Exception {
        Path root = Files.createDirectories(temp.resolve(name));
        Files.createDirectories(root.resolve("config/dbhelpers"));
        Files.createDirectories(root.resolve("templates"));
        Files.createDirectories(root.resolve("testcase"));
        TestSchemas.install(root);
        write(root.resolve("config/config.yaml"), baseConfig("config/dbhelpers/sit.yaml", "config/dbhelpers/uat.yaml"));
        write(root.resolve("config/dbhelpers/sit.yaml"), helper("jdbc:postgresql://sit.internal/orders", "sit-user", "sit-secret", false));
        write(root.resolve("config/dbhelpers/uat.yaml"), helper("jdbc:postgresql://uat.internal/orders", "uat-user", "uat-secret", true));
        return root;
    }

    private static String baseConfig(String sit, String uat) {
        return "schemaVersion: att-config/v2.12\n"
                + "environment: SIT\n"
                + "timeoutMs: 5000\n"
                + "templates:\n  root: templates\n"
                + "testcase:\n  root: testcase\n"
                + "environments:\n"
                + "  SIT:\n    dbhelpers: [" + sit + "]\n"
                + "  UAT:\n    dbhelpers: [" + uat + "]\n";
    }

    private static String helper(String url, String username, String password, boolean readOnly) {
        return "schemaVersion: att-dbhelper/v2.6\n"
                + "id: orders\nname: Orders database\ndescription: profile helper\n"
                + "connection:\n  url: '" + url + "'\n  username: '" + username + "'\n  password: '" + password + "'\n"
                + "  driverClass: org.postgresql.Driver\n  readOnly: " + readOnly + "\n  isolation: readCommitted\n"
                + "  properties: {sslMode: require, password: property-secret}\n"
                + "statement:\n  timeoutSeconds: 30\ntransaction:\n  scope: case\n  onEnd: rollback\n"
                + "evidence:\n  sql: full\n  parameters: masked\n";
    }

    private static Path write(Path file, String value) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, value.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
