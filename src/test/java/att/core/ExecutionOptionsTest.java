/* Author: Jeffrey + ChatGPT */
package att.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionOptionsTest {
    @Test void parsesDocsAndSelectionOptions() {
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"docs"});
        assertEquals("docs", options.command());
        assertEquals("clean", ExecutionOptions.parse(new String[]{"clean"}).command());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"docs", "--single-page"}));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"run"}));
    }
    @Test void validateDefaultsToPackageAndRejectsCommandSpecificOptions() {
        assertEquals("package", ExecutionOptions.parse(new String[]{"validate"}).validationScope());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"version", "--all"}));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"validate", "--run-id", "x"}));
    }
    @Test void parsesCiOutputSelectionForRunOnly() {
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"run","--all","--ci-output","json"});
        assertEquals(java.util.Collections.singleton("json"), options.ciOutputs());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"validate","--package","--ci-output","junit"}));
    }
    @Test void parsesRunConcurrencyPolicy() {
        assertEquals("reject", ExecutionOptions.parse(new String[]{"run","--all"}).concurrencyMode());
        assertEquals("queue", ExecutionOptions.parse(new String[]{"run","--all","--queue"}).concurrencyMode());
        assertEquals("parallel", ExecutionOptions.parse(new String[]{"run","--all","--parallel"}).concurrencyMode());
        ExecutionOptions renamed = ExecutionOptions.parse(new String[]{"run","--all","--allow-parallel-runs","--profile"});
        assertEquals("parallel", renamed.concurrencyMode());
        assertTrue(renamed.profile());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"run","--all","--queue","--parallel"}));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"run","--all","--parallel","--allow-parallel-runs"}));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"validate","--parallel"}));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"run","--all","--concurrency","parallel"}));
    }
    @Test void rerunFailedIsACompleteSelectionAndAcceptsNarrowingFilters() {
        ExecutionOptions rerun = ExecutionOptions.parse(new String[]{"run","--rerun-failed"});
        assertTrue(rerun.rerunFailed());
        assertNull(rerun.suiteDirectory());
        ExecutionOptions narrowed = ExecutionOptions.parse(new String[]{"run","--rerun-failed","--tag","payment"});
        assertTrue(narrowed.tags().contains("payment"));
    }

    @Test void parsesStandaloneDebugTargetAndInput() {
        ExecutionOptions options = ExecutionOptions.parse(new String[]{"debug", "flow", "common.compose.v1",
                "--input", "templates/flows/common/compose/debug.yaml", "--output-dir", "out", "--verbose"});
        assertEquals("debug", options.command());
        assertEquals("flow", options.debugTargetType());
        assertEquals("common.compose.v1", options.debugTargetId());
        assertEquals(java.nio.file.Paths.get("templates/flows/common/compose/debug.yaml"), options.debugInput());
        assertEquals("debug", options.validationScope());
        assertTrue(options.verbose());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"debug", "template", "X", "--all"}));
    }

    @Test void unsafeFailureDetailsIsStandaloneDebugOnly() {
        ExecutionOptions debug = ExecutionOptions.parse(new String[]{"debug", "template", "SIMPLE", "--unsafe-failure-details"});
        assertTrue(debug.unsafeFailureDetails());
        assertFalse(ExecutionOptions.parse(new String[]{"debug", "template", "SIMPLE"}).unsafeFailureDetails());
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"run", "--all", "--unsafe-failure-details"}));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"load", "--debug", "template", "SIMPLE", "--unsafe-failure-details"}));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"validate", "--package", "--unsafe-failure-details"}));
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"snapshot", "--all", "--unsafe-failure-details"}));
    }

    @Test void parsesBootstrapOverridesAndLoadDebugPromotion() {
        ExecutionOptions debug = ExecutionOptions.parse(new String[]{"debug", "flow", "common.compose.v1",
                "--set", "vars.refNo=R001", "--set", "vars.copy=${EXEC.INPUT.refNo}"});
        assertEquals(2, debug.variableOverrides().size());
        assertEquals("vars.refNo=R001", debug.variableOverrides().get(0));
        ExecutionOptions load = ExecutionOptions.parse(new String[]{"load", "--debug", "template", "PAYMENT",
                "--input", "debug.yaml", "--users", "2", "--duration", "1s", "--set", "vars.refNo=R002"});
        assertTrue(load.loadDebug());
        assertEquals("template", load.debugTargetType());
        assertEquals("PAYMENT", load.debugTargetId());
        assertEquals("debug.yaml", load.debugInput().toString());
        assertEquals("2", load.loadUsers());
        ExecutionOptions tool = ExecutionOptions.parse(new String[]{"load", "--debug", "tool", "sample.echo"});
        assertTrue(tool.loadDebug());
        assertEquals("tool", tool.debugTargetType());
    }

    @Test void parsesNoTargetDiscoveryAndValidatesSetNamespaces() {
        assertEquals("debug", ExecutionOptions.parse(new String[]{"debug"}).command());
        assertEquals("json", ExecutionOptions.parse(new String[]{"debug", "--format", "json"}).format());
        assertNull(ExecutionOptions.parse(new String[]{"load"}).loadScenario());
        assertEquals("json", ExecutionOptions.parse(new String[]{"load", "--format", "json"}).format());
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionOptions.parse(new String[]{"debug", "template", "X", "--set", "other.value=1"}));
        assertThrows(IllegalArgumentException.class,
                () -> ExecutionOptions.parse(new String[]{"debug", "template", "X", "--set", "input.value="}));
    }

    @Test void rejectsConflictingLoadModels() {
        assertThrows(IllegalArgumentException.class, () -> ExecutionOptions.parse(new String[]{"load", "scenario.yaml",
                "--users", "2", "--arrival-rate", "5/s"}));
    }

    @Test void defaultsInteractiveCommandsToVerboseAndQuietRetainsAnOptOut() {
        ExecutionOptions run = ExecutionOptions.parse(new String[]{"run", "--all"});
        ExecutionOptions debug = ExecutionOptions.parse(new String[]{"debug", "template", "SIMPLE"});
        ExecutionOptions load = ExecutionOptions.parse(new String[]{"load", "scenario.yaml"});
        assertTrue(run.verbose());
        assertTrue(debug.verbose());
        assertTrue(load.verbose());
        assertTrue(ExecutionOptions.parse(new String[]{"debug", "template", "SIMPLE", "--verbose"}).verbose());
        assertTrue(ExecutionOptions.parse(new String[]{"load", "scenario.yaml", "--verbose"}).verbose());
        assertFalse(ExecutionOptions.parse(new String[]{"run", "--all", "--quiet"}).verbose());
        assertFalse(ExecutionOptions.parse(new String[]{"debug", "template", "SIMPLE", "--quiet"}).verbose());
        assertFalse(ExecutionOptions.parse(new String[]{"load", "scenario.yaml", "--quiet"}).verbose());
    }
}
