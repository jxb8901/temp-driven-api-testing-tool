# ATT v3.8.0 quick start

[中文快速入門](quick-start.zh.md) · [Reference Manual](reference.html)

This guide gets you from a clean checkout to a successful ATT run with the smallest useful example. It deliberately teaches the normal Run workflow first. Debug, Load, DB/MQ, environments, retry, and the full Context model come later as next steps.

Here, **Testcase** means an authored normalized workbook row. A **Case execution** is one Run of that row. They share a Case ID but describe different things; this guide uses Testcase for workbook data and Case execution for runtime results.

The checked-in Quick Start example is intentionally offline: the first Testcase uses only `assign`, `log`, and `assert`. The second Testcase adds ATT's local sample Tool without requiring a database, MQ server, API endpoint, credentials, or network access.

## What you will run

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

The workbook contains two Testcases:

| Testcase ID | Purpose | External dependency |
|---|---|---|
| `quickStart.default.QS001` | first successful ATT run | none |
| `quickStart.default.QS002` | same flow plus a local sample Tool | none |

You do not need to understand every ATT schema before running them.

## Check prerequisites

ATT requires Java 8 or later. The source checkout launcher also needs Maven for its first compile; when Maven is unavailable, `javac` and the ATT dependency jars in the local Maven cache must be present. Packaged releases do not need Maven. From the repository root:

1. On macOS/Linux, make the launcher executable if necessary:
   ```sh
   chmod +x att.sh
   ```

2. Check the CLI:

   ```sh
   ./att.sh version
   ```

   On Windows, use `att.bat` instead of `./att.sh`.

## Inspect the workbook and sidecar

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

## Understand the Template

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

That is enough Context knowledge for a first run. The complete model is in [Runtime and Context](reference/runtime-context.md) and [Expressions](reference/expressions.md).

## Prepare the package

ATT keeps the Excel workbook and normalized XML snapshot in sync:

1. Generate the snapshot:
   ```sh
   ./att.sh snapshot --suite testcase/quick_start.xlsx
   ```

   If you edit the workbook later, regenerate the snapshot and review its XML diff before committing it.

2. Validate the package before executing it:

   ```sh
   ./att.sh validate --package
   ```

   Validation checks schemas, workbook/snapshot consistency, Template references, expressions, Tool/resource references, and other package contracts without executing a Testcase. Resolve validation errors before the first Case execution.

## Run the offline Testcase

1. Start a Case execution for the offline Testcase:

   ```sh
   ./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
   ```

### Expected result

The Case execution should be `PASS`.

The high-level status model is:

| Status | Meaning |
|---|---|
| `PASS` | execution completed and assertions passed |
| `FAIL` | execution completed but a business assertion failed |
| `ERROR` | execution/runtime/integration failure |
| `INVALID` | validation prevented execution |
| `SKIPPED` | execution was intentionally skipped |

You do not need the detailed aggregation rules yet; see [Validation and Troubleshooting](reference/validation-diagnostics.md) when troubleshooting real suites.

## Inspect the Case execution

ATT writes each normal run below `output/<runId>/` and updates `output/latest-run.yaml` only for a completed run.

1. Open the run directory and inspect its summary and execution artifacts:

```text
output/<runId>/
  run.yaml
  ... case output ...
  ... case.log ...
```

2. Open or regenerate the HTML report using [Results, Reports, and Evidence](reference/results-reports-evidence.md).

For the `QS001` Case execution, `case.log` should include the line written by `showInput`, and the final assertion should compare the workbook's `Amount` with `Expected`.

## Create and restore a controlled failure

A useful way to learn ATT is to make one business assertion fail without breaking the framework.

1. In `testcase/quick_start.xlsx`, change `QS001` `Expected` from `100` to `999` and regenerate the snapshot:

   ```sh
   ./att.sh snapshot --suite testcase/quick_start.xlsx
   ```

2. Run that Testcase again:

   ```sh
   ./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
   ```

### Expected result

The Case execution should be `FAIL`, not `ERROR`: ATT completed the execution, but the business assertion was false.

3. Restore `Expected` to `100` and regenerate the snapshot.

## Add a real Tool call

The same Template already contains one optional Tool Action:

```yaml
readDate:
  type: tool
  call: "#{sample.getAcDate()}"
  runWhen: "#{${EXEC.INPUT.useTool} == true}"
```

`QS001` has `Use Tool = false`, so this Action is skipped. `QS002` has `Use Tool = true`.

1. Start a Case execution for `QS002`:

   ```sh
   ./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS002
   ```

This calls the checked-in `sample.getAcDate` Tool from `config/tools/sample.yaml`. It is a local command-backed example, so the tutorial remains offline.

### Expected result

The `QS002` Case execution should be `PASS`, with the Tool result in the execution evidence.

The important mental model is:

```text
Testcase input -> Template Action -> Tool -> Action output/evidence
```

For complete Tool configuration, command-backed vs call-backed behavior, arguments, outputs, and evidence, use [Resources - Tool](reference/resources/tools.md).

## Run the whole Quick Start workbook

Once both Testcases make sense, run them together:

1. Start a Case execution for every Testcase in the workbook:

   ```sh
   ./att.sh run --suite testcase/quick_start.xlsx
   ```

### Expected result

The Case executions for both Testcases should have `PASS` status after you restore `QS001`'s `Expected` value.

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

## What to learn next

Do not try to learn every ATT feature from this tutorial. Follow the Reference page that matches the task you are doing:

| I want to... | Read next |
|---|---|
| understand workbook, sidecar, snapshot, Template, Flow | [Test Authoring](reference/test-authoring.md) |
| understand `EXEC`, `META`, `EXEC.VARS`, `EXEC.ACTIONS`, `output` | [Runtime and Context](reference/runtime-context.md) |
| debug one Template/Flow/Tool without Excel | [Standalone Debug](reference/execution-modes/debug.md) |
| run load tests | [Load](reference/execution-modes/load.md) |
| call scripts/programs or framework-native Tools | [Tool](reference/resources/tools.md) |
| query/update a database, including query timeout/retry | [DBHelper](reference/resources/dbhelper.md) |
| send/receive/request MQ messages | [MQHelper](reference/resources/mqhelper.md) |
| switch SIT/UAT resource bindings | [Configuration and Environments](reference/configuration.md) |
| use `${...}` and `#{...}` correctly | [Expressions](reference/expressions.md) |
| add assertion, timeout, retry, `runWhen`, `onFailure` | [Reliability and Execution Control](reference/reliability-execution-control.md) |
| look up commands and options | [CLI Reference](reference/cli.md) |
| troubleshoot `FAIL`, `ERROR`, `INVALID` | [Validation and Troubleshooting](reference/validation-diagnostics.md) |
| integrate ATT into CI or package it | [CI, Packaging, and Operations](reference/ci-packaging-operations.md) |

For direct DB Actions specifically, remember the safety boundary: `query` may retry `ASSERTION`/`TIMEOUT`, while `update` supports `timeoutMs` but rejects automatic retry. See the [DBHelper Reference](reference/resources/dbhelper.md) for the full contract.

For field-by-field supported behavior, use the generated [ATT V3.8.0 Reference Manual](reference.html) rather than extending this tutorial into a second manual.
