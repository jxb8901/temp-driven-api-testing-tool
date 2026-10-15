package att.server;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.catalina.realm.MemoryRealm;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.Context;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.OutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticationIntegrationTest {
    @TempDir Path temp;
    private String previousAttHome;
    private String previousServerConfig;

    @BeforeEach void captureServerConfig() {previousServerConfig=System.getProperty("att.server.config");}
    @AfterEach void restoreAttHome() {
        if (previousAttHome == null) System.clearProperty("att.home");
        else System.setProperty("att.home", previousAttHome);
        restore(previousServerConfig);
    }

    @Test void trustedGatewayOriginsPreserveLocalAuthenticationAndCsrfForBothOriginModes() throws Exception {
        previousAttHome=System.getProperty("att.home");
        System.setProperty("att.home",createLocalRuntime().toString());
        for(boolean strict:List.of(false,true)) {
            Path base=Files.createDirectories(temp.resolve("trusted-origin-"+strict));
            Path packages=Files.createDirectories(base.resolve("packages"));Path pkg=Files.createDirectory(packages.resolve("p"));
            Path data=base.resolve("data");
            Path config=Files.writeString(base.resolve("server.yaml"),"server:\n  dataDir: "+data
                    +"\n  auth:\n    mode: local\n  security:\n    checkCrossOrigin: "+strict
                    +"\n    trustedOrigins: ['https://gateway.example:443/']\nworkers: {}\npackages:\n  allowedRoots: ["+packages+"]\n  entries:\n    p: "+pkg+"\n");
            System.setProperty("att.server.config",config.toString());
            Path users=Files.writeString(base.resolve("users.xml"),"<tomcat-users></tomcat-users>");
            Tomcat tomcat=start(users,appDirectory("trusted-origin-app-"+strict),true);
            try {
                int port=tomcat.getConnector().getLocalPort();HttpClient client=HttpClient.newHttpClient();
                String gateway="https://gateway.example";
                String password="origin policy secure password";
                String credentials="{\"loginId\":\"owner\",\"password\":\""+password+"\"}";
                String bootstrap="{\"token\":\""+Files.readString(data.resolve("bootstrap-admin.token")).strip()
                        +"\",\"loginId\":\"owner\",\"displayName\":\"Owner\",\"password\":\""+password+"\"}";
                assertEquals(403,sendViaTrustedProxy(client,port,"POST","/att/api/v1/auth/bootstrap","https://gateway.example.evil.test",bootstrap).statusCode());
                assertEquals(201,sendViaTrustedProxy(client,port,"POST","/att/api/v1/auth/bootstrap",gateway,bootstrap).statusCode());
                for(String origin:List.of("null","https://gateway.example.evil.test","https://gateway.example:8443"))
                    assertEquals(403,sendViaTrustedProxy(client,port,"POST","/att/api/v1/auth/login",origin,credentials).statusCode(),origin);
                HttpResponse<String> login=sendViaTrustedProxy(client,port,"POST","/att/api/v1/auth/login",gateway,credentials);
                assertEquals(200,login.statusCode(),login.body());
                String setCookie=login.headers().firstValue("Set-Cookie").orElseThrow();
                assertTrue(setCookie.contains("; Secure"));assertTrue(setCookie.contains("Path=/att/"));assertTrue(setCookie.contains("SameSite=Lax"));
                String cookie=setCookie.split(";",2)[0];
                String csrf=sendWithCookie(client,port,"GET","/att/api/v1/auth/me",cookie,null,null,null).headers().firstValue("X-ATT-CSRF").orElseThrow();
                for(String token:List.of("","invalid"))
                    assertEquals(403,sendWithCookie(client,port,"POST","/att/api/v1/auth/logout",cookie,gateway,token,"{}").statusCode());
                if(strict)assertEquals(403,sendWithCookie(client,port,"POST","/att/api/v1/auth/logout",cookie,"https://attacker.example",csrf,"{}").statusCode());
                String basic="Basic "+Base64.getEncoder().encodeToString(("owner:"+password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                String basicCsrf=sendBasicViaTrustedProxy(client,port,"GET","/att/api/v1/auth/me",gateway,null,basic,null).headers().firstValue("X-ATT-CSRF").orElseThrow();
                String newUser="{\"loginId\":\"viewer\",\"displayName\":\"Viewer\",\"password\":\"viewer secure password\",\"role\":\"USER\"}";
                assertEquals(403,sendBasicViaTrustedProxy(client,port,"POST","/att/api/v1/auth/users",gateway,newUser,basic,null).statusCode());
                assertEquals(403,sendBasicViaTrustedProxy(client,port,"POST","/att/api/v1/auth/users",gateway,newUser,basic,"invalid").statusCode());
                if(strict)assertEquals(403,sendBasicViaTrustedProxy(client,port,"POST","/att/api/v1/auth/users","https://attacker.example",newUser,basic,basicCsrf).statusCode());
                assertEquals(201,sendBasicViaTrustedProxy(client,port,"POST","/att/api/v1/auth/users",gateway,newUser,basic,basicCsrf).statusCode());
                assertEquals(401,send(client,port,"GET","/att/api/v1/packages",gateway,null,null).statusCode());
                HttpRequest insecure=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/att/api/v1/auth/login"))
                        .header("Origin",gateway).header("X-Forwarded-For","203.0.113.9").header("X-Forwarded-Proto","http")
                        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(credentials)).build();
                assertHttpsRequired(client.send(insecure,HttpResponse.BodyHandlers.ofString()));
                assertEquals(200,sendWithCookie(client,port,"POST","/att/api/v1/auth/logout",cookie,gateway,csrf,"{}").statusCode());
            } finally {try{tomcat.stop();}finally{tomcat.destroy();}}
        }
    }

    @Test void localAuthenticationBootstrapsLogsInEnforcesCsrfAndSupportsAdminUserManagement() throws Exception {
        previousAttHome=System.getProperty("att.home");Path localHome=createLocalRuntime();System.setProperty("att.home",localHome.toString());
        Path packages=Files.createDirectory(temp.resolve("local-auth-packages"));Path pkg=Files.createDirectory(packages.resolve("p"));
        Path data=temp.resolve("local-auth-data");Path config=Files.writeString(temp.resolve("local-auth.yaml"),"server:\n  dataDir: "+data+"\n  auth:\n    mode: local\n  security:\n    checkCrossOrigin: false\nworkers: {}\npackages:\n  allowedRoots:\n    - "+packages+"\n  entries:\n    p: "+pkg+"\n");
        String oldConfig=System.getProperty("att.server.config");System.setProperty("att.server.config",config.toString());
        Path users=Files.writeString(temp.resolve("local-auth-users.xml"),"<tomcat-users></tomcat-users>");Tomcat tomcat=start(users,appDirectory("local-auth"),true,true);
        try {
            int port=tomcat.getConnector().getLocalPort();String origin="http://localhost:"+port;HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            assertEquals(401,send(client,port,"GET","/att/api/v1/packages",null,null,null).statusCode());
            HttpResponse<String> ui=send(client,port,"GET","/att/ui/",null,null,null);assertEquals(200,ui.statusCode());assertTrue(ui.body().contains("id=\"root\""));String uiCsp=ui.headers().firstValue("Content-Security-Policy").orElseThrow();java.util.regex.Matcher uiNonce=java.util.regex.Pattern.compile("style-src 'self' 'nonce-([^']+)'").matcher(uiCsp);assertTrue(uiCsp.contains("style-src-attr 'unsafe-inline'"),uiCsp);assertFalse(uiCsp.contains("script-src 'self' 'unsafe-inline'"),uiCsp);assertTrue(uiNonce.find(),uiCsp);assertTrue(ui.body().contains("nonce=\""+uiNonce.group(1)+"\""),"Studio HTML must carry the matching CodeMirror CSP nonce");
            assertEquals(200,send(client,port,"GET","/att/ui/login.html",null,null,null).statusCode());
            assertEquals(200,send(client,port,"GET","/att/api/v1/auth/bootstrap/status",null,null,null).statusCode());
            String token=Files.readString(data.resolve("bootstrap-admin.token")).strip();
            HttpResponse<String> crossOriginBootstrap=send(client,port,"POST","/att/api/v1/auth/bootstrap","https://attacker.example",null,"{\"token\":\""+token+"\",\"loginId\":\"owner\",\"displayName\":\"ATT Owner\",\"password\":\"correct horse battery staple\"}");
            assertEquals(403,crossOriginBootstrap.statusCode());
            assertEquals("ATT-SERVER-ORIGIN-MISMATCH",ServerRuntime.JSON.readTree(crossOriginBootstrap.body()).path("error").path("code").asText());
            assertEquals(403,sendViaTrustedProxyScheme(client,port,"POST","/att/api/v1/auth/bootstrap","http","{\"token\":\""+token+"\",\"loginId\":\"owner\",\"displayName\":\"ATT Owner\",\"password\":\"correct horse battery staple\"}").statusCode(),"Bootstrap credentials must not be accepted over non-loopback HTTP");
            assertEquals(403,sendViaTrustedProxyScheme(client,port,"POST","/att/api/v1/auth/login","http","{\"loginId\":\"owner\",\"password\":\"correct horse battery staple\"}").statusCode(),"Login credentials must not be accepted over non-loopback HTTP");
            HttpResponse<String> setup=send(client,port,"POST","/att/api/v1/auth/bootstrap",origin,null,"{\"token\":\""+token+"\",\"loginId\":\"owner\",\"displayName\":\"ATT Owner\",\"password\":\"correct horse battery staple\"}");assertEquals(201,setup.statusCode(),setup.body());
            assertFalse(setup.body().contains("passwordHash"));assertFalse(Files.exists(data.resolve("bootstrap-admin.token")));
            HttpResponse<String> proxyLogin=sendViaTrustedProxy(client,port,"POST","/att/api/v1/auth/login","https://att.example.test","{\"loginId\":\"owner\",\"password\":\"correct horse battery staple\"}");
            assertEquals(200,proxyLogin.statusCode(),proxyLogin.body());String proxyCookie=proxyLogin.headers().firstValue("Set-Cookie").orElseThrow();assertTrue(proxyCookie.contains("; Secure"));assertTrue(proxyCookie.contains("Path=/att/"));assertTrue(proxyCookie.contains("HttpOnly"));assertTrue(proxyCookie.contains("SameSite=Lax"));
            String basic="Basic "+Base64.getEncoder().encodeToString("owner:correct horse battery staple".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            HttpResponse<String> basicMe=sendBasicViaTrustedProxy(client,port,"GET","/att/api/v1/auth/me","https://att.example.test",null,basic,null);assertEquals(200,basicMe.statusCode());assertTrue(basicMe.body().contains("owner"));String basicCsrf=basicMe.headers().firstValue("X-ATT-CSRF").orElseThrow();
            assertEquals(500,sendBasicViaTrustedProxy(client,port,"GET","/att/api/v1/test-failure","https://att.example.test",null,basic,null).statusCode(),"A downstream servlet failure must remain a server error after Basic authentication succeeds");
            assertEquals(403,sendBasicViaTrustedProxy(client,port,"POST","/att/api/v1/auth/users","https://att.example.test","{}",basic,null).statusCode(),"Browser-originated Basic-auth mutations require the returned CSRF token");
            String secondUser="{\"loginId\":\"basic-user\",\"displayName\":\"Basic User\",\"password\":\"basic user secure password\",\"role\":\"USER\"}";
            String crossOriginBasicUser="{\"loginId\":\"cross-origin-basic-user\",\"displayName\":\"Cross-Origin Basic User\",\"password\":\"cross origin basic secure password\",\"role\":\"USER\"}";
            assertEquals(201,sendBasicViaTrustedProxy(client,port,"POST","/att/api/v1/auth/users","https://attacker.example",crossOriginBasicUser,basic,basicCsrf).statusCode(),"checkCrossOrigin=false permits a verified cross-origin Basic mutation after the CSRF token is supplied");
            assertEquals(201,sendBasicViaTrustedProxy(client,port,"POST","/att/api/v1/auth/users","https://att.example.test",secondUser,basic,basicCsrf).statusCode());
            HttpResponse<String> localLogin=send(client,port,"POST","/att/api/v1/auth/login",origin,null,"{\"loginId\":\"owner\",\"password\":\"correct horse battery staple\"}");assertEquals(200,localLogin.statusCode());String ownerCookie=localLogin.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
            HttpResponse<String> me=sendWithCookie(client,port,"GET","/att/api/v1/auth/me",ownerCookie,null,null,null);assertEquals(200,me.statusCode(),me.body());assertFalse(me.body().contains("passwordHash"));String csrf=me.headers().firstValue("X-ATT-CSRF").orElseThrow();
            JsonNode ownerCapabilities=ServerRuntime.JSON.readTree(sendWithCookie(client,port,"GET","/att/api/v1/auth/capabilities",ownerCookie,null,null,null).body());
            assertTrue(ownerCapabilities.path("configurationAdmin").asBoolean());
            HttpResponse<String> adminConfiguration=sendWithCookie(client,port,"GET","/att/api/v1/packages/p/configuration/effective?includeAllFields=true",ownerCookie,null,null,null);
            assertEquals(200,adminConfiguration.statusCode(),adminConfiguration.body());
            assertEquals("no-store",adminConfiguration.headers().firstValue("Cache-Control").orElse(""));
            Class.forName("org.h2.Driver"); // Tomcat may deregister its webapp-loaded driver between embedded contexts.
            try(java.sql.Connection audit=java.sql.DriverManager.getConnection("jdbc:h2:file:"+data.resolve("db/att-server"),"sa","");
                java.sql.PreparedStatement query=audit.prepareStatement("SELECT COUNT(*) FROM audit WHERE principal=? AND action='CONFIGURATION_INCLUDE_ALL_FIELDS' AND package_id='p'")) {
                query.setString(1,"owner");try(java.sql.ResultSet rows=query.executeQuery()){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
            }
            assertEquals(400,sendWithCookie(client,port,"GET","/att/api/v1/packages/p/configuration?view=declared&includeAllFields=true",ownerCookie,null,null,null).statusCode(),
                    "includeAllFields is meaningful only on Effective and Compare projections");
            assertEquals(200,sendWithCookie(client,port,"GET","/att/api/v1/packages",ownerCookie,null,null,null).statusCode());
            HttpResponse<String> failedPasswordChange=sendWithCookie(client,port,"POST","/att/api/v1/auth/password",ownerCookie,origin,csrf,
                    "{\"currentPassword\":\"incorrect current password\",\"newPassword\":\"replacement secure password\"}");
            assertEquals(401,failedPasswordChange.statusCode());
            assertEquals(200,sendWithCookie(client,port,"GET","/att/api/v1/auth/me",ownerCookie,null,null,null).statusCode(),
                    "A failed current-password check must preserve the existing session");
            assertHttpsRequired(sendWithCookieViaTrustedProxyScheme(client,port,"/att/api/v1/auth/password",ownerCookie,"http",csrf,
                    "{\"currentPassword\":\"correct horse battery staple\",\"newPassword\":\"replacement secure password\"}"));
            assertHttpsRequired(sendWithCookieViaTrustedProxyScheme(client,port,"/att/api/v1/auth/users",ownerCookie,"http",csrf,
                    "{\"loginId\":\"plaintext-user\",\"displayName\":\"Plaintext User\",\"password\":\"plaintext secure password\",\"role\":\"USER\"}"));
            assertEquals(403,sendWithCookie(client,port,"POST","/att/api/v1/auth/logout",ownerCookie,origin,null,"{}").statusCode());
            String viewerBody="{\"loginId\":\"viewer\",\"displayName\":\"Read Only\",\"password\":\"viewer secure password\",\"role\":\"USER\"}";
            HttpResponse<String> crossOriginWithoutCsrf=sendWithCookie(client,port,"POST","/att/api/v1/auth/users",ownerCookie,"http://attacker.example",null,viewerBody);
            assertEquals(403,crossOriginWithoutCsrf.statusCode());
            assertEquals("ATT-AUTH-CSRF-INVALID",ServerRuntime.JSON.readTree(crossOriginWithoutCsrf.body()).path("error").path("code").asText());
            assertEquals(201,sendWithCookie(client,port,"POST","/att/api/v1/auth/users",ownerCookie,"http://attacker.example",csrf,viewerBody).statusCode(),
                    "checkCrossOrigin=false relaxes origin equality while retaining local cookie CSRF protection");
            HttpResponse<String> viewerLogin=send(client,port,"POST","/att/api/v1/auth/login",origin,null,"{\"loginId\":\"viewer\",\"password\":\"viewer secure password\"}");assertEquals(200,viewerLogin.statusCode());String viewerCookie=viewerLogin.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
            assertHttpsRequired(sendWithCookieViaTrustedProxyScheme(client,port,"/att/api/v1/auth/users/viewer/password",ownerCookie,"http",csrf,
                    "{\"password\":\"plaintext reset password\"}"));
            assertEquals(200,sendWithCookie(client,port,"POST","/att/api/v1/auth/users/viewer/password",ownerCookie,origin,csrf,"{\"password\":\"viewer reset secure password\"}").statusCode());
            assertEquals(401,sendWithCookie(client,port,"GET","/att/api/v1/auth/me",viewerCookie,null,null,null).statusCode(),"Admin password reset must revoke existing sessions");
            assertEquals(401,send(client,port,"POST","/att/api/v1/auth/login",origin,null,"{\"loginId\":\"viewer\",\"password\":\"viewer secure password\"}").statusCode());
            viewerLogin=send(client,port,"POST","/att/api/v1/auth/login",origin,null,"{\"loginId\":\"viewer\",\"password\":\"viewer reset secure password\"}");assertEquals(200,viewerLogin.statusCode());viewerCookie=viewerLogin.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
            JsonNode viewerCapabilities=ServerRuntime.JSON.readTree(sendWithCookie(client,port,"GET","/att/api/v1/auth/capabilities",viewerCookie,null,null,null).body());
            assertFalse(viewerCapabilities.path("configurationAdmin").asBoolean());
            assertEquals(403,sendWithCookie(client,port,"GET","/att/api/v1/packages/p/configuration/effective?includeAllFields=true",viewerCookie,null,null,null).statusCode());
            HttpResponse<String> submitted=sendWithCookie(client,port,"POST","/att/api/v1/jobs/run",ownerCookie,origin,csrf,"{\"packageId\":\"p\",\"all\":true}");assertEquals(202,submitted.statusCode(),submitted.body());
            String ownedJobId=ServerRuntime.JSON.readTree(submitted.body()).path("jobId").asText();assertTrue(ownedJobId.matches("J[0-9A-F]{16}"));
            assertEquals(200,sendWithCookie(client,port,"POST","/att/api/v1/auth/logout",ownerCookie,origin,csrf,"{}").statusCode());
            assertEquals(401,sendWithCookie(client,port,"GET","/att/api/v1/packages",ownerCookie,null,null,null).statusCode());
            assertEquals(403,sendWithCookie(client,port,"GET","/att/api/v1/auth/users",viewerCookie,null,null,null).statusCode());
            assertEquals(200,sendWithCookie(client,port,"GET","/att/api/v1/packages",viewerCookie,null,null,null).statusCode());
            assertEquals(404,sendWithCookie(client,port,"GET","/att/api/v1/jobs/"+ownedJobId,viewerCookie,null,null,null).statusCode(),"Known IDs must not expose another local user's job");
            assertEquals(404,sendWithCookie(client,port,"GET","/att/api/v1/jobs/"+ownedJobId+"/events",viewerCookie,null,null,null).statusCode(),"SSE must use the same local identity ownership check");
            assertEquals(404,sendWithCookie(client,port,"GET","/att/api/v1/jobs/"+ownedJobId+"/artifacts",viewerCookie,null,null,null).statusCode(),"Artifact listings must enforce the same local identity ownership check");
            assertEquals(404,sendWithCookie(client,port,"GET","/att/api/v1/jobs/"+ownedJobId+"/artifacts/result.json",viewerCookie,null,null,null).statusCode(),"Artifact downloads must enforce the same local identity ownership check");
        } finally {try{tomcat.stop();}finally{tomcat.destroy();if(oldConfig==null)System.clearProperty("att.server.config");else System.setProperty("att.server.config",oldConfig);}}
    }

    private static HttpResponse<String> send(HttpClient client,int port,String method,String path,String origin,String csrf,String body)throws Exception {
        URI uri=URI.create("http://localhost:"+port+path);HttpRequest.Builder request=HttpRequest.newBuilder(uri);
        if(origin!=null)request.header("Origin",origin);if(csrf!=null)request.header("X-ATT-CSRF",csrf);
        if("GET".equals(method))request.GET();else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body==null?"{}":body));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> sendWithCookie(HttpClient client,int port,String method,String path,String cookie,String origin,String csrf,String body)throws Exception {
        URI uri=URI.create("http://localhost:"+port+path);HttpRequest.Builder request=HttpRequest.newBuilder(uri).header("Cookie",cookie);
        if(origin!=null)request.header("Origin",origin);if(csrf!=null)request.header("X-ATT-CSRF",csrf);
        if("GET".equals(method))request.GET();else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body==null?"{}":body));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> sendViaTrustedProxy(HttpClient client,int port,String method,String path,String origin,String body)throws Exception {
        HttpRequest request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Origin",origin).header("X-Forwarded-For","203.0.113.9").header("X-Forwarded-Proto","https").header("X-Forwarded-Host","att.example.test").header("X-Forwarded-Port","443").header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> sendViaTrustedProxyScheme(HttpClient client,int port,String method,String path,String scheme,String body)throws Exception {
        int forwardedPort="https".equals(scheme)?443:80;
        HttpRequest request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Origin",scheme+"://att.example.test").header("X-Forwarded-For","203.0.113.9").header("X-Forwarded-Proto",scheme).header("X-Forwarded-Host","att.example.test").header("X-Forwarded-Port",Integer.toString(forwardedPort)).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> sendWithCookieViaTrustedProxyScheme(HttpClient client,int port,String path,String cookie,String scheme,String csrf,String body)throws Exception {
        int forwardedPort="https".equals(scheme)?443:80;
        HttpRequest request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Origin",scheme+"://att.example.test")
                .header("Cookie",cookie).header("X-ATT-CSRF",csrf).header("X-Forwarded-For","203.0.113.9")
                .header("X-Forwarded-Proto",scheme).header("X-Forwarded-Host","att.example.test").header("X-Forwarded-Port",Integer.toString(forwardedPort))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private static void assertHttpsRequired(HttpResponse<String> response)throws Exception {
        assertEquals(403,response.statusCode(),response.body());
        assertEquals("ATT-AUTH-HTTPS-REQUIRED",ServerRuntime.JSON.readTree(response.body()).path("error").path("code").asText());
    }
    private static HttpResponse<String> sendBasicViaTrustedProxy(HttpClient client,int port,String method,String path,String origin,String body,String authorization,String csrf)throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Origin",origin).header("X-Forwarded-For","203.0.113.10").header("X-Forwarded-Proto","https").header("X-Forwarded-Host","att.example.test").header("X-Forwarded-Port","443").header("Authorization",authorization);
        if(csrf!=null)request.header("X-ATT-CSRF",csrf);
        if("GET".equals(method))request.GET();else request.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body==null?"{}":body));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void tomcatBasicRealmProtectsServerApiAndSupportsConfiguredAnonymousMode() throws Exception {
        previousAttHome=System.getProperty("att.home");
        Path localHome=createLocalRuntime();
        System.setProperty("att.home",localHome.toString());
        Path packages=Files.createDirectory(temp.resolve("packages"));Files.createDirectory(packages.resolve("p"));
        Path debugPackage=Files.createDirectory(packages.resolve("debug"));Path template=Files.createDirectories(debugPackage.resolve("templates/FORM"));
        Files.createDirectories(debugPackage.resolve("testcase"));Files.createDirectories(debugPackage.resolve("config"));copySchemas(debugPackage);
        Files.writeString(debugPackage.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"environments:\n  SIT: {}\ntestcase:\n  root: testcase\ntemplates:\n  root: templates\n");
        Files.writeString(template.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: FORM\ndescription: authenticated debug form\nactions:\n"
                +"  log:\n    type: log\n    message: '${EXEC.INPUT.value}'\n");
        Path loadDirectory=Files.createDirectories(debugPackage.resolve("load"));
        Files.writeString(loadDirectory.resolve("path-based.yaml"),"schemaVersion: att-load/v1.6\nworkloads:\n- id: path-based\n  target:\n    type: template\n    id: FORM\n  load:\n    users: 1\n    duration: 1s\n");
        Files.writeString(template.resolve("debug.yaml"),"schemaVersion: att-debug/v1.2\ninputs:\n  value: safe\n"
                +"  payload: {account: 'customer account 123456789', secret: 'temporary credential violet-123'}\n");
        Path users=Files.writeString(temp.resolve("tomcat-users.xml"),"<tomcat-users><role rolename=\"ATT_USER\"/><role rolename=\"OTHER\"/><user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/><user username=\"other\" password=\"secret\" roles=\"OTHER\"/></tomcat-users>");
        Path config=Files.writeString(temp.resolve("server.yaml"),"server:\n  dataDir: "+yaml(temp.resolve("data"))+"\n  authenticationRequired: true\n  security:\n    checkCrossOrigin: true\n  inlineLoad:\n    enabled: true\n    maxWorkloads: 1\nworkers:\n  maxConcurrent: 2\n  queuedLimit: 2\n  maxConcurrentLoad: 2\npackages:\n  allowedRoots:\n    - "+yaml(packages)+"\n  entries:\n    p: "+yaml(packages.resolve("p"))+"\n    debug: "+yaml(debugPackage)+"\n");
        String old=System.getProperty("att.server.config");System.setProperty("att.server.config",config.toString());
        Tomcat tomcat=start(users,appDirectory("authenticated"),true);
        try {
            int port=tomcat.getConnector().getLocalPort();
            assertEquals(401,request(port,null));
            assertEquals(401,get(port,"/att/ui/index.html",null));
            String basic="Basic "+Base64.getEncoder().encodeToString("att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals(200,request(port,basic));
            String other="Basic "+Base64.getEncoder().encodeToString("other:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));assertEquals(200,request(port,other),"Any authenticated container principal has the same v1 API permissions");
            assertEquals(200,get(port,"/att/ui/index.html",basic));
            assertEquals(200,get(port,"/att/ui/app.js",basic));
            JsonNode resources=getJson(port,"/att/api/v1/packages/debug/resources?type=template",basic);
            String resourceId=resources.path("items").get(0).path("resourceId").asText();
            String formPath="/att/api/v1/packages/debug/resources/template/"+resourceId+"/debug-form";
            assertEquals(401,get(port,formPath,null),"Debug defaults must remain behind container authentication");
            assertEquals(401,postStatus(port,null,"/att/api/v1/drafts/debug",Map.of("packageId","debug")),"Draft creation must remain behind container authentication");
            JsonNode form=getJson(port,formPath,basic);
            assertFalse(form.toString().contains("123456789"));assertFalse(form.toString().contains("violet-123"));
            JsonNode draftBody=ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","target",Map.of("type","template","id","FORM"),"input",form.path("input")));
            JsonNode draft=postJson(port,"/att/api/v1/drafts/debug",basic,draftBody);
            assertFalse(draft.toString().contains("123456789"));assertFalse(draft.toString().contains("violet-123"));
            String draftId=draft.path("draftId").asText();
            assertEquals(200,get(port,"/att/api/v1/drafts/"+draftId,basic));
            assertEquals(404,get(port,"/att/api/v1/drafts/"+draftId,other),"Drafts must be isolated by the authenticated principal");
            JsonNode loadPolicy=getJson(port,"/att/api/v1/packages/debug/load-policy?model=virtualUsers",basic);
            assertEquals("virtualUsers",loadPolicy.path("model").asText());
            assertTrue(getJson(port,"/att/api/v1/version",basic).path("inlineLoadEnabled").asBoolean(false));
            JsonNode advancedScenario=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion","att-load/v1.6",
                    "load",Map.of("users",1,"duration","1s"),"workloads",List.of(Map.of("id","auth-load",
                            "load",Map.of("users",1),"target",Map.of("type","template","id","FORM"),
                            "inputs",Map.of("value","from-advanced-load")))));
            JsonNode advancedDraft=postJson(port,"/att/api/v1/drafts/load",basic,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","scenario",advancedScenario)));
            String advancedDraftId=advancedDraft.path("draftId").asText();assertTrue(advancedDraftId.startsWith("A"));
            assertEquals(200,get(port,"/att/api/v1/drafts/"+advancedDraftId,basic));
            assertEquals(404,get(port,"/att/api/v1/drafts/"+advancedDraftId,other),"Advanced Load drafts must be isolated by the authenticated principal");
            Map<String,Object> firstWorkload=Map.of("id","auth-load-one","load",Map.of("users",1),
                    "target",Map.of("type","template","id","FORM"),"inputs",Map.of("value","within-limit"));
            Map<String,Object> secondWorkload=Map.of("id","auth-load-two","load",Map.of("users",1),
                    "target",Map.of("type","template","id","FORM"),"inputs",Map.of("value","over-limit"));
            JsonNode overLimitScenario=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion","att-load/v1.6",
                    "load",Map.of("users",1,"duration","1s"),"workloads",List.of(firstWorkload,secondWorkload)));
            JsonNode limitError=postErrorJson(port,"/att/api/v1/drafts/load",basic,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","scenario",overLimitScenario)),400);
            assertEquals("ATT-SERVER-INLINE-LOAD-LIMIT",limitError.path("error").path("code").asText());
            assertTrue(limitError.path("error").path("summary").asText().contains("maxWorkloads"));
            JsonNode acceptedLoad=postJson(port,"/att/api/v1/jobs/load",basic,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","draftId",advancedDraftId,"principal","Spoofed(anonym)")),202);
            String loadJobId=acceptedLoad.path("jobId").asText();long loadDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            JsonNode loadJob=getJson(port,"/att/api/v1/jobs/"+loadJobId,basic);
            while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(loadJob.path("status").asText())&&System.nanoTime()<loadDeadline) {
                Thread.sleep(20);loadJob=getJson(port,"/att/api/v1/jobs/"+loadJobId,basic);
            }
            assertEquals("PASS",loadJob.path("status").asText(),"The authenticated Advanced Load REST route must validate and submit its immutable Worker scenario");
            assertEquals("att",loadJob.path("principal").asText(),"Authenticated requests must ignore caller-supplied attribution labels");
            assertFalse(loadJob.has("attributionPrincipal"),"Attribution reuses the existing principal property");
            JsonNode longScenario=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion","att-load/v1.6",
                    "load",Map.of("users",1,"duration","30s"),"workloads",List.of(Map.of("id","cancel-resume",
                            "load",Map.of("users",1),"execution",Map.of("thinkTime","500ms"),
                            "target",Map.of("type","template","id","FORM"),"inputs",Map.of("value","cancel-resume")))));
            JsonNode longDraft=postJson(port,"/att/api/v1/drafts/load",basic,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","scenario",longScenario)));
            JsonNode cancellable=postJson(port,"/att/api/v1/jobs/load",basic,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","draftId",longDraft.path("draftId").asText())),202);
            String cancellableJobId=cancellable.path("jobId").asText();
            assertTrue(cancellableJobId.matches("J[0-9A-F]{16}"));
            long resumeAfter=assertRetainedSseEvent(port,basic,cancellableJobId);
            assertEquals(200,cancelJob(port,basic,cancellableJobId),"The real Tomcat API must accept cancellation of an active Load job");
            long cancelDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            JsonNode cancelledJob=getJson(port,"/att/api/v1/jobs/"+cancellableJobId,basic);
            while(!"CANCELLED".equals(cancelledJob.path("status").asText())&&System.nanoTime()<cancelDeadline) {
                Thread.sleep(20);cancelledJob=getJson(port,"/att/api/v1/jobs/"+cancellableJobId,basic);
            }
            assertEquals("CANCELLED",cancelledJob.path("status").asText(),"The running Load job must reach a terminal cancellation state");
            assertLastEventIdResume(port,basic,cancellableJobId,resumeAfter);
            // Exercise the real public API behind the authenticated browser console.
            String jobId=submitJob(port,basic);
            assertEquals(200,get(port,"/att/api/v1/jobs/"+jobId,basic));
            assertEquals(200,get(port,"/att/api/v1/jobs/"+jobId+"/artifacts",basic));
            assertRetainedSseEvent(port,basic,jobId);
            assertEquals(200,cancelJob(port,basic,jobId));
            assertEquals(200,get(port,"/att/api/v1/jobs/"+jobId+"/result",basic));
            assertEquals(415,post(port,basic,"application/x-www-form-urlencoded",null));
            assertEquals(403,post(port,basic,"application/json","https://attacker.example"));
            HttpResponse<String> unauthenticatedOrigin=send(HttpClient.newHttpClient(),port,"POST","/att/api/v1/jobs/run","https://attacker.example",null,"{}");
            assertEquals(401,unauthenticatedOrigin.statusCode(),"Container authentication rejects the request before the API origin policy can return a JSON error");
            assertEquals(400,post(port,basic,"application/json","https://att.example.test",java.util.Map.of(
                    "X-Forwarded-For","203.0.113.10","X-Forwarded-Proto","https",
                    "X-Forwarded-Host","att.example.test","X-Forwarded-Port","443")),
                    "Trusted HTTPS proxy headers must make the external Origin match the Servlet request");
            assertEquals(400,postBody(port,basic,"application/json",null,"{not-json"));
            assertFiveSseObserversReceiveLiveEvents(tomcat,port,basic);
        } finally {tomcat.stop();tomcat.destroy();restore(old);}

        Path disabledConfig=Files.writeString(temp.resolve("inline-load-disabled.yaml"),"server:\n  dataDir: "+yaml(temp.resolve("inline-load-disabled-data"))+"\n  authenticationRequired: true\n  inspection:\n    enabled: true\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 1\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+yaml(packages)+"\n  entries:\n    debug: "+yaml(debugPackage)+"\n");
        System.setProperty("att.server.config",disabledConfig.toString());
        Tomcat inlineLoadDisabled=start(users,appDirectory("inline-load-disabled"));
        try {
            int port=inlineLoadDisabled.getConnector().getLocalPort();
            String basic="Basic "+Base64.getEncoder().encodeToString("att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(getJson(port,"/att/api/v1/version",basic).path("inlineLoadEnabled").asBoolean(true));
            assertEquals(403,get(port,"/att/api/v1/packages/debug/load-policy?model=virtualUsers",basic));
            assertEquals(403,get(port,"/att/api/v1/packages/debug/resources/template/not-real/quick-load-form?model=virtualUsers",basic));
            assertEquals(403,get(port,"/att/api/v1/drafts/L"+"0".repeat(32),basic));
            assertEquals(403,get(port,"/att/api/v1/drafts/A"+"0".repeat(32),basic));
            assertEquals(403,postStatus(port,basic,"/att/api/v1/drafts/quick-load",Map.of()));
            assertEquals(403,postStatus(port,basic,"/att/api/v1/drafts/load",Map.of()));
            assertEquals(403,postStatus(port,basic,"/att/api/v1/jobs/load",Map.of("packageId","debug","draftId","A"+"0".repeat(32))));
            JsonNode pathBased=postJson(port,"/att/api/v1/jobs/load",basic,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","scenario","load/path-based.yaml")),202);
            JsonNode pathBasedStatus=getJson(port,"/att/api/v1/jobs/"+pathBased.path("jobId").asText(),basic);
            assertEquals("load",pathBasedStatus.path("command").asText(),"Disabling browser drafts must still admit the existing path-based Load API");
        } finally {inlineLoadDisabled.stop();inlineLoadDisabled.destroy();restore(old);}

        Path openConfig=Files.writeString(temp.resolve("open-server.yaml"),"server:\n  dataDir: "+yaml(temp.resolve("open-data"))+"\n  authenticationRequired: false\n  inlineLoad:\n    enabled: true\n    maxWorkloads: 1\n  inspection:\n    enabled: true\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 2\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+yaml(packages)+"\n  entries:\n    p: "+yaml(packages.resolve("p"))+"\n    debug: "+yaml(debugPackage)+"\n");System.setProperty("att.server.config",openConfig.toString());
        Tomcat open=start(users,appDirectory("anonymous"),true);
        String anonymousDraftToInvalidate=null;
        try {
            int port=open.getConnector().getLocalPort();
            assertEquals(200,request(port,null));assertEquals(200,get(port,"/att/ui/index.html",null));
            assertEquals(200,get(port,"/att/ui/app.js",null));
            assertEquals(200,get(port,"/att/api/v1/health",null));
            assertEquals("4.2.0",getJson(port,"/att/api/v1/version",null).path("version").asText());
            assertEquals("debug",getJson(port,"/att/api/v1/packages/debug",null).path("packageId").asText());
            JsonNode resources=getJson(port,"/att/api/v1/packages/debug/resources?type=template",null);
            String resourceId=resources.path("items").get(0).path("resourceId").asText();
            String resourcePath="/att/api/v1/packages/debug/resources/template/"+resourceId;
            JsonNode form=getJson(port,resourcePath+"/debug-form",null);
            assertEquals("FORM",form.path("target").path("id").asText());
            assertEquals("declared",getJson(port,"/att/api/v1/packages/debug/configuration?view=declared",null).path("view").asText());
            JsonNode configFiles=getJson(port,"/att/api/v1/packages/debug/configuration/files?limit=100",null);
            assertEquals("ready",configFiles.path("state").asText());
            assertTrue(configFiles.path("items").isArray());
            JsonNode configSource=getJson(port,"/att/api/v1/packages/debug/configuration/source?path=config/config.yaml",null);
            assertTrue(configSource.has("available"));
            assertEquals("virtualUsers",getJson(port,"/att/api/v1/packages/debug/load-policy?model=virtualUsers",null).path("model").asText());

            // Browser requests pass through the reverse-proxy context path and retain same-origin checks.
            JsonNode debugDraft=postJsonViaTrustedProxy(port,"/att/api/v1/drafts/debug",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","target",Map.of("type","template","id","FORM"),"input",form.path("input"))),201);
            String debugDraftId=debugDraft.path("draftId").asText();
            assertTrue(debugDraftId.startsWith("D"));
            assertEquals(200,get(port,"/att/api/v1/drafts/"+debugDraftId,null),"Anonymous callers share one draft identity");
            JsonNode debugAccepted=postJsonViaTrustedProxy(port,"/att/api/v1/jobs/debug",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","draftId",debugDraftId,"principal","Debug User")),202);
            JsonNode debugJob=waitForTerminalJob(port,debugAccepted.path("jobId").asText());
            assertEquals("Debug User(anonym)",debugJob.path("principal").asText());
            assertEquals("PASS",debugJob.path("status").asText());

            JsonNode quickForm=getJson(port,resourcePath+"/quick-load-form?model=virtualUsers",null);
            JsonNode quickDraft=postJsonViaTrustedProxy(port,"/att/api/v1/drafts/quick-load",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","target",Map.of("type","template","id","FORM"),"model","virtualUsers",
                            "input",quickForm.path("input"),"load",Map.of("users",1,"duration","1s"),"execution",Map.of(),"testdata",List.of())),201);
            String quickDraftId=quickDraft.path("draftId").asText();
            assertTrue(quickDraftId.startsWith("L"));
            assertEquals(200,get(port,"/att/api/v1/drafts/"+quickDraftId,null));
            JsonNode quickAccepted=postJsonViaTrustedProxy(port,"/att/api/v1/jobs/load",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","draftId",quickDraftId,"principal","Quick User")),202);
            JsonNode quickJob=waitForTerminalJob(port,quickAccepted.path("jobId").asText());
            assertEquals("Quick User(anonym)",quickJob.path("principal").asText());
            assertEquals("PASS",quickJob.path("status").asText());

            JsonNode advancedScenario=ServerRuntime.JSON.valueToTree(Map.of("schemaVersion","att-load/v1.6",
                    "load",Map.of("users",1,"duration","1s"),"workloads",List.of(Map.of("id","anonymous-load",
                            "load",Map.of("users",1),"target",Map.of("type","template","id","FORM"),"inputs",Map.of("value","anonymous")))));
            JsonNode advancedDraft=postJsonViaTrustedProxy(port,"/att/api/v1/drafts/load",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","scenario",advancedScenario)),201);
            String advancedDraftId=advancedDraft.path("draftId").asText();
            assertTrue(advancedDraftId.startsWith("A"));
            assertEquals(200,get(port,"/att/api/v1/drafts/"+advancedDraftId,null));
            JsonNode advancedAccepted=postJsonViaTrustedProxy(port,"/att/api/v1/jobs/load",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","draftId",advancedDraftId,"principal","Advanced User")),202);
            JsonNode advancedJob=waitForTerminalJob(port,advancedAccepted.path("jobId").asText());
            assertEquals("Advanced User(anonym)",advancedJob.path("principal").asText());
            assertEquals("PASS",advancedJob.path("status").asText());

            // ATT Remote's no-auth request flow uses the same Server identity for each legacy operation.
            JsonNode invalidName=postErrorJson(port,"/att/api/v1/jobs/run",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","p","all",true,"principal","Bob(anonym)")),400);
            assertEquals("ATT-SERVER-INVALID-REQUEST",invalidName.path("error").path("code").asText(),"Callers cannot supply or double the reserved anonymous suffix");
            JsonNode remoteAccepted=postJson(port,"/att/api/v1/jobs/run",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","p","all",true,"principal","Bob")),202);
            String remoteJobId=remoteAccepted.path("jobId").asText();
            JsonNode remoteJob=waitForTerminalJob(port,remoteJobId);
            assertEquals("Bob(anonym)",remoteJob.path("principal").asText());
            JsonNode validateAccepted=postJson(port,"/att/api/v1/jobs/validate",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","validationScope","package","principal","Alice")),202);
            assertEquals("Alice(anonym)",waitForTerminalJob(port,validateAccepted.path("jobId").asText()).path("principal").asText());
            JsonNode remoteDebugAccepted=postJson(port,"/att/api/v1/jobs/debug",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","target",Map.of("type","template","id","FORM"),"principal","Carol")),202);
            assertEquals("Carol(anonym)",waitForTerminalJob(port,remoteDebugAccepted.path("jobId").asText()).path("principal").asText());
            JsonNode remoteLoadAccepted=postJson(port,"/att/api/v1/jobs/load",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","scenario","load/path-based.yaml","principal","Dan")),202);
            assertEquals("Dan(anonym)",waitForTerminalJob(port,remoteLoadAccepted.path("jobId").asText()).path("principal").asText());
            assertEquals(200,get(port,"/att/api/v1/jobs",null));
            JsonNode namedJobs=getJson(port,"/att/api/v1/jobs?principal=Bob%28anonym%29",null).path("items");
            assertEquals(1,namedJobs.size(),"Principal search filters on the existing persisted principal column");
            assertEquals("Bob(anonym)",namedJobs.get(0).path("principal").asText());
            assertEquals(200,get(port,"/att/api/v1/jobs/"+remoteJobId+"/artifacts",null));
            assertRetainedSseEvent(port,null,remoteJobId);
            assertEquals(200,cancelJob(port,null,remoteJobId));
            assertEquals(200,get(port,"/att/api/v1/jobs/"+remoteJobId+"/result",null));
            assertEquals(400,post(port,null,"application/json","https://attacker.example",Map.of(
                    "X-Forwarded-For","203.0.113.10","X-Forwarded-Proto","https",
                    "X-Forwarded-Host","att.example.test","X-Forwarded-Port","443")),
                    "Anonymous mode defaults to origin-check relaxation; request validation still applies");
            JsonNode unlabelledAccepted=postJson(port,"/att/api/v1/jobs/run",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","p","all",true)),202);
            JsonNode unlabelledJob=waitForTerminalJob(port,unlabelledAccepted.path("jobId").asText());
            assertEquals("anonymous",unlabelledJob.path("principal").asText(),"Omitting the label keeps the shared default identity");
            JsonNode blankLabelAccepted=postJson(port,"/att/api/v1/jobs/run",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","p","all",true,"principal"," \t ")),202);
            JsonNode blankLabelJob=waitForTerminalJob(port,blankLabelAccepted.path("jobId").asText());
            assertEquals("anonymous",blankLabelJob.path("principal").asText(),"A blank label uses the same stable default as an omitted label");
            assertRemoteNoAuthCommands(port);
            assertTrue(auditCount(temp.resolve("open-data"),"anonymous")>0,"Unlabelled anonymous jobs keep the shared default identity in audit records");
            assertTrue(auditCount(temp.resolve("open-data"),"Bob(anonym)")>0,"Named anonymous job submissions use their server-formatted principal in audit records");
            JsonNode pendingDraft=postJsonViaTrustedProxy(port,"/att/api/v1/drafts/debug",null,
                    ServerRuntime.JSON.valueToTree(Map.of("packageId","debug","target",Map.of("type","template","id","FORM"),"input",form.path("input"))),201);
            anonymousDraftToInvalidate=pendingDraft.path("draftId").asText();
        }
        finally {open.stop();open.destroy();restore(old);}

        Files.writeString(openConfig,Files.readString(openConfig).replace("authenticationRequired: false","authenticationRequired: true"));
        System.setProperty("att.server.config",openConfig.toString());
        Tomcat restarted=start(users,appDirectory("anonymous-restart-authenticated"));
        try {
            int port=restarted.getConnector().getLocalPort();String path="/att/api/v1/drafts/"+anonymousDraftToInvalidate;
            String basic="Basic "+Base64.getEncoder().encodeToString("att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(401,get(port,path,null),"Enabling authentication on restart must protect the former anonymous draft");
            assertEquals(404,get(port,path,basic),"Anonymous drafts are discarded on restart and never transferred to authenticated users");
        } finally {restarted.stop();restarted.destroy();restore(old);}
    }

    @Test void inspectorDisclosureAndAnonymousSchedulePoliciesWorkThroughTomcat() throws Exception {
        previousAttHome=System.getProperty("att.home");Path localHome=createLocalRuntime();System.setProperty("att.home",localHome.toString());
        String oldConfig=System.getProperty("att.server.config");
        Path packages=Files.createDirectory(temp.resolve("inspection-packages"));Path packageRoot=Files.createDirectory(packages.resolve("debug"));
        Path template=Files.createDirectories(packageRoot.resolve("templates/FORM"));
        Files.createDirectories(packageRoot.resolve("testcase"));Files.createDirectories(packageRoot.resolve("config"));
        Files.createDirectories(packageRoot.resolve("load"));Files.createDirectories(packageRoot.resolve("testdata"));Files.createDirectories(packageRoot.resolve("payloads"));copySchemas(packageRoot);
        Files.writeString(packageRoot.resolve("config/config.yaml"),"schemaVersion: att-config/v2.12\nenvironment: SIT\n"
                +"environments:\n  SIT:\n    testdata: [testdata/customers.yaml]\n  UAT: {}\ntestcase:\n  root: testcase\ntemplates:\n  root: templates\n");
        Files.writeString(packageRoot.resolve("testdata/customers.yaml"),"schemaVersion: att-testdata/v1.0\nid: customers\nrecords:\n"
                +"  - accountId: A-1001\n    status: ACTIVE\n    email: customer@example.test\n  - accountId: A-1002\n    status: CLOSED\n    email: other@example.test\nselection:\n  strategy: sequential\n");
        Files.writeString(packageRoot.resolve("payloads/request.xml"),"<request><accountId>A-1001</accountId><email>private@example.test</email>"
                +"<address>12 Main Street</address><password>payload-secret</password></request>");
        Files.writeString(template.resolve("template.yaml"),"schemaVersion: att-template/v3.6\nname: FORM\ndescription: ordinary template description\nactions:\n"
                +"  log:\n    type: log\n    message: '&{payloads/request.xml}'\n");
        Files.writeString(packageRoot.resolve("load/scenario.yaml"),"schemaVersion: att-load/v1.6\nworkloads:\n"
                +"  - id: smoke\n    target: {type: template, id: FORM}\n    load: {users: 1, duration: 1s}\n");
        Path users=Files.writeString(temp.resolve("inspection-users.xml"),"<tomcat-users><role rolename=\"ATT_USER\"/><user username=\"att\" password=\"secret\" roles=\"ATT_USER\"/></tomcat-users>");
        Path authenticatedConfig=Files.writeString(temp.resolve("inspection-authenticated.yaml"),"server:\n  dataDir: "+yaml(temp.resolve("inspection-authenticated-data"))
                +"\n  authenticationRequired: true\n  inspection:\n    enabled: true\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 2\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+yaml(packages)+"\n  entries:\n    debug: "+yaml(packageRoot)+"\n");
        Tomcat authenticated=null,anonymous=null;
        try {
            System.setProperty("att.server.config",authenticatedConfig.toString());
            authenticated=start(users,appDirectory("inspection-authenticated"),true);
            int port=authenticated.getConnector().getLocalPort();
            String basic="Basic "+Base64.getEncoder().encodeToString("att:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JsonNode resources=getJson(port,"/att/api/v1/packages/debug/resources?type=template",basic);
            String resourceId=resources.path("items").get(0).path("resourceId").asText();
            String resourcePath="/att/api/v1/packages/debug/resources/template/"+resourceId;
            assertEquals(401,get(port,resourcePath+"/source",null));
            assertEquals(401,get(port,"/att/api/v1/packages/debug/configuration/testdata/customers/records?environment=SIT",null));
            JsonNode source=getJson(port,resourcePath+"/source",basic);
            assertTrue(source.path("available").asBoolean(),source.toString());
            assertTrue(source.path("resource").path("sourceAvailable").asBoolean());
            assertFalse(source.path("resource").path("redactionEnabled").asBoolean(true));
            assertTrue(source.path("text").asText().contains("ordinary template description"));
            assertEquals(1,source.path("fileReferences").size());
            String referenceId=source.path("fileReferences").get(0).path("referenceId").asText();
            assertEquals("payloads/request.xml",source.path("fileReferences").get(0).path("logicalPath").asText());
            assertEquals(401,get(port,resourcePath+"/source-file?referenceId="+referenceId,null));
            JsonNode referenced=getJson(port,resourcePath+"/source-file?referenceId="+referenceId,basic);
            assertTrue(referenced.path("available").asBoolean(),referenced.toString());
            assertTrue(referenced.path("text").asText().contains("A-1001"));
            assertTrue(referenced.path("text").asText().contains("private@example.test"));
            assertTrue(referenced.path("text").asText().contains("12 Main Street"));
            assertFalse(referenced.path("text").asText().contains("payload-secret"));
            JsonNode records=getJson(port,"/att/api/v1/packages/debug/configuration/testdata/customers/records?environment=SIT&offset=0&limit=1",basic);
            assertFalse(records.path("redactionEnabled").asBoolean(true));assertEquals(2,records.path("total").asInt());
            assertEquals(1,records.path("nextOffset").asInt());
            assertEquals("A-1001",records.path("records").get(0).path("value").path("accountId").asText());
            assertEquals("customer@example.test",records.path("records").get(0).path("value").path("email").asText());
            assertTrue(records.toString().contains("customer@example.test"));
            JsonNode compare=getJson(port,"/att/api/v1/packages/debug/configuration/compare?left=SIT&right=UAT&section=globals&offset=0&limit=50",basic);
            assertEquals("globals",compare.path("section").asText());
            assertTrue(compare.path("fields").size()>0);
            for(JsonNode field:compare.path("fields"))assertTrue(field.path("path").asText().startsWith("globals."));
            assertEquals(404,get(port,"/att/api/v1/load-schedules",basic),"The scheduling feature gate remains independent of authentication");
        } finally {if(authenticated!=null){authenticated.stop();authenticated.destroy();}}

        Path anonymousConfig=Files.writeString(temp.resolve("inspection-anonymous.yaml"),"server:\n  dataDir: "+yaml(temp.resolve("inspection-anonymous-data"))
                +"\n  authenticationRequired: false\n  inspection:\n    enabled: true\n  scheduling:\n    enabled: true\nworkers:\n  maxConcurrent: 1\n  queuedLimit: 2\n  maxConcurrentLoad: 1\npackages:\n  allowedRoots:\n    - "+yaml(packages)+"\n  entries:\n    debug: "+yaml(packageRoot)+"\n");
        try {
            System.setProperty("att.server.config",anonymousConfig.toString());
            anonymous=start(users,appDirectory("inspection-anonymous"),true);
            int port=anonymous.getConnector().getLocalPort();
            JsonNode resources=getJson(port,"/att/api/v1/packages/debug/resources?type=template",null);
            String resourceId=resources.path("items").get(0).path("resourceId").asText();
            JsonNode source=getJson(port,"/att/api/v1/packages/debug/resources/template/"+resourceId+"/source",null);
            assertTrue(source.path("available").asBoolean(),source.toString());
            String referenceId=source.path("fileReferences").get(0).path("referenceId").asText();
            JsonNode referenced=getJson(port,"/att/api/v1/packages/debug/resources/template/"+resourceId+"/source-file?referenceId="+referenceId,null);
            assertTrue(referenced.path("available").asBoolean(),referenced.toString());
            assertTrue(referenced.path("text").asText().contains("private@example.test"));
            assertTrue(referenced.path("text").asText().contains("12 Main Street"));
            assertFalse(referenced.path("text").asText().contains("payload-secret"));
            JsonNode records=getJson(port,"/att/api/v1/packages/debug/configuration/testdata/customers/records?environment=SIT&offset=0&limit=1",null);
            assertEquals("A-1001",records.path("records").get(0).path("value").path("accountId").asText());
            assertEquals("customer@example.test",records.path("records").get(0).path("value").path("email").asText());
            HttpClient client=HttpClient.newHttpClient();
            HttpResponse<String> relaxedOrigin=send(client,port,"POST","/att/api/v1/load-schedules/preview","https://attacker.example",null,
                    "{\"timezone\":\"UTC\",\"cron\":\"0 2 * * *\"}");
            assertEquals(200,relaxedOrigin.statusCode(),relaxedOrigin.body());
            JsonNode schedule=postJsonViaTrustedProxy(port,"/att/api/v1/load-schedules",null,
                    ServerRuntime.JSON.valueToTree(Map.of("name","Anonymous SIT","packageId","debug","scenario","load/scenario.yaml",
                            "environment","SIT","timezone","UTC","cron","0 2 * * *","enabled",false)),201);
            String scheduleId=schedule.path("id").asText();assertFalse(scheduleId.isBlank());
            assertEquals(scheduleId,getJson(port,"/att/api/v1/load-schedules",null).path("items").get(0).path("id").asText());
            assertEquals(scheduleId,getJson(port,"/att/api/v1/load-schedules/"+scheduleId,null).path("id").asText());
            assertEquals(200,send(client,port,"DELETE","/att/api/v1/load-schedules/"+scheduleId,"https://att.example.test",null,"{}").statusCode());
        } finally {
            if(anonymous!=null){anonymous.stop();anonymous.destroy();}
            restore(oldConfig);
        }
    }

    private Path createLocalRuntime() throws Exception {
        Path home=Files.createDirectory(temp.resolve("local-runtime"));
        Path lib=Files.createDirectories(home.resolve("lib"));
        Set<String> added=new HashSet<>();
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(lib.resolve("att-local-"+ServerBuildInfo.PRODUCT+".jar")))) {
            for(String module:List.of("att-security","att-cli","att-engine","att-remote","att-server-api","att-worker")) {
                Path classes=Path.of(module,"target/classes");
                try(var paths=Files.walk(classes)) {
                    for(Path source:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator) {
                        String name=classes.relativize(source).toString().replace(java.io.File.separatorChar,'/');
                        if(!added.add(name))continue;
                        jar.putNextEntry(new JarEntry(name));Files.copy(source,jar);jar.closeEntry();
                    }
                }
            }
        }
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            Path dependency=Path.of(element);
            if(!Files.isRegularFile(dependency)||!dependency.getFileName().toString().endsWith(".jar"))continue;
            String name=dependency.getFileName().toString();
            if(name.startsWith("att-")||name.startsWith("tomcat-")||name.contains("servlet-api"))continue;
            Path target=lib.resolve(name);
            if(!Files.exists(target))Files.copy(dependency,target);
        }
        return home;
    }

    private Tomcat start(Path users,Path app) throws Exception {return start(users,app,false);}
    private Tomcat start(Path users,Path app,boolean trustedProxy) throws Exception {return start(users,app,trustedProxy,false);}
    private Tomcat start(Path users,Path app,boolean trustedProxy,boolean failAfterAuthentication) throws Exception {
        Tomcat tomcat=new Tomcat();tomcat.setBaseDir(temp.resolve("tomcat-"+System.nanoTime()).toString());tomcat.setPort(0);tomcat.getConnector();MemoryRealm realm=new MemoryRealm();realm.setPathname(users.toString());tomcat.getEngine().setRealm(realm);
        if(trustedProxy){RemoteIpValve valve=new RemoteIpValve();valve.setInternalProxies("127\\.0\\.0\\.1");valve.setRemoteIpHeader("X-Forwarded-For");valve.setProtocolHeader("X-Forwarded-Proto");valve.setHostHeader("X-Forwarded-Host");valve.setPortHeader("X-Forwarded-Port");tomcat.getEngine().getPipeline().addValve(valve);}
        Context context=tomcat.addWebapp("/att",app.toString());
        if(failAfterAuthentication){Tomcat.addServlet(context,"testFailure",new jakarta.servlet.http.HttpServlet(){@Override protected void doGet(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException, jakarta.servlet.ServletException {throw new jakarta.servlet.ServletException("deliberate downstream failure");}});context.addServletMappingDecoded("/api/v1/test-failure","testFailure");}
        tomcat.start();return tomcat;
    }
    private Path appDirectory(String name)throws Exception {
        Path app=Files.createDirectory(temp.resolve(name));Path webInf=Files.createDirectories(app.resolve("WEB-INF"));Path classes=Files.createDirectories(webInf.resolve("classes"));Files.createDirectories(webInf.resolve("lib"));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));int index=0;
        for(String element:classpath.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))){Path entry=Path.of(element);if(Files.isDirectory(entry)){try(var paths=Files.walk(entry)){for(Path source:(Iterable<Path>)paths.filter(Files::isRegularFile)::iterator){Path target=classes.resolve(entry.relativize(source).toString());Files.createDirectories(target.getParent());if(!Files.exists(target))Files.copy(source,target);}}}else if(Files.isRegularFile(entry)&&entry.toString().endsWith(".jar")&&!entry.getFileName().toString().startsWith("tomcat-embed-")&&!entry.getFileName().toString().contains("servlet-api")){Path target=webInf.resolve("lib").resolve(entry.getFileName());Files.copy(entry,target,StandardCopyOption.REPLACE_EXISTING);}index++;}
        Path descriptor=Path.of("src/main/webapp/WEB-INF/web.xml");
        if(!Files.isRegularFile(descriptor))descriptor=Path.of("att-server/src/main/webapp/WEB-INF/web.xml");
        try(var input=Files.newInputStream(descriptor)){Files.copy(input,webInf.resolve("web.xml"));}
        Path ui=Files.createDirectories(app.resolve("ui"));
        for(String asset:java.util.List.of("index.html","app.js","app.css","login.html","login.js","login.css")) {
            try(var stream=Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/resources/ui/"+asset)) {
                assertNotNull(stream,"ATT Web UI asset must exist on test classpath: "+asset);
                Files.copy(stream,ui.resolve(asset));
            }
        }
        addModuleJar(webInf.resolve("lib"),"att-worker",Path.of("att-worker/target/classes"));
        addModuleJar(webInf.resolve("lib"),"att-engine",Path.of("att-engine/target/classes"));
        addModuleJar(webInf.resolve("lib"),"att-server-api",Path.of("att-server-api/target/classes"));
        return app;
    }

    private static String submitJob(int port,String authorization)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/att/api/v1/jobs/run").openConnection();
        c.setConnectTimeout(3000);c.setReadTimeout(5000);
        c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Authorization",authorization);
        c.setRequestProperty("Content-Type","application/json");
        try(var out=c.getOutputStream()) {out.write("{\"packageId\":\"p\",\"all\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        assertEquals(202,c.getResponseCode());
        String json=new String(c.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        c.disconnect();
        String id=ServerRuntime.JSON.readTree(json).path("jobId").asText();
        assertTrue(id.matches("J[0-9A-F]{16}"));
        return id;
    }
    private static long assertRetainedSseEvent(int port,String authorization,String id)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/att/api/v1/jobs/"+id+"/events").openConnection();
        c.setReadTimeout(5000);if(authorization!=null)c.setRequestProperty("Authorization",authorization);
        assertEquals(200,c.getResponseCode());assertTrue(c.getHeaderField("Content-Type").startsWith("text/event-stream"));
        try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))) {
            String line=reader.readLine();assertNotNull(line,"SSE must replay retained job events");assertTrue(line.startsWith("id: "),"SSE must replay retained job events");
            return Long.parseLong(line.substring(4));
        } finally {c.disconnect();}
    }
    private static void assertLastEventIdResume(int port,String authorization,String id,long cursor)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/att/api/v1/jobs/"+id+"/events").openConnection();
        c.setReadTimeout(10000);if(authorization!=null)c.setRequestProperty("Authorization",authorization);
        c.setRequestProperty("Last-Event-ID",Long.toString(cursor));
        assertEquals(200,c.getResponseCode(),"The real Tomcat event endpoint must accept a Last-Event-ID resume request");
        long previous=cursor;boolean sawEvent=false,sawCancelled=false;
        try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while((line=reader.readLine())!=null) {
                if(line.startsWith("id: ")) {
                    long eventId=Long.parseLong(line.substring(4));assertTrue(eventId>previous,"Resumed SSE must not replay events at or before Last-Event-ID");previous=eventId;sawEvent=true;
                } else if(line.startsWith("data: ")) {
                    JsonNode data=ServerRuntime.JSON.readTree(line.substring(6));
                    if("CANCELLED".equals(data.path("status").asText()))sawCancelled=true;
                }
            }
        } finally {c.disconnect();}
        assertTrue(sawEvent,"A resumed stream must deliver events newer than Last-Event-ID");
        assertTrue(sawCancelled,"The resumed stream must include the terminal CANCELLED status");
    }
    private static int cancelJob(int port,String authorization,String id)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/att/api/v1/jobs/"+id).openConnection();
        c.setRequestMethod("DELETE");if(authorization!=null)c.setRequestProperty("Authorization",authorization);c.setReadTimeout(5000);
        try{return c.getResponseCode();}finally{c.disconnect();}
    }
    private static int post(int port,String authorization,String contentType,String origin)throws Exception {return post(port,authorization,contentType,origin,java.util.Map.of());}
    private static int post(int port,String authorization,String contentType,String origin,java.util.Map<String,String> extraHeaders)throws Exception {
        try(java.net.Socket socket=new java.net.Socket("127.0.0.1",port)) {socket.setSoTimeout(3000);java.io.OutputStream out=socket.getOutputStream();String body="{}";StringBuilder request=new StringBuilder("POST /att/api/v1/jobs/run HTTP/1.1\r\nHost: 127.0.0.1:").append(port).append("\r\nConnection: close\r\n");if(authorization!=null)request.append("Authorization: ").append(authorization).append("\r\n");request.append("Content-Type: ").append(contentType).append("\r\nContent-Length: ").append(body.length()).append("\r\n");if(origin!=null)request.append("Origin: ").append(origin).append("\r\n");extraHeaders.forEach((name,value)->request.append(name).append(": ").append(value).append("\r\n"));request.append("\r\n").append(body);out.write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();String status=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)).readLine();return Integer.parseInt(status.split(" ")[1]);}
    }
    private static int request(int port,String authorization)throws Exception {return get(port,"/att/api/v1/packages",authorization);}
    private static int get(int port,String uri,String authorization)throws Exception {HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+uri).openConnection();connection.setConnectTimeout(3000);connection.setReadTimeout(3000);if(authorization!=null)connection.setRequestProperty("Authorization",authorization);int code=connection.getResponseCode();if(code==401)assertTrue(connection.getHeaderField("WWW-Authenticate").startsWith("Basic"));
        if(code==200&&uri.startsWith("/att/ui/")) {assertEquals("nosniff",connection.getHeaderField("X-Content-Type-Options"));assertNotNull(connection.getHeaderField("Content-Security-Policy"));}
        connection.disconnect();return code;}
    private static JsonNode getJson(int port,String uri,String authorization)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+uri).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(30000);
        if(authorization!=null)connection.setRequestProperty("Authorization",authorization);
        assertEquals(200,connection.getResponseCode());try{return ServerRuntime.JSON.readTree(connection.getInputStream());}finally{connection.disconnect();}
    }
    private static JsonNode postJson(int port,String uri,String authorization,JsonNode body)throws Exception {
        return postJson(port,uri,authorization,body,201);
    }
    private static JsonNode postJson(int port,String uri,String authorization,JsonNode body,int expectedStatus)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+uri).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");connection.setDoOutput(true);if(authorization!=null)connection.setRequestProperty("Authorization",authorization);connection.setRequestProperty("Content-Type","application/json");
        try(var output=connection.getOutputStream()){output.write(ServerRuntime.JSON.writeValueAsBytes(body));}
        int actualStatus=connection.getResponseCode();if(actualStatus!=expectedStatus){java.io.InputStream error=connection.getErrorStream();String detail=error==null?"":new String(error.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(expectedStatus,actualStatus,detail);}try{return ServerRuntime.JSON.readTree(connection.getInputStream());}finally{connection.disconnect();}
    }
    private static JsonNode postJsonViaTrustedProxy(int port,String uri,String authorization,JsonNode body,int expectedStatus)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+uri).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");connection.setDoOutput(true);if(authorization!=null)connection.setRequestProperty("Authorization",authorization);
        connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Origin","https://att.example.test");
        connection.setRequestProperty("X-Forwarded-For","203.0.113.10");connection.setRequestProperty("X-Forwarded-Proto","https");
        connection.setRequestProperty("X-Forwarded-Host","att.example.test");connection.setRequestProperty("X-Forwarded-Port","443");
        try(var output=connection.getOutputStream()){output.write(ServerRuntime.JSON.writeValueAsBytes(body));}
        int actualStatus=connection.getResponseCode();if(actualStatus!=expectedStatus){java.io.InputStream error=connection.getErrorStream();String detail=error==null?"":new String(error.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);assertEquals(expectedStatus,actualStatus,detail);}try{return ServerRuntime.JSON.readTree(connection.getInputStream());}finally{connection.disconnect();}
    }
    private static JsonNode waitForTerminalJob(int port,String id)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);JsonNode job=getJson(port,"/att/api/v1/jobs/"+id,null);
        while(!List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(job.path("status").asText())&&System.nanoTime()<deadline) {
            Thread.sleep(20);job=getJson(port,"/att/api/v1/jobs/"+id,null);
        }
        assertTrue(List.of("PASS","FAIL","ERROR","INVALID","CANCELLED").contains(job.path("status").asText()),"Job did not reach a terminal state: "+id);
        waitForWorkersIdle(port);
        return job;
    }
    private static void waitForWorkersIdle(int port)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);JsonNode metrics=getJson(port,"/att/api/v1/metrics",null);
        while(metrics.path("activeWorkers").asInt(-1)>0&&System.nanoTime()<deadline) {
            Thread.sleep(20);metrics=getJson(port,"/att/api/v1/metrics",null);
        }
        assertEquals(0,metrics.path("activeWorkers").asInt(-1),"Worker admission must be released before the next job");
    }
    private static JsonNode postErrorJson(int port,String uri,String authorization,JsonNode body,int expectedStatus)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+uri).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");connection.setDoOutput(true);if(authorization!=null)connection.setRequestProperty("Authorization",authorization);connection.setRequestProperty("Content-Type","application/json");
        try(var output=connection.getOutputStream()){output.write(ServerRuntime.JSON.writeValueAsBytes(body));}
        assertEquals(expectedStatus,connection.getResponseCode());try{return ServerRuntime.JSON.readTree(connection.getErrorStream());}finally{connection.disconnect();}
    }
    private static int postStatus(int port,String authorization,String uri,Map<String,Object> body)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+uri).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");connection.setDoOutput(true);if(authorization!=null)connection.setRequestProperty("Authorization",authorization);connection.setRequestProperty("Content-Type","application/json");
        try(var output=connection.getOutputStream()){output.write(ServerRuntime.JSON.writeValueAsBytes(body));}
        try{return connection.getResponseCode();}finally{connection.disconnect();}
    }
    private static int auditCount(Path dataDir,String principal)throws Exception {
        Class.forName("org.h2.Driver");
        String url="jdbc:h2:file:"+dataDir.resolve("db/att-server").toAbsolutePath().toString().replace('\\','/');
        try(var connection=java.sql.DriverManager.getConnection(url,"sa","");var query=connection.prepareStatement("SELECT COUNT(*) FROM audit WHERE principal=?")) {
            query.setString(1,principal);try(var rows=query.executeQuery()){rows.next();return rows.getInt(1);}
        }
    }
    private void assertRemoteNoAuthCommands(int port)throws Exception {
        Path home=Files.createDirectories(temp.resolve("remote-home/.att"));
        Files.writeString(home.resolve("servers.yaml"),"default: anonymous\nservers:\n  anonymous:\n    url: http://127.0.0.1:"+port+"/att\n    auth:\n      type: basic\n      username: configured-user\n      passwordEnv: ATT_TEST_PASSWORD\n");
        String previousHome=System.getProperty("user.home");java.io.PrintStream previousOut=System.out;
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        System.setProperty("user.home",temp.resolve("remote-home").toString());
        try {
            System.setOut(new java.io.PrintStream(output,true,java.nio.charset.StandardCharsets.UTF_8));
            for(String[] command:List.of(
                    new String[]{"--server","anonymous","--no-auth","ping"},
                    new String[]{"--server","anonymous","--no-auth","packages"},
                    new String[]{"--server","anonymous","--no-auth","run","p","--all","--principal","Remote Run","--detach"},
                    new String[]{"--server","anonymous","--no-auth","validate","debug","--package","--principal","Remote Validate","--detach"},
                    new String[]{"--server","anonymous","--no-auth","debug","debug","template","FORM","--principal","Remote Debug","--detach"},
                    new String[]{"--server","anonymous","--no-auth","load","debug","load/path-based.yaml","--users","1","--duration","1s","--principal","Remote Load","--detach"})) {
                assertEquals(0,att.remote.RemoteCommand.run(command),"ATT Remote --no-auth command should succeed: "+String.join(" ",command));
                if(command.length>4) {
                    JsonNode jobs=getJson(port,"/att/api/v1/jobs",null).path("items");
                    waitForTerminalJob(port,jobs.get(0).path("jobId").asText());
                    int principalOption=java.util.Arrays.asList(command).indexOf("--principal");
                    if(principalOption>=0) {
                        String expected=command[principalOption+1]+"(anonym)";
                        JsonNode named=getJson(port,"/att/api/v1/jobs?principal="+java.net.URLEncoder.encode(expected,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20"),null).path("items");
                        assertEquals(1,named.size(),"Remote --principal must be sent raw and formatted once by the Server");
                        assertEquals(expected,named.get(0).path("principal").asText());
                    }
                }
            }
            assertEquals(0,att.remote.RemoteCommand.run(new String[]{"--server","anonymous","--no-auth","jobs","--principal","Remote Run(anonym)"}),"Remote job search should filter the existing principal field");
        } finally {
            System.setOut(previousOut);
            if(previousHome==null)System.clearProperty("user.home");else System.setProperty("user.home",previousHome);
        }
        assertFalse(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("rejected authentication"));
        assertTrue(output.toString(java.nio.charset.StandardCharsets.UTF_8).contains("Remote Run(anonym)"),"Remote job output displays the Server-formatted principal");
    }
    private static void restore(String old){if(old==null)System.clearProperty("att.server.config");else System.setProperty("att.server.config",old);}
    private static int postBody(int port,String authorization,String contentType,String origin,String body)throws Exception {
        try(java.net.Socket socket=new java.net.Socket("127.0.0.1",port)) {socket.setSoTimeout(3000);java.io.OutputStream out=socket.getOutputStream();StringBuilder request=new StringBuilder("POST /att/api/v1/jobs/run HTTP/1.1\r\nHost: 127.0.0.1:").append(port).append("\r\nConnection: close\r\nAuthorization: ").append(authorization).append("\r\nContent-Type: ").append(contentType).append("\r\nContent-Length: ").append(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append("\r\n");if(origin!=null)request.append("Origin: ").append(origin).append("\r\n");request.append("\r\n").append(body);out.write(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();String status=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8)).readLine();return Integer.parseInt(status.split(" ")[1]);}
    }
    private static void assertFiveSseObserversReceiveLiveEvents(Tomcat tomcat,int port,String authorization)throws Exception {
        Context context=(Context)tomcat.getHost().findChild("/att");Object runtime=context.getServletContext().getAttribute(ServerBootstrap.RUNTIME);ClassLoader appLoader=runtime.getClass().getClassLoader();
        Object config=field(runtime,"config");Path dataDir=(Path)field(config,"dataDir");int maxEvents=(Integer)field(config,"maxEventsPerJob");
        String id="J"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();Path directory=Files.createDirectories(dataDir.resolve("jobs").resolve(id));
        Class<?> journalType=Class.forName("att.server.JobEvents",true,appLoader);var journalConstructor=journalType.getDeclaredConstructor(Path.class,int.class);journalConstructor.setAccessible(true);Object journal=journalConstructor.newInstance(directory.resolve("events.jsonl"),maxEvents);
        Class<?> requestType=Class.forName("att.server.api.WorkerRequest",true,appLoader);Object workerRequest=requestType.getConstructor().newInstance();
        Class<?> jobType=Class.forName("att.server.Job",true,appLoader);var jobConstructor=jobType.getDeclaredConstructor(String.class,String.class,String.class,String.class,requestType,journalType);jobConstructor.setAccessible(true);Object job=jobConstructor.newInstance(id,"run","p","observer-test",workerRequest,journal);
        Object store=field(runtime,"store");var insert=store.getClass().getDeclaredMethod("insert",jobType,String.class);insert.setAccessible(true);insert.invoke(store,job,"{}");
        @SuppressWarnings("unchecked") Map<String,Object> jobs=(Map<String,Object>)field(runtime,"jobs");jobs.put(id,job);
        var append=journalType.getDeclaredMethod("append",String.class,Map.class);append.setAccessible(true);append.invoke(journal,"status",Map.of("jobId",id,"status","RUNNING"));
        List<HttpURLConnection> connections=new ArrayList<>();
        try {
            for(int i=0;i<5;i++) {
                HttpURLConnection connection=(HttpURLConnection)new java.net.URL("http://127.0.0.1:"+port+"/att/api/v1/jobs/"+id+"/events").openConnection();
                connection.setConnectTimeout(3000);connection.setReadTimeout(5000);connection.setRequestProperty("Authorization",authorization);
                connections.add(connection);
                assertEquals(200,connection.getResponseCode());
                // HTTP 200 can arrive before the asynchronous stream task subscribes to the journal.
                // Wait for this listener before opening the next stream; earlier observers stay open.
                awaitSseListeners(journal,connections.size());
            }
            append.invoke(journal,"progress",Map.of("message","live observer event"));
            for(HttpURLConnection connection:connections) {
                try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(),java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;boolean received=false;while((line=reader.readLine())!=null){if(line.contains("live observer event")){received=true;break;}}
                    assertTrue(received,"Each concurrent observer should receive the live event");
                }
            }
        } finally {
            var finish=runtime.getClass().getDeclaredMethod("finish",jobType,String.class,int.class);finish.setAccessible(true);finish.invoke(runtime,job,"PASS",0);for(HttpURLConnection c:connections)c.disconnect();
        }
    }
    private static void awaitSseListeners(Object journal,int expected)throws Exception {
        var listeners=journal.getClass().getDeclaredField("listeners");listeners.setAccessible(true);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        int count=0;
        do {
            count=((java.util.Collection<?>)listeners.get(journal)).size();
            if(count==expected)return;
            Thread.sleep(10);
        } while(System.nanoTime()<deadline);
        assertEquals(expected,count,"All concurrent SSE streams should subscribe before the live event is appended");
    }
    private static Object field(Object target,String name)throws Exception {var field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void copySchemas(Path packageRoot)throws Exception {
        Path source=Path.of("schemas").toRealPath(),destination=packageRoot.resolve("schemas");
        try(var paths=Files.walk(source)) {for(Path path:(Iterable<Path>)paths::iterator){Path target=destination.resolve(source.relativize(path));if(Files.isDirectory(path))Files.createDirectories(target);else{Files.createDirectories(target.getParent());Files.copy(path,target);}}}
    }
    private static void addModuleJar(Path lib,String name,Path classes)throws Exception {
        Path target=lib.resolve(name+".jar");try(OutputStream file=Files.newOutputStream(target);JarOutputStream jar=new JarOutputStream(file);var paths=Files.walk(classes)) {
            paths.filter(Files::isRegularFile).forEach(path->{try{jar.putNextEntry(new JarEntry(classes.relativize(path).toString().replace('\\','/')));Files.copy(path,jar);jar.closeEntry();}catch(Exception error){throw new IllegalStateException(error);}});
        }
    }
    private static String yaml(Path path){return "'"+path.toAbsolutePath().toString().replace("'","''")+"'";}

}
