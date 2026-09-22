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
            new PackageValidator(projectRoot, config).validateDebugTarget(target.template(), testCase, stage, target.flows(),
                    scenario.source(), "load", scenario.inputs());
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
