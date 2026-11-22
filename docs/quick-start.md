# ATT v4.6.0 quick start

[中文快速入門](quick-start.zh.md) · [Reference Manual](reference.md) · [Migration matrix](maintenance/native-case-default-package-migration-s12.md)

This guide follows the Native Case workflow targeted for ATT 4.6.0. The release and tag have not been published. The target stack contains the inline and Excel Native RUN path, while deployed acceptance remains open under [#393](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/393) and Default Package adoption remains open under [#413](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/413). Report integration is tracked under [#397](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/397); shared-editor deployment evidence remains separate under [#387](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/387).

Native LOAD Case targets ([#418](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/418)), advanced Testdata and generators ([#419](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/419)), and Debug interoperability ([#420](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/420)) are excluded from this target. Existing direct LOAD and Debug paths remain supported.

This guide gets you from a clean checkout to a successful **Native Case** run with the smallest offline example. It teaches the new authoring model first:

```text
Package → Flow → testcase.yaml (+ inline or Excel Data Source)
       → Preview / Resolve → Select Case(s) → Validate → RUN → Results
```

Legacy Excel + Sidecar + XML Snapshot + Stage/Template remains supported. Use the [legacy Workbook appendix](#legacy-workbook-quick-start-supported) when that path is still the right one.

The checked-in Native examples are offline: they use only `assign`, `log`, `assert`, and optionally ATT's local sample Tool. No database, MQ, API endpoint, credentials, or network access is required.

## What you will run

Primary files in the Default Package:

```text
packages/default/templates/flows/quick-start/flow.yaml
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

The Flow at `templates/flows/quick-start/flow.yaml` owns the Actions. The Test Case does not duplicate Steps or assert DSL.

## Validate the Package

```sh
./att.sh validate --package
```

Validation is read-only. It checks Native Definitions, Flow/Tool closure, and the retained legacy suites together.

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

On this S05-based head, HTML/CI/JUnit evidence follows the existing Native RUN path. **Source-independent standard native XLSX reporting is S07 and is not claimed here.**

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

The workbook `testdata/quick-start-native.xlsx` is a read-only Data Source. Preview and RUN do not modify it and do not create a legacy XML Snapshot. Expect both `quick.start.excel.QS001` and `quick.start.excel.QS002` in the run summary.

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
| Implicit Suite (`run native-suite`, tags across Definitions) | S06 / #396 — implemented in the release-target stack; acceptance pending |
| Source-independent native XLSX report | S07 / #397 — implemented in the release-target stack; integrated acceptance pending |
| Native LOAD Case targets | S08 — deferred |
| Automatic Stage/Template → Flow conversion | #398 — analysis track; keep legacy suites |

## Legacy Workbook Quick Start (supported)

Prefer Native for new work. Keep the Workbook path when you need Stage/Template semantics, existing Snapshot IDs, or workbook report layouts:

```text
packages/default/testcase/quick_start.xlsx
packages/default/testcase/quick_start.yaml
packages/default/testcase/quick_start.xml
packages/default/templates/QUICK_START/template.yaml
```

```sh
./att.sh snapshot --suite testcase/quick_start.xlsx
./att.sh validate --package
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS002
```

| Legacy Case ID | Purpose |
|---|---|
| `quickStart.default.QS001` | Offline assign/log/assert |
| `quickStart.default.QS002` | Same path plus local sample Tool |

Do not edit the committed `quick_start.xml` by hand; regenerate with `snapshot` after workbook edits. Full Stage/Template authoring detail remains in [Test authoring](reference/test-authoring.md).

## Next steps

| Goal | Guide |
|---|---|
| Native Case fields and Excel mapping | [Test authoring](reference/test-authoring.md) |
| S12 inventory / migration decisions | [Migration matrix](maintenance/native-case-default-package-migration-s12.md) |
| Debug a Template/Flow | [Debug](reference/execution-modes/debug.md) |
| LOAD (existing direct targets) | [Load](reference/execution-modes/load.md) |
| CLI grammar (includes `run native-case` and `run native-suite`) | [Current CLI Reference](reference/cli.md) |
