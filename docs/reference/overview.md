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

## RUN and LOAD

RUN selects Resolved Cases by qualified ID, tag, or an implicit Suite. Debug runs a direct Flow or Tool target. LOAD repeats Flow or Tool workloads; Native Case LOAD targets remain deferred. See [Unified RUN and schedules](execution-modes/unified-run.md).

| Mode | Input | Result |
|---|---|---|
| RUN | Definition and selected Resolved Cases | One Case Execution per selected Case |
| Debug | Direct Flow or Tool target and explicit inputs | One isolated diagnostic execution |
| LOAD | Validated scenario with Flow or Tool workloads | Repeated iterations and workload evidence |

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

Each Package owns Flows, Test Case Definitions, package-local Tools, Testdata, and generated output. All Packages use shared runtime configuration under `ATT_HOME/config/`; trusted schemas are bundled with ATT. A Package does not need its own `config/` or `schemas/` directory. Paths and identifiers are validated before execution. Credentials belong in protected runtime inputs or secret handling.

For a guided example, use [Quick Start](../quick-start.md). For an existing Package authored with an earlier model, use the [Legacy documentation](../legacy/README.md). The rest of this manual is normative lookup documentation.

## How to use this manual

| Goal | Go to |
|---|---|
| Build the first ATT package | [Quick Start](../quick-start.md) |
| Understand the core ATT model | [Product model](overview.md), [Test Authoring](test-authoring.md), [Actions](actions.md), [Runtime and Context](runtime-context.md), and [Run mode](execution-modes/run.md) |
| Configure DB/MQ/HTTP/SSH | [Resources](resources/overview.md) |
| Find a CLI option | [CLI Reference](cli.md) |
| Diagnose a failure | [Validation and Troubleshooting](validation-diagnostics.md) |
| Maintain an existing Package | [Legacy documentation](../legacy/README.md) |

Reference defines the public contract; README, Quick Start and examples explain that contract for narrower tasks. Each contract has one semantic owner; other Reference pages summarize and link to that owner.
