<!-- att-doc-status: current release=4.2.5 -->

# RUN 與 LOAD API

## 目標範圍內的 Debug 與 draft

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

## 目標範圍內的 Quick Load

設定 `server.inlineLoad.enabled: true` 後，Quick Load 才可從已選取的 Template、Flow 或 Tool 開啟；它固定使用該目標並組成一個 Workload。Model 可選 `virtualUsers` 或 `arrivalRate`；Server 會組成相符的現行 `att-load/v1.9` policy，並使用現有 Load pipeline 驗證目標。功能停用時，Quick Load form、policy 及 draft endpoint 都會回傳 `403 ATT-SERVER-INLINE-LOAD-DISABLED`。

```http
GET /api/v1/packages/payments/resources/flow/{resourceId}/quick-load-form?model=virtualUsers&environment=SIT
```

表單回應會為 Template/Flow 提供安全的 `inputs`、`vars` 預設值，或為 Tool 提供 `inputs`、`arguments`，並附上已遮蔽的單 workload 預覽。它只讀取目標選填 `debug.yaml` 內的 business 欄位；不會複製 Debug 專用的 `case`、`stage` 及 local `testdata`。若 sidecar 包含 Debug-local Testdata，表單會明確提示這些 imports 已略過。需要的 package-relative descriptor 可另外放入 Load-level `testdata` array，並會按 effective Load scenario 驗證。

Server 會為 `virtualUsers` 讀取 `load/load.visualuser.yaml`，為 `arrivalRate` 讀取 `load/load.arrivalrate.yaml`。這些是 policy-only 的 `att-load/v1.9` descriptor，必須與所選 model 相符。若 model 專用檔案不存在，會使用內建低強度 fallback：一個 Virtual User 執行 10 秒，或每秒 1 次 arrival、執行 10 秒，並設定 `maxConcurrent: 1` 及 `overloadPolicy: drop`。既有 CLI `load/load.yaml` 行為不變。

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

## Advanced Load builder

設定 `server.inlineLoad.enabled: true` 後，Advanced Load 才會建立包含一個或多個 workload 的完整 `att-load/v1.9` scenario。所有 workload 必須使用同一 `virtualUsers` 或 `arrivalRate` model。Virtual Users workload 可使用單一固定 target 或 weighted `mix`；每個 Arrival Rate workload 使用一個固定 target。Engine 會在 Server 建立 draft 前驗證共用時間窗口、workload intensity、thresholds、Testdata policy 及所有 target，Server 隨後會檢查管理員設定的 inline Load 上限。若超出任何上限，回傳 `400 ATT-SERVER-INLINE-LOAD-LIMIT` 及安全摘要，且不會排程 scenario。

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
    "schemaVersion": "att-load/v1.9",
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
