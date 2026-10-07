package att.worker;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Child process used by Worker shutdown regression coverage. */
public final class ToolProcessFixture {
    private ToolProcessFixture() { }

    public static void main(String[] args) throws Exception {
        if ("parent".equals(args[0])) {
            Files.createFile(Paths.get(args[1]));
            String executable = Paths.get(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
            Process child = new ProcessBuilder(executable, "-cp", System.getProperty("java.class.path"),
                    ToolProcessFixture.class.getName(), "child", args[2], args[3]).start();
            child.waitFor();
        } else {
            Files.createFile(Paths.get(args[1]));
            Thread.sleep(6000L);
            Files.createFile(Paths.get(args[2]));
        }
    }
}
