package att.server;

import att.server.api.WorkerRequest;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.Principal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactDownloadTest {
    @TempDir Path temp;

    @ParameterizedTest @ValueSource(strings={"before", "during", "inplace"})
    void servletServesApprovedBytesAndLengthDespitePublishedFileReplacement(String race) throws Exception {
        try(ServerRuntime runtime=runtime()) {
            String id="J0000000000000001";
            Path output=Files.createDirectories(runtime.outputDirectoryForJob(id));
            Files.writeString(output.resolve("case.log"),"Approved evidence\n".repeat(20_000));
            Job job=new Job(id,"run","p","user-a",new WorkerRequest(),new JobEvents(output.getParent().resolve("events.jsonl"),100));
            runtime.store.insert(job,"{}");job.status="PASS";job.finishedAt=Instant.now();runtime.store.update(job);
            Path archive=runtime.artifactArchivePath(id);
            ArtifactArchive.build(output,archive,Files::readAllBytes);
            Files.writeString(archive.resolveSibling("archive-status.json"),"{\"state\":\"READY\"}");
            byte[] approved=Files.readAllBytes(archive);
            Path replacement=Files.writeString(temp.resolve("replacement.zip"),"UNVERIFIED REPLACEMENT");
            AtomicBoolean replaced=new AtomicBoolean();
            Runnable replace=()->{
                if(!replaced.compareAndSet(false,true))return;
                try {
                    if("inplace".equals(race))Files.write(archive,Files.readAllBytes(replacement));
                    else Files.move(replacement,archive,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                } catch(IOException failure){throw new java.io.UncheckedIOException(failure);}
            };
            ServletContext context=proxy(ServletContext.class,(self,method,args)->method.getName().equals("getAttribute")?runtime:null);
            ApiServlet servlet=new ApiServlet();
            servlet.init(proxy(ServletConfig.class,(self,method,args)->method.getName().equals("getServletContext")?context:null));
            HttpServletRequest request=proxy(HttpServletRequest.class,(self,method,args)->switch(method.getName()) {
                case "getServletContext" -> context;
                case "getPathInfo" -> "/jobs/"+id+"/artifacts/archive.zip";
                case "getUserPrincipal" -> (Principal)()->"user-a";
                case "isUserInRole" -> runtime.config.authorization.userRole.equals(args[0]);
                default -> null;
            });
            ByteArrayOutputStream body=new ByteArrayOutputStream(),errors=new ByteArrayOutputStream();
            AtomicLong length=new AtomicLong(-1);AtomicInteger status=new AtomicInteger(200);
            ServletOutputStream stream=new ServletOutputStream() {
                @Override public boolean isReady(){return true;}
                @Override public void setWriteListener(WriteListener listener){}
                @Override public void write(int value){body.write(value);}
                @Override public void write(byte[] bytes,int offset,int count) {
                    if("during".equals(race)&&body.size()>0)replace.run();
                    body.write(bytes,offset,count);
                }
            };
            HttpServletResponse response=proxy(HttpServletResponse.class,(self,method,args)->{
                switch(method.getName()) {
                    case "setContentLengthLong" -> {length.set((Long)args[0]);if(!"during".equals(race))replace.run();}
                    case "setStatus" -> status.set((Integer)args[0]);
                    case "getOutputStream" -> {return stream;}
                    case "getWriter" -> {return new PrintWriter(errors,true);}
                }
                return null;
            });
            servlet.doGet(request,response);
            assertEquals(200,status.get(),errors.toString());assertTrue(replaced.get());
            assertEquals(approved.length,length.get());assertArrayEquals(approved,body.toByteArray());
            assertThrows(ServerRuntime.ArchiveUnavailableException.class,()->runtime.openArtifactArchive(id,true));
        }
    }

    @Test void alteredSpoolFailsBeforeSendingChangedBytesAndIsDeletedOnClose() throws Exception {
        Path archive=archive();
        Path directory;
        try(ArtifactDownload download=ArtifactDownload.open(archive)) {
            directory=directory(download);
            try(var file=new java.io.RandomAccessFile(directory.resolve("archive.zip").toFile(),"rw")){file.seek(0);file.writeByte('X');}
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            assertThrows(IOException.class,()->download.writeTo(output));assertEquals(0,output.size());
        }
        assertFalse(Files.exists(directory));
    }

    @Test void disconnectStillCleansThePrivateSnapshot() throws Exception {
        Path archive=archive(),directory;
        try(ArtifactDownload download=ArtifactDownload.open(archive)) {
            directory=directory(download);
            assertThrows(IOException.class,()->download.writeTo(new java.io.OutputStream(){
                @Override public void write(int value)throws IOException{throw new IOException("disconnected");}
            }));
        }
        assertFalse(Files.exists(directory));
    }

    @Test void refusesOversizedAndCorruptArchivesBeforeApproval() throws Exception {
        Path archive=temp.resolve("oversized.zip");
        try(var file=new java.io.RandomAccessFile(archive.toFile(),"rw")){file.setLength(ArtifactArchive.MAX_ARCHIVE_BYTES+1);}
        assertThrows(IOException.class,()->ArtifactDownload.open(archive));
        Files.writeString(archive,"not a ZIP");assertThrows(Exception.class,()->ArtifactDownload.open(archive));
    }

    private Path archive() throws Exception {
        Path output=Files.createDirectory(temp.resolve("output"));Files.writeString(output.resolve("case.log"),"approved");
        return ArtifactArchive.build(output,temp.resolve("artifacts.zip"),Files::readAllBytes);
    }
    private Path directory(ArtifactDownload download) throws Exception {
        var field=ArtifactDownload.class.getDeclaredField("directory");field.setAccessible(true);return (Path)field.get(download);
    }
    private ServerRuntime runtime() throws Exception {
        Path packages=Files.createDirectories(temp.resolve("packages")),pkg=Files.createDirectory(packages.resolve("p"));
        Path config=Files.writeString(temp.resolve("server.yaml"),"server:\n  dataDir: '"+temp.resolve("data")+"'\nworkers: {}\npackages:\n  allowedRoots: ['"+packages+"']\n  entries:\n    p: '"+pkg+"'\n");
        return new ServerRuntime(ServerConfig.load(config),Files.createDirectory(temp.resolve("libs")).toString());
    }
    private static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));
    }
}
