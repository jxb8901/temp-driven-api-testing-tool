package att.server;

import java.nio.file.Files;
import java.nio.file.Path;

/** Child-process writer that can be killed after a ZIP entry is written but before finalization. */
public final class ArtifactArchiveCrashWriter {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]),archive=Path.of(args[1]),entered=Path.of(args[2]);
        ArtifactArchive.build(output,archive,0,path->{
                    Files.writeString(entered,path);
                    while(true)Thread.sleep(1000);
                });
    }
}
