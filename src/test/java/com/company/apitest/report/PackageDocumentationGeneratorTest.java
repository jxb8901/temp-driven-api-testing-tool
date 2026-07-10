/* Author: Jeffrey + ChatGPT */
package com.company.apitest.report;

import com.company.apitest.config.*;
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
        Path result = new PackageDocumentationGenerator().generate(tempDir, config, true);
        String html = new String(Files.readAllBytes(result), "UTF-8");
        assertTrue(result.endsWith("single-page.html"));
        assertTrue(html.contains("Search English or 中文"));
        assertNotEquals(HtmlSupport.id("中文一"), HtmlSupport.id("中文二"));
    }
}
