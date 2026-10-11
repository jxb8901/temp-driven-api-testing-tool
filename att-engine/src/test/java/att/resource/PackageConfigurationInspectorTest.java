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

import static org.junit.jupiter.api.Assertions.*;

class PackageConfigurationInspectorTest {
    @TempDir Path temp;

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

    @Test void comparesOnlyVisibleValuesAndNeverReportsHiddenEqualityOrChanges() throws Exception {
        Path root = packageRoot("compare-config");
        Map<String, Object> comparison = inspector(root).inspect("compare", "SIT", "UAT");
        assertEquals("ready", comparison.get("state"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> fields = (List<Map<String, Object>>) comparison.get("fields");
        Map<String, Object> visible = fields.stream().filter(item -> "dbhelpers.orders.readOnly".equals(item.get("path"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("changed", visible.get("change"));
        Map<String, Object> hidden = fields.stream().filter(item -> "dbhelpers.orders.url".equals(item.get("path"))).findFirst().orElseThrow(AssertionError::new);
        assertEquals("hidden", hidden.get("change"));
        assertFalse(hidden.toString().contains("value"));
        String serialized = comparison.toString();
        assertFalse(serialized.contains("jdbc:postgresql"));
        assertFalse(serialized.contains("sit-secret"));
        assertFalse(serialized.contains("uat-secret"));
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

        Map<String,Object> comparison=inspector(root).inspect("compare","SIT","UAT",null,0,1);
        @SuppressWarnings("unchecked") List<Map<String,Object>> fields=(List<Map<String,Object>>)comparison.get("fields");
        assertEquals(1,fields.size());assertTrue(((Number)comparison.get("total")).intValue()>1);
        assertNotNull(comparison.get("nextOffset"));
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
        PackageConfigurationInspector bounded=new PackageConfigurationInspector(root,Paths.get("config/config.yaml"),8192);
        assertThrows(PackageConfigurationInspector.ResponseTooLargeException.class,
                ()->bounded.inspect("effective","SIT",null));
        Map<String,Object> page=bounded.inspect("effective","SIT",null,"dbhelpers",0,5);
        assertEquals("ready",page.get("state"));assertEquals(100,page.get("total"));assertEquals(5,page.get("limit"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> sections=(List<Map<String,Object>>)page.get("sections");
        @SuppressWarnings("unchecked") List<Map<String,Object>> entries=(List<Map<String,Object>>)sections.get(0).get("entries");
        assertEquals(5,entries.size());
        Map<String,Object> compared=bounded.inspect("compare","SIT","UAT",null,0,5);
        @SuppressWarnings("unchecked") List<Map<String,Object>> comparedFields=(List<Map<String,Object>>)compared.get("fields");
        assertEquals(5,comparedFields.size());assertTrue(((Number)compared.get("total")).intValue()>5);
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
                + "statement:\n  timeoutSeconds: 30\ntransaction:\n  scope: case\n  onEnd: rollback\n";
    }

    private static Path write(Path file, String value) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, value.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
