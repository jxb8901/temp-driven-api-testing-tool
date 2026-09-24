# ATT V3.5.1 Quick Start

[中文快速入门](quick-start.zh.md) · [Reference Manual](reference.html)

This guide gets you from a clean checkout to a successful ATT run with the smallest useful example. It deliberately teaches the normal Run workflow first. Debug, Load, DB/MQ, environments, retry, and the full Context model come later as next steps.

The checked-in Quick Start example is intentionally offline: the first case uses only `assign`, `log`, and `assert`. The second case adds ATT's local sample Tool without requiring a database, MQ server, API endpoint, credentials, or network access.

## 1. What you will run

ATT's normal authoring path is:

```text
Excel Testcase
   -> Stage
      -> Template
         -> ordered Actions
```

For this tutorial, the important files are already in the repository:

```text
testcase/quick_start.xlsx
testcase/quick_start.yaml
testcase/quick_start.xml
templates/QUICK_START/template.yaml
config/config.yaml
```

The workbook contains two cases:

| Case | Purpose | External dependency |
|---|---|---|
| `quickStart.default.QS001` | first successful ATT run | none |
| `quickStart.default.QS002` | same flow plus a local sample Tool | none |

You do not need to understand every ATT schema before running them.

## 2. Prerequisites

ATT requires Java 8 or later. From the repository root, make the launcher executable on macOS/Linux if necessary:

```sh
chmod +x att.sh
```

Check the CLI:

```sh
./att.sh version
```

On Windows, use `att.bat` instead of `./att.sh` in the commands below.

## 3. Inspect the workbook and sidecar

Open `testcase/quick_start.xlsx`. The second header row contains the physical Excel column names used by the sidecar:

```text
Case ID | Tags | Name | Amount | Use Tool | Template | Expected
```

`testcase/quick_start.yaml` maps those columns into ATT input:

```yaml
schemaVersion: att-sidecar/v2.2
id: quickStart
excel:
  sheet: QuickStart
  headerRows: 2
  caseId: Case ID
  tags: Tags
  dataColumns: name=Name, amount=Amount, useTool=Use Tool(yaml), expected=Expected

stages:
  - key: main
    template: Template
    required: true
    onFailure: stop
    runWhen: normal
```

The `(yaml)` marker on `Use Tool` preserves Excel `true`/`false` as a Boolean instead of a string.

The generated `testcase/quick_start.xml` is the normalized snapshot ATT executes against. Do not edit it manually.

## 4. Understand the Template

Both rows select `QUICK_START`, implemented by `templates/QUICK_START/template.yaml`.

The first three useful ideas are enough for now:

```yaml
actions:
  captureAmount:
    type: assign
    name: quickStartAmount
    expression: "${EXEC.INPUT.amount}"

  showInput:
    type: log
    message: "Quick Start case ${META.SOURCE.caseId}: amount=${EXEC.VARS.quickStartAmount}"

  verifyAmount:
    type: assert
    assert: "#{${EXEC.VARS.quickStartAmount} == ${EXEC.INPUT.expected}}"
    expected: "${EXEC.INPUT.expected}"
    actual: "${EXEC.VARS.quickStartAmount}"
```

For this tutorial:

- `EXEC.INPUT` contains values materialized from the Testcase row;
- `EXEC.VARS` contains values explicitly created by `assign`;
- `${...}` reads/interpolates Context values;
- `#{...}` evaluates a typed expression.

That is enough Context knowledge for a first run. The complete model is in [Runtime and Context](reference/03_runtime_context.md) and [Expressions](reference/07_expressions.md).

## 5. Regenerate the snapshot

ATT keeps the Excel workbook and its normalized XML snapshot in sync. Run:

```sh
./att.sh snapshot --suite testcase/quick_start.xlsx
```

If you edit the workbook later, run the same command again and review the resulting XML diff before committing it.

## 6. Validate before execution

First validate the whole package:

```sh
./att.sh validate --package
```

Validation checks schemas, workbook/snapshot consistency, Template references, expressions, Tool/resource references, and other package contracts without executing the test.

For a normal package you should fix validation errors before running anything.

## 7. Run your first case

Run only the completely offline case:

```sh
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
```

The expected result is `PASS`.

The high-level status model is:

| Status | Meaning |
|---|---|
| `PASS` | execution completed and assertions passed |
| `FAIL` | execution completed but a business assertion failed |
| `ERROR` | execution/runtime/integration failure |
| `INVALID` | validation prevented execution |
| `SKIPPED` | execution was intentionally skipped |

You do not need the detailed aggregation rules yet; see [Validation and Diagnostics](reference/12_validation_diagnostics.md) when troubleshooting real suites.

## 8. Inspect the result

ATT writes each normal run below `output/<runId>/` and updates `output/latest-run.yaml` only for a completed run.

Start with these artifacts:

```text
output/<runId>/
  run.yaml
  ... case output ...
  ... case.log ...
```

Then generate or inspect the HTML report using the normal report workflow documented in [Results, Reports, and Evidence](reference/11_results_reports_evidence.md).

For the Quick Start case, `case.log` should include the line written by `showInput`, and the final assertion should compare the workbook's `Amount` with `Expected`.

## 9. See a controlled failure

A useful way to learn ATT is to make one business assertion fail without breaking the framework.

In `testcase/quick_start.xlsx`, change `QS001` `Expected` from `100` to `999`, then regenerate the snapshot:

```sh
./att.sh snapshot --suite testcase/quick_start.xlsx
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
```

The case should now be `FAIL`, not `ERROR`: ATT executed successfully, but the assertion was false.

Change the value back to `100` and regenerate the snapshot when finished.

## 10. Add a real Tool call

The same Template already contains one optional Tool Action:

```yaml
readDate:
  type: tool
  call: "#{sample.getAcDate()}"
  runWhen: "#{${EXEC.INPUT.useTool} == true}"
```

`QS001` has `Use Tool = false`, so this Action is skipped. `QS002` has `Use Tool = true`.

Run the second case:

```sh
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS002
```

This calls the checked-in `sample.getAcDate` Tool from `config/tools/sample.yaml`. It is a local command-backed example, so the tutorial remains offline.

The important mental model is:

```text
Testcase input -> Template Action -> Tool -> Action output/evidence
```

For complete Tool configuration, command-backed vs call-backed behavior, arguments, outputs, and evidence, use [Resources - Tool](reference/05_resources/tools.md).

## 11. Run the whole Quick Start workbook

Once both individual cases make sense, run them together:

```sh
./att.sh run --suite testcase/quick_start.xlsx
```

You now have the basic ATT authoring loop:

```text
edit Excel / Template
        |
        v
snapshot
        |
        v
validate
        |
        v
run
        |
        v
inspect log/report
```

That loop is the foundation for larger SIT/UAT packages.

## 12. What to learn next

Do not try to learn every ATT feature from this tutorial. Follow the Reference chapter that matches the task you are doing:

| I want to... | Read next |
|---|---|
| understand workbook, sidecar, snapshot, Template, Flow | [Test Authoring](reference/02_test_authoring.md) |
| understand `EXEC`, `META`, `EXEC.VARS`, `EXEC.ACTIONS`, `output` | [Runtime and Context](reference/03_runtime_context.md) |
| debug one Template/Flow/Tool without Excel | [Standalone Debug](reference/04_execution_modes/debug.md) |
| run load tests | [Load](reference/04_execution_modes/load.md) |
| call scripts/programs or framework-native Tools | [Tool](reference/05_resources/tools.md) |
| query/update a database, including query timeout/retry | [DBHelper](reference/05_resources/dbhelper.md) |
| send/receive/request MQ messages | [MQHelper](reference/05_resources/mqhelper.md) |
| switch SIT/UAT resource bindings | [Environment and Test Data](reference/06_environment_testdata.md) |
| use `${...}` and `#{...}` correctly | [Expressions](reference/07_expressions.md) |
| add assertion, timeout, retry, `runWhen`, `onFailure` | [Reliability and Execution Control](reference/08_reliability_execution_control.md) |
| look up commands and options | [CLI Reference](reference/10_cli.md) |
| troubleshoot `FAIL`, `ERROR`, `INVALID` | [Validation and Diagnostics](reference/12_validation_diagnostics.md) |
| integrate ATT into CI or package it | [CI, Packaging, and Operations](reference/13_ci_packaging_operations.md) |

For direct DB Actions specifically, remember the safety boundary: `query` may retry `ASSERTION`/`TIMEOUT`, while `update` supports `timeoutMs` but rejects automatic retry. See the [DBHelper Reference](reference/05_resources/dbhelper.md) for the full contract.

For field-by-field supported behavior, use the generated [ATT V3.5.1 Reference Manual](reference.html) rather than extending this tutorial into a second manual.
