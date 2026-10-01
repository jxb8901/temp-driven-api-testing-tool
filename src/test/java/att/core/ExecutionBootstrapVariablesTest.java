package att.core;

import att.template.DefaultBuiltInProvider;
import att.template.UnifiedTemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionBootstrapVariablesTest {
    @TempDir Path temp;

    @Test void evaluatesTypedTreesAndOrderIndependentDependenciesAgainstInitializedContext() throws Exception {
        CaseRuntimeContext context = context("EXEC-12");
        Map<String, Object> definitions = new LinkedHashMap<String, Object>();
        definitions.put("derived", "${EXEC['VARS']['base']}");
        definitions.put("expressionDerived", "#{${EXEC.VARS['base']} + 1}");
        definitions.put("bracketInput", "${EXEC['INPUT'].amount}");
        definitions.put("rendered", "REQ-${EXEC.ID}");
        definitions.put("arithmetic", "#{${EXEC.INPUT.amount} * 2}");
        definitions.put("nested", mapOf("amount", "${EXEC.INPUT.amount}", "list", Arrays.asList("${EXEC.INPUT.amount}", "item-${EXEC.ID}")));
        definitions.put("base", "${EXEC.INPUT.amount}");
        definitions.put("pure", "#{upper('att')}");
        ExecutionBootstrapVariables.evaluate(definitions, context, engine());

        assertEquals(12L, ((Number) context.require("EXEC.VARS.base")).longValue());
        assertEquals(12L, ((Number) context.require("EXEC.VARS.derived")).longValue());
        assertEquals(13L, ((Number) context.require("EXEC.VARS.expressionDerived")).longValue());
        assertEquals(12L, ((Number) context.require("EXEC.VARS.bracketInput")).longValue());
        assertEquals("REQ-EXEC-12", context.require("EXEC.VARS.rendered"));
        assertEquals(24L, ((Number) context.require("EXEC.VARS.arithmetic")).longValue());
        Map<?, ?> nested = (Map<?, ?>) context.require("EXEC.VARS.nested");
        assertEquals(12L, ((Number) nested.get("amount")).longValue());
        assertEquals(12L, ((Number) ((List<?>) nested.get("list")).get(0)).longValue());
        assertEquals("item-EXEC-12", ((List<?>) nested.get("list")).get(1));
        assertEquals("ATT", context.require("EXEC.VARS.pure"));
        context.assignCaseVariable("base", "replaced");
        assertEquals("replaced", context.require("EXEC.VARS.base"));
    }

    @Test void bootstrapRootClassificationMatchesParsedRuntimeSegments() {
        Map<String, Object> inputs = mapOf("amount", Integer.valueOf(12));
        Map<String, Object> bracketInput = mapOf("value", "${EXEC['INPUT'].amount}");
        ExecutionBootstrapVariables.validate(bracketInput, engine(), inputs, null, "vars",
                att.validation.DiagnosticCodes.DEBUG_INVALID);

        CaseRuntimeContext context = context("EXEC-BRACKET-ROOT");
        assertThrows(att.validation.DiagnosticException.class,
                () -> context.require("exec.input.amount"));
        att.validation.DiagnosticException lowercase = assertThrows(att.validation.DiagnosticException.class,
                () -> ExecutionBootstrapVariables.validate(mapOf("value", "${exec.input.amount}"), engine(), inputs,
                        null, "vars", att.validation.DiagnosticCodes.DEBUG_INVALID));
        assertEquals("vars.value", lowercase.field());

        att.validation.DiagnosticException numericVarsSelector = assertThrows(att.validation.DiagnosticException.class,
                () -> ExecutionBootstrapVariables.validate(mapOf("value", "${EXEC.VARS[0]}"), engine(), inputs,
                        null, "vars", att.validation.DiagnosticCodes.DEBUG_INVALID));
        assertEquals("vars.value", numericVarsSelector.field());
    }

    @Test void probesInputPathsAsFoundMissingNullIntermediateOrStructurallyInvalid() {
        Map<String, Object> input = mapOf("customer", "C001", "nullable", null,
                "profile", mapOf("id", "C002"), "items", Arrays.asList("first"));

        assertEquals(CaseRuntimeContext.InputPathStatus.FOUND,
                CaseRuntimeContext.probeInputPath(input, "EXEC.INPUT.profile.id"));
        assertEquals(CaseRuntimeContext.InputPathStatus.MISSING,
                CaseRuntimeContext.probeInputPath(input, "EXEC.INPUT.profile.absent"));
        assertEquals(CaseRuntimeContext.InputPathStatus.NULL_INTERMEDIATE,
                CaseRuntimeContext.probeInputPath(input, "EXEC.INPUT.nullable.id?"));
        assertEquals(CaseRuntimeContext.InputPathStatus.INVALID_PATH,
                CaseRuntimeContext.probeInputPath(input, "EXEC.INPUT.customer.id?"));
        assertEquals(CaseRuntimeContext.InputPathStatus.INVALID_PATH,
                CaseRuntimeContext.probeInputPath(input, "EXEC.INPUT.items.key?"));
        assertEquals(CaseRuntimeContext.InputPathStatus.INVALID_PATH,
                CaseRuntimeContext.probeInputPath(input, "exec.input.profile.id"));
    }

    @Test void permitsExplicitNullAndMakesEachEvaluationOwnItsMutableTree() throws Exception {
        Map<String, Object> definitions = new LinkedHashMap<String, Object>();
        definitions.put("nullable", "${EXEC.INPUT.explicitNull}");
        definitions.put("payload", mapOf("items", Arrays.asList("one", "two")));
        CaseRuntimeContext first = context("EXEC-1");
        CaseRuntimeContext second = context("EXEC-2");
        ExecutionBootstrapVariables.evaluate(definitions, first, engine());
        ExecutionBootstrapVariables.evaluate(definitions, second, engine());
        assertNull(first.require("EXEC.VARS.nullable"));
        ((List<Object>) ((Map<String, Object>) first.require("EXEC.VARS.payload")).get("items")).set(0, "mutated");
        assertEquals("one", ((List<?>) ((Map<?, ?>) second.require("EXEC.VARS.payload")).get("items")).get(0));
    }

    @Test void rejectsCyclesMissingDependenciesUnavailableRootsAndSideEffectingCalls() {
        Map<String, Object> cycle = mapOf("first", "${EXEC.VARS.second}", "second", "${EXEC.VARS.first}");
        IllegalArgumentException cyclic = assertThrows(IllegalArgumentException.class,
                () -> ExecutionBootstrapVariables.validate(cycle, engine()));
        assertTrue(cyclic.getMessage().contains("cycle"), cyclic.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionBootstrapVariables.validate(mapOf("value", "${EXEC.VARS.missing}"), engine()));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionBootstrapVariables.validate(mapOf("value", "${EXEC.ACTIONS.previous.output}"), engine()));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionBootstrapVariables.validate(mapOf("value", "#{file.delete(path='x')}"), engine()));
    }

    @Test void bootstrapSafetyUsesCanonicalBuiltInAliases() throws Exception {
        assertTrue(DefaultBuiltInProvider.isSafeForBootstrap("misc.string"));
        assertTrue(DefaultBuiltInProvider.isSafeForBootstrap("misc.number"));
        assertTrue(DefaultBuiltInProvider.isSafeForBootstrap("misc.boolean"));
        assertTrue(DefaultBuiltInProvider.isSafeForBootstrap("misc.prettyprint"));
        assertTrue(DefaultBuiltInProvider.isSafeForBootstrap("format.pretty"));
        assertFalse(DefaultBuiltInProvider.isSafeForBootstrap("date.sysdate"));
        assertFalse(DefaultBuiltInProvider.isSafeForBootstrap("file.delete"));
        assertFalse(DefaultBuiltInProvider.isSafeForBootstrap("seq.next"));
        assertFalse(DefaultBuiltInProvider.isSafeForBootstrap("misc.randomchoice"));

        CaseRuntimeContext context = context("EXEC-ALIAS");
        ExecutionBootstrapVariables.evaluate(mapOf("text", "#{misc.string(23)}"), context, engine());
        assertEquals("23", context.require("EXEC.VARS.text"));
    }

    @Test void failedEvaluationRollsBackPreviouslyResolvedEntries() {
        CaseRuntimeContext context = context("EXEC-9");
        Map<String, Object> definitions = mapOf("first", "ready", "broken", "#{1 / 0}");
        assertThrows(Exception.class, () -> ExecutionBootstrapVariables.evaluate(definitions, context, engine()));
        assertNull(context.resolve("EXEC.VARS.first"));
        assertNull(context.resolve("EXEC.VARS.broken"));
    }

    private CaseRuntimeContext context(String executionId) {
        Map<String, Object> inputs = mapOf("amount", Integer.valueOf(12), "explicitNull", null);
        TestCase testCase = new TestCase(1, "debug", "template", "row", Collections.<String>emptyList(),
                inputs, Collections.<String, StageCaseData>emptyMap(), "");
        Path output = temp.resolve(executionId);
        CaseRuntimeContext context = new CaseRuntimeContext(testCase, output, executionId, "RUN-1", output,
                output.resolve("case.log"), "debug", "2026-09-30T12:00:00Z", "2026-09-30T11:00:00Z");
        context.setProject(temp);
        context.setSourceMetadata("debug", temp.resolve("debug.yaml"), "debug");
        context.setTargetMetadata("template", "SIMPLE");
        context.setTemplateMetadata("SIMPLE", temp.resolve("templates/SIMPLE"));
        return context;
    }

    private UnifiedTemplateEngine engine() {
        return new UnifiedTemplateEngine(null, null, null, null, new DefaultBuiltInProvider());
    }

    private static Map<String, Object> mapOf(Object... values) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]);
        return result;
    }
}
