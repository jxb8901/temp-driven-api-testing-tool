package att.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Explicit per-load policy: discovery never reads process environment values. */
final class EnvironmentReferences {
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{ENV:([A-Za-z_][A-Za-z0-9_]*)}");
    private final boolean discovery;
    private final List<HelperDiscoveryFailure> helperFailures;
    private final List<EnvironmentRequirement> requirements;
    private final String helperType, helperId, descriptor;

    private EnvironmentReferences(boolean discovery, List<EnvironmentRequirement> requirements,
                                  String helperType, String helperId, String descriptor, List<HelperDiscoveryFailure> helperFailures) {
        this.discovery = discovery;
        this.helperFailures = helperFailures;
        this.requirements = requirements;
        this.helperType = helperType;
        this.helperId = helperId;
        this.descriptor = descriptor;
    }

    static EnvironmentReferences execution() { return create(false); }
    static EnvironmentReferences discovery() { return create(true); }
    private static EnvironmentReferences create(boolean discovery) {
        return new EnvironmentReferences(discovery, new ArrayList<EnvironmentRequirement>(), "", "", "", new ArrayList<HelperDiscoveryFailure>());
    }

    EnvironmentReferences forHelper(String type, Object id, String path) {
        return new EnvironmentReferences(discovery, requirements, type, String.valueOf(id), path, helperFailures);
    }

    List<EnvironmentRequirement> requirements() {
        return Collections.unmodifiableList(new ArrayList<EnvironmentRequirement>(requirements));
    }

    boolean isDiscovery() { return discovery; }
    int requirementCount() { return requirements.size(); }
    void discardRequirementsAfter(int count) { requirements.subList(count, requirements.size()).clear(); }
    void helperFailure(String type, String path, String state) {
        if (helperFailures.size() >= 1024) throw new IllegalArgumentException("Helper discovery failure limit exceeded");
        helperFailures.add(new HelperDiscoveryFailure(type, path, state));
    }
    List<HelperDiscoveryFailure> helperFailures() { return helperFailures; }

    String resolve(String configured, String field, boolean nonEmpty) {
        Matcher reference = REFERENCE.matcher(configured);
        if (!reference.matches()) {
            if (configured.contains("${ENV:")) throw new IllegalArgumentException(field + " has an invalid environment reference");
            return configured;
        }
        String variable = reference.group(1);
        if (discovery) {
            if (requirements.size() >= 4096 || variable.length() > 256)
                throw new IllegalArgumentException("Environment requirements exceed the discovery limit");
            requirements.add(new EnvironmentRequirement(helperType, helperId, descriptor, field, variable));
            return configured;
        }
        String value = att.security.ExecutionEnvironment.get(variable);
        if (value == null || (nonEmpty && value.trim().isEmpty()))
            throw new IllegalArgumentException(field + " references missing or empty environment variable " + variable);
        return value;
    }
}
