# ATT Documentation

This is the canonical entry point for the current ATT documentation. Choose a
task below; the versioned files at the top of `docs/` are compatibility paths,
not alternate current manuals.

## Start here

| I am... | Start with |
|---|---|
| New to ATT | [Quick Start (English)](quick-start.md) |
| 使用中文 | [快速入門（繁體中文）](quick-start.zh.md) |
| Looking up a supported field, CLI option, or schema | [Reference Manual (English)](generated/reference.html) · [modular sources](reference/README.md) |
| 查閱欄位、CLI 選項或 schema | [Reference Manual（繁體中文）](generated/reference.zh.html) · [模組化來源](reference.zh/README.md) |

Minimal offline workflow:

```sh
./att.sh snapshot --suite testcase/quick_start.xlsx
./att.sh validate --package
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
```

## Common tasks

| I want to... | Go to |
|---|---|
| Create my first test | [Quick Start](quick-start.md) |
| Understand workbook, sidecar, or snapshot | [Test Authoring](reference/02_test_authoring.md) |
| Write a Template or Flow | [Test Authoring](reference/02_test_authoring.md) |
| Call an external script or program | [Tool](reference/05_resources/tools.md) |
| Query or update a database | [DBHelper](reference/05_resources/dbhelper.md) |
| Send, receive, or request MQ messages | [MQHelper](reference/05_resources/mqhelper.md) |
| Debug one Template, Flow, or Tool | [Debug](reference/04_execution_modes/debug.md) |
| Run a load test | [Load](reference/04_execution_modes/load.md) |
| Switch SIT/UAT resources | [Environment and Test Data](reference/06_environment_testdata.md) |
| Understand `${...}` and `#{...}` | [Expressions](reference/07_expressions.md) |
| Diagnose FAIL, ERROR, or INVALID | [Validation and Diagnostics](reference/12_validation_diagnostics.md) |
| Integrate ATT with CI or package a release | [CI, Packaging, and Operations](reference/13_ci_packaging_operations.md) |

## Documentation types and language

- **Quick Start** is the tutorial for a first successful run.
- **Reference** is the supported public contract. Use the [English](generated/reference.html) or [繁體中文](generated/reference.zh.html) generated manual, or its [English](reference/) and [繁體中文](reference.zh/) source modules.
- **System Design** explains maintainer-facing implementation architecture: [runtime execution](system-design/runtime-execution.md).
- **CHANGELOG** records release chronology: [CHANGELOG.md](../CHANGELOG.md).
- **History** preserves superseded or completed version-specific material: [history/README.md](history/README.md).

English and Chinese are explicit maintained entry points. The generated
Reference manuals are assembled from the matching modular sources; this page
only routes users and does not duplicate their normative field-level content.

## Current tree

```text
docs/
├── README.md                 # this landing page
├── quick-start.md            # current English tutorial
├── quick-start.zh.md         # current 繁體中文 tutorial
├── reference/                # current English Reference sources
├── reference.zh/             # current 繁體中文 Reference sources
├── generated/                # generated combined Reference artifacts
├── system-design/            # current maintainer design
└── history/                  # superseded/version-specific material
```

Build and migration notes remain available for maintainers in
[`documentation-architecture.md`](documentation-architecture.md),
[`reference-migration-map.md`](reference-migration-map.md), and the Reference
source READMEs. They are intentionally below the end-user task navigation.

## Compatibility paths

These retained top-level versioned paths exist only to keep old links working:

| Compatibility path | Classification | Current destination |
|---|---|---|
| [`02_System_Design_V3.md`](02_System_Design_V3.md) | lightweight compatibility stub | [System Design](system-design/) |
| [`08_Quick_Start_V3.md`](08_Quick_Start_V3.md) | lightweight compatibility stub | [Quick Start](quick-start.md) |
| `09_Reference_Manual_V3*` | generated compatibility artifacts | [generated Reference](generated/) |

Do not use these paths as primary navigation or edit generated compatibility
manuals directly. Superseded numbered documents are classified in
[History](history/README.md).
