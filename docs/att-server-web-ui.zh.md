# ATT Server Web UI

ATT Server 在 `<Tomcat context path>/ui/` 提供靜態瀏覽器主控台，例如 `https://host.example.com/tools/att/ui/`。支援維護中的 Chrome、Edge 或 Firefox 版本。`server.auth.mode: container` 由 Tomcat 保護 `/ui/*` 及 `/api/v1/*`；HTTP Basic 憑證由瀏覽器處理，ATT 不會儲存。`server.auth.mode: local` 則由 ATT 提供登入頁面及本地帳戶功能。啟用驗證的部署應使用 HTTPS。

## 本地使用者管理

Local mode 中，每位已登入的本地用戶均可在目前用戶選單使用 **Change password**。表單需要目前密碼、新密碼及確認。變更成功後會撤銷目前 session，並將用戶轉回登入頁面及顯示成功訊息。密碼只會留在目前表單，不會寫入 local storage、session storage、URL 或應用程式日誌。

Local `ADMIN` 可前往 **Settings → User Management**，以登入 ID、顯示名稱、角色及狀態搜尋或排序安全帳戶列表。管理員可建立 `ADMIN` 或 `USER`、啟用或停用帳戶，以及重設密碼。建立帳戶及密碼表單都要求確認欄位相符，並遵守 Server 的 12 至 1024 字元密碼政策。系統不會顯示現有密碼。停用用戶或重設密碼前需要確認，並會撤銷該用戶的 session。即使多個停用請求同時執行，Server 仍會阻止停用最後一個啟用中的 `ADMIN`。若所有管理員都無法登入，請使用 [Server 部署指南](server-deployment.zh.md) 所述的離線復原程序；復原只會更新現有 `ADMIN`，不會重新開啟 bootstrap。

只有已驗證的 local `ADMIN` 可檢視帳戶列表及控制項。其他本地用戶看不到帳戶資料或管理功能。Container mode 的 Settings 會說明帳戶由 Tomcat 或設定的 SSO provider 管理；ATT 不會把外部帳戶顯示成由 ATT 管理。UI 支援英文及繁體中文；存取控制及密碼政策仍由 Server 執行。

主控台會列出已設定的邏輯套件 ID 與近期持久化工作。選擇套件後可提交 Run、Debug、Load 或 Validate，接著開啟工作以追蹤進度、檢視終端結果與安全診斷、取消執行中的工作，或下載邏輯 artifact。套件資源欄位使用相對於所選套件的邏輯路徑。UI 不接受或顯示 `PACKAGE_ROOT` 或 Server 儲存路徑。Artifact 以附件下載；UI 不會呈現 artifact HTML。 工作深層連結（例如 `/ui/jobs/J10045`）會轉址至標準 hash route，並透過公開 API 與 SSE 重新載入工作狀態。

Package Resource Explorer 會按 package-scoped 邏輯資源 ID 列出已設定的 Case、Template、Flow 及 Tool，並支援類型與文字篩選、加密續頁 cursor、資源詳細資料及安全關聯。只有 `server.inspection.safeTextSources` 列出的 package 相對路徑才會提供 source；YAML 會以遞迴結構投影回傳並隱藏自由文字值，Tool script 文字則會遮蔽。Case 詳細資料會顯示身份及 Stage/Template 中繼資料，並隱藏 workbook 業務值。反向關聯最多內嵌 100 筆，並同時回傳準確數量及 `referencedByHasMore`。Explorer 不會瀏覽任意檔案、不會執行 Tool，也不會修改 package 內容。若分頁期間 package 有變更，Server 會拒絕過期 cursor，UI 可重新整理清單。

Debug 只能從已選取的 Template、Flow 或 Tool 詳細資料開啟。表單會載入該目標選填 `debug.yaml` 的安全預設值；若檔案不存在，Server 會提供有效的空白 `att-debug/v1.2` 預設值。Package 撰寫的值預設會隱藏。保留 `$attDebugKeepDefault` 標記即可沿用隱藏值；若要覆寫，請改成明確值。可以用 JSON object 編輯巢狀值、還原已載入的預設值、驗證，並在開始 Debug 前檢視已遮蔽的 YAML 預覽。所有目標都使用 `inputs`；Template 與 Flow 亦使用 `vars`，Tool 則使用 `arguments` 並不接受 `vars`。Server 會將記憶體中的 draft 綁定至已驗證 Principal 及目前 package revision。Draft 於 10 分鐘後過期，Server 重啟後失效。編輯或提交都不會寫入 package 檔案。現有以路徑提交的 Debug API 仍供相容用戶端使用。

瀏覽器建立的 Quick Load 與 Advanced Load 預設停用。Server 管理員必須設定 `server.inlineLoad.enabled: true`，並配置 workload、target、user、rate、並行數及 duration 上限，這些表單才可建立 draft。UI 會從 `/api/v1/version` 讀取 `inlineLoadEnabled`；功能關閉時會停用 Advanced Load 入口、隱藏 Quick Load，直接開啟頁面則會顯示停用狀態。停用時 endpoint 回傳 `403 ATT-SERVER-INLINE-LOAD-DISABLED`；超出上限的 draft 會回傳 `400 ATT-SERVER-INLINE-LOAD-LIMIT`，並以摘要指出超出的設定。此設定不會停用既有 path-based Load 用戶端。

Quick Load 是第一層路由，可對單一選定的 Template、Flow 或 Tool 執行。選擇 package/server policy 或內建 **Quick smoke** preset，再編輯安全 business inputs 和負載 pacing。Preset 以 `examples/load/quick-profile.yaml` 為基礎，只會修改尚未提交的草稿。你可另外加入 package-relative Load-level Testdata descriptors；Debug-local Testdata imports 不會自動提升。若存在相符的 `load/load.visualuser.yaml` 或 `load/load.arrivalrate.yaml`，Server 會讀取該 policy，否則使用內建 10 秒低強度 fallback。先驗證單一 target scenario，確認後才提交。Server 會將 draft 綁定至已驗證 Principal 及目前 package revision，並提交與驗證相同的不可變 scenario。Quick Load 和 Advanced Load 的 Schedule 控制會停用並說明原因，直至支援 immutable inline schedule snapshots；既有 Scheduled Load 路由不受影響。

首頁每個 package 都可開啟 Advanced Load。先選擇 `virtualUsers` 或 `arrivalRate`，再新增 workloads；所有 workload 都使用同一 model。設定共用時間窗口、選填 seed、package-relative Testdata imports、execution defaults、aggregate thresholds 及 evidence policy。你可以新增、複製、排序或移除 workload。每個 workload 有自己的 intensity、target、Testdata policy 及 thresholds。Workload 可使用單一 Template、Flow 或 Tool target；Virtual Users 亦可使用 weighted target mix。每個 Arrival Rate workload 使用一個 target。Target sidecar 預設值只包含安全的 business 欄位；不會複製 Debug `case`、`stage` 或 local Testdata。驗證完整 scenario 並檢視安全 YAML 後，確認即可由 Server 以不可變 draft 交給一般 Load scheduler。只有預覽未遮蔽時才可複製或匯出。

當 `/api/v1/version` 回傳 `loadSchedulingEnabled: true` 且 caller 具備設定的排程管理 role（預設 `ATT_ADMIN`）時，可在 React 主控台的 `#/load-schedules` 使用 Scheduled Load。頁面可建立及編輯 package-relative scenario 排程、按選定的 IANA timezone 顯示 next run，並支援 daily、weekly 及五欄位 cron。Daily 和 weekly 使用當地時間；weekly 可選工作日、週末或個別日子。新排程預設暫停，只有選取 **Enable after saving** 才會啟用。Server 計算 preview，並執行設定的最短間隔及每排程並行上限。春季 DST 跳時中不存在的當地時間會略過；秋季回撥時同一當地時間可能對應兩個有效時間點。錯過時間、並行上限及 Server 全域容量造成的 occurrence 會記錄為 skipped，不會補跑或排隊。Run Now 和刪除都需要確認。Occurrence history 會說明略過及提交失敗，並將已提交工作連結至一般 job 頁面、結果、事件與 artifacts。排程和歷史在 Server 重啟後仍保留；編輯或刪除排程不會取消已接納工作。

共用 React shell 會跟隨作業系統的淺色或深色偏好，直到你自行選擇主題；選擇會保存在瀏覽器儲存空間。按 `/` 或 `Ctrl K`/`⌘ K` 搜尋頁面，按 `?` 開啟快捷鍵清單，按 `Esc` 關閉對話框或手機導覽。在 Advanced Load 頁面，按 `Ctrl Enter`/`⌘ Enter` 只會啟動仍有效且已驗證的 draft，並保留既有確認步驟。主控台和 Scheduled Load 編輯器會適應平板及手機寬度，保留清晰的鍵盤焦點，尊重減少動態效果設定，並以文字標籤配合狀態顏色。

第一層 Configuration 工作區位於 `#/configuration/{packageId}`，結合有界限的 package 檔案樹，以及 Source、Declared、Effective、Compare 和 Testdata 檢視。工作區為唯讀；從 Explorer、Testdata metadata 或關聯資源開啟的 source 連結會保留 package-relative 路徑及 origin。檔案樹只列出 package configuration root 下的檔案，並套用深度、結果數量及分頁上限；若 source 不可用，會清楚顯示原因。以 #214 API 為基礎時，可讀 source 仍受 `server.inspection.safeTextSources` 限制；#215 的共用 source eligibility 和 redaction policy 尚待整合，UI 不會放寬現有 allowlist。Testdata 檢視目前可顯示已解析的 descriptor metadata；記錄預覽會在 Server 提供 #215 的 `getTestdataRecords` endpoint 時使用，連接 #214 時則會說明預覽不可用。

Declared 和 Engine effective 檢視使用 package 正常的 `FrameworkConfigLoader` 選擇規則，不會連接 DB、MQ、HTTP 或 SSH。Root-only package 可直接檢視預設值，無需選擇 profile。可能洩露憑證或網絡拓撲的值會標示為 hidden；Server 不會比較 hidden 值。比較結果分別顯示左右狀態，因此 `hidden` 和 `missing` 會保持區分。Explorer 顯示欄位狀態與來源、篩選 section，並可把 effective environment 帶入現有 Run/Debug/Load 表單而不提交工作。Tool 詳細資料會連結至其設定 section。公開 endpoint 及分頁參數見 [ATT Server 部署與 API](server-deployment.zh.md#package-configuration-inspection)。

Source 和 preview 共用 lazy-loaded CodeMirror 編輯器，支援 YAML、JSON、XML、SQL、Shell 及純文字。它會標示 ATT 的 `${...}`、`#{...}`、`@{...}` 和 `&{...}` expression，並將合資格的 descriptor-relative 檔案參照連結回 Configuration。工具列支援複製、搜尋、跳至行號、全螢幕及安全格式化，不會執行程式碼。Debug sidecar 的 scalar 欄位仍以表單為主，巢狀值可用 Advanced JSON 編輯。Threshold 和 Evidence 提供表單及 Advanced JSON；無效 JSON 會保留在草稿中，不會取代最後有效設定。

## 使用 React Explorer 與 Debug 表單

React Explorer 以三欄工作區呈現套件資源、所選定義及資源操作。選擇 Case、Template、Flow 或 Tool 即可開啟詳細資料。`#/packages/{packageId}/resources/{type}/{resourceId}` hash route 會在重新載入時還原所選資源，並支援瀏覽器導覽。Definition、Source 及 Dependencies 分頁可使用方向鍵、Home 及 End 鍵盤操作。

Source 分頁只載入 Server 提供的安全 source projection。畫面最多顯示前 200,000 個字元及 12,000 行，並只渲染視窗內的行；source 內容會以純文字呈現。Package 更新後可重新整理。**Copy displayed source** 只會複製上述上限內已顯示的文字。空白或不可用的 projection 會明確顯示；驗證及權限錯誤不會包含原始回應細節。

可從資源 Inspector 執行 Debug 或 Case。Sidecar 提供的 scalar 欄位會使用文字、數字、布林值及 null 等型別控制項。巢狀值及完整 business input 可在 **Advanced JSON** 編輯。提交驗證前，請修正無效 JSON 或 scalar 值。**Reset defaults** 會還原從 sidecar 載入的值。若 Server 將 sidecar 值標示為 redacted，表單會顯示 keep-default 標記，不會揭露隱藏值。保留標記即可沿用 package 值；如需覆寫，請選擇型別並輸入明確值。開始 Debug 前請先驗證並檢視安全預覽。Server 仍是驗證及存取檢查的權威來源。

當 Debug 編輯尚未儲存時，切換 package、resource 或 environment 會先詢問是否捨棄變更。同一保護也涵蓋 hash route 及瀏覽器上一頁／下一頁。選填 job label 會隨執行請求作為 Principal label 傳送；它不能取代 Tomcat 驗證。若 session 過期，請重新登入後重試。在 Server 允許請求時，匿名部署亦可使用此 label。

## React Load Builder 與 Jobs/Results

Advanced Load route 使用有型別的控制項設定 duration 單位、Virtual Users、Arrival Rate、最大並行數及 think time。Target sidecar 預設值會為 `inputs`、`vars` 及 Tool `arguments` 提供有型別 scalar 控制項；巢狀值保留在 Advanced JSON。表單會按 model 顯示適用限制，但驗證、policy 上限、draft 過期、package revision 及執行仍以 Server 為準。確認後，UI 會提交同一份由 Server 簽發的 draft；回應已遮蔽時不會提供預覽複製或匯出。

Jobs route 載入 Server 回傳的最新工作列（最多 100 筆）。狀態、套件、類型、建立日期篩選、排序及用戶端分頁只作用於已載入列；Principal 搜尋會送到 Server。此 API 不會回傳工作所屬 environment，因此 UI 不提供 environment 篩選。重新整理等待回應時會保留所選篩選及排序。工作詳細資料提供穩定的 Overview、Live logs、Events、Results、Artifacts 及 Metrics 分頁。原生同源 `EventSource` 會使用瀏覽器的 `Last-Event-ID` 重新連線；取消前需要確認，UI 會等候 Server 狀態事件。Metrics 只顯示工作及結果 endpoint 實際回傳的值；Server timing 與 Worker JVM 資料會和 Load 結果指標分開呈現，結果沒有 time-series buckets 時不會推算 throughput 圖表。

Explorer 是 React 與 TypeScript 單頁 UI，於 `att-web` Maven resource 階段使用 Vite 建置。Maven 會把產生的靜態資源複製至 `META-INF/resources/ui/`；Server WAR 再將它們封裝供 Tomcat 提供。Node.js 只用於建置階段。部署後的 UI 呼叫同源公開 REST API，無需額外 Node 程序或前端服務。

瀏覽器只使用公開的同源 `/api/v1` REST 與 SSE 契約。REST 請求與原生 `EventSource` 使用瀏覽器管理的容器驗證。SSE 重新連線使用瀏覽器標準 `Last-Event-ID` 行為；重新整理工作頁面時會從 Server 重新載入工作狀態與保留事件。關閉瀏覽器分頁不會取消工作。取消前需要確認，UI 會等候 Server 回報終端狀態。

UI 會在載入工作資料前檢查 Server API 版本。工作到達最終狀態並收到 SSE result 後會關閉事件串流，以免重複連線；即時進度另外顯示，事件歷史則限制筆數。靜態 UI 與 API 共用 Tomcat 驗證邊界。

主控台以 `att-web` JAR 中 `META-INF/resources/ui/` 的靜態資源提供，並封裝於 Server WAR。相對 URL 支援非根 Tomcat context path，無需額外前端服務或執行期 Node.js。Server 套用同源 Content Security Policy，並拒絕帶有非預期 `Origin` 的狀態變更瀏覽器請求；無需設定含憑證的 CORS。API 文字以純文字方式插入 DOM，事件清單最多保留 500 筆。

對尚無公開 discovery schema 的欄位，UI 會使用安全 sidecar 資料提供 typed scalar 控制項，並以 Advanced JSON 編輯巢狀值。Server 驗證仍是權威來源；瀏覽器端驗證與篩選不構成授權邊界。
