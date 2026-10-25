package att.resource;

import att.config.PackageConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackageSourceRevisionTest {
    @TempDir Path root;
    private static final String SOURCE = "schemaVersion: att-package/v1\ntestdata: {descriptors: [testdata/base.yaml]}\n";
    private Path write(String name,String text) throws Exception {
        Path file=root.resolve(name);Files.createDirectories(file.getParent());Files.write(file,text.getBytes("UTF-8"));return file;
    }
    private String capture(Path pkg,Path config) throws Exception {
        return PackageSourceRevision.capture(pkg,config,PackageConfig.parse(pkg,SOURCE));
    }
    @Test void tracksDeclaredDependenciesAndRootBindingsWithoutWalkingUnrelatedFiles() throws Exception {
        Path config=write("central/config/config.yaml","schemaVersion: att-config/v2.13\nenvironment: SIT\n"
                +"environments: {SIT: {}, UAT: {httphelpers: [config/uat.yaml]}}\n");
        Path pkg=Files.createDirectories(root.resolve("package"));
        write("package/testdata/base.yaml","value: first\n");
        String first=capture(pkg,config);
        write("package/package.yaml","schemaVersion: att-package/v1\n");
        write("package/output/result.txt","volatile");write("package/archive/fixture.yaml","unrelated");
        write("central/config/unreferenced.yaml","unrelated shared file");
        assertEquals(first,capture(pkg,config));
        write("package/testdata/base.yaml","value: second\n");
        String changed=capture(pkg,config);assertNotEquals(first,changed);
        write("central/config/uat.yaml","helpers: {}\n");
        String sharedChanged=capture(pkg,config);assertNotEquals(changed,sharedChanged);
        Files.createDirectories(pkg.resolve("templates"));
        assertNotEquals(sharedChanged,capture(pkg,config));
        String rootsChanged=capture(pkg,config);
        write("package/templates/example.yaml","not read by Manifest validation\n");
        assertEquals(rootsChanged,capture(pkg,config));
        write("central/config/config.yaml","schemaVersion: att-config/v2.13\nenvironment: UAT\nenvironments: {SIT: {}, UAT: {}}\n");
        assertNotEquals(rootsChanged,capture(pkg,config));
    }
    @Test void rejectsUnsafeDeclaredDependenciesButIgnoresUnrelatedSymlinksAndDeepArchives() throws Exception {
        Path config=write("central/config.yaml","schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments: {SIT: {}}\n");
        Path pkg=Files.createDirectories(root.resolve("package"));
        write("package/archive/"+String.join("/",java.util.Collections.nCopies(20,"nested"))+"/file","ignored");
        Path outside=write("outside","secret");Files.createSymbolicLink(pkg.resolve("unrelated-link"),outside);
        assertNotNull(capture(pkg,config));
        Files.createDirectories(pkg.resolve("testdata"));Files.createSymbolicLink(pkg.resolve("testdata/base.yaml"),outside);
        Exception error=assertThrows(Exception.class,()->capture(pkg,config));
        assertFalse(error.getMessage().contains(outside.toString()));
    }
    @Test void candidateValidationRevisionChangesForDeclaredDescriptorsButNotUnrelatedBusinessFiles() throws Exception {
        Path config=write("central/config.yaml","schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments: {SIT: {}}\n");
        Path pkg=Files.createDirectories(root.resolve("package"));
        Path descriptor=write("package/testdata/base.yaml","schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: first}]\n");
        PackageManifestInspector inspector=new PackageManifestInspector(pkg,config);
        Object first=inspector.validateCandidate(SOURCE,null).get("validationRevision");
        write("package/templates/example.yaml","value: unrelated\n");
        // Creation of the conventional root changes its binding once; its contents do not.
        Object withRoot=inspector.validateCandidate(SOURCE,null).get("validationRevision");
        write("package/templates/example.yaml","value: revised\n");
        assertEquals(withRoot,inspector.validateCandidate(SOURCE,null).get("validationRevision"));
        Files.write(descriptor,"schemaVersion: att-testdata/v1.0\nid: accounts\nrecords: [{id: second}]\n".getBytes("UTF-8"));
        assertNotEquals(withRoot,inspector.validateCandidate(SOURCE,null).get("validationRevision"));
        assertNotNull(first);assertFalse(Files.exists(pkg.resolve("package.yaml")));
    }
}
