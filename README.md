# ATT - Automated Testing Tool

ATT V1.2 runs Excel-driven API test suites through configurable stages and Tool Invocation Templates.

## Run From Source

Requires JDK 8 or JDK 11. Maven is optional for development builds.

```sh
./att.sh --config config/config.yaml --suite testcase/payment_regression.xlsx
```

Useful selectors:

```sh
./att.sh --suite-dir testcase
./att.sh --tag smoke --exclude-tag slow
./att.sh --case-id TC001
./att.sh --rerun-failed
./att.sh --dry-run
```

## Build Release Package

The release package contains `att.sh`, compiled classes, dependency jars, config, templates, tools, testcase examples, and docs.

```sh
scripts/build-release.sh
```

The package is generated under `dist/`, for example:

```text
dist/att-v1.2.tar.gz
```

After download or copy:

```sh
tar -xzf att-v1.2.tar.gz
cd att-v1.2
./att.sh --suite testcase/payment_regression.xlsx
```

## V1.2 Scope

- Excel case loading with configured stage template columns.
- Unified Tool Invocation Templates under `templates/stage/`.
- `${...}` context references and `#{...}` tool calls.
- Action outputs referenced by invocation ID, for example `${TOOLS.invokeApi.output.Response.Status}`.
- Single case execution log under `output/<RunID>/<CaseID>/`.
- Result workbook generated in the RunID directory.
- Run history through `run.yaml` and `output/latest-run.yaml`.
