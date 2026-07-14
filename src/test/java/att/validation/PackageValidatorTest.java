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
    @Test void enforcesTypeSpecificActionContracts() throws Exception {
        FrameworkConfig config = new FrameworkConfig(tempDir,tempDir,tempDir,"SIT",10,tempDir,Collections.<String,ToolConfig>emptyMap(),null,null);
        PackageValidator validator = new PackageValidator(tempDir,config);
        java.lang.reflect.Method method = PackageValidator.class.getDeclaredMethod("validateTemplate", StageTemplate.class, FrameworkConfig.class); method.setAccessible(true);
        Map<String,Object> invalidRender = new LinkedHashMap<String,Object>(); invalidRender.put("type","render"); invalidRender.put("payload","p.txt"); invalidRender.put("saveAs","out.txt"); invalidRender.put("call","#{x()}");
        Files.write(tempDir.resolve("p.txt"),new byte[]{1});
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(validator,new StageTemplate("T",tempDir,Collections.singletonList(new TemplateAction("render",invalidRender))),config));
        Map<String,Object> builtInTool = new LinkedHashMap<String,Object>(); builtInTool.put("type","tool"); builtInTool.put("call","#{upper(value='x')}");
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(validator,new StageTemplate("T",tempDir,Collections.singletonList(new TemplateAction("tool",builtInTool))),config));
    }
    @Test void referencedToolExecutableCannotEscapePackage() throws Exception {
        Path project=tempDir.resolve("project"), outside=tempDir.resolve("outside.sh"); Files.createDirectories(project); Files.write(outside, "#!/bin/sh\n".getBytes("UTF-8")); outside.toFile().setExecutable(true);
        ToolConfig tool=new ToolConfig("outside","Outside","test","../outside.sh","txt",Collections.<String,ToolArgumentConfig>emptyMap());
        PackageValidator validator=new PackageValidator(project,new FrameworkConfig(project,project,project,"SIT",1000,project,Collections.<String,ToolConfig>emptyMap(),null,null));
        java.lang.reflect.Method method=PackageValidator.class.getDeclaredMethod("validateToolExecutable",ToolConfig.class); method.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class, () -> method.invoke(validator,tool));
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
