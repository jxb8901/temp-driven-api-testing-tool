/* Author: Jeffrey + ChatGPT */
package att.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CaseExecutionLogTest {
    @TempDir Path tempDir;
    @Test void appendsUtf8StructuredEntries() throws Exception {
        Path file=tempDir.resolve("case.log"); new CaseExecutionLog(file).append("階段","完成");
        String text=new String(Files.readAllBytes(file),"UTF-8"); assertTrue(text.contains("階段")); assertTrue(text.contains("完成"));
    }

    @Test void expandsSharedObjectsWithoutYamlAnchors() throws Exception {
        Map<String,Object> input=new LinkedHashMap<String,Object>(); input.put("requestFile","request.xml");
        Map<String,Object> action=new LinkedHashMap<String,Object>(); action.put("input",input); action.put("toolInput",input);
        Path file=tempDir.resolve("case.log"); new CaseExecutionLog(file).append("ACTION call",action);
        String text=new String(Files.readAllBytes(file),"UTF-8");
        assertFalse(text.contains("&id")); assertFalse(text.contains("*id"));
        assertEquals(2, occurrences(text,"requestFile:"));
    }

    @Test void emitsYamlAnchorsWhenEnabled() throws Exception {
        Map<String,Object> input=new LinkedHashMap<String,Object>(); input.put("requestFile","request.xml");
        Map<String,Object> action=new LinkedHashMap<String,Object>(); action.put("input",input); action.put("toolInput",input);
        Path file=tempDir.resolve("case.log"); new CaseExecutionLog(file,true).append("ACTION call",action);
        String text=new String(Files.readAllBytes(file),"UTF-8");
        assertTrue(text.contains("&id")); assertTrue(text.contains("*id"));
    }

    @Test void marksErrorFailAndInvalidBlocksForSearching() throws Exception {
        Path file=tempDir.resolve("case.log"); CaseExecutionLog log=new CaseExecutionLog(file);
        log.append("ACTION call", status("PASS"));
        log.append("ACTION call", status("FAIL"));
        log.append("ACTION retry", nestedStatus("ERROR"));
        log.append("INVALID", "missing testcase data");
        String text=new String(Files.readAllBytes(file),"UTF-8");
        assertEquals(3,occurrences(text,"【!!!!!】"));
        assertTrue(text.contains("【!!!!!】[ACTION call]"));
        assertTrue(text.contains("【!!!!!】[ACTION retry]"));
        assertTrue(text.contains("【!!!!!】[INVALID]"));
    }

    @Test void compactActionKeepsEachToolAttemptOnceAndOmitsPersistedToolTree() throws Exception {
        Map<String,Object> attempt=new LinkedHashMap<String,Object>();
        attempt.put("attempt",1); attempt.put("status","FAIL"); attempt.put("output","PAYLOAD");
        attempt.put("rawOutput","PAYLOAD"); attempt.put("stdout","PAYLOAD\n"); attempt.put("stderr","");
        attempt.put("command","'sample'"); attempt.put("logicalArgv",java.util.Collections.singletonList("sample"));
        attempt.put("argv",java.util.Collections.singletonList("sample")); attempt.put("TOOL",nestedStatus("FAIL"));
        Map<String,Object> output=new LinkedHashMap<String,Object>(); output.put("status","FAIL"); output.put("success",false);
        output.put("result","PAYLOAD"); output.put("stdout","PAYLOAD\n"); output.put("rawOutput","PAYLOAD");
        output.put("attempts",java.util.Collections.singletonList(attempt));
        Map<String,Object> action=new LinkedHashMap<String,Object>(); action.put("id","call"); action.put("type","tool");
        action.put("output",output); action.put("TOOL",nestedStatus("FAIL"));
        Path file=tempDir.resolve("compact.log");
        CaseExecutionLog log = new CaseExecutionLog(file);
        log.appendRaw("TOOL call STDOUT", "PAYLOAD\n");
        log.appendAction("ACTION call",action);

        String text=new String(Files.readAllBytes(file),"UTF-8");
        assertEquals(1,occurrences(text,"[ACTION call]"));
        assertEquals(1,occurrences(text,"PAYLOAD"));
        assertFalse(text.contains("TOOL:"));
        assertFalse(text.contains("rawOutput:"));
        assertFalse(text.contains("command:"));
    }

    @Test void rawContentPreservesOriginalLineEndings() throws Exception {
        Path file = tempDir.resolve("raw.log");
        new CaseExecutionLog(file).appendRaw("LOG note INFO", "first\r\nsecond\rthird");
        String text = new String(Files.readAllBytes(file), "UTF-8");
        assertTrue(text.contains("[LOG note INFO]\nfirst\r\nsecond\rthird\n\n"));
        assertFalse(text.contains("\\n"));
    }

    @Test void recordSeparatorsDoNotConvertTerminalLoneCrToCrLf() throws Exception {
        String[][] samples = {{"A\n", "A\n\n"}, {"A\r\n", "A\r\n\n"}, {"A\r", "A\r\r\n\n"}};
        for (int index = 0; index < samples.length; index++) {
            Path file = tempDir.resolve("terminal-" + index + ".log");
            new CaseExecutionLog(file).appendRaw("RAW", samples[index][0]);
            String text = new String(Files.readAllBytes(file), "UTF-8");
            assertTrue(text.endsWith(samples[index][1]), text.replace("\r", "<CR>").replace("\n", "<LF>"));
        }
    }

    @Test void structuredMultilineStringsRenderAsBlocksAndPreserveCrLf() throws Exception {
        Path file = tempDir.resolve("structured-lines.log");
        Map<String, Object> attempt = new LinkedHashMap<String, Object>();
        attempt.put("payload", "<A>x</A>\r\n  <B> y </B>\r\n\r\n");
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("output", Collections.singletonMap("attempts", Collections.singletonList(
                Collections.singletonMap("input", attempt))));

        new CaseExecutionLog(file).append("ACTION call", record);
        String text = new String(Files.readAllBytes(file), "UTF-8");
        assertTrue(text.contains("payload: |+\n          <A>x</A>\r\n            <B> y </B>\r\n          \r\n"), text);
        assertFalse(text.contains("\\\\"), text);
        assertFalse(text.contains("\\r\\n"), text);

        Path loneCrFile = tempDir.resolve("structured-terminal-cr.log");
        new CaseExecutionLog(loneCrFile).append("ACTION call", Collections.singletonMap("payload", "A\r"));
        String loneCrText = new String(Files.readAllBytes(loneCrFile), "UTF-8");
        assertTrue(loneCrText.contains("A\r\r\n"), loneCrText.replace("\r", "<CR>").replace("\n", "<LF>"));
    }

    @Test void consoleMirrorAndRetainedLoadLogKeepTheSameMultilinePresentation() throws Exception {
        String payload = "<A>  leading  </A>\r\n\r\n<B>trailing  </B>\n";
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("output", Collections.singletonMap("attempts", Collections.singletonList(
                Collections.singletonMap("input", Collections.singletonMap("payload", payload)))));

        StringBuilder mirror = new StringBuilder();
        Path runFile = tempDir.resolve("mirrored-case.log");
        try (CaseExecutionLog runLog = new CaseExecutionLog(runFile, false, part -> mirror.append(part))) {
            runLog.append("ACTION call", record);
        }
        String runText = new String(Files.readAllBytes(runFile), "UTF-8");
        assertEquals(runText, mirror.toString());
        assertTrue(runText.contains("<A>  leading  </A>\r\n"), runText);
        assertTrue(runText.contains("\r\n          \r\n"), runText);
        assertTrue(runText.contains("<B>trailing  </B>\n"), runText);
        assertTrue(runText.contains("payload: |\n"), runText);

        Path retained = tempDir.resolve("retained-load-case.log");
        CaseExecutionLog loadLog = CaseExecutionLog.lightweight(tempDir.resolve("logical-load/case.log"));
        loadLog.append("ACTION call", record);
        assertEquals(retained.toAbsolutePath().normalize(), loadLog.materialize(retained));
        assertEquals(runText, new String(Files.readAllBytes(retained), "UTF-8"));

        StringBuilder loneCrMirror = new StringBuilder();
        Path loneCrFile = tempDir.resolve("mirrored-terminal-cr.log");
        try (CaseExecutionLog loneCrLog = new CaseExecutionLog(loneCrFile, false, part -> loneCrMirror.append(part))) {
            loneCrLog.append("ACTION terminal CR", Collections.singletonMap("payload", "A\r"));
        }
        String loneCrText = new String(Files.readAllBytes(loneCrFile), "UTF-8");
        assertEquals(loneCrText, loneCrMirror.toString());
        assertTrue(loneCrText.contains("A\r\r\n"), loneCrText.replace("\r", "<CR>").replace("\n", "<LF>"));
    }

    @Test void rawFileKeepsCrLfAndLoneCrAcrossReaderChunks() throws Exception {
        Path source = tempDir.resolve("process-spool.txt");
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 9000; index++) content.append('x');
        content.append("\r\nend\r");
        Files.write(source, content.toString().getBytes("UTF-8"));
        Path file = tempDir.resolve("raw-file-lines.log");

        new CaseExecutionLog(file).appendRawFile("TOOL STDOUT", source, false, content.length());
        String text = new String(Files.readAllBytes(file), "UTF-8");
        assertTrue(text.endsWith("x\r\nend\r\r\n\n"));
        assertFalse(text.endsWith("end\r\n\n"));
    }

    @Test void presentsProjectPathsPortablyAndBoundsExternalPathFields() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("ATT home space"));
        Path file = tempDir.resolve("paths.log");
        CaseExecutionLog log = new CaseExecutionLog(file);
        log.setProjectRoot(root);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("templatePath", root.resolve("templates/付款.xml"));
        values.put("outputDirectory", root.resolve("output/run 1" ).toString());
        values.put("identityFile", tempDir.resolve("private/token.pem").toString());
        values.put("remotePath", "/srv/app/request.xml");
        values.put("diagnostic", "Unable to read " + tempDir.resolve("private/token.pem"));
        log.append("PATHS", values);
        log.appendRaw("ERROR", "failed at " + root.resolve("templates/付款.xml"));
        log.appendRaw("ERROR", "Unable to read " + tempDir.resolve("private/token.pem"));
        log.appendRaw("SSH application STDOUT", "remote=/srv/app/request.xml");
        String text = new String(Files.readAllBytes(file), "UTF-8");
        assertTrue(text.contains("$ATT_HOME/templates/付款.xml"));
        assertTrue(text.contains("$ATT_HOME/output/run 1"));
        assertTrue(text.contains("$EXTERNAL/token.pem"));
        assertTrue(text.contains("remotePath: /srv/app/request.xml"));
        assertTrue(text.contains("diagnostic: Unable"));
        assertTrue(text.contains("Unable to read $EXTERNAL/token.pem"));
        assertTrue(text.contains("remote=/srv/app/request.xml"));
        assertFalse(text.contains(root.toString()));
        assertFalse(text.contains("private/token.pem"));
    }

    @Test void runDebugAndLoadUseTheSameLogicalPathPresentation() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("project"));
        Path output = root.resolve("output/run-123/case");
        TestCase testCase = new TestCase(1, "S", "G", "C", java.util.Collections.<String>emptyList(),
                java.util.Collections.<String, Object>emptyMap(), java.util.Collections.<String, StageCaseData>emptyMap(), "");
        String[] modes = {"testcase", "debug", "load"};
        Integer expectedOccurrences = null;
        for (String mode : modes) {
            Path logPath = output.resolve("case.log");
            CaseRuntimeContext context = new CaseRuntimeContext(testCase, output, "run-123", root, logPath, mode);
            context.setProject(root);
            CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve(mode + ".log"));
            log.setProjectRoot(context.projectRoot());
            log.append("CASE", context.caseTree());
            String rendered = new String(Files.readAllBytes(tempDir.resolve(mode + ".log")), "UTF-8");
            assertTrue(rendered.contains("$ATT_HOME/output/run-123/case"), mode);
            assertFalse(rendered.contains(root.toString()), mode);
            int occurrences = occurrences(rendered, "$ATT_HOME/output/run-123/case");
            if (expectedOccurrences == null) expectedOccurrences = occurrences;
            else assertEquals(expectedOccurrences.intValue(), occurrences, mode);
        }
    }

    @Test void compactToolInvocationKeepsCaptureAndCleanupFailuresVisible() throws Exception {
        Map<String,Object> attempt = new LinkedHashMap<String,Object>();
        attempt.put("id", "invoke");
        attempt.put("status", "ERROR");
        attempt.put("stdoutCaptureError", "capture failed");
        attempt.put("stderrCaptureError", "stderr failed");
        attempt.put("cleanupWarning", "spool cleanup failed");
        attempt.put("evidenceError", "case log append failed");
        Path file = tempDir.resolve("tool.log");
        new CaseExecutionLog(file).appendToolInvocation("ACTION invoke", attempt);
        String text = new String(Files.readAllBytes(file), "UTF-8");
        assertTrue(text.contains("stdoutCaptureError"));
        assertTrue(text.contains("stderrCaptureError"));
        assertTrue(text.contains("cleanupWarning"));
        assertTrue(text.contains("evidenceError"));
    }

    @Test void discardingLogSkipsValueTraversalAndDoesNotMaterializeAFile() throws Exception {
        Map<String, Object> expensive = new AbstractMap<String, Object>() {
            @Override public Set<Entry<String, Object>> entrySet() {
                throw new AssertionError("discarding logs must not traverse or serialize values");
            }
        };
        Path file = tempDir.resolve("discarded.log");
        CaseExecutionLog log = CaseExecutionLog.discarding(file);
        log.registerSecretRedactions(java.util.Collections.singletonList("secret-value"));
        assertFalse(log.appendInternalErrorOnce(new RuntimeException("ignored"), "ignored"));
        log.append("ACTION", expensive);
        log.appendAction("ACTION", expensive);
        log.appendToolInvocation("ACTION", expensive);
        log.appendRaw("ACTION", "ignored");
        log.materialize(file);
        assertFalse(Files.exists(file));
    }

    @Test void boundedDeferredLogRetainsRecentFailureDetailsAndRedactsSecrets() throws Exception {
        Path file = tempDir.resolve("bounded.log");
        CaseExecutionLog log = CaseExecutionLog.bounded(file, false, 160);
        log.registerSecretRedactions(java.util.Collections.singletonList("secret-token"));
        StringBuilder oldEvents = new StringBuilder();
        for (int index = 0; index < 100; index++) oldEvents.append("old-event-").append(index).append(' ');
        log.appendRaw("ACTION prior", oldEvents.toString());
        log.appendRaw("LOAD ERROR", "failure detail token=secret-token");
        log.materialize(file);

        String text = new String(Files.readAllBytes(file), "UTF-8");
        assertTrue(text.length() <= 160, "deferred failure log must stay within its character bound");
        assertTrue(text.startsWith("... earlier case log events omitted ..."));
        assertTrue(text.contains("failure detail token=[REDACTED_SECRET]"));
        assertFalse(text.contains("secret-token"));
    }

    @Test void consoleMirrorFlushesEachAlreadyRedactedAppendWithCaseIdentity() throws Exception {
        Path file = tempDir.resolve("live.log");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CountingPrintStream output = new CountingPrintStream(bytes);
        CaseExecutionLog log = new CaseExecutionLog(file, false, new CaseLogConsoleMirror("payments.payment.TC001", output));
        log.registerSecretRedactions(java.util.Collections.singletonList("secret-value"));
        log.appendRaw("ACTION START", "token=secret-value");

        String live = bytes.toString("UTF-8");
        String persisted = new String(Files.readAllBytes(file), "UTF-8");
        assertTrue(live.startsWith("[CASE-LOG case=payments.payment.TC001] [ACTION START]"));
        assertTrue(live.contains("token=[REDACTED_SECRET]"));
        assertFalse(live.contains("secret-value"));
        assertFalse(persisted.contains("secret-value"));
        assertTrue(output.flushes > 0);
    }

    @Test void redactsProjectRootSecretsBeforePathPresentation() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("secret project"));
        String secret = root.resolve("private-token").toString();
        Path file = tempDir.resolve("secret-path.log");
        CaseExecutionLog log = new CaseExecutionLog(file);
        log.setProjectRoot(root);
        log.registerSecretRedactions(java.util.Collections.singletonList(secret));

        log.appendRaw("ERROR", "credential=" + secret);
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("identityFile", secret);
        log.append("SSH", values);

        String text = new String(Files.readAllBytes(file), "UTF-8");
        assertTrue(text.contains("credential=[REDACTED_SECRET]"));
        assertTrue(text.contains("identityFile:"));
        assertTrue(occurrences(text, "[REDACTED_SECRET]") >= 2);
        assertFalse(text.contains(secret));
        assertFalse(text.contains("private-token"));
    }

    @Test void sanitizesKnownDiagnosticFieldsAndCleanupWarningsButKeepsSshErrorPaths() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("diagnostic-project"));
        Path external = Files.createDirectories(tempDir.resolve("John Doe/private"))
                .resolve("token (final).pem");
        Files.write(external, new byte[]{1});
        Path file = tempDir.resolve("diagnostic-fields.log");
        CaseExecutionLog log = new CaseExecutionLog(file);
        log.setProjectRoot(root);

        Map<String, Object> fields = new LinkedHashMap<String, Object>();
        fields.put("parserDiagnostic", "parse failed at " + external);
        fields.put("stdoutCaptureError", "capture failed at " + external);
        fields.put("stderrCaptureError", "capture failed at " + external);
        fields.put("cleanupWarning", "cleanup failed at " + external);
        fields.put("evidenceError", "evidence failed at " + external);
        fields.put("retryDecision", Collections.<String, Object>singletonMap("reason", "retry failed at " + external));
        log.append("ACTION collector", fields);
        log.appendRaw("ACTION collector cleanup warning", "cleanup failed at " + external);

        Map<String, Object> ssh = new LinkedHashMap<String, Object>();
        ssh.put("type", "ssh");
        ssh.put("input", Collections.<String, Object>singletonMap("remotePath", "/srv/app/request.xml"));
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("category", "SSH_UPLOAD_ERROR");
        error.put("message", "Remote destination exists: /srv/app/request.xml");
        ssh.put("error", error);
        log.append("SSH failed upload", ssh);

        String text = new String(Files.readAllBytes(file), "UTF-8");
        assertTrue(occurrences(text, "$EXTERNAL/token (final).pem") >= 6, text);
        assertFalse(text.contains(external.toString()), text);
        assertTrue(text.contains("Remote destination exists: /srv/app/request.xml"), text);
        assertFalse(text.contains("Remote destination exists: $EXTERNAL/request.xml"), text);
    }

    @Test void concurrentMirrorsKeepEachAppendedChunkAtomicAndIdentifiable() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(bytes, true, "UTF-8");
        CaseLogConsoleMirror first = new CaseLogConsoleMirror("case-A", output);
        CaseLogConsoleMirror second = new CaseLogConsoleMirror("case-B", output);
        Thread a = new Thread(() -> { for (int i = 0; i < 100; i++) first.accept("event-A-" + i + "\n"); });
        Thread b = new Thread(() -> { for (int i = 0; i < 100; i++) second.accept("event-B-" + i + "\n"); });
        a.start(); b.start(); a.join(); b.join();
        String[] lines = bytes.toString("UTF-8").split("\\n");
        assertEquals(200, lines.length);
        for (String line : lines) assertTrue(line.startsWith("[CASE-LOG case=case-A] ")
                || line.startsWith("[CASE-LOG case=case-B] "), line);
    }

    private Map<String,Object> status(String value){Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("status",value);return result;}
    private Map<String,Object> nestedStatus(String value){Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("TOOL",status(value));return result;}

    private int occurrences(String text,String value){int count=0,index=0;while((index=text.indexOf(value,index))>=0){count++;index+=value.length();}return count;}

    private static final class CountingPrintStream extends PrintStream {
        private int flushes;
        private CountingPrintStream(ByteArrayOutputStream bytes) { super(bytes, true); }
        @Override public void flush() { flushes++; super.flush(); }
    }
}
