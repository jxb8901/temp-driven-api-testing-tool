package att.validation;

/** Authoritative stable V2.1 diagnostic code catalog used by producers. */
public final class DiagnosticCodes {
    public static final String CLI_INVALID = "ATT-CLI-001";
    public static final String CONFIG_INVALID = "ATT-CFG-001";
    public static final String TESTCASE_INVALID = "ATT-TC-001";
    public static final String SELECTION_EMPTY = "ATT-TC-EMPTY";
    public static final String STAGE_INVALID = "ATT-STG-001";
    public static final String TEMPLATE_INVALID = "ATT-TPL-001";
    public static final String CONTEXT_INVALID = "ATT-CTX-001";
    public static final String CONTEXT_AMBIGUOUS = "ATT-CTX-002";
    /** Migration diagnostics introduced by the 3.4.2 Context contract. */
    public static final String CONTEXT_LEGACY_PATH = "CONTEXT_LEGACY_PATH";
    public static final String CONTEXT_CROSS_SCOPE = "CONTEXT_CROSS_SCOPE";
    public static final String CONTEXT_TOOL_INPUT_SHORTHAND = "CONTEXT_TOOL_INPUT_SHORTHAND";
    public static final String BUILTIN_INVALID = "ATT-BUILTIN-001";
    public static final String ASSERTION_FAILED = "ATT-ASSERT-001";
    public static final String TOOL_INVALID = "ATT-TOOL-001";
    public static final String TOOL_EXECUTION = "ATT-TOOL-002";
    public static final String PATH_INVALID = "ATT-PATH-001";
    public static final String RUN_FAILED = "ATT-RUN-001";
    public static final String PACKAGE_INVALID = "ATT-PKG-001";
    public static final String SELECTED_SCOPE = "ATT-PKG-SELECTED";
    public static final String DEBUG_INVALID = "ATT-DEBUG-001";
    public static final String LOAD_INVALID = "ATT-LOAD-001";
    private DiagnosticCodes() {}
}
