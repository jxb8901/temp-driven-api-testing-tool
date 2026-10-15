package att.server;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OriginPolicyTest {
    @Test void comparesExactCanonicalOriginsWithoutReadingForwardedHeaders() {
        OriginPolicy policy=new OriginPolicy(List.of("https://GATEWAY.example:443/","https://gateway.example:8443"));
        assertTrue(policy.allows(request("https://gateway.example")));
        assertTrue(policy.allows(request("https://GATEWAY.example:443")));
        assertTrue(policy.allows(request("https://gateway.example:8443")));
        assertTrue(policy.allows(request("https://internal.example")));
        assertTrue(policy.allows(request((String)null)),"Missing Origin preserves CLI compatibility");
        for(String value:List.of("https://gateway.example.evil.test","http://gateway.example","https://gateway.example:8444",
                "https://gateway.example/","https://gateway.example/path","https://user@gateway.example","https://gateway.example?query",
                "https://gateway.example#fragment","https://gateway.example:","https://gateway.example:0","https://gateway.example:65536",
                "null","*","https://gateway.example https://internal.example"," https://gateway.example"))
            assertFalse(policy.allows(request(value)),value);
    }

    @Test void rejectsMalformedUnsafeAndEquivalentDuplicateConfiguration() {
        for(String value:List.of("null","*","https://*.example","http://public.example","https://gateway.example/path",
                "https://user@gateway.example","https://gateway.example?q","https://gateway.example#f",
                "https://gateway.example:bad","https://gateway.example:","https://gateway.example:0","https://gateway.example:65536"))
            assertThrows(IllegalArgumentException.class,()->new OriginPolicy(List.of(value)),value);
        assertThrows(IllegalArgumentException.class,()->new OriginPolicy(List.of("https://gateway.example","https://GATEWAY.example:443/")));
        assertDoesNotThrow(()->new OriginPolicy(List.of("http://localhost","http://127.0.0.1:8080","http://[::1]:8080")));
        assertThrows(IllegalArgumentException.class,()->new OriginPolicy(Collections.nCopies(101,"https://gateway.example")));
    }

    @Test void emptyAllowlistKeepsSameOriginAndMissingOriginBehavior() {
        OriginPolicy policy=new OriginPolicy(List.of());
        assertTrue(policy.allows(request((String)null)));
        assertTrue(policy.allows(request("https://internal.example:443")));
        assertFalse(policy.allows(request("https://gateway.example")));
        assertFalse(policy.allows(request("https://internal.example","https://internal.example")),"Multiple Origin headers fail closed");
    }

    private static HttpServletRequest request(String... origins) {
        List<String> values=origins.length==1&&origins[0]==null?List.of():java.util.Arrays.asList(origins);
        return (HttpServletRequest)Proxy.newProxyInstance(OriginPolicyTest.class.getClassLoader(),new Class<?>[]{HttpServletRequest.class},(proxy,method,args)->{
            return switch(method.getName()) {
                case "getHeaders" -> Collections.enumeration(values);
                case "getScheme" -> "https";
                case "getServerName" -> "internal.example";
                case "getServerPort" -> 443;
                default -> throw new AssertionError("Unexpected request access: "+method.getName());
            };
        });
    }
}
