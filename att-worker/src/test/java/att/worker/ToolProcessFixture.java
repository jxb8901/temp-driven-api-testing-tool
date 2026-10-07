package att.worker;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Child process used by Worker shutdown regression coverage. */
public final class ToolProcessFixture {
    private ToolProcessFixture() { }

    public static void main(String[] args) throws Exception {
        Files.createFile(Paths.get(args[0]));
        Thread.sleep(6000L);
        Files.createFile(Paths.get(args[1]));
    }
}
