package att.resource;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackageSourceRevisionTest {
    @TempDir Path root;
    private Path write(String name,String text) throws Exception {
        Path file=root.resolve(name);Files.createDirectories(file.getParent());Files.write(file,text.getBytes("UTF-8"));return file;
    }
    @Test void tracksPackageGlobalAndSchemaDependenciesWhileIgnoringLiveManifestAndOutput() throws Exception {
        Path config=write("central/config.yaml","schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments: {SIT: {}}\n");
        Path pkg=Files.createDirectories(root.resolve("package"));
        write("package/templates/example.yaml","value: first\n");
        String first=PackageSourceRevision.capture(pkg,config);
        write("package/package.yaml","schemaVersion: att-package/v1\n");write("package/output/result.txt","volatile");
        assertEquals(first,PackageSourceRevision.capture(pkg,config));
        write("package/templates/example.yaml","value: second\n");
        String businessChanged=PackageSourceRevision.capture(pkg,config);assertNotEquals(first,businessChanged);
        write("central/tools.yaml","tools: {}\n");
        assertNotEquals(businessChanged,PackageSourceRevision.capture(pkg,config));
    }
    @Test void skipsExcludedSubtreesWithoutWalkingAndRejectsUnsafeOrDeepDependencies() throws Exception {
        Path config=write("central/config.yaml","schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments: {SIT: {}}\n");
        Path pkg=Files.createDirectories(root.resolve("package"));
        write("package/node_modules/"+String.join("/",java.util.Collections.nCopies(20,"nested"))+"/file","ignored");
        assertNotNull(PackageSourceRevision.capture(pkg,config));
        Path outside=write("outside","secret");Files.createSymbolicLink(pkg.resolve("link"),outside);
        assertThrows(java.io.IOException.class,()->PackageSourceRevision.capture(pkg,config));Files.delete(pkg.resolve("link"));
        write("package/"+String.join("/",java.util.Collections.nCopies(17,"nested"))+"/file","too deep");
        assertThrows(java.io.IOException.class,()->PackageSourceRevision.capture(pkg,config));
    }
    @Test void candidateValidationRevisionChangesWhenReferencedSourcesChange() throws Exception {
        Path config=write("central/config.yaml","schemaVersion: att-config/v2.13\nenvironment: SIT\nenvironments: {SIT: {}}\n");
        Path pkg=Files.createDirectories(root.resolve("package"));
        PackageManifestInspector inspector=new PackageManifestInspector(pkg,config);
        String source="schemaVersion: att-package/v1\n";
        Object first=inspector.validateCandidate(source,null).get("validationRevision");
        write("package/templates/example.yaml","value: revised\n");
        assertNotEquals(first,inspector.validateCandidate(source,null).get("validationRevision"));
        assertFalse(Files.exists(pkg.resolve("package.yaml")));
    }
}
