#!/usr/bin/env python3
from pathlib import Path
from textwrap import dedent
import re

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"
EN = DOCS / "reference"
ZH = DOCS / "reference.zh"

TRANSITIONAL = (
    "<!-- Transitional placement produced by issue #41. #42 owns semantic reorganization. -->",
)


def write(path: Path, text: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(dedent(text).strip() + "\n", encoding="utf-8")


def pair(rel: str, en: str, zh: str):
    write(EN / rel, en)
    write(ZH / rel, zh)


def remove_transitional(text: str) -> str:
    for marker in TRANSITIONAL:
        text = text.replace(marker + "\n\n", "").replace(marker, "")
    text = text.replace("This module establishes the target information-architecture location. The detailed normative material is preserved in the migrated legacy sections in this transitional #41 structure and will be moved here by #42 without changing runtime behavior.\n", "")
    text = text.replace("本模組先固定新的資訊架構位置。現有規範內容在 #41 過渡結構中完整保留於已遷移的舊章節，#42 會在不改變 runtime 行為的前提下把相關內容移入本章。\n", "")
    return text


def promote_after_wrapper(text: str, legacy_no: str) -> str:
    text = remove_transitional(text)
    text = re.sub(r"(?m)^### " + re.escape(legacy_no) + r"\s+.*\n\n?", "", text, count=1)
    lines = []
    for line in text.splitlines():
        if line.startswith("##### "):
            line = line[1:]
        elif line.startswith("#### "):
            line = line[1:]
        lines.append(line)
    return "\n".join(lines).rstrip() + "\n"


def migrate_test_authoring(path: Path, title: str):
    text = remove_transitional(path.read_text(encoding="utf-8"))
    # The standalone Quick Start owns the end-to-end tutorial.
    text = re.sub(r"(?ms)^### 02\s+.*?(?=^### 03\s+)", "", text, count=1)
    # Tool, execution and report concepts are now first-class chapters 4, 5 and 11.
    text = re.sub(r"(?ms)^#### 3\.3\s+.*?(?=^#### 3\.4\s+)", "", text, count=1)
    text = re.sub(r"(?ms)^#### 3\.4\s+.*?(?=^#### 3\.5\s+)", "", text, count=1)
    text = re.sub(r"(?ms)^#### 3\.5\s+.*?(?=^### 04\s+)", "", text, count=1)
    # Cookbook material belongs to Quick Start/examples rather than normative Reference.
    text = re.sub(r"(?ms)^### 04\s+.*\Z", "", text, count=1)
    text = re.sub(r"(?m)^### 03\s+.*$", "### " + title, text, count=1)
    lines = []
    for line in text.splitlines():
        if line.startswith("##### "):
            line = line[1:]
        elif line.startswith("#### "):
            line = line[1:]
        lines.append(line)
    path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


def move_maintainer_architecture():
    old = (EN / "13_ci_packaging_operations.md").read_text(encoding="utf-8")
    old = remove_transitional(old)
    old = re.sub(r"(?m)^## 13 CI, Packaging, and Operations\n+", "", old, count=1)
    old = re.sub(r"(?m)^### 10 Architecture for Maintainers\n+", "", old, count=1)
    write(DOCS / "system-design" / "runtime-execution.md", """
# Runtime and Execution Internals

Status: Maintainer documentation

This material was moved out of the normative end-user Reference Manual by issue #42. It describes implementation ownership and invariants. Supported user-visible contracts are defined in `docs/reference/`; this file explains internal sequencing and safety decisions.

""" + old)


def canonical_quick_start():
    source = DOCS / "08_Quick_Start_V3.md"
    if source.is_file():
        text = source.read_text(encoding="utf-8")
        text = re.sub(r"(?m)^# .*Quick Start.*$", "# ATT Quick Start", text, count=1)
        write(DOCS / "quick-start.md", text)


def rewrite_root_readme():
    write(ROOT / "README.md", r'''
# ATT 3.5.1 - Automated Testing Tool

ATT is an offline, template-driven API and integration test runner for SIT/UAT. Excel rows define Testcases; Stages select Templates; Templates execute ordered Actions; reusable Flows and configured Resources keep implementation logic out of test data.

```text
Testcase -> Stage -> Template -> Action -> Resource
                                  |         |-- Tool
                                  |         |-- DBHelper
                                  |         `-- MQHelper
                                  `-- Flow
```

ATT has three peer execution modes over the same runtime model:

- **Run** — workbook-driven Testcase execution;
- **Debug** — standalone Template, Flow or Tool execution;
- **Load** — closed-VU or fixed-arrival-rate execution against a Template, Flow or Tool.

All modes use canonical `EXEC` / `META` Context roots and Action-local `output`. Tool, DB and MQ operations converge on the same Action result/evidence model. Environment profiles select DB/MQ bindings through stable logical IDs without changing Actions.

## Start here

```sh
./att.sh snapshot
./att.sh validate --package
./att.sh run --all
```

Useful peer-mode commands:

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh load examples/load/closed-smoke.yaml
./att.sh load examples/load/arrival-smoke.yaml --format json
```

Windows uses the same commands through `att.bat`.

## Documentation

- Guided tutorial: [`docs/quick-start.md`](docs/quick-start.md)
- Complete generated Reference Manual: [`docs/generated/reference.html`](docs/generated/reference.html)
- English Reference sources: [`docs/reference/`](docs/reference/)
- Chinese Reference sources: [`docs/reference.zh/`](docs/reference.zh/)
- Maintainer design: [`docs/system-design/`](docs/system-design/)
- Documentation architecture: [`docs/documentation-architecture.md`](docs/documentation-architecture.md)
- Release chronology: [`CHANGELOG.md`](CHANGELOG.md)

The current Reference Manual is organized by product concepts rather than release history: authoring, Context, Run/Debug/Load, Tool/DB/MQ, environments, expressions, reliability, configuration, CLI, results, validation and operations.

## Core package layout

```text
config/       global config, Tool groups, DBHelper/MQHelper descriptors
testcase/     xlsx + yaml sidecar + generated xml snapshot
templates/    Template directories and reusable Flows
tools/        process-backed integration scripts/programs
schemas/      published ATT schemas
output/       run/debug/load evidence and reports
```

The current global configuration schema is `att-config/v2.6`. Templates/Flows, DBHelper, MQHelper, debug and load scenarios have their own versioned schemas under `schemas/`.

## Build and validation

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./build.sh
```

`build.sh` runs the release gate and regenerates the modular Reference Manual before packaging. Java 8+ remains the runtime baseline. JDBC drivers are supplied in `lib/`; IBM MQ remains an optional runtime integration.
''')


def main():
    # First preserve implementation-only chapter 10 before replacing Reference 13.
    move_maintainer_architecture()
    canonical_quick_start()

    # Remove the duplicated tutorial/cookbook and the Tool/Run/Report ownership leakage.
    migrate_test_authoring(EN / "02_test_authoring.md", "Authoring contracts")
    migrate_test_authoring(ZH / "02_test_authoring.md", "編寫契約")

    # Existing correct lookup material remains, but historical wrapper chapters disappear.
    for rel, legacy in (
        ("07_expressions.md", "07"),
        ("09_configuration.md", "06"),
        ("10_cli.md", "05"),
        ("11_results_reports_evidence.md", "08"),
        ("12_validation_diagnostics.md", "09"),
    ):
        for root in (EN, ZH):
            path = root / rel
            path.write_text(promote_after_wrapper(path.read_text(encoding="utf-8"), legacy), encoding="utf-8")

    pair("01_overview.md", r'''
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
''', r'''
## 01 概覽與核心概念

ATT 把測試意圖與整合機制分離。測試數據以 workbook／sidecar／snapshot 版本化；Template 與 Flow 定義可重用行為；Resource 把這些行為連接到外部系統。

### 產品模型

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

**Testcase** 是一個標準化 workbook row；**Stage** 選擇 Template 並提供 stage-private data；**Template** 是可執行 scenario 邊界；**Flow** 是具有獨立 Action scope 的可重用 Template 邏輯；**Action** 是一個有序工作單元；**Resource** 是 Action 或允許的 expression call 所使用的 Tool、DBHelper 或 MQHelper。

### 三種執行模式是同級概念

Run、Debug、Load 把不同輸入適配到同一 execution-neutral Context 和同一批 reusable components：

| 模式 | 主要輸入 | 重用內容 |
|---|---|---|
| Run | workbook Testcase 與 Stage selector | Template、Flow、Tool、DB/MQ |
| Debug | `att-debug/v1.0` sidecar 或 `--input` | 單一 Template、Flow 或 Tool target |
| Load | `att-load/v1.0` scenario | 重複執行單一 Template、Flow 或 Tool target |

模式差異由 `EXEC.MODE` 表達；只有 load 會額外出現 `EXEC.LOAD`。可重用 Template/Flow 應主要依賴 `EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS`、`META` 和 Action-local `output`，而不是建立另一套 mode-specific runtime tree。

### 三種 Resource 是同級概念

Tool、DBHelper、MQHelper 是獨立 Resource 類型。它們的配置與 lifecycle 不同，但 operation data 最終都收斂到同一 Action envelope。公開 expression 應讀取 Action result/evidence，而不是 resource 內部 connection/process state。

```text
Tool ----\
DBHelper --+--> operation result/evidence --> Action output
MQHelper -/
```

### Package 邊界

一般 package 包含 `config/`、`testcase/`、`templates/`、`tools/`、`schemas/` 和生成的 `output/`。ATT 在執行前驗證 path 與 identifier。Credential 應放在環境變數或外部 secrets 管理，不應提交到 YAML。

需要逐步建立一個可工作的 package，請使用 `docs/quick-start.md`；本 Reference 其餘內容是規範性查閱文件。
''')

    pair("03_runtime_context.md", r'''
## 03 Runtime and Context Model

ATT uses one public Context model for Run, Debug and each Load iteration.

### Canonical tree

```text
EXEC
├── ID
├── MODE
├── STARTED_AT
├── OUTPUT_DIR
├── INPUT
├── VARS
├── ACTIONS
└── LOAD          # load mode only

META
├── PROJECT
├── SOURCE
├── TARGET
├── TEMPLATE
├── FLOW
├── TOOL
├── DBHELPER
└── MQHELPER

output            # current Action only
```

`EXEC.INPUT` contains execution inputs adapted from the workbook/stage, debug sidecar, or load scenario. `EXEC.VARS` is the mutable publication area for `assign` and other explicit shared values. `EXEC.ACTIONS` contains completed Actions in the **current** Stage/Template/Flow scope. `META` is curated, immutable and secret-safe. `output` is the active Action's local result and is not a persistent root.

### Scope and lifetime

A normal Testcase owns its Case runtime. `EXEC.VARS` can carry explicitly published values across its Stages/Templates. Each Stage/Template starts a fresh `EXEC.ACTIONS` scope. Invoking a Flow temporarily installs a fresh Action scope for the Flow; nested Actions can read earlier Flow Actions, and the caller's Action scope is restored when the Flow returns. Values needed after a Flow returns must be published through `EXEC.VARS`.

There is no public `EXEC.STAGES`, `EXEC.OUTPUT`, `EXEC.CALL`, or invocation-worker tree. Stage/Flow history belongs to result/report evidence, not reusable expression state. Resource connection/pool/process lifecycle state is internal.

### Action-local output and publication

Executable Actions publish a stable envelope. Fields are present where meaningful:

```text
output
├── status
├── success
├── durationMs
├── result
├── diagnostic
├── evidence
└── attempts[]
```

While an Action is active, use `${output...}`. After it completes in the current scope, use `${EXEC.ACTIONS.<id>.output...}`. `result` is the final/winning primary operation result. Retry history and per-attempt collectors remain under `attempts[n]`; they do not replace the top-level final result.

### Load-only Context

`EXEC.LOAD` is conditional data added to the same Context model, not a second runtime. It may contain `RUN_ID`, `MODEL`, `USER_ID`, `ITERATION_ID`, `ITERATION`, `PHASE`, and `RUN_STARTED_AT`. Closed workloads provide a stable `USER_ID` for one virtual user; fixed-arrival-rate iterations have no persistent VU identity.

### Optional lookup

`${path}` is strict. `${path?}` returns null for a missing map/list path where optional lookup is defined, but it does not make malformed syntax, ambiguity, invalid traversal, or illegal scope access valid.

### Compatibility aliases

Deterministic legacy views such as `CASE`, `RUN`, and `ACTIONS` remain readable where they map one-to-one to canonical data and may produce migration warnings. New documentation and new authoring use `EXEC`/`META`. Semantically incompatible historical paths such as stage-history-as-runtime-state are errors rather than aliases.
''', r'''
## 03 Runtime 與 Context 模型

ATT 對 Run、Debug 以及每一個 Load iteration 使用同一套公開 Context 模型。

### Canonical tree

```text
EXEC
├── ID
├── MODE
├── STARTED_AT
├── OUTPUT_DIR
├── INPUT
├── VARS
├── ACTIONS
└── LOAD          # 只在 load mode 存在

META
├── PROJECT
├── SOURCE
├── TARGET
├── TEMPLATE
├── FLOW
├── TOOL
├── DBHELPER
└── MQHELPER

output            # 只屬於目前 Action
```

`EXEC.INPUT` 保存由 workbook/stage、debug sidecar 或 load scenario 適配而來的執行輸入；`EXEC.VARS` 是 `assign` 等顯式共享值的 mutable publication area；`EXEC.ACTIONS` 只保存**目前** Stage/Template/Flow scope 已完成的 Action；`META` 是經過篩選、immutable、secret-safe 的 metadata；`output` 只是 active Action 的局部結果，並不是持久 root。

### Scope 與 lifetime

一般 Testcase 擁有自己的 Case runtime。`EXEC.VARS` 可以在該 Case 的 Stage/Template 之間保存明確發布的值。每個 Stage/Template 都建立新的 `EXEC.ACTIONS` scope。呼叫 Flow 時會暫時換成該 Flow 的新 Action scope；Flow 內可讀取較早完成的內部 Action，返回 caller 後原來 scope 會恢復。Flow 返回後仍需使用的值必須透過 `EXEC.VARS` 發布。

公開模型沒有 `EXEC.STAGES`、`EXEC.OUTPUT`、`EXEC.CALL` 或 invocation-worker tree。Stage/Flow 歷史屬於 result/report evidence，而不是可重用 expression state。Resource connection/pool/process lifecycle state 亦屬內部資料。

### Action-local output 與 publication

可執行 Action 以穩定 envelope 發布結果；各欄位在有意義時存在：

```text
output
├── status
├── success
├── durationMs
├── result
├── diagnostic
├── evidence
└── attempts[]
```

Action 執行中使用 `${output...}`；在目前 scope 完成後使用 `${EXEC.ACTIONS.<id>.output...}`。`result` 是最後／勝出的 primary operation result；retry history 與每次 attempt 的 collector 保留在 `attempts[n]`，不會取代 top-level final result。

### Load-only Context

`EXEC.LOAD` 只是加在同一 Context 上的 conditional data，不是第二套 runtime。它可包含 `RUN_ID`、`MODEL`、`USER_ID`、`ITERATION_ID`、`ITERATION`、`PHASE`、`RUN_STARTED_AT`。Closed workload 對同一 virtual user 提供穩定 `USER_ID`；fixed-arrival-rate iteration 沒有 persistent VU identity。

### Optional lookup

`${path}` 是 strict lookup。`${path?}` 在定義允許的 missing map/list path 上返回 null，但不會把 malformed syntax、ambiguity、invalid traversal 或越權 scope access 變成合法。

### Compatibility aliases

`CASE`、`RUN`、`ACTIONS` 等 deterministic legacy view 在能與 canonical data 一對一映射時仍可讀，並可能產生 migration warning。新文件與新 authoring 使用 `EXEC`/`META`；不能保持相同語義的歷史 path 不會偽裝成 alias。
''')

    pair("04_execution_modes/index.md", r'''
## 04 Execution Modes

Run, Debug and Load are peer adapters over the same reusable Template/Flow/Tool/DB/MQ execution semantics.

| Mode | Context `EXEC.MODE` | Unit of execution | Primary result location |
|---|---|---|---|
| Run | `testcase` | selected Testcase | `output/<RunID>/` |
| Debug | `debug` | one target invocation | `output/debug/<debugId>/` |
| Load | `load` | repeated target iterations | `output/load/<runId>/` |

All three resolve the selected environment before execution, construct canonical Context, validate the target/dependency closure, and use the same component contracts. Mode-specific scheduling, selection and reporting do not create alternate Template or expression semantics.
''', r'''
## 04 執行模式

Run、Debug、Load 是同級 adapter，共用相同的 Template/Flow/Tool/DB/MQ 執行語義。

| 模式 | Context `EXEC.MODE` | 執行單位 | 主要結果位置 |
|---|---|---|---|
| Run | `testcase` | selected Testcase | `output/<RunID>/` |
| Debug | `debug` | 單一 target invocation | `output/debug/<debugId>/` |
| Load | `load` | 重複 target iterations | `output/load/<runId>/` |

三種模式都會先解析 environment、建立 canonical Context、驗證 target/dependency closure，再使用相同 component contract。Mode-specific scheduling、selection、reporting 不會建立另一套 Template 或 expression 語義。
''')

    pair("04_execution_modes/run.md", r'''
### 4.1 Run Mode

Run is workbook-driven Testcase execution.

```sh
./att.sh run --all
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001
./att.sh run --all --tag smoke --exclude-tag slow
```

ATT loads the effective configuration/environment, verifies canonical workbook snapshots, validates the selected dependency closure, reserves a unique Run ID, then executes selected Cases in Stage order. A Stage resolves its selector to a Template; Actions execute in YAML order subject to `runWhen` and `onFailure`.

Run evidence is written directly below `output/<RunID>/`. The completed run publishes `run.yaml`, Case directories/logs, result workbooks, HTML/CI outputs as configured, and only after completion updates `latest-run.yaml`. A pre-existing Run ID is rejected rather than overwritten. `run --update-snapshot` is the explicit opt-in snapshot refresh path before validation/execution.

Status aggregation preserves severity: ERROR > INVALID > FAIL > PASS > SKIPPED. Process exit code is `0` when the run completes without failing status, `1` for test/assertion failure, `2` for invalid command/configuration/validation, and `3` for runtime/infrastructure error.

Use Chapter 10 for exact selectors/options, Chapter 8 for execution control, and Chapter 11 for artifact contracts.
''', r'''
### 4.1 Run 模式

Run 是 workbook-driven Testcase execution。

```sh
./att.sh run --all
./att.sh run --suite testcase/payment.xlsx --case payment.payment.TC001
./att.sh run --all --tag smoke --exclude-tag slow
```

ATT 先載入 effective configuration/environment、驗證 canonical workbook snapshot、驗證 selected dependency closure、保留唯一 Run ID，然後依 Stage 順序執行 selected Case。Stage 將 selector 解析成 Template；Action 依 YAML 順序並受 `runWhen` / `onFailure` 控制。

Run evidence 直接寫到 `output/<RunID>/`。完成後才發布 `run.yaml`、Case directories/logs、結果 workbook、HTML/CI output，並更新 `latest-run.yaml`。已存在的 Run ID 會被拒絕，不會覆寫。`run --update-snapshot` 是執行前明確授權更新 snapshot 的唯一流程。

Status aggregation 的嚴重度為 ERROR > INVALID > FAIL > PASS > SKIPPED。Process exit code：`0` 表示沒有失敗狀態、`1` 表示測試/assertion failure、`2` 表示 command/configuration/validation 無效、`3` 表示 runtime/infrastructure error。

精確 selector/option 見第 10 章；執行控制見第 8 章；artifact contract 見第 11 章。
''')

    pair("04_execution_modes/debug.md", r'''
### 4.2 Standalone Debug

Debug executes one Template, Flow or Tool without requiring a workbook Testcase.

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh debug flow common.compose.v1 --input /tmp/compose.debug.yaml
./att.sh debug tool fpp.invokeApi --input /tmp/invoke.debug.yaml --env UAT
```

Debug input uses `schemaVersion: att-debug/v1.0`. Supported top-level data is `case`, optional `stage`, `inputs`, `arguments`, and grouped `tools.<localKey>.arguments`. `inputs` is adapted into canonical `EXEC.INPUT`; framework-owned identity, output, Actions, resource metadata and compatibility views cannot be overwritten by user input.

Without `--input`, ATT looks for `debug.yaml` beside a selected Template or Flow and for `config/tools/<group>.debug.yaml` for a grouped Tool. When no default sidecar exists, supply `--input`. `--env` uses the same environment resolver as Run/Validate/Load before target validation.

Debug performs target-scoped validation: it validates the selected Template/Flow dependency closure or Tool contract, rather than requiring unrelated workbooks. Template and Flow debug use the same Action/Flow scope rules as Run. Tool debug constructs the same configured Tool invocation contract.

Each invocation is isolated under:

```text
output/debug/<debugId>/
├── case.log
├── result.yaml
└── artifacts/
```

Debug does not create or update normal `latest-run.yaml`. Exit codes are `0` PASS, `1` FAIL, `2` invalid CLI/config/input/validation, and `3` runtime error. It is execution-equivalent at the selected reusable-component boundary, but it is **not** a workbook Case: there is no workbook selection, Stage history or result-workbook lifecycle unless explicitly represented by debug inputs/artifacts.
''', r'''
### 4.2 Standalone Debug

Debug 可在沒有 workbook Testcase 的情況下執行單一 Template、Flow 或 Tool。

```sh
./att.sh debug template PAYMENT_INVOKE
./att.sh debug flow common.compose.v1 --input /tmp/compose.debug.yaml
./att.sh debug tool fpp.invokeApi --input /tmp/invoke.debug.yaml --env UAT
```

Debug input 使用 `schemaVersion: att-debug/v1.0`。Top-level 支援 `case`、可選 `stage`、`inputs`、`arguments`、以及 grouped `tools.<localKey>.arguments`。`inputs` 會適配到 canonical `EXEC.INPUT`；framework-owned identity、output、Actions、resource metadata 與 compatibility view 不能被 user input 覆寫。

未指定 `--input` 時，Template/Flow 會在旁邊尋找 `debug.yaml`；grouped Tool 會查找 `config/tools/<group>.debug.yaml`。沒有可發現 default sidecar 時請使用 `--input`。`--env` 在 target validation 之前使用與 Run/Validate/Load 相同的 environment resolver。

Debug 執行 target-scoped validation：只驗證 selected Template/Flow dependency closure 或 Tool contract，不要求無關 workbook。Template/Flow debug 使用與 Run 相同的 Action/Flow scope rule；Tool debug 使用相同 configured Tool invocation contract。

每次 invocation 隔離於：

```text
output/debug/<debugId>/
├── case.log
├── result.yaml
└── artifacts/
```

Debug 不建立或更新普通 `latest-run.yaml`。Exit code：`0` PASS、`1` FAIL、`2` CLI/config/input/validation 無效、`3` runtime error。它在 selected reusable-component 邊界上與正常執行等價，但**不是** workbook Case：除非 debug input/artifact 明確提供，否則沒有 workbook selection、Stage history 或 result-workbook lifecycle。
''')

    pair("04_execution_modes/load.md", r'''
### 4.3 Load Mode

Load repeatedly executes one Template, Flow or Tool target from an `att-load/v1.0` scenario.

```yaml
schemaVersion: att-load/v1.0
target: {type: template, id: V3_FLOW_EXAMPLE}
inputs: {region: HK}
load:
  users: 20
  duration: 5m
execution:
  thinkTime: 500ms
thresholds:
  errorRate: "< 1%"
  p95: "< 800ms"
```

`target.type` is `template`, `flow`, or `tool`. Only Tool targets accept `target.arguments`. Scenario `inputs` become each iteration's `EXEC.INPUT`.

#### Workload models

**Closed VU** uses positive `load.users`. One virtual user repeatedly runs iterations and keeps a stable `EXEC.LOAD.USER_ID`; `execution.thinkTime` applies between its iterations.

**Fixed arrival rate** uses `load.arrivalRate` plus positive `maxConcurrent` and V1 `overloadPolicy: drop`. It has no persistent VU identity. Arrivals that cannot start because the concurrency limit is full are recorded as `dropped`; they are not queued and are not counted as SUT errors.

The models are mutually exclusive. `duration` is required. Optional `warmup`, `rampUp`, and `rampDown` define phases; `EXEC.LOAD.PHASE` identifies `WARMUP`, `RAMP_UP`, `STEADY`, or `RAMP_DOWN`. Warm-up traffic executes but is excluded from measured threshold aggregates.

#### CLI overrides

Explicit CLI values replace the corresponding scenario values and the effective scenario is validated again:

`--users`, `--arrival-rate`, `--warmup`, `--ramp-up`, `--duration`, `--ramp-down`, `--think-time`, `--max-concurrent`, `--overload-policy`.

#### Metrics, thresholds and evidence

Load records bounded metrics including iteration success/failure, latency percentiles and throughput. Arrival-rate runs additionally track scheduled/started/completed/dropped and achieved arrival rate. Supported thresholds include `errorRate`, `p95`, `p99`, `minThroughput`, `droppedRate`, and `achievedArrivalRate`.

Evidence policy controls successful samples and failure retention. Success workspaces are not retained by default; failures/samples are materialized only as required by policy. DB/MQ pools and load-run resource ownership are shared at the load-run resource layer, while mutable `EXEC.VARS`, `EXEC.ACTIONS`, Tool transient state and per-iteration output remain isolated.

#### Outputs and exit codes

```text
output/load/<runId>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── samples/      # when retained
├── failures/     # when retained
└── performance.json   # with --profile
```

`--profile` measures ATT generator/runtime overhead; it is not a target-host CPU/memory benchmark. Load exit codes are `0` PASS, `1` threshold failure, `2` invalid scenario/configuration/target, and `3` runtime/infrastructure error.

`EXEC.LOAD` is defined centrally in Chapter 3; artifact schemas and report details are in Chapter 11.
''', r'''
### 4.3 Load 模式

Load 由 `att-load/v1.0` scenario 重複執行一個 Template、Flow 或 Tool target。

```yaml
schemaVersion: att-load/v1.0
target: {type: template, id: V3_FLOW_EXAMPLE}
inputs: {region: HK}
load:
  users: 20
  duration: 5m
execution:
  thinkTime: 500ms
thresholds:
  errorRate: "< 1%"
  p95: "< 800ms"
```

`target.type` 只可以是 `template`、`flow`、`tool`；只有 Tool target 可使用 `target.arguments`。Scenario `inputs` 會成為每個 iteration 的 `EXEC.INPUT`。

#### Workload models

**Closed VU** 使用正整數 `load.users`。同一 virtual user 連續執行 iteration 並保持穩定 `EXEC.LOAD.USER_ID`；`execution.thinkTime` 用於該 VU 兩次 iteration 之間。

**Fixed arrival rate** 使用 `load.arrivalRate`，並必須同時提供正整數 `maxConcurrent` 和 V1 唯一的 `overloadPolicy: drop`。它沒有 persistent VU identity。因 concurrent limit 已滿而不能開始的 arrival 會記為 `dropped`，不排隊，也不計成 SUT error。

兩種 model 互斥。`duration` 必填；可選 `warmup`、`rampUp`、`rampDown` 定義 phase，`EXEC.LOAD.PHASE` 為 `WARMUP`、`RAMP_UP`、`STEADY` 或 `RAMP_DOWN`。Warm-up traffic 會執行，但不納入 measured threshold aggregate。

#### CLI overrides

明確提供的 CLI 值會覆蓋 scenario 同名值，然後再次驗證 effective scenario：

`--users`、`--arrival-rate`、`--warmup`、`--ramp-up`、`--duration`、`--ramp-down`、`--think-time`、`--max-concurrent`、`--overload-policy`。

#### Metrics、thresholds 與 evidence

Load 以 bounded memory 記錄 iteration success/failure、latency percentile、throughput；arrival-rate 另外記錄 scheduled/started/completed/dropped 與 achieved arrival rate。Threshold 支援 `errorRate`、`p95`、`p99`、`minThroughput`、`droppedRate`、`achievedArrivalRate`。

Evidence policy 控制成功 sample 與 failure retention。成功 workspace 預設不保留；failure/sample 只在 policy 需要時 materialize。DB/MQ pool 與 load-run resource ownership 屬 load-run resource layer；mutable `EXEC.VARS`、`EXEC.ACTIONS`、Tool transient state 與 per-iteration output 仍彼此隔離。

#### Outputs 與 exit codes

```text
output/load/<runId>/
├── load-summary.json
├── load-summary.yaml
├── report/index.html
├── samples/      # 有保留時
├── failures/     # 有保留時
└── performance.json   # 使用 --profile 時
```

`--profile` 量度 ATT generator/runtime overhead，不是 target host 的 CPU/memory benchmark。Load exit code：`0` PASS、`1` threshold failure、`2` scenario/configuration/target 無效、`3` runtime/infrastructure error。

`EXEC.LOAD` 的中央定義見第 3 章；artifact schema/report 細節見第 11 章。
''')

    pair("05_resources/index.md", r'''
## 05 Resources and Integrations

Tool, DBHelper and MQHelper are peer integration/resource types. They have different descriptors and lifecycle rules but converge on the common operation-result/evidence contract in 5.4.

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
```

Resource IDs are logical contracts referenced by Templates/expressions. Environment profiles may bind the same DB/MQ logical ID to different descriptors without changing Action YAML.
''', r'''
## 05 資源與整合

Tool、DBHelper、MQHelper 是同級 integration/resource 類型。它們的 descriptor 與 lifecycle rule 不同，但最後都收斂到 5.4 的 common operation-result/evidence contract。

```text
Tool      -> process/call operation --\
DBHelper  -> JDBC operation ----------+--> Action output
MQHelper  -> MQ operation ------------/
```

Resource ID 是 Template/expression 所引用的 logical contract。Environment profile 可以把相同 DB/MQ logical ID 綁定到不同 descriptor，而不需要修改 Action YAML。
''')

    pair("05_resources/tools.md", r'''
### 5.1 Tool

A Tool is a named external or framework-native capability. A Tool declares exactly one backend: **command-backed** or **call-backed**.

#### Command-backed Tool

Command-backed Tools execute a configured argv contract locally or through configured SSH transport. Argv-list definitions preserve item boundaries; scalar command definitions are tokenized into the same internal argv model. ATT does not implicitly invoke a shell or expand wildcards for ordinary process-backed Tools. Stdout/stderr, exit code, timeout and process diagnostics are evidence; a non-zero process exit does not by itself define assertion PASS/FAIL unless the Action contract says so.

Use command-backed Tools for scripts, CLIs, SSH and third-party executables.

#### Call-backed Tool

Call-backed Tools execute typed framework-native calls, such as supported DB read/update façades or pure built-ins, without converting typed values into process strings. Use them when the capability is naturally represented by a typed ATT call contract rather than an external process.

Both backends publish the same public Action envelope. The primary value is `${output.result}` while active and `${EXEC.ACTIONS.<id>.output.result}` after publication. Final operation evidence is under `output.evidence`; retries preserve per-attempt evidence under `output.attempts[n].evidence`.

A Tool Action may use object `saveAs` and post-operation evidence collectors where permitted. Collectors execute after the primary operation and before that attempt's assertion; collector failure policy does not replace the primary `result`.
''', r'''
### 5.1 Tool

Tool 是具名的 external 或 framework-native capability。每個 Tool 必須二選一：**command-backed** 或 **call-backed**。

#### Command-backed Tool

Command-backed Tool 依 configured argv contract 在本機或已配置 SSH transport 執行。Argv list 會保留每個 item 的 argument boundary；scalar command 只會被 tokenize 成相同 internal argv model。一般 process-backed Tool 不會隱式啟動 shell，也不會自動 wildcard expansion。Stdout/stderr、exit code、timeout 和 process diagnostic 屬 evidence；非零 process exit 本身不等於 assertion FAIL，除非 Action contract 明確這樣判定。

Script、CLI、SSH、third-party executable 適合 command-backed Tool。

#### Call-backed Tool

Call-backed Tool 執行 typed framework-native call，例如支援的 DB read/update facade 或 pure built-in，不需要把 typed value 轉成 process string。若 capability 本身就是 typed ATT call contract，優先使用 call-backed。

兩種 backend 都發布相同 public Action envelope。Active Action 使用 `${output.result}`，完成後使用 `${EXEC.ACTIONS.<id>.output.result}`。Final operation evidence 位於 `output.evidence`；retry 的 per-attempt evidence 保留在 `output.attempts[n].evidence`。

Tool Action 在支援位置可以使用 object `saveAs` 和 post-operation evidence collector。Collector 在 primary operation 後、該 attempt assertion 前執行；collector failure policy 不會取代 primary `result`。
''')

    pair("05_resources/dbhelper.md", r'''
### 5.2 DBHelper

DBHelper is a first-class JDBC resource, configured independently from Tools. Each descriptor uses `schemaVersion: att-dbhelper/v2.5` and a stable logical `id`; global `dbhelpers` references descriptor files.

```yaml
schemaVersion: att-dbhelper/v2.5
id: orders
driverClass: oracle.jdbc.OracleDriver
url: ${ENV:ORDERS_DB_URL}
username: ${ENV:ORDERS_DB_USERNAME}
password: ${ENV:ORDERS_DB_PASSWORD}
```

Credentials may be resolved from environment variables and must not be published into `META`, reports or diagnostics. JDBC driver jars are supplied in `lib/`; ATT does not bundle a database driver.

A `type: db` Action selects one helper ID and exactly one `query` or `update` block. Read operations are also available through supported `#{db.<id>.query(...)}` / `scalar(...)` expression calls. Positional JDBC `?` bindings and direct-Action named `:name` parameters are supported by the documented contracts.

Queries return typed rows/scalars; updates return the documented update result. Operation and SQL/parameter evidence enters the common Action envelope. Secret credentials are never evidence. Parameter evidence follows descriptor/Action masking/type policy.

DBHelper owns connection/statement limits, query timeout and transaction behavior defined by its descriptor. Transaction finalization is tied to the Case/iteration lifecycle; commit/rollback/reconnect are resource operations, not public Context roots. Future DB Action-level timeout/retry belongs to Chapter 8 without changing the DBHelper identity model.
''', r'''
### 5.2 DBHelper

DBHelper 是獨立於 Tool 的一級 JDBC resource。每個 descriptor 使用 `schemaVersion: att-dbhelper/v2.5` 和穩定 logical `id`；global `dbhelpers` 只引用 descriptor file。

```yaml
schemaVersion: att-dbhelper/v2.5
id: orders
driverClass: oracle.jdbc.OracleDriver
url: ${ENV:ORDERS_DB_URL}
username: ${ENV:ORDERS_DB_USERNAME}
password: ${ENV:ORDERS_DB_PASSWORD}
```

Credential 可從 environment variable 解析，但不能發布到 `META`、report 或 diagnostic。JDBC driver jar 由使用者放入 `lib/`；ATT 不內置 database driver。

`type: db` Action 選擇一個 helper ID，並且只能有一個 `query` 或 `update` block。Read operation 亦可透過支援的 `#{db.<id>.query(...)}` / `scalar(...)` expression call 使用。文件契約支援 positional JDBC `?` binding，以及 direct Action 的 named `:name` parameter。

Query 返回 typed row/scalar；update 返回規範的 update result。Operation、SQL/parameter evidence 進入 common Action envelope；secret credential 永遠不是 evidence。Parameter evidence 按 descriptor/Action 的 masking/type policy 處理。

DBHelper 擁有 descriptor 定義的 connection/statement limit、query timeout、transaction behavior。Transaction finalization 綁定 Case/iteration lifecycle；commit/rollback/reconnect 是 resource operation，不是 public Context root。未來 DB Action-level timeout/retry 應擴展第 8 章，而不改變 DBHelper identity model。
''')

    pair("05_resources/mqhelper.md", r'''
### 5.3 MQHelper

MQHelper is a first-class IBM MQ resource. Each descriptor uses `schemaVersion: att-mqhelper/v1.0`, a stable logical `id`, connection topology and optional credentials. Global `mqhelpers` references descriptor files; environment profiles may select a different descriptor for the same logical ID.

Primary calls are:

```text
#{mq.<id>.send(...)}
#{mq.<id>.receive(...)}
#{mq.<id>.request(...)}
```

Payloads are file-based so request bytes do not have to be duplicated into Context/evidence. `request` combines send and correlated receive behavior. Correlation identifiers, queue/operation metadata, timing and diagnostic information are evidence; credentials and payload bytes are not copied into evidence.

Timeout behavior is operation-specific and remains distinct from assertion failure. MQ connection/pool lifecycle is framework-owned resource state, especially in Load mode; it is not exposed as a public `EXEC.MQ` tree.

ATT's default build does not require IBM MQ client classes. Runtime MQ use requires the IBM MQ client jar/profile documented by the package/release instructions. MQ operations feed the same Action result/evidence envelope as Tool and DB operations.
''', r'''
### 5.3 MQHelper

MQHelper 是一級 IBM MQ resource。每個 descriptor 使用 `schemaVersion: att-mqhelper/v1.0`、穩定 logical `id`、connection topology 與可選 credential。Global `mqhelpers` 引用 descriptor file；environment profile 可讓相同 logical ID 在不同環境選擇不同 descriptor。

主要 call：

```text
#{mq.<id>.send(...)}
#{mq.<id>.receive(...)}
#{mq.<id>.request(...)}
```

Payload 採 file-based contract，因此 request bytes 不需要複製到 Context/evidence。`request` 結合 send 與 correlated receive。Correlation identifier、queue/operation metadata、timing、diagnostic 屬 evidence；credential 與 payload bytes 不複製到 evidence。

Timeout 是 operation-specific failure，與 assertion failure 分開。MQ connection/pool lifecycle 是 framework-owned resource state，特別是在 Load mode，不會公開成 `EXEC.MQ` tree。

ATT default build 不要求 IBM MQ client class；真正執行 MQ 需要 package/release 文件所述 IBM MQ client jar/profile。MQ operation 與 Tool、DB 一樣進入同一 Action result/evidence envelope。
''')

    pair("05_resources/operation_result.md", r'''
### 5.4 Common Operation Result and Evidence

Tool, DB and MQ executors converge at one operation boundary before the Template runner applies Action lifecycle, assertions and retry policy.

```text
operation
├── result
├── evidence
├── executionSuccess
├── diagnostic
└── timing
       |
       v
Action output
├── status / success
├── durationMs
├── result          # final/winning primary operation
├── diagnostic
├── evidence        # final operation evidence
└── attempts[]      # retry history + per-attempt evidence
```

`result` is business/operation data. `evidence` is supporting execution data. `diagnostic` explains an operational failure. `status` is the Action-level classification after operation outcome and assertion handling. These are intentionally different concepts.

Retries never publish multiple competing top-level results: only the final/winning primary operation is top-level. Each attempt retains its own evidence and collector results in `attempts[n]`. Connection pools, JDBC transaction objects, MQ sessions and process handles are internal lifecycle state and must not be treated as Context.
''', r'''
### 5.4 Common Operation Result 與 Evidence

Tool、DB、MQ executor 先收斂到同一 operation boundary，之後 Template runner 才套用 Action lifecycle、assertion、retry policy。

```text
operation
├── result
├── evidence
├── executionSuccess
├── diagnostic
└── timing
       |
       v
Action output
├── status / success
├── durationMs
├── result          # 最後／勝出的 primary operation
├── diagnostic
├── evidence        # final operation evidence
└── attempts[]      # retry history + per-attempt evidence
```

`result` 是 business/operation data；`evidence` 是支援執行的證據；`diagnostic` 解釋 operational failure；`status` 則是在 operation outcome 與 assertion 處理後的 Action-level classification。這些概念刻意分開。

Retry 不會在 top-level 發布多個競爭結果：只有最後／勝出的 primary operation 位於 top-level。每個 attempt 的 evidence 與 collector result 保留在 `attempts[n]`。Connection pool、JDBC transaction object、MQ session、process handle 都是 internal lifecycle state，不屬於 Context。
''')

    pair("06_environment_testdata.md", r'''
## 06 Environment and Test Data

Environment selection changes resource binding, not Action logic.

### Environment profiles

`att-config/v2.6` may declare an `environment` default and an `environments` map. `--config` selects the base configuration file; `--env` selects one named binding inside that configuration. Explicit `--env` wins over the configured default. Unknown environments fail before external execution.

Profiles are typed shallow bindings, not generic recursive YAML inheritance. Current profile-owned lists are `dbhelpers` and `mqhelpers`: when a profile supplies one of those lists it replaces that resource list; an omitted list inherits the common root list.

```yaml
environment: SIT
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

The descriptor in every environment should expose the same stable logical IDs (`orders`, `payment`, etc.). Template/Flow/Action references therefore remain unchanged across SIT/UAT/PREPROD.

### Topology and secrets

Topology may vary by descriptor/environment. Secrets should be injected through `${ENV:NAME}` where the descriptor supports it and must not be committed or surfaced in effective metadata/diagnostics. Missing required environment variables are validation/configuration errors that identify the field/variable name without printing a resolved secret.

### Cross-mode consistency

Run, Validate, Debug and Load resolve the environment through the same effective-config step before their mode-specific work. `--env` therefore cannot be used as Action branching and does not create mode-specific helper IDs.

### Migration from separate configs

Existing separate `--config config/environments/sit.yaml` / `uat.yaml` workflows remain useful when whole configurations genuinely differ. Profiles are preferable when the package contract is common and only typed DB/MQ bindings vary. Separate configs remain preferable for materially different package policy, roots, Tool topology or configuration ownership.

### Test data extension point

Workbook/sidecar/snapshot remains the current Testcase data contract. Future logical environment-bound fixtures (#38) belong in this chapter and should follow the same stable logical-name principle rather than introducing environment branches into Actions.
''', r'''
## 06 環境與測試數據

Environment selection 改變 resource binding，不改變 Action logic。

### Environment profiles

`att-config/v2.6` 可以定義 `environment` default 與 `environments` map。`--config` 選擇 base configuration file；`--env` 在該 configuration 內選擇 named binding。明確 `--env` 優先於 configured default；未知 environment 在任何 external execution 前失敗。

Profile 是 typed shallow binding，不是 generic recursive YAML inheritance。目前 profile 可擁有 `dbhelpers`、`mqhelpers` list：profile 明確提供某 list 時會取代 root 的該 resource list；沒有提供的 list 則繼承 common root list。

```yaml
environment: SIT
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

不同 environment 的 descriptor 應暴露相同 stable logical ID（例如 `orders`、`payment`），因此 Template/Flow/Action 在 SIT/UAT/PREPROD 之間不需要修改。

### Topology 與 secrets

Topology 可以隨 descriptor/environment 改變。Secret 應在 descriptor 支援的位置使用 `${ENV:NAME}` 注入，不應提交到 repo，也不應出現在 effective metadata/diagnostic。缺少 required environment variable 屬 validation/configuration error；錯誤會指出 field/variable name，但不列印 resolved secret。

### Cross-mode consistency

Run、Validate、Debug、Load 在 mode-specific 工作前都經過相同 effective-config environment resolution。因此 `--env` 不是 Action branching，也不會產生 mode-specific helper ID。

### 從獨立 config 遷移

原有 `--config config/environments/sit.yaml` / `uat.yaml` 工作方式仍可使用。若 package contract 相同、只改 typed DB/MQ binding，profile 更簡潔；若整體 policy、root、Tool topology 或 configuration ownership 有重大差異，仍應使用 separate config。

### Test data 擴展位置

Workbook/sidecar/snapshot 仍是目前 Testcase data contract。未來 logical environment-bound fixture（#38）應擴展本章，並沿用 stable logical-name 原則，而不是把 environment branch 放入 Action。
''')

    pair("08_reliability_execution_control.md", r'''
## 08 Reliability and Execution Control

This chapter owns cross-cutting public execution behavior.

### Assertion and status

An assertion evaluates a boolean condition after the Action's primary work at the documented assertion point. A false assertion is `FAIL`; an exception/infrastructure problem is `ERROR`; invalid authoring/configuration is `INVALID`; a non-selected condition is `SKIPPED`; successful work is `PASS`. Operation failure and assertion failure are therefore distinct.

### `runWhen` and `onFailure`

`runWhen` controls whether a statically known Action/Stage is eligible to execute. `onFailure: stop|continue` controls continuation after failure; `continue` never changes the failed status into PASS. Cleanup/diagnostic work should use the documented conditional execution semantics rather than hiding failures.

### Timeout

Timeout terminates or abandons the operation according to the supported backend and records diagnostic/evidence. Timeout is an operational failure; it is not an assertion false result. Tool timeout behavior and resource-specific DB/MQ limits are documented in their resource contracts.

### Retry and attempts

Where retry is supported, one logical Action owns multiple attempts. Retry policy determines which operation failures are retryable. The final/winning operation becomes top-level `output.result` / `output.evidence`; every attempt remains available under `output.attempts[n]`. A later success does not erase earlier attempt evidence.

### Evidence collectors

Tool evidence collectors run after the primary operation and before that attempt's assertion. Collectors have independent timeout and `onFailure: continue|stop`. Collector output belongs to the attempt's evidence and never replaces the primary operation result.

### Transaction/resource lifecycle

DB transaction finalization and DB/MQ resource cleanup occur at the appropriate execution lifecycle boundary. These mechanisms can affect operation success/diagnostics but are internal resource state, not public Context namespaces.

### Aggregation

When multiple child outcomes contribute to a parent, severity is preserved:

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

Future fixture behavior (#38) and DB Action-level timeout/retry (#39) extend this chapter's existing concepts rather than creating a new reliability model.
''', r'''
## 08 可靠性與執行控制

本章集中定義 cross-cutting public execution behavior。

### Assertion 與 status

Assertion 在文件規定的 assertion point、primary work 之後評估 boolean condition。False assertion 是 `FAIL`；exception/infrastructure problem 是 `ERROR`；authoring/configuration 無效是 `INVALID`；條件未選中是 `SKIPPED`；成功工作是 `PASS`。因此 operation failure 與 assertion failure 是不同概念。

### `runWhen` 與 `onFailure`

`runWhen` 決定 statically known Action/Stage 是否 eligible；`onFailure: stop|continue` 決定 failure 後是否繼續。`continue` 不會把 failed status 改成 PASS。Cleanup/diagnostic 應使用規範的 conditional execution semantics，而不是隱藏 failure。

### Timeout

Timeout 依 backend 支援能力終止或放棄 operation，並記錄 diagnostic/evidence。Timeout 是 operational failure，不是 assertion false。Tool timeout 與 resource-specific DB/MQ limit 分別由其 resource contract 定義。

### Retry 與 attempts

在支援 retry 的位置，一個 logical Action 可以擁有多個 attempt。Retry policy 決定哪些 operation failure 可重試。最後／勝出的 operation 成為 top-level `output.result` / `output.evidence`；每次 attempt 保留在 `output.attempts[n]`。後續成功不會抹掉較早 attempt evidence。

### Evidence collectors

Tool evidence collector 在 primary operation 後、該 attempt assertion 前執行。Collector 有獨立 timeout 與 `onFailure: continue|stop`；collector output 屬於該 attempt evidence，不會取代 primary operation result。

### Transaction/resource lifecycle

DB transaction finalization 與 DB/MQ resource cleanup 在相應 execution lifecycle boundary 進行。它們可能影響 operation success/diagnostic，但屬 internal resource state，不是 public Context namespace。

### Aggregation

多個 child outcome 聚合時保留嚴重度：

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

未來 fixture（#38）與 DB Action-level timeout/retry（#39）應延伸本章既有概念，而不是再建立一套 reliability model。
''')

    pair("13_ci_packaging_operations.md", r'''
## 13 CI, Packaging, and Operations

ATT supports source-tree development and offline release packages.

### Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` runs the release gate, regenerates the modular Reference Manual, builds the application jar and release/source archives, and verifies the packaged launcher. It requires Java/Maven plus Python 3 and Pandoc for Reference generation.

### Runtime dependencies

Java 8+ is the runtime baseline. ATT does not bundle JDBC drivers; place required driver/dependency jars in `lib/`. IBM MQ is optional: the default build remains usable without MQ client classes, while MQ deployments package the supported IBM client jar/profile.

### Documentation operations

`./att.sh docs` generates package documentation at `build/docs/index.html` from the validated ATT package model. The normative product Reference is independently generated from `docs/reference*` by `tools/build_reference_manual.py`. `./att.sh clean` removes documented generated runtime/build outputs but preserves source inputs.

### CI and environment promotion

CI should validate the package before executing external integration tests, keep Run/Debug/Load evidence as job artifacts as appropriate, and select environments through explicit `--config`/`--env` policy. Stable logical DB/MQ IDs let the same Templates move across SIT/UAT/PREPROD without Action edits.

Parallel jobs should use unique Run IDs and, when independent retention/latest-run state is required, separate output roots. Destructive operations such as clean/report/archive over one shared output root must be serialized.

Maintainer implementation sequencing, scheduler internals and resource-owner details live in `docs/system-design/`, not in this end-user Reference.
''', r'''
## 13 CI、打包與運維

ATT 支援 source-tree development 以及 offline release package。

### Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` 會執行 release gate、重新生成 modular Reference Manual、建立 application jar 與 release/source archive，並驗證 packaged launcher。Reference generation 另外需要 Python 3 與 Pandoc。

### Runtime dependencies

Java 8+ 是 runtime baseline。ATT 不內置 JDBC driver；需要的 driver/dependency jar 放入 `lib/`。IBM MQ 是 optional integration：default build 在沒有 MQ client class 時仍可使用；MQ deployment 需 package 支援的 IBM client jar/profile。

### Documentation operations

`./att.sh docs` 從已驗證 ATT package model 生成 `build/docs/index.html`。Normative product Reference 則獨立由 `docs/reference*` 經 `tools/build_reference_manual.py` 生成。`./att.sh clean` 刪除文件規定的 generated runtime/build output，但保留 source input。

### CI 與 environment promotion

CI 應先 validate package，再執行 external integration test，並按需要保存 Run/Debug/Load evidence。Environment 透過明確 `--config`/`--env` policy 選擇。穩定 DB/MQ logical ID 讓相同 Template 在 SIT/UAT/PREPROD 間移動而不用改 Action。

Parallel job 應使用唯一 Run ID；若需要獨立 retention/latest-run state，應使用不同 output root。對同一 shared output root 的 clean/report/archive 等 destructive operation 必須序列化。

Maintainer implementation sequencing、scheduler internals、resource-owner detail 位於 `docs/system-design/`，不屬於本 end-user Reference。
''')

    pair("appendices/index.md", """
## 14 Appendices

The appendices collect stable lookup material that should not drive the main product narrative: schema/version matrix, compatibility/deprecations, migration notes, and limits/defaults.
""", """
## 14 附錄

附錄集中保存不應主導主要產品敘事的穩定查閱資料：schema/version matrix、compatibility/deprecation、migration note、limit/default。
""")

    pair("appendices/schema_matrix.md", r'''
### 14.1 Schema and Version Matrix

| Artifact | Current schema |
|---|---|
| Global configuration | `att-config/v2.6` |
| DBHelper | `att-dbhelper/v2.5` |
| MQHelper | `att-mqhelper/v1.0` |
| Tool group | `att-tool-group/v2.6` |
| Sidecar | `att-sidecar/v2.2` |
| Snapshot | `att-testcases/v2.4` |
| Template | `att-template/v3.0` (older supported forms remain readable where compatible) |
| Flow | `att-flow/v3.0` |
| Debug input | `att-debug/v1.0` |
| Load scenario | `att-load/v1.0` |
| Load summary | `att-load-summary/v1.0` |

`schemas/catalog.yaml` is the authoritative repository catalog. Compatibility is a reader contract; new authoring should use the current schema for the feature being authored.
''', r'''
### 14.1 Schema 與版本矩陣

| Artifact | Current schema |
|---|---|
| Global configuration | `att-config/v2.6` |
| DBHelper | `att-dbhelper/v2.5` |
| MQHelper | `att-mqhelper/v1.0` |
| Tool group | `att-tool-group/v2.6` |
| Sidecar | `att-sidecar/v2.2` |
| Snapshot | `att-testcases/v2.4` |
| Template | `att-template/v3.0`（相容的舊格式仍可讀） |
| Flow | `att-flow/v3.0` |
| Debug input | `att-debug/v1.0` |
| Load scenario | `att-load/v1.0` |
| Load summary | `att-load-summary/v1.0` |

`schemas/catalog.yaml` 是 repository 的 authoritative catalog。Compatibility 是 reader contract；新 authoring 應使用相應 feature 的 current schema。
''')

    pair("appendices/compatibility.md", r'''
### 14.2 Compatibility and Deprecated Aliases

Compatibility exists to read established packages without creating a second current model. New authoring uses canonical `EXEC`, `META`, Action-local `output`, current schema versions, `--env`, and current Tool/DB/MQ contracts.

Deterministic legacy aliases may remain readable with migration warnings. Aliases are not created where old semantics conflict with scope isolation or the common result/evidence contract. Deprecated CLI/authoring forms remain documented in their owning chapter or CHANGELOG only when users still need a migration path.
''', r'''
### 14.2 相容性與已棄用 Alias

Compatibility 的目的，是讓既有 package 可讀，而不是維持第二套 current model。新 authoring 使用 canonical `EXEC`、`META`、Action-local `output`、current schema、`--env` 與目前 Tool/DB/MQ contract。

Deterministic legacy alias 在可一對一映射時可以保留並產生 migration warning；若舊語義與 scope isolation 或 common result/evidence contract 衝突，就不建立 alias。Deprecated CLI/authoring form 只有在使用者仍需要 migration path 時才保留在其 owner chapter 或 CHANGELOG。
''')

    pair("appendices/migrations.md", r'''
### 14.3 Migration Notes

The current Reference describes ATT by product concept rather than release chronology. Release-by-release changes remain in `CHANGELOG.md` and `docs/history/`.

Key current migrations are:

- prefer `EXEC` / `META` over legacy Context aliases;
- use `output.result` / `EXEC.ACTIONS.<id>.output.result` and the common evidence/attempt contract;
- treat Tool, DBHelper and MQHelper as peer resources;
- use environment profiles when only typed DB/MQ bindings vary;
- treat Run, Debug and Load as peer execution modes.

The auditable disposition of the pre-#42 monolithic manual is recorded in `docs/reference-migration-map.md`.
''', r'''
### 14.3 遷移說明

Current Reference 依產品概念描述 ATT，不再按 release chronology 組織。逐 release 變更仍保留在 `CHANGELOG.md` 與 `docs/history/`。

目前主要 migration：

- 新 authoring 優先使用 `EXEC` / `META`，而非 legacy Context alias；
- 使用 `output.result` / `EXEC.ACTIONS.<id>.output.result` 及 common evidence/attempt contract；
- 把 Tool、DBHelper、MQHelper 視為 peer resource；
- 若只改 typed DB/MQ binding，使用 environment profile；
- 把 Run、Debug、Load 視為 peer execution mode。

Pre-#42 monolithic manual 的可審核 disposition 記錄在 `docs/reference-migration-map.md`。
''')

    pair("appendices/limits_defaults.md", r'''
### 14.4 Limits and Defaults

Use the owning schema/configuration chapter for normative field defaults. Important architectural limits include:

- load V1 chooses exactly one workload model;
- arrival-rate overload policy is `drop`;
- Flow invocation receives a fresh Action scope and caller scope is restored on return;
- `EXEC.LOAD` exists only for Load iterations;
- resource lifecycle state is not a public Context tree;
- unknown schema fields are rejected except documented extension locations such as root `x-*` where supported.

Operational numeric limits such as timeout ranges, evidence sample bounds, result limits and pool sizes remain defined by their schemas/descriptors so this appendix does not become a second source of truth.
''', r'''
### 14.4 限制與預設值

Normative field default 以其 owner schema/configuration chapter 為準。重要 architecture limit 包括：

- load V1 必須二選一 workload model；
- arrival-rate overload policy 為 `drop`；
- 每次 Flow invocation 有新的 Action scope，返回後恢復 caller scope；
- `EXEC.LOAD` 只在 Load iteration 存在；
- resource lifecycle state 不是 public Context tree；
- 除文件明確允許的 extension location（例如支援位置的 root `x-*`）外，未知 schema field 會被拒絕。

Timeout range、evidence sample bound、result limit、pool size 等 operational numeric limit 仍由對應 schema/descriptor 定義，避免本附錄成為第二個 source of truth。
''')

    # Manifest gains the explicit common-operation peer section.
    manifest = DOCS / "reference-manifest.txt"
    items = [x for x in manifest.read_text(encoding="utf-8").splitlines() if x.strip()]
    if "05_resources/operation_result.md" not in items:
        idx = items.index("05_resources/mqhelper.md") + 1
        items.insert(idx, "05_resources/operation_result.md")
    manifest.write_text("\n".join(items) + "\n", encoding="utf-8")

    write(DOCS / "reference-migration-map.md", r'''
# Reference Manual migration map for issue #42

Status: completed content migration baseline
Source: pre-#42 `09_Reference_Manual_V3` EN/ZH manuals
Target architecture: `docs/documentation-architecture.md`

The same semantic disposition applies to the matching Chinese sections. This map records where normative behavior moved; release-history wording and duplicate tutorials are intentionally not preserved verbatim.

| Legacy major area | Disposition | Current owner |
|---|---|---|
| 01 Introduction | MERGE / REWRITE | Reference 1 Overview; README keeps only orientation |
| 02 Quick Start | DELETE / MERGE | `docs/quick-start.md`; concise contract examples remain in owning Reference chapters |
| 03 User Guide | SPLIT / MOVE | Workbook/Template -> 2; Tool -> 5.1; Run -> 4.1/10; Reports -> 11 |
| 04 Cookbook | DELETE / MERGE | Quick Start and `examples/`; only normative examples remain in Reference |
| 05 CLI Reference | KEEP / REORGANIZE | Reference 10; Debug/Load conceptual material moves to 4.2/4.3 |
| 06 Configuration Reference | KEEP / SPLIT | Field lookup -> 9; environment concept -> 6; resource concept -> 5 |
| 07 Expression Reference | KEEP / REWRITE | Reference 7; Context ownership moved centrally to 3 |
| 08 Report Reference | KEEP / MERGE | Reference 11; common Action evidence links to 3/5.4 |
| 09 Troubleshooting | MOVE / REWRITE | Reference 12 Validation and Diagnostics |
| 10 Architecture for Maintainers | MOVE | `docs/system-design/runtime-execution.md`; public observable contracts stay in Reference |

## Major subsection dispositions

| Legacy subsection/topic | Disposition | Current owner |
|---|---|---|
| package layout / core concepts | MOVE / MERGE | 1 + 2 |
| historical "What V3.x guarantees" | REWRITE | current chapter contracts; chronology -> CHANGELOG/history |
| 3.1 Workbook | MOVE | 2 |
| 3.2 Template / Flow authoring | MOVE / REWRITE | 2; Flow scope -> 3 |
| 3.3 Tool | SPLIT / REWRITE | 5.1; common result/evidence -> 5.4; execution control -> 8 |
| DBHelper embedded in Tool/config chronology | MOVE / REWRITE | 5.2; field lookup -> 9 |
| MQHelper embedded in configuration | MOVE / REWRITE | 5.3; field lookup -> 9 |
| 3.4 Running Tests | SPLIT | 4.1 + 10 + 12 |
| 3.5 Reports | MOVE / MERGE | 11 |
| standalone Debug material | MOVE / REWRITE | 4.2; CLI syntax remains in 10 |
| Load scenario/scheduler/context/report material | MOVE / MERGE / REWRITE | 4.3; Context -> 3; outputs -> 11; CLI -> 10 |
| Environment profiles / secrets / topology | MOVE / REWRITE | 6; field lookup -> 9 |
| EXEC/META/output scattered material | MOVE / MERGE / REWRITE | 3 |
| timeout/retry/evidence collectors | MOVE / MERGE | 8; resource restrictions cross-link from 5 |
| debug/load outputs | MOVE / MERGE | 4.2/4.3 + 11 |
| diagnostics/validation spread across chapters | MERGE | 12 |
| scheduler/thread/resource-owner internals | MOVE | System Design |
| compatibility aliases/deprecations | MERGE | 3 + Appendix 14.2 |
| schema/version compatibility | MERGE | Appendix 14.1 + owning configuration/authoring chapters |
''')

    readme = """# Reference Manual source modules\n\nThese modules are the authoritative editable sources for the current ATT Reference Manual.\n\n- English sources: `docs/reference/`\n- Chinese sources: `docs/reference.zh/`\n- Deterministic order: `docs/reference-manifest.txt`\n- Generated combined outputs: `docs/generated/`\n- Legacy `docs/09_Reference_Manual_V3*` paths are generated compatibility outputs.\n\nAfter editing modules, run:\n\n```sh\npython3 tools/build_reference_manual.py\npython3 tools/validate_reference_content.py\n```\n\nIssue #42 completed the semantic migration into the information architecture defined by `docs/documentation-architecture.md`. Tutorial material belongs in `docs/quick-start.md`; maintainer internals belong in `docs/system-design/`.\n"""
    write(EN / "README.md", readme)
    write(ZH / "README.md", readme)
    rewrite_root_readme()
    print("Issue #42 Reference content migration applied")


if __name__ == "__main__":
    main()
