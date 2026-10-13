# ATT Server Web UI

ATT Server 在 `<Tomcat context path>/ui/` 提供靜態瀏覽器主控台，例如 `https://host.example.com/tools/att/ui/`。支援維護中的 Chrome、Edge 或 Firefox 版本。Tomcat 容器驗證同時保護 `/ui/*` 與 `/api/v1/*`；此 UI 不建立帳號，也不收集憑證。啟用驗證的部署應使用 HTTPS。HTTP Basic 憑證由瀏覽器處理，ATT 不會儲存。

主控台會列出已設定的邏輯套件 ID 與近期持久化工作。選擇套件後可提交 Run、Debug、Load 或 Validate，接著開啟工作以追蹤進度、檢視終端結果與安全診斷、取消執行中的工作，或下載邏輯 artifact。套件資源欄位使用相對於所選套件的邏輯路徑。UI 不接受或顯示 `PACKAGE_ROOT` 或 Server 儲存路徑。Artifact 以附件下載；UI 不會呈現 artifact HTML。 工作深層連結（例如 `/ui/jobs/J10045`）會轉址至標準 hash route，並透過公開 API 與 SSE 重新載入工作狀態。

Package Resource Explorer 會按 package-scoped 邏輯資源 ID 列出已設定的 Case、Template、Flow 及 Tool，並支援類型與文字篩選、加密續頁 cursor、資源詳細資料及安全關聯。只有 `server.inspection.safeTextSources` 列出的 package 相對路徑才會提供 source；YAML 會以遞迴結構投影回傳並隱藏自由文字值，Tool script 文字則會遮蔽。Case 詳細資料會顯示身份及 Stage/Template 中繼資料，並隱藏 workbook 業務值。反向關聯最多內嵌 100 筆，並同時回傳準確數量及 `referencedByHasMore`。Explorer 不會瀏覽任意檔案、不會執行 Tool，也不會修改 package 內容。若分頁期間 package 有變更，Server 會拒絕過期 cursor，UI 可重新整理清單。

Debug 只能從已選取的 Template、Flow 或 Tool 詳細資料開啟。表單會載入該目標選填 `debug.yaml` 的安全預設值；若檔案不存在，Server 會提供有效的空白 `att-debug/v1.2` 預設值。Package 撰寫的值預設會隱藏。保留 `$attDebugKeepDefault` 標記即可沿用隱藏值；若要覆寫，請改成明確值。可以用 JSON object 編輯巢狀值、還原已載入的預設值、驗證，並在開始 Debug 前檢視已遮蔽的 YAML 預覽。所有目標都使用 `inputs`；Template 與 Flow 亦使用 `vars`，Tool 則使用 `arguments` 並不接受 `vars`。Server 會將記憶體中的 draft 綁定至已驗證 Principal 及目前 package revision。Draft 於 10 分鐘後過期，Server 重啟後失效。編輯或提交都不會寫入 package 檔案。現有以路徑提交的 Debug API 仍供相容用戶端使用。

瀏覽器建立的 Quick Load 與 Advanced Load 預設停用。Server 管理員必須設定 `server.inlineLoad.enabled: true`，並配置 workload、target、user、rate、並行數及 duration 上限，這些表單才可建立 draft。UI 會從 `/api/v1/version` 讀取 `inlineLoadEnabled`；功能關閉時會停用 Advanced Load 入口、隱藏 Quick Load，直接開啟頁面則會顯示停用狀態。停用時 endpoint 回傳 `403 ATT-SERVER-INLINE-LOAD-DISABLED`；超出上限的 draft 會回傳 `400 ATT-SERVER-INLINE-LOAD-LIMIT`，並以摘要指出超出的設定。此設定不會停用既有 path-based Load 用戶端。

Quick Load 同樣可從這些目標詳細資料開啟。選擇 Virtual Users 或 Arrival Rate，編輯安全的 business inputs、調整 model pacing，並可另外提供 package-relative Load-level Testdata descriptors。若表單略過 Debug-local Testdata imports，UI 會明確提示；這些 imports 不會自動提升。若存在相符的 `load/load.visualuser.yaml` 或 `load/load.arrivalrate.yaml`，Server 會讀取該 policy；否則使用內建 10 秒低強度 fallback。UI 會在提交流量前要求檢視及確認。Quick Load 只複製 `inputs`、`vars` 或 Tool `arguments`，不會複製 Debug case/stage 欄位或 Debug-local Testdata。Server 會將 draft 綁定至已驗證 Principal 及目前 package revision，並提交與驗證時相同的不可變 scenario。

首頁每個 package 都可開啟 Advanced Load。先選擇 `virtualUsers` 或 `arrivalRate`，再新增 workloads；所有 workload 都使用同一 model。設定共用時間窗口、選填 seed、package-relative Testdata imports、execution defaults、aggregate thresholds 及 evidence policy。你可以新增、複製、排序或移除 workload。每個 workload 有自己的 intensity、target、Testdata policy 及 thresholds。Workload 可使用單一 Template、Flow 或 Tool target；Virtual Users 亦可使用 weighted target mix。每個 Arrival Rate workload 使用一個 target。Target sidecar 預設值只包含安全的 business 欄位；不會複製 Debug `case`、`stage` 或 local Testdata。驗證完整 scenario 並檢視安全 YAML 後，確認即可由 Server 以不可變 draft 交給一般 Load scheduler。只有預覽未遮蔽時才可複製或匯出。

當 `server.scheduling.enabled` 為 true 且 caller 具備設定的排程管理 role（預設 `ATT_ADMIN`）時，可使用 Scheduled Load。頁面可建立及編輯 package-relative scenario 排程、按選定的 IANA timezone 顯示 next run，並提供暫停/恢復、需確認的手動觸發及刪除。Cron 使用五個數字欄位並支援清單、範圍及 step；Server 會執行設定的最短間隔限制。Occurrence history 會區分 `skipped`、`failed-to-submit` 及已接納工作，submitted run 連結至一般 job 頁面、結果、事件與 artifacts。排程和歷史在 Server 重啟後仍保留。重啟不會補跑錯過的 occurrence；編輯或刪除排程不會取消已接納工作。

Configuration Explorer 會顯示已宣告設定、按 section 分組的 Engine effective 設定，以及兩個 profile 的分頁欄位比較。它使用 package 正常的 `FrameworkConfigLoader` 選擇規則，不會連接 DB、MQ、HTTP 或 SSH。Root-only package 可直接檢視已設定的預設值，無需選擇 profile。可能洩露憑證或網絡拓撲的值會標示為 hidden；Server 不會比較 hidden 值。比較結果會分別顯示左右兩邊的狀態，因此 `hidden` 和 `missing` 會保持區分。Explorer 顯示欄位狀態與來源、篩選所選 section，並可把 effective environment 填入現有 Run/Debug/Load 表單而不提交工作。Tool 詳細資料會連結至其設定 section。Explorer 顯示安全的 helper 與 Tool metadata、profile 繼承或替換狀態，以及 Testdata descriptor metadata，但不會顯示 records 或未限制的 YAML。公開 endpoint 及分頁參數見 [ATT Server 部署與 API](server-deployment.zh.md#package-configuration-inspection)。

## 使用 React Explorer 與 Debug 表單

React Explorer 以三欄工作區呈現套件資源、所選定義及資源操作。選擇 Case、Template、Flow 或 Tool 即可開啟詳細資料。`#/packages/{packageId}/resources/{type}/{resourceId}` hash route 會在重新載入時還原所選資源，並支援瀏覽器導覽。Definition、Source 及 Dependencies 分頁可使用方向鍵、Home 及 End 鍵盤操作。

Source 分頁只載入 Server 提供的安全 source projection。畫面最多顯示前 200,000 個字元及 12,000 行，並只渲染視窗內的行；source 內容會以純文字呈現。Package 更新後可重新整理。**Copy displayed source** 只會複製上述上限內已顯示的文字。空白或不可用的 projection 會明確顯示；驗證及權限錯誤不會包含原始回應細節。

可從資源 Inspector 執行 Debug 或 Case。Sidecar 提供的 scalar 欄位會使用文字、數字、布林值及 null 等型別控制項。巢狀值及完整 business input 可在 **Advanced JSON** 編輯。提交驗證前，請修正無效 JSON 或 scalar 值。**Reset defaults** 會還原從 sidecar 載入的值。若 Server 將 sidecar 值標示為 redacted，表單會顯示 keep-default 標記，不會揭露隱藏值。保留標記即可沿用 package 值；如需覆寫，請選擇型別並輸入明確值。開始 Debug 前請先驗證並檢視安全預覽。Server 仍是驗證及存取檢查的權威來源。

當 Debug 編輯尚未儲存時，切換 package、resource 或 environment 會先詢問是否捨棄變更。同一保護也涵蓋 hash route 及瀏覽器上一頁／下一頁。選填 job label 會隨執行請求作為 Principal label 傳送；它不能取代 Tomcat 驗證。若 session 過期，請重新登入後重試。在 Server 允許請求時，匿名部署亦可使用此 label。

Explorer 是 React 與 TypeScript 單頁 UI，於 `att-web` Maven resource 階段使用 Vite 建置。Maven 會把產生的靜態資源複製至 `META-INF/resources/ui/`；Server WAR 再將它們封裝供 Tomcat 提供。Node.js 只用於建置階段。部署後的 UI 呼叫同源公開 REST API，無需額外 Node 程序或前端服務。

瀏覽器只使用公開的同源 `/api/v1` REST 與 SSE 契約。REST 請求與原生 `EventSource` 使用瀏覽器管理的容器驗證。SSE 重新連線使用瀏覽器標準 `Last-Event-ID` 行為；重新整理工作頁面時會從 Server 重新載入工作狀態與保留事件。關閉瀏覽器分頁不會取消工作。取消前需要確認，UI 會等候 Server 回報終端狀態。

UI 會在載入工作資料前檢查 Server API 版本。工作到達最終狀態並收到 SSE result 後會關閉事件串流，以免重複連線；即時進度另外顯示，事件歷史則限制筆數。靜態 UI 與 API 共用 Tomcat 驗證邊界。

主控台以 `att-web` JAR 中 `META-INF/resources/ui/` 的靜態資源提供，並封裝於 Server WAR。相對 URL 支援非根 Tomcat context path，無需額外前端服務或執行期 Node.js。Server 套用同源 Content Security Policy，並拒絕帶有非預期 `Origin` 的狀態變更瀏覽器請求；無需設定含憑證的 CORS。API 文字以純文字方式插入 DOM，事件清單最多保留 500 筆。

對尚無公開 discovery schema 的欄位，UI 會使用安全 sidecar 資料提供 typed scalar 控制項，並以 Advanced JSON 編輯巢狀值。Server 驗證仍是權威來源；瀏覽器端驗證與篩選不構成授權邊界。
