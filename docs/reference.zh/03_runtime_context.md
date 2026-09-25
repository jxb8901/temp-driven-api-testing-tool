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

`EXEC.LOAD` 只是加在同一 Context 上的 conditional data，不是第二套 runtime。它可包含：

```text
EXEC.LOAD
├── RUN_ID
├── WORKLOAD_ID     # att-load/v1.1 multi-workload run
├── MODEL
├── USER_ID         # 只適用 closed-VU
├── TARGET_TYPE     # v1.1 workload 固定 target 身份
├── TARGET_ID       # v1.1 workload 固定 target 身份
├── ITERATION_ID
├── ITERATION
├── PHASE
└── RUN_STARTED_AT
```

在 `att-load/v1.1` 中，`WORKLOAD_ID` 就是 scenario 配置的 workload `id`；`TARGET_TYPE`、`TARGET_ID` 標識該 workload 固定擁有的 target。Closed workload 對同一 virtual user 提供穩定 `USER_ID`；fixed-arrival-rate iteration 沒有 persistent VU identity。

不同 closed-VU workload pool 可以各自出現 `VU-1`，因此跨 workload 的完整 VU 身份是 `(EXEC.LOAD.WORKLOAD_ID, EXEC.LOAD.USER_ID)`。ATT 不會新增 `EXEC.USER` root，也不會在 VU 之間共享 mutable Context；每個 iteration 的 `EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS`、Tool/DB transient state 及 Action-local `output` 仍然彼此隔離。

### Optional lookup

`${path}` 是 strict lookup。`${path?}` 在定義允許的 missing map/list path 上返回 null，但不會把 malformed syntax、ambiguity、invalid traversal 或越權 scope access 變成合法。

### Compatibility aliases

`CASE`、`RUN`、`ACTIONS` 等 deterministic legacy view 在能與 canonical data 一對一映射時仍可讀，並可能產生 migration warning。新文件與新 authoring 使用 `EXEC`/`META`；不能保持相同語義的歷史 path 不會偽裝成 alias。
