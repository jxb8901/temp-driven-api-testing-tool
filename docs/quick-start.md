# ATT v4.6.0 quick start

[中文快速入門](quick-start.zh.md) · [Reference Manual](reference.md) · [Migration matrix](maintenance/native-case-default-package-migration-s12.md)

Maintaining a Package authored with an earlier model? See the [Legacy documentation](legacy/README.md).

This guide follows the Native Case workflow on the current default branch. No 4.6.0 release or tag has been published. Inline and Excel Native RUN are integrated here. Issue [#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393) is closed for Native RUN acceptance; that closure is not a product release. Default Package adoption remains open under [#413](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/413). Report acceptance remains open under [#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397). Shared-editor deployment evidence remains separate under [#387](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/387).

Native LOAD Case targets ([#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)), advanced Testdata and generators ([#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)), and Debug interoperability ([#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)) are excluded from this target. Existing direct LOAD and Debug paths remain supported.

This guide gets you from a clean checkout to a successful **Native Case** run with the smallest offline example. It teaches the new authoring model first:

```text
Package → Flow → testcase.yaml (+ inline or Excel Data Source)
       → Preview / Resolve → Select Case(s) → Validate → RUN → Results
```

The checked-in Native examples are offline: they use only `assign`, `log`, `assert`, and optionally ATT's local sample Tool. No database, MQ, API endpoint, credentials, or network access is required.

## What you will run

The Default Package contains these Native Definitions and the read-only Excel Data Source:

```text
packages/default/testcase/quick-start/single/testcase.yaml
packages/default/testcase/quick-start/inline-multi/testcase.yaml
packages/default/testcase/quick-start/excel/testcase.yaml
packages/default/testdata/quick-start-native.xlsx
config/config.yaml
```

| Definition ID | Resolved Case IDs | Purpose |
|---|---|---|
| `quick.start.single` | `default` | One Definition + typed `inputs` → one Case |
| `quick.start.inline` | `basic`, `with-tool` | One Definition + inline records → multiple Cases |
| `quick.start.excel` | `QS001`, `QS002` | One Definition + read-only Excel Data Source |

All three bind Flow `quick.start.v1`. A Resolved Case is a selectable logical Case; each RUN creates a separate execution.

## Prerequisites

1. JDK and Maven available so the source checkout can compile when needed.
2. From the repository root, build the Local CLI if classes are missing:

```sh
mvn -DskipTests -pl att-cli -am compile
```

3. Optional placeholders used by Package validation for unrelated Helpers:

```sh
export ORDERS_DB_USERNAME=docs-example-user
export ORDERS_DB_PASSWORD=docs-example-password
export PAYMENT_MQ_USERNAME=docs-example-user
export PAYMENT_MQ_PASSWORD=docs-example-password
```

## Inspect the Native Definition

Open `testcase/quick-start/single/testcase.yaml`:

```yaml
schemaVersion: att-testcase/v1
id: quick.start.single
name: Native Quick Start (single Case)
flow: quick.start.v1
tags: [quick-start, smoke, native]
inputs:
  amount: 100
  useTool: false
  expected: 100
```

The Flow at `flows/quick-start/flow.yaml` owns the Actions. The Test Case does not duplicate Steps or assert DSL.

## Validate the Package

```sh
./att.sh validate --package
```

Validation is read-only. It checks Native Definitions and their selected Flow/Tool dependencies.

## Resolve without executing (dry-run)

```sh
./att.sh --local -p default run native-case quick.start.single \
  --resolved-case-id default --dry-run --format json
```

Dry-run resolves and validates the selected Case IDs. It does not execute Flow Actions.

## RUN the single Case

```sh
./att.sh --local -p default --output-dir output --run-id native-qs-single \
  run native-case quick.start.single --resolved-case-id default
```

Expect overall status `PASS`. Use a dedicated `--run-id` (and optional `--output-dir`) so a second attempt does not collide with an existing run directory.

## Inspect Results

Native RUN writes evidence under `<output-dir>/<run-id>/` (default Package output is `packages/default/output/` when `--output-dir` is omitted):

```text
output/native-qs-single/
  run.yaml
  report/index.html
  report/junit.html
  ci/summary.json
  ci/junit.xml
  executions/<execId>/case.log
  executions/<execId>/case.yaml
```

Open `report/index.html` for the HTML overview and `ci/summary.json` for machine-readable totals. Each selected Case has its own `executions/<execId>/case.log`.

| Status | Meaning for this guide |
|---|---|
| `PASS` | Selected Cases finished and assertions succeeded |
| `FAIL` | A business assertion failed |
| `ERROR` | Runtime/integration failure |
| `INVALID` | Validation prevented execution |
| `SKIPPED` | Dry-run resolved the Case without executing the Flow |

This run also writes the source-independent workbook `workbooks/native-cases.result.xlsx` next to the HTML, CI, and JUnit artifacts. That report is implemented on this branch under `att-testcase/v1`. Issue [#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397) remains open for report acceptance. The [offline payments walkthrough](guides/native-payments.md) covers a multi-Flow Suite, per-Case PASS/FAIL evidence, the standard XLSX, and the optional source-workbook report.

## RUN multiple inline Cases

```sh
./att.sh --local -p default --output-dir output --run-id native-qs-inline \
  run native-case quick.start.inline \
  --resolved-case-id basic --resolved-case-id with-tool
```

Qualified IDs are `quick.start.inline.basic` and `quick.start.inline.with-tool`. Both should appear as `PASS` in `ci/summary.json` (`total=2`, `passed=2`).

## RUN Excel-backed Cases

```sh
./att.sh --local -p default --output-dir output --run-id native-qs-excel \
  run native-case quick.start.excel \
  --resolved-case-id QS001 --resolved-case-id QS002
```

The Excel file `testdata/quick-start-native.xlsx` is a read-only Data Source. Preview and RUN do not modify it. Expect both `quick.start.excel.QS001` and `quick.start.excel.QS002` in the run summary.

## Studio Preview (when Server/Studio is available)

With ATT Server and Studio built from the 4.6.0 release-target stack (not yet published):

1. Open the Default Package in Explorer.
2. Open or create a `testcase.yaml` under `testcase/`.
3. Use **Preview / Resolve** to list qualified Case IDs and typed inputs (read-only; no Job).
4. Publish through the existing Change Set Draft → Validate → Review → Publish flow, then RUN when the Server advertises `att-execution/v2`.

See [Test authoring — Native Case](reference/test-authoring.md).

## Deferred on this head (honest limits)

| Capability | Status |
|---|---|
| Implicit Suite (`run native-suite`, tags, excludes, fail-fast, rerun-failed) | Implemented and integrated on this branch (`att-execution/v2`). [#396](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/396) acceptance remains open. Not a 4.6.0 release |
| Standard native XLSX and optional source-workbook report | Implemented and integrated on this branch (`att-testcase/v1` report contract). [#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397) acceptance remains open |
| Native LOAD Case targets | Deferred under [#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418) |
| n-to-m generators | Deferred under [#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419) |
| Debug interoperability | Deferred under [#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420) |
| Conversion of existing Package behavior | Tracked separately under [#398](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/398) |

## Next steps

| Goal | Guide |
|---|---|
| Native Case fields and Excel mapping | [Test authoring](reference/test-authoring.md) |
| S12 inventory / migration decisions | [Migration matrix](maintenance/native-case-default-package-migration-s12.md) |
| Debug a Flow or Tool | [Debug](reference/execution-modes/debug.md) |
| Run a Flow or Tool under LOAD | [Load](reference/execution-modes/load.md) |
| Multi-Flow offline payments Suite, reports, and rerun-failed | [Native payments walkthrough](guides/native-payments.md) |
| CLI grammar (includes `run native-case` and `run native-suite`) | [Current CLI Reference](reference/cli.md) |
| Maintain an existing Package | [Legacy documentation](legacy/README.md) |
