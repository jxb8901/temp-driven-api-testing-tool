# 可靠性與執行控制

本頁定義 Native Flow Actions 及其 Resource calls 的共通行為。

## Assertion 與 status

Assertion 會在文件指定的 assertion point、Action primary work 之後評估 boolean condition。False assertion 是 `FAIL`；exception 或 infrastructure problem 是 `ERROR`；authoring 或 configuration 無效是 `INVALID`；未選中的條件是 `SKIPPED`；成功工作是 `PASS`。Operation failure 與 assertion failure 是不同概念。

## `runWhen` 與 `onFailure`

`runWhen` 決定 Action 是否 eligible to execute。`onFailure: stop|continue` 決定 failure 後是否繼續；`continue` 不會把 failed status 改為 PASS。Cleanup 和 diagnostics 應使用 conditional execution，而不是隱藏 primary operation failure。

## Timeout

Timeout 會依 backend 支援能力終止或放棄 operation，並記錄 diagnostic evidence。Timeout 是 operational failure，不是 false assertion。Tool timeout 行為及 DB/MQ/HTTP/SSH limits 由各 Resource contract 定義。

## Retry 與 attempts

在支援 retry 的位置，一個 logical Action 可有多個 attempts。Retry policy 決定哪些 operation failure 可重試。最後或勝出的 operation 會成為 top-level `output.result` 與 `output.evidence`；每次 attempt 均保留在 `output.attempts[n]`。後續成功不會抹去較早的 attempt evidence。

## Evidence collectors

Tool evidence collectors 會在 primary operation 發布 typed `output.result` 後、該次 attempt assertion 前執行。Action 執行期間可用 `${output.evidence.collectors.<id>.result}` 與 `${output.evidence.collectors.<id>.status}`；發布後的 canonical path 為 `${EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result}` 與 `.status`。Collector 有獨立的 `timeoutMs` 和 `onFailure: continue|stop`。Collector output 屬於 attempt evidence，不會取代 primary operation result。

每次 primary attempt 都會執行 collector。Top-level collector node 代表最後或勝出的 attempt；`output.attempts[n].evidence.collectors.<id>` 則保留各次 attempt。`continue` 會在 diagnostic collection 失敗時保留 primary outcome；`stop` 會令 collector failure 成為 Action error。若收集值是 business/test data，而不是 assertion 前的診斷資料，請使用一般 Tool 或 Log Action。

## Transaction 與 Resource lifecycle

DB transaction finalization 及 DB/MQ/HTTP/SSH cleanup 會在適當的 execution boundary 執行。這些機制可能影響 operation success 和 diagnostics，但屬於 internal Resource state，不是 public Context namespace。

## Aggregation

多個 child outcome 聚合時會保留嚴重程度：

~~~text
ERROR > INVALID > FAIL > PASS > SKIPPED
~~~

## Tool timeout precedence

Tool Action timeout 優先於 Tool descriptor timeout，再優先於 global timeout。Call-backed DB Tool 的 DBHelper statement timeout 仍是 backend ceiling。每次支援的 primary retry attempt 都會在 assertion 前執行 collectors。

## Direct DB timeout 與 retry eligibility

Direct DB Actions 可設定 `timeoutMs`，範圍為 1 至 3,600,000 ms。設定時，`Action.timeoutMs` 會覆蓋 `DBHelper.statement.timeoutSeconds`；否則使用 helper timeout。每次 retry attempt 都會重新取得完整 Action timeout，retry interval 不計入該 timeout。

Direct `query` Action 亦可使用標準 retry block：`maxAttempts` 2–10、`intervalMs` 0–3,600,000，`retryOn` 是非空且不重複的 `ASSERTION`、`TIMEOUT` 清單。使用 `ASSERTION` 必須同時定義 Action `assert`。一般 SQL errors 為 terminal。啟用 retry 後，每次 query attempt 會保留在 `output.attempts[n]`；top-level `output.result` 與 `output.evidence` 代表最後或勝出的 attempt，並以 `winningAttempt` 或 `finalAttempt` 記錄終止 attempt 編號。

Direct `update` Actions 支援 `timeoutMs`，但拒絕 `retry`。Timeout 或 database/transport failure 發生後，ATT 通常無法判定 mutation 是否已送達或 commit；自動重放可能重複修改業務資料。因此，application-specific idempotent retry 必須明確建模。
