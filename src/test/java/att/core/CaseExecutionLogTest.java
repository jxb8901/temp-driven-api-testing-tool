/* Author: Jeffrey + ChatGPT */
package att.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CaseExecutionLogTest {
    @TempDir Path tempDir;
    @Test void appendsUtf8StructuredEntries() throws Exception {
        Path file=tempDir.resolve("case.log"); new CaseExecutionLog(file).append("階段","完成");
        String text=new String(Files.readAllBytes(file),"UTF-8"); assertTrue(text.contains("階段")); assertTrue(text.contains("完成"));
    }
}
