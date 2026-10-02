package att.load;

import att.config.FrameworkConfig;
import att.validation.PackageValidator;

import java.nio.file.Path;
import java.util.Set;

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
            LoadWorkload workload = scenario.workload();
            att.testdata.TestdataRegistry testdata = new att.testdata.TestdataRegistry(projectRoot,
                    config.testdataDescriptors(), scenario.testdataDescriptors());
            testdata.validateAll();
            att.testdata.TestdataMappingValidator.validate(workload.inputs(), testdata);
            Set<String> testdataIds = new java.util.LinkedHashSet<String>(
                    att.testdata.TestdataSyntax.references(workload.inputs()));
            testdataIds.addAll(workload.testdata().keySet());
            for (String id : testdataIds) {
                att.testdata.TestdataDescriptor descriptor = testdata.resolve(id);
                Object policyValue = workload.testdata().get(id);
                if (descriptor.count() == 1 && policyValue instanceof java.util.Map
                        && ((java.util.Map<?, ?>) policyValue).containsKey("selection"))
                    throw new IllegalArgumentException("Load workload selection override is meaningless for one-record testdata: " + id);
                if (descriptor.count() > 1 && descriptor.selection() == null
                        && (!(policyValue instanceof java.util.Map)
                            || !((java.util.Map<?, ?>) policyValue).containsKey("selection")))
                    throw new IllegalArgumentException("Testdata selection policy is required for multiple records: " + id);
            }
            att.template.UnifiedTemplateEngine bootstrapEngine = new att.template.UnifiedTemplateEngine(null, null, null, null,
                    new att.template.DefaultBuiltInProvider(new att.template.SequenceService()));
            String varsField = workload.sourceIndex() < 0 ? "vars"
                    : "workloads[" + workload.sourceIndex() + "].vars";
            Set<String> availableLoadFields = att.core.CaseRuntimeContext.availableLoadContextFields(
                    scenario.model() == LoadScenario.Model.CLOSED, !scenario.legacyV10());
            String inputsField = workload.sourceIndex() < 0 ? "inputs"
                    : "workloads[" + workload.sourceIndex() + "].inputs";
            att.core.ExecutionBootstrapVariables.validateInputMapping(workload.inputs(), bootstrapEngine,
                    scenario.source(), inputsField, att.validation.DiagnosticCodes.LOAD_INVALID,
                    att.core.ExecutionBootstrapVariables.InputMappingMode.LOAD, availableLoadFields);
            try {
                att.core.ExecutionBootstrapVariables.validate(scenario.vars(), bootstrapEngine, scenario.inputs(),
                        scenario.source(), varsField, att.validation.DiagnosticCodes.LOAD_INVALID,
                        att.core.ExecutionBootstrapVariables.Scope.LOAD, availableLoadFields);
            } catch (att.validation.DiagnosticException error) {
                throw error.withDetail("workloadId: " + workload.id());
            }
            new PackageValidator(projectRoot, config).validateDebugTarget(target.template(), testCase, stage, target.flows(),
                    scenario.source(), "load", scenario.inputs(), scenario.vars(), scenario.testdataDescriptors());
            target.withFileSnapshot(new att.template.FileExpressionResolver(projectRoot)
                    .snapshotFor(target.template(), target.flows()));
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
