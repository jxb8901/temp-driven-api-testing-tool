package att.api;

import att.config.FrameworkConfigLoader;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

/** Original #284 reproduction across the real Engine entry points. */
class PackageManifestExecutionTest {
    @TempDir Path temp;

    @Test void identicalPackageFlowsRunDebugAndLoadWithoutActivatingUnrelatedGlobalOrManifestTestdata() throws Exception {
        Path config=write(temp.resolve("runtime/config/config.yaml"),"schemaVersion: att-config/v2.13\nenvironment: SIT\n"
                +"testdata: [testdata/missing-global.yaml]\nenvironments:\n  SIT: {}\n  UAT: {}\n");
        DefaultAttService service=new DefaultAttService();
        for(String name:new String[]{"default","stresstest"}) {
            Path root=Files.createDirectories(temp.resolve("packages").resolve(name));
            att.TestSchemas.install(root);
            if("stresstest".equals(name)) write(root.resolve("package.yaml"),"schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n"
                    +"testdata: {descriptors: [testdata/missing-unused.yaml]}\n");
            write(root.resolve("templates/SIMPLE/template.yaml"),"schemaVersion: att-template/v3.4\nname: SIMPLE\ndescription: Package isolation reproduction\nactions:\n"
                    +"  invoke: {type: flow, use: sample.shared.v1}\n");
            write(root.resolve("templates/flows/flow.yaml"),"schemaVersion: att-flow/v3.4\nid: sample.shared.v1\nname: Shared ID\ndescription: Selected Package only\nactions:\n"
                    +"  show: {type: log, message: 'package="+name+"'}\n");
            write(root.resolve("templates/flows/debug.yaml"),"schemaVersion: att-debug/v1.2\n");
            write(root.resolve("load/scenario.yaml"),"schemaVersion: att-load/v1.3\nworkloads:\n"
                    +"  - id: sample\n    target: {type: flow, id: sample.shared.v1}\n    load: {users: 1, duration: 100ms}\n"
                    +"    execution: {thinkTime: 0ms}\nevidence: {mode: metrics}\n");
            Path suite=root.resolve("testcase/sample.xlsx");Files.createDirectories(suite.getParent());
            try(XSSFWorkbook workbook=new XSSFWorkbook();java.io.OutputStream output=Files.newOutputStream(suite)) {
                org.apache.poi.ss.usermodel.Sheet sheet=workbook.createSheet("Cases");org.apache.poi.ss.usermodel.Row header=sheet.createRow(0);
                String[] columns={"ID","Name","Tags","Template"};
                for(int index=0;index<columns.length;index++) header.createCell(index).setCellValue(columns[index]);
                org.apache.poi.ss.usermodel.Row row=sheet.createRow(1);row.createCell(0).setCellValue("C1");row.createCell(1).setCellValue("No Testdata");
                row.createCell(2).setCellValue("smoke");row.createCell(3).setCellValue("name: SIMPLE");workbook.write(output);
            }
            write(root.resolve("testcase/sample.yaml"),"schemaVersion: att-sidecar/v2.1\nid: sample\nexcel:\n  sheet: cases=Cases\n  caseId: ID\n  tags: Tags\n"
                    +"  dataColumns: caseName=Name\nstages:\n  - key: invoke\n    template: Template\n    required: true\n");
            assertEquals("stresstest".equals(name)?"UAT":"SIT",new FrameworkConfigLoader().load(config,config.getParent().getParent(),root,null).environment());
            SnapshotResult snapshot=service.snapshot(new SnapshotRequest(root,config,null,Collections.singletonList(suite),null,Collections.emptySet(),false));
            assertEquals("PASS",snapshot.status(),snapshot.toMap().toString());
            RunResult run=service.run(new RunRequest(root,config,null,null,"run-"+name,Collections.singletonList(suite),null,
                    Collections.emptySet(),Collections.emptySet(),Collections.emptySet(),false,false,false,false));
            assertEquals("PASS",run.status(),run.toMap().toString());
            assertEquals(1,((Number)run.summary().get("passed")).intValue());
            DebugResult debug=service.debug(new DebugRequest(root,config,null,null,"debug-"+name,"flow","sample.shared.v1",null,false));
            assertEquals("PASS",debug.status(),debug.toMap().toString());
            LoadResult quick=service.load(new LoadRequest(root,config,null,null,"quick-"+name,null,"flow","sample.shared.v1","1",null,
                    null,null,"100ms",null,"0ms",null,null,Collections.emptyList()));
            assertEquals("PASS",quick.status(),quick.toMap().toString());
            assertTrue(((Number)((java.util.Map<?,?>)quick.summary().get("metrics")).get("completed")).longValue()>0);
            LoadResult path=service.load(new LoadRequest(root,config,null,null,"path-"+name,root.resolve("load/scenario.yaml"),null,null,null,null,
                    null,null,null,null,null,null,null,Collections.emptyList()));
            assertEquals("PASS",path.status(),path.toMap().toString());
            assertTrue(((Number)((java.util.Map<?,?>)path.summary().get("metrics")).get("completed")).longValue()>0);
            if("stresstest".equals(name)) {
                ValidateResult full=service.validate(new ValidateRequest(root,config,null,Collections.emptyList(),null,Collections.emptySet(),Collections.emptySet(),Collections.emptySet(),true,"package"));
                assertEquals("INVALID",full.status(),"Full Package validation must still reject its declared missing descriptor");
                assertTrue(full.toMap().toString().contains("missing-unused.yaml"),full.toMap().toString());
            }
        }
    }

    private static Path write(Path path,String text) throws Exception {
        Files.createDirectories(path.getParent());Files.write(path,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));return path;
    }
}
