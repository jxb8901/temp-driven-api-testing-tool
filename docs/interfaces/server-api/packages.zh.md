<!-- att-doc-status: current release=4.2.6 -->

# Package 檢視 API

## 套件資源探索

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

## Package configuration inspection

設定檢查使用與 package resource 相同、受界限限制的 inspection Worker。以下唯讀 endpoint 使用 Server 解析出的已驗證或匿名身份：

```http
GET /api/v1/configuration/declared
GET /api/v1/configuration/effective?environment=SIT
GET /api/v1/configuration/compare?left=SIT&right=UAT&section=dbhelpers
GET /api/v1/configuration/files?path=config&limit=100
GET /api/v1/configuration/source?path=config/config.yaml
GET /api/v1/packages/payments/configuration?view=declared
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

Declared view 會回傳 schema version、安全的 global 欄位、已設定 profile，以及每個設定 section 的 declared/absent 狀態、項目數量和 profile 繼承或替換狀態，不會回傳 helper 欄位值。Effective view 使用 `FrameworkConfigLoader` 選擇 environment，並回傳安全的 helper、Tool 及 Testdata descriptor metadata。Root-only package 或使用已設定的預設值時可省略 `environment`。第一個 effective 回應會提供各 section 的數量；按 section ID（`dbhelpers`、`mqhelpers`、`sshhelpers`、`httphelpers`、`tools` 或 `testdata`）要求其項目。Effective section 及 comparison 欄位回應支援 `offset` 與 `limit`（1–100，預設 50），並回傳 `total` 和 `nextOffset` 以供分頁。比較要求必須提供 section ID；Server 會先驗證並篩選兩個 profile，再分頁。檢查過程不會連接 DB、MQ、HTTP 或 SSH 服務，也不會回傳未限制的設定 YAML。只有所選 environment 已設定的 Testdata descriptor 可透過上述 records endpoint 分頁預覽；一般值預設可見，憑證、認證資料及常見個人資料欄位始終遮蔽。啟用 `redaction` 後亦會套用設定的敏感路徑和欄位 pattern。未知或未設定的 descriptor 回傳 `404`。

Compare 預設比較 profile-sourced values；`includeInherited=true` 會一併包括 inherited/global values 及其來源。Section filter 會在分頁前套用。比較結果會列出可顯示的 effective 欄位及其來源。敏感欄位會以 `state: "hidden"` 和 `change: "hidden"` 回傳；回應不會洩露其值是否相同。系統不回傳絕對本機路徑、憑證、Server 自身的秘密或未限制的 descriptor 內容。關閉 masking 時，非憑證 URL 和 MQ host／port 可在 Effective／Compare 顯示；HTTP header maps 及 SSH host／user／port／identity-file 在各 view 始終受保護。啟用 masking 後，設定的操作欄位及路徑亦會遮蔽。

`includeAllFields=true` 只適用於 effective 或 compare view，並加入其他非秘密 DB properties、evidence SQL／parameters 及平常會遮蔽的 DB 運作設定。Caller 必須具備 `server.inspection.configurationAdminRole`，local-auth mode 則必須是本地 `ADMIN`。回應不會快取並會寫入 audit log。Username、password、token、個人資料、HTTP header maps、MQ evidence payload、SSH 連線資料及 identity-file 路徑、已分類欄位及設定的敏感值仍會遮蔽。Source 保持安全淨化；Declared 只提供宣告狀態和數量。

`source` endpoint 在 `safe-package-files` policy 下可依 origin 解析合資格的 package-local 文字參照；`allowlist-only` 則要求既有 `server.inspection.safeTextSources` 精確 package-relative 路徑。連結請求必須包含已授權 parent source 回傳的 Server 不透明 `referenceId`；caller 自行提供 path 和 origin 並不足以授權。External scripts 只會透過設定 external root 下每 package 的 canonical 精確 allowlist 提供，且不會回傳其絕對路徑。這仍是有界 source endpoint，並非任意檔案瀏覽器。Testdata records endpoint 需要 effective descriptor ID。Literal records 使用 `limit` 1–100 分頁（UI 每次讀取 50 筆）。Generated descriptor 預設回傳 `state: "preview-required"` 且不含 records；只有明確設定 `previewGenerated=true` 才會回傳有界 generated preview。此 endpoint 使用相同身份、redaction policy、bounded inspection worker、source/response 大小、佇列和逾時限制。

設定回應包含 `view`、`state`、可用時的 `schemaVersion` 及 `diagnostics`。分頁回應亦包含 `section`、`offset`、`limit`、`total` 及 `nextOffset`。無效設定會以 HTTP 200 回傳 `state: "invalid"` 及穩定診斷碼；格式錯誤的 profile 參數回傳 `400`。未知 package 回傳 `404`，超大回應回傳 `413`，檢查佇列已滿回傳 `503`，Inspector 逾時回傳 `504`。回應大小、佇列和逾時限制與資源探索相同。
