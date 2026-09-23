# ATT 5.3.1 - Automated Testing Tool

ATT V5.3.1 uses one execution-neutral Context for ordinary TestCase execution, standalone debug, and load iterations, with canonical `EXEC`, `META`, and Action-local `output` scopes. Tool, DB, and MQ operations converge through one operation-result boundary and one stable Action result/evidence envelope while retaining the compatible V3.4 execution, MQ, diagnostics, and Case-log behavior.

V2.6 retains the V2.5 first-class DB design and adds `call` as a typed alternative to Tool `command`. A call-backed Tool can wrap a DB query/scalar/update or pure built-in while direct DB Actions and expressions remain available.

`testcase.root` defaults to `testcase`. ATT recursively discovers adjacent `basename.xlsx` + `basename.yaml` + `basename.xml` triples below it; each triple is one testcase set.

## Quick Start

```sh
./att.sh snapshot
./att.sh validate --package
./att.sh run --all
./att.sh debug template PAYMENT_INVOKE
```

On Windows, run the same commands through `att.bat`:

```bat
att.bat snapshot
att.bat validate --package
att.bat docs
att.bat run --all
```

`snapshot`, `validate`, and `docs` run natively through `att.bat` without invoking configured testcase tools. On Windows, validation checks `.sh` file existence and path safety but skips POSIX launch/executable compatibility and emits a warning; provide and test `.bat`, `.cmd`, or `.exe` equivalents before `run`.

Run one workbook, full Case ID, or tag:

```sh
./att.sh validate --selected --case payment.payment.TC001
./att.sh validate --package --format json
./att.sh run --suite testcase/payment_regression.xlsx
./att.sh run --suite testcase/payment_regression.xlsx --update-snapshot
./att.sh run --all --case payment.payment.TC001
./att.sh run --all --tag smoke --exclude-tag slow
./att.sh run --all --ci-output junit,json
```

Every workbook requires a same-basename YAML sidecar with a package-unique `id` and a generated same-basename XML snapshot. The included `payment` workbook contains Chinese `payment` and `batch` sheets; both may contain row Case ID `TC001`, producing `payment.payment.TC001` and `payment.batch.TC001`.

## Commands

```sh
./att.sh                 # help
./att.sh snapshot
./att.sh validate --package
./att.sh run --all
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
./att.sh report --run-id <RunID>
./att.sh docs
./att.sh build
./att.sh clean
```

- Reports: `output/<RunID>/report/index.html`
- Result workbooks: `output/<RunID>/workbooks/`
- CI JSON: `output/<RunID>/ci/summary.json`
- CI JUnit XML: `output/<RunID>/ci/junit.xml`
- JUnit HTML report: `output/<RunID>/report/junit.html`
- Optional performance profile: `output/<RunID>/performance.json` from `run --profile`
- Load summaries and offline reports: `output/load/<runId>/load-summary.{json,yaml}` and `output/load/<runId>/report/index.html`
- Load exit codes: `0` PASS, `1` threshold failure, `2` validation/configuration failure, `3` runtime/infrastructure error
- Package documentation: `build/docs/index.html`
- Latest completed-run archive: `build/att-run-<RunID>.tar.gz`

`./att.sh docs` always produces one self-contained page at `build/docs/index.html`; Testcases are grouped by workbook and Sheet, and each table includes the validation-time Expected Result assembled from assert actions. Tool, DB helper, and built-in sections have top indexes, and search filters by workbook, sheet, Case ID, template, Tool, or DB helper. `--single-page` is not a supported option. `./att.sh clean` removes the configured `outputDirectory`, `build/docs`, and `build/att-*.tar.gz`, while preserving testcase, template, tool, dbhelper, configuration, and documentation source files.

## Compatibility essentials (V3.4 baseline)

- Authoring schemas remain named `att-template/v3.0` and `att-flow/v3.0`; their reusable descriptor shape is unchanged, while the V3.4.2 compatibility baseline gives each Flow invocation a fresh Action scope. V2.6, V2.5 and V2.3 Templates remain readable without semantic changes.
- Flow descriptors live below `templates/flows/**/flow.yaml`, use a path-independent ID ending in `.vN`, and contain only metadata plus ordered `actions`; `inputs` and `outputs` are invalid.
- A V3 Template invokes a Flow with `type: flow` and static `use`; `with` is invalid. Flow Actions expose only their standard status outcome.
- Flow internals use the canonical `EXEC`/`META` Context but each Flow invocation has a fresh `EXEC.ACTIONS` scope. Internal Actions are readable only inside that Flow; publish values needed by the caller through unique `EXEC.VARS` assignments. `CASE`/`RUN`/`ACTIONS` remain compatibility aliases, while `input.*`, lowercase `actions.*`, `runtime.*`, `flow.*`, and `output.outputs` are invalid.
- Action IDs must be unique within one Stage/Template/Flow scope. Repeated or nested Flow invocations may reuse internal IDs; duplicate IDs in the same scope fail validation before execution.
- V3 Action `runWhen` skips one statically known Action. `runAlways`, warning impact, Flow timeout/retry, dynamic dispatch, loops and parallel branches are not V3.4.0 features.
- `validate --package` checks every Flow; `validate --selected` loads and validates only the selected Template dependency closure. Runtime uses the precompiled registry and never discovers Flow files dynamically.

- Current configuration uses `att-config/v2.6`; call-backed groups use `att-tool-group/v2.6`. Existing V2.1/V2.2/V2.5 configuration and V2.2 command-backed groups remain readable.
- A Tool declares exactly one of `command` or `call`. `call` may target one DB query/scalar/update or pure built-in and keeps typed results. DB update façades work only as the primary call of a Tool Action; READ façades also work in expressions.
- Prefer call-backed Tools for new framework-native or reusable capabilities. Use command-backed Tools when the capability genuinely requires an external process, script, CLI, SSH transport, or third-party executable; command-backed Tools remain supported and are not deprecated.
- Both Tool backends publish the same observable Action envelope: `${output.result}` while active and `${EXEC.ACTIONS.<id>.output.result}` after publication, with final helper/process evidence under `${output.evidence.<kind>.invocations[0]}` and per-attempt evidence under `${output.attempts[n].evidence}`. Call arguments remain typed values; command arguments remain deterministic argv items, including command-only `argName`/`argNameMode` shaping.
- Call-backed Tools may opt into `{cache: {scope: case|db}}`. DB update, commit, rollback, and reconnect never invalidate cache; `db` scope can intentionally return stale data and is for stable/reference lookups only.
- Configure each DB helper in its own `att-dbhelper/v2.5` YAML file and reference those files with the global `dbhelpers` list. DB helpers are first-class runtime services, not Tool implementations.
- Use `type: db` with exactly one `query` or `update` block. Read queries are also available inside Case-runtime expressions as `#{db.<instance>.query(...)}` and `#{db.<instance>.scalar(...)}`. Results remain typed at `EXEC.ACTIONS.<id>.output.result` or in an exact assign expression.
- DB helpers support positional JDBC `?` bindings and direct DB Action `parameters: {name: value}` bindings for `:name` placeholders. Parameter evidence defaults to resolved values; credentials remain excluded, and `masked`/`types` remain explicit options. Per-instance timeout, result bounds, connection lifecycle, and transaction rules are unchanged.
- A Tool Action may declare `evidence` collectors keyed by collector ID. Each collector runs after the primary result and before that attempt's assertion, repeats with primary retries, and supports `timeoutMs` plus `onFailure: continue|stop`; collector results are stored under `output.attempts[n].evidence.<id>` without replacing `output.result`.
- IBM MQ helpers are configured as `att-mqhelper/v1.0` files under `config/mqhelpers/` and referenced by `mqhelpers` in `att-config/v2.6`. Use `#{mq.<instance>.send(...)}`, `receive(...)`, or `request(...)` with file payloads; credentials may use `${ENV:NAME}` and payload bytes are not copied into evidence.
- The default Maven build uses a reflection adapter and does not require IBM MQ. Enable the client profile with `mvn -Pibm-mq package`, or package a downloaded IBM client jar with `IBM_MQ_JAR=/path/to/com.ibm.mq.allclient-10.0.0.0.jar ./build.sh`.
- `saveAs.path: console` writes the selected representation to the Case log without creating a file. Process stdout/stderr are written directly to the Case log and no persistent `process-output` directory is created; explicit file `saveAs` remains available.
- ATT bundles no database driver. Put the driver and its dependencies in `lib/` before starting ATT; both source and packaged Unix/Windows launchers include that directory.

- Edit testcase values in `basename.xlsx` and never hand-edit `basename.xml`; generate it with `./att.sh snapshot` (the default is all discovered workbooks), `./att.sh snapshot --all`, or `./att.sh snapshot --suite <xlsx>`, review the XML diff, then commit both files (plus the YAML sidecar when its mappings changed). Snapshot XML uses `att-testcases/v2.4`, preserves group/Case/stage order and typed nested YAML values, prefers CDATA for multiline or XML-special string content, and excludes styles, widths, comments, and unconfigured sheets/columns.
- `validate` and ordinary `run` reject a missing, malformed, non-canonical, or stale snapshot before creating run output. `run --update-snapshot` is the explicit opt-in overwrite workflow: it refreshes only changed complete-workbook snapshots before the same validation gate, including with `--dry-run`. Formula cells and merged data cells in configured testcase columns are rejected because they cannot provide stable versioned values.

- Render actions require a safe template-relative `payload` glob and `renderAs: file|text|json|yaml|xml`. File mode preserves each matched relative path below the Case output directory; other modes store typed values in `EXEC.ACTIONS.<id>.output.result`.
- Action outcome fields are nested under `output`; ordinary executable Actions may add `targetFiles`, `result`, and assertion detail to the common `status`, `success`, `durationMs`, and `exception` fields. A Flow invocation exposes its standard outcome in the parent scope; internal Flow evidence remains nested under that invocation. Use `${output...}` for the current Action and `${EXEC.ACTIONS.<id>.output...}` for completed Actions in the current scope. Stage/Flow history is result evidence, not a reusable Context namespace.
- Assert actions use required `assert` instead of `expression` and may declare validation-time `expected` plus runtime `actual`. Only assert actions contribute ordered, LF-preserving Expected/Actual report text.
- Every action supports an expression-bearing `description`; validation resolves known static Case values and preserves runtime placeholders for final evaluation after the action outcome exists.
- Render, tool, and log actions may use `assert` to decide PASS/FAIL. Operational exceptions stay ERROR; a tool's exit code is evidence at `output.exitCode`, not an automatic status decision.
- `${EXEC.OUTPUT_DIR}` exposes the normalized absolute current Case output directory. Local tools run with that directory as cwd and receive framework-owned `ATT_ROOT_DIR` and `ATT_CASE_OUTPUT_DIR` environment variables, while package-relative `./`/`../` executables are still resolved from the package root. SSH remote cwd remains the remote account default and local-path variables are not injected remotely.

- Current global configuration uses `att-config/v2.6`; Tool groups may use `att-tool-group/v2.6` or legacy `v2.2`, and DB helpers remain independent `att-dbhelper/v2.5` files. Grouped Tools are called as `#{group.tool(...)}` while inline `tools` remain global and unqualified.
- Linux/macOS use `./att.sh`; Windows uses `att.bat`. Both launch the same Java runner and accept the same commands and exit codes. `snapshot`, `validate`, and `docs` are safe Windows authoring commands and never invoke configured testcase tools; Windows validation warns when `.sh` launch compatibility was not checked. Release packages need only Java 8+; source-tree mode compiles with Maven when it is available.
- Tool `command` and group `script` accept a scalar or argv list. Lists preserve each YAML item as one argument; scalar commands use the existing tokenizer once. Group scripts receive `<tool key> <tool command argv>` after the script argv.
- An argument may declare `argName`, such as `--reference`. When its exact-token placeholder has a non-blank value, ATT emits the name and value as separate atomic argv; a missing/blank optional value emits neither. Multi-value inputs are typed YAML arrays at the call site, not delimiter-split strings. `argNameMode: once` emits the name before the list, while `repeat` emits it before every value. Omitted or empty `argName` is positional. V2.6 rejects `delimit`; legacy schemas retain read compatibility only.
- Root `ssh` applies to inline global tools; a group's `ssh` applies only to that group. ATT prefers local OpenSSH and automatically warns/falls back to the bundled mwiede/jsch Java client when `ssh` is unavailable. Both use strict host-key checking, optional key files, and a safely quoted remote command; see Reference Manual Chapter 09 for Java algorithm limits.
- Canonical built-ins use package-like names such as `str.lpad`, `date.format`, `file.move`, and `misc.nvl`; legacy flat names remain callable aliases. Tool-group IDs already provide the same `group.tool` package model. Built-in package roots and all canonical/legacy names are reserved against configured Tool collisions. `misc.dbText(...)` renders a stable DB result in the same SQL*Plus-style text used by DB Action `saveAs`.
- The shipped `fpp` tool group provides POSIX reference scripts for an API-adapter skeleton, SQLPlus pipe-delimited output to XML, transaction-log search, and child-script execution with YAML status plus captured stdout/stderr. `loghelper` and `exehelper` explicitly expand pathname wildcards per atomic path/argument, and a shared loghelper SSH server list may include the already-searched local host. Ordinary process-backed Tools still perform no shell or wildcard expansion. Replace the API script's marked integration block before production use; provide equivalent commands on Windows.
- A built-in that accepts exactly one value may be written as `#{upper(${EXEC.INPUT.currency})}` instead of `value=...`. A configured tool may omit its argument name only when its configuration declares exactly one argument, for example `#{getAppLogs(${EXEC.INPUT.caseId})}`; multi-argument tools still require names.
- `schemaVersion` is mandatory in global configuration, DB helper files, tool groups, workbook sidecars, and templates. New Tool polling and object `saveAs` use `att-template/v2.6`; `att-template/v2.5` and `att-template/v2.3` remain readable when they do not use the removed EXIT_CODE retry contract.
- `validate --package` is the default full-package check; `validate --selected` checks only the selected case/suite/tag dependency closure.
- Validation parses the same Context references and inline `#{...}` calls used at runtime. A Context path may use any case-sensitive segment suffix that uniquely identifies one currently readable logical path; append `?` as `${path?}` to turn missing maps, list entries, or intermediate segments into a real null while keeping ambiguity, malformed syntax, and invalid traversal errors. `${path}` remains strict. Ambiguity is `ATT-CTX-002`; unknown strict references report the requested path, deepest reached node, and missing segment without dumping the full Context tree.
- `type: assign` evaluates a text `expression` and publishes it under a unique Case-scoped `name`, for example `${EXEC.VARS.txnSeq}`, while retaining the same value at `${EXEC.ACTIONS.<id>.output.result}`. `EXEC.VARS` persists across stages/templates but is isolated per Test Case; an optional assertion does not roll back a successfully evaluated assignment.
- Every expression-bearing surface uses one engine. `${...}` remains Context interpolation; `#{...}` is a typed expression block supporting calls, parentheses, `+ - * /`, comparisons, boolean operators, `is null`, `like`, and `in`. Context operands still use `${...}`; bare Context paths are rejected. Use `#{prettyPrint(${EXEC.ACTIONS.query.output.result})}` for deterministic nested Map/List/array text.
- V2.4.3 caches compiled schemas, Templates, and render payloads, bounds process-output previews, limits HTML log embedding, supports `report.mode: none`, and exposes phase/counter evidence through `--profile`; V3.3 routes the bounded stream to the Case log instead of retaining default artifacts.
- A human `run` enables lifecycle progress and mirrors every complete Case-log block by default, including template/tool input/argv/stdout/stderr and payload evidence. `--verbose` remains accepted for compatibility; `--quiet` suppresses the default output. Use non-quiet output only where sensitive Case data may be displayed safely.
- `sysdate([format])` and `systimestamp([format])` retain their ISO defaults and accept one positional or named Java `DateTimeFormatter` pattern, for example `#{sysdate('yyyyMMdd')}`.
- Every Tool attempt has a 1–3,600,000 ms timeout. Resolution is tool-action `timeoutMs`, Tool descriptor `timeoutMs`, global `timeoutMs`, then 10,000 ms; sidecars, stages, and Templates cannot define timeout defaults. Command-backed and call-backed Tools use the same public contract.
- Retry exists only on a Tool Action. It requires `maxAttempts`, `intervalMs`, and `retryOn`, whose only values are `ASSERTION` and `TIMEOUT`. A normal result is asserted after every attempt; assertion false or timeout retries only when explicitly selected. Exit codes remain evidence at `${output.exitCode}` and have no special retry mechanism.
- A valid Run ID and full Case ID are used directly as `output/<RunID>/<CaseID>/` directory names. They must not contain path separators, control characters, or platform-reserved names.
- Tool command templates are normalized before declared arguments are injected as atomic argv values; resolved values are never tokenized again. Local tools do not use a shell. Prefer canonical `${input.argument}` with exact case-sensitive argument keys. `${argument}` remains a deprecated compatibility shorthand only when it matches one declared Tool argument and `validate` emits `CONTEXT_TOOL_INPUT_SHORTHAND`; undeclared or ambiguous shorthand is an error. Tools write results to stdout and diagnostics to stderr; ATT records logical/executed argv, input/stdout/stderr in case evidence and creates a dedicated raw-stdout artifact only when the action sets `saveAs`.
- In Tool-call expressions, expression quotes delimit strings rather than shell words: the opposite quote is literal, a matching quote may be backslash-escaped, and `${...}` may be interpolated inside a quoted value. Prefer a YAML block scalar for calls mixing apostrophes, double quotes, and Context values; the Reference Manual contains copyable examples.

## Standalone debug

Run one real target through the ordinary Template/Flow/Tool runtime:

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh debug flow common.compose.v1
./att.sh debug tool fpp.invokeApi --input /tmp/invoke.debug.yaml
```

Without `--input`, ATT looks for `debug.yaml` beside a Template or Flow, or `config/tools/<group>.debug.yaml` for a grouped Tool. Debug inputs use `schemaVersion: att-debug/v1.0` and may contain `case`, `stage`, `inputs`, `arguments`, or grouped `tools.<localKey>.arguments`. Results are isolated under `output/debug/<debugId>/` with `case.log`, `result.yaml`, and `artifacts/`. Exit codes are `0` PASS, `1` FAIL, `2` invalid CLI/config/input, and `3` runtime error. Framework-owned `CASE.*`, `RUN.*`, `ACTIONS.*`, `TOOL.*`, and `DB.*` values cannot be overwritten by debug data.

## V3 Model

```text
test case --1:n stage--> template --1:n action--> tool
                                      └── flow --1:n action
```

- Testcases come from one or more configured Excel sheet groups.
- A template is a directory containing `template.yaml`.
- Stage template cells are YAML maps with `name`, or scalar shorthand such as `PAYMENT_INVOKE`.
- A template selector first resolves a symbolic `template.yaml.name`, then a path relative to `templates.root`.
- Stage `runWhen` defaults to `normal`; V3 Action `runWhen` is an optional boolean expression. Stage/action `onFailure` defaults to `stop` and accepts only `stop` or `continue`.
- Template and Flow Actions use canonical `EXEC`/`META` roots plus the current Action's local `output`. Root-level `TOOL.*` and `DB.*` may remain in internal or persisted historical/result compatibility views, but are not supported general expression APIs; `EXEC.TOOL`, `EXEC.DB`, `EXEC.MQ`, `EXEC.OUTPUT`, and `EXEC.STAGES` are not canonical Context nodes. The complete property reference is in the V3 Reference Manual.
- Current Stage input is exposed through canonical `EXEC.INPUT`; completed Action results are exposed through the current scope's `EXEC.ACTIONS`. A Flow enters a fresh Action scope and restores its parent on return; explicit `EXEC.VARS` assignments are the supported cross-Flow data channel. Curated helper identity may be exposed through `META.TOOL`, `META.DBHELPER`, or `META.MQHELPER`; mutable helper/resource state remains internal. Stage/Template/Flow history is retained as result evidence, not a reusable expression namespace.
- V2.6 Tool argument descriptors contain `name`, `description`, `required`, optional `argName`, and optional `argNameMode: once|repeat`. Multi-value calls pass YAML arrays directly.
- `N/A`, `NA`, `NULL`, and `NONE` normalize to blank strings.

See the [V3 System Design](docs/02_System_Design_V3.md), [V2.6.2 Tool System Design](docs/02_System_Design_V2.6.2.md), and [V2.5 Database Helper System Design](docs/history/02_System_Design_V2.5.md) for normative specifications.
See the [ATT V5.3.1 Reference Manual](docs/09_Reference_Manual_V3.md) and [ATT V5.3.1 Quick Start](docs/08_Quick_Start_V3.md) for operation and authoring guidance.

### Load V1 (5.3.1)

For the complete load configuration matrix and copyable target, threshold, evidence, DB/MQ pooling, and diagnostic examples, see [`examples/load/README.md`](examples/load/README.md). The two smoke scenarios above finish in a few seconds; `closed.yaml` and `arrival-rate.yaml` remain realistic longer-running examples.

Load scenarios are validated before scheduling and write isolated summaries and an offline report below `output/load/<runId>/`:

```sh
./att.sh load examples/load/closed.yaml
./att.sh load examples/load/arrival-rate.yaml --format json
./att.sh load examples/load/tool.yaml --duration 100ms --run-id load-tool-example
```

Use `load.users` for the closed-VU model, or `load.arrivalRate` with `maxConcurrent` and `overloadPolicy: drop` for fixed arrivals. Reusable Templates, Flows, and Tools read `EXEC.INPUT`, `EXEC.VARS`, `EXEC.ACTIONS`, and action-local `output`; load identity is only under `EXEC.LOAD.*`. See [`examples/load/README.md`](examples/load/README.md) for copyable configurations, CLI overrides, thresholds, evidence, DB/MQ pooling, and the release-gate checks.

### Multi-environment profiles (5.3.1)

Use one common `att-config/v2.6` file with an `environments` map and select a profile with `--env`:

```sh
./att.sh validate --config config/config.yaml --env SIT --package
./att.sh run --config config/config.yaml --env UAT --all
./att.sh debug template PAYMENT_INVOKE --config config/config.yaml --env UAT
./att.sh load examples/load/closed-smoke.yaml --config config/config.yaml --env SIT
```

Only `dbhelpers` and `mqhelpers` are shallow, typed profile overlays. The common Template/Flow/Action definitions keep stable logical IDs such as `orders` and `payment`; credentials remain `${ENV:NAME}` references and are never copied into profile metadata or reports. Existing complete-config commands such as `--config config/environments/uat.yaml` remain compatible. See [`examples/environments/README.md`](examples/environments/README.md) for the full contract, migration, CI, and scenario guidance.

For environments that intentionally need different package roots or runtime/report policy, keep using a complete top-level config:

```sh
./att.sh validate --config config/environments/sit.yaml --package
./att.sh run --config config/environments/uat.yaml --all
```
