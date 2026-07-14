package att.template;

import att.core.CaseRuntimeContext;
import att.core.TestCase;
import att.core.CaseExecutionLog;
import att.config.FrameworkConfig;
import att.config.ToolConfig;
import att.config.ToolArgumentConfig;
import att.exec.ToolInvoker;
import att.exec.ToolInvocationResult;
import att.exec.CommandRunner;
import att.exec.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test void supportsV22BuiltInsAndRejectsInvalidInputs() throws Exception {
        CaseRuntimeContext context = new CaseRuntimeContext(new TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(), new LinkedHashMap<String,Object>(), Collections.emptyMap(), null), tempDir, "RUN-1", tempDir, tempDir.resolve("case.log"));
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null);
        assertEquals("fallback", engine.render("#{nvl('', 'fallback')}", context));
        assertEquals("  ", engine.render("#{nvl('  ', 'fallback')}", context));
        assertEquals("yes", engine.render("#{iif(true, 'yes', 'no')}", context));
        assertEquals("no", engine.render("#{iif(condition=no, trueValue='yes', falseValue='no')}", context));
        assertEquals("999", engine.render("#{nchar(3, '9')}", context));
        assertEquals("abab", engine.render("#{nchar(count=2, value=ab)}", context));
        assertEquals("", engine.render("#{nchar(0, x)}", context));
        assertThrows(IllegalArgumentException.class, () -> engine.render("#{nvl('only-one')}", context));
        assertThrows(IllegalArgumentException.class, () -> engine.render("#{iif(maybe, a, b)}", context));
        assertThrows(IllegalArgumentException.class, () -> engine.render("#{nchar(1.5, x)}", context));
        assertThrows(IllegalArgumentException.class, () -> engine.render("#{nchar(10001, x)}", context));
    }

    @Test void supportsV221StringAndDateBuiltIns() throws Exception {
        CaseRuntimeContext context = new CaseRuntimeContext(new TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(), new LinkedHashMap<String,Object>(), Collections.emptyMap(), null), tempDir, "RUN-1", tempDir, tempDir.resolve("case.log"));
        Clock clock = Clock.fixed(Instant.parse("2026-07-14T04:34:56.789Z"), ZoneId.of("Asia/Hong_Kong"));
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null, new DefaultBuiltInProvider(clock));

        assertEquals("x  |  x|bcd|ef|2|4", engine.render("#{ltrim('  x  ')}|#{rtrim('  x  ')}|#{substr('abcdef', 1, 3)}|#{substr('abcdef', -2)}|#{indexOf('banana', 'na')}|#{indexOf('banana', 'na', 3)}", context));
        assertEquals("true|true|true|PAY-001|0007|7___", engine.render("#{contains('payment', 'pay')}|#{startsWith('payment', 'pay')}|#{endsWith('payment', 'ment')}|#{replace('REF-001', 'REF', 'PAY')}|#{padLeft('7', 4, '0')}|#{padRight('7', 4, '_')}", context));
        assertEquals("2026-07-14", engine.render("#{sysdate()}", context));
        assertEquals("2026-07-14T12:34:56.789+08:00", engine.render("#{systimestamp()}", context));
        assertEquals("20260714-1234", engine.render("#{formatDate('2026-07-14T04:34:56Z', 'yyyyMMdd-HHmm', 'Asia/Hong_Kong')}", context));
        assertEquals("2026-02-28|2026-07-14T05:34:56Z", engine.render("#{dateAdd('2026-01-31', 1, 'month')}|#{dateAdd('2026-07-14T04:34:56Z', 1, 'hour')}", context));

        assertThrows(IllegalArgumentException.class, () -> engine.render("#{sysdate('unexpected')}", context));
        assertThrows(IllegalArgumentException.class, () -> engine.render("#{substr('abc', 4)}", context));
        assertThrows(IllegalArgumentException.class, () -> engine.render("#{padLeft('x', 3, '')}", context));
        assertThrows(IllegalArgumentException.class, () -> engine.render("#{formatDate('14/07/2026', 'yyyyMMdd')}", context));
        assertThrows(IllegalArgumentException.class, () -> engine.render("#{dateAdd('2026-07-14', 1, 'hour')}", context));
    }

    @Test void singleValueBuiltInsAcceptOnlyNamedOrUnnamedValue() throws Exception {
        CaseRuntimeContext context = new CaseRuntimeContext(new TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(), new LinkedHashMap<String,Object>(), Collections.emptyMap(), null), tempDir, "RUN-1", tempDir, tempDir.resolve("case.log"));
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(null);
        assertEquals("ABC|ABC", engine.render("#{upper('abc')}|#{upper(value='abc')}", context));
        assertThrows(IllegalArgumentException.class, () -> engine.render("#{upper()}", context));
        assertThrows(IllegalArgumentException.class, () -> engine.render("#{upper(other='abc')}", context));
        assertThrows(IllegalArgumentException.class, () -> engine.render("#{upper('a', 'b')}", context));
    }

    @Test void singleArgumentToolAcceptsUnnamedArgument() throws Exception {
        Map<String,ToolArgumentConfig> arguments = new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("message", new ToolArgumentConfig("message", "Message", "Text", true, ""));
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("echoOne", new ToolConfig("echoOne", "Echo one", "Echo one value", "echo ${message}", "txt", arguments));
        CapturingRunner runner = new CapturingRunner();
        ToolInvoker invoker = new ToolInvoker(tempDir, new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,tools,null,null), runner);
        CaseRuntimeContext context = new CaseRuntimeContext(new TestCase(2,"payment","sheet","TC001",Collections.<String>emptyList(),new LinkedHashMap<String,Object>(),Collections.emptyMap(),null),tempDir,"RUN-1",tempDir,tempDir.resolve("case.log"));
        context.beginStage(new att.core.StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);

        assertEquals("ok", new UnifiedTemplateEngine(invoker).executeCall("#{echoOne('hello world')}", context, new CaseExecutionLog(tempDir.resolve("case.log")), "single"));
        assertEquals(Arrays.asList("echo", "hello world"), runner.calls.get(0));
        assertEquals("hello world", context.resolve("ACTIONS.single.input.message"));
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

    @Test void executesGlobalAndQualifiedGroupedCallsThroughTheSameTemplatePath() throws Exception {
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>();
        tools.put("globalEcho", new ToolConfig("globalEcho", "Global", "Global", "echo global", "txt", Collections.<String,ToolArgumentConfig>emptyMap()));
        tools.put("sample.echo", new ToolConfig("sample.echo", "echo", "sample", "Grouped", "Grouped",
                Arrays.asList("echo", "grouped"), Arrays.asList("dispatch"), "txt", Collections.<String,ToolArgumentConfig>emptyMap(), null));
        CapturingRunner runner = new CapturingRunner();
        ToolInvoker invoker = new ToolInvoker(tempDir, new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,tools,null,null), runner);
        CaseRuntimeContext context = new CaseRuntimeContext(new TestCase(2,"payment","sheet","TC001",Collections.<String>emptyList(),new LinkedHashMap<String,Object>(),Collections.emptyMap(),null),tempDir,"RUN-1",tempDir,tempDir.resolve("case.log"));
        context.beginStage(new att.core.StageCaseData("invoke","T",Collections.<String,Object>emptyMap()),"T",tempDir);
        UnifiedTemplateEngine engine = new UnifiedTemplateEngine(invoker);
        CaseExecutionLog log = new CaseExecutionLog(tempDir.resolve("case.log"));
        assertEquals("ok", engine.executeCall("#{globalEcho()}", context, log, "global"));
        assertEquals(Arrays.asList("echo", "global"), runner.calls.get(0));
        assertEquals("ok", engine.executeCall("#{sample.echo()}", context, log, "grouped"));
        assertEquals(Arrays.asList("dispatch", "echo", "echo", "grouped"), runner.calls.get(1));
    }

    private static final class CapturingInvoker extends ToolInvoker {
        private Map<String,Object> input;
        CapturingInvoker(Path root){super(root,new FrameworkConfig(null,null,null,"SIT",10000,null,null,null,null));}
        @Override public ToolInvocationResult invoke(String invocationId,String toolName,Map<String,Object> input,CaseRuntimeContext context,CaseExecutionLog log){this.input=input;return new ToolInvocationResult(toolName,invocationId,"ok",Collections.<String,Object>emptyMap());}
    }

    private static final class CapturingRunner extends CommandRunner {
        private final List<List<String>> calls = new ArrayList<List<String>>();
        @Override public CommandResult run(List<String> argv, Duration timeout, Path workingDirectory) {
            calls.add(new ArrayList<String>(argv));
            return new CommandResult(0, "ok", "", false);
        }
    }
}
