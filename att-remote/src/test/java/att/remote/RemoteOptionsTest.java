package att.remote;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteOptionsTest {
    @Test void parsesRemoteOnlyOptionsWithoutChangingLocalCliModel() {
        RemoteOptions o=RemoteOptions.parse(new String[]{"--server","sit","run","payments","--suite","smoke.xlsx","--format","json"});
        assertEquals("sit",o.server);assertEquals("json",o.format);assertEquals("run",o.command.get(0));assertEquals("--suite",o.command.get(2));
    }
    @Test void rejectsInvalidFormatAndMissingProfile() {
        assertThrows(IllegalArgumentException.class,()->RemoteOptions.parse(new String[]{"--format","xml","jobs"}));
        assertThrows(IllegalArgumentException.class,()->RemoteOptions.parse(new String[]{"--server"}));
    }
}
