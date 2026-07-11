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
}
