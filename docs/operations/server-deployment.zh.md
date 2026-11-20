<!-- att-doc-status: current release=4.3.0 -->

# ATT Server 部署

## 準備部署

1. 將 Local archive 解壓至安裝根目錄，並將 `ATT_HOME` 設為該目錄。升級時，先移除舊的 `lib/att-local-*.jar` 再解壓新 archive，確保 `ATT_HOME/lib` 只包含一個 runtime。Engine/Worker 共用設定位於 `$ATT_HOME/config/config.yaml`，受信任 schemas 由 ATT runtime 內嵌載入。Package root `$ATT_HOME/packages/default` 只包含業務資源，例如 `testcase/`、`templates/`、`load/`、Testdata 及 package-local scripts，不需要 `config/` 或 `schemas/`。`att-server.yaml` 是獨立的 Server deployment 設定。
2. 將 package root 設於 allowed root 之下，並確保 Tomcat 服務帳戶可讀取。解壓後的 `$ATT_HOME/packages/default` 可直接註冊為 package root。
3. 啟動前先建立 `server.dataDir`，並只允許 Tomcat 服務帳戶寫入。範例使用 `$ATT_HOME/data`；只要它與所有已註冊 package root 不相等、互不包含，即可使用。亦可使用 `/var/lib/att-server`。Server data 不可放入 package 內容。
4. 將 `$ATT_HOME/config/att-server.example.yaml` 複製至 `$ATT_HOME/config/att-server.yaml` 或受保護的外部路徑，更新絕對路徑，並勿將憑證放入 package。升級時不可覆寫既有的 active config。
5. 將 `att-server-4.3.0.war` 部署至 Tomcat 10.1+。Tomcat 可直接部署 packed WAR；隔離的 Worker 會從 `$ATT_HOME/lib` 載入版本相符的 `att-local-4.3.0.jar`。

Local archive 解壓後的目錄就是共享安裝及部署根目錄 `ATT_HOME`。此目錄包含 launcher 及 `lib/att-local-4.3.0.jar` 中共用的 Worker/Engine runtime；升級或 rollback 時，必須同時部署配套的 Local archive 與 Server WAR。Startup 會驗證 Worker JAR 版本、build commit 及內嵌 Schema Registry；每個 Worker 處理請求前也會確認 Registry identity 與 Server 驗證過的 runtime 相符。每個 `ATT_HOME/lib` 只保留一個有版本號的 `att-local-<version>.jar`。不要混合升級 Server 與 Worker：Local runtime 的產品版本或 build commit 不相符時，Server 會拒絕啟動；舊 Worker 亦會拒絕不可變 source snapshot dispatch。可執行的 package 內容位於 `packages/<id>/`；Server state 位於另外設定的 `server.dataDir`。Archive 只包含 `config/att-server.example.yaml` 範本，不會包含 active 的 `config/att-server.yaml` 或 `data/` 目錄。

Source-pinned Job 需要 OS 強制的 Worker 邊界：Linux deployment 必須安裝可使用 user/PID namespace 的 `bwrap`（bubblewrap）；macOS 使用 `/usr/bin/sandbox-exec`。Server 在啟動後第一次接納 source-pinned Job 前，會針對已接受的 Package 及 Config snapshot 執行有界 sandbox probe。若無法建立隔離，request 會回傳 `409 ATT-SOURCE-ISOLATION-UNAVAILABLE`，不會排入可能讀取可變 source 的工作。其他作業系統不支援 source-pinned Worker execution。

從 Worker source-snapshot v1 升級後，缺少 executable metadata 的歷史 Job lease 會標記為 **UNAVAILABLE**，不可 dispatch。Job record 及不含值的 provenance 仍可讀取。若要再次執行，請先針對目前 Package review 及驗證，再確認 rerun；Server 會建立有 v2 source snapshot 的新 Job，不會從目前 Package 推測舊 bytes，也不會改寫歷史 Job。

Server 依次尋找 JVM property `-Datt.server.config`、環境變數 `ATT_SERVER_CONFIG`，再尋找 `$ATT_HOME/config/att-server.yaml`。最後一個路徑的 `ATT_HOME` 先取自 `-Datt.home`，再取自 `ATT_HOME` 環境變數。若選定的 config 不存在、不可讀或無效，Server 會在診斷中指出其路徑並停止啟動，不會改用其他來源。沒有明確來源或可用的預設 config 時，啟動錯誤會提供設定指引。

Tomcat 負責 listener、TLS、access log 及驗證。請讓服務帳戶可讀取 ATT_HOME 和 package root，並擁有或可寫入 `server.dataDir`。請設定 Realm、SSO 整合、用戶端憑證或其他容器支援的機制；驗證失敗時，Server 會保留容器回傳的 challenge 或 redirect。啟用排程後，所有通過 `server.authorization.userRole` 驗證的 USER 均可管理共用排程；ADMIN role 不會授予 Package、Job 或 Schedule 操作權限。若設定、Java 基線或 package mapping 無效，Server 會在啟動時失敗。

## ATT_HOME 佈局與遷移

共享安裝可採用以下佈局：

```text
att/                              # ATT_HOME
  att.sh
  att.bat
  lib/
  config/
    att-server.example.yaml       # 僅為 Server 設定範本
    config.yaml                   # 共用 Engine/Worker 設定
    tools/                        # 共用 Tool 定義
    dbhelpers/                    # 共用 helper descriptors
  data/                           # 選用的 server.dataDir，由 Tomcat 管理
  packages/default/               # 完整 package root
    testcase/
    templates/
    testdata/                     # package-local descriptor/data
    tools/                        # package-local scripts
```

遷移舊版平面 Local 安裝前先備份，並將新版 archive 解壓到暫存目錄。保留一份經管理員審核的共用 `config/config.yaml`，供 runtime 設定及共用 Helpers 使用；將業務 Tool descriptors 移入各自已註冊的 Package，放在 `packages/<id>/tools/`。若舊安裝的不同 package 使用不同設定，請先人工合併；ATT runtime 不會選擇或合併 package 內的設定。請勿將舊 schema 複製至 ATT_HOME 或 Package：runtime validation 只使用版本相符的 ATT Engine JAR 內嵌 registry，既有 `ATT_HOME/schemas/` 目錄會被忽略。將 `testcase/`、`templates/`、`load/`、頂層 `tools/`、`sql/` 及 Testdata descriptors 放到各自已註冊的 Package，並保留 `packages/<id>/tools/` 內的 Package scripts。遷移 runtime 設定時排除 `att-server.example.yaml` 及 `att-server.yaml`。既有 active Server config 應留在 `ATT_HOME/config/att-server.yaml` 或受保護的外部路徑。在新 Package 通過驗證及執行前，保留舊的平面目錄。將 Server registry entries 更新至 Package root，並為 Tomcat 設定 `ATT_HOME` 或 `-Datt.home`。Server state 必須留在 Package root 以外；Local archive 不包含 `data/` 或 active Server config。

## 發行與相容性

Server binary 會以獨立的 `att-4.3.0-server.tar.gz` 發佈，內含 `server/att-server-4.3.0.war`（ATT application code 已合併至 `WEB-INF/lib/att-server-4.3.0.jar`）及部署指南。Local CLI 位於 `att-4.3.0-local.tar.gz`，其 `lib/att-local-4.3.0.jar` 提供共用 Worker/Engine runtime；Server 會載入相同版本的 library。Tomcat 提供 HTTP listener 及 Servlet API。Server 需要 Java 17；CLI、Engine 及 Worker 維持 Java 8 目標。
