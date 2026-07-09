# ATT - Automated Testing Tool

ATT V1.3 runs Excel-driven API test suites through suite-local configuration, reusable Test Case Templates, and ordered Template Actions.

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
dist/att-v1.3.tar.gz
```

After download or copy:

```sh
tar -xzf att-v1.3.tar.gz
cd att-v1.3
./att.sh --suite testcase/payment_regression.xlsx
```

## V1.3 Scope

- Suite-local config beside Excel workbooks, for example `testcase/payment_regression.yaml`.
- Test Case Template defaults under `templates/testcase/<templateId>/template.yaml`.
- Shared stage templates under `templates/<template>/`, with `templates/stage/<template>/` kept as a compatibility fallback.
- `${...}` context references and `#{...}` tool calls.
- Action outputs referenced by Action ID, for example `${ACTIONS.invoke.callApi.output.Response.Status}`.
- Render actions can save long output to files, for example `${ACTIONS.invoke.renderRequest.outputFile}`.
- Tool definitions support named arguments plus optional variadic `argv`.
- Single case execution log under `output/<RunID>/<CaseID>/`.
- Result workbook generated in the RunID directory.
- Run history through `run.yaml` and `output/latest-run.yaml`.
