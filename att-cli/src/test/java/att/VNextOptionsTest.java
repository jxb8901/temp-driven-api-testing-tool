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

    @Test void nativeCaseUsesExplicitLocalResolvedIdsAndLeavesLegacyCaseSelectorAlone() {
        assertTrue(VNextOptions.isVNextRequest(new String[]{"run","native-case","payments.transfer","--resolved-case-id","valid"}));
        VNextOptions nativeCase=VNextOptions.parse(new String[]{"--local","-p","payments","run","native-case","payments.transfer",
                "--resolved-case-id","valid","--resolved-case-id","declined"});
        assertEquals("native-case",nativeCase.targetKind());
        assertEquals("payments.transfer",nativeCase.target());
        assertEquals(java.util.Arrays.asList("valid","declined"),nativeCase.resolvedCaseIds());
        VNextOptions remotePreview=VNextOptions.parse(new String[]{"--server","sit","-p","payments","run","native-case","payments.transfer",
                "--resolved-case-id","valid","--dry-run"});
        assertTrue(remotePreview.dryRun());

        VNextOptions legacy=VNextOptions.parse(new String[]{"run","case","payments.transfer.valid"});
        assertEquals("case",legacy.targetKind());
        assertTrue(legacy.resolvedCaseIds().isEmpty());
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","native-case","payments.transfer"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","native-case","payments.transfer",
                "--resolved-case-id","valid","--resolved-case-id","valid"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","native-case","payments.transfer",
                "--resolved-case-id","valid","--case","payments.transfer.valid"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","case","payments.transfer.valid",
                "--resolved-case-id","valid"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","native-case","payments.transfer",
                "--resolved-case-id",""}));
    }

    @Test void nativeSuiteRequiresExplicitDefinitionsAndUsesQualifiedSelectors() {
        String[] selectedArgs={"--local","run","native-suite","--definition","fpp.alpha",
                "--definition","fpp.zeta","--qualified-case-id","fpp.zeta.third",
                "--qualified-case-id","fpp.alpha.first","--tag","smoke","--exclude-tag","skip"};
        assertTrue(VNextOptions.isVNextRequest(selectedArgs));
        VNextOptions selected=VNextOptions.parse(selectedArgs);
        assertEquals("native-suite",selected.targetKind());
        assertNull(selected.target());
        assertEquals(java.util.Arrays.asList("fpp.alpha","fpp.zeta"),selected.definitionIds());
        assertEquals(java.util.Arrays.asList("fpp.zeta.third","fpp.alpha.first"),selected.qualifiedCaseIds());
        assertEquals(java.util.Collections.singleton("smoke"),selected.tags());
        assertEquals(java.util.Collections.singleton("skip"),selected.excludeTags());

        VNextOptions all=VNextOptions.parse(new String[]{"--server","sit","-p","payments","run","native-suite",
                "--definition","fpp.transfer","--all"});
        assertTrue(all.all());
        assertEquals("payments",all.packageId());

        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","native-suite","--definition","fpp.transfer"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","native-suite","--definition","fpp.transfer","--all","--qualified-case-id","fpp.transfer.one"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","native-suite","--definition","fpp.transfer","--qualified-case-id","fpp.other.one"}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","native-suite","fpp.transfer","--all","--definition","fpp.transfer"}));
        String longTag=String.join("",java.util.Collections.nCopies(129,"x"));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","native-suite","--definition","fpp.transfer","--tag",longTag}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","native-suite","--definition","fpp.transfer","--all","--exclude-tag",longTag}));
        assertThrows(IllegalArgumentException.class,()->VNextOptions.parse(new String[]{"run","native-suite","--definition","fpp.transfer","--all","--rerun-failed"}));
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
