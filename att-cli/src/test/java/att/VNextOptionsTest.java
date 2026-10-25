package att;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VNextOptionsTest {
    @Test void recognizesOnlyTheThreeTypedCommandsAndParsesFlagsAroundThem() {
        assertTrue(VNextOptions.isVNextRequest(new String[]{"--server","sit","-p","payments","run","suite","testcase/payment.xlsx"}));
        VNextOptions options=VNextOptions.parse(new String[]{"-p","payments","validate","config","-e","UAT","--format","json"});
        assertEquals("validate",options.command());assertEquals("config",options.targetKind());
        assertEquals("payments",options.packageId());assertEquals("UAT",options.environment());assertEquals("json",options.format());
        assertFalse(VNextOptions.isVNextRequest(new String[]{"snapshot","--all"}));
    }

    @Test void validateAllIsExclusiveAndCannotCarryClientPathsRemotely() {
        assertEquals("validate",VNextOptions.parse(new String[]{"--local","validate","--all","--config","config/config.yaml"}).command());
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"validate","--all","-p","payments"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"validate","--all","-e","SIT"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--server","sit","validate","config","--config","config/config.yaml"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--server","sit","run","case","id","--output-dir","out"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--server","sit","run","case","id"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--server","sit","load","scenario","smoke.yaml"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--server","sit","validate","package"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--server","sit","validate","case","PAYMENT"}));
        assertEquals("config",VNextOptions.parse(new String[]{"--server","sit","validate","config","-e","UAT"}).targetKind());
    }

    @Test void rejectsUnsafeOrAmbiguousExecutionCombinationsBeforeDispatch() {
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","cases","--all","--tag","smoke"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"load","flow","payment.submit","--users","2","--arrival-rate","5/s"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"load","scenario","load/smoke.yaml","--dry-run"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--local","--server","sit","validate","config"}));
    }
}
