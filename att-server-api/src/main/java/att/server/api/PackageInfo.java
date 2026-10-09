package att.server.api;

/** Logical package registry entry. Never contains a server filesystem path. */
public final class PackageInfo {
    public String packageId;
    public String name;
    public PackageInfo() { }
    public PackageInfo(String packageId, String name) { this.packageId=packageId; this.name=name; }
}
