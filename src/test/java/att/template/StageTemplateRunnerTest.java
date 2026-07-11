/* Author: Jeffrey + ChatGPT */
package att.template;

import att.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StageTemplateRunnerTest {
    @TempDir Path tempDir;
    @Test void recordsLogAndAssertionActions() throws Exception {
        TestCase test=new TestCase(2,"g","s","TC1",Collections.<String>emptyList(),new LinkedHashMap<String,Object>(),Collections.emptyMap(),null);
        CaseRuntimeContext context=new CaseRuntimeContext(test,tempDir,"R",tempDir,tempDir.resolve("case.log"));
        context.beginStage(new StageCaseData("verify","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        List<TemplateAction> actions=Arrays.asList(new TemplateAction("note",map("type","log","message","ok")),new TemplateAction("check",map("type","assert","expression","true")));
        List<ValidationResult> results=new StageTemplateRunner(new UnifiedTemplateEngine(null)).execute("verify",new StageTemplate("T",tempDir,actions),context,new CaseExecutionLog(tempDir.resolve("case.log")));
        assertEquals(2,results.size()); assertEquals(ResultStatus.PASS,results.get(1).status());
    }
    private Map<String,Object> map(Object... values){Map<String,Object> out=new LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)out.put(String.valueOf(values[i]),values[i+1]);return out;}
}
