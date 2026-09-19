/* Author: Jeffrey + ChatGPT */
package att.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shared classification rules for Context paths.
 *
 * <p>Keeping these rules in one place prevents the renderer, validator, and
 * runtime resolver from slowly growing different ideas of which paths are
 * canonical, legacy, transient, or runtime-only.</p>
 */
public final class ContextPathPolicy {
    public enum Scope {
        CANONICAL_INPUT,
        CANONICAL_VARS,
        CANONICAL_ACTIONS,
        CANONICAL_META,
        LEGACY_STAGE_EVIDENCE,
        LEGACY_ALIAS,
        TRANSIENT_TOOL,
        TRANSIENT_DB,
        ACTION_OUTPUT,
        OTHER
    }

    private static final Set<String> CANONICAL_ROOTS = roots("EXEC", "META");
    private static final Set<String> EXPRESSION_ROOTS = roots(
            "EXEC", "META", "CASE", "RUN", "ACTIONS", "TOOL", "DB", "output");

    private ContextPathPolicy() { }

    public static boolean isExplicitRoot(String root) {
        return root != null && EXPRESSION_ROOTS.contains(root);
    }

    public static boolean isCanonicalRoot(String root) {
        return root != null && CANONICAL_ROOTS.contains(root);
    }

    public static boolean isUnsupportedStagePath(String path) {
        String field = firstSegment(path == null ? "" : path);
        return "STAGE".equals(field) || "STAGES".equals(field);
    }

    /**
     * Returns whether a path below {@code EXEC} names a helper/resource or
     * orchestration namespace that is deliberately not canonical in 3.4.2.
     * These namespaces may still exist as legacy/transient views outside
     * {@code EXEC}; they must never be created below the canonical root.
     */
    public static boolean isUnsupportedExecPath(String path) {
        String field = firstSegment(path == null ? "" : path);
        return "TOOL".equals(field) || "DB".equals(field) || "MQ".equals(field)
                || "OUTPUT".equals(field) || "LOAD".equals(field)
                || "CALL".equals(field) || "INVOCATION".equals(field)
                || isUnsupportedStagePath(path);
    }

    public static boolean isFrameworkOwnedExecField(String field) {
        if (field == null) return false;
        return "ID".equals(field) || "MODE".equals(field) || "STARTED_AT".equals(field)
                || "OUTPUT_DIR".equals(field) || "INPUT".equals(field) || "VARS".equals(field)
                || "ACTIONS".equals(field);
    }

    /** Returns whether the first child below EXEC is part of the public 3.4.2 tree. */
    public static boolean isCanonicalExecField(String field) {
        return isFrameworkOwnedExecField(field);
    }

    /** Returns whether the first child below META is part of the curated public tree. */
    public static boolean isCanonicalMetaField(String field) {
        return "PROJECT".equals(field) || "SOURCE".equals(field) || "TARGET".equals(field)
                || "TEMPLATE".equals(field) || "FLOW".equals(field) || "TOOL".equals(field)
                || "DBHELPER".equals(field) || "MQHELPER".equals(field);
    }

    public static Scope classify(String path) {
        if (path == null || path.isEmpty()) return Scope.OTHER;
        if (path.startsWith("EXEC.INPUT.") || path.startsWith("EXEC.INPUT[")) return Scope.CANONICAL_INPUT;
        if (path.startsWith("EXEC.VARS.") || path.startsWith("EXEC.VARS[")) return Scope.CANONICAL_VARS;
        if (path.startsWith("EXEC.ACTIONS.") || path.startsWith("EXEC.ACTIONS[")) return Scope.CANONICAL_ACTIONS;
        if (path.equals("META") || path.startsWith("META.")) return Scope.CANONICAL_META;
        if (path.equals("CASE.STAGES") || path.startsWith("CASE.STAGES.") || path.startsWith("CASE.STAGES["))
            return Scope.LEGACY_STAGE_EVIDENCE;
        if (path.equals("TOOL") || path.startsWith("TOOL.") || path.startsWith("TOOL[")) return Scope.TRANSIENT_TOOL;
        if (path.equals("DB") || path.startsWith("DB.") || path.startsWith("DB[")) return Scope.TRANSIENT_DB;
        if (path.equals("output") || path.startsWith("output.") || path.startsWith("output[")) return Scope.ACTION_OUTPUT;
        if (path.startsWith("CASE.") || path.startsWith("CASE[") || path.startsWith("RUN.")
                || path.startsWith("RUN[") || path.startsWith("ACTIONS.") || path.startsWith("ACTIONS["))
            return Scope.LEGACY_ALIAS;
        return Scope.OTHER;
    }

    public static boolean isValidationValueAvailable(String path) {
        Scope scope = classify(path);
        return scope == Scope.CANONICAL_INPUT
                || (path != null && (path.startsWith("META.SOURCE.") || path.startsWith("META.SOURCE[")
                || path.startsWith("META.TARGET.") || path.startsWith("META.TARGET[")))
                || (path != null && path.startsWith("CASE.") && !isLegacyStageEvidence(path)
                && !"CASE.outputDirectory".equals(path));
    }

    public static boolean isRuntimeDependent(String path) {
        Scope scope = classify(path);
        if (scope == Scope.LEGACY_STAGE_EVIDENCE || scope == Scope.CANONICAL_ACTIONS
                || scope == Scope.TRANSIENT_TOOL || scope == Scope.TRANSIENT_DB
                || scope == Scope.ACTION_OUTPUT) return true;
        if (scope == Scope.LEGACY_ALIAS && path.startsWith("ACTIONS.")) return true;
        return "CASE.outputDirectory".equals(path)
                || (path != null && (path.startsWith("META.TEMPLATE.") || path.startsWith("META.FLOW.")
                || path.startsWith("META.TOOL.") || path.startsWith("META.DBHELPER.")
                || path.startsWith("META.MQHELPER.")));
    }

    public static boolean isLegacyStageEvidence(String path) {
        return classify(path) == Scope.LEGACY_STAGE_EVIDENCE;
    }

    public static String firstSegment(String path) {
        int dot = path.indexOf('.');
        int bracket = path.indexOf('[');
        int end = dot < 0 ? path.length() : dot;
        if (bracket >= 0 && bracket < end) end = bracket;
        return path.substring(0, end);
    }

    private static Set<String> roots(String... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(values)));
    }
}
