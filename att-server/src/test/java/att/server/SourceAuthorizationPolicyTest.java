package att.server;

import org.junit.jupiter.api.Test;

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
    }

    @Test void centralConfigurationRequiresTheIndependentAuthenticatedSystemCapability() {
        assertTrue(SourceAuthorizationPolicy.canWriteSystemSettings(true,true));
        assertFalse(SourceAuthorizationPolicy.canWriteSystemSettings(true,false));
        assertFalse(SourceAuthorizationPolicy.canWriteSystemSettings(false,true));
    }
}
