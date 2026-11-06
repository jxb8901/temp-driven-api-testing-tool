package att.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class AuthoringSourceSupportTest {
    @TempDir Path temp;

    @Test void rejectsOversizedMalformedUtf8ProtectedAndSymlinkedSources() throws Exception {
        Path root=Files.createDirectories(temp.resolve("package"));
        Path source=root.resolve("source.yaml");

        Files.write(source,"12345".getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResponseTooLargeException.class,
                ()->AuthoringSourceSupport.read(root,source,"source.yaml",4));

        Files.write(source,new byte[]{(byte)0xc3,(byte)0x28});
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,source,"source.yaml",16));

        Files.write(source,"apiKey: unique-token-123\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,source,"source.yaml",128));

        Path protectedStore=root.resolve("credentials.yaml");
        Files.write(protectedStore,"private: arbitrary opaque value\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,protectedStore,"credentials.yaml",128));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.absent("credentials.yaml"),
                "An absent protected path must not disclose existence through a different response");

        Path target=temp.resolve("outside.yaml");
        Files.write(target,"schemaVersion: att-template/v1\n".getBytes(StandardCharsets.UTF_8));
        Path link=root.resolve("linked.yaml");
        try {
            Files.createSymbolicLink(link,target);
            assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                    ()->AuthoringSourceSupport.read(root,link,"linked.yaml",128));
        } catch(UnsupportedOperationException|java.nio.file.FileSystemException unsupported) {
            org.junit.jupiter.api.Assumptions.abort("Symlink creation is unavailable on this filesystem");
        }
    }

    @Test void absentSourceHasStableEmptyByteEtag() throws Exception {
        java.util.Map<String,Object> result=AuthoringSourceSupport.absent("package.yaml");
        assertEquals(Boolean.FALSE,result.get("present"));assertEquals("",result.get("text"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                result.get("revision"));
        assertEquals("\"sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"",
                result.get("sourceEtag"));
    }

    @Test void failsClosedForClassifiedRecordsAndConfiguredFieldsAcrossFormats() throws Exception {
        Path root=Files.createDirectories(temp.resolve("classified-sources"));
        Path classified=root.resolve("classified.yaml");
        Files.write(classified,"classification: secret\nvalue: opaque-classified-canary\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,classified,"classified.yaml",4096));

        Path classifiedJson=root.resolve("classified.json");
        Files.write(classifiedJson,"{\"records\":[{\"classification\":\"secret\",\"value\":\"json-classified-canary\"}]}"
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,classifiedJson,"classified.json",4096));

        Path classifiedXml=root.resolve("classified.xml");
        Files.write(classifiedXml,"<record classification=\"secret\"><value>xml-classified-canary</value></record>"
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,classifiedXml,"classified.xml",4096));

        Path classifiedCsv=root.resolve("classified.csv");
        Files.write(classifiedCsv,"classification,value\nsecret,csv-classified-canary\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,classifiedCsv,"classified.csv",4096));

        Path pathProtected=root.resolve("path-protected.yaml");
        Files.write(pathProtected,"records: [{payload: configured-path-canary}]\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,pathProtected,"path-protected.yaml",4096,
                        Collections.singletonList("records[0].payload"),Collections.<String>emptyList()));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,pathProtected,"path-protected.yaml",4096,
                        Collections.singletonList("testdata.accounts.records[*].payload"),Collections.<String>emptyList()));

        Path json=root.resolve("field-protected.json");
        Files.write(json,"{\"customPayload\":\"configured-pattern-canary\"}".getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,json,"field-protected.json",4096,
                        Collections.<String>emptyList(),Collections.singletonList("custom.*")));

        Path xml=root.resolve("field-protected.xml");
        Files.write(xml,"<record><customPayload>xml-pattern-canary</customPayload></record>".getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,xml,"field-protected.xml",4096,
                        Collections.<String>emptyList(),Collections.singletonList("custom.*")));

        Path csv=root.resolve("field-protected.csv");
        Files.write(csv,"id,customPayload\n1,csv-pattern-canary\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,csv,"field-protected.csv",4096,
                        Collections.<String>emptyList(),Collections.singletonList("custom.*")));
    }
}
