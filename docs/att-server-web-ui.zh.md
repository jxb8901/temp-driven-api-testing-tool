# ATT Server Web UI

ATT Server 在 `<Tomcat context path>/ui/` 提供靜態瀏覽器主控台，例如 `https://host.example.com/tools/att/ui/`。支援維護中的 Chrome、Edge 或 Firefox 版本。Tomcat 容器驗證同時保護 `/ui/*` 與 `/api/v1/*`；此 UI 不建立帳號，也不收集憑證。啟用驗證的部署應使用 HTTPS。HTTP Basic 憑證由瀏覽器處理，ATT 不會儲存。

主控台會列出已設定的邏輯套件 ID 與近期持久化工作。選擇套件後可提交 Run、Debug、Load 或 Validate，接著開啟工作以追蹤進度、檢視終端結果與安全診斷、取消執行中的工作，或下載邏輯 artifact。套件資源欄位使用相對於所選套件的邏輯路徑。UI 不接受或顯示 `PACKAGE_ROOT` 或 Server 儲存路徑。Artifact 以附件下載；UI 不會呈現 artifact HTML。 工作深層連結（例如 `/ui/jobs/J10045`）會轉址至標準 hash route，並透過公開 API 與 SSE 重新載入工作狀態。

Package Resource Explorer 會按 package-scoped 邏輯資源 ID 列出已設定的 Case、Template、Flow 及 Tool，並支援類型與文字篩選、加密續頁 cursor、資源詳細資料、安全關聯及唯讀 source 檢視。YAML 會以解析及遮蔽後的投影回傳；只有 `server.inspection.safeTextSources` 列出的 package 相對路徑才會顯示 Tool script 文字。Case 詳細資料可使用現有 job API 執行 Run。Explorer 不會瀏覽任意檔案、不會執行 Tool，也不會修改 package 內容。若分頁期間 package 有變更，Server 會拒絕過期 cursor，UI 可重新整理清單。

Configuration Explorer 會顯示已宣告設定、所選 environment 經 Engine 解析後的設定，以及兩個 profile 的欄位比較。它使用 package 正常的 `FrameworkConfigLoader` 選擇規則，不會連接 DB、MQ、HTTP 或 SSH。可能洩露憑證或網絡拓撲的值會標示為 hidden；Server 不會比較 hidden 值。Explorer 顯示安全的 helper 與 Tool metadata、environment 來源、profile 繼承或替換狀態，以及 Testdata descriptor metadata，但不會顯示 records 或未限制的 YAML。公開 endpoint 詳情見 [ATT Server 部署與 API](server-deployment.zh.md)。

瀏覽器只使用公開的同源 `/api/v1` REST 與 SSE 契約。REST 請求與原生 `EventSource` 使用瀏覽器管理的容器驗證。SSE 重新連線使用瀏覽器標準 `Last-Event-ID` 行為；重新整理工作頁面時會從 Server 重新載入工作狀態與保留事件。關閉瀏覽器分頁不會取消工作。取消前需要確認，UI 會等候 Server 回報終端狀態。

UI 會在載入工作資料前檢查 Server API 版本。工作到達最終狀態並收到 SSE result 後會關閉事件串流，以免重複連線；即時進度另外顯示，事件歷史則限制筆數。靜態 UI 與 API 共用 Tomcat 驗證邊界。

主控台以 `att-web` JAR 中 `META-INF/resources/ui/` 的靜態資源提供，並封裝於 Server WAR。相對 URL 支援非根 Tomcat context path，無需額外前端服務或執行期 Node.js。Server 套用同源 Content Security Policy，並拒絕帶有非預期 `Origin` 的狀態變更瀏覽器請求；無需設定含憑證的 CORS。API 文字以純文字方式插入 DOM，事件清單最多保留 500 筆。

第一版對尚無公開 discovery endpoint 的欄位提供文字輸入。Server 驗證仍是權威來源；瀏覽器端驗證與篩選不構成授權邊界。
