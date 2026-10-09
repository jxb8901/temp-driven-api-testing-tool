package att.server;

import att.Version;
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
            String libs=event.getServletContext().getRealPath("/WEB-INF/lib");
            if(libs==null||!java.nio.file.Files.isDirectory(java.nio.file.Paths.get(libs)))throw new IllegalStateException("Tomcat must deploy an exploded WAR (enable unpackWARs) to launch isolated Workers");
            ServerRuntime runtime=new ServerRuntime(config,libs);
            event.getServletContext().setAttribute(RUNTIME,runtime);
            event.getServletContext().log("ATT Server "+Version.PRODUCT+" started; packageIds="+config.packages.keySet().size()+" maxConcurrent="+config.maxConcurrent);
        } catch(Exception e) {
            throw new IllegalStateException("ATT Server startup failed: "+e.getMessage(),e);
        }
    }
    @Override public void contextDestroyed(ServletContextEvent event) {
        Object value=event.getServletContext().getAttribute(RUNTIME);
        if(value instanceof ServerRuntime runtime)runtime.close();
    }
}
