# 結果、報告與 evidence

Run 會執行 authored Testcase，並為每個選中的 row 記錄一次 Case execution。Testcase 是 Workbook data；Case execution 是使用相同完整 Case ID 標識的 runtime result。閱讀 status、日誌、報表和 evidence 時使用 Case execution。

| 任務 | 前往 |
|---|---|
| 查找 Run files 和 execution artifacts | [查找 Run artifacts](#查找-run-artifacts) |
| 查看狀態或檢查失敗的 Action | [在 HTML 報表中查看 Case execution 結果](#在-html-報表中查看-case-execution-結果) |
| 匯出 JUnit 或 CI 結果 | [匯出 JUnit 結果](#匯出-junit-結果)或[查看 CI JSON 彙總](#查看-ci-json-彙總) |
| 重現已完成的 Run | [重現 Run](#重現-run) |

## 查找 Run artifacts

```text
<outputDirectory>/<RunID>/
├── run.yaml
├── events.jsonl
├── workbooks/
├── ci/summary.json
├── ci/junit.xml
├── report/index.html
├── report/junit.html
└── executions/<EXEC.ID>/...
```

Run ID 和 Case ID 在校驗後保持原樣。只有 `run.yaml` 狀態為 `COMPLETE` 才表示運行完成；中斷工作會直接保留在已保留的 Run ID 目錄中供調試。

## 在 HTML 報表中查看 Case execution 結果

`report/index.html` 是主要終端用戶報表，可以直接從磁盤打開。組按 `workbookId.groupId` 彙總；界面把 `groupId` 標記為 Sheet。Cases view 支持 Workbook/Sheet/Status 下拉框、對 workbook/group/full Case ID/tag 的大小寫不敏感搜索，以及每列標題的升序/降序排序。Duration 按數值排序。

展開的 Case 包含完整 Case ID、名稱、狀態、持續時間、Expected 和 Actual 結果、每條記錄 Action 結果的一行、詳細執行日誌，以及 `.log`/`case.yaml` 的顯式鏈接。Action Results 每行獨立顯示最終渲染的 Description，並寫入 `run.yaml` 與 CI JSON。為兼容既有報表，Expected 仍是所有 assert Action 非空最終 description 與 `expected` 的有序 LF 聯接；Actual 是所有非空運行時 `actual` 的有序 LF 聯接。

## 診斷 Tool evidence collector 失敗

Evidence collector 是 operation 完成後的 observability，不是 primary Tool result。使用 `onFailure: continue` 時，primary Action 可以維持 `PASS`，而 collector 會獨立記錄為 `ERROR`：

```yaml
evidence:
  appLog:
    call: >-
      #{ssh.app.execute(command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100')}
    timeoutMs: 5000
    onFailure: continue
```

請查看 `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<collectorId>`（或等價的 `ACTIONS` compatibility view）。Record 包含 `status`、`success`、`invocationId`、`result`、`error`，以及 bounded/redacted 的 underlying operation `evidence`；operation 有提供 structured diagnostics 時會在 `operationDiagnostic` 保留 native operation diagnostic 的安全 field。`diagnostic` 則記錄 collector failure 及其 source file/field。`error.message` 會從 underlying exception、operation status/exit code 或安全 fallback 填入。若 executor 有提供，SSH helper/instance、exit code、bounded stderr、MQ reason code、HTTP status 和 timeout detail 等 resource identity/field 會留在 `evidence`。Failed collector evidence 會被 bounded/redacted；raw input、payload、argv、output、resolved command text 與 failed `result` 不會發布。完整 projection、numeric budgets 與 security guarantees 見 [Limits, Security Guarantees, and Advanced Diagnostics](appendices/limits-defaults.md)。

有 retry 時，請查看 `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<collectorId>`。即使後一個 attempt 成功，較早的 failed collector record 仍會保留；top-level collector record 代表最後／勝出的 attempt。使用 `onFailure: stop` 時，Action 可以失敗，但其 diagnostic 仍會包含 collector root-cause message 和保留的 evidence。同一 structured record 也會寫入 `case.log` 的 `EVIDENCE <action> attempt=<n> collector=<id>` block，因此不必打開 internal exception trace，便可看到基本 resource、category、message、exit code 和 bounded stderr。既有 capture limit 與 secret redaction 仍然有效；collector wrapper 不會開放無上限 raw output。

## 將 execution 結果寫入 Workbook

ATT 會復制源 Workbook，並使用 `report.mode: append-to-copy` 追加配置的結果列。`report.mode: none` 跳過 result Workbook，適合不需要 copy 的 CI 或大型 run。Global `report.fileNamePattern` 控制檔名。Sidecar `report.columns` 只修改 Workbook 標簽。支持的映射包括 `result`、`durationMs`、`expectedResult`、`actualResult`、`caseLog`、`reportLink`、`runTime`；Expected/Actual 單元格保留 LF 字符並以換行文本顯示。結果回填使用與 testcase loader 相同的 Excel 顯示格式和空白規範化規則讀取 Case ID，因此帶前導零等數字格式的 ID 在執行與報表寫入時會匹配同一 Case。

## 匯出 JUnit 結果

每個 Case execution 對應一個 JUnit `<testcase>`：

| ATT 狀態 | JUnit 表示 |
|---|---|
| PASS | 無 failure 子節點 |
| FAIL | `<failure>` |
| ERROR | `<error>` |
| SKIPPED | `<skipped>` |
| INVALID | `<error type="ATTValidationError">` |

文本會被 XML 轉義。JUnit XML 與 HTML 使用 `report.junit.caseLogEmbedThresholdBytes`。低於或等於閾值的日誌會被嵌入；更大的日誌使用相對鏈接。`0` 始終使用鏈接。

## 查看 CI JSON 彙總

`ci/summary.json` 使用 `schemaVersion: att-ci-summary/v2.1`，包含 ATT/Run ID、環境、時間、聚合狀態/統計、持續時間統計、每個 Case 記錄、診斷計數、報表/產物路徑以及輸入清單哈希。

## 重現 Run

`run.yaml` 使用 `schemaVersion: att-run/v2.1`，記錄 ATT/構建身份、Java/OS/locale/timezone、校驗模式、環境、時間戳、狀態/摘要、輸出路徑，以及有效配置、Tool group 文件、call-backed Tool SQL 文件（`tool-sql`）、Workbook、Sidecar、解析 Template/負載、包內 Tool 文件和 schema/catalog 版本的 SHA-256 hash。

## 生成文檔並管理 package 輸出

| 命令 | 輸出/行為 |
|---|---|
| `docs` | 在 `build/docs/index.html` 生成可搜索離線包文檔；Testcases 按 Workbook 和 Sheet 分組 |
| `report --run-id <id>` | 從完成證據重建兩個 HTML 報告 |
| `build` | 歸檔最新完成 run，不執行測試 |
| `clean` | 刪除配置輸出目錄、`build/docs` 與 `build/att-*.tar.gz` |

## 從 execution identity 追蹤 artifact

| Identity | 意義 | Scope | Artifact 用途 |
|---|---|---|---|
| EXEC.RUN_ID | 外層 ATT run。 | Run。 | Run root、summary、report。 |
| EXEC.ID | 目前 Case/Debug/Load execution。 | Execution。 | Workspace 建立時作為 log/evidence key。 |
| EXEC.OUTPUT_DIR | 與 EXEC.ID 關聯的 workspace。 | Execution。 | Run/Debug 實體 workspace 或 Load planned lazy workspace。 |

一般 Run 的功能性 Case 位於 output/<RUN_ID>/executions/<EXEC.ID>/。Load 執行期間，EXEC.OUTPUT_DIR 固定指向 output/load/<RUN_ID>/executions/<EXEC.ID>/。Iteration 被保留時，artifact 也會複製到 samples/<EXEC.ID>/ 或 failures/<EXEC.ID>/。Metrics-only iteration 有 EXEC.ID；scheduler 清理暫存 workspace 後不保留 per-iteration 目錄。Load report 顯示 retained rows 的 EXEC.ID，有保留 case.log 時提供連結。Debug 使用同一 debug ID 作為 EXEC.RUN_ID 與 EXEC.ID。

DIAG 是 evidence-only。Expression 不可讀取 DIAG、EXEC.MODE 或任意 scheduler counter；業務差異請透過 EXEC.INPUT 傳入。


## 查閱 generated-output schemas

| 產物 | 頂層必需契約 |
|---|---|
| `run.yaml` | `schemaVersion`、`att`、`runtime`、`run`、`validation`、`inputs`、`cases`、`summary`、`outputs` |
| Validation JSON | `schemaVersion`、`attVersion`、`valid`、`mode`、`summary`、`diagnostics` |
| CI summary JSON | `schemaVersion`、`attVersion`、`runId`、`environment`、`startedAt`、`endedAt`、`status`、`summary`、`durationStatistics`、`cases`、`diagnosticCounts`、`report`、`inputManifestHash` |
| JUnit XML | 一個 testsuite，含 test/failure/error/skipped 計數，以及每個 Case execution 的 testcase |

## Reading `case.log` and `case.yaml`

Case log structured entries use YAML. The human log records each normal Action and each Tool/DB invocation once; duplicated attempt fields and persisted TOOL/DB subtrees are omitted from this projection. Complete final Stage/Template/Action/Tool/DB state remains in `case.yaml`. `caseLog.yamlAnchors: false` fully expands shared Map/List objects; `true` permits YAML anchor markers, which carry no ATT identifier semantics.

巢狀 structured entry 中的 multiline String 會以易讀的 YAML block content 顯示。ATT 保留 LF、CRLF 或單獨 CR 分隔符，不會解析、修剪或重排 business text。Raw process 與 user content 也保留原始換行。Live console mirror 與已寫入的 `case.log` 使用相同的 render text。

ATT prefixes Case log blocks whose section or nested status is ERROR, FAIL or INVALID with `【!!!!!】`. Search for that marker to find abnormal blocks; PASS, SKIPPED and informational blocks remain unmarked.
