package att.server;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class RequestIdentityTest {
    @Test void anonymousNamesAreNormalizedAndOnlyChangePersistedAttribution() throws Exception {
        JsonNode request=ServerRuntime.JSON.readTree("{\"packageId\":\"p\",\"principal\":\"  Bób  \"}");
        RequestIdentity identity=RequestIdentity.resolve(false,"container-user",request);
        assertEquals("anonymous",identity.authorizationPrincipal());
        assertEquals("Bób(anonym)",identity.persistedPrincipal());
        assertEquals("{\"packageId\":\"p\"}",RequestIdentity.withoutSubmittedPrincipal(request).toString());
        assertEquals("  Bób  ",request.path("principal").asText(),"Removing the attribution input must not mutate the original request");
    }

    @Test void omittedAnonymousNameUsesTheStableSharedIdentity() {
        RequestIdentity identity=RequestIdentity.resolve(false,null,null);
        assertEquals("anonymous",identity.authorizationPrincipal());
        assertEquals("anonymous",identity.persistedPrincipal());
    }

    @Test void blankAnonymousNamesUseTheStableSharedIdentity() throws Exception {
        for (String label : new String[]{"", " \t\n "}) {
            JsonNode request=ServerRuntime.JSON.readTree("{\"principal\":"+ServerRuntime.JSON.writeValueAsString(label)+"}");
            RequestIdentity identity=RequestIdentity.resolve(false,null,request);
            assertEquals("anonymous",identity.authorizationPrincipal());
            assertEquals("anonymous",identity.persistedPrincipal());
        }
    }

    @Test void authenticatedIdentityCannotBeOverriddenBySubmittedName() throws Exception {
        JsonNode request=ServerRuntime.JSON.readTree("{\"principal\":\"Alice(anonym)\"}");
        RequestIdentity identity=RequestIdentity.resolve(true,"alice",request);
        assertEquals("alice",identity.authorizationPrincipal());
        assertEquals("alice",identity.persistedPrincipal());
    }

    @Test void invalidAndAlreadySuffixedLabelsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->RequestIdentity.formatAnonymousPrincipal(" "));
        assertThrows(IllegalArgumentException.class,()->RequestIdentity.formatAnonymousPrincipal("Alice(anonym)"));
        assertThrows(IllegalArgumentException.class,()->RequestIdentity.formatAnonymousPrincipal("Alice\nAdmin"));
        assertThrows(IllegalArgumentException.class,()->RequestIdentity.formatAnonymousPrincipal("A".repeat(121)));
    }
}
