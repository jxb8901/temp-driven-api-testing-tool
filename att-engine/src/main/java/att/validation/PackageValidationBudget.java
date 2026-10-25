package att.validation;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, no-follow preflight for installation-wide full Package validation. */
public final class PackageValidationBudget {
    public static final int MAX_ENTRIES_PER_PACKAGE=20000;
    public static final long MAX_BYTES_PER_PACKAGE=256L*1024L*1024L;
    public static final long MAX_INSTALLATION_WORK_UNITS=10000000L;
    private static final int MAX_DEPTH=64;

    private PackageValidationBudget() { }

    public static Result inspect(Path root) {
        return inspect(root,MAX_ENTRIES_PER_PACKAGE,MAX_BYTES_PER_PACKAGE,MAX_DEPTH);
    }

    static Result inspect(Path root,int maximumEntries,long maximumBytes,int maximumDepth) {
        if(root==null||Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!Files.isReadable(root))
            return new Result(false,0,0,"unavailable");
        final Path boundary=root.toAbsolutePath().normalize();
        final AtomicLong entries=new AtomicLong(),bytes=new AtomicLong();
        final String[] reason={null};
        try {
            Files.walkFileTree(boundary,new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attributes) {
                    if(!directory.toAbsolutePath().normalize().startsWith(boundary))return stop("outside-root");
                    if(entries.incrementAndGet()>maximumEntries)return stop("entry-limit");
                    if(boundary.relativize(directory.toAbsolutePath().normalize()).getNameCount()>=maximumDepth
                            && !directory.toAbsolutePath().normalize().equals(boundary))return stop("depth-limit");
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attributes) {
                    if(!file.toAbsolutePath().normalize().startsWith(boundary))return stop("outside-root");
                    if(entries.incrementAndGet()>maximumEntries)return stop("entry-limit");
                    if(attributes.isRegularFile()) {
                        long size=attributes.size();
                        if(size>maximumBytes-bytes.get())return stop("size-limit");
                        bytes.addAndGet(size);
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file,IOException failure) {
                    if(reason[0]==null)reason[0]="unreadable";
                    return FileVisitResult.SKIP_SUBTREE;
                }
                private FileVisitResult stop(String value) {reason[0]=value;return FileVisitResult.TERMINATE;}
            });
        } catch(Exception failure) {
            if(reason[0]==null)reason[0]="unreadable";
        }
        return new Result(reason[0]==null,entries.get(),bytes.get(),reason[0]);
    }

    public static final class Result {
        private final boolean complete;private final long entries,bytes;private final String reason;
        private Result(boolean complete,long entries,long bytes,String reason) {
            this.complete=complete;this.entries=entries;this.bytes=bytes;this.reason=reason;
        }
        public boolean complete(){return complete;}
        public long entries(){return entries;}
        public long bytes(){return bytes;}
        public String reason(){return reason;}
    }
}
