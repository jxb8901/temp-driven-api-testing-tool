package att.server;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.nio.file.Path;

@WebListener
public final class ServerBootstrap implements ServletContextListener {
    static final String RUNTIME="att.server.runtime";
    @Override public void contextInitialized(ServletContextEvent event) {
        try {
            Path configured=ServerConfig.configPath();
            ServerConfig config=ServerConfig.load(configured);
            Path libs=WorkerRuntimeClasspath.resolve();
            ServerRuntime runtime=new ServerRuntime(config,libs.toString());
            event.getServletContext().setAttribute(RUNTIME,runtime);
            event.getServletContext().log("ATT Server "+ServerBuildInfo.PRODUCT+" started; packageIds="+config.packages.keySet().size()+" maxConcurrent="+config.maxConcurrent);
            if(!config.authenticationRequired)event.getServletContext().log("WARNING: ATT Server anonymous mode is enabled. Every caller shares the 'anonymous' identity and can access shared drafts, jobs, and artifacts; there is no per-user isolation or accountability. Restrict access to loopback or a private interface and protect any proxy with a VPN, firewall or IP allowlist. Use TLS for traffic leaving localhost.");
        } catch(Exception e) {
            throw new IllegalStateException("ATT Server startup failed: "+e.getMessage(),e);
        }
    }
    @Override public void contextDestroyed(ServletContextEvent event) {
        Object value=event.getServletContext().getAttribute(RUNTIME);
        if(value instanceof ServerRuntime runtime)runtime.close();
    }
}
