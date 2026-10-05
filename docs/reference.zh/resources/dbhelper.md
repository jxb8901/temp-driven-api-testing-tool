# DBHelper

DBHelper 是獨立於 Tool 的一級 JDBC resource。每個 descriptor 使用 `schemaVersion: att-dbhelper/v2.6` 和穩定 logical `id`；global `dbhelpers` 只引用 descriptor file。

```yaml
schemaVersion: att-dbhelper/v2.6
id: orders
name: Orders database
description: Orders JDBC resource
connection:
  driverClass: oracle.jdbc.OracleDriver
  url: ${ENV:ORDERS_DB_URL}
  username: ${ENV:ORDERS_DB_USERNAME}
  password: ${ENV:ORDERS_DB_PASSWORD}
```

Credential 可從 environment variable 解析，但不能發布到 `META`、report 或 diagnostic。JDBC driver jar 由使用者放入 `lib/`；ATT 不內置 database driver。

在現行 `att-template/v3.6` 契約中，DB operation 在普通 `type: tool` Action 內使用 `#{db.<id>.query(...)}`、`scalar(...)` 或 `update(...)` call。Call 必須有一個 String `sql` argument。SQL 若存放於 package，可使用 `sql=&{sql/find-order.sql}`；`sqlFile` 只供 historical compatibility。Positional `params` 與 named `parameters` 互斥，並使用相同 JDBC binding 規則。

Query 返回 typed row/scalar；update 返回規範的 update result。Operation、SQL/parameter evidence 進入 common Action envelope；secret credential 永遠不是 evidence。Parameter evidence 按 descriptor/Action 的 masking/type policy 處理。

[Reliability](../reliability-execution-control.md) 定義 Action timeout precedence、Retry eligibility、attempt limits 與 replay caution；DBHelper 定義 statement timeout 與 transaction lifecycle。Eligible query 範例：

```yaml
actions:
  waitForOrder:
    timeoutMs: 1500
    type: tool
    call: >-
      #{db.orders.query(
        sql='select status from orders where id = :id',
        parameters={id: ${EXEC.INPUT.orderId}}
      )}
    assert: "#{${output.result.rowCount} == 1 and ${output.result.rows[0].STATUS} == 'DONE'}"
    retry:
      maxAttempts: 5
      intervalMs: 500
      retryOn: [ASSERTION, TIMEOUT]
```

明確的 update 亦使用 Tool Action，而且不可使用 automatic retry：

```yaml
actions:
  markOrder:
    type: tool
    call: "#{db.orders.update(sql='update orders set status = ? where id = ?', params=['DONE', ${EXEC.INPUT.orderId}])}"
```

歷史 v3.5/v3.4 的 `type: db` Action 及其 `query`/`update` block 只會由 archived schema 與 compatibility loader 支援。

DBHelper 擁有 descriptor 定義的 connection/statement limit、query timeout、transaction behavior。Transaction finalization 綁定 Case/iteration lifecycle；commit/rollback/reconnect 是 resource operation，不是 public Context root。Action-level timeout/retry 只擴展共同 Action lifecycle，不改變 DBHelper identity 或 Context model。

## DBHelper 配置

| 路徑 | 必填/默認值 | 約束 |
|---|---|---|
| `schemaVersion` | 必填 | `att-dbhelper/v2.6` |
| `id` | 必填 | `[A-Za-z_][A-Za-z0-9_-]*`；全包忽略大小寫後唯一 |
| `name`、`description` | 必填 | 非空顯示文字 |
| `connection.url` | 必填 | 非空 JDBC URL |
| `connection.username/password` | `""` | 字符串；可用完整 `${ENV:NAME}` |
| `connection.driverClass` | `""` | 可選顯式 class；默認 JDBC discovery |
| `connection.properties` | `{}` | 字符串鍵和值；敏感鍵在錯誤中淨化 |
| `connection.readOnly` | `false` | 布爾值；update Action 在 prepare 前拒絕 |
| `connection.isolation` | `driverDefault` | `driverDefault`／`readUncommitted`／`readCommitted`／`repeatableRead`／`serializable` |
| `statement.timeoutSeconds` | `30` | 每個 statement 使用的整數 1–3600 秒 |
| `transaction.scope` | `case` | `case` 或 `statement` |
| `transaction.onEnd` | `rollback` | `commit` 或 `rollback` |
| `result.maxRows` | `1000` | 整數 1–1000000 |
| `result.maxCellBytes` | `1048576` | 整數 1–1073741824 |
| `result.maxBytes` | `10485760` | 整數 1–1073741824，且不小於 maxCellBytes |
| `evidence.sql` | `full` | `full` 或 `hash` |
| `evidence.parameters` | `values` | `masked`、`types` 或 `values`；使用 values 可能暴露敏感業務數據 |
| `evidence.output` | 不啟用 | `format: json\|yaml\|xml\|text\|sqlplus`；`maxChars` 預設 10000（1–1000000） |
| `pool` | 默認值 | `maxSize` 默認 20、`minIdle` 默認 0、`connectionTimeout` 默認 2s；`maxSize` 為 1–10000，`minIdle` 不可大於 `maxSize`，timeout 至少 250ms |

Query result byte limit 逐 row 累加；CLOB UTF-8 bytes 在讀取 chunks 時計算，使限制檢查隨結果大小線性成長。

validate、docs、snapshot 與 dry-run 都不會打開 DB Connection。dbhelper 文件路徑、ID、字段、SQL 文件和 template call 會在執行前校驗。

DB/MQ/HTTP 共用 `evidence.output: {format: json, maxChars: 10000}` presentation policy；支援 `text`、`json`、`yaml`、`xml`、`sqlplus`，後者要求 DB query/update result。`maxChars` 預設 10000，範圍 1–1000000；formatted text 先遮蔽 credential，再按字元確定性截斷，並保留 `format`／`text`／`truncated`。Formatting failure 只寫入有界 `outputError`，不改變 typed `output.result` 或 operation status。Run/Debug 的正常 resource invocation 自動將 snapshot 寫入 Action evidence 和 Case log，不需要額外 Log Action；SQL、parameter、MQ payload metadata、HTTP status/header 等 diagnostics 維持各自契約。Load 不會在每個 iteration 立即 stringify；僅 retained iteration 在 `resource-output.yaml` materialize，`evidence.resources.output: none` 完全跳過。Credential 不會因 presentation 被新增到 evidence。

以上 `waitForOrder` query 無需後續 Log Action。DBHelper 可作以下設定，自動保留 SQL*Plus-style row snapshot：

```yaml
evidence:
  sql: full
  parameters: masked
  output:
    format: sqlplus
    maxChars: 10000
```

`sql` 決定 SQL evidence；`parameters` 僅接受 `values`、`types` 或 `masked`（沒有 `no_mask` 選項），預設為 `values`。任何 parameter mode 都會繼續遮蔽 configured credentials。`output` 只控制 presentation；Assertion 和後續 Action 仍讀取 typed rows，`db.<id>.query`／`scalar` 遵循相同 policy。`maxChars` 預設 10000，範圍為 1 至 1000000。大型 `SELECT` 可能需要提高上限，才能保留完整 formatted snapshot。
