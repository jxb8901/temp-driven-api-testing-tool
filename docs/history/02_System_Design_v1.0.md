
# Template-driven API Test Framework

**Version:** V0.1  
**Status:** Implemented
**Last Updated:** 2026-07-06

---

# 1. System Overview

## 1.1 Purpose

The framework provides a lightweight automation layer on top of the existing API execution tool.

It does **not** replace the current execution tool.

Instead, it automates the complete SIT/UAT workflow:

- Manage test cases
- Generate Request XML
- Execute API
- Validate results
- Generate reports

---

## 1.2 Overall Architecture

```text
                    Test Suite (Excel)
                           │
                           ▼
                 Load Test Cases
                           │
                           ▼
                Generate Request XML
             (Request Template Engine)
                           │
                           ▼
              Existing Shell Executor
                           │
                           ▼
                   Response XML
                           │
          ┌────────────────┼────────────────┐
          │                │                │
          ▼                ▼                ▼
    XML Validation    DB Validation    Log Validation
      (XPath)         (Shell Script)   (Shell Script)
          │                │                │
          └────────────────┼────────────────┘
                           ▼
                  Test Result
                           ▼
                  Excel Report
```

---

# 2. Project Structure

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
│             sample.yaml
│             README.md
│
│     expected/
│         PAYMENT_SUCCESS/
│             template.yaml
│             README.md
│
├── config/
│     config.yaml
│
├── output/
│     xml/
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

# 3. Test Suite

A Test Suite is one Excel workbook.

Each workbook contains all test cases belonging to one business module.

Example:

```text
Payment Regression

Account Management

Customer Inquiry
```

Version 1 recommends three worksheets.

| Sheet | Purpose |
|--------|---------|
| TestCases | Test Case Definitions |
| Config | Suite Configuration |
| README | Documentation |

---

# 4. Test Case

Each row represents one executable Test Case.

A Test Case contains:

- Request definition
- Execution definition
- Validation definition

Typical execution:

```text
Load Case

↓

Generate XML

↓

Execute API

↓

Validate

↓

Report
```

---

# 5. Excel Specification

## 5.1 Fixed Columns

Frequently used fields are defined as dedicated columns.

Required columns:

| Column | Purpose |
|----------|---------|
| Enable | Whether the case should be executed |
| Case ID | Unique test case identifier |
| API | Target API name or code |
| Request Template | Request template identifier |
| Expected Template | Expected template identifier |

Recommended optional columns:

| Column |
|----------|
| Case Name |
| Tags |
| Debit Account |
| Credit Account |
| Amount |
| Currency |
| Expected Status |
| Expected Reject Code |
| Request Data |
| Expected Data |
| Remarks |

Projects may add additional fixed columns if frequently used.

---

## 5.2 Extended Parameters

Less frequently used parameters are stored inside:

Request Data

Expected Data

using YAML.

Example:

Request Data

```yaml
channel: ATM
customerType: PERSONAL
purposeCode: SALARY
```

Expected Data

```yaml
expectedLedgerStatus: POSTED
expectedBalance: 100
```

Runtime parameter context:

```text
Fixed Columns
        +
Request Data
        +
Request Context

Fixed Columns
        +
Expected Data
        +
Expected Context
```

Request templates may reference variables from the Request Context only.

Expected templates may reference variables from the Expected Context only.

Expected Data must not affect generated Request XML.

This separation prevents validation-only values from accidentally changing the request under test.

---

## 5.3 Required Field Handling

If a required column is missing, the suite load must fail before execution starts.

If a required value is empty for an enabled case, that case must be marked as invalid and must not be executed.

Disabled cases may be skipped without validating the remaining required values.

YAML parsing errors in Request Data or Expected Data must mark the case as invalid.

---

# 6. Request Template

Each Test Case references one Request Template.

```
Request Template = PAYMENT_TRANSFER
```

A template contains:

- XML template
- Documentation
- Sample parameters

Example:

```text
templates/request/

    PAYMENT_TRANSFER/

        template.xml

        sample.yaml

        README.md
```

The framework replaces variables and generates the final Request XML.

---

# 7. Existing API Executor

The framework delegates API invocation to the existing shell command.

Responsibilities:

Framework

- Generate XML
- Execute shell command
- Collect outputs

Existing Tool

- Invoke API
- Save Response XML

The framework treats the execution tool as a black box.

## 7.1 Executor Contract

The executor command is configured in `config/config.yaml`.

For each enabled case, the framework provides at least:

- Generated request XML file path
- Expected response XML output path
- Case ID
- API name
- Environment

The exact command line is template-based so existing scripts can be reused.

Example:

```yaml
executorCommand: "./run_api.sh --api ${api} --request ${requestXml} --response ${responseXml} --env ${environment}"
timeoutSeconds: 120
```

Execution result handling:

- Exit code `0` means the executor completed.
- Non-zero exit code means execution failed.
- If the executor completes but the response XML file is missing, the case result is `ERROR`.
- If the executor exceeds the configured timeout, the case result is `ERROR`.
- Executor stdout and stderr are captured in the framework log.

---

# 8. Expected Template

Each Test Case references one Expected Template.

Example:

```
Expected Template = PAYMENT_SUCCESS
```

Expected Template describes how to validate:

- XML
- Database
- Log

One template may be shared by many Test Cases.

The expected template is a YAML file.

Minimum structure:

```yaml
xml:
  - name: Status
    xpath: "/Response/Status"
    equals: "${Expected Status}"
  - name: RejectCode
    xpath: "/Response/RejectCode"
    equals: "${Expected Reject Code}"

database:
  - name: LedgerStatus
    command: "./check_db.sh --case ${Case ID}"
    expected: "${expectedLedgerStatus}"

log:
  - name: ErrorLog
    command: "./check_log.sh --case ${Case ID}"
    expected: "NOT_FOUND"
```

All `${...}` placeholders are resolved from the Expected Context.

---

# 9. Validation Engine

Version 1 supports three validation sources.

## Response XML

Validate using XPath.

Example:

```yaml
- name: Status
  xpath: "/Response/Status"
  equals: "SUCCESS"
- name: RejectCode
  xpath: "/Response/RejectCode"
  equals: "0000"
```

Version 1 supports exact string comparison only.

More operators, such as regex or numeric comparison, are reserved for future versions.

---

## Database

The framework executes an existing shell script.

Example:

```
check_db.sh --case TC001

↓

SUCCESS
```

The framework compares:

Expected

vs

Actual

The framework does not access Oracle directly.

Script result contract:

- Exit code `0` means the script completed and stdout contains the actual value.
- Non-zero exit code means validation error.
- Stdout first non-empty line is treated as the actual value.
- Stderr is captured in the framework log.

---

## Application Log

Validation is delegated to existing shell scripts.

The framework neither understands remote log locations nor parsing logic.

It only executes the script and compares the returned result.

Log validation scripts follow the same result contract as database validation scripts.

---

# 10. Execution Flow

```text
Load Suite

↓

Select Test Cases

↓

FOR EACH Test Case

    Generate Request XML

    Execute API

    Save Request XML

    Save Response XML

    Execute Expected Template

    Generate Validation Result

END

↓

Generate Report
```

Execution is sequential.

Parallel execution is reserved for future versions.

---

# 11. Report

Version 1 generates an Excel report.

Recommended columns:

| Column |
|----------|
| Case ID |
| Case Name |
| Result |
| Duration |
| Expected |
| Actual |
| Output XML |

Future versions may support:

- Markdown
- HTML

---

# 12. Configuration

Global configuration is stored in:

```
config/config.yaml
```

Typical settings:

- Output directory
- Report directory
- Shell command
- Environment
- Timeout
- Temporary directory

---

# 13. Logging

Framework log:

```
logs/framework.log
```

Execution artifacts:

```
output/xml/

report/
```

Every execution preserves:

- Request XML
- Response XML

These files are used for troubleshooting.

---

# 14. Extension Points

The architecture reserves the following future capabilities without affecting Version 1.

| Feature | V1 |
|----------|----|
| Run Failed Cases | Reserved |
| Dry Run | Reserved |
| Variable Extraction | Reserved |
| Case Dependency | Reserved |
| Parallel Execution | Reserved |
| Interactive Debug | Reserved |
| Web UI | Reserved |

---

# 15. Design Principles

1. Keep the framework lightweight.
2. Reuse existing execution tools and shell scripts.
3. Separate business logic from framework code.
4. Prefer configuration over programming.
5. Keep templates reusable.
6. Preserve execution artifacts.
7. Minimize the learning curve for developers.

---

# 16. Technology Choice

Primary implementation language: Java

Target runtime: JDK 8

Rationale:

- Team familiarity
- Better fit for existing Java-based system
- Easier dependency management in restricted environment
- Mature XML / Excel ecosystem
- Suitable for long-term internal framework
