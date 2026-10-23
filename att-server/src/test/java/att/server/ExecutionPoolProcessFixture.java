package att.server;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;

/** Idle protocol fixture: no business request executes until EOF. */
public final class ExecutionPoolProcessFixture {
    public static void main(String[] args) throws Exception {
        if(args.length>0&&"child".equals(args[0])) {Thread.sleep(30_000);return;}
        if(System.getenv("POOL_SECRET")!=null)throw new IllegalStateException("Idle process inherited application environment");
        if(args.length>0&&"descendant".equals(args[0])) {
            Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                    "-cp",System.getProperty("java.class.path"),ExecutionPoolProcessFixture.class.getName(),"child")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Path destination=Path.of(args[1]);
            Path staged=Files.createTempFile(destination.getParent(),".child-pid-",".tmp");
            try {
                Files.writeString(staged,Long.toString(child.pid()));
                try {Files.move(staged,destination,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
                catch(java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(staged,destination,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {Files.deleteIfExists(staged);}
        }
        Map<String,Object> ready=Map.of("type","READY","protocolVersion",args.length>0&&"invalid".equals(args[0])?"invalid":"att-prewarm/v1",
                "productVersion",ServerBuildInfo.PRODUCT,"gitCommit",ServerBuildInfo.GIT_COMMIT,
                "schemaRegistryDigest","a".repeat(64),"cwd",Path.of("").toRealPath().toString());
        System.out.println(ServerRuntime.JSON.writeValueAsString(ready));System.out.flush();
        while(System.in.read()!=-1) { }
    }
}
