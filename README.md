# ATT V2 - Automated Testing Tool

ATT V2 loads grouped Excel testcases through mandatory sidecar YAML, executes template actions and external tools, and produces result workbooks, offline HTML reports, documentation, logs, and completed-run archives.

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
./att.sh docs --single-page
./att.sh build
```

- Reports: `output/<RunID>/report/index.html`
- Result workbooks: `output/<RunID>/workbooks/`
- Package documentation: `build/docs/index.html`
- Single-page documentation: `build/docs/single-page.html`
- Latest completed-run archive: `dist/att-<RunID>.tar.gz`

## V2 Model

```text
test case --1:n stage--> template --1:n action--> tool
```

- Testcases come from one or more configured Excel sheet groups.
- A template is a directory containing `template.yaml`.
- Stage template cells are YAML maps with a mandatory `name` key.
- Runtime data is persisted under the `CASE.STAGES.<key>.TEMPLATE.ACTIONS.<actionId>` tree.
- Tool argument descriptors contain `name`, `description`, `required`, and optional final-argument `delimit`.
- `N/A`, `NA`, `NULL`, and `NONE` normalize to blank strings.

See [V2 System Design](docs/02_System_Design_V2.0.md) for the normative specification.
See the [ATT V2 Reference Manual](docs/09_Reference_Manual_V2.md) and [ATT V2 Quick Start](docs/08_Quick_Start_V2.md) for operation and authoring guidance.
