# ATT Remote 用戶端

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

請由 CI secret store 注入指定的環境變數。互動使用時，可省略 `passwordEnv`；CLI 會隱藏 password 輸入。設定 `ATT_REMOTE_CONFIG` 可使用其他 profile 檔案。`ATT_SERVER_CONFIG` 保留給 Server deployment YAML 使用。

使用 `--server` 選擇 profile。`ATT_SERVER` 可覆蓋 default profile URL。明確的 `--server` profile 優先；否則依序採用 `ATT_SERVER` URL，再採用設定檔的 default profile。若 `ATT_SERVER` 覆蓋 URL，authentication settings 仍取自 profile。

若 Server 明確允許匿名存取，可使用 `--no-auth` 略過 profile credentials 及 password prompt。Basic authentication 只可透過 HTTPS 使用。Client 採用 JVM 預設 TLS trust 及 hostname verification，沒有 insecure TLS 選項。每項操作開始前均會檢查 health/version；Server 必須宣告 API version `1`。

## 發現 package

使用 Server 設定的 package ID。Client 不會要求或顯示 package root 路徑。

```sh
./att.sh remote --server sit ping
./att.sh remote --server sit version
./att.sh remote --server sit packages
./att.sh remote --server sit package payments
```

## 提交 Run、Debug、Load 及 Validate 工作

先提供 Server 的邏輯 package ID。常用 ATT selection 及 environment option 會映射至 Server job 欄位。

```sh
./att.sh remote --server sit run payments --suite testcase/payment.xlsx --tag smoke --env SIT
./att.sh remote --server sit debug payments flow PAYMENT.submit --set vars.Channel=WEB
./att.sh remote --server sit load payments load/payment.yaml --users 2 --duration 30s
./att.sh remote --server sit validate payments --package
```

`--suite`、`--suite-dir`、`--config`、`--input` 及 Load scenario 等路徑，均指 Server package 內的邏輯相對路徑。Client 會拒絕絕對路徑及 parent-directory traversal。請求只包含 `packageId`，不會包含 `PACKAGE_ROOT`、`SERVER_DATA_DIR`、Worker 路徑或 client 輸出目錄。

命令預設會等待終端結果。Client 只提交一次工作，接收排序後的 status/progress/log/diagnostic event，再取得 result 及退出。它不會在本機調用 `att-engine`。

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
