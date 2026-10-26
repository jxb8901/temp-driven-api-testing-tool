package att.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PackageValidationBudgetTest {
    @TempDir Path temp;

    @Test void stopsDiscoveryAtTheConfiguredEntryBudget() throws Exception {
        Files.write(temp.resolve("one.yaml"),new byte[]{1});
        Files.write(temp.resolve("two.yaml"),new byte[]{2});

        PackageValidationBudget.Result result=PackageValidationBudget.inspect(temp,2,1024,64);

        assertFalse(result.complete());
        assertEquals("entry-limit",result.reason());
        assertEquals(3,result.entries());
    }
}
