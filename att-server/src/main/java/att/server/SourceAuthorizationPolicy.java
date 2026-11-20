package att.server;

/** Server-side authorization rules for privileged source authoring. */
final class SourceAuthorizationPolicy {
    private SourceAuthorizationPolicy() { }

    static boolean canEditPackageContent(boolean authenticationRequired, boolean sourceEditingEnabled,
                                         String packageId, String loginId, boolean activeUser) {
        return authenticationRequired && sourceEditingEnabled && activeUser
                && packageId != null && !"default".equals(packageId)
                && loginId != null && !RequestIdentity.ANONYMOUS.equals(loginId)
                && canonicalOwnerIdentity(packageId) && canonicalOwnerIdentity(loginId)
                && packageId.equals(loginId);
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
