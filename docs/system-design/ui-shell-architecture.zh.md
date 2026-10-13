# ATT Web UI Shell 架構決策

**狀態：** ATT 4.1.3 Phase 1 已採納  
**日期：** 2026-10-13  
**範圍：** `att-web` 的靜態 UI，以及它在現有 Server WAR 中的發佈方式。

## 決策

UI 繼續以 HTML、CSS 和 vanilla JavaScript 實作，並由 ATT Server WAR 作為靜態資源提供。新增獨立而精簡的 `app-shell.js` 模組，負責常駐 Shell。現有 feature routes 和 API workflows 留在 `app.js`；後續 phase 可再加入 feature modules。

Production 安裝仍只需要 Java 和 Apache Tomcat，無須 Node.js、npm、前端 server 或下載 dependencies。Browser 只載入 same-origin 靜態資源，並呼叫現有 same-origin `/api/v1/` 和 SSE endpoints。

## 考慮過的方案

| 方案 | 優點 | 本 Phase 的成本 | 決定 |
|---|---|---|---|
| 漸進式 HTML、CSS 和 vanilla JavaScript | 保留現有 UI、嚴格 self-only CSP、hash routes 和 WAR resource packaging | Components 和 state 需要明確慣例 | 採用 |
| React、TypeScript 和 Vite | 大型 rewrite 有強大的 component 和型別系統 | 在現有 routes 尚未達到 feature parity 前先增加 build toolchain 和 migration 工作 | 暫緩 |
| Java server-rendered views | Server 控制 markup，毋須 client framework | 需要重寫現有 browser-side resource 和 job workflows | 暫緩 |

## 限制和預算

- Scripts 和 styles 必須保持外置，讓現有 Content Security Policy 繼續禁止 inline code。
- `#/packages/{id}`、`#/advanced-load/{packageId}` 和 `#/jobs/{jobId}` 等 routes，在 `/ui/` 和 `/att/ui/` 等非 root context 下均須可用。
- 所有 Dashboard 數據均來自現有 package、job 和 version responses。`/jobs` 最多回傳最新 100 筆記錄；UI 必須清楚標示從這些 rows 計算的數量。
- 保留現有 API payloads、authentication 行為、安全 source projections 和 feature gates。
- 首次載入的 HTML、CSS 和 JavaScript gzip 大小以 150 KiB 為 review budget。新增 assets 或 dependencies 時重新檢查。
- 使用共用 `--att-*` tokens，讓後續 phase modules 共用顏色、間距、圓角和 focus ring。

## Migration 和 rollback

Phase 1 為既有 screens 加上常駐 navigation、breadcrumb、context 和 action areas。現有 route dispatcher 繼續負責 feature 行為。後續 phases 可新增自己的靜態 UI modules，並連接至此 Shell，無須改變 Java API。

如果 Shell 造成 regression，可從前一個 commit 還原 `index.html`、`app.css` 和 `app.js`，並移除 `app-shell.js` 引用。Shell 不會遷移 Server data 或改動 REST contracts。

只有在 UI 的共用 state 已無法清楚維護於現有 modules 時，才重新考慮 compile-time component framework。日後使用的 framework 必須輸出 WAR 靜態資源；Production 仍只執行 Java 和 Tomcat。
