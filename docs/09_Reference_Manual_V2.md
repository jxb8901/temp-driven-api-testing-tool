# ATT V2 Reference Manual

Author: Jeffrey + ChatGPT  
Version: 2.0  
Status: Stable reference

This is the complete English reference for ATT V2. It combines the user manual and configuration reference and describes the implemented V2 model, design principles, configuration, execution, reporting, documentation, and packaging behavior.

ATT is an offline, template-driven API test runner. A workbook row becomes a test case; ordered stages select templates; ordered actions render files, invoke tools, assert results, or write structured logs.

    test case --1:n stages--> template --1:n actions--> tool

Test cases, templates, and tools are the primary concepts. A stage belongs to a test case and controls template selection and order. An action belongs to a template and controls operations and tool invocation order.

## 1. Design principles

V2 continues the established ATT terms, command/output tool model, Java package organization, and ordered stage/template/action execution model. It deliberately avoids compatibility aliases and unrelated workflow-engine abstractions.

The primary ownership rule is:

- testcase owns ordered stages;
- stage selects a template and contributes stage-private data;
- template owns ordered actions;
- action invokes a tool or performs render, assertion, or logging;
- tool is a reusable external capability configured once globally.

Configuration is explicit. Every workbook has a mandatory sidecar; templates are directories identified by template.yaml; validation reports errors before tools execute; and the persisted runtime tree has one CASE root.

## 2. Release layout and CLI

A release contains att.sh, config/config.yaml, testcase/, templates/, tools/, output/, lib/att-v2.0.jar, and dependency jars under lib/. The application is packaged as a JAR; no application classes directory is required.

    ./att.sh help
    ./att.sh validate --all
    ./att.sh run --all
    ./att.sh run --suite testcase/payment_regression.xlsx --tag smoke
    ./att.sh run --all --case payment.TC001 --run-id SIT-001
    ./att.sh run --all --dry-run
    ./att.sh report --run-id SIT-001
    ./att.sh docs --single-page
    ./att.sh build

Selection options include --suite, --suite-dir, --all, --case, --tag, and --exclude-tag. Execution options include --run-id, --output-dir, --dry-run, --fail-fast, and --rerun-failed. Output options include --format human|json, --quiet, and --verbose.

Exit codes are 0 for success, 1 for assertion failure, 2 for configuration/validation errors, and 3 for runtime errors.

## 3. Global configuration

config/config.yaml owns runtime defaults, template root, report defaults, run ID behavior, and tool contracts. It must not define workbook sheets, case ID columns, data columns, or stages.

Supported fields are outputDirectory, reportDirectory, logDirectory, environment, timeoutSeconds, run.id.default, run.id.timestampFormat, templates.root, report, and tools.

    outputDirectory: output
    environment: SIT
    timeoutSeconds: 120
    templates:
      root: templates
    tools:
      invokePaymentApi:
        name: Invoke Payment API
        description: Invoke a rendered payment request
        command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
        output: xml
        arguments:
          requestFile:
            name: Request File
            description: Rendered request file
            required: true
          environment:
            name: Environment
            description: Target environment
            required: true

V2 does not support argv. Tool arguments are metadata for validation and generated tool reference pages. Each argument requires name, description, and required; only the final argument may define delimit.

## 4. Workbook sidecar

Every workbook requires an adjacent same-basename sidecar. For payment_regression.xlsx, ATT requires payment_regression.yaml in the same directory.

    excel:
      sheet: payment=支付測試案例集, batch=批量測試案例集
      caseId: 案例編號
      tags: 標籤
      dataColumns: caseName=案例名稱, amount=金額, 預期結果(yaml)

    stages:
      - key: invoke
        template: 執行模板
        dataColumns: channel=渠道, 執行參數(yaml)
        required: true
        onFailure: stop
        runWhen: normal

excel.sheet accepts comma-separated groupId=sheetName entries. With one sheet, the group ID may be omitted and ATT uses default. The full Case ID is always groupId.rowCaseId and must be unique across the workbook.

excel.caseId and excel.tags are mandatory structural columns. tags is outside dataColumns; blank tags are valid. V2 has no configurable required-column list.

dataColumns supports alias=ColumnName, ColumnName, alias=ColumnName(yaml), and ColumnName(yaml). Double quotes protect commas, equals signs, and parentheses. YAML cells retain maps, lists, numbers, booleans, and strings.

## 5. Stages and template selection

Each stage requires key and template. The template field names the physical Excel column whose cell value must be a YAML mapping with a non-blank name, for example name: PAYMENT_INVOKE and retry: 2.

The name value selects one template in exactly one of two ways: it may match the symbolic name declared by a template.yaml, or it may be a complete template directory path relative to templates.root, such as payment/local/CT001.

All key-value pairs in the cell mapping, including name, are copied directly into the stage data tree. stages[].dataColumns adds stage-private data. Duplicate keys are validation errors.

required: true makes a blank selector an error. A blank selector on an optional stage skips that stage. runWhen supports normal, onSuccess, onFailure, and always; onFailure supports stop and continue. V2 does not define name, templateColumn, or a fixed sidecar template selector.

## 6. Templates and actions

A template is a directory if and only if it directly contains template.yaml. Nested and Unicode/Chinese paths are supported. The descriptor contains an ordered YAML actions map.

    name: PAYMENT_INVOKE
    description: Render and invoke a payment request
    actions:
      renderRequest:
        type: render
        payload: request.tmp.xml
        saveAs: request.xml
      callApi:
        type: tool
        call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile}, environment=${CASE.environment})}"
      checkStatus:
        type: assert
        expression: "${ACTIONS.callApi.output.Response.Status} == 'SUCCESS'"

Supported action types are render, tool, assert, and log. Action IDs are unique within a template and cannot contain a dot. Optional request files request.tmp.xml, request.tmp.json, and request.tmp.yaml live beside template.yaml and are rendered as UTF-8.

## 7. Runtime Context

The authoritative persisted tree is rooted at CASE:

    CASE
    ├── caseId, groupId, rowCaseId, workbook, sheet, rowNumber, tags
    ├── status, startedAt, durationMs, error
    ├── <case data columns>
    └── STAGES
        └── <stageKey>
            ├── <stage data and template-cell YAML keys>
            └── TEMPLATE
                └── ACTIONS
                    └── <actionId>
                        ├── action metadata
                        └── TOOL
                            └── <toolName>
                                └── input/output/status/log/artifact data

Core keywords are uppercase: CASE, STAGES, TEMPLATE, ACTIONS, and TOOL. Metadata properties retain camelCase, such as caseId, groupId, startedAt, durationMs, and outputFile. There are no CASE.fields, CASE.data, or TOOLS nodes.

Normative references include ${CASE.caseId}, ${CASE.amount}, ${CASE.STAGES.invoke.channel}, and ${CASE.STAGES.invoke.TEMPLATE.ACTIONS.callApi.TOOL.invokePaymentApi.output}. ${ACTIONS.<actionId>...} is a current-template convenience view. ${TOOL.input}, ${TOOL.output}, ${TOOL.inputFile}, and ${TOOL.outputFile} are transient views while a tool is running.

## 8. Expressions and built-in functions

${path} performs a read-only Context lookup. #{name(...)} invokes a configured external tool or built-in function. Exact Context arguments preserve their original string, number, or boolean type. Supported literals are quoted strings, numbers, and true/false.

Assertions support ==, !=, >, >=, <, <=, like, is null, is not null, parentheses, not, and, and or.

Built-ins:

| Function | Purpose | Example |
|---|---|---|
| upper | Convert text to upper case | #{upper(value=${CASE.currency})} |
| lower | Convert text to lower case | #{lower(value=${CASE.channel})} |
| trim | Remove surrounding whitespace | #{trim(value=${CASE.reference})} |
| string | Convert a value to text | #{string(value=${CASE.amount})} |
| number | Parse and normalize a number | #{number(value='12.50')} |
| boolean | Convert true/false, yes/no, or 1/0 | #{boolean(yes)} |
| length | Return text length | #{length(value=${CASE.reference})} |
| concat | Concatenate arguments in order | #{concat(a='PAY', b=${CASE.caseId})} |
| coalesce | Return the first non-blank value | #{coalesce(${CASE.optional}, 'N/A')} |

## 9. Tool configuration and execution

A tool action supplies named arguments that must match the configured contract. Unknown, duplicate, or missing required arguments fail validation. Argument descriptions do not inject execution values.

The final argument may declare delimit. ATT normalizes the value, splits it into an ordered array, and passes each element as a separate process argument. N/A, NA, NULL, NONE, empty cells, and whitespace-only values are blank; blank values passed to scripts become the empty string.

Tool output types are txt, yaml, and xml. YAML and XML output become structured values; text output remains a string. Tool input, command, stdout, stderr, exit code, status, duration, parsed output, and artifact paths are recorded under TOOL.toolName.

## 10. End-to-end development example

A typical workbook row contains TC001, tags smoke,payment, amount 100, an expected YAML value such as status: SUCCESS, and stage template cells name: PAYMENT_INVOKE and name: PAYMENT_VERIFY.

A matching sidecar declares excel.sheet, excel.caseId, excel.tags, case dataColumns, and ordered invoke and verify stages. The invoke template renders request.tmp.xml and calls invokePaymentApi; the verify template reads the canonical CASE.STAGES.invoke.TEMPLATE.ACTIONS.callApi.TOOL.invokePaymentApi.output path and asserts the expected status.

A request file can be:

    <PaymentRequest>
      <CaseId>${CASE.caseId}</CaseId>
      <Amount>${CASE.amount}</Amount>
    </PaymentRequest>

Run validate first, execute the selected Case ID, inspect case.yaml and the HTML report, then package the latest completed run with ./att.sh build.

## 11. Validation, reports, documentation, and packaging

./att.sh validate --all checks sidecars, sheets, structural columns, duplicate full Case IDs, YAML stage mappings, recursive template directories, descriptors, action IDs, tool calls, and tool contracts before any tool executes.

Each run contains run.yaml, events.jsonl, result workbooks, one directory per full Case ID, case.yaml, case logs, action files, and report/index.html. The single-page report includes total/pass/fail/error/skip/invalid counts, pass rate, start/end time, wall and aggregate duration, minimum/maximum/average duration, group summaries, filters, the persisted execution tree, detailed logs, and artifact links.

./att.sh docs generates JavaDoc-like testcase/template/tool indexes. ./att.sh docs --single-page also generates a self-contained searchable build/docs/single-page.html. ./att.sh build archives the latest completed run, report, workbooks, case logs, configuration snapshot, and manifest.

## 12. Best practices and troubleshooting

- Validate before every run and keep each workbook and sidecar together.
- Use stable, dot-free stage and action IDs.
- Use symbolic template names for reusable business templates and full paths for unambiguous categorized templates.
- Keep large request bodies in template directories.
- Use onFailure stages for compensation/diagnostics and runWhen: always for cleanup.
- Keep tool outputs deterministic and redact secrets from logs and reports.
- Use tags for repeatable smoke, regression, and batch selections.
- Use canonical CASE paths for persisted cross-stage references; use ACTIONS and TOOL for local convenience.

For a missing sidecar, check workbook and YAML basenames and directory. For a missing sheet or column, compare physical headers with excel.sheet, caseId, tags, and dataColumns. For an unknown template, check template.yaml.name or the relative template directory path. For an empty Context value, check uppercase keywords and dot-free IDs. For a rejected tool argument, compare the action call with the tool arguments contract. For offline links, keep the generated run/docs directory together and regenerate if needed.

For the concise authoring workflow, see [ATT V2 Quick Start](08_Quick_Start_V2.md). For architecture and acceptance criteria, see [ATT V2 System Design](02_System_Design_V2.0.md).
