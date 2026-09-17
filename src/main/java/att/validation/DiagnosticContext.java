package att.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Execution provenance, separate from the file containing the invalid value. */
public final class DiagnosticContext {
    public static final DiagnosticContext EMPTY = new DiagnosticContext(null, null, null, null, Collections.<String>emptyList());
    private final String caseFile, caseId, stage, flowId;
    private final List<String> callChain;

    public DiagnosticContext(String caseFile, String caseId, String stage, String flowId, List<String> callChain) {
        this.caseFile = caseFile; this.caseId = caseId; this.stage = stage; this.flowId = flowId;
        this.callChain = Collections.unmodifiableList(new ArrayList<String>(callChain == null ? Collections.<String>emptyList() : callChain));
    }

    public DiagnosticContext withFallback(DiagnosticContext fallback) {
        if (fallback == null) return this;
        return new DiagnosticContext(first(caseFile, fallback.caseFile), first(caseId, fallback.caseId),
                first(stage, fallback.stage), first(flowId, fallback.flowId), callChain.isEmpty() ? fallback.callChain : callChain);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        if (caseFile != null) map.put("caseFile", caseFile);
        if (caseId != null) map.put("caseId", caseId);
        if (stage != null) map.put("stage", stage);
        if (flowId != null) map.put("flowId", flowId);
        if (!callChain.isEmpty()) map.put("callChain", callChain);
        return map;
    }
    private static String first(String value, String fallback) { return value == null ? fallback : value; }
}
