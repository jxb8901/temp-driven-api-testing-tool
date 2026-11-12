<!-- att-doc-status: current release=4.2.10 -->

# Studio Read-only validation

**Validate** 工作區會執行有界、同步的 Server validation，並顯示 `att-validation/v1` report。可從 sidebar、command palette 或 `#/validate` 開啟。此工作區不會編輯 Config 或 Package file，也不會建立 RUN、LOAD、Debug draft、Job、Schedule 或已保存 snapshot。

## 選擇範圍

先選擇範圍，再按需要選擇已註冊 Package。Package selector 只會列出已驗證 Server 回傳的 Package。此處選取的 Package 只屬於 route context，不會變更 Studio 全域 Workspace。

| 範圍 | Target 及 context |
|---|---|
| Shared / Effective Config | 不選 Package 時驗證 Server Runtime Config；選擇 Package 時驗證其生效 Config。 |
| Package | 驗證已註冊 Package 及其靜態 dependency/resource 檢查。 |
| Case | 以 logical Case ID 選取已授權 Case。 |
| Suite | 選擇 Package-relative Suite path。若 Explorer 中的 Case 有回報所屬 Suite，可直接從該處開啟此 action。 |
| Template、Flow、Tool | 以 Server 提供的 canonical resource ID 選取一個已授權 resource。 |
| LOAD Scenario | 從已授權 LOAD catalog 選擇可執行情境 path。僅含 policy 的 template 不是有效 target。 |

Package 範圍 validation 使用 active Environment Profile。可明確選取一個 Environment，或保留 **Package default**。後者不會在 request 傳入 Environment，讓 Server 解析實際生效／預設 Environment。Server 回傳解析後名稱時，頁面會顯示該名稱。無法使用或未獲授權的 profile 不可視為已驗證。

可從 Shared Configuration、Explorer resource 或可執行 LOAD Scenario 的上下文 action 開啟此工作區。Shared Configuration 提供全域 Runtime Config 驗證捷徑；選取 Package context 時，亦會提供該套件生效 Config 的獨立捷徑。Explorer 及 LOAD 捷徑會保留來源 Package、Environment 及所選 target，方便在目前已驗證 Studio context 中重新載入或分享。

## 執行與檢視

選擇 **Run Validation** 才會送出一個 `POST /api/v1/validation` request。載入頁面、變更範圍、選取 Package／Environment／target 或搜尋 catalog 都不會提交 validation。Studio 會檢查 `GET /api/v1/version.validationApiVersion`；Server 宣告 `att-validation/v1` 後才會啟用此 action。Server role、Package 及 Environment policy、同源控制仍會授權每個 request。

Report 會顯示階層式檢查、完整性、數量、Server request ID 及安全 context。可使用 severity、code、source、resource 及文字篩選 findings。**Copy safe report** 只會複製支援的 report 欄位及 allowlist context，並淨化類似 credential 的值與 absolute path。Finding 識別所選 Explorer resource 時，會顯示 **Open matching resource**。

只有 Server 回傳 `overallStatus: PASS`、`complete: true` 及 `exitCode: 0` 時，Studio 才會顯示 `PASS`。`INVALID` report 保持無效；不完整或其他非通過結果會顯示 `PARTIAL`。單有 HTTP `200` 並不代表通過。

頁面會將身份驗證、存取、resource 不存在、source 過期、request 大小、media type、Worker 容量及 timeout 錯誤轉為可操作訊息。遇到 `404` 或 `409` 時，請重新整理 target catalog 後再執行。取消會中止 browser request、清除等待中的結果並忽略較晚回傳的 response；Server 仍會套用時間及資源上限。

## 權限與呈現

操作用戶只能驗證 Server 授權的範圍。Studio 不會提供整個安裝範圍的 validation。Server 版本不支援時，頁面會顯示所需 capability，不會提交 request。Validation report 只保留於目前 route 及 auth context 的記憶體；變更範圍、Package、Environment、target、principal context 或 inspection session 時會清除舊 report 並中止作用中的 browser request。

控制項使用有標籤的原生輸入、明顯 focus、鍵盤操作及即時狀態訊息。版面會配合窄螢幕，並沿用 Studio light/dark theme。英文及繁體中文標籤會依 Studio 語言偏好顯示。靜態資源及 API call 使用相對於 Tomcat context path 的 URL，亦支援非 root path。

Request、report、authorization 及錯誤 contract 請參閱 [Server Validation API](../server-api/validation.zh.md)。
