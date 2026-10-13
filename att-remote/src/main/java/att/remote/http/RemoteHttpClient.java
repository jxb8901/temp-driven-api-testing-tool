package att.remote.http;

import att.remote.RemoteException;
import att.remote.config.ServerProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Small Java 8 HTTP transport. Uses JVM TLS verification and never logs credentials. */
public class RemoteHttpClient {
    public static final ObjectMapper JSON=new ObjectMapper();
    private static final int RESPONSE_LIMIT=8*1024*1024;
    private final String apiBase,serverUrl;
    private final boolean basicAuth,autoAuth;
    private final CredentialProvider credentialProvider;
    private final ConnectionFactory connectionFactory;
    private volatile String username,password;
    public RemoteHttpClient(ServerProfile profile,String password) throws RemoteException { this(profile,password,profile.username,null); }
    public RemoteHttpClient(ServerProfile profile,String password,String username) throws RemoteException { this(profile,password,username,null); }
    public RemoteHttpClient(ServerProfile profile,String password,String username,CredentialProvider credentialProvider) throws RemoteException {
        this(profile,password,username,credentialProvider,url->(HttpURLConnection)url.openConnection());
    }
    RemoteHttpClient(ServerProfile profile,String password,String username,CredentialProvider credentialProvider,ConnectionFactory connectionFactory) throws RemoteException {
        this.apiBase=profile.url.endsWith("/api/v1")?profile.url:profile.url+"/api/v1";
        this.serverUrl=profile.url;this.basicAuth=profile.basicAuth;this.autoAuth=profile.autoAuth;this.credentialProvider=credentialProvider;
        this.connectionFactory=connectionFactory;
        this.username="none".equals(profile.authType)?null:username;this.password="none".equals(profile.authType)?null:password;
        if((password!=null||profile.basicAuth)&&!profile.url.toLowerCase(java.util.Locale.ROOT).startsWith("https://"))throw new RemoteException("Basic authentication requires HTTPS");
    }
    public JsonNode get(String path) throws RemoteException { return json("GET",path,null,null); }
    public JsonNode post(String path,JsonNode body) throws RemoteException { return json("POST",path,body,null); }
    public JsonNode delete(String path) throws RemoteException { return json("DELETE",path,null,null); }
    public HttpURLConnection openEvents(String path,String lastId) throws RemoteException {
        HttpURLConnection c=null;boolean returned=false;
        try {
            c=connectedRequest("GET",path,null,"text/event-stream",lastId);check(c);returned=true;return c;
        } catch(RemoteException e) { throw e; }
        catch(IOException e) { throw RemoteException.retryableTransport("Unable to connect to ATT Server events endpoint",e); }
        catch(Exception e) { throw new RemoteException("Unable to connect to ATT Server events endpoint",e); }
        finally { if(!returned&&c!=null)c.disconnect(); }
    }
    public void download(String path,java.nio.file.Path target) throws RemoteException, IOException {
        HttpURLConnection c=null;java.nio.file.Path temp=null;
        try {
            java.nio.file.Path absolute=target.toAbsolutePath().normalize();java.nio.file.Path parent=absolute.getParent();
            if(parent==null)throw new IOException("Artifact target has no parent directory");
            temp=java.nio.file.Files.createTempFile(parent,".att-download-",".part");
            c=connectedRequest("GET",path,null,"application/octet-stream");check(c);
            try(InputStream in=c.getInputStream();java.io.OutputStream out=java.nio.file.Files.newOutputStream(temp,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.StandardOpenOption.WRITE)){copyBounded(in,out,Long.MAX_VALUE);}
            publishNoReplace(temp,absolute);temp=null;
        } catch(RemoteException e){throw e;} finally {if(c!=null)c.disconnect();if(temp!=null)java.nio.file.Files.deleteIfExists(temp);}
    }
    static void publishNoReplace(java.nio.file.Path temporary,java.nio.file.Path target)throws IOException {java.nio.file.Files.move(temporary,target);}
    private JsonNode json(String method,String path,JsonNode body,String accept) throws RemoteException {
        HttpURLConnection c=null;byte[] bytes;
        try {
            byte[] requestBytes=null;
            if(body!=null){requestBytes=JSON.writeValueAsBytes(body);if(requestBytes.length>1024*1024)throw new RemoteException("ATT Remote request exceeds the 1 MiB limit");}
            c=connectedRequest(method,path,requestBytes,accept==null?"application/json":accept);check(c);try(InputStream in=c.getInputStream()){bytes=readBounded(in,RESPONSE_LIMIT);}
        } catch(RemoteException e){throw e;}
          catch(IOException e){String message="ATT Server request failed: "+safeMessage(e);if("GET".equals(method))throw RemoteException.retryableTransport(message,e);throw new RemoteException(message,e);}
          catch(Exception e){throw new RemoteException("ATT Server request failed: "+safeMessage(e),e);}
          finally{if(c!=null)c.disconnect();}
        try {JsonNode value=JSON.readTree(bytes);if(value==null||!value.isObject())throw new RemoteException("ATT Server returned malformed JSON");return value;}
        catch(RemoteException e){throw e;}
        catch(Exception e){throw new RemoteException("ATT Server returned malformed JSON",e);}
    }
    private HttpURLConnection connectedRequest(String method,String path,byte[] body,String accept)throws IOException,RemoteException {
        return connectedRequest(method,path,body,accept,null);
    }
    private HttpURLConnection connectedRequest(String method,String path,byte[] body,String accept,String lastId)throws IOException,RemoteException {
        for(int attempt=0;attempt<2;attempt++) {
            HttpURLConnection c=connection(path);boolean keep=false;
            try {
                c.setRequestMethod(method);c.setRequestProperty("Accept",accept);
                if(lastId!=null)c.setRequestProperty("Last-Event-ID",lastId);
                if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(java.io.OutputStream out=c.getOutputStream()){out.write(body);}}
                c.connect();int status=c.getResponseCode();
                if(status==401) {
                    String challenge=c.getHeaderField("WWW-Authenticate");
                    if(challenge!=null&&!challenge.trim().isEmpty()&&!hasBasicChallenge(challenge))
                        throw new RemoteException("ATT Server requested an unsupported authentication scheme; ATT Remote auto mode supports HTTP Basic only");
                    if(hasBasicChallenge(challenge)) {
                        if("none".equals(authMode()))throw new RemoteException("ATT Server requires HTTP Basic authentication, but anonymous access was selected");
                        if(autoAuth&&"GET".equals(method)&&attempt==0) {
                            if(!serverUrl.regionMatches(true,0,"https://",0,8))throw new RemoteException("ATT Server requested Basic authentication over HTTP; credentials were not sent because HTTPS is required");
                            if(username!=null&&password!=null)throw authenticationRejected();
                            if(credentialProvider==null)throw missingCredentials();
                            Credentials credentials=credentialProvider.credentials();
                            if(credentials==null||credentials.username==null||credentials.password==null)throw missingCredentials();
                            if(credentials.username.trim().isEmpty()||credentials.username.indexOf(':')>=0||credentials.username.indexOf('\n')>=0||credentials.username.indexOf('\r')>=0||credentials.password.isEmpty())throw new RemoteException("Basic authentication credentials are invalid");
                            username=credentials.username;password=credentials.password;
                            continue;
                        }
                        if(autoAuth&&!("GET".equals(method)))throw new RemoteException("ATT Server requested HTTP Basic authentication for a "+method+" request. ATT Remote did not retry this potentially non-idempotent request; configure auth.type: basic for this Server profile, check the job list, then retry if needed");
                    }
                }
                keep=true;return c;
            } finally { if(!keep)c.disconnect(); }
        }
        throw authenticationRejected();
    }
    private HttpURLConnection connection(String path) throws RemoteException {
        try {
            URL url=new URL(apiBase+path); HttpURLConnection c=connectionFactory.open(url);c.setConnectTimeout(10000);c.setReadTimeout(60000);c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            if(basicAuth||password!=null) { if(username==null||password==null)throw new RemoteException("Basic credentials are missing");String token=Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));c.setRequestProperty("Authorization","Basic "+token); }
            c.setRequestProperty("User-Agent","ATT-Remote/4.1.0");return c;
        } catch(RemoteException e){throw e;} catch(Exception e){throw new RemoteException("Invalid ATT Server request URL",e);}
    }
    private String authMode(){return basicAuth?"basic":autoAuth?"auto":"none";}
    private static boolean hasBasicChallenge(String value){return value!=null&&java.util.regex.Pattern.compile("(?:^|,)\\s*Basic(?:\\s|$)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(value).find();}
    private static RemoteException authenticationRejected(){return new RemoteException("ATT Server rejected HTTP Basic authentication (HTTP 401)");}
    private static RemoteException missingCredentials(){return new RemoteException("ATT Server requires HTTP Basic authentication. Configure username and passwordEnv for this profile, or run ATT Remote in a secure terminal to enter credentials");}
    private static void check(HttpURLConnection c) throws IOException,RemoteException {
        int status=c.getResponseCode();if(status>=200&&status<300)return;
        if(status>=300&&status<400)throw new RemoteException("ATT Server redirected the request (HTTP "+status+"). Redirect-based FORM/OIDC authentication is not supported; use an endpoint with a supported authentication scheme");
        String detail="";InputStream err=c.getErrorStream();if(err!=null)try{JsonNode node=JSON.readTree(readBounded(err,64*1024));JsonNode e=node.path("error");detail=e.path("summary").asText("");if(detail.isEmpty())detail=e.path("detail").asText("");}catch(Exception ignored){}
        if(status==401) {
            String challenge=c.getHeaderField("WWW-Authenticate");
            if(hasBasicChallenge(challenge))throw authenticationRejected();
            throw new RemoteException("ATT Server returned HTTP 401 without a supported Basic challenge; auto mode will not guess or send credentials");
        }
        if(status==403)throw new RemoteException("ATT Server rejected authorization (HTTP 403)");
        String message="ATT Server returned HTTP "+status+(detail.isEmpty()?"":": "+detail);
        if(status>=500||status==408||status==429)throw RemoteException.retryableHttp(message,status==400?2:4);
        throw new RemoteException(message,status==400?2:4);
    }
    public interface CredentialProvider { Credentials credentials() throws RemoteException; }
    interface ConnectionFactory { HttpURLConnection open(URL url)throws IOException; }
    public static final class Credentials {
        public final String username,password;
        public Credentials(String username,String password){this.username=username;this.password=password;}
    }
    private static byte[] readBounded(InputStream in,int max)throws IOException,RemoteException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new RemoteException("ATT Server response exceeded the configured size limit");out.write(b,0,n);}return out.toByteArray();}
    private static void copyBounded(InputStream in,java.io.OutputStream out,long max)throws IOException,RemoteException{byte[] b=new byte[8192];long total=0;int n;while((n=in.read(b))!=-1){total+=n;if(total>1024L*1024*1024)throw new RemoteException("Artifact exceeded the 1 GiB limit");out.write(b,0,n);}}
    private static String safeMessage(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s.replaceAll("(?i)(password|authorization)\\s*[:=]\\s*[^ ,]+","$1=[REDACTED_SECRET]");}
}
