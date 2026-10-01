## 04 Runtime 與 Context Model

Run、Debug、Load 共用 canonical EXEC/META expression model。EXEC 透過 framework lifecycle 和明確的 input/variable/action publication 更新。META 是 curated、immutable、secret-safe 的描述資訊。

Standalone Debug bootstrap value 會映射到這些 canonical root：`inputs` 寫入 `EXEC.INPUT`，Template/Flow 的 `vars` 會在 target 開始前 seed `EXEC.VARS`。v1.1 schema、typed literal 規則及受保護的 framework roots 請參考 [Standalone Debug](04_execution_modes/debug.md)。

### Identity roots

| 路徑 | 意義與型別 | 可用時機 |
|---|---|---|
| EXEC.ID | 目前 Case、Debug execution 或 Load iteration ID；String。 | execution initialization 後；Load 在 business action 前產生。 |
| EXEC.RUN_ID | 外層 ATT Run ID；String。 | 三種模式均可用，同一 run 的 Cases/iterations 共用。 |
| EXEC.STARTED_AT | 目前 execution 開始時間；ISO-8601 String。 | 三種模式均可用。 |
| EXEC.RUN_STARTED_AT | 外層 run 開始時間；ISO-8601 String。 | 三種模式均可用。 |
| EXEC.OUTPUT_DIR | execution workspace 絕對路徑。 | 初始化後可用；Load 中可能先有 planned path，尚未實際建立。 |
| EXEC.INPUT | Case/stage、Debug sidecar 或 Load workload input map。 | 三種模式均可用；Stage input 暫時 overlay Case input。 |
| EXEC.VARS | assign 發布的型別化值。 | 三種模式均可用；指派前為空。 |
| EXEC.ACTIONS | active Template/Flow scope 已完成的 Actions。 | 發布 Action 後可用；Flow 有巢狀 scope。 |
| EXEC.LOAD.MODEL | closed 或 arrivalRate。 | 僅 Load；ID 產生前可用。 |
| EXEC.LOAD.WORKLOAD_ID | 配置的 workload ID。 | 僅 Load；ID 產生前可用。 |
| EXEC.LOAD.USER_ID | 穩定 virtual user ID。 | 僅 closed Load；arrival-rate 缺席。 |
| EXEC.LOAD.ITERATION | 數值 iteration 序號。 | 僅 Load；ID 產生前可用。 |
| EXEC.LOAD.PHASE | WARMUP、RAMP_UP、STEADY 或 RAMP_DOWN。 | 僅 Load；ID 產生前可用。 |

EXEC.LOAD 只公開穩定 identity。Scheduler counter、queue state 與 timing diagnostics 留在 evidence-only DIAG.load，不能作為 expression root。

### META 欄位清單與生命週期

公開 META root 只包含下表列出的 `PROJECT`、`SOURCE`、`TARGET`、`TEMPLATE`、`FLOW`、`TOOL`、`DBHELPER`、`MQHELPER` 和 `HTTPHELPER`。META 只包含描述欄位。元件在目前 mode/scope 尚未 active 時，相應路徑可能不存在。

| 公開路徑 | 意義、type 與範例 | Mode 與可用時機 | Scope 與缺席時機 |
|---|---|---|---|
| META.PROJECT.id | Project 目錄名稱；String，例如 `payment-att`。 | Run、Debug、Load；project 綁定後。 | Execution-wide。 |
| META.PROJECT.root | 正規化 project root 路徑；String，例如 `/srv/att/payment`。 | Run、Debug、Load；project 綁定後。 | Execution-wide。 |
| META.SOURCE.type | Source 類型；String：`testcase`、`debug` 或 `load`。 | Run、Debug、Load。 | Execution-wide。 |
| META.SOURCE.path | 正規化絕對 source path；String，例如 `/srv/att/payment/testcase/payment.xlsx`、`/srv/att/payment/debug.yaml` 或 `/srv/att/payment/load/payment.yaml`。 | Run、Debug、Load，source file 存在時。 | Execution-wide；memory source 可缺席。 |
| META.SOURCE.caseId | Canonical TestCase 或 synthetic Debug Case ID；String，例如 `payment.default.P001`。 | Run、Debug。 | Execution-wide；Load 缺席。 |
| META.SOURCE.workbookId | Workbook identifier；String，例如 `payment`。 | Run。 | Execution-wide；非 workbook Case 缺席。 |
| META.SOURCE.groupId | Workbook group identifier；String，例如 `default`。 | Run。 | Execution-wide；非 workbook Case 缺席。 |
| META.SOURCE.rowCaseId | Workbook row 的 Case ID；String，例如 `P001`。 | Run。 | Execution-wide；非 workbook Case 缺席。 |
| META.SOURCE.sheet | Workbook sheet 名稱；String，例如 `Payment`。 | Run，adapter 有提供時。 | Execution-wide；Debug/Load 或無值時缺席。 |
| META.SOURCE.row | Workbook 由 1 起始的 row number；Number，例如 `12`。 | Run，adapter 有提供時。 | Execution-wide；Debug/Load 或無值時缺席。 |
| META.SOURCE.workbook | 可選 workbook label；有值時為 String，例如 `payment_regression.xlsx`。 | Run，adapter 有提供時。 | Execution-wide；否則缺席。 |
| META.SOURCE.scenario | 不含副檔名的 Load scenario 名稱；String，例如 `payment-smoke`。 | Load。 | Execution-wide；非 Load 時缺席。 |
| META.TARGET.type | 已解析 target 類型；String：`testcase`、`template`、`flow` 或 `tool`。 | Run、Debug、Load；選定 target 後。 | Execution-wide。 |
| META.TARGET.id | 已解析 target identifier；String，例如 `PAYMENT_INVOKE` 或 `payment.flow.v1`。 | Run、Debug、Load；選定 target 後。 | Execution-wide。 |
| META.TEMPLATE.id | Active Template 或已解析 Load execution-wrapper ID；String，例如 `PAYMENT_INVOKE`。 | Run/Debug 的 Stage 執行期間；Load 的 target resolution 後及 wrapper 執行期間。 | Component scope；解析前缺席，scope 結束後 restore/remove。Load Flow/Tool target 使用已解析的 synthetic wrapper。 |
| META.TEMPLATE.path | 正規化 Template 或 execution-wrapper 目錄；String，例如 `/srv/att/templates/PAYMENT_INVOKE`。 | 與 META.TEMPLATE.id 相同。 | Component scope；解析前缺席，scope 結束後 restore/remove。 |
| META.FLOW.id | Active Flow ID；String，例如 `payment.request.v1`。 | Run、Debug、Load 的 Flow invocation 期間。 | Invocation scope；進入 push、返回 restore、inactive 時缺席。 |
| META.FLOW.invocationId | Caller Action ID；String，例如 `sendRequest`。 | 與 META.FLOW.id 相同。 | 沒有 active Flow 時缺席。 |
| META.FLOW.depth | 由 1 開始的巢狀 Flow 深度；Number，例如 `1`。 | 與 META.FLOW.id 相同。 | 沒有 active Flow 時缺席。 |
| META.TOOL.id | Active configured Tool 或 built-in 名稱；String，例如 `payment.queryOrder` 或 `upper`。 | Run、Debug、Load invocation 期間。 | Invocation scope；返回後不保留最後一次 Tool。 |
| META.TOOL.type | Invocation 類型；String，例如 `tool` 或 `builtin`。 | 與 META.TOOL.id 相同。 | 外層 invocation 不存在時，返回後缺席。 |
| META.DBHELPER.id | Logical DBHelper ID；String，例如 `orders`。 | Run、Debug、Load 的 DBHelper operation/DB Action 期間。 | Invocation scope；外層 scope 不存在時返回後缺席。 |
| META.DBHELPER.type | Resource 類型；String，`dbhelper`。 | 與 META.DBHELPER.id 相同。 | 外層 scope 不存在時返回後缺席。 |
| META.MQHELPER.id | Logical MQHelper ID；String，例如 `payment`。 | Run、Debug、Load 的 MQHelper operation 期間。 | Invocation scope；push/restore，返回後無外層 scope 時缺席。 |
| META.MQHELPER.type | Resource 類型；String，`mqhelper`。 | 與 META.MQHELPER.id 相同。 | 返回後無外層 scope 時缺席。 |
| META.HTTPHELPER.id | Logical HTTPHelper ID；String，例如 `payment`。 | Run、Debug、Load 的 HTTPHelper operation 期間。 | Invocation scope；push/restore，返回後無外層 scope 時缺席。 |
| META.HTTPHELPER.type | Resource 類型；String，`httphelper`。 | 與 META.HTTPHELPER.id 相同。 | 返回後無外層 scope 時缺席。 |

META.SSHHELPER 不是公開欄位。SSH connection 與 credential settings 留在 Tool invocation 內部。META.TOOL 可識別 active Tool；SSH endpoint、user、identity file、credentials 不會公開在 META。

ATT 會遞迴過濾 password、secret、token、authorization/cookie、API key、private key 等 secret-bearing keys。Expressions 與 adapters 可讀 META，但不能修改。

### Invocation 與 scope 規則

進入 Template、Flow、Tool、helper invocation 時，公開該 active scope 的 metadata。巢狀呼叫會 push frame，離開時 restore 前一份 metadata。沒有 active invocation 時，其 branch 缺席。請勿依賴「最後一次呼叫」狀態。

EXEC.INPUT 是 canonical input map。Stage 暫時 overlay Case input，完成後還原。EXEC.VARS 可供同一 Case 後續 Stages 共用。EXEC.ACTIONS 屬於 active Template/Flow。Action 執行期間讀 local output，完成後發布到 EXEC.ACTIONS.<id>.output。

### Optional lookup 與相容性

${path} 是 strict lookup。${path?} 在允許的 map/list 缺失路徑回傳 null；不會讓錯誤語法或不合法 scope access 變有效。CASE、RUN、ACTIONS alias 只在能一對一對應 canonical data 時保留。新 Template 請使用 EXEC/META。

### Lifecycle 導覽

Action-local `output` 與發布後的 `EXEC.ACTIONS.<id>.output` contract 見[Actions](14_actions.md)。[Debug](04_execution_modes/debug.md) 與 [Load](04_execution_modes/load.md) 分別定義 bootstrap vars、identity initialization 與可用 scope；[Results](11_results_reports_evidence.md) 定義 artifact navigation。
