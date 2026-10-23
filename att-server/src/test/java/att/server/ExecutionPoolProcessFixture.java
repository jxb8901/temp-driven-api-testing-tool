package att.server;

import java.nio.file.Path;
import java.util.Map;

/** Idle protocol fixture: no business request executes until EOF. */
public final class ExecutionPoolProcessFixture {
    public static void main(String[] args) throws Exception {
        if(System.getenv("POOL_SECRET")!=null)throw new IllegalStateException("Idle process inherited application environment");
        Map<String,Object> ready=Map.of("type","READY","protocolVersion",args.length==0?"att-prewarm/v1":"invalid",
                "productVersion",ServerBuildInfo.PRODUCT,"gitCommit",ServerBuildInfo.GIT_COMMIT,
                "schemaRegistryDigest","a".repeat(64),"cwd",Path.of("").toRealPath().toString());
        System.out.println(ServerRuntime.JSON.writeValueAsString(ready));System.out.flush();
        while(System.in.read()!=-1) { }
    }
}
