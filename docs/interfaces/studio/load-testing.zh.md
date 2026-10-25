<!-- att-doc-status: current release=4.2.4 -->

# Studio Load testing

瀏覽器建立的 Quick Load 與 Advanced Load 預設停用。Server 管理員必須設定 `server.inlineLoad.enabled: true`，並配置 workload、target、user、rate、並行數及 duration 上限，這些表單才可建立 draft。UI 會從 `/api/v1/version` 讀取 `inlineLoadEnabled`；功能關閉時會停用 Advanced Load 入口、隱藏 Quick Load，直接開啟頁面則會顯示停用狀態。停用時 endpoint 回傳 `403 ATT-SERVER-INLINE-LOAD-DISABLED`；超出上限的 draft 會回傳 `400 ATT-SERVER-INLINE-LOAD-LIMIT`，並以摘要指出超出的設定。此設定不會停用既有 path-based Load 用戶端。

Quick Load 是第一層路由，可對單一選定的 Template、Flow 或 Tool 執行。有限制的 template picker 會列出 package `load/` 目錄內相容的 policy-only YAML 檔案，包括相容的 `load/load.visualuser.yaml` 和 `load/load.arrivalrate.yaml`，以及只適用於 Virtual Users 的內建 **Quick smoke** preset。選取 template 會載入其支援設定；未選取相容 package template 時，Server 會提供該 model 的預設值或內建 10 秒低強度 fallback。切換 preset 或 model 會更新未提交的 scenario；Target 維持固定，Debug-local Testdata imports 不會自動提升。Advanced Settings 保留 thresholds、evidence、execution options、Testdata paths 及完整 scenario preview。先驗證單一 target scenario，確認後才提交。Server 會將 draft 綁定至已驗證 Principal 及目前 package revision，並提交與驗證相同的不可變 scenario。Schedule 會將已驗證 draft 帶入 immutable inline Load snapshot，因此無須重新輸入 target、environment、inputs、pacing、thresholds 或 evidence。

首頁每個 package 都可開啟 Advanced Load。先選擇 `virtualUsers` 或 `arrivalRate`，再新增 workloads；所有 workload 都使用同一 model。設定共用時間窗口、選填 seed、package-relative Testdata imports、execution defaults、aggregate thresholds 及 evidence policy。你可以新增、複製、排序或移除 workload。每個 workload 有自己的 intensity、target、Testdata policy 及 thresholds。Workload 可使用單一 Template、Flow 或 Tool target；Virtual Users 亦可使用 weighted target mix。每個 Arrival Rate workload 使用一個 target。Target sidecar 預設值只包含安全的 business 欄位；不會複製 Debug `case`、`stage` 或 local Testdata。驗證完整 scenario 並檢視安全 YAML 後，確認即可由 Server 以不可變 draft 交給一般 Load scheduler。只有預覽未遮蔽時才可複製或匯出。

當 `/api/v1/version` 回傳 `loadSchedulingEnabled: true` 且 caller 具備設定的排程管理 role（預設 `ATT_ADMIN`）時，可在 React 主控台的 `#/schedules` 使用 Schedules。可從已驗證的 Quick/Advanced Load draft 建立排程，亦可沿用 package-relative Load scenario。Draft-based 排程會保留 Server 正規化後的 immutable scenario snapshot；API view 只顯示 `inlineSnapshot`，不會回傳 scenario 內容。Server 會在每次 occurrence 前重新驗證 snapshot 及 package revision。頁面支援 descriptions、tags、IANA timezone 的 next-run preview，以及 daily、weekly 或五欄位 cron。Preview 只計算執行時間；儲存時會透過 inspection Worker 驗證完整 scenario 及 target closure。切換 recurrence 會取代已儲存的 trigger；只修改 metadata 時則保留該 trigger 及其經 Server 確認的 next run，因此已執行過的一次性排程仍可重新命名或修改說明。儲存驗證失敗時會顯示診斷碼，以及可用的摘要、欄位、source 位置、package、environment、target 和建議，並附 request ID。使用 **Review field** 可前往相關控制項或開啟已保存的 snapshot。本機絕對路徑會遮蔽。新排程預設暫停，只有選取 **Enable after saving** 才會啟用。Run Now 和刪除都需要確認。Occurrence history 會說明略過及提交失敗，並將已提交工作連結至一般 job 頁面、結果、事件與 artifacts。排程和歷史在 Server 重啟後仍保留；編輯或刪除排程不會取消已接納工作。

在 Quick 或 Advanced Load 按 **Schedule…** 會於目前編輯器上開啟排程表單，並顯示安全的驗證預覽。取消後會返回同一個 draft。若 inline snapshot 含有 credentials 或其他已分類敏感值，Server 會在儲存排程前拒絕；請將這些值保留在 package-managed configuration。

共用 React shell 會跟隨作業系統的淺色或深色偏好，直到你自行選擇主題；選擇會保存在瀏覽器儲存空間。按 `/` 或 `Ctrl K`/`⌘ K` 搜尋頁面，按 `?` 開啟快捷鍵清單，按 `Esc` 關閉對話框或手機導覽。在 Advanced Load 頁面，按 `Ctrl Enter`/`⌘ Enter` 只會啟動仍有效且已驗證的 draft，並保留既有確認步驟。主控台和 Schedules 編輯器會適應平板及手機寬度，保留清晰的鍵盤焦點，尊重減少動態效果設定，並以文字標籤配合狀態顏色。

第一層 Configuration 工作區位於 `#/configuration`，在 **All packages** 下也可直接使用。Source、Declared、Effective 和 Compare 透過全域已驗證 API 檢視共用 Runtime config；Environment 只切換 effective values，不切換來源樹，也不會回退至任何 Package。只有 Testdata 顯示獨立的 Package selector，並在該 Package 內解析 records。舊有 `#/configuration/{packageId}` deep link 保留，但共用檢視忽略其 Package context。檔案遍歷、source-reference 授權、source policy、遮蔽及 admin-field 限制保持不變；無效或不可用設定顯示明確狀態。

Declared 和 Engine effective 檢視使用共用 Runtime 的 `FrameworkConfigLoader` 選擇規則，不會連接 DB、MQ、HTTP 或 SSH。可直接檢視 Runtime 預設值，無需選擇 profile。Compare 預設顯示 environment-specific 值，並在分頁前套用所選 section；**Include inherited/global fields** 可選擇顯示 shared values 及 provenance。Configuration admin 可選擇 **Show additional DB configuration fields** 檢視其他非秘密 DB properties 及 evidence fields。Container-auth deployment 使用 `server.inspection.configurationAdminRole`（預設 `ATT_ADMIN`）；local-auth deployment 需要本地 `ADMIN`。Credentials 及設定的敏感值仍會隱藏。Hidden 和 missing 狀態會保持區分；UI 不會比較 hidden 值。Tool 詳細資料會連結至其設定 section。公開 endpoint 及分頁參數見 [ATT Server 安全與身份](../../operations/security.zh.md)。

Source 和 preview 共用 lazy-loaded CodeMirror 編輯器，支援 YAML、JSON、XML、SQL、Shell 及純文字。它會標示 ATT 的 `${...}`、`#{...}`、`@{...}` 和 `&{...}` expression。只有帶有 Server 簽發不透明 token 的檔案參照才會連結回 Configuration。Server 將 token 綁定 package、origin 及 target，並在回傳文字前重新驗證參照；UI 不會根據客戶端解析的路徑建立連結。編輯器工具列支援複製、搜尋、跳至行號、全螢幕及安全格式化，不會執行程式碼。Debug sidecar 的 scalar 欄位仍以表單為主，巢狀值可用 Advanced JSON 編輯。Threshold 和 Evidence 提供表單及 Advanced JSON；無效 JSON 會保留在草稿中，不會取代最後有效設定。

開啟 **Schedules** 時，首先顯示已儲存的 RUN／LOAD 定義清單，不會顯示新增或編輯表單。可搜尋名稱、target、package、environment、owner 或 cron，並按執行類型、狀態、package 及 API 提供的 owner 篩選。清單顯示類型、target、package／environment、時區、recurrence、next run 及狀態；只有 API 授權回傳的 owner／近期狀態才會顯示。

選取 **New Schedule**（`#/schedules/new`）或 **Edit**（`#/schedules/<id>/edit`）開啟獨立表單。RUN 必須先在 **Run** 驗證 Case、Template、Flow、Tool 或 typed batch selection，再從 Review 選擇 **Schedule…**。已驗證的 Quick／Advanced／YAML LOAD 使用相同排程 drawer，保留背景來源編輯器及 Server draft ID，因此不會重建或改寫 typed inputs、Case 選取、Debug mode 或 LOAD policies。儲存只建立定義，不會立即執行；新排程預設暫停，啟用前請檢查 next-occurrences preview。LOAD 亦可使用現有 package-relative scenario path。

**Cancel** 及 **Back to Schedules** 會保護未儲存變更；重新整理或關閉分頁時會提示，切換分頁則保留記憶體內表單。Draft 與憑證不會寫入 browser storage。重新整理無法還原尚未儲存的已驗證 draft，必須返回來源重新驗證。Edit／History deep link 會重新讀取 Server 定義，舊 `#/load-schedules` 及 `#/scheduled-load` 連結仍可使用。

**History** 使用 `#/schedules/<id>/history`，occurrence 連結使用 `#/schedules/<id>/history/<occurrenceId>`，範圍為最近 100 筆保留紀錄。超出範圍或已移除的紀錄會顯示不可用狀態。只有 Server 提供可用 job status 時才顯示 Job 連結，Job／Report 授權仍由 API 檢查。**Run Now**、**Pause/Resume** 及 **Delete** 均須確認；手動觸發 LOAD 仍建立 LOAD Job。舊 Scheduled Debug 顯示為 RUN 加 Debug mode，透過 adapter 保留後端識別。Misfire、retention、capacity、revision 及 DST 政策仍由既有統一 Scheduler 管理。

Advanced Load route 使用有型別的控制項設定 duration 單位、Virtual Users、Arrival Rate、最大並行數及 think time。Target sidecar 預設值會為 `inputs`、`vars` 及 Tool `arguments` 提供有型別 scalar 控制項；巢狀值保留在 Advanced JSON。表單會按 model 顯示適用限制，但驗證、policy 上限、draft 過期、package revision 及執行仍以 Server 為準。確認後，UI 會提交同一份由 Server 簽發的 draft；回應已遮蔽時不會提供預覽複製或匯出。

Jobs 在 Server 對完整 matching history 執行 filter、Target 排序／分組及真正分頁。Target 連結開啟指定 Job Details；每列保留 Package/Environment、可複製的縮短 Job ID、Schedule principal、Created/Started/Finished/elapsed。Recent Jobs 預設最新優先。Rows per page 支援 25/50/100（初次預設 100，保存在瀏覽器）。Pin/Delete 圖示立即生效。兩個 footer 選單對當頁所有 Job（包括收合群組）設定 pin/unpin 或 mark/unmark；逐 ID 結果解釋部分失敗。Deletion mark 在至少 30 秒後的背景 claim 前可取消；pinned、active、busy、quarantined Job 仍受保護。Job Details 保留 Overview、Live Output、Events、Results、Artifacts、Metrics；取消執行仍需確認。

Explorer 是 React 與 TypeScript 單頁 UI，於 `att-web` Maven resource 階段使用 Vite 建置。Maven 會把產生的靜態資源複製至 `META-INF/resources/ui/`；Server WAR 再將它們封裝供 Tomcat 提供。Node.js 只用於建置階段。部署後的 UI 呼叫同源公開 REST API，無需額外 Node 程序或前端服務。
