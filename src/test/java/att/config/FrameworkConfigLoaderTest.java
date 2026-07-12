/* Author: Jeffrey + ChatGPT */
package att.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkConfigLoaderTest {
    @TempDir Path tempDir;
    @Test void loadsV2AndRejectsGlobalStages() throws Exception {
        Path ok=tempDir.resolve("ok.yaml"); Files.write(ok,"schemaVersion: att-config/v2.1\noutputDirectory: out\ntools: {}\n".getBytes("UTF-8"));
        assertEquals(Paths.get("out"),new FrameworkConfigLoader().load(ok).outputDirectory());
        assertEquals(10000, new FrameworkConfigLoader().load(ok).timeoutMs());
        Path rooted=tempDir.resolve("rooted.yaml"); Files.write(rooted,"schemaVersion: att-config/v2.1\ntestcase: {root: cases/nested}\ntimeoutMs: 3600000\n".getBytes("UTF-8"));
        FrameworkConfig rootedConfig = new FrameworkConfigLoader().load(rooted);
        assertEquals(Paths.get("cases/nested"), rootedConfig.testcasesRoot());
        assertEquals(3600000, rootedConfig.timeoutMs());
        Path excessive=tempDir.resolve("excessive.yaml"); Files.write(excessive,"schemaVersion: att-config/v2.1\ntimeoutMs: 3600001\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class,()->new FrameworkConfigLoader().load(excessive));
        Path mismatch=tempDir.resolve("mismatch.yaml"); Files.write(mismatch,("schemaVersion: att-config/v2.1\ntools:\n  find:\n    name: 顯示 名稱 !\n    description: test\n    command: 'echo ${KeyWords}'\n    arguments:\n      keywords: {name: 關鍵 字詞 !, description: test, required: true}\n").getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class,()->new FrameworkConfigLoader().load(mismatch));
        Path direct=tempDir.resolve("direct.yaml"); Files.write(direct,("schemaVersion: att-config/v2.1\ntools:\n  find:\n    name: 顯示 名稱 !\n    description: test\n    command: 'echo ${keywords} ${input.keywords}'\n    arguments:\n      keywords: {name: 關鍵 字詞 !, description: test, required: true}\n").getBytes("UTF-8"));
        assertEquals("顯示 名稱 !", new FrameworkConfigLoader().load(direct).tool("find").name());
        Path bad=tempDir.resolve("bad.yaml"); Files.write(bad,"stages: []\n".getBytes("UTF-8"));
        assertThrows(IllegalArgumentException.class,()->new FrameworkConfigLoader().load(bad));
    }
}
