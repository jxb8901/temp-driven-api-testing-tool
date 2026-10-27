package att.server;

import att.core.ResultStatus;
import att.load.LoadEvent;
import att.load.LoadMetrics;
import att.load.LoadReportWriter;
import att.load.LoadRunResult;
import att.load.LoadScenario;
import att.load.LoadScenarioLoader;
import att.load.LoadThresholdSummary;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactHtmlPolicyTest {
    @TempDir Path tempDir;

    @Test void keepsOnlyApprovedRelativeNavigationAndMarksMissingEvidence() throws Exception {
        String html="<html><head><style>body{color:#123}</style><base href='https://evil.test/'><meta http-equiv='refresh' content='0;url=https://evil.test/'></head><body>"
                +"<a href='../case%20log.log#details' target='_top' ping='https://evil.test'>Case</a>"
                +"<a href='../private.bin'>Unavailable</a><a href='https://evil.test/'>External</a>"
                +"<a href='javascript:alert(1)'>Active</a><a href='../../../../api/v1/jobs'>Escape</a>"
                +"<script>fetch('/api/v1/jobs')</script><img src='https://evil.test/track' onerror='alert(1)'>"
                +"<iframe srcdoc='<script>alert(1)</script>'></iframe><form action='/api/v1/jobs'><input></form></body></html>";
        byte[] bytes=ArtifactHtmlPolicy.publish(html.getBytes(StandardCharsets.UTF_8),"run/report/index.html",Set.of("run/report/index.html","run/case log.log"));
        var document=Jsoup.parse(new String(bytes,StandardCharsets.UTF_8));
        assertEquals("../case%20log.log#details",document.selectFirst("a").attr("href"));
        assertEquals(1,document.select("a[href]").size());assertTrue(document.text().contains("unavailable in exported archive"));
        assertEquals(0,document.select("script,iframe,form,base,[onerror],[target],[ping]").size());
        assertTrue(document.selectFirst("style").data().contains("color:#123"));
        assertEquals(ArtifactHtmlPolicy.CONTENT_POLICY,document.selectFirst("meta[http-equiv]").attr("content"));
        assertFalse(document.outerHtml().contains("evil.test"));assertFalse(document.outerHtml().contains("private.bin"));
    }

    @Test void preservesReviewedBuiltInReportControlsForOfflineUseButNoOtherScripts() throws Exception {
        String source=Files.readString(Path.of("att-engine/src/main/java/att/report/HtmlReportGenerator.java"));
        var matcher=Pattern.compile("html\\.append\\((\"<script>[^\\n]+\")\\);").matcher(source);
        assertTrue(matcher.find());
        String generated=ServerRuntime.JSON.readValue(matcher.group(1),String.class);
        String script=generated.substring("<script>".length(),generated.indexOf("</script>"));
        assertEquals(ArtifactHtmlPolicy.REPORT_SCRIPT_DIGEST,ArtifactArchive.sha256(script.getBytes(StandardCharsets.UTF_8)),"Review changes to the built-in offline control script before updating its allowlist");
        String html="<html><body><script>"+script+"</script><script>alert('injected')</script></body></html>";
        var document=Jsoup.parse(new String(ArtifactHtmlPolicy.publish(html.getBytes(StandardCharsets.UTF_8),"index.html",Set.of("index.html")),StandardCharsets.UTF_8));
        assertEquals(1,document.select("script:not([type=application/json])").size());
        assertEquals(script,document.selectFirst("script:not([type=application/json])").data());
    }

    @Test void preservesOnlyTheReviewedLoadChartScriptAndSafeSvgForOfflinePreview() throws Exception {
        Path scenarioPath=tempDir.resolve("load.yaml");
        Files.write(scenarioPath,Collections.singletonList("schemaVersion: att-load/v1.9\nworkloads:\n- target:\n    type: tool\n    id: sample.getAcDate\n  load: {users: 1, duration: 2s}\n  id: offline-chart"),StandardCharsets.UTF_8);
        LoadScenario scenario=new LoadScenarioLoader(tempDir).load(scenarioPath);
        Instant started=Instant.parse("2026-10-24T00:00:00Z");long startMs=started.toEpochMilli();
        LoadMetrics metrics=LoadMetrics.forScenario(scenario,startMs,3_000L);
        metrics.onEvent(LoadEvent.completed("offline", "closed", "STEADY", "iteration-1", "VU-1", 1,
                startMs+1_000L,startMs+1_000L,startMs+1_050L,ResultStatus.PASS));
        metrics.finish(startMs+3_000L);
        LoadRunResult result=new LoadRunResult("offline",scenario,started,started.plusSeconds(3),metrics.snapshot());
        Path report=new LoadReportWriter().write(tempDir.resolve("output"),result);
        String generated=Files.readString(report,StandardCharsets.UTF_8);
        var original=Jsoup.parse(generated);
        String script=original.select("script").stream().map(org.jsoup.nodes.Element::data)
                .filter(value->value.contains("renderCharts")).findFirst().orElseThrow(AssertionError::new);
        assertEquals(ArtifactHtmlPolicy.LOAD_REPORT_SCRIPT_DIGEST,
                ArtifactArchive.sha256(script.getBytes(StandardCharsets.UTF_8)),
                "Review changes to the offline chart renderer before updating its allowlist");

        String hostile=generated+"<script>alert('injected')</script><svg id='hostile' onload='alert(1)'><path d='M0 0' onmouseover='alert(1)'></path></svg>";
        byte[] published=ArtifactHtmlPolicy.publish(hostile.getBytes(StandardCharsets.UTF_8),
                "load/offline/report/index.html",Set.of("load/offline/report/index.html"));
        var document=Jsoup.parse(new String(published,StandardCharsets.UTF_8));
        assertEquals(1,document.select("script:not([type=application/json])").size());
        assertEquals(script,document.selectFirst("script:not([type=application/json])").data());
        assertEquals(1,document.select("script[type=application/json]#att-load-summary-data").size());
        assertTrue(document.select("svg#rate-chart[viewbox]").size()==1);
        assertTrue(document.select("svg#latency-chart,svg#concurrency-chart,svg#lag-chart,svg#failure-chart").size()==4);
        assertChartCards(document);
        assertEquals(0,document.select("[onload],[onmouseover],svg[href],script[src]").size());
        assertFalse(document.outerHtml().contains("alert('injected')"));

        Path archive=ArtifactArchive.build(tempDir.resolve("output"),tempDir.resolve("offline-report.zip"));
        byte[] archived=ArtifactArchive.read(archive,"load/offline/report/index.html");
        assertChartCards(Jsoup.parse(new String(archived,StandardCharsets.UTF_8)));
    }

    private void assertChartCards(org.jsoup.nodes.Document document) {
        var cards=document.select(".chart-grid > section.chart-card");
        assertEquals(5,cards.size(),"all five sanitized chart cards must retain their wrappers");
        for(org.jsoup.nodes.Element card:cards) {
            var children=card.children();
            assertTrue(children.size()==3||children.size()==4,card.outerHtml());
            assertEquals("h3",children.get(0).tagName(),"chart title must come first");
            assertEquals("svg",children.get(1).tagName(),"responsive plot must follow its title");
            assertEquals("ul",children.get(2).tagName(),"each chart legend must follow its plot");
            assertEquals("chart-legend",children.get(2).className());
            if(children.size()==4) assertEquals("p",children.get(3).tagName(),"optional chart note follows the legend");
            assertTrue(children.get(1).hasAttr("viewbox"));
        }
        String css=document.select("style").stream().map(org.jsoup.nodes.Element::data)
                .filter(value->value.contains(".chart-grid")).findFirst().orElseThrow(AssertionError::new);
        assertTrue(css.contains("grid-template-columns:minmax(0,1fr)"),"one full-width chart per row");
        assertTrue(css.contains(".chart-card{box-sizing:border-box;min-width:0;width:100%"));
        assertTrue(css.contains(".chart-card svg{display:block;width:100%;height:auto"));
        assertTrue(css.contains(".chart-legend{display:flex;flex-wrap:wrap;justify-content:flex-start"));
    }
}
