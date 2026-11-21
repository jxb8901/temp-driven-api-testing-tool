package att.server;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceAuthorizationPolicyTest {
    @Test void packageOwnerRequiresAnActiveAuthenticatedUserAndExactRegisteredId() {
        assertTrue(SourceAuthorizationPolicy.canEditPackageContent(true,true,"alice","alice",true));
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,true,"alice","Alice",true));
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,true,"alice","alice ",true));
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,true,"alice","alice",false),
                "ADMIN and client supplied ownership claims do not count as an active USER role");
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,false,"alice","alice",true));
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(false,true,"alice","alice",true));
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,true,"default","default",true));
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,true,"alice",RequestIdentity.ANONYMOUS,true));
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,true,"Alice","Alice",true),
                "Trusted principals and registered owner IDs must use the local-auth canonical form");
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,true,"alice","Ａlice",true),
                "Trusted identity normalization must not create collisions with local-auth login IDs");
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,true,"shared","carol",true,List.of("alice","bob")));
    }

    @Test void explicitlyAssignedPackageEditorsReceiveOnlyThePackageSourceCapability() {
        assertTrue(SourceAuthorizationPolicy.canEditPackageContent(true,true,"fpp-common","alice",true,List.of("alice","bob")));
        assertTrue(SourceAuthorizationPolicy.canEditPackageContent(true,true,"fpp-common","bob",true,List.of("alice","bob")));
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,true,"fpp-common","carol",true,List.of("alice","bob")));
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,true,"fpp-common","bob",false,List.of("alice","bob")),
                "A trusted grant does not override USER activity or role checks");
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,true,"fpp-common","ADMIN",true,List.of("ADMIN")),
                "Trusted grants must use canonical IDs");
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,true,"default","bob",true,List.of("bob")),
                "The built-in default Package remains non-editable");
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(true,false,"fpp-common","bob",true,List.of("bob")));
        assertFalse(SourceAuthorizationPolicy.canEditPackageContent(false,true,"fpp-common","bob",true,List.of("bob")));
        assertTrue(SourceAuthorizationPolicy.canonicalOwnerIdentity("alice"));
        assertFalse(SourceAuthorizationPolicy.canonicalOwnerIdentity("Alice"));
        assertFalse(SourceAuthorizationPolicy.canonicalOwnerIdentity("alice "));
        assertFalse(SourceAuthorizationPolicy.canonicalOwnerIdentity("Ａlice"));
    }

    @Test void centralConfigurationRequiresTheIndependentAuthenticatedSystemCapability() {
        assertTrue(SourceAuthorizationPolicy.canWriteSystemSettings(true,true));
        assertFalse(SourceAuthorizationPolicy.canWriteSystemSettings(true,false));
        assertFalse(SourceAuthorizationPolicy.canWriteSystemSettings(false,true));
    }
}
