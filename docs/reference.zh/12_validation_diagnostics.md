## 12 Validation 與 Troubleshooting

### 診斷順序

先執行 `validate --package` 並修正 diagnostic 的 file/field。Runtime 失敗先看 report status/message，再看該 execution 的 `case.log`、`case.yaml` 與 Action evidence。`FAIL` 與 `ERROR` 的區分、continuation 與 Retry 見 [Reliability](08_reliability_execution_control.md)；collector failure path 見 [Results](11_results_reports_evidence.md)。Windows launcher、Java SSH negotiation 與 stack-trace policy 見 [Appendix D](appendices/limits_defaults.md)。

### 先從 validation 開始

每次修改 workbook、sidecar、template、helper 或 tool 後執行：

```sh
./att.sh validate --package
```

針對單一環境可執行 `./att.sh validate --config config/config.yaml --env SIT --package`。ATT 僅接受 [Appendix A](appendices/schema_matrix.md) 列出的 active schemas。`schemas/history/` 中的舊 schema 僅供歷史參考，不是 runtime compatibility contract。請先更新 `schemaVersion` 並將欄位遷移至現行契約，再執行 validation。診斷會保留原始違規、檔案及 YAML 欄位位置，並提供 migration guidance；ATT 不會改寫 descriptor。例如，移除舊 Render `result.path`，並依[Action 與型別化值](14_actions.md)將 typed `output.result` 傳至下一個 Action。Unsupported version 會在執行前失敗。

現行 schema 位於 [`schemas/`](../../schemas/)，較舊定義位於 [`schemas/history/`](../../schemas/history/)。`validate --package` 會檢查 catalog 登錄的每一份 schema，即使 package 沒有使用。缺少、無法讀取、不安全或重複的註冊 schema 會硬性回報 `PACKAGE_INVALID`。Validation 不會改寫 YAML。請檢視 migration guidance、更新檔案，再針對每個選定的 `--env` 重跑 package validation。

然後根據诊斷代碼和结構化位置排查。不要针對人類可讀消息做自動化判斷。

| 類別 | 典型原因 | 修正措施 |
|---|---|---|
| `ATT-TC` | 缺失/過期 Snapshot、Sidecar/Sheet/表頭錯誤、重復 Case ID | 检查 Snapshot/基名、sheet 映射、有效表頭和完整 ID |
| `ATT-CTX` | 未知或歧義 Context 路径 | 检查請求/當前/缺失字段、最近建议或规范候選 |
| `ATT-STG` | 必需選擇器為空白、選擇器 YAML 無效、Stage 键重復 | 检查選擇器形式、`name`、別名和 required 標志 |
| `ATT-TPL` | 未知/重復 Template、Action 或負載無效 | 检查符號名/完整路径、描述符、Action 類型和本地文件 |
| `ATT-CFG` | 未知字段、重復键、schema 類型/枚举錯誤 | 與第 6 章對照並移除不支持字段 |
| `ATT-TOOL` | 未知/缺失參數、進程或解析失敗 | 對比調用契約，检查退出碼和有界 stdout/stderr capture evidence |
| `ATT-PATH` | 非法 ID 或路径逃逸 | 移除非法字符，並保持內容在配置根目錄下 |
| `ATT-RUN` | 超時、非零退出、渲染/运行時失敗 | 检查 Case 日志和 Action/Tool 證據 |

### 常見问题

#### 為什么 Excel 看起來没问题，但 Case ID 被拒絕？

ATT 導入的是顯示單元格文本，然後應用严格的 ID 安全检查。检查隐藏的首尾空白、尾随 `.`、路径字符、控制字符以及 Windows 设备名。以文本形式保存標識符，以保留前導零。

#### 两张 sheet 能同時包含 `TC001` 吗？

可以。給 sheet 不同的 group ID，即可生成例如 `payment.payment.TC001` 和 `payment.batch.TC001` 這样的 ID。

#### 為什么 `N/A` 變成空了？

ATT 會在數據映射和 Stage 選擇前，把 `N/A`、`NA`、`NULL`、`NONE`、空和僅空白值歸一化為 blank。

#### 為什么 Context 變量失敗？

ATT 會把缺失路径视作作者/运行時錯誤，而不是静默渲染成空字符串。遵循 `ATT-CTX-001` 的 `requestedPath`、`currentNode`、`missingSegment` 和最近建议，检查大小寫敏感的作用域、物理表頭/別名、Stage key、Action ID，以及可用性時間点。後缀簡寫必须唯一識別一個可讀逻辑路径；當 validation 能識別 canonical current-scope replacement 時，會以 `CONTEXT_LEGACY_PATH` 發出迁移 warning。`ATT-CTX-002` 會列出所有冲突候選，以便你加长後缀或使用规范路径。聲明的可選字段即使值為空白，仍然是有效空字符串。

#### 為什么 FAIL 變成 ERROR？

假斷言是 FAIL。無效表達式語法/導航、Tool 失敗、超時、解析失敗、I/O 失敗或运行時異常，都是 ERROR。應查看 Action 證據，而不只看最終聚合状態。

#### 為什么 Tool 跑了不止一次？

它的 Action 啟用了重试，並收到了符合條件的非零退出碼。查看 Case 日志中的尝试列表和最終 Action 记錄。

#### 我能在 `command` 中使用 shell 管道吗？

不能。ATT 會把 `|`、`>`、`<` 按字面值传递。把 shell 行為放到审查過的 Tool 腳本中。

#### 為什么必需的 array 參數會拒絕 `[]`？

必需項驗證發生在 argv 擴展之前。空 typed List 被视為缺失；請至少传入一個標量 item，或將參數设為 optional。

#### 我應該使用包校驗還是選中校驗？

本地快速反馈請用 selected 模式。發布前、CI 推進、或共享包時請用 package 模式。

#### 報告能否不依赖服務器打開？

可以。保持生成的 run 目錄完整即可，相關相對链接仍可工作。

#### build 會不會再次執行测试？

不會。它只是歸档一個已完成的持久化 run。

### 安全提醒

不要把密碼、token、私钥或敏感客户數據放進 Workbook 單元格、Template 描述符、命令字符串、stdout 或 stderr。優先使用 Tool 腳本中经批准的秘密注入方式。在共享報表和歸档前進行审查。

### Validation JSON 合約

```json
{
  "schemaVersion": "att-validation/v2.1",
  "attVersion": "3.6.1",
  "valid": false,
  "mode": "package",
  "summary": {"errors": 1, "warnings": 0, "suites": 1, "cases": 22, "templates": 7, "tools": 7},
  "diagnostics": [{
    "code": "ATT-TPL-104",
    "severity": "ERROR",
    "message": "assert action requires a non-blank expression",
    "file": "templates/PAYMENT_VERIFY/template.yaml",
    "field": "actions.assertStatus.expression",
    "sheet": null,
    "row": null,
    "column": null,
    "template": "PAYMENT_VERIFY",
    "action": "assertStatus",
    "suggestion": "Add expression to the assert action"
  }]
}
```

每個诊斷都包含 `code`、`severity`、`message`、`file`、`field`、`sheet`、`row`、`column`、`template`、`action` 和 `suggestion`。不适用的字段為 `null`。當 package 和 case 驗證發现同一個根本錯誤時，ATT 輸出一條诊斷，並在适用時附带 `occurrences` 和 `affectedCases`；`summary.errors` 統計唯一诊斷，`summary.errorOccurrences` 保留原始出现次數。代碼穩定；自動化不能解析人類消息。

ATT 可另外提供 `summary`、`detail`、`source`、`context` 和 `schemaViolations`。`source` 中的 `line`、`column`、`endLine`、`endColumn` 是 YAML 或 payload 文件的物理位置；顶層 `row` 和 `column` 仍表示 Excel 單元格。單行纯文本及可直接對應的引號字符串，表達式語法錯誤會指向具体字符；折叠、多行或经過轉義的 YAML 字符串若無法精确映射，則報告整個 scalar 范围。每項 Schema 錯誤保留自己的路径、關键字、消息及物理位置。`context` 可包含 Case、Stage、Flow ID 和嵌套調用链。表達式語法詳情在安全時會指出所在 Tool 調用參數（例如 `logFiles`）、意外 token 及带 caret 的有限邻近片段；可能含有凭據或敏感值的字段及整行不會顯示原文摘要。

运行時 Action 錯誤的结構化诊斷會传入 Case YAML、`run.yaml`、重新生成的報表、CI JSON 和 JUnit 錯誤詳情。嵌套 Flow 錯誤會指出內部 `flow.yaml` 及 Action，調用链說明 Template 如何到達該位置。Tool 與 DB evidence 在适用時记錄尝试次數、超時、解析／采集状態、參數绑定及取消操作；文件保存錯誤包含配置路径和允許的產物根目錄。

