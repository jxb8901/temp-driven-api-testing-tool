<!-- att-doc-status: current release=4.3.0 -->

# Package 檢視 API

## 可信任的 registry 與 source scope

Server 只會從選定、由部署管理的 `att-server.yaml` 之 `packages.entries` 載入 Package 根目錄。Tomcat 持續服務時，Server 會監看原地編輯及原子替換；候選 mapping 必須整體通過 canonical 既存目錄及 `packages.allowedRoots` 驗證。無效或不穩定的候選設定會保留最後已知良好的 registry。`packages` 以外的設定變更會標示為需要重新啟動，不會部分套用。未註冊的目錄不會成為 Package。

```http
GET /api/v1/registry
GET /api/v1/packages/payments/scope
```

`GET /registry` 回傳 `att-package-registry/v1`：content-addressable `revision`、單調遞增的 `generation`、reload 時間、`Ready` 或 `Invalid` 狀態、安全診斷，以及明確註冊的邏輯 Package ID 和 `Registered`、`Warming` 或 `Ready` 狀態。回應不會公開檔案系統路徑。`GET /packages/{packageId}/scope` 回傳 `att-source-scope/v1`，並區分 registry、Package source、Package Manifest、Central Runtime Config source、active effective Config 及 embedded Engine schema 的 revision identity，以及安全的 environment/version metadata。此 API 僅供讀取；client 不能指定 source root。registry 變更後，以舊 registry revision 建立的 draft 和 approval 會被拒絕。已接受的 Job 會保留其已核准的 Package root 與輸入。

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

詳細回應包含 `resource`、`definition`、`diagnostics` 及 `requestId`。Template/Flow YAML source 回應包含 `resource`、`available`、`format`、`text`、`redacted`、`fileReferences`、`fileReferencesTruncated`、`fileReferencesUnavailable` 及 `requestId`。每個合資格 `fileReferences` 項目包含 package-relative `logicalPath` 及不透明 `referenceId`；將 ID 傳給 `source-file` 後，Server 會再次執行 canonical containment 及 visibility 檢查並回傳有界文字。若有一個或多個 locator 無法安全驗證，`fileReferencesUnavailable` 會是 `true`；不會因此公開原始路徑或檔案內容。Source-file 回應包含 `logicalPath`、已淨化的 `text` 及 `redacted`。此 route 使用與其他 inspection endpoint 相同的身份及授權 mode；匿名模式仍受個別 inspection feature gate 限制。不要由 logical path 建立 filesystem URL。來源不可用時，`available` 為 `false`，會回傳穩定 reason，而且不會回傳部分內容。找不到目標的關聯會標示 `resolution: "unresolved"`，並附有穩定資源診斷碼。

無效清單參數會回傳 `400`；未知 package/resource 使用相同的 `404` 格式。過期 cursor 回傳 `409`，超大回應回傳 `413`，探索佇列已滿回傳 `503`，Inspector 逾時回傳 `504`。錯誤回應使用既有的 `error.code`、`error.summary` 及 `requestId` 格式，不會包含實體路徑或 parser exception 訊息。

## Lossless authoring-source reads

上方的安全 source inspection 仍供獲授權的 USER operator 使用，並回傳既有的 redacted projection；該 projection 不可當作可寫入來源。Package authoring 使用獨立 endpoint，要求已驗證的 active USER，其 canonical login ID 必須與已登錄 Package ID 完全相同；`default` 永不允許編輯。本機 login ID 會先經 NFKC 正規化及 `Locale.ROOT` 小寫轉換，再限制為小寫 ASCII 字母、數字、點、底線及連字號。Trusted container/SSO principal 必須已使用相同 canonical 格式；source API 不會轉換大小寫或 Unicode，也不會合併 identity。亦必須啟用可信部署設定 `server.packageConfiguration.sourceEditingEnabled: true`。ADMIN 不會取得 Package USER operations 或 owner 權限；Package owner 亦不會取得 central configuration 權限。

```http
GET /api/v1/packages/{packageId}/authoring-source?type=manifest
GET /api/v1/packages/{packageId}/authoring-source?type=resource&resourceType=template&resourceId={resourceId}
GET /api/v1/packages/{packageId}/authoring-source?type=file&resourceType=template&resourceId={resourceId}&referenceId={opaque}
GET /api/v1/packages/{packageId}/authoring-source?type=load&path=load%2Fscenario.yaml
GET /api/v1/packages/{packageId}/authoring-source?type=testdata&resourceId={descriptorId}&environment=SIT
GET /api/v1/configuration/authoring-source?path=config%2Fconfig.yaml
```

Package source 僅限固定 `package.yaml`、ready Template/Flow YAML、允許的 `load/*.yaml` 與 `load/*.yml`、已設定的 Package Testdata descriptor，以及由授權 Template/Flow 的靜態 `&{path}` reference 到達的合資格 UTF-8 文字檔。`file` request 必須重用安全 source inspection 簽發的不透明 reference 與 origin。Central source 僅限所選的 `config/config.yaml` Runtime Config 及其宣告的 YAML Helper/Tool descriptor；不允許 `att-server.yaml` 或敏感憑證／金鑰路徑。必須具有 active `SYSTEM_SETTINGS_WRITE` (ADMIN)，並啟用獨立部署 switch `server.centralConfigurationEditingEnabled: true`，預設為 `false`。

成功回應包含完整 UTF-8 `text`、`logicalPath`、`present`、SHA-256 `revision` 及 quoted `sourceEtag`；HTTP `ETag` header 使用相同的內容 ETag。回應使用 `Cache-Control: private, no-store`。每次讀取都會重新檢查身份／角色、canonical root、registry、path allowlist、symlink、大小、UTF-8 及受保護秘密。即使關閉一般 masking，憑證與金鑰仍會 fail closed。不可使用 `redacted` query parameter。未驗證或無權限回傳 `401`／`403`；不可用或不允許的路徑回傳 `404`；來源／root 競態回傳 `409`；超出大小上限回傳 `413`。

Package inspection 將不存在的選填 `templates/`、`testcase/` 及 `load/` 根目錄視為空 inventory。若根目錄存在但不是可讀目錄，或解析後超出 canonical Package，會回傳 HTTP `422 ATT-RESOURCE-ROOT-INVALID`，並以安全的 `field` 指出受影響根目錄。其他 Worker validation errors 回傳 `422 ATT-RESOURCE-VALIDATION-FAILED`；兩種回應都不會包含絕對路徑或 parser 細節。

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

`includeAllFields=true` 只適用於 effective 或 compare view，並加入其他非秘密 DB properties、evidence SQL／parameters 及平常會遮蔽的 DB 運作設定。Caller 必須具備 active `SYSTEM_SETTINGS_WRITE` (ADMIN)。回應不會快取並會寫入 audit log。Username、password、token、個人資料、HTTP header maps、MQ evidence payload、SSH 連線資料及 identity-file 路徑、已分類欄位及設定的敏感值仍會遮蔽。Source 保持安全淨化；Declared 只提供宣告狀態和數量。

`source` endpoint 在 `safe-package-files` policy 下可依 origin 解析合資格的 package-local 文字參照；`allowlist-only` 則要求既有 `server.inspection.safeTextSources` 精確 package-relative 路徑。連結請求必須包含已授權 parent source 回傳的 Server 不透明 `referenceId`；caller 自行提供 path 和 origin 並不足以授權。External scripts 只會透過設定 external root 下每 package 的 canonical 精確 allowlist 提供，且不會回傳其絕對路徑。這仍是有界 source endpoint，並非任意檔案瀏覽器。Testdata records endpoint 需要 effective descriptor ID。Literal records 使用 `limit` 1–100 分頁（UI 每次讀取 50 筆）。Generated descriptor 預設回傳 `state: "preview-required"` 且不含 records；只有明確設定 `previewGenerated=true` 才會回傳有界 generated preview。此 endpoint 使用相同身份、redaction policy、bounded inspection worker、source/response 大小、佇列和逾時限制。

設定回應包含 `view`、`state`、可用時的 `schemaVersion` 及 `diagnostics`。分頁回應亦包含 `section`、`offset`、`limit`、`total` 及 `nextOffset`。無效設定會以 HTTP 200 回傳 `state: "invalid"` 及穩定診斷碼；格式錯誤的 profile 參數回傳 `400`。未知 package 回傳 `404`，超大回應回傳 `413`，檢查佇列已滿回傳 `503`，Inspector 逾時回傳 `504`。回應大小、佇列和逾時限制與資源探索相同。
