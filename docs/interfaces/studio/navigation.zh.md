<!-- att-doc-status: current release=4.2.4 -->

# Studio 導覽

ATT Server 在 `<Tomcat context path>/ui/` 提供靜態瀏覽器主控台，例如 `https://host.example.com/tools/att/ui/`。支援維護中的 Chrome、Edge 或 Firefox 版本。`server.auth.mode: container` 由 Tomcat 保護 `/ui/*` 及 `/api/v1/*`；HTTP Basic 憑證由瀏覽器處理，ATT 不會儲存。`server.auth.mode: local` 則由 ATT 提供登入頁面及本地帳戶功能。啟用驗證的部署應使用 HTTPS。

![ATT Studio Dashboard 顯示 Package 摘要與近期 Job 活動](../../system-design/ui-shell-baseline/dashboard-1440-light.png)

*Studio 示意畫面，使用示例 Package 與 Job 資料。*

![ATT Studio Package 列表](../../system-design/ui-shell-baseline/packages-1440-light.png)

*Package 選擇示意畫面，使用示例資料。*

*以上為靜態 UI shell 基線截圖；圖中版本標籤屬於該截圖基線。*

主控台會列出已設定的邏輯套件 ID 與近期持久化工作。選擇套件後可提交 Run、Debug、Load 或 Validate，接著開啟工作以追蹤進度、檢視終端結果與安全診斷、取消執行中的工作，或下載邏輯 artifact。套件資源欄位使用相對於所選套件的邏輯路徑。UI 不接受或顯示 `PACKAGE_ROOT` 或 Server 儲存路徑。Artifacts 分頁顯示封存狀態，並從經驗證的 ZIP 預覽報告及文字。**Download Artifacts (.zip)** 是唯一下載操作；HTML 報告使用隔離的檢視器。詳見 [Artifact 發布與復原](../../reference/results-reports-evidence.md#att-server-job-artifact-publication)。 工作深層連結（例如 `/ui/jobs/J10045`）會轉址至標準 hash route，並透過公開 API 與 SSE 重新載入工作狀態。

瀏覽器只使用公開的同源 `/api/v1` REST 與 SSE 契約。REST 請求與原生 `EventSource` 使用瀏覽器管理的容器驗證。SSE 重新連線使用瀏覽器標準 `Last-Event-ID` 行為；重新整理工作頁面時會從 Server 重新載入工作狀態與保留事件。關閉瀏覽器分頁不會取消工作。取消前需要確認，UI 會等候 Server 回報終端狀態。

UI 會在載入工作資料前檢查 Server API 版本。工作到達最終狀態並收到 SSE result 後會關閉事件串流，以免重複連線；即時進度另外顯示，事件歷史則限制筆數。靜態 UI 與 API 共用 Tomcat 驗證邊界。

主控台以 `att-web` JAR 中 `META-INF/resources/ui/` 的靜態資源提供，並封裝於 Server WAR。相對 URL 支援非根 Tomcat context path，無需額外前端服務或執行期 Node.js。Server 套用同源 Content Security Policy，並拒絕帶有非預期 `Origin` 的狀態變更瀏覽器請求；無需設定含憑證的 CORS。API 文字以純文字方式插入 DOM，事件清單最多保留 500 筆。

對尚無公開 discovery schema 的欄位，UI 會使用安全 sidecar 資料提供 typed scalar 控制項，並以 Advanced JSON 編輯巢狀值。Server 驗證仍是權威來源；瀏覽器端驗證與篩選不構成授權邊界。
