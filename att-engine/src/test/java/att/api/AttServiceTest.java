package att.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class AttServiceTest {
    @TempDir Path temp;
    @Test void validationIsInvokableThroughTypedRequestWithoutCliArgumentsOrConsoleOutput() throws Exception {
        Path root=temp.resolve("package"); Files.createDirectories(root.resolve("config")); Files.createDirectories(root.resolve("templates"));
        Files.createDirectories(root.resolve("testcase")); Files.createDirectories(root.resolve("tools"));
        att.TestSchemas.install(root);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.11\n"
                + "outputDirectory: output\nenvironment: SIT\ntimeoutMs: 10000\n"
                + "templates: {root: templates}\ntestcase: {root: testcase}\ntools: {}\n").getBytes("UTF-8"));
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

    @Test void debugResultRetainsTheCompleteTypedDiagnostic() throws Exception {
        Path root=temp.resolve("debug-package"); Files.createDirectories(root.resolve("config")); Files.createDirectories(root.resolve("templates"));
        Files.createDirectories(root.resolve("testcase")); Files.createDirectories(root.resolve("tools"));
        att.TestSchemas.install(root);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.11\n"
                + "outputDirectory: output\nenvironment: SIT\n"
                + "templates: {root: templates}\ntestcase: {root: testcase}\ntools: {}\n").getBytes("UTF-8"));
        DebugResult result=new DefaultAttService().debug(new DebugRequest(root,Paths.get("config/config.yaml"),null,
                null,null,"template","MISSING",null,false));
        assertEquals(1,result.diagnostics().size());
        assertNotNull(result.diagnostics().get(0).suggestion());
        assertEquals(result.diagnostics().get(0).toMap(),result.toMap().get("diagnostics") instanceof java.util.List
                ? ((java.util.List<java.util.Map<String,Object>>)result.toMap().get("diagnostics")).get(0) : null);
    }
}
