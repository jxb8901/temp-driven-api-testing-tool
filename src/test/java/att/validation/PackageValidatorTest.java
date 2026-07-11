/* Author: Jeffrey + ChatGPT */
package att.validation;

import att.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
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
}
