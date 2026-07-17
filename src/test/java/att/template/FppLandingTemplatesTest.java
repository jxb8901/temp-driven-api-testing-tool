/* Author: Jeffrey + ChatGPT */
package att.template;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FppLandingTemplatesTest {
    private final Path projectRoot = Paths.get("").toAbsolutePath().normalize();

    @Test void d3iTemplateCoversAllFiveBatchPhasesAndPollingContract() throws Exception {
        StageTemplate template = loader().load("FPP_D3I_BATCH_SIT");
        Map<String, TemplateAction> actions = index(template);

        for (String id : new String[]{"moveD3iFiles", "runJob2D3i", "runJob3D3i", "convertD3Rlt",
                "runJob2Rlt", "runJob3Rlt", "inspectDvoOutput", "moveDrrFiles", "runJob2Drr",
                "runJob3Drr", "moveDsrFiles", "runJob2Dsr", "runJob3Dsr", "inspectDsrAftOutput"}) {
            assertNotNull(actions.get(id), "missing D3I phase action " + id);
        }
        assertTrue(template.actions().size() >= 70, "D3I template should preserve the complete photographed flow");
        assertPolling(actions.get("queryD3iTxn"));
        assertPolling(actions.get("queryDsrFinalTxn"));
        assertTrue(actions.get("assertDrrTxn").expected().contains("CAPT or CRJT"));
        assertTrue(actions.get("assertDsrTxn").expected().contains("ACPT or UCPT"));
    }

    @Test void ctoRtiTemplateActivatesPrecheckAndKeepsLaterFlowStaged() throws Exception {
        StageTemplate template = loader().load("FPP_CTO_RTI_USMF");
        Map<String, TemplateAction> actions = index(template);

        for (String id : new String[]{"buildTxnSeq", "renderPrecheckRequest", "log1",
                "invokePrecheck", "assertPrecheck"}) {
            assertNotNull(actions.get(id), "missing active CTO/RTI precheck action " + id);
        }
        assertEquals(5, actions.size());
        assertNull(actions.get("searchPrecheckLog"), "top-level x-staged extension must not become an action");
        assertEquals("TxnSeq", actions.get("buildTxnSeq").name());
        assertTrue(actions.get("invokePrecheck").call().contains("${CASE.VARS.TxnSeq}"));
    }

    private StageTemplateLoader loader() throws Exception {
        return new StageTemplateLoader(projectRoot, Paths.get("templates"));
    }

    private Map<String, TemplateAction> index(StageTemplate template) {
        Map<String, TemplateAction> result = new LinkedHashMap<String, TemplateAction>();
        for (TemplateAction action : template.actions()) result.put(action.id(), action);
        return result;
    }

    private void assertPolling(TemplateAction action) {
        assertNotNull(action);
        assertEquals(Long.valueOf(10000), action.timeoutMs());
        assertEquals("3", String.valueOf(action.retry().get("maxAttempts")));
        assertTrue(String.valueOf(action.retry().get("retryOn")).contains("EXIT_CODE"));
        assertTrue(action.call().contains("fpp.querySqlplus"));
    }
}
