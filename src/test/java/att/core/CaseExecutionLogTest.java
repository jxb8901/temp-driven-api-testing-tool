/* Author: Jeffrey + ChatGPT */
package att.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CaseExecutionLogTest {
    @TempDir Path tempDir;
    @Test void appendsUtf8StructuredEntries() throws Exception {
        Path file=tempDir.resolve("case.log"); new CaseExecutionLog(file).append("階段","完成");
        String text=new String(Files.readAllBytes(file),"UTF-8"); assertTrue(text.contains("階段")); assertTrue(text.contains("完成"));
    }

    @Test void expandsSharedObjectsWithoutYamlAnchors() throws Exception {
        Map<String,Object> input=new LinkedHashMap<String,Object>(); input.put("requestFile","request.xml");
        Map<String,Object> action=new LinkedHashMap<String,Object>(); action.put("input",input); action.put("toolInput",input);
        Path file=tempDir.resolve("case.log"); new CaseExecutionLog(file).append("ACTION call",action);
        String text=new String(Files.readAllBytes(file),"UTF-8");
        assertFalse(text.contains("&id")); assertFalse(text.contains("*id"));
        assertEquals(2, occurrences(text,"requestFile:"));
    }

    @Test void emitsYamlAnchorsWhenEnabled() throws Exception {
        Map<String,Object> input=new LinkedHashMap<String,Object>(); input.put("requestFile","request.xml");
        Map<String,Object> action=new LinkedHashMap<String,Object>(); action.put("input",input); action.put("toolInput",input);
        Path file=tempDir.resolve("case.log"); new CaseExecutionLog(file,true).append("ACTION call",action);
        String text=new String(Files.readAllBytes(file),"UTF-8");
        assertTrue(text.contains("&id")); assertTrue(text.contains("*id"));
    }

    @Test void marksErrorFailAndInvalidBlocksForSearching() throws Exception {
        Path file=tempDir.resolve("case.log"); CaseExecutionLog log=new CaseExecutionLog(file);
        log.append("ACTION call", status("PASS"));
        log.append("ACTION call", status("FAIL"));
        log.append("ACTION retry", nestedStatus("ERROR"));
        log.append("INVALID", "missing testcase data");
        String text=new String(Files.readAllBytes(file),"UTF-8");
        assertEquals(3,occurrences(text,"【!!!!!】"));
        assertTrue(text.contains("【!!!!!】[ACTION call]"));
        assertTrue(text.contains("【!!!!!】[ACTION retry]"));
        assertTrue(text.contains("【!!!!!】[INVALID]"));
    }

    @Test void compactActionKeepsEachToolAttemptOnceAndOmitsPersistedToolTree() throws Exception {
        Map<String,Object> attempt=new LinkedHashMap<String,Object>();
        attempt.put("attempt",1); attempt.put("status","FAIL"); attempt.put("output","PAYLOAD");
        attempt.put("rawOutput","PAYLOAD"); attempt.put("stdout","PAYLOAD\n"); attempt.put("stderr","");
        attempt.put("command","'sample'"); attempt.put("logicalArgv",java.util.Collections.singletonList("sample"));
        attempt.put("argv",java.util.Collections.singletonList("sample")); attempt.put("TOOL",nestedStatus("FAIL"));
        Map<String,Object> output=new LinkedHashMap<String,Object>(); output.put("status","FAIL"); output.put("success",false);
        output.put("result","PAYLOAD"); output.put("stdout","PAYLOAD\n"); output.put("rawOutput","PAYLOAD");
        output.put("attempts",java.util.Collections.singletonList(attempt));
        Map<String,Object> action=new LinkedHashMap<String,Object>(); action.put("id","call"); action.put("type","tool");
        action.put("output",output); action.put("TOOL",nestedStatus("FAIL"));
        Path file=tempDir.resolve("compact.log"); new CaseExecutionLog(file).appendAction("ACTION call",action);

        String text=new String(Files.readAllBytes(file),"UTF-8");
        assertEquals(1,occurrences(text,"[ACTION call]"));
        assertEquals(1,occurrences(text,"PAYLOAD"));
        assertFalse(text.contains("TOOL:"));
        assertFalse(text.contains("rawOutput:"));
        assertFalse(text.contains("command:"));
    }

    private Map<String,Object> status(String value){Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("status",value);return result;}
    private Map<String,Object> nestedStatus(String value){Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("TOOL",status(value));return result;}

    private int occurrences(String text,String value){int count=0,index=0;while((index=text.indexOf(value,index))>=0){count++;index+=value.length();}return count;}
}
