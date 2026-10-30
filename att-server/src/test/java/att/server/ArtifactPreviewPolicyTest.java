package att.server;

import att.load.LoadMetrics;
import att.load.LoadReportWriter;
import att.load.LoadRunResult;
import att.load.LoadScenario;
import att.load.LoadScenarioLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactPreviewPolicyTest {
    @TempDir Path temp;

    @Test void generatedLoadTrendScriptHashIsApprovedByBothViewerAndResponsePolicy() throws Exception {
        Instant started=Instant.parse("2026-10-29T00:00:00Z");
        Path scenarioPath=temp.resolve("load/trend-hash.yaml");
        Files.createDirectories(scenarioPath.getParent());
        Files.writeString(scenarioPath,"schemaVersion: att-load/v1.9\nworkloads:\n"
                +"  - id: trend-hash\n    target: {type: template, id: SIMPLE}\n"
                +"    load: {users: 1, duration: 2s}\n    execution: {thinkTime: 0ms}\n");
        LoadScenario scenario=new LoadScenarioLoader(temp).load(scenarioPath);
        LoadMetrics metrics=LoadMetrics.forScenario(scenario,started.toEpochMilli(),2_000L);
        metrics.finish(started.plusSeconds(2).toEpochMilli());
        Path report=new LoadReportWriter().write(temp,new LoadRunResult("trend-hash",scenario,started,started.plusSeconds(2),metrics.snapshot()));
        String html=Files.readString(report,StandardCharsets.UTF_8);
        Matcher matcher=Pattern.compile("<script>(.*?)</script>",Pattern.DOTALL).matcher(html);
        assertTrue(matcher.find(),"The generated Load report must contain its inline trend script.");
        String hash=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(matcher.group(1).getBytes(StandardCharsets.UTF_8)));
        assertEquals("JngTyYfak/+hWviebrkpXD20Pdm7CCCZsual7sBCLrQ=",hash,
                "Generated trend script bytes changed; review the new hash before updating allowlists.");
        assertTrue(ArtifactPreviewPolicy.APPROVED_REPORT_SCRIPT_HASHES.containsValue(hash));
        assertTrue(ArtifactPreviewPolicy.CONTENT_POLICY.contains("'sha256-"+hash+"'"));
        assertFalse(ArtifactPreviewPolicy.CONTENT_POLICY.contains("script-src 'unsafe-inline'"));
    }
}
