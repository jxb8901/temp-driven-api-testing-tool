package att.server;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class InspectionProtocolReaderTest {
    @Test void preservesFrameBoundariesAcrossChunksAndUtf8Sequences() throws Exception {
        String first="x".repeat(8191)+"中文😀";
        try(var reader=new InspectionProtocolReader(new InputStreamReader(
                new ByteArrayInputStream((first+"\r\nsecond\nlast").getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8))) {
            assertEquals(first,reader.readLine(20000));assertEquals("second",reader.readLine(20000));
            assertEquals("last",reader.readLine(20000));assertNull(reader.readLine(20000));assertNull(reader.readLine(20000));
        }
    }
    @Test void drainsOversizedFramesWithoutLosingTheFollowingFrame() throws Exception {
        try(var reader=new InspectionProtocolReader(new StringReader("x".repeat(20000)+"\nvalid\n"))) {
            assertEquals("\u0000OVERSIZED",reader.readLine(1024));
            assertEquals("valid",reader.readLine(1024));assertNull(reader.readLine(1024));
        }
    }
    @Test void handlesEmptyFramesExactLimitsAndUnterminatedOversizedInput() throws Exception {
        try(var reader=new InspectionProtocolReader(new StringReader("\r\n1234\n12345"))) {
            assertEquals("",reader.readLine(4));assertEquals("1234",reader.readLine(4));
            assertEquals("\u0000OVERSIZED",reader.readLine(4));assertNull(reader.readLine(4));assertNull(reader.readLine(4));
        }
    }
    @Test void acceptsStreamsThatDeliverOnlyOneCharacterAtATime() throws Exception {
        Reader partial=new StringReader("one\r\ntwo\n") {
            @Override public int read(char[] target,int offset,int length)throws IOException {return super.read(target,offset,Math.min(length,1));}
        };
        try(var reader=new InspectionProtocolReader(partial)) {
            assertEquals("one",reader.readLine(32));assertEquals("two",reader.readLine(32));assertNull(reader.readLine(32));
        }
    }
}
