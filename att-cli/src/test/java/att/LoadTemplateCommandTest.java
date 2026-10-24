package att;
import att.config.FrameworkConfig;
import att.config.FrameworkConfigLoader;
import att.load.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static att.load.LoadTemplateCatalog.obj;
class LoadTemplateCommandTest {
 @TempDir Path root;
 @Test void previewsAllSixModesWithTypedIndependentSidecarsAndRepeatedTargetsWithoutTraffic() throws Exception {
  FrameworkConfig config=fixture();
  for(String mode:LoadTemplateCatalog.MODES) {
   Map<String,Object> definition=LoadTemplateCatalog.builtin(mode);
   boolean single=mode.equals("single-vu")||mode.equals("single-tps");
   List<String> argv=new ArrayList<>(Arrays.asList("load","--debug"));
   if(single)Collections.addAll(argv,"template","TARGET");
   Collections.addAll(argv,"--load-template","builtin:"+mode,"--preview","--env","SIT","--format","json");
   if(!single) {
    Map<String,Object> bindings=new LinkedHashMap<>();int index=0;
    for(Object item:(List<?>)definition.get("workloads")) {
     Map<?,?> row=(Map<?,?>)item;Map<String,Object> selected=new LinkedHashMap<>();
     if(row.containsKey("mix")) {
      Map<String,Object> mix=new LinkedHashMap<>();
      for(Object entry:(List<?>)row.get("mix"))mix.put(String.valueOf(((Map<?,?>)entry).get("id")),obj("type","template","id",index++%2==0?"TARGET":"SECOND"));
      selected.put("mix",mix);
     } else selected.put("target",obj("type","template","id","TARGET"));
     bindings.put(String.valueOf(row.get("id")),selected);
    }
    write("load/bindings/test.yaml",new org.yaml.snakeyaml.Yaml().dump(obj("schemaVersion",LoadTemplateCatalog.BINDINGS_SCHEMA,"bindings",bindings)));
    Collections.addAll(argv,"--bindings","load/bindings/test.yaml");
   }
   LoadScenario scenario=LoadTemplateCommand.compose(root,config,CliOptions.parse(argv.toArray(new String[0])));
   assertEquals("att-load/v1.9",scenario.schemaVersion());assertEquals(mode.startsWith("multi-")?2:1,scenario.workloads().size());
   for(LoadWorkload workload:scenario.workloads()) {
    if(workload.mixed()) {assertEquals(7,workload.mix().get(0).inputs().get("count"));assertEquals(9,workload.mix().get(1).inputs().get("count"));}
    else assertEquals(7,workload.inputs().get("count"));
   }
  }
  assertFalse(Files.exists(root.resolve("output")));assertFalse(Files.exists(root.resolve(".att")));
 }
 @Test void explicitCliIntensityAndTimingOverrideWorkloadDefaults() throws Exception {
  FrameworkConfig config=fixture();
  Map<String,Object> definition=LoadTemplateCatalog.builtin("single-vu");
  @SuppressWarnings("unchecked") Map<String,Object> workload=(Map<String,Object>)((List<?>)definition.get("workloads")).get(0);
  workload.put("load",obj("users",9,"duration","30s"));
  write("load/templates/custom.yaml",new org.yaml.snakeyaml.Yaml().dump(definition));
  CliOptions options=CliOptions.parse(new String[]{"load","--debug","template","TARGET","--load-template","load/templates/custom.yaml","--users","2","--duration","2s","--preview"});
  LoadScenario scenario=LoadTemplateCommand.compose(root,config,options);
  assertEquals(2,scenario.workloads().get(0).users());
  assertEquals(java.time.Duration.ofSeconds(2),scenario.workloads().get(0).duration());
 }
 @Test void rejectsDebugLocalImportsAndMismatchedTemplateBindingGrammar() throws Exception {
  FrameworkConfig config=fixture();
  write("testdata/local.yaml","schemaVersion: att-testdata/v1.0\nid: local\nrecords: [{count: 1}]\n");
  write("templates/TARGET/debug.yaml","schemaVersion: att-debug/v1.2\ntestdata: [testdata/local.yaml]\ninputs: {count: 7}\n");
  CliOptions options=CliOptions.parse(new String[]{"load","--debug","template","TARGET","--load-template","builtin:single-vu","--preview"});
  assertTrue(assertThrows(IllegalArgumentException.class,()->LoadTemplateCommand.compose(root,config,options)).getMessage().contains("testdata"));
  CliOptions wrong=CliOptions.parse(new String[]{"load","--debug","template","TARGET","--load-template","builtin:multi-vu","--preview"});
  assertTrue(assertThrows(IllegalArgumentException.class,()->LoadTemplateCommand.compose(root,config,wrong)).getMessage().contains("bindings"));
 }
 private FrameworkConfig fixture() throws Exception {
  TestSchemas.install(root);
  write("config/config.yaml","schemaVersion: att-config/v2.12\nenvironment: SIT\nenvironments: {SIT: {}}\ntemplates: {root: templates}\n");
  for(String id:Arrays.asList("TARGET","SECOND")) {
   write("templates/"+id+"/template.yaml","schemaVersion: att-template/v3.6\nname: "+id+"\ndescription: Preview fixture\nactions:\n  log:\n    type: log\n    message: '${EXEC.INPUT.count}'\n");
   write("templates/"+id+"/debug.yaml","schemaVersion: att-debug/v1.2\ninputs: {count: "+(id.equals("TARGET")?7:9)+"}\nvars: {enabled: true}\n");
  }
  return new FrameworkConfigLoader().load(root.resolve("config/config.yaml"),root);
 }
 private void write(String path,String text) throws Exception {Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.write(file,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
