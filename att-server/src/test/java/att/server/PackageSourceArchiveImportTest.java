package att.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackageSourceArchiveImportTest {
    @Test void acceptsRelativeUtf8FilesAndEmptyDirectoryEntries() throws Exception {
        byte[] archive=zip(new String[][]{{"templates/", ""}, {"templates/checkout.yaml", "name: checkout\n"}});
        Map<String,byte[]> files=PackageSourceArchiveImport.parse(archive);
        assertEquals(1,files.size());
        assertArrayEquals("name: checkout\n".getBytes(StandardCharsets.UTF_8),files.get("templates/checkout.yaml"));
    }

    @Test void rejectsTraversalAbsoluteDuplicateAndCaseCollidingPaths() throws Exception {
        assertThrows(PackageSourceArchiveImport.InvalidArchive.class,
                ()->PackageSourceArchiveImport.parse(zip(new String[][]{{"../outside.yaml","x"}})));
        assertThrows(PackageSourceArchiveImport.InvalidArchive.class,
                ()->PackageSourceArchiveImport.parse(zip(new String[][]{{"/outside.yaml","x"}})));
        assertThrows(PackageSourceArchiveImport.InvalidArchive.class,
                ()->PackageSourceArchiveImport.parse(zip(new String[][]{{"A.yaml","x"},{"a.yaml","y"}})));
    }

    @Test void rejectsFilesThatConflictWithExplicitDirectoryEntries() throws Exception {
        assertThrows(PackageSourceArchiveImport.InvalidArchive.class,
                ()->PackageSourceArchiveImport.parse(zip(new String[][]{{"a","file"},{"a/child/",""}})));
        assertThrows(PackageSourceArchiveImport.InvalidArchive.class,
                ()->PackageSourceArchiveImport.parse(zip(new String[][]{{"a/",""},{"a","file"}})));
    }

    @Test void rejectsExpansionRatioOverLimit() throws Exception {
        String repeated="A".repeat(800_000);
        assertThrows(PackageSourceArchiveImport.InvalidArchive.class,
                ()->PackageSourceArchiveImport.parse(zip(new String[][]{{"templates/large.yaml",repeated}})));
    }

    @Test void rejectsUnixSpecialFileEntries() throws Exception {
        byte[] archive=zip(new String[][]{{"templates/link.yaml","target"}});
        int central=findSignature(archive,0x02014b50);
        if(central<0)throw new AssertionError("Missing central directory");
        archive[central+5]=3; // Unix creator OS.
        putLittleEndian32(archive,central+38,((long)0120777)<<16);
        assertThrows(PackageSourceArchiveImport.InvalidArchive.class,
                ()->PackageSourceArchiveImport.parse(archive));

        byte[] fifo=zip(new String[][]{{"templates/pipe.yaml","payload"}});
        central=findSignature(fifo,0x02014b50);
        fifo[central+5]=3;
        putLittleEndian32(fifo,central+38,((long)0010644)<<16);
        assertThrows(PackageSourceArchiveImport.InvalidArchive.class,
                ()->PackageSourceArchiveImport.parse(fifo));
    }

    @Test void rejectsMalformedCentralDirectory() throws Exception {
        byte[] archive=zip(new String[][]{{"templates/valid.yaml","ok"}});
        archive[archive.length-10]=0;
        assertThrows(PackageSourceArchiveImport.InvalidArchive.class,
                ()->PackageSourceArchiveImport.parse(archive));
    }

    @Test void rejectsLocalEntryThatDisagreesWithCentralDirectorySizes() throws Exception {
        byte[] archive=zip(new String[][]{{"templates/valid.yaml","ok"}});
        int central=findSignature(archive,0x02014b50);
        if(central<0)throw new AssertionError("Missing central directory");
        int compressedSize=(archive[central+20]&0xff)|((archive[central+21]&0xff)<<8)
                |((archive[central+22]&0xff)<<16)|((archive[central+23]&0xff)<<24);
        putLittleEndian32(archive,central+20,(compressedSize&0xffffffffL)+1L);
        assertThrows(PackageSourceArchiveImport.InvalidArchive.class,
                ()->PackageSourceArchiveImport.parse(archive));
    }

    private static byte[] zip(String[][] entries) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream output=new ZipOutputStream(bytes,StandardCharsets.UTF_8)) {
            for(String[] item:entries) {
                output.putNextEntry(new ZipEntry(item[0]));
                output.write(item[1].getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static int findSignature(byte[] bytes,int signature) {
        for(int index=0;index+4<=bytes.length;index++)
            if((bytes[index]&0xff)==(signature&0xff)&&(bytes[index+1]&0xff)==((signature>>>8)&0xff)
                    &&(bytes[index+2]&0xff)==((signature>>>16)&0xff)&&(bytes[index+3]&0xff)==((signature>>>24)&0xff))
                return index;
        return -1;
    }

    private static void putLittleEndian32(byte[] bytes,int offset,long value) {
        for(int index=0;index<4;index++)bytes[offset+index]=(byte)(value>>>(8*index));
    }
}
