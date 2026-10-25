package att;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import att.api.ValidateResult;
import att.server.api.AttValidationReport;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VNextCommandTest {
    @TempDir Path packageRoot;

    @Test void diagnosticProjectionRedactsHostPathsAndCredentialValues() {
        Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();
        diagnostic.put("detail","Unable to read /tmp/host/private/passwords.yaml; password=top-secret-value");
        Map<String,Object> source=new LinkedHashMap<String,Object>();
        source.put("file",packageRoot.resolve("testdata/uat.yaml").toString());
        diagnostic.put("source",source);

        @SuppressWarnings("unchecked") Map<String,Object> safe=(Map<String,Object>)VNextCommand.safeDiagnosticValue(diagnostic,packageRoot);
        String output=att.validation.JsonSupport.write(safe);

        assertFalse(output.contains("/tmp/host/private"));
        assertFalse(output.contains("top-secret-value"));
        assertTrue(output.contains("$EXTERNAL/passwords.yaml"));
        assertTrue(output.contains("testdata/uat.yaml"));
    }

    @Test void fullInventoryFailureIsReportedAndLaterEnvironmentStillRuns() {
        AttValidationReport report=new AttValidationReport("ALL","LOCAL");
        AttValidationReport.Section pkg=report.addSection("Package payments","packages/payments","PASS");

        VNextCommand.validateInventory(report,pkg,"payments","SIT",packageRoot,()->{
            throw new IllegalArgumentException("Unable to read "+packageRoot.resolve("private.yaml")+"; password=secret-value");
        });
        VNextCommand.validateInventory(report,pkg,"payments","UAT",packageRoot,()->
                new ValidateResult(null,"PASS",0,0,Collections.emptyList(),Collections.emptyMap(),Collections.emptyMap()));

        assertEquals(2,report.exitCode());
        assertEquals("INVALID",report.toMap().get("overallStatus"));
        String rendered=att.validation.JsonSupport.write(report.toMap());
        assertTrue(rendered.contains("SIT — Testcases / Templates / Flows / dependencies"));
        assertTrue(rendered.contains("UAT — Testcases / Templates / Flows / dependencies"));
        Map<?,?> packageSection=(Map<?,?>)((List<?>)report.toMap().get("sections")).get(0);
        assertEquals("FAIL",packageSection.get("status"));
        assertFalse(rendered.contains("secret-value"));
    }

    @Test void resourceDryRunFailsBeforeRecordingToolCanRun() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name","").toLowerCase().contains("win"),
                "The recording Tool fixture requires a POSIX executable script");
        Path home=packageRoot.resolve("att-home");
        Path pkg=home.resolve("packages/payments");
        Path config=home.resolve("config/config.yaml");
        Path marker=packageRoot.resolve("tool-invoked.txt");
        Path script=pkg.resolve("record-tool.sh");
        Files.createDirectories(config.getParent());Files.createDirectories(pkg.resolve("templates"));
        Files.createDirectories(pkg.resolve("testcase"));Files.createDirectories(pkg.resolve("tools"));
        Files.createDirectories(home.resolve("config/tools"));
        TestSchemas.install(home);
        Files.write(script,("#!/bin/sh\nprintf invoked >> '"+marker.toString()+"'\nprintf 'ok\\n'\n").getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(script,java.util.EnumSet.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        Files.write(config,("schemaVersion: att-config/v2.13\noutputDirectory: output\nenvironment: SIT\n"
                +"templates: {root: templates}\ntestcase: {root: testcase}\ntools:\n  recording:\n"
                +"    name: Recording tool\n    description: Records a Tool invocation\n"
                +"    command: [\"./record-tool.sh\"]\n"
                +"    stdoutFormat: text\n    arguments: {}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(home.resolve("config/tools/recording.debug.yaml"),
                "schemaVersion: att-debug/v1.2\narguments: {}\n".getBytes(StandardCharsets.UTF_8));

        att.api.DebugResult control=new att.api.DefaultAttService().debug(new att.api.DebugRequest(pkg,config,null,null,
                "baseline-tool","tool","recording",null,false));
        assertEquals("PASS",control.status(),control.diagnostics().isEmpty()?"":control.diagnostics().get(0).toJson());
        assertTrue(Files.exists(marker),"The fixture Tool should record its control invocation");
        Files.delete(marker);

        String previousHome=System.getProperty("att.home");
        try {
            System.setProperty("att.home",home.toString());
            Captured captured=capture(()->VNextCommand.run(new String[]{
                    "--local","-p","payments","run","tool","recording","--dry-run"}));
            assertEquals(2,captured.exitCode);
            assertTrue(captured.stderr.contains("--dry-run is not available for Template, Flow, or Tool"));
            assertFalse(Files.exists(marker),"Rejected dry-run must not invoke the configured Tool");
        } finally {
            if(previousHome==null)System.clearProperty("att.home");else System.setProperty("att.home",previousHome);
        }
    }

    @Test void jsonParseAndPackageFailuresAreWrittenAsOneStdoutDocument() throws Exception {
        Captured parse=capture(()->VNextCommand.run(new String[]{
                "--format","json","--unknown-option","validate","config"}));
        assertEquals(2,parse.exitCode);
        assertTrue(parse.stdout.contains("\"schemaVersion\":\"att-validation/v1\""));
        assertTrue(parse.stdout.contains("\"overallStatus\":\"INVALID\""));
        assertEquals("",parse.stderr);

        Captured missingPackage=capture(()->VNextCommand.run(new String[]{
                "--format","json","-p","vnext-test-missing-package-928471","validate","config"}));
        assertEquals(2,missingPackage.exitCode);
        assertTrue(missingPackage.stdout.contains("\"schemaVersion\":\"att-validation/v1\""));
        assertTrue(missingPackage.stdout.contains("\"overallStatus\":\"INVALID\""));
        assertEquals("",missingPackage.stderr);
    }

    @Test void missingSelectedFlowAndLoadScenarioProduceValidationReports() throws Exception {
        Path runtimeRoot=packageRoot.resolve("runtime");
        Path config=runtimeRoot.resolve("config/config.yaml");
        Files.createDirectories(config.getParent());
        TestSchemas.install(runtimeRoot);
        Files.write(config,("schemaVersion: att-config/v2.13\noutputDirectory: output\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\n").getBytes(StandardCharsets.UTF_8));

        Captured flow=capture(()->VNextCommand.validate(
                VNextOptions.parse(new String[]{"--local","validate","flow","UNKNOWN.flow","--format","json"}),packageRoot,config));
        assertEquals(2,flow.exitCode);
        assertTrue(flow.stdout.contains("\"schemaVersion\":\"att-validation/v1\""));
        assertTrue(flow.stdout.contains("\"overallStatus\":\"INVALID\""));
        assertTrue(flow.stdout.contains("ATT-RESOURCE-INVALID"));

        Captured load=capture(()->VNextCommand.validate(
                VNextOptions.parse(new String[]{"--local","validate","load","load/missing.yaml","--format","json"}),packageRoot,config));
        assertEquals(2,load.exitCode);
        assertTrue(load.stdout.contains("\"schemaVersion\":\"att-validation/v1\""));
        assertTrue(load.stdout.contains("\"overallStatus\":\"INVALID\""));
        assertTrue(load.stdout.contains("ATT-LOAD-SCENARIO-INVALID"));
        assertTrue(load.stdout.contains("load/missing.yaml"));
    }

    @Test void configValidationScansPackageOnlyWhenPackageWasExplicitlySelected() throws Exception {
        Path runtimeRoot=packageRoot.resolve("runtime");
        Path config=runtimeRoot.resolve("config/config.yaml");
        Files.createDirectories(config.getParent());
        TestSchemas.install(runtimeRoot);
        Files.write(config,("schemaVersion: att-config/v2.13\noutputDirectory: output\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\n").getBytes(StandardCharsets.UTF_8));
        Files.write(packageRoot.resolve("package.yaml"),"schemaVersion: [invalid\n".getBytes(StandardCharsets.UTF_8));

        Captured central=capture(()->VNextCommand.validate(
                VNextOptions.parse(new String[]{"--local","validate","config","--format","json"}),packageRoot,config));
        assertEquals(0,central.exitCode,central.stdout);
        assertTrue(central.stdout.contains("\"overallStatus\":\"PASS\""),central.stdout);
        assertFalse(central.stdout.contains("Package Manifest"),"Without -p, validation must not scan the working-directory Package");

        Captured selected=capture(()->VNextCommand.validate(
                VNextOptions.parse(new String[]{"--local","-p","payments","validate","config","--format","json"}),packageRoot,config));
        assertEquals(2,selected.exitCode);
        assertTrue(selected.stdout.contains("ATT-PACKAGE-INVALID"),selected.stdout);
        assertTrue(selected.stdout.contains("Package Manifest"),selected.stdout);
    }

    private static Captured capture(OutputAction action) throws Exception {
        PrintStream originalOut=System.out,originalErr=System.err;
        ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
        try(PrintStream out=new PrintStream(stdout,true,"UTF-8");PrintStream err=new PrintStream(stderr,true,"UTF-8")) {
            System.setOut(out);System.setErr(err);
            int exit=action.run();out.flush();err.flush();
            return new Captured(exit,stdout.toString("UTF-8"),stderr.toString("UTF-8"));
        } finally {System.setOut(originalOut);System.setErr(originalErr);}
    }
    private interface OutputAction { int run() throws Exception; }
    private static final class Captured {
        final int exitCode;final String stdout,stderr;
        Captured(int exitCode,String stdout,String stderr){this.exitCode=exitCode;this.stdout=stdout;this.stderr=stderr;}
    }
}
