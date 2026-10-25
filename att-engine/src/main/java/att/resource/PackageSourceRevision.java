package att.resource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded conservative source fingerprint for static candidate validation and publication. */
final class PackageSourceRevision {
    private PackageSourceRevision() { }
    static String capture(Path packageRoot, Path config) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long[] bytes={0};int[] files={0};
        tree(digest,packageRoot.toRealPath(),true,bytes,files);
        tree(digest,config.toRealPath().getParent(),false,bytes,files);
        digest.update(att.validation.SchemaFiles.registryDigest().getBytes(StandardCharsets.UTF_8));
        StringBuilder text=new StringBuilder();for(byte b:digest.digest())text.append(String.format("%02x",b&255));return text.toString();
    }
    private static void tree(MessageDigest digest,Path root,boolean business,long[] bytes,int[] count) throws Exception {
        List<Path> paths=new ArrayList<Path>();
        Files.walkFileTree(root,new java.nio.file.SimpleFileVisitor<Path>() {
            private void count() throws java.io.IOException {
                if(++count[0]>10000)throw new java.io.IOException("Package source revision exceeds its file limit");
            }
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path directory,java.nio.file.attribute.BasicFileAttributes attributes) throws java.io.IOException {
                Path relative=root.relativize(directory);
                if(business&&!directory.equals(root)) {
                    String name=directory.getFileName().toString();
                    if(java.util.Arrays.asList("output","target","dist",".git","node_modules").contains(name)||name.startsWith(".att-"))
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                count();
                if(relative.getNameCount()>16)throw new java.io.IOException("Package source revision exceeds its depth limit");
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(Path path,java.nio.file.attribute.BasicFileAttributes attributes) throws java.io.IOException {
                count();
                if(attributes.isSymbolicLink())throw new java.io.IOException("Package source revision contains an unsafe symlink");
                if(attributes.isRegularFile()&&!(business&&root.relativize(path).toString().equals("package.yaml")))paths.add(path);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        Collections.sort(paths);byte[] buffer=new byte[8192];
        digest.update((business?"package":"global").getBytes(StandardCharsets.UTF_8));
        for(Path path:paths) {
            digest.update(root.relativize(path).toString().replace('\\','/').getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
            try(InputStream input=Files.newInputStream(path)) {
                int size;while((size=input.read(buffer))!=-1){bytes[0]+=size;if(bytes[0]>268435456L)throw new IllegalArgumentException("Package source revision exceeds its byte limit");digest.update(buffer,0,size);}
            }
            digest.update((byte)255);
        }
    }
}
