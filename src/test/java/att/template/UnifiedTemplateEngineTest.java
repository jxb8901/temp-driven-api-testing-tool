package att.template;

import att.core.CaseRuntimeContext;
import att.core.TestCase;
import att.core.CaseExecutionLog;
import att.config.FrameworkConfig;
import att.exec.ToolInvoker;
import att.exec.ToolInvocationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Author: Jeffrey + ChatGPT. */
class UnifiedTemplateEngineTest {
    @TempDir Path tempDir;

    @Test void rendersContextAndBuiltInFunctions() throws Exception {
        LinkedHashMap<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("name", "  jeffrey  ");
        CaseRuntimeContext context = new CaseRuntimeContext(new TestCase(2, "payments", "payment", "sheet", "TC001", Collections.<String>emptyList(), data, Collections.emptyMap(), null), tempDir, "RUN-1", tempDir, tempDir.resolve("case.log"));
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null);
        assertEquals("JEFFREY / jeffrey / 12 / true", engine.render("#{upper(value=jeffrey)} / #{trim(value=${CASE.name})} / #{number('12.00')} / #{boolean(yes)}", context));
        assertEquals("payments.payment.TC001", engine.render("${CASE.caseId}", context));
        Map<String,Object> response = new LinkedHashMap<String,Object>();
        response.put("{urn:payment}Status", "SUCCESS");
        context.put("CASE.response", response);
        assertEquals("SUCCESS", engine.render("${CASE.response['{urn:payment}Status']}", context));
    }

    @Test void supportsQuotedCommaAndTypedBuiltInArguments() throws Exception {
        CaseRuntimeContext context = new CaseRuntimeContext(new TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(), new LinkedHashMap<String,Object>(), Collections.emptyMap(), null), tempDir, "RUN-1", tempDir, tempDir.resolve("case.log"));
        assertEquals("hello, world:true:12.5", new UnifiedTemplateEngine(null).render("#{concat('hello, world', ':', true, ':', 12.5)}", context));
    }

    @Test void configuredToolReceivesContextTypesWithoutGuessing() throws Exception {
        LinkedHashMap<String,Object> data=new LinkedHashMap<String,Object>();
        data.put("account","00123"); data.put("count",7); data.put("enabled",true); data.put("items",Collections.singletonList(Collections.singletonMap("status","READY")));
        CaseRuntimeContext context=new CaseRuntimeContext(new TestCase(2,"payment","sheet","TC001",Collections.<String>emptyList(),data,Collections.emptyMap(),null),tempDir,"RUN-1",tempDir,tempDir.resolve("case.log"));
        CapturingInvoker invoker=new CapturingInvoker(tempDir);
        Object output=new UnifiedTemplateEngine(invoker).executeCall("#{capture(account=${CASE.account}, count=${CASE.count}, enabled=${CASE.enabled}, message='hello, world', first=${CASE.items[0].status})}",context,new CaseExecutionLog(tempDir.resolve("tool.log")),"call");
        assertEquals("ok",output);
        assertEquals("00123",invoker.input.get("account"));
        assertEquals(Integer.valueOf(7),invoker.input.get("count"));
        assertEquals(Boolean.TRUE,invoker.input.get("enabled"));
        assertEquals("hello, world",invoker.input.get("message"));
        assertEquals("READY",invoker.input.get("first"));
    }

    private static final class CapturingInvoker extends ToolInvoker {
        private Map<String,Object> input;
        CapturingInvoker(Path root){super(root,new FrameworkConfig(null,null,null,"SIT",10000,null,null,null,null));}
        @Override public ToolInvocationResult invoke(String invocationId,String toolName,Map<String,Object> input,CaseRuntimeContext context,CaseExecutionLog log){this.input=input;return new ToolInvocationResult(toolName,invocationId,"ok",Collections.<String,Object>emptyMap());}
    }
}
