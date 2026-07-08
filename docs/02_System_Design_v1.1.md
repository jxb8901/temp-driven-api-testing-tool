# ATT System Design V1.1

**Version:** V1.1
**Status:** Draft
**Source:** `docs/v1.1.req.JPEG`
**Last Updated:** 2026-07-08

---

# 1. System Overview

## 1.1 Purpose

ATT, short for Automated Testing Tool, provides a lightweight automation layer for XML API SIT/UAT testing.

V1.1 extends the V1 framework by introducing:

- PreCheck before API invocation
- PostCheck after API invocation
- Unified external tool configuration
- Tool output persistence under each case output directory
- SQL-like check expressions for XML, YAML and text outputs

The existing API invocation logic remains outside ATT. ATT only calls the configured external tools and evaluates their outputs.

---

## 1.2 Terminology Changes

| V1 Term | V1.1 Term | Meaning |
|---------|-----------|---------|
| Expected check | PostCheck | Validation after API tool execution |
| Expected Template | PostCheck Template | Template defining post-execution checks |
| Expected Data | PostCheck Data | Case-level data used by PostCheck expressions |

PreCheck and PostCheck use the same check engine and template format. The only difference is when they run.

---

# 2. Overall Architecture

```text
                         Test Suite (Excel)
                                │
                                ▼
                         Load Test Cases
                                │
                                ▼
                  Build Case Runtime Context
                                │
                                ▼
                         PreCheck Engine
                    (Check Template + Tools)
                                │
                  ┌─────────────┴─────────────┐
                  │                           │
                  ▼                           ▼
          PreCheck Failed              PreCheck Passed
                  │                           │
                  ▼                           ▼
          Mark Case Failed          Generate Request XML
                                              │
                                              ▼
                                  API Invocation Execution
                              (api.invocation.yaml in template)
                                              │
                                              ▼
                                      PostCheck Engine
                                  (Check Template + Tools)
                                              │
                                              ▼
                                      Test Case Result
                                              │
                                              ▼
                                       Excel Report
```

Execution remains sequential in V1.1.

All artifacts generated during one case execution are stored under:

```text
output/${CaseID}/
```

---

# 3. Project Structure

```text
project/

├── testcase/
│     payment_regression.xlsx
│
├── templates/
│
│     request/
│         PAYMENT_TRANSFER/
│             template.xml
│             api.invocation.yaml
│             sample.yaml
│             README.md
│
│     check/
│         PAYMENT_PRECHECK/
│             template.yaml
│             README.md
│
│         PAYMENT_POSTCHECK/
│             template.yaml
│             README.md
│
├── config/
│     config.yaml
│
├── tools/
│     get_ac_date.sh
│     get_seq.sh
│     gen_e2e_id.sh
│
├── output/
│     ${CaseID}/
│
├── report/
│
├── logs/
│
├── pom.xml
│
└── src/
      main/
        java/
          com/company/apitest/
            FrameworkRunner.java
```

---

# 4. Test Suite

## 4.1 Workbook

A Test Suite is one Excel workbook.

ATT reads data from the `TestCases` worksheet. Workbook formatting such as fonts, colors, borders and merged cells must not affect case loading or execution.

## 4.2 TestCases Columns

V1.1 test case columns are configured in `config/config.yaml`.

ATT only loads columns defined in configuration. Columns that exist in Excel but are not configured are ignored automatically.

This allows each project to keep additional human-facing notes, formatting or helper columns in the workbook without affecting execution.

Example configuration:

```yaml
testcase:
  sheet: TestCases
  columns:
    caseId: Case ID
    caseName: Case Name
    tags: Tags
    api: API
    precheckTemplate: PreCheck Template
    expectedPrecheckResult: Expected PreCheck Result
    expectedPrecheckData: Expected PreCheck Data
    requestTemplate: Request Template
    debitAccount: Debit Account
    creditAccount: Credit Account
    amount: Amount
    currency: Currency
    requestData: Request Data
    postcheckTemplate: PostCheck Template
    expectedPostcheckResult: Expected PostCheck Result
    expectedPostcheckData: Expected PostCheck Data
    remarks: Remarks
```

Recommended logical fields:

| Column | Purpose |
|--------|---------|
| Case ID | Unique case identifier |
| Case Name | Human-readable case name |
| Tags | Comma-separated selection tags |
| API | API name or API group |
| PreCheck Template | Check template executed before API invocation |
| Expected PreCheck Result | Expected overall PreCheck result |
| Expected PreCheck Data | YAML data used by PreCheck expressions |
| Request Template | Request XML template identifier |
| Debit Account | Common request parameter |
| Credit Account | Common request parameter |
| Amount | Common request parameter |
| Currency | Common request parameter |
| Request Data | YAML data used by request templates |
| PostCheck Template | Check template executed after API invocation |
| Expected PostCheck Result | Expected overall PostCheck result |
| Expected PostCheck Data | YAML data used by PostCheck expressions |
| Remarks | Free-form notes |

For backward compatibility during migration, ATT may accept legacy column names:

| Legacy Column | V1.1 Column |
|---------------|-------------|
| Expected Template | PostCheck Template |
| Expected Result | Expected PostCheck Result |
| Expected Data | Expected PostCheck Data |

---

# 5. Case Runtime Context

ATT creates one Case Runtime Context for each selected test case.

The Case Runtime Context is the source of truth for template rendering, tool argument rendering, tool output lookup and report diagnostics.

It is isolated per case. Values produced by one case must not be visible to another case.

## 5.1 Context Structure

```text
Case Runtime Context

├── CASE
│   ├── configured Excel columns
│   ├── Request Data
│   ├── Expected PreCheck Data
│   └── Expected PostCheck Data
│
├── PATH
│   └── caseOutputDir
│
├── TOOL
│   ├── input
│   ├── output
│   ├── inputFile
│   ├── outputFile
│   └── invocation
│
└── TOOLS
    └── ordered outputs already produced in this case
```

## 5.2 Variable Resolution

Template placeholders resolve against the Case Runtime Context.

Resolution order:

1. Current `TOOL.input`
2. Current `TOOL.output`
3. Configured Excel columns and YAML data in `CASE`
4. Standard paths in `PATH`
5. Previous tool outputs in `TOOLS`

If a key exists in more than one scope, the earlier scope wins.

Recommended explicit forms:

| Form | Meaning |
|------|---------|
| `${CaseID}` | Short form for a case value |
| `${CASE.CaseID}` | Explicit case value |
| `${PATH.caseOutputDir}` | Case output directory |
| `${TOOL.input.a.b.c}` | Current tool input value |
| `${TOOL.output.a.b.c}` | Current parsed tool output value |
| `${TOOL.inputFile}` | Current invocation input file path |
| `${TOOL.outputFile}` | Current invocation raw output file path |
| `${TOOLS.toolName[0].output.a.b.c}` | Output from a previous tool invocation |
| `${TOOLS.toolName[0].outputFile}` | Raw output file from a previous tool invocation |

Short form should be used for common case fields.

Explicit scoped form should be used when ambiguity is possible.

## 5.3 Unified Template Syntax

All templates use the same syntax:

| Syntax | Meaning |
|--------|---------|
| `${path.to.value}` | Resolve a value from Case Runtime Context |
| `#{toolName(...)}` | Execute a configured tool and inject its output |

This applies to:

- Request XML templates
- API invocation templates
- PreCheck templates
- PostCheck templates

`${}` never triggers tool execution.

`#{}` is the only syntax that triggers tool execution.

Excel columns not configured in `config.yaml` are not loaded into the Case Runtime Context.

## 5.4 Tool Invocation Context

Every tool invocation has a structured input object.

The input object can come from:

- `#{toolName(...)}` in a request XML template
- `#{toolName(...)}` in an API invocation template
- `#{toolName(...)}` in a PreCheck or PostCheck template

Before a tool runs, ATT resolves placeholders inside the input object.

The resolved input object is exposed to the tool's configured `arguments` as:

```text
${TOOL.input}
${TOOL.input.a.b.c}
```

After a tool runs, ATT parses its output according to the configured output type and stores it in the context as an ordered invocation:

```text
TOOLS.${toolName}[n]
```

The current invocation is also exposed as:

```text
${TOOL.output}
${TOOL.output.a.b.c}
```

## 5.5 Context Injection

Tools may inject key-value pairs into the Case Runtime Context.

This is used for tool-to-tool information transfer, for example:

```yaml
tools:
  genEndToEndId:
    name: Generate a unique end-to-end ID
    command: "./tools/gen_e2e_id.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: txt
    inject:
      EndToEndId: "${TOOL.output}"
```

After the tool runs, later templates can use:

```text
${EndToEndId}
${TOOLS.genEndToEndId[0].output}
```

If an injected key already exists, the tool configuration must define the overwrite behavior.

Default behavior is `overwrite: false`.

## 5.6 Tool Invocation Artifacts

Every tool invocation persists both input and output files.

Because the same tool may be called multiple times in one case, ATT assigns a per-case sequence number to each invocation.

Artifact structure:

```text
output/${CaseID}/tools/
  001_getAcDate/
    input.yaml
    command.txt
    stdout.txt
    stderr.txt
    output.txt
    parsed-output.yaml
    context-injection.yaml

  002_getSeq/
    input.yaml
    command.txt
    stdout.txt
    stderr.txt
    output.txt
    parsed-output.yaml
    context-injection.yaml
```

The generated request XML is saved as the `output.xml` artifact of the built-in `renderRequestTemplate` invocation. ATT does not create separate V1-style `output/${CaseID}/request.xml` or `output/${CaseID}/response.xml` files; API responses are the raw output files of API tools.

---

# 6. External Tool Configuration

All external tools are configured in `config/config.yaml`.

Tool configuration format:

```yaml
tools:
  invokeOutwardService:
    name: Invoke Outward Service
    command: "./tools/invoke_outward_service.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
    arguments:
      requestXml: "${TOOL.input.requestXml}"
      environment: "${environment}"
  invokeInwardService:
    name: Invoke Inward Service
    command: "./tools/invoke_inward_service.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
  getAPIResponse:
    name: Get API Response
    command: "./tools/cat_api_response.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
  selectCtxn:
    name: select CT records
    command: "./tools/select_ct_txn.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: yaml
    arguments:
      caseId: "${CaseID}"
      txnRef: "${TOOL.input.txn.ref}"
  grepFromAppLogs:
    name: grep keywords from application logs
    command: "./tools/grep_from_app_logs.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: yaml
  getAppLogs:
    name: get application logs
    command: "./tools/get_app_logs.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: txt
  genEndToEndId:
    name: generate a unique end2end ID
    command: "./tools/gen_e2e_id.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: txt
    inject:
      EndToEndId: "${TOOL.output}"
  getAcDate:
    name: get account date
    command: "./tools/get_ac_date.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: txt
  getSeq:
    name: get a new sequence
    command: "./tools/get_seq.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: txt
```

Supported output formats:

- `xml`
- `yaml`
- `txt`

Tool `arguments` define the structured input passed to a tool invocation.

Argument values may reference:

- Configured Excel columns
- Request Data
- Expected PreCheck Data or Expected PostCheck Data
- `TOOL.input.a.b.c` values supplied by the template or check action
- Outputs from tools already executed in the same case

`TOOL.input.a.b.c` uses dotted-path access into the input object provided for that tool call.

Each tool output is saved as:

```text
output/${CaseID}/tools/${sequence}_${tool}/output.[xml|yaml|txt]
```

If a tool exits with a non-zero status, the action fails.

If a tool output cannot be parsed according to its configured output type, the action fails.

---

# 7. Request Template Package

Each test case references one Request Template package.

The package contains:

```text
templates/request/
  PAYMENT_TRANSFER/
    template.xml
    api.invocation.yaml
    sample.yaml
    README.md
```

Request templates support:

- Configured Excel column variables
- `Request Data` variables
- Explicit tool calls using `#{}`

Example:

```xml
<Request>
  <CaseId>${CaseID}</CaseId>
  <MessageId>#{getAcDate()}#{getSeq(caseId=${CaseID})}</MessageId>
  <EndToEndId>#{genEndToEndId(caseId=${CaseID})}</EndToEndId>
  <DebitAccount>${DebitAccount}</DebitAccount>
  <CreditAccount>${CreditAccount}</CreditAccount>
  <Amount>${Amount}</Amount>
  <Currency>${Currency}</Currency>
  <Channel>${channel}</Channel>
</Request>
```

When ATT finds `#{toolName}`, it resolves `toolName` against `config.yaml`, executes the tool and replaces the expression with the tool output.

When ATT finds `${VariableName}`, it resolves the value from the Request Context only.

Tools that require input can be called with structured input:

```xml
<ResponseId>#{getAPIResponse(response.caseId=${CaseID}, response.channel=${channel})}</ResponseId>
```

The structured values passed inside the tool call become available to tool argument templates as:

```text
${TOOL.input.response.caseId}
${TOOL.input.response.channel}
```

Tool-call outputs produced during request rendering are persisted under `output/${CaseID}/`.

---

# 8. API Invocation Template

The API invocation tool is defined by `api.invocation.yaml` in the request template package.

This keeps API execution behavior with the request template instead of duplicating tool choices in Excel test cases.

Example:

```yaml
invokePaymentApi:
  description: Invoke payment API after request XML generation
  call: "#{invokePaymentApi(requestXml=${TOOLS.renderRequestTemplate[0].outputFile})}"
```

ATT behavior:

- Load `api.invocation.yaml` from the request template package
- Resolve and execute the configured `call` expression using the unified template syntax
- Persist each invocation under `output/${CaseID}/tools/${sequence}_${toolName}/`
- Treat the API tool as a black box

The API tool may invoke one or more APIs internally. ATT does not inspect or control the internal flow of that tool.

API tool failure marks the case as `ERROR` unless the design is later extended to support expected API-tool failure.

---

# 9. Check Template

PreCheck and PostCheck share the same template format.

A check template contains one or more ordered check actions.

Example:

```yaml
checkCtxRecords:
  description: Check CT records
  call: "#{selectCtxn(txn.ref=${TxnRef})}"
  expected: "${TOOL.output.effectRows} != '${effectedRows}'
            and (${TOOL.output.record.amount} > '${ExpectedAmount}'
            or ${TOOL.output.record.status} like '%${status}%')"

checkAPIResult:
  description: Check API result
  call: "#{getAPIResponse(responseXml=${TOOLS.invokePaymentApi[0].outputFile})}"
  expected: "${TOOL.output.Response.Status} != '${expectedStatus}'"

checkTxnAmt:
  description: Check txn amt
  call: "#{getAPIResponse(responseXml=${TOOLS.invokePaymentApi[0].outputFile})}"
  expected: "${TOOL.output.Response.Amt} >= '${ExpectedMinAmt}'"

checkAppLog:
  description: Check app log
  call: "#{getAppLogs(keyword1=${Keyword1}, keyword2=${Keyword2})}"
  expected: "${TOOL.output} like '%${Keyword1}%${Keyword2}%'"
```

Execution rules:

- Check actions execute in template order.
- A failed action does not stop later actions.
- If tool execution fails, the action fails and ATT does not evaluate the expression.
- If tool output parsing fails, the action fails.
- All actions must pass for the full PreCheck or PostCheck to pass.

If a check action defines `call`, ATT resolves and executes it before expression evaluation.

The resolved tool input object is exposed to tool argument templates as `TOOL.input`.

---

# 10. Check Expression Engine

Check expressions use SQL-like boolean syntax.

Supported operators:

| Category | Operators |
|----------|-----------|
| Comparison | `>`, `>=`, `<`, `<=`, `==`, `!=` |
| Logical | `and`, `or`, `not` |
| Pattern matching | `like` |
| Null check | `is null`, `is not null` |
| Grouping | `(`, `)` |

Comparison behavior:

- Pure numeric values are compared numerically.
- Non-numeric values are compared as strings.
- `like` follows SQL wildcard behavior using `%` and `_`.

Tool output access:

```text
${TOOL.output}
${TOOL.output.a.b.c}
```

For XML and YAML output, dotted paths access nested data.

For text output, `${TOOL.output}` represents the full text content.

---

# 11. Execution Flow

```text
Load Suite

↓

Select Test Cases

↓

FOR EACH Test Case

    Create output/${CaseID}/

    Build PreCheck Context

    Execute PreCheck Template

    IF PreCheck fails
        Mark case as PRECHECK_FAILED
        Continue next case
    END

    Build Request Context

    Execute request-template tool placeholders

    Generate Request XML

    Execute API Invocation Template

    Build PostCheck Context

    Execute PostCheck Template

    Generate Case Result

END

↓

Generate Report
```

---

# 12. Result Status

V1.1 report statuses:

| Status | Meaning |
|--------|---------|
| PASS | PreCheck, API invocation and PostCheck all passed |
| PRECHECK_FAILED | PreCheck failed, API invocation was not executed |
| POSTCHECK_FAILED | API invocation executed, but PostCheck failed |
| ERROR | Tool execution, output parsing, template loading or framework processing failed |
| SKIPPED | Case was not selected or disabled |

---

# 13. Report

V1.1 generates an Excel report.

Recommended report columns:

| Column | Purpose |
|--------|---------|
| Case ID | Test case identifier |
| Case Name | Test case name |
| Result | Final status |
| PreCheck Result | Overall PreCheck result |
| API Invocation Result | API invocation result |
| PostCheck Result | Overall PostCheck result |
| Duration | Case execution duration |
| Output Directory | `output/${CaseID}` path |
| Failed Action | First failed action, if any |
| Expected | Expected expression/result |
| Actual | Actual tool output or evaluated value |
| Remarks | Additional diagnostic message |

---

# 14. Logging and Artifacts

Each case output directory preserves:

- Generated request XML
- API tool output
- PreCheck tool outputs
- PostCheck tool outputs
- Tool stdout and stderr logs
- Parsed check results

Framework-level logs are stored under:

```text
logs/
```

Case artifacts are stored under:

```text
output/${CaseID}/
```

---

# 15. Failure Handling

## 15.1 PreCheck Failure

If PreCheck fails:

- ATT does not invoke the API tool.
- ATT marks the case as `PRECHECK_FAILED`.
- ATT continues with the next case.

## 15.2 API Invocation Failure

If API invocation execution fails:

- ATT marks the case as `ERROR`.
- ATT does not run PostCheck.
- ATT preserves stdout, stderr and any produced outputs.

## 15.3 PostCheck Failure

If PostCheck fails:

- ATT marks the case as `POSTCHECK_FAILED`.
- ATT preserves all action-level results.

## 15.4 Check Action Failure

If one check action fails:

- ATT records that action as failed.
- ATT continues executing later actions in the same template.
- The overall PreCheck or PostCheck result is failed.

---

# 16. Technology Choice

Primary implementation language: Java

Target runtime: JDK 8

Rationale:

- Compatible with restricted enterprise runtime environments
- Mature XML, YAML and Excel ecosystem
- Suitable for long-term internal maintenance
- Aligns with existing Java-based system context

---

# 17. Compatibility Notes

V1.1 changes the validation model from direct expected-template checks to unified PreCheck/PostCheck templates.

Migration from V1 should include:

- Rename Expected Template to PostCheck Template
- Rename Expected Data to Expected PostCheck Data
- Move DB/log/XML checks into check templates
- Define all external tools in `config.yaml`
- Ensure all generated artifacts are written under `output/${CaseID}/`
