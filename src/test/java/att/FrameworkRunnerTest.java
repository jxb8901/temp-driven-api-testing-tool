/* Author: Jeffrey + ChatGPT */
package att;

import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class FrameworkRunnerTest {
    @Test void helpDocumentsSinglePageAndAllSelection() throws Exception {
        java.lang.reflect.Method help=FrameworkRunner.class.getDeclaredMethod("help"); help.setAccessible(true);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); PrintStream previous=System.out;
        try { System.setOut(new PrintStream(bytes)); help.invoke(null); } finally { System.setOut(previous); }
        String text=bytes.toString("UTF-8"); assertTrue(text.contains("--single-page")); assertTrue(text.contains("--all"));
    }
}
