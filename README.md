# ATT V2.1 - Automated Testing Tool

ATT V2.1 loads grouped Excel testcases through mandatory strict-schema sidecar YAML, executes template actions and external tools, and produces atomic completed runs, result workbooks, offline HTML reports, JSON/JUnit CI output, logs, and verified run archives.

V2.1 retains the V2 Case → Stage → Template → Action → Tool model while requiring explicit V2.1 schema versions, rejecting unknown fields, validating identifiers and paths, and distinguishing business FAIL from execution ERROR.

## Quick Start

Requires Java 8 or newer. Maven is optional; `att.sh` can compile with `javac` when running from source.

```sh
./att.sh validate --all
./att.sh run --all
```

Run one workbook, full Case ID, or tag:

```sh
./att.sh run --suite testcase/payment_regression.xlsx
./att.sh run --all --case payment.TC001
./att.sh run --all --tag smoke --exclude-tag slow
```

Every workbook requires a same-basename sidecar. The included example contains 22 cases across Chinese `payment` and `batch` sheets; both may contain row Case ID `TC001`, producing `payment.TC001` and `batch.TC001`.

## Commands

```sh
./att.sh                 # help
./att.sh validate --all
./att.sh run --all
./att.sh report --run-id <RunID>
./att.sh docs
./att.sh build
./att.sh clean
```

- Reports: `output/<RunID>/report/index.html`
- Result workbooks: `output/<RunID>/workbooks/`
- Package documentation: `build/docs/index.html`
- Latest completed-run archive: `build/att-run-<RunID>.tar.gz`

`./att.sh docs` always produces one self-contained searchable page at `build/docs/index.html`; `--single-page` is not a supported option. `./att.sh clean` removes end-user output (`output`, `report`, `logs`, `build/docs`, and `build/att-*.tar.gz`) while preserving development `target`/`dist` content. `./build.sh clean` separately removes only `target` and `dist`.

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
See the [ATT V2 Reference Manual](docs/09_Reference_Manual_V2.md) and [ATT V2 Quick Start](docs/08_Quick_Start_V2.md) for operation and authoring guidance.
