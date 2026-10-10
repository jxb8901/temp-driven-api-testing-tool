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

`server.dataDir` 儲存 H2 控制平面資料及工作輸出。`workers` 限制 Worker 並行數、佇列大小、Load admission、優雅停止逾時及可選的每個 Worker Heap 上限。`workers.heapMaxMb` 為每個 Worker 設定 `-Xmx`（64–65536 MiB）；選填 `heapInitialMb` 設定 `-Xms`（32–65536 MiB），必須同時設定 `heapMaxMb`，並且不可超過上限。請按 `maxConcurrent`、Server 及 container 的記憶體預算設定總 Heap 上限。被拒絕的提交會在建立持久工作記錄前清理。已完成工作的 metadata、journal 及 artifacts 會保留 `server.jobRetentionDays` 天（預設 30，範圍 1–3650）；啟動時及每小時清理過期工作，並保留執行中的工作。`workers.maxConcurrentLoad` 限制已接納的 Load 工作總數，包括佇列中及執行中的工作，避免等待中的 Load 佔用一般 Worker thread。Load 或整體容量超出上限時會回傳 HTTP 429。`workers.libraryDirs` 可選擇列出絕對、已存在且可讀的目錄，Worker subprocess 會將其中 JAR 加入 classpath，以載入額外 JDBC、MQ 或其他 dependency；只可設定由 Server 管理員信任的目錄。唯讀 `packages` registry 將穩定 package ID 對應到 `allowedRoots` 下的 canonical root。

```yaml
server:
  dataDir: /var/lib/att-server
  authenticationRequired: true
  jobRetentionDays: 30
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

`dataDir` 必須是絕對路徑。啟動時，allowed root、package root 及 Worker library directory 必須已存在。系統會將 package 路徑 canonicalize；若 symlink 指向 allowed root 以外，便會拒絕。Client 提交 `packageId`，不能指定 package 路徑、輸出路徑、Worker 執行檔或 classpath。v1 不提供 package 註冊或修改 API。

Server 狀態存放於 `dataDir/db/`。每項工作在 `dataDir/jobs/<jobId>/` 保存有上限的事件日誌及執行輸出。Server 重啟後會將舊的排隊或執行中工作標記為 `ERROR`，診斷碼為 `ATT-SERVER-INTERRUPTED`，不會重新執行。

## 驗證與身份

Tomcat 負責驗證請求。WAR 使用 Servlet container 配置的 authentication mechanism。v1 所有已驗證的 Servlet Principal 具有相同 API 權限，不要求 `ATT_USER` 角色，也沒有 ATT 專用 RBAC。Health 及 version 維持公開。對外提供 BASIC credentials 前，請先終止 TLS。驗證失敗時，Server 保留 container response，包括 BASIC challenge 或 FORM/SSO redirect。ATT Server 透過 `HttpServletRequest.getUserPrincipal()` 取得 Principal；package、job、result、event 及 artifact API 均要求 Principal。Health 及 version 可匿名存取。Principal 名稱會記錄在 job 與 audit metadata 中，不會傳給 Worker，也不會放進 ATT expression Context。

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
| `GET` | `/health`、`/version` | 健康狀態及 build 資訊 |
| `GET` | `/metrics` | 有界的工作及 Worker 數量 |
| `GET` | `/packages`、`/packages/{packageId}` | 讀取設定中的 registry |
| `POST` | `/jobs/run`、`/jobs/debug`、`/jobs/load`、`/jobs/validate` | 提交一項工作 |
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

工作記錄在有量度數據時會加入 `performance` 物件。`performance.timings` 使用 monotonic clock 記錄 admission、佇列等待、Worker 準備及啟動、Worker-ready（首個 `STATUS`）、execution-ready（首個 `PROGRESS` 或 `LOG`）、Worker 存活時間及結果至終止時間。`performance.worker` 記錄隔離 Worker 的 Heap、live thread、GC、process CPU 及抽樣 RSS 峰值。抽樣由事件觸發，每 100 ms 最多一次，可能漏掉短暫峰值。RSS 只在 Linux `/proc` 系統提供。Worker 資源數據會保存在標準工作記錄，Server 重啟後仍可讀取。

`GET /jobs` 回傳最近 100 項工作。系統不會保存提交 payload，只保存 command 及 package ID 摘要。Engine result 及診斷會作為控制平面 metadata 保存。較大的報告、log 及其他證據會留在工作輸出目錄。

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
