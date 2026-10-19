# 結果、報告與 evidence

## ATT Server Job artifact 發佈

Worker 檔案在 Server 核准其最終 bytes 前，均屬 **Raw/Trusted Worker Output**；只符合目錄邊界不代表可安全匯出。此邊界適用於 RUN（含 Debug mode、Case、Template、Flow、Tool、batch）、LOAD 及保留的 legacy Jobs。

執行結束後，Server 非同步建立唯一的 `artifacts.zip`。Archive 狀態為 `PENDING`、`ARCHIVING`、`READY` 或 `FAILED`，與 Job result 分開；PASS/FAIL/ERROR/INVALID/CANCELLED 不會因發佈未完成而改變。驗證及原子發佈成功後刪除 loose output。ZIP 是唯一持久 artifact payload；metadata、audit、events 及 archive-status 記錄另行保留。

### 發佈及敏感資料政策

只接納一般、非 symlink 的 UTF-8 檔案，副檔名包括 `txt`、`log`、`json`、`yaml`、`yml`、`xml`、`html`、`htm`、`css`、`csv`、`md`。Binary、無效 UTF-8、無效 structured data、巢狀 archive 及不支援格式會被排除；公開 manifest 不會列出被拒絕的檔名。Studio 會顯示 omissions，不會把不完整匯出稱為完整 raw output。

Logs、文字、XML、HTML、CSV 會套用必要的 secret-field、authorization header、bearer/basic token、連線 URL 憑證、private key 及 cookie 保護，並投影 host-local paths。JSON/YAML（包括 Tool/DB 結果、服務回應及 Evidence）先經有界 safe loader 解析，再遞迴保護敏感欄位及明確分類為 `secret` 的 record，最後序列化為有效格式。一般 business values（包括姓名或可能受規管的資料）若不符合明確敏感規則便會保留；不提供任意 PII 自動偵測。操作 Job 的授權不等於 raw-secret 匯出權限，也沒有公開 raw-export 或 raw-file fallback。

支援的敏感名稱包括 password/passphrase、token、secret、credentials、API/access/private key、authorization、cookies。Structured field name 內符合明確 secret text 規則的內容亦會受保護；secret/path projection 後名稱衝突會拒絕該 entry，不會靜默覆寫 business field。敏感 business data 應放在明確敏感的欄位，或把其所屬 structured record 分類為 `secret`。

Artifact data 不可使用 Server-issued Debug default-pointer 例外。已知私有 Job/Package prefix 即使位於 remote-location field 亦會投影；location field 不會令任意巢狀 object 免除保護。一般 remote path、queue name 等 semantic location scalar 仍屬 business value。

JSON 拒絕 duplicate key、尾隨 document 及超過 64 層的 nesting。YAML 拒絕 duplicate key、collection alias 和 unsafe tag。Numeric scalar 最多 1,000 characters；finite decimal 及大型 integer 在匯出時保留精確數值，包括 YAML base-60 number。不支援的 non-finite YAML number 會令 entry 被拒絕，不會發佈經 rounding 或無效的值。

HTML 發佈前經 parser 清理：保留已核准的 relative links、inline styling 及 digest allowlist 中經審核的內建 RUN search/sort script；移除其他 script、active markup、external links、base URL 及離開已核准 archive 的 navigation。缺少或排除的 evidence link 會標示 unavailable。最終 HTML 內含 offline CSP；online viewer 另加 opaque-origin sandbox、相同 restrictive CSP、禁止 forms 及 Studio-origin 權限。任意自訂 raw HTML 的 controls 因而可能與發佈版本不同。

### Manifest 及 Resource limits

`export-manifest-v1.json` 使用 manifest version `1`、policy `att-export-safe-text-v2`。每個 entry 記錄 logical path、media type、byte size、SHA-256、report/preview eligibility、classification 及 publication decision。`complete` 和彙總 `omissions` 不包含被拒絕的檔名或值。ZIP、preview 及解壓檔案使用完全相同的最終 bytes；GET 不會再次獨立遮罩。

Inventory 不受 `server.maxArtifacts` 限制：最多 50,000 filesystem candidates、深度 64、10,000 已發佈 entries、每項 16 MiB、總 bytes 128 MiB、manifest 8 MiB。超出總量上限會使發佈失敗並保留私有來源。ZIP 使用 STORED encoding，避免重複 log 的 compression-ratio 歧義。Reader 拒絕不安全路徑、control characters、encoded traversal、重複或大小寫衝突名稱、過大或高壓縮比 entries、未列入 manifest 的 entries 及 integrity mismatch。Writer 為單一有界佇列，同時最多八個 artifact reads。

Reader 在信任 manifest 前，亦會比較 ZIP central directory 與 local header。Link、special-file 或 Windows reparse attribute、file/directory prefix collision、覆寫 filename 的 extra field、comment、隱藏 prefix／record、重疊 entry、multipart archive 及 ZIP64 均會被拒絕。受限 export profile 只接受 STORED／Deflate entry 及 timestamp-only extra metadata；ATT 寫入 STORED entry。Manifest parsing 拒絕 duplicate key、尾隨 document、fractional version／size、未知 field，以及不符合 policy 的 omission 文字。ZIP 結構可讀並不代表已核准匯出。

### 檢視及下載 Job archive

在 Job Details 開啟 **Artifacts**。報告清單獨立於分頁；使用 **View Report** 或 **Preview** 開啟內容。文字 viewer 提供唯讀、search、copy、line numbers，顯示最多 200,000 characters；**Load more artifacts** 載入後續頁面。**Download Artifacts (.zip)** 是唯一下載操作。解壓後可直接開啟 HTML，relative links 保持相同 archive hierarchy。

Authenticated archive index 僅在 ZIP 已驗證且為 READY 時回傳 `archiveSizeBytes`。Studio 在 ZIP download 旁顯示 human-readable size，不會為計算大小下載 ZIP 或發送 HEAD request。

ZIP download 在 JVM 的 OS temporary directory 建立 request-private snapshot，每份最多 140 MiB。Server 先驗證完整副本及所有 entry digest，再設定 download headers；response length 取自該副本，並從相同 open handle 串流。每個 64 KiB buffer 在寫出前比對已保存的 SHA-256，因此 published pathname 後續被替換或原地修改，都不能以其他 bytes 取代 response。既有八個 concurrent reader 上限亦限制同時存在的 download snapshot，部署時須預留 temporary disk space。正常完成、disconnect 或 approval 失敗會關閉及刪除副本；process 突然終止可能留下 temporary files，須由 OS 或 operator 清理。Entry preview 仍使用獨立 request-scoped verified ZIP handle 及逐 entry digest/CRC 檢查。

**Jobs and results → Metrics** 的 **Artifact archive** 顯示獨立 Server 發佈指標，即使 terminal ERROR Job 沒有 Load result 亦可查看。Job detail 和 archive index 都提供 typed `archiveMetrics`（version 1），獨立持久化，不會混入 Worker execution metrics。Attempts/retries 跨重啟保留；時間和 resource 用量描述最新 writer attempt。Queue wait 至 writer 開始時結束；`publicationWallMs` 包括 writer finalization 和 cleanup，但不含 queue wait。Stage milliseconds 分別量度 scan/classification/redaction、ZIP compression/write、verification/file-fsync 和 private/staging cleanup。Source file count/bytes 計入被拒絕的 regular file；included/omitted count 描述該次發佈。Ratio 為 approved uncompressed entry bytes 除以完整 ZIP bytes（包含 manifest），並非節省空間百分比。Source byte sum 最多為 signed 64-bit 上限。

`writerCpuMs` 僅量度 Server writer thread，且須 JVM 已支援並啟用 thread CPU timing。`writerAllocatedBytes` 是該 thread 的 allocated-byte delta，不是 retained memory、JVM heap 或 peak memory；不會量度 Worker CPU 或主動啟用 JVM instrumentation。不支援／未啟用的量度或未執行的 stage（例如重用既有 verified ZIP）回傳 `null` 並顯示 Unavailable，不會填入假零。真正 empty input 的 file/entry count 為零，但 ZIP 仍有 manifest。Outcome 和 failure phase/reason 僅使用固定安全 code，不包含 filename、data 或 exception message。RUNNING 代表 attempt 尚未完成；cleanup 失敗時為 CLEANUP_PENDING，但已驗證 ZIP 仍可下載。Metrics store 失敗會記錄安全 warning，不會改變 publication safety 或回退至 raw output。

解壓後的 `file://` 報告由瀏覽器決定如何開啟 linked file types；例如 Chromium 可能下載 linked YAML，而非直接顯示。Link 仍指向同一份已核准的解壓 bytes；Studio 會在 viewer 中以 inline text 提供這些 entries。

| 方法 | 相對於 `/api/v1` 的路徑 | 結果 |
|---|---|---|
| GET | `/jobs/{jobId}/artifacts?offset=0&limit=100` | Archive 狀態、分頁 entries、完整 primary reports 及安全 omissions |
| GET | `/jobs/{jobId}/artifacts/archive.zip` | 已驗證的最終 ZIP attachment |
| GET | `/jobs/{jobId}/artifacts/view/{entryPath}` | Manifest 核准的 HTML/CSS 或 inline text，保留相對目錄 |
| POST | `/jobs/{jobId}/artifacts/retry` | 私有來源仍存在時，安排 terminal Job 重試發佈 |

所有路徑均要求共用 OPERATE capability。Active USER 可存取其他 USER 建立的已核准 Job archives；creator 僅作 audit metadata。ADMIN-only 沒有隱含操作權限。Authentication、disabled-account 檢查、local CSRF 及設定的 Origin 政策均適用。回應使用 private/no-store；loose-file download 回傳 404。未完成或不可用 archive 回傳 `ATT-SERVER-ARCHIVE-UNAVAILABLE`（409）；read capacity 已滿則回傳 `ATT-SERVER-ARTIFACT-CAPACITY`（503）。

### Recovery 及 expiry

失敗時保留私有 output，並在可用時選擇 **Retry archive**。同時提出的 retry 會合併；若上一個 writer 正在結束，已接受的 retry 會保留為下一次 publication，不會被靜默丟棄。Startup 及每五分鐘的 reconciliation 使用持久化 recovery-needed flag 和 cursor，每次最多安排 64 個未完成的 terminal Jobs，並使用與 retention 分離的 maintenance executor。ZIP 驗證及 loose-output/staging cleanup 全部成功後才清除 flag；可重試的 FAILED 或中斷工作仍會納入；quarantined Jobs 不會自動納入。Database migration 只將既有 history 納入一次，再以相同有界批次處理，不會每次重掃已成功發佈的全部 history。Terminal legacy loose-output Jobs 使用相同發佈政策；recovery 會驗證已原子提交但狀態未更新的 ZIP，並清理 staging 及剩餘 loose output，不會直接提供 legacy raw files。若來源已不存在，損壞 ZIP 無法重建，archive 會維持不可用。

Worker 的 terminal RESULT 不代表 execution cleanup 已完成。Publication 會等到記憶體中的 execution 結束並釋放 admission lease。Server 每個 Job 最多保留 1,024 個已觀察的 process identity，包括 terminal delivery 前及 cancellation/error cleanup 時發現的 child。Graceful stop 和 forced escalation 使用相同 identity，即使 parent 已退出亦然；所有已觀察的 process 退出前，admission 和 publication 仍受 fence 保護。Enumeration 或 identity persistence 失敗不會授權 publication。獨立確認所有已知 process 均停止後，Server 先持久化 quarantine，才釋放 admission（包括 LOAD capacity）及移除 active Job fence。每秒執行的有界 reconciliation 會重試延後的 release，包括暫時的 quarantine-store failure；quarantine 成功保存前仍保留 admission。Quarantine 跨 restart 保留，阻止 publication 和 retention，archive reason 為 `worker_unverified`。即使 filesystem status 為 READY，archive index/read routes 仍會遵守持久化 quarantine，並停用一般 archive retry。Retry 不會清除 quarantine：operator 必須獨立確認完整 process containment，才可進行 offline recovery；本版沒有清除 quarantine 或匯出 raw output 的 API。Restart 後，publication 和 retention 會檢查 Worker PID/start time 及已保存的 observed-process ledger；相符的 live process，或 start identity 未知的 live PID，會阻止 publication/deletion。Archive status `FAILED`、reason `worker_active` 會保留 private output；請在 Worker 及已記錄的 descendant 停止後 retry。已知但不同的 start time 表示 PID reuse，而非原本的 Worker。Observed-process fence 尚不能證明在 observation 前已逃離的任意 detached descendant 已被限制及停止；完整 OS-level containment 及跨平台 acceptance 仍待完成。

選用的 `server.jobStorage` 配額可在 `dataDir/jobs` 超過高水位時刪除完整 Job。它預設關閉，只測量該目錄，不跟隨符號連結，測量未完成時不會清理。回收依最舊的已結束 Job 開始，並同時刪除 Job 目錄與 H2 列。`partial` 清掃會從上次檢查的最後一個候選之後繼續；隔離中與 artifact recovery 中的 Job 不會進入候選頁，因此受保護的前綴不能佔滿預算。已包含最舊候選的一輪若走到結尾且用量仍高於目標，結果為 `quota_unmet`。執行中、佇列中、隔離、復原中、使用中，以及仍在 `minRetentionHours` 內的 Job 會保留。配額不會壓縮 H2，也不會只因單一 Job 過大而刪除它。既有 `server.jobRetentionDays` 會連同 ZIP 一併到期刪除完整 Job；選取有界 expiry page 前會先排除 quarantined Jobs，避免其長期保留阻止其他已過期 Job 清理；active readers/writers 存在時會延後該次 sweep，不會阻塞 cleanup thread 或刪除使用中的 archive。Classification 及發佈結果寫入不含 raw values 的 audit。失敗的發佈不會刪除唯一未封存的來源。

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
