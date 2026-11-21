package att.server;

import java.util.Collection;
import java.util.List;

/** Server-side authorization rules for privileged source authoring. */
final class SourceAuthorizationPolicy {
    private SourceAuthorizationPolicy() { }

    static boolean canEditPackageContent(boolean authenticationRequired, boolean sourceEditingEnabled,
                                         String packageId, String loginId, boolean activeUser) {
        return canEditPackageContent(authenticationRequired,sourceEditingEnabled,packageId,loginId,activeUser,List.of());
    }

    static boolean canEditPackageContent(boolean authenticationRequired, boolean sourceEditingEnabled,
                                         String packageId, String loginId, boolean activeUser,
                                         Collection<String> trustedEditors) {
        return authenticationRequired && sourceEditingEnabled && activeUser
                && packageId != null && !"default".equals(packageId)
                && loginId != null && !RequestIdentity.ANONYMOUS.equals(loginId)
                && canonicalOwnerIdentity(loginId)
                && ((canonicalOwnerIdentity(packageId)&&packageId.equals(loginId))
                    || trustedEditors!=null&&trustedEditors.contains(loginId));
    }

    /** Local IDs are NFKC/lowercase ASCII; trusted identities must already use that canonical form. */
    static boolean canonicalOwnerIdentity(String identity) {
        if(identity==null)return false;
        try { return identity.equals(AuthService.normalize(identity)); }
        catch(IllegalArgumentException invalid) { return false; }
    }

    static boolean canWriteSystemSettings(boolean authenticationRequired, boolean systemSettingsWrite) {
        return authenticationRequired && systemSettingsWrite;
    }
}
