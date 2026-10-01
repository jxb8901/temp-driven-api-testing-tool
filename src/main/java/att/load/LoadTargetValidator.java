package att.load;

import att.config.FrameworkConfig;
import att.validation.PackageValidator;

import java.nio.file.Path;

/** Performs selected Template/Flow/Tool dependency validation before scheduling. */
public final class LoadTargetValidator {
    private final Path projectRoot;
    private final FrameworkConfig config;
    public LoadTargetValidator(Path projectRoot, FrameworkConfig config) { this.projectRoot = projectRoot; this.config = config; }

    public void validate(LoadScenario scenario, LoadTarget target) throws Exception {
        LoadExecutionContextAdapter adapter = new LoadExecutionContextAdapter(projectRoot, config, target);
        att.core.TestCase testCase = adapter.testCase("iteration-validation", scenario.inputs());
        att.core.StageCaseData stage = adapter.stage();
        try {
            att.template.UnifiedTemplateEngine bootstrapEngine = new att.template.UnifiedTemplateEngine(null, null, null, null,
                    new att.template.DefaultBuiltInProvider(new att.template.SequenceService()));
            LoadWorkload workload = scenario.workload();
            String varsField = workload.sourceIndex() < 0 ? "vars"
                    : "workloads[" + workload.sourceIndex() + "].vars";
            try {
                att.core.ExecutionBootstrapVariables.validate(scenario.vars(), bootstrapEngine, scenario.inputs(),
                        scenario.source(), varsField, att.validation.DiagnosticCodes.LOAD_INVALID,
                        att.core.ExecutionBootstrapVariables.Scope.LOAD);
            } catch (att.validation.DiagnosticException error) {
                throw error.withDetail("workloadId: " + workload.id());
            }
            new PackageValidator(projectRoot, config).validateDebugTarget(target.template(), testCase, stage, target.flows(),
                    scenario.source(), "load", scenario.inputs(), scenario.vars());
        } catch (Exception e) {
            att.validation.DiagnosticException typed = att.validation.DiagnosticException.find(e);
            if (typed != null) throw typed;
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.LOAD_INVALID,
                    "Invalid load target dependencies", e.getMessage(), scenario.source().toString(), "target", null, null, null,
                    target.template().name(), null,
                    "Correct the selected Template/Flow/Tool dependency before starting load scheduling.", e);
        }
    }

}
