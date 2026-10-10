# ATT Server 部署與 API

ATT Server 4.1.0 提供單節點控制平面，讓用戶透過版本化 REST API 提交 ATT Run、Debug、Load 及 Validate 工作。請將 WAR 部署至使用 Java 17 或更新版本的外部 Tomcat 10.1+。CLI、Engine 及 Worker 仍相容 Java 8。

## 準備部署

1. 建立由 Server 管理的資料目錄，例如 `/var/lib/att-server`，只允許 Tomcat 服務帳戶寫入。此目錄須與 ATT 安裝檔案及 package root 分開。
2. 在允許的根目錄下建立 package 目錄，並確保 Tomcat 服務帳戶可讀取。
3. 將 `config/att-server.example.yaml` 複製到受保護的位置，更新絕對路徑，並勿將憑證放進設定檔或 package 內容。
4. 在 Tomcat Java 選項設定 `-Datt.server.config=/etc/att/server.yaml`。若沒有系統屬性，亦可使用 `ATT_SERVER_CONFIG` 環境變數。
5. 將 `att-server-4.1.0.war` 部署至 Tomcat 10.1+。請保持 Tomcat 的 `unpackWARs` 啟用，讓 Server 可從 `WEB-INF/lib` 啟動 Worker。

Tomcat 負責監聽器、TLS、存取記錄及驗證。請設定 Realm、SSO 整合、用戶端憑證或其他容器支援的機制；驗證失敗時，Server 會保留容器回傳的 challenge 或 redirect。若設定、Java 基線或 package mapping 無效，Server 會在啟動時失敗。

## 設定

`server.dataDir` 儲存 H2 控制平面資料及工作輸出。`workers` 限制 Worker 並行數、佇列大小、Load admission、優雅停止逾時及可選的每個 Worker Heap 上限。`workers.heapMaxMb` 為每個 Worker 設定 `-Xmx`（64–65536 MiB）；選填 `heapInitialMb` 設定 `-Xms`（32–65536 MiB），必須同時設定 `heapMaxMb`，並且不可超過上限。請按 `maxConcurrent`、Server 及 container 的記憶體預算設定總 Heap 上限。被拒絕的提交會在建立持久工作記錄前清理。已完成工作的 metadata、journal 及 artifacts 會保留 `server.jobRetentionDays` 天（預設 30，範圍 1–3650）；啟動時及每小時清理過期工作，並保留執行中的工作。`workers.maxConcurrentLoad` 限制已接納的 Load 工作總數，包括佇列中及執行中的工作，避免等待中的 Load 佔用一般 Worker thread。Load 或整體容量超出上限時會回傳 HTTP 429。`workers.libraryDirs` 可選擇列出絕對、已存在且可讀的目錄，Worker subprocess 會將其中 JAR 加入 classpath，以載入額外 JDBC、MQ 或其他 dependency；只可設定由 Server 管理員信任的目錄。唯讀 `packages` registry 將穩定 package ID 對應到 `allowedRoots` 下的 canonical root。`server.inspection` 獨立限制唯讀資源探索，使用一次性的 Worker，並設有獨立並行數、佇列、逾時、heap、source 及 response 上限。除非 package 的 `server.inspection.safeTextSources` 明確列出相對路徑，否則不會提供 Tool script 文字。

瀏覽器建立的 Quick Load 及 Advanced Load draft 另有獨立 opt-in：`server.inlineLoad.enabled` 預設為 `false`。啟用後，Server 會在 Engine 驗證後及進入佇列前，再次檢查 workload 數、target 數、Virtual User 總數、Arrival Rate 總和、每個 workload 及整體並行數上限，以及完整時間窗口上限。以下範例設定保守預設值。時間窗口上限為 warmup、ramp-up、duration、ramp-down 的總和；target 數包含固定 target 及每個 VU mix entry。功能停用時回傳 `403 ATT-SERVER-INLINE-LOAD-DISABLED`。這些限制適用於瀏覽器建立的 draft；既有 path-based Load request 維持相容行為，並繼續受 `workers.maxConcurrentLoad` 限制。

```yaml
server:
  dataDir: /var/lib/att-server
  authenticationRequired: true
  jobRetentionDays: 30
  inspection:
    enabled: true
    maxConcurrent: 2
    queuedLimit: 16
    timeoutMs: 30000
    heapMaxMb: 512
    maxResponseBytes: 262144
    maxSourceBytes: 65536
    # 選填 Tool script source allowlist；路徑相對於 package。
    # safeTextSources:
    #   payments:
    #     - tools/payment-check.sh
  inlineLoad:
    enabled: false
    maxWorkloads: 10
    maxTargets: 20
    maxTotalUsers: 100
    maxAggregateArrivalRatePerSecond: 100
    maxConcurrentPerWorkload: 100
    maxDurationSeconds: 3600
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
| `maxAggregateArrivalRatePerSecond` | 100 | 大於 0 至 1,000,000 | 轉為每秒請求數後的 Arrival Rate 總和 |
| `maxConcurrentPerWorkload` | 100 | 1–1,000,000 | 每個 Arrival Rate workload |
| `maxTotalConcurrent` | 1,000 | 1–1,000,000 | 所有 Arrival Rate workload 的 `maxConcurrent` 總和 |
| `maxDurationSeconds` | 3,600 | 1–86,400 | warmup、ramp-up、duration 及 ramp-down 總和 |

`dataDir` 必須是絕對路徑。啟動時，allowed root、package root 及 Worker library directory 必須已存在。系統會將 package 路徑 canonicalize；若 symlink 指向 allowed root 以外，便會拒絕。Client 提交 `packageId`，不能指定 package 路徑、輸出路徑、Worker 執行檔或 classpath。v1 不提供 package 註冊或修改 API。

Server 狀態存放於 `dataDir/db/`。每項工作在 `dataDir/jobs/<jobId>/` 保存有上限的事件日誌及執行輸出。Server 重啟後會將舊的排隊或執行中工作標記為 `ERROR`，診斷碼為 `ATT-SERVER-INTERRUPTED`，不會重新執行。

## 驗證與身份

Tomcat 負責驗證請求。WAR 使用 Servlet container 配置的 authentication mechanism。v1 所有已驗證的 Servlet Principal 具有相同 API 權限，不要求 `ATT_USER` 角色，也沒有 ATT 專用 RBAC。Health 及 version 維持公開。對外提供 BASIC credentials 前，請先終止 TLS。驗證失敗時，Server 保留 container response，包括 BASIC challenge 或 FORM/SSO redirect。ATT Server 透過 `HttpServletRequest.getUserPrincipal()` 取得 Principal；package、資源檢視、Debug/Quick Load draft、job、result、event 及 artifact API 均要求 Principal。即使舊 API 啟用匿名模式，新的資源探索及 Debug/Quick Load draft endpoint 仍要求真實 Servlet Principal。Health 及 version 可匿名存取。Principal 名稱會記錄在 job 與 audit metadata 中，不會傳給 Worker，也不會放進 ATT expression Context。

改變狀態的請求必須使用 `application/json`；如請求帶有 `Origin`，必須與請求來源相同。若 TLS 在反向代理終止，請設定 Tomcat `RemoteIpValve`，由代理的 forwarded headers 還原 Servlet scheme、host 及 port。`internalProxies` 只可列出實際代理位址，並確保代理會先移除用戶提交的 `Forwarded`/`X-Forwarded-*` headers，再加入自身的值。請依代理實際使用的 header 名稱及受信任位址調整設定：

```xml
<Valve className="org.apache.catalina.valves.RemoteIpValve"
       internalProxies="10\\.20\\.0\\.12"
       remoteIpHeader="X-Forwarded-For"
       protocolHeader="X-Forwarded-Proto"
       hostHeader="X-Forwarded-Host"
       portHeader="X-Forwarded-Port" />
```

在刻意隔離的網絡中，可設定 `server.authenticationRequired: false` 接受匿名 API 請求；這會略過所有 API 操作的驗證，不應用於不受信任的網絡。ATT Server 不會實作密碼、JWT/OIDC 驗證、LDAP 驗證或登入流程。請勿在 API payload 或 package 檔案中放置憑證。

內建瀏覽器主控台位於 `<context path>/ui/`，使用與 API 相同的容器驗證機制。詳見 [ATT Server Web UI](reference.zh/att-server-web-ui.md)。

## REST API

所有 endpoint 位於 `/api/v1`。除 SSE stream 及 artifact 下載外，請求與回應均使用 JSON。

| 方法 | 路徑 | 用途 |
| --- | --- | --- |
| `GET` | `/health`、`/version` | 健康狀態、build 及瀏覽器 Load capability 資訊 |
| `GET` | `/metrics` | 有界的工作及 Worker 數量 |
| `GET` | `/packages`、`/packages/{packageId}` | 讀取設定中的 registry |
| `GET` | `/packages/{packageId}/resources?type=case&query=...&limit=50&cursor=...` | 列出安全的 Case、Template、Flow 及 Tool 投影 |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}` | 讀取單一資源定義及關聯 |
| `GET` | `/packages/{packageId}/resources/{kind}/{resourceId}/source` | 讀取已遮蔽的 YAML 投影或允許清單內的 Tool script |
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

使用穩定 package ID 及該 ATT Worker command 的欄位提交工作：

```http
POST /api/v1/jobs/run
Content-Type: application/json

{"packageId":"payments","all":true}
```

API 會回傳 `202 Accepted` 及 job ID。工作狀態依序為 `QUEUED`、`PREPARING`、`RUNNING`，最後為 `PASS`、`FAIL`、`ERROR`、`INVALID` 或 `CANCELLED`。容量已滿時會回傳 `429` 及 `ATT-SERVER-CAPACITY-EXCEEDED`。錯誤使用包含 `code`、`summary`、`detail` 及 `requestId` 的 `error` 物件；Server 不會回傳 stack trace。

啟用 `server.inlineLoad.enabled` 前，瀏覽器 Quick 及 Advanced Load endpoint 會回傳 `403 ATT-SERVER-INLINE-LOAD-DISABLED`。超出已啟用上限的 scenario 會回傳 `400 ATT-SERVER-INVALID-REQUEST`，且不會進入佇列。

工作記錄在有量度數據時會加入 `performance` 物件。`performance.timings` 使用 monotonic clock 記錄 admission、佇列等待、Worker 準備及啟動、Worker-ready（首個 `STATUS`）、execution-ready（首個 `PROGRESS` 或 `LOG`）、Worker 存活時間及結果至終止時間。`performance.worker` 記錄隔離 Worker 的 Heap、live thread、GC、process CPU 及抽樣 RSS 峰值。抽樣由事件觸發，每 100 ms 最多一次，可能漏掉短暫峰值。RSS 只在 Linux `/proc` 系統提供。Worker 資源數據會保存在標準工作記錄，Server 重啟後仍可讀取。

`GET /jobs` 回傳最近 100 項工作。系統不會保存提交 payload，只保存 command 及 package ID 摘要。Engine result 及診斷會作為控制平面 metadata 保存。較大的報告、log 及其他證據會留在工作輸出目錄。

資源探索可用 `case`、`template`、`flow` 或 `tool` 篩選。每頁預設 50 項，最多 100 項；續頁 cursor 會加密並綁定已驗證的 Principal 及查詢。若 package 內容改變，舊 cursor 會以 `409` 拒絕。Explorer 回傳邏輯資源 ID、安全投影、來源資訊、關聯及穩定診斷碼。YAML source 是解析及遮蔽後的投影，並非原始檔案內容。只有 Server 設定明確允許的 package 相對路徑才會提供 Tool script source。探索程序不會執行 Tool，也不提供檔案系統瀏覽器。即使 `server.authenticationRequired: false`，資源探索仍要求已驗證的 Servlet Principal；檢查佇列、Worker heap、逾時及 response 大小均與一般工作執行分開限制。

### 套件資源探索

這些公開唯讀 endpoint 均要求已驗證的 Servlet Principal。`kind` 可為 `case`、`template`、`flow` 或 `tool`；`resourceId` 是清單 endpoint 回傳的不透明套件範圍 ID。清單的 `query` 會比對邏輯 ID、名稱、描述及 tags。省略 `type`、`query` 或 `limit` 時會使用預設值。續頁 cursor 只適用於相同 package、Principal、type 及 query。

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
```

詳細回應包含 `resource`、`definition`、`diagnostics` 及 `requestId`。來源回應包含 `resource`、`available`、`format`、`text`、`redacted` 及 `requestId`。來源不可用時，`available` 為 `false`，`reason` 為 `source-unavailable` 或 `size-limit`，而且不會回傳部分內容。找不到目標的關聯會標示 `resolution: "unresolved"`，並附有穩定資源診斷碼。

無效清單參數會回傳 `400`；未知 package/resource 使用相同的 `404` 格式。過期 cursor 回傳 `409`，超大回應回傳 `413`，探索佇列已滿回傳 `503`，Inspector 逾時回傳 `504`。錯誤回應使用既有的 `error.code`、`error.summary` 及 `requestId` 格式，不會包含實體路徑或 parser exception 訊息。

### 目標範圍內的 Debug 與 draft

Debug form、Quick Load form、Load policy 及 draft endpoint 要求真實、已驗證的 Servlet Principal，即使舊 job API 啟用匿名存取亦一樣。請使用 Package Resource Explorer 回傳的資源 ID；Case 不能作為 Debug 或 Quick Load 目標。

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

回應包含不透明 `draftId`、安全 YAML 預覽、驗證診斷及過期時間。`GET /api/v1/drafts/{draftId}` 只會向擁有該 draft 的 Principal 回傳相同安全預覽。Draft 僅存於記憶體，最多 10 分鐘；每個 Server 最多 128 個有效 draft，每個 Principal 最多 16 個。Server 重啟會令 draft 失效。Server 不會回傳內部 package revision digest。

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

`load` 只接受所選 model 支援的 pacing 欄位；`execution` 可包含 closed-workload 的 `thinkTime`。可選的 `testdata` array 可指定額外 package-relative Load descriptor paths；這些路徑與 Debug-local imports 分開處理，並會附加於 model policy 既有 descriptors。回應會提供綁定 Principal 的不透明 `draftId`（以 `L` 開頭）、安全的 effective YAML 預覽、遮蔽狀態及過期時間。Debug 與 Quick Load drafts 共用每個 Server 最多 128 個、每位 Principal 最多 16 個、有效期 10 分鐘的記憶體限制。

檢視預覽後，只提交 draft identity：

```http
POST /api/v1/jobs/load
Content-Type: application/json

{"packageId":"payments","draftId":"L0123456789ABCDEF0123456789ABCDEF"}
```

Server 會在 draft 提交時、Worker 啟動前及 Worker 內重新檢查 package revision。若 draft 過期會回傳 `409 ATT-SERVER-DRAFT-STALE`；工作接納後才發現變更，則會標示為 `INVALID` 並附該診斷。Worker 會收到與預覽驗證相同的不可變 scenario；內容只留在記憶體，不會寫入 package。舊有 path-based Load request 仍受支援。

### Advanced Load builder

設定 `server.inlineLoad.enabled: true` 後，Advanced Load 才會建立包含一個或多個 workload 的完整 `att-load/v1.6` scenario。所有 workload 必須使用同一 `virtualUsers` 或 `arrivalRate` model。Virtual Users workload 可使用單一固定 target 或 weighted `mix`；每個 Arrival Rate workload 使用一個固定 target。Engine 會在 Server 建立 draft 前驗證共用時間窗口、workload intensity、thresholds、Testdata policy 及所有 target，Server 隨後會檢查管理員設定的 inline Load 上限。若超出任何上限，回傳 `400 ATT-SERVER-INVALID-REQUEST` 及安全摘要，且不會排程 scenario。

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

Server 會驗證完整 scenario 並 resolve 所有 target，不會排程流量。只會從所選 target package-local `debug.yaml` 的 business 欄位還原已遮蔽值；不會複製 Debug `case`、`stage` 或 local Testdata。回應包含以 `A` 開頭的不透明 draft ID、安全 effective YAML 預覽、model、遮蔽標記及過期時間。Advanced Load 與 Debug、Quick Load 共用每個 Server 最多 128 個、每位 Principal 最多 16 個 draft 及 10 分鐘有效期限制。

向 `POST /api/v1/jobs/load` 只提交 package 及 draft ID。Server 會在提交及 Worker 啟動前檢查 package revision，再透過既有 Worker、Engine、scheduler、event 及 evidence 流程傳遞相同的不可變 normalized scenario。若 package revision 已變更，會回傳 `409 ATT-SERVER-DRAFT-STALE`；工作接納後才發現變更，工作會標示為 `INVALID`。若預覽有任何遮蔽欄位，瀏覽器會停用 YAML 複製及匯出。舊有 path-based Load request 仍受支援。

### Package configuration inspection

設定檢查使用與 package resource 相同、受界限限制的 inspection Worker。以下唯讀 endpoint 均要求已驗證的 Servlet Principal；即使 `server.authenticationRequired: false` 亦一樣：

```http
GET /api/v1/packages/payments/configuration?view=declared
GET /api/v1/packages/payments/configuration/effective?environment=SIT
GET /api/v1/packages/payments/configuration/effective?environment=SIT&section=dbhelpers&offset=0&limit=50
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT
GET /api/v1/packages/payments/configuration/compare?left=SIT&right=UAT&offset=0&limit=50
```

Declared view 會回傳 schema version、安全的 global 欄位、已設定 profile，以及每個設定 section 的 declared/absent 狀態、項目數量和 profile 繼承或替換狀態。Effective view 使用 `FrameworkConfigLoader` 選擇 environment，並回傳安全的 helper、Tool 及 Testdata descriptor metadata。Root-only package 或使用已設定的預設值時可省略 `environment`。第一個 effective 回應會提供各 section 的數量；按 section ID（`dbhelpers`、`mqhelpers`、`sshhelpers`、`httphelpers`、`tools` 或 `testdata`）要求其項目。Effective section 及 comparison 欄位回應支援 `offset` 與 `limit`（1–100，預設 50），並回傳 `total` 和 `nextOffset` 以供分頁。檢查過程不會連接 DB、MQ、HTTP 或 SSH 服務，也不會回傳未限制的設定 YAML 或 Testdata records。

比較結果會列出可顯示的 effective 欄位及其來源。敏感欄位會以 `state: "hidden"` 和 `change: "hidden"` 回傳；回應不會洩露其值是否相同。系統不回傳絕對路徑、憑證、連線 endpoint、網絡拓撲或未限制的 descriptor 內容。

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

Server binary 會以獨立的 `att-4.1.0-server.tar.gz` 發佈，內含 `server/att-server-4.1.0.war` 及部署指南。Local CLI 位於 `att-4.1.0-local.tar.gz`，並包含其 runtime library。兩個 archive 分開後，local package 不會再重複附帶 WAR 內的 library。Tomcat 提供 HTTP listener 及 Servlet API。Server 需要 Java 17；CLI、Engine 及 Worker 維持 Java 8 目標。

### Issue #176 rollout checklist

1. 備份 Server `dataDir`，並保留目前 WAR 及部署設定以便回復。
2. 保持 Tomcat 驗證啟用，並以 HTTPS 接收公開流量。確認 package root 與 Server data directory 分離，且只有服務帳戶可寫入。
3. 將 Server WAR 以 exploded web application 部署，確保 Worker process 可使用 `WEB-INF/lib`。透過實際 Tomcat context path 檢查 `/api/v1/health` 及 `/api/v1/version`。
4. 初始維持 `server.inlineLoad.enabled: false`。先用非正式環境 package 檢視 Package 與 Configuration Explorer。只在核准的環境啟用瀏覽器 Load，並設定 workload 數、target、users 或 rate、並行數及完整時間窗口上限。
5. 監察 Worker 與 Load 佇列容量、工作結果、取消及保留的 artifacts。Package 維持唯讀；回復時停用 inline Load，必要時還原前一版 WAR 及設定。

此清單說明部署步驟，不能取代 P6 的安全性、瀏覽器、相容性、WAR 封裝及 hosted CI 驗收證據。
