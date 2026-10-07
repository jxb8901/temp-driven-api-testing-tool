package att.server.api;

import com.fasterxml.jackson.databind.JsonNode;

/** Canonical response from GET /jobs/{jobId}/result. */
public final class JobResult {
    public JobSummary job;
    public JsonNode result;
    public JsonNode diagnostic;
    public JobResult() { }
    public static final class JobSummary {
        public String jobId;
        public String command;
        public String packageId;
        public String status;
        public Integer exitCode;
        public JobSummary() { }
    }
}
