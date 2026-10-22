package att;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LoadTemplateOptionsTest {
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
