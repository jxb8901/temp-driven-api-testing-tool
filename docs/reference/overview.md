# Overview and product model

ATT separates test intent from integration mechanics. Test data is versioned in workbook/sidecar/snapshot form; Templates and Flows define reusable behavior; Resources connect that behavior to external systems.

## Product model

```text
Testcase
  `-- ordered Stage
        `-- Template
              |-- Action
              |     |-- file-content expression / assert / log / assign
              |     |-- Tool
              |     `-- DB
              `-- Flow -> ordered Actions
```

A **Testcase** is one authored, normalized workbook row. A **Case execution** is one Run execution of that Testcase; its full Case ID identifies the Testcase and is reused in execution results and evidence. Use *Testcase* when discussing workbook data and authoring, and *Case execution* when discussing runtime status, logs, reports, and artifacts. A **Stage** selects a Template and contributes stage-private data. A **Template** is the executable scenario boundary. A **Flow** is reusable Template logic with an isolated Action scope. An **Action** is one ordered unit of work. A **Resource** is a configured Tool, DBHelper, MQHelper, HTTPHelper or SSHHelper used by Actions or permitted expression calls.

## Execution modes are peers

Run, Debug and Load adapt different inputs into the same execution-neutral Context and reusable components:

| Mode | Primary input | Reuses |
|---|---|---|
| Run | workbook Testcases and Stage selectors | Templates, Flows, Tools, DB/MQ/HTTP/SSH |
| Debug | `att-debug/v1.2` sidecar or `--input` | one Template, Flow or Tool target |
| Load | `att-load/v1.6` scenario | one or more Template, Flow or Tool workloads repeatedly |

Reusable Templates/Flows depend on `EXEC.INPUT`, `EXEC.VARS`, `EXEC.ACTIONS`, `META`, and Action-local `output`. Execution mode and scheduler identity are framework diagnostics in retained evidence, not expression data.

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

A normal package contains `config/`, `testcase/`, `templates/`, `tools/`, `schemas/` and generated `output/`. Paths and identifiers are validated before execution. Credentials belong in environment variables or external secret handling, not committed YAML.

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
