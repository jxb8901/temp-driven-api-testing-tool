# ATT Roadmap V1.4

**Version:** Draft
**Status:** Proposed
**Last Updated:** 2026-07-09

---

# 1. Summary

V1.4 is the stabilization release for the current ATT runtime.

The goal is to make the existing V1.3 model easier to operate, validate, and support without changing the execution model again.

V1.4 should focus on:

- correctness
- validation
- clearer error handling
- compatibility cleanup
- test coverage
- required sidecar presence
- explicit workbook-to-template binding
- Chinese Excel workbook support
- Chinese column name mapping support
- multi-row Excel header support
- shared `Case Data` with stage-specific overlays
- log level driven detail filtering for case, stage, and action diagnostics
- tool retry configuration with tool defaults and action overrides

It should not introduce a new runtime model.

---

# 2. Design Direction

## 2.1 Keep

- Suite sidecar config beside each workbook, required for every Excel suite
- Test Case Template ownership of stage definitions
- Ordered template actions
- `ACTIONS.<ActionID>` as the preferred context path
- `TOOLS.<ActionID>` as a legacy alias
- File-based outputs for large artifacts
- Existing release packaging model
- Unicode-safe workbook, sheet, and column name handling

## 2.2 Simplify

- Remove `config.yaml` assumptions about which Test Case Template an Excel workbook should use
- Require the workbook sidecar to declare the Test Case Template explicitly
- Treat missing sidecar config as an error instead of falling back to a global default
- Treat column mapping as an explicit workbook-side concern, not an English-only framework default

## 2.3 Tighten

- Template search order
- Fallback behavior for legacy paths
- Validation of missing or invalid stage keys
- Validation of missing or invalid action IDs
- Validation of missing tool arguments
- Clearer error messages when a template file or config field cannot be resolved
- `arguments` should be documented as a lightweight parameter contract for supported parameter names, required flags, and defaults
- Retry policy should be documented as part of the tool execution contract

## 2.4 Improve

- Case log readability
- Log level controls for case/stage/action detail
- Retry diagnostics such as attempt count and retry delay visibility
- Run history inspection
- Result workbook trace fields
- Template and tool-level unit coverage
- Chinese workbook examples and documentation

## 2.5 Localization Rules

- Excel workbook file names may be Chinese or mixed-language
- Worksheet names may be Chinese or mixed-language
- Display column headers may be Chinese or mixed-language
- Workbook header rows may span multiple lines
- `testcase.headerRows` should default to `1` and may be raised for stacked or merged headers
- The effective column label should be resolved from the last non-empty cell within each header column
- `testcase.columns` mapping must support Chinese source headers and Chinese target fields
- Mapping resolution must be based on exact configured header text, not translated assumptions
- Framework internals should continue to use stable logical keys while accepting localized display names at the workbook boundary
- Template cell values may span multiple lines, with the first line used as the template name and the remaining lines treated as remark text
- Template names may be Chinese or mixed-language
- Remark text should be injected into case context separately and may be emitted into the case log
- `Case Data` should be treated as shared base data, with stage-specific data able to override shared keys inside that stage
- Log levels should act as a detail filter over the same case log path, not as separate logging backends
- Tool `arguments` should describe common supported parameters, while actual runtime values continue to come from template/context input
- `argv` should remain the variadic command-line channel and should not be conflated with the parameter contract in `arguments`
- Tool retry should support a default policy in the tool definition and an override policy at action call sites

## 2.6 Tool Argument Contract

In V1.4, `tools.<toolName>.arguments` should be treated as a lightweight parameter contract rather than a runtime value map.

It should describe:

- supported parameter names
- whether the parameter is required
- optional default value

The recommended compact syntax is:

- `arg1`
- `arg2*`
- `arg3='${CaseID}'`

In this syntax:

- `name` means an optional known parameter
- `name*` means a required parameter
- `name=value` means an optional parameter with a default value
- undeclared parameters may still appear in runtime calls

Runtime values should still come from template calls and resolved context.

Example:

```yaml
tools:
  invokePaymentApi:
    command: "./tools/invoke_payment_api.sh ${TOOL.argv} --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
    arguments: "requestFile*, environment='SIT', traceId"
    argv:
      - "--trace-id"
      - "${traceId}"
```

In this model:

- `arguments` declares common supported parameters
- template/context provides actual values
- `argv` remains the variadic command-line channel
- `arguments` and `argv` should not be treated as the same layer
- undeclared parameters are still allowed in tool calls

## 2.7 Tool Retry Contract

In V1.4, tool execution should support retry configuration as part of the execution contract.

The minimum retry policy should include:

- `count`
- `delayMs`

Retry should be defined in two layers:

- tool definition default policy
- action call override policy

Retry should apply only to tool execution attempts. It should not imply rerunning the whole case or rerunning the whole stage.

Example:

```yaml
tools:
  invokePaymentApi:
    command: "./tools/invoke_payment_api.sh ${TOOL.argv} --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
    retry:
      count: 2
      delayMs: 1000
    arguments: "requestFile*, environment='SIT'"

actions:
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile})}"
    retry:
      count: 4
      delayMs: 2000
```

In this model:

- tool config provides the default retry policy
- a specific action may override retry when one call site needs different tolerance
- retry should be logged with attempt count, final status, and applied delay
- timeout and retry are separate controls and should not be treated as the same field

---

# 3. V1.4 Checklist

1. Define the final compatibility policy for:
   - `TOOLS.<ActionID>`
   - legacy template paths
   - legacy stage naming
2. Add schema-like validation for:
   - suite sidecar config
   - test case template config
   - stage definitions
   - action definitions
3. Add tests for:
   - template resolution priority
   - action output file path creation
   - argv handling
   - retry default and override behavior
   - report column mapping
   - legacy alias behavior
4. Improve diagnostics for:
   - missing config keys
   - missing templates
   - invalid stage order
   - unsupported action types
   - retry exhaustion and retry attempt visibility
5. Document the supported V1.4 contract in README and system design docs
6. Add Chinese workbook and column mapping examples to the documentation

---

# 4. Non-Goals

V1.4 should not introduce:

- parallel execution
- dependency graphs between cases or actions
- conditional branching as a core runtime feature
- plugin architecture
- new persistent service or database
- major report backend changes
