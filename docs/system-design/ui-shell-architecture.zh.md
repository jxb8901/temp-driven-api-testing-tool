# ATT Web UI Shell 架構決策

**狀態：** 已採納；Phase 1 已完成實作

**日期：** 2026-10-13

**範圍：** React 應用基礎，以及在現有 Server WAR 中以靜態資源發佈。

## 決策

Browser UI 使用 React、TypeScript 和 Vite 建置。唯一的 React mount 位於 `att-web/frontend/src/main.tsx`；`App.tsx` 管理路由與 API-backed app state，`AppShell.tsx` 管理固定 navigation 和 workspace context，feature pages 透過 typed routes 和注入的 callbacks 整合。`api.ts` 提供 `request<T>()`，使用 JSON、標準化 errors、`AbortSignal` 和相對路徑 `../api/v1/`。Hash routes 在 `/ui/` 和 `/att/ui/` 等非 root context 下都可使用。

Vite 將靜態輸出寫入 `att-web/frontend/dist`。Maven 將這些檔案複製至 `att-web.jar` 的 `META-INF/resources/ui`，再由現有 Server WAR 封裝及提供。Node.js 和 npm 只用於 build 與測試。Production 安裝仍只執行 Java 和 Apache Tomcat，UI 與 API 由 same origin 提供。

## 考慮過的方案

| 方案 | 優點 | 成本 | 決定 |
|---|---|---|---|
| React、TypeScript 和 Vite | Shared routes 和 API contracts 有型別，feature pages 可組合，支援 component tests | 增加固定的 build-time Node toolchain，並需要分階段遷移 | 採用 |
| HTML、CSS 和 vanilla JavaScript | 保留舊實作，無 compile step | 共享 shell 和 feature state 缺少大型 feature modules 所需的 component/type 邊界 | 僅保留作遷移 rollback artifact |
| Java server-rendered views | Server 控制 markup，毋須 browser framework | 要取代現有以 browser 和 REST 為基礎的 workflows | 暫緩 |

## State 和整合邊界

- `types.ts`、`api.ts`、`state.ts` 和 `router.ts` 是共用 TypeScript 基礎。Feature operations 可集中加入 `api.ts`；pages 不自行建構 API URLs。
- `ServerVersion.inlineLoadEnabled` 和 `loadSchedulingEnabled` 會保存在 server capability state，並傳入 shell 和 feature routes。API 不會提供目前 principal，因此 identity chip 明確顯示「Server managed」和「not exposed by API」。
- `AppRoute` 包含 dashboard、package list、package/resource、jobs/job、load/package、Scheduled Load 及 Settings。`useNavigationGuard()` 提供 `navigate()` 和 `registerUnsavedChanges()`；Shell 使用 keyboard-accessible discard dialog 確認內部或 browser-history navigation。
- Shell context 會提供 package、environment 和 accepted-job callbacks，讓後續 modules 由 app root 管理狀態。
- Dashboard summary 和 lists 只使用 `/version`、`/packages` 和 `/jobs` responses。數量只描述回傳 rows；API error 不會顯示為零。
- 外部 CSS 和 scripts 保留 self-only Content Security Policy。所有 requests 都是 same-origin。

## Build 和 size budget

Frontend dependencies 版本固定於 `package.json` 和 `package-lock.json`。Development 和 CI 會執行 `npm ci`、型別檢查、component tests 和 Vite production build。Java 17 CI job 安裝固定版本 Node.js 22.22.2；Java 8 compatibility job 不需要 Node。初次載入的 JavaScript 和 CSS gzip 預算為 150 KiB，並以 Vite 輸出檢查。

## 分階段遷移和 rollback

Vite 產生的 `index.html` 和 bundles 是 active app entry。舊版會直接改 DOM 的 `app.js`、`app-shell.js` 和 stylesheet 僅透過 `legacy.html` 暫時保留作 rollback artifact；React entry 不會載入該頁或舊 scripts。Feature modules 逐階段取代舊 workflows，同時保留既有 Java routes、REST contracts、authentication 和 safe-source behavior。完成 feature parity 驗證後移除 rollback page 和 legacy assets。

如需回復 shell，恢復先前的 `index.html` 和 WAR packaging 路徑中的 static resources，然後重新 build Server WAR。`legacy.html` 只供診斷或 rollback 參考，並不是 deployment switch。Rollback 不會更改 server data 或 API contracts。

## Browser acceptance

Development-only fixture 和 browser commands 記錄於 [`tools/ui-acceptance/README.md`](../../tools/ui-acceptance/README.md)。Harness 會在 `/att/ui/` 測試已 build 的 Vite output、提供 deterministic API responses，並以 axe 檢查 Dashboard、Settings、Explorer、Load/Jobs 及 Scheduled Load flows。Cross-browser suite 在 desktop、tablet 和 phone widths 以 light/dark themes 覆蓋 Chromium、Firefox 及 WebKit，檢查 responsive layout 與 overflow，在每個 viewport 執行 axe，並把截圖與各 browser/viewport/theme 的 Scheduled Load baseline 比較；截圖亦會保存為 CI artifacts。Dashboard 參考截圖仍保留於 [`ui-shell-baseline/`](ui-shell-baseline/)，Scheduled Load visual baselines 位於 [`tools/ui-acceptance/visual-baselines/scheduled-load/`](../../tools/ui-acceptance/visual-baselines/scheduled-load/)。Browser screenshots 和 axe checks 不能取代 Java WAR packaging 或 non-root Tomcat tests。

Cross-browser job 於 `ubuntu-latest` 執行，並比對 `linux` baseline；本機 macOS 使用 `darwin`。Harness 會核對所選 manifest 的 platform；該平台缺少 manifest 或 captures 時會令檢查失敗，不會改用其他平台的截圖。

Scheduled Load 使用 lazy-loaded React route。只有 `/api/v1/version` 回報 `loadSchedulingEnabled` 時，Shell 才會顯示入口；直接導覽時仍會顯示 server-disabled 狀態。頁面呼叫 typed `load-schedules` API，儲存前要求有效的 Server preview，新排程預設暫停，Run Now 或刪除前要求確認。Occurrence history 同時顯示 skipped、failed submissions 及連結工作。Cron 驗證、timezone 和 DST 行為、最短間隔、並行上限及授權仍由 Server 決定。

Shell 會跟隨 `prefers-color-scheme`，直到使用者選擇主題。Settings 頁提供 System、Light 和 Dark 選項；明確選擇會保存在此 browser，System 則移除 override。按 `/` 或 `Ctrl K`/`⌘ K` 開啟頁面 palette，按 `?` 開啟快捷鍵說明，Escape 關閉對話框及手機導覽。在 Advanced Load 頁面，`Ctrl Enter`/`⌘ Enter` 會對有效且已驗證的 draft 呼叫既有確認流程。

## 各 Phase 截圖

Gallery 使用 synthetic fixture responses，展示各個 phase 共用的 design system。每個連結會開啟 1440 × 1000 PNG 截圖。

| Phase 和畫面 | Light | Dark |
|---|---|---|
| Phase 1：Dashboard | [截圖](ui-shell-baseline/dashboard-1440-light.png) | [截圖](ui-shell-baseline/dashboard-1440-dark.png) |
| Phase 2：Explorer 和 Debug | [截圖](ui-shell-baseline/explorer-debug-1440-light.png) | [截圖](ui-shell-baseline/explorer-debug-1440-dark.png) |
| Phase 3：Load Builder | [截圖](ui-shell-baseline/load-builder-1440-light.png) | [截圖](ui-shell-baseline/load-builder-1440-dark.png) |
| Phase 3：Job Results | [截圖](ui-shell-baseline/job-results-1440-light.png) | [截圖](ui-shell-baseline/job-results-1440-dark.png) |
| Phase 4：Scheduled Load | [截圖](ui-shell-baseline/scheduled-load-1440-light.png) | [截圖](ui-shell-baseline/scheduled-load-1440-dark.png) |
[Screenshot manifest](ui-shell-baseline/manifest.json) 記錄每張截圖的 route、fixture、browser、viewport 和 theme。

如需移除 acceptance harness，回復 `tools/ui-acceptance/` 和 `docs/system-design/ui-shell-baseline/`。請將測試 artifact rollback 與 app rollback 分開處理。
