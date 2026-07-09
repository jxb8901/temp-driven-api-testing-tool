# ATT - Automated Testing Tool

ATT V1.4 runs Excel-driven API test suites through suite-local configuration, reusable Test Case Templates, and ordered Template Actions.

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

## V1.4 Scope

- Suite-local config beside Excel workbooks, for example `testcase/payment_regression.yaml`.
- The suite sidecar must travel with the Excel workbook and explicitly declare the Test Case Template.
- Chinese or mixed-language Excel workbook names, sheet names, and column headers are supported.
- `testcase.headerRows` may be used to declare multi-row headers; the effective column name is resolved from the last non-empty cell in each header column.
- `testcase.columns` mappings must support Chinese source headers and Chinese display names without assuming English field names.
- Template cells may contain multiple lines: the first line is the template name, and the remaining lines are remark text.
- The template name may be Chinese or mixed-language.
- Remark text is injected into case context separately and can be written into case logs.
- `Case Data` is shared across all stages, and stage-specific values may override shared keys for that stage.
- V1.4 log levels use a detail-filter model: the same log path is kept, while case, stage, and action sections vary by detail level.
- Test Case Template defaults under `templates/testcase/<templateId>/template.yaml`.
- Shared stage templates under `templates/<template>/`, with `templates/stage/<template>/` kept as a compatibility fallback.
- `${...}` context references and `#{...}` tool calls.
- Action outputs referenced by Action ID, for example `${ACTIONS.invoke.callApi.output.Response.Status}`.
- Render actions can save long output to files, for example `${ACTIONS.invoke.renderRequest.outputFile}`.
- Tool definitions support `arguments` as a parameter contract for supported parameter names, types, required flags, and defaults, plus optional variadic `argv` for command-line values.
- Single case execution log under `output/<RunID>/<CaseID>/`.
- Result workbook generated in the RunID directory.
- Run history through `run.yaml` and `output/latest-run.yaml`.

## V1.4 Notes

- V1.4 treats missing suite sidecar config as an error.
- V1.4 does not infer a workbook's Test Case Template from `config/config.yaml`.
- V1.4 keeps internal runtime keys stable while accepting localized workbook and column labels at the Excel boundary.
- V1.4 should treat `testcase.headerRows` as `1` by default, so existing single-row workbooks remain valid.
- V1.4 should treat shared `Case Data` as the base context and stage data as a per-stage overlay.
- V1.4 should treat multi-line template cell remarks as stage context that can be referenced by templates and recorded in logs.
