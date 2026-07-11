# ATT System Design V1.3

**Version:** V1.3
**Status:** Implemented
**Source:** User review comments on V1.2 design
**Last Updated:** 2026-07-09

---

# 1. System Overview

ATT, short for Automated Testing Tool, provides a lightweight automation layer for API SIT/UAT testing.

V1.3 extends V1.2 by moving suite-specific testcase configuration into Test Suite Config sidecar files.

The key design changes are:

- Test Suite Config may define or override testcase columns, selected Test Case Template, report columns, and suite defaults.
- Stage definitions belong to the Test Case Template selected by the Excel suite sidecar rather than only to global `config.yaml`.
- Template action outputs may be saved as files and passed to tools by file path.
- `Invocation ID` is renamed to **Action ID**.
- Result workbook generation remains run-level, while report columns may be suite-specific.
- Tool calls may support variadic arguments while preserving file-based input as the recommended integration mode.

V1.3 still keeps the V1.2 principles:

- `${...}` references runtime context values.
- `#{...}` invokes tools.
- Actions execute in YAML order.
- External scripts and tools remain outside ATT business logic.

---

# 2. V1.3 Design Goals

## 2.1 Test-Suite-Owned Configuration

V1.2 puts most case model and stage configuration in global `config.yaml`.

V1.3 allows each Excel suite to provide its own sidecar config.

This is useful when different API families require different Excel columns or different stage flows.

Example:

- Payment transfer template needs `Debit Account`, `Credit Account`, `Amount`, `Currency`.
- Customer enquiry template needs `Customer ID`, `ID Type`, `Channel`.
- Batch upload template needs `Batch File`, `Control Total`, `Expected Accepted Count`.

ATT should not force all suites to share one global column model.

The Test Case Template remains useful as a reusable default model, but the suite sidecar is the clearest place for workbook-specific overrides.

## 2.2 Stage Model Follows Test Case Template

In V1.3, the Test Case Template selected by an Excel suite defines the stages.

Different Excel suites may select different Test Case Templates and therefore use different stage lists.

Example:

- `payment_transfer_cases`: `prepare`, `invoke`, `verify`, `cleanup`
- `payment_reversal_cases`: `prepare`, `invoke`, `verify`, `rollback`
- `customer_enquiry_cases`: `invoke`, `verify`

The selected Test Case Template tells ATT which shared stage templates to load and in what order.

If multiple cases use the same stage key, such as `invoke`, they share the same stage definition for that Test Case Template.

## 2.3 File-Based Action Outputs

Some action outputs are too long to pass as command-line strings or duplicate in case logs.

Examples:

- Rendered XML request
- Rendered SQL query
- Large JSON payload
- Large API response
- Diagnostic log snippets

V1.3 allows an action to save output to a file under:

```text
output/<RunID>/<CaseID>/<ActionID>/
```

Later actions and tools may reference the file path from context.

## 2.4 Action ID Naming

V1.2 used the term `Invocation ID`.

V1.3 standardizes the term as **Action ID** because every action can produce a context record, not only tool invocations.

The preferred context path is:

```text
${ACTIONS.<ActionID>.output}
${ACTIONS.<ActionID>.outputFile}
${ACTIONS.<ActionID>.rawOutput}
```

`TOOLS.<ActionID>` may remain as a compatibility alias for V1.2 templates.

## 2.5 Run-Level Result Workbook

V1.3 does not change the V1.2 result workbook directory or naming model.

The result workbook remains under the run directory:

```text
output/<RunID>/<suiteName>.result.xlsx
```

Suite sidecar config may override Test Case Template and global report columns.

Run history remains under:

```text
output/<RunID>/run.yaml
output/latest-run.yaml
```

---

# 3. Changes From V1.2

| Area | V1.2 | V1.3 |
|------|------|------|
| Testcase columns | Global config default | Suite sidecar may override, with Test Case Template defaults |
| Stage definition | Global stage list | Test Case Template selected by suite sidecar owns stage list |
| Template layout | `templates/stage/<template>` | `templates/<template>` by default, plus optional stage-level `templatePath` override |
| Invocation naming | `Invocation ID` | `Action ID` |
| Context path | `TOOLS.<InvocationId>` | `ACTIONS.<ActionID>`, with optional `TOOLS` alias |
| Render output | Usually stored in context/log | May be stored as file |
| Report workbook | Run-level workbook | Run-level workbook; report columns may be suite-specific |
| Tool arguments | Named arguments map | Named arguments plus optional `argv` |
| Failure behavior | Stage/template config | Default stop, overridable by template/action |

---

# 4. Terminology

| Term | Meaning |
|------|---------|
| Test Suite Config | Suite-local configuration for one Excel workbook, usually stored as `<suiteName>.yaml` beside `<suiteName>.xlsx` |
| Test Case Template | A reusable test model selected by Test Suite Config, defining default testcase columns, stage list, report columns, and defaults |
| Template Config | Configuration defined inside a Test Case Template or stage template |
| Stage | A named execution step owned by the selected Test Case Template |
| Stage Template | A shared template package referenced by a stage definition, containing ordered actions |
| Template Action | One ordered executable step inside a stage template |
| Action ID | Stable identifier for a template action and its runtime context record |
| Action Output File | File written by an action under the current case output directory |
| Variadic Arguments | Optional list-style arguments passed to a tool command |

---

# 5. Test Suite And Test Case Configuration

## 5.1 Test Suite Sidecar Config

V1.3 recommends placing testcase-related suite configuration beside the Excel workbook.

Recommended naming:

```text
testcase/payment_regression.xlsx
testcase/payment_regression.yaml
```

The sidecar config is the first place to look when reviewing how one Excel suite is parsed and executed.

It may define or override:

- Selected Test Case Template
- Testcase sheet name
- Testcase columns
- Suite-specific report columns
- Suite-specific execution defaults

Example:

`testcase/payment_regression.yaml`:

```yaml
testCaseTemplate:
  id: payment_transfer_cases
  name: Payment Transfer

testcase:
  sheet: TestCases
  columns:
    caseId: Case ID
    caseName: Case Name
    tags: Tags
    debitAccount: Debit Account
    creditAccount: Credit Account
    amount: Amount
    currency: Currency
    data: Case Data

stages:
  - key: prepare
    name: Prepare
    template: PAYMENT_PREPARE
    required: false
    onFailure: stop
  - key: invoke
    name: Invoke
    template: PAYMENT_INVOKE
    required: true
    onFailure: stop
  - key: verify
    name: Verify
    template: PAYMENT_VERIFY
    required: true
    onFailure: stop
  - key: cleanup
    name: Cleanup
    template: PAYMENT_CLEANUP
    required: false
    runWhen: always
    onFailure: continue

actionDefaults:
  onFailure: stop
  logOutput: summary

report:
  columns:
    result: Test Result
    durationMs: Duration(ms)
    actualResult: Actual Result
    caseLog: Case Log
    runTime: Run Time
```

By default, a stage `template` value resolves to:

```text
templates/<template>/template.yaml
```

Custom template path example:

```yaml
stages:
  - key: invoke
    name: Invoke
    templatePath: templates/custom/payment/invoke
    required: true
```

## 5.2 Test Case Template Package

A Test Case Template is a reusable test model selected by the suite config.

It is not the same thing as the Excel suite config.

Example layout:

```text
templates/
  testcase/
    payment_transfer_cases/
      template.yaml
  PAYMENT_PREPARE/
    template.yaml
  PAYMENT_INVOKE/
    template.yaml
    request.xml
  PAYMENT_VERIFY/
    template.yaml
    select_txn.sql
  PAYMENT_CLEANUP/
    template.yaml
```

The Test Case Template level `template.yaml` defines the Excel test model and stage model for this suite type.

Stage templates remain shared by default under:

```text
templates/<template>/template.yaml
```

An advanced project may point a stage to a custom path when it needs a different layout.

## 5.3 Configuration Priority

Configuration priority depends on the setting type.

For suite model settings, such as testcase columns, stage list, and report columns:

```text
suite sidecar config
  > Test Case Template config
  > global config suite binding
  > global config.yaml default
```

For stage execution settings, such as action defaults, output logging, timeout, and action-level behavior:

```text
stage template config
  > suite sidecar config
  > Test Case Template config
  > global config.yaml default
```

This means a stage template may override execution defaults, but it should not redefine the suite's stage list.

Global config still provides defaults so simple projects do not need to repeat common settings.

## 5.4 Global Config Binding

Global config may provide defaults and optional suite bindings.

`config/config.yaml`:

```yaml
templates:
  root: templates
  defaultTestCaseTemplate: payment_transfer_cases
```

Global config may still define suite bindings, but the sidecar file is the recommended suite-local override.

V1.3 does not support selecting different Test Case Templates per case row by default.

The suite sidecar selects one Test Case Template for the workbook, because the stage model is intended to be shared by all cases in the Excel suite.

If the sidecar and global suite binding do not specify a Test Case Template, ATT uses the configured default Test Case Template.

---

# 6. Stage Model Owned By Test Case Template

## 6.1 Stage Definition

In V1.3, stages are loaded from the Test Case Template selected by the Excel suite.

This means different Excel files can use different stage definitions.

It does not mean each individual case or each action template defines its own independent stage model.

Each stage defines:

| Field | Meaning |
|-------|---------|
| `key` | Required stable stage key used by ATT |
| `name` | Optional human-readable name; defaults to `key` |
| `template` | Logical stage template name, resolved under the default stage template root |
| `templatePath` | Optional explicit stage template path; higher priority than `template` |
| `required` | Whether the stage must exist |
| `onFailure` | Default behavior when this stage fails |
| `runWhen` | Whether to run normally, always, on failure, or on success |

## 6.2 Different Test Case Templates, Different Stages

Payment transfer Test Case Template:

```yaml
stages:
  - { key: prepare, template: PAYMENT_PREPARE, required: false }
  - { key: invoke, template: PAYMENT_INVOKE, required: true }
  - { key: verify, template: PAYMENT_VERIFY, required: true }
  - { key: cleanup, template: PAYMENT_CLEANUP, required: false, runWhen: always }
```

Customer enquiry Test Case Template:

```yaml
stages:
  - { key: invoke, template: CUSTOMER_ENQUIRY_INVOKE, required: true }
  - { key: verify, template: CUSTOMER_ENQUIRY_VERIFY, required: false }
```

Batch upload Test Case Template:

```yaml
stages:
  - { key: prepareFile, template: BATCH_PREPARE_FILE, required: true }
  - { key: upload, template: BATCH_UPLOAD, required: true }
  - { key: verifyBatch, template: BATCH_VERIFY, required: true }
  - { key: rollback, template: BATCH_ROLLBACK, required: false, runWhen: onFailure }
```

## 6.3 Stage Template Resolution

Given:

```yaml
templates:
  root: templates
  defaultTestCaseTemplate: payment_transfer_cases
```

and:

```yaml
stages:
  - key: invoke
    template: PAYMENT_INVOKE
```

ATT loads:

```text
templates/PAYMENT_INVOKE/template.yaml
```

If `templatePath` is configured, it has higher priority:

```yaml
stages:
  - key: invoke
    templatePath: templates/custom/payment/invoke
```

ATT then loads:

```text
templates/custom/payment/invoke/template.yaml
```

This keeps the default path concise when template names already include stage meaning, while allowing explicit custom paths.

## 6.4 Shared Stage Template Definition

Within one Test Case Template, a stage key maps to one shared stage definition.

Example:

```yaml
stages:
  - key: invoke
    template: PAYMENT_INVOKE
```

All cases using this Test Case Template and stage key `invoke` use the same stage definition.

By default, that stage definition loads:

```text
templates/PAYMENT_INVOKE/template.yaml
```

Case data can differ per row, but the stage definition and resolved stage template are shared.

This gives ATT two useful properties:

- Each Excel suite can have its own stage flow.
- Cases with the same stage key run the same stage definition and resolved stage template.

---

# 7. Excel Test Suite Model With Template-Specific Columns

## 7.1 Column Resolution

ATT resolves testcase columns after selecting the Test Case Template for the Excel suite.

Resolution flow:

```text
Load global config
  -> Load suite
  -> Load suite sidecar config if testcase/<suiteName>.yaml exists
  -> Determine Test Case Template
  -> Load Test Case Template config
  -> Merge Test Case Template defaults
  -> Apply suite sidecar overrides
  -> Resolve testcase columns
  -> Parse case rows
```

Suite sidecar columns override Test Case Template defaults and global defaults.

Test Case Template columns provide the suite model default for the selected test type.

Unconfigured Excel columns are ignored.

## 7.2 Suite Sidecar Example

For:

```text
testcase/payment_regression.xlsx
```

ATT looks for:

```text
testcase/payment_regression.yaml
```

Example:

```yaml
testCaseTemplate: payment_transfer_cases

testcase:
  sheet: TestCases
  columns:
    caseId: Case ID
    caseName: Case Name
    tags: Tags
    debitAccount: Debit Account
    creditAccount: Credit Account
    amount: Amount
    currency: Currency
    data: Case Data
```

## 7.3 Test-Case-Template-Specific Example

Payment transfer:

| Case ID | Debit Account | Credit Account | Amount | Currency | Case Data |
|---------|---------------|----------------|--------|----------|-----------|
| TC001 | 111111 | 222222 | 100 | HKD | expected: ... |

Customer enquiry:

| Case ID | Customer ID | ID Type | Channel | Case Data |
|---------|-------------|---------|---------|-----------|
| TC101 | C12345 | HKID | MOBILE | expected: ... |

These suites can use different column definitions without changing the global config.

---

# 8. Template Action Model

## 8.1 Ordered Actions

Actions are executed in YAML order.

Example:

```yaml
actions:
  renderRequest:
    type: render
    payload: request.xml
    saveAs: request.xml

  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile})}"

  checkStatus:
    type: assert
    expression: "${ACTIONS.callApi.output.Response.Status} == '${expected.status}'"
```

The YAML key is the default Action ID.

An action may also define an explicit `id`:

```yaml
actions:
  step1:
    id: renderRequest
    type: render
    payload: request.xml
```

## 8.2 Supported Action Types

| Type | Purpose |
|------|---------|
| `render` | Render payload file or inline content |
| `tool` | Invoke a configured tool |
| `assert` | Evaluate an expression |
| `log` | Write structured message to case log |

## 8.3 Action ID Rules

Action IDs must be unique within one case execution.

If a Test Case Template uses the same stage template multiple times, the stage template should prefix or explicitly set action IDs.

Recommended naming:

```text
<stageKey>.<actionName>
```

Example:

```yaml
actions:
  renderRequest:
    id: invoke.renderRequest
    type: render
```

The context path uses the Action ID exactly as defined.

---

# 9. Action Output Files

## 9.1 Output Directory

When an action writes output to a file, ATT restricts the file to:

```text
output/<RunID>/<CaseID>/<ActionID>/
```

Example:

```text
output/20260709-193000/TC001/invoke.renderRequest/request.xml
```

This prevents templates from writing outside the case output directory.

## 9.2 Render Action Save Example

`templates/PAYMENT_INVOKE/template.yaml`:

```yaml
actions:
  renderRequest:
    id: invoke.renderRequest
    type: render
    payload: request.xml
    saveAs: request.xml
    output:
      mode: file
      log: summary
```

`templates/PAYMENT_INVOKE/request.xml`:

```xml
<PaymentTransferRequest>
  <CaseId>${CaseID}</CaseId>
  <DebitAccount>${Debit Account}</DebitAccount>
  <CreditAccount>${Credit Account}</CreditAccount>
  <Amount>${Amount}</Amount>
  <Currency>${Currency}</Currency>
</PaymentTransferRequest>
```

Runtime context:

```text
${ACTIONS.invoke.renderRequest.outputFile}
```

may resolve to:

```text
output/20260709-193000/TC001/invoke.renderRequest/request.xml
```

## 9.3 Passing Output File To Tool

```yaml
actions:
  callApi:
    id: invoke.callApi
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.invoke.renderRequest.outputFile})}"
```

The API tool reads the XML from file instead of receiving a long XML string.

This avoids:

- Command-line length limits
- Shell escaping problems
- Large duplicated XML in the case log

## 9.4 Case Log For File Outputs

For file-based outputs, case log should record:

```yaml
actionId: invoke.renderRequest
type: render
status: PASS
outputFile: output/20260709-193000/TC001/invoke.renderRequest/request.xml
outputBytes: 4921
outputSha256: 30cf...
outputPreview: "<PaymentTransferRequest>..."
durationMs: 12
```

The full output content should not be duplicated in the case log unless explicitly configured.

---

# 10. Case Runtime Context

## 10.1 Context Root

V1.3 context includes:

| Root | Purpose |
|------|---------|
| `CASE` | Case metadata and parsed testcase data |
| `RUN` | Run ID, run directory, output root |
| `STAGE` | Current stage metadata |
| `ACTIONS` | Action records by Action ID |
| `TOOLS` | Optional V1.2 compatibility alias |

## 10.2 Action Record

Each action writes one record:

```yaml
ACTIONS:
  invoke.renderRequest:
    actionId: invoke.renderRequest
    type: render
    status: PASS
    outputFile: output/20260709-193000/TC001/invoke.renderRequest/request.xml
    output: null
    rawOutput: null
    durationMs: 12
```

Tool action example:

```yaml
ACTIONS:
  invoke.callApi:
    actionId: invoke.callApi
    type: tool
    tool: invokePaymentApi
    input:
      requestFile: output/20260709-193000/TC001/invoke.renderRequest/request.xml
    output:
      Response:
        Status: SUCCESS
    rawOutput: "<Response><Status>SUCCESS</Status></Response>"
    outputFile: output/20260709-193000/TC001/invoke.callApi/response.xml
    status: PASS
    durationMs: 208
```

## 10.3 Compatibility Alias

For V1.2 migration, ATT may expose:

```text
${TOOLS.<ActionID>.output}
```

as an alias to:

```text
${ACTIONS.<ActionID>.output}
```

New V1.3 templates should use `ACTIONS`.

---

# 11. Failure Handling

## 11.1 Default Behavior

Actions execute in order.

Default rule:

```text
If an action fails or an assert action evaluates to false, stop the current template and mark the stage as failed.
```

The case result is failed unless a later recovery rule changes the final result.

## 11.2 Action-Level Override

A non-critical action may continue on failure.

Example:

```yaml
actions:
  collectLogs:
    type: tool
    call: "#{getAppLogs(caseId=${CaseID})}"
    onFailure: continue
```

If `collectLogs` fails, ATT records the action as failed but continues to the next action.

## 11.3 Template-Level Default

A stage template may define an action default:

```yaml
config:
  actionDefaults:
    onFailure: stop
```

Individual actions may override this default.

## 11.4 Cleanup And Rollback Stages

Cleanup and rollback behavior is stage-level behavior.

Example:

```yaml
stages:
  - key: cleanup
    template: PAYMENT_CLEANUP
    runWhen: always
    onFailure: continue
  - key: rollback
    template: PAYMENT_ROLLBACK
    runWhen: onFailure
    onFailure: continue
```

If the invoke or verify stage fails, ATT may still run rollback and cleanup according to `runWhen`.

---

# 12. Log Action

## 12.1 Purpose

`log` is a first-class template action in V1.3.

It records checkpoints, selected context values, and business-readable progress messages.

It should not replace tool stdout/stderr capture.

## 12.2 Log Action Example

```yaml
actions:
  logRenderedRequest:
    type: log
    level: INFO
    message: "Rendered request for ${CaseID}"
    fields:
      actionId: invoke.renderRequest
      requestFile: "${ACTIONS.invoke.renderRequest.outputFile}"
      amount: "${Amount}"
      currency: "${Currency}"
```

Case log output:

```yaml
actionId: logRenderedRequest
type: log
level: INFO
message: "Rendered request for TC001"
fields:
  actionId: invoke.renderRequest
  requestFile: output/20260709-193000/TC001/invoke.renderRequest/request.xml
  amount: "100"
  currency: HKD
status: PASS
```

## 12.3 Log Levels

Recommended levels:

| Level | Meaning |
|-------|---------|
| `DEBUG` | Detailed diagnostics |
| `INFO` | Normal progress |
| `WARN` | Non-blocking warning |
| `ERROR` | Error context |

---

# 13. Tool Argument Model

## 13.1 Named Arguments

V1.3 keeps named arguments.

Example:

```yaml
tools:
  invokePaymentApi:
    command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
    arguments:
      requestFile: "${TOOL.input.requestFile}"
      environment: "${environment}"
```

Tool action:

```yaml
actions:
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.invoke.renderRequest.outputFile})}"
```

Named arguments are still written into the tool input file.

## 13.2 Variadic Arguments

Some existing tools expect positional or variable-length CLI arguments.

V1.3 may add `argv`:

```yaml
tools:
  grepLogs:
    command: "./tools/grep_logs.sh ${TOOL.argv}"
    output: yaml
    argv:
      - "--file"
      - "${TOOL.input.logFile}"
      - "--keyword"
      - "${TOOL.input.keyword1}"
      - "--keyword"
      - "${TOOL.input.keyword2}"
```

Tool action:

```yaml
actions:
  grepPaymentLogs:
    type: tool
    call: "#{grepLogs(logFile=${ACTIONS.collectLogs.outputFile}, keyword1=PAYMENT, keyword2=POSTED)}"
```

## 13.3 Safety Rule

File-based input remains the recommended integration mode.

`argv` should be used for:

- Short flags
- Existing CLI tools
- Values that do not contain large payloads

Large XML, SQL, JSON, or log content should be passed by file path.

The recommended implementation is to execute tools with an argv array rather than concatenating arguments into a shell string.

If the runtime still renders `${TOOL.argv}` into a shell command string, it must quote each argv item independently to avoid shell injection issues.

---

# 14. Case Execution Output Layout

V1.3 output layout:

```text
output/
  <RunID>/
    run.yaml
    <suiteName>.result.xlsx
    <CaseID>/
      <CaseID>.<yyyyMMdd>.<HHmmss>.<seq>.log
      <ActionID>/
        input.yaml
        output.xml
        request.xml
        response.xml
```

Action directories are created only when the action writes files.

Examples:

```text
output/20260709-193000/TC001/invoke.renderRequest/request.xml
output/20260709-193000/TC001/invoke.callApi/response.xml
output/20260709-193000/payment_regression.result.xlsx
```

---

# 15. Result Workbook

## 15.1 Result Path

V1.3 keeps the V1.2 result workbook path:

```text
output/<RunID>/<suiteName>.result.xlsx
```

Example:

```text
output/20260709-193000/payment_regression.result.xlsx
```

## 15.2 Workbook Content

The result workbook should be copied from the source Excel workbook, then append configured result columns.

Suite sidecar report config may override Test Case Template and global report columns, but it does not change the default run-level output location.

Recommended columns:

| Field | Example Header |
|-------|----------------|
| `result` | Test Result |
| `durationMs` | Duration(ms) |
| `actualResult` | Actual Result |
| `caseLog` | Case Log |
| `failedStage` | Failed Stage |
| `failedAction` | Failed Action |
| `runTime` | Run Time |

The source Excel workbook should not be overwritten.

## 15.3 Run Summary

Run-level summary remains machine-readable:

```text
output/<RunID>/run.yaml
output/latest-run.yaml
```

`run.yaml` should include the result workbook path and each case log path.

---

# 16. Configuration Examples

## 16.1 Global Config Defaults

```yaml
outputDirectory: output
environment: SIT
timeoutSeconds: 120

templates:
  root: templates
  defaultTestCaseTemplate: payment_transfer_cases

testcase:
  sheet: TestCases
  columns:
    caseId: Case ID
    caseName: Case Name
    tags: Tags

report:
  columns:
    result: Test Result
    durationMs: Duration(ms)
    actualResult: Actual Result
    caseLog: Case Log
```

## 16.2 Suite Sidecar Config

`testcase/payment_regression.yaml`:

```yaml
testCaseTemplate: payment_transfer_cases

testcase:
  sheet: TestCases
  columns:
    caseId: Case ID
    caseName: Case Name
    tags: Tags
    debitAccount: Debit Account
    creditAccount: Credit Account
    amount: Amount
    currency: Currency
    data: Case Data

report:
  columns:
    result: Test Result
    durationMs: Duration(ms)
    actualResult: Actual Result
    caseLog: Case Log
```

## 16.3 Payment Transfer Test Case Template

```yaml
testCaseTemplate:
  id: payment_transfer_cases

testcase:
  columns:
    debitAccount: Debit Account
    creditAccount: Credit Account
    amount: Amount
    currency: Currency
    data: Case Data

stages:
  - { key: prepare, template: PAYMENT_PREPARE, required: false }
  - { key: invoke, template: PAYMENT_INVOKE, required: true }
  - { key: verify, template: PAYMENT_VERIFY, required: true }
  - { key: cleanup, template: PAYMENT_CLEANUP, required: false, runWhen: always }
```

## 16.4 Customer Enquiry Test Case Template

```yaml
testCaseTemplate:
  id: customer_enquiry_cases

testcase:
  columns:
    customerId: Customer ID
    idType: ID Type
    channel: Channel
    data: Case Data

stages:
  - { key: invoke, template: CUSTOMER_ENQUIRY_INVOKE, required: true }
  - { key: verify, template: CUSTOMER_ENQUIRY_VERIFY, required: false }
```

## 16.5 Stage Template With Log And File Output

```yaml
config:
  actionDefaults:
    onFailure: stop

actions:
  renderRequest:
    id: invoke.renderRequest
    type: render
    payload: request.xml
    saveAs: request.xml
    output:
      mode: file
      log: summary

  logRenderedRequest:
    type: log
    level: INFO
    message: "Rendered request for ${CaseID}"
    fields:
      requestFile: "${ACTIONS.invoke.renderRequest.outputFile}"

  callApi:
    id: invoke.callApi
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.invoke.renderRequest.outputFile})}"

  checkStatus:
    id: verify.checkStatus
    type: assert
    expression: "${ACTIONS.invoke.callApi.output.Response.Status} == '${expected.status}'"
```

---

# 17. Migration From V1.2

## 17.1 Concept Mapping

| V1.2 | V1.3 |
|------|------|
| Global stage config | Test Case Template stage config selected by Excel suite |
| Global-only suite settings | Suite sidecar config such as `testcase/payment_regression.yaml` may override global defaults |
| `templates/stage/<template>` | `templates/<template>` by default; `templatePath` may override |
| Invocation ID | Action ID |
| `${TOOLS.<id>.output}` | `${ACTIONS.<id>.output}` |
| Run-level result workbook | Run-level result workbook, with suite-specific report columns |
| Long rendered output in log/context | File output plus log summary |

## 17.2 Compatibility

V1.3 may keep V1.2 compatibility for:

- `TOOLS.<ActionID>` as an alias of `ACTIONS.<ActionID>`
- Global `stages` as a default Test Case Template fallback
- V1.2 `templates/stage/<template>` stage template convention as a compatibility option
- V1.2 run-level report workbook path and naming

New templates should prefer:

- Suite-local sidecar config placed next to the Excel workbook
- Test Case Template config
- Test-Case-Template-owned stages
- Stage-level `template` or `templatePath` references
- `ACTIONS` context root
- File-based outputs for large payloads

---

# 18. Acceptance Criteria

V1.3 design is considered complete when the following statements are true:

- Suite sidecar config is documented as `<suiteName>.yaml` placed beside `<suiteName>.xlsx`.
- Suite sidecar config can override global `config.yaml` defaults for the selected Excel suite.
- Suite model priority is documented as suite sidecar config higher than Test Case Template config, then global suite binding, then global default.
- Stage execution priority is documented as stage template config higher than suite sidecar config, then Test Case Template config, then global default.
- Stage ownership is moved from global config to the Test Case Template selected by an Excel suite.
- Different Excel suites can select different Test Case Templates and therefore use different testcase columns and stage lists.
- Cases using the same Test Case Template and same stage key share the same stage definition.
- Stage template resolution supports `template`, defaulting to `templates/<template>/template.yaml`.
- Stage template resolution supports explicit `templatePath` override.
- Stage `key` is required; stage `name` is optional and defaults to `key`.
- `Invocation ID` is renamed to `Action ID`.
- `${ACTIONS.<ActionID>...}` is the preferred context reference.
- `${TOOLS.<ActionID>...}` is documented only as an optional V1.2 compatibility alias.
- `render` action can write output to a file.
- Action output files are constrained to `output/<RunID>/<CaseID>/<ActionID>/`.
- Case log behavior for large file outputs records path, size, hash, preview, duration, and status instead of duplicating full content.
- Actions execute in YAML order.
- Default failure behavior stops the current template on action failure or failed assert.
- `onFailure: continue` can override the default at action or template level.
- Cleanup and rollback stages are controlled by stage `runWhen`.
- `log` is defined as a first-class action type with `level`, `message`, and optional `fields`.
- The report workbook remains run-level, for example `output/<RunID>/<suiteName>.result.xlsx`.
- Tool arguments support existing named arguments and optional variadic `argv`.
- Variadic `argv` execution prefers argv array execution; shell-string rendering must quote each item independently.
- File-based tool input remains the recommended mode for large payloads.
