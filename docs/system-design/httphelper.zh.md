# HTTPHelper 執行設計

`att-config/v2.10` loader 在外部執行前解析所選 profile 的 `httphelpers` 清單，驗證每個 `att-httphelper/v1.1` descriptor，並建立不分大小寫的 logical registry。Profile 整組替換清單，不逐屬性合併。Helper 設定不可變；認證資料及 trust-store 密碼屬 transport state，不進入 expression Context。

`UnifiedTemplateEngine` 將 primary `http.<id>.<operation>` 呼叫交給 run-owned `HttpHelperExecutor`。每個 logical helper 懶建立 thread-safe Apache HTTP client 與有界 pool。一般 run 每個 suite 擁有一個 executor；load iteration 經 `LoadRunResources` 共享；debug 每次呼叫自有一個。owner 在生命週期結束時關閉 executor。不會隱式保留 cookie 或自動重試 request。

Executor 在網路 I/O 前驗證相對 path、query、headers 和 payload。Action deadline 限制 pool/connect/read 等候；過期纔回來的結果仍分類為 `HTTP_TIMEOUT`。Redirect 手動限制次數且只允許同 origin。`ActionExecutionResult` 依 response media type 將原生 typed body 放入 `result`，安全 HTTP 欄位放入 `outputMetadata`，另提供只含 metadata 且已遮蔽的 evidence；失敗則提供 HTTP 專屬診斷。Action `result.format` 只作下游序列化，不能重新解析 body；`raw` 不是共用 result 格式。Response header key 會轉小寫供大小寫敏感的 Context lookup；值只依敏感 header 與已知 secret 遮蔽。Action runner 負責斷言、持久化及 retry。收到 4xx/5xx 屬完成的 transport exchange，作者可斷言；transport/format 失敗為 `ERROR`。

舊版 descriptor 驗證透過 `SchemaFiles` 從 `schemas/` 尋找現行版本、從 `schemas/history/` 尋找已知歷史版本。`SchemaMigrationGuidance` 先依宣告版本驗證，再只替換 `schemaVersion` 以現行 schema 探測；僅探測通過才建議升版，並保留原始違規及 YAML 來源位置。既有 `renderAs`/`saveAs` 專門欄位對照仍然有效。
