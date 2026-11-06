package att.resource;

import att.security.SecretProtection;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Lossless source response shared by narrowly allowlisted authoring inspectors. */
final class AuthoringSourceSupport {
    private AuthoringSourceSupport() { }

    static Map<String,Object> read(Path trustedRoot, Path authoredPath, String logicalPath, int maxBytes) throws Exception {
        if(protectedSourcePath(logicalPath))throw new PackageResourceInspector.ResourceNotFoundException();
        Path root=trustedRoot.toRealPath();
        Path lexicalRoot=trustedRoot.toAbsolutePath().normalize();
        Path path=authoredPath.toAbsolutePath().normalize();
        if(!path.startsWith(lexicalRoot)||containsSymlink(lexicalRoot,path)
                ||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().startsWith(root))
            throw new PackageResourceInspector.ResourceNotFoundException();
        long size=Files.size(path);
        if(size>maxBytes)throw new PackageResourceInspector.ResponseTooLargeException();
        byte[] bytes=readBounded(path,maxBytes);
        String text=decodeUtf8(bytes);
        if(text.indexOf('\0')>=0||!SecretProtection.text(text).equals(text))
            throw new PackageResourceInspector.ResourceNotFoundException();
        return result(logicalPath,bytes,text,true);
    }

    static Map<String,Object> absent(String logicalPath) throws Exception {
        if(protectedSourcePath(logicalPath))throw new PackageResourceInspector.ResourceNotFoundException();
        return result(logicalPath,new byte[0],"",false);
    }

    private static Map<String,Object> result(String logicalPath,byte[] bytes,String text,boolean present) throws Exception {
        String revision=hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String etag="\"sha256:"+revision+"\"";
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("available",Boolean.TRUE);result.put("present",Boolean.valueOf(present));
        result.put("logicalPath",logicalPath);result.put("format",format(logicalPath));result.put("text",text);
        result.put("sourceEtag",etag);result.put("revision",revision);
        return result;
    }

    private static byte[] readBounded(Path path,int maxBytes) throws Exception {
        try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int total=0,count;
            while((count=input.read(buffer))>=0) {
                total+=count;if(total>maxBytes)throw new PackageResourceInspector.ResponseTooLargeException();
                output.write(buffer,0,count);
            }
            return output.toByteArray();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result=new StringBuilder(bytes.length*2);
        for(byte value:bytes)result.append(String.format(Locale.ROOT,"%02x",value&0xff));
        return result.toString();
    }

    private static String decodeUtf8(byte[] bytes) throws Exception {
        try {return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid) {throw new PackageResourceInspector.ResourceNotFoundException();}
    }

    private static boolean containsSymlink(Path root,Path path) {
        Path current=root;
        for(Path part:root.relativize(path)) {
            current=current.resolve(part);
            if(Files.isSymbolicLink(current))return true;
        }
        return false;
    }

    private static String format(String path) {
        String lower=path.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".yaml")||lower.endsWith(".yml"))return "yaml";
        if(lower.endsWith(".json"))return "json";
        if(lower.endsWith(".xml"))return "xml";
        if(lower.endsWith(".sql"))return "sql";
        return "text";
    }

    private static boolean protectedSourcePath(String logicalPath) {
        if(logicalPath==null)return true;
        String normalized=logicalPath.replace('\\','/').toLowerCase(Locale.ROOT);
        for(String segment:normalized.split("/")) {
            if(segment.startsWith("att-server")||segment.startsWith(".env")||segment.contains("secret")
                    ||segment.contains("credential")||segment.contains("password")||segment.contains("token")
                    ||segment.endsWith(".pem")||segment.endsWith(".key")||segment.endsWith(".p12")
                    ||segment.endsWith(".pfx")||segment.endsWith(".jks")||segment.endsWith(".keystore"))return true;
        }
        return false;
    }
}
