package att.worker;

/** Native child environment check without echoing assigned values. */
public final class PrewarmEnvironmentFixture {
    public static void main(String[] args) throws Exception {
        if(!"assigned".equals(System.getenv("LEASE_MARKER")))System.exit(3);
        java.nio.file.Files.write(java.nio.file.Paths.get(args[0]),"env-ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
