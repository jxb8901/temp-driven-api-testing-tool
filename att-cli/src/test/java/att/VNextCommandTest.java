package att;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VNextCommandTest {
    @TempDir Path packageRoot;

    @Test void diagnosticProjectionRedactsHostPathsAndCredentialValues() {
        Map<String,Object> diagnostic=new LinkedHashMap<String,Object>();
        diagnostic.put("detail","Unable to read /tmp/host/private/passwords.yaml; password=top-secret-value");
        Map<String,Object> source=new LinkedHashMap<String,Object>();
        source.put("file",packageRoot.resolve("testdata/uat.yaml").toString());
        diagnostic.put("source",source);

        @SuppressWarnings("unchecked") Map<String,Object> safe=(Map<String,Object>)VNextCommand.safeDiagnosticValue(diagnostic,packageRoot);
        String output=att.validation.JsonSupport.write(safe);

        assertFalse(output.contains("/tmp/host/private"));
        assertFalse(output.contains("top-secret-value"));
        assertTrue(output.contains("$EXTERNAL/passwords.yaml"));
        assertTrue(output.contains("testdata/uat.yaml"));
    }
}
