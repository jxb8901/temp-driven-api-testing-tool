/* Author: Jeffrey + ChatGPT */
package att.report;

import att.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class PackageDocumentationGeneratorTest {
    @TempDir Path tempDir;
    @Test void generatesModernSinglePageAndUniqueUnicodeIds() throws Exception {
        Files.createDirectories(tempDir.resolve("testcase")); Files.createDirectories(tempDir.resolve("templates"));
        LinkedHashMap<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("echo", new ToolConfig("echo", "Echo", "Global echo", "echo ok", "txt", Collections.<String,ToolArgumentConfig>emptyMap()));
        tools.put("sample.date", new ToolConfig("sample.date", "date", "sample", "Date", "Grouped date", Arrays.asList("date"), Arrays.asList("dispatch"), "txt", Collections.<String,ToolArgumentConfig>emptyMap(), null));
        FrameworkConfig config = new FrameworkConfig(Paths.get("output"), Paths.get("report"), Paths.get("logs"), "SIT", 30,
                Paths.get("templates"), tools, null, new RunConfig("timestamp", "yyyyMMdd"));
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
        assertTrue(html.contains("Global tools"));
        assertTrue(html.contains("Tool group: sample"));
        assertTrue(html.contains("sample.date"));
        assertTrue(html.contains("nvl(value, defaultValue)"));
        assertTrue(html.contains("iif(condition, trueValue, falseValue)"));
        assertTrue(html.contains("nchar(count, value)"));
        assertTrue(html.contains("substr(value, start[, length])"));
        assertTrue(html.contains("systimestamp()"));
        assertTrue(html.contains("dateAdd(value, amount, unit)"));
        assertTrue(html.contains("<h2>indexOf</h2>"));
        assertNotEquals(HtmlSupport.id("中文一"), HtmlSupport.id("中文二"));
    }
}
