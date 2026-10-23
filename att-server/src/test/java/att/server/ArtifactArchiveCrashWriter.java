package att.server;

import att.server.api.internal.DiagnosticSanitizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Child-process writer that can be killed after a ZIP entry is written but before finalization. */
public final class ArtifactArchiveCrashWriter {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]),archive=Path.of(args[1]),entered=Path.of(args[2]);
        ArtifactArchive.build(output,archive,path->DiagnosticSanitizer.redactText(Files.readString(path))
                .getBytes(StandardCharsets.UTF_8),0,path->{
                    Files.writeString(entered,path);
                    while(true)Thread.sleep(1000);
                });
    }
}
