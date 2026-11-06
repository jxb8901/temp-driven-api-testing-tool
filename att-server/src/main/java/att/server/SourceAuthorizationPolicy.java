package att.server;

/** Server-side authorization rules for privileged source authoring. */
final class SourceAuthorizationPolicy {
    private SourceAuthorizationPolicy() { }

    static boolean canEditPackageContent(boolean authenticationRequired, boolean sourceEditingEnabled,
                                         String packageId, String loginId, boolean activeUser) {
        return authenticationRequired && sourceEditingEnabled && activeUser
                && packageId != null && !"default".equals(packageId)
                && loginId != null && !RequestIdentity.ANONYMOUS.equals(loginId)
                && packageId.equals(loginId);
    }

    static boolean canWriteSystemSettings(boolean authenticationRequired, boolean systemSettingsWrite) {
        return authenticationRequired && systemSettingsWrite;
    }
}
