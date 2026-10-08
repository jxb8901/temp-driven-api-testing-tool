# ATT Server 部署與 API

ATT Server 4.0.0 提供單節點控制平面，讓用戶透過版本化 REST API 提交 ATT Run、Debug、Load 及 Validate 工作。請將 WAR 部署至使用 Java 17 或更新版本的外部 Tomcat 10.1+。CLI、Engine 及 Worker 仍相容 Java 8。

## 準備部署

1. 建立由 Server 管理的資料目錄，例如 `/var/lib/att-server`，只允許 Tomcat 服務帳戶寫入。此目錄須與 ATT 安裝檔案及 package root 分開。
2. 在允許的根目錄下建立 package 目錄，並確保 Tomcat 服務帳戶可讀取。
3. 將 `config/att-server.example.yaml` 複製到受保護的位置，更新絕對路徑，並勿將憑證放進設定檔或 package 內容。
4. 在 Tomcat Java 選項設定 `-Datt.server.config=/etc/att/server.yaml`。若沒有系統屬性，亦可使用 `ATT_SERVER_CONFIG` 環境變數。
5. 將 `att-server-4.0.0.war` 部署至 Tomcat 10.1+。請保持 Tomcat 的 `unpackWARs` 啟用，讓 Server 可從 `WEB-INF/lib` 啟動 Worker。

Tomcat 負責監聽器、TLS、存取記錄及驗證。請設定 Realm、SSO 整合、用戶端憑證或其他容器支援的機制。若設定、Java 基線或 package mapping 無效，Server 會在啟動時失敗。

## 設定

`server.dataDir` 儲存 H2 控制平面資料及工作輸出。`workers` 限制 Worker 並行數、佇列大小、Load 並行數及優雅停止逾時。唯讀 `packages` registry 將穩定 package ID 對應到 `allowedRoots` 下的 canonical root。

```yaml
server:
  dataDir: /var/lib/att-server
  authenticationRequired: true
workers:
  maxConcurrent: 8
  queuedLimit: 100
  maxConcurrentLoad: 2
  gracefulStopMs: 10000
packages:
  allowedRoots:
    - /srv/att/packages
  entries:
    payments: /srv/att/packages/payments
```

`dataDir` 必須是絕對路徑。啟動時，allowed root 及 package root 必須已存在。系統會將 package 路徑 canonicalize；若 symlink 指向 allowed root 以外，便會拒絕。Client 提交 `packageId`，不能指定 package 路徑、輸出路徑、Worker 執行檔或 classpath。v1 不提供 package 註冊或修改 API。

Server 狀態存放於 `dataDir/db/`。每項工作在 `dataDir/jobs/<jobId>/` 保存有上限的事件日誌及執行輸出。Server 重啟後會將舊的排隊或執行中工作標記為 `ERROR`，診斷碼為 `ATT-SERVER-INTERRUPTED`，不會重新執行。

## 驗證與身份

Tomcat 負責驗證請求。WAR 會為 package、job API 及 metrics 設定 HTTP BASIC authentication，並要求容器角色 `ATT_USER`；請在 Tomcat Realm 將指定用戶加入此角色。Health 及 version 維持公開。對外提供 BASIC credentials 前，請先終止 TLS。ATT Server 透過 `HttpServletRequest.getUserPrincipal()` 取得 Principal；package、job、result、event 及 artifact API 均要求 Principal。Health 及 version 可匿名存取。Principal 名稱會記錄在 job 與 audit metadata 中，不會傳給 Worker，也不會放進 ATT expression Context。

改變狀態的請求必須使用 `application/json`；如請求帶有 `Origin`，必須與請求來源相同。若 TLS 在反向代理終止，請設定 Tomcat `RemoteIpValve`，由代理的 forwarded headers 還原 Servlet scheme、host 及 port。`internalProxies` 只可列出實際代理位址，並確保代理會先移除用戶提交的 `Forwarded`/`X-Forwarded-*` headers，再加入自身的值。請依代理實際使用的 header 名稱及受信任位址調整設定：

```xml
<Valve className="org.apache.catalina.valves.RemoteIpValve"
       internalProxies="10\\.20\\.0\\.12"
       remoteIpHeader="X-Forwarded-For"
       protocolHeader="X-Forwarded-Proto"
       hostHeader="X-Forwarded-Host"
       portHeader="X-Forwarded-Port" />
```

v1 所有 `ATT_USER` 已驗證 Principal 具有相同權限.在刻意隔離的網絡中，可設定 `server.authenticationRequired: false` 接受匿名 API 請求；這會略過所有 API 操作的驗證，不應用於不受信任的網絡。ATT Server 不會實作密碼、JWT/OIDC 驗證、LDAP 驗證、登入流程或 ATT 專用 RBAC。請勿在 API payload 或 package 檔案中放置憑證。

內建瀏覽器主控台位於 `<context path>/ui/`，使用與 API 相同的 Tomcat HTTP Basic 驗證及 `ATT_USER` 權限。詳見 [ATT Server Web UI](reference.zh/att-server-web-ui.md)。

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

`GET /jobs` 回傳最近 100 項工作。系統不會保存提交 payload，只保存 command 及 package ID 摘要。Engine result 及診斷會作為控制平面 metadata 保存。較大的報告、log 及其他證據會留在工作輸出目錄。

## Server-sent events

使用 `Accept: text/event-stream` 連接 `/api/v1/jobs/{jobId}/events`。事件保存在 `dataDir/jobs/<jobId>/events.jsonl`，每項工作使用遞增數字 ID，事件名稱為 `status`、`progress`、`log`、`diagnostic` 或 `result`。

```text
id: 2
event: status
data: {"jobId":"J...","status":"RUNNING"}

```

重新連線時傳送 `Last-Event-ID`，系統會重播 ID 較大的保留事件。較舊事件若已超出日誌保留上限，stream 會從仍保留的事件繼續。閒置時會傳送 `: keepalive` 註解，送出終端結果後會關閉。觀察端斷線不會取消工作。每個觀察端使用獨立的單一項目通知佇列，stream executor 亦設有上限。Worker 事件讀取器不會同步等待用戶端寫入。

## 取消與復原

`DELETE /jobs/{jobId}` 會要求 Worker 優雅終止，等待最多 `gracefulStopMs`，必要時只強制終止該 Worker。工作記錄會保留。Server 重啟後不會自動重跑尚未完成的工作。

Artifact 路徑必須相對於工作輸出目錄。絕對路徑、目錄 traversal 及 symlink escape 均會遭拒。Server 不提供一般檔案系統瀏覽或 package 修改 API。

## 發行與相容性

4.0.0 binary release 在 `server/att-server-4.0.0.war` 提供 WAR；現有 CLI 仍可透過 `att.sh` 及 `att.bat` 使用。Tomcat 提供 HTTP listener 及 Servlet API。Server 需要 Java 17；CLI、Engine 及 Worker 維持 Java 8 目標。
