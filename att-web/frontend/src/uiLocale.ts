export type UiLocale = 'en' | 'zh-Hant';
export type UiLocalePreference = 'browser' | UiLocale;

const translations: Record<string, string> = {
  'Skip to main content': '跳至主要內容',
  'Close navigation': '關閉導覽',
  'Expand navigation': '展開導覽',
  'Collapse navigation': '收合導覽',
  'ATT Studio home': 'ATT Studio 首頁',
  'Application sidebar': '應用程式側邊欄',
  'Primary navigation': '主要導覽',
  Workspace: '工作區', Dashboard: '儀表板', Packages: '套件',
  'Explorer and Run': '探索與執行', Configuration: '設定檢視',
  'Load testing': '負載測試', 'Jobs and results': '工作與結果',
  'Scheduled Load': '排程負載', Settings: '設定', Server: '伺服器',
  'Search pages': '搜尋頁面', 'Keyboard shortcuts': '鍵盤快速鍵',
  'Switch to light theme': '切換至淺色主題', 'Switch to dark theme': '切換至深色主題',
  'Connected': '已連線', 'Unavailable': '無法使用', 'Connecting…': '連線中…',
  'Loading identity…': '正在載入身分…', 'Loading authenticated identity': '正在載入已驗證的身分',
  Breadcrumb: '導覽路徑', 'Go to': '前往', 'Close dialog': '關閉對話方塊',
  'Search pages…': '搜尋頁面…', Pages: '頁面', 'No matching pages.': '沒有符合的頁面。',
  'Workspace overview': '工作區總覽', 'Configured packages': '已設定的套件',
  'Browse package resources': '瀏覽套件資源', 'Inspect package configuration and Testdata': '檢視套件設定與 Testdata',
  'Create a validated Load job': '建立已驗證的負載工作', 'Inspect recent jobs': '檢視近期工作',
  'Manage recurring Load schedules': '管理重複負載排程', 'Appearance and keyboard help': '外觀與鍵盤說明',
  'Keyboard shortcuts (?)': '鍵盤快速鍵 (?)',
  'Dashboard summary': '儀表板摘要', 'Available to this Server': '此伺服器可用',
  'Active jobs in results': '結果中的執行中工作', 'Waiting for recent jobs': '等待近期工作',
  'Latest job': '最新工作', 'From the latest 100 job rows returned by the Server': '來自伺服器回傳的最近 100 筆工作',
  'Server status': '伺服器狀態', 'ATT API v1': 'ATT API v1',
  'Monitor recent ATT activity and open a package workspace.': '查看 ATT 近期活動並開啟套件工作區。',
  'Open a configured package workspace.': '開啟已設定的套件工作區。',
  'Review jobs returned by the ATT Server API.': '檢視 ATT Server API 回傳的工作。',
  'Open a package to inspect resources and run tests.': '開啟套件以檢視資源及執行測試。',
  'Retry': '重試', 'Refresh': '重新整理', 'Refreshing…': '正在重新整理…',
  'Loading configured packages…': '正在載入已設定的套件…',
  'Could not load packages:': '無法載入套件：', 'No packages are configured yet.': '尚未設定任何套件。',
  'configured packages are available.': '個已設定的套件可用。',
  'Package workspace': '套件工作區', 'Open package': '開啟套件',
  'Recent activity': '近期活動', 'Latest job rows returned by this Server.': '此伺服器回傳的最新工作資料。',
  'Updated': '更新時間', 'Loading recent jobs…': '正在載入近期工作…',
  'Could not load recent jobs:': '無法載入近期工作：', 'No jobs yet.': '目前沒有工作。',
  'recent job rows returned.': '筆近期工作已回傳。', 'Recent jobs': '近期工作',
  'From ': '來自 ', 'Job ': '工作 ', 'Unknown': '未知',
  'Package Explorer': '套件探索器', 'Quick Load': '快速負載',
  'Advanced Load': '進階負載',
  'ATT Studio': 'ATT Studio', 'ATT Server': 'ATT Server', 'No jobs yet': '目前沒有工作',
  'Quick Load supports one Template, Flow, or Tool target. This route has an unsupported target type.': '快速負載只支援一個 Template、Flow 或 Tool 目標。此路由的目標類型不受支援。',
  'Loading Server Load policy…': '正在載入伺服器負載政策…',
  'Loading safe policy defaults…': '正在載入安全的政策預設值…',
  'Browser-created Load drafts are disabled by Server configuration.': '伺服器設定已停用由瀏覽器建立負載草稿。',
  'Safe model-specific policy defaults loaded. Sensitive values remain protected.': '已載入此模型的安全政策預設值。敏感值仍受到保護。',
  'The selected model’s package policy or Server fallback is loaded. Validate before running.': '已載入所選模型的套件政策或伺服器預設值。執行前請先驗證。',
  'Load policy is unavailable.': '負載政策無法使用。',
  'Safe target defaults loaded. Sensitive values remain protected by the Server.': '已載入安全的目標預設值。伺服器會保護敏感值。',
  'Target defaults loaded from the package debug sidecar.': '已從套件 Debug sidecar 載入目標預設值。',
  'No safe debug sidecar defaults were available. You can add inputs manually.': '沒有可用的安全 Debug sidecar 預設值。您可以手動新增輸入。',
  'Validate this single target before running it.': '執行前請先驗證此單一目標。',
  'The scenario changed. Validate it again before running or scheduling.': '情境已變更。執行或排程前請重新驗證。',
  'Quick Load draft': '快速負載草稿', 'Correct the invalid target inputs before validation.': '驗證前請修正無效的目標輸入。',
  'Wait for the Server to resolve this resource before validating.': '請等待伺服器解析此資源後再驗證。',
  'Quick Load validation failed.': '快速負載驗證失敗。', 'Correct the fields or retry validation.': '請修正欄位或重試驗證。',
  'Run this validated single-target Quick Load now?': '現在要執行這個已驗證的單一目標快速負載嗎？',
  'Single-target workload': '單一目標工作負載',
  'Run a focused load check against one selected resource. Advanced Load supports multi-workload scenarios.': '對所選資源執行單一負載檢查。進階負載支援多工作負載情境。',
  'Back to Explorer': '返回探索器', 'Schedule…': '排程…', 'Target': '目標',
  'The selected resource is fixed for this Quick Load.': '此快速負載會固定使用所選資源。',
  'Load template / preset': '負載範本／預設值', 'Load template or preset': '負載範本或預設值',
  'Model default': '模型預設值', 'Load model': '負載模型', 'Virtual Users': '虛擬使用者',
  'Arrival Rate': '到達率', 'Environment': '環境', 'Package default': '套件預設值',
  'Users': '使用者數', 'Target TPS': '目標 TPS', 'Maximum concurrent': '最大並行數',
  'Duration': '持續時間', 'Inputs and variables': '輸入與變數',
  'Values from the safe debug sidecar stay editable as ATT-authored literals.': '安全 Debug sidecar 的值仍可作為 ATT 字面值編輯。',
  'Reset inputs to sidecar defaults': '重設輸入為 sidecar 預設值',
  'Advanced Settings': '進階設定', 'Warm-up': '暖機', 'Ramp-up': '升載', 'Ramp-down': '降載',
  'Thresholds JSON': 'Thresholds JSON', 'Evidence settings JSON': 'Evidence 設定 JSON',
  'Execution defaults JSON': '執行預設值 JSON', 'Testdata paths JSON': 'Testdata 路徑 JSON',
  'Complete scenario JSON preview': '完整情境 JSON 預覽', 'Validate and run': '驗證及執行',
  'Changing a preset or model never starts a job.': '變更預設值或模型不會啟動工作。',
  'Validate': '驗證', 'Run Now': '立即執行', 'Starting…': '正在啟動…',
  'The Server returned validation diagnostics. Review them before running.': '伺服器回傳驗證診斷。執行前請先檢視。',
  'The Server accepted this draft for review.': '伺服器已接受此草稿供檢視。',
  'Scheduled Load is disabled by Server configuration.': '伺服器設定已停用排程負載。',
  'Validate the scenario before scheduling.': '排程前請先驗證情境。',
  'Quick Load is disabled by Server policy.': '伺服器政策已停用快速負載。',
  'Correct the Quick Load fields.': '請修正快速負載欄位。',
  'Quick Load could not be started.': '無法啟動快速負載。',
  'The validated draft could not be submitted. Retry validation if it expired.': '無法提交已驗證的草稿。若草稿已逾期，請重新驗證。',
  'Scheduling creates a recurring job from this validated immutable scenario snapshot.': '排程會根據此已驗證且不可變更的情境快照建立重複工作。',
  'Request ID:': '請求 ID：', 'Filter event type': '篩選事件類型', 'All event types': '所有事件類型', 'All Events': '所有事件',
  'Search events': '搜尋事件', 'Search event text': '搜尋事件內容', 'Auto-scroll': '自動捲動',
  'Pause auto-scroll': '暫停自動捲動', 'Resume auto-scroll': '繼續自動捲動',
  'Copy visible events': '複製目前顯示的事件', 'No events match the current filters.': '沒有符合目前篩選條件的事件。',
  'No SSE events have been returned.': '尚未收到 SSE 事件。', 'Events': '事件', 'Live logs': '即時記錄', 'Live events': '即時事件',
  'Connected; no events have been emitted yet.': '已連線；目前尚未產生事件。',
  'Reconnecting to live events; no recent events are available.': '正在重新連線至即時事件；目前沒有近期事件。',
  'Results': '結果', 'Artifacts': '產物', 'Metrics': '指標', 'Overview': '總覽',
  'Event controls': '事件控制', 'Chronological job events': '依時間排序的工作事件',
  'Clipboard access is unavailable.': '無法使用剪貼簿。', 'events copied.': '個事件已複製。',
  'Appearance': '外觀', 'Choose how ATT Studio displays colors and contrast.': '選擇 ATT Studio 顯示色彩和對比的方式。',
  'Use these shortcuts from any page. Text-entry fields keep their normal typing behavior.': '您可以在任何頁面使用這些快速鍵。文字輸入欄位會保留一般輸入行為。',
  'Theme preference:': '主題偏好：', 'Color theme preference': '色彩主題偏好',
  'The selected preference is stored in this browser. Selecting System removes the saved override.': '此偏好會儲存在這個瀏覽器。選擇「系統」會移除已儲存的覆寫設定。',
  'System': '系統', 'Light': '淺色', 'Dark': '深色',
  'Follow the appearance preference set by your operating system.': '依照作業系統的外觀偏好設定。',
  'Use the light color theme on this browser.': '在此瀏覽器使用淺色主題。',
  'Use the dark color theme on this browser.': '在此瀏覽器使用深色主題。',
  'Language': '語言', 'Choose the language used throughout ATT Studio.': '選擇 ATT Studio 使用的語言。',
  'Browser default': '瀏覽器預設', 'English': 'English', 'Traditional Chinese': '繁體中文',
  'Follow this browser’s language unless you choose a language below.': '除非您在下方選擇語言，否則會依照此瀏覽器的語言。',
  'Start a validated Load draft': '啟動已驗證的負載草稿',
  'Return to Explorer': '返回探索器', 'Configuration view': '設定檢視',
  'Configuration file tree': '設定檔案樹', 'Configuration file search results': '設定檔案搜尋結果',
  'No matching files.': '沒有符合的檔案。', 'Loading configuration files…': '正在載入設定檔…',
  'No configuration files were returned.': '沒有回傳設定檔。', 'Empty directory': '空資料夾',
  'Load more entries': '載入更多項目', 'This directory reached its listing cap. Search by path to find more.': '此資料夾已達清單上限。請以路徑搜尋更多項目。',
  'Source': '來源', 'Declared': '宣告值', 'Effective': '生效值', 'Compare': '比較', 'Testdata': 'Testdata',
  'Loading safe configuration source…': '正在載入安全的設定來源…',
  'Package resources and Run actions are available from this route.': '此路由可瀏覽套件資源並執行 Run 動作。',
  'Resource route ready': '資源路由已就緒', 'Explorer route ready': '探索器路由已就緒',
  'Browser-submitted Load is disabled by Server configuration.': '伺服器設定已停用由瀏覽器提交的負載。',
  'Select a package': '選取套件', 'Choose a configured package to build a Load scenario.': '選取已設定的套件以建立負載情境。',
};

Object.assign(translations, {
  // Shared shell, navigation, dialogs, and assistive labels.
  'Go to': '前往', 'Open page search': '開啟頁面搜尋', 'Open this help': '開啟此說明',
  'Close a dialog or menu': '關閉對話方塊或選單', 'Close a dialog or mobile navigation': '關閉對話方塊或行動版導覽',
  'Search pages, shortcut Control or Command K or slash': '搜尋頁面，快速鍵為 Control 或 Command K，或斜線',
  'Search and open a page': '搜尋並開啟頁面', 'Show shortcut help': '顯示快速鍵說明',
  'Unsaved work': '未儲存的工作', 'Discard your changes?': '要捨棄變更嗎？',
  'Stay on this page': '留在此頁', 'Discard changes and continue': '捨棄變更並繼續',
  'Close history': '關閉記錄', 'Open navigation': '開啟導覽', 'Close navigation or mobile navigation': '關閉導覽或行動版導覽',
  'Package': '套件', 'All packages': '所有套件', 'Per run': '每次執行設定',
  'Environment': '環境', 'Identity': '身分', 'Server managed': '由伺服器管理', 'not exposed by API': 'API 不會公開',
  'Keyboard shortcuts': '鍵盤快速鍵', 'Search pages': '搜尋頁面',
  'Start a validated Load draft': '啟動已驗證的負載草稿',
  'The slash and help shortcuts do not intercept text fields. The page-search key combination works from any field; the Load submit shortcut is available only on Advanced Load and always asks for confirmation.': '斜線和說明快速鍵不會攔截文字欄位輸入。您可以在任何欄位使用頁面搜尋組合鍵；負載提交快速鍵僅適用於進階負載，且一定會要求確認。',
  'Open account menu': '開啟帳戶選單', 'Current user': '目前使用者',

  // Resource Explorer, safe source, Debug, and configuration inspection.
  'Package Explorer': '套件探索器', 'Packages /': '套件 /', ' / Explorer': ' / 探索器',
  'Package resources and counts are independent of runtime environment. Select an environment only when inspecting or running a resource.': '套件資源與數量不受執行環境影響。只有在檢視或執行資源時才需要選取環境。',
  'Resources': '資源', 'Search resources': '搜尋資源', 'Select a resource': '選取資源',
  'Choose a Case, Template, Flow, or Tool from the resource list.': '從資源清單選取 Case、Template、Flow 或 Tool。',
  'Invalid resource': '無效資源', 'Loading resource detail…': '正在載入資源詳細資料…',
  'Definition': '定義', 'Dependencies': '相依項目', 'No definition projection is available.': '沒有可用的定義檢視資料。',
  'References': '參照', 'Referenced by': '被參照項目', 'None': '無', 'Inspector': '檢視器',
  'Select a resource to view its metadata and actions.': '選取資源以檢視中繼資料和動作。',
  'Logical ID': '邏輯 ID', 'State': '狀態', 'Tags': '標籤', 'Anonymous job label (optional)': '匿名工作標籤（選填）',
  'Run selected case': '執行所選 Case', 'Close Debug': '關閉 Debug', 'Debug this target': '對此目標執行 Debug',
  'Open in Configuration': '在設定檢視中開啟', 'Debug / Test Runner': 'Debug／測試執行器',
  'Reset defaults': '重設為預設值', 'Validating…': '正在驗證…', 'Validate and preview': '驗證並預覽',
  'Scheduling keeps this validated Debug target, environment and input snapshot. Protected package defaults remain server-managed and are not saved as schedule values.': '排程會保留此已驗證的 Debug 目標、環境和輸入快照。受保護的套件預設值仍由伺服器管理，不會儲存為排程值。',
  'Safe effective Debug preview': '安全的生效 Debug 預覽', 'Refresh source': '重新整理來源',
  'Load source': '載入來源', 'Retry source': '重試載入來源',
  'Package runtime configuration': '套件執行階段設定',
  'Inspect safe package source and resolved values. This workspace is read-only.': '檢視安全的套件來源與解析後的值。此工作區為唯讀。',
  'Choose a package': '選取套件', 'Configuration is scoped to a registered package.': '設定範圍限於已註冊的套件。',
  'No registered packages are available.': '沒有可用的已註冊套件。', 'Configuration files': '設定檔',
  'Search files': '搜尋檔案', 'Search paths': '搜尋路徑', 'Collapse all': '全部收合',
  'Only files and folders under the registered package config root are listed.': '清單只會顯示已註冊套件設定根目錄下的檔案和資料夾。',
  'Configuration inspection': '設定檢視', 'Configuration inspector': '設定檢視器',
  'Loading safe configuration source…': '正在載入安全的設定來源…', 'Refresh file list': '重新整理檔案清單',
  'Source is not available': '來源無法使用', 'The Server withheld or could not read this file. Its path and safe metadata remain visible.': '伺服器隱藏或無法讀取此檔案，但仍會顯示其路徑和安全中繼資料。',
  'The Server did not authorize any referenced files for navigation from this source.': '伺服器未授權從此來源導覽至任何參照檔案。',
  'Select a file': '選取檔案', 'Choose a configuration file to inspect its safe source.': '選取設定檔以檢視安全來源。',
  'Select a file to inspect its safe metadata, status, and references.': '選取檔案以檢視安全中繼資料、狀態和參照。',
  'Safe source available': '安全來源可用', 'Unavailable by Server policy': '伺服器政策禁止使用',
  'Related resources': '相關資源', 'Copy Testdata value': '複製 Testdata 值', 'Inspect nested value': '檢視巢狀值',
  'Structured Testdata catalog': '結構化 Testdata 目錄', 'Resolved descriptor metadata and bounded records for': '已解析的描述項中繼資料及有限筆數的記錄：',
  'Search descriptors': '搜尋描述項', 'Search Testdata descriptors': '搜尋 Testdata 描述項',
  'Search descriptor IDs and sources': '搜尋描述項 ID 和來源', 'Catalog': '目錄',
  'No descriptors match this search.': '沒有描述項符合此搜尋。', 'Descriptor': '描述項',
  'Generated': '已產生', 'Records': '記錄', 'Selection': '選取方式', 'Exhaustion': '耗盡處理方式',
  'Environment origin': '環境來源', 'Server projection': '伺服器投影資料', 'Generated Testdata': '產生的 Testdata',
  'This descriptor creates records at runtime. Previewing generated records is bounded and starts only when you request it.': '此描述項會在執行階段建立記錄。產生記錄的預覽有筆數上限，且只會在您要求時開始。',
  'Sequence start': '序列起點', 'Sequence end': '序列終點', 'Record template': '記錄範本',
  'Preview first 50 generated records': '預覽前 50 筆產生的記錄',
  'Generated records are not loaded automatically. Choose': '不會自動載入產生的記錄。請選取',
  'to request a bounded preview.': '以要求有限筆數的預覽。',
  'Fields observed in this page': '此頁面觀察到的欄位',
  'Requiredness and authored descriptions are not provided by this record preview.': '此記錄預覽不會提供欄位必填狀態或原始描述。',
  'Observed type': '觀察到的型別', 'Visibility': '可見性', 'Actions': '動作', 'Some fields redacted': '部分欄位已遮蔽',
  'Visible by Server policy': '依伺服器政策顯示', 'Copy row': '複製資料列', 'Load next 50 records': '載入接下來 50 筆記錄',
  'Advanced descriptor metadata': '進階描述項中繼資料', 'No Testdata descriptors were returned for the selected environment.': '所選環境沒有回傳 Testdata 描述項。',
  'Show additional DB configuration fields': '顯示其他資料庫設定欄位',
  'Declared global fields': '已宣告的全域欄位', 'Declared global configuration': '已宣告的全域設定',
  'No declared global configuration was returned.': '沒有回傳已宣告的全域設定。',
  'Effective configuration fields': '生效的設定欄位', 'No effective fields match this filter.': '沒有生效欄位符合此篩選條件。',
  'Configuration profile comparison': '設定檔比較', 'No comparison fields match this filter.': '沒有比較欄位符合此篩選條件。',
  'No environment-specific differences. Turn on “Include inherited/global fields” to inspect shared values.': '環境之間沒有差異。開啟「包含繼承／全域欄位」以檢視共用值。',
  'Environment profiles': '環境設定檔', 'No active environment profiles; package defaults apply.': '沒有啟用中的環境設定檔；套用套件預設值。',
  'Global fields': '全域欄位', 'Global fields (optional)': '全域欄位（選填）',
  'Left environment': '左側環境', 'Right environment': '右側環境', 'Filter fields': '篩選欄位',

  // Load, Quick Load, jobs, results, and events.
  'Quick Load': '快速負載', 'Back to Explorer': '返回探索器', 'Single-target workload': '單一目標工作負載',
  'Run a focused load check against one selected resource. Advanced Load supports multi-workload scenarios.': '針對所選資源進行單一負載檢查。進階負載支援多工作負載情境。',
  'The selected resource is fixed for this Quick Load.': '此快速負載會固定使用所選資源。',
  'Load template / preset': '負載範本／預設值', 'Model default': '模型預設值',
  'Virtual Users': '虛擬使用者', 'Arrival Rate': '到達率', 'bundled': '內建',
  'Selecting a template loads its supported settings into this single-target draft. It never starts a job; validate before running.': '選取範本會將支援的設定載入此單一目標草稿。這不會啟動工作；執行前請先驗證。',
  'The package template catalog reached its display limit.': '套件範本目錄已達顯示上限。',
  'Load model': '負載模型', 'Target': '目標', 'Duration': '持續時間', 'Inputs and variables': '輸入與變數',
  'Advanced scenario settings': '進階情境設定', 'Advanced workload settings': '進階工作負載設定',
  'Advanced raw duration': '進階原始持續時間', 'Advanced raw rate': '進階原始速率',
  'Changing a preset or model never starts a job.': '變更預設值或模型不會啟動工作。',
  'Validate scenario': '驗證情境', 'Validate and run': '驗證並執行', 'Return to Load': '返回負載設定',
  'Build a shared timing plan, configure workloads, then validate the complete scenario on the Server.': '建立共用時間規劃、設定工作負載，再於伺服器驗證完整情境。',
  'Load a policy to begin; the Server defaults will populate the first workload.': '載入政策以開始；伺服器預設值會填入第一個工作負載。',
  'Shared timing envelope': '共用時間範圍', 'Every workload shares the selected model and timing envelope.': '所有工作負載都會使用所選模型和時間範圍。',
  'Add workload': '新增工作負載', 'Add mix target': '新增混合目標', 'Remove workload': '移除工作負載',
  'Remove mix target': '移除混合目標', 'Add threshold': '新增門檻', 'Add field': '新增欄位', 'Add a value': '新增值',
  'Load scenario sections': '負載情境區段', 'Thresholds and evidence': '門檻與證據',
  'Complete evidence settings object': '完整證據設定物件', 'Threshold scope': '門檻範圍',
  'Per-workload': '每個工作負載', 'Aggregate run': '整體執行', 'Advanced JSON': '進階 JSON',
  'Current complete scenario JSON': '目前完整情境 JSON', 'Complete scenario JSON preview': '完整情境 JSON 預覽',
  'Reset inputs to sidecar defaults': '重設輸入為 sidecar 預設值',
  'Values from the safe debug sidecar stay editable as ATT-authored literals.': '安全 Debug sidecar 中的值仍可作為 ATT 編寫的字面值編輯。',
  'Load policy is unavailable.': '負載政策無法使用。', 'The Server returned validation diagnostics. Review them before running.': '伺服器回傳驗證診斷。執行前請先檢視。',
  'The Server accepted this draft for review.': '伺服器已接受此草稿供檢視。', 'Confirm and run this validated Load scenario?': '要確認並執行此已驗證的負載情境嗎？',
  'Job filters': '工作篩選條件', 'All loaded statuses': '所有已載入狀態', 'All loaded packages': '所有已載入套件',
  'All loaded types': '所有已載入類型', 'No matching jobs': '沒有符合的工作', 'Loaded jobs pages': '已載入的工作頁面',
  'Jobs returned by the Server; most recently created first by default': '伺服器回傳的工作；預設依建立時間由新至舊排序',
  'Loading jobs…': '正在載入工作…', 'Refreshing…': '正在重新整理…', 'Refresh jobs': '重新整理工作',
  'Jobs unavailable': '工作無法使用', 'Jobs could not be loaded.': '無法載入工作。', 'Job details are unavailable.': '工作詳細資料無法使用。',
  'Job ID': '工作 ID', 'Job ID A–Z': '工作 ID A–Z', 'Created': '建立時間', 'Created from': '開始時間', 'Created to': '結束時間',
  'Open details': '開啟詳細資料', 'Try again': '再試一次', 'Cancellation request sent; waiting for the Server status update.': '已送出取消要求，正在等待伺服器更新狀態。',
  'Cancel and return to editor': '取消並返回編輯器', 'Cancel edit': '取消編輯', 'Cancel confirmation is required': '需要確認取消',
  'Server status, live events, results, artifacts and reported metrics.': '伺服器狀態、即時事件、結果、產物和回報指標。',
  'Loading artifacts…': '正在載入產物…', 'No artifacts were returned for this job.': '此工作沒有回傳產物。',
  'Loading result…': '正在載入結果…', 'Loading…': '正在載入…', 'Refreshing result…': '正在重新整理結果…',
  'The API did not return metrics for this job.': 'API 沒有回傳此工作的指標。',
  'No time-series buckets were returned. Summary metrics are shown in the table above.': '沒有回傳時間序列區間。摘要指標顯示於上方表格。',
  'No {title} were returned.': '沒有回傳 {title}。', 'Load result metrics': '負載結果指標', 'Load time-series buckets': '負載時間序列區間',
  'Returned Load time-series buckets': '回傳的負載時間序列區間', 'Latest live log events': '最新即時記錄事件',
  'Sample successes and failures': '成功與失敗樣本', 'Server timings': '伺服器時間資料', 'Worker metrics': 'Worker 指標',
  'Raw result payload': '原始結果內容', 'Skip resource-output snapshots': '略過資源輸出快照',
  'Only include when evidence is retained': '只在保留證據時包含', 'Maximum retained samples': '保留樣本數上限',
  'Success sample rate': '成功樣本率', 'Failed iterations': '失敗迭代', 'Successful iterations': '成功迭代',
  'No SSE events have been returned.': '尚未收到 SSE 事件。', 'All event types': '所有事件類型',
  'Search events': '搜尋事件', 'Search event text': '搜尋事件內容', 'Auto-scroll': '自動捲動',
  'Pause auto-scroll': '暫停自動捲動', 'Resume auto-scroll': '繼續自動捲動', 'Copy visible events': '複製目前顯示的事件',
  'Chronological job events': '依時間排序的工作事件', 'Request ID:': '請求 ID：',

  // Schedules and recurring Load/Debug jobs.
  'Manage recurring Load and Debug jobs and inspect the Server\'s recorded occurrences.': '管理重複的 Load 和 Debug 工作，並檢視伺服器記錄的執行次數。',
  'Schedules': '排程', 'Schedule list': '排程清單', 'Create schedule': '建立排程', 'Refresh schedules': '重新整理排程',
  'Retry schedule list': '重試載入排程清單', 'Filter schedules': '篩選排程', 'Search schedules': '搜尋排程',
  'No schedules have been created. Use Create schedule to add the first one.': '尚未建立排程。請使用「建立排程」新增第一個排程。',
  'No schedules match these filters.': '沒有排程符合這些篩選條件。', 'Schedule form loaded from the validated': '排程表單已從已驗證的',
  'draft. Choose a recurrence and review the Server next-run preview.': '草稿載入。請選取重複規則並檢視伺服器提供的下次執行預覽。',
  'Schedule preview': '排程預覽', 'Loading occurrence history': '正在載入執行記錄', 'Loading occurrence history…': '正在載入執行記錄…',
  'Occurrence history': '執行記錄', 'No executions have been recorded for this schedule.': '此排程尚無執行記錄。',
  'Editing': '正在編輯', 'Enable after saving': '儲存後啟用', 'Enabled': '已啟用', 'Paused': '已暫停',
  'Run now': '立即執行', 'Run Now recorded as': '立即執行已記錄為', 'Deleted': '已刪除',
  'Any already accepted jobs continue running.': '已接受的工作會繼續執行。', 'Showing the latest 100 occurrences for': '顯示最新 100 次執行記錄：',
  'Latest 100 occurrences. Skips and submission failures remain visible even when no job was created.': '最新 100 次執行記錄。即使沒有建立工作，略過和提交失敗也會保留顯示。',
  'Times are displayed in each schedule\'s timezone.': '時間會依各排程的時區顯示。',
  'Next five scheduled occurrences': '接下來五次排程執行', 'Next-occurrences preview': '後續執行預覽', 'Next run': '下次執行',
  'When this limit or the Server\'s global capacity is reached, that occurrence is recorded as skipped.': '達到此上限或伺服器全域容量時，該次執行會記錄為略過。',
  'Cron': 'Cron', 'Cron · minute hour day-of-month month day-of-week': 'Cron · 分鐘 小時 月中日期 月份 星期',
  'Daily': '每日', 'Weekly': '每週', 'Recurrence': '重複規則', 'Timezone · IANA': '時區 · IANA',
  'Enter a valid local time before previewing this schedule.': '預覽此排程前，請輸入有效的本地時間。',
  'Environment profiles are unavailable; the package default remains selectable.': '環境設定檔無法使用；仍可選取套件預設值。',
  'Validation details': '驗證詳細資料', 'The Server rejected this schedule input.': '伺服器拒絕此排程輸入。',
  'Field:': '欄位：', 'Location:': '位置：', 'Package / environment:': '套件／環境：',
  'Resource:': '資源：', 'Targets:': '目標：', 'Suggestion:': '建議：', 'Review field': '檢視欄位',

  // Code viewer and common structured controls.
  'Copy': '複製', 'Find': '尋找', 'Go to line': '前往行', 'Go': '前往', 'Format': '格式化',
  'Loading syntax-aware viewer…': '正在載入語法檢視器…', 'The code viewer could not be loaded.': '無法載入程式碼檢視器。',
  'Code viewer unavailable.': '程式碼檢視器無法使用。', 'Export YAML': '匯出 YAML', 'Copy YAML': '複製 YAML',
  'Edit complete business input JSON, including nested values': '編輯完整業務輸入 JSON，包括巢狀值',
  'Choose type': '選擇型別', 'Text': '文字', 'Number': '數字', 'Boolean': '布林值', 'Null': '空值',
  'JSON object; final validation is performed by the Server.': 'JSON 物件；最終驗證由伺服器執行。',
  'JSON array of package-relative paths.': '套件相對路徑的 JSON 陣列。', 'Enter a JSON object.': '請輸入 JSON 物件。',
  'Hours': '小時', 'Minutes': '分鐘', 'Seconds': '秒', 'Milliseconds': '毫秒',

  // Common labels and states.
  'Name': '名稱', 'Description': '描述', 'Path': '路徑', 'Type': '類型', 'Size': '大小',
  'Source': '來源', 'Value': '值', 'Field': '欄位', 'Field name': '欄位名稱', 'Unit': '單位',
  'Status': '狀態', 'Reason': '原因', 'Origin': '來源', 'Example': '範例', 'Index': '索引', 'Sample': '樣本',
  'Condition': '條件', 'Invalid': '無效', 'Started': '已啟動', 'Finished': '已完成',
  'PASS': '通過', 'FAIL': '失敗', 'ERROR': '錯誤', 'INVALID': '無效', 'QUEUED': '排隊中',
  'PREPARING': '準備中', 'RUNNING': '執行中', 'CANCEL_REQUESTED': '已要求取消', 'CANCELLED': '已取消',
});

const localeKey = 'att-ui-language';

export function detectUiLocale(languages?: readonly string[]): UiLocale {
  const preferred = languages ?? (typeof navigator === 'undefined' ? [] : [...(navigator.languages || []), navigator.language]);
  return preferred.some(language => /^zh-(?:Hant|TW|HK|MO)(?:-|$)/i.test(language)) ? 'zh-Hant' : 'en';
}

export function readUiLocale(): UiLocale {
  try {
    const saved = localStorage.getItem(localeKey);
    if (saved === 'en' || saved === 'zh-Hant') return saved;
  } catch { /* Browser storage can be unavailable. */ }
  return detectUiLocale();
}

export function readUiLocalePreference(): UiLocalePreference {
  try {
    const saved = localStorage.getItem(localeKey);
    if (saved === 'en' || saved === 'zh-Hant') return saved;
  } catch { /* Browser storage can be unavailable. */ }
  return 'browser';
}

export function saveUiLocalePreference(preference: UiLocalePreference): void {
  try {
    if (preference === 'browser') localStorage.removeItem(localeKey);
    else localStorage.setItem(localeKey, preference);
  } catch { /* The preference remains active for this session. */ }
}

type TranslationState = { original: string; translated: string };
const translatedText = new WeakMap<Text, TranslationState>();
const translatedAttributes = new WeakMap<Element, Map<string, TranslationState>>();
let currentLocale: UiLocale = 'en';
let observer: MutationObserver | null = null;

function normalized(value: string): string { return value.replace(/\s+/g, ' ').trim(); }

function blockedTextContainer(element: Element | null): boolean {
  return !element || Boolean(element.closest('script,style,pre,code,textarea,[contenteditable="true"],.cm-editor,.cm-content,.att-code-fallback,.att-raw-result'));
}

function translateTextNode(node: Text, acceptChangedText: boolean): void {
  if (blockedTextContainer(node.parentElement)) return;
  const state = translatedText.get(node);
  const current = node.data;
  const original = state && (!acceptChangedText || current === state.translated) ? state.original : current;
  const originalText = normalized(original);
  const replacement = currentLocale === 'zh-Hant' ? translations[originalText] : undefined;
  const translated = replacement === undefined ? original
    : (original.match(/^\s*/)?.[0] || '') + replacement + (original.match(/\s*$/)?.[0] || '');
  translatedText.set(node, { original, translated });
  if (node.data !== translated) node.data = translated;
}

const translatedAttributesList = ['aria-label', 'aria-description', 'aria-valuetext', 'placeholder', 'title', 'alt'];
function translateElement(element: Element, acceptChangedAttributes: boolean): void {
  for (const name of translatedAttributesList) {
    const value = element.getAttribute(name);
    if (value === null) continue;
    let states = translatedAttributes.get(element);
    if (!states) { states = new Map(); translatedAttributes.set(element, states); }
    const state = states.get(name);
    const original = state && (!acceptChangedAttributes || value === state.translated) ? state.original : value;
    const translated = currentLocale === 'zh-Hant' ? translations[normalized(original)] ?? original : original;
    states.set(name, { original, translated });
    if (value !== translated) element.setAttribute(name, translated);
  }
}

function walkAndTranslate(node: Node, acceptChanged = false): void {
  if (node.nodeType === Node.TEXT_NODE) { translateTextNode(node as Text, acceptChanged); return; }
  if (node.nodeType !== Node.ELEMENT_NODE && node.nodeType !== Node.DOCUMENT_NODE) return;
  const element = node.nodeType === Node.ELEMENT_NODE ? node as Element : null;
  if (element && blockedTextContainer(element)) return;
  if (element) translateElement(element, acceptChanged);
  for (const child of Array.from(node.childNodes)) walkAndTranslate(child, acceptChanged);
}

export function applyUiLocale(locale: UiLocale): void {
  currentLocale = locale;
  if (typeof document === 'undefined') return;
  document.documentElement.lang = locale === 'zh-Hant' ? 'zh-Hant' : 'en';
  walkAndTranslate(document.documentElement);
  if (!observer && typeof MutationObserver !== 'undefined') {
    observer = new MutationObserver(records => {
      for (const record of records) {
        if (record.type === 'characterData') walkAndTranslate(record.target, true);
        else if (record.type === 'attributes') walkAndTranslate(record.target, true);
        else for (const node of Array.from(record.addedNodes)) walkAndTranslate(node);
      }
    });
    observer.observe(document.documentElement, {
      subtree: true, childList: true, characterData: true, characterDataOldValue: true,
      attributes: true, attributeOldValue: true, attributeFilter: translatedAttributesList,
    });
  }
}
