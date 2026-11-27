# ATT v4.6.1 reference manual

Author: Jeffrey + ChatGPT
Version: 4.6.1
Status: Normative end-user documentation; generated from modular sources

<!-- GENERATED FILE. Edit docs/reference*/ modules, not this combined output. -->

**Contents**

- [Overview and product model](#overview-and-product-model)
  - [Product model](#product-model)
  - [RUN and LOAD](#run-and-load)
  - [Resources are peers](#resources-are-peers)
  - [Package boundaries](#package-boundaries)
  - [How to use this manual](#how-to-use-this-manual)
- [Test authoring](#test-authoring)
  - [Author a Native Case](#author-a-native-case)
  - [Distinguish Resolved Cases from Case executions](#distinguish-resolved-cases-from-case-executions)
  - [Authoring contracts](#authoring-contracts)
  - [Native Test Case Definition](#native-test-case-definition)
  - [Flow](#flow)
  - [Authoring lifecycle](#authoring-lifecycle)
  - [Test data ownership](#test-data-ownership)
  - [Testdata registry and input mapping](#testdata-registry-and-input-mapping)
- [Actions and typed values](#actions-and-typed-values)
  - [Action types](#action-types)
  - [Separate logical values from representations](#separate-logical-values-from-representations)
  - [File-content expressions return a `String`](#file-content-expressions-return-a-string)
  - [Tool, DB and Flow results](#tool-db-and-flow-results)
  - [Tool evidence collectors](#tool-evidence-collectors)
  - [Log: typed value to case log](#log-typed-value-to-case-log)
  - [Expressions and variable scope](#expressions-and-variable-scope)
  - [Resource output evidence](#resource-output-evidence)
  - [Action output and evidence paths](#action-output-and-evidence-paths)
  - [Common Retry and boolean conditions](#common-retry-and-boolean-conditions)
- [Runtime and Context model](#runtime-and-context-model)
  - [EXEC roots](#exec-roots)
  - [META inventory](#meta-inventory)
  - [Invocation and scope rules](#invocation-and-scope-rules)
  - [Optional lookup](#optional-lookup)
- [Expressions and built-ins](#expressions-and-built-ins)
  - [Unified expression engine](#unified-expression-engine)
  - [File-content expressions](#file-content-expressions)
  - [Argument result typing](#argument-result-typing)
  - [Testdata input mapping syntax](#testdata-input-mapping-syntax)
  - [Operators](#operators)
  - [Built-in functions](#built-in-functions)
  - [Resource helper methods](#resource-helper-methods)
  - [Expression scope and errors](#expression-scope-and-errors)
  - [Retry-condition lifecycle](#retry-condition-lifecycle)
- [Execution mode overview](#execution-mode-overview)
  - [Unified RUN and schedules](#unified-run-and-schedules)
  - [Run mode](#run-mode)
  - [Standalone Debug](#standalone-debug)
  - [Load mode](#load-mode)
- [Resources and integrations](#resources-and-integrations)
  - [Operation result and Evidence](#operation-result-and-evidence)
  - [Tool](#tool)
  - [DBHelper](#dbhelper-1)
  - [MQHelper](#mqhelper-1)
  - [HTTPHelper](#httphelper-1)
  - [SSHHelper: logical SSH targets](#sshhelper-logical-ssh-targets)
- [Reliability and execution control](#reliability-and-execution-control)
  - [Assertion and status](#assertion-and-status)
  - [`runWhen` and `onFailure`](#runwhen-and-onfailure)
  - [Timeout](#timeout)
  - [Retry and attempts](#retry-and-attempts)
  - [Evidence collectors](#evidence-collectors)
  - [Transaction and Resource lifecycle](#transaction-and-resource-lifecycle)
  - [Aggregation](#aggregation)
  - [Tool timeout precedence](#tool-timeout-precedence)
  - [Direct DB timeout and retry eligibility](#direct-db-timeout-and-retry-eligibility)
- [Configuration and environments](#configuration-and-environments)
  - [Configuration layers](#configuration-layers)
  - [Select an Environment](#select-an-environment)
  - [Configure execution identity and output](#configure-execution-identity-and-output)
  - [Ignore or disable ATT-owned configuration with x](#ignore-or-disable-att-owned-configuration-with-x)
  - [Resource bindings and credentials](#resource-bindings-and-credentials)
  - [Validate changes](#validate-changes)
- [Package Manifest](#package-manifest)
  - [Declare Package defaults](#declare-package-defaults)
  - [Resolve an Environment](#resolve-an-environment)
  - [Apply typed defaults](#apply-typed-defaults)
  - [Resolve Testdata for one invocation](#resolve-testdata-for-one-invocation)
  - [Inspect and publish in Studio](#inspect-and-publish-in-studio)
  - [Migrate legacy Package inventories](#migrate-legacy-package-inventories)
  - [API paths](#api-paths)
- [CLI Reference](#cli-reference)
  - [Build a source checkout](#build-a-source-checkout)
  - [Start with a command](#start-with-a-command)
  - [RUN a Case or Suite](#run-a-case-or-suite)
  - [Validate a Package](#validate-a-package)
  - [Debug a Flow or Tool](#debug-a-flow-or-tool)
  - [Run a Load workload](#run-a-load-workload)
  - [Run a Server command](#run-a-server-command)
  - [Common options](#common-options)
  - [Exit codes](#exit-codes)
- [Results, reports, and evidence](#results-reports-and-evidence)
  - [ATT Server Job artifact publication](#att-server-job-artifact-publication)
  - [Find artifacts for a RUN](#find-artifacts-for-a-run)
  - [Read a Case result](#read-a-case-result)
  - [Read the CI summary](#read-the-ci-summary)
  - [Diagnose Tool evidence collectors](#diagnose-tool-evidence-collectors)
  - [Reproduce a RUN](#reproduce-a-run)
  - [Read Case logs](#read-case-logs)
  - [Offline SUT Metrics report](#offline-sut-metrics-report)
- [Validation and troubleshooting](#validation-and-troubleshooting)
  - [Choose the first diagnostic step](#choose-the-first-diagnostic-step)
  - [Validate Native inputs](#validate-native-inputs)
  - [Common questions](#common-questions)
  - [Security and structured diagnostics](#security-and-structured-diagnostics)
- [CI, packaging, and operations](#ci-packaging-and-operations)
  - [Development/release gates](#developmentrelease-gates)
  - [Runtime dependencies](#runtime-dependencies)
  - [Studio inspection caches](#studio-inspection-caches)
  - [Documentation operations](#documentation-operations)
  - [CI and environment promotion](#ci-and-environment-promotion)
- [Appendix A — schema and version matrix](#appendix-a-schema-and-version-matrix)
- [Appendix D — limits, security guarantees and advanced diagnostics](#appendix-d-limits-security-guarantees-and-advanced-diagnostics)
  - [Limits and defaults](#limits-and-defaults)
  - [Collector projection and redaction guarantees](#collector-projection-and-redaction-guarantees)
  - [Advanced diagnostics](#advanced-diagnostics)
## Overview and product model

ATT separates reusable execution logic from business data. The current Case path binds a Flow and a Data Source in a Test Case Definition, resolves that Definition to selected Resolved Cases, and runs each Case as a Flow of ordered Actions and Resources.

### Product model

```text
Test Case Definition (att-testcase/v1)
  |-- Flow (att-flow/v3.6)
  `-- Data Source
        -> Resolution
        -> Resolved Case
        -> Suite selection
        -> Case Execution
              `-- Flow
                    `-- ordered Actions
                          |-- file-content expression / assert / log / assign / set
                          `-- Resource
                                |-- Tool
                                |-- DBHelper
                                |-- MQHelper
                                |-- HTTPHelper
                                `-- SSHHelper
```

A **Test Case Definition** (`att-testcase/v1`, usually `testcase.yaml`) binds one Flow and one Data Source. A **Flow** is reusable ordered Actions. **Resolution** turns the Definition into Resolved Cases. V1 resolves each nonblank source record to exactly one **Resolved Case**; it does not expand, join, or generate extra Cases from one record. **Suite selection** chooses which Resolved Cases to run. There is no mandatory `suite.yaml`. A **Case execution** is one RUN of one selected Resolved Case. An **Action** is one ordered unit of work inside that Flow. A **Resource** is a configured Tool, DBHelper, MQHelper, HTTPHelper or SSHHelper used by Actions or permitted expression calls.

### RUN and LOAD

RUN selects Resolved Cases by qualified ID, tag, or an implicit Suite. Debug runs a direct Flow or Tool target. LOAD repeats Flow or Tool workloads; Native Case LOAD targets remain deferred. See [Unified RUN and schedules](reference/execution-modes/unified-run.md).

| Mode | Input | Result |
|---|---|---|
| RUN | Definition and selected Resolved Cases | One Case Execution per selected Case |
| Debug | Direct Flow or Tool target and explicit inputs | One isolated diagnostic execution |
| LOAD | Validated scenario with Flow or Tool workloads | Repeated iterations and workload evidence |

Reusable Flows depend on `EXEC.INPUT`, `EXEC.VARS`, `EXEC.ACTIONS`, `META`, and Action-local `output`. Execution mode and scheduler identity are framework diagnostics in retained evidence, not expression data.

### Resources are peers

Tool, DBHelper, MQHelper, HTTPHelper and SSHHelper are independent resource types. They differ in configuration and lifecycle, while Actions publish native typed results through `output.result` and keep optional presentation evidence separate. Public expressions should consume Action results/evidence rather than resource-internal connection/process state. SSH resource operations use the common `ssh.<helperId>.<operation>` form inside a normal `type: tool` Action.

```text
Tool ------\
DBHelper ---+
MQHelper ---+--> typed operation result --> Action output
HTTPHelper -+
SSHHelper --/
```

### Package boundaries

Each Package owns Flows, Test Case Definitions, package-local Tools, Testdata, and generated output. All Packages use shared runtime configuration under `ATT_HOME/config/`; trusted schemas are bundled with ATT. A Package does not need its own `config/` or `schemas/` directory. Paths and identifiers are validated before execution. Credentials belong in protected runtime inputs or secret handling.

For a guided example, use [Quick Start](quick-start.md). For an existing Package authored with an earlier model, use the [Legacy documentation](legacy/README.md). The rest of this manual is normative lookup documentation.

### How to use this manual

| Goal | Go to |
|---|---|
| Build the first ATT package | [Quick Start](quick-start.md) |
| Understand the core ATT model | [Product model](reference/overview.md), [Test Authoring](reference/test-authoring.md), [Actions](reference/actions.md), [Runtime and Context](reference/runtime-context.md), and [Run mode](reference/execution-modes/run.md) |
| Configure DB/MQ/HTTP/SSH | [Resources](reference/resources/overview.md) |
| Find a CLI option | [CLI Reference](reference/cli.md) |
| Diagnose a failure | [Validation and Troubleshooting](reference/validation-diagnostics.md) |
| Maintain an existing Package | [Legacy documentation](legacy/README.md) |

Reference defines the public contract; README, Quick Start and examples explain that contract for narrower tasks. Each contract has one semantic owner; other Reference pages summarize and link to that owner.

## Test authoring

### Author a Native Case

New Cases follow this path. For a Package authored with an earlier model, use the [Legacy documentation](legacy/README.md).

~~~text
Flow + testcase.yaml + Data Source -> Resolution -> Resolved Case -> Suite selection -> Case Execution
~~~

1. Author or reuse a Flow (`att-flow/v3.6`).
2. Author a Test Case Definition (`att-testcase/v1`) that binds that Flow and a Data Source.
3. Resolve the Definition. Preview is read-only and does not create a Job.
4. Select Resolved Cases: one Case, explicit qualified IDs, tags, or an implicit Suite. There is no mandatory `suite.yaml`.
5. Validate the Package, then RUN. Each selected Resolved Case becomes one Case Execution with its own execution ID, status, and evidence.

`att-testcase/v1` is an exact 1:1 source-record contract, owned by this page and schema `att-testcase/v1`:

- A typed `inputs` map with no Data Source resolves to one Case whose ID is `default`.
- Each nonblank inline or Excel record resolves to exactly one Resolved Case. Resolution does not expand one record into many Cases.
- `caseIdExpression` is a single `${DATA.key}` lookup. It is not a generator.
- Excel `dataColumns` explicitly selects source columns and supports aliases, YAML parsing, and quoted delimiters.
- The Definition `id` is Package-scoped. A Resolved Case ID is one path-safe segment. The qualified ID is `definitionId.caseId`.
- Typed input projections, reports, and Package files stay isolated. Resolution and RUN do not modify the Definition or the source workbook.

n-to-m generators are outside `att-testcase/v1` and remain deferred. For data-source format details maintained for existing Packages, see the [Legacy documentation](legacy/README.md).

### Distinguish Resolved Cases from Case executions

A **Resolved Case** is one selectable logical Case produced by Resolution. A **Case execution** is one runtime execution of that Resolved Case. The qualified Case ID is stable across RUNs; the execution ID, status, and evidence belong to one execution.

### Authoring contracts

| Need | Native contract |
|---|---|
| Reusable ordered Actions | Flow |
| One ordered operation | Action |
| External capability | Resource |
| Business input | Definition `inputs` or a `DATA` projection |
| Cross-Action mutable state | `EXEC.VARS` |
| Case selection | Resolved Case IDs, tags, or an implicit Suite |

### Native Test Case Definition

The active contract is `att-testcase/v1`. The current default branch includes Native Case CLI and validated Server draft-flow support for discovery, resolution, and RUN of singleton, inline, and Excel-backed Definitions. That code is integrated on this branch. Native RUN is implemented here. No 4.6.0 release or tag has been published. Implicit Suite acceptance, Native report acceptance, and remaining Default Package adoption stay open. Native LOAD Case targets, n-to-m generators, and Debug interoperability stay deferred. Existing direct LOAD and Debug paths remain supported.

The native Excel adapter reads a Package-contained `.xlsx` Data Source without modifying it and records workbook revision, sheet, row, and column provenance on each Resolved Case. It accepts at most 32 MiB of compressed workbook bytes; before POI loads the workbook, ZIP preflight caps it at 256 entries, 32 MiB per expanded entry, and 64 MiB total expanded data. The selected worksheet is capped at 100,000 physical rows, including blank rows. Header scanning is capped at 1,000,000 header cells, and Resolution accepts at most 10,000 nonblank records; preview output remains bounded. YAML cells use the shared parser limits of 3,000,000 code points, 50 collection aliases, and 50 nested levels; resulting value graphs are further capped at depth 64 and 100,000 values. Non-finite numbers and recursive aliases are rejected.

The v1 contract defines a singleton inline Case with a complete typed `inputs` map and the fixed Resolved Case ID `default`, or a multi-record inline/Excel Data Source with stable source IDs. Definition IDs are Package-scoped, case-preserving dotted IDs; Resolved Case IDs are one path-safe segment. Excel Case IDs must be literal text cells matching `[A-Za-z0-9_-]{1,128}`; formulas, numeric/date/boolean/error cells, blank markers, surrounding whitespace, reserved device names, and case-fold collisions are rejected. A `caseIdExpression` can override the resolved ID using only one `${DATA.key}` lookup. Empty, duplicate, malformed, missing, or colliding IDs and empty resolution results are errors; they never produce an all-PASS result.

For data-driven Cases, omitted `inputs` means same-name projection from `DATA`; a supplied projection replaces that identity mapping and uses `{from: DATA.key}` or `{value: <typed value>}`. Excel `dataColumns` explicitly selects columns and supports aliases, YAML parsing, and quoted delimiters. Omitted or blank `dataColumns` selects no business columns into `DATA` (Case ID and tags selectors are never auto-exposed); declare `dataColumns` explicitly when Excel inputs are required. Excel `path` values must be portable Package-relative `.xlsx` paths.

Every Native RUN writes the source-independent result workbook at `workbooks/native-cases.result.xlsx`; it contains the selected Case results and does not depend on the source workbook. An Excel-backed Definition may also set `report: {format: xlsx, template: sourceWorkbook}` to request the optional source-workbook-shaped artifact at `workbooks/native-cases.source-workbook.xlsx`. Before RUN starts, Engine pins and verifies the workbook bytes, formula inventory, and selected physical row identities. It writes results only to those selected rows in a separate file, preserves source formulas, and leaves the Package workbook unchanged.

The optional artifact is limited to one Excel Definition and worksheet, a nonempty selection of at most 1,000 Cases, and an output size of at most 16 MiB. Before publication, ATT redacts sensitive source columns on every worksheet, including columns whose headers or mapped aliases are classified as sensitive, and masks registered secret values elsewhere. If redaction could leave a sensitive cached formula result or safely verifying/publishing the source-shaped file fails, ATT omits that optional artifact and records a fallback diagnostic; the standard result workbook and Case results remain available. A workbook that changed after resolution or no longer matches the pinned rows is rejected before execution rather than silently falling back to stale source data.

The source-shaped artifact contains the workbook's other rows and sheets, with sensitive values redacted. The Server publishes and serves it only when the Job's Package source permission is current; it rechecks Package editor permission for the artifact listing, each entry read, and the archive download. Users without that permission can still access the source-independent standard result workbook and other Job artifacts under the normal artifact policy.

See the [active schema](../schemas/att-testcase-v1.schema.json), [single inline example](adr/examples/att-testcase-v1/inline-single.yaml), [multi-record inline example](adr/examples/att-testcase-v1/inline-multi.yaml), [Excel example](adr/examples/att-testcase-v1/excel.yaml), [Excel omitted-`dataColumns` example](adr/examples/att-testcase-v1/excel-omitted-data-columns.yaml), and [ADR-0001](adr/0001-flow-centric-testcase-resolution.md) for the complete contract and validation boundary.

The Default Package ships offline Native Quick Start Definitions under `packages/default/testcase/quick-start/**/testcase.yaml` bound to Flow `quick.start.v1`. Use the [Default Package Native Quick Start guide](quick-start.md) and the [S12 migration matrix](maintenance/native-case-default-package-migration-s12.md). S07 adds source-workbook-shaped reports for Excel-backed Definitions.

#### Author in Studio

In Explorer, choose **New native Test Case** or open an indexed `testcase.yaml`. The guided editor sets the Package-scoped Definition ID, name, tags, canonical Flow, typed inputs, and either one Case or inline records. Advanced YAML remains available for fields outside the guided form. New files must be placed under the Package's configured `resources.testcaseRoot` (default `testcase/`).

**Preview / Resolve** sends the current editor candidate and Package-relative `testcase.yaml` path to the authenticated Server. The Server checks the exact USER/editor authority and Package source revisions, then resolves the candidate through the bounded Test Case resolver and S04 Excel adapter. Preview output is capped at 1,000 Cases and returns the Package source revision, Definition revision, and workbook revision when applicable. Excel row errors include sheet, row, and column locations. Typed inputs use the Server's configured redaction policy. Preview does not create a Job or RunDraft, write Package source, or modify a workbook; RUN validation resolves and freezes the selected Case IDs again.

Use **Add to Change Set** to stage a create or replacement through the existing Package source workflow: Draft → Validate → Review → Publish. The current Package source stays active until Publish. After publication, preview the latest source and select the Case IDs to validate and submit a RUN. Native Case RUN is available only when the Server advertises `att-execution/v2`; the Server validates and freezes the source and selected IDs for the Job.

Excel-backed definitions use the same read-only S04 adapter as native resolution, with row and column diagnostics shown in Studio. For a Package authored with an earlier model, use the [Legacy documentation](legacy/README.md).

#### Run an implicit Native Case Suite

The 4.6.0 release target adds Suite selection over resolved Cases without an authored `suite.yaml`. Declare one or more Definition IDs, then select all Cases, explicit qualified Case IDs, or Cases matching one or more tags. Excluded tags take precedence over included tags.

This local command selects every `smoke` Case from two Definitions except Cases tagged `destructive`:

```sh
att --local --package payments run native-suite --definition fpp.transfer --definition fpp.refund --tag smoke --exclude-tag destructive --fail-fast
```

To select named Cases, use `--qualified-case-id` for each ID, such as `fpp.transfer.customerA`. Each ID must belong to one of the declared Definitions. The Server backend uses the same selectors through the validated `att-execution/v2` RUN draft flow.

ATT orders Definitions by ID and keeps each Definition's source record order. Duplicate Definition IDs, duplicate qualified Case IDs, unknown IDs, and empty selections fail validation. The accepted Server draft freezes its selected IDs and Definition source revisions before the Worker runs. Per-Case results use the existing RUN evidence and report pipeline.

This implicit Suite is a runtime selection, not a reusable authored resource. It does not add LOAD targets or advanced Testdata/generator bindings; Debug interoperability remains deferred.

Studio's Run editor also exposes this selection: enter one or more Definition IDs, then choose all Cases, explicit Qualified Case IDs, or Cases matching tags. Optional included and excluded tags further filter the selection, and replay preserves tag selectors returned in the saved definition. Native Suite `--rerun-failed` retries only failed, executed Cases from the latest eligible completed RUN for the same Package and Definitions. Local runs verify the latest-run pointer and manifest hash; Server drafts freeze the source Job/run, manifest digest, Package revision, and exact Qualified Case IDs. A stale draft or changed source is rejected, skipped Cases are not retried, and per-attempt execution/skipped counts remain separate from source and retry counts. Schedules retain the validated retry source. The same selector is available in Studio's Run editor and on the CLI. It is implemented and integrated on the current default branch. Implicit Suite acceptance remains open, and no 4.6.0 release tag has been published.

### Flow

A Flow is a first-class reusable sequence of ordered Actions, declared in `flow.yaml` using `att-flow/v3.6`. A Native Test Case Definition binds that Flow directly. Required fields are `schemaVersion`, a versioned canonical `id` such as `common.payment.v1`, `name`, `description`, and a non-empty ordered `actions` map. Each invocation creates a fresh `EXEC.ACTIONS` scope and restores the caller's scope on return. `META.FLOW` exists during the invocation only. [Actions](reference/actions.md) owns Flow results and Assign behavior; [Context](reference/runtime-context.md) owns scope lifetime.

### Authoring lifecycle

After changing a Flow or a Test Case Definition, run `./att.sh validate --package`, then RUN the selected Resolved Cases. Use [Debug](reference/execution-modes/debug.md) to isolate one Flow or Tool, and [Run](reference/execution-modes/run.md) to create Case executions. Follow [Quick Start](quick-start.md) to build the first package.

### Test data ownership

A Native Test Case Definition owns its Data Source and input projections. Resolution reads that source and does not modify it. [Context](reference/runtime-context.md) defines their scope and lifetime. RUN turns each selected Resolved Case into a Case execution. Environment selection belongs to [Configuration](reference/configuration.md).

### Testdata registry and input mapping

Use `att-testdata/v1.0` descriptors for reusable records, then reference them from a Case, Debug `inputs`, or LOAD workload `inputs` mapping. An exact `@{id}` reference keeps the record's native map/list/scalar type; `@{id.path}` selects a nested value, including a numeric list index. Interpolated references such as `"ORD-@{accounts.id}"` produce text and therefore require a scalar value from the selected record. `${...}` in a mapping reads Context roots initialized before that mapping is resolved. The allowed roots depend on the mapping phase, and validation checks them before execution starts (before the scheduler starts for Load):

- RUN mappings may read `EXEC.ID`, `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, `EXEC.OUTPUT_DIR`, and `META.SOURCE` or `META.TARGET`.
- Debug `inputs` may read the same execution roots and metadata, plus target metadata.
- Load workload `inputs` may read `EXEC.RUN_ID`, `EXEC.STARTED_AT`, `EXEC.RUN_STARTED_AT`, initialized `EXEC.LOAD` identity fields, and `META.SOURCE`, `META.TARGET`, or target metadata. `EXEC.ID` and `EXEC.OUTPUT_DIR` are initialized only after input resolution. `EXEC.LOAD.USER_ID` is absent for arrival-rate workloads; use the optional path form `${EXEC.LOAD.USER_ID?}` when one mapping must support both models.

Every mode rejects references to `EXEC.INPUT` (the value being built), `EXEC.VARS`, `EXEC.ACTIONS`, Action `output`, and invocation-scoped helper metadata. The V1 mapping grammar evaluates literals, selected-record `@{...}` references, and `${...}` Context references; built-in calls are not evaluated. `#{...}`, `&{...}`, and `%{...}` are not input-mapping expressions.

~~~yaml
schemaVersion: att-testdata/v1.0
id: accounts
records:
  - {id: "A-100", tier: gold}
  - {id: "A-200", tier: silver}
selection: {strategy: sequential, exhaustion: recycle}
~~~

Generated records are virtual and indexed; ATT materializes only the selected record. The inclusive integer range is capped at 1,000,000 records, and `%{seq}` is the only supported generated-record substitution:

~~~yaml
schemaVersion: att-testdata/v1.0
id: generatedAccounts
records:
  generate:
    seq: {from: 100, to: 999999, format: "%06d"}
  record: {id: "A-%{seq}", amount: 42}
selection: {strategy: roundRobin, exhaustion: stop}
~~~

The [Package Manifest](reference/package-manifest.md) declares the Package Testdata registry and optional Environment-specific replacement lists. A Load scenario may declare its own top-level `testdata` imports; matching IDs replace the whole environment descriptor for that Load only. Duplicate IDs within one layer fail. Ordinary Run/Debug activate referenced IDs lazily, while `validate --package` checks every declared Manifest descriptor layer. Flows and Tool definitions receive resolved values through `EXEC.INPUT`; they cannot contain direct `@{...}` or `%{...}` references. See [Environment and Test Data](reference/configuration.md), [Load](reference/execution-modes/load.md), and the maintainer [testdata design](system-design/testdata.md).

## Actions and typed values

This page defines the Native Action contract. Native Flow Actions use att-flow/v3.6. Each completed action publishes its logical typed value at output.result. Actions do not use a shared result.format/path/overwrite object. For resource configuration, start with [Resources](reference/resources/overview.md).

### Action types

| Type | Required fields | Result and behavior |
|---|---|---|
| tool | call | Invokes a configured Tool, built-in or helper call and preserves the native typed result. DB query/scalar/update calls are ordinary Tool calls. |
| assert | assert | Evaluates a boolean condition and records PASS or FAIL. expected and actual are optional diagnostic values. |
| log | message or value | Formats a typed value for the Case log. Its fields are message, value and format. |
| assign | name and expression | Publishes the expression's typed result below EXEC.VARS once. |
| set | name and expression | Replaces an existing top-level EXEC.VARS value with the expression's typed result. |
| flow | use | Runs a registered Flow in a nested Action scope and restores the caller's scope on return. |

Actions run in YAML order. Where supported, an action may also define id, description, onFailure and runWhen. Action IDs are unique within their scope. Type-specific invalid fields fail validation. Action result, Log file and Log fields are not part of the current action contract.

For the lowercase `x-` convention that disables ATT-owned Action fields and keyed entries before validation or execution—and how it differs from `runWhen: false`—see [Configuration](reference/configuration.md#ignore-or-disable-att-owned-configuration-with-x).

### Separate logical values from representations

ATT keeps the logical operation result separate from human or wire representations:

| Boundary | Field/value | Purpose |
|---|---|---|
| Command Tool stdout | stdoutFormat | Parses external stdout into a typed result. |
| HTTP/MQ response | responseFormat | Parses external response bytes into a typed result. |
| File-content expression | `String` | Reads one safe UTF-8 package file and preserves its exact characters after expression evaluation. |
| Abstract Map/List sent over HTTP/MQ | requestFormat | Serializes the value at the outbound boundary. |
| Log or resource evidence | format / evidence.output.format | Produces a human-readable representation. |

DB results are already typed values. Tool, Action, Flow and expression results remain typed while they move through ATT.

DB query, scalar, and update operations use the first-class DBHelper call forms `db.<helper>.query(...)`, `db.<helper>.scalar(...)`, and `db.<helper>.update(...)` inside a normal `type: tool` Action. A DB call accepts one String `sql` argument plus either positional `params` or named `parameters`; `sql=&{package-relative-file.sql}` supplies package SQL content. The historical `type: db` Action is retained only by archived schema versions.

### File-content expressions return a `String`

The current replacement for the historical Render Action is the typed file-content value expression `&{path}`. It always returns one `String`; it never infers a document format, parses an extension, expands a glob, or creates an output file:

~~~yaml
requestText:
  type: assign
  name: requestText
  expression: "&{payload/payment/request.xml}"
~~~

`${...}` remains a Context reference and `#{...}` remains an expression/call. `&{...}` is a static, one-file locator; v1 has no glob or dynamic locator form. The locator is relative to the canonical ATT package root. A descriptor-relative `./` or `../` path is allowed only when its canonical target remains inside that root. Absolute paths, missing files, directories, symlink escapes, non-UTF-8 bytes, surrounding whitespace and glob syntax fail validation.

Ordinary UTF-8 files are returned unchanged. If the file contains `${...}` or `#{...}`, ATT compiles those nodes once and evaluates them for each execution; the compiled plan is immutable and dynamic values are not reparsed as a new expression source. Run and Debug reuse the plan until the file fingerprint changes. Load validates and captures the selected file identity, content and compiled dependency closure before scheduling, so active iterations see a immutable dependency closure.

Use Assign when the String is reused by later Actions:

~~~yaml
requestText:
  type: assign
  name: requestText
  expression: "&{payload/payment/request.xml}"
sendRequest:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

For HTTP or MQ, pass the `String` as the body/payload. The resource encodes the exact text with its configured charset/CCSID. HTTP content type and MQ transport metadata remain resource-owned settings. `&{...}` is valid in Tool/Helper call arguments, Assign expressions, Log values and other typed value positions.

requestFormat is for abstract structured values such as Map or List. Such a body requires an explicit format, for example requestFormat=json. Combining requestFormat with a `String` fails; a file-content result is never silently parsed and serialized. HTTP, MQ, and SSH Resource calls consume file content as String values and do not resolve local file-path arguments.

### Tool, DB and Flow results

A command-backed Tool declares stdoutFormat in its Tool descriptor:

~~~yaml
tools:
  getOrder:
    command: [./get-order.sh]
    stdoutFormat: json
~~~

stdoutFormat parses external stdout once into output.result; it is not output serialization. Call-backed Tools and DB/HTTP/MQ/SSH operations keep their native return types.

A DB action uses db and exactly one query or update block. SQL, bind parameters, transaction controls and DB evidence follow the DB action and DBHelper contracts.

A Flow action uses use with a canonical Flow ID. It runs in a fresh EXEC.ACTIONS scope and publishes its result/evidence to the caller when it returns. META.FLOW exists only while that invocation is active.

### Tool evidence collectors

A Tool Action may define first-class `evidence` collectors for diagnostics that must be gathered before the Action assertion. The lifecycle is:

```text
primary Tool call
    -> typed primary output.result
    -> evidence collector call(s)
    -> Action assertion
    -> PASS / FAIL / ERROR
```

Collectors are diagnostic operations, not replacement Actions. Each collector has its own typed result and does not replace or mutate the primary `output.result`:

```yaml
callPayment:
  type: tool
  call: >-
    #{mq.payment.request(
      payload=${EXEC.VARS.requestText},
      responseFormat='xml'
    )}
  evidence:
    appLog:
      call: >-
        #{ssh.app.execute(
          command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100'
        )}
      timeoutMs: 10000
      onFailure: continue
  assert: >-
    ${output.result.replyReceived} == true
```

While the containing Action, including its assertion, is active, use these paths:

```text
${output.result}
${output.evidence.collectors.<collectorId>.result}
${output.evidence.collectors.<collectorId>.status}
```

After publication, the same values are available below `EXEC.ACTIONS`:

```text
${EXEC.ACTIONS.callPayment.output.result}
${EXEC.ACTIONS.callPayment.output.evidence.collectors.appLog.result}
${EXEC.ACTIONS.callPayment.output.evidence.collectors.appLog.status}
```

The public shape keeps primary resource evidence and collector evidence separate:

```text
output
├── result                         # primary Tool logical result
├── evidence
│   ├── <resource-kind>            # primary operation evidence
│   └── collectors
│       └── <collectorId>
│           ├── result             # typed collector result
│           ├── status
│           ├── invocationId
│           └── durationMs
└── attempts
    └── [n]
        └── evidence.collectors.<collectorId>.result/status
```

When a Tool retries, collectors run for every primary attempt before that attempt's assertion. The top-level `output.evidence.collectors.<id>` is the final/winning attempt; `output.attempts[n].evidence.collectors.<id>` retains each attempt, including earlier failures. After publication, the corresponding history path is `${EXEC.ACTIONS.<actionId>.output.attempts[0].evidence.collectors.<id>.result}`.

`call` is required. `timeoutMs` is independent of the primary Tool timeout. `onFailure: continue` is the normal application-log pattern so a diagnostic collection failure does not hide the original business or assertion failure; `stop` makes the collector failure an Action error. Collector status and diagnostic remain observable, and collector failure never changes the primary logical result. Distinguish an evidence collector from an ordinary Tool/Log/Assign Action: use a collector for diagnostic data needed before the containing Tool assertion, and an ordinary Action when the collected value is normal business/test data for later assertions.

Collector results follow the normal typed-result rules. Evidence placement does not stringify a map, list or file-content `String`; helper `evidence.output` is an explicit presentation boundary. In Load, explicit collector execution is separate from helper `evidence.output` serialization. Resource-output formatting remains controlled by the Load evidence policy and is not silently substituted for or dropped in place of an author-requested collector.

### Log: typed value to case log

Log is a presentation action and therefore has its own format field:

~~~yaml
logOrder:
  type: log
  message: "Order response"
  value: "${EXEC.ACTIONS.callOrder.output.result}"
  format: yaml
~~~

Log is a plain Case-log entry. The current v3.6 contract removes `level`; delete it when migrating. Earlier Log-level behavior is documented in the [Legacy authoring guide](legacy/authoring.md). Internal diagnostic severity remains separate. At least one of message or value is required. message is rendered as text. value accepts any typed value, including nested maps/lists. Exact ${...} and #{...} expressions preserve their native types; map/list children are evaluated recursively without converting numbers, booleans, nulls or nested values to strings. format accepts text, json, yaml, xml or sqlplus and controls only the emitted Case-log string. When format is present, value is required.

When both message and value are supplied, Log emits the message, a newline, then the formatted value. output.result is that emitted string. A file-content String is emitted as-is when used as a value; Log does not infer or attach a document format. Log does not read a file and has no fields map. Put a typed map/list in value for structured log content.

### Expressions and variable scope

Action expressions use the regular ATT expression engine. A complete ${...} or #{...} expression keeps its result type; embedding an expression in surrounding text produces a String. See [Expressions and Built-ins](reference/expressions.md).

assign publishes its typed value once below EXEC.VARS.<name>. The name must match [A-Za-z_][A-Za-z0-9_]* and be unique within the Case. Use set with the same name and expression fields to update an existing top-level EXEC.VARS value; set never declares a missing variable. Its expression reads the old Context value and publishes only after evaluation and its assertion succeed, preserving the native result type. A set may use the common Action retry block: each attempt evaluates against the unchanged variable, failed attempts leave its old value intact, and only the winning attempt publishes once. Attempt status and retry decisions remain in output.attempts. Both Actions share EXEC.VARS across Actions in sequence and nested Flow calls. A set of a Debug/Load bootstrap variable consumes its first-assign allowance; a later assign cannot overwrite that update. Action-local output is available at output.* while an Action runs and at EXEC.ACTIONS.<id>.output.* after publication. Flow invocation creates a temporary Action namespace; EXEC.VARS updates remain visible to the caller.

~~~yaml
initializeCounter:
  type: assign
  name: counter
  expression: "#{0}"
incrementCounter:
  type: set
  name: counter
  expression: "#{${EXEC.VARS.counter} + 1}"
~~~

The example leaves `EXEC.VARS.counter` as a typed number. A skipped or failed set leaves its previous value unchanged.

### Resource output evidence

Resource evidence is separate from the logical result. A helper may configure an optional evidence.output presentation policy:

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

This adds a bounded bounded human-readable rendering beside operation metadata; it does not change output.result or response parsing. In Load, evidence.resources.output accepts inherit (default) or none. none skips resource-output formatting and file materialization. Metrics-only iterations create no execution directory. When iteration evidence is retained, eligible resource output is formatted lazily into that workspace.

### Action output and evidence paths

| Path | Meaning and availability |
|---|---|
| `output.result` | Primary typed Action result while the Action is active, including its assertion. |
| `output.evidence.collectors.<id>.result` | Typed result of an active Tool evidence collector. |
| `output.evidence.collectors.<id>.status` | Collector `PASS`/`ERROR` status while the Action is active. |
| `output.evidence.collectors.<id>.error` | Bounded failure summary with a non-blank `message` when the collector fails. |
| `output.evidence.collectors.<id>.evidence` | Preserved bounded/redacted underlying Tool/resource evidence, including resource identity and native failure fields when supplied. |
| `EXEC.ACTIONS.<actionId>.output.result` | Published primary typed result after the Action completes. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result` | Published final/winning collector result. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.status` | Published final/winning collector status. |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.error/evidence` | Published collector failure summary and preserved operation evidence. |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.result/status` | Collector result/status for a specific retry attempt; earlier attempts remain after a later success. |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.error/evidence` | Failure summary and underlying evidence for that specific collector attempt. |

Strings, numbers, booleans, null, maps and lists remain typed across Action/Flow boundaries.

### Common Retry and boolean conditions

Tool Actions, retry-capable DB query/scalar calls, and set Actions share the `retry` contract. `maxAttempts` (2–10), `intervalMs` (0–3600000) and a non-empty `retryOn` list (ASSERTION/TIMEOUT) remain required. `when` is an optional non-empty Boolean expression String. `ASSERTION` retries for set require an Action `assert`; failed attempts never commit their candidate to EXEC.VARS. Existing restrictions on mutating DB updates and SSH transfers still apply.

~~~yaml
retry:
  maxAttempts: 3
  intervalMs: 1000
  retryOn: [TIMEOUT]
  when: "#{${output.evidence.mq.invocations[0].reasonCode?} != 2033}"
~~~

Each attempt executes its operation, publishes current result/evidence/diagnostic, evaluates its assertion where applicable, and selects a retry category. Only after `retryOn` matches, and while attempts remain, does ATT evaluate `when`. Omitting it preserves ordinary retry behavior. True permits the interval wait and another attempt; false preserves the current TIMEOUT/FAIL and stops. Successful attempts, category mismatches and exhausted attempts do not evaluate the gate.

The condition can inspect `output.status`, `output.result`, `output.evidence`, `output.diagnostic`, the one-based `output.attempt`, and EXEC/META paths valid in the current scope. Top-level output is cleared at the start of each attempt so it cannot expose stale result/evidence. History remains in `output.attempts[n]`. Each `retryDecision` records category, candidate, whenEvaluated, whenResult (if evaluated), allowed and a reason such as WHEN_FALSE or MAX_ATTEMPTS.

Conditions use normal `${...}`/`#{...}` typing and must return Boolean; numbers and strings such as 'false' are not coerced. Use `when: "#{false}"` to stop retry. Strict missing paths and expression failures produce normal diagnostics at retry.when and terminate retry. Pure deterministic built-ins are allowed; Tool/DB/MQ/HTTP/SSH calls, file/file-content operations, sequences, randomness and current-time operations are forbidden. Deterministic syntax/type errors fail validation; runtime result types and unavailable paths are checked when the gate runs.

TIMEOUT is the canonical Action outcome; suite/report aggregate operational failure remains ERROR. Authors must decide whether side-effecting operations such as MQ request or HTTP POST are safe to replay. Unconditional TIMEOUT retry can duplicate a business transaction; ATT does not silently suppress MQ retry. See the [MQHelper example](reference/resources/mqhelper.md).

## Runtime and Context model

RUN, Debug, and LOAD share the canonical `EXEC` and `META` expression roots. `EXEC` holds identity, typed inputs, variables, and completed Actions. `META` describes the selected source, target, Flow, and active Resource invocation. These roots contain no secrets.

Standalone Debug maps `inputs` to `EXEC.INPUT`; optional `vars` seed `EXEC.VARS`. See [Standalone Debug](reference/execution-modes/debug.md) for the input schema and protected roots.

### EXEC roots

| Path | Meaning and availability |
|---|---|
| `EXEC.ID` | Current Case execution, Debug execution, or LOAD iteration ID; available after initialization. |
| `EXEC.RUN_ID` | Enclosing ATT Run ID; shared by Cases and iterations in a run. |
| `EXEC.STARTED_AT` | Current execution start time as an ISO-8601 string. |
| `EXEC.RUN_STARTED_AT` | Enclosing run start time as an ISO-8601 string. |
| `EXEC.OUTPUT_DIR` | Execution workspace path; in LOAD it may be planned before the directory exists. |
| `EXEC.INPUT` | Typed input map for a Case, Debug target, or LOAD workload. |
| `EXEC.VARS` | Typed values created by `assign`, updated by `set`, or supplied as Debug bootstrap variables. |
| `EXEC.ACTIONS` | Completed Actions in the active Flow scope. A nested Flow receives a separate scope. |
| `EXEC.LOAD.*` | Workload and iteration identity available only during LOAD. `USER_ID` is absent for arrival-rate workloads. |

Scheduler counters, queue state, and timing diagnostics remain in evidence; they are not expression roots. A reusable Flow should receive business identifiers through its input contract, not infer them from `EXEC.ID`.

### META inventory

| Path | Meaning and availability |
|---|---|
| `META.SOURCE.type` | Source kind: `testcase`, `debug`, or `load`. |
| `META.SOURCE.path` | Logical Package-relative source path when the source is file-backed. |
| `META.SOURCE.caseId` | Qualified Case ID during a Case RUN; absent for Debug and LOAD. |
| `META.SOURCE.scenario` | LOAD scenario identity; absent outside LOAD. |
| `META.TARGET.type` | Resolved target kind: Case, Flow, or Tool. |
| `META.TARGET.id` | Resolved target identifier. |
| `META.FLOW.id` | Active Flow ID during a Flow invocation. |
| `META.FLOW.invocationId` | Calling Action ID during that invocation. |
| `META.FLOW.depth` | One-based nested Flow depth. |
| `META.TOOL.id`, `META.TOOL.type` | Active configured Tool or built-in invocation. |
| `META.DBHELPER.*`, `META.MQHELPER.*`, `META.HTTPHELPER.*`, `META.SSHHELPER.*` | Logical Resource identity during the corresponding invocation. |

META is descriptive and immutable. An inactive component's fields are absent. Resource endpoints, usernames, identity files, and credentials remain inside the executor and are never published as META values.

### Invocation and scope rules

Entering a Flow, Tool, or Resource invocation publishes its active metadata. Nested calls push a scope frame and restore the caller's metadata on return. With no active invocation, its branch is absent; do not depend on “last call” state.

`EXEC.INPUT` is the input map for the current execution. `EXEC.VARS` is available to later Actions in the same Flow invocation. `EXEC.ACTIONS` contains completed Action results in that Flow scope. During an Action, read its local `output`; after completion, the value is published at `EXEC.ACTIONS.<id>.output`.

### Optional lookup

`${path}` is a strict lookup. `${path?}` returns null only for an allowed missing map/list path; it does not make invalid syntax or disallowed scope access valid.

For existing Packages that rely on earlier Context names or metadata, use the [Legacy documentation](legacy/README.md). The [Actions reference](reference/actions.md) owns Action results; [Debug](reference/execution-modes/debug.md) and [LOAD](reference/execution-modes/load.md) own their input initialization rules.

## Expressions and built-ins

### Unified expression engine

ATT uses one expression engine for Native Flows, Actions and Tool calls:

- ${path} reads a Context value and interpolates it into surrounding text.
- #{expression} evaluates a typed expression. It supports Context operands, built-in calls, list literals, parentheses, unary operators, arithmetic, comparisons, like, in, null checks and boolean logic.

A complete expression preserves its value type. For example, an exact #{...} may return a number, boolean, map, list or String. Embedding an expression in surrounding text also produces a String. Use canonical EXEC and META paths; optional lookup uses a trailing question mark.

~~~yaml
assert: "#{${EXEC.INPUT.amount} > 0}"
description: "case=${META.SOURCE.caseId}; value=#{upper(${EXEC.INPUT.name})}"
~~~

Use the expression form supported by each field. File-content content, Action descriptions/assertions, Log message/value, assign/set expressions and Tool calls use the ordinary runtime model. A Log value can recursively contain typed expressions; see [Actions and Typed Values](reference/actions.md).

### File-content expressions

`&{path}` is a file-content expression. It reads exactly one statically addressed regular UTF-8 package resource and returns its String content. Bare names resolve from the package root; explicit `./` and `../` locators resolve from the referring descriptor directory. Static validation, runtime-value validation, compiled File Expression plans and execution all bind that descriptor source directory for the current Template/Flow body, including nested Flow invocations that restore the caller's scope on return. Resolution rejects absolute names, package escapes and symlink targets outside the package. It never infers or parses a document format, expands a glob or creates an output file. Absolute paths, missing files, directories, symlink escapes, non-UTF-8 bytes, surrounding whitespace, glob syntax and dynamic locators fail validation. File content is not implicitly parsed as JSON, YAML or XML.

### Argument result typing

An argument expression is evaluated recursively before its receiving call runs. The call's argument contract applies to the final resolved value only: an inner `&{...}` passed to `str.substr` first produces file text, `str.substr` returns its String result, and only then does the outer target argument apply its type and range rules. Thus a numeric helper argument may accept `waitMs=#{str.substr(&{params/wait.txt}, 0, 4)}` when that result is an integral value, while a String payload keeps every original character. Scalar String coercion trims only for numeric or Boolean parsing; it does not globally trim String/payload values. Map/List values remain typed and are never inferred from file extensions or parsed file content.

Package validation resolves literal values, static file content and explicitly pure built-ins when their inputs are static, then applies the same target argument contract used at runtime. It checks the structure and declared types of runtime-dependent expressions and defers only their final value/range checks. Tool, resource and external calls are never run to discover values during validation.

Use a YAML string when authoring a standalone value or embedding the locator in a larger expression:

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{payload/payment/request.xml}"
send:
  type: tool
  call: "#{http.payment.post(body=${EXEC.VARS.requestText})}"
~~~

`${...}` and `#{...}` inside the file are compiled and evaluated when the file value is used. Run and Debug cache the compiled plan and invalidate it when the file fingerprint changes; Load freezes the validated file identity, content and compiled plan for the scenario. File output is not reparsed as a new expression source.

### Testdata input mapping syntax

Testdata references are resolved while Native Case, Debug, or Load input maps are prepared; they are not part of the general `${...}` / `#{...}` expression engine. Use `@{id}` to preserve a selected record's native type, `@{id.object.path}` or `@{id.items[0]}` to select a value, and scalar interpolation to compose text. One logical ID selects one record per mapping/lifetime, so every reference to that ID in the same mapping sees the same record. Mapping interpolation rejects nulls, maps and lists. `${...}` can read initialized Context values except `EXEC.INPUT`; input construction cannot depend on itself. Direct testdata markers are rejected in reusable Flow and Tool definitions so those components consume resolved `EXEC.INPUT` values only.

See [Testdata Registry and Input Mapping](reference/test-authoring.md) for descriptor and generation syntax.

### Operators

Supported assertion operators are `==`, `!=`, `>`, `>=`, `<`, `<=`, `like`, `is null`, `is not null`, `not`, `and`, and `or`. Use parentheses when mixing logical operators so intent is explicit.

`like` is case-insensitive as an operator keyword, but the canonical authored spelling is lowercase. It matches the complete value and uses two SQL-style wildcards:

- `%` matches zero or more characters;
- `_` matches exactly one character;
- matching is case-sensitive.

The current implementation translates `%` to Java regular-expression `.*` and `_` to `.`. Other regular-expression metacharacters such as `.`, `+`, `*`, `[`, `]`, `(`, `)`, `?`, `^`, `$`, `|`, and backslash retain their regular-expression meaning instead of being escaped automatically. Use `like` with ordinary literal text plus `%`/`_`; use `==` for exact text.

Comparison first recognizes boolean literals. If both operands are valid decimal numbers, ATT compares them as arbitrary-precision decimals, including ordinary Excel strings such as `"100"`; numeric equality also uses this coercion, so `1.0 == 1` is true. Otherwise ATT compares the rendered strings lexicographically and case-sensitively. A present blank value compares as an empty string; a missing Context path is `ATT-CTX-001`, not an implicit null/empty value. Use `is null` only for a path that exists with a null value, and use `#{number(...)}` when invalid numeric text should become an explicit evaluation error instead of a string comparison.

### Built-in functions

Only authored file-expression nodes are executable. Context values, Tool results and file output remain literal Strings even when they contain `&{...}`. Embedded Context paths and calls follow the enclosing Action's normal ordering, scope and resource validation rules. Nested `&{...}` inside file content, including inside `#{...}` arguments, is rejected in v1 in Run, Debug, validation and Load dependency discovery.

Built-ins are called with `#{...}`. Canonical names use framework-owned `str.*`, `date.*`, `file.*`, `misc.*`, and `seq.*` packages. Legacy flat names remain aliases for compatibility. Tool groups use the same package-like `group.tool` shape; configured Tools cannot claim a built-in package root or any canonical/legacy built-in name.

| Function | Purpose | Example |
|---|---|---|
| `seq.next` | Return a run-scoped `Long`; optional sequence name and width produce independent named counters or exact-width zero-padded text | `#{seq.next('payment', 10)}` |
| `str.upper` | Convert text to upper case | `#{str.upper(value=${EXEC.INPUT.currency})}` |
| `str.lower` | Convert text to lower case | `#{str.lower(value=${EXEC.INPUT.channel})}` |
| `str.trim` | Remove surrounding whitespace | `#{str.trim(value=${EXEC.INPUT.reference})}` |
| `str.ltrim` / `str.rtrim` | Remove leading/trailing whitespace | `#{str.ltrim(${EXEC.INPUT.reference})}` |
| `str.length` | Return text length | `#{str.length(value=${EXEC.INPUT.reference})}` |
| `str.concat` | Concatenate arguments in call order | `#{str.concat(a='PAY-', b=${EXEC.INPUT.caseId})}` |
| `str.substr` | Extract text from a zero-based start | `#{str.substr(${EXEC.INPUT.reference}, 0, 8)}` |
| `str.indexOf` | Return zero-based position or `-1` | `#{str.indexOf(${EXEC.INPUT.reference}, '-')}` |
| `str.contains` | Test literal substring membership | `#{str.contains(${EXEC.INPUT.message}, 'SUCCESS')}` |
| `str.startsWith` / `str.endsWith` | Test a literal prefix/suffix | `#{str.startsWith(${EXEC.INPUT.reference}, 'PAY')}` |
| `str.replace` | Replace every literal target | `#{str.replace(${EXEC.INPUT.reference}, '-', '')}` |
| `str.lpad` / `str.rpad` | Pad to a minimum length | `#{str.lpad(${EXEC.INPUT.sequence}, 8, '0')}` |
| `str.repeat` | Repeat a value 0–10000 times | `#{str.repeat(3, '9')}` |
| `date.sysdate` | Return system-zone date, optionally formatted | `#{date.sysdate('yyyyMMdd')}` |
| `date.systimestamp` | Return system-zone timestamp, optionally formatted | `#{date.systimestamp(format='yyyyMMdd-HHmmssXXX')}` |
| `date.format` | Format an ISO-8601 value | `#{date.format(${EXEC.INPUT.timestamp}, 'yyyyMMdd', 'Asia/Hong_Kong')}` |
| `date.add` | Add a calendar/time amount | `#{date.add(${EXEC.INPUT.businessDate}, 1, 'day')}` |
| `misc.string` | Convert a value to text | `#{misc.string(value=${EXEC.INPUT.amount})}` |
| `misc.number` | Parse and normalize a number | `#{misc.number(value='12.50')}` |
| `misc.boolean` | Convert true/false, yes/no, or 1/0 | `#{misc.boolean(yes)}` |
| `misc.coalesce` | Return first non-blank value | `#{misc.coalesce(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.nvl` | Return a default for null/empty text | `#{misc.nvl(${EXEC.INPUT.optional}, 'N/A')}` |
| `misc.iif` | Select one of two values from a boolean | `#{misc.iif(${EXEC.INPUT.enabled}, 'Y', 'N')}` |
| `misc.randomChoice` | Return one of 1–1000 input values | `#{misc.randomChoice('A', 'B', 'C')}` |

#### `Seq.next` Run-scoped sequences

`seq.next` supports these four positional overloads (the same arguments may be supplied by the names `name` and `width`; do not mix named and positional styles):

| Call | Counter | Return value |
|---|---|---|
| `#{seq.next()}` | Default sequence | Incrementing Java `Long` |
| `#{seq.next('payment')}` | Independent sequence named `payment` | Incrementing Java `Long` |
| `#{seq.next(10)}` | Default sequence | Java `String`, decimal value left-padded with zeroes to exactly 10 characters |
| `#{seq.next('payment', 10)}` | Sequence named `payment` | Java `String`, decimal value left-padded with zeroes to exactly 10 characters |

The default and each named sequence have independent counters, each starting at 1. State is owned by one ATT Run: Run shares counters across its Testcases and suites; Debug has a fresh service for its one-shot execution; Load shares counters across its workloads and concurrent iterations. Each per-name counter is thread-safe and issues unique, monotonically increasing values within that Run; concurrent scheduling does not guarantee which VU receives which value. A new Run, Debug execution, or Load run starts the counters again at 1. No `EXEC.SEQUENCES` Context node or reset/current API is exposed.

| Mode | Example use | Scope note |
|---|---|---|
| Testcase | In an `assign` Action: `expression: "#{seq.next('payment', 10)}"` | Consecutive Cases in one Run share the `payment` counter. |
| Debug | In an `assign` Action: `expression: "#{seq.next()}"` | A fresh one-shot Debug execution starts at 1. |
| Load | In an `assign` Action in the target Flow: `expression: "#{seq.next('load-order', 10)}"` | Iterations share the counter; concurrent calls are unique, but no stable VU allocation order is promised. |

`width` must be an integer from 1 through 1000. The name must be non-blank text; with one positional argument, a number means `width` and a string means sequence name. More than two arguments, mixed named/positional argument styles, invalid argument types, blank names, fractional/zero/negative/out-of-range widths are errors. Diagnostics identify `seq.next` and the invalid arity, argument, or range. If a padded value needs more digits than `width`, or the underlying `Long` counter overflows, evaluation fails explicitly; ATT never truncates a sequence or silently exceeds the requested width.

The single-value `str.upper/lower/trim/ltrim/rtrim/length` and `misc.string/number/boolean` functions accept either `value=...` or one unnamed value. Other built-ins accept either their documented names or a complete positional list; do not mix named and positional arguments in one call. Case conversion is locale-independent. `misc.number` rejects non-numeric input and removes unnecessary trailing zeroes. `misc.boolean` accepts true/false, yes/no, and 1/0. `str.concat` treats null as empty; `misc.coalesce` skips null and whitespace-only values and returns empty when none qualifies. `misc.nvl` tests null/empty without trimming. `misc.iif` accepts the same boolean text forms and resolves all three arguments eagerly. `str.repeat` requires an integer count from 0 through 10000 and repeats the complete value.

`substr(value, start[, length])` uses zero-based UTF-16 indexes. A negative start counts from the end; an out-of-range start or negative length is an error, while an overlong length stops at the end. `indexOf` is case-sensitive, accepts an optional zero-based `fromIndex`, and returns `-1` when absent. Match and replacement functions are case-sensitive and literal, not regular expressions. Padding defaults to one space, never truncates an already long value, rejects an empty pad, and limits target length to 10000.

`sysdate()` returns `yyyy-MM-dd`. `systimestamp()` returns `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`; both use the JVM system zone at invocation time. Each accepts zero arguments or one positional/named `format` argument using a locale-independent Java `DateTimeFormatter` pattern. Blank, invalid, or incompatible patterns are `ATT-BUILTIN-001` errors that identify the function, argument, supplied value, and formatter cause. `formatDate` accepts ISO local dates, local date-times, offset/zoned timestamps, and UTC instants, then applies the same pattern rules. `zoneId` accepts an IANA name such as `Asia/Hong_Kong` or an offset such as `+08:00`; it converts instant/offset/zoned values and attaches a zone to a local date-time. `dateAdd` preserves the input ISO shape and accepts singular/plural `year`, `month`, `week`, `day`, `hour`, `minute`, `second`, or `millisecond`; incompatible combinations such as hours plus a date-only value are errors.



`randomChoice` accepts either a complete positional list or consistently named values, preserves the selected value's type, and rejects zero, more than 1000, or mixed-style inputs. Selection is deliberately non-deterministic and is intended for test-data variation, not cryptography or reproducible sampling.







Typical expressions:

```yaml
assert: "${EXEC.INPUT.amount} > 0"
assert: "${EXEC.ACTIONS.callApi.output.result.status} == ${EXEC.INPUT.expectedStatus}"
assert: "${EXEC.ACTIONS.callApi.output.result.message} like 'PAYMENT%SUCCESS'"
assert: "(${EXEC.INPUT.channel} == 'MOBILE') and (${EXEC.INPUT.amount} <= 1000)"
```

### Resource helper methods

Configured `db.*`, `mq.*`, `ssh.*`, and `http.*` calls use the same `#{...}` syntax, but they are resource operations rather than pure built-ins: they access external systems and return typed results. MQ, SSH, and HTTP calls must be the primary `call` of a `type: tool` Action. DB `query` and `scalar` calls are also available to supported expression fields; `db.update` must be the primary call of a `type: tool` Action. They are not permitted in `retry.when`, which accepts only deterministic pure built-ins. All arguments below are named; omit optional arguments to use the configured helper default. See each linked helper page for its full resource and result contract.

#### DBHelper

| Method | Arguments (required unless marked optional) | Example |
|---|---|---|
| `db.<id>.query` / `db.<id>.scalar` | `sql: String`; optional `params: List` **or** `parameters: Map<String, value>` (mutually exclusive; default: no bind values). `sql` may be an inline SQL string or a file-content expression returning a String. | `#{db.orders.query(sql='select status from orders where id = :id', parameters={id: ${EXEC.INPUT.orderId}})}` |
| `db.<id>.update` | Same arguments and types as `query`; primary Tool Action only. | `#{db.orders.update(sql='update orders set status = ? where id = ?', params=['DONE', ${EXEC.INPUT.orderId}])}` |

`query` returns typed rows, `scalar` returns a scalar result, and `update` returns the update result. See [DBHelper](reference/resources/dbhelper.md) for SQL binding, transaction, and result details.

#### MQHelper

| Method | Arguments (required unless marked optional) | Example |
|---|---|---|
| `mq.<id>.send` | `payload: String, byte[] or structured Map/List` (required); `queue: String` is required unless a request queue is configured. Optional `requestFormat: text\|json\|yaml\|xml` (required for Map/List only), `instance: String` (selects a configured physical instance). | `#{mq.payment.send(queue='PAYMENT.REQUEST', payload=${EXEC.VARS.requestText})}` |
| `mq.<id>.receive` | `queue: String` is required unless a reply queue is configured. Optional `waitMs: Integer` (default 10,000 ms or helper setting), `correlationId: String`, `responseFormat: text\|json\|yaml\|xml` (default `text` or helper setting), `instance: String`. | `#{mq.payment.receive(queue='PAYMENT.REPLY', waitMs=5000, responseFormat='json')}` |
| `mq.<id>.request` | `payload` as above; effective `requestQueue` and `replyQueue` are required (each may come from the helper defaults). Optional `requestFormat` as above, `waitMs: Integer` (default 10,000 ms or helper setting), `responseFormat` (default `text` or helper setting), `instance: String`. | `#{mq.payment.request(requestQueue='PAYMENT.REQUEST', replyQueue='PAYMENT.REPLY', payload=${EXEC.VARS.requestText}, waitMs=5000, responseFormat='xml')}` |

`requestFormat` applies only to structured payloads; a String is sent as-is and must not be paired with it. `waitMs` is 0–3,600,000. See [MQHelper](reference/resources/mqhelper.md) for configured queue defaults and typed reply behavior.

#### SSHHelper

| Method | Arguments (required unless marked optional) | Example |
|---|---|---|
| `ssh.<id>.execute` | `command: String`; optional `stdoutFormat: text\|json\|yaml\|xml` (default `text`), `timeoutMs: Integer` (default 60,000 ms, capped by the Action deadline). | `#{ssh.application.execute(command='systemctl is-active example.service', stdoutFormat='text', timeoutMs=5000)}` |
| `ssh.<id>.upload` | `remotePath: String`, `payload: String or byte[]`; optional `overwrite: Boolean` (default `true`), `timeoutMs: Integer` (default 60,000 ms). | `#{ssh.application.upload(remotePath='/srv/app/request.json', payload=${EXEC.VARS.requestText}, overwrite=true)}` |
| `ssh.<id>.stat` / `ssh.<id>.mkdirs` / `ssh.<id>.delete` | `remotePath: String`; optional `timeoutMs: Integer` (default 60,000 ms); `delete` additionally accepts `missingOk: Boolean` (default `false`). | `#{ssh.application.stat(remotePath='/srv/app/result.json')}` |
| `ssh.<id>.move` | `sourcePath: String`, `targetPath: String`; optional `overwrite: Boolean` (default `false`), `timeoutMs: Integer` (default 60,000 ms). | `#{ssh.application.move(sourcePath='/srv/app/out.json', targetPath='/srv/app/archive/out.json', overwrite=false)}` |

SSH operations accept named arguments only; paths are remote paths, and upload takes content rather than a local file path. See [SSHHelper](reference/resources/sshhelper.md) for path restrictions, return values, and timeout/retry behavior.

#### HTTPHelper

`http.<id>.get(...)` and `http.<id>.post(...)` select the method by name; `http.<id>.request(...)` additionally requires `method: String` (`GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, or `OPTIONS`). All three accept these optional named arguments:

| Argument | Type and default | Notes |
|---|---|---|
| `path` | `String`, default `''` | Relative to the configured base URL; no absolute URL, query string, or fragment. |
| `query` | `Map<String, value>`, optional | Iterable values become repeated query parameters. |
| `headers` | `Map<String, value>`, optional | Merged with configured headers; values are rendered as strings. |
| `body` | `String`, `byte[]`, or structured Map/List, optional | GET and HEAD do not accept a body. Map/List requires `requestFormat`. |
| `requestFormat` | `text\|json\|yaml\|xml`, optional | Required for Map/List body only; String/byte[] content is passed through. |
| `contentType` | `String`, optional | Overrides the request Content-Type header. |
| `responseFormat` | `auto\|text\|json\|yaml\|xml`, default helper setting (`auto` by default) | `auto` resolves from response Content-Type. |
| `connectTimeoutMs`, `readTimeoutMs`, `connectionRequestTimeoutMs` | Integer 1–3,600,000 ms, optional | Override corresponding helper timeout; descriptor defaults are 5,000 ms, 30,000 ms, and 5,000 ms respectively. |
| `followRedirects` | `Boolean`, default helper setting (`false` by default) | Redirects are followed up to the runtime limit. |

Example: `#{http.payment.post(path='/v1/payments', query={dryRun: true}, headers={Accept: 'application/json'}, body=${EXEC.INPUT.request}, requestFormat='json', responseFormat='json')}`. See [HTTPHelper](reference/resources/httphelper.md) for timeout ranges, body encoding, and response parsing.

### Expression scope and errors

This page defines the language. Each field's owner defines available roots and evaluation timing: [Tool command/call](reference/resources/tools.md), [Load identity and vars](reference/execution-modes/load.md), [Debug vars](reference/execution-modes/debug.md), and [report filenames](reference/configuration.md). `${path?}` permits an absent allowed map/list path to return null; malformed syntax and illegal scope access still fail. Expression syntax and missing required Context paths produce structured diagnostics; see [Validation](reference/validation-diagnostics.md).

Removed APIs: `dbText`/`misc.dbText`, `prettyPrint`/`misc.prettyPrint`/`format.pretty`, all local `file.*` built-ins and their legacy aliases. Keep DB results typed and migrate display calls to Log `value: ${EXEC.ACTIONS.queryOrders.output.result}` with `format: sqlplus`; use `format: json` or `yaml` for Maps/Lists. Read package content with `&{...}` and pass its String to HTTP body, MQ payload, or SSH upload payload. SSHHelper upload accepts content only; native SSH download was removed. ATT local output remains framework-owned. Removed calls fail with migration guidance.

### Retry-condition lifecycle

`retry.when` runs after the current attempt completes, only after retryOn matches and while another attempt is available. `output.*` binds current result/evidence/diagnostic and `output.attempt`. Normal Boolean typing and strict/optional Context paths apply. Only deterministic pure built-ins are permitted; external, file, sequence, random and current-time operations are rejected. See [Action retry](reference/actions.md).

## Execution mode overview

RUN executes selected Cases. Debug adds diagnostics to one Flow or Tool execution. LOAD repeats Flow or Tool workloads under a scheduler. VALIDATE checks the selected inputs without starting business execution.

| Mode | Input | Output location | Starts business work |
|---|---|---|---|
| RUN | Definition and selected Resolved Cases | `output/<runId>/` | Yes, after validation |
| Debug | Flow or Tool target and explicit inputs | `output/debug/<debugId>/` | Yes, for the selected target |
| LOAD | Validated Flow or Tool workloads | `output/load/<runId>/` | Yes, when the scheduler starts |
| VALIDATE | Package or selected dependency closure | Validation report | No |

Modes share the same Expression, Action, and Resource contracts. Selection and scheduling affect which work starts; they do not define alternate input-binding semantics. For an earlier authoring model, use the [Legacy documentation](legacy/README.md).

### Unified RUN and schedules

Native RUN executes selected Resolved Cases from a Test Case Definition. Each Case resolves to a Flow and Data Source; ATT validates the selected dependency closure before execution. New work should start with the [Quick Start](quick-start.md). Existing Packages authored with an earlier model are covered by the [Legacy documentation](legacy/README.md).

#### Validate and execute a RUN

Use the authenticated Server origin and context path used by ATT Studio. Send a Native Case draft to `POST /api/v1/drafts/run`:

<!-- att-doc-example: current id=unified-run-draft schema=att-execution/v2 format=json -->
~~~json
{
  "schemaVersion": "att-execution/v2",
  "executionType": "run",
  "packageId": "payments",
  "environment": "SIT",
  "target": {
    "type": "nativeCase",
    "definitionId": "payments.authorize",
    "selection": {"resolvedCaseIds": ["accepted"]}
  },
  "options": {"debug": false}
}
~~~

Replace the package, environment, Definition, and local Case ID with registered values. Review the safe preview, then submit `{"packageId":"payments","draftId":"R..."}` to `POST /api/v1/jobs/run`. A draft is bound to the validating identity and Package revision and expires after ten minutes. Package changes require validation again. Preview and validation do not submit a Job.

A Native Suite draft uses `target.type: nativeSuite`, a non-empty `definitionIds` list, and one selection: `all:true`, `qualifiedCaseIds`, or tag filters. Included tags use any-match semantics; excluded tags take precedence. Validation resolves and freezes at most 10,000 Case IDs. Empty or unknown selections fail rather than expanding to all Cases.

Unknown fields and invalid combinations fail validation. Invalid input returns HTTP 400, unavailable drafts 404, changed revisions 409, and exhausted Job or draft capacity 429. Inspection size, time, and concurrency limits also apply.

Each selected Resolved Case produces one Case execution with its own execution ID, status, and evidence. A Server draft freezes selected Case IDs and the Package source revision; a source change requires validation again. The accepted Job uses the standard status, cancellation, event, result, and artifact routes.

#### Rerun failed Cases

`rerunFailed:true` on a Native Suite selects only failed, executed Cases from the latest eligible completed Native Suite RUN for the same owner, Package, Definition IDs, and Package revision. The Server orders candidates by completion time and does not cap discovery at the latest 100 Jobs.

Local execution uses a Package/Definition-scoped Native Suite pointer with a verified manifest hash. Dry runs and unrelated RUNs do not replace it. A Server draft freezes the source Job/run, manifest digest, revision, source selection count, retry attempt number, and exact failed Qualified Case IDs. Submission revalidates that source and rejects a stale draft if a newer matching RUN completed after validation. Skipped source Cases are excluded. The new RUN reports its own selected, executed, and skipped counts; retry counts remain separate from source counts. An unavailable or inconsistent manifest, changed source, or empty failed selection is a validation error.

#### Enable Debug diagnostics

Set `options.debug:true` to retain additional diagnostic execution events. This does not repeat execution, increase concurrency, grant a role, or enable unsafe failure details. Server size limits, disclosure policy, secret filtering, and artifact controls still apply.

#### Schedule a validated definition

Canonical schedule creation uses `att-schedule/v2`. Create a RUN schedule with a validated draft:

<!-- att-doc-example: current id=run-schedule schema=att-schedule/v2 format=json -->
~~~json
{
  "schemaVersion": "att-schedule/v2",
  "name": "Nightly payment checks",
  "executionType": "run",
  "packageId": "payments",
  "environment": "SIT",
  "draftId": "R...",
  "timezone": "Asia/Hong_Kong",
  "cron": "0 2 * * *",
  "maxConcurrentRuns": 1,
  "enabled": false
}
~~~

RUN schedules preserve the validated inputs, Case selection, and source revision. A changed Package, target, environment, or configuration requires a fresh validated definition. Protected Package defaults use path-bound keep markers. Submitted sensitive overrides cannot be persisted in a schedule.

Use either five-field `cron` with an IANA timezone or a single `runAt` instant. Do not supply both. An update that supplies either trigger replaces the stored trigger; an update that supplies neither keeps it. One-time triggers are claimed once. Nonexistent local times during spring-forward are skipped; repeated local times during fall-back can produce two instants, subject to the configured minimum interval. Preview trigger times with `POST /api/v1/schedules/preview` before enabling.

The scheduler records an occurrence before admission. Per-schedule and global overlap limits skip an occurrence at capacity; ordinary Worker and Load admission limits also apply. Late or restart misfires are skipped without backfill. Failed submissions follow `server.scheduling.failurePolicy`; execution is not retried automatically. Cancelling a Job cancels that occurrence, while pausing a schedule prevents future firings. Job and occurrence retention use the configured Server limits.

Every active local `USER` or authenticated container principal in `server.authorization.userRole` can manage shared schedules. `ADMIN` accounts have system and account administration only. The creator is retained for audit but does not restrict which active USER can view, edit, pause, trigger, or delete a schedule. Under local authentication, the creator must remain an active USER for scheduled RUN occurrences to submit. Container roles are checked for each management request; an operator must pause schedules if the creator loses the configured role because the background scheduler cannot query the external realm.

#### Replay or configure a new execution

From Jobs or Job Details, choose **Rerun with same settings** or **Configure & Rerun**. The Server creates an authorized draft and validates the current Package and Environment. The editor receives an opaque `draftRef` and fetches its sanitized definition from the Server; the definition is not put in the URL or browser storage.

Rerun preserves safely retained parameters and presents the source and current revisions before confirmation. Strict source revision selection rejects a mismatch and offers review against the current Package. Configure & Rerun opens the standard editor with supported safe settings, validates any changes, and creates an independent Job. Cancel or Back leaves the source Job and Package unchanged. Secrets, transport data, host roots, and unsafe paths are never reconstructed from logs or artifacts.

Draft references and confirmation intents are principal-bound and expire after ten minutes. They are held in Server memory, so a Server restart requires creating a new draft from the source Job. A stale Package, expired draft, removed Resource, invalid schema, or capacity rejection has a distinct response.

The [Run mode reference](reference/execution-modes/run.md) covers local Native execution and output paths. The [CLI Reference](reference/cli.md) owns command syntax and exit codes.

### Run mode

A Native RUN executes selected Resolved Cases from a Test Case Definition. A Case resolves to a Flow and Data Source; RUN validates the selected dependency closure before execution. New work should start with the [Quick Start](quick-start.md). For an existing Package authored with an earlier model, see the [Legacy documentation](legacy/README.md).

#### Select Cases

Select one Case by qualified ID or use an implicit Suite to select Cases by Definition, ID, or tag. There is no required `suite.yaml`. Excluded tags take precedence over included tags. RUN rejects an unknown or empty selection rather than executing a different set.

The [Unified RUN reference](reference/execution-modes/unified-run.md) describes the Server draft and schedule lifecycle. The [CLI Reference](reference/cli.md) owns command syntax and exit codes.

#### Validate and execute

Before a Case starts, ATT validates the Definition, resolved Data Source, Flow, and referenced Resource contracts. A Server draft freezes the selected Case IDs and Package source revision; a source change requires validation again. Preview and validation do not submit a Job.

Each selected Resolved Case produces one Case execution with its own execution ID, status, and evidence. RUN writes results under a unique run directory and updates `latest-run.yaml` only after completion. A pre-existing Run ID is rejected rather than overwritten.

#### Results and failure status

Status aggregation preserves severity: ERROR > INVALID > FAIL > PASS > SKIPPED. Process exit code is `0` when the run completes without failing status, `1` for a Case/assertion failure, `2` for invalid command/configuration/validation, and `3` for runtime or infrastructure error.

In Studio, **Configure & Rerun** creates a new validated draft from a retained RUN definition. Review the Package revision and selection before submitting. See [Job replay and new execution](reference/execution-modes/unified-run.md#replay-or-configure-a-new-execution).

### Standalone Debug

Debug runs one Flow or Tool with explicit inputs and writes results to its own directory. It does not require a Test Case Definition. For a Package authored with an earlier model, see the [Legacy documentation](legacy/README.md).

```sh
./att.sh debug flow common.payment.v1 --input common.payment.debug.yaml
./att.sh debug tool sample.getAcDate --input sample-tool.debug.yaml --env UAT
```

#### Bootstrap inputs

Use `att-debug/v1.2`. Flow Debug accepts `inputs`, optional `vars`, and optional Testdata imports. Tool Debug accepts the Tool's declared `arguments`. These values are validated before execution; framework-owned identity and output fields cannot be overridden.

```yaml
schemaVersion: att-debug/v1.2
inputs:
  amount: 100
vars:
  reference: REF-001
```

A Flow that reads `EXEC.INPUT` needs `inputs`; `vars` seeds a reusable Flow's initial `EXEC.VARS`. Testdata references use the same mapping rules as RUN and LOAD. See [Testdata registry and input mapping](reference/test-authoring.md).

#### Discovery and results

Run `./att.sh debug` without a target to list statically valid Flows and Tools. Discovery validates the selected dependency closure and does not execute resources. Use `--format json` for machine-readable output.

Each invocation writes `case.log`, `result.yaml`, and retained Action artifacts under `output/debug/<debugId>/`. Debug does not update the ordinary `latest-run.yaml` pointer. Exit codes are `0` for PASS, `1` for a failing Action, `2` for invalid CLI/input/configuration, and `3` for a runtime error.

For resource calls, use the same package-relative file-content expression in Debug, RUN, and LOAD. Validation resolves referenced files before external I/O. Review the selected Environment and safe inputs before running a Tool that can contact an external system.

### Load mode

ATT accepts `att-load/v1.9` scenarios. Each workload uses one Flow or Tool target, or a closed-user weighted mix of those targets. For an existing Package authored with an earlier model, see the [Legacy documentation](legacy/README.md). Workloads own their inputs, testdata policy, bootstrap vars and pacing policy. Root defaults may be shared by all workloads, while workload-local fields override them. ATT validates and pre-resolves every configured target before a scheduler starts.

Run `./att.sh load` with no scenario to discover valid full Load descriptors under `load/`. Only YAML declaring `schemaVersion: att-load/*` is considered; unrelated YAML is ignored, while invalid declared descriptors are shown with their diagnostics. Discovery resolves and validates targets without starting a scheduler or making resource calls.

#### Prepare referenced resources before LOAD starts

After target validation and Load plan preparation, ATT discovers only DB, MQ and HTTP helpers reachable from the selected Flow and primary Tool calls, nested Tool calls, and frozen Render payload expressions. It prepares these resources before the scheduler start gate opens. Unused helpers in global configuration are not opened. A reference that cannot be resolved statically is reported as `SKIPPED_DYNAMIC` and remains lazy on first use.

For each referenced DB helper, startup creates distinct physical connections sufficient for `max(1, minIdle)` simultaneous ready sessions. The configured `minIdle` and `maxSize` remain unchanged; normal pool growth stays lazy. MQ readiness creates idle connections for the configured physical instances but does not open queues, send messages or issue business requests. HTTP readiness builds the client and TLS configuration only; it does not connect or send a request.

Preparation is bounded to 64 initial DB/MQ sessions per run and 256 per Worker JVM, with a deadline and cancellation handling. ATT Server admits at most four concurrently executing LOAD Workers per Server process, so Server-submitted LOAD jobs share an aggregate initial-session ceiling of 256. Cleanup keeps each reservation charged until its resource owners finish closing. This scheduler cap covers only LOAD jobs submitted to that Server process; RUN/Debug jobs, CLI or direct Engine processes, and other Server instances are outside its admission budget and can add sessions to a shared backend. Failure stops the run before any iteration is admitted. Live output and structured events report each helper's readiness; the run summary records preparation results. This barrier is LOAD-only and does not replace configured warm-up traffic.

#### Independent evidence quotas

`att-load/v1.9` supports shared run-level quotas and optional independent per-workload quotas. Capped evidence follows fixed run-time windows, so the retained set reflects both the configured cap and each window.

The optional `evidence.quotaScope: perWorkload` selects the independent model. It is explicit so an existing scenario is never silently reinterpreted:

~~~yaml
evidence:
  quotaScope: perWorkload
  success: sample
  failure: full
  sampleRate: 0.1
  defaults:
    maxSuccessSamples: 10
    maxFailureSamples: 20
workloads:
  - id: payment-api
    target: {type: flow, id: common.payment.v1}
    load: {users: 10, duration: 2m}
  - id: mq-processing
    target: {type: tool, id: sample.getAcDate}
    load: {users: 5, duration: 2m}
    evidence:
      maxSuccessSamples: 5
      maxFailureSamples: 30
~~~

In this mode root `success`, `failure`, `sampleRate` and `resources` remain the shared capture policy. `evidence.defaults` supplies each workload's default `maxSuccessSamples` and `maxFailureSamples`; an omitted default is **1,000**. A `workloads[].evidence` object may override either cap, and it accepts only those two cap keys. A partial override inherits the other default. Caps are integers from 0 to 1,000,000; 0 reserves no evidence for that workload and category. In the example, `payment-api` receives 10 success / 20 failure and `mq-processing` receives 5 success / 30 failure.

Per-workload mode must not be mixed with root `maxSamples`, `maxSuccessSamples` or `maxFailureSamples`, and `evidence.defaults` or any workload cap override requires `quotaScope: perWorkload`. Before execution, ATT validates the sum of all effective success caps and the sum of all effective failure caps; either sum may not exceed the aggregate safety budget of 1,000,000. This is a preflight allocation bound, not a runtime global allocator.

Each workload owns its caps. Reaching one workload's success or failure cap never reserves capacity from another workload, and unused capacity stays unused. There is no lending, borrowing, repayment, reclaiming, revocation or quota-driven artifact deletion. A workload's first eligible success or failure is accepted whenever its own cap has room, even if every other workload is already full. Retained evidence is append-only for the run. Reservations are released on cancellation, rejection, PASS-to-FAIL reclassification, dropped/unstarted arrivals or missing evidence. Success and failure capacity remain independent, and exhaustion of one category never suppresses the other.

`sampleRate` applies only to sampled PASS iterations; FAIL/ERROR (including operational TIMEOUT) use the failure cap without sampling. `failure: full` is bounded by the effective failure cap and does not guarantee that every failure is retained. Failure log capture keeps the existing 65,536-character tail, mandatory secret protection and deferred resource formatting.

Sampled-success eligibility uses MurmurHash3 x86 32-bit, seed 0, over the iteration ID encoded as UTF-8. ATT interprets the 32-bit result as unsigned and selects it when it is below `floor(sampleRate × 2^32)`; the resolved policy reports `murmur3-32-v1`. Hash qualification is not retention: an eligible success still needs an available quota slot and an evidence artifact.

Capped evidence slots are allocated across up to ten equal windows of the planned run duration, separately for each outcome cap and workload cap. The windows use scheduled arrival time; a cap below ten uses one window per slot. Slots are append-only: there is no replacement, revocation, borrowing from empty windows, or deletion of already retained artifacts. Candidates are first-come within each window, so a small cap, an empty window, or an uneven candidate rate can still limit representativeness. Uncapped retention and direct store callers without a scheduler timeline keep their existing behavior.

`load-summary.json` / YAML, the HTML report and Studio job results expose the resolved sampling hash and retention strategy, completed successes, hash-qualified successes, sampling-ineligible successes, retained evidence, success/failure quota denials, outcome-unclassified reservations, and missing artifacts. For sampled runs, completed successes equal hash-qualified plus sampling-ineligible successes. Quota denials are counted once for the final outcome when a sampled success later fails; missing artifacts count only an already reserved completed outcome. These fields describe different stages and are not an additive retention funnel. Workload rows also include target IDs and effective per-workload caps. Reports show retained coverage by time window and phase, and warn when a combined or category quota denies candidates; aggregate metrics, thresholds and failure events continue. Retained links remain under `samples/` and `failures/` with EXEC.ID, case.log and case.yaml. Studio separates shared caps from the explicit per-workload scope, shows effective defaults and workload overrides, and keeps advanced JSON synchronized. See [the per-workload example](../examples/load/independent-evidence.yaml).


#### Define a Load scenario

~~~yaml
schemaVersion: att-load/v1.9
testdata: [examples/testdata/generated-account.yaml]
workloads:
  - id: payment
    target: {type: flow, id: common.payment.v1}
    inputs: {region: HK, account: "@{generatedAccounts}", accountId: "@{generatedAccounts.id}"}
    testdata:
      generatedAccounts:
        scope: iteration
        selection: {strategy: sequential, exhaustion: recycle}
    vars:
      baseAmount: "${EXEC.INPUT.amount}"
      total: "#{${EXEC.INPUT.amount} * 2}"
      reference: "REF-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
    load:
      users: 20
      rampUp: 5s
      warmup: 10s
      duration: 1m
      rampDown: 5s
    execution:
      thinkTime: 500ms
    thresholds:
      p95: "< 800ms"
      errorRate: "< 1%"
thresholds:
  minThroughput: ">= 10/s"
evidence:
  mode: failures
  resources:
    output: none
~~~

#### Distribute closed-user work across targets

A workload can declare `mix` instead of `target` to distribute each closed user's next iteration across pre-resolved targets. Every entry has a unique `id`, a positive integer `weight`, and a Flow or Tool `target`. The selector uses the run seed, workload ID, stable VU ID and that VU's iteration number; changing targets does not reset the VU or its think-time stream. Weights express selection probability, not a promise that a short run will match the exact ratio.

~~~yaml
schemaVersion: att-load/v1.9
seed: 73
workloads:
  - id: checkout
    inputs: {region: HK}
    mix:
      - id: browse
        weight: 60
        target: {type: flow, id: common.payment.v1}
        inputs: {operation: browse}
      - id: purchase
        weight: 30
        target: {type: flow, id: PURCHASE}
        inputs: {operation: purchase}
      - id: report
        weight: 10
        target: {type: tool, id: REPORT, arguments: {format: csv}}
    load: {users: 20, duration: 1m}
~~~

Workload `inputs` and `vars` provide defaults; an entry's same-named top-level keys replace those defaults. Tool arguments stay under that entry's `target.arguments`, and Tool entries cannot declare `vars`. A mix is closed-model only. Each completed iteration waits its normal think time before the next target is selected. `EXEC.LOAD.MIX_ID`, `TARGET_TYPE` and `TARGET_ID` are available before execution ID expressions, testdata and bootstrap vars are evaluated. Testdata selection state remains scoped to the workload/VU when targets change.

The run summary and HTML report include configured weights, observed selection counts, and bounded per-entry metrics. Overall and workload percentiles are calculated from their own aggregate latency collectors; ATT does not average per-entry percentiles to produce them. Events and retained evidence include the selected `mixId` and target identity.

A target accepts flow or tool; Tool targets may provide named arguments but cannot declare bootstrap vars. Workload inputs become EXEC.INPUT for each iteration; Flow workload vars become a fresh initial EXEC.VARS tree for every started iteration. Workloads must share one model (closed users or arrivalRate) and one warmup/rampUp/duration/rampDown envelope. They are independently paced fixed targets, not a transaction mix.

| Workload field | Runtime destination | Contract |
|---|---|---|
| `inputs` | `EXEC.INPUT` | Business input values; not bootstrap variables |
| `vars` | initial `EXEC.VARS` | Typed expression tree for Flow; independently evaluated per execution |
| `target.arguments` | Tool arguments | Tool-only call arguments; separate from `EXEC.INPUT` and `EXEC.VARS` |

ATT snapshots each iteration's input map once as a deeply immutable tree. Request metadata copies reuse that snapshot, and the Load adapter passes its nested values through into the per-iteration `EXEC.INPUT` map without copying them again. A later change to the caller's source map cannot affect a started iteration, and separate iterations do not share their input snapshots.

#### Map inputs and choose testdata

The environment profile contributes the shared `testdata` descriptor list. A scenario's optional top-level `testdata` list imports package-relative YAML files as a Load-only overlay. A matching local ID replaces the whole environment descriptor for that scenario; records and selection settings are not merged. Duplicate IDs within either layer fail validation.

Use `inputs` to map `@{id}`, `@{id.path}`, or scalar interpolation into `EXEC.INPUT`. Each ID is selected once for a mapping, and the configured `scope` controls how long that choice is reused: `workload`, `user`, or `iteration`. If omitted, Load uses `iteration`. `user` requires a closed-VU workload and is invalid for `arrivalRate`. The workload `testdata` map is policy only; it does not import descriptors. Its optional `selection` object replaces the descriptor's entire selection policy. Policies support `sequential`, `roundRobin`, or seeded `random`, with exhaustion behavior `error` (default), `recycle`, or `stop`. `stop` ends that workload cleanly after its records are consumed. A one-record descriptor needs no selection policy.

Selection evidence records only the testdata ID, source layer, record index, generated sequence where applicable, scope, strategy, and random seed. It never includes record contents. Run and Debug load only IDs used in mappings; Load validates referenced IDs and explicit workload policies before starting.

#### Initialize Flow variables

After the scheduler identity and unique EXEC.ID/EXEC.OUTPUT_DIR are ready, ATT evaluates each workload's `vars` tree before starting its Flow. Exact `${...}` references preserve native types, mixed text becomes a string, `#{...}` uses the ordinary typed expression parser, and nested maps/lists are evaluated recursively. References between vars are declaration-order independent; missing vars and dependency cycles fail before the target starts. Each iteration owns its evaluated maps/lists, so concurrent users and workloads cannot share mutations. The first normal `assign` may replace a bootstrapped variable; a successful `set` updates that value and consumes the first-assign allowance. Each iteration's sets remain isolated from every other iteration.

Bootstrap expressions may use initialized `EXEC.RUN_ID`, `EXEC.ID`, `EXEC.OUTPUT_DIR`, `EXEC.INPUT`, `EXEC.LOAD`, other `EXEC.VARS.<name>` values, and stable project/source/target metadata. `EXEC.ACTIONS`, action-local `output`, invocation-scoped metadata, and Tool/DB/MQ/HTTP/SSH/process/filesystem or stateful calls are unavailable. Only safe pure built-ins are permitted. Tool arguments remain separate from `vars`.

#### Choose a closed-user or arrival-rate workload

Closed workloads use positive load.users. Each stable virtual user repeatedly executes its target and observes execution.thinkTime before starting the next iteration. thinkTime may be a duration or a {min, max} range.

Arrival-rate workloads use load.arrivalRate, positive load.maxConcurrent and overloadPolicy: drop. They schedule against absolute due times. Arrivals beyond maxConcurrent are recorded as generator drops; they are not queued or counted as SUT errors. Arrival-rate workloads have no persistent USER_ID and cannot configure thinkTime.

Closed workloads preserve the configured number of virtual users while iterations run synchronously. For scheduler and worker-pool implementation details, see [Load scheduler design](system-design/load-scheduler.md).

#### Set pacing and size Resource pools

For a steady arrival rate, estimate average in-flight requests with Little's law:

```text
average concurrency ≈ arrival rate (requests/second) × mean response time (seconds)
```

For example, 20 HTTP requests/second at a mean 1.5-second response time needs about 30 concurrent connections to avoid the client pool becoming the limiting factor. HTTP defaults to `pool.maxConnections: 50` and `pool.maxConnectionsPerRoute: 20`; raise both as needed for the workload, keeping the per-route value no greater than the total. Add headroom for latency variation and other routes, then confirm with `resources.http` active/idle/waiting/peak observations. Pool capacity is a generator-side ceiling, not a recommendation to send that load to an unverified service.

For DB work, use the connection-lease time in the same estimate: DB operations/second × mean time a borrowed connection stays in use. For example, 40 operations/second with a 100 ms mean lease needs about four concurrent connections. Set `pool.maxSize` for each DB helper to that concurrency plus headroom, then check its `resources.db` active/idle/waiting, borrow-timeout and total-borrow-wait metrics. The default maximum is 20 per helper; it is a client-side limit, not a target database capacity recommendation.

For MQ request/reply, 10 requests/second with a mean 3-second reply time likewise needs about 30 leased connections across the workload. Size `pool.maxSize` for the expected in-flight requests **per physical MQ instance**, not per logical helper. With a single instance (or calls pinned to one instance), that is about 30 plus headroom. With two evenly selected instances, it is about 15 per instance on average; account for selection skew and verify the actual distribution. `resources.mq` reports PRE_TEARDOWN pool metrics per physical instance (`logicalId::instanceId`), so inspect each pool's waiting and timeout metrics alongside response latency. Disconnect confirmation is the separate `resources.postTeardown` sample; see [Idle connections and teardown](reference/resources/mqhelper.md#idle-connections-and-teardown). `minIdle` is applied when that physical pool is first created on use; it does not create or warm the pool before Load starts. To move connection creation out of measured steady-state latency, combine `minIdle` with a Load warm-up/first-use warm-up phase. Recalculate from observed mean latency as load changes; tail latency is useful for headroom, but is not the mean used by the estimate.

duration is required. rampUp, warmup and rampDown default to zero. Generation runs in Ramp-up → Warm-up → Steady → Ramp-down order, then DRAIN lets admitted work finish without new arrivals: ramp-up increases from zero to the configured target, warm-up sustains that full target, and ramp-down decreases it after steady duration. Ramp-up and warm-up send real traffic but both are excluded from measured metrics and threshold denominators; measured load is Steady + Ramp-down. For example, a 100/s target with 60s ramp-up, 60s warm-up, 180s steady duration and 60s ramp-down schedules 3,000 + 6,000 + 18,000 + 3,000 = 30,000 arrivals. Existing saved descriptors keep their values and history; rerunning one uses the new order and does not reproduce the old phase timing. Optional seed makes closed-VU think-time randomization deterministic.

#### Identify iterations and find their artifacts

Every started iteration receives an engine-managed `EXEC.ID` before bootstrap vars and target execution; `EXEC.RUN_ID` remains the enclosing Run. See the [Legacy documentation](legacy/README.md) for earlier ID initialization notes.

Generated IDs must be non-empty, path-safe segments. Duplicate IDs fail before the target starts; ATT does not silently append a suffix.

~~~text
output/load/<RUN_ID>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── executions/<EXEC.ID>/
│   ├── case.log
│   └── action outputs written under EXEC.OUTPUT_DIR
├── failures/<EXEC.ID>/case.log
├── failures/<EXEC.ID>/case.yaml
├── samples/<EXEC.ID>/case.log
└── samples/<EXEC.ID>/case.yaml
~~~

A metrics-only iteration still has `EXEC.ID` but does not create a per-iteration execution directory unless an operation writes an artifact or retention materializes evidence. `EXEC.OUTPUT_DIR` remains the logical planned path at `executions/<EXEC.ID>` while the iteration runs. Retained failures and sampled successes receive an evidence copy under `failures/<EXEC.ID>/` or `samples/<EXEC.ID>/`. The report and evidence summary show `EXEC.ID` and link to `case.log` when it exists. Optional Resource output formatting is deferred until an iteration is retained; explicit Tool evidence collectors still run because they are part of the requested scenario.

#### Choose which iteration evidence to retain

`evidence.mode` accepts `metrics`, `failures`, `samples` or `all`; the default is `failures`. These modes set the default effective success/failure policies to `none/none`, `none/full`, `sample/full` and `full/full`, respectively. Explicit `evidence.success` and `evidence.failure` values override those defaults independently. `sampleRate` determines hash eligibility; quota slots separately determine retention, and dropped arrivals do not create iteration evidence. Capped selections use up to ten append-only time windows, with first-come selection inside each window; see [Independent evidence quotas](#independent-evidence-quotas) for the sampling and representativeness limits.

Case-log capture follows the effective success/failure policies and remaining retention capacity. With failure policy `full` and a `maxSamples` slot available, eligible failures—including unselected successes under `samples`—retain a redacted rolling log tail of at most 65,536 characters. ATT materializes the failure log only when the failure claims a retention slot. If the tail is truncated, a marker identifies the omitted earlier events, and the latest action and runtime failure details remain at the end. Failure logs are not retained after capacity is exhausted. Sampled successes and full-success policies retain full deferred logs. For example, `mode: metrics, failure: full` enables bounded failure capture while capacity remains, while `mode: failures, failure: none` disables it. See [Load scheduler design](system-design/load-scheduler.md) for implementation and storage details.

evidence.resources.output accepts inherit (default) or none. none disables optional human-readable resource-output formatting and materialization while preserving typed results, stdoutFormat/responseFormat parsing, exact file-content String output and requestFormat behavior. In Load, resource output is deferred until the iteration is retained. Metrics-only iterations do no business-output formatting or evidence file I/O.

#### Read Load results and apply thresholds

ATT writes bounded load-summary.json/yaml and a self-contained report/index.html below the run root. The report shows EXEC.ID for retained executions, workload/target identity, status, timing and case.log links when available. Aggregate latency percentiles use the aggregate latency collector; ATT does not average workload percentiles.

Direct Engine/CLI `OperationResult` output keeps its legacy full-summary detail shape; its schema definition remains under `schemas/history/`. The archived report emitted by `LoadReportWriter` uses additive `att-load-summary/v1.3` and moves large aggregate/per-workload metric collections into relative `details/v1/` detail files referenced by `details/v1/manifest.json`. Inline collections have at most 32 preview items, each detail file chunk is at most 4 MiB, the detail file set is capped at 100 MiB/9,000 files, and complete report output is capped at 120 MiB; each main report file remains at or below 16 MiB. Aggregate counters and thresholds remain exact. Server-mediated Job Results use `att-load-terminal-result/v1` in `result.summary`: execution outcome, performance verdict, aggregate counters, bounded workload/threshold previews, primary diagnostic and detail/archive state. The compact summary is capped at 32 KiB; measured terminal journal and SSE envelopes stay below 48 KiB, below the unchanged 64 KiB journal event-data limit. The 1 MiB Worker frame limit is also unchanged. Existing LOAD scheduling, virtual-user and arrival-rate semantics do not change.

Full buckets, failure samples, workload details, threshold details and evidence remain in `load-summary.json`, `load-summary.yaml`, `report/index.html` and the versioned detail files. The Server exposes those through the existing authorized artifact archive routes. Studio shows the compact result first, loads evidence/workload previews when Results is opened, and loads metric-series detail files when Metrics is opened. No artifact request is made on the initial Job Details load. Archive availability is independent from execution outcome; `PENDING`, `ARCHIVING`, `READY`, `FAILED` or omitted details do not change terminal status or exit code. ERROR and INVALID keep their real runtime/preparation/validation cause ahead of Worker/Journal truncation and archive diagnostics.

Each metrics snapshot includes `metrics.accounting`. It reconciles scheduled arrivals with started and dropped arrivals, started iterations with completed and unresolved iterations, and completed outcomes with PASS, SUT failure, runtime error, and explicit other outcome counts such as `SKIPPED`. The accounting state is `IN_PROGRESS`, `VERIFIED`, `PARTIAL` or `FAILED`; final partial or failed accounting makes execution `ERROR` and adds a bounded diagnostic. Multi-workload summaries also verify that aggregate counters equal the sum of the workload counters. Phase counters are checked at the same snapshot boundary.

When checkpointing is enabled, ATT writes versioned aggregate and workload metrics snapshots every five minutes and at finalization under `output/load/<RUN_ID>/checkpoints/`. Each scope retains at most two generations. The snapshot includes its run identity, scope, sequence, timestamp and integrity digest; a final checkpoint is idempotent. Checkpoints are recovery evidence, not a complete report or a successful performance verdict. `load-report-manifest.json` is committed after the summary, details and offline HTML report, and records their lengths and SHA-256 digests.

For long scenarios, the Engine performs a read-only preflight and records `resources.preflight` in the summary. It reports a planning estimate for retained event files and bytes, configured evidence quotas, retained Tool output, the artifact archive limit, and low or unavailable output disk space. The estimate uses 4 KiB per retained event and excludes Tool output; actual sizes vary. These warnings do not reject a run solely because of its duration. Browser inline drafts and package-backed scenarios follow the Server's configured effective-envelope cap (`server.inlineLoad.maxDurationSeconds`, up to 10 days). The opt-in manual CLI Local benchmark is documented in the [25-hour LOAD soak acceptance guide](operations/load-soak-acceptance.md).

Load v1.9 adds event-time windows named `all`, `measured` and `steady`. `measured` starts after ramp-up and warm-up and includes steady and ramp-down; `steady` covers only the configured steady duration. The HTML report opens on `steady` and lets readers select another window. Scheduled, started and completed rates use the timestamp of each event and the configured window duration. `startedRate` therefore remains event-time. `admissionRate` instead uses the same scheduled cohort: arrivals scheduled in the window that start by run finish divided by all arrivals scheduled in that window; arrivals still unstarted at finish count as not admitted. Latency percentiles and outcome counts use the scheduled cohort, so a drained iteration remains attributed to the phase where it was scheduled. Existing top-level metric names remain available; measured aggregates include scheduled STEADY and RAMP_DOWN work only.

Nested `thresholds.steady` and `thresholds.overall` evaluate against the steady and all-execution windows. Top-level measured thresholds use the corrected measured interval, `STEADY + RAMP_DOWN`; ramp-up and warm-up are excluded. An incomplete or unevaluable window returns `NOT_EVALUATED`; it is not a performance pass. Reports show execution status separately from the performance verdict and disclose the percentile sample and observation counts so sparse estimates are visible. Direct Engine/CLI summaries use existing Engine/CLI summary contract; archived reports use the additive `att-load-summary/v1.3` schema with optional detail-manifest and workload-detail file references.

##### SUT threshold acceptance

A LOAD scenario may define `observability.thresholds` against registered SUT Metrics profiles. Rules currently support a sampled `max <= value` over the actual UTC `STEADY` interval and all bound targets. Every source target is evaluated independently. A value equal to the limit is `MET`; any valid exceedance is `NOT_MET`, even if coverage is otherwise incomplete. Missing, stale, partial, or under-covered samples produce `NOT_EVALUATED` unless a valid exceedance already proves `NOT_MET`. The default minimum coverage is 95 percent.

The Server stores immutable normalized rules in the Job's SUT snapshot and writes post-run outcomes to the separate versioned `att-sut-threshold-evaluation/v1` record. `GET /api/v1/jobs/{id}/result` may include an authorized `performanceAcceptance` object with `executionStatus`, `nativeLoadThresholds`, `sutThresholds`, `externalEvaluationStatus`, and the combined `performanceVerdict`. Per-target evidence includes the actual UTC STEADY window, sampled peak and time, resolution, coverage, and pinned source/profile/target revisions. The Worker terminal result and native thresholds remain unchanged. A pending external evaluation keeps the combined verdict pending; a known native or SUT threshold failure remains `NOT_MET`.

Scenario authoring, the complete rule shape, coverage requirements, and Studio evidence are documented in [Studio Load testing](interfaces/studio/load-testing.md#sut-metrics).

##### Single-call business metrics

A base profile can define a business metric with a configured `call` instead of PromQL. ATT runs each expanded call group once after LOAD drains, through the selected package's existing DBHelper or Package Tool configuration. The default scope is Job-wide. A single-workload binding requires one exact `workloadId`; row-mapped outputs use the immutable Workload and mix identities from the LOAD scenario. ATT does not fan out calls, poll, retry, or infer identity from a target ID. DBHelper calls use the existing helper configuration, bind parameters, and connection limits. A Package Tool requires `sideEffectsAcknowledged: true` because ATT cannot determine whether package code has side effects.

~~~json
{
  "schemaVersion": "att-sut-metrics-profile/v1",
  "id": "active-sessions",
  "kind": "base",
  "category": "db",
  "metrics": [
    {
      "id": "active",
      "title": "Active sessions",
      "unit": "count",
      "kind": "gauge",
      "call": "#{db.reporting.scalar(sql='select count(*) from active_sessions')}",
      "sampling": "afterDrain"
    }
  ]
}
~~~

Use `outputs` when one query or Package Tool invocation returns several numeric columns or rows. Each output declares its own metric ID, title, result column, unit, and kind. The following profile runs one DBHelper query and maps its two fixed columns to separate counter metrics:

~~~json
{
  "schemaVersion": "att-sut-metrics-profile/v1",
  "id": "transaction-summary",
  "kind": "base",
  "category": "db",
  "metrics": [{
    "id": "transaction-query",
    "call": "#{db.reporting.query(sql='select workload_id, mix_id, success, failure from transaction_summary')}",
    "sampling": "afterDrain",
    "mapping": {
      "rows": "rows",
      "workloadColumn": "workload_id",
      "mixColumn": "mix_id"
    },
    "outputs": [
      {"id": "successful_transactions", "title": "Successful transactions", "column": "success", "unit": "transactions", "kind": "counter"},
      {"id": "failed_transactions", "title": "Failed transactions", "column": "failure", "unit": "transactions", "kind": "counter"}
    ]
  }]
}
~~~

The result path defaults to `rows`. It may select a bounded object/list path such as `payload.rows`. Without `workloadColumn`, the result must contain exactly one row for a Job-wide binding. With `workloadColumn` and no `mixColumn`, each row maps to Workload scope, including for a mixed Workload. If the query returns an aggregate across mix slots, define that aggregation in the query or Tool; ATT keeps the result at Workload scope and does not copy it to individual slots. There must be at most one row per Workload. Add `mixColumn` to identify a mix slot by the pair `workloadId` and `mixId`; it requires `workloadColumn`. A row with a missing or unknown mix ID remains unassociated and is reported in mapping diagnostics, while independently mapped slots remain available. ATT does not use target IDs, dynamic pivots, fuzzy joins, or implicit aggregation to associate rows. Column lookup is exact and case-sensitive, so configured column names must match the labels returned by the query or Tool.

Each call group supports up to 64 outputs and 4,096 result rows. The Job supports at most 16 actual calls, with a 60-second per-call limit and a 120-second total call budget. ATT keeps returned rows in memory only; detail files and reports store each numeric output and mapping diagnostics, not the raw rows. An invalid output column affects that metric and subject only. A failed or timed-out call makes every output in its group unavailable. Duplicate, unknown, missing, and ambiguous rows remain visible as diagnostics, and every expected Workload/mix output remains present. Partial or unavailable values are never replaced with zero.

Set `sampling: windowQuery` and `window: steady` to make one post-drain call with the actual STEADY bounds available as `EXEC.OBSERVATION.WINDOW_START` and `EXEC.OBSERVATION.WINDOW_END`. The query author defines the aggregation; a returned value does not prove exhaustive coverage. A single-output map or list result requires an explicit `valuePath` such as `rows[0].active`, and the resolved value must be one finite number. The Server pins the Package and Runtime Config revisions at Job admission. It does not retain executable source for delayed execution; if either revision changes before collection, the detail file is `NOT_EVALUATED` and Job diagnostics report `BUSINESS_CALL_PACKAGE_CHANGED` or `BUSINESS_CALL_CONFIG_CHANGED`. ATT never silently runs the call against changed files. Calls run in a bounded Server worker pool, outside LOAD workers. After-drain values are fresh only when collected within two minutes of LOAD completion, independent of the Prometheus after buffer; later values are stale. In mixed Jobs, after-drain calls start promptly after LOAD finishes. Verified business detail files are exposed as provisional, read-only evidence while the combined manifest remains `PENDING`, with `business: COMPLETE`, `prometheus: PENDING`, and the Prometheus `eligibleAt` deadline. Threshold and combined performance verdicts remain pending until the full collection is terminal; polling backs off toward that deadline. Restart recovery resumes the unfinished Prometheus phase without repeating a completed Business phase. A restart during an in-progress Business call fails closed with `SERVER_RESTART_INTERRUPTED` and does not retry a possibly side-effecting call. A failed, timed-out, stale, or non-numeric call evaluates to `NOT_EVALUATED`; it is never substituted with zero and does not change the Worker result. Binding validation and live preview never execute a business call.

Business metrics support `max <= value` thresholds, including numeric counters. Use `window: afterDrain` for an `afterDrain` metric or `window: steady` for a `windowQuery` metric. These single-value rules do not use `minimumCoverage`. Omit `subjects` or set it to `all` to evaluate every expected subject. For an exact Workload or mix selection, provide `subjects` as a list; a mix selector must include both immutable IDs:

~~~yaml
thresholds:
  - id: mobile-success-limit
    profile: transaction-summary
    metric: successful_transactions
    max: 1000
    unit: transactions
    subjects:
      - scope: mix
        workloadId: checkout
        mixId: mobile
~~~

Every selector must resolve to exactly one pinned Job, Workload, or Workload/mix metric identity. A metric ID shared across several Workloads remains distinct in the threshold results. Missing, stale, partial, or ambiguous mappings produce `NOT_EVALUATED` unless another valid selected subject proves `NOT_MET`.

In the summary's `thresholds` object, `status` and `performanceVerdict` are authoritative and use `MET`, `NOT_MET` or `NOT_EVALUATED`. `legacyPassed` is an optional compatibility boolean that retains the previous `passed()` semantics: it is false when configured thresholds are unevaluable and true when no thresholds are configured. Consumers should use the tri-state fields to distinguish incomplete evidence from a missed threshold.

The summary separates generator observations from SUT outcomes. `metrics.generator` includes sampled heap used/committed/maximum, observed peak live threads, GC count/time, and process CPU when the JVM exposes it. Sampling is limited to one observation per 100 ms, so brief peaks may be missed. `schedulerWakeups`, `submitLag*`, and `workerQueueDepth*` describe scheduler pressure; arrival drops remain separate from SUT errors. `resources.http` reports active/idle/waiting and observed peak connections per HTTP helper alongside DB, MQ, and Render pool/plan diagnostics; `resources.resourceMetricSamples` reports the number of rate-limited resource observations. `resources.executionIds` reports custom execution-ID reservation counts. Testdata mapping and selection counts are under `resources.generator.testdata`; interpretation and implementation limits are documented in [Load Generator Telemetry](system-design/load-telemetry.md).

`latencySampleCapacity`, `latencySampleCount`, `latencyObservationCount`, and `latencySampleRate` describe the run-level percentile estimate; exact latency aggregates remain exact. The run-level reservoir keeps up to 4,096 samples. Legacy `metrics.phases.*` percentiles keep up to 256 samples per phase, while each configured window's `metrics.windowMetrics.windows.*.cohort.latency` uses a separate 4,096-sample reservoir. Each chart-bin latency estimate under `metrics.windowMetrics.timeSeries.points` keeps up to 128 samples.

The full-execution series at `metrics.windowMetrics.timeSeries.points` stores up to 2,048 populated time bins, initially 1,000 ms wide. When the populated-bin count exceeds that capacity, ATT merges adjacent bins and doubles the bucket width using power-of-two coarsening. It does not evict historical bins, so longer runs retain their history at lower time resolution. The measured and steady series at `metrics.windowMetrics.timeSeries.windowSeries.measured.points` and `metrics.windowMetrics.timeSeries.windowSeries.steady.points` have separate 2,048-bin bounds. They align bins to their configured window start, start with the smaller of 1,000 ms or the window duration, and clip a final partial bucket at the window boundary; they use the same coarsening and no-eviction policy. The compatibility `metrics.buckets` series is separate: it keeps the newest 4,096 one-second buckets in a circular buffer and evicts older entries. ATT does not average per-workload percentiles to calculate the overall percentile.

The Load telemetry fields remain optional in the direct existing Engine/CLI summary contract; archived reports use `att-load-summary/v1.3` and detail file references. Server Job Results use the separate, versioned `att-load-terminal-result/v1` contract. Consumers that need full metrics should follow `detailManifestPath` and its relative chunk references after the archive reports READY.

For sampling limits and maintainer verification, see [Load Generator Telemetry](system-design/load-telemetry.md) and [Compact LOAD terminal result](system-design/load-terminal-result.md). The deterministic short-versus-synthetic-long transfer benchmark runs with `mvn -q -o -pl att-server -am -Dtest=ServerRuntimeLoadTerminalResultTest#benchmarkShortAndSyntheticLongMultiWorkloadTransferSizes -Datt.skipFrontendUnitTests=true -Dsurefire.failIfNoSpecifiedTests=false test`; it constructs a 30–60-minute-equivalent multi-workload result and does not run a long LOAD.

Top-level thresholds apply only to the aggregate run; workload thresholds apply only to their individual workload. Root thresholds are not inherited into workload thresholds. Threshold failure returns FAIL/exit 1. Invalid config/target returns exit 2; runtime/infrastructure errors return ERROR/exit 3. Generator drops are not SUT errors.

#### Cancel a Load run

Cancellation stops new admissions and interrupts admitted iterations. ATT drains completion events for admitted work and accounts for those outcomes before finalizing workload results and metrics.

#### Understand payload changes during a Load run

Before a Load workload starts, ATT resolves each reachable Render payload glob once and freezes the matched UTF-8 source content for that run. A payload edit, replacement, or new glob match made while the run is active does not affect its iterations; the next Load run resolves the package again. Normal Run and Debug use a fresh plan for each execution, so edits are picked up by the next execution.

Each iteration evaluates Context references, built-in calls, and external calls against its own Context. Stateful calls such as `seq.next()`, clock/random functions, and external calls run for each iteration. Render returns its String in memory, so passing `ACTIONS.<id>.output.result` to a downstream action does not create an intermediate Render file. Use `EXEC.OUTPUT_DIR` only when an operation explicitly needs a file.

With `--profile`, `performance.json` records `renderPlansCompiled`, `renderPlanCacheHits`, `renderPayloadResolutions`, `renderPayloadResolutionCacheHits`, `renderEvaluations`, `renderArtifactWrites`, and `renderSourceBytes`. See [Load scheduler design](system-design/load-scheduler.md) for how Load prepares plans and reuses immutable source data across iterations.

Load startup also compiles the selected Flow action sequence, primary Tool calls and argument expressions, `runWhen`, assertions, and `retry.when`. Each iteration evaluates that immutable plan against its own Context. Testdata input mappings are likewise compiled during target validation; descriptors and effective workload policies are prepared once, while record selection and Context values remain iteration-specific. The run summary's `resources.execution` contains `executionPlansCompiled`, `actionPlansCompiled`, and `actionEvaluations`.

#### Override workload settings from the CLI

For one workload, options such as --users, --arrival-rate, --ramp-up, --warmup, --duration, --ramp-down, --think-time and --max-concurrent can override matching YAML values. Unscoped load-model overrides fail for multi-workload scenarios.

Repeatable `--set` accepts `input.path=value`, Tool-only `arg.name=value`, or Flow-only `vars.path=value`. Values use safe YAML parsing and remain typed; nested maps and numeric list indexes are supported where practical, for example `input.customer.ids[0]=42`. Duplicate assignments apply in order (last wins). ATT expressions are not evaluated during option parsing. Unqualified overrides are rejected for multi-workload scenarios.

`load/load.yaml` is an optional current `att-load/v1.9` policy-only descriptor with no target, inputs or Tool arguments. It contains the default `load` policy and may also declare `execution`, `thresholds`, `evidence`, `seed` and Load-local `testdata` imports. Explicit CLI pacing values override the policy. `load --debug flow|tool <id>` promotes the selected detail file's `inputs`, `vars` or Tool `arguments` into a transient single-workload scenario and then uses the regular Load validation, scheduler and evidence pipeline; Debug execution is not run first. With no policy, provide a complete CLI policy such as `--users 2 --duration 10s` (arrival-rate also requires `--max-concurrent` and `--overload-policy`).

Example policy descriptor (copy to `load/load.yaml`):

~~~yaml
schemaVersion: att-load/v1.9
load: {users: 2, duration: 10s}
execution: {thinkTime: 250ms}
evidence: {mode: failures}
~~~

~~~sh
./att.sh load ../../examples/load/closed-smoke.yaml
./att.sh load ../../examples/load/arrival-smoke.yaml --format json
./att.sh load ../../examples/load/multi-closed.yaml
./att.sh load ../../examples/load/multi-arrival.yaml
./att.sh debug flow common.payment.v1 --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh load --debug flow common.payment --users 2 --duration 10s --set 'vars.reference=${EXEC.INPUT.reference}'
./att.sh debug
./att.sh load
./att.sh load --debug tool fpp.invokeApi --set arg.requestId=42
~~~

Copyable examples and field descriptions are maintained in [examples/load/README.md](../examples/load/README.md). Migration details are maintained in the [Legacy migration reference](legacy/migration-reference.md).

See the [Legacy documentation](legacy/README.md) for earlier Load field migration notes.

The current `att-load/v1.9` contract rejects `execution.execIdFormat`; see the [Legacy migration reference](legacy/migration-reference.md) for earlier configuration migration details.

#### Error rates and failure diagnosis (4.2.4)

Measured completions are divided into PASS, SUT failures, and runtime ERROR/INVALID. Ramp-up and warm-up are excluded from measured rates; either can still make execution ERROR. Generator drops remain separate.

| Metric | Numerator / denominator |
| --- | --- |
| `sutErrorRate` / existing `errorRate` threshold | `measuredFailure / measuredCompleted` |
| `runtimeErrorRate` | `measuredRuntimeError / measuredCompleted` |
| `totalErrorRate` | `(measuredFailure + measuredRuntimeError) / measuredCompleted` |
| `completedThroughput` | all measured completions / measured seconds |
| `successfulThroughput` | measured PASS completions / measured seconds |

Optional `runtimeErrorRate` and `totalErrorRate` thresholds use the same percentage comparisons as `errorRate`, for example `{errorRate: '< 1%', totalErrorRate: '< 1%'}`. `errorRate` remains SUT FAIL-only and excludes runtime errors and drops. Thresholds PASS and Execution ERROR can coexist; neither thresholds nor completed TPS imply successful execution. Rates in JSON/YAML remain numeric zero when no measured completions exist, for compatibility; the HTML report shows N/A. The report summary renders `Thresholds: none configured` when no thresholds exist, or `Thresholds: N passed / M failed` when they do.

The Runtime failures / Failure diagnosis section shows total/measured runtime counters and at most 100 sanitized lightweight failure samples per aggregate, workload or mix collector. Each sample identifies iteration, sequence, phase, timestamp, workload/mix, outcome, classification, diagnostic source/code/summary and evidence state. Counts stay complete after sample limits are reached. When no samples are available, the report says `No lightweight failure samples were recorded.` Scheduler exceptions are distinguished from returned executor failures; public samples omit stack traces, classified secrets and host paths. Retained Case Logs are linked in the Evidence section. `NO_CASE_LOG`, `WORKSPACE_MISSING`, `EVIDENCE_IO_ERROR`, `EVIDENCE_MATERIALIZATION_ERROR` and `NOT_RETAINED_BY_POLICY` explain missing evidence independently of the iteration outcome. An evidence failure is not another failed iteration.

#### Load presets

Load testing opens preset resources by default. All six modes use `att-load-template/v1`: `single-vu`, `single-tps`, `single-workload-mix`, `multi-vu`, `multi-tps`, and `multi-workload-mix`. Single VU/TPS reuse Quick Load. Mixed modes use independent weighted iteration selection within each workload; VU pools are never divided by mix weights. Multiple TPS workloads request arrivals independently and report drops and achieved throughput separately. All workloads share the same phase timing envelope.

The **Scenarios** tab retains executable YAML and saved Quick Load policies. It excludes `load/templates/` and `load/bindings/`. **View LOAD Jobs** opens Jobs and results with an authorized Server-side LOAD filter and pagination; the filter survives details and browser navigation.

Built-ins use `builtin:<mode>`. Put package presets in `load/templates/` and bindings in `load/bindings/`. Files must be package-relative, schema-valid, at most 256 KiB, and within the bounded catalog scan. Presets contain structural/pacing defaults only, never target IDs or business values. A preset cannot execute until every slot is bound. For example:

Catalog inspection pages contain at most 100 items and also honor the configured serialized response budget (256 KiB by default). Follow `nextOffset` until it is null: each page retains complete definitions, so the same query and offset retrieve the corresponding definitions without a separate detail call. A definition that cannot fit even alone is listed as `response-too-large` without its definition; reduce its size for Studio, or compose it directly by file reference in the CLI. CLI `load --list-templates` follows every page automatically and retains the scan-limit `truncated` indicator.

<!-- att-doc-example: current id=multi-workload-load-template schema=att-load-template/v1 format=yaml -->
```yaml
schemaVersion: att-load-template/v1
name: Independent mixed pools
mode: multi-workload-mix
policy:
  load: {users: 10, duration: 10s}
  evidence: {mode: metrics}
workloads:
  - id: workload-a
    mix: [{id: browse, weight: 70}, {id: purchase, weight: 30}]
  - id: workload-b
    load: {users: 20}
    mix: [{id: purchase, weight: 40}, {id: report, weight: 60}]
```

<!-- att-doc-example: current id=multi-workload-load-bindings schema=att-load-bindings/v1 format=yaml -->
```yaml
schemaVersion: att-load-bindings/v1
bindings:
  workload-a:
    mix:
      browse: {type: flow, id: common.payment.v1}
      purchase: {type: flow, id: PURCHASE}
  workload-b:
    mix:
      purchase: {type: flow, id: PURCHASE}
      report: {type: tool, id: REPORT}
```

This creates two workloads, four bindings, three unique targets and 30 VU. Workload and mix slot IDs remain stable when targets repeat or rows move. Fixed modes use `bindings.<workloadId>.target`. Extra workload/mix bindings require an explicit `prototype` naming an existing slot in that scope; they copy its policy, not its target. Missing required slots, unknown prototypes, duplicate IDs and incompatible models fail before traffic. The descriptor limit is 128 workloads/entries; Server aggregate limits may be lower.

The builder summarizes bindings, users/rates and weights. Detailed workload, mix, target and common settings use Save/Cancel dialogs; Cancel or Escape discards tentative changes. Target inputs/vars/Tool arguments retain safe typed detail file defaults independently for each target. Precedence is preset policy, then safe target detail file business defaults, then target binding values, then workload binding inputs/vars; applicable explicit CLI overrides win. Business maps merge by key, preserving distinct keys and letting the later layer win conflicts. Studio preserves workload-specific timing, ramps and execution settings, including ranged think time, through validation and edits. Debug-local testdata imports are rejected: declare supported Load testdata sources explicitly. Bindings may specify workload inputs, vars and testdata selection as well as target-specific values.

Validate creates a full `att-load/v1.9` immutable draft through the shared Engine composer. Review shows redacted Server-normalized YAML. Any saved edit or environment change invalidates validation; source/revision changes are checked again at submission. Run requires traffic confirmation; Schedule uses the same validated draft and normal permissions, limits, expiry and revision policy. No browser storage or package source writes are used.

**Configure & Rerun** from a LOAD Job uses this standard editor with a server-issued `draftRef`. It prefills the safe retained scenario, environment, workload IDs/mix, model, pacing, timing phases, thresholds, evidence and eligible inputs. Edit the common timing envelope and model-specific fields, then validate against the current Package before reviewing the delta. Current-package rerun does not silently reload latest YAML defaults or convert Arrival Rate to Virtual Users. File-backed LOAD replay remains on its package-relative source route and does not inherit inline-draft-only duration caps; see [Job replay](reference/execution-modes/unified-run.md#replay-or-configure-a-new-execution).

```bash
./att.sh load --list-templates --format json
./att.sh load --debug flow common.payment.v1 --load-template builtin:single-vu --preview
./att.sh load --debug --load-template load/templates/mixed.yaml --bindings load/bindings/mixed.yaml --env SIT --preview --format json
# Remove --preview only after reviewing the composed scenario.
```

Use the same arguments with `att.bat` on Windows. List and preview are read-only and start no traffic. `--preview` requires a preset; JSON output remains machine-readable. Multi-target mode requires a complete binding descriptor and rejects unscoped `--input`, `--set`, `--users`, `--arrival-rate`, `--think-time`, `--max-concurrent` and `--overload-policy`. Common duration/warmup/ramp timing overrides apply to the shared envelope. Existing no-preset `load --debug <type> <id>` and no-argument scenario discovery retain their behavior. Validation errors exit nonzero with field context.

## Resources and integrations

Tool, DBHelper, MQHelper, HTTPHelper and SSHHelper are peer Resource types. SSHHelper routes command-backed Tools and exposes `ssh.<helperId>.<operation>` operations through the common operation-result/evidence contract.

```text
Tool       -> process/call operation --\
DBHelper   -> JDBC operation ----------+--> Action output
MQHelper   -> MQ operation ------------/
HTTPHelper -> HTTP operation ----------/
SSHHelper  -> SSH operation -----------/
```

Resource IDs are logical contracts referenced by Flows, Actions, and expressions. Environment profiles can bind the same DB/MQ/HTTP/SSH logical ID to different descriptors without changing Action YAML.

The lowercase `x-` prefix can disable fields and keyed entries in ATT-owned Resource configuration. It does not strip user data such as HTTP header names or DB parameters; see [Configuration](reference/configuration.md#ignore-or-disable-att-owned-configuration-with-x).

### Operation result and Evidence

ATT keeps an operation's logical result separate from execution evidence:

~~~text
Operation
├── result       # native typed value
└── evidence     # bounded execution/transport metadata
~~~

The Action publishes the final operation value at output.result. Action status, assertion detail, diagnostic and attempts describe execution; they do not replace the business result. Command stdout is parsed through stdoutFormat. HTTP/MQ responses use responseFormat. DB operations return native typed values. A file-content expression returns exact file text as a String, as described in [Actions and Typed Values](reference/actions.md).

Resource evidence can include low-cost metadata. A helper may also configure an optional human-readable snapshot:

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

Evidence output supports json, yaml, xml, text and sqlplus. It is presentation only; it does not mutate or replace output.result. Secret-bearing values are filtered or omitted.

Load scenarios may set evidence.resources.output to inherit (default) or none. none skips optional resource-output formatting/materialization. inherit defers formatting until a success sample or failure receives a retention slot. Metrics-only iterations do not serialize resource output or create an evidence workspace. Transport parsing and file-content String representation are unchanged.

### Tool

A Tool is a named capability owned by the selected Package. Each `tools/*.yaml` file defines one `att-tool/v1` descriptor with exactly one command or call backend. DB/MQ/HTTP/SSH Helpers and their bindings remain global.

#### Command-backed Tool

A command Tool declares stdoutFormat to parse external stdout into the typed result:

~~~yaml
schemaVersion: att-tool/v1
id: queryOrder
name: queryOrder
description: Package Tool queryOrder
command: [./tools/query-order.sh]
stdoutFormat: json
arguments: {}
~~~

stdoutFormat accepts text, json, yaml or xml. It is ingress parsing: ATT parses stdout once and publishes the typed value at output.result. It does not control human-readable logging or file output. Exit code, bounded stdout/stderr preview and streamed process artifacts remain evidence.

#### Call-backed Tool

A call-backed Tool invokes a built-in or supported native DB/MQ/HTTP/SSH operation. Its native typed return value is output.result. Call-backed descriptors do not declare stdoutFormat.

~~~yaml
schemaVersion: att-tool/v1
id: queryOrder
call: "#{db.orders.query(sql='select id from orders where id=:id', parameters={id: ${input.orderId}})}"
arguments:
  orderId:
    name: Order ID
    description: Order key
    required: true
~~~

Tool invocation has no result.format/path/overwrite contract. File persistence is explicit to an API that defines it; human-readable presentation belongs to Log or configured evidence output. HTTP/MQ parsing is owned by those transport boundaries.

See [Actions and Typed Values](reference/actions.md) for Action result handling and [Operation Result and Evidence](reference/resources/operation-result.md) for typed results versus evidence.

#### Tool-definition `command` expressions

##### Context and legal forms

A configured Tool `command` also has its own restricted Context. It may reference only keys declared by that Tool's `arguments` map. The canonical placeholder is `${input.argument}`. `${TOOL.input.argument}` and the exact `${argument}` spelling remain compatible legacy forms and both produce `CONTEXT_TOOL_INPUT_SHORTHAND` when they uniquely match a declared key:

| Form | Meaning |
|---|---|
| `${requestText}` | Legacy shorthand; emits `CONTEXT_TOOL_INPUT_SHORTHAND` |
| `${input.requestText}` | Explicit Tool-input namespace |
| `${TOOL.input.requestText}` | Legacy full alias; emits `CONTEXT_TOOL_INPUT_SHORTHAND` |

For example:

```yaml
schemaVersion: att-tool/v1
id: invokePaymentApi
name: Invoke Payment API
description: Invoke a file-content payment request
command:
  - ./tools/invoke_payment_api.sh
  - "${input.requestText}"
  - "${input.environment}"
stdoutFormat: json
arguments:
  requestText:
    name: Request Body
    description: Request body String
    required: true
  environment:
    name: Environment
    description: Target environment
    required: true
```

The action call is the boundary between the general Runtime Context and this restricted Tool-input Context:

```yaml
callApi:
  type: tool
  call: "#{invokePaymentApi(requestText=${EXEC.VARS.requestText}, environment=${EXEC.INPUT.environment})}"
```

The call resolves the explicit `${EXEC.ACTIONS...}` and `${EXEC.INPUT...}` references first and creates Tool inputs named `requestText` and `environment`. The command then substitutes `${input.requestText}` and `${input.environment}` from those inputs; `${input.environment}` does not read global configuration directly. The legacy `${requestText}` / `${ENVIRONMENT}` spelling and `${TOOL.input.*}` remain compatible only when each name is declared and emit `CONTEXT_TOOL_INPUT_SHORTHAND`.

Each command token also accepts built-in calls through the same expression engine. Built-ins see only the declared Tool-input aliases introduced in [Tool-definition `command` expressions](#tool-definition-command-expressions), and calls may be nested:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - "--environment=#{upper(${input.environment})}"
  - "--label=#{concat('ATT-', #{lower(${input.requestText})})}"
```

Inside a command-side built-in call, declared inputs must also use placeholders: `${input.requestText}` is canonical; `${TOOL.input.requestText}` and `${requestText}` are deprecated compatible forms and produce `CONTEXT_TOOL_INPUT_SHORTHAND`. Bare `requestText` or `input.requestText` is not inferred. Outside `#{...}`, command text continues to use the same Tool-local rule.

A normal argument placeholder may occupy a complete argv token, which is preferred, or be embedded in fixed text:

```yaml
command:
  - ./tools/invoke_payment_api.sh
  - "--request=${input.requestText}"
  - "--environment=${input.environment}"
```

Because this is a YAML argv list, each list item remains one atomic process argument even when its resolved value contains spaces or shell-like characters. ATT does not invoke a local shell.

##### Quotes, Context values, and atomic argv

Quotes inside a Tool call belong to the ATT expression grammar; they are not shell quotes. The outer `'...'` or `"..."` delimiters are removed before invocation, the opposite quote is literal, and a matching quote can be escaped with a backslash. A `${...}` reference embedded in a quoted value is interpolated, while an unquoted canonical Context path passes its typed value directly.

The following configured Tool keeps each declared input as one argv value:

```yaml
schemaVersion: att-tool/v1
id: writeAudit
name: Write audit
description: Write one audit message for one source file
command: [./tools/write_audit.sh, "${input.message}", "${input.sourceFile}"]
stdoutFormat: yaml
arguments:
  message:
    name: Message
    description: Exact audit message
    required: true
  sourceFile:
    name: Source file
    description: File associated with the message
    required: true
```

Use a YAML block scalar when a call contains several quote layers:

```yaml
singleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message="Customer O'Reilly",
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

doubleQuote:
  type: tool
  call: >-
    #{writeAudit(
        message='status="READY"',
        sourceFile=${EXEC.INPUT.sourceFile}
    )}

mixedQuotesAndContext:
  type: tool
  call: >-
    #{writeAudit(
        message="O'Reilly said \"READY\" for ${EXEC.INPUT.caseId}",
        sourceFile=${EXEC.INPUT.sourceFile}
    )}
```

The child process receives the three messages exactly as `Customer O'Reilly`, `status="READY"`, and, for example, `O'Reilly said "READY" for payment.payment.TC001`. A Context value that itself contains either quote needs no caller-side shell escaping and still occupies one argv item.

If a call is kept on one YAML line, YAML escaping is an additional and separate layer:

```yaml
call: "#{writeAudit(message='status=\"READY\"', sourceFile=${EXEC.INPUT.sourceFile})}"
call: '#{writeAudit(message="O''Reilly", sourceFile=${EXEC.INPUT.sourceFile})}'
```

The first line escapes double quotes for the YAML double-quoted scalar. The second doubles the apostrophe for the YAML single-quoted scalar. The expression engine then evaluates the resulting `#{...}` text.

Ordinary process-backed Tools never ask a shell to reinterpret resolved inputs. Text such as `$HOME`, `$(date)`, `a*.xml`, `|`, `>`, and quotes carried by a Context value is passed literally. Use an explicitly reviewed wrapper when shell-like behavior is required; the shipped `fpp.exehelper` and `fpp.loghelper` provide only the narrowly documented pathname expansion above.

##### Illegal forms and token restrictions

Tool commands cannot directly read the general Runtime Context, use unique-suffix navigation, or navigate argument fields with bracket syntax. These `${...}` forms are rejected during configuration or package validation:

```text
${EXEC.INPUT.environment}
${EXEC.ID}
${EXEC.ID}
${STAGES.invoke.InstrAmt}
${input['requestText']}
${TOOL.input['requestText']}
${requestText.path}
```

Configured Tool calls are not available inside `command`:

```text
#{anotherConfiguredTool(value=${requestText})}
```

This is rejected during configuration loading. Expanding one Tool's command cannot invoke another Tool or recursively invoke itself. An unknown, misspelled, differently cased, or undeclared `${...}` argument reference is also a validation error. For a Package Tool, the executable token is static and cannot itself contain `${...}` or `#{...}`.

If an argument declares a non-empty `argName`, its placeholder must appear exactly once and occupy one complete command token:

```yaml
command: [./tools/invoke_payment_api.sh, "${input.requestText}"]
arguments:
  requestText:
    name: Request Body
    description: File-content request body String
    required: true
    argName: --request
```

ATT expands that token to two argv values: `--request`, then the resolved path. An embedded form such as `--request=${input.requestText}` or a transformed form such as `#{str.upper(${input.requestText})}` is invalid when `argName` is non-empty. Likewise, every typed List must use a complete-token placeholder so ATT can safely expand it to zero or more argv values. For an optional argument, a blank complete-token placeholder emits neither its `argName` nor a value; an embedded scalar placeholder instead leaves its surrounding fixed token in argv.

#### Tool-definition `call` expressions

Call-backed expressions use the declared `arguments` map and preserve typed values. They allow pure built-ins and one primary DB query, scalar, or update. `${input.customerId}` comes from the outer Tool call, not a Case global; roots such as `CASE` and `ACTIONS` are unavailable in the definition. Inline SQL and package-contained SQL file content render in this scope. Pass test data through `params` and use JDBC `?` placeholders.

#### Inline Tool descriptor fields

Each `att-tool/v1` file defines one Package Tool. Global `tools` and `toolGroups` are no longer accepted:

| Object | Allowed properties |
|---|---|
| `att-tool/v1` | `name`, `description`, exactly one of `command`/`call`, optional `arguments`; command Tools require `stdoutFormat`, call-backed Tools may use `cache`; `x-*` |
| call-backed `att-tool/v1.cache` | required `scope: case|db` |
| `arguments.<key>` | `name`, `description`, `required`, optional `type`, `enumValues` (required only for `type: enum`), `argName`, `argNameMode`, `delimit`, `x-*` |

An argument `type` declares the final resolved value contract for configured Tools: `string`, `integer`, `long`, `decimal`, `boolean`, `enum`, `bytes`, `map`, or `list`. Omitted `type` remains `any` for existing descriptors. Numeric and Boolean scalar types accept native values or trimmed numeric/`true`/`false` Strings; enum values must match one of `enumValues`; `bytes` accepts a byte array or converts a String to UTF-8. `string`, `map`, and `list` require that native value type. These checks run after the complete nested expression resolves, in validation when safely static and at runtime otherwise.

#### Package-local Tool Groups

Standalone Tools use `tools/<id>.yaml` and `att-tool/v1`. Grouped Tools use a fixed `tools/<folder>/tool-group.yaml` manifest with **`att-tool-group/v3.0`**, which lists each `att-tool/v1` member explicitly (no recursive discovery):

```yaml
# packages/fpp/tools/fpp/tool-group.yaml
schemaVersion: att-tool-group/v3.0
id: fpp
name: FPP Tools
description: FPP integration commands
tools: [invokeApi.yaml, loghelper.yaml]
```

The member `tools/fpp/invokeApi.yaml` contains `schemaVersion: att-tool/v1` and `id: invokeApi`; its caller remains `#{fpp.invokeApi(...)}`. The manifest's `id` owns the public prefix, independent of the folder's name. An optional `script` preserves historical dispatcher argv + member Tool ID + member command argv behavior. An optional `ssh: {helper: application}` binds an existing centrally configured SSHHelper and may specify selection. The manifest cannot embed direct host/credential topology. Missing/duplicate group members and ambiguous qualified identities fail validation.

The selected registered Package owns Tool definitions; Shared Configuration continues to own DB/MQ/HTTP/SSH Helpers, not Tools or Tool Groups. For migration see [Package-owned Tools](guides/package-tools-migration.md).

### DBHelper

DBHelper is a first-class JDBC resource, configured independently from Tools. Each descriptor uses `schemaVersion: att-dbhelper/v2.6` and a stable logical `id`; global `dbhelpers` references descriptor files.

```yaml
schemaVersion: att-dbhelper/v2.6
id: orders
name: Orders database
description: Orders JDBC resource
connection:
  driverClass: oracle.jdbc.OracleDriver
  url: ${ENV:ORDERS_DB_URL}
  username: ${ENV:ORDERS_DB_USERNAME}
  password: ${ENV:ORDERS_DB_PASSWORD}
```

Credentials may be resolved from environment variables and must not be published into `META`, reports or diagnostics. JDBC driver jars are supplied in `lib/`; ATT does not bundle a database driver.

In the Native `att-flow/v3.6` contract, DB operations run under ordinary `type: tool` Actions using `#{db.<id>.query(...)}`, `scalar(...)`, or `update(...)` calls. The call has exactly one String `sql` argument. Use a file-content expression such as `sql=&{sql/find-order.sql}` when the SQL is stored in the package. Positional `params` and named `parameters` are mutually exclusive and use the same JDBC binding rules.

Queries return typed rows/scalars; updates return the documented update result. Operation and SQL/parameter evidence enters the common Action envelope. Secret credentials are never evidence. Parameter evidence follows descriptor/Action masking/type policy.

[Reliability](reference/reliability-execution-control.md) owns Action timeout precedence, retry eligibility, attempt limits and replay cautions. DBHelper owns the descriptor's statement timeout and transaction lifecycle. Example of an eligible query:

```yaml
actions:
  waitForOrder:
    timeoutMs: 1500
    type: tool
    call: >-
      #{db.orders.query(
        sql='select status from orders where id = :id',
        parameters={id: ${EXEC.INPUT.orderId}}
      )}
    assert: "#{${output.result.rowCount} == 1 and ${output.result.rows[0].STATUS} == 'DONE'}"
    retry:
      maxAttempts: 5
      intervalMs: 500
      retryOn: [ASSERTION, TIMEOUT]
```

An explicit update is also a Tool Action. It must not use automatic retry:

```yaml
actions:
  markOrder:
    type: tool
    call: "#{db.orders.update(sql='update orders set status = ? where id = ?', params=['DONE', ${EXEC.INPUT.orderId}])}"
```

The historical v3.5/v3.4 `type: db` Action and its `query`/`update` blocks remain available only through the archived schemas and compatibility loaders.



DBHelper owns connection/statement limits, query timeout and transaction behavior defined by its descriptor. Transaction finalization is tied to the Case/iteration lifecycle; commit/rollback/reconnect are resource operations, not public Context roots. Action-level timeout/retry extends the shared Action lifecycle without changing the DBHelper identity or Context model.

#### DBHelper configuration

Each path in global `dbhelpers` resolves from the package root and contains one `att-dbhelper/v2.6` object:

| Object | Required/default | Allowed properties and constraints |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, `connection`; optional `statement`, `transaction`, `result`, `evidence`, `pool`, `x-*` |
| `connection` | required | required `url`; optional `username`, `password`, `driverClass`, `properties`, `readOnly`, `isolation`, `x-*` |
| `statement` | defaults | `timeoutSeconds` defaults to 30, integer 1–3600 |
| `transaction` | defaults | `scope: case|statement`, `onEnd: commit|rollback`; defaults `case`/`rollback` |
| `result` | defaults | `maxRows` 1000, `maxCellBytes` 1048576, `maxBytes` 10485760; positive bounded integers |
| `evidence` | defaults | `sql: full\|hash` defaults full; `parameters: values\|types\|masked` defaults values; optional `output: {format: json\|yaml\|xml\|text\|sqlplus, maxChars: 10000}` |
| `pool` | defaults | `maxSize` defaults 20, `minIdle` defaults 0, `connectionTimeout` defaults 2s; `maxSize` 1–10000, `minIdle` cannot exceed `maxSize`, timeout is at least 250ms |

For LOAD's run-scoped connection provider, `scope: statement` returns the borrowed connection after each statement's transaction, result materialization, statement close and cancellation cleanup. A later DB Action borrows again. `statement/commit` uses JDBC auto-commit; `statement/rollback` rolls back before return. Query/update failures also release the statement-scoped connection after cleanup. No automatic SQL retry is added.

`scope: case` retains its shared connection until Case finalization, including read-your-writes and rollback-only behavior. The defaults remain `case/rollback`. Direct RUN/DEBUG connections retain their existing lifecycle. Choose statement scope explicitly only when Actions are independent; changing from Case scope changes transaction semantics.

LOAD `resources.db` metrics include per-helper aggregate `totalConnectionHoldMs`, `releasedConnections`, `totalStatementMs`, `statementCount`, `activePeak` and sampled `observedWaitingPeak`, alongside existing borrow count/wait/timeout metrics. Hold time starts after acquisition and ends after close; statement time excludes pool acquisition and includes result/resource cleanup. The sampled waiting peak can miss brief waits. These counters contain no SQL, parameters, URLs or credentials. `DB_POOL_TIMEOUT` still means acquisition failed before SQL execution; SQL and Tool Action timeouts remain separate budgets.

Query result byte limits are accounted one row at a time; CLOB UTF-8 bytes are counted while reading chunks. This keeps limit enforcement linear in the returned data size.

The root `id` must match `^[A-Za-z_][A-Za-z0-9_-]*$` and be package-unique ignoring case. `connection.isolation` is `driverDefault`, `readUncommitted`, `readCommitted`, `repeatableRead`, or `serializable`. Driver `properties` is a string-to-string map. Complete `${ENV:NAME}` values resolve while loading configuration; missing variables are errors. See [DBHelper](reference/resources/dbhelper.md) for Action, expression, result, security, and lifecycle behaviour.

DB/MQ/HTTP share the optional `evidence.output: {format: json, maxChars: 10000}` presentation policy. Supported formats are `text`, `json`, `yaml`, `xml`, and `sqlplus`; `sqlplus` requires a DB query/update result. `maxChars` defaults to 10000 and accepts 1–1000000. Credentials are redacted before deterministic character truncation; snapshots contain `format`, `text`, and `truncated`. Formatting failure adds only a bounded `outputError` and changes neither typed `output.result` nor operation status. Normal Run/Debug invocations automatically include the snapshot in Action evidence and the Case log, without an extra Log Action; SQL, parameters, MQ payload metadata, and HTTP status/header diagnostics keep their own contracts. Load defers formatting until an iteration is retained, then materializes `resource-output.yaml`; `evidence.resources.output: none` skips it entirely. Presentation never introduces credential values into evidence.

The `waitForOrder` query above needs no following Log Action. Configure its DBHelper like this to retain an automatic SQL*Plus-style row snapshot:

```yaml
evidence:
  sql: full
  parameters: masked
  output:
    format: sqlplus
    maxChars: 10000
```

`sql` controls SQL evidence; `parameters` accepts only `values`, `types`, or `masked` (there is no `no_mask` option), and defaults to `values`. Configured credentials remain redacted under every parameter mode. `output` affects presentation only; assertions and later Actions still read typed rows, and `db.<id>.query`/`scalar` use the same policy. `maxChars` defaults to 10000 and ranges from 1 to 1000000. A large `SELECT` may need a higher limit to retain its complete formatted rendering.

### MQHelper

MQHelper is a logical IBM MQ resource with one or more physical instances. Current descriptors use att-mqhelper/v1.3. Connection settings, credentials, queue defaults and pool limits belong to the resource and are not exposed through META.

~~~yaml
schemaVersion: att-mqhelper/v1.3
id: payment
name: Payment MQ
description: Payment request/reply queues
defaults:
  connection:
    queueManager: QM1
    host: mq.example.internal
    port: 1414
    channel: APP.SVRCONN
  message:
    requestQueue: PAYMENT.REQUEST
    replyQueue: PAYMENT.REPLY
  requestReply:
    waitMs: 5000
    responseFormat: xml
    maxResponseBytes: 10485760
  pool:
    maxSize: 20
instances:
  - id: primary
evidence:
  payload: none
  output:
    format: text
    maxChars: 10000
~~~

A Tool Action calls mq.<id>.send, mq.<id>.receive or mq.<id>.request as its primary operation. MQ reply bytes are decoded using received CCSID metadata when available, then parsed by responseFormat (text/json/yaml/xml). The parsed typed value is output.result. responseFormat owns ingress parsing; Log.format and evidence.output.format only control presentation.

#### Request queue routing

<!-- att-docs:historical -->
`att-mqhelper/v1.3` adds `message.requestQueues` and `message.requestQueueSelection.strategy`. Existing `att-mqhelper/v1.2` scalar descriptors remain accepted and keep their original behavior; the new keys require v1.3.
<!-- /att-docs:historical -->

~~~yaml
schemaVersion: att-mqhelper/v1.3
id: payment
name: Payment MQ
description: Payment request queue routing
defaults:
  connection: {queueManager: QM1, host: mq.example.internal, port: 1414, channel: APP.SVRCONN}
  message:
    requestQueues: [PAYMENT.REQUEST.01, PAYMENT.REQUEST.02, PAYMENT.REQUEST.03]
    requestQueueSelection: {strategy: roundRobin}
    replyQueue: PAYMENT.REPLY
instances:
  - id: primary
~~~

A list contains 1–256 unique, nonblank queue names, each at most 48 characters from `[A-Za-z0-9_.%/-]`. `single` (default) chooses the first entry; `roundRobin` rotates in list order; `random` chooses only configured entries. Scalar `requestQueue` and list `requestQueues` cannot coexist in one message object. An instance's scalar replaces an inherited list and its selection policy; an instance's list replaces an inherited scalar/list and resets selection to `single` unless it supplies its own policy. An instance that only overrides `requestQueueSelection` keeps the inherited list. Selection policy requires an effective list. Reply defaults continue to inherit separately and remain one optional scalar `replyQueue`.

Runtime first selects the physical instance using `instance=` or physical `selection.strategy`. Then `send(queue=...)` or `request(requestQueue=...)` overrides automatic queue routing. Blank/invalid explicit queues fail with `MQ_ARGUMENT` before MQ I/O; they never fall back to a configured destination. Without an explicit queue, the selected instance supplies its scalar or list. Missing required effective queues also fail before connecting. `receive` uses its explicit `queue` or the single reply default. Each request queue belongs to the selected instance's queue manager; this does not route across queue managers.

Queue round-robin counters are thread-safe and scoped to one `MqHelperExecutor`, logical helper, and physical instance. Run/Debug/Load calls sharing that executor share counters; separate executors and Worker JVMs start their own counters. There is no Server-wide or cross-worker fairness guarantee. Explicit overrides and receive calls do not advance queue counters. Automatic selection occurs once after argument/payload validation and before connecting; connection/open/PUT failures still advance it. A request never reselects or automatically fails over after PUT, including a correlated reply timeout. Explicit Action retry remains governed by the replay policy below.

Action evidence and output metadata include `requestQueue`, `queue`, `requestQueueSelectionStrategy` (`single`, `roundRobin`, `random`, or `explicit`), the selected physical instance and queue manager, and the effective `replyQueue` for requests. ReplyToQ/ReplyToQMgr and sent MsgId → reply CorrelId matching remain unchanged. These bounded routing fields accompany retained Run/Debug/Load evidence; credentials remain protected.

#### Sending file-content strings and abstract values

A file-content expression produces a String and can be passed directly as payload:

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
send:
  type: tool
  call: "#{mq.payment.request(payload=${EXEC.VARS.requestText})}"
~~~

ATT encodes the exact file text using the configured MQ charset/CCSID. It does not parse and reserialize the String. Do not supply requestFormat for a file-content String; MQ transport metadata remains resource-owned.

A Map/List is an abstract structured value and requires requestFormat (text/json/yaml/xml), for example payload=${EXEC.INPUT.request}, requestFormat=json. String + requestFormat is rejected. MQ send/request calls do not accept local file paths; `file` is an unknown argument. A file-content expression does not create a file or targetFiles.

#### Evidence, response parsing and Load

MQ evidence may contain bounded transport metadata such as helper/instance identity, operation, safe queue names, message IDs, CCSID, byte counts, response format, duration and failure classification. Payload capture is controlled by evidence.payload; human-readable result snapshots are separately controlled by evidence.output. Load can disable resource snapshots with evidence.resources.output: none; otherwise formatting is deferred until the iteration is retained. Typed result and response parsing do not change.

Call-level responseFormat may override requestReply.responseFormat for receive/request; send does not parse a reply. Instance selection and pool limits belong to the descriptor. See the [Legacy migration reference](legacy/migration-reference.md) for migration details.

MQ replies are capped at 10 MiB. The IBM MQ adapter configures the message receive limit before reading and reports `MQ_RESPONSE_TOO_LARGE` when a reply exceeds it. This is a transport-success size rejection and remains distinct from a missing reply or connection failure.

See [Actions and Typed Values](reference/actions.md) for the shared typed-result contract.

#### Descriptor configuration

| Object | Required/default | Contract |
|---|---|---|
| root | required | `schemaVersion`, `id`, `name`, `description`, non-empty `instances`; optional `defaults`, `selection`, `evidence`, `x-*` |
| `defaults` / `instances[]` settings | inherited then overridden | `connection`, `message`, `requestReply`, `pool`; each instance has an `id` |
| `connection` | effective fields required | queue manager, host, port and channel as required by transport; port 1–65535; optional username/password |
| `message` | defaults | CCSID 1208; `format` supports MQSTR/MQHRF2/MQFMT_STRING/MQFMT_NONE/NONE or empty; persistence supports asQueue/persistent/notPersistent/nonPersistent or 0–2 |
| `requestReply` | defaults | `waitMs` 10000, range 0–3600000; `responseFormat` controls receive/request parsing; `maxResponseBytes` defaults to 10485760 and accepts 1–1073741824 |
| `pool` | defaults | maxSize 20 (1–10000), minIdle 0 (not above maxSize), borrowTimeout 2s. Optional `att-mqhelper/v1.3` lifecycle keys default off: `maxIdle`, `maxLifetime`, `staleIdleValidation`, `staleIdleThreshold` (60s), `validationTimeout` (200ms, 1–5000ms), `heartbeatInterval` (0) |
| `selection.strategy` | descriptor policy | `random` or `roundRobin` |
| `evidence` | policy | `payload: none|metadata`; raw payload bytes are not structured evidence; optional `output` is human presentation |

Connection credentials may be complete `${ENV:NAME}` references. Resolved secrets do not enter metadata, diagnostics or Case evidence. Queue names are non-blank, at most 48 characters, and use IBM MQ queue-name characters. Logical helper and physical instance IDs are resolved case-insensitively; duplicate IDs and descriptor paths fail validation.

The machine-readable field constraints remain in [the active MQ schema](../schemas/att-mqhelper-v1.3.schema.json).

##### Idle connections and teardown

A LOAD pool key is `logicalId::instanceId` (for example `fromeaimqhk::mq01`). Teardown diagnostics use a single colon, `fromeaimqhk:mq01`. Those strings name the same physical pool. A failure for `fromeaimqhk::mq01` is not a failure of `fromeaimqcb::mq01` or `toeaimq::hk02`.

`resources.mq` is a PRE_TEARDOWN sample taken before disconnect. `failures` counts creation failures only. Idle-validation problems are `validationFailures` and are not included in `failures`. `cleanupFailuresIncluded` is false: disconnect results stay in `disconnectFailureCount` and `failureClass`. `failures: 0` with `idle: 2` does not mean disconnect succeeded and does not mean validation ran. `resources.postTeardown` is the separate confirmation sample taken after disconnect attempts. Unconfirmed sessions stay quarantined: the slot remains charged, the connection is not borrowed again, and MQRC 2009 does not prove the queue manager released it. The job stays ERROR.

`PHYSICAL_DISCONNECT_FAILED` means disconnect returned or threw before the shutdown wait ended. `LOAD_RESOURCE_TEARDOWN_TIMEOUT` means disconnect or connect was still running when that wait ended (`max(borrowTimeout, 5s)`). That session is one frozen record, including a connect still inside the factory before a connection object exists. A later completion, including MQRC 2009 or a successful disconnect, is attached to the same record and is not counted again; the failure class stays `LOAD_RESOURCE_TEARDOWN_TIMEOUT`. A validation timeout is neither of those: that connection is not offered. `close()` interrupts the idle-validation thread. That worker clears the interrupt status before it calls disconnect, so the closer does not observe the validation interrupt. If the check is still running when shutdown ends and `disconnect()` was not attempted, the result is `MQ_IDLE_VALIDATION_INCOMPLETE` and it is not added to `failedCloseCount`. `isConnected()` returning false is `MQ_IDLE_NOT_CONNECTED` and does not invent MQRC 2009. Reason 2009 is recorded only when IBM returns it. A cancelled lease or other non-connection invalidation stays out of the idle pool with `exclusionCode=INVALIDATED` and is not marked known-dead. `knownDead` is reserved for a recognized connection failure or an explicit outage.

Reason 2009 at final disconnect does not say when the socket stopped working. Correlate queue-manager, channel, client and network logs at the failure time. For the 2026-10-30 run that finished 2026-11-01 10:52:35Z, that local time is 2026-11-01 18:52:35 HKT (UTC+8). Process exit does not prove a graceful MQ disconnect.

The default borrow path does not call MQ. Optional stale validation, when `staleIdleValidation` is true and the idle age is at least `staleIdleThreshold`, calls IBM `isConnected()` and, when the client exposes it, `inquire` of `MQIA_PLATFORM`. It does not put or get a business message. `staleIdleThreshold: 0` validates every idle borrow. A transport without those methods is counted as unsupported and is still offered. `maxIdle` and `maxLifetime` recycle an idle connection by closing it, with no validation call and no business message. The next borrow recycles an expired idle session before lending it. While either limit is greater than 0, a background sweep about every 30 seconds closes idle sessions that have already passed the limit, including a session that is never borrowed again, so it does not wait for final teardown. Ordinary borrow and return notifications do not run that sweep early. `0` disables that limit. With both at 0 the sweep does not start, and the default borrow path still does no MQ I/O. `close()` stops the sweep immediately, before it waits for disconnects, and a session is disconnected once. A connection failure, including MQRC 2009, queues that physical disconnect and releases the borrow permit without waiting on the LOAD worker. The physical slot stays charged until the disconnect is confirmed, so that connection is not borrowed again. Each pool runs at most 4 disconnects at once, on daemon threads named `att-load-resource-disconnect`. Further closes wait in that pool's queue and keep their slots. `close()` counts queued and running disconnects together. Any that are still outstanding when the shutdown wait ends stay one frozen `LOAD_RESOURCE_TEARDOWN_TIMEOUT` record, and a disconnect that returns MQRC 2009 still leaves the job ERROR. ATT does not retry the business call.

`pool.heartbeatInterval` is applied only when `com.ibm.mq.constants.MQConstants` actually exposes `HEARTBEAT_INTERVAL_PROPERTY` and the value is greater than 0. IBM MQ classes for Java 9.4 do not publish that connection property, so the setting does not override the SVRCONN channel HBINT on that client. `0` never sets a client property. Set the channel HBINT below the firewall idle timeout when idle sessions are being dropped. TCP keepalive is an operating-system and IBM client setting (`com.ibm.mq.cfg.TCP.KeepAlive`, channel KAINT). ATT does not enable it. Raise `borrowTimeout` only when a successful disconnect genuinely needs longer; a hung disconnect remains `LOAD_RESOURCE_TEARDOWN_TIMEOUT` and stays quarantined.

DB/MQ/HTTP share the optional `evidence.output: {format: json, maxChars: 10000}` presentation policy. Supported formats are `text`, `json`, `yaml`, `xml`, and `sqlplus`; `sqlplus` requires a DB query/update result. `maxChars` defaults to 10000 and accepts 1–1000000. Credentials are redacted before deterministic character truncation; snapshots contain `format`, `text`, and `truncated`. Formatting failure adds only a bounded `outputError` and changes neither typed `output.result` nor operation status. Normal Run/Debug invocations automatically include the snapshot in Action evidence and the Case log, without an extra Log Action; SQL, parameters, MQ payload metadata, and HTTP status/header diagnostics keep their own contracts. Load defers formatting until an iteration is retained, then materializes `resource-output.yaml`; `evidence.resources.output: none` skips it entirely. Presentation never introduces credential values into evidence.

##### Request/reply timeout and replay policy

A correlated reply completes `mq.<id>.request(...)` with PASS. A successful PUT followed by correlated GET MQRC 2033 (`MQRC_NO_MSG_AVAILABLE`) is a standard TIMEOUT with `MQ_TIMEOUT` diagnostic, even if the outer Action deadline has time remaining. Native evidence retains `sent: true`, `replyReceived: false`, `completionCode: 2`, `reasonCode: 2033`, the reason name and effective `waitMs`. Other transport failures retain the stable MQ ERROR taxonomy; outer deadline and pool borrow timeout also follow the normal TIMEOUT path.

The canonical Action outcome is `output.status: TIMEOUT`; suite/report aggregate operational failure remains ERROR, with TIMEOUT and MQRC 2033 in the report message and Case log. Native metadata is available at `output.evidence.mq.invocations[0]` during the attempt and at `EXEC.ACTIONS.<actionId>.output.evidence.mq.invocations[0]` afterwards.

Without `retry.when`, `retryOn: [TIMEOUT]` can PUT the whole request again. For a side-effecting request, add a Boolean gate that excludes the already-sent/no-reply case:

~~~yaml
invokePayment:
  type: tool
  call: "#{mq.payment.request(payload=${EXEC.INPUT.requestText})}"
  timeoutMs: 30000
  retry:
    maxAttempts: 3
    intervalMs: 1000
    retryOn: [TIMEOUT]
    when: "#{${output.evidence.mq.invocations[0].reasonCode?} != 2033}"
~~~

The optional `?` path evaluates to null when another timeout has no MQ reason code. The condition permits ordinary timeout retry, but reasonCode 2033 suppresses a second PUT without changing the TIMEOUT outcome. ATT does not infer idempotency or deduplicate messages. Use `send` followed by correlated `receive` when repeated reply polling is required.

Standalone `receive` explicitly retains its non-error polling contract: MQRC 2033 returns PASS with `received: false` when the outer deadline has not expired, including a bounded wait that finds no message. An expired outer deadline is TIMEOUT. This operation-aware contract supersedes the earlier guidance that avoided request/2033 TIMEOUT to prevent replay. See [common retry semantics](reference/actions.md).

### HTTPHelper

HTTPHelper is an environment-bound HTTP resource. The selected config profile binds a stable logical helper ID to its base URL. Descriptors use att-httphelper/v1.1.

~~~yaml
schemaVersion: att-httphelper/v1.1
id: payment
name: Payment API
description: Payment service
baseUrl: https://payments.example.internal
defaults:
  responseFormat: auto
  maxResponseBytes: 10485760
  connectTimeoutMs: 5000
  readTimeoutMs: 30000
  followRedirects: false
evidence:
  output:
    format: json
    maxChars: 10000
~~~

The HTTP connection pool has these defaults and limits:

| Pool field | Default | Contract |
|---|---:|---|
| `maxConnections` | 50 | Total connections, 1–10000 |
| `maxConnectionsPerRoute` | 20 | Per-route connections, 1–10000 and no greater than `maxConnections` |
| `connectionRequestTimeoutMs` | 5000 ms | Maximum wait to lease a connection, 1–3600000 ms |
| `keepAliveMs` | 30000 ms | Keep-alive duration, 1–3600000 ms |
| `idleEvictMs` | 60000 ms | Idle age after which a connection is eligible for eviction; checked before requests, 1–3600000 ms |

Size the pool for the expected in-flight Load concurrency. For example, the following tuning overrides raise the per-route capacity and shorten the connection-lease wait; values shown here are not defaults:

~~~yaml
pool:
  maxConnections: 50
  maxConnectionsPerRoute: 40
  connectionRequestTimeoutMs: 2000
~~~

`connectionRequestTimeoutMs` bounds how long an operation waits to lease a pooled connection. A short wait/timeout while service latency remains stable can indicate generator pool saturation; inspect the per-helper active/idle/waiting/peak metrics in Load output.

Call http.<id>.get/post/request as the primary call of a type: tool Action. Response bytes are parsed at this boundary using call responseFormat, the helper default, or Content-Type when auto is selected. Supported response formats are auto, text, json, yaml and xml. The parsed native value is output.result. Optional evidence.output is a bounded human-readable snapshot and never changes that value.

#### Request bodies and file-content strings

A file-content expression returns the exact UTF-8 file content as a String. Assign it once and pass it directly as the body:

~~~yaml
prepareRequest:
  type: assign
  name: requestText
  expression: "&{templates/payment/payload/request.xml}"
sendRequest:
  type: tool
  call: "#{http.payment.post(path='/v1/payments', body=${EXEC.VARS.requestText})}"
~~~

HTTP sends the exact String to its charset-encoding boundary. ATT does not parse and reserialize it. Do not combine a file-content String with requestFormat.

A Map/List is an abstract structured value and requires explicit requestFormat, such as body=${EXEC.INPUT.request}, requestFormat=json. requestFormat accepts text, json, yaml or xml and applies only to Map/List. String + requestFormat is rejected. HTTP calls do not accept local file paths; `file` is an unknown argument. A file-content expression creates no result file and has no targetFiles.

The file-content String has no format metadata and does not set HTTP Content-Type. Configure contentType/header when a specific media type is required. Request charset/headers and response parsing remain HTTPHelper concerns, separate from Action result or Log formatting.

#### Failure and Evidence

Transport/protocol and response-parse failures are operational errors. A received 4xx/5xx is a completed response and can be asserted through statusCode. HTTP evidence may include helper ID, method, safe URL, response status, content type, byte counts, response format and duration. Credentials and payloads are not implicitly stored. Load can set evidence.resources.output: none to skip optional resource-output formatting, or defer it until the iteration evidence is retained.

HTTP responses are capped at 10 MiB. A larger declared Content-Length is rejected before reading; unknown or incorrect lengths are read through a bounded stream that stops at the first byte beyond the cap. The operation reports `HTTP_RESPONSE_TOO_LARGE` while retaining the received status code in diagnostics. In Load resource diagnostics, HTTP connection pools report active, idle, waiting, and peak counts by helper.

Set `defaults.maxResponseBytes` from 1 through 1073741824 in the HTTP helper descriptor; it defaults to 10485760 (10 MiB).

See [Actions and Typed Values](reference/actions.md) for the shared file-content String and typed-result contract.

DB/MQ/HTTP share the optional `evidence.output: {format: json, maxChars: 10000}` presentation policy. Supported formats are `text`, `json`, `yaml`, `xml`, and `sqlplus`; `sqlplus` requires a DB query/update result. `maxChars` defaults to 10000 and accepts 1–1000000. Credentials are redacted before deterministic character truncation; snapshots contain `format`, `text`, and `truncated`. Formatting failure adds only a bounded `outputError` and changes neither typed `output.result` nor operation status. Normal Run/Debug invocations automatically include the snapshot in Action evidence and the Case log, without an extra Log Action; SQL, parameters, MQ payload metadata, and HTTP status/header diagnostics keep their own contracts. Load defers formatting until an iteration is retained, then materializes `resource-output.yaml`; `evidence.resources.output: none` skips it entirely. Presentation never introduces credential values into evidence.

### SSHHelper: logical SSH targets

#### SSH Resource helper operations

SSHHelper also exposes the common Resource Helper form inside a normal `type: tool` Action: `ssh.<helperId>.execute`, `ssh.<helperId>.upload`, `ssh.<helperId>.stat`, `ssh.<helperId>.mkdirs`, `ssh.<helperId>.move`, and `ssh.<helperId>.delete`. The helper ID is logical; native Resource Helper calls select one physical instance using `single`, `random`, or `roundRobin`. `selection.strategy: all` is rejected for native Resource Helper calls; it is reserved for command-backed Tool fan-out. These calls are validated without opening an SSH connection, and they share the helper's concurrency bound and redacted identity handling.

```yaml
actions:
  health:
    type: tool
    call: >-
      #{ssh.application.execute(
        command='systemctl is-active example.service',
        stdoutFormat='text',
        timeoutMs=5000
      )}
  uploadRequest:
    type: tool
    call: >-
      #{ssh.application.upload(
        remotePath='/srv/app/request.json',
        payload=${EXEC.VARS.requestText},
        overwrite=true
      )}
```

`execute` requires `command` and accepts `stdoutFormat: text|json|yaml|xml` plus `timeoutMs`. Text returns the exact stdout String; the structured formats parse stdout into the native Map/List/scalar result. A timeout, non-zero remote exit, or parse failure returns an operation error with a distinct category and retains bounded stderr, exit code, byte counts and transport evidence. SSH resource execution treats a non-zero exit as an operation failure. The command-backed Tool fan-out contract has different failure semantics.

`upload` requires `remotePath` and a String or byte-array `payload`. To upload a package file, pass the UTF-8 String returned by `&{...}` directly; SSHHelper does not accept or resolve local filesystem paths. Map/List values are rejected rather than implicitly serialized. Absolute remote paths are allowed. Upload overwrite defaults to `true`.

SSHHelper has no `download` operation because downloading requires a local destination path. Use an explicitly configured command-backed Tool when host-level file retrieval is required.

All operations accept only named arguments. Unknown operations, helper IDs, arguments, duplicate arguments, invalid formats, invalid timeout values, missing required fields, upload source conflicts and unsafe local paths fail validation before external execution. Runtime evidence contains the logical helper, selected instance, host/port, operation, transport, timing and transfer/command details; command input and represented payload content are not copied into evidence. Environment-supplied identity paths remain redacted.

Native SSH failures expose stable categories in both invocation `error.category` and `SSH.error.category`:

| Category | Meaning |
|---|---|
| `SSH_CONNECTION_ERROR` | Connection, host verification, or channel setup failed without a timeout; also used when the backend cannot identify authentication reliably. |
| `SSH_AUTH_ERROR` | The Java backend identifies authentication rejection/cancellation or cannot initialize the configured identity. |
| `SSH_TRANSPORT_ERROR` | OpenSSH returned 255. This may indicate connection/authentication failure or a remote command that itself exited 255; ATT retains stderr and exit code without guessing from localized diagnostics. |
| `SSH_TIMEOUT` / `SSH_POOL_TIMEOUT` | Operation/connect deadline or concurrency wait expired. |
| `SSH_REMOTE_EXIT` | A remote command completed with a non-zero exit (OpenSSH 255 uses the category above). |
| `SSH_RESULT_PARSE_ERROR` | stdout parsing failed. |
| `SSH_UPLOAD_ERROR` | The upload failed after connection setup, including remote permission/missing-path/protocol errors. |
| `SSH_ARGUMENT` | Argument validation failed. |
| `SSH_INTERRUPTED` | The caller interrupted the operation. |

Transfer connection/channel failures retain `phase: connect|channel`; timeout evidence also retains the applicable timeout budgets. All failures keep their selected operation and actual transport. Transfer errors remain ineligible for automatic timeout replay.

Native Resource Helper calls use one absolute Action deadline covering concurrency-pool wait, connection and channel setup, and command or SFTP operation. A per-call `timeoutMs` or helper `timeouts.commandTimeoutMs` sets the operation limit but cannot extend the enclosing Action deadline. `timeouts.connectTimeoutMs` caps connection establishment within the remaining deadline; it does not add time to the operation. The timeout applies to execute/upload/stat/mkdirs/move/delete. Native `execute`, read-only `stat`, and idempotent `mkdirs` `SSH_TIMEOUT` and `SSH_POOL_TIMEOUT` failures may use Action `retryOn: [TIMEOUT]`, including through call-backed Tools; common `retry.when` controls replay. Native `upload`, `move`, and `delete` reject timeout retry because their mutation outcome may be uncertain after timeout. Validation and runtime use the same operation policy. A timed-out SFTP Action returns at its deadline while its concurrency lease remains held by the cleanup worker until the transfer worker and transport terminate.

SSHHelper routes a command-backed Tool to a stable logical application-server ID instead of embedding a physical host in the Tool group. The `att-sshhelper/v1.0` YAML descriptor contains `id`, optional `name`/`description`, optional `defaults` (`user`, `port`, `identityFile`), a non-empty ordered `instances` list, optional `selection.strategy`, and optional `fanout.maxConcurrency` (default 4, range 1–256). Each instance needs `id` and `host`; `user` must come from the instance or defaults. Instance fields override defaults; port defaults to 22 and must be 1–65535. Helper and instance IDs match `[A-Za-z_][A-Za-z0-9_-]*` and are unique ignoring case. Invalid hosts/users, unknown properties, duplicates, missing users, unsafe paths, and unsupported strategies fail before SSH execution.

```yaml
# config/sshhelpers/sit/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
name: Application servers
description: SIT application tier
defaults: {user: deploy, port: 22, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: roundRobin}
fanout: {maxConcurrency: 2}
timeouts: {connectTimeoutMs: 10000, commandTimeoutMs: 60000}
instances:
  - {id: app1, host: sit-app1.example}
  - {id: app2, host: sit-app2.example, port: 2222}
```

Bind descriptor paths globally or in `environments.<NAME>.sshhelpers` of `att-config/v2.15`. Current packages use config v2.15 and Tool Group v3.0. The selected environment's list replaces the global list; omission inherits it. A group binding must resolve to the same logical ID in each selected profile. SIT can bind one host and UAT two without changing the Tool, Action, or Resource Helper call:

```yaml
# config/config.yaml
schemaVersion: att-config/v2.15
environment: SIT
environments:
  SIT:
    sshhelpers: [config/sshhelpers/sit/application.yaml]
  UAT:
    sshhelpers: [config/sshhelpers/uat/application.yaml]
```

```yaml
# config/sshhelpers/uat/application.yaml
schemaVersion: att-sshhelper/v1.0
id: application
defaults: {user: deploy, identityFile: '${ENV:APP_SSH_KEY}'}
selection: {strategy: all} # command-backed Tool fan-out only; native ssh.application.* rejects all
fanout: {maxConcurrency: 2}
instances:
  - {id: app1, host: uat-app1.example}
  - {id: app2, host: uat-app2.example}
```

```yaml
# packages/<id>/tools/app/tool-group.yaml
schemaVersion: att-tool-group/v3.0
id: app
name: Application tools
description: Remote application inspection
ssh:
  helper: application
  selection: {strategy: all} # optional group override
tools: [status.yaml]

# packages/<id>/tools/app/status.yaml (separate file)
schemaVersion: att-tool/v1
id: status
name: Status
description: Print service status
command: [systemctl, is-active, example.service]
stdoutFormat: text
```

The unchanged Action calls `app.status`. Set `APP_SSH_KEY` to a readable private-key **path** in the local/CI secret environment, then validate both profiles: `./att.sh validate --config config/config.yaml --env SIT --package` and the equivalent UAT command. An exact `${ENV:NAME}` identity-file reference is resolved at load time; a missing/empty variable is rejected without revealing its value. A Package-local Tool Group supports only a logical global SSHHelper binding (`helper` with optional `selection`); legacy direct host/user/key declarations must migrate to Global SSHHelper descriptors. Call-backed Tools may target the native SSH Resource Helper call when it is the primary `type: tool` operation. The active Tool Group schema is v3.0. Command-backed Tools declare stdout parsing with `stdoutFormat` (`text|json|yaml|xml`); call-backed Tools preserve their native result type. Superseded config and group schemas are migration references only. SSH Resource Helper calls publish only `META.SSHHELPER.id` and `.type`; endpoint, user, identity file and credentials remain private. Resource calls have no Action- or per-call strategy override; selection remains helper configuration.

Strategy precedence is group override then helper default. For native Resource Helper calls, one instance works without a strategy (`single`), multiple instances require `random` or `roundRobin`, and `all` is rejected rather than silently selecting one host. For command-backed Tool routing, `random` selects one uniformly, `roundRobin` selects one via a thread-safe cyclic counter, and explicit `all` executes every listed instance once with bounded parallelism. **Command-backed `all` has side effects on every host**: use only commands safe across the entire group. There is no implicit fan-out, cross-host retry, or failover. If an author configures an Action timeout retry, the whole `all` invocation is repeated, not just one host. Each host gets the Action/Tool/global timeout; interruption cancels active OpenSSH processes or Java SSH sessions. Both transports receive the same normalized host/user/port/key. OpenSSH is preferred; mwiede/jsch fallback retains strict host-key verification and the limitations in [SSH diagnostics](reference/appendices/limits-defaults.md).

For a single selected host, parsed `output.result` remains the legacy scalar/object value. Evidence adds `sshHelper`, `instance`, `host`, `selectionStrategy`, `selectionSource` (`helper` or `toolGroup`), transport, start/end/duration, exit code, output and errors. Only command-backed Tool fan-out uses `all`; its `output.result` contains `sshHelper`, effective `selectionStrategy`, `selectionSource`, and `instances` keyed in descriptor order. Every entry has `instance`, `host`, `port`, `transport`, `startedAt`, `endedAt`, `durationMs`, `status`, and when available `exitCode`, `stdout`, `stderr`, `rawOutput`, parsed `output`, or `error`. A completed command has `status: PASS` even with a non-zero `exitCode`; that code is evidence for the Action assertion, not an operational failure. The operation fails only on an execution, output-parse, cancellation, or timeout error; other hosts' evidence is retained. Assertions may inspect `${output.result.instances.app1.exitCode}`, `${output.result.instances.app1.status}`, or `${output.result.instances.app1.output}`. Credential contents and environment-supplied key paths are not recorded; keep private keys outside the package and do not put secrets in commands.

Environment-supplied identity paths are redacted from argv, transport stderr (including streamed Case-log diagnostics), and exception evidence for both single-host and `all` execution. This no-recording guarantee applies to ATT metadata and transport diagnostics; parsed business stdout remains unchanged, so commands must not print secret paths.

Migration: move legacy Group host/user/port/key into a central SSHHelper descriptor, bind it per environment, and replace the legacy group file with Package `tools/app/tool-group.yaml` (`att-tool-group/v3.0`) plus `att-tool/v1` members using `ssh: {helper: application}`. Validate each Environment. Actions stay unchanged. Inventory discovery, per-Action host override, distributed transactions, cross-host failover and orchestration are out of scope.

`stat(remotePath='/srv/app/result.xml')` uses SFTP lstat and returns typed `{path, exists}` plus `type: file|directory|other`, file `size`, and available ISO `modifiedAt`. A missing path is normal `exists: false`; permission/auth/transport errors still fail. `mkdirs(remotePath='/srv/app/archive')` creates parents, succeeds for an existing directory, and rejects a non-directory. `move(sourcePath='/srv/app/out.xml', targetPath='/srv/app/archive/out.xml', overwrite=false)` renames on the same selected host; source must exist and replacing a target requires explicit overwrite. It does not stage bytes locally. Explicit overwrite removes an existing matching regular file or empty directory before SFTP rename, so it also works on servers without rename-overwrite support. Non-empty directories, special files, and mismatched types are rejected. Replacement is not atomic: a removal failure preserves both paths; a later rename failure leaves the source in place and may leave the target absent. `delete(remotePath='/srv/app/tmp.xml', missingOk=false)` supports regular files and empty directories; missingOk is explicit, and non-empty directories, recursive arguments, and wildcard/backslash paths are rejected. All operations accept named arguments and optional `timeoutMs`, sharing selection, concurrency, deadlines, host verification, redacted identity, and Run/Debug/Load execution. Filesystem errors use `SSH_STAT_ERROR`, `SSH_MKDIRS_ERROR`, `SSH_MOVE_ERROR`, or `SSH_DELETE_ERROR`. There is no remote copy API; use explicit `execute(command='cp /srv/app/a /srv/app/b')` when needed. File-content expressions use `&{...}`; ATT manages local output.

## Reliability and execution control

This page defines cross-cutting behavior for Native Flow Actions and their Resource calls.

### Assertion and status

An assertion evaluates a boolean condition after the Action's primary work at the documented assertion point. A false assertion is `FAIL`; an exception or infrastructure problem is `ERROR`; invalid authoring or configuration is `INVALID`; a non-selected condition is `SKIPPED`; successful work is `PASS`. Operation failure and assertion failure are distinct.

### `runWhen` and `onFailure`

`runWhen` controls whether an Action is eligible to execute. `onFailure: stop|continue` controls continuation after failure; `continue` never changes a failed status into PASS. Use conditional execution for cleanup and diagnostics without hiding a failed primary operation.

### Timeout

Timeout terminates or abandons the operation according to the supported backend and records diagnostic evidence. Timeout is an operational failure, not a false assertion. Tool timeout behavior and DB/MQ/HTTP/SSH limits are defined by their Resource contracts.

### Retry and attempts

Where retry is supported, one logical Action owns multiple attempts. Retry policy determines which operation failures are retryable. The final or winning operation becomes top-level `output.result` and `output.evidence`; every attempt remains available under `output.attempts[n]`. A later success does not erase earlier attempt evidence.

### Evidence collectors

Tool evidence collectors run after the primary operation publishes its typed `output.result` and before that attempt's assertion. While the Action is active, `${output.evidence.collectors.<id>.result}` and `${output.evidence.collectors.<id>.status}` are available; after publication, the canonical paths are `${EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result}` and `.status`. Collectors have independent `timeoutMs` and `onFailure: continue|stop`. Their output belongs to attempt evidence and never replaces the primary operation result.

Collectors run once per primary attempt. The top-level collector node represents the final or winning attempt, while `output.attempts[n].evidence.collectors.<id>` retains each attempt. `continue` keeps the primary outcome visible when diagnostic collection fails; `stop` makes collector failure an Action error. Use an ordinary Tool or Log Action when the collected value is business or test data rather than pre-assertion diagnostics.

### Transaction and Resource lifecycle

DB transaction finalization and DB/MQ/HTTP/SSH cleanup occur at the appropriate execution boundary. These mechanisms can affect operation success and diagnostics, but they are internal Resource state rather than public Context namespaces.

### Aggregation

When multiple child outcomes contribute to a parent, severity is preserved:

~~~text
ERROR > INVALID > FAIL > PASS > SKIPPED
~~~

### Tool timeout precedence

Tool Action timeout overrides the Tool descriptor timeout, which overrides the global timeout. For call-backed DB Tools, the DBHelper statement timeout remains a backend ceiling. Each supported primary retry attempt runs its collectors before assertion.

### Direct DB timeout and retry eligibility

Direct DB Actions may declare `timeoutMs` from 1 to 3,600,000 ms. When present, `Action.timeoutMs` overrides `DBHelper.statement.timeoutSeconds`; otherwise the helper timeout is used. Each retry attempt gets a fresh Action timeout, and the retry interval is outside that timeout.

A direct `query` Action may also use the standard retry block with `maxAttempts` 2–10, `intervalMs` 0–3,600,000, and a non-empty unique `retryOn` list containing `ASSERTION`, `TIMEOUT`, or both. `ASSERTION` requires an Action `assert`. Ordinary SQL errors are terminal. Retry-enabled query attempts are retained in `output.attempts[n]`; top-level `output.result` and `output.evidence` represent the final or winning attempt, with `winningAttempt` or `finalAttempt` recording the terminal attempt number.

Direct `update` Actions support `timeoutMs` but reject `retry`. After a timeout or database/transport failure, ATT generally cannot prove whether a mutation reached or committed at the server. Automatic replay could duplicate business state, so any application-specific idempotent retry must be modeled explicitly.

## Configuration and environments

Shared Runtime Configuration selects the Environment, output behavior, and global Resource bindings. Native Package roots and Package-owned resources are defined by the [Package Manifest](reference/package-manifest.md). Existing packages authored with earlier configuration fields can use the [Legacy configuration guide](legacy/configuration.md).

### Configuration layers

| Layer | Source | Owns |
|---|---|---|
| Shared Runtime Configuration | `ATT_HOME/config/config.yaml` | Environment selection, execution defaults, reporting limits, and shared DB/MQ/HTTP/SSH Resources |
| Environment profile | `environments.<name>` in shared configuration | Environment-specific Resource bindings |
| Package Manifest | `<PACKAGE_ROOT>/package.yaml` | Package identity, Flows, Test Case Definitions, Tools, Testdata, and Package-owned files |
| CLI options | Current command | One-command selection, Environment, identity, and output overrides |

The Package Manifest controls Package resource roots and Testdata. A Native Package does not need a separate runtime configuration, schema tree, or helper registry.

### Select an Environment

Use one shared configuration file and select the Environment with `--env`. The top-level `environment` value is the default.

~~~yaml
schemaVersion: att-config/v2.15
environment: SIT
outputDirectory: output
timeoutMs: 30000
dbhelpers:
  - config/dbhelpers/orders.yaml
mqhelpers:
  - config/mqhelpers/payment.yaml
environments:
  SIT:
    dbhelpers:
      - config/dbhelpers/sit/orders.yaml
    mqhelpers:
      - config/mqhelpers/sit/payment.yaml
  UAT:
    dbhelpers:
      - config/dbhelpers/uat/orders.yaml
    mqhelpers:
      - config/mqhelpers/uat/payment.yaml
~~~

~~~sh
./att.sh validate --package --config config/config.yaml --env SIT
./att.sh validate --package --config config/config.yaml --env UAT
./att.sh --local -p default run native-case payments.offline.authorize --resolved-case-id auth-accepted --config config/config.yaml --env SIT
~~~

Profiles replace the configured Resource lists as a whole. Keep logical Resource IDs stable across Environments; use profile bindings to select endpoints and credentials.

### Configure execution identity and output

| Field | Default | Meaning |
|---|---|---|
| `outputDirectory` | `output` | Package-relative root for local output |
| `timeoutMs` | `10000` | Default execution timeout, from 1 to 3,600,000 milliseconds |
| `run.id.default` | `timestamp` | The only supported Run ID default |
| `run.id.timestampFormat` | `yyyyMMdd-HHmmss` | Java date/time format for generated IDs |
| `execution.runIdFormat` | unset | Optional expression for the outer RUN or LOAD identity |
| `execution.debugIdFormat` | unset | Optional expression for standalone Flow or Tool Debug |
| `execution.processOutput.memoryLimitBytes` | `65536` | Bounded stdout/stderr preview retained in memory |
| `execution.processOutput.artifactLimitBytes` | `104857600` | Maximum bytes streamed to each process artifact |
| `report.html.caseLogInlineLimitBytes` | `32768` | Inline case-log limit in HTML output |
| `report.junit.caseLogEmbedThresholdBytes` | `10240` | JUnit case-log embedding threshold |

Explicit `--run-id` and `--debug-id` values override generated identities. Generated values must be safe single path segments and unique within the selected output root. Identity expressions run before execution; they cannot read per-Case or per-iteration values.

The Native RUN result workbook is written as `workbooks/native-cases.result.xlsx`. Earlier source-workbook report naming is described in the [Legacy configuration guide](legacy/configuration.md).

### Ignore or disable ATT-owned configuration with x

The lowercase `x-` prefix can disable ATT-owned fields and keyed entries at schema-supported extension locations before validation or execution. It does not remove user data such as HTTP header names or DB parameters. `runWhen: false` is a runtime condition and does not disable configuration fields.

### Resource bindings and credentials

The shared configuration owns DB, MQ, HTTP, and SSH Resource descriptors. Paths must remain within their configured runtime root. Environment profiles can replace each Resource list.

Use the approved secret-injection syntax where supported. Never commit credentials or place resolved secret values in logs, report fields, or diagnostic output. Missing-variable messages identify only the required name.

### Validate changes

~~~sh
./att.sh validate --package --format json --quiet
./att.sh validate --package --config config/config.yaml --env UAT
~~~

Validation checks configuration schemas, profile references, Resource descriptors, Package resources, and dependency paths. It is read-only. It does not submit a Job, execute a Tool, or start a Load scheduler.

For Native Package roots and Manifest fields, see [Package Manifest](reference/package-manifest.md). For shared Resource contracts, see [Resources](reference/resources/overview.md).

## Package Manifest

Use `<PACKAGE_ROOT>/package.yaml` to give one Package its own defaults, business roots, Testdata inventory, and SUT Metrics inventory. The optional file uses the current `att-package/v2` schema, independently of Global Configuration. The matching Engine JAR supplies the authoritative schema.

A Package ID and its filesystem root come from trusted registration or the supported Local package selection. A Manifest never registers a Package, grants access, or supplies a privileged runtime configuration. Shared profiles, Tools, and DB/MQ/HTTP/SSH Helpers remain owned by Global Configuration.

### Declare Package defaults

The current runtime discovers Flow files under `<PACKAGE_ROOT>/<flowRoot>/` (default `flows/`). `resources.flowRoot` is part of `att-package/v2`. Existing Packages may also keep Flows under `<templatesRoot>/flows/` during migration; the same Flow ID in both roots is rejected.

This example selects an existing central profile and declares two Package-owned Testdata files. Create those descriptors before validating the Package.

```yaml
schemaVersion: att-package/v2
metadata:
  name: Payments
  description: Payment regression resources
defaultEnvironment: SIT
resources:
  flowRoot: flows
  templatesRoot: templates
  testcaseRoot: testcase
testdata:
  descriptors: [testdata/accounts.yaml]
  environments:
    UAT: [testdata/uat-accounts.yaml]
    PROD: []
observability:
  metricSources: []
  groups: []
  profiles: []
defaults:
  timeoutMs: 15000
  report:
    columns: {result: Result, durationMs: Duration}
  xml: {namespaceMode: preserve}
```

The `observability` paths are optional and list Package-owned Metric Sources, Groups, and Profiles under `load/sut-metrics/{sources,groups,profiles}/`. Empty inventories mean the Package has no SUT Metrics; they do not fall back to global definitions. See the [SUT Metrics migration guide](guides/sut-metrics-config-migration.md) for complete descriptors and shared Data Source connections.

<!-- att-docs:historical -->
Existing `att-package/v1` Manifests remain in compatibility mode and keep their legacy global SUT Metrics behavior. New or edited manifests use v2.
<!-- /att-docs:historical -->

`SIT`, `UAT`, and `PROD` must exist in the central `environments` mapping. Omitting a profile's Testdata entry inherits `descriptors`. A present list replaces that inventory completely; `[]` clears it.

Paths use forward slashes and stay relative to the selected Package. Absolute paths, `..`, unsafe symlinks, and escapes are rejected. The canonical file is limited to 262144 UTF-8 bytes. Unknown fields, duplicate YAML keys, aliases, anchors, and `x-` extensions are rejected.

Without a Manifest, the Package discovers Flows under `flows/` (and any remaining `templates/flows/` migration companion), uses `testcase/` for Test Case Definitions, and has an empty Package Testdata inventory. A Native Package may omit `templates/` entirely.

The current Flow root (`flows/`), Test Case Definition root, and `load/` root are optional for inspection. If a directory is absent, Explorer, LOAD Catalog, and Quick Load return an empty inventory for that root. If a configured or conventional root exists but is not a readable directory, or resolves outside the canonical Package, inspection fails with a sanitized `ATT-RESOURCE-ROOT-INVALID` diagnostic; it never follows an unsafe path or treats an invalid present root as empty.

### Resolve an Environment

An explicit request or supported CLI `--env` selects the profile first. Otherwise, `defaultEnvironment` selects an existing central profile; the central default applies when the Manifest omits it. Profile names resolve to their canonical central spelling. The Effective view shows the selected profile and its origin.

Server policy applies after selection. A Package default cannot bypass the allowed list or explicit-selection requirement. New Server execution drafts, direct submissions, and schedules retain the resolved Environment. An omitted Environment on schedule creation inherits the validated draft's selection. A later default edit cannot redirect that snapshot; source revision checks may require a new review.

Registry adapters can use `FrameworkConfigLoader.loadContext` with an explicit `PackageContext`. Its Environment precedence is request → Context → Package → Global. Only the adapter-supplied registered Package ID enters provenance; a folder name or login does not supply an ID. The future CLI Context persistence commands remain a separate change.

Execution results retain a value-free `summary.configuration` snapshot: canonical Environment, original selection layer, Global and Manifest digests, effective configuration identity, Engine version/revision, Schema Registry digest, and field origins with logical authored paths. RUN, Debug, and Load write `configuration.json` alongside their managed artifacts. Server drafts, queued Jobs, and Schedule definitions retain the approved snapshot, including the registered Package ID and validated Package revision. Job history and results preserve it across Server restarts. Callers cannot supply these internal approval fields.

A Worker checks the approved effective configuration identity before execution. A Schedule using the `ignore` Package-change policy revalidates an execution copy against current sources; the stored approval remains unchanged, and the execution evidence retains its approved configuration identity for comparison. No configuration values, descriptor records, credentials, or host paths are included in this evidence.

### Apply typed defaults

The Manifest permits only these execution and presentation defaults. Unspecified fields inherit their global values.

| Manifest path | Behavior |
|---|---|
| `defaults.timeoutMs` | Positive integer, bounded by central `packagePolicy.maxTimeoutMs`; default cap 300000 ms |
| `defaults.run.id.default` | `timestamp` only |
| `defaults.run.id.timestampFormat` | Existing execution timestamp format validation |
| `defaults.execution.runIdFormat` | Existing Run/Load identity expression contract |
| `defaults.execution.debugIdFormat` | Existing Debug identity expression contract |
| `defaults.report.fileNamePattern` | Existing report filename contract |
| `defaults.report.columns` | Replaces the complete column mapping, including explicit `{}` |
| `defaults.report.html.caseLogInlineLimitBytes` | Integer 0–1048576 |
| `defaults.report.junit.caseLogEmbedThresholdBytes` | Integer 0–1048576 |
| `defaults.caseLog.yamlAnchors` | Boolean |
| `defaults.xml.namespaceMode` | `ignore` or `preserve` |

Output directories, process output limits, scheduling limits, authentication, credentials, helper definitions, worker settings, and schema locations cannot be overridden here. The Manifest schema in the embedded Engine registry defines the complete field whitelist.

### Resolve Testdata for one invocation

Base inventories belong to the selected Package and Environment. Another Package can use the same descriptor ID without sharing records or importing its neighbor's files.

Execution validates the base descriptors needed by the referenced Testdata closure. An unrelated missing or malformed base descriptor does not block a Quick Load that never references it. Invocation-local Debug/Load imports remain strict: explicitly imported missing or malformed files fail validation. An invocation descriptor replaces the entire base descriptor with the same ID; fields and records are not merged. Duplicate IDs within one layer remain errors.

Full Package validation checks every declared Manifest descriptor layer, including Environment-specific lists. For the bundled default Package, run:

```bash
./att.sh validate --package --format json
```

Inspect diagnostics for Package ownership, logical path, layer, and descriptor ID. Resolve full-validation failures before publishing or distributing a Package.

### Inspect and publish in Studio

With a registered Package selected, open **Configuration**, then **Package Configuration**. **Source**, **Declared**, **Effective**, and **Compare with Global** inspect this Manifest separately from **Shared Configuration**. The All Packages shared inspector remains central-only.

Source reading uses the existing source browsing policy and structured masking. Masked projections are read only. Editing requires enabled Server policy and an authenticated active USER whose exact login equals the explicitly registered Package ID. Other USERs retain ordinary operations. An ADMIN-only account has no implicit source-edit permission. Matching a login never registers a directory.

1. Open **Source** and edit the visible Manifest, or create a new optional Manifest.
2. Select **Create Manifest draft** to stage a private, expiring candidate against the displayed digest.
3. Select **Validate Manifest draft** to validate the candidate through the Worker without changing the live file.
4. Review the safe declaration, effective values, Environment, and origins; select **Review validated Manifest**.
5. Select **Publish reviewed Manifest**. The Server rechecks ownership, live digest, global/Package dependencies, and schema revision, then atomically replaces `package.yaml`.

Manifest publication fingerprints the central Runtime Config, explicitly listed Tool/Helper descriptors across its declared profiles, every candidate Testdata descriptor layer, business root bindings, and the embedded Schema Registry. It checks the fingerprint before and after validation and again at publication. It does not scan unrelated business files, archives, or unreferenced central files. The validation dependency set is bounded to 10000 distinct descriptor paths and 256 MiB of file content; these are dependency limits, not a total Package size limit. Execution and Job revisions retain their separate business dependency checks.

Edited source and pending drafts participate in Studio navigation protection. Refresh and view switching stay disabled until the source is staged or explicitly discarded. A conflict requires a fresh draft. Publication records an audit intent before replacement and a completion record afterward. History stays in private Server data; the API returns revision identifiers rather than raw historical source, ordered by stored modification time newest first with an ascending digest tie-breaker. **Create rollback draft** creates another candidate that must pass the same validation and review gates. Restoring pre-Manifest defaults produces a canonical empty `att-package/v2` file, as shown in review.

The trusted deployment policy is optional and defaults to editing disabled:

```yaml
server:
  packageConfiguration:
    sourceEditingEnabled: false
    maxDrafts: 128
    maxDraftsPerUser: 8
    draftTtlSeconds: 900
    maxHistory: 50
    allowedEnvironments: [SIT, UAT]
    requireExplicitEnvironments: [UAT]
```

`allowedEnvironments: []` permits any centrally defined profile. `requireExplicitEnvironments` rejects implicit selection of its listed profiles before execution validation. Draft counts, expiry, history, and source size remain bounded. Source editing also requires inspection and the existing authenticated USER authorization path.

### Migrate legacy Package inventories

Global `testdata` and profile-level `testdata` lists are deprecated and ignored by default. The temporary central `packagePolicy.legacyTestdata: true` switch restores those inventories only for Packages without a Manifest. A Manifest takes ownership completely, including explicit empty inventories. Migration warnings identify the deprecated ownership; this compatibility switch is intended for a bounded migration window.

The migration helper produces a versioned read-only plan from an explicit trusted Server registry. Run it from the repository with a Python environment containing `tools/requirements-migration.txt`:

```bash
python tools/migrate_package_config.py --config config/config.yaml --server-config /ABSOLUTE/PATH/att-server.yaml
```

Replace `/ABSOLUTE/PATH/att-server.yaml` with your deployment registry. The helper inventories each registered Package's business text and bounded XLSX text. It proposes only locally owned, referenced descriptors; preserves explicit Environment empties; skips existing Manifests; and reports missing, unused, or unsafe imports. It lists inherited default field names without printing their values or descriptor records. Duplicate mapping keys and YAML aliases fail inventory. It does not publish files or infer registration.

Review each proposed Manifest, supply any required descriptors, and run full Package validation. Publish through the source workflow or your controlled deployment process. Then remove legacy global inventories and disable `legacyTestdata`.

### API paths

All paths are relative to `/api/v1`. Apply the existing authenticated USER, origin, CSRF, source policy, and request-size rules.

| Method and path | Result |
|---|---|
| `GET /packages/{id}/package-configuration/{source\|declared\|effective\|compare}` | Safe projection; optional `environment` query for effective selection |
| `POST /packages/{id}/package-configuration/drafts` | Stage `{source, expectedManifestDigest}` |
| `POST /packages/{id}/package-configuration/drafts/{draftId}/{validate\|review\|publish\|discard}` | Advance the private draft using `{}` |
| `GET /packages/{id}/package-configuration/history` | Owner-authorized bounded revision list |
| `POST /packages/{id}/package-configuration/rollback` | Stage `{revision, expectedManifestDigest}` for review |

Source revision conflicts return `409`; denied source access or Environment policy returns `403`; expired or unavailable drafts return `404`; draft capacity returns `429`. Publication never runs a test or changes central profile definitions.

## CLI Reference

The current CLI runs Native Cases, validates Package resources, diagnoses Flow or Tool targets, and runs Flow or Tool Load workloads. For earlier Package formats, see the [Legacy CLI guide](legacy/cli.md).

### Build a source checkout

The source checkout launchers run prebuilt classes. Build the Java CLI, Engine, Worker, and Server API modules when classes are missing:

~~~sh
mvn -DskipTests -pl att-cli -am compile
~~~

Use `./att.sh` on macOS/Linux or `att.bat` on Windows. Packaged releases contain prebuilt application JARs and require Java 8 or later; the Server WAR requires Java 17 or later.

### Start with a command

| Task | Command |
|---|---|
| Show help | `./att.sh` or `./att.sh help` |
| Print the version | `./att.sh version` |
| Validate the Package | `./att.sh validate --package` |
| Validate a selected dependency closure | `./att.sh validate --selected <selection>` |
| Run one Native Definition | `./att.sh --local -p default run native-case quick.start.single --resolved-case-id default` |
| Run a Native Suite | `./att.sh --local -p default run native-suite --all` |
| Diagnose a Flow | `./att.sh debug flow <id>` |
| Diagnose a Tool | `./att.sh debug tool <id>` |
| Run a Flow or Tool workload | `./att.sh load --debug flow <id> --load-template builtin:single-vu --preview` |
| Run a declared Load scenario | `./att.sh load examples/load/closed-smoke.yaml` |
| Regenerate reports for a completed Run | `./att.sh report --run-id <id>` |
| Generate local documentation | `./att.sh docs` |
| Archive the latest completed Run | `./att.sh build` |
| Remove documented generated output | `./att.sh clean` |

Native selector syntax and source ownership are defined in [Test Authoring](reference/test-authoring.md). A native Definition binds a Flow and a Data Source; its Cases are selected by qualified ID, tag, or Suite criteria.

### RUN a Case or Suite

~~~sh
./att.sh --local -p default run native-case quick.start.single --resolved-case-id default
./att.sh --local -p default run native-case quick.start.inline --resolved-case-id basic --resolved-case-id with-tool
./att.sh --local -p default run native-suite --all --tag smoke --exclude-tag slow
~~~

Use `--dry-run` to validate and resolve the selection without running Actions. Use `--rerun-failed` with a Native Suite to select the failed executed Cases from its latest eligible completed Run. Use `--fail-fast` to stop scheduling after a failure.

### Validate a Package

~~~sh
./att.sh validate --package
./att.sh validate --selected testcase/quick-start/single/testcase.yaml
~~~

Validation checks the selected Definition, Flow, Tools, Testdata, Runtime Configuration, schemas, and resource references. It does not execute Actions or start a Load scheduler.

### Debug a Flow or Tool

~~~sh
./att.sh debug flow common.payment.v1 --input debug/payment.yaml --env SIT
./att.sh debug tool sample.getAcDate --set arg.date=2026-11-23
~~~

`--input` selects a Debug input file. Repeat `--set input.path=value`, `--set vars.path=value`, or `--set arg.name=value` for typed values. Flow targets accept input and vars; Tool targets accept input, vars, and declared arguments. See [Debug](reference/execution-modes/debug.md) for discovery, protected paths, output, and result behavior.

### Run a Load workload

A direct Load workload targets a Flow or Tool. A declared scenario can contain validated Flow and Tool workloads.

~~~sh
./att.sh load --debug flow common.payment.v1 --load-template builtin:single-vu --preview
./att.sh load examples/load/closed-smoke.yaml --users 1 --duration 100ms
~~~

List and preview operations do not start traffic. See [Load mode](reference/execution-modes/load.md) for pacing, Testdata, evidence, thresholds, and scenario composition.

### Run a Server command

Local commands execute on the current machine. Add `remote` to submit supported operations to ATT Server:

~~~sh
./att.sh remote --server sit packages
./att.sh remote --server sit run payments --detach
./att.sh remote --server sit jobs
./att.sh remote --server sit watch J0123456789ABCDEF
~~~

Remote Package names, Definition selections, configuration, and scenario paths are logical values resolved by Server. Client absolute paths and local output directories are not sent. See [ATT Remote client](remote-client.md) for connection profiles, authentication, streaming, and troubleshooting.

### Common options

Options apply only to commands that accept them.

| Option | Purpose |
|---|---|
| `--config <file>`, `--env <name>` | Select shared Runtime Configuration and Environment |
| `--run-id <id>`, `--debug-id <id>` | Set the Run or Debug output identity |
| `--output-dir <dir>` | Select an output root for supported local commands |
| `--format human` or `--format json` | Select terminal output |
| `--quiet`, `--verbose` | Control progress detail |
| `--profile` | Write supported phase timings and counters |
| `--queue`, `--allow-parallel-runs` | Set output-root process concurrency behavior |
| `--substitute-package-root`, `--substitute-external-basename` | Hide absolute paths in displayed diagnostics |
| `--help` | Print command-specific help |

`--resolved-case-id` may be repeated for a Native Definition. Suite selection uses `--all`, `--tag`, and `--exclude-tag`. Options are validated per command; unsupported combinations return an error.

### Exit codes

| Code | Meaning |
|---:|---|
| 0 | Command succeeded without FAIL, ERROR, or INVALID |
| 1 | One or more Case or assertion failures |
| 2 | Invalid command, configuration, or validation |
| 3 | Runtime or infrastructure failure |

Windows uses `att.bat` with the same supported Native Case, Flow, Tool, and Load arguments.

## Results, reports, and evidence

### ATT Server Job artifact publication

Worker files remain private until the Server's publication step approves them. Artifact producers apply their sensitive-data protections before writing files. The archive is not a redaction boundary: it validates paths, file types, sizes and UTF-8 text, then preserves captured producer bytes exactly. This boundary applies to Native RUN (including Debug diagnostics), LOAD, and their Server Job artifacts.

After execution ends, the Server asynchronously builds one `artifacts.zip`. Its status is independent of the Job result: `PENDING`, `ARCHIVING`, `READY`, or `FAILED`. A PASS/FAIL/ERROR/INVALID/CANCELLED Job remains terminal while publication runs. On successful verification and atomic publication, loose output is removed. The ZIP is the only persistent artifact payload; metadata, audit, events and archive-status records remain separate.

#### Publication and sensitive-data policy

The archive accepts regular, non-symlink UTF-8 text files with these extensions: `txt`, `log`, `json`, `yaml`, `yml`, `xml`, `html`, `htm`, `css`, `csv`, and `md`. Binary data, malformed UTF-8, NUL bytes, nested archives and unsupported formats are excluded. JSON and YAML are treated as producer-authored text during archive publication; the archive does not parse, redact or reserialize them. Rejected filenames are not recorded in the public manifest. Studio displays omissions explicitly; an incomplete export does not claim to contain all output.

| Input | Publication behavior |
|---|---|
| Logs, text, XML, CSV and generated structured results | Producers apply the relevant secret, credential and path protections before writing files. The archive preserves the resulting bytes exactly. |
| Ordinary business values, including names and potentially regulated data | Producer rules preserve values unless they match an explicit sensitive-field or secret-text rule; arbitrary PII detection is not provided. |
| HTML | The archive preserves exact producer bytes, including markup, scripts and relative links. HTTP previews use a restrictive sandbox CSP; downloaded HTML is not rewritten to add one. |

Producer-side protections recognize names such as password/passphrase, token, secret, credentials, API/access/private keys, authorization and cookies. Recognized secret text in structured field names is protected too; producer-side collisions after secret/path projection reject the record rather than silently overwriting business fields. Put sensitive business data under an explicitly sensitive field or classify its enclosing structured record as `secret`. An unrelated field containing arbitrary business data is not automatically confidential. Authorization to operate Jobs does not authorize raw-secret export. There is no public raw-export capability or raw-file fallback; producers must protect content before it reaches the archive input tree.

Engine-generated artifact data applies path and secret projection before writing. The archive does not inspect semantic fields or rewrite known paths. Online HTML previews use a sandbox with an opaque origin, a restrictive CSP, no forms and no Studio-origin privileges. Relative links in the downloaded report continue to resolve against the archive's file layout when their targets are present.

#### Manifest and resource limits

`export-manifest-v1.json` uses manifest version `1` and policy `att-export-approved-bytes-v1`; entries use decision `preserved`. Approved producer bytes are preserved exactly; producers must mask sensitive values before emitting artifacts. Each entry records its logical path, media type, exact byte size, SHA-256, report/preview eligibility, classification and publication decision. `complete` and aggregate `omissions` describe excluded output without rejected filenames or values. ZIP, preview and extracted files use the same captured bytes; GET requests never independently redact them.

Inventory is independent of `server.maxArtifacts`: up to 50,000 filesystem candidates, depth 64, 10,000 published entries, 16 MiB per entry and 128 MiB total published bytes. The manifest is limited to 8 MiB. Crossing aggregate limits fails publication and preserves private source output. The default ZIP method is STORED; server configuration may select bounded per-entry DEFLATE or AUTO while respecting the 200:1 ratio limit. Readers reject unsafe paths, control characters, encoded traversal, duplicate/case-colliding names, oversized or high-ratio entries, unmanifested entries and integrity mismatches. Publication uses one writer with a bounded queue; at most eight artifact reads run concurrently.

Readers also check the ZIP central directory against local headers before trusting the manifest. Link, special-file and Windows reparse attributes, file/directory prefix collisions, filename-override extra fields, comments, hidden prefixes or records, overlapping entries, multipart archives and ZIP64 are rejected. The bounded export profile permits STORED or Deflate entries and timestamp-only extra metadata; STORED remains the default. Manifest parsing rejects duplicate keys, trailing documents, fractional versions/sizes, unknown fields and non-policy omission text. A structurally readable ZIP is not sufficient proof of an approved export.

ZIP downloads use a request-private copy in the JVM's OS temporary directory. The Server copies at most 140 MiB per download, verifies the complete copy and all entry digests before setting download headers, and streams from that same open file with its captured length. Each 64 KiB buffer is checked against its captured SHA-256 before writing, so later replacement or in-place modification of the published pathname cannot substitute response bytes. The existing eight-reader limit also bounds concurrent download copies; allow temporary disk space for these copies. Normal completion, disconnect and failed approval close/delete the copy. Abrupt process termination can leave temporary files for OS or operator cleanup. Entry previews retain their separate request-scoped verified ZIP handle and per-entry digest/CRC checks.

#### Detach and restore a Job console

Studio can open a non-modal floating console from Live Output, Events, or the Job detail header. It remains mounted while the user navigates elsewhere in Studio, and one console is allowed per browser tab. Switching to another Job requires explicit confirmation. Minimize leaves a bottom chip and keeps one bounded per-tab SSE subscription without rendering hidden event rows; Close releases the subscription. Neither action cancels the Job. The console can be moved and resized with pointer or keyboard controls and becomes a full-screen sheet on small viewports.

**Open full Job Details in a new tab** uses the encoded same-origin route `#/jobs/{jobId}?standalone=1&tab=events` with `noopener noreferrer`. It opens Overview, Live Output, Events, Results, Artifacts and Metrics, preselecting the originating tab. Old `/console?view=live|events` bookmarks remain compatible. Switching tabs preserves the Job ID and shared SSE subscription. Refresh, direct links and non-root context paths remain supported. Unauthorized or missing Jobs use the existing sign-in or safe-unavailable message. Floating console behavior remains unchanged.

#### View and download a Job archive

In Job Details, open **Artifacts**. Reports appear independently of listing pagination. Choose **View Report** or **Preview** for read-only text with search, copy and line numbers. Text rendering is capped at 200,000 characters. Use **Load more artifacts** for subsequent pages. **Download Artifacts (.zip)** is the sole download action. Extract the ZIP and open its HTML report for offline browsing; relative links preserve the same archive hierarchy.

The authenticated archive index returns `archiveSizeBytes` only for a READY, verified ZIP. Studio shows its human-readable size beside the ZIP download; it does not download the ZIP or make a HEAD request to calculate size.

The index also reports full-archive entry counts and uncompressed bytes for summaries/manifests, metrics/time series, execution evidence, recovery checkpoints, and other files. Studio keeps the primary report visible, groups the remaining entries in collapsed categories, nests execution evidence under workload and execution groups when those path components are available, and searches the loaded pages by path or workload; load more pages to extend a search. Entry previews show compact filenames and keep the full approved archive path available in the row.

The Server validates report completeness independently from execution status, performance verdict and archive status. Routine recovery checkpoints are omitted only when the canonical report is verified complete and `executionStatus: PASS`; a `NOT_MET` performance verdict remains separate and does not change this policy. Cancelled or runtime-error runs retain up to the latest two integrity-verified checkpoint generations per scope even if they committed a complete diagnostic report. If the report is absent, incomplete, corrupt, interrupted, or unavailable, up to the latest two verified generations per scope are retained. An incomplete or corrupt report bundle is omitted as a bundle, while verified checkpoints remain previewable and the archive reports its omissions. Source checkpoints are not deleted before report-commit validation.

When identical large aggregate and single-workload series share detail files, the versioned manifest reports how many files were reused and the serialized bytes saved. Values that differ or belong to multi-workload runs keep their separately scoped files.

**Artifact archive metrics** appear under **Jobs and results → Metrics**, including terminal ERROR Jobs without Load results. Both Job detail and archive index expose the typed `archiveMetrics` object (version 1), persisted independently of Worker execution metrics. Attempts/retries survive restart; timings and resource measurements describe the latest writer attempt. Queue wait ends when the writer starts; `publicationWallMs` includes the writer's finalization and cleanup, not queue wait. Timing buckets cover scan, validation and byte capture, ZIP compression/write, verification/file-fsync, and private/staging cleanup. Regular source file count/bytes include rejected regular files; included/omitted counts describe the attempted publication. The ratio is approved uncompressed entry bytes divided by complete ZIP bytes (including the manifest), not percentage space saved. Source byte sums saturate at the signed 64-bit maximum.

`writerCpuMs` measures only the Server writer thread when JVM thread CPU timing is already supported/enabled. `writerAllocatedBytes` is that thread's allocated-byte delta, not retained memory, JVM heap use, or peak memory. Telemetry does not enable JVM instrumentation or measure Worker CPU. Unsupported/disabled measurements and stages not executed (for example, reusing an existing verified ZIP) are `null` and displayed as Unavailable, never fabricated zeros. Real empty input has zero file/entry counts but a nonempty manifest ZIP. Outcome and failure phase/reason use fixed safe codes, never filenames, data or exception messages. RUNNING metrics identify an unfinished attempt; cleanup failure is CLEANUP_PENDING while an already verified ZIP stays downloadable. A metrics-store failure is logged without changing publication safety or falling back to raw output.

`server.artifacts.compression.mode` defaults to `store`; `deflate` and `auto` are opt-in, with levels 1–9 (default 3). AUTO leaves entries under 256 bytes stored and deflates only with at least 5% savings. Every entry remains subject to the 200:1 expansion-ratio verifier; highly repetitive entries fall back to STORED even in explicit DEFLATE mode. Metrics report configured/effective mode, level, compression duration, stored/deflated counts, compressed payload and ZIP overhead bytes, savings, and bounded fallback reasons.

For extracted `file://` reports, the browser controls how linked file types open. For example, Chromium may download a linked YAML file instead of displaying it. The link still targets the same approved extracted bytes; Studio serves those entries as inline text in its viewer.

| Method | Path relative to `/api/v1` | Result |
|---|---|---|
| GET | `/jobs/{jobId}/artifacts?offset=0&limit=100` | Archive state, paginated entries, primary reports and safe omissions |
| GET | `/jobs/{jobId}/artifacts/archive.zip` | Verified finalized ZIP attachment |
| GET | `/jobs/{jobId}/artifacts/view/{entryPath}` | Manifest-approved HTML/CSS or inline text; directory-preserving relative links |
| POST | `/jobs/{jobId}/artifacts/retry` | Schedule a terminal Job's retry while private source remains available |

Every route requires the shared OPERATE capability. Active USER accounts can access other USERs' approved Job archives; creator identity remains audit metadata. ADMIN-only accounts have no implicit operational access. Authentication, disabled-account checks, local CSRF and configured Origin policy apply. Responses use private/no-store caching; loose-file download routes return 404. Pending or unavailable archive reads return `ATT-SERVER-ARCHIVE-UNAVAILABLE` (409); exhausted read capacity returns `ATT-SERVER-ARTIFACT-CAPACITY` (503).

#### Recover and expire archives

After a failure, keep the private output and use **Retry archive** when available. Concurrent retries are coalesced; a retry accepted while the previous writer is finishing is retained for one follow-up publication instead of being silently dropped. Startup and five-minute reconciliation use a persisted recovery-needed flag and a cursor, scheduling at most 64 unfinished terminal Jobs per pass on maintenance isolated from retention. Successful ZIP verification plus loose-output/staging cleanup clears the flag; Retryable FAILED or interrupted work remains eligible; quarantined Jobs are excluded. Existing history is enrolled once during database migration and processed in these bounded batches, not rescanned after every successful publication. Recovery validates a ZIP committed before its status update and removes abandoned staging and leftover loose output. It never serves unarchived loose files. A missing private source cannot be reconstructed from a corrupt ZIP; the archive remains unavailable.

A terminal Worker RESULT is not an execution-cleanup fence. Publication waits until the in-memory execution has finished and its admission lease is released. The Server retains up to 1,024 observed process identities per Job, including children seen before terminal delivery and cancellation/error cleanup. Graceful stop and forced escalation use those same identities even after a parent exits; admission and publication remain fenced until all observed processes have exited. Enumeration/identity-persistence failure never authorizes publication. After every known process has independently been confirmed stopped, the Server persists a quarantine before releasing admission (including LOAD capacity) and removing the active Job fence. Bounded one-second reconciliation retries deferred release, including transient quarantine-store failures; until the quarantine is durable, admission remains held. The quarantine survives restart and blocks both publication and retention, with archive reason `worker_unverified`. Archive index/read routes honor the durable quarantine even if filesystem status says READY; ordinary archive retry is disabled. Retry cannot clear it: operators must independently establish complete process containment before any offline recovery; this release provides no API to clear quarantine or export raw output. On restart, publication and retention check both the Worker PID/start time and the persisted observed-process ledger; a matching live process, or a live PID with unknown start identity, prevents publication/deletion. Archive status `FAILED` with reason `worker_active` preserves the private output; retry after the Worker and recorded descendants have stopped. A different known start time identifies PID reuse, not the original Worker. This observed-process fencing does not yet prove containment of arbitrary detached descendants that escape before observation. Full OS-level containment and its cross-platform acceptance remain outstanding.

The optional `server.jobStorage` quota can also remove complete Jobs when `dataDir/jobs` exceeds its high watermark. It is disabled by default, measures only that directory, never follows symbolic links, and skips a sweep when measurement is incomplete. Eviction is oldest-terminal-first and deletes the Job directory together with its H2 row. A partial sweep resumes after the last candidate it examined, and quarantined or artifact-recovery Jobs are omitted from those pages so a protected prefix cannot fill the budget. Finishing a pass that already included the oldest candidate reports `quota_unmet` when usage is still above the target. Running, queued, quarantined, recovering, in-use, and Jobs inside `minRetentionHours` stay. The quota does not compact H2 or delete a Job only because that one Job is large. The existing `server.jobRetentionDays` policy expires the complete Job, including its ZIP. Quarantined Jobs are excluded before selecting the bounded expiry page, so their indefinite preservation cannot starve ordinary expired Jobs. Retention defers its sweep while archive readers/writers are active; it does not block the cleanup thread or delete a file in use. Classification and publication outcomes are audited without raw values. Publication failures never delete the only unarchived source.

### Find artifacts for a RUN

A completed Native RUN writes its manifest, event stream, reports, and Case execution evidence under a unique Run directory:

~~~text
<outputDirectory>/<RunID>/
├── run.yaml
├── events.jsonl
├── ci/summary.json
├── ci/junit.xml
├── report/index.html
├── report/junit.html
├── workbooks/native-cases.result.xlsx
└── executions/<EXEC.ID>/...
~~~

The standard XLSX is generated output and is independent of test inputs. A Server Job indexes it as a binary download; text preview is disabled. The HTML report and CI outputs retain Definition ID, qualified Case ID, Flow ID, source revisions, execution ID, and available source-record provenance.

### Read a Case result

The HTML report shows Case identity, status, duration, Expected and Actual values, Action results, and bounded execution-log previews. Each Action result includes its final rendered description and status. The setting `report.html.caseLogInlineLimitBytes` controls the preview; `0` keeps only the artifact link. `report/junit.html` provides a human-readable JUnit view.

Every Case execution maps to one JUnit `<testcase>`. PASS has no failure child; FAIL maps to `<failure>`, ERROR and INVALID to `<error>`, and SKIPPED to `<skipped>`. Text is XML-escaped. `report.junit.caseLogEmbedThresholdBytes` controls inline log content; `0` always uses a relative link.

### Read the CI summary

`ci/summary.json` uses `att-ci-summary/v2.1` and records Run identity, environment, timing, aggregate status and counts, duration statistics, Case records, diagnostic counts, report paths, and the input-manifest hash. Native Case entries also record Definition/Flow identity and source revisions. The generated Run manifest uses `att-run/v2.1`.

### Diagnose Tool evidence collectors

An evidence collector is post-operation observability, not the primary Tool result. With `onFailure: continue`, the primary Action may remain `PASS` while the collector is independently recorded as `ERROR`.

~~~yaml
evidence:
  appLog:
    call: >-
      #{ssh.app.execute(command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100')}
    timeoutMs: 5000
    onFailure: continue
~~~

Inspect `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<collectorId>`. The record contains status, success, invocation ID, result, error, and bounded/redacted operation evidence. Failed collector evidence does not publish raw input, payload, argv, output, resolved command text, or the failed result. See [Limits, Security Guarantees, and Advanced Diagnostics](reference/appendices/limits-defaults.md) for projection rules and numeric budgets.

For retries, inspect `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<collectorId>`. Earlier failed collector records remain available after a later successful attempt; the top-level record follows the final or winning attempt.

### Reproduce a RUN

`run.yaml` records ATT/build identity, runtime information, Environment, timestamps, status, outputs, and SHA-256 inputs for effective configuration, Package files, Tools, SQL resources, Testdata, and schema registry. Use the retained revision and manifest identity to compare runs. `report --run-id <id>` regenerates HTML reports from completed evidence without executing the Package.

### Read Case logs

Structured Case log entries use YAML. ATT preserves embedded text line endings and does not parse or reformat business text. The live console and persisted `case.log` use the same rendered text. Blocks with ERROR, FAIL, or INVALID status use the `【!!!!!】` marker; PASS, SKIPPED, and informational blocks are unmarked.

### Offline SUT Metrics report

A completed LOAD with SUT collection evidence can publish a versioned offline report archive. The publisher verifies its manifest and each normalized observation file, preserves the sealed source archive byte-for-byte, and publishes the report only after verification. The `att-load-report-extension/v2` manifest references `att-load-offline-observations/v1`. Observation records include source, metric, profile, target, UTC window, phase, units, quality, and checksums. Time series include bounded chart previews; business metrics include their value, scope, Workload identity, mapping quality, and unmatched-row count.

When the Job's approved execution definition includes Metric Groups, the report publishes safe group metadata and assignments without raw selectors. Unassigned and ambiguous observations remain listed with diagnostics. The report is usable offline and applies a restrictive Content Security Policy. It does not evaluate performance thresholds. If an `att-load-verdict/v1` record is supplied, the report displays its recorded state and evidence reference without recalculating the verdict. The archive is available through the authenticated Job download route after verified publication.

## Validation and troubleshooting

### Choose the first diagnostic step

Run `./att.sh validate --package` and start with the reported file and field. A **Resolved Case** is one selectable Case from a Test Case Definition. A **Case execution** is one run of a selected Resolved Case. For runtime failures, inspect the execution status and message, then `case.log`, `case.yaml`, and Action evidence. For an existing Package authored with an earlier model, use the [Legacy documentation](legacy/README.md).

### Validate Native inputs

Package validation checks Definitions, Flows, Tools, Testdata, Runtime Configuration, registered schemas, and semantic references. It does not execute Tools, submit a Server Job, or start a LOAD scheduler. It is read-only and never creates authoring inputs.

Studio and other immutable source-inspection paths validate Package configuration semantics without requiring the pinned revision or its configured relative `outputDirectory` to be writable. Local CLI `validate`/`run` and Server Job execution still check that the actual output destination has a writable ancestor; Server Jobs use their independently allocated Job output directory, not a path inside the immutable source tree.

Use a selected-validation option for fast feedback on one Definition or resource target. Before sharing a Package or promoting a build, validate the whole Package and each selected Environment. The [Schema and Version Matrix](reference/appendices/schema-matrix.md) lists the contracts used by this release. Diagnostics retain the source file, field, and structured location; automation should use stable diagnostic codes rather than message text.

| Category | Typical cause | Corrective action |
|---|---|---|
| `ATT-TC` | Missing Definition, invalid Data Source, or duplicate Case ID | Check Definition ID, source records, and resolved Case IDs |
| `ATT-CTX` | Unknown or ambiguous Context path | Check the requested path, current scope, and suggested canonical path |
| `ATT-FLOW` | Unknown Flow, invalid Action, or unresolved dependency | Check the Flow ID, ordered Actions, and referenced resources/files |
| `ATT-CFG` | Unknown field, duplicate key, or wrong type | Compare the value with the active Configuration schema |
| `ATT-TOOL` | Unknown argument, process failure, or parse failure | Check the Tool contract, exit code, and bounded output evidence |
| `ATT-PATH` | Illegal identifier or escaping path | Keep identifiers and files beneath configured Package roots |
| `ATT-RUN` | Timeout, non-zero exit, or runtime exception | Inspect the Case log and Action evidence |

### Common questions

#### Why was a Case ID rejected?

Resolved Case IDs must be unique and path-safe. For Excel sources, store IDs as text and check for blank values, leading or trailing whitespace, reserved device names, and case-fold collisions.

#### Why does a Context variable fail?

ATT reports an absent path as an authoring or runtime error. Check the exact scope, source-field mapping, Action ID, and when the value becomes available. A declared optional blank value is valid and renders as an empty string.

#### Why did a Case fail or error?

A false assertion makes the Case execution FAIL. Invalid expression syntax, Tool failure, timeout, parse failure, I/O failure, or runtime exception makes it ERROR. Inspect the Action evidence for the underlying cause.

#### Can a report be opened without a Server?

Yes. Keep the generated run directory together so relative artifact links continue to work.

### Security and structured diagnostics

Do not place passwords, tokens, private keys, or sensitive customer data in Definition inputs, Tool arguments, logs, or documentation examples. Use approved secret injection and review reports before sharing them.

Machine-readable validation uses `att-validation/v2.1`. Diagnostics include stable codes, severity, file and field, source location when available, and a safe suggestion. Codes are the supported automation interface; message wording can change.

## CI, packaging, and operations

ATT supports source-tree development and offline release packages.

### Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` runs the release gate, regenerates the modular Markdown Reference Manual, builds the application jar and release archives, and smoke-tests the extracted local package. It requires Java/Maven and Python 3. It outputs `att-4.3.1-local.tar.gz` for the CLI, `att-4.3.1-server.tar.gz` for the WAR and deployment guides, and `att-4.3.1-src.tar.gz` for source. Each binary archive must be smaller than 35 MB. The local package no longer includes the WAR or its duplicate runtime libraries.

### Runtime dependencies

The CLI, Engine, and Worker remain Java 8-compatible. The ATT Server WAR requires Java 17+ and external Tomcat 10.1+. ATT does not bundle JDBC drivers for CLI integrations; place required driver/dependency jars in `lib/`. IBM MQ is optional: the default CLI build remains usable without MQ client classes, while MQ deployments package the supported IBM client jar/profile.

### Studio inspection caches

Resource metadata discovery defers supported DB/MQ/SSH/HTTP Helper `${ENV:NAME}` values without reading process secrets. Resource summaries expose `metadataState` separately from `executionReadiness`: `not-validated` is not execution approval; `dependency-unresolved` includes value-free `ATT-RESOURCE-ENV-REQUIRED` diagnostics for discoverable transitive Helper references. These diagnostics name requirements, not confirmed missing variables or network failures. Explorer displays the effective environment and remediation while keeping safe metadata available. The legacy Resource `state: ready` describes metadata, not execution readiness.

Discovery still enforces root YAML/schema/profile checks and shared-config path boundaries. After validating those boundaries, it isolates malformed or missing DB/MQ/SSH/HTTP Helper descriptors, keeps healthy Helpers and unrelated resource metadata available, and reports value-free `ATT-RESOURCE-HELPER-INVALID` / `ATT-RESOURCE-HELPER-UNAVAILABLE` warnings in Explorer. Failed descriptors do not create usable Helper bindings. Explorer returns at most four individual Helper failure diagnostics plus a count of additional failures. Discovery is bounded to 256 descriptors per Helper type, 3,000,000 bytes per descriptor and 16 MiB per type; unsafe/symlink paths, duplicate paths/Helper IDs, trusted-schema failures and exceeded limits remain fatal. Tool groups with missing or invalid SSH Helper descriptors retain their logical bindings for discovery. Explorer propagates SSH binding and ENV requirements through command-backed Tools to dependent targets; unresolved bindings do not authorize execution. Tool-group structural/schema failures remain fatal. Configuration Effective and Compare now use the same value-free discovery policy, including their parsed snapshot cache. They retain valid metadata for missing/malformed Helpers and show structured dependency warnings; `state: degraded` and `executionReadiness: not-validated` never authorize execution. ENV variable names identify requirements, not evidence that a variable is absent: these views never read process secret values. Invalid root configuration, profiles, unsafe paths, trusted-schema failures and inspection limits remain fail-closed. Missing Helper descriptors participate in content reconciliation, so creation or repair invalidates cached metadata without requiring a restart. Selected-target-only strict loading and the unified automatic/manual reindex operation are not yet implemented. Explicit validation and execution continue to use strict configuration loading, so an unrelated active Helper with a missing variable can still block them. Metadata-only configurations cannot execute. Supply required variables to the Tomcat/Worker process environment and validate again; do not add production placeholder credentials to make browsing work.

Studio keeps Resource pages, Detail, safe Source, and ordinary Configuration projections in session memory. The shared cache retains at most 128 responses and 8 MiB of encoded payload, with a 1 MiB per-response admission limit. Entries remain fresh for 15 seconds; returning to an older view shows its cached projection while a background read refreshes it. In-flight reads are deduplicated, and aborting one consumer does not cancel another consumer's request. Search, sorting, pagination and scroll state remain available on view return.

Every reusable read first checks the authenticated inspection context. Identity, session, role or inspection-policy changes discard previous responses. Package/environment selection, explicit refresh, logout, page suspension, a newly observed package revision, and stale-cursor or access errors invalidate the affected memory. Nothing stores Configuration or Source content in localStorage, IndexedDB, a service worker, or an HTTP cache. Auth/CSRF responses and `includeAllFields` administrator responses bypass the query cache. `Cache-Control: no-store` remains enforced on HTTP responses.

Each inspection Worker still handles one request at a time and retains two Resource inspectors. Dispatch prefers an idle Worker already holding the same package/config/environment and policy key, then falls back to another idle Worker. Parsed Configuration snapshots have a separate four-entry LRU and a 32 MiB weighted retention budget per JVM; the configured Worker heap limit remains the hard JVM bound. Root configuration, selected Helper/Tool Group/Testdata dependencies, and the schema tree undergo bounded content reconciliation on reuse. Renames, deletions, same-timestamp replacements and unknown dependency changes therefore force a fresh load. Cache failures fall back to ordinary uncached validation. Every request constructs a fresh permission-checked projection; an administrator projection cannot enter the ordinary cache.

Optional `server.inspection.warmup` defaults to `enabled: false`, `workers: 1`, `keys: []`. `workers` must be from 1 through `server.inspection.maxConcurrent`. Each key contains a `packageId` and optional Engine `environment`; configure no more than twice the warmup Worker count, with no duplicates. Omit `environment` to warm Explorer's package-default index. Keys are distributed among Workers with at most two per Worker. With no keys, each selected Worker warms the first registered package. Missing packages and preload failures do not block Tomcat startup. Warmup skips interactive demand, retries each key at most three times with backoff, reloads after a daemon replacement, and stops at shutdown. Interactive traffic can cancel a preload when every Worker is occupied.

`GET /api/v1/metrics` follows the configured Server authentication policy and includes `inspection.server` and the latest bounded `inspection.workers` snapshots. Counters and total nanoseconds cover HTTP handling, queueing, process launch, dispatch, processing, Configuration load/parse, source requests, Definition/YAML parsing and index builds. They also report cache hits/misses/evictions, watcher invalidation/overflow counts, watched directories, retained keys, instrumented read bytes, protocol response bytes, heap-pool peaks, GC and process CPU. Up to 16 opaque package/environment aggregates retain request count, processing time, index builds, Configuration loads and instrumented read bytes per Worker. Package keys are hashed; counters never contain paths, configuration values or file contents. Byte counters cover instrumented inspection reads rather than OS-level physical I/O. Process-launch timing excludes JVM readiness; dispatch includes readiness and protocol transport. Benchmarks record sample p50/p95 separately. If optional inspection telemetry cannot fit within the configured response-byte budget, the Worker omits that snapshot and the Server clears its routing hint; metrics never relax the response limit or make an otherwise valid response exceed it.

Display snapshots never authorize execution. Debug/Quick Load approval and Worker execution retain authoritative package revision checks, `ATT-SERVER-DRAFT-STALE`, principal-bound cursors and stale-cursor HTTP `409`. See [Server deployment](server-deployment.md) for the configuration example and [inspection benchmarks](system-design/web-ui-inspection-inline-jobs.md) for measurement procedures.

### Documentation operations

`./att.sh docs` generates package documentation at `build/docs/index.html` from the validated ATT package model. The normative product Reference is independently generated from `docs/reference*` by `tools/build_reference_manual.py`. `./att.sh clean` removes documented generated runtime/build outputs but preserves source inputs.

### CI and environment promotion

CI should validate the package before executing external integration tests, keep Run/Debug/Load evidence as job artifacts as appropriate, and select environments through explicit `--config`/`--env` policy. Stable logical DB/MQ IDs let the same Flows move across SIT/UAT/PREPROD without Action edits.

Parallel jobs should use unique Run IDs and, when independent retention/latest-run state is required, separate output roots. Destructive operations such as clean/report/archive over one shared output root must be serialized.

Maintainer implementation sequencing, scheduler internals and resource-owner details live in `docs/system-design/`, not in this end-user Reference.

## Appendix A — schema and version matrix

Active schemas are supplied by the matching Engine JAR's embedded registry, built from `schemas/catalog.yaml`.

| Artifact | Active schema |
|---|---|
| Schedule creation | `att-schedule/v2` |
| Native RUN definition | `att-execution/v2` |
| Native Test Case Definition | `att-testcase/v1` |
| Global configuration | `att-config/v2.15` |
| Package Manifest | `att-package/v2` |
| Testdata descriptor | `att-testdata/v1.0` |
| DBHelper | `att-dbhelper/v2.6` |
| MQHelper | `att-mqhelper/v1.3` |
| HTTPHelper | `att-httphelper/v1.1` |
| SSHHelper | `att-sshhelper/v1.0` |
| Package Tool | `att-tool/v1` |
| Package Tool Group | `att-tool-group/v3.0` |
| Flow | `att-flow/v3.6` |
| Debug input | `att-debug/v1.2` |
| Load scenario | `att-load/v1.9` |
| LOAD observability binding | `att-load-observability/v1` |
| SUT Metrics Data Source | `att-sut-metrics-source/v1` |
| SUT Metric Source acquisition and output mapping | `att-sut-metrics-metric-source/v1` |
| SUT logical Metric Group | `att-sut-metrics-group/v1` |
| SUT Metrics Profile | `att-sut-metrics-profile/v1` |
| SUT Metrics Profile | `att-sut-metrics-profile/v2` |
| SUT Metrics execution binding | `att-sut-metrics-snapshot/v1`, `att-sut-metrics-snapshot/v2` |
| Server-private SUT Metrics execution payload | `att-sut-metrics-execution-payload/v2` |
| SUT Metrics collection manifest | `att-sut-metrics-manifest/v1` |
| SUT Metrics manifest integrity history | `att-sut-metrics-manifest-integrity/v1` |
| SUT Metrics normalized observation | `att-sut-metrics-observation/v1`, `att-sut-metrics-observation/v2` |
| SUT Metrics series data | `att-sut-metrics-series/v1` |
| SUT Metrics threshold evaluation record | `att-sut-threshold-evaluation/v1` |
| Load preset | `att-load-template/v1` |
| Load bindings | `att-load-bindings/v1` |
| Load summary | Engine/CLI result contract; archived report uses `att-load-summary/v1.3` |
| LOAD report detail files | `att-load-detail-manifest/v1`, `att-load-detail-reference/v1`, `att-load-detail-series/v1`, `att-load-detail-series/v2`, `att-load-workload-detail/v1` |
| Run manifest | `att-run/v2.1` |
| Minimal CLI hierarchical validation report | `att-validation/v1` |
| Package validation JSON | `att-validation/v2.1` |
| CI summary | `att-ci-summary/v2.1` |
| JUnit XML | `att-junit/v2.1` |

The Engine reads schema bytes from its classpath registry. Compiled validators are held in a bounded in-memory cache keyed by registry digest and schema resource. The embedded registry is authoritative at runtime; `ATT_HOME/schemas/` and Package-local `schemas/` cannot override it. Validation revisions bind Engine and registry identity, Package files, shared configuration files, and selected Environment. Workers verify the registry identity supplied by the Server before handling requests.

The SUT Metrics Data Source schema defines the registered connection. The Metric Source schema describes one acquisition and its declared output mapping; it does not register another Data Source, DBHelper, or Package Tool. The Server-private execution payload binds executable fetch/call data to the Job's approved execution definition and is never returned by an API or report. Offline report metadata and externally supplied verdict records use standalone Server contracts under `schemas/api/`, outside the Engine execution registry. See [Offline SUT metrics report](reference/results-reports-evidence.md).

## Appendix D — limits, security guarantees and advanced diagnostics

### Limits and defaults

Use the owning schema or configuration page for normative field defaults. Important architectural limits include:

- Load chooses exactly one workload model;
- arrival-rate overload policy is `drop`;
- Flow invocation receives a fresh Action scope and caller scope is restored on return;
- `DIAG` is framework-owned evidence and is not part of the expression tree;
- resource lifecycle state is not a public Context tree;
- unknown schema fields are rejected except documented extension locations such as root `x-*` where supported.

Operational numeric limits such as timeout ranges, evidence sample bounds, result limits and pool sizes remain defined by their schemas/descriptors so this appendix does not become a second source of truth.

### Collector projection and redaction guarantees

All failed collectors, including returned operation errors and thrown Tool exceptions, pass through the same public projection before publication or logging. The projection omits raw input, payload, argv, output, resolved command text, and the failed record's `result`, and does not guarantee `parserDiagnostic`. Native error/diagnostic maps retain only safe fields; each retained text field is limited to 1024 characters. `inputOmitted` and truncation flags identify omitted or bounded evidence. Free-form messages, stderr, per-instance errors, and cleanup warnings redact string, typed text, and array inputs within a fixed budget: 256 input nodes, 8192 token characters, and 1024 characters per token. Byte arrays are limited to 128 bytes (UTF-8, Base64, hexadecimal, and Java decimal renderings), other arrays to 64 elements, and char arrays to 1024 characters. Exceeding any budget, encountering a private token shorter than 4 characters, or encountering an unknown input type omits all free-form failure details with a safe marker, including upstream-truncated secret prefixes or head/tail echoes, and sets `inputRedactionLimited` and `failureDetailsOmitted`. Free-form fields longer than 1024 characters are also omitted and marked truncated; structured metadata remains available. Structured status, category, and resource identity are only length-bounded. SSH fan-out retains bounded metadata, errors, and stderr for up to 64 instances, prioritizing failures; `instanceCount` and `instancesTruncated` identify the total and omitted instances. When private tokens exist and an operation or instance record reports capture/detail truncation (such as `stderrTruncated` or `stderrArtifactTruncated`), that record's free-form failure details are also omitted to avoid leaking a split short secret's prefix/suffix. Without private tokens, bounded previews can remain available. Primitive arrays redact both the complete list rendering and individual elements within the same node/token budgets. Returned DB failures extract a safe summary from native `result.error` (`type`, bounded/redacted `message`, `sqlState`, `vendorCode`, and safe cancellation metadata), retain it as DB evidence `error`, and use it for the collector's `error`; rows, parameters, SQL text, and raw results are omitted. Failed command `stdout` can remain as separate diagnostic evidence under the same bounded/redacted/omission policy as `stderr`; it is not used as `error.message` or restored as the failed `result`. MQ resource nodes and error summaries retain `completionCode`, `reasonCode`, and bounded symbolic `reason`. Safe location metadata includes HTTP `method` and the `url` origin (scheme/host/port only), and MQ `queueManager`, `physicalInstance`, `host`, `port`, `channel`, and `transport`. HTTP evidence does not carry resolved request inputs, so failed collector URLs always omit path, query, fragment, and user info, with `urlPathOmitted` identifying omitted components; no raw input is added. URLs that cannot be safely parsed or exceed the budget are omitted with a safe marker.

### Advanced diagnostics

#### When does an unexpected exception get a stack trace?

Unexpected internal failures such as `NullPointerException`, `ClassCastException`, reflection lookup/access failures, other unexpected runtime exceptions, and non-domain `IllegalStateException` (including when nested in a wrapper cause) add a bounded `[ATT INTERNAL ERROR]` block to `case.log` with the execution phase and original cause chain. Validation `IllegalArgumentException`, recognized domain/transport failures, timeout/cancellation, assertion failures, and ordinary MQ no-message outcomes remain concise. A Throwable is written only once per Case log even when both a resource executor and its Action boundary see it. Resource-specific redactions (including environment-supplied SSH identity-file paths) are registered with that Case log and applied to subsequent log writes, so the outer Action diagnostic cannot expose text omitted from the sanitized stack. Stack output is capped at 180 lines/16 KB; configured secrets and sensitive key/value assignments are also redacted. Public Action evidence contains only the compact error type/phase, not the stack. The shared logging path covers Run, Debug, and reusable Tool/HTTP/MQ/DB execution.

#### Why does `att.bat` ask for Maven, or why does a `.sh` Tool fail on windows?

In a binary release, `att.bat` finds the versioned runtime JAR, such as `lib\att-local-4.3.1.jar`, and only requires Java 8+. Keep one `att-local-<version>.jar` in each `ATT_HOME/lib` so the launcher selects an unambiguous runtime. In a source tree it compiles with Maven when Maven is on `PATH`; without Maven, previously compiled `target\classes` must exist. Use `att.bat version` to confirm the launcher before validating the package.

The launcher makes ATT itself cross-platform; it cannot translate external tool executables. Configure a Windows-compatible `.bat`, `.cmd`, PowerShell script (with an explicit `powershell`/`pwsh` argv), or native executable instead of a POSIX-only `.sh` command. PATH validation follows Windows `PATHEXT`, so names such as `pwsh` can resolve `pwsh.exe`. Keep argument contracts and stdout output formats identical when maintaining platform variants.

#### Why did ATT say it will use mwiede/jsch, or why did Java SSH algorithm negotiation fail?

ATT uses the local `ssh` command when it is executable on `PATH`. If it is absent, ATT prints `local ssh command not found; ATT will use Java SSH library mwiede/jsch` and opens a Java exec channel instead. This is an automatic fallback, not a remote connectivity test.

The fallback is deliberately minimal: ATT includes `com.github.mwiede:jsch:2.28.2` but does not bundle Bouncy Castle. It requires a readable non-symbolic-link `~/.ssh/known_hosts` for strict host verification. It does not read `~/.ssh/config` or automatically use the OpenSSH agent; configure an unencrypted or otherwise non-interactively readable `identityFile`. Password and interactive passphrase prompts remain unsupported.

Algorithm availability depends on the Java runtime:

| Algorithm | Java fallback limitation | Preferred solution |
|---|---|---|
| `ssh-ed25519`, `ssh-ed448` | Require Java 15+, or a Bouncy Castle provider | Prefer local OpenSSH or Java 15+; otherwise have an administrator add approved `bcprov-jdk18on` to the runtime classpath |
| `curve25519-sha256`, `curve448-sha512` | Require Java 11+, or Bouncy Castle | Prefer local OpenSSH or Java 11+; otherwise use an approved Bouncy Castle provider |
| `chacha20-poly1305@openssh.com` | Requires Bouncy Castle on every Java version | Prefer local OpenSSH, enable an AES-GCM/CTR cipher on the server, or add an approved Bouncy Castle provider |
| RSA/SHA-1 `ssh-rsa` signatures | Disabled by default by mwiede/jsch | Update the server to RSA/SHA-2 (`rsa-sha2-256`/`rsa-sha2-512`) or another modern host/user-key algorithm; do not re-enable SHA-1 except as a reviewed temporary legacy measure |

When negotiation fails, first run the same connection with local `ssh -v` to identify the host-key, key-exchange, cipher, or user-key mismatch. Prefer upgrading Java or the server's algorithm set over weakening JSch defaults. The authoritative compatibility notes and configurable `jsch.kex`, `jsch.server_host_key`, `jsch.cipher`, and `jsch.mac` system properties are documented in the [mwiede/jsch README](https://github.com/mwiede/jsch). ATT does not change those secure defaults.
