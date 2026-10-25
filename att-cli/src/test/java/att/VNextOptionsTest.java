package att;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VNextOptionsTest {
    @Test void recognizesOnlyTheThreeTypedCommandsAndParsesFlagsAroundThem() {
        assertTrue(VNextOptions.isVNextRequest(new String[]{"--server","sit","-p","payments","run","suite","testcase/payment.xlsx"}));
        VNextOptions options=VNextOptions.parse(new String[]{"-p","payments","validate","config","-e","UAT","--format","json"});
        assertEquals("validate",options.command());assertEquals("config",options.targetKind());
        assertEquals("payments",options.packageId());assertEquals("UAT",options.environment());assertEquals("json",options.format());
        assertFalse(VNextOptions.isVNextRequest(new String[]{"snapshot","--all"}));
    }

    @Test void validateAllIsExclusiveAndCannotCarryClientPathsRemotely() {
        assertEquals("validate",VNextOptions.parse(new String[]{"--local","validate","--all","--config","config/config.yaml"}).command());
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"validate","--all","-p","payments"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"validate","--all","-e","SIT"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--server","sit","validate","config","--config","config/config.yaml"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--server","sit","run","case","id","--output-dir","out"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--server","sit","run","case","id"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--server","sit","load","scenario","smoke.yaml"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--server","sit","validate","package"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--server","sit","validate","case","PAYMENT"}));
        assertEquals("config",VNextOptions.parse(new String[]{"--server","sit","validate","config","-e","UAT"}).targetKind());
    }

    @Test void rejectsUnsafeOrAmbiguousExecutionCombinationsBeforeDispatch() {
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","cases","--all","--tag","smoke"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"load","flow","payment.submit","--users","2","--arrival-rate","5/s"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"load","scenario","load/smoke.yaml","--dry-run"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"--local","--server","sit","validate","config"}));
    }

    @Test void resourceRunDryRunIsRejectedForLocalAndRemoteBeforeDispatch() {
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{
                "--local","run","flow","PAYMENT.submit","--dry-run"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{
                "--server","sit","-p","payments","run","tool","mq.send","--dry-run"}));
        assertTrue(VNextOptions.parse(new String[]{"--local","run","flow","PAYMENT.submit"}).input()==null);
    }

    @Test void singleCaseAndSuiteTargetsRejectAdditionalSelectorsForLocalAndRemote() {
        String[][] prefixes={{"--local"},{"--server","sit","-p","payments"}};
        String[][] extraSelectors={
                {"run","case","PAYMENT.A","--case","PAYMENT.B"},
                {"run","case","PAYMENT.A","--case-id","PAYMENT.B"},
                {"run","case","PAYMENT.A","--tag","smoke"},
                {"run","case","PAYMENT.A","--exclude-tag","skip"},
                {"run","suite","testcase/A.xlsx","--suite","testcase/B.xlsx"},
                {"run","suite","testcase/A.xlsx","--suite-dir","testcase"}
        };
        for(String[] prefix:prefixes) {
            for(String[] command:extraSelectors) {
                String[] args=concat(prefix,command);
                assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(args));
            }
        }
        assertEquals("case",VNextOptions.parse(new String[]{"run","case","PAYMENT.A"}).targetKind());
        assertEquals("suite",VNextOptions.parse(new String[]{"run","suite","testcase/A.xlsx"}).targetKind());
    }

    @Test void directLoadRejectsInputUntilBothAdaptersSupportIt() {
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{
                "--local","load","flow","PAYMENT.submit","--input","templates/flows/PAYMENT.submit/load.yaml"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{
                "--server","sit","-p","payments","load","template","PAYMENT_INVOKE","--input","input.yaml"}));
        assertEquals("input.yaml",VNextOptions.parse(new String[]{
                "--local","run","flow","PAYMENT.submit","--input","input.yaml"}).input().toString());
        assertEquals("input.yaml",VNextOptions.parse(new String[]{
                "--server","sit","-p","payments","run","flow","PAYMENT.submit","--input","input.yaml"}).input().toString());
    }

    private static String[] concat(String[] prefix,String[] command) {
        String[] result=new String[prefix.length+command.length];
        System.arraycopy(prefix,0,result,0,prefix.length);
        System.arraycopy(command,0,result,prefix.length,command.length);
        return result;
    }

    @Test void requestedFormatSurvivesOtherArgumentErrors() {
        assertEquals("json",VNextOptions.requestedFormat(new String[]{"--bad-option","--format","json"}));
        assertEquals("human",VNextOptions.requestedFormat(new String[]{"--format","xml"}));
    }
}
