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

    @Test void failsClosedForProtectedRecordsEmbeddedInScalarContent() throws Exception {
        Path root=Files.createDirectories(temp.resolve("embedded-scalar-sources"));
        Path block=root.resolve("block.yaml");
        Files.write(block,("schemaVersion: att-package/v1\npayload: |\n"
                +"  classification: secret\n  value: block-classified-canary\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,block,"block.yaml",4096));

        Path folded=root.resolve("folded.yaml");
        Files.write(folded,("schemaVersion: att-package/v1\npayload: >\n"
                +"  classification: secret\n  value: folded-classified-canary\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,folded,"folded.yaml",4096));

        Path escaped=root.resolve("escaped.yaml");
        Files.write(escaped,("schemaVersion: att-package/v1\npayload: \"{\\\"classification\\\":\\\"secret\\\","
                +"\\\"value\\\":\\\"escaped-classified-canary\\\"}\"\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,escaped,"escaped.yaml",4096));

        Path xmlText=root.resolve("xml-text.yaml");
        Files.write(xmlText,("schemaVersion: att-package/v1\npayload: |\n"
                +"  <payload>{&quot;classification&quot;:&quot;secret&quot;,&quot;value&quot;:&quot;xml-text-canary&quot;}</payload>\n")
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,xmlText,"xml-text.yaml",4096));

        Path xmlAttribute=root.resolve("xml-attribute.yaml");
        Files.write(xmlAttribute,("schemaVersion: att-package/v1\npayload: |\n"
                +"  <payload value=\"{&quot;classification&quot;:&quot;secret&quot;,&quot;value&quot;:&quot;xml-attribute-canary&quot;}\"/>\n")
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,xmlAttribute,"xml-attribute.yaml",4096));

        Path xmlCdata=root.resolve("xml-cdata.yaml");
        Files.write(xmlCdata,("schemaVersion: att-package/v1\npayload: |\n"
                +"  <payload><![CDATA[{\"classification\":\"secret\",\"value\":\"xml-cdata-canary\"}]]></payload>\n")
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,xmlCdata,"xml-cdata.yaml",4096));

        StringBuilder deepXml=new StringBuilder();
        for(int index=0;index<66;index++)deepXml.append("<node>");
        deepXml.append("safe");
        for(int index=0;index<66;index++)deepXml.append("</node>");
        Path deepXmlSource=root.resolve("deep-xml.yaml");
        Files.write(deepXmlSource,("schemaVersion: att-package/v1\npayload: |\n  "+deepXml+"\n")
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,deepXmlSource,"deep-xml.yaml",8192),
                "Embedded XML nesting beyond the inspection limit must fail closed");

        Path configured=root.resolve("configured-block.yaml");
        Files.write(configured,("schemaVersion: att-package/v1\npayload: |\n"
                +"  records:\n    opaqueConfigValue: configured-block-canary\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,configured,"configured-block.yaml",4096,
                        Collections.<String>emptyList(),Collections.singletonList("^opaqueConfigValue$")));

        Path configuredFolded=root.resolve("configured-folded.yaml");
        Files.write(configuredFolded,("schemaVersion: att-package/v1\npayload: >\n"
                +"  records:\n    opaqueConfigValue: configured-folded-canary\n").getBytes(StandardCharsets.UTF_8));
        assertThrows(PackageResourceInspector.ResourceNotFoundException.class,
                ()->AuthoringSourceSupport.read(root,configuredFolded,"configured-folded.yaml",4096,
                        Collections.<String>emptyList(),Collections.singletonList("^opaqueConfigValue$")));

        Path sql=root.resolve("safe-query.sql");
        Files.write(sql,"SELECT * FROM payments WHERE sequence = 7;\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("SELECT * FROM payments WHERE sequence = 7;\n",
                AuthoringSourceSupport.read(root,sql,"safe-query.sql",4096).get("text"));
    }
}
