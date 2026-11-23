# Runtime 和 Context model

RUN、Debug 和 LOAD 共用 canonical `EXEC` 與 `META` expression root。`EXEC` 保存 identity、typed input、variable 和已完成 Action。`META` 描述所選 source、target、Flow 和 active Resource invocation。這些 root 不包含 secret。

Standalone Debug 會將 `inputs` 映射到 `EXEC.INPUT`；選用的 `vars` 會初始化 `EXEC.VARS`。Input schema 和受保護的 root 見 [Standalone Debug](execution-modes/debug.md)。

## EXEC roots

| Path | 意義與可用時機 |
|---|---|
| `EXEC.ID` | 目前 Case execution、Debug execution 或 LOAD iteration ID；初始化後可用。 |
| `EXEC.RUN_ID` | Enclosing ATT Run ID；同一 run 的 Cases 和 iteration 共用。 |
| `EXEC.STARTED_AT` | 目前 execution 的 ISO-8601 起始時間。 |
| `EXEC.RUN_STARTED_AT` | Enclosing run 的 ISO-8601 起始時間。 |
| `EXEC.OUTPUT_DIR` | Execution workspace path；LOAD 中可能先規劃、之後才建立目錄。 |
| `EXEC.INPUT` | Case、Debug target 或 LOAD workload 的 typed input map。 |
| `EXEC.VARS` | 由 `assign` 建立、由 `set` 更新，或由 Debug bootstrap 提供的 typed value。 |
| `EXEC.ACTIONS` | Active Flow scope 中已完成的 Action。巢狀 Flow 使用獨立 scope。 |
| `EXEC.LOAD.*` | 只在 LOAD 中提供 workload 和 iteration identity。Arrival-rate workload 不提供 `USER_ID`。 |

Scheduler counter、queue state 和 timing diagnostic 保留於 evidence，不屬於 expression root。可重用 Flow 應透過 input contract 取得 business identifier，不要依賴 `EXEC.ID`。

## META 欄位

| Path | 意義與可用時機 |
|---|---|
| `META.SOURCE.type` | Source 類型：`testcase`、`debug` 或 `load`。 |
| `META.SOURCE.path` | Source 有檔案時的 Package-relative logical path。 |
| `META.SOURCE.caseId` | Case RUN 中的 qualified Case ID；Debug 和 LOAD 時缺席。 |
| `META.SOURCE.scenario` | LOAD scenario identity；非 LOAD 時缺席。 |
| `META.TARGET.type` | 已解析 target 類型：Case、Flow 或 Tool。 |
| `META.TARGET.id` | 已解析的 target identifier。 |
| `META.FLOW.id` | Flow invocation 期間的 active Flow ID。 |
| `META.FLOW.invocationId` | Invocation 期間呼叫該 Flow 的 Action ID。 |
| `META.FLOW.depth` | 由 1 起始的巢狀 Flow depth。 |
| `META.TOOL.id`、`META.TOOL.type` | Active configured Tool 或 built-in invocation。 |
| `META.DBHELPER.*`、`META.MQHELPER.*`、`META.HTTPHELPER.*`、`META.SSHHELPER.*` | 對應 invocation 期間的 logical Resource identity。 |

META 是不可變的描述資料。Component 未啟用時，相應欄位會缺席。Resource endpoint、username、identity file 和 credential 會留在 executor 內，不會發布為 META value。

## Invocation 和 scope 規則

進入 Flow、Tool 或 Resource invocation 時會發布其 active metadata。巢狀呼叫會 push scope frame，返回時恢復 caller metadata。沒有 active invocation 時，相應 branch 缺席；不要依賴「最後一次呼叫」狀態。

`EXEC.INPUT` 是目前 execution 的 input map。`EXEC.VARS` 可供同一 Flow invocation 內後續 Action 使用。`EXEC.ACTIONS` 保存該 Flow scope 中已完成的 Action result。Action 執行時讀取 local `output`；完成後 value 會發布至 `EXEC.ACTIONS.<id>.output`。

## Optional lookup

`${path}` 是 strict lookup。`${path?}` 只會對允許的缺失 map/list path 回傳 null，不會讓無效語法或不允許的 scope access 變成有效。

既有 Package 若依賴較早的 Context name 或 metadata，請使用[舊版文件](../legacy/README.zh.md)。[Actions Reference](actions.md) 定義 Action result；[Debug](execution-modes/debug.md) 和 [LOAD](execution-modes/load.md) 定義各自的 input initialization rules。
