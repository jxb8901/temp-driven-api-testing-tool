/* Author: Jeffrey + ChatGPT */
package att.validation;

import att.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.nio.file.Files;
import att.template.*;
import static org.junit.jupiter.api.Assertions.*;

class PackageValidatorTest {
    @TempDir Path tempDir;

    private static Map<String,Object> map(Object... values) {
        Map<String,Object> result = new LinkedHashMap<String,Object>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    @Test void validatesFirstClassDbActionsAndExpressionSourcesWithoutConnecting() throws Exception {
        Files.createDirectories(tempDir.resolve("sql"));
        Files.write(tempDir.resolve("sql/find.sql"), "select id from orders where id = ?".getBytes("UTF-8"));
        DbHelperConfig helper = new DbHelperConfig("orders", "Orders", "Orders DB", "jdbc:never-connect",
                "", "", "", Collections.<String,String>emptyMap(), false, "driverDefault", 7,
                "case", "rollback", 10, 1024, 4096, "full", "masked", null);
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,tempDir,
                Collections.<String,ToolConfig>emptyMap(), Collections.singletonMap("orders", helper), null, null,
                null,"","",null,null,1,"ignore","",false,ProcessOutputConfig.defaults());
        PackageValidator validator = new PackageValidator(tempDir, config);
        java.lang.reflect.Method contract = PackageValidator.class.getDeclaredMethod("validateTemplate", StageTemplate.class, FrameworkConfig.class);
        contract.setAccessible(true);

        TemplateAction query = new TemplateAction("query", map("type","db","db","orders",
                "query",map("sqlFile","sql/find.sql","params",Collections.singletonList("${CASE.id}")),
                "saveAs",map("path","result.json","format","json")), "att-template/v2.5");
        TemplateAction expression = new TemplateAction("check", map("type","assert",
                "assert","#{db.orders.scalar(sql='select count(*) from orders where id = ?', params=[${CASE.id}, 'OPEN'])} >= 0"),
                "att-template/v2.5");
        assertDoesNotThrow(() -> { try { contract.invoke(validator,
                new StageTemplate("DB",tempDir,Arrays.asList(query, expression),"att-template/v2.5"),config); }
            catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
            catch (Exception e) { throw new RuntimeException(e); } });

        TemplateAction updateAsTool = new TemplateAction("wrong", map("type","tool",
                "call","#{db.orders.query(sql='select 1')}"), "att-template/v2.5");
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> contract.invoke(validator,
                new StageTemplate("DB",tempDir,Collections.singletonList(updateAsTool),"att-template/v2.5"),config));

        TemplateAction dynamicFile = new TemplateAction("dynamic", map("type","assign","name","x",
                "expression","#{db.orders.query(sqlFile=${CASE.file}, params=[])}"), "att-template/v2.5");
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> contract.invoke(validator,
                new StageTemplate("DB",tempDir,Collections.singletonList(dynamicFile),"att-template/v2.5"),config));
    }

    @Test void validatesCallBackedReadAndWriteToolBoundaries() throws Exception {
        DbHelperConfig helper = new DbHelperConfig("orders", "Orders", "Orders DB", "jdbc:never-connect",
                "", "", "", Collections.<String,String>emptyMap(), false, "driverDefault", 7,
                "case", "rollback", 10, 1024, 4096, "full", "masked", null);
        Map<String, ToolArgumentConfig> arguments = Collections.singletonMap("id",
                new ToolArgumentConfig("id", "ID", "Order ID", true, ""));
        ToolConfig query = new ToolConfig("orders.find", "find", "orders", "Find", "Find",
                Collections.<String>emptyList(), "#{db.orders.query(sql='select * from orders where id = ?', params=[input.id])}",
                "case", Collections.<String>emptyList(), "", arguments, null, null);
        ToolConfig update = new ToolConfig("orders.close", "close", "orders", "Close", "Close",
                Collections.<String>emptyList(), "#{db.orders.update(sql='update orders set status = 1 where id = ?', params=[input.id])}",
                "", Collections.<String>emptyList(), "", arguments, null, null);
        Map<String, ToolConfig> tools = new LinkedHashMap<String, ToolConfig>();
        tools.put(query.key(), query); tools.put(update.key(), update);
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,tempDir,
                tools, Collections.singletonMap("orders", helper), null,null,null,"","",null,null,1,
                "ignore","",false,ProcessOutputConfig.defaults());
        PackageValidator validator = new PackageValidator(tempDir, config);
        java.lang.reflect.Method contract = PackageValidator.class.getDeclaredMethod("validateTemplate", StageTemplate.class, FrameworkConfig.class);
        contract.setAccessible(true);

        TemplateAction readExpression = new TemplateAction("read", map("type","assign","name","orders",
                "expression","#{orders.find(id=${CASE.id})}"), "att-template/v2.5");
        TemplateAction writeAction = new TemplateAction("write", map("type","tool",
                "call","#{orders.close(id=${CASE.id})}"), "att-template/v2.5");
        assertDoesNotThrow(() -> { try { contract.invoke(validator,
                new StageTemplate("Facade", tempDir, Arrays.asList(readExpression, writeAction), "att-template/v2.5"), config); }
            catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
            catch (Exception e) { throw new RuntimeException(e); } });

        TemplateAction writeExpression = new TemplateAction("bad", map("type","assign","name","x",
                "expression","#{orders.close(id=${CASE.id})}"), "att-template/v2.5");
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> contract.invoke(validator,
                new StageTemplate("Facade", tempDir, Collections.singletonList(writeExpression), "att-template/v2.5"), config));

        TemplateAction processOptions = new TemplateAction("bad", map("type","tool",
                "call","#{orders.find(id=${CASE.id})}", "timeoutMs", 1000), "att-template/v2.5");
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> contract.invoke(validator,
                new StageTemplate("Facade", tempDir, Collections.singletonList(processOptions), "att-template/v2.5"), config));

        TemplateAction rawSave = new TemplateAction("bad", map("type","tool",
                "call","#{orders.find(id=${CASE.id})}", "saveAs", map("path","out.json","format","raw")), "att-template/v2.5");
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> contract.invoke(validator,
                new StageTemplate("Facade", tempDir, Collections.singletonList(rawSave), "att-template/v2.5"), config));
        TemplateAction legacySave = new TemplateAction("bad", map("type","tool",
                "call","#{orders.find(id=${CASE.id})}", "saveAs", "out.txt"), "att-template/v2.5");
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> contract.invoke(validator,
                new StageTemplate("Facade", tempDir, Collections.singletonList(legacySave), "att-template/v2.5"), config));

        ToolConfig invalid = new ToolConfig("orders.invalid", "invalid", "orders", "Invalid", "Invalid",
                Collections.<String>emptyList(), "#{db.missing.query(sql='select 1')}", "",
                Collections.<String>emptyList(), "", Collections.<String,ToolArgumentConfig>emptyMap(), null, null);
        Map<String,ToolConfig> invalidTools = new LinkedHashMap<String,ToolConfig>(tools);
        invalidTools.put(invalid.key(), invalid);
        FrameworkConfig selectedConfig = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,tempDir,
                invalidTools, Collections.singletonMap("orders", helper), null,null,null,"","",null,null,1,
                "ignore","",false,ProcessOutputConfig.defaults());
        PackageValidator selectedValidator = new PackageValidator(tempDir, selectedConfig);
        TemplateAction invalidReference = new TemplateAction("bad", map("type","tool",
                "call","#{orders.invalid()}"), "att-template/v2.5");
        java.lang.reflect.Method referenced = PackageValidator.class.getDeclaredMethod("validateReferencedTools", StageTemplate.class, FrameworkConfig.class);
        referenced.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> referenced.invoke(selectedValidator,
                new StageTemplate("Facade", tempDir, Collections.singletonList(invalidReference), "att-template/v2.5"), selectedConfig));
    }

    @Test void validatorUsesSharedParserForQuotedComma() throws Exception {
        Map<String,ToolArgumentConfig> args = new LinkedHashMap<String,ToolArgumentConfig>();
        args.put("message", new ToolArgumentConfig("message", "Message", "Text", true, ""));
        ToolConfig tool = new ToolConfig("send", "Send", "Send message", "echo", "txt", args);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>(); tools.put("send", tool);
        FrameworkConfig config = new FrameworkConfig(tempDir, tempDir, tempDir, "SIT", 10, tempDir, tools, null, null);
        PackageValidator validator = new PackageValidator(tempDir, config);
        java.lang.reflect.Method method = PackageValidator.class.getDeclaredMethod("validateToolCall", String.class, FrameworkConfig.class);
        method.setAccessible(true);
        assertDoesNotThrow(() -> { try { method.invoke(validator, "#{send(message='hello, world')}", config); } catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); } catch (Exception e) { throw new RuntimeException(e); } });
        assertDoesNotThrow(() -> { try { method.invoke(validator, "#{send('hello, world')}", config); } catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); } catch (Exception e) { throw new RuntimeException(e); } });
    }

    @Test void positionalToolArgumentsRemainInvalidForMultiArgumentTools() throws Exception {
        Map<String,ToolArgumentConfig> args = new LinkedHashMap<String,ToolArgumentConfig>();
        args.put("first", new ToolArgumentConfig("first", "First", "First", true, ""));
        args.put("second", new ToolArgumentConfig("second", "Second", "Second", true, ""));
        ToolConfig tool = new ToolConfig("send", "Send", "Send values", "echo", "txt", args);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>(); tools.put("send", tool);
        PackageValidator validator = new PackageValidator(tempDir, new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10,tempDir,tools,null,null));
        java.lang.reflect.Method method = PackageValidator.class.getDeclaredMethod("validateToolCall", String.class, FrameworkConfig.class);
        method.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(validator, "#{send('a', 'b')}", new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10,tempDir,tools,null,null)));
    }

    @Test void windowsPathLookupUsesPathExtWithoutChangingUnixLookup() {
        assertEquals(Arrays.asList("pwsh", "pwsh.EXE", "pwsh.CMD"), PackageValidator.executableCandidates("pwsh", true, ".EXE;.CMD"));
        assertEquals(Collections.singletonList("pwsh.exe"), PackageValidator.executableCandidates("pwsh.exe", true, ".EXE;.CMD"));
        assertEquals(Collections.singletonList("pwsh"), PackageValidator.executableCandidates("pwsh", false, ".EXE;.CMD"));
    }

    @Test void windowsValidationSkipsOnlyShellExecutabilityAndEmitsOneWarning() throws Exception {
        Path toolsDir = tempDir.resolve("tools"); Files.createDirectories(toolsDir);
        Path shell = toolsDir.resolve("sample.sh"); Files.write(shell, "#!/bin/sh\n".getBytes("UTF-8"));
        shell.toFile().setExecutable(false);
        ToolConfig tool = new ToolConfig("sample", "Sample", "POSIX sample", "./tools/sample.sh", "txt",
                Collections.<String,ToolArgumentConfig>emptyMap());
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,
                Collections.singletonMap("sample",tool),null,null);
        PackageValidator windowsValidator = new PackageValidator(tempDir, config, true);
        java.lang.reflect.Method executable = PackageValidator.class.getDeclaredMethod("validateToolExecutable", ToolConfig.class);
        executable.setAccessible(true);
        assertDoesNotThrow(() -> { try { executable.invoke(windowsValidator, tool); }
            catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
            catch (Exception e) { throw new RuntimeException(e); } });

        java.lang.reflect.Method warning = PackageValidator.class.getDeclaredMethod("addWindowsShellExecutableWarning", List.class);
        warning.setAccessible(true);
        List<Diagnostic> diagnostics = new ArrayList<Diagnostic>(); warning.invoke(windowsValidator, diagnostics);
        assertEquals(1, diagnostics.size());
        assertEquals(Diagnostic.Severity.WARNING, diagnostics.get(0).severity());
        assertTrue(diagnostics.get(0).message().contains("sample"));
        assertTrue(diagnostics.get(0).message().contains("did not check whether POSIX .sh tools can be launched"));

        PackageValidator unixValidator = new PackageValidator(tempDir, config, false);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> executable.invoke(unixValidator, tool));
    }

    @Test void windowsValidationStillRejectsMissingOrUnsafeShellFiles() throws Exception {
        ToolConfig missing = new ToolConfig("missing", "Missing", "Missing script", "./tools/missing.sh", "txt",
                Collections.<String,ToolArgumentConfig>emptyMap());
        PackageValidator validator = new PackageValidator(tempDir,
                new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,Collections.<String,ToolConfig>emptyMap(),null,null), true);
        java.lang.reflect.Method executable = PackageValidator.class.getDeclaredMethod("validateToolExecutable", ToolConfig.class);
        executable.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> executable.invoke(validator, missing));
    }

    @Test void windowsPackageValidationPublishesSkippedShellWarning() throws Exception {
        Path root = java.nio.file.Paths.get("").toAbsolutePath().normalize();
        FrameworkConfig config = new FrameworkConfigLoader().load(root.resolve("config/config.yaml"), root);
        PackageValidator.ValidationSummary summary = new PackageValidator(root, config, true)
                .validate(att.core.ExecutionOptions.parse(new String[]{"validate", "--package"}));
        List<Diagnostic> warnings = new ArrayList<Diagnostic>();
        for (Diagnostic diagnostic : summary.diagnostics) if (diagnostic.severity() == Diagnostic.Severity.WARNING
                && diagnostic.message().contains("did not check whether POSIX .sh tools can be launched")) warnings.add(diagnostic);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).message().contains("invokePaymentApi"));
        assertTrue(warnings.get(0).message().contains("sample.getSeq"));
    }
    @Test void enforcesTypeSpecificActionContracts() throws Exception {
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10,tempDir,Collections.<String,ToolConfig>emptyMap(),null,null);
        PackageValidator validator = new PackageValidator(tempDir,config);
        java.lang.reflect.Method method = PackageValidator.class.getDeclaredMethod("validateTemplate", StageTemplate.class, FrameworkConfig.class); method.setAccessible(true);
        Map<String,Object> invalidRender = new LinkedHashMap<String,Object>(); invalidRender.put("type","render"); invalidRender.put("payload","p.txt"); invalidRender.put("saveAs","out.txt"); invalidRender.put("call","#{x()}");
        Files.write(tempDir.resolve("p.txt"),new byte[]{1});
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(validator,new StageTemplate("T",tempDir,Collections.singletonList(new TemplateAction("render",invalidRender))),config));
        Map<String,Object> builtInTool = new LinkedHashMap<String,Object>(); builtInTool.put("type","tool"); builtInTool.put("call","#{upper(value='x')}");
        assertDoesNotThrow(() -> { try { method.invoke(validator,new StageTemplate("T",tempDir,Collections.singletonList(new TemplateAction("tool",builtInTool))),config); }
            catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
            catch (Exception e) { throw new RuntimeException(e); } });
        Map<String,Object> fileLog = new LinkedHashMap<String,Object>(); fileLog.put("type","log"); fileLog.put("file","${CASE.outputDirectory}/response.txt");
        assertDoesNotThrow(() -> { try { method.invoke(validator,new StageTemplate("T",tempDir,Collections.singletonList(new TemplateAction("log",fileLog))),config); }
            catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
            catch (Exception e) { throw new RuntimeException(e); } });
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(validator,
                new StageTemplate("T",tempDir,Collections.singletonList(new TemplateAction("emptyLog",Collections.<String,Object>singletonMap("type","log")))),config));
    }
    @Test void validatesRenderGlobMatchesAndStaticStructuredPayloads() throws Exception {
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10,tempDir,Collections.<String,ToolConfig>emptyMap(),null,null);
        PackageValidator validator = new PackageValidator(tempDir,config);
        java.lang.reflect.Method method = PackageValidator.class.getDeclaredMethod("validateTemplate", StageTemplate.class, FrameworkConfig.class); method.setAccessible(true);
        Files.createDirectories(tempDir.resolve("data"));
        Files.write(tempDir.resolve("data/valid.json"),"{\"ok\":true}".getBytes("UTF-8"));
        Map<String,Object> valid = new LinkedHashMap<String,Object>(); valid.put("type","render"); valid.put("payload","data/*.json"); valid.put("renderAs","json");
        assertDoesNotThrow(() -> { try { method.invoke(validator,new StageTemplate("T",tempDir,Collections.singletonList(new TemplateAction("render",valid))),config); } catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); } catch (Exception e) { throw new RuntimeException(e); } });
        Files.write(tempDir.resolve("data/invalid.json"),"{".getBytes("UTF-8"));
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(validator,new StageTemplate("T",tempDir,Collections.singletonList(new TemplateAction("render",valid))),config));
        valid.put("payload","missing/*.json");
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(validator,new StageTemplate("T",tempDir,Collections.singletonList(new TemplateAction("render",valid))),config));
    }
    @Test void referencedToolExecutableCannotEscapePackage() throws Exception {
        Path project=tempDir.resolve("project"), outside=tempDir.resolve("outside.sh"); Files.createDirectories(project); Files.write(outside, "#!/bin/sh\n".getBytes("UTF-8")); outside.toFile().setExecutable(true);
        ToolConfig tool=new ToolConfig("outside","Outside","test","../outside.sh","txt",Collections.<String,ToolArgumentConfig>emptyMap());
        PackageValidator validator=new PackageValidator(project,new FrameworkConfig(project,project,project,"SIT",1000,project,Collections.<String,ToolConfig>emptyMap(),null,null));
        java.lang.reflect.Method method=PackageValidator.class.getDeclaredMethod("validateToolExecutable",ToolConfig.class); method.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(validator,tool));
    }

    @Test void validateRejectsUnknownInlineToolAndStaticContextTyposBeforeRun() throws Exception {
        Path payload = tempDir.resolve("request.txt");
        Files.write(payload, "sequence=#{fpp.getSeq(name='PAYMENT')}".getBytes("UTF-8"));
        ToolConfig getSeq = new ToolConfig("getSeq", "Get sequence", "Generate sequence", "echo", "txt",
                Collections.<String,ToolArgumentConfig>emptyMap());
        Map<String,ToolConfig> configuredTools = new LinkedHashMap<String,ToolConfig>(); configuredTools.put("getSeq", getSeq);
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,
                configuredTools,null,null);
        PackageValidator validator = new PackageValidator(tempDir, config);
        Map<String,Object> render = new LinkedHashMap<String,Object>();
        render.put("type", "render"); render.put("payload", "request.txt"); render.put("renderAs", "text");
        StageTemplate template = new StageTemplate("PAYMENT", tempDir,
                Collections.singletonList(new TemplateAction("request", render)));
        java.lang.reflect.Method contract = PackageValidator.class.getDeclaredMethod("validateTemplate", StageTemplate.class, FrameworkConfig.class);
        contract.setAccessible(true);
        java.lang.reflect.InvocationTargetException unknown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> contract.invoke(validator, template, config));
        DiagnosticException toolError = DiagnosticException.find(unknown.getCause());
        assertNotNull(toolError);
        assertEquals(DiagnosticCodes.TOOL_INVALID, toolError.code());
        assertTrue(toolError.format().contains("fpp.getSeq"));
        assertTrue(toolError.suggestion().contains("getSeq"));
        assertEquals("PAYMENT", toolError.template());
        assertEquals("request", toolError.action());

        Files.write(payload, "case=${CASE.csaeId}".getBytes("UTF-8"));
        java.lang.reflect.Method values = PackageValidator.class.getDeclaredMethod("validateTemplateValues", StageTemplate.class,
                att.core.TestCase.class, att.core.StageCaseData.class, FrameworkConfig.class);
        values.setAccessible(true);
        att.core.StageCaseData stage = new att.core.StageCaseData("invoke", "PAYMENT", Collections.<String,Object>singletonMap("name", "PAYMENT"));
        att.core.TestCase testCase = new att.core.TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.singletonMap("invoke", stage), null);
        java.lang.reflect.InvocationTargetException typo = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> values.invoke(validator, template, testCase, stage, config));
        DiagnosticException contextError = DiagnosticException.find(typo.getCause());
        assertNotNull(contextError);
        assertEquals(DiagnosticCodes.CONTEXT_INVALID, contextError.code());
        assertTrue(contextError.format().contains("CASE.caseId"));
    }

    @Test void validateRejectsUnknownRunStageAndFutureActionContextBeforeRun() throws Exception {
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,
                Collections.<String,ToolConfig>emptyMap(),null,null);
        PackageValidator validator = new PackageValidator(tempDir, config);
        java.lang.reflect.Method values = PackageValidator.class.getDeclaredMethod("validateTemplateValues", StageTemplate.class,
                att.core.TestCase.class, att.core.StageCaseData.class, FrameworkConfig.class);
        values.setAccessible(true);
        att.core.StageCaseData stage = new att.core.StageCaseData("invoke", "PAYMENT", Collections.<String,Object>singletonMap("name", "PAYMENT"));
        att.core.TestCase testCase = new att.core.TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.singletonMap("invoke", stage), null);

        for (String invalid : Arrays.asList("${RUN.runID}", "${CASE.STAGES.invkoe.status}", "${ACTIONS.later.output.result}",
                "#{length(RUN.runID)}", "#{length(CASE.STAGES.invkoe.status)}", "#{length(ACTIONS.later.output.result)}")) {
            Map<String,Object> log = new LinkedHashMap<String,Object>();
            log.put("type", "log"); log.put("message", invalid);
            Map<String,Object> later = new LinkedHashMap<String,Object>();
            later.put("type", "log"); later.put("message", "done");
            StageTemplate template = new StageTemplate("PAYMENT", tempDir, Arrays.asList(
                    new TemplateAction("first", log), new TemplateAction("later", later)));
            java.lang.reflect.InvocationTargetException thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> values.invoke(validator, template, testCase, stage, config), invalid);
            DiagnosticException error = DiagnosticException.find(thrown.getCause());
            assertNotNull(error, invalid);
            assertEquals(DiagnosticCodes.CONTEXT_INVALID, error.code(), invalid);
        }
    }

    @Test void caseBoundValidationUsesTheRuntimeUniqueSuffixResolver() throws Exception {
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,
                Collections.<String,ToolConfig>emptyMap(),null,null);
        PackageValidator validator = new PackageValidator(tempDir, config);
        java.lang.reflect.Method values = PackageValidator.class.getDeclaredMethod("validateTemplateValues", StageTemplate.class,
                att.core.TestCase.class, att.core.StageCaseData.class, FrameworkConfig.class);
        values.setAccessible(true);
        att.core.StageCaseData stage = new att.core.StageCaseData("invoke", "PAYMENT", Collections.<String,Object>singletonMap("name", "PAYMENT"));
        Map<String,Object> response = new LinkedHashMap<String,Object>(); response.put("resultCode", "SUCCESS");
        Map<String,Object> payment = new LinkedHashMap<String,Object>(); payment.put("response", response);
        Map<String,Object> data = new LinkedHashMap<String,Object>(); data.put("payment", payment);
        att.core.TestCase testCase = new att.core.TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(),
                data, Collections.singletonMap("invoke", stage), null);
        Map<String,Object> log = new LinkedHashMap<String,Object>(); log.put("type", "log"); log.put("message", "code=${resultCode}");
        StageTemplate template = new StageTemplate("PAYMENT", tempDir, Collections.singletonList(new TemplateAction("show", log)));
        assertDoesNotThrow(() -> { try { values.invoke(validator, template, testCase, stage, config); }
            catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); } });

        Map<String,Object> refundResponse = new LinkedHashMap<String,Object>(); refundResponse.put("resultCode", "REFUNDED");
        Map<String,Object> ambiguousData = new LinkedHashMap<String,Object>(data);
        ambiguousData.put("refund", Collections.singletonMap("response", refundResponse));
        att.core.TestCase ambiguousCase = new att.core.TestCase(2, "payment", "sheet", "TC001", Collections.<String>emptyList(),
                ambiguousData, Collections.singletonMap("invoke", stage), null);
        java.lang.reflect.InvocationTargetException thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> values.invoke(validator, template, ambiguousCase, stage, config));
        DiagnosticException error = DiagnosticException.find(thrown.getCause());
        assertNotNull(error);
        assertEquals(DiagnosticCodes.CONTEXT_AMBIGUOUS, error.code());
        assertTrue(error.format().contains("CASE.payment.response.resultCode"));
        assertTrue(error.format().contains("CASE.refund.response.resultCode"));
    }

    @Test void packageTemplateValidationChecksContextScopesWithoutAnyCaseReference() throws Exception {
        Map<String,ToolArgumentConfig> arguments = new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("requestFile", new ToolArgumentConfig("requestFile", "Request file", "Rendered request", true, ""));
        ToolConfig invoke = new ToolConfig("fpp.invokeApi", "invokeApi", "fpp", "Invoke API", "Invoke API",
                Arrays.asList("echo", "${requestFile}"), Collections.<String>emptyList(), "txt", arguments, null);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>(); tools.put("fpp.invokeApi", invoke);
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,tools,null,null);
        PackageValidator validator = new PackageValidator(tempDir, config);
        java.lang.reflect.Method contract = PackageValidator.class.getDeclaredMethod("validateTemplate", StageTemplate.class, FrameworkConfig.class);
        contract.setAccessible(true);

        Map<String,Object> rendered = new LinkedHashMap<String,Object>();
        rendered.put("type", "log"); rendered.put("message", "rendered");
        Map<String,Object> invalidCall = new LinkedHashMap<String,Object>();
        invalidCall.put("type", "tool");
        invalidCall.put("call", "#{fpp.invokeApi(requestFile=${xxrenderPrecheckRequest.output.targetFiles[0]})}");
        StageTemplate invalid = new StageTemplate("UNREFERENCED", tempDir, Arrays.asList(
                new TemplateAction("renderPrecheckRequest", rendered), new TemplateAction("invokePrecheck", invalidCall)));

        java.lang.reflect.InvocationTargetException thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> contract.invoke(validator, invalid, config));
        DiagnosticException error = DiagnosticException.find(thrown.getCause());
        assertNotNull(error);
        assertEquals(DiagnosticCodes.CONTEXT_INVALID, error.code());
        assertEquals("UNREFERENCED", error.template());
        assertEquals("invokePrecheck", error.action());
        assertTrue(error.suggestion().contains("${ACTIONS.renderPrecheckRequest.output.targetFiles[0]}"));

        Map<String,Object> validCall = new LinkedHashMap<String,Object>(invalidCall);
        validCall.put("call", "#{fpp.invokeApi(requestFile=${ACTIONS.renderPrecheckRequest.output.targetFiles[0]})}");
        StageTemplate valid = new StageTemplate("UNREFERENCED", tempDir, Arrays.asList(
                new TemplateAction("renderPrecheckRequest", rendered), new TemplateAction("invokePrecheck", validCall)));
        assertDoesNotThrow(() -> { try { contract.invoke(validator, valid, config); }
            catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
            catch (Exception e) { throw new RuntimeException(e); } });
    }

    @Test void caseContextFailurePointsToTemplateSourceAndIdentifiesTriggeringCase() throws Exception {
        Map<String,ToolArgumentConfig> arguments = new LinkedHashMap<String,ToolArgumentConfig>();
        arguments.put("requestId", new ToolArgumentConfig("requestId", "Request ID", "Correlation ID", true, ""));
        ToolConfig invoke = new ToolConfig("invoke", "Invoke", "Invoke", "echo", "txt", arguments);
        Map<String,ToolConfig> tools = new LinkedHashMap<String,ToolConfig>(); tools.put("invoke", invoke);
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,tools,null,null);
        PackageValidator validator = new PackageValidator(tempDir, config);
        Map<String,Object> tool = new LinkedHashMap<String,Object>();
        tool.put("type", "tool");
        tool.put("call", "#{invoke(requestId=${CASE.precheckRequestId})}");
        StageTemplate template = new StageTemplate("CTO/RTI", tempDir,
                Collections.singletonList(new TemplateAction("invokePrecheck", tool)));
        att.core.StageCaseData stage = new att.core.StageCaseData("invoke", "CTO/RTI", Collections.<String,Object>emptyMap());
        att.core.TestCase testCase = new att.core.TestCase(3, "payment2", "CT", "CT001", Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(), Collections.singletonMap("invoke", stage), null);
        Path workbook = tempDir.resolve("testcase/payment2.xlsx");
        java.lang.reflect.Method values = PackageValidator.class.getDeclaredMethod("validateTemplateValues", StageTemplate.class,
                att.core.TestCase.class, att.core.StageCaseData.class, FrameworkConfig.class, Path.class);
        values.setAccessible(true);

        java.lang.reflect.InvocationTargetException thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> values.invoke(validator, template, testCase, stage, config, workbook));
        DiagnosticException error = DiagnosticException.find(thrown.getCause());
        assertNotNull(error);
        assertEquals(DiagnosticCodes.CONTEXT_INVALID, error.code());
        assertEquals(tempDir.resolve("template.yaml").toString(), error.file());
        assertEquals("actions.invokePrecheck.call", error.field());
        assertEquals("CT", error.sheet());
        assertEquals(Integer.valueOf(3), error.row());
        assertTrue(error.detail().contains("Case source: testcase/payment2.xlsx!CT:3"));
    }

    @Test void validateTracksCaseVariablesAcrossOrderedStagesAndRejectsEarlyOrDuplicateUse() throws Exception {
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,
                Collections.<String,ToolConfig>emptyMap(),null,null);
        PackageValidator validator = new PackageValidator(tempDir, config);
        Map<String,att.core.StageCaseData> stages = new LinkedHashMap<String,att.core.StageCaseData>();
        att.core.StageCaseData prepare = new att.core.StageCaseData("prepare", "PREPARE", Collections.<String,Object>emptyMap());
        att.core.StageCaseData invoke = new att.core.StageCaseData("invoke", "INVOKE", Collections.<String,Object>emptyMap());
        stages.put("prepare", prepare); stages.put("invoke", invoke);
        att.core.TestCase testCase = new att.core.TestCase(3,"payment2","CT","CT001",Collections.<String>emptyList(),
                Collections.<String,Object>emptyMap(),stages,null);
        Map<String,Object> assignMap = new LinkedHashMap<String,Object>();
        assignMap.put("type","assign"); assignMap.put("name","txnSeq");
        assignMap.put("expression","ATT#{sysdate('yyyyMMdd')}");
        StageTemplate producer = new StageTemplate("PREPARE",tempDir,
                Collections.singletonList(new TemplateAction("buildTxnSeq",assignMap)));
        Map<String,Object> logMap = new LinkedHashMap<String,Object>();
        logMap.put("type","log"); logMap.put("message","id=${CASE.VARS.txnSeq}");
        StageTemplate consumer = new StageTemplate("INVOKE",tempDir,
                Collections.singletonList(new TemplateAction("useTxnSeq",logMap)));
        java.lang.reflect.Method values = PackageValidator.class.getDeclaredMethod("validateTemplateValues", StageTemplate.class,
                att.core.TestCase.class, att.core.StageCaseData.class, FrameworkConfig.class, Path.class, Set.class);
        values.setAccessible(true);
        Set<String> assigned = new LinkedHashSet<String>();
        assertDoesNotThrow(() -> { try { values.invoke(validator,producer,testCase,prepare,config,tempDir.resolve("payment2.xlsx"),assigned); }
            catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
            catch (Exception e) { throw new RuntimeException(e); } });
        assertTrue(assigned.contains("txnSeq"));
        assertDoesNotThrow(() -> { try { values.invoke(validator,consumer,testCase,invoke,config,tempDir.resolve("payment2.xlsx"),assigned); }
            catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); }
            catch (Exception e) { throw new RuntimeException(e); } });

        Set<String> empty = new LinkedHashSet<String>();
        java.lang.reflect.InvocationTargetException early = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> values.invoke(validator,consumer,testCase,invoke,config,tempDir.resolve("payment2.xlsx"),empty));
        DiagnosticException earlyError = DiagnosticException.find(early.getCause());
        assertNotNull(earlyError);
        assertEquals(DiagnosticCodes.CONTEXT_INVALID, earlyError.code());
        assertEquals("actions.useTxnSeq.message", earlyError.field());

        java.lang.reflect.InvocationTargetException duplicate = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> values.invoke(validator,producer,testCase,prepare,config,tempDir.resolve("payment2.xlsx"),assigned));
        DiagnosticException duplicateError = DiagnosticException.find(duplicate.getCause());
        assertNotNull(duplicateError);
        assertEquals(DiagnosticCodes.CONTEXT_INVALID, duplicateError.code());
        assertEquals("actions.buildTxnSeq.name", duplicateError.field());
    }

    @Test void validateAssignExpressionUsesNormalInlineCallContracts() throws Exception {
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,
                Collections.<String,ToolConfig>emptyMap(),null,null);
        PackageValidator validator = new PackageValidator(tempDir, config);
        Map<String,Object> assign = new LinkedHashMap<String,Object>();
        assign.put("type","assign"); assign.put("name","txnSeq"); assign.put("expression","#{missing.getSeq()}");
        StageTemplate template = new StageTemplate("ASSIGN",tempDir,
                Collections.singletonList(new TemplateAction("build",assign)));
        java.lang.reflect.Method contract = PackageValidator.class.getDeclaredMethod("validateTemplate",StageTemplate.class,FrameworkConfig.class);
        contract.setAccessible(true);
        java.lang.reflect.InvocationTargetException thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> contract.invoke(validator,template,config));
        DiagnosticException error = DiagnosticException.find(thrown.getCause());
        assertNotNull(error);
        assertEquals(DiagnosticCodes.TOOL_INVALID,error.code());
        assertEquals("build",error.action());
    }

    @Test void validateRejectsInvalidStaticSystemDateFormatBeforeRun() throws Exception {
        Path payload = tempDir.resolve("date.txt");
        Files.write(payload, "date=#{sysdate(\"yyyy-MM-dd'\")}".getBytes("UTF-8"));
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,
                Collections.<String,ToolConfig>emptyMap(),null,null);
        PackageValidator validator = new PackageValidator(tempDir, config);
        Map<String,Object> render = new LinkedHashMap<String,Object>();
        render.put("type", "render"); render.put("payload", "date.txt"); render.put("renderAs", "text");
        StageTemplate template = new StageTemplate("DATES", tempDir,
                Collections.singletonList(new TemplateAction("date", render)));
        java.lang.reflect.Method contract = PackageValidator.class.getDeclaredMethod("validateTemplate", StageTemplate.class, FrameworkConfig.class);
        contract.setAccessible(true);

        java.lang.reflect.InvocationTargetException thrown = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> contract.invoke(validator, template, config));
        DiagnosticException error = DiagnosticException.find(thrown.getCause());
        assertNotNull(error);
        assertEquals(DiagnosticCodes.BUILTIN_INVALID, error.code());
        assertTrue(error.detail().contains("yyyy-MM-dd"));
    }

    @Test void sshToolValidationChecksIdentityButSkipsRemoteExecutable() throws Exception {
        Path identity = tempDir.resolve("id_ed25519"); Files.write(identity, new byte[]{1});
        ToolConfig remote = new ToolConfig("remote.query", "query", "remote", "Query", "Remote query",
                Arrays.asList("/remote/not-present", "arg"), Collections.<String>emptyList(), "txt",
                Collections.<String,ToolArgumentConfig>emptyMap(), new SshConfig("host.example", "att", 22, identity.toString()));
        PackageValidator validator = new PackageValidator(tempDir, new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,Collections.<String,ToolConfig>emptyMap(),null,null));
        java.lang.reflect.Method method = PackageValidator.class.getDeclaredMethod("validateToolExecutable", ToolConfig.class); method.setAccessible(true);
        assertDoesNotThrow(() -> { try { method.invoke(validator, remote); } catch (java.lang.reflect.InvocationTargetException e) { throw new RuntimeException(e.getCause()); } catch (Exception e) { throw new RuntimeException(e); } });
        ToolConfig missingKey = new ToolConfig("remote.query", "query", "remote", "Query", "Remote query",
                Arrays.asList("/remote/not-present"), Collections.<String>emptyList(), "txt",
                Collections.<String,ToolArgumentConfig>emptyMap(), new SshConfig("host.example", "att", 22, "missing-key"));
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(validator, missingKey));
    }

    @Test void allSelectionUsesConfiguredRecursiveTestcaseRoot() throws Exception {
        Path cases = tempDir.resolve("custom/cases/nested");
        Files.createDirectories(cases);
        Files.write(cases.resolve("sample.xlsx"), new byte[]{0});
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",1000,tempDir,
                java.nio.file.Paths.get("custom/cases"),Collections.<String,ToolConfig>emptyMap(),null,null,
                null,"","",null,null,1,"ignore","");
        PackageValidator validator = new PackageValidator(tempDir,config);
        java.lang.reflect.Method method = PackageValidator.class.getDeclaredMethod("suites",att.core.ExecutionOptions.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked") List<Path> suites = (List<Path>) method.invoke(validator,att.core.ExecutionOptions.parse(new String[]{"validate","--package"}));
        assertEquals(Collections.singletonList(cases.resolve("sample.xlsx")), suites);
    }
}
