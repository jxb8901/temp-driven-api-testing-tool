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

Tool evidence collector 在 primary operation 發布 typed `output.result` 後、該 attempt assertion 前執行。Action active 時可使用 `${output.evidence.collectors.<id>.result}` 與 `${output.evidence.collectors.<id>.status}`；發布後的 canonical path 是 `${EXEC.ACTIONS.<actionId>.output.evidence.collectors.<id>.result}` 和 `.status`。Collector 有獨立 `timeoutMs` 與 `onFailure: continue|stop`；collector output 屬於該 attempt evidence，不會取代或修改 primary operation result。

每個 primary attempt 都會執行 collector。Top-level collector node 代表最後／勝出的 attempt，`output.attempts[n].evidence.collectors.<id>` 則保留各 attempt。`continue` 讓 primary/assertion outcome 在診斷收集失敗時仍可觀察；`stop` 令 collector failure 成為 Action error。若收集的值是 business/test data，而不是 pre-assertion 診斷資料，應使用普通 Tool/Log/Assign Action。

### Transaction/resource lifecycle

DB transaction finalization 與 DB/MQ resource cleanup 在相應 execution lifecycle boundary 進行。它們可能影響 operation success/diagnostic，但屬 internal resource state，不是 public Context namespace。

### Aggregation

多個 child outcome 聚合時保留嚴重度：

```text
ERROR > INVALID > FAIL > PASS > SKIPPED
```

未來 fixture（#38）與 DB Action-level timeout/retry（#39）應延伸本章既有概念，而不是再建立一套 reliability model。
