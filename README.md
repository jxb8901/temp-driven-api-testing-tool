# ATT 2.3.3 - Automated Testing Tool

ATT V2.3.3 loads grouped Excel testcases through mandatory strict-schema sidecar YAML, executes template actions and local or SSH external tools, and produces atomic completed runs, result workbooks, offline HTML reports, JSON/JUnit CI output, logs, and verified run archives.

V2.3 retains the V2 Case → Stage → Template → Action → Tool model and all V2.2 tool-group, argv-list, SSH, Windows, shorthand, and built-in capabilities. It adds multi-file render globs and typed render results, nests every action outcome under `action.output`, lets `assert` decide non-assert action PASS/FAIL, partially evaluates action descriptions during validation, and records explicit Expected/Actual report values. Templates use `att-template/v2.3`; configuration/tool-group schemas remain V2.2 and sidecar/run/CI schemas retain their existing versions.

`testcase.root` defaults to `testcase`. ATT recursively discovers adjacent `basename.yaml` + `basename.xlsx` pairs below it; each pair is one testcase set.

## Quick Start

```sh
./att.sh validate --package
./att.sh run --all
```

On Windows, run the same commands through `att.bat`:

```bat
att.bat validate --package
att.bat run --all
```

Run one workbook, full Case ID, or tag:

```sh
./att.sh validate --selected --case payment.payment.TC001
./att.sh validate --package --format json
./att.sh run --suite testcase/payment_regression.xlsx
./att.sh run --all --case payment.payment.TC001
./att.sh run --all --tag smoke --exclude-tag slow
./att.sh run --all --ci-output junit,json
```

Every workbook requires a same-basename sidecar with a package-unique `id`. The included `payment` workbook contains Chinese `payment` and `batch` sheets; both may contain row Case ID `TC001`, producing `payment.payment.TC001` and `payment.batch.TC001`.

## Commands

```sh
./att.sh                 # help
./att.sh validate --package
./att.sh run --all
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
- Package documentation: `build/docs/index.html`
- Latest completed-run archive: `build/att-run-<RunID>.tar.gz`

`./att.sh docs` always produces one self-contained page at `build/docs/index.html`; Testcases are grouped by workbook and Sheet, and each table includes the validation-time Expected Result assembled from assert actions. Tool and built-in sections have top indexes, and search filters by workbook, sheet, Case ID, template, or tool. `--single-page` is not a supported option. `./att.sh clean` removes the configured `outputDirectory`, `build/docs`, and `build/att-*.tar.gz`, while preserving testcase, template, tool, configuration, and documentation source files.

## V2.3 essentials

- Render actions require a safe template-relative `payload` glob and `renderAs: file|text|json|yaml|xml`. File mode preserves each matched relative path below the Case output directory; other modes store typed values in `ACTIONS.<id>.output.result`.
- Action outcome fields are nested under `output`: `status`, `success`, `durationMs`, `exception`, `targetFiles`, `result`, and optional assertion detail. Use `${output...}` for the current action and `${ACTIONS.<id>.output...}` for completed actions.
- Assert actions use required `assert` instead of `expression` and may declare validation-time `expected` plus runtime `actual`. Only assert actions contribute ordered, LF-preserving Expected/Actual report text.
- Every action supports an expression-bearing `description`; validation resolves known static Case values and preserves runtime placeholders for final evaluation after the action outcome exists.
- Render, tool, and log actions may use `assert` to decide PASS/FAIL. Operational exceptions stay ERROR; a tool's exit code is evidence at `output.exitCode`, not an automatic status decision.
- `${CASE.outputDirectory}` exposes the normalized absolute current Case output directory. Local tools run with that directory as cwd and receive framework-owned `ATT_ROOT_DIR` and `ATT_CASE_OUTPUT_DIR` environment variables, while package-relative `./`/`../` executables are still resolved from the package root. SSH remote cwd remains the remote account default and local-path variables are not injected remotely.

- Global configuration uses `att-config/v2.2`; each file in `toolGroups` uses `att-tool-group/v2.2` and a package-unique `id`. Grouped tools are called as `#{group.tool(...)}` while inline `tools` remain global and unqualified.
- Linux/macOS use `./att.sh`; Windows uses `att.bat`. Both launch the same Java runner and accept the same commands and exit codes. Release packages need only Java 8+; source-tree mode compiles with Maven when it is available.
- Tool `command` and group `script` accept a scalar or argv list. Lists preserve each YAML item as one argument; scalar commands use the existing tokenizer once. Group scripts receive `<tool key> <tool command argv>` after the script argv.
- An argument may declare `argName`, such as `--reference`. When its exact-token placeholder has a non-blank value, ATT emits the name and value as separate atomic argv; a missing/blank optional value emits neither. Omitted or empty `argName` is positional, and optional positional placeholders are likewise omitted when blank.
- Root `ssh` applies to inline global tools; a group's `ssh` applies only to that group. ATT prefers local OpenSSH and automatically warns/falls back to the bundled mwiede/jsch Java client when `ssh` is unavailable. Both use strict host-key checking, optional key files, and a safely quoted remote command; see Reference Manual Chapter 09 for Java algorithm limits.
- Built-ins remain unqualified. V2.3.1 adds regular-file/directory checks, file size, directory creation, copy/move/delete operations, and `randomChoice`; it also includes the V2.3 string, date, conversion, `nvl`, `iif`, and `nchar` functions. File writes reject collisions unless their explicit overwrite option is true, and custom Java built-in providers are not loaded.
- The shipped `fpp` tool group provides POSIX reference scripts for an API-adapter skeleton, SQLPlus pipe-delimited output to XML, and child-script execution with YAML status plus captured stdout/stderr. Replace the API script's marked integration block before production use; provide equivalent commands on Windows.
- A built-in that accepts exactly one value may be written as `#{upper(${CASE.currency})}` instead of `value=...`. A configured tool may omit its argument name only when its configuration declares exactly one argument, for example `#{getAppLogs(${CASE.caseId})}`; multi-argument tools still require names.
- `schemaVersion` is mandatory in global configuration, tool groups, workbook sidecars, and templates. V2.3 templates use `att-template/v2.3`; unknown non-`x-*` fields are validation errors.
- `validate --package` is the default full-package check; `validate --selected` checks only the selected case/suite/tag dependency closure.
- Timeouts use milliseconds with range 1–3,600,000 and default 10,000: global `timeoutMs`, optional sidecar `timeoutMs`, then tool-action `timeoutMs` from highest to lowest precedence.
- Retry is available only on a tool action and only for `retryOn: [EXIT_CODE]`; retries run immediately and timeout is never retried.
- A valid Run ID and full Case ID are used directly as `output/<RunID>/<CaseID>/` directory names. They must not contain path separators, control characters, or platform-reserved names.
- Tool command templates are normalized before declared arguments are injected as atomic argv values; resolved values are never tokenized again. Local tools do not use a shell. Prefer `${argument}` or `${input.argument}` with exact case-sensitive argument keys. Tools write results to stdout and diagnostics to stderr; ATT records logical/executed argv, input/stdout/stderr in case evidence and creates a dedicated raw-stdout artifact only when the action sets `saveAs`.

## V2 Model

```text
test case --1:n stage--> template --1:n action--> tool
```

- Testcases come from one or more configured Excel sheet groups.
- A template is a directory containing `template.yaml`.
- Stage template cells are YAML maps with `name`, or scalar shorthand such as `PAYMENT_INVOKE`.
- A template selector first resolves a symbolic `template.yaml.name`, then a path relative to `templates.root`.
- `runWhen` defaults to `normal` and stage/action `onFailure` defaults to `stop`; action `onFailure` accepts only `stop` or `continue`.
- Context properties are available under uppercase `CASE`, `STAGES`, `TEMPLATE`, `ACTIONS`, and `TOOL` nodes; the complete built-in property reference is in the V2 Reference Manual.
- Runtime data is persisted under the `CASE.STAGES.<key>.TEMPLATE.ACTIONS.<actionId>` tree.
- Tool argument descriptors contain `name`, `description`, `required`, optional `argName`, and optional final-argument `delimit`.
- `N/A`, `NA`, `NULL`, and `NONE` normalize to blank strings.

See [V2.3 System Design](docs/02_System_Design_V2.3.md) for the normative specification.
See the [ATT V2.3.3 Reference Manual](docs/09_Reference_Manual_V2.md) and [ATT V2.3.3 Quick Start](docs/08_Quick_Start_V2.md) for operation and authoring guidance.
