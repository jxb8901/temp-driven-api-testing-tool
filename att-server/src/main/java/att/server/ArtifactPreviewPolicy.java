package att.server;

import java.util.Map;
import java.util.stream.Collectors;

/** HTTP-only restrictions for artifact previews; stored ZIP entries stay byte-for-byte unchanged. */
final class ArtifactPreviewPolicy {
    static final Map<String, String> APPROVED_REPORT_SCRIPT_HASHES = Map.of(
            "legacy-report-v1", "DmkzPdiGxyCfiKKZtr34jiaf1ukqUeAK8RSwFF19ryg=",
            "legacy-report-v2", "2n13XsaCAniqXDGJgtRR2kgh8VlBPDQQ3kb5BQLyzq4=",
            "load-report-4.2.7", "JngTyYfak/+hWviebrkpXD20Pdm7CCCZsual7sBCLrQ=");
    static final String CONTENT_POLICY = "default-src 'none'; script-src "
            + APPROVED_REPORT_SCRIPT_HASHES.values().stream().map(hash -> "'sha256-" + hash + "'")
                    .collect(Collectors.joining(" "))
            + "; style-src 'unsafe-inline'; img-src data:; form-action 'none'; base-uri 'none'";
    private ArtifactPreviewPolicy() {}
}
