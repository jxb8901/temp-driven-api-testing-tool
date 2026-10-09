package att.remote;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteOptionsTest {
    @Test void noAuthIsExplicitAndDoesNotConsumeTheCommand(){RemoteOptions options=RemoteOptions.parse(new String[]{"--no-auth","--server","local","ping"});assertTrue(options.noAuth);assertEquals("local",options.server);assertEquals("ping",options.command.get(0));}
}
