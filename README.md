# ATT 2.1.2 - Automated Testing Tool

ATT V2.1 loads grouped Excel testcases through mandatory strict-schema sidecar YAML, executes template actions and external tools, and produces atomic completed runs, result workbooks, offline HTML reports, JSON/JUnit CI output, logs, and verified run archives.

V2.1 retains the V2 Case → Stage → Template → Action → Tool model while requiring explicit V2.1 schema versions, rejecting unknown fields, validating identifiers and paths, and distinguishing business FAIL from execution ERROR.

`testcase.root` defaults to `testcase`. ATT recursively discovers adjacent `basename.yaml` + `basename.xlsx` pairs below it; each pair is one testcase set.

## Quick Start

```sh
./att.sh validate --package
./att.sh run --all
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

`./att.sh docs` always produces one self-contained page at `build/docs/index.html`; tool and built-in sections have top indexes, and search filters by workbook, sheet, Case ID, template, or tool. `--single-page` is not a supported option. `./att.sh clean` removes the configured `outputDirectory`, `build/docs`, and `build/att-*.tar.gz`, while preserving testcase, template, tool, configuration, and documentation source files.

## V2.1 essentials

- `schemaVersion` is mandatory in global configuration, workbook sidecars, and templates. Unknown non-`x-*` fields are validation errors.
- `validate --package` is the default full-package check; `validate --selected` checks only the selected case/suite/tag dependency closure.
- Timeouts use milliseconds with range 1–3,600,000 and default 10,000: global `timeoutMs`, optional sidecar `timeoutMs`, then tool-action `timeoutMs` from highest to lowest precedence.
- Retry is available only on a tool action and only for `retryOn: [EXIT_CODE]`; retries run immediately and timeout is never retried.
- A valid Run ID and full Case ID are used directly as `output/<RunID>/<CaseID>/` directory names. They must not contain path separators, control characters, or platform-reserved names.
- Tool command templates are tokenized before declared arguments are injected as atomic argv values; resolved values are never tokenized again and no shell is used. Prefer `${argument}` or `${input.argument}` with exact case-sensitive argument keys. Tools write results to stdout and diagnostics to stderr; ATT records input/argv/stdout/stderr in case evidence and creates a dedicated raw-stdout artifact only when the action sets `saveAs`.

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
- Tool argument descriptors contain `name`, `description`, `required`, and optional final-argument `delimit`.
- `N/A`, `NA`, `NULL`, and `NONE` normalize to blank strings.

See [V2.1 System Design](docs/02_System_Design_V2.1.md) for the normative specification.
See the [ATT V2.1 Reference Manual](docs/09_Reference_Manual_V2.md) and [ATT V2.1 Quick Start](docs/08_Quick_Start_V2.md) for operation and authoring guidance.
