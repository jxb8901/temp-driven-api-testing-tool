## 03 Runtime 與 Context 模型

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

公開 META root 只包含下表列出的 `PROJECT`、`SOURCE`、`TARGET`、`TEMPLATE`、`FLOW`、`TOOL`、`DBHELPER`、`MQHELPER`、`HTTPHELPER` 和 `SSHHELPER`。META 只包含描述欄位。元件在目前 mode/scope 尚未 active 時，相應路徑可能不存在。

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
| META.SSHHELPER.id | Logical SSHHelper ID；String，例如 `application`。 | Run、Debug、Load 的 SSH Resource Helper operation 期間。 | Invocation scope；push/restore，返回後無外層 scope 時缺席。 |
| META.SSHHELPER.type | Resource 類型；String，`sshhelper`。 | 與 META.SSHHELPER.id 相同。 | 返回後無外層 scope 時缺席。 |

META.SSHHELPER 只公開 logical helper ID 和 resource type。SSH endpoint、user、identity file、credentials 留在 executor 內部，不會公開在 META。

ATT 會遞迴過濾 password、secret、token、authorization/cookie、API key、private key 等 secret-bearing keys。Expressions 與 adapters 可讀 META，但不能修改。

### Invocation 與 scope 規則

進入 Template、Flow、Tool、helper invocation 時，公開該 active scope 的 metadata。巢狀呼叫會 push frame，離開時 restore 前一份 metadata。沒有 active invocation 時，其 branch 缺席。請勿依賴「最後一次呼叫」狀態。

EXEC.INPUT 是 canonical input map。Stage 暫時 overlay Case input，完成後還原。EXEC.VARS 可供同一 Case 後續 Stages 共用。EXEC.ACTIONS 屬於 active Template/Flow。Action 執行期間讀 local output，完成後發布到 EXEC.ACTIONS.<id>.output。

### Action output 與 evidence path

| Path | 意義與可用時機 |
|---|---|
| `output.result` | Action active（包括 assertion）期間的 primary typed result。 |
| `output.evidence.collectors.<id>.result` | Active Tool evidence collector 的 typed result。 |
| `output.evidence.collectors.<id>.status` | Collector 的 `PASS`／`ERROR` status。 |
| `output.evidence.collectors.<id>.error` | Collector 失敗時的 bounded failure summary；有可用訊息時包含非空 `message`。 |
| `output.evidence.collectors.<id>.evidence` | 保留 bounded/redacted 的 underlying Tool/resource evidence，包括 executor 提供的 resource identity 與 native failure fields。 |
| `EXEC.ACTIONS.<actionId>.output.result` | Action 完成後發布的 primary typed result。 |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result` | 發布後最後／勝出的 collector result。 |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.status` | 發布後最後／勝出的 collector status。 |
| `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.error/evidence` | 發布後的 collector failure summary 與保留的 operation evidence。 |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.result/status` | 指定 retry attempt 的 collector result/status；後續成功後仍保留較早 attempt。 |
| `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<id>.error/evidence` | 該 collector attempt 的 failure summary 與 underlying evidence。 |

String、Number、Boolean、null、Map、List 等值跨越 Action/Template/Flow boundary 時都保留原型別。Render 發布原樣 String，不建立額外的文件 wrapper。

### Execution bootstrap variables

Debug sidecar 與現行 Load workload 可為 Template/Flow 提供 canonical 初始 `EXEC.VARS` tree。初始化順序為：解析及驗證 target/definitions；建立 `EXEC.RUN_ID`、`EXEC.INPUT`、`EXEC.LOAD`、穩定 META 與 timestamps；產生並發布 `EXEC.ID`；設定 `EXEC.OUTPUT_DIR`；評估 vars；最後啟動 Template/Flow。Debug 亦使用相同規則及已初始化 identity/output path。

Values 使用 ATT 一般 expression parser。完整 `${...}` 保留 reference 原生型別（包括 null、number、list、map）；混合文字成為字串；`#{...}` 保留 typed result。Map/list 遞迴處理而 key 維持字面值。Vars dependency 不受宣告順序影響；missing bootstrap var 及直接／間接循環會報錯。每個 Load execution 都評估獨立複本，併發 user/workload 不共用 mutable values。第一次一般 `assign` 可取代初始值。

可用 root 包括初始化完成的 `EXEC.RUN_ID`、`EXEC.ID`、`EXEC.OUTPUT_DIR`、`EXEC.INPUT`、`EXEC.LOAD`、其他命名的 `EXEC.VARS`，以及穩定 META project/source/target/template metadata。Actions、action-local `output`、invocation-scoped META 和 external/stateful calls 不可用。只允許安全 pure built-in，並沿用相同 expression syntax/type rules。`--set vars.path=value` 會在 evaluation 前修改原始 definition。

### Load execution ID initialization

Load 使用 att-load/v1.4。設定 execution.execIdFormat 時，ATT 在每個 iteration initialization 使用一般 ${...} / #{...} engine 求值一次；省略時維持預設 run-scoped ID。Bootstrap vars 會在生成 ID 及 output path 發布後評估。

可用值有 EXEC.RUN_ID、timestamps、EXEC.INPUT、EXEC.LOAD.MODEL/WORKLOAD_ID/ITERATION/PHASE、closed-only EXEC.LOAD.USER_ID，以及已建立的 META.PROJECT/SOURCE/TARGET/TEMPLATE。EXEC.ID 和 EXEC.OUTPUT_DIR 尚未可用，因為生成的 ID 決定 workspace。還沒有 Action 執行，所以 EXEC.ACTIONS 與 Flow/Tool/helper invocation META 缺席。

只允許 deterministic、side-effect-free built-ins。External Tool/DB/MQ/HTTP/SSH calls 及 stateful、random、clock、filesystem functions 會被拒絕。seq.next() 不允許也不需要。請使用穩定 identity：

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-${EXEC.LOAD.USER_ID}-${EXEC.LOAD.ITERATION}"
~~~

Arrival-rate 沒有 USER_ID：

~~~yaml
execution:
  execIdFormat: "${EXEC.RUN_ID}-${EXEC.LOAD.WORKLOAD_ID}-arrival-${EXEC.LOAD.ITERATION}"
~~~

ID 必須非空、安全且為單一路徑 segment，並在 Load run 內唯一。重複或不安全值會在 target 開始前失敗；ATT 不會附加隱藏 suffix。

### Run、execution 與 evidence 導覽

| Identity | 意義 | Scope | Artifact 用途 |
|---|---|---|---|
| EXEC.RUN_ID | 外層 ATT run。 | Run。 | Run root、summary、report。 |
| EXEC.ID | 目前 Case/Debug/Load execution。 | Execution。 | Workspace 建立時作為 log/evidence key。 |
| EXEC.OUTPUT_DIR | 與 EXEC.ID 關聯的 workspace。 | Execution。 | Run/Debug 實體 workspace 或 Load planned lazy workspace。 |

一般 Run 的功能性 Case 位於 output/<RUN_ID>/executions/<EXEC.ID>/。Load 執行期間，EXEC.OUTPUT_DIR 與 CASE.outputDirectory 固定指向 output/load/<RUN_ID>/executions/<EXEC.ID>/。Iteration 被保留時，artifact 也會複製到 samples/<EXEC.ID>/ 或 failures/<EXEC.ID>/。Metrics-only iteration 有 EXEC.ID；scheduler 清理暫存 workspace 後不保留 per-iteration 目錄。Load report 顯示 retained rows 的 EXEC.ID，有保留 case.log 時提供連結。Debug 使用同一 debug ID 作為 EXEC.RUN_ID 與 EXEC.ID。

DIAG 是 evidence-only。Expression 不可讀取 DIAG、EXEC.MODE 或任意 scheduler counter；業務差異請透過 EXEC.INPUT 傳入。

### Optional lookup 與相容性

${path} 是 strict lookup。${path?} 在允許的 map/list 缺失路徑回傳 null；不會讓錯誤語法或不合法 scope access 變有效。CASE、RUN、ACTIONS alias 只在能一對一對應 canonical data 時保留。新 Template 請使用 EXEC/META。
