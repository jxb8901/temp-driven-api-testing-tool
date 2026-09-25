## 03 Runtime 與 Context 模型

ATT 對 Run、Debug 以及每一個 Load iteration 使用同一套公開 Context 模型。

### Canonical tree

```text
EXEC
├── ID
├── RUN_ID
├── STARTED_AT
├── RUN_STARTED_AT
├── OUTPUT_DIR
├── INPUT
├── VARS
└── ACTIONS

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

### 執行身份與診斷資料

身份與時間戳的 scope 依執行模式而異：

| 模式 | `EXEC.ID` | `EXEC.RUN_ID` | `EXEC.STARTED_AT` | `EXEC.RUN_STARTED_AT` |
|---|---|---|---|---|
| Testcase | canonical `workbookId.groupId.rowCaseId`，每個 Case 一個 | 外層 Run ID，同一 Run 的 Cases 共用 | 該 Case 開始時間 | 外層 Run 開始時間；該 Run 的 Cases 共用 |
| Debug | debug ID；同時也是 `EXEC.RUN_ID` | 相同 debug ID | 單次 Debug execution 開始時間 | 與 `EXEC.STARTED_AT` 相同 |
| Load | 每個 iteration 使用 run 內唯一的 `<runId>-execution-<n>` | 外層 Load Run ID；所有 workload/iteration 共用 | 該 iteration 開始時間 | 外層 Load Run 開始時間；iterations 共用 |

例如，同一 Run 的兩個 Case 有不同 `EXEC.ID`，但共用 `EXEC.RUN_ID`：

| Case | `EXEC.ID` | `EXEC.RUN_ID` |
|---|---|---|
| `payments.payment.TC001` | `payments.payment.TC001` | `RUN-42` |
| `payments.payment.TC002` | `payments.payment.TC002` | `RUN-42` |

兩個 Load iteration（可屬於不同 workload 或 VU）共用 `EXEC.RUN_ID`，但 `EXEC.ID` 唯一，例如 `LOAD-7-execution-1` 與 `LOAD-7-execution-2`。Closed VU 的穩定身份另存於 evidence（`DIAG.load.userId`）；fixed-arrival iteration 沒有 persistent VU identity。

這些 framework-owned 欄位不能由輸入覆寫。舊有 `RUN.id` 和 `RUN.runId` 仍確定性地對應 `EXEC.RUN_ID`。

執行模式、時間戳和 load scheduler metadata 保存在 evidence-only `DIAG`，不屬於 expression Context。因此 `${EXEC.MODE}`、`${EXEC.LOAD...}` 和 `${DIAG...}` 均無效。一般 Template/Flow 作者不得引用或依賴 `DIAG` 結構；ATT 可在不提供 Template/Flow 相容保證下新增、移除、重組或重新命名其中欄位。業務差異請透過 `EXEC.INPUT` 傳入；精選 target identity 使用 `META.TARGET`。Load evidence 可包含：

```text
DIAG.load
├── runId
├── workloadId
├── model
├── userId
├── targetType
├── targetId
├── iterationId
├── iteration
└── phase
```

在 `att-load/v1.1` 中，`WORKLOAD_ID` 就是 scenario 配置的 workload `id`；`TARGET_TYPE`、`TARGET_ID` 標識該 workload 固定擁有的 target。Closed workload 對同一 virtual user 提供穩定 `USER_ID`；fixed-arrival-rate iteration 沒有 persistent VU identity。

不同 closed-VU workload pool 可各自有 `VU-1`，因此 evidence 中應用 `(workloadId, userId)` 識別 VU。ATT 不新增 `EXEC.USER` root，也不會在 VU 之間共享 mutable Context；每個 iteration 的 `EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS`、Tool/DB transient state 及 Action-local `output` 仍然彼此隔離。

#### 從 mode-specific Context 遷移

| 舊 expression／用途 | 支援的替代方式 |
|---|---|
| `${EXEC.LOAD.RUN_ID}` | `${EXEC.RUN_ID}` |
| `${EXEC.LOAD.TARGET_TYPE}`／`${EXEC.LOAD.TARGET_ID}` | 有選定 target 時使用 `${META.TARGET.type}`／`${META.TARGET.id}`（例如 Debug/Load） |
| 按 mode、workload、VU 或 phase 分支業務行為 | 透過 adapter 提供的 `${EXEC.INPUT.<name>}` 明確傳入業務 selector |
| 在 expression 讀取 scheduler／diagnostic 資料 | 在 expression 之外檢查保留的 `DIAG` evidence；Template/Flow 不得引用 `DIAG` |

### Optional lookup

`${path}` 是 strict lookup。`${path?}` 在定義允許的 missing map/list path 上返回 null，但不會把 malformed syntax、ambiguity、invalid traversal 或越權 scope access 變成合法。

### Compatibility aliases

`CASE`、`RUN`、`ACTIONS` 等 deterministic legacy view 在能與 canonical data 一對一映射時仍可讀，並可能產生 migration warning。新文件與新 authoring 使用 `EXEC`/`META`；不能保持相同語義的歷史 path 不會偽裝成 alias。
