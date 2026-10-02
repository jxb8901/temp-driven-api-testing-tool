### 7.3 DBHelper

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

[Reliability](../08_reliability_execution_control.md) 定義 Action timeout precedence、Retry eligibility、attempt limits 與 replay caution；DBHelper 定義 statement timeout 與 transaction lifecycle。Eligible query 範例：

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

### Dbhelper 配置

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
| `pool` | 默認值 | `maxSize` 默認 20、`minIdle` 默認 0、`connectionTimeout` 默認 2s；`maxSize` 為 1–10000，`minIdle` 不可大於 `maxSize`，timeout 至少 250ms |

validate、docs、snapshot 與 dry-run 都不會打開 DB Connection。dbhelper 文件路徑、ID、字段、SQL 文件和 template call 會在執行前校驗。
