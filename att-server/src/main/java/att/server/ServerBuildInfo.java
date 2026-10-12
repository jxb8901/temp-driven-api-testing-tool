package att.server;

import java.io.InputStream;
import java.util.Properties;

/** Server-local view of the product build metadata, without loading Engine classes in Tomcat. */
final class ServerBuildInfo {
    static final String PRODUCT=property("att.version","unknown");
    static final String BUILD_TIME=property("att.buildTime","unknown");
    static final String GIT_COMMIT=property("att.gitCommit","unknown");

    private ServerBuildInfo() { }

    private static String property(String key,String fallback) {
        try(InputStream input=ServerBuildInfo.class.getResourceAsStream("/att-build.properties")) {
            if(input==null)return fallback;
            Properties values=new Properties();values.load(input);
            String value=values.getProperty(key);
            return value==null||value.trim().isEmpty()?fallback:value.trim();
        } catch(Exception ignored) { return fallback; }
    }
}
