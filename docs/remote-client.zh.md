# ATT Remote 用戶端

<!-- att-doc-status: legacy-current release=4.2.5 -->


ATT Remote 可透過現有 `att.sh` 或 `att.bat` 使用 ATT Server。它會呼叫 Server 公開的 REST API、透過 SSE 追蹤工作事件，並以 ATT exit code 回傳 canonical result。Local command 仍在本機執行。

## 設定 Server profile

建立 `~/.att/servers.yaml`，設定 Server URL 及可選的 Basic Auth。請勿將 password 寫入此檔案。

```yaml
default: sit

servers:
  sit:
    url: https://att-sit.example.com
    auth:
      type: basic
      username: att-user
      passwordEnv: ATT_SIT_PASSWORD

  ci:
    url: https://att-ci.example.com
    auth:
      type: basic
      username: att-ci
      passwordEnv: ATT_CI_PASSWORD
```

請由 CI secret store 注入指定的環境變數。互動使用時，可省略 `passwordEnv`；Server 要求 Basic authentication 後，CLI 會隱藏輸入 credentials。設定 `ATT_REMOTE_CONFIG` 可使用其他 profile 檔案。`ATT_SERVER_CONFIG` 保留給 Server deployment YAML 使用。

`--server` 可選擇 profile 或直接接受 HTTP(S) base URL。`ATT_SERVER` 同樣接受 profile 名稱或 URL。選擇優先次序為明確指定的 `--server`、`ATT_SERVER`、YAML `default` profile。直接選擇 URL 時不會沿用其他 profile 的 credentials，並會保留 context path。URL validation 會拒絕 embedded credentials、query、fragment 及不支援的 scheme。

Profile 預設使用 `auth.type: auto`。Run、Debug、Load、Validate 或 cancel 等改變狀態的命令執行前，ATT Remote 會呼叫受保護且唯讀的 `GET /packages`，先取得 credentials 再提交狀態變更請求。唯讀命令會在自身的 `GET` 遇到 Basic challenge 時處理；`ping` 和 `version` 不依賴 package list 存取。此 probe 假設 package list 與狀態變更 API 使用相同的驗證邊界。若 `/packages` 公開但狀態變更操作仍要求驗證，請為該 profile 設定 `auth.type: basic`。若匿名存取成功，ATT Remote 不會傳送 credentials。若受保護的 `GET` 回覆 HTTP Basic challenge，ATT Remote 會使用所選 profile 的 credentials，或安全地提示輸入。使用 `auth.type: basic` 會預先傳送 profile credentials；使用 `auth.type: none` 或 `--no-auth` 則不傳送 credentials。Basic authentication（包括直接 URL 輸入的 credentials）必須使用 HTTPS，並維持 JVM 的 TLS certificate 及 hostname 檢查。

Auto mode 可在之後受保護的 `GET` endpoint 遇到 Basic challenge 時處理並沿用 credentials。遇到 challenge 後不會重試 `POST` 或 `DELETE`；submission 被拒絕後，請先檢查 job list，再決定是否重試。ATT Remote 不會跟隨 redirect，避免將 credentials 傳給 redirect target 或降級至 HTTP。FORM/OIDC challenge 不受支援；請使用 Tomcat BASIC-protected endpoint。Server 必須宣告 API version `1`。

匿名模式只適用於隔離的 SIT/development 部署。所有 caller 共用相同授權身份，亦可存取相同 jobs 及 drafts；只有部署方明確啟用並保護網絡邊界時才使用 `--no-auth`。Run、Debug、Load 或 Validate 可加上 `--principal <name>` 提交自我聲稱的顯示/audit label。Server 會將其存入既有 `principal` 欄位並格式化為 `<name>(anonym)`；不會產生個別用戶存取或授權。驗證模式會忽略此 option。

## 發現 package

使用 Server 設定的 package ID。Client 不會要求或顯示 package root 路徑。

```sh
./att.sh remote --server sit ping
./att.sh remote --server sit version
./att.sh remote --server sit packages
./att.sh remote --server sit package payments
./att.sh remote --server sit --no-auth ping
./att.sh remote --server sit --no-auth packages
./att.sh remote --server https://att-sit.example.com/att packages
ATT_SERVER=sit ./att.sh remote packages
```

## 提交 Run、Debug、Load 及 Validate 工作

先提供 Server 的邏輯 package ID。常用 ATT selection 及 environment option 會映射至 Server job 欄位。

```sh
./att.sh remote --server sit run payments --suite testcase/payment.xlsx --tag smoke --env SIT
./att.sh remote --server sit debug payments flow PAYMENT.submit --set vars.Channel=WEB
./att.sh remote --server sit --no-auth run payments --all --principal Bob
./att.sh remote --server sit load payments load/payment.yaml --users 2 --duration 30s
./att.sh remote --server sit validate payments --package
./att.sh remote --server sit jobs --principal 'Bob(anonym)'
```

`--suite`、`--suite-dir`、`--config`、`--input` 及 Load scenario 等路徑，均指 Server package 內的邏輯相對路徑。Client 會拒絕絕對路徑及 parent-directory traversal。請求只包含 `packageId`，不會包含 `PACKAGE_ROOT`、`SERVER_DATA_DIR`、Worker 路徑或 client 輸出目錄。

命令預設會等待終端結果。Client 只提交一次工作，接收排序後的 status/progress/log/diagnostic event，再取得 result 及退出。它不會在本機調用 `att-engine`。`jobs --principal <text>` 會以不區分大小寫的子字串搜尋既有 principal 欄位，包括 Server 格式化後的匿名 label；搜尋 `alice` 亦會匹配 `alice(anonym)`，並可搜尋歷史 `anonymous` 紀錄。

使用 `--detach` 回傳 job ID，不會保留背景 process：

```sh
./att.sh remote --server ci run payments --all --detach
```

## 管理工作

使用回傳的 job ID 檢視、追蹤、取得結果、取消或下載證據。

```sh
./att.sh remote --server sit jobs
./att.sh remote --server sit job J0123456789ABCDEF
./att.sh remote --server sit watch J0123456789ABCDEF
./att.sh remote --server sit result J0123456789ABCDEF --format json
./att.sh remote --server sit cancel J0123456789ABCDEF
./att.sh remote --server sit artifacts J0123456789ABCDEF
./att.sh remote --server sit artifacts J0123456789ABCDEF --download ./results
./att.sh remote --server sit artifact J0123456789ABCDEF report/index.html --download ./results
```

Artifact 名稱來自 Server 的邏輯 artifact list。下載會保留原始 bytes，只能寫入指定目錄之下；若 parent 是 symlink 或目的檔已存在，操作會失敗。

## JSON output 及 exit code

加入 `--format json` 可為 listing、job result 及 detached submission 輸出 structured JSON。Human mode 的進度會顯示於 terminal；JSON mode 只輸出 final structured object，不會混入進度文字。

| Exit code | 含義 |
|---:|---|
| `0` | PASS 或操作成功 |
| `1` | ATT job 完成但結果為 FAIL |
| `2` | Request、package 或 validation 結果為 INVALID |
| `3` | ATT 執行 ERROR 或 cancellation result |
| `4` | 取得可信結果前發生 connection、authentication 或 protocol failure |

Exit code `4` 表示 Client 未能取得可信 ATT result，不代表已被 Server 接受的工作已取消。

## SSE 重新連線

Attached command 及 `watch` 會記錄最後處理的 event ID。暫時斷線後，Client 會帶上 `Last-Event-ID` 重新連線；Server 會重播該 ID 之後仍保留的 event，Client 會去除重複 ID。重新嘗試次數有上限。Client 斷線不會取消 Server job，也不會重新提交工作。

## 排查連線問題

- 遇到 TLS error 時，請將正確的 CA chain 安裝至 CLI 使用的 Java trust store。Client 會驗證 certificate 及 hostname。
- 若收到 HTTP 401 或 403，請檢查 Server profile username 及 CI secret 環境變數。Password 不會出現在 command-line argument 或 log。
- 若 API 相容性檢查失敗，請部署宣告 `/api/v1`、`apiVersion: "1"` 的 Server。
- Attached run 回傳 exit code `4` 時，請先用 `att remote job <jobId>` 檢查工作，再決定是否重新提交。

## 模組邊界

`att-cli` 會在解析 local CLI option 前路由 `att remote`。`att-remote` 負責 HTTP、Basic authentication、profile、SSE、rendering 及安全 artifact 下載，只依賴 `att-server-api` 和 client library。`att-server-api` 包含 Server 與 Client 共用的 Java 8 wire contract，不含 Servlet、persistence、Worker 或 Engine implementation。Local execution 經由 `att-engine`；Remote execution 一律使用 Server REST/SSE API。
