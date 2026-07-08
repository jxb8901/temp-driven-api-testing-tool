# Changelog

## [V1.1] - 2026-07-08

V1.1 introduces a unified template runtime for ATT: `${...}` is reserved for context references, `#{...}` is reserved for tool calls, and all request/check/API interactions are represented as ordered tool artifacts.

### Added

- Implemented ATT V1.1 runtime with unified `${...}` context references and `#{...}` tool invocations.
- Added `CaseRuntimeContext` to keep case data, tool input/output, tool files, and ordered tool invocation history in one place.
- Added configurable tool execution through `config/config.yaml`, including tool arguments, parsed outputs, and context injection.
- Added built-in `renderRequestTemplate` artifact generation so rendered request XML is saved as a V1.1 tool artifact.
- Added `api.invocation.yaml` under request templates to define API tool invocation with the request template package.
- Added check template execution using the same tool invocation and context model as request templates.
- Expanded `testcase/payment_regression.xlsx` to 20 executable payment scenarios.
- Added example shell scripts for tool execution and artifact persistence.
- Added `TOOLS.toolName[index]` history references so later templates can consume earlier tool outputs or raw output files.

### Changed

- Replaced V1 fixed `output/<case>/request.xml` and `output/<case>/response.xml` files with ordered tool artifacts under `output/<case>/tools/NNN_toolName/`.
- Updated post-check templates to read API response XML from `${TOOLS.invokePaymentApi[0].outputFile}`.
- Updated Excel loading to honor `testcase.columns` from `config.yaml`; unconfigured columns are ignored.
- Updated reports to point to the case artifact directory instead of a single output XML file.
- Updated V1.1 design documentation to reflect the tool-artifact request/response flow.
- Moved API execution selection out of Excel cases and into request-template-owned `api.invocation.yaml`.

### Removed

- Removed V1 `ApiExecutor` and expected-template validation classes that were replaced by V1.1 tool invocation and check templates.

### Verified

- Compiled main and test sources with `javac --release 8`.
- Ran `testcase/payment_regression.xlsx`: 20 total, 20 passed, 0 failed, 0 error, 0 skipped.
- Ran `git diff --check`.

## [V1] - 2026-07-08

V1 delivers the first executable ATT framework: Excel test cases drive request XML generation, API execution, validation, and Excel report output.

### Added

- Added Maven/JDK8 Java project structure and `FrameworkRunner` CLI entry point.
- Added `config/config.yaml` loading for output, report, log, environment, timeout, and executor settings.
- Added Excel test suite loading from `testcase/payment_regression.xlsx`.
- Added request XML rendering with `${...}` placeholders and request template packages under `templates/request/`.
- Added API execution through shell command integration, including generated request XML and captured response XML files.
- Added expected/check template loading for XML, DB, and log validation scenarios.
- Added validation result aggregation and Excel report generation.
- Added sample payment transfer template, sample workbook, shell tool scripts, and unit tests for the V1 execution path.

### Changed

- Established the original V1 output contract around per-case `request.xml`, `response.xml`, and report workbook artifacts.
- Standardized the initial package layout under `com.company.apitest`.

### Verified

- Added focused tests for context creation, template rendering, Excel suite loading, and framework execution.

## [Initial] - 2026-07-06

Initial planning release for the template-driven API testing tool.

### Added

- Added `docs/01_Product_Vision_and_Scope.md` to define purpose, users, scope, assumptions, and success criteria.
- Added `docs/02_System_Design.md` as the first system design draft.
- Defined the first ATT workflow: load Excel cases, render request XML, call an existing executor, validate XML/DB/log outputs, and write a report.
- Defined the initial project structure for `testcase/`, `templates/`, `config/`, `output/`, `report/`, `logs/`, and Java source.
- Documented the first Excel test case model with fixed columns plus YAML-style extended request/expected data.
