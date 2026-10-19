package att.server;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactHtmlPolicyTest {
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
        assertEquals(1,document.select("script").size());assertEquals(script,document.selectFirst("script").data());
    }
}
