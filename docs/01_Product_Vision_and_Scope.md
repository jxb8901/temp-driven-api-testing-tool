
# Template-driven API Test Framework

**Version:** V0.1  
**Status:** Draft  
**Last Updated:** 2026-07-06

---

# 1. Background

## 1.1 Current Situation

The current banking application exposes its services through XML-based APIs.

Each API accepts:

```
Request XML
```

and returns

```
Response XML
```

The development team already has an execution tool capable of:

- Loading an input XML file
- Invoking the target API
- Saving the response XML to a file

This execution tool has been widely adopted and is considered stable. It is **not** part of this project.

---

## 1.2 Current Testing Workflow

A typical SIT/UAT workflow is:

```
Developer

↓

Prepare XML request

↓

Execute existing API tool

↓

Obtain Response XML

↓

Check XML manually

↓

Check application logs manually

↓

Check database manually

↓

Determine PASS / FAIL

↓

Record result
```

As the number of APIs and regression cases grows, several problems become increasingly significant:

- XML requests are repeatedly created manually.
- Test cases are difficult to organize and maintain.
- Validation logic is inconsistent among developers.
- XML, database and log verification are performed manually.
- Regression execution is time-consuming.
- Test reports are manually summarized.

---

## 1.3 Problem Statement

The existing API execution tool solves only one problem:

> Execute one XML request.

It does **not** provide:

- Test case management
- Parameterized request generation
- Automated validation
- Batch execution
- Regression support
- Standardized reporting

A lightweight framework is therefore required to automate repetitive testing work while preserving the existing execution tool.

---

# 2. Product Vision

Build a lightweight, template-driven API testing framework for SIT/UAT that enables developers to create, execute and validate API test cases with minimal effort.

The framework should:

- Minimize manual repetitive work
- Encourage standardized test cases
- Reuse existing testing infrastructure
- Be easy for developers to learn
- Be maintainable over many years

The framework is intended to become the standard regression testing tool for XML API projects within the team.

---

# 3. Target Users

Primary users:

- Application Developers
- Development Team

Potential future users:

- SIT Engineers
- UAT Support Engineers
- Technical QA Engineers

The framework is **not** intended for business users.

---

# 4. Design Principles

## 4.1 Simplicity First

The framework should remain easy to understand.

Developers should be able to create and execute a new test case with minimal learning.

Complex DSLs, scripting languages or rule engines should be avoided whenever possible.

---

## 4.2 Reuse Existing Assets

The framework should reuse existing project assets instead of replacing them.

Examples include:

- Existing API execution tool
- Existing shell scripts
- Existing XML templates
- Existing SQL scripts
- Existing log checking scripts

---

## 4.3 Template-driven

Everything that may change should be represented as a template rather than hard-coded.

Typical templates include:

- Request XML Template
- Expected Result Template

This allows business logic to evolve without modifying framework code.

---

## 4.4 Configuration over Programming

Developers should define test cases by editing configuration rather than writing code.

Typical workflow:

```
Edit Excel

↓

Run Framework

↓

View Report
```

---

## 4.5 Lightweight

The framework should remain lightweight.

It should avoid introducing unnecessary infrastructure such as:

- Database
- Web Server
- Rule Engine
- Plugin System

unless future requirements clearly justify them.

---

# 5. Product Scope

The framework focuses on four core capabilities.

## 5.1 Test Case Management

Provide a standardized way to organize test cases.

Initial implementation:

- Excel-based test case definition

Future possibilities:

- Web UI
- Desktop UI

---

## 5.2 Request Generation

Generate Request XML using:

- Request Template
- Parameters from Excel

Generated XML will be passed to the existing API execution tool.

---

## 5.3 Test Execution

Execute one or more test cases.

Support:

- Run All
- Run by Case ID
- Run by Tag

Execution is sequential in Version 1.

---

## 5.4 Result Validation

Automatically validate execution results.

Supported validation sources:

- Response XML
- Database (through existing shell scripts)
- Application logs (through existing shell scripts)

Validation rules are defined by Expected Templates.

---

# 6. Out of Scope

The following items are intentionally excluded from Version 1.

- Parallel execution
- Case dependency
- Test history management
- Scheduling
- Distributed execution
- Web UI
- Plugin architecture
- Generic automation framework
- CI/CD integration

Version 1 supports local/manual batch regression execution only.

It does not provide pipeline triggers, build server integration or automated scheduled regression runs.

These capabilities may be considered in future versions.

---

# 7. Success Criteria

Version 1 is considered successful if developers can:

- Create test cases using Excel
- Reuse Request Templates
- Execute multiple test cases in one run
- Automatically validate Response XML
- Automatically validate database results through shell scripts
- Automatically validate application logs through shell scripts
- Generate readable execution reports

without modifying framework source code.

---

# 8. Version Roadmap

## Version 1

Core framework.

Features:

- Excel test cases
- Request templates
- Expected templates
- Batch execution
- XML validation
- Shell-based DB validation
- Shell-based Log validation
- Excel report

---

## Version 2

Productivity improvements.

Possible features:

- Run Failed Cases
- Dry Run
- Variable extraction
- Shared variables
- Case dependency
- Better reports
- Interactive debugging

---

## Version 3

Advanced framework capabilities.

Possible features:

- Parallel execution
- Web UI
- Test history
- CI integration
- Dashboard
- Metrics
- Team collaboration

---

# 9. Guiding Philosophy

This framework is **not** intended to replace professional automation testing platforms.

Instead, it provides a lightweight, maintainable solution specifically designed for XML-based banking API testing.

Its philosophy can be summarized as:

> **Template-driven. Lightweight. Easy to maintain. Developer-friendly.**
