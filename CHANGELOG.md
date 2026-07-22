# Changelog

## 2.5.0 - 2026-07-22

- Added first-class `type: db` Actions and independent `att-dbhelper/v2.5` instance files referenced by `att-config/v2.5`; process Tool groups remain on `att-tool-group/v2.2`.
- Added explicit `query`/`update` blocks with inline or package-contained file SQL, rendered SQL sources, typed positional `PreparedStatement` parameters, per-instance `statement.timeoutSeconds`, and bounded stable result objects whose `rows` field is always a list.
- Added read-only expression calls `#{db.<instance>.query(...)}` and `#{db.<instance>.scalar(...)}`, including typed multi-parameter lists and exact-expression Java-object preservation.
- Added one JDBC connection per dbhelper instance/execution thread, pre-Case rollback/reconnect isolation, statement/case transaction scopes, rollback-only handling, and fixed Case-finalization evidence at `CASE.DB.<instance>`; DB operational errors produce Case ERROR.
- Added the V2.5 common object `saveAs` contract (`path`, target-specific `format`, `overwrite`) for process Tools, primary built-ins, and DB Actions while preserving legacy V2.3 scalar Tool `saveAs` behavior.
- Added JDBC driver discovery from package-root `lib/*.jar`; ATT still bundles no vendor-specific JDBC driver.

## 2.4.3 - 2026-07-22

- Added `run --profile` and per-run `performance.json` phase timings, cache counters, process-output byte counts, truncation counts, and a heap snapshot.
- Added process-level compiled JSON Schema, Template descriptor/compiled Template, and UTF-8 render-payload caches with file-fingerprint invalidation.
- Bounded local process stdout/stderr in memory with head/tail previews and streamed Case artifacts; added configurable memory/artifact limits and bounded the Java SSH fallback preview.
- Reused one Case-log writer and YAML serializer for the Case lifetime, limited main HTML Case-log embedding, and changed framework input hashing to streaming SHA-256.
- Added `report.mode: none` for CI runs that do not need copied result workbooks.
- Renamed the concurrent-process opt-in to `--allow-parallel-runs`; the older `--parallel` spelling remains a deprecated compatibility alias and still does not parallelize Cases inside one run.
- Fixed result-workbook row matching to use the same formatted and normalized Case ID value as testcase loading, including numeric cells with Excel formats such as leading zeroes.
- Allowed a `type: tool` action to use an ATT built-in as its primary call, with normal action result/assertion handling and optional `saveAs` output but no external-process `TOOL` evidence.

## 2.4.2 - 2026-07-21

- Unified `${...}` value references and `#{...}` built-in/configured-Tool calls behind one expression engine across every Case-runtime expression surface, including action descriptions, assertions, expected/actual values, log messages/fields, assign expressions, render payloads, Tool-call arguments, and Tool `saveAs`.
- Added direct canonical Context paths inside call arguments, nested call evaluation, and assertion pre-evaluation, so the preferred `#{length(value=CASE.VARS.SrcRefNo)} <= 35` form validates and executes with typed comparison semantics while the older nested `${...}` form remains compatible.
- Enabled built-ins in the dedicated `report.fileNamePattern` and Tool-definition `command` scopes while retaining their restricted values (`suiteName` or declared Tool inputs) and rejecting configured Tool calls in those non-Case/recursive boundaries.
- Expanded configuration/package validation and regression coverage so every expression-bearing field uses the runtime call grammar without executing built-ins or external Tools during validation.
- Reused the testcase loader's logical multi-row header projection when writing result workbooks, preventing negative Apache POI cell indexes when a configured header appears above the final header row and reporting a precise missing-header diagnostic instead.
- Made Excel header matching ignore embedded spaces, tabs, line breaks, non-breaking spaces, and other Unicode whitespace while retaining case-sensitive matching and original header text in diagnostics.
- Added optional `file` input to log actions so UTF-8 files below the current Case output directory can be emitted with or without a rendered message; expressions are supported and symlink/path escapes are rejected.
- Compacted human Case logs to one action block and one canonical record per Tool attempt, removing duplicated Tool/action evidence while preserving the complete final execution state in `case.yaml`.
- Removed full Context-tree dumps from unknown and ambiguous expression diagnostics; requested path, deepest node, missing segment, nearest suggestion, and ambiguity candidates remain available.

## 2.4.1 - 2026-07-20

- Added deterministic case-sensitive Context suffix lookup. A suffix resolves only when it identifies one readable logical path; ambiguous suffixes fail with new `ATT-CTX-002` candidate diagnostics instead of selecting by tree order.
- Expanded unknown/ambiguous Context diagnostics with the requested path, deepest successfully reached node, missing segment where applicable, and a complete author-facing key/type tree. Console diagnostics never copy Context values; existing Case-log evidence remains unchanged.
- Reformatted human validation output into indented diagnostic blocks with stable blank-line separation while leaving validation JSON unchanged.
- Added run-only `--update-snapshot` to explicitly create or replace changed canonical snapshots before normal validation and output creation. It handles complete selected workbooks, preserves byte-identical files, rejects snapshot symlinks, uses atomic per-file replacement, serializes concurrent update phases, works with `--dry-run`, and keeps JSON stdout clean.
- Made Windows validation skip POSIX `.sh` launch/executable compatibility checks while retaining file/path safety checks and emitting one warning that lists unchecked tools; documented native `att.bat snapshot`, `validate`, and `docs` authoring workflows.
- Simplified expanded HTML Case details to retain the detailed execution log and explicit `.log`/`case.yaml` artifact links without duplicating the persisted Stage/Template/Action/Tool tree inline.

## 2.4.0 - 2026-07-19

- Added mandatory same-basename semantic XML snapshots for Excel testcase version control. Excel remains the editable source; `snapshot --suite` and `snapshot --all` generate deterministic `basename.xml` documents using `att-testcases/v2.4`.
- Added pre-validation and pre-execution snapshot verification with field-level added, removed, reordered, and modified Case diagnostics. Missing, malformed, non-canonical, or stale snapshots prevent execution before run output is created.
- Snapshot XML records only sidecar-mapped groups, normalized Case data, tags, and ordered stage selectors/data. It uses explicit scalar/map/list types, CDATA for multiline or XML-special strings (including deterministic splitting of literal `]]>`), secure XXE-disabled parsing, UTF-8/LF canonical output, atomic replacement, XSD publication, and run-manifest hashing.
- Rejected formula cells and merged data cells in configured testcase columns to keep execution and versioned values deterministic; merged presentation cells remain allowed within configured header rows.
- Added sample snapshots, CLI/help/manual/design updates, and regression coverage for deterministic XML, Unicode/multiline/nested values, stale or unsafe snapshots, generation safety, formula/merge boundaries, and run enforcement.

## 2.3.5 - 2026-07-17

- Reworked user-facing failures into typed, actionable diagnostics for command-line, global/tool configuration, workbook/sidecar/Case loading, template/action execution, Context lookup, built-in invocation, and external-tool execution. Diagnostics now preserve stable codes, precise locations, detailed causes, available choices, likely corrections, and repair guidance instead of inferring categories from message text.
- Tightened validate/run parity by parsing render-payload `#{...}` calls with the runtime grammar, validating built-in/tool signatures and Context references, checking selected-scope inline-tool executables, and rejecting unknown Context scopes, `RUN` variables, Case stage keys, and unavailable action references before execution. Package validation applies static Context/action-order checks even to templates that no Case references. Unknown Context paths now raise `ATT-CTX-001` instead of silently becoming empty text.
- Simplified normal human run logging to the final summary and report path. `--verbose` now shows lifecycle progress and mirrors every complete Case-log block, including template/tool invocation evidence; `--quiet` continues to suppress normal output.
- Added an optional positional or named Java `DateTimeFormatter` pattern to `sysdate` and `systimestamp`, retaining their existing default formats and returning detailed `ATT-BUILTIN-001` diagnostics for invalid or incompatible patterns.
- Added `type: assign` for evaluating a `${...}`/`#{...}` text expression into a unique `${CASE.VARS.<name>}` value shared by later actions/stages/templates in the same Test Case, with canonical action evidence, optional assertion, and collision/order validation.
- Updated V2.3 design, Quick Start, Reference Manual, README, diagnostic catalog, generated built-in documentation, and focused regression coverage for V2.3.5.

## 2.3.4 - 2026-07-16

- Allowed multiple declared arguments in one tool to use `delimit`, with independent ordered expansion before local, grouped, or SSH execution.
- Added `argNameMode: once|repeat` for delimited values. The backward-compatible default `once` emits `[argName, value1, value2]`; explicit `repeat` emits `[argName, value1, argName, value2]`.
- Updated strict schemas, generated tool documentation, user/developer documentation, and regression coverage for multiple delimited arguments and both naming modes.

## 2.3.3 - 2026-07-16

- Added optional tool-argument `argName` expansion: a provided value emits `[argName, value]`, while a missing or normalized-blank optional value emits no argv; omitted or empty `argName` keeps positional-argument behavior.
- Applied the same atomic expansion to local, grouped, and SSH logical argv, including one `argName` followed by all values of a delimited argument.
- Added strict schema/loader validation, generated tool-documentation output, and regression coverage for named, positional, delimited, blank, malformed, and grouped argument cases.

## 2.3.2 - 2026-07-15

- Added reserved `${CASE.outputDirectory}` Context containing the normalized absolute current Case output directory, with validation-time placeholder preservation and completed-run path rewriting in persisted text evidence.
- Changed local configured tools to run from the current Case output directory so relative tool artifacts are captured with the Case, while preserving package-root resolution for `./` and `../` executables and retaining PATH lookup for bare commands.
- Added framework-owned `ATT_ROOT_DIR` and `ATT_CASE_OUTPUT_DIR` environment variables to every local tool process.
- Documented that SSH remote commands keep the remote account's default working directory because local Case paths have no implicit remote mapping.

## 2.3.1 - 2026-07-14

- Added local filesystem built-ins for regular-file/directory existence, file size, directory creation, collision-safe copy/move, and delete operations, with normalized path results and explicit overwrite/missing-file controls.
- Added `randomChoice` for selecting one of 1–1000 positional or named input values while preserving the selected value's type.
- Added the `fpp` reference tool group with executable POSIX scripts for an API-adapter XML skeleton, multi-row pipe-delimited SQLPlus output conversion using safe column names as direct elements, and child-script execution with YAML status plus captured stdout/stderr.
- Expanded generated package documentation, Quick Start, Reference Manual, V2.3 design notes, README, and unit/integration coverage for the V2.3.1 contracts.

## 2.3.0 - 2026-07-14

- Added `att-template/v2.3` with safe deterministic render payload globs and required `renderAs: file|text|json|yaml|xml`; file mode preserves matched template-relative paths below the Case output directory, while typed modes store one value or an ordered path-to-value map.
- Unified every action outcome below `action.output`, including status, success, exception, duration, target files, typed result, process/retry evidence, and assertion details; completed-action Context now uses `.output.result` and `.output.targetFiles` without legacy flattening.
- Made `assert` available to render, tool, and log actions and the required expression field for assert actions. Operational failures remain ERROR, while completed tool exit codes are evidence and explicit assertions decide PASS/FAIL.
- Added two-phase action text handling: validation resolves known Case values while preserving runtime placeholders, then runtime completes descriptions and assert-action `actual` values against the action-local `${output...}` scope. Assert actions add optional `expected` and canonical `actual`; legacy `expression`, `acture`, and `actural` are rejected.
- Added ordered LF-preserving Expected/Actual aggregation from assert actions across HTML, result workbooks, CI JSON, JUnit detail, regenerated reports, Case logs, default/configurable report columns, and sample templates.
- Grouped generated package-documentation Testcases by workbook and Sheet, removed the redundant Sheet table column, and added validation-time Expected Result text assembled from assert actions.
- Updated the V2.3 design, Quick Start, Reference Manual, README, schema catalog, generated package documentation, shipped templates/configuration, and focused unit/integration coverage.

## 2.2.1 - 2026-07-14

- Added `att.bat` as the Windows CLI entrypoint with packaged-release and Maven-backed source-tree modes, Windows `PATHEXT` tool lookup, and inclusion in package validation, binary/source release archives, manifests, help, and user documentation.
- Added `substr`, `indexOf`, `sysdate`, `systimestamp`, `ltrim`, `rtrim`, `contains`, `startsWith`, `endsWith`, `replace`, `padLeft`, `padRight`, `formatDate`, and `dateAdd` built-ins with deterministic argument validation and documented ISO-8601 date behavior.
- Added unnamed single-argument calls for configured tools that declare exactly one argument; single-value built-ins likewise accept either `value=...` or one unnamed argument. Multi-argument tools continue to require named arguments.
- Expanded unit coverage and generated package documentation for Windows packaging, string/date built-ins, and positional shorthand validation/runtime behavior.

## 2.2.0 - 2026-07-13

- Added independently maintained `att-tool-group/v2.2` files with package-unique group IDs, qualified `group.tool` calls, optional shared group dispatch scripts, qualified Case Context evidence, and package-wide validation/documentation.
- Added `att-config/v2.2` `toolGroups` and SSH targets while retaining readable `att-config/v2.1` inline global-tool configurations. Root SSH applies to global tools and group SSH remains isolated to its group.
- Added scalar-or-list argv configuration for tool commands and group scripts. List items remain atomic, scalar commands are tokenized once, and group dispatch uses `<script argv> <tool key> <command argv>`.
- Added SSH execution with local OpenSSH preferred and automatic mwiede/jsch fallback when `ssh` is unavailable, including a visible fallback warning, strict host-key checking, optional identity files, transport evidence, timeout/retry/output parity, safe POSIX remote argv quoting, and non-network package validation.
- Added global `nvl`, `iif`, and `nchar` built-ins behind an internal provider boundary; custom Java built-in providers remain disabled in V2.2.
- Updated the V2.2 design, Quick Start, Reference Manual, generated package documentation, sample configuration, unit/integration coverage, and release metadata.

## 2.1.4

- Case log YAML now expands repeated Map/List values at every location by default, avoiding generated anchors such as `&id001` and aliases such as `*id001`.
- Added global `caseLog.yamlAnchors`; it defaults to `false` for fully expanded YAML and can restore YAML anchors when enabled.
- Added the searchable `【!!!!!】` prefix to Case log blocks whose section or structured status is `ERROR`, `FAIL`, or `INVALID`.

## 2.1.3

- Added inline `assert` expressions to `render` and `tool` actions so their results can be validated without a separate assert action.
- Removed per-attempt evidence directories. Retry evidence is recorded in the case log/action record, and a configured `saveAs` file is replaced by the latest retry attempt.
- Moved action `saveAs` files to the case-log directory, added expression-based filenames, and added `overwrite` with a default of `false` and collision errors when disabled.

## 2.1.2

- Hardened tool execution by tokenizing the static command template before injecting declared arguments as atomic argv values; global commands can no longer hide runtime Context dependencies.
- Standardized the logical workbook grouping term as `groupId` in Case IDs, Context, run manifests, reports, CLI help, and V2.1 documentation while retaining report regeneration for older `sheetId` manifests.
- Completed the V2.1 manual consistency pass for executable Quick Start tooling, configurable recursive testcase discovery, `saveAs`, rerun selection, result aggregation, numeric coercion, quoted Context keys, XML projection, and report contents.

## 2.1.1

- Made the package documentation index and report-style filters sticky, with keyword, workbook, sheet, Case ID, and combined tool/built-in controls.
- Added case-sensitive `${argument}` and `${input.argument}` tool-command references while keeping argument display names unrestricted.
- Simplified XML mapping so only repeated siblings become arrays, attribute-free leaves become text scalars, and every attributed element preserves attribute names under `attributes`.
- Corrected workbook sidecar examples to include the mandatory package-unique `id`.

- Added configurable recursive `testcase.root` discovery: every adjacent `basename.yaml`/`basename.xlsx` pair below the root is one testcase set.
- Removed automatic tool `inputFile`/`outputFile` artifacts. Tools now return stdout, record input/stdout/stderr in the case log, and use action `saveAs` only when persistence is requested.
- Added tool and built-in indexes to generated docs plus workbook, sheet, Case ID, template, and tool filters.
- Standardized `timeoutMs` to 1–3,600,000 with a 10,000 ms default, and flattened XML child access such as `output.Messages.Message[1].attributes.severity`.

- Reorganized the ATT V2.1 end-user manual into a guided ten-chapter structure: Introduction, Quick Start, User Guide, Cookbook, CLI, Configuration, Expressions, Reports, Troubleshooting, and Maintainer Architecture. Merged the configuration and schema references into one reference chapter and retained the workbook/POI, tool-tokenization, XML, retry, CI, concurrency, and run-lifecycle examples.

- Enhanced HTML run reports with Workbook.Sheet group statistics, workbook/sheet/status filters, working case/tag search, and sortable Cases columns; persisted report metadata supports equivalent regeneration.

- Added mandatory package-unique workbook sidecar `id`; full Case IDs now use `<workbookId>.<groupId>.<rowCaseId>` with package validation and ExecutionPlan duplicate protection.

- Added default concurrent-run rejection plus explicit `run --queue` and `run --parallel` modes, pre-execution Run ID duplicate rejection, collision-safe numeric Run ID allocation at parallel completion, full run-validation diagnostics, and a consolidated Chapter 14 config/schema reference.

- Hardened V2.1 contracts with immutable pre-mutation `ExecutionPlan`, selected-tool executable validation, symlink-safe report/archive lookup, exact clean ownership, NetworkNT Draft 2020-12 validation, Jackson-only JSON writing, EXIT_CODE-only retry, safe full report regeneration, and BigDecimal assertions (`1.0 == 1.00`).

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
- Full Case IDs such as `payment.payment.TC001`, Chinese workbook fixtures, YAML map and scalar-shorthand template selectors, blank-marker normalization, and the persisted `CASE.STAGES.TEMPLATE.ACTIONS.TOOL` tree.
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
