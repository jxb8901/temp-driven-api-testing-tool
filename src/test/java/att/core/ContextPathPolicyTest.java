/* Author: Jeffrey + ChatGPT */
package att.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextPathPolicyTest {
    @Test void classifiesCanonicalLegacyAndTransientScopes() {
        assertEquals(ContextPathPolicy.Scope.CANONICAL_INPUT,
                ContextPathPolicy.classify("EXEC.INPUT.amount"));
        assertEquals(ContextPathPolicy.Scope.CANONICAL_VARS,
                ContextPathPolicy.classify("EXEC.VARS.txnSeq"));
        assertEquals(ContextPathPolicy.Scope.CANONICAL_ACTIONS,
                ContextPathPolicy.classify("EXEC.ACTIONS.call.output.result"));
        assertEquals(ContextPathPolicy.Scope.LEGACY_STAGE_EVIDENCE,
                ContextPathPolicy.classify("CASE.STAGES.invoke.channel"));
        assertEquals(ContextPathPolicy.Scope.TRANSIENT_TOOL,
                ContextPathPolicy.classify("TOOL.output.result"));
        assertEquals(ContextPathPolicy.Scope.TRANSIENT_DB,
                ContextPathPolicy.classify("DB.orders.query.result"));
        assertEquals(ContextPathPolicy.Scope.ACTION_OUTPUT,
                ContextPathPolicy.classify("output.result"));
    }

    @Test void centralizesValidationAvailabilityAndProtectedFields() {
        assertTrue(ContextPathPolicy.isValidationValueAvailable("EXEC.INPUT.amount"));
        assertTrue(ContextPathPolicy.isValidationValueAvailable("META.SOURCE.caseId"));
        assertTrue(ContextPathPolicy.isRuntimeDependent("EXEC.ACTIONS.call.output.result"));
        assertTrue(ContextPathPolicy.isRuntimeDependent("CASE.STAGES.invoke.status"));
        assertTrue(ContextPathPolicy.isFrameworkOwnedExecField("INPUT"));
        assertTrue(ContextPathPolicy.isCanonicalExecField("ACTIONS"));
        assertTrue(ContextPathPolicy.isCanonicalMetaField("TARGET"));
        org.junit.jupiter.api.Assertions.assertFalse(ContextPathPolicy.isCanonicalExecField("STATUS"));
        org.junit.jupiter.api.Assertions.assertFalse(ContextPathPolicy.isCanonicalMetaField("TARGETT"));
        assertTrue(ContextPathPolicy.isUnsupportedStagePath("STAGES.invoke"));
        assertTrue(ContextPathPolicy.isCanonicalRoot("EXEC"));
        assertTrue(ContextPathPolicy.isCanonicalRoot("META"));
        assertTrue(ContextPathPolicy.isUnsupportedExecPath("TOOL.orders.find"));
        assertTrue(ContextPathPolicy.isUnsupportedExecPath("DB.orders.query"));
        assertTrue(ContextPathPolicy.isUnsupportedExecPath("MQ.orders.send"));
        assertTrue(ContextPathPolicy.isUnsupportedExecPath("STAGES.invoke"));
    }
}
