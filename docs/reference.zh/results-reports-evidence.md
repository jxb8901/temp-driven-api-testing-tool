# 結果、報告與 evidence

## ATT Server Job artifact 發佈

Worker 檔案在 Server 核准前會留在私有目錄。Artifact producer 在寫檔前套用敏感資料保護。Archive 不是遮罩邊界：它驗證路徑、檔案類型、大小及 UTF-8 文字，然後原樣保留擷取到的 producer bytes。此邊界適用於 Native RUN（含 Debug diagnostics）、LOAD 及其 Server Job artifacts。

執行結束後，Server 非同步建立唯一的 `artifacts.zip`。Archive 狀態為 `PENDING`、`ARCHIVING`、`READY` 或 `FAILED`，與 Job result 分開；PASS/FAIL/ERROR/INVALID/CANCELLED 不會因發佈未完成而改變。驗證及原子發佈成功後刪除 loose output。ZIP 是唯一持久 artifact payload；metadata、audit、events 及 archive-status 記錄另行保留。

### 發佈及敏感資料政策

Archive 接納一般、非 symlink 的 UTF-8 文字檔，副檔名包括 `txt`、`log`、`json`、`yaml`、`yml`、`xml`、`html`、`htm`、`css`、`csv`、`md`。Binary、無效 UTF-8、NUL bytes、巢狀 archive 及不支援格式會被排除；公開 manifest 不會列出被拒絕的檔名。Archive 將 JSON/YAML 視為 producer 寫入的文字，不會在發佈時解析、遮罩或重新序列化。Studio 會顯示 omissions，不會把不完整匯出稱為完整 output。

Logs、文字、XML、CSV 及生成的 structured results 由 producer 在寫檔前套用相關 secret、credential 及 path 保護；Archive 原樣保留結果 bytes。一般 business values（包括姓名或可能受規管的資料）若不符合 producer 的明確敏感規則便會保留；不提供任意 PII 自動偵測。操作 Job 的授權不等於 raw-secret 匯出權限，也沒有公開 raw-export 或 raw-file fallback。

Producer 端保護的敏感名稱包括 password/passphrase、token、secret、credentials、API/access/private key、authorization、cookies。Structured field name 內符合明確 secret text 規則的內容亦會受保護；secret/path projection 後名稱衝突會由 producer 拒絕，不會靜默覆寫 business field。敏感 business data 應放在明確敏感的欄位，或把其所屬 structured record 分類為 `secret`。Producer 必須在資料進入 archive input tree 前完成保護。

Engine 生成的 artifact data 會在寫檔前套用 path 及 secret projection。Archive 不檢查 semantic fields，也不改寫已知 paths。HTTP HTML preview 使用 opaque-origin sandbox、restrictive CSP、禁止 forms，且不授予 Studio-origin 權限。下載報告的 relative links 會依 archive 內檔案配置解析，前提是目標檔案存在。

### Manifest 及 Resource limits

`export-manifest-v1.json` 使用 manifest version `1`、policy `att-export-approved-bytes-v1`；新 entry 的 decision 為 `preserved`（ZIP 使用 `att-export-approved-bytes-v1` publication policy）。ZIP 完整保留 producer 輸出的核准 bytes；敏感值必須在 artifact 生成前完成遮罩。每個 entry 記錄 logical path、media type、精確 byte size、SHA-256、report/preview eligibility、classification 及 publication decision。`complete` 和彙總 `omissions` 不包含被拒絕的檔名或值。ZIP、preview 及解壓檔案使用相同的已擷取 bytes；GET 不會再次獨立遮罩。

Inventory 不受 `server.maxArtifacts` 限制：最多 50,000 filesystem candidates、深度 64、10,000 已發佈 entries、每項 16 MiB、總 bytes 128 MiB、manifest 8 MiB。超出總量上限會使發佈失敗並保留私有來源。ZIP 預設使用 STORED；Server 設定可選有界的逐 entry DEFLATE 或 AUTO，並維持 200:1 ratio 上限。Reader 拒絕不安全路徑、control characters、encoded traversal、重複或大小寫衝突名稱、過大或高壓縮比 entries、未列入 manifest 的 entries 及 integrity mismatch。Writer 為單一有界佇列，同時最多八個 artifact reads。

Reader 在信任 manifest 前，亦會比較 ZIP central directory 與 local header。Link、special-file 或 Windows reparse attribute、file/directory prefix collision、覆寫 filename 的 extra field、comment、隱藏 prefix／record、重疊 entry、multipart archive 及 ZIP64 均會被拒絕。受限 export profile 只接受 STORED／Deflate entry 及 timestamp-only extra metadata；預設仍為 STORED。Manifest parsing 拒絕 duplicate key、尾隨 document、fractional version／size、未知 field，以及不符合 policy 的 omission 文字。ZIP 結構可讀並不代表已核准匯出。

### 分離及還原 Job 主控台

Studio 可從 Live Output、Events 或 Job 詳情標題開啟非 modal floating console。使用者切換 Studio 其他頁面時它仍會保留；每個瀏覽器分頁只允許一個 console，切換至另一個 Job 必須明確確認。Minimize 會留下底部 chip，保留一條有界的分頁 SSE 訂閱，但不會渲染隱藏的事件列；Close 會釋放訂閱。兩者都不會取消 Job。主控台可用滑鼠或鍵盤移動及調整大小，小螢幕會改為全螢幕 sheet。

**Open full Job Details in a new tab** 使用已編碼的同源 route `#/jobs/{jobId}?standalone=1&tab=events` 及 `noopener noreferrer`，包含 Overview、Live Output、Events、Results、Artifacts、Metrics，預選來源 tab。舊 `/console?view=live|events` 書籤仍相容；切換 tab 保持同一 Job ID 及共用 SSE 訂閱。支援重新整理、直接連結及非根 context path；未授權或缺少 Job 使用既有登入或安全 unavailable 訊息。Floating console 行為不變。

### 檢視及下載 Job archive

在 Job Details 開啟 **Artifacts**。報告清單獨立於分頁；使用 **View Report** 或 **Preview** 開啟內容。文字 viewer 提供唯讀、search、copy、line numbers，顯示最多 200,000 characters；**Load more artifacts** 載入後續頁面。**Download Artifacts (.zip)** 是唯一下載操作。解壓後可直接開啟 HTML，relative links 保持相同 archive hierarchy。

Index 亦會回報整份 archive 中 summaries/manifests、metrics/time series、execution evidence、recovery checkpoints 及其他檔案的 entry count 和 uncompressed bytes。Studio 會先顯示 primary report，再把其他項目放在預設收合的 categories；若安全 path 有 workload 與 execution 元件，execution evidence 會依 workload 和執行分組。搜尋會比對已載入頁面的 path 或 workload，載入更多頁面可擴大搜尋範圍。Artifact row 顯示精簡 filename，並可查看完整核准 archive path。

Server 會分別驗證 report 完整性、execution status、performance verdict 及 archive status。只有 canonical report 通過完整性驗證而且 `executionStatus: PASS` 時，LOAD ZIP 才會排除一般 recovery checkpoint；`NOT_MET` performance verdict 會獨立呈現，不會改變此規則。Cancelled 或 runtime-error 的 run 即使已提交完整診斷 report，仍會保留每個 scope 最新兩代 integrity-verified checkpoints。Report 缺失、不完整、損壞、中斷或無法使用時，每個 scope 最多保留最新兩代 verified checkpoints。不完整或損壞的 report bundle 會整組省略，並保留可預覽的 verified checkpoints，同時如實回報 archive omissions。Report commit 驗證完成前不會刪除 source checkpoints。

相同的大型 aggregate 與單一 workload series 共用 detail files 時，版本化 manifest 會記錄重用檔案數和精確省下的序列化 bytes。不同值或多 workload 執行仍保留各自 scoped files。

Authenticated archive index 僅在 ZIP 已驗證且為 READY 時回傳 `archiveSizeBytes`。Studio 在 ZIP download 旁顯示 human-readable size，不會為計算大小下載 ZIP 或發送 HEAD request。

ZIP download 在 JVM 的 OS temporary directory 建立 request-private copy，每份最多 140 MiB。Server 先驗證完整副本及所有 entry digest，再設定 download headers；response length 取自該副本，並從相同 open file 串流。每個 64 KiB buffer 在寫出前比對已保存的 SHA-256，因此 published pathname 後續被替換或原地修改，都不能以其他 bytes 取代 response。既有八個 concurrent reader 上限亦限制同時存在的 download copies，部署時須預留 temporary disk space。正常完成、disconnect 或 approval 失敗會關閉及刪除副本；process 突然終止可能留下 temporary files，須由 OS 或 operator 清理。Entry preview 仍使用獨立 request-scoped verified ZIP handle 及逐 entry digest/CRC 檢查。

**Jobs and results → Metrics** 的 **Artifact archive** 顯示獨立 Server 發佈指標，即使 terminal ERROR Job 沒有 Load result 亦可查看。Job detail 和 archive index 都提供 typed `archiveMetrics`（version 1），獨立持久化，不會混入 Worker execution metrics。Attempts/retries 跨重啟保留；時間和 resource 用量描述最新 writer attempt。Queue wait 至 writer 開始時結束；`publicationWallMs` 包括 writer finalization 和 cleanup，但不含 queue wait。Timing buckets 分別量度 scan、validation 和 byte capture、ZIP compression/write、verification/file-fsync 和 private/staging cleanup。Source file count/bytes 計入被拒絕的 regular file；included/omitted count 描述該次發佈。Ratio 為 approved uncompressed entry bytes 除以完整 ZIP bytes（包含 manifest），並非節省空間百分比。Source byte sum 最多為 signed 64-bit 上限。

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

失敗時保留私有 output，並在可用時選擇 **Retry archive**。同時提出的 retry 會合併；若上一個 writer 正在結束，已接受的 retry 會保留為下一次 publication，不會被靜默丟棄。Startup 及每五分鐘的 reconciliation 使用持久化 recovery-needed flag 和 cursor，每次最多安排 64 個未完成的 terminal Jobs，並使用與 retention 分離的 maintenance executor。ZIP 驗證及 loose-output/staging cleanup 全部成功後才清除 flag；可重試的 FAILED 或中斷工作仍會納入；quarantined Jobs 不會自動納入。Database migration 只將既有 history 納入一次，再以相同有界批次處理，不會每次重掃已成功發佈的全部 history。Recovery 會驗證已原子提交但狀態未更新的 ZIP，並清理 staging 及剩餘 loose output，不會直接提供未封存檔案。若來源已不存在，損壞 ZIP 無法重建，archive 會維持不可用。

Worker 的 terminal RESULT 不代表 execution cleanup 已完成。Publication 會等到記憶體中的 execution 結束並釋放 admission lease。Server 每個 Job 最多保留 1,024 個已觀察的 process identity，包括 terminal delivery 前及 cancellation/error cleanup 時發現的 child。Graceful stop 和 forced escalation 使用相同 identity，即使 parent 已退出亦然；所有已觀察的 process 退出前，admission 和 publication 仍受 fence 保護。Enumeration 或 identity persistence 失敗不會授權 publication。獨立確認所有已知 process 均停止後，Server 先持久化 quarantine，才釋放 admission（包括 LOAD capacity）及移除 active Job fence。每秒執行的有界 reconciliation 會重試延後的 release，包括暫時的 quarantine-store failure；quarantine 成功保存前仍保留 admission。Quarantine 跨 restart 保留，阻止 publication 和 retention，archive reason 為 `worker_unverified`。即使 filesystem status 為 READY，archive index/read routes 仍會遵守持久化 quarantine，並停用一般 archive retry。Retry 不會清除 quarantine：operator 必須獨立確認完整 process containment，才可進行 offline recovery；本版沒有清除 quarantine 或匯出 raw output 的 API。Restart 後，publication 和 retention 會檢查 Worker PID/start time 及已保存的 observed-process ledger；相符的 live process，或 start identity 未知的 live PID，會阻止 publication/deletion。Archive status `FAILED`、reason `worker_active` 會保留 private output；請在 Worker 及已記錄的 descendant 停止後 retry。已知但不同的 start time 表示 PID reuse，而非原本的 Worker。Observed-process fence 尚不能證明在 observation 前已逃離的任意 detached descendant 已被限制及停止；完整 OS-level containment 及跨平台 acceptance 仍待完成。

選用的 `server.jobStorage` 配額可在 `dataDir/jobs` 超過高水位時刪除完整 Job。它預設關閉，只測量該目錄，不跟隨符號連結，測量未完成時不會清理。回收依最舊的已結束 Job 開始，並同時刪除 Job 目錄與 H2 列。`partial` 清掃會從上次檢查的最後一個候選之後繼續；隔離中與 artifact recovery 中的 Job 不會進入候選頁，因此受保護的前綴不能佔滿預算。已包含最舊候選的一輪若走到結尾且用量仍高於目標，結果為 `quota_unmet`。執行中、佇列中、隔離、復原中、使用中，以及仍在 `minRetentionHours` 內的 Job 會保留。配額不會壓縮 H2，也不會只因單一 Job 過大而刪除它。既有 `server.jobRetentionDays` 會連同 ZIP 一併到期刪除完整 Job；選取有界 expiry page 前會先排除 quarantined Jobs，避免其長期保留阻止其他已過期 Job 清理；active readers/writers 存在時會延後該次 sweep，不會阻塞 cleanup thread 或刪除使用中的 archive。Classification 及發佈結果寫入不含 raw values 的 audit。失敗的發佈不會刪除唯一未封存的來源。

## 查找 RUN artifacts

已完成的 Native RUN 會將 manifest、event stream、reports 和 Case execution evidence 寫入唯一 Run directory：

~~~text
<outputDirectory>/<RunID>/
├── run.yaml
├── events.jsonl
├── ci/summary.json
├── ci/junit.xml
├── report/index.html
├── report/junit.html
├── workbooks/native-cases.result.xlsx
└── executions/<EXEC.ID>/...
~~~

標準 XLSX 是生成的結果檔，與測試輸入格式無關。Server Job 會將它列為 binary download，並停用文字預覽。HTML report 和 CI outputs 會保留 Definition ID、qualified Case ID、Flow ID、source revisions、execution ID 及可用的 source-record provenance。

## 閱讀 Case 結果

HTML report 顯示 Case identity、status、duration、Expected 和 Actual values、Action results 及有界 execution-log previews。每個 Action result 均包含最後 render 的 description 和 status。`report.html.caseLogInlineLimitBytes` 控制 inline preview；設定為 `0` 時只保留 artifact link。`report/junit.html` 提供人類可讀的 JUnit view。

每次 Case execution 對應一個 JUnit `<testcase>`。PASS 沒有 failure 子節點；FAIL 對應 `<failure>`；ERROR 和 INVALID 對應 `<error>`；SKIPPED 對應 `<skipped>`。文字會做 XML escaping。`report.junit.caseLogEmbedThresholdBytes` 控制 inline log；設定為 `0` 時一律使用相對連結。

## 查看 CI summary

`ci/summary.json` 使用 `att-ci-summary/v2.1`，記錄 Run identity、environment、時間、aggregate status/count、duration statistics、Case records、diagnostic counts、report paths 和 input-manifest hash。Native Case entries 亦記錄 Definition/Flow identity 及 source revisions。Generated Run manifest 使用 `att-run/v2.1`。

## 診斷 Tool evidence collectors

Evidence collector 是 operation 後的 observability，不是 primary Tool result。使用 `onFailure: continue` 時，primary Action 可維持 `PASS`，而 collector 獨立記錄為 `ERROR`。

~~~yaml
evidence:
  appLog:
    call: >-
      #{ssh.app.execute(command='grep "${EXEC.INPUT.txnId}" /app/log/payment.log | tail -100')}
    timeoutMs: 5000
    onFailure: continue
~~~

請查看 `EXEC.ACTIONS.<actionId>.output.evidence.collectors.<collectorId>`。Record 包含 status、success、invocation ID、result、error，以及有界和已遮罩的 operation evidence。Failed collector evidence 不會發布 raw input、payload、argv、output、resolved command text 或 failed result。Projection 規則和數值上限見[Limits, Security Guarantees, and Advanced Diagnostics](appendices/limits-defaults.md)。

重試時請查看 `EXEC.ACTIONS.<actionId>.output.attempts[n].evidence.collectors.<collectorId>`。後續 attempt 成功後，較早失敗的 collector records 仍會保留；top-level record 代表最後或勝出的 attempt。

## 重現 RUN

`run.yaml` 記錄 ATT/build identity、runtime 資訊、Environment、時間、status、outputs，以及有效 configuration、Package files、Tools、SQL resources、Testdata 和 schema registry 的 SHA-256 inputs。使用保留的 revision 和 manifest identity 比較 runs。`report --run-id <id>` 可從已完成 evidence 重建 HTML reports，不會執行 Package。

## 閱讀 Case logs

Structured Case log entries 使用 YAML。ATT 會保留內嵌文字的 line endings，不會解析或重排 business text。Live console 和保存的 `case.log` 使用相同的 rendered text。ERROR、FAIL 或 INVALID block 會使用 `【!!!!!】` marker；PASS、SKIPPED 和 informational blocks 不會加上 marker。

## 離線 SUT Metrics 報告

完成的 LOAD 若有 SUT collection evidence，可發佈版本化的離線報告 archive。Publisher 會驗證 manifest 和每個 normalized observation file，逐 byte 保留已封存的 source archive，並在驗證通過後發佈報告。`att-load-report-extension/v2` manifest 引用 `att-load-offline-observations/v1`。Observation records 包含 source、metric、profile、target、UTC window、phase、單位、quality 和 checksums。Time series 提供有界 chart previews；business metrics 提供數值、scope、Workload identity、mapping quality 及 unmatched-row count。

若 Job 的 approved execution definition 包含 Metric Groups，報告會發布安全的 group metadata 和 assignments，不含 raw selectors。未分組和有歧義的 observations 仍會連同 diagnostics 列出。報告可離線使用並套用 restrictive Content Security Policy；它不會評估 performance thresholds。如有 `att-load-verdict/v1` record，報告只顯示該 record 的 state 和 evidence reference，不會重算 verdict。驗證發佈成功後，可透過 authenticated Job download route 取得 archive。
