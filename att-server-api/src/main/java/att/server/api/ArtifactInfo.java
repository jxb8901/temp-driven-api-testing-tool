package att.server.api;

/** Logical job artifact metadata; path is relative to that job's output namespace. */
public final class ArtifactInfo {
    public String path;
    public long size;
    public ArtifactInfo() { }
    public ArtifactInfo(String path,long size){this.path=path;this.size=size;}
}
