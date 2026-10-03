package att.template;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompiledExecutionPlanTest {
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
}
