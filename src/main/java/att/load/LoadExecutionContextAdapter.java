package att.load;

import att.config.FrameworkConfig;
import att.core.CaseRuntimeContext;
import att.core.StageCaseData;
import att.core.TestCase;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapts scheduler-owned iteration data into the canonical ATT Context.
 *
 * <p>This is the single load boundary: it creates the synthetic Case, maps
 * scenario inputs to {@code EXEC.INPUT}, publishes curated META, and adds
 * scheduler identity below {@code EXEC.LOAD}. Runtime execution remains in
 * the common Stage/Template/Flow runner.</p>
 */
public final class LoadExecutionContextAdapter {
    private final Path projectRoot;
    private final FrameworkConfig config;
    private final LoadTarget target;

    public LoadExecutionContextAdapter(Path projectRoot, FrameworkConfig config, LoadTarget target) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.config = config;
        this.target = target;
    }

    public Prepared prepare(IterationRequest request, String executionId, Path iterationDirectory, Path logPath) {
        TestCase testCase = testCase(request.iterationId(), request.inputs());
        StageCaseData stage = stage();
        CaseRuntimeContext context = new CaseRuntimeContext(testCase, iterationDirectory, executionId, request.runId(),
                iterationDirectory, logPath, "load", request.startedAt().toString(), request.runStartedAt().toString());
        context.setProject(projectRoot);
        context.setLoadSourceMetadata(target.scenarioSource(), target.scenarioName());
        context.setTargetMetadata(target.type(), target.id());
        context.put("CASE.environment", config.environment());
        context.setLegacyInputsView(request.inputs());
        context.setLoad(request.runId(), request.model(), request.iterationId(), request.iteration(), request.phase(),
                request.startedAt().toString(), request.userId(), request.runStartedAt().toString());
        if (request.workloadId() != null) context.setLoadWorkload(request.workloadId(), target.type(), target.id());
        return new Prepared(testCase, stage, context);
    }

    TestCase testCase(String caseId, Map<String, Object> inputs) {
        Map<String, Object> data = inputs == null ? new LinkedHashMap<String, Object>() : LoadIsolation.deepCopyMap(inputs);
        if (!data.containsKey("caseName")) data.put("caseName", "LOAD " + target.type() + " " + target.id());
        String rowId = caseId == null ? "iteration-validation" : caseId.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new TestCase(1, "LOAD", target.type(), rowId, Collections.<String>emptyList(), data,
                Collections.singletonMap("LOAD", stage()), "");
    }

    StageCaseData stage() {
        return new StageCaseData("LOAD", target.template().name(), Collections.<String, Object>emptyMap());
    }

    public static final class Prepared {
        private final TestCase testCase;
        private final StageCaseData stage;
        private final CaseRuntimeContext context;

        private Prepared(TestCase testCase, StageCaseData stage, CaseRuntimeContext context) {
            this.testCase = testCase;
            this.stage = stage;
            this.context = context;
        }

        public TestCase testCase() { return testCase; }
        public StageCaseData stage() { return stage; }
        public CaseRuntimeContext context() { return context; }
    }
}
