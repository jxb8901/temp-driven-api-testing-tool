package att.remote.artifact;

import att.remote.RemoteException;
import att.remote.http.RemoteHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/** Downloads only logical artifact names and never overwrites or escapes the chosen directory. */
public final class ArtifactDownloader {
    private ArtifactDownloader() { }
    public static Path download(RemoteHttpClient client,String jobId,String logicalName,Path directory) throws RemoteException,IOException {
        if(logicalName==null||logicalName.trim().isEmpty()||logicalName.indexOf('\0')>=0||logicalName.indexOf('\\')>=0)throw new RemoteException("Invalid logical artifact name");
        Path relative=Paths.get(logicalName);if(relative.isAbsolute()||relative.normalize().startsWith(".."))throw new RemoteException("Artifact name escapes the selected download directory");
        for(Path part:relative)if(".".equals(part.toString())||"..".equals(part.toString()))throw new RemoteException("Invalid logical artifact name");
        Path root=directory.toAbsolutePath().normalize();Files.createDirectories(root);root=root.toRealPath();
        Path target=root.resolve(relative).normalize();if(!target.startsWith(root))throw new RemoteException("Artifact name escapes the selected download directory");
        Path parent=target.getParent();Path walk=root;
        for(Path part:root.relativize(parent)) {
            walk=walk.resolve(part);
            try { Files.createDirectory(walk); } catch(java.nio.file.FileAlreadyExistsException exists) { }
            if(Files.isSymbolicLink(walk)||!Files.isDirectory(walk,LinkOption.NOFOLLOW_LINKS))throw new RemoteException("Download destination contains a symbolic link or non-directory");
        }
        Path realParent=parent.toRealPath();if(!realParent.startsWith(root))throw new RemoteException("Download destination resolves outside the selected directory");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new RemoteException("Refusing to overwrite existing file: "+relative);
        String encoded=encodePath(logicalName);client.download("/jobs/"+segment(jobId)+"/artifacts/"+encoded,target);return target;
    }
    private static String segment(String v){try{return java.net.URLEncoder.encode(v,"UTF-8").replace("+","%20");}catch(java.io.UnsupportedEncodingException impossible){throw new IllegalStateException(impossible);}}
    private static String encodePath(String path){String[] parts=path.split("/",-1);StringBuilder b=new StringBuilder();for(String part:parts){if(b.length()>0)b.append('/');b.append(segment(part));}return b.toString();}
}
