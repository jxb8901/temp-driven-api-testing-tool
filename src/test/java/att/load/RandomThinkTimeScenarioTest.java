package att.load;

import att.core.ExecutionOptions;
import att.validation.DiagnosticException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomThinkTimeScenarioTest {
    @TempDir Path temp;

    @Test
    void parsesUniformRangeSeedAndReportSafePolicyProjection() throws Exception {
        Path project = project();
        Path file = write(project, "range.yaml", "schemaVersion: att-load/v1.0\n"
                + "seed: 12345\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\n"
                + "load: {users: 2, duration: 1s}\n"
                + "execution:\n  thinkTime:\n    min: 0ms\n    max: 2s\n");

        LoadScenario scenario = new LoadScenarioLoader(project).load(file);
        assertEquals(Long.valueOf(12345L), scenario.seed());
        assertEquals(ThinkTimePolicy.Type.UNIFORM_RANGE, scenario.thinkTimePolicy().type());
        assertEquals(0L, scenario.thinkTimePolicy().minMillis());
        assertEquals(2000L, scenario.thinkTimePolicy().maxMillis());
        assertTrue(scenario.thinkTimePolicy().randomized());

        @SuppressWarnings("unchecked") Map<String, Object> execution = (Map<String, Object>) scenario.toSummaryMap().get("execution");
        assertEquals("uniform: 0ms..2s", execution.get("thinkTimePolicy"));
        assertTrue(execution.get("thinkTime") instanceof Map);
    }

    @Test
    void scalarAndEqualBoundsRemainFixedAndCliOverrideRemainsScalarOnly() throws Exception {
        Path project = project();
        Path scalar = write(project, "fixed.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\nload: {users: 1, duration: 1s}\n"
                + "execution: {thinkTime: 500ms}\n");
        LoadScenario fixed = new LoadScenarioLoader(project).load(scalar);
        assertEquals(ThinkTimePolicy.Type.FIXED, fixed.thinkTimePolicy().type());
        assertEquals(500L, fixed.thinkTimePolicy().minMillis());
        assertEquals("fixed: 500ms", ((Map<?, ?>) fixed.toSummaryMap().get("execution")).get("thinkTimePolicy"));

        Path range = write(project, "equal.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\nload: {users: 1, duration: 1s}\n"
                + "execution:\n  thinkTime: {min: 500ms, max: 500ms}\n");
        LoadScenario equal = new LoadScenarioLoader(project).load(range);
        assertEquals(ThinkTimePolicy.Type.FIXED, equal.thinkTimePolicy().type());
        assertFalse(equal.thinkTimePolicy().randomized());

        ExecutionOptions options = ExecutionOptions.parse(new String[]{"load", range.toString(), "--think-time", "250ms"});
        LoadScenario overridden = new LoadScenarioLoader(project).load(range, LoadOverrides.from(options));
        assertEquals(ThinkTimePolicy.Type.FIXED, overridden.thinkTimePolicy().type());
        assertEquals(250L, overridden.thinkTimePolicy().minMillis());
        assertEquals("250ms", overridden.toMap().get("execution") instanceof Map
                ? ((Map<?, ?>) overridden.toMap().get("execution")).get("thinkTime") : null);
    }

    @Test
    void rejectsInvalidRangeShapesOrderingSyntaxOverflowAndSeedOverflow() throws Exception {
        Path project = project();
        DiagnosticException reversed = invalid(project, "reversed.yaml",
                "execution:\n  thinkTime: {min: 2s, max: 500ms}\n");
        assertEquals("execution.thinkTime.max", reversed.field());

        DiagnosticException missing = invalid(project, "missing.yaml",
                "execution:\n  thinkTime: {min: 500ms}\n");
        assertTrue(missing.format().contains("max"));

        DiagnosticException syntax = invalid(project, "syntax.yaml",
                "execution:\n  thinkTime: {min: 500ms, max: banana}\n");
        assertTrue(syntax.format().contains("thinkTime"));

        DiagnosticException unknown = invalid(project, "unknown.yaml",
                "execution:\n  thinkTime: {min: 500ms, max: 2s, distribution: uniform}\n");
        assertTrue(unknown.format().contains("distribution") || unknown.format().contains("thinkTime"));

        DiagnosticException overflow = invalid(project, "overflow.yaml",
                "execution:\n  thinkTime: {min: 0ms, max: 999999999999999999999h}\n");
        assertTrue(overflow.format().contains("too large") || overflow.format().contains("thinkTime"));

        Path seedOverflow = write(project, "seed-overflow.yaml", base()
                + "seed: 9223372036854775808\n");
        DiagnosticException seed = assertThrows(DiagnosticException.class,
                () -> new LoadScenarioLoader(project).load(seedOverflow));
        assertEquals("seed", seed.field());
    }

    @Test
    void arrivalRateStillRejectsBothScalarAndRangeThinkTime() throws Exception {
        Path project = project();
        Path scalar = write(project, "arrival-fixed.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\n"
                + "load: {arrivalRate: 10/s, duration: 1s, maxConcurrent: 2, overloadPolicy: drop}\n"
                + "execution: {thinkTime: 10ms}\n");
        DiagnosticException fixed = assertThrows(DiagnosticException.class, () -> new LoadScenarioLoader(project).load(scalar));
        assertEquals("execution.thinkTime", fixed.field());

        Path range = write(project, "arrival-range.yaml", "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\n"
                + "load: {arrivalRate: 10/s, duration: 1s, maxConcurrent: 2, overloadPolicy: drop}\n"
                + "execution:\n  thinkTime: {min: 10ms, max: 20ms}\n");
        DiagnosticException ranged = assertThrows(DiagnosticException.class, () -> new LoadScenarioLoader(project).load(range));
        assertEquals("execution.thinkTime", ranged.field());
    }

    private DiagnosticException invalid(Path project, String name, String tail) throws Exception {
        Path file = write(project, name, base() + tail);
        return assertThrows(DiagnosticException.class, () -> new LoadScenarioLoader(project).load(file));
    }

    private String base() {
        return "schemaVersion: att-load/v1.0\n"
                + "target: {type: template, id: LOAD_TEMPLATE}\n"
                + "load: {users: 1, duration: 1s}\n";
    }

    private Path project() throws Exception {
        Path project = temp.resolve("project-" + System.nanoTime());
        Files.createDirectories(project.resolve("schemas"));
        Files.copy(Paths.get("schemas/att-load-v1.0.schema.json"), project.resolve("schemas/att-load-v1.0.schema.json"));
        return project;
    }

    private Path write(Path project, String name, String content) throws Exception {
        Path file = project.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
