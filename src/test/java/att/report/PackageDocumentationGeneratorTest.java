/* Author: Jeffrey + ChatGPT */
package att.report;

import att.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class PackageDocumentationGeneratorTest {
    @TempDir Path tempDir;
    @Test void generatesModernSinglePageAndUniqueUnicodeIds() throws Exception {
        Files.createDirectories(tempDir.resolve("testcase")); Files.createDirectories(tempDir.resolve("templates"));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), Collections.<String,ToolConfig>emptyMap(), null, new RunConfig("timestamp", "yyyyMMdd"));
        Path result = new PackageDocumentationGenerator().generate(tempDir, config);
        String html = new String(Files.readAllBytes(result), "UTF-8");
        assertTrue(result.endsWith("index.html"));
        assertTrue(html.contains("id=\"workbookFilter\""));
        assertTrue(html.contains("id=\"sheetFilter\""));
        assertTrue(html.contains("id=\"caseFilter\""));
        assertTrue(html.contains("id=\"toolFilter\""));
        assertTrue(html.contains("position:sticky;top:0"));
        assertTrue(html.contains("href=\"#testcases\""));
        assertTrue(html.contains("data-tool="));
        assertTrue(html.contains("<strong>Index</strong>"));
        assertTrue(html.contains("Built-in functions"));
        assertNotEquals(HtmlSupport.id("中文一"), HtmlSupport.id("中文二"));
    }
}
