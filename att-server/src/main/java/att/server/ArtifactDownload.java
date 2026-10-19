package att.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Bounded private download snapshot; never reopen the published pathname after approval. */
final class ArtifactDownload implements AutoCloseable {
    private static final int CHUNK_BYTES = 64 * 1024;
    private final Path directory;
    private final FileChannel channel;
    private final List<byte[]> hashes;
    final long length;

    private ArtifactDownload(Path directory, FileChannel channel, List<byte[]> hashes, long length) {
        this.directory=directory;this.channel=channel;this.hashes=List.copyOf(hashes);this.length=length;
    }

    static ArtifactDownload open(Path archive) throws Exception {
        // A private OS-temp directory keeps the copy outside publication/recovery's namespace.
        Path directory=Files.createTempDirectory("att-artifact-download-");
        Path snapshot=directory.resolve("archive.zip");
        FileChannel channel=null;
        try {
            channel=FileChannel.open(snapshot,StandardOpenOption.CREATE_NEW,StandardOpenOption.READ,StandardOpenOption.WRITE);
            List<byte[]> hashes=new ArrayList<>();long length=0;
            ByteBuffer buffer=ByteBuffer.allocate(CHUNK_BYTES);
            try(FileChannel input=FileChannel.open(archive,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                if(input.size()>ArtifactArchive.MAX_ARCHIVE_BYTES)throw new IOException("Artifact archive exceeds download limit");
                while(true) {
                    buffer.clear();
                    int read=0,count;
                    while(buffer.hasRemaining()&&(count=input.read(buffer))!=-1)read+=count;
                    if(read==0)break;
                    length+=read;
                    if(length>ArtifactArchive.MAX_ARCHIVE_BYTES)throw new IOException("Artifact archive exceeds download limit");
                    hashes.add(digest(buffer.array(),read));
                    buffer.flip();while(buffer.hasRemaining())channel.write(buffer);
                }
            }
            // Validate the copied bytes, not a source that publication may atomically replace.
            ArtifactArchive.verify(snapshot,true);
            return new ArtifactDownload(directory,channel,hashes,length);
        } catch(Exception failure) {
            if(channel!=null)try{channel.close();}catch(IOException close){failure.addSuppressed(close);}
            try{Files.deleteIfExists(snapshot);Files.deleteIfExists(directory);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    void writeTo(OutputStream output) throws IOException {
        byte[] bytes=new byte[CHUNK_BYTES];long remaining=length,position=0;
        for(byte[] expected:hashes) {
            int count=(int)Math.min(bytes.length,remaining);
            ByteBuffer buffer=ByteBuffer.wrap(bytes,0,count);
            while(buffer.hasRemaining()) {
                int read=channel.read(buffer,position+buffer.position());
                if(read<0)throw new IOException("Artifact download snapshot is incomplete");
            }
            // Check before writing each buffer: even an altered spool cannot emit changed bytes.
            if(!MessageDigest.isEqual(expected,digest(bytes,count)))throw new IOException("Artifact download snapshot changed");
            output.write(bytes,0,count);remaining-=count;position+=count;
        }
        if(remaining!=0)throw new IOException("Artifact download snapshot is incomplete");
    }

    private static byte[] digest(byte[] bytes,int count) {
        try {MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(bytes,0,count);return digest.digest();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    @Override public void close() throws IOException {
        try {channel.close();}
        finally {try {Files.deleteIfExists(directory.resolve("archive.zip"));}finally {Files.deleteIfExists(directory);}}
    }
}
