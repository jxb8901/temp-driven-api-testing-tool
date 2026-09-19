package att.load;

import att.config.FrameworkConfig;
import att.core.StageCaseData;
import att.core.TestCase;
import att.validation.PackageValidator;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Performs selected Template/Flow/Tool dependency validation before scheduling. */
public final class LoadTargetValidator {
    private final Path projectRoot;
    private final FrameworkConfig config;
    public LoadTargetValidator(Path projectRoot, FrameworkConfig config) { this.projectRoot = projectRoot; this.config = config; }

    public void validate(LoadScenario scenario, LoadTarget target) throws Exception {
        Map<String, Object> data = caseData(scenario.inputs());
        if (!data.containsKey("caseName")) data.put("caseName", "LOAD " + scenario.targetType() + " " + scenario.targetId());
        StageCaseData stage = new StageCaseData("LOAD", target.template().name(), Collections.<String, Object>emptyMap());
        TestCase testCase = new TestCase(1, "LOAD", scenario.targetType(), "iteration-validation", Collections.<String>emptyList(), data,
                Collections.singletonMap("LOAD", stage), "");
        try {
            new PackageValidator(projectRoot, config).validateDebugTarget(target.template(), testCase, stage, target.flows(), scenario.source());
        } catch (Exception e) {
            att.validation.DiagnosticException typed = att.validation.DiagnosticException.find(e);
            if (typed != null) throw typed;
            throw new att.validation.DiagnosticException(att.validation.DiagnosticCodes.LOAD_INVALID,
                    "Invalid load target dependencies", e.getMessage(), scenario.source().toString(), "target", null, null, null,
                    target.template().name(), null,
                    "Correct the selected Template/Flow/Tool dependency before starting load scheduling.", e);
        }
    }

    private Map<String, Object> caseData(Map<String, Object> inputs) {
        Map<String, Object> data = new LinkedHashMap<String, Object>(inputs);
        if (!inputs.isEmpty()) {
            data.put("inputs", new LinkedHashMap<String, Object>(inputs));
            for (Map.Entry<String, Object> entry : inputs.entrySet())
                if (!data.containsKey(entry.getKey())) data.put(entry.getKey(), entry.getValue());
        }
        return data;
    }
}
