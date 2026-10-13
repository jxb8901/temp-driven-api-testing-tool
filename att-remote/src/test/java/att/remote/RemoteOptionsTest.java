package att.remote;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RemoteOptionsTest {
    @Test void noAuthIsExplicitAndDoesNotConsumeTheCommand(){RemoteOptions options=RemoteOptions.parse(new String[]{"--no-auth","--server","local","ping"});assertTrue(options.noAuth);assertEquals("local",options.server);assertEquals("ping",options.command.get(0));}
    @Test void serverAcceptsAnHttpUrlWithoutConsumingTheCommand(){RemoteOptions options=RemoteOptions.parse(new String[]{"--server","https://host.example:8443/att","packages"});assertEquals("https://host.example:8443/att",options.server);assertEquals("packages",options.command.get(0));}
    @Test void serverRejectsAnEmptyOptionValue(){assertThrows(IllegalArgumentException.class,()->RemoteOptions.parse(new String[]{"--server","--no-auth","ping"}));}
}
