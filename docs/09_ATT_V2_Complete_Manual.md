# ATT V2 User Manual

**Author: Jeffrey + ChatGPT**
**Version:** 2.0

ATT is an offline, template-driven API test runner. A workbook row becomes a test case; each configured stage selects a template; template actions render files, invoke configured tools, assert outcomes, or write structured logs.

```text
workbook row → ordered stages → template actions → tools → report and artifacts
```

## Run ATT

JDK 8 or later is required; Maven is optional for source users.

```sh
./att.sh validate --all
./att.sh run --all
./att.sh run --suite testcase/payment_regression.xlsx --tag smoke
./att.sh run --all --case payment.TC001 --run-id SIT-001
./att.sh run --all --dry-run
```

`--exclude-tag`, `--fail-fast`, `--rerun-failed`, `--output-dir`, `--format json`, and `--quiet` are also available. Run `./att.sh help` for the command synopsis.

## Package layout

```text
config/config.yaml             global tools and defaults
testcase/<suite>.xlsx          test cases in one or more sheet groups
testcase/<suite>.yaml          mandatory workbook sidecar
templates/<name>/template.yaml ordered template actions
tools/*.sh                     external programs called by configured tools
output/<run-id>/               report, logs, case data, artifacts, results
```

Each workbook requires a same-basename sidecar. The bundled sample contains 22 executable cases in `payment` and `batch`, with IDs such as `payment.TC001` and `batch.TC001`.

## Configuration and tools

`config/config.yaml` defines shared settings and tools. Tools receive generated `${TOOL.inputFile}` and `${TOOL.outputFile}` paths; declared arguments are validated before the command runs.

```yaml
tools:
  invokePaymentApi:
    command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
    arguments:
      requestFile: {name: Request File, description: Rendered XML, required: true}
      environment: {name: Environment, description: Target environment, required: true}
```

`output` is `txt`, `yaml`, or `xml`. YAML/XML output becomes structured action output; text remains a string. The final declared argument may use `delimit` to expand a comma-separated value safely.

## Workbook sidecar and stages

The sidecar maps physical Excel headers to runtime keys and lists stages in execution order.

```yaml
excel:
  sheet: payment=支付測試案例集, batch=批量測試案例集
  caseId: 案例編號
  tags: 標籤
  dataColumns: caseName=案例名稱, amount=金額, 預期結果(yaml)
stages:
  - {key: prepare, template: 準備模板, required: false, runWhen: normal, onFailure: stop}
  - {key: invoke, template: 執行模板, dataColumns: channel=渠道, required: true, runWhen: normal, onFailure: stop}
  - {key: verify, template: 驗證模板, required: true, runWhen: onSuccess, onFailure: stop}
```

Stage template cells are YAML maps with a `name`, for example `name: PAYMENT_INVOKE`. A `(yaml)` sidecar spec means the matching physical header is parsed as YAML (the Excel header itself has no `(yaml)` suffix). `N/A`, `NA`, `NULL`, and `NONE` normalize to blank.

## Templates, context, and expressions

Every template is a directory containing `template.yaml`. Its ordered action map supports `render`, `tool`, `assert`, and `log` actions.

```yaml
name: PAYMENT_INVOKE
actions:
  renderRequest: {type: render, payload: payload.xml, saveAs: request.xml}
  callApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile}, environment=${CASE.environment})}"
  checkStatus: {type: assert, expression: "${ACTIONS.callApi.output.Response.Status} == 'SUCCESS'"}
```

`${path}` reads context: `${CASE.caseId}`, `${CASE.amount}`, `${CASE.STAGES.invoke.channel}`, `${ACTIONS.callApi.output.Response.Status}`, `${RUN.id}`, and `${TOOL.output}` are common examples. List results support `${CASE.items[0].status}` and `${CASE.items.0.status}`.

`#{name(...)}` invokes a configured tool or a built-in function. Built-ins are `upper`, `lower`, `trim`, `string`, `number`, `boolean`, `length`, `concat`, and `coalesce`. Arguments may be named or positional, with string (`'abc'`), number (`12.50`), and boolean (`true`) literals.

Assertions support `==`, `!=`, `>`, `>=`, `<`, `<=`, `like`, `is [not] null`, parentheses, and `and` / `or` / `not`. Numeric literals compare numerically; boolean literals support equality.

An exact Context tool argument such as `account=${CASE.account}` preserves its original type and leading zeroes; quoting it explicitly forces a string. Assertions resolve Context as typed operands, so a value containing words such as `and` or `or` cannot alter the expression grammar.

## Reports, artifacts, and troubleshooting

`output/<run-id>/report/index.html` is a single self-contained page with summary cards, group and case tables, expandable case detail, logs, and artifact links. The run directory also contains `events.jsonl`, `run.yaml`, each case’s `case.yaml`, action input/output files, and result workbooks.

Use `./att.sh report --run-id <id>` to regenerate a report, `./att.sh docs` to generate local package reference at `build/docs/index.html`, and `./att.sh build` to archive the latest completed run.

`./att.sh docs --single-page` additionally selects `build/docs/single-page.html`, a self-contained searchable reference suitable for sharing. Run reports are always generated as a single offline page.

Run `validate` before execution. `FAIL` means an assertion was false; `ERROR` means loading, rendering, parsing, or a tool failed. Inspect report detail, then `case.yaml`, action files, and the case log.

See [Test Case Development Guide](08_Quick_Start_for_Case_Authors.md) and [V2 System Design](02_System_Design_V2.0.md).

## Complete configuration reference

Global `config/config.yaml` owns runtime defaults only: `outputDirectory`, `environment`, `timeoutSeconds`, `run.id.default`, `run.id.timestampFormat`, `templates.root`, `report`, and `tools`. Workbook parsing never falls back to global defaults. Every tool requires `name`, `description`, `command`, `output`, and an `arguments` map. Each argument requires `name`, `description`, and `required`; only the final argument may declare non-empty `delimit`.

The adjacent sidecar owns `excel`, ordered `stages`, optional `actionDefaults`, and result-workbook `report` columns. `excel.sheet` accepts either one implicit default sheet or comma-separated `groupId=sheetName` entries. `excel.caseId` and `excel.tags` are mandatory structural columns. `dataColumns` entries are `alias=Column`, `Column`, `alias=Column(yaml)`, or `Column(yaml)`; CSV-style double quoting protects commas, equals signs and literal parentheses.

Every stage requires `key` and `template`. Optional fields are `dataColumns`, `required`, `runWhen` (`normal`, `onSuccess`, `onFailure`, `always`) and `onFailure` (`stop`, `continue`). Stage and Action IDs cannot contain `.` because dots separate Context path components.

## Context tree and data ownership

```text
CASE
├── caseId, groupId, rowCaseId, workbook, sheet, rowNumber, tags
├── <case data columns>
└── STAGES.<stageKey>
    ├── <stage data and every template-cell YAML key>
    └── TEMPLATE
        └── ACTIONS.<actionId>.input/output/status/files/log data
```

`${CASE...}` is authoritative. `${ACTIONS.<id>...}` is a current-template convenience view. `${TOOL.input}`, `${TOOL.output}`, `${TOOL.inputFile}`, and `${TOOL.outputFile}` exist while a tool is executing. Unknown paths render as blank. `N/A`, `NA`, `NULL`, `NONE`, empty and whitespace-only cells normalize to blank; tool input serializes blank as `""`.

## Expression and tool-call grammar

`${...}` reads Context without side effects. `#{...}` invokes a configured tool or built-in function. Quoted string literals retain spaces and commas, numbers become numeric values, and `true`/`false` remain booleans in tool input. Named external-tool arguments are mandatory and checked before execution; missing required, unknown and duplicate parameters fail validation.

Assertions use `==`, `!=`, `>`, `>=`, `<`, `<=`, `like`, `is null`, `is not null`, parentheses, `not`, `and`, and `or`. Quote business strings. Numeric comparisons are numeric when both operands are numeric. Boolean ordering is invalid.

## Outputs, status and diagnostics

`PASS` means all executed assertions/actions succeeded; `FAIL` means an assertion failed; `ERROR` means configuration, rendering, parsing or tool execution failed; `SKIPPED` is used by dry run; `INVALID` is reserved for invalid case data. CLI exit codes are `0` success, `1` test failure, `2` validation/configuration error, and `3` runtime error.

Each run contains `run.yaml`, `events.jsonl`, `report/index.html`, result workbooks, and one directory per full Case ID with `case.yaml`, logs and action artifacts. The report includes reconciled totals, pass rate, wall/aggregate/min/max/average duration, group summaries, search/filter, the persisted execution tree and detailed logs. `build` packages the latest completed run, configuration snapshot, README and manifest.

## Advanced example and best practices

The checked-in 22-case workbook demonstrates two Chinese sheet groups, duplicate row IDs safely scoped by group, optional prepare, Chinese symbolic/full-path templates, stage YAML data, tags, blank markers, rollback selectors and always-run cleanup. Keep production templates deterministic, store large request bodies beside `template.yaml`, give actions stable IDs, prefer files over large command arguments, avoid secrets in workbook cells, and validate before every run.

For failure compensation, configure a normal stage with `onFailure: stop`, a later `runWhen: onFailure` stage, and an independent `runWhen: always` cleanup stage. `onFailure: continue` records failure but permits later normal work; it does not erase the failure state.

## Troubleshooting

| Symptom | Check |
|---|---|
| Sidecar missing | Workbook and YAML basename and directory must match |
| Unknown template | Check symbolic `name`, relative template path and `template.yaml` |
| Empty Context value | Check uppercase concept keywords and dot-free IDs |
| Tool argument rejected | Compare call names with the tool `arguments` contract |
| YAML cell error | Ensure the cell is a mapping and stage template cell includes `name` |
| `onFailure` stage skipped | Confirm an earlier executed stage actually failed |
| Offline link broken | Regenerate report/docs and keep the run or docs directory together |
