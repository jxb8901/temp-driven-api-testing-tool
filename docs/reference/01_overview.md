## 01 Overview and Concepts

ATT separates test intent from integration mechanics. Test data is versioned in workbook/sidecar/snapshot form; Templates and Flows define reusable behavior; Resources connect that behavior to external systems.

### Product model

```text
Testcase
  `-- ordered Stage
        `-- Template
              |-- Action
              |     |-- render / assert / log / assign
              |     |-- Tool
              |     `-- DB
              `-- Flow -> ordered Actions
```

A **Testcase** is one normalized workbook row. A **Stage** selects a Template and contributes stage-private data. A **Template** is the executable scenario boundary. A **Flow** is reusable Template logic with an isolated Action scope. An **Action** is one ordered unit of work. A **Resource** is a configured Tool, DBHelper or MQHelper used by Actions or permitted expression calls.

### Execution modes are peers

Run, Debug and Load adapt different inputs into the same execution-neutral Context and reusable components:

| Mode | Primary input | Reuses |
|---|---|---|
| Run | workbook Testcases and Stage selectors | Templates, Flows, Tools, DB/MQ |
| Debug | `att-debug/v1.0` sidecar or `--input` | one Template, Flow or Tool target |
| Load | `att-load/v1.0` scenario | one Template, Flow or Tool target repeatedly |

Mode-specific identity is carried by `EXEC.MODE` and, for load only, `EXEC.LOAD`. Reusable Templates/Flows should normally depend on `EXEC.INPUT`, `EXEC.VARS`, `EXEC.ACTIONS`, `META`, and Action-local `output`, not on a second mode-specific runtime tree.

### Resources are peers

Tool, DBHelper and MQHelper are independent resource types. They differ in configuration and lifecycle but publish operation data into one common Action envelope. Public expressions should consume Action results/evidence rather than resource-internal connection/process state.

```text
Tool ----\
DBHelper --+--> operation result/evidence --> Action output
MQHelper -/
```

### Package boundaries

A normal package contains `config/`, `testcase/`, `templates/`, `tools/`, `schemas/` and generated `output/`. Paths and identifiers are validated before execution. Credentials belong in environment variables or external secret handling, not committed YAML.

For a guided package build, use `docs/quick-start.md`. The rest of this manual is normative lookup documentation.
