# ATT Server 部署與 API

ATT Server 4.2.0 提供單節點控制平面，讓用戶透過版本化 REST API 提交 ATT Run、Debug、Load 及 Validate 工作。請將 WAR 部署至使用 Java 17 或更新版本的外部 Tomcat 10.1+。CLI、Engine 及 Worker 仍相容 Java 8。

## 準備部署

1. 將 Local archive 解壓至安裝根目錄，並將 `ATT_HOME` 設為該目錄。升級時，先移除舊的 `lib/att-local-*.jar` 再解壓新 archive，確保 `ATT_HOME/lib` 只包含一個 runtime。預設 package root 為 `$ATT_HOME/packages/default`；其中包含自己的 `config/config.yaml`、`schemas/`、單數 `testcase/`、`templates/` 及頂層 `tools/`。
2. 將 package root 設於 allowed root 之下，並確保 Tomcat 服務帳戶可讀取。解壓後的 `$ATT_HOME/packages/default` 可直接註冊為 package root。
3. 啟動前先建立 `server.dataDir`，並只允許 Tomcat 服務帳戶寫入。範例使用 `$ATT_HOME/data`；只要它與所有已註冊 package root 不相等、互不包含，即可使用。亦可使用 `/var/lib/att-server`。Server data 不可放入 package 內容。
4. 將 `$ATT_HOME/config/att-server.example.yaml` 複製至 `$ATT_HOME/config/att-server.yaml` 或受保護的外部路徑，更新絕對路徑，並勿將憑證放入 package。升級時不可覆寫既有的 active config。
5. 將 `att-server-4.2.0.war` 部署至 Tomcat 10.1+。Tomcat 可直接部署 packed WAR；隔離的 Worker 會從 `$ATT_HOME/lib` 載入版本相符的 `att-local-4.2.0.jar`。

Local archive 解壓後的目錄就是共享安裝及部署根目錄 `ATT_HOME`。此目錄包含 launcher 及 `lib/att-local-4.2.0.jar` 中共用的 Worker/Engine runtime；請在 Server WAR 旁安裝版本相符的 Local archive。每個 `ATT_HOME/lib` 只保留一個有版本號的 `att-local-<version>.jar`。可執行的 package 內容位於 `packages/<id>/`；Server state 位於另外設定的 `server.dataDir`。Archive 只包含 `config/att-server.example.yaml` 範本，不會包含 active 的 `config/att-server.yaml` 或 `data/` 目錄。

Server 依次尋找 JVM property `-Datt.server.config`、環境變數 `ATT_SERVER_CONFIG`，再尋找 `$ATT_HOME/config/att-server.yaml`。最後一個路徑的 `ATT_HOME` 先取自 `-Datt.home`，再取自 `ATT_HOME` 環境變數。若選定的 config 不存在、不可讀或無效，Server 會在診斷中指出其路徑並停止啟動，不會改用其他來源。沒有明確來源或可用的預設 config 時，啟動錯誤會提供設定指引。

Tomcat 負責 listener、TLS、access log 及驗證。請讓服務帳戶可讀取 ATT_HOME 和 package root，並擁有或可寫入 `server.dataDir`。請設定 Realm、SSO 整合、用戶端憑證或其他容器支援的機制；驗證失敗時，Server 會保留容器回傳的 challenge 或 redirect。啟用 Scheduled Load 後，只有 `server.scheduling.adminRole`（預設 `ATT_ADMIN`）中的 Servlet 使用者可管理排程。若設定、Java 基線或 package mapping 無效，Server 會在啟動時失敗。

## ATT_HOME 佈局與遷移

共享安裝可採用以下佈局：

```text
att/                              # ATT_HOME
  att.sh
  att.bat
  lib/
  config/att-server.example.yaml  # 僅為範本
  data/                           # 選用的 server.dataDir，由 Tomcat 管理
  packages/default/               # 完整 package root
    config/config.yaml
    config/tools/
    schemas/
    testcase/
    templates/
    tools/                        # Tool script
```

遷移舊版平面 Local 安裝前先備份，並將新版 archive 解壓到暫存目錄。把舊版 `config/` 內容（排除 `att-server.example.yaml` 及 `att-server.yaml`）、`schemas/`、`testcase/`、`templates/`、頂層 `tools/` 及選用的 `sql/` 複製至相對應的 `packages/default/` 目錄。合併時保留目的地已存在的檔案；手動檢查 `config/config.yaml` 及自訂 package 檔案的衝突。請分開保留 `config/tools/`（Tool 設定）及頂層 `tools/`（script）。既有 active Server config 應留在 `ATT_HOME/config/att-server.yaml` 或受保護的外部路徑；不可複製到 package root 或在遷移時覆寫。在遷移後的 package 通過驗證及執行前，保留舊的平面目錄。將 Server registry entry 更新至新 package root，並為 Tomcat 設定 `ATT_HOME` 或 `-Datt.home`。Server state 必須留在 package root 以外，安裝替換或升級時須保留；Local archive 不包含 `data/` 或 active Server config。

## 設定

`server.dataDir` 儲存 H2 控制平面資料及工作輸出。`workers` 限制 Worker 並行數、佇列大小、Load admission、優雅停止逾時及可選的每個 Worker Heap 上限。`workers.heapMaxMb` 為每個 Worker 設定 `-Xmx`（64–65536 MiB）；選填 `heapInitialMb` 設定 `-Xms`（32–65536 MiB），必須同時設定 `heapMaxMb`，並且不可超過上限。請按 `maxConcurrent`、Server 及 container 的記憶體預算設定總 Heap 上限。被拒絕的提交會在建立持久工作記錄前清理。已完成工作的 metadata、journal 及 artifacts 會保留 `server.jobRetentionDays` 天（預設 30，範圍 1–3650）；啟動時及每小時清理過期工作，並保留執行中的工作。`workers.maxConcurrentLoad` 限制已接納的 Load 工作總數，包括佇列中及執行中的工作，避免等待中的 Load 佔用一般 Worker thread。Load 或整體容量超出上限時會回傳 HTTP 429。`workers.libraryDirs` 可選擇列出絕對、已存在且可讀的目錄，Worker subprocess 會將其中 JAR 加入 classpath，以載入額外 JDBC、MQ 或其他 dependency；只可設定由 Server 管理員信任的目錄。唯讀 `packages` registry 將穩定 package ID 對應到 `allowedRoots` 下的 canonical root。`server.inspection` 以有界的常駐 Worker pool 獨立限制唯讀資源探索。Worker pool 的程序數不會超過 `maxConcurrent`，每個程序會依次處理請求，並快取最多兩個 package index；package 檔案變更事件會令快取失效。資源探索另設佇列、逾時、heap、source 及 response 上限。`sourceBrowsing.policy: safe-package-files` 預設允許合資格且非敏感的 package-local 文字，無須逐檔 allowlist，包括有界的 Template/Flow YAML 投影、Tool scripts、一般設定與 Testdata descriptor，以及由已授權 source 連結的合資格 `&{...}` targets。`config/` 是唯一可列出目錄的檔案樹。每個連結檔案都使用 Server 簽發、綁定 package 及 origin 的不透明 reference ID；caller 單靠路徑和 origin 不會獲得導覽權限。`allowlist-only` 會保留 `server.inspection.safeTextSources` 既有的 package-relative 精確路徑語義。External scripts 只會在 `sourceBrowsing.externalTextSources.<packageId>` 列出其 canonical 精確檔案，且檔案位於已設定的 canonical `externalSourceRoots` 目錄時提供；不接受 wildcard、不列出 external 檔案，回應亦不會洩露 filesystem path。敏感路徑、deny 檔案、binary、package root 以外的檔案及超大檔案仍不可用；憑證及個人資料在所有模式下都會受到保護。

`server.inspection.redaction.enabled` 預設為 `false`，會在安全 projection 中顯示一般 package 值。即使關閉遮蔽或匿名存取，憑證、認證資料、private key 及常見個人資料欄位仍會保護；啟用遮蔽後亦會套用設定的敏感路徑、欄位 pattern 及自由文字預設值。遮蔽關閉時 Server 會記錄啟動警告，UI 會顯示目前設定。共享或正式資料建議啟用遮蔽並要求登入；未遮蔽值只應用於隔離的 SIT 網絡。

`server.inspection.configurationAdminRole`（預設 `ATT_ADMIN`）控制 declared、effective 及 compare configuration view 的 `includeAllFields=true`。Local-auth mode 只有本地 `ADMIN` 可使用。此檢視會加入其他非秘密 properties、evidence fields 及平常會遮蔽的 DB 運作設定；username、password、已分類欄位及設定的敏感值仍會保護。回應會設定 `Cache-Control: no-store` 並寫入 audit log。

`server.security.checkCrossOrigin` 預設為 `false`，只控制 state-changing API 的 Origin 相等檢查；設為 `true` 時，不匹配會回傳 `ATT-SERVER-ORIGIN-MISMATCH`。關閉此檢查不會略過 local cookie authentication 的 CSRF 驗證或 login/bootstrap 的 Origin 檢查，也不會啟用 CORS 或 wildcard header。反向代理應以可信來源設定 Tomcat `RemoteIpValve`，讓 Servlet request 提供外部 scheme、host 及 port；應用程式不會信任任意 forwarded header。Origin 檢查關閉時 Server 會記錄啟動警告。

瀏覽器建立的 Quick Load 及 Advanced Load draft 另有獨立 opt-in：`server.inlineLoad.enabled` 預設為 `false`。啟用後，Server 會在 Engine 驗證後及進入佇列前，再次檢查 workload 數、target 數、Virtual User 總數、Arrival Rate 總和、每個 workload 及整體並行數上限，以及完整時間窗口上限。以下範例設定保守預設值。時間窗口上限為 warmup、ramp-up、duration、ramp-down 的總和；target 數包含固定 target 及每個 VU mix entry。功能停用時回傳 `403 ATT-SERVER-INLINE-LOAD-DISABLED`；超出設定上限的 draft 會回傳 `400 ATT-SERVER-INLINE-LOAD-LIMIT`，並以摘要指出超出的設定。這些限制適用於瀏覽器建立的 draft；既有 path-based Load request 維持相容行為，並繼續受 `workers.maxConcurrentLoad` 限制。

週期性 package-backed Load 排程另有獨立 opt-in：`server.scheduling.enabled` 預設為 `false`。啟用前請先選定管理員角色並檢視排程上限。已驗證部署需要 Servlet container role `server.scheduling.adminRole`（預設 `ATT_ADMIN`）；請在 Tomcat 將此 role 對應至可信操作人員。只有在 `authenticationRequired: false` 且排程已啟用時，`allowAnonymousManagement` 才預設為 `true`；設為 `false` 可保留 role 限制。匿名 caller 會共享排程管理權限，Server 會記錄明顯啟動警告；這只適用刻意隔離的測試部署，不建議用於共享或正式環境。此功能需要有界 package inspection。排程定義及執行歷史保存在 `server.dataDir` 的 H2 資料庫；請在同一 dataDir 只執行一個 Server 程序。每個排程預設保留最新 10,000 筆 terminal occurrence（`maxExecutionsPerSchedule` 可設為 100–100,000）；執行中及處理中的 occurrence 會保留。本 scheduler 不提供多節點 leader election。

排程可使用 package-relative Load scenario 檔案，或將有效的 Quick/Advanced Load draft 存成 snapshot。建立 draft snapshot 時，resolved principal、package 及 environment 必須與 draft 相同；Debug draft 不可排程。有界 Inspector 會在保存前及每次 occurrence 前驗證和正規化完整 scenario 及 target closure。Schedule 會私下保存 normalized scenario，只在 API view 回傳 `inlineSnapshot: true`，不包含 scenario 內容。Inline snapshot 不可直接替換或編輯，亦不可使用獨立 Load overrides；如需變更 workload，請以新的有效 draft 建立新排程。即使瀏覽器建立的 Load draft 停用，Server 的 Load users、rate、duration、workload 及 target 上限仍然適用。每個工作都經標準 Worker 和 Server Load admission 路徑提交；既有 Load concurrency 及 queue 上限仍然適用。不接受輸出路徑、package root、Worker 設定、憑證或 caller 提供的 principal。工作使用 Server 建立的 `load-schedule:<id>` principal。提交失敗會記錄且不重試。已接納工作的狀態與 evidence 維持標準 job 生命週期，與 schedule occurrence 分開。

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
| `maxDurationSeconds` | 3,600 | 1–86,400 | warmup、ramp-up、duration 及 ramp-down 總和 |

`dataDir` 必須是絕對路徑。啟動時，allowed root、package root 及 Worker library directory 必須已存在。系統會將 package 路徑 canonicalize；若 symlink 指向 allowed root 以外，便會拒絕。Client 提交 `packageId`，不能指定 package 路徑、輸出路徑、Worker 執行檔或 classpath。v1 不提供 package 註冊或修改 API。

Server 狀態存放於 `dataDir/db/`。每項工作在 `dataDir/jobs/<jobId>/` 保存有上限的事件日誌及執行輸出。Server 重啟後會將舊的排隊或執行中工作標記為 `ERROR`，診斷碼為 `ATT-SERVER-INTERRUPTED`，不會重新執行。

## 驗證與身份

`server.auth.mode` 選擇驗證提供者。為保留現有部署，預設為 `container`。設為 `local` 後，ATT 會管理本地帳戶。Local mode 必須同時設定 `authenticationRequired: true`；若與舊式匿名設定 `false` 一起使用，Server 會拒絕啟動。

在 `container` mode 中，Tomcat 驗證請求，ATT 使用 Servlet Principal。現有 BASIC、FORM 及 SSO 部署會保留由 container 管理的登入流程。Health 及 version 維持公開。舊式 `server.authenticationRequired: false` 只適用於 container mode，會讓 UI 及 API 共用匿名存取，只應用於隔離且可信的網絡。所有匿名 caller 共用 drafts、jobs 及 artifacts。啟用時 ATT 會在啟動日誌發出警告。

在 `local` mode 中，未登入的瀏覽器開啟 `/ui/` 時會轉至 ATT 登入頁；REST API 則回傳結構化 JSON `401`。首次管理員帳戶使用 `dataDir/bootstrap-admin.token` 內的隨機一次性 token 建立。請限制 `dataDir` 只供 Server 作業系統帳戶存取，在登入頁輸入 token 完成建立，之後移除對 token 檔案的存取。初始化時會先寫入權限受限的暫存檔，再以原子 rename 安裝 token；啟動時只有在尚無帳戶且 bootstrap 未被認領時才會修復無效 token。Bootstrap 一經認領便不會重新開啟。系統沒有預設帳戶或密碼。除 loopback 開發外，所有包含本地密碼的請求（登入、bootstrap、建立帳戶、重設及自行變更密碼）都必須使用 HTTPS。Tomcat 只會透過已設定的可信任 proxy 判斷 proxy 終止的 HTTPS；不可信任的 forwarded header 不會建立 HTTPS 狀態。本地密碼以每位用戶獨立 salt 的 PBKDF2-HMAC-SHA-256 雜湊保存。首個版本支援 `ADMIN` 及 `USER`；所有已驗證用戶均可使用一般 Server 操作，帳戶管理及 Scheduled Load 管理則要求 `ADMIN`。

Local session 使用不透明的 server-side identifier，登入時會輪替，閒置 30 分鐘或累計 12 小時後失效。登出、密碼變更或重設、停用帳戶都會撤銷 session。Server 重啟後，未到期或未撤銷的 session 仍有效。Cookie 設為 HttpOnly、SameSite=Lax，Path 限於部署 context；Tomcat 判斷請求使用 HTTPS 時亦會設 Secure。啟用 TLS termination 前，請先設定可信任的反向代理，讓 Tomcat 識別外部 HTTPS scheme。所有使用 cookie 驗證的狀態變更請求，以及由瀏覽器發出的 Basic-authenticated 狀態變更請求，必須在 `X-ATT-CSRF` 提交 CSRF token；登入及一次性 bootstrap endpoint 則要求 same-origin JSON。設為 `security.checkCrossOrigin: false` 只會放寬 Origin 相同來源檢查，不會略過 cookie CSRF token，也不會略過登入/bootstrap 的 same-origin JSON 要求，亦不會啟用 wildcard CORS。同一 login ID 及 client address 在 10 分鐘內失敗 8 次，或同一 client address 以不同 login ID 失敗 32 次後，登入會被限速。Bootstrap 每個 client address 在 10 分鐘內最多可輸入錯誤 token 8 次。自行變更密碼的 current-password 驗證使用相同限速及有界 hash semaphore。這些記憶體限速只適用於單一 Server process；多個 instance 請在共用的 edge 層設定 rate limit。登入回應不會區分不存在、已停用或密碼錯誤的帳戶。登入成功及失敗都會寫入 audit，並不記錄憑證。密碼以 310,000 次 PBKDF2-HMAC-SHA-256 計算雜湊；成功登入時會升級計算次數較低的舊 hash。

`GET /api/v1/auth/me` 回傳安全的目前用戶資料及瀏覽器所需的 CSRF token。`POST /api/v1/auth/login` 及 `/api/v1/auth/logout` 管理 session。使用 local account 的 CLI 及 REST client 亦可透過 HTTPS Basic authentication 存取。管理員可使用 `GET`、`POST /api/v1/auth/users`、`POST /api/v1/auth/users/{loginId}/password`、`POST /api/v1/auth/users/{loginId}/enable` 及 `DELETE /api/v1/auth/users/{loginId}` 管理帳戶。`POST /api/v1/auth/password` 會先核對目前密碼，再變更自己的密碼。API 回應不會包含密碼雜湊、session identifier 或密碼。

在 Studio 中，local `ADMIN` 可於 **Settings → User Management** 管理帳戶。列表只顯示登入 ID、顯示名稱、角色及啟用狀態。建立、啟用／停用及重設密碼都需要明確確認；停用或重設密碼會撤銷該用戶的 session。即使來自不同 Server runtime 的請求同時執行，Server 仍會拒絕停用最後一個啟用中的 `ADMIN`。所有 local 用戶均可在目前用戶選單使用 **Change password**。密碼變更成功後會撤銷目前 session，並返回登入頁面。密碼只會在表單開啟時暫存，不會寫入瀏覽器儲存空間或應用程式日誌。Container mode 的 Settings 會說明帳戶由 Tomcat 或設定的 SSO provider 管理。

### 復原失去存取權的本地管理員

一次性 bootstrap 不會重新開啟，也不是帳戶復原方式。若現有 `ADMIN` 忘記密碼或已被停用，請使用離線復原工具。此操作不能建立帳戶；若帳戶不存在或不是 `ADMIN`，工具會拒絕執行。

1. 停止所有使用此 `server.dataDir` 的 Tomcat process，並等待完全結束。以下命令須以 Server 作業系統帳戶執行，並可寫入現有的 `dataDir`。
2. 設定 `ATT_SERVER_WAR` 為部署中的 `att-server-4.1.4.war`、`CATALINA_HOME` 為 Tomcat 安裝目錄，以及 `JAVA_HOME` 為 Java 17 或更新版本。將 WAR 解壓至權限受限的暫存目錄，再以套件內 library 及 Tomcat Servlet API 啟動工具：

   ```sh
   umask 077
   recovery_dir="$(mktemp -d)"
   trap 'rm -rf "$recovery_dir"' EXIT
   (
     cd "$recovery_dir"
     "$JAVA_HOME/bin/jar" xf "$ATT_SERVER_WAR"
     "$JAVA_HOME/bin/java" -Datt.server.config=/absolute/path/to/att-server.yaml \
       -cp "WEB-INF/lib/*:$CATALINA_HOME/lib/*" att.server.AuthRecoveryTool
   )
   ```

3. 按互動提示輸入現有 ADMIN 登入 ID、輸入 `RECOVER ADMIN` 確認，然後輸入新密碼兩次。密碼輸入時不會顯示，也不接受作為命令列參數。
4. 重新啟動 Tomcat，並使用新密碼登入。

工具只會更新所選的現有 `ADMIN` row；若帳戶已停用，會重新啟用，並更新密碼雜湊及 CSRF token、撤銷該帳戶所有 session，以及記錄 `ADMIN_RECOVERY` audit event。工具不會修改 bootstrap claim 或建立 bootstrap token。若找不到現有資料庫、資料庫無法開啟，或指定帳戶不是 `ADMIN`，復原會失敗且不會建立帳戶。請保護備份、暫存解壓目錄及 `server.dataDir`，並視為 Server 憑證處理。

Local mode 的所有用戶在 v1 均可存取相同 package。Draft 及提交的工作歸屬於已驗證的 login ID；其他用戶即使猜中 ID，也無法存取該 draft 或 job。同一歸屬檢查亦保護 job 狀態、結果、SSE、artifacts 及取消操作。Container mode 保留現有授權行為。

匿名 job submission 可在最外層 JSON 加入選填的 `principal` label，只供顯示及 audit 使用。Server 會 trim 並以 Unicode 正規化 label、驗證長度及字元，然後在既有 job/audit `principal` 欄位加上 `(anonym)` 後綴，例如 `Bob(anonym)`。省略時維持既有值 `anonymous`；caller 不可自行提交保留後綴。此 label 由 caller 自行聲稱，不會改變授權、draft 存取、job 可見範圍、artifact 存取、取消或排程。驗證模式永遠使用 Servlet Principal，並忽略提交的 label。此歸屬資料不會傳給 Worker，也不會放進 ATT expression Context；不會新增持久化欄位或回應欄位。

改變狀態的請求必須使用 `application/json`；如請求帶有 `Origin`，必須與請求來源相同。若 TLS 在反向代理終止，請設定 Tomcat `RemoteIpValve`，由代理的 forwarded headers 還原 Servlet scheme、host 及 port。`internalProxies` 只可列出實際代理位址，並確保代理會先移除用戶提交的 `Forwarded`/`X-Forwarded-*` headers，再加入自身的值。請依代理實際使用的 header 名稱及受信任位址調整設定：

```xml
<Valve className="org.apache.catalina.valves.RemoteIpValve"
       internalProxies="10\\.20\\.0\\.12"
       remoteIpHeader="X-Forwarded-For"
       protocolHeader="X-Forwarded-Proto"
       hostHeader="X-Forwarded-Host"
       portHeader="X-Forwarded-Port" />
```

`RemoteIpValve` 只應信任實際 proxy 位址。Proxy 加入 forwarded headers 前，必須先移除 client 自行提交的值。請將 Tomcat 綁定 loopback 或 private interface，並用 VPN、防火牆或 IP allowlist 保護 proxy。localhost 以外的流量必須使用 HTTPS。Draft 存於記憶體，Server 重啟後會清除。切換驗證 mode 需要重啟；現有 draft 不會轉移到其他身份。Local mode 不會讀取或修改 JSPWiki 帳戶檔案，也不提供外部 LDAP、OIDC、SAML 或共用 SSO。

內建瀏覽器主控台位於 `<context path>/ui/`，使用所選的 container 或 local 驗證 mode。Local mode 會在主控台提供帳戶管理及密碼變更功能。詳見 [ATT Server Web UI](att-server-web-ui.zh.md)。

## REST API

所有 endpoint 位於 `/api/v1`。除 SSE stream 及 artifact 下載外，請求與回應均使用 JSON。

| 方法 | 路徑 | 用途 |
| --- | --- | --- |
| `GET` | `/health`、`/version` | 健康狀態、build 及瀏覽器 Load capability 資訊 |
| `GET`、`POST` | `/auth/bootstrap/status`、`/auth/bootstrap` | 檢查及執行一次性本地管理員初始化 |
| `POST`、`GET` | `/auth/login`、`/auth/logout`、`/auth/me` | 建立／撤銷 local session 及讀取目前用戶資料 |
| `GET`、`POST`、`DELETE` | `/auth/users`、`/auth/users/{loginId}` | 列出、建立、停用、啟用或重設本地帳戶（管理員） |
| `POST` | `/auth/password` | 變更目前本地用戶的密碼 |
| `GET` | `/metrics` | 有界的工作、Worker 及已啟用排程數量 |
| `GET` | `/packages`、`/packages/{packageId}` | 讀取設定中的 registry |
| `GET` | `/packages/{packageId}/resources?type=case&query=...&limit=50&cursor=...` | 列出安全的 Case、Template、Flow 及 Tool 投影 |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}` | 讀取單一資源定義及關聯 |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}/source` | 讀取已淨化 YAML 投影或合資格的 package-local Tool 文字；檔案引用須使用 Server 授權的不透明 ID |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}/source-file?referenceId=...` | 透過不透明的資源範圍引用讀取一個合資格文字檔 |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}/debug-form?environment=SIT` | 讀取目標範圍內的安全 Debug 預設值 |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}/quick-load-form?model=virtualUsers&environment=SIT` | 讀取安全預設值及單 workload Quick Load 預覽 |
| `GET` | `/packages/{packageId}/load-policy?model=virtualUsers&environment=SIT` | 讀取 Advanced Load 使用的安全 model policy 預設值 |
| `POST` | `/drafts/debug` | 驗證固定目標的 Debug 輸入並建立記憶體 draft |
| `POST` | `/drafts/quick-load` | 驗證固定目標的 Quick Load 並建立記憶體 draft |
| `POST` | `/drafts/load` | 驗證 `att-load/v1.6` scenario 並建立記憶體 draft |
| `GET` | `/drafts/{draftId}` | 讀取所屬 Debug、Quick Load 或 Advanced Load draft 的安全預覽 |
| `POST` | `/jobs/debug`、`/jobs/load` | 提交所屬 Debug、Quick Load 或 Advanced Load draft |
| `POST` | `/jobs/run`、`/jobs/load`、`/jobs/validate` | 提交 path-based 工作 |
| `GET` | `/jobs`、`/jobs/{jobId}` | 列出近期工作或讀取狀態 |
| `GET` | `/jobs/{jobId}/result` | 讀取標準結果及診斷 |
| `GET` | `/jobs/{jobId}/events` | 接收保留及即時事件 |
| `GET` | `/jobs/{jobId}/artifacts` | 列出輸出檔案 |
| `GET` | `/jobs/{jobId}/artifacts/{path}` | 下載輸出檔案 |
| `DELETE` | `/jobs/{jobId}` | 取消執行中工作並保留歷史 |
| `GET`、`POST` | `/load-schedules` | 列出或建立排程（僅管理員） |
| `POST` | `/load-schedules/preview` | 只驗證 timezone/cron 並預覽執行時間（僅管理員） |
| `GET`、`PATCH`、`DELETE` | `/load-schedules/{scheduleId}` | 讀取、編輯或刪除排程（僅管理員） |
| `POST` | `/load-schedules/{scheduleId}/pause`、`resume`、`trigger` | 暫停、恢復或手動觸發（僅管理員） |
| `GET` | `/load-schedules/{scheduleId}/executions?limit=100` | 讀取 occurrence history 及已接納工作連結 |

排程停用時，相關 endpoint 會回傳 `404`。啟用後，已驗證 caller 預設需要 `ATT_ADMIN` role。Browser/API 狀態變更請求亦會套用同源檢查。建立排程的 JSON 範例如下：

```json
{
  "name": "Nightly checkout load",
  "packageId": "payments",
  "scenario": "load/checkout.yaml",
  "config": "config/config.yaml",
  "environment": "SIT",
  "timezone": "Asia/Hong_Kong",
  "cron": "0 2 * * *",
  "load": { "duration": "10m" },
  "maxConcurrentRuns": 1,
  "enabled": false
}
```

如要排程已驗證的 UI draft，請用 `draftId`（`A...` Advanced Load ID 或 `L...` Quick Load ID）取代 `scenario`，並提供驗證時使用的相同 `environment`。此方式只適用於建立排程；之後不可替換已保存的 snapshot。

Preview endpoint 只檢查 recurrence 並計算執行時間；不會驗證 package scenario、Testdata 或已保存的 draft。`POST /load-schedules` 會在保存前驗證完整 scenario 及 target closure。驗證拒絕會以 HTTP 400 回傳 `error` 及 `diagnostics` 陣列。每項可用診斷可包括 code、summary、detail、field、source file 及行/欄位置、package ID、environment、resource/target ID 和 suggestion。頂層 `requestId` 亦會複製到每項診斷。Server API 診斷會遮蔽本機絕對路徑。UI 會在排程編輯器旁顯示這些資料，並可將焦點移至相符欄位或開啟已保存的 snapshot。

排程預設為 paused。建立、編輯及恢復時會按目前時間計算下一個符合的 instant。Occurrence 狀態為 `scheduled`、`submitted`、`skipped` 及 `failed-to-submit`；job 執行結果仍使用一般 job API。刪除排程會移除定義，但保留歷史，也不會取消已接納的工作。刪除前請記下 UI/API 顯示的 schedule ID；只要仍有保留記錄，管理員在定義刪除後仍可使用 `/load-schedules/{scheduleId}/executions` 讀取歷史，即使該排程不再出現在 UI 或 schedule list。History 受 `maxExecutionsPerSchedule` 限制；處理中 occurrence 及連結至執行中工作的 occurrence 可暫時超出上限，相關工作進入 terminal 狀態時會執行 pruning，schedule 定義刪除後亦會如此。

使用穩定 package ID 及該 ATT Worker command 的欄位提交工作：

```http
POST /api/v1/jobs/run
Content-Type: application/json

{"packageId":"payments","all":true,"principal":"Bob"}
```

選填的 `principal` input 只會在匿名模式使用。請求傳送原始值；Server 會自行加上 `(anonym)`，並將結果存入既有的 `principal` property。省略時仍使用 `anonymous`。

API 會回傳 `202 Accepted` 及 job ID。工作狀態依序為 `QUEUED`、`PREPARING`、`RUNNING`，最後為 `PASS`、`FAIL`、`ERROR`、`INVALID` 或 `CANCELLED`。容量已滿時會回傳 `429` 及 `ATT-SERVER-CAPACITY-EXCEEDED`。錯誤使用包含 `code`、`summary`、`detail` 及 `requestId` 的 `error` 物件；Server 不會回傳 stack trace。

啟用 `server.inlineLoad.enabled` 前，瀏覽器 Quick 及 Advanced Load endpoint 會回傳 `403 ATT-SERVER-INLINE-LOAD-DISABLED`。超出已啟用上限的 scenario 會回傳 `400 ATT-SERVER-INLINE-LOAD-LIMIT`，摘要會指出超出的設定，且 scenario 不會進入佇列。

工作記錄在有量度數據時會加入 `performance` 物件。`performance.timings` 使用 monotonic clock 記錄 admission、佇列等待、Worker 準備及啟動、Worker-ready（首個 `STATUS`）、execution-ready（首個 `PROGRESS` 或 `LOG`）、Worker 存活時間及結果至終止時間。`performance.worker` 記錄隔離 Worker 的 Heap、live thread、GC、process CPU 及抽樣 RSS 峰值。抽樣由事件觸發，每 100 ms 最多一次，可能漏掉短暫峰值。RSS 只在 Linux `/proc` 系統提供。Worker 資源數據會保存在標準工作記錄，Server 重啟後仍可讀取。

`GET /jobs` 回傳最近 100 項工作。可加上 `?principal=<text>`，在既有 `principal` property 內作不區分大小寫的文字搜尋；搜尋會在 100 項上限前執行，並採用子字串比對，因此搜尋 `alice` 亦會匹配 `alice(anonym)`。Web UI 及 `att remote jobs --principal <text>` 均使用此篩選。舊紀錄保留原有值，包括先前的 `anonymous` 紀錄。系統不會保存提交 payload，只保存 command 及 package ID 摘要。Engine result 及診斷會作為控制平面 metadata 保存。較大的報告、log 及其他證據會留在工作輸出目錄。

資源探索可用 `case`、`template`、`flow` 或 `tool` 篩選。每頁預設 50 項，最多 100 項；續頁 cursor 會加密並綁定 Server 解析出的身份及查詢。匿名模式下所有 caller 共用同一 cursor 身份。若 package 內容改變，舊 cursor 會以 `409` 拒絕。Explorer 回傳邏輯資源 ID、安全投影、來源資訊、關聯及穩定診斷碼。YAML source 是解析及淨化後的投影，並非原始檔案內容。預設 `safe-package-files` policy 會在無須逐檔 allowlist 的情況下提供合資格、有界的 package-local Tool 文字；`allowlist-only` 則要求 `server.inspection.safeTextSources` 內完全相同的 package-relative 路徑。External scripts 必須位於已設定的 external root，並列入該 package 的 canonical 精確 allowlist；回應不會返回其 filesystem path。denyGlobs 對 package 檔案比對 package-relative path；對 external 檔案則比對相對於最具體 canonical external root 的路徑。Template/Flow 或 Configuration source 內安全的 `&{...}` 引用可透過有界 source route 讀取合資格的 package-local 文字；Server 會確認 canonical path 留在 package root，檢查副檔名、deny path、文字編碼、大小及 visibility rules，並使用綁定 package 和 origin 的 Server 不透明 `referenceId`。獨立 Configuration source route 會對 `config/` 下的合資格文字套用相同 visibility policy，並不會提供通用檔案系統瀏覽器。Resource source route 不會提供原始 config、Testdata descriptor 或 Debug sidecar；合資格且非敏感的 configuration 和 Testdata 文字可透過 Configuration route 讀取。憑證及個人資料值一律受保護。探索程序不會執行 Tool，也不會暴露檔案系統瀏覽器；檢查佇列、Worker heap、逾時及 response 大小均與一般工作執行分開限制。

### 套件資源探索

這些公開唯讀 endpoint 使用 Server 解析出的身份：驗證模式下為 Servlet Principal，匿名模式下為共享的 `anonymous` 身份。`kind` 可為 `case`、`template`、`flow` 或 `tool`；`resourceId` 是清單 endpoint 回傳的不透明套件範圍 ID。清單的 `query` 會比對邏輯 ID、名稱、描述及 tags。省略 `type`、`query` 或 `limit` 時會使用預設值。續頁 cursor 只適用於相同 package、身份、type 及 query。

```http
GET /api/v1/packages/payments/resources?type=case&query=refund&limit=50
```

回應包含安全摘要、總數，以及存在下一頁時才提供的不透明 `nextCursor`：

```json
{
  "items": [{
    "resourceId": "case.<opaque>",
    "type": "case",
    "logicalId": "PAYMENT.REFUND01",
    "name": "Refund request",
    "state": "ready",
    "sourceAvailable": false,
    "provenance": {"suite": "testcase/payments.xlsx", "groupId": "PAYMENT", "sheet": "Cases", "rowNumber": 12},
    "references": [{"type": "template", "logicalId": "PAYMENT.refund", "resourceId": "template.<opaque>", "resolution": "resolved"}],
    "referencedBy": [],
    "diagnostics": []
  }],
  "total": 1,
  "nextCursor": null,
  "diagnostics": [],
  "requestId": "..."
}
```

先讀取單一資源的解析後定義及關聯，再另外要求安全來源投影：

```http
GET /api/v1/packages/payments/resources/template/{resourceId}
GET /api/v1/packages/payments/resources/template/{resourceId}/source
GET /api/v1/packages/payments/resources/template/{resourceId}/source-file?referenceId=<opaque>
```

詳細回應包含 `resource`、`definition`、`diagnostics` 及 `requestId`。Template/Flow YAML source 回應包含 `resource`、`available`、`format`、`text`、`redacted`、`fileReferences`、`fileReferencesTruncated` 及 `requestId`。每個合資格 `fileReferences` 項目包含 package-relative `logicalPath` 及不透明 `referenceId`；將 ID 傳給 `source-file` 後，Server 會再次執行 canonical containment 及 visibility 檢查並回傳有界文字。Source-file 回應包含 `logicalPath`、已淨化的 `text` 及 `redacted`。此 route 使用與其他 inspection endpoint 相同的身份及授權 mode；匿名模式仍受個別 inspection feature gate 限制。不要由 logical path 建立 filesystem URL。來源不可用時，`available` 為 `false`，會回傳穩定 reason，而且不會回傳部分內容。找不到目標的關聯會標示 `resolution: "unresolved"`，並附有穩定資源診斷碼。

無效清單參數會回傳 `400`；未知 package/resource 使用相同的 `404` 格式。過期 cursor 回傳 `409`，超大回應回傳 `413`，探索佇列已滿回傳 `503`，Inspector 逾時回傳 `504`。錯誤回應使用既有的 `error.code`、`error.summary` 及 `requestId` 格式，不會包含實體路徑或 parser exception 訊息。

### 目標範圍內的 Debug 與 draft

Debug form、Quick Load form、Load policy 及 draft endpoint 使用 Server 解析出的相同身份。匿名模式下，一位 caller 建立的 draft 可由所有其他匿名 caller 使用。請使用 Package Resource Explorer 回傳的資源 ID；Case 不能作為 Debug 或 Quick Load 目標。

```http
GET /api/v1/packages/payments/resources/flow/{resourceId}/debug-form?environment=SIT
```

回應包含所選資源、固定邏輯目標、安全的 `att-debug/v1.2` 輸入預設值、`redacted` 標記及 `requestId`。若 sidecar 不存在，Server 會提供有效的空白預設值。敏感欄位會在回應前遮蔽。

提交 typed input 以建立 Server 簽發的 draft：

```http
POST /api/v1/drafts/debug
Content-Type: application/json

{"packageId":"payments","environment":"SIT","target":{"type":"flow","id":"PAYMENT.submit"},"input":{"inputs":{"channel":"WEB"},"vars":{"reference":"REF001"}}}
```

回應包含不透明 `draftId`、安全 YAML 預覽、驗證診斷及過期時間。`GET /api/v1/drafts/{draftId}` 只會向擁有該 draft 的 Server 解析身份回傳相同安全預覽。Draft 僅存於記憶體，最多 10 分鐘；每個 Server 最多 128 個有效 draft，每個身份最多 16 個。匿名模式下所有 caller 共用此身份及限額。Server 重啟會令 draft 失效。Server 不會回傳內部 package revision digest。

提交時只傳 draft identity：

```http
POST /api/v1/jobs/debug
Content-Type: application/json

{"packageId":"payments","draftId":"D0123456789ABCDEF0123456789ABCDEF"}
```

Server 會在提交時及 Worker 啟動前重新檢查 package revision。若在提交時發現變更，會回傳 `409 ATT-SERVER-DRAFT-STALE`；若工作已接受後才發現變更，工作會以該 diagnostic 標記為 `INVALID`。驗證失敗會在頂層 `diagnostics` array 回傳安全的 `code`、`summary`、`field` 及邏輯 `resourceId`；不會包含 source path 或 parser snippet。Worker 會收到與預覽驗證相同的不可變 typed values；inline input 留在記憶體，不會在 package root 下建立檔案。舊有以路徑提交的 Debug request 仍可供相容用戶端使用。

### 目標範圍內的 Quick Load

設定 `server.inlineLoad.enabled: true` 後，Quick Load 才可從已選取的 Template、Flow 或 Tool 開啟；它固定使用該目標並組成一個 Workload。Model 可選 `virtualUsers` 或 `arrivalRate`；Server 會組成相符的現行 `att-load/v1.6` policy，並使用現有 Load pipeline 驗證目標。功能停用時，Quick Load form、policy 及 draft endpoint 都會回傳 `403 ATT-SERVER-INLINE-LOAD-DISABLED`。

```http
GET /api/v1/packages/payments/resources/flow/{resourceId}/quick-load-form?model=virtualUsers&environment=SIT
```

表單回應會為 Template/Flow 提供安全的 `inputs`、`vars` 預設值，或為 Tool 提供 `inputs`、`arguments`，並附上已遮蔽的單 workload 預覽。它只讀取目標選填 `debug.yaml` 內的 business 欄位；不會複製 Debug 專用的 `case`、`stage` 及 local `testdata`。若 sidecar 包含 Debug-local Testdata，表單會明確提示這些 imports 已略過。需要的 package-relative descriptor 可另外放入 Load-level `testdata` array，並會按 effective Load scenario 驗證。

Server 會為 `virtualUsers` 讀取 `load/load.visualuser.yaml`，為 `arrivalRate` 讀取 `load/load.arrivalrate.yaml`。這些是 policy-only 的 `att-load/v1.6` descriptor，必須與所選 model 相符。若 model 專用檔案不存在，會使用內建低強度 fallback：一個 Virtual User 執行 10 秒，或每秒 1 次 arrival、執行 10 秒，並設定 `maxConcurrent: 1` 及 `overloadPolicy: drop`。既有 CLI `load/load.yaml` 行為不變。

提交 business values 及 pacing overrides 以建立所屬 draft：

```http
POST /api/v1/drafts/quick-load
Content-Type: application/json

{"packageId":"payments","environment":"SIT","target":{"type":"flow","id":"PAYMENT.submit"},"model":"virtualUsers","input":{"inputs":{"channel":"WEB"},"vars":{"reference":"REF001"}},"load":{"users":4,"duration":"30s"},"execution":{"thinkTime":"100ms"},"testdata":["testdata/load-accounts.yaml"]}
```

`load` 只接受所選 model 支援的 pacing 欄位；`execution` 可包含 closed-workload 的 `thinkTime`。可選的 `testdata` array 可指定額外 package-relative Load descriptor paths；這些路徑與 Debug-local imports 分開處理，並會附加於 model policy 既有 descriptors。回應會提供綁定 Server 解析身份的不透明 `draftId`（以 `L` 開頭）、安全的 effective YAML 預覽、遮蔽狀態及過期時間。Debug 與 Quick Load drafts 共用每個 Server 最多 128 個、每個身份最多 16 個、有效期 10 分鐘的記憶體限制。

檢視預覽後，只提交 draft identity：

```http
POST /api/v1/jobs/load
Content-Type: application/json

{"packageId":"payments","draftId":"L0123456789ABCDEF0123456789ABCDEF"}
```

Server 會在 draft 提交時、Worker 啟動前及 Worker 內重新檢查 package revision。若 draft 過期會回傳 `409 ATT-SERVER-DRAFT-STALE`；工作接納後才發現變更，則會標示為 `INVALID` 並附該診斷。Worker 會收到與預覽驗證相同的不可變 scenario；內容只留在記憶體，不會寫入 package。舊有 path-based Load request 仍受支援。

### Advanced Load builder

設定 `server.inlineLoad.enabled: true` 後，Advanced Load 才會建立包含一個或多個 workload 的完整 `att-load/v1.6` scenario。所有 workload 必須使用同一 `virtualUsers` 或 `arrivalRate` model。Virtual Users workload 可使用單一固定 target 或 weighted `mix`；每個 Arrival Rate workload 使用一個固定 target。Engine 會在 Server 建立 draft 前驗證共用時間窗口、workload intensity、thresholds、Testdata policy 及所有 target，Server 隨後會檢查管理員設定的 inline Load 上限。若超出任何上限，回傳 `400 ATT-SERVER-INLINE-LOAD-LIMIT` 及安全摘要，且不會排程 scenario。

讀取安全的 model policy 預設值：

```http
GET /api/v1/packages/payments/load-policy?model=virtualUsers&environment=SIT
```

回應包含 `model`、已遮蔽的 `policy`、安全的 `previewYaml`、`redacted` 標記及 `requestId`。此唯讀 endpoint 不會建立 draft。

提交完整 scenario 以建立 Server 簽發的 draft：

```http
POST /api/v1/drafts/load
Content-Type: application/json

{
  "packageId": "payments",
  "environment": "SIT",
  "scenario": {
    "schemaVersion": "att-load/v1.6",
    "load": {"users": 1, "duration": "30s"},
    "workloads": [
      {"id": "browse", "target": {"type": "flow", "id": "PAYMENT.browse"}, "load": {"users": 4}},
      {"id": "submit", "target": {"type": "template", "id": "PAYMENT.submit"}, "load": {"users": 2}}
    ]
  }
}
```

Server 會驗證完整 scenario 並 resolve 所有 target，不會排程流量。只會從所選 target package-local `debug.yaml` 的 business 欄位還原已遮蔽值；不會複製 Debug `case`、`stage` 或 local Testdata。回應包含以 `A` 開頭的不透明 draft ID、安全 effective YAML 預覽、model、遮蔽標記及過期時間。Advanced Load 與 Debug、Quick Load 共用每個 Server 最多 128 個、每個身份最多 16 個 draft 及 10 分鐘有效期限制。

向 `POST /api/v1/jobs/load` 只提交 package 及 draft ID。Server 會在提交及 Worker 啟動前檢查 package revision，再透過既有 Worker、Engine、scheduler、event 及 evidence 流程傳遞相同的不可變 normalized scenario。若 package revision 已變更，會回傳 `409 ATT-SERVER-DRAFT-STALE`；工作接納後才發現變更，工作會標示為 `INVALID`。若預覽有任何遮蔽欄位，瀏覽器會停用 YAML 複製及匯出。舊有 path-based Load request 仍受支援。

### Package configuration inspection

設定檢查使用與 package resource 相同、受界限限制的 inspection Worker。以下唯讀 endpoint 使用 Server 解析出的已驗證或匿名身份：

```http
GET /api/v1/packages/payments/configuration?view=declared
GET /api/v1/packages/payments/configuration?view=declared&includeAllFields=true
GET /api/v1/packages/payments/configuration/effective?environment=SIT
GET /api/v1/packages/payments/configuration/effective?environment=SIT&section=dbhelpers&offset=0&limit=50
GET /api/v1/packages/payments/configuration/effective?environment=SIT&section=dbhelpers&includeAllFields=true
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT&section=dbhelpers
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT&section=dbhelpers&includeInherited=true&offset=0&limit=50
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT&section=dbhelpers&includeAllFields=true
GET /api/v1/packages/payments/configuration/source?path=templates%2Fpayment%2Fpayload%2Frequest.xml&origin=flows%2Fpayment.yaml
GET /api/v1/packages/payments/configuration/testdata/accounts/records?environment=SIT&offset=0&limit=50
GET /api/v1/packages/payments/configuration/testdata/generatedAccounts/records?environment=SIT&offset=0&limit=50&previewGenerated=true
```

Declared view 會回傳 schema version、安全的 global 欄位、已設定 profile，以及每個設定 section 的 declared/absent 狀態、項目數量和 profile 繼承或替換狀態。Effective view 使用 `FrameworkConfigLoader` 選擇 environment，並回傳安全的 helper、Tool 及 Testdata descriptor metadata。Root-only package 或使用已設定的預設值時可省略 `environment`。第一個 effective 回應會提供各 section 的數量；按 section ID（`dbhelpers`、`mqhelpers`、`sshhelpers`、`httphelpers`、`tools` 或 `testdata`）要求其項目。Effective section 及 comparison 欄位回應支援 `offset` 與 `limit`（1–100，預設 50），並回傳 `total` 和 `nextOffset` 以供分頁。比較要求必須提供 section ID；Server 會先驗證並篩選兩個 profile，再分頁。檢查過程不會連接 DB、MQ、HTTP 或 SSH 服務，也不會回傳未限制的設定 YAML。只有所選 environment 已設定的 Testdata descriptor 可透過上述 records endpoint 分頁預覽；一般值預設可見，憑證、認證資料及常見個人資料欄位始終遮蔽。啟用 `redaction` 後亦會套用設定的敏感路徑和欄位 pattern。未知或未設定的 descriptor 回傳 `404`。

Compare 預設比較 profile-sourced values；`includeInherited=true` 會一併包括 inherited/global values 及其來源。Section filter 會在分頁前套用。比較結果會列出可顯示的 effective 欄位及其來源。敏感欄位會以 `state: "hidden"` 和 `change: "hidden"` 回傳；回應不會洩露其值是否相同。系統不回傳絕對本機路徑、憑證、Server 自身的秘密或未限制的 descriptor 內容。關閉 masking 時，非憑證 URL、host 及 port 等一般連線設定會顯示；啟用 masking 後，設定的操作欄位及路徑亦會遮蔽。

`includeAllFields=true` 會在 declared、effective 或 compare view 加入其他非秘密 configuration properties 及 evidence fields；caller 必須具備 `server.inspection.configurationAdminRole`，local-auth mode 則必須是本地 `ADMIN`。回應不會快取並會寫入 audit log。Username、password、已分類欄位及設定的敏感值仍會遮蔽。

`source` endpoint 在 `safe-package-files` policy 下可依 origin 解析合資格的 package-local 文字參照；`allowlist-only` 則要求既有 `server.inspection.safeTextSources` 精確 package-relative 路徑。連結請求必須包含已授權 parent source 回傳的 Server 不透明 `referenceId`；caller 自行提供 path 和 origin 並不足以授權。External scripts 只會透過設定 external root 下每 package 的 canonical 精確 allowlist 提供，且不會回傳其絕對路徑。這仍是有界 source endpoint，並非任意檔案瀏覽器。Testdata records endpoint 需要 effective descriptor ID。Literal records 使用 `limit` 1–100 分頁（UI 每次讀取 50 筆）。Generated descriptor 預設回傳 `state: "preview-required"` 且不含 records；只有明確設定 `previewGenerated=true` 才會回傳有界 generated preview。此 endpoint 使用相同身份、redaction policy、bounded inspection worker、source/response 大小、佇列和逾時限制。

設定回應包含 `view`、`state`、可用時的 `schemaVersion` 及 `diagnostics`。分頁回應亦包含 `section`、`offset`、`limit`、`total` 及 `nextOffset`。無效設定會以 HTTP 200 回傳 `state: "invalid"` 及穩定診斷碼；格式錯誤的 profile 參數回傳 `400`。未知 package 回傳 `404`，超大回應回傳 `413`，檢查佇列已滿回傳 `503`，Inspector 逾時回傳 `504`。回應大小、佇列和逾時限制與資源探索相同。

## Server-sent events

使用 `Accept: text/event-stream` 連接 `/api/v1/jobs/{jobId}/events`。事件保存在 `dataDir/jobs/<jobId>/events.jsonl`，每項工作使用遞增數字 ID，事件名稱為 `status`、`progress`、`log`、`diagnostic` 或 `result`。

```text
id: 2
event: status
data: {"jobId":"J...","status":"RUNNING"}

```

重新連線時傳送 `Last-Event-ID`，系統會重播 ID 較大的保留事件。較舊事件若已超出日誌保留上限，stream 會從仍保留的事件繼續。閒置時會傳送 `: keepalive` 註解，送出終端結果後會關閉。觀察端斷線不會取消工作。最多 32 個 observer 會透過 direct handoff 各自取得 stream thread；超出容量的請求會在 stream 啟動前回傳 HTTP 503。每個觀察端使用獨立的單一項目通知佇列。Worker 事件讀取器不會同步等待用戶端寫入。

## 取消與復原

`DELETE /jobs/{jobId}` 會要求 Worker 優雅終止，等待最多 `gracefulStopMs`，必要時只強制終止該 Worker。工作記錄會保留。Server 重啟後不會自動重跑尚未完成的工作。

Artifact 路徑必須相對於工作輸出目錄。絕對路徑、目錄 traversal 及 symlink escape 均會遭拒。Server 不提供一般檔案系統瀏覽或 package 修改 API。

## 發行與相容性

Server binary 會以獨立的 `att-4.2.0-server.tar.gz` 發佈，內含 `server/att-server-4.2.0.war`（ATT application code 已合併至 `WEB-INF/lib/att-server-4.2.0.jar`）及部署指南。Local CLI 位於 `att-4.2.0-local.tar.gz`，其 `lib/att-local-4.2.0.jar` 提供共用 Worker/Engine runtime；Server 會載入相同版本的 library。Tomcat 提供 HTTP listener 及 Servlet API。Server 需要 Java 17；CLI、Engine 及 Worker 維持 Java 8 目標。

### Issue #176 rollout checklist

1. 備份 Server `dataDir`，並保留目前 WAR 及部署設定以便回復。
2. 保持 Tomcat 驗證啟用，並以 HTTPS 接收公開流量。確認 package root 與 Server data directory 分離，且只有服務帳戶可寫入。
3. 請將 `ATT_HOME` 或 `-Datt.home` 設為版本相符的 Local installation，讓 Worker process 使用 `$ATT_HOME/lib/*`。Tomcat 可直接部署 packed WAR；`workers.libraryDirs` 仍可額外加入外部 JDBC、MQ 或其他 library。透過實際 Tomcat context path 檢查 `/api/v1/health` 及 `/api/v1/version`。
4. 初始維持 `server.inlineLoad.enabled: false`。先用非正式環境 package 檢視 Package 與 Configuration Explorer。只在核准的環境啟用瀏覽器 Load，並設定 workload 數、target、users 或 rate、並行數及完整時間窗口上限。
5. 監察 Worker 與 Load 佇列容量、工作結果、取消及保留的 artifacts。Package 維持唯讀；回復時停用 inline Load，必要時還原前一版 WAR 及設定。

此清單說明部署步驟，不能取代 P6 的安全性、瀏覽器、相容性、WAR 封裝及 hosted CI 驗收證據。
