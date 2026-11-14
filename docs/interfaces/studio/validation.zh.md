<!-- att-doc-status: current release=4.2.10 -->

# Studio Read-only validation

Studio 使用有界唯讀 Server validation，並顯示 `att-validation/v1` report。Configuration、Package Explorer 及 LOAD Scenario 的上下文 action 會建立持久非同步 record。`#/validate` 的進階 **Validate** 工作區仍保留同步 validation form。兩種流程都不會編輯 Config 或 Package file，也不會建立 RUN、LOAD、Debug draft、Job、Schedule 或已保存 snapshot。

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

可從 Shared Configuration、Package Explorer 或可執行 LOAD Scenario 的上下文 action 開啟 inline record。Shared Configuration 提供全域 Runtime Config 及該 Package 生效 Config 的獨立 action。選取 source file 不會改變 validation 範圍。Package Explorer 使用已開啟的 Package 及 Environment，並提供獨立的 **Read-only validate Package** action，以及選取的 Case、Suite、Template、Flow 或 Tool action。LOAD Scenario validation 使用目前 scenario、Package 及 Environment。這些 action 不會隱含掃描整個 Package，只有明確選擇 **Read-only validate Package** 才會執行整包檢查。

## 執行與檢視

上下文 validation 會送出 `POST /api/v1/validations`。Server 接受工作並保存 record 後，會回傳 durable record ID。頁面可見時，Studio 每 2.5 秒讀取 `GET /api/v1/validations/{validationId}`，顯示 `QUEUED` 或 `RUNNING`、生命週期時間、排隊與執行時間，以及可取得的 phase。Studio 不會顯示虛構的百分比或預計完成時間。Shell 會連結至 owner 最新的作用中或近期 record。選擇 **Open full record** 會開啟可加入書籤的 `#/validate?record=V…` route，稍後可重新載入。

同步進階工作區則在選擇 **Run Validation** 後送出單一 `POST /api/v1/validation` request。載入頁面、變更範圍、選取 Package／Environment／target 或搜尋 catalog 都不會提交 validation。Studio 會檢查 `GET /api/v1/version.validationApiVersion`；Server 宣告 `att-validation/v1` 後才會啟用同步 action。上下文 action 另需 `validationRecordsApiVersion: "att-validation-records/v1"`。Server 會依目前 principal、Package／Environment policy 及 Origin/CSRF 控制授權每個 request。

Report 會顯示階層式檢查、完整性、數量、Server request ID 及安全 context。可使用 severity、code、source、resource 及文字篩選 findings。**Copy safe report** 只會複製支援的 report 欄位及 allowlist context，並淨化類似 credential 的值與 absolute path。Finding 識別所選 Explorer resource 時，會顯示 **Open matching resource**。

只有 Server 回傳 `overallStatus: PASS`、`complete: true` 及 `exitCode: 0` 時，Studio 才會顯示 `PASS`。`INVALID` report 保持無效；不完整或其他非通過結果會顯示 `PARTIAL`。單有 HTTP `200` 並不代表通過。

Report 會把 transport state 與 validation outcome 分開。`COMPLETED` 可以包含 `PASS`、`INVALID` 或 `PARTIAL`；只有 `overallStatus: PASS`、`complete: true` 及 `exitCode: 0` 才代表通過。歷史 record 會顯示來源適用性 `UNVERIFIED`，因此舊 PASS 不會被當成目前結果。**Cancel validation** 會呼叫 `POST /api/v1/validations/{validationId}/cancel`。關閉 panel、離開 route 或停止 polling 都不會取消 Server 工作。Record 及 report 會持續供 owner 存取，直到保留或儲存清理移除。Server restart 會把作用中的 record 標記為 `INTERRUPTED`。

## 權限與呈現

操作用戶只能驗證 Server 授權的範圍。Studio 不會提供整個安裝範圍的 validation。Server 預設保留 record 七日，並支援設定筆數及 bytes 上限。用戶只能開啟自己的 record；讀取時 Server 會重新檢查 Package 及 Environment access。Server build 未宣告 async capability 時不會提供上下文 async action。若 Server 宣告 `att-validation/v1`，同步工作區仍可使用。

控制項使用有標籤的原生輸入、明顯 focus、鍵盤操作及即時狀態訊息。版面會配合窄螢幕，並沿用 Studio light/dark theme。英文及繁體中文標籤會依 Studio 語言偏好顯示。靜態資源及 API call 使用相對於 Tomcat context path 的 URL，亦支援非 root path。

Request、report、authorization 及錯誤 contract 請參閱 [Server Validation API](../server-api/validation.zh.md)。
