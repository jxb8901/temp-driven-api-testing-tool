# Overview and product model

ATT separates reusable execution logic from business data. The current Case path binds a Flow and a Data Source in a Test Case Definition, resolves that Definition to selected Resolved Cases, and runs each Case as a Flow of ordered Actions and Resources.

## Product model

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

## Supported legacy compatibility

Workbook, Sidecar, Snapshot, Stage, and Template remain a supported runtime. This section does not remove Template from ATT. The diagram below is the legacy compatibility path, not the current Case model.

```text
Workbook row
  `-- ordered Stage
        `-- Template
              |-- Action
              |     |-- file-content expression / assert / log / assign / set
              |     |-- Tool
              |     `-- DB
              `-- Flow -> ordered Actions
```

A legacy **Testcase** is one authored, normalized workbook row. A **Stage** selects a Template and contributes stage-private data. A **Template** is the executable scenario boundary on this path and may call a Flow. Legacy RUN, Debug, and LOAD continue to execute that path.

## RUN and LOAD execution models

RUN executes one selected functional target or Case selection. Native RUN selects Resolved Cases by qualified ID, tag, or an implicit Suite. Legacy RUN selects workbook Testcases and Stage selectors. Debug diagnoses one Template, Flow, or Tool. LOAD repeats Template, Flow, or Tool workloads. See [Unified RUN and schedules](execution-modes/unified-run.md).

| Mode | Primary input | Reuses |
|---|---|---|
| Run | Native: Resolved Case IDs, tags, or an implicit Suite. Legacy: workbook Testcases and Stage selectors | Flows and Actions; legacy RUN also uses Templates |
| Debug | `att-debug/v1.2` sidecar or `--input` | one Template, Flow or Tool target |
| Load | `att-load/v1.9` scenario | one or more Template, Flow or Tool workloads repeatedly |

Reusable Flows depend on `EXEC.INPUT`, `EXEC.VARS`, `EXEC.ACTIONS`, `META`, and Action-local `output`. Execution mode and scheduler identity are framework diagnostics in retained evidence, not expression data.

## Resources are peers

Tool, DBHelper, MQHelper, HTTPHelper and SSHHelper are independent resource types. They differ in configuration and lifecycle, while Actions publish native typed results through `output.result` and keep optional presentation evidence separate. Public expressions should consume Action results/evidence rather than resource-internal connection/process state. SSH resource operations use the common `ssh.<helperId>.<operation>` form inside a normal `type: tool` Action.

```text
Tool ------\
DBHelper ---+
MQHelper ---+--> typed operation result --> Action output
HTTPHelper -+
SSHHelper --/
```

## Package boundaries

Each Package contains its business resources, such as `testcase/`, `templates/`, package-local `tools/`, `testdata/`, and generated `output/`. All Packages use the shared runtime configuration under `ATT_HOME/config/`; trusted schemas are bundled with ATT. A Package does not need its own `config/` or `schemas/` directory. Paths and identifiers are validated before execution. Credentials belong in environment variables or external secret handling, not committed YAML.

For a guided package build, use [Quick Start](../quick-start.md). The rest of this manual is normative lookup documentation.

## How to use this manual

| Goal | Go to |
|---|---|
| Build the first ATT package | [Quick Start](../quick-start.md) |
| Understand the core ATT model | [Product model](overview.md), [Test Authoring](test-authoring.md), [Actions](actions.md), [Runtime and Context](runtime-context.md), and [Run mode](execution-modes/run.md) |
| Configure DB/MQ/HTTP/SSH | [Resources](resources/overview.md) |
| Find a CLI option | [CLI Reference](cli.md) |
| Diagnose a failure | [Validation and Troubleshooting](validation-diagnostics.md) |
| Upgrade an older package | [Migration Notes](appendices/migrations.md) |

Reference defines the public contract; README, Quick Start and examples explain that contract for narrower tasks. Each contract has one semantic owner; other Reference pages summarize and link to that owner.
