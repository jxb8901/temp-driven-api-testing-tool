# 可靠性與執行控制

本章集中定義 cross-cutting public execution behavior。

## Assertion 與 status

Assertion 在文件規定的 assertion point、primary work 之後評估 boolean condition。False assertion 是 `FAIL`；exception/infrastructure problem 是 `ERROR`；authoring/configuration 無效是 `INVALID`；條件未選中是 `SKIPPED`；成功工作是 `PASS`。因此 operation failure 與 assertion failure 是不同概念。

## `runWhen` 與 `onFailure`

`runWhen` 決定 statically known Action/Stage 是否 eligible；`onFailure: stop|continue` 決定 failure 後是否繼續。`continue` 不會把 failed status 改成 PASS。Cleanup/diagnostic 應使用規範的 conditional execution semantics，而不是隱藏 failure。

## Timeout

Timeout 依 backend 支援能力終止或放棄 operation，並記錄 diagnostic/evidence。Timeout 是 operational failure，不是 assertion false。Tool timeout 與 resource-specific DB/MQ/HTTP/SSH limit 分別由其 resource contract 定義。

## Retry 與 attempts

在支援 retry 的位置，一個 logical Action 可以擁有多個 attempt。Retry policy 決定哪些 operation failure 可重試。最後／勝出的 operation 成為 top-level `output.result` / `output.evidence`；每次 attempt 保留在 `output.attempts[n]`。後續成功不會抹掉較早 attempt evidence。

## Evidence collectors

Tool evidence collector 在 primary operation 發布 typed `output.result` 後、該 attempt assertion 前執行。Action active 時可使用 `${output.evidence.collectors.<id>.result}` 與 `${output.evidence.collectors.<id>.status}`；發布後的 canonical path 是 `${EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result}` 和 `.status`。Collector 有獨立 `timeoutMs` 與 `onFailure: continue|stop`；collector output 屬於該 attempt evidence，不會取代或修改 primary operation result。

每個 primary attempt 都會執行 collector。Top-level collector node 代表最後／勝出的 attempt，`output.attempts[n].evidence.collectors.<id>` 則保留各 attempt。`continue` 讓 primary/assertion outcome 在診斷收集失敗時仍可觀察；`stop` 令 collector failure 成為 Action error。若收集的值是 business/test data，而不是 pre-assertion 診斷資料，應使用普通 Tool/Log/Assign Action。

## Transaction/Resource lifecycle

DB transaction finalization 與 DB/MQ/HTTP/SSH resource cleanup 在相應 execution lifecycle boundary 進行。它們可能影響 operation success/diagnostic，但屬 internal resource state，不是 public Context namespace。

## Aggregation

多個 child outcome 聚合時保留嚴重度：

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

## Stage execution controls

| Setting | Values/default | 說明 |
|---|---|---|
| `required` | boolean / `false` | Blank selector 是否為 error |
| `runWhen` | `normal`（default）、`onSuccess`、`onFailure`、`always` | Stage eligibility |
| `onFailure` | `stop`（default）、`continue` | Failure 後是否允許後續 eligible work |

`continue` 不會把 FAIL 或 ERROR 改成 PASS。

| Earlier outcome | Later `normal` | `onSuccess` | `onFailure` | `always` |
|---|---:|---:|---:|---:|
| PASS | Run | Run | Skip | Run |
| FAIL/ERROR with `stop` | Skip | Skip | Run | Run |
| FAIL/ERROR with `continue` | Run | Skip | Run | Run |

Rollback/diagnostics 使用 `onFailure`；cleanup 或 final Evidence 使用 `always`。

## Tool Timeout precedence

Tool Action timeout 優先於 Tool descriptor timeout，再優先於 global timeout。Sidecar、Stage 與 Template 不定義 timeout/retry defaults。Call-backed DB Tool 的 DBHelper statement timeout 仍是 backend ceiling。每次 supported primary retry attempt 都會在 assertion 前執行 collectors。

## Direct DB timeout 與 retry eligibility

Direct DB Action 可設定 `timeoutMs`，範圍為 1 至 3,600,000 ms。明確的 Action timeout 會覆蓋 DBHelper `statement.timeoutSeconds` 預設值；未設定時才使用 helper timeout。JDBC statement timeout 以秒向上取整，ATT 仍保留毫秒級 deadline cancellation；每次 retry attempt 都重新取得完整 Action timeout，`retry.intervalMs` 的等待時間不計入該 attempt timeout。

Direct `query` Action 亦可使用標準 retry block：`maxAttempts` 2–10、`intervalMs` 0–3,600,000，`retryOn` 必須是非空且不重複的 `ASSERTION` / `TIMEOUT` 列表。使用 `ASSERTION` 時必須同時定義 Action `assert`。一般 SQL error 為 terminal，不會自動 retry。啟用 retry 後，每次 query attempt 會保留在 `output.attempts[n]`；top-level `output.result` / `output.evidence` 永遠代表 final 或 winning attempt，並以 `winningAttempt` 或 `finalAttempt` 記錄終止 attempt 編號。

Direct `update` Action 支援 `timeoutMs`，但明確拒絕 `retry`。發生 timeout 或 database/transport failure 後，ATT 通常無法證明 mutation 是否已送達或 commit；自動重放可能造成重複業務變更。因此，需要 application-specific idempotent retry 時應由作者明確建模，而不是啟用通用 DB Action retry。
