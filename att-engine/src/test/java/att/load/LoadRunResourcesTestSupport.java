package att.load;

import att.config.FrameworkConfig;
import att.exec.MqTransport;
import java.nio.file.Path;

/** Package bridge for API-layer tests that need the LOAD-scoped MQ transport seam. */
public final class LoadRunResourcesTestSupport {
    private LoadRunResourcesTestSupport() { }

    public static LoadRunResources withMqTransport(Path packageRoot, FrameworkConfig config,
                                                     MqTransport.Factory mqTransportFactory) {
        return new LoadRunResources(packageRoot, config, mqTransportFactory);
    }
}
