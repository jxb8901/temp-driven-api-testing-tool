# Changelog

- Fixed `run --verbose` so it emits safe run/suite/case/stage/action lifecycle progress; completed the normal `[1/4]` through `[4/4]` progress sequence and made `--quiet` suppress completion output.

## [V2.1] - 2026-07-11

- Added strict versioned schemas and package-wide/selected validation with structured diagnostics.
- Added identifier/path hardening, atomic run publication, reproducibility manifests, JSON CI output, JUnit XML, JUnit HTML, and verified run archives.
- Changed timeouts from `timeoutSeconds` to millisecond `timeoutMs`; timeout precedence is tool action, workbook sidecar, then global configuration.
- Added tool-action `timeoutMs` override and bounded immediate retry for `retryOn: [EXIT_CODE]`; delay/backoff, timeout retry, parser retry, and assertion retry are outside V2.1.
- Corrected FAIL/ERROR aggregation and exit codes, XML repeated-element/attribute handling, and end-user generated-output cleanup ownership.

## [V2.0] - 2026-07-11

V2 implements the grouped Excel → stage → template → action → tool model defined by the V2 System Design.

### Added

- Mandatory V2 workbook sidecars with multi-sheet `groupId=sheetName`, multi-row headers, required `caseId`/`tags`, and compact case/stage `dataColumns` parsing.
- Full Case IDs such as `payment.TC001`, Chinese workbook fixtures, YAML map and scalar-shorthand template selectors, blank-marker normalization, and the persisted `CASE.STAGES.TEMPLATE.ACTIONS.TOOL` tree.
- Strict template indexing by symbolic name or full path and pre-run package/template/tool contract validation.
- Tool argument documentation contracts with final-argument `delimit` expansion; V2 rejects `argv`; action failure behavior is explicitly `stop` or `continue` with default `stop`.
- Offline single-page HTML run summaries and package documentation, detailed case logs/artifact links, `case.yaml`, `events.jsonl`, report regeneration, and latest-run tar.gz archives with hashes.
- V2 CLI commands: `run`, `validate`, `docs`, `report`, `build`, `clean`, `version`, and `help`, including explicit `--all`, JSON output, and safe generated-output cleanup.
- Built-in CASE/STAGES/TEMPLATE/ACTIONS/TOOL properties are documented and exposed through canonical CASE paths plus transient action/tool convenience scopes.

### Verified

- V2.0 release verification recorded Java 8-target compilation and 43 automated tests.
- The bundled V2.0 sample used `validate --all` for 1 suite, 22 cases, 7 templates, and 7 tools; V2.1 replaces this workflow with `validate --package` / `validate --selected`.

## [V1.3] - 2026-07-09

V1.3 implements suite-local testcase configuration, Test Case Template defaults, Action ID based context, file-based action outputs, and variadic tool arguments.

### Added

- Added suite sidecar config loading from `<suiteName>.yaml` beside each Excel workbook.
- Added Test Case Template defaults under `templates/testcase/<templateId>/template.yaml`.
- Added V1.3 stage template resolution from `templates/<template>/template.yaml`, with `templates/stage/<template>/template.yaml` retained as a compatibility fallback.
- Added `ACTIONS.<ActionID>` context references, while keeping `TOOLS.<ActionID>` as a compatibility alias.
- Added render action `saveAs` support so long payloads can be written under `output/<RunID>/<CaseID>/<ActionID>/`.
- Added persisted action input/output files under each action directory.
- Added `log` action examples for checkpoints and selected context fields.
- Added tool `argv` support for variadic CLI arguments.
- Added `testcase/payment_regression.yaml` and `templates/testcase/payment_transfer_cases/template.yaml` as V1.3 examples.

### Changed

- Updated sample payment flow to use shared V1.3 stage templates under `templates/PAYMENT_PREPARE`, `templates/PAYMENT_INVOKE`, and `templates/PAYMENT_VERIFY`.
- Updated payment invocation to pass the rendered XML file path instead of embedding long XML in the tool input.
- Updated release package default version to `v1.3`.

### Verified

- Compiled main sources with `javac --release 8` through `scripts/build-release.sh`.
- Ran `testcase/payment_regression.xlsx`: 20 total, 20 passed, 0 failed, 0 error, 0 skipped.

## [V1.2] - 2026-07-09

V1.2 implements the generic Tool Invocation Template model: ATT no longer treats request and check templates as separate framework concepts. Cases now run configured stages made of ordered template actions, and every action result is addressable through `TOOLS.<InvocationId>`.

### Added

- Added V1.2 stage/action runtime with `render`, `tool`, `assert`, and `log` template actions.
- Added configurable stages in `config/config.yaml`, with default Pre/Main/Post stage columns.
- Added `templates/stage/` template packages for payment prepare, invoke, and verify flows.
- Added invocation ID based context references such as `${TOOLS.invokeApi.output.Response.Status}`.
- Added single case execution log files under `output/<RunID>/<CaseID>/`.
- Added run history files at `output/<RunID>/run.yaml` and `output/latest-run.yaml`.
- Added `att.sh` command line entry point with suite, suite-dir, tag, exclude-tag, run-id, dry-run, fail-fast, and rerun-failed options.
- Added result workbook generation under the run directory, for example `output/<RunID>/payment_regression.result.xlsx`.
- Added `scripts/build-release.sh` to build a self-contained release package with compiled classes, dependency jars, config, templates, scripts, testcase examples, and docs.

### Changed

- Replaced V1.1 request/check/API template categories with generic Tool Invocation Templates.
- Replaced per-tool input/output artifact files with ordered sections in the case execution log.
- Updated Excel testcase configuration to use stage template columns and configurable report result columns.
- Updated `testcase/payment_regression.xlsx` to 20 V1.2 scenarios.
- Updated report generation to copy the original Excel workbook and append configured result fields.
- Updated `att.sh` to run directly from a release package using `lib/att-v2.0.jar` and dependency jars.

### Removed

- Removed V1.1 context injection from the active V1.2 runtime model; action outputs are read directly from `TOOLS.<InvocationId>`.

### Verified

- Compiled main sources with `javac --release 8`.
- Ran `testcase/payment_regression.xlsx`: 20 total, 20 passed, 0 failed, 0 error, 0 skipped.

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

## [V1] - 2026-07-07

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
- Standardized the initial package layout under `att`.

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
