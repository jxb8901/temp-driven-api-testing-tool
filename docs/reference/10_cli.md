## 10 CLI Reference

<!-- Transitional placement produced by issue #41. #42 owns semantic reorganization. -->

### 05 CLI Reference

#### Commands

| Command | Purpose | External tools? |
|---|---|---:|
| `help` | Show syntax and options; default with no command | No |
| `version` | Print ATT version | No |
| `validate` | Validate package or selected dependency closure | No |
| `snapshot` | Generate same-basename canonical testcase XML | No |
| `run` | Validate and execute selected cases | Yes, except dry-run |
| `debug` | Execute one Template, Flow, or Tool with a debug sidecar | Yes |
| `docs` | Generate searchable package documentation | No |
| `report` | Regenerate reports for a completed run | No |
| `build` | Archive the latest completed run | No |
| `clean` | Remove documented ATT-generated output | No |

#### Command syntax

The tables use the Linux/macOS launcher `./att.sh`. On Windows, use `att.bat` with the same command and options. `att.bat snapshot`, `att.bat validate`, and `att.bat docs` do not invoke configured testcase tools. Windows validation checks `.sh` file existence and path safety, skips POSIX launch/executable compatibility, and emits one warning listing affected tools; a validation PASS does not prove those scripts can run on Windows. Provide and test Windows-native equivalents before `run`. Binary releases require Java 8+. Source-tree `att.bat` compiles with Maven when available and otherwise requires existing `target\classes`.

| Syntax | Notes |
|---|---|
| `./att.sh` or `./att.sh help` | Show help |
| `./att.sh version` | Print version |
| `./att.sh snapshot` | Generate snapshots recursively below `testcase.root`; equivalent to `--all` when no selector is supplied |
| `./att.sh snapshot --suite <xlsx>` | Generate one same-basename XML snapshot |
| `./att.sh snapshot --all` | Generate snapshots recursively below `testcase.root` |
| `./att.sh snapshot --suite-dir <dir>` | Generate snapshots recursively below a directory |
| `./att.sh validate --package` | Validate complete package; default scope |
| `./att.sh validate --selected <selection>` | Validate selected dependency closure |
| `./att.sh validate --package --format json` | Emit one validation JSON document to stdout |
| `./att.sh run --all` | Run all discovered cases |
| `./att.sh run --suite <xlsx>` | Run one workbook; repeatable |
| `./att.sh run --suite-dir <dir>` | Discover workbooks below a directory |
| `./att.sh run <selection> --case <workbookId.groupId.rowCaseId>` | Include one full Case ID |
| `./att.sh run <selection> --tag <tag>` | Include a tag |
| `./att.sh run <selection> --exclude-tag <tag>` | Exclude a tag |
| `./att.sh run <selection> --dry-run` | Validate/plan without tool execution |
| `./att.sh run <selection> --update-snapshot` | Explicitly refresh changed complete-workbook snapshots before validation |
| `./att.sh run <selection> --fail-fast` | Stop scheduling after first FAIL/ERROR |
| `./att.sh run <selection> --rerun-failed` | Select prior FAIL/ERROR cases |
| `./att.sh run <selection> --run-id <id>` | Set final run directory name |
| `./att.sh run <selection> --output-dir <dir>` | Override output root |
| `./att.sh run <selection> --ci-output junit,json` | Write CI XML/JSON plus JUnit HTML |
| `./att.sh run <selection> --profile` | Write per-run `performance.json` timings and counters |
| `./att.sh run <selection> --queue` | Wait for another ATT process using the same output root |
| `./att.sh run <selection> --allow-parallel-runs` | Allow concurrent ATT processes; does not parallelize Cases in this run |
| `./att.sh run <selection> --format json` | Emit machine-readable summary |
| `./att.sh run <selection> --quiet` | Suppress the default lifecycle and Case-log output |
| `./att.sh run <selection> --verbose` | Explicitly retain the default lifecycle progress and complete Case-log mirroring; accepted for compatibility |
| `./att.sh debug template <id>` | Execute one Template; auto-discover `<template-dir>/debug.yaml` |
| `./att.sh debug flow <id>` | Execute one canonical Flow; auto-discover `<flow-dir>/debug.yaml` |
| `./att.sh debug tool <id>` | Execute one Tool; auto-discover `config/tools/<group>.debug.yaml` |
| `./att.sh debug <type> <id> --input <file>` | Override the target's auto-discovered debug input |
| `./att.sh debug <type> <id> --output-dir <dir>` | Isolate debug output below `<dir>/debug/<debugId>/` |
| `./att.sh debug <type> <id> --format json` | Emit a compact machine-readable console summary; full evidence remains in `result.yaml` |
| `./att.sh report --run-id <id>` | Regenerate `report/index.html` and `report/junit.html` |
| `./att.sh docs` | Generate `build/docs/index.html` |
| `./att.sh build` | Archive latest completed run in `build/` |
| `./att.sh clean` | Remove documented generated outputs |

Options are command-specific. Unknown commands/options and missing option values are errors. `--package` and `--selected` are mutually exclusive. Selected validation and run require an explicit selection.

#### Standalone debug inputs and outputs

Debug input files use `att-debug/v1.0`. `case` values become synthetic `CASE` data, `stage.key` and `stage.values` declare the one debug stage, and `inputs` is adapted directly into canonical `EXEC.INPUT.*`. For compatibility, `${CASE.inputs.<field>}` remains a read-only view when no business field is literally named `inputs`; it is not duplicated below `EXEC.INPUT`. Tool arguments come from the root `arguments` map or `tools.<localKey>.arguments`. An explicit `--input` always wins over auto-discovery.

Before execution ATT validates only the selected Template or Flow dependency closure, or the selected Tool definition. It does not require unrelated workbook snapshots or unrelated malformed Template descriptors to pass. The selected target still uses the normal Template/Flow/Tool runner, including Context resolution, Flow nesting, Tool retry/timeout, evidence, saveAs, DB finalization, and Case-log behavior.

##### Configuration examples

The following examples show the supported placement of debug values. Every file is a complete `att-debug/v1.0` document.

Template sidecar (`templates/PAYMENT_INVOKE/debug.yaml`):

```yaml
schemaVersion: att-debug/v1.0
case:
  caseName: PAYMENT debug
  amount: 100
  environment: SIT
stage:
  key: invoke
  values:
    channel: WEB
    sourceRef: SRC-001
```

Run it with `./att.sh debug template PAYMENT_INVOKE`. Template expressions should prefer `${EXEC.INPUT.amount}`, `${EXEC.INPUT.environment}`, and the current Stage value `${EXEC.INPUT.channel}`; the current Stage's `values` overlay Case-level input for that Stage, with the Stage value winning on collisions. The corresponding `CASE.*` paths remain compatibility aliases, while `CASE.STAGES.*` is retained only as the legacy execution/evidence view.

Flow sidecar (`templates/flows/common/compose/debug.yaml`):

```yaml
schemaVersion: att-debug/v1.0
case:
  caseName: Compose debug
  traceId: TRACE-001
stage:
  key: DEBUG
  values:
    mode: SIT
inputs:
  source: payment
  suffix: -debug
```

Run it with `./att.sh debug flow common.compose.v1`. Flow inputs are available as `${EXEC.INPUT.source}` and, when there is no same-named Case value, as the compatibility alias `${EXEC.INPUT.source}`.

Grouped Tool sidecar (`config/tools/fpp.debug.yaml` for `fpp.invokeApi`):

```yaml
schemaVersion: att-debug/v1.0
case:
  RefNo: REF001
tools:
  invokeApi:
    arguments:
      requestId: REF001
      requestType: PAYMENT
      requestFile: /tmp/payment-request.xml
      apiLogPath: /tmp/payment-api.log
```

Run it with `./att.sh debug tool fpp.invokeApi`. The `invokeApi` key is the group-local Tool key. Values must be scalar or list values accepted by the Tool descriptor; map literals are not supported by the standalone Tool adapter.

Ungrouped Tool sidecar (`config/tools/invokePaymentApi.debug.yaml`):

```yaml
schemaVersion: att-debug/v1.0
arguments:
  requestFile: /tmp/payment-request.xml
  environment: SIT
```

Run it with `./att.sh debug tool invokePaymentApi`. For an ungrouped Tool, root `arguments` is passed directly; it is not wrapped under `tools`.

An explicit file overrides sidecar discovery, which is useful for temporary values in CI or local diagnosis:

```sh
./att.sh debug template PAYMENT_INVOKE --input /tmp/payment-debug.yaml \
  --output-dir /tmp/att-debug --format json
```

The selected input is validated before execution. Missing files, invalid schema, unknown or missing Tool arguments, and other input/configuration errors return exit code `2`. Framework-owned values such as `EXEC.ID`, `EXEC.MODE`, `EXEC.OUTPUT_DIR`, `EXEC.VARS`, and `EXEC.ACTIONS`, together with the corresponding `CASE.*`, `RUN.*`, `ACTIONS.*`, `TOOL.*`, and `DB.*` aliases, remain authoritative even if they appear in the input `case` map. `EXEC.STAGES` is not a canonical Context node; Stage history remains in the legacy `CASE.STAGES` evidence view.

Each invocation writes:

```text
output/debug/<debugId>/
├── case.log
├── result.yaml
└── artifacts/
    └── case.yaml
```

`result.yaml` contains the target, status, exit code, duration, input path, Case ID, action results, diagnostic (when present), and evidence locations. Synthetic framework-owned fields always win over same-named values in `case`; debug inputs cannot replace `CASE.caseId`, `CASE.workbookId`, `CASE.groupId`, `CASE.rowCaseId`, `CASE.outputDirectory`, the legacy `CASE.STAGES` evidence view, `CASE.DB`, `CASE.VARS`, `RUN.*`, `ACTIONS.*`, `TOOL.*`, or `DB.*`. Debug output is independent of ordinary `output/latest-run.yaml` and report lifecycle.

For `validate --format json`, stdout contains exactly one JSON document; progress and human diagnostics go to stderr.

#### Exit codes

| Code | Meaning |
|---:|---|
| 0 | Command/run succeeded without FAIL, ERROR, or INVALID |
| 1 | One or more FAIL results and no ERROR/INVALID |
| 2 | CLI/configuration/validation/INVALID failure |
| 3 | One or more ERROR results or unrecoverable runtime failure |
