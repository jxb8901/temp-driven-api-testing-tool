package att.security;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SecretProtectionTest {
    @Test void protectsEveryTextFormatAndRegisteredValuesIdempotently() {
        for (String source : Arrays.asList("password=canary", "password=canary; after=ordinary", "password=canary\nmessage=ordinary", "DB_PASSWORD=canary", "refresh_token=canary", "password: {unknown: {value: canary}}", "password:\n  unknown: canary", "{\"token\":\"canary\"}",
                "Authorization: Bearer canary", "Cookie: session=canary", "<s:password>canary</s:password>",
                "https://user:canary@host/path", "-----BEGIN PRIVATE KEY-----\ncanary\n-----END PRIVATE KEY-----",
                "value: !secret canary", "name,password\r\nalice,\"canary\"\r\n")) {
            String safe=SecretProtection.text(source);
            assertFalse(safe.contains("canary"), source);
            assertEquals(safe,SecretProtection.text(safe),source);
        }
        assertEquals("x [REDACTED_SECRET]",SecretProtection.text("x registered-value",Arrays.asList("registered-value")));
        String protectedMarker=SecretProtection.text("x SECRET",Arrays.asList("SECRET","REDACTED"));
        assertEquals("x [REDACTED_SECRET]",protectedMarker);
        assertEquals(protectedMarker,SecretProtection.text(protectedMarker,Arrays.asList("SECRET","REDACTED")));
    }
    @Test void ordinaryFieldsRemainVisibleAndUnknownSensitiveStructuresFailClosed() {
        Map<String,Object> map=new LinkedHashMap<String,Object>();
        map.put("host","host.example");map.put("username","alice");map.put("clientId","application");
        map.put("headers",Collections.singletonMap("Content-Type","application/xml"));
        map.put("password",Collections.singletonMap("unknown",new Object()));
        map.put("cycle",map);map.put("unknown",new Object());
        String safe=SecretProtection.value(map).toString();
        assertTrue(safe.contains("host.example"));assertTrue(safe.contains("alice"));assertTrue(safe.contains("application/xml"));
        assertFalse(safe.contains("unknown="+new Object().toString()));
        assertEquals(SecretProtection.REDACTED,((Map<?,?>)SecretProtection.value(map)).get("password"));
        assertEquals(SecretProtection.REDACTED,((Map<?,?>)SecretProtection.value(map)).get("unknown"));
        assertEquals(SecretProtection.REDACTED,SecretProtection.value(Collections.singletonMap("secret",true)));
    }
    @Test void preservesAuthoredDiagnosticContent() {
        String source="payload='&{./Pacs008.xml}' ${#{./nested}} ../relative https://host/path user@host:/remote <a></a>";
        assertEquals(source,SecretProtection.text(source));
        assertEquals("unexpected token: operator '/'", SecretProtection.text("unexpected token: operator '/'"));
        assertEquals("token: [REDACTED_SECRET]", SecretProtection.text("token: canary"));
        assertFalse(SecretProtection.text("unexpected token: operator '/'\npassword: canary").contains("canary"));
    }
}
