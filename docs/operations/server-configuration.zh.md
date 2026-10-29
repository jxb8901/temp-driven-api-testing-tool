<!-- att-doc-status: current release=4.2.7 -->

# ATT Server 設定

## 設定

Server 會驗證 `min(workers.maxConcurrent, workers.maxConcurrentLoad) <= 4`。每個 Worker 最多預留 64 個 DB/MQ initial session，因此單一 Server process 最多同時執行四個 LOAD Worker，並將該 Server 提交的 LOAD preparation 限制在 256 個 session。此上限只涵蓋該 Server process admission 的 LOAD 工作。RUN/Debug、CLI 或直接 Engine process，以及其他 Server instance 不由同一 admission semaphore 協調；若它們共用 backend，請另行計算其 session。

`server.dataDir` 儲存 H2 控制平面資料及工作輸出。`workers` 限制 Worker 並行數、佇列大小、Load admission、優雅停止逾時及可選的每個 Worker Heap 上限。`workers.heapMaxMb` 為每個 Worker 設定 `-Xmx`（64–65536 MiB）；選填 `heapInitialMb` 設定 `-Xms`（32–65536 MiB），必須同時設定 `heapMaxMb`，並且不可超過上限。請按 `maxConcurrent`、Server 及 container 的記憶體預算設定總 Heap 上限。被拒絕的提交會在建立持久工作記錄前清理。已完成工作的 metadata、journal 及 artifacts 會保留 `server.jobRetentionDays` 天（預設 30，範圍 1–3650）；啟動時及每小時清理過期工作，並保留執行中的工作。天數保留與選用的容量配額彼此獨立。`server.jobStorage` 只有在 `enabled: true` 時才會啟用。啟用時必須設定 `maxTotalSizeMb`，以及嚴格較小的 `cleanupTargetSizeMb`（各為 1–10485760 MiB）。Server 只測量 `dataDir/jobs`，不會跟隨符號連結，也不會計入 H2 資料庫。測量無法完成時不會刪除任何內容。用量高於高水位時，會由最舊的已結束 Job 開始刪除，直到用量低於或等於清理目標：先比 `finishedAt`，再比 `jobId`。執行中、佇列中、仍在記憶體中、隔離、artifact recovery、封存讀取中，以及新於 `minRetentionHours`（預設 24）的 Job 都會保留。`minRetentionHours` 只限制容量回收。`jobRetentionDays` 的天數到期仍單獨生效，可能刪除容量回收會保留的 Job。每次回收會刪除整個 Job 目錄及其 H2 列；標記為刪除中的 Job 會從一般清單、LOAD 清單與讀取 API 隱藏，直到刪除完成。重啟後完成刪除時會寫入一筆 `JOB_STORAGE_EVICT` audit。`maxSingleJobSizeMb` 會被拒絕，因為不會只因單一 Job 過大而刪除它。配額不會壓縮 H2。若一次清掃達到候選 Job 上限而仍有可刪除的 Job，結果為 `partial`，不會記為 `quota_unmet`。下次清掃會從上次檢查的最後一個候選之後繼續。當錨點 Job 的完成時間改變、候選清單已走完，或用量回到高水位以下時，會捨棄這個續掃位置。若續掃已包含最舊的候選並走到清單結尾，而用量仍高於清理目標，結果是 `quota_unmet`。隔離中以及仍需 artifact recovery 的 Job 不會進入候選頁，因此受保護的前綴不能佔滿本次預算。`quota_unmet` 表示候選已看完，且因為只剩受保護的 Job 而用量仍高於清理目標。已驗證的管理員可從 `GET /api/v1/metrics` 的 `jobStorage` 讀取最近一次清理結果。`workers.maxConcurrentLoad` 限制已接納的 Load 工作總數，包括佇列中及執行中的工作，避免等待中的 Load 佔用一般 Worker thread。Load 或整體容量超出上限時會回傳 HTTP 429。`workers.libraryDirs` 可選擇列出絕對、已存在且可讀的目錄，Worker subprocess 會將其中 JAR 加入 classpath，以載入額外 JDBC、MQ 或其他 dependency；只可設定由 Server 管理員信任的目錄。唯讀 `packages` registry 將穩定 package ID 對應到 `allowedRoots` 下的 canonical root。每個 Package 都必須明確登錄；package ID 只是標籤，與 login ID 相同不代表 owner 或額外權限。Business resources 僅從各自 Package root 解析；所有 Package 共用 ATT_HOME runtime config 及 trusted schema。`server.inspection` 以有界的常駐 Worker pool 獨立限制唯讀資源探索。Worker pool 的程序數不會超過 `maxConcurrent`，每個程序會依次處理請求，並快取最多兩個 package index；package 檔案變更事件會令快取失效。資源探索另設佇列、逾時、heap、source 及 response 上限。`sourceBrowsing.policy: safe-package-files` 預設允許合資格且非敏感的 package-local 文字，無須逐檔 allowlist，包括有界的 Template/Flow YAML 投影、Tool scripts、一般設定與 Testdata descriptor，以及由已授權 source 連結的合資格 `&{...}` targets。`config/` 是唯一可列出目錄的檔案樹。每個連結檔案都使用 Server 簽發、綁定 package 及 origin 的不透明 reference ID；caller 單靠路徑和 origin 不會獲得導覽權限。`allowlist-only` 使用 `server.inspection.safeTextSources` 設定精確路徑；共用設定來源以 ATT_HOME-relative 路徑表示，Package 檔案仍以各自 root-relative 路徑表示。External scripts 只會在 `sourceBrowsing.externalTextSources.<packageId>` 列出其 canonical 精確檔案，且檔案位於已設定的 canonical `externalSourceRoots` 目錄時提供；不接受 wildcard、不列出 external 檔案，回應亦不會洩露 filesystem path。敏感路徑、deny 檔案、binary、package root 以外的檔案及超大檔案仍不可用；憑證及個人資料在所有模式下都會受到保護。

舊版的 `server.inspection.safeTextSources` 若包含 YAML entry，可能令 Tomcat 因 `Inspection source allowlist accepts text script files only` 而無法啟動。如需先恢復舊版服務，請在重啟前移除該 entry。本版本已接受 YAML entry；預設 `safe-package-files` policy 會提供合資格的共用設定 YAML（例如 `config/tools/fpp.yaml`），無須逐檔設定。只有使用 `allowlist-only` 時才需保留精確路徑。

`server.inspection.redaction.enabled` 預設為 `false`，會在安全 projection 中顯示一般 package 值。即使關閉遮蔽或匿名存取，憑證、認證資料、private key 及常見個人資料欄位仍會保護；啟用遮蔽後亦會套用設定的敏感路徑、欄位 pattern 及自由文字預設值。遮蔽關閉時 Server 會記錄啟動警告，UI 會顯示目前設定。共享或正式資料建議啟用遮蔽並要求登入；未遮蔽值只應用於隔離的 SIT 網絡。

Inspection cache、optional `server.inspection.warmup` 設定、lifecycle 及 metrics 的完整 contract 見 [運維 Reference](../reference.zh/ci-packaging-operations.md#studio-inspection-cache)。

`server.inspection.configurationAdminRole`（預設 `ATT_ADMIN`）控制 Effective 及 Compare configuration view 的 `includeAllFields=true`。Local-auth mode 只有本地 `ADMIN` 可使用。此選項會加入非秘密 DB properties、evidence SQL／parameters 及平常會遮蔽的 DB 運作設定；它不會完整披露設定值。Username、password、token、個人資料、HTTP header maps、MQ evidence payload、SSH 連線資料及 identity-file 路徑、已分類欄位及設定為敏感的值仍會受到保護。Source 維持安全 source projection；Declared 顯示宣告狀態及項目數量。各 view 都會隱藏受保護值。回應會設定 `Cache-Control: no-store` 並寫入 audit log。

`server.security.checkCrossOrigin` 預設為 `false`，只控制 state-changing API 的 Origin 相等檢查；設為 `true` 時，不匹配會回傳 `ATT-SERVER-ORIGIN-MISMATCH`。關閉此檢查不會略過 local cookie authentication 的 CSRF 驗證或 login/bootstrap 的 Origin 檢查，也不會啟用 CORS 或 wildcard header。反向代理應以可信來源設定 Tomcat `RemoteIpValve`，讓 Servlet request 提供外部 scheme、host 及 port；應用程式不會信任任意 forwarded header。Origin 檢查關閉時 Server 會記錄啟動警告。

`server.security.trustedOrigins` 是可選的瀏覽器 gateway Origin 精確允許清單，最多 100 項；省略或空清單會保留原有行為。應先設定 Tomcat 可信代理位址及外部 HTTPS scheme、host、port。清單只處理瀏覽器 Origin 與 Servlet origin 的已知差異，不能把 HTTP 連線變成安全連線。

```yaml
server:
  security:
    checkCrossOrigin: true
    trustedOrigins:
      - https://att.example.com
      - https://att-gateway.example.com:8443
```

Origin 以 scheme、忽略大小寫的 hostname/IP 及有效 port 精確比較（HTTPS 預設 443）。設定項目可有結尾 `/`，但請求 Origin 不可含 path。啟動時會拒絕 path、query、fragment、userinfo、wildcard、無效 port、`null`、等價重複項目及公開 HTTP Origin。HTTP 僅容許 `localhost`、`127.0.0.1` 或 IPv6 loopback 開發用途。不會進行 DNS 查詢、前綴／後綴比對，亦不會自動信任 forwarded header。

| 瀏覽器 Origin | 一般狀態變更，啟用檢查 | 一般狀態變更，關閉檢查 | Login/bootstrap，兩種設定 |
| --- | --- | --- | --- |
| 與 Servlet 相同或精確符合清單 | 通過 Origin 規則 | 通過 Origin 規則 | 通過 Origin 規則 |
| 不同且不在清單 | 拒絕 | 略過 Origin 檢查 | 拒絕 |
| `null`、格式無效或多個 Origin | 拒絕 | 略過 Origin 檢查 | 拒絕 |
| 缺少 Origin（REST/CLI 相容性） | 通過 Origin 規則 | 通過 Origin 規則 | 通過 Origin 規則 |

此共用規則適用於 local/container authentication 中所有需要 Origin 相等檢查的位置。拒絕時回傳 `ATT-SERVER-ORIGIN-MISMATCH`，並寫入不含原始 Origin 或憑證的 `ORIGIN_REJECT` audit。通過 Origin 規則不會授予權限：local cookie 及 Basic 狀態變更仍須提交 `X-ATT-CSRF`，authentication、role、憑證 HTTPS 傳輸、限速、Secure/HttpOnly/SameSite cookie，以及 draft ownership 規則均維持；active USER 共用符合資格的 package、run、job、結果、artifact 及 schedule 操作權限。此設定不會設定 CORS、發送 `Access-Control-Allow-Origin` 或啟用跨站 cookie；真正跨站的瀏覽器 API 存取須另行設計 CORS。


瀏覽器建立的 Quick Load 及 Advanced Load draft 另有獨立 opt-in：`server.inlineLoad.enabled` 預設為 `false`。啟用後，Server 會在 Engine 驗證後及進入佇列前，再次檢查 workload 數、target 數、Virtual User 總數、Arrival Rate 總和、每個 workload 及整體並行數上限，以及完整時間窗口上限。以下範例設定保守預設值。時間窗口上限依 ramp-up、warmup、duration、ramp-down 順序計算；target 數包含固定 target 及每個 VU mix entry。功能停用時回傳 `403 ATT-SERVER-INLINE-LOAD-DISABLED`；超出設定上限的 draft 會回傳 `400 ATT-SERVER-INLINE-LOAD-LIMIT`，並以摘要指出超出的設定。這些限制適用於瀏覽器建立的 draft；既有 path-based Load request 維持相容行為，並繼續受 `workers.maxConcurrentLoad` 限制。

週期性 package-backed Load 排程另有獨立 opt-in：`server.scheduling.enabled` 預設為 `false`。所有已驗證的 USER 均可在共用操作範圍內管理 RUN/LOAD 排程；container-auth 使用 `server.authorization.userRole`，local-auth 要求 active `USER`。ADMIN role 不會取得排程操作權限。匿名操作只由 `server.authorization.allowAnonymousOperations` 控制，且僅在 `authenticationRequired: false` 時可用。舊版 `server.scheduling.adminRole` 及 `allowAnonymousManagement` 僅為設定相容性保留，不會改變權限。此功能需要有界 package inspection。排程定義及執行歷史保存在 `server.dataDir` 的 H2 資料庫；請在同一 dataDir 只執行一個 Server 程序。每個排程預設保留最新 10,000 筆 terminal occurrence（`maxExecutionsPerSchedule` 可設為 100–100,000）；執行中及處理中的 occurrence 會保留。本 scheduler 不提供多節點 leader election。

排程可使用 package-relative Load scenario 檔案，或將有效的 Quick/Advanced Load draft 存成 snapshot。建立 draft snapshot 時，resolved principal、package 及 environment 必須與 draft 相同；Debug draft 不可排程。有界 Inspector 會在保存前及每次 occurrence 前驗證和正規化完整 scenario 及 target closure。Schedule 會私下保存 normalized scenario，只在 API view 回傳 `inlineSnapshot: true`，不包含 scenario 內容。Inline snapshot 不可直接替換或編輯，亦不可使用獨立 Load overrides；如需變更 workload，請以新的有效 draft 建立新排程。即使瀏覽器建立的 Load draft 停用，Server 的 Load users、rate、duration、workload 及 target 上限仍然適用。每個工作都經標準 Worker 和 Server Load admission 路徑提交；既有 Load concurrency 及 queue 上限仍然適用。不接受輸出路徑、package root、Worker 設定、憑證或 caller 提供的 principal。工作使用 Server 建立的 `load-schedule:<id>` principal。提交失敗會記錄且不重試。已接納工作的狀態與 evidence 維持標準 job 生命週期，與 schedule occurrence 分開。

手動 Run Now 使用觸發操作的 active USER 作為 execution identity；週期 occurrence 使用排程中保存的 execution identity。每個已接納的 occurrence 都會 audit 該 identity。Schedule-owned Load/Debug job 繼續使用 Server 建立的 `load-schedule:<id>` principal，讓共用排程 history 維持可讀。Local-auth 模式下，未遷移排程的 creator 帳戶遺失或停用時會 fail closed，且 execution identity 必須持續為 active USER。舊版由 active ADMIN 建立的排程，可在第一位 active USER 手動觸發時明確遷移：保留原始 `createdBy` 作 audit，記錄遷移操作者與時間，並將該 USER 設為週期 execution identity。遺失或停用的舊 ADMIN 不可遷移；遷移後則由指定 USER 的 active 狀態決定能否繼續執行。既有 draft 驗證規則仍禁止將 sensitive RUN/Debug 值保存到排程。

UI 提供每日及每週本地時間選項，亦支援五欄位 cron（`minute hour day-of-month month day-of-week`），包括 `*`、清單、範圍及 `/step`；不接受月份/星期名稱或擴充 cron 語法。日及星期欄位同時受限時，任一欄位匹配即算符合。Timezone 必須是 IANA zone ID。春季 DST 跳時中不存在的本地分鐘會略過；秋季重複時段中，每個實際 instant 都會執行一次。Occurrence 間隔至少為 `minimumIntervalMinutes`（預設 15，範圍 1–10080）。重啟或延遲 poll 錯過的 firing 會直接推進至下一個未來 instant，並按每個排程及每次 poll 合併為一筆 `skipped` history/audit 記錄，原因為 `misfire-no-backfill`；不會補跑 Load。每次 poll 對每個排程最多提交一個 occurrence。每個排程的 `maxConcurrentRuns` 預設為 1，並且不可高於全域 `server.scheduling.maxConcurrentRuns`；達到排程自己的執行中上限時會略過新 occurrence。全域 `maxConcurrentRuns`（預設 1，且不可高於 `workers.maxConcurrentLoad`）限制由 scheduler 提交的工作。容量或 package 驗證拒絕會記錄為 `failed-to-submit`，不會重試。`failurePolicy: continue`（預設值）會讓排程繼續啟用；`pauseAfterSubmitFailure` 會在首次提交失敗後暫停排程。此設定只處理提交錯誤；已提交工作的後續結果仍是標準 job outcome。暫停、編輯或刪除排程不會取消已接納的工作。

內建 Scheduled Load 頁面支援建立、編輯、暫停/恢復、手動觸發、刪除、每日/每週/cron recurrence、時區感知的 next-run 預覽，以及連結標準工作記錄的 occurrence history。手動觸發需要瀏覽器確認，並遵守每排程及全域 active-run、容量上限和最短間隔 rate limit。

啟用後的 `/metrics` 會包含下一次到期時間、執行中的排程 Load 數量，以及依狀態統計的保留 occurrence 數。Occurrence history 和 audit table 會記錄提交、略過、容量及 misfire 結果；變更及手動觸發動作會記錄已解析的 caller。Scheduler 使用 Server 主機的 wall clock 及排程的 IANA timezone；請以 NTP 保持主機時間同步。時鐘向前調整會按 misfire 處理並記錄，不會補跑；時鐘向後調整會等候 wall clock 追上已儲存的 next instant。更新 WAR 或重啟 Tomcat 時，請採用單一 instance 的標準部署流程：runtime 停止時 scheduler 一併停止，已接納工作依標準 shutdown recovery 處理，重啟後略過錯過的 occurrence。不要讓新舊 Server 程序同時使用相同 `server.dataDir`，也不要為此單節點 scheduler 使用 rolling multi-instance deployment。

```yaml
server:
  dataDir: /var/lib/att-server
  authenticationRequired: true
  jobRetentionDays: 30
  inspection:
    enabled: true
    configurationAdminRole: ATT_ADMIN
    maxConcurrent: 2
    queuedLimit: 16
    timeoutMs: 30000
    heapMaxMb: 512
    maxResponseBytes: 262144
    maxSourceBytes: 65536
    # Optional asynchronous preload. Keep at most two keys per warmup Worker.
    warmup:
      enabled: false
      workers: 1
      keys: []
      # keys:
      #   - packageId: payments
      #     environment: SIT
    sourceBrowsing:
      enabled: true
      policy: safe-package-files
      denyGlobs: []
      # 選填 canonical external roots 及每 package 的精確檔案。
      # externalSourceRoots:
      #   - /srv/att/external-scripts
      # externalTextSources:
      #   payments:
      #     - /srv/att/external-scripts/mq/check-queue.sh
    redaction:
      enabled: false
      sensitivePaths: []
      sensitiveFieldPatterns: []
    # 舊有精確 package-relative allowlist；只在 policy: allowlist-only 時使用。
    # 不會把這些路徑重新解讀為 external filesystem paths。
    # safeTextSources:
    #   payments:
    #     - tools/payment-check.sh
  security:
    checkCrossOrigin: false
  inlineLoad:
    enabled: false
    maxWorkloads: 10
    maxTargets: 20
    maxTotalUsers: 100
    maxAggregateArrivalRatePerSecond: 100
    maxConcurrentPerWorkload: 100
    maxDurationSeconds: 3600
  scheduling:
    enabled: false
    allowAnonymousManagement: false
    adminRole: ATT_ADMIN
    maxSchedules: 100
    minimumIntervalMinutes: 15
    pollIntervalSeconds: 10
    maxConcurrentRuns: 1
    failurePolicy: continue
    packageChangePolicy: strict
    maxExecutionsPerSchedule: 10000
workers:
  maxConcurrent: 8
  queuedLimit: 100
  # 已接納的 Load 工作上限，包括佇列中及執行中的工作。
  maxConcurrentLoad: 2
  gracefulStopMs: 10000
  # 選填的每個 Worker process 記憶體上限。
  # heapInitialMb: 256
  # heapMaxMb: 1024
  # 選填的額外 Worker dependency JAR 目錄。
  libraryDirs:
    - /opt/att/worker-libs
packages:
  allowedRoots:
    - /srv/att/packages
  entries:
    payments: /srv/att/packages/payments
```

Inline Load 設定使用以下預設值及硬性範圍：

| 設定 | 預設值 | 允許範圍 | 限制對象 |
| --- | ---: | ---: | --- |
| `maxWorkloads` | 10 | 1–128 | 每個 draft 的 workload 數 |
| `maxTargets` | 20 | 1–256 | 固定 target 及 VU mix entry 總數 |
| `maxTotalUsers` | 100 | 1–100,000 | 所有 workload 的 Virtual Users 總數 |
| `maxAggregateArrivalRatePerSecond` | 100 | 大於 0 至 1,000,000 | 所有 Arrival Rate workload 轉為每秒請求數後的總和 |
| `maxConcurrentPerWorkload` | 100 | 1–1,000,000 | 每個 Arrival Rate workload |
| `maxTotalConcurrent` | 1,000 | 1–1,000,000 | 所有 Arrival Rate workload 的 `maxConcurrent` 總和 |
| `maxDurationSeconds` | 3,600 | 1–864,000 | LOAD 完整 envelope 上限：ramp-up + warmup + duration + ramp-down，最多 10 日 |

`dataDir` 必須是絕對路徑。啟動時，allowed root、package root 及 Worker library directory 必須已存在。系統會將 package 路徑 canonicalize；若 symlink 指向 allowed root 以外，便會拒絕。Client 提交 `packageId`，不能指定 package 路徑、輸出路徑、Worker 執行檔或 classpath。v1 不提供 package 註冊或修改 API。

Server 狀態存放於 `dataDir/db/`。每項工作在 `dataDir/jobs/<jobId>/` 保存有上限的事件日誌及執行輸出。Server 重啟後會將舊的排隊或執行中工作標記為 `ERROR`，診斷碼為 `ATT-SERVER-INTERRUPTED`，不會重新執行。

### Event journal 上限及磁碟空間耗盡

`server.maxEventsPerJob` 限制保留記錄數量（預設 10,000；範圍 100–1,000,000）。`server.maxEventJournalBytes` 獨立限制新建或健康日誌的磁碟大小（預設 64 MiB；範圍 1–256 MiB）。每個 event payload 最多 65,536 個 JSON 編碼 UTF-8 位元組；每行最多 131,072 位元組；每個記憶體內 Job journal 會將每筆記錄開銷計入，最多保留 16 MiB。Runtime 會按 `workers.maxConcurrent + workers.queuedLimit` 分配 128 MiB 已計量記憶體預算（每個 Job 最少 1 KiB）。每個日誌會保留最新一筆 event，即使超過單一 Job 的預算；超額幅度最多一行，並會在 `eventJournal.activeMemoryOverBudgetBytes` 顯示。背景壓縮每 128 個 event 執行一次，並會在達到位元組上限時同步執行。壓縮會寫入有界的暫存檔、核對 SHA-256，並只會透過同一 filesystem 的 atomic move 取代原檔；不會把整份 snapshot 拼成單一字串。

Server 最多只會重新載入設定位元組窗口內的尾段，並跳過超過行長上限的記錄。Terminal journal 使用有界 LRU 快取，最多 16 個 Job 並以 128 MiB 的已計量保留記憶體為總上限。`GET /api/v1/metrics` 的 `eventJournal` 包含 active Job 的 journal bytes、保留記錄／記憶體、壓縮嘗試／失敗數、degraded Job 數及 terminal 快取大小。當 journal 超過位元組上限的 80% 或持久化降級時，Job event stream 會發出 `diagnostic` event。

如果既有 journal 已超過設定上限，Server 啟動時不會改寫它：只會讀取有界尾段，保留原始檔，並將該 Job 的 journal persistence 標示為 `DEGRADED`。格式錯誤的最後一行、超大記錄或無法確認的序列也會產生該 Job 專屬的 history 診斷。當可用空間低於 8 MiB 加下一筆記錄大小時，Server 會停止寫入該 journal、維持有界的記憶體事件傳送，並以遞增間隔重試，最長五分鐘。此 headroom 檢查並不會預留檔案系統空間；並行寫入仍可能耗盡磁碟。Job 的執行狀態／結果與 journal 警告分開處理。

磁碟空間耗盡時，先確認大檔案的確切路徑及 filesystem，再判斷是否由 `events.jsonl` 增長造成；本次來源修正未能確認事故中 6.3 GB 檔案的路徑。修復前請先將原始檔複製到其他儲存空間並驗證備份，恢復可用空間後，再檢查 Job stream/result 及 `GET /api/v1/metrics` 中的 `journalPersistence`、`journalBytes` 和壓縮診斷。請記錄 journal 路徑、大小及增長時間、可用位元組／inode、`server.maxEventsPerJob`、`server.maxEventJournalBytes`、JVM Heap 設定、GC/OOM log，以及相關 Worker/archive 錯誤。確認另有可驗證備份前，不要刪除 Job 目錄或 journal。

### Artifact archive 壓縮

`server.artifacts.compression.mode` 可選 `store`（預設）、`deflate` 或 `auto`；`level` 範圍為 1–9，預設 3。STORED 保留舊有 archive 格式。DEFLATE 與 AUTO 會對每個核准 entry 單次壓縮至有界的私有 staging file。AUTO 對小於 256 bytes 的檔案使用 STORED，只有節省至少 5% 才使用 DEFLATE。若 entry 的壓縮擴展比例會超過 verifier 的 200:1 上限，即使明確設定 DEFLATE 也會安全退回 STORED。原有 16 MiB 單 entry、128 MiB 總量、entry 數、CRC/SHA、路徑及 atomic publication 限制保持不變。Archive metrics 記錄設定及實際模式、level、payload size、節省量、耗時、entry 方法及有界 fallback 原因。
