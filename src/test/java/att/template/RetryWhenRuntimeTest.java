package att.template;

import att.core.*;
import att.validation.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetryWhenRuntimeTest {
    @TempDir Path tempDir;

    @Test void assertionRetryReadsCurrentAttemptAndPreservesHistory() throws Exception {
        Run run = run("ASSERTION", "#{${output.status} == 'FAIL' && ${output.attempt} == 1 && length(${output.result}) == 2}",
                "#{${output.attempt} >= 2}");
        assertEquals(ResultStatus.PASS, run.result.status(), run.result.message());
        assertEquals(2, run.context.resolve("EXEC.ACTIONS.call.output.attempt"));
        assertEquals("FAIL", run.context.resolve("EXEC.ACTIONS.call.output.attempts[0].status"));
        assertEquals(Boolean.TRUE, run.context.resolve("EXEC.ACTIONS.call.output.attempts[0].retryDecision.whenResult"));
        assertEquals("OK", run.context.resolve("EXEC.ACTIONS.call.output.result"));
        assertEquals("PASS", run.context.resolve("EXEC.ACTIONS.call.output.status"));
    }

    @Test void falseGatePreservesAssertionFailure() throws Exception {
        Run run = run("ASSERTION", "#{false}", "#{false}");
        assertEquals(ResultStatus.FAIL, run.result.status());
        assertEquals("FAIL", run.context.resolve("EXEC.ACTIONS.call.output.status"));
        assertEquals(1, run.attempts());
        assertEquals("WHEN_FALSE", run.context.resolve("EXEC.ACTIONS.call.output.attempts[0].retryDecision.reason"));
    }

    @Test void nonMatchingCategoryDoesNotEvaluateAnUnavailableCondition() throws Exception {
        Run run = run("TIMEOUT", "#{${output.missing} == 1}", "#{false}");
        assertEquals(ResultStatus.FAIL, run.result.status(), run.result.message());
        assertEquals(1, run.attempts());
        assertEquals(Boolean.FALSE, run.context.resolve("EXEC.ACTIONS.call.output.attempts[0].retryDecision.whenEvaluated"));
        assertEquals("CATEGORY_NOT_SELECTED", run.context.resolve("EXEC.ACTIONS.call.output.attempts[0].retryDecision.reason"));
    }

    @Test void successfulAttemptDoesNotEvaluateCondition() throws Exception {
        Run run = run("ASSERTION", "#{${output.missing} == 1}", "#{true}");
        assertEquals(ResultStatus.PASS, run.result.status(), run.result.message());
        assertEquals(1, run.attempts());
        assertFalse(run.context.contains("EXEC.ACTIONS.call.output.attempts[0].retryDecision"));
    }

    @Test void exhaustedAttemptsDoNotEvaluateTheGateAgain() throws Exception {
        Run run = run("ASSERTION", "#{true}", "#{false}");
        assertEquals(ResultStatus.FAIL, run.result.status());
        assertEquals(2, run.attempts());
        assertEquals("MAX_ATTEMPTS", run.context.resolve("EXEC.ACTIONS.call.output.attempts[1].retryDecision.reason"));
        assertEquals(Boolean.FALSE, run.context.resolve("EXEC.ACTIONS.call.output.attempts[1].retryDecision.whenEvaluated"));
    }

    @Test void runtimeMissingPathAndNonBooleanConditionsAreTerminal() throws Exception {
        for (String expression : new String[]{"#{${output.missing} == 1}", "${output.result}"}) {
            Run run = run("ASSERTION", expression, "#{false}");
            assertEquals(ResultStatus.ERROR, run.result.status());
            assertEquals(1, run.attempts());
            assertNotNull(run.result.diagnostic());
            assertEquals("actions.call.retry.when", run.result.diagnostic().field());
            assertEquals("EXPRESSION_ERROR", run.context.resolve("EXEC.ACTIONS.call.output.attempts[0].retryDecision.reason"));
        }
    }

    private Run run(String category, String when, String assertion) throws Exception {
        Path dir = Files.createTempDirectory(tempDir, "retry-");
        TestCase test = new TestCase(1, "g", "s", "t", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.emptyMap(), null);
        CaseRuntimeContext context = new CaseRuntimeContext(test, dir, "R", tempDir, dir.resolve("case.log"));
        context.beginStage(new StageCaseData("main", "RETRY", Collections.<String,Object>emptyMap()), "RETRY", tempDir);
        Map<String,Object> retry = new LinkedHashMap<String,Object>();
        retry.put("maxAttempts", 2); retry.put("intervalMs", 0);
        retry.put("retryOn", Collections.singletonList(category)); retry.put("when", when);
        Map<String,Object> values = new LinkedHashMap<String,Object>();
        values.put("type", "tool"); values.put("call", "#{upper('ok')}");
        values.put("assert", assertion); values.put("retry", retry);
        TemplateAction action = new TemplateAction("call", values, att.Version.TEMPLATE_SCHEMA);
        try (CaseExecutionLog log = new CaseExecutionLog(dir.resolve("case.log"))) {
            List<ValidationResult> results = new StageTemplateRunner(new UnifiedTemplateEngine(null))
                    .execute("main", new StageTemplate("RETRY", tempDir, Collections.singletonList(action)), context, log);
            return new Run(results.get(0), context);
        }
    }

    private static class Run {
        final ValidationResult result; final CaseRuntimeContext context;
        Run(ValidationResult result, CaseRuntimeContext context) { this.result = result; this.context = context; }
        int attempts() { return ((List<?>) context.resolve("EXEC.ACTIONS.call.output.attempts")).size(); }
    }
}
