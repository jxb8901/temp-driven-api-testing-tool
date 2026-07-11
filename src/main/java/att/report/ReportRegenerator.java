/* Author: Jeffrey + ChatGPT */
package att.report;

import att.core.ResultStatus;
import att.core.RunSummary;
import att.core.TestResult;
import att.core.ValidationResult;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Rebuilds report HTML from the persisted completed run manifest. */
public final class ReportRegenerator {
    @SuppressWarnings("unchecked")
    public Path regenerate(Path outputRoot, String runId) throws Exception {
        if (runId == null || runId.trim().isEmpty()) throw new IllegalArgumentException("report requires --run-id");
        Path runDir = outputRoot.resolve(runId).normalize();
        Path manifest = runDir.resolve("run.yaml");
        if (!Files.exists(manifest)) throw new IllegalArgumentException("Run manifest does not exist: " + manifest);
        Object loaded = new Yaml().load(new String(Files.readAllBytes(manifest), "UTF-8"));
        if (!(loaded instanceof Map)) throw new IllegalArgumentException("Invalid run manifest: " + manifest);
        Map<String, Object> run = (Map<String, Object>) loaded;
        if (!"COMPLETE".equals(String.valueOf(run.get("status")))) throw new IllegalArgumentException("Run is not COMPLETE: " + runId);
        List<TestResult> results = new ArrayList<TestResult>();
        Object cases = run.get("cases");
        if (cases instanceof Iterable) for (Object item : (Iterable<?>) cases) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> row = (Map<String, Object>) item;
            String caseId = String.valueOf(row.get("caseId"));
            Path log = String.valueOf(row.get("caseLog")).isEmpty() ? null : java.nio.file.Paths.get(String.valueOf(row.get("caseLog")));
            String caseName = row.get("caseName") == null ? caseId : String.valueOf(row.get("caseName"));
            results.add(new TestResult(caseId, caseName, ResultStatus.valueOf(String.valueOf(row.get("status"))),
                    Duration.ofMillis(longValue(row.get("durationMs"))), text(row.get("expected")), text(row.get("actual")), log, Collections.<ValidationResult>emptyList()));
        }
        Instant ended = run.get("endedAt") == null ? Files.getLastModifiedTime(manifest).toInstant() : Instant.parse(String.valueOf(run.get("endedAt")));
        Instant started = run.get("startedAt") == null ? ended : Instant.parse(String.valueOf(run.get("startedAt")));
        return new HtmlReportGenerator().generate(runDir, runId, new RunSummary(results, runDir), started, ended);
    }
    private long longValue(Object value) { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
