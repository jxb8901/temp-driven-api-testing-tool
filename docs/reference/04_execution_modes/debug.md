### 6.2 Standalone Debug

Debug executes one Template, Flow or Tool without requiring a workbook Testcase.

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh debug flow common.compose.v1 --input /tmp/compose.debug.yaml
./att.sh debug tool fpp.invokeApi --input /tmp/invoke.debug.yaml --env UAT
```

Run `./att.sh debug` with no target to list statically valid runnable Tools, Templates and Flows with copyable commands. A default sidecar path is displayed only when that regular non-symlink file exists. Discovery validates selected target dependencies but does not create Debug output or invoke Tools. Use `--format json` for machine-readable discovery output.

Debug input uses the current `schemaVersion: att-debug/v1.1`. Supported top-level data is `case`, optional `stage`, `inputs`, `vars`, `arguments`, and grouped `tools.<localKey>.arguments`. `inputs` is adapted into canonical `EXEC.INPUT`; Template/Flow `vars` is evaluated as a typed bootstrap tree and seeds canonical `EXEC.VARS` before a target starts. Tool Debug uses `arguments` and does not support `vars`. Framework-owned identity, output, Actions, resource metadata and compatibility views cannot be overwritten by user input. Schema migration is documented in [Appendix C](../appendices/migrations.md).

#### Standalone Debug bootstrap data

The three input contracts are intentionally separate:

| Debug field | Runtime destination | Use |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Business input consumed directly by a Flow/Template |
| `vars` | initial `EXEC.VARS` | Caller-prepared values expected by a reusable Flow/Template |
| `arguments` / `tools.<localKey>.arguments` | Tool argument contract | Explicit arguments for standalone Tool Debug |

A Flow that only consumes `EXEC.INPUT` needs no `vars`. A Flow that normally runs after a parent Flow publishes `EXEC.VARS.refNo` can be debugged directly with a scalar or typed structure:

Debug `inputs` use the same Testdata mapping syntax as Run: select a configured environment with `--env`, then use exact `@{id}` / `@{id.path}` references or scalar interpolation. ATT resolves these before publishing `EXEC.INPUT`; direct Testdata markers remain invalid inside the reusable Template, Flow or Tool definition. See [Testdata Registry and Input Mapping](../02_test_authoring.md).

```yaml
schemaVersion: att-debug/v1.1
inputs:
  amount: 100
vars:
  refNo: REF001
  txnSeq: 23
  tags: [SIT, PAYMENT]
  order:
    id: ORD001
    amount: 100
```

```sh
./att.sh debug flow common.payment --input common.payment.debug.yaml
```

`vars` values use the shared expression engine: an exact `${EXEC.INPUT.amount}` preserves its native type, interpolated text becomes a string, and `#{...}` preserves the expression result type. Maps and lists recurse; map keys remain literal. Vars may reference other vars regardless of declaration order; cycles, missing vars, unavailable roots, and side-effecting calls fail before the target starts. The first normal `assign` may replace a bootstrapped variable, after which normal duplicate-assignment rules apply. Final values appear through the normal `EXEC.VARS`/`CASE.VARS` context and result artifacts, subject to existing redaction rules; no second Debug-only namespace is created.

Bootstrap values may use initialized execution identity, `EXEC.INPUT`, `EXEC.LOAD` when present, other `EXEC.VARS.<name>` entries, and stable project/source/target/template metadata. `EXEC.ACTIONS`, action-local `output`, and invocation-scoped metadata are not available. Tool/DB/MQ/HTTP/SSH/process/filesystem or stateful calls are blocked; safe pure built-ins use the normal ATT parser. Repeatable `--set` namespaces are `input.path=value`, `vars.path=value`, and Tool-only `arg.name=value`. Values are parsed as safe YAML; nested maps and numeric list indexes are supported where the destination accepts them. For example, `--set 'vars.refNo=${EXEC.INPUT.refNo}'` changes the raw definition before evaluation.

Without `--input`, ATT looks for `debug.yaml` beside a selected Template or Flow and for `config/tools/<group>.debug.yaml` for a grouped Tool (`config/tools/<localKey>.debug.yaml` for an ungrouped Tool). When no default sidecar exists, supply `--input`; an explicit `--input` replaces auto-discovery. `--env` uses the same environment resolver as Run/Validate/Load before target validation.

Debug performs target-scoped validation: it validates the selected Template/Flow dependency closure or Tool contract, rather than requiring unrelated workbooks. Template and Flow debug use the same Action/Flow scope rules as Run. Tool debug constructs the same configured Tool invocation contract.

Each invocation is isolated under:

```text
output/debug/<debugId>/
├── case.log
├── result.yaml
└── artifacts/
```

Debug does not create or update normal `latest-run.yaml`. Exit codes are `0` PASS, `1` FAIL, `2` invalid CLI/config/input/validation, and `3` runtime error. It is execution-equivalent at the selected reusable-component boundary, but it is **not** a workbook Case: there is no workbook selection, Stage history or result-workbook lifecycle unless explicitly represented by debug inputs/artifacts.

### Debug troubleshooting and MQ payload paths

When a debug target cannot be resolved, check the target kind and identifier first, then use `--input <path>` to remove sidecar discovery from the diagnosis. Template and Flow debug discover `<target directory>/debug.yaml`; grouped Tool debug discovers `config/tools/<group>.debug.yaml`. The selected target's dependency closure is validated, so an unrelated workbook or Case file is not a prerequisite.

Use the same `&{project-relative-file}` expression in Debug, Run and Load, then pass its UTF-8 String as HTTP `body`, MQ `payload`, or SSH upload `payload`. These Resource Helpers do not resolve separate local paths. Validation resolves project files before external I/O; obsolete HTTP/MQ `file`, SSH upload `localPath`, and SSH `download` calls are rejected.

Use the output directory to separate diagnosis stages:

| Symptom | Check |
|---|---|
| `Debug input file does not exist` | Add the sidecar beside the selected target or pass `--input` explicitly. |
| `Debug input uses a historical schemaVersion` | Use the active Debug schema; see [Appendix C](../appendices/migrations.md); add `vars` only when a Flow/Template needs caller-prepared `EXEC.VARS`. |
| `target` or dependency validation fails | Confirm the target type/id and inspect the reported dependency field; unrelated workbook files are not required. |
| MQ reports a missing/unsafe payload | Verify the absolute package path or the relative Case-output path; remove traversal and symlinks. |
| The action runs but output is unexpected | Read `case.log`, `result.yaml` and the action artifacts under `output/debug/<debugId>/`; compare rendered inputs with the selected environment. |

Load-specific evidence retention (`metrics`, `failures`, `samples`, `all`) does not apply to a standalone Debug invocation. Debug always keeps its invocation result and artifacts under its own debug directory. For the same target in a load run, see [Load evidence and resource output](load.md#evidence-and-resource-output).

#### Configuration examples

The following examples show the supported placement of debug values. Every file is a complete `att-debug/v1.1` document.

Template sidecar (`templates/PAYMENT_INVOKE/debug.yaml`):

```yaml
schemaVersion: att-debug/v1.1
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
schemaVersion: att-debug/v1.1
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
schemaVersion: att-debug/v1.1
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
schemaVersion: att-debug/v1.1
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

The selected input is validated before execution. Missing files, invalid schema, unknown or missing Tool arguments, and other input/configuration errors return exit code `2`. Framework-owned values such as `EXEC.ID`, `EXEC.RUN_ID`, `EXEC.OUTPUT_DIR`, `EXEC.VARS`, and `EXEC.ACTIONS`, together with the corresponding `CASE.*`, `RUN.*`, `ACTIONS.*`, `TOOL.*`, and `DB.*` aliases, remain authoritative even if they appear in the input `case` map. Mode/scheduler diagnostics are not expression-visible. `EXEC.STAGES` is not a canonical Context node; Stage history remains in the legacy `CASE.STAGES` evidence view.

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
