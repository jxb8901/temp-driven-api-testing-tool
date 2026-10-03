package att.template;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import att.core.*;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompiledExecutionPlanTest {
    @TempDir Path tempDir;
    @Test void compilesOrderedActionsCallsConditionsAssertionsAndRetryPredicate() {
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("type", "tool");
        raw.put("call", "#{fixture.lookup(value=${CASE.input})}");
        raw.put("runWhen", "${CASE.enabled}");
        raw.put("assert", "${ACTIONS.lookup.output} == 1");
        raw.put("expected", "${CASE.expected}");
        raw.put("actual", "${ACTIONS.lookup.output}");
        Map<String, Object> retry = new LinkedHashMap<String, Object>();
        retry.put("when", "${CASE.retry}");
        raw.put("retry", retry);
        TemplateAction action = new TemplateAction("lookup", raw);
        StageTemplate template = new StageTemplate("sample", Paths.get("."), Collections.singletonList(action));

        CompiledExecutionPlan plan = CompiledExecutionPlan.compile(template, null);
        CompiledExecutionPlan.ActionPlan actionPlan = plan.action(action);
        assertEquals(1, plan.orderedActions().size());
        assertEquals("fixture.lookup", actionPlan.primaryCall().name());
        assertNotNull(actionPlan.primaryCall().arguments().get(0).compiled());
        assertNotNull(actionPlan.runWhen());
        assertNotNull(actionPlan.assertion());
        assertNotNull(actionPlan.expected());
        assertNotNull(actionPlan.actual());
        assertNotNull(actionPlan.retryWhen());
        assertThrows(UnsupportedOperationException.class, () -> plan.orderedActions().clear());
    }

    @Test void immutableActionPlansCanBeSharedAcrossConcurrentIterations() throws Exception {
        Map<String, Object> raw = new LinkedHashMap<String, Object>();
        raw.put("type", "tool"); raw.put("call", "#{fixture.lookup(value=${CASE.input})}");
        TemplateAction action = new TemplateAction("lookup", raw);
        CompiledExecutionPlan plan = CompiledExecutionPlan.compile(new StageTemplate("sample", Paths.get("."),
                Collections.singletonList(action)), null);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            java.util.List<Future<String>> results = new java.util.ArrayList<Future<String>>();
            for (int index = 0; index < 64; index++) results.add(workers.submit(new Callable<String>() {
                @Override public String call() {
                    plan.recordEvaluation();
                    return plan.action(action).primaryCall().name() + ":"
                            + plan.action(action).primaryCall().arguments().get(0).expression();
                }
            }));
            for (Future<String> result : results) assertEquals("fixture.lookup:${CASE.input}", result.get());
        } finally { workers.shutdownNow(); }
        assertEquals(64L, plan.evaluations());
    }

    @Test void sharedRunnerKeepsConcurrentIterationContextsIndependentAndMatchesUncompiledResults() throws Exception {
        Map<String,Object> assign = new LinkedHashMap<String,Object>();
        assign.put("type", "assign"); assign.put("name", "copy"); assign.put("expression", "${CASE.value}");
        Map<String,Object> check = new LinkedHashMap<String,Object>();
        check.put("type", "assert"); check.put("assert", "${ACTIONS.copy.output.value} == ${CASE.value}");
        StageTemplate template = new StageTemplate("shared", tempDir,
                java.util.Arrays.asList(new TemplateAction("copy", assign),
                        new TemplateAction("invoke", toolWithRetry()), new TemplateAction("check", check)));
        CompiledExecutionPlan plan = CompiledExecutionPlan.compile(template, null);
        UnifiedTemplateEngine sharedEngine = new UnifiedTemplateEngine(null);
        StageTemplateRunner compiledRunner = new StageTemplateRunner(sharedEngine, null, new RenderPlanCache(), plan);
        StageTemplateRunner ordinaryRunner = new StageTemplateRunner(new UnifiedTemplateEngine(null));
        java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            java.util.List<Future<String>> outcomes = new java.util.ArrayList<Future<String>>();
            for (int i=0;i<32;i++) {
                final int value=i;
                outcomes.add(workers.submit(() -> {
                    Path caseDir=tempDir.resolve("case-"+value); Files.createDirectories(caseDir);
                    TestCase test=new TestCase(2,"g","s","TC"+value,Collections.<String>emptyList(),
                            Collections.<String,Object>emptyMap(),Collections.emptyMap(),null);
                    CaseRuntimeContext context=new CaseRuntimeContext(test,caseDir,"R",tempDir,caseDir.resolve("case.log"));
                    context.put("CASE.value", value);
                    context.put("CASE.enabled", Boolean.TRUE); context.put("CASE.retryAllowed", Boolean.TRUE);
                    context.beginStage(new StageCaseData("shared","shared",Collections.<String,Object>emptyMap()),"shared",tempDir);
                    java.util.List<ValidationResult> actual=compiledRunner.execute("LOAD",template,context,
                            new CaseExecutionLog(caseDir.resolve("case.log")));
                    if (actual.get(1).status()!=ResultStatus.FAIL || actual.get(2).status()!=ResultStatus.PASS)
                        throw new AssertionError("compiled result: "+actual);
                    Path baselineDir=tempDir.resolve("baseline-"+value); Files.createDirectories(baselineDir);
                    CaseRuntimeContext baseline=new CaseRuntimeContext(test,baselineDir,"R",tempDir,baselineDir.resolve("case.log"));
                    baseline.put("CASE.value",value);
                    baseline.put("CASE.enabled", Boolean.TRUE); baseline.put("CASE.retryAllowed", Boolean.TRUE);
                    baseline.beginStage(new StageCaseData("shared","shared",Collections.<String,Object>emptyMap()),"shared",tempDir);
                    java.util.List<ValidationResult> expected=ordinaryRunner.execute("LOAD",template,baseline,
                            new CaseExecutionLog(baselineDir.resolve("case.log")));
                    Object compiledAttempts=context.resolve("ACTIONS.invoke.output.attempts");
                    Object baselineAttempts=baseline.resolve("ACTIONS.invoke.output.attempts");
                    if (((java.util.List<?>)compiledAttempts).size()!=2 || ((java.util.List<?>)baselineAttempts).size()!=2)
                        throw new AssertionError("retry attempt count differs");
                    return actual.get(1).status()+":"+expected.get(1).status()+":"+
                            actual.get(2).status()+":"+expected.get(2).status()+":"+context.resolve("ACTIONS.copy.output.value");
                }));
            }
            for(int i=0;i<outcomes.size();i++) assertEquals("FAIL:FAIL:PASS:PASS:"+i,outcomes.get(i).get());
        } finally { workers.shutdownNow(); }
    }

    private static Map<String,Object> toolWithRetry() {
        Map<String,Object> raw=new LinkedHashMap<String,Object>();
        raw.put("type","tool"); raw.put("call","#{upper(value=${CASE.value})}");
        raw.put("runWhen","${CASE.enabled}"); raw.put("assert","1 == 2");
        Map<String,Object> retry=new LinkedHashMap<String,Object>();
        retry.put("maxAttempts",2); retry.put("retryOn",Collections.singletonList("ASSERTION"));
        retry.put("when","${CASE.retryAllowed}"); raw.put("retry",retry);
        return raw;
    }
}
