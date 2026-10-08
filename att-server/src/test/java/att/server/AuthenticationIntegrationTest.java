package att.server;

import org.apache.catalina.realm.MemoryRealm;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationIntegrationTest {
    @TempDir Path temp;

    @Test void tomcatBasicRealmProtectsServerApiAndSupportsConfiguredAnonymousMode() throws Exception {
        Path packages=Files.createDirectory(temp.resolve("packages"));Files.createDirectory(packages.resolve("p"));
        Path users=Files.writeString(temp.resolve("tomcat-users.xml"),"<tomcat-users><role rolename=\"ATT_USER\"/><role rolename=\"OTHER\"/><user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/><user username=\"other\" password=\"secret\" roles=\"OTHER\"/></tomcat-users>");
        Path config=Files.writeString(temp.resolve("server.yaml"),"server:\n  dataDir: "+temp.resolve("data")+"\n  authenticationRequired: true\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+packages+"\n  entries:\n    p: "+packages.resolve("p")+"\n");
        String old=System.getProperty("att.server.config");System.setProperty("att.server.config",config.toString());
        Tomcat tomcat=start(users,appDirectory("authenticated"));
        try {
            int port=tomcat.getConnector().getLocalPort();
            assertEquals(401,request(port,null));
            String basic="Basic "+Base64.getEncoder().encodeToString("att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals(200,request(port,basic));
            String other="Basic "+Base64.getEncoder().encodeToString("other:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals(200,request(port,other),"Any authenticated container principal has the same v1 API permissions");
            assertEquals(415,post(port,basic,"application/x-www-form-urlencoded",null));
            assertEquals(403,post(port,basic,"application/json","https://attacker.example"));
        } finally {tomcat.stop();tomcat.destroy();restore(old);}

        Path openConfig=Files.writeString(temp.resolve("open-server.yaml"),"server:\n  dataDir: "+temp.resolve("open-data")+"\n  authenticationRequired: false\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+packages+"\n  entries:\n    p: "+packages.resolve("p")+"\n");System.setProperty("att.server.config",openConfig.toString());
        Tomcat open=start(users,appDirectory("anonymous"));
        try {assertEquals(200,request(open.getConnector().getLocalPort(),null));}
        finally {open.stop();open.destroy();restore(old);}
    }

    private Tomcat start(Path users,Path app) throws Exception {
        Tomcat tomcat=new Tomcat();tomcat.setBaseDir(temp.resolve("tomcat-"+System.nanoTime()).toString());tomcat.setPort(0);tomcat.getConnector();MemoryRealm realm=new MemoryRealm();realm.setPathname(users.toString());tomcat.getEngine().setRealm(realm);tomcat.addWebapp("/att",app.toString());tomcat.start();return tomcat;
    }
    private Path appDirectory(String name)throws Exception {
        Path app=Files.createDirectory(temp.resolve(name));Path webInf=Files.createDirectories(app.resolve("WEB-INF"));Path classes=Files.createDirectories(webInf.resolve("classes"));Files.createDirectories(webInf.resolve("lib"));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));int index=0;
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))){Path entry=Path.of(element);if(Files.isDirectory(entry)){try(var paths=Files.walk(entry)){for(Path source:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator){Path target=classes.resolve(entry.relativize(source).toString());Files.createDirectories(target.getParent());if(!Files.exists(target))Files.copy(source,target);}}}else if(Files.isRegularFile(entry)&&entry.toString().endsWith(".jar")&&!entry.getFileName().toString().startsWith("tomcat-embed-")&&!entry.getFileName().toString().contains("servlet-api")){Path target=webInf.resolve("lib").resolve(entry.getFileName());Files.copy(entry,target,StandardCopyOption.REPLACE_EXISTING);}index++;}
        try(var input=Files.newInputStream(Path.of("att-server/src/main/webapp/WEB-INF/web.xml"))){Files.copy(input,webInf.resolve("web.xml"));}return app;
    }
    private static int post(int port,String authorization,String contentType,String origin)throws Exception {
        try(java.net.Socket socket=new java.net.Socket("127.0.0.1",port)) {socket.setSoTimeout(3000);java.io.OutputStream out=socket.getOutputStream();String body="{}";StringBuilder request=new StringBuilder("POST /att/api/v1/jobs/run HTTP/1.1\r\nHost: 127.0.0.1:").append(port).append("\r\nConnection: close\r\nAuthorization: ").append(authorization).append("\r\nContent-Type: ").append(contentType).append("\r\nContent-Length: ").append(body.length()).append("\r\n");if(origin!=null)request.append("Origin: ").append(origin).append("\r\n");request.append("\r\n").append(body);out.write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();String status=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)).readLine();return Integer.parseInt(status.split(" ")[1]);}
    }
    private static int request(int port,String authorization)throws Exception {HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/att/api/v1/packages").openConnection();connection.setConnectTimeout(3000);connection.setReadTimeout(3000);if(authorization!=null)connection.setRequestProperty("Authorization",authorization);int code=connection.getResponseCode();if(code==401)assertTrue(connection.getHeaderField("WWW-Authenticate").startsWith("Basic"));connection.disconnect();return code;}
    private static void restore(String old){if(old==null)System.clearProperty("att.server.config");else System.setProperty("att.server.config",old);}
}
