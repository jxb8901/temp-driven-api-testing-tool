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
    private final String apiBase, username, password;
    public RemoteHttpClient(ServerProfile profile,String password) throws RemoteException { this(profile,password,profile.username); }
    public RemoteHttpClient(ServerProfile profile,String password,String username) throws RemoteException {
        this.apiBase=profile.url.endsWith("/api/v1")?profile.url:profile.url+"/api/v1";
        this.username=username; this.password=password;
        if(password!=null && !profile.url.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) throw new RemoteException("Basic authentication requires HTTPS");
    }
    public JsonNode get(String path) throws RemoteException { return json("GET",path,null,null); }
    public JsonNode post(String path,JsonNode body) throws RemoteException { return json("POST",path,body,null); }
    public JsonNode delete(String path) throws RemoteException { return json("DELETE",path,null,null); }
    public HttpURLConnection openEvents(String path,String lastId) throws RemoteException {
        HttpURLConnection c=null;boolean returned=false;
        try {
            c=connection(path); c.setRequestMethod("GET"); c.setRequestProperty("Accept","text/event-stream");
            if(lastId!=null)c.setRequestProperty("Last-Event-ID",lastId); c.connect(); check(c);returned=true;return c;
        } catch(RemoteException e) { throw e; }
        catch(Exception e) { throw new RemoteException("Unable to connect to ATT Server events endpoint",e); }
        finally { if(!returned&&c!=null)c.disconnect(); }
    }
    public void download(String path,java.nio.file.Path target) throws RemoteException, IOException {
        HttpURLConnection c=null;
        try { c=connection(path);c.setRequestMethod("GET");c.connect();check(c);try(InputStream in=c.getInputStream();java.io.OutputStream out=java.nio.file.Files.newOutputStream(target,java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE)){copyBounded(in,out,Long.MAX_VALUE);} }
        catch(RemoteException e){java.nio.file.Files.deleteIfExists(target);throw e;}catch(IOException e){java.nio.file.Files.deleteIfExists(target);throw e;}finally{if(c!=null)c.disconnect();}
    }
    private JsonNode json(String method,String path,JsonNode body,String accept) throws RemoteException {
        HttpURLConnection c=null;
        try {
            c=connection(path); c.setRequestMethod(method); c.setRequestProperty("Accept",accept==null?"application/json":accept);
            if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");byte[] bytes=JSON.writeValueAsBytes(body);if(bytes.length>1024*1024)throw new RemoteException("ATT Remote request exceeds the 1 MiB limit");try(java.io.OutputStream out=c.getOutputStream()){out.write(bytes);}}
            c.connect();check(c); byte[] bytes;try(InputStream in=c.getInputStream()){bytes=readBounded(in,RESPONSE_LIMIT);}
            JsonNode value=JSON.readTree(bytes); if(value==null||!value.isObject())throw new RemoteException("ATT Server returned malformed JSON");return value;
        } catch(RemoteException e){throw e;}catch(Exception e){throw new RemoteException("ATT Server request failed: "+safeMessage(e),e);}finally{if(c!=null)c.disconnect();}
    }
    private HttpURLConnection connection(String path) throws RemoteException {
        try {
            URL url=new URL(apiBase+path); HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(10000);c.setReadTimeout(30000);c.setUseCaches(false);
            if(username!=null) { if(password==null)throw new RemoteException("Basic credentials are missing");String token=Base64.getEncoder().encodeToString((username+":"+password).getBytes(StandardCharsets.UTF_8));c.setRequestProperty("Authorization","Basic "+token); }
            c.setRequestProperty("User-Agent","ATT-Remote/4.0.0");return c;
        } catch(RemoteException e){throw e;} catch(Exception e){throw new RemoteException("Invalid ATT Server request URL",e);}
    }
    private static void check(HttpURLConnection c) throws IOException,RemoteException {
        int status=c.getResponseCode();if(status>=200&&status<300)return;
        String detail="";InputStream err=c.getErrorStream();if(err!=null)try{JsonNode node=JSON.readTree(readBounded(err,64*1024));JsonNode e=node.path("error");detail=e.path("summary").asText("");if(detail.isEmpty())detail=e.path("detail").asText("");}catch(Exception ignored){}
        if(status==401||status==403)throw new RemoteException("ATT Server rejected authentication (HTTP "+status+")");
        throw new RemoteException("ATT Server returned HTTP "+status+(detail.isEmpty()?"":": "+detail),status==400?2:4);
    }
    private static byte[] readBounded(InputStream in,int max)throws IOException,RemoteException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new RemoteException("ATT Server response exceeded the configured size limit");out.write(b,0,n);}return out.toByteArray();}
    private static void copyBounded(InputStream in,java.io.OutputStream out,long max)throws IOException,RemoteException{byte[] b=new byte[8192];long total=0;int n;while((n=in.read(b))!=-1){total+=n;if(total>1024L*1024*1024)throw new RemoteException("Artifact exceeded the 1 GiB limit");out.write(b,0,n);}}
    private static String safeMessage(Exception e){String s=e.getMessage();return s==null?e.getClass().getSimpleName():s.replaceAll("(?i)(password|authorization)\\s*[:=]\\s*[^ ,]+","$1=[REDACTED_SECRET]");}
}
