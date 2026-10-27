package att.server;

/** HTTP-only restrictions for artifact previews; stored ZIP entries stay byte-for-byte unchanged. */
final class ArtifactPreviewPolicy {
    static final String CONTENT_POLICY = "default-src 'none'; script-src 'sha256-DmkzPdiGxyCfiKKZtr34jiaf1ukqUeAK8RSwFF19ryg=' 'sha256-2n13XsaCAniqXDGJgtRR2kgh8VlBPDQQ3kb5BQLyzq4='; style-src 'unsafe-inline'; img-src data:; form-action 'none'; base-uri 'none'";
    private ArtifactPreviewPolicy() {}
}
