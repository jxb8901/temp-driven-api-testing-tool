package att;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LoadTemplateOptionsTest {
 @Test @SuppressWarnings("unchecked") void cliListingIncludesEveryTemplateAcrossPages(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
  java.nio.file.Path directory=java.nio.file.Files.createDirectories(root.resolve("load/templates"));
  byte[] yaml=new org.yaml.snakeyaml.Yaml().dump(att.load.LoadTemplateCatalog.builtin("single-vu")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
  for(int i=0;i<105;i++) java.nio.file.Files.write(directory.resolve(String.format("template-%03d.yaml",i)),yaml);
  java.util.Map<String,Object> listing=LoadTemplateCommand.list(root);
  java.util.List<java.util.Map<String,Object>> items=(java.util.List<java.util.Map<String,Object>>)listing.get("items");
  assertEquals(111,items.size()); assertEquals(111,listing.get("total")); assertNull(listing.get("nextOffset"));
  assertFalse((Boolean)listing.get("truncated"));
  assertEquals(111,items.stream().map(item->item.get("id")).distinct().count());
  assertTrue(items.stream().anyMatch(item->"load/templates/template-104.yaml".equals(item.get("id"))));
 }

 @Test void acceptsExplicitSingleAndMultiGrammarAndReadOnlyOperations() {
  CliOptions single=CliOptions.parse(new String[]{"load","--debug","tool","echo","--load-template","builtin:single-tps","--arrival-rate","2/s","--preview","--format","json","--env","SIT"});
  assertEquals("echo",single.debugTargetId()); assertTrue(single.previewLoadTemplate()); assertEquals("SIT",single.environment());
  CliOptions multi=CliOptions.parse(new String[]{"load","--debug","--load-template","builtin:multi-workload-mix","--bindings","load/bindings/mix.yaml","--preview"});
  assertTrue(multi.loadDebug()); assertEquals("load/bindings/mix.yaml",multi.loadBindings());
  assertTrue(CliOptions.parse(new String[]{"load","--list-templates","--format","json"}).listLoadTemplates());
 }
 @Test void rejectsAmbiguousOrIncompleteMultiTargetArgumentsAndPreservesLegacyGrammar() {
  assertThrows(IllegalArgumentException.class,()->CliOptions.parse(new String[]{"load","--debug"}));
  assertThrows(IllegalArgumentException.class,()->CliOptions.parse(new String[]{"load","--debug","--load-template","builtin:multi-vu"}));
  for(String[] override:new String[][]{{"--users","10"},{"--arrival-rate","2/s"},{"--set","input.value=1"},{"--input","debug.yaml"}}) {
   java.util.List<String> args=new java.util.ArrayList<>(java.util.Arrays.asList("load","--debug","--load-template","builtin:multi-vu","--bindings","load/bindings/vu.yaml"));
   java.util.Collections.addAll(args,override);
   assertThrows(IllegalArgumentException.class,()->CliOptions.parse(args.toArray(new String[0])));
  }
  assertTrue(CliOptions.parse(new String[]{"load","--debug","tool","echo","--users","1","--duration","1s"}).loadDebug());
 }
}
