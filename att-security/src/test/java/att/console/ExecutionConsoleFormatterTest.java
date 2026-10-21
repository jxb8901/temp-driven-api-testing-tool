package att.console;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionConsoleFormatterTest {
    @Test void explicitPublishedPolicyIsIndependentAndRedactsBeforeTruncation() {
        String text=String.join("",java.util.Collections.nCopies(8000,"x"))+" password=hidden";
        assertEquals(8016, text.length());
        String expanded=ExecutionConsoleFormatter.present(text,ExecutionConsoleFormatter.Options.published(16000,100));
        assertFalse(expanded.contains("hidden"));assertFalse(expanded.contains("[truncated]"));
        assertTrue(ExecutionConsoleFormatter.present(text,ExecutionConsoleFormatter.Options.published()).endsWith("[truncated]"));
        assertTrue(ExecutionConsoleFormatter.present("a\nb\nc",ExecutionConsoleFormatter.Options.published(16000,2)).endsWith("[truncated]"));
        String unicode=ExecutionConsoleFormatter.present("aaa\uD83D\uDE00",ExecutionConsoleFormatter.Options.published(4,40));
        assertEquals("aaa [truncated]",unicode);
        assertThrows(IllegalArgumentException.class,()->ExecutionConsoleFormatter.Options.published(32001,40));
        assertThrows(IllegalArgumentException.class,()->ExecutionConsoleFormatter.Options.published(4000,201));
    }

    @Test void expandedLinePolicyPreservesOneHundredLinesAndNormalizesControls() {
        String lines=java.util.stream.IntStream.rangeClosed(1,100).mapToObj(i->"line-"+i).collect(java.util.stream.Collectors.joining("\r\n"));
        String expanded=ExecutionConsoleFormatter.present(lines,ExecutionConsoleFormatter.Options.published(16000,100));
        assertTrue(expanded.endsWith("line-100"));assertFalse(expanded.contains("[truncated]"));assertFalse(expanded.contains("\r"));
        assertTrue(ExecutionConsoleFormatter.present(lines,ExecutionConsoleFormatter.Options.published()).contains("[truncated]"));
        assertEquals("ab",ExecutionConsoleFormatter.present("a\u0000\u0007b",ExecutionConsoleFormatter.Options.published()));
    }

    @Test void rendersRunDebugAndLoadLinesFromTheSameFields() {
        Map<String, Object> finished = new LinkedHashMap<String, Object>();
        finished.put("event", "CASE_FINISHED");
        assertEquals("[CASE] id=case-1 status=PASS durationMs=12",
                ExecutionConsoleFormatter.format("STATUS", "run-1", "case-1", null, null, "PASS", 12L, null,
                        finished, ExecutionConsoleFormatter.Options.cli(null)));
        Map<String, Object> debug = new LinkedHashMap<String, Object>();
        debug.put("event", "DEBUG_STARTED");
        debug.put("targetType", "flow");
        debug.put("targetId", "payment.invoke");
        debug.put("input", "in");
        debug.put("output", "out");
        assertEquals("[DEBUG] START target=flow:payment.invoke input=in output=out",
                ExecutionConsoleFormatter.format("DEBUG", "run-1", null, null, null, null, null, null,
                        debug, ExecutionConsoleFormatter.Options.published()));
        assertEquals("[LOAD] START runId=load-1 model=closed",
                ExecutionConsoleFormatter.loadStart("load-1", "closed", null));
        assertTrue(ExecutionConsoleFormatter.loadProgress("PROGRESS", "load-1", null, 1000L, 2, 2, 2, 0, 2, 0, 0, 0, 2.0, 4.5, 0)
                .startsWith("[LOAD] PROGRESS runId=load-1 elapsedMs=1000"));
        assertEquals("[RUN] status=PASS jobId=J0000000000000001 exitCode=0",
                ExecutionConsoleFormatter.lifecycle("RUN", "J0000000000000001", "PASS", 0));
        assertEquals("Complete: total=1, passed=1, failed=0, error=0, skipped=0, invalid=0",
                ExecutionConsoleFormatter.runComplete(false, 1, 1, 0, 0, 0, 0));
    }

    @Test void publishedLinesRedactSecretsStripAnsiAndRemainPlainText() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionEventType", "LOG");
        data.put("message", "\u001B[31mpassword=super-secret <script>alert(1)</script>");
        String line = ExecutionConsoleFormatter.formatPayload(data, ExecutionConsoleFormatter.Options.published());
        assertNotNull(line);
        assertFalse(line.contains("super-secret"));
        assertFalse(line.contains("\u001B"));
        assertTrue(line.contains("[REDACTED_SECRET]"));
        assertTrue(line.contains("<script>alert(1)</script>"));
        Map<String, Object> iteration = new LinkedHashMap<String, Object>();
        iteration.put("phase", "STEADY");
        iteration.put("sequence", 4);
        assertNull(ExecutionConsoleFormatter.formatPayload(iteration, ExecutionConsoleFormatter.Options.published()));
    }

    @Test void contractCoversMissingFieldsUnicodeBoundsAndInterleavedCases() {
        assertNull(ExecutionConsoleFormatter.format(null, null, null, null, null, null, null, null, null,
                ExecutionConsoleFormatter.Options.published()));
        Map<String, Object> unusual = new LinkedHashMap<String, Object>();
        unusual.put("event", "CASE_FINISHED");
        assertEquals("[CASE] id=案例/1 status=FAIL durationMs=null",
                ExecutionConsoleFormatter.format("STATUS", "run", "案例/1", null, null, "FAIL", null, null,
                        unusual, ExecutionConsoleFormatter.Options.cli(null)));
        Map<String, Object> cancelled = new LinkedHashMap<String, Object>();
        cancelled.put("event", "RUN_CANCELLED");
        assertEquals("[RUN] CANCELLED runId=run-1",
                ExecutionConsoleFormatter.format("STATUS", "run-1", null, null, null, null, null, null,
                        cancelled, ExecutionConsoleFormatter.Options.published()));
        Map<String, Object> timeout = new LinkedHashMap<String, Object>();
        timeout.put("event", "ACTION_FINISHED");
        timeout.put("detail", "timeout");
        assertTrue(ExecutionConsoleFormatter.format("STATUS", "run-1", "case-a", "stage", "call", "ERROR", 9L, null,
                timeout, ExecutionConsoleFormatter.Options.published()).contains("message=timeout"));
        StringBuilder lines = new StringBuilder("one");
        for (int i = 0; i < 45; i++) lines.append("\nline");
        String multiline = ExecutionConsoleFormatter.format("LOG", "run-1", "case-a", null, null, null, null,
                lines.toString(), null, ExecutionConsoleFormatter.Options.published());
        assertTrue(multiline.contains("one"));
        assertTrue(multiline.contains("[truncated]"));
        String first = ExecutionConsoleFormatter.format("STATUS", "run", "case-a", "s", "a", "PASS", 1L, null, unusual,
                ExecutionConsoleFormatter.Options.cli(null));
        Map<String, Object> other = new LinkedHashMap<String, Object>();
        other.put("event", "CASE_FINISHED");
        String second = ExecutionConsoleFormatter.format("STATUS", "run", "case-b", "s", "a", "PASS", 2L, null, other,
                ExecutionConsoleFormatter.Options.cli(null));
        assertTrue(first.contains("case-a") || first.contains("案例"));
        assertTrue(second.contains("case-b"));
        assertFalse(first.equals(second));
    }

    @Test void publishedLinesOmitNestedValuesAndBoundLength() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionEventType", "LOG");
        char[] padding = new char[5000];
        java.util.Arrays.fill(padding, 'x');
        data.put("message", new String(padding));
        Map<String, Object> nested = new LinkedHashMap<String, Object>();
        nested.put("password", "hidden-secret");
        data.put("nested", nested);
        String line = ExecutionConsoleFormatter.formatPayload(data, ExecutionConsoleFormatter.Options.published());
        assertNotNull(line);
        assertTrue(line.endsWith(" [truncated]"));
        assertTrue(line.length() < 4200);
        assertFalse(line.contains("hidden-secret"));
    }
}
