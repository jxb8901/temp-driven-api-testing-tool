package att.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test void validationChecksIdentityFormatSyntaxBeforeAnyRunStarts() throws Exception {
        Path root=temp.resolve("identity-validation-package");
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("templates"));
        Files.createDirectories(root.resolve("testcase"));
        Files.createDirectories(root.resolve("tools"));
        att.TestSchemas.install(root);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.12\n"
                + "templates: {root: templates}\ntestcase: {root: testcase}\ntools: {}\n"
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
                + "templates: {root: templates}\ntestcase: {root: testcase}\ntools: {}\n").getBytes("UTF-8"));
        DebugResult result=new DefaultAttService().debug(new DebugRequest(root,Paths.get("config/config.yaml"),null,
                null,null,"template","MISSING",null,false));
        assertEquals(1,result.diagnostics().size());
        assertNotNull(result.diagnostics().get(0).suggestion());
        assertEquals(result.diagnostics().get(0).toMap(),result.toMap().get("diagnostics") instanceof java.util.List
                ? ((java.util.List<java.util.Map<String,Object>>)result.toMap().get("diagnostics")).get(0) : null);
    }

    @Test void concurrentLoadRequestsAtomicallyReserveTheSameOuterRunId() throws Exception {
        Path root=temp.resolve("load-collision-package");
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("templates/SIMPLE"));
        Files.createDirectories(root.resolve("load"));
        att.TestSchemas.install(root);
        Files.write(root.resolve("config/config.yaml"), ("schemaVersion: att-config/v2.11\n"
                + "outputDirectory: output\nenvironment: SIT\ntemplates: {root: templates}\n"
                + "testcase: {root: testcase}\ntools: {}\n").getBytes("UTF-8"));
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
}
