# ATT Documentation Architecture

Status: Accepted architecture baseline
Date: 2026-09-23
Scope: Current ATT documentation structure and future Reference Manual evolution
Related issues: #40, #41, #42, #43

## 1. Purpose

ATT has evolved from a primarily workbook/template/tool test runner into a broader testing framework with multiple execution modes, multiple resource types, a unified runtime Context model, common Action result/evidence semantics, environment profiles, standalone debug, and load execution.

The documentation structure must reflect the current product model rather than the chronological order in which features were added.

This document defines the long-term documentation information architecture. It is the architecture baseline for:

- #41 — modular Reference Manual sources and build pipeline;
- #42 — content migration/rewrite;
- #43 — documentation consistency and release-gate validation.

This document defines structure, ownership, navigation, naming, versioning, language parity, and migration rules. It does not itself perform the bulk content rewrite.

## 2. Documentation principles

### 2.1 Organize by user mental model, not release history

Current documentation must explain ATT as one coherent product. The primary organization must not be based on when DBHelper, Flow, debug, MQHelper, load, environment profiles, or other features were introduced.

Release chronology belongs in `CHANGELOG.md`, migration notes, and `docs/history/`.

### 2.2 Separate tutorial, normative reference, and implementation design

ATT documentation has three distinct reading modes:

1. learn ATT by building something;
2. look up supported public behavior;
3. understand internal implementation architecture.

These must not be mixed in one document.

### 2.3 Treat execution modes as peers

The public model is:

```text
Execution Modes
├── Run
├── Debug
└── Load
```

Debug and load are not auxiliary CLI features. They are first-class execution adapters over shared runtime semantics.

### 2.4 Treat Tool, DB, and MQ as peer integration/resource concepts

The public model is:

```text
Tool ─┐
DB   ─┼──> common Action result/evidence contract
MQ   ─┘
```

Tool is not the parent concept under which DB/MQ must be hidden. DBHelper and MQHelper are first-class resource/integration types.

### 2.5 Runtime Context and Action lifecycle are central concepts

The canonical runtime model must be documented once and centrally:

```text
EXEC
├── INPUT
├── VARS
├── ACTIONS
└── LOAD        # load mode only

META
├── PROJECT
├── SOURCE
├── TARGET
├── TEMPLATE
├── FLOW
├── TOOL
├── DBHELPER
└── MQHELPER

output            # Action-local
```

Scope, publication, attempts, evidence, Flow isolation, and compatibility aliases are part of this runtime model rather than scattered feature-specific facts.

### 2.6 Current documentation is versioned content, not versioned filenames

Current documentation source filenames should not depend on `_V3`, `_V4`, or another major-version suffix.

Preferred current paths:

```text
docs/quick-start.md
docs/reference/...
docs/reference.zh/...
docs/system-design/...
docs/documentation-architecture.md
```

Historical version-specific material belongs under `docs/history/`.

Product version appears in generated titles/metadata and release artifacts, not in the canonical source path.

## 3. Document ownership model

### 3.1 README

**Purpose:** 5-minute orientation and navigation entry point.

README owns:

- what ATT is;
- a compact architecture/mental-model diagram;
- supported execution modes at a glance;
- supported resource/integration types at a glance;
- minimal install/run commands;
- links to Quick Start, Reference, System Design, examples, and releases.

README does **not** own:

- full field-by-field configuration;
- complete CLI tables;
- detailed load semantics;
- detailed expression grammar;
- implementation architecture.

### 3.2 Quick Start

**Purpose:** 30–60 minute guided tutorial.

Quick Start owns one coherent happy-path package that teaches:

- package layout;
- workbook/sidecar/snapshot;
- one Template;
- representative Actions;
- at least one Tool/resource integration;
- validate/run/report;
- short introductions to debug/load/environment usage with links to Reference.

Quick Start may use copyable examples, but it is not the normative field reference.

### 3.3 Reference Manual

**Purpose:** authoritative end-user contract and lookup reference.

Reference owns:

- public schemas and configuration semantics;
- authoring contracts;
- Context/expression model;
- Action semantics;
- execution-mode behavior;
- Tool/DB/MQ public resource behavior;
- timeout/retry/assertion/execution-control behavior;
- outputs, evidence, reports, diagnostics, exit codes;
- compatibility and public limits.

Reference does **not** own:

- a second end-to-end tutorial;
- internal class structure;
- scheduler implementation details beyond observable behavior;
- thread-local/internal adapter implementation;
- roadmap/release chronology.

### 3.4 System Design

**Purpose:** maintainer/internal implementation architecture.

System Design owns:

- major Java components and ownership boundaries;
- execution adapters and internal pipelines;
- scheduler implementation;
- resource ownership/pooling internals;
- cache internals;
- thread/concurrency design;
- internal compatibility adapters;
- implementation invariants needed by maintainers.

System Design may reference public contracts in Reference but must not redefine them inconsistently.

### 3.5 History

**Purpose:** archived and superseded material.

History owns:

- retired version-specific designs;
- old schema-era manuals where preservation is useful;
- migration records;
- superseded architecture decisions.

History is never the primary link target for current user guidance.

### 3.6 CHANGELOG

**Purpose:** release chronology.

CHANGELOG describes what changed by release. It must not become a substitute Reference Manual.

## 4. Approved Reference Manual information architecture

The Reference Manual shall use the following major structure.

### 1. Overview and Concepts

Owns:

- ATT purpose and boundaries;
- package model;
- Testcase → Stage → Template → Action → Resource;
- execution-neutral reusable assets;
- relationship among Run, Debug, and Load;
- relationship among Tool, DB, and MQ.

### 2. Test Authoring

Owns:

- Workbook;
- Sidecar;
- Snapshot;
- Case ID / tags / stage mapping;
- Template;
- Flow;
- Action types;
- authoring-time validation constraints.

### 3. Runtime and Context Model

Owns:

- `EXEC`;
- `META`;
- Action-local `output`;
- `EXEC.INPUT`;
- `EXEC.VARS`;
- `EXEC.ACTIONS`;
- conditional `EXEC.LOAD`;
- scope and lifetime;
- Flow Action-scope isolation/restoration;
- Action publication;
- final vs per-attempt result/evidence;
- compatibility aliases and migration rules;
- internal resource state vs public Context.

This chapter is the canonical definition of the public Context model. Other chapters link here rather than duplicating it.

### 4. Execution Modes

Owns three peer sections:

```text
4.1 Run
4.2 Standalone Debug
4.3 Load
```

#### 4.1 Run

- workbook-driven execution;
- selection;
- Case/Stage lifecycle;
- output layout;
- normal run status/exit semantics.

#### 4.2 Standalone Debug

- Template / Flow / Tool targets;
- `att-debug/v1.0`;
- default `debug.yaml` lookup;
- explicit `--input`;
- Context adapter behavior;
- environment selection;
- outputs/artifacts;
- diagnostics/exit codes;
- differences from normal Testcase execution.

#### 4.3 Load

- `att-load/v1.0` scenario;
- targets;
- closed-VU model;
- arrival-rate model;
- phases;
- duration, users/rate overrides, think time;
- overload/drop semantics;
- runtime resource ownership/pooling;
- `EXEC.LOAD`;
- metrics and thresholds;
- summaries/reports;
- performance profile;
- exit codes.

Primary load explanation must be coherent in this section. CLI, Context, and report chapters provide reference details and link back here.

### 5. Resources and Integrations

Owns:

```text
5.1 Tool
    5.1.1 command-backed
    5.1.2 call-backed
5.2 DBHelper
5.3 MQHelper
5.4 Common operation-result/evidence contract
```

#### Tool

- descriptor/group model;
- arguments;
- process execution;
- SSH where applicable;
- call-backed capabilities;
- saveAs/output/evidence.

#### DBHelper

- descriptor and connection model;
- query/update;
- transaction lifecycle;
- statement limits/timeouts;
- expression calls;
- Action result/evidence;
- driver/runtime dependencies;
- resource finalization compatibility behavior where still public.

#### MQHelper

- descriptor and connection model;
- send/receive/request;
- payload/correlation behavior;
- timeout/evidence;
- IBM MQ runtime dependency;
- connection/pool behavior where observable.

#### Common contract

Defines the convergence of Tool/DB/MQ operation data into the Action envelope and distinguishes operation result, evidence, diagnostics, attempts, and internal resource state.

### 6. Environment and Test Data

Owns:

- `--env` selection;
- base config vs environment binding;
- precedence/default behavior;
- typed resource overlay semantics;
- stable DB/MQ logical IDs;
- topology vs secrets;
- promotion across SIT/UAT/PREPROD;
- when separate top-level configs remain preferable;
- future environment-bound logical test-data fixtures (#38) when implemented.

Environment selection must not be presented as Action branching.

### 7. Expressions and Built-ins

Owns:

- `${...}` Context interpolation;
- `#{...}` typed expressions/calls;
- operators;
- optional paths;
- built-in catalog;
- Tool/DB/MQ expression-access rules;
- type preservation;
- validation and error behavior.

### 8. Reliability and Execution Control

Owns cross-cutting Action behavior:

- assertion;
- `runWhen`;
- `onFailure`;
- timeout;
- retry;
- retry reasons;
- per-attempt publication;
- evidence collectors;
- transaction/resource lifecycle implications;
- status classification: PASS / FAIL / ERROR / INVALID / SKIPPED.

Feature-specific restrictions remain documented here and cross-linked from Action/resource sections. Future #39 DB Action timeout/retry belongs here without restructuring the manual.

### 9. Configuration Reference

Owns field-level configuration reference only:

- global config;
- templates/testcase/report/execution sections;
- environment profiles;
- tool groups;
- DBHelper descriptors;
- MQHelper descriptors;
- schema rules and defaults;
- path/secret rules.

Conceptual explanation belongs in earlier chapters; this chapter is lookup-oriented.

### 10. CLI Reference

Owns:

- commands;
- command-specific options;
- selection options;
- environment option;
- output/profile options;
- exit codes;
- option conflicts and defaults.

CLI Reference must not be the primary conceptual explanation of debug or load.

### 11. Results, Reports, and Evidence

Owns:

- Action envelope;
- attempt history;
- operation evidence;
- Case log;
- run directory layout;
- workbook result behavior;
- HTML report;
- JUnit/CI JSON;
- load summary/report;
- debug result artifacts;
- performance profile;
- retention/generated artifacts where public.

### 12. Validation and Diagnostics

Owns:

- validation phases;
- package vs selected validation;
- schema/config/template/expression validation;
- diagnostic model/codes;
- common failure investigation;
- troubleshooting;
- distinction among invalid input, assertion failure, timeout, and infrastructure/runtime error.

### 13. CI, Packaging, and Operations

Owns:

- build/package;
- `docs` and `clean` operational behavior;
- Java requirements;
- JDBC driver packaging;
- IBM MQ client packaging;
- CI pipelines;
- environment matrix/promotion;
- offline deployment concerns;
- release artifact documentation entry points.

### 14. Appendices

Owns stable lookup tables:

- schema/version matrix;
- compatibility matrix;
- deprecated aliases/features;
- migration notes;
- limits/defaults;
- glossary if needed.

## 5. Approved modular source layout

#41 may refine exact filenames, but it must preserve topic-level ownership and EN/ZH parity.

Baseline:

```text
docs/
├── documentation-architecture.md
├── quick-start.md
├── reference/
│   ├── 01_overview.md
│   ├── 02_test_authoring.md
│   ├── 03_runtime_context.md
│   ├── 04_execution_modes/
│   │   ├── run.md
│   │   ├── debug.md
│   │   └── load.md
│   ├── 05_resources/
│   │   ├── tools.md
│   │   ├── dbhelper.md
│   │   ├── mqhelper.md
│   │   └── operation_result.md
│   ├── 06_environment_testdata.md
│   ├── 07_expressions.md
│   ├── 08_reliability_execution_control.md
│   ├── 09_configuration.md
│   ├── 10_cli.md
│   ├── 11_results_reports_evidence.md
│   ├── 12_validation_diagnostics.md
│   ├── 13_ci_packaging_operations.md
│   └── appendices/
│       ├── schema_matrix.md
│       ├── compatibility.md
│       ├── migrations.md
│       └── limits_defaults.md
├── reference.zh/
│   └── mirror of `reference/`
├── system-design/
│   └── maintainer/internal design modules
├── generated/
│   ├── reference.md
│   ├── reference.html
│   ├── reference.zh.md
│   └── reference.zh.html
└── history/
```

Generated output paths may be adjusted by #41, but source modules must remain distinct from generated combined artifacts.

## 6. EN/ZH ownership and generation strategy

### 6.1 Both languages are normative maintained content

English and Chinese are both maintained documentation. Chinese is not treated as an automatically generated non-authoritative translation.

### 6.2 Structural parity is mandatory

`docs/reference/` and `docs/reference.zh/` must have matching logical module structure and major chapter order.

A module may differ in paragraph count or examples where language clarity requires it, but public semantics must match.

### 6.3 Generation is structural, not translational

The documentation build combines ordered source modules into complete EN/ZH manuals and generates HTML. It does not machine-translate one language into the other.

### 6.4 Product version is injected from one source

The generated manual title/version must come from one authoritative ATT version source rather than being manually repeated across many source modules.

### 6.5 Parity validation belongs in #43

CI/release checks should detect at minimum:

- missing language counterpart modules;
- chapter-order mismatch;
- missing generated artifacts;
- stale product version metadata.

## 7. Naming and versioning policy

### 7.1 Current source filenames

Do not put product major version in canonical current source paths.

Preferred:

```text
docs/quick-start.md
docs/reference/...
docs/system-design/...
```

Avoid new canonical names such as:

```text
Quick_Start_V4.md
Reference_Manual_V4.md
System_Design_V4.md
```

### 7.2 Generated documents

Generated release artifacts may include human-readable product version in title/content. Filename version suffixes are optional release-packaging concerns, not source ownership identifiers.

### 7.3 Historical documents

Superseded version-specific documents may keep their original filenames under `docs/history/`.

### 7.4 Stable links

README and current docs should link to stable current entry points. Historical paths must not be the main navigation route.

## 8. Content placement decision rules

Use these questions in order.

### Does the user need this to complete a first successful package?

If yes and it is part of the happy path, put the teaching sequence in **Quick Start**.

### Is this supported public behavior users may need to look up?

If yes, put the authoritative contract in **Reference**.

### Is this about why/how the implementation works internally?

If yes, put it in **System Design**.

### Is this mainly about what changed in a release?

If yes, put it in **CHANGELOG** or migration/history material.

### Is this obsolete but worth preserving?

If yes, move it to **History**.

## 9. Migration classification

#42 must classify current content using exactly these disposition categories:

```text
KEEP     correct normative contract already in the right conceptual role
MOVE     correct content that belongs in a different module
MERGE    duplicated material that should have one canonical home
DELETE   obsolete or redundant tutorial/reference duplication
REWRITE  stale descriptions that conflict with current architecture
ADD      missing first-class coverage
```

No major current Reference section should disappear without an explicit disposition.

## 10. Migration map from current 09 manual

The following map is the approved baseline for #42.

| Current `09_Reference_Manual_V3` area | Disposition | New owner |
|---|---|---|
| 01 Introduction | MERGE / REWRITE | Reference 1 Overview and Concepts; short orientation remains in README |
| 01 package layout / core concepts | MOVE / MERGE | Reference 1 and 2 |
| historical “What V3.x guarantees” style material | REWRITE | Current guarantees moved to relevant chapters; release chronology to CHANGELOG/history |
| 02 Quick Start | DELETE / MERGE | Standalone `docs/quick-start.md`; retain only concise Reference examples where contract clarity requires them |
| 03 User Guide | SPLIT | Reference 2, 4, 5, 11 depending topic |
| 3.1 Workbook | MOVE | Reference 2 Test Authoring |
| 3.2 Template | MOVE / REWRITE | Reference 2 Test Authoring |
| Flow authoring embedded in introduction/template material | MOVE / REWRITE | Reference 2 plus scope semantics in Reference 3 |
| 3.3 Tool | SPLIT / REWRITE | Reference 5.1 Tool; common evidence to 5.4/11; execution control to 8 |
| DBHelper content currently embedded under config/tool-era material | MOVE / REWRITE | Reference 5.2 DBHelper; field tables to 9; lifecycle cross-reference to 8 |
| MQHelper content currently embedded under configuration | MOVE / REWRITE | Reference 5.3 MQHelper; descriptor fields to 9 |
| 3.4 Running Tests | SPLIT | Reference 4.1 Run + 10 CLI + 12 Validation |
| 3.5 Reports | MOVE / MERGE | Reference 11 Results, Reports, and Evidence |
| 04 Cookbook | DELETE / MERGE | Reusable learning scenarios to Quick Start/examples; compact contract examples remain near relevant Reference sections |
| 05 CLI Reference | KEEP / REORGANIZE | Reference 10 CLI Reference |
| Standalone debug material currently near CLI/config sections | MOVE / REWRITE | Reference 4.2 Standalone Debug; CLI syntax cross-reference in 10 |
| Load CLI/scenario/runtime material spread across manual | MOVE / MERGE / REWRITE | Reference 4.3 Load; CLI fields in 10; `EXEC.LOAD` definition in 3; reports in 11 |
| 06 Configuration Reference | KEEP / SPLIT | Reference 9 Configuration; conceptual environment/resource material moves to 5/6 |
| Environment profiles | MOVE / REWRITE | Reference 6 Environment and Test Data; field-level config remains in 9 |
| secrets/topology guidance | MOVE / MERGE | Reference 6 plus field-specific security notes in 9 |
| 07 Expression Reference | KEEP / REWRITE | Reference 7 Expressions and Built-ins |
| EXEC/META/output material currently spread across expression/load/architecture sections | MOVE / MERGE / REWRITE | Reference 3 Runtime and Context Model |
| retry/timeout/evidence-collector material currently resource/tool-specific | MOVE / MERGE | Reference 8 Reliability and Execution Control; resource-specific restrictions cross-link from 5 |
| 08 Report Reference | KEEP / MERGE | Reference 11 Results, Reports, and Evidence |
| debug outputs | MOVE | Reference 4.2 + 11 |
| load summary/report/performance output | MOVE / MERGE | Reference 4.3 + 11 |
| 09 Troubleshooting | MOVE / REWRITE | Reference 12 Validation and Diagnostics |
| diagnostic codes/validation behavior spread across sections | MERGE | Reference 12 |
| 10 Architecture for Maintainers | MOVE | `docs/system-design/`; only supported observable contracts remain in Reference |
| internal scheduler/thread/resource-owner details | MOVE | System Design |
| compatibility aliases/deprecations scattered throughout | MERGE | Reference 3 where semantic + Appendix compatibility/deprecations |
| schema/version compatibility scattered throughout | MERGE | Appendix schema/version matrix + Configuration/authoring cross-references |
| build/docs/clean/package operational material | MOVE / MERGE | Reference 13 CI, Packaging, and Operations |

## 11. New-content gaps to fill during #42

The migration must add coherent first-class coverage where current documentation is fragmented.

Required ADD areas:

1. shared execution-mode model explaining Run/Debug/Load as peer adapters;
2. standalone Debug as a complete mode, not only CLI syntax;
3. Load as one coherent end-to-end section;
4. DBHelper and MQHelper as first-class peer resources;
5. common Tool/DB/MQ operation result/evidence model;
6. unified Runtime/Context chapter centered on `EXEC`, `META`, and `output`;
7. environment profiles as a promotion/resource-binding concept;
8. clear boundary between Context data and internal resource lifecycle state;
9. reliability/execution-control chapter that can absorb #39 cleanly;
10. Environment/Test Data chapter designed to absorb #38 without restructuring.

## 12. Cross-document duplication rules

### README vs Quick Start

README may show minimal commands, but full setup/tutorial sequence lives only in Quick Start.

### Quick Start vs Reference

Quick Start teaches sequence. Reference defines contract.

The same complete example should not be maintained independently in both places unless it is generated/shared or intentionally reduced to a small contract snippet.

### Reference vs System Design

Reference defines observable supported behavior. System Design explains implementation.

If an implementation detail is required to understand a public guarantee, Reference states the guarantee without binding users to unnecessary internal class names/structures.

### Reference vs CHANGELOG

Reference describes current behavior. CHANGELOG describes deltas between versions.

## 13. Navigation policy

Primary navigation should be:

```text
README
├── Quick Start
├── Reference Manual
├── System Design
├── Examples
└── History / migration when needed
```

Generated single-page Reference HTML remains a supported distribution format even though source content is modular.

## 14. Documentation acceptance criteria

The architecture is considered successfully implemented when:

- README, Quick Start, Reference, System Design, History, and CHANGELOG have distinct documented roles;
- Reference uses the approved major chapter model;
- Run/Debug/Load are peer execution modes;
- Tool/DB/MQ are peer resource/integration concepts;
- Runtime/Context has a dedicated central chapter;
- environment/profile guidance has one clear conceptual home;
- reliability/execution-control has one clear cross-cutting home;
- maintainer architecture is outside the end-user Reference;
- current source filenames no longer require major-version suffixes;
- Reference sources are modular and EN/ZH structures mirror one another;
- generated EN/ZH combined manuals remain available;
- old 09 content has an explicit migration disposition;
- future #38 and #39 features can be added without changing the top-level information architecture;
- #43 can validate structural parity, generation freshness, version consistency, links, schemas, and critical examples.

## 15. Implementation sequence

The approved order is:

```text
#40  documentation architecture baseline
  ↓
#41  modular source layout + generation pipeline
  ↓
#42  EN/ZH migration and rewrite
  ↓
#43  consistency/generation/release gates
```

#43 may be implemented incrementally while #41/#42 progress, but bulk migration must follow this architecture baseline.

## 16. Decision summary

The current monolithic `09_Reference_Manual_V3` structure is retired as the long-term source architecture.

ATT documentation will move to:

- stable-role documents;
- modular Reference sources;
- first-class Run/Debug/Load organization;
- first-class Tool/DB/MQ organization;
- a central Runtime/Context model;
- explicit Environment/Test Data and Reliability/Execution Control extension points;
- mirrored EN/ZH structures;
- generated combined manuals;
- current filenames without major-version suffixes;
- historical/version-specific material isolated under history/migration documentation.

This architecture is the normative documentation-design baseline for #41, #42, and #43.
