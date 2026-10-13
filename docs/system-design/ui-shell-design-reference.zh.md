# ATT Web UI Shell 設計參考

本 annotated wireframe 描述 4.1.3 Phase 1 Shell 和 Dashboard，供現有 static UI 實作交接使用；它不代表新增 API metrics。

## Desktop wireframe

```text
┌───────────────────────┬─────────────────────────────────────────────────────────────┐
│ [A] ATT Studio         │ [B] ATT Studio         Server ● Connected        [Theme]     │
│                       ├─────────────────────────────────────────────────────────────┤
│ WORKSPACE             │ Dashboard / Packages       [Package: All] [Env: Per run]     │
│ [C] Dashboard         │                          [Identity: Server managed] [View packages] │
│     Packages          │                                                             │
│     Explorer and Run  │ Dashboard                                                  │
│     Load testing      │ [D] Configured packages  [E] Active jobs  [F] Latest job   │
│     Jobs and results  │     API 回傳數量            最新 rows       API status    │
│                       │                                                             │
│                       │ [G] Configured packages       [H] Recent activity            │
│                       │ package actions              latest 100 job rows             │
│                       │                             search / refresh / results table │
└───────────────────────┴─────────────────────────────────────────────────────────────┘
```

| 標記 | 用途 |
|---|---|
| A | Home link 和 sidebar collapse control。Sidebar 狀態保存在此 browser。 |
| B | Server connection status，以及系統、light、dark theme control。Identity chip 會說明 API 沒有提供目前 principal。 |
| C | 已實作 Dashboard、package、explorer/run、Load 和 job routes 的固定 links。 |
| D–F | Summary cards 使用 configured package 數量、回傳 job rows 中的 active statuses、最新回傳 job，以及 API version check。 |
| G | Configured package 清單，提供 explorer 和 Advanced Load 直接操作。 |
| H | 可搜尋的 latest-job table；每個 status 都有文字標籤。 |

Sidebar 展開時為 248 px，收合時為 64 px。Top bar 高 56 px。Cards 使用 12 px 圓角，controls 使用 8 px 圓角，spacing 以 4 px 為基礎。Tables 可水平捲動，避免欄位縮得難以閱讀。

## Tablet 和 mobile 行為

在 768 px 時，sidebar 變成可關閉的 navigation drawer。Menu button 開啟 drawer；Escape 和 close control 可關閉。Summary cards 使用兩欄，content panels 垂直排列。手機寬度下，cards 保持兩個較窄欄位，context chips 換行，tables 保持水平捲動。

## Design tokens

| Token | Light | Dark | 用途 |
|---|---|---|---|
| `--att-color-primary` | `#2563EB` | `#60A5FA` | Links、選取的 navigation、主要 actions |
| `--att-color-canvas` | `#F8FAFC` | `#0B1220` | Workspace 背景 |
| `--att-color-surface` | `#FFFFFF` | `#111827` | Sidebar、panels、controls |
| `--att-color-text` | `#0F172A` | `#F1F5F9` | 主要文字 |
| `--att-color-muted` | `#64748B` | `#94A3B8` | 輔助文字 |
| `--att-color-border` | `#E2E8F0` | `#334155` | 分隔線和 control borders |
| `--att-color-success` | `#15803D` | `#4ADE80` | PASS status，並顯示文字 |
| `--att-color-danger` | `#B91C1C` | `#F87171` | Failure status，並顯示文字 |
| `--att-color-warning` | `#B45309` | `#FBBF24` | Warning，並顯示說明文字 |
| `--att-space-1`–`--att-space-8` | 4–32 px，每級相差 4 px | 相同 | 一致的 layout spacing |
| `--att-radius-control` / `--att-radius-card` | 8 / 12 px | 相同 | Controls 和 cards |
| `--att-focus-ring` | 2 px 並向外偏移的可見 ring | 相同 | Keyboard focus |

## Screen states

| 狀態 | Package panel | Jobs panel | Summary |
|---|---|---|---|
| Loading | 宣告正在載入並顯示中性色 placeholder bar | 宣告正在載入並顯示中性色 placeholder bar | 對應 response 返回前，數值保持 unavailable |
| Empty | 說明尚未註冊 packages | 說明如何提交第一個 job | 成功取得 response 後才顯示零 packages 或無 jobs |
| Loaded | 顯示 package IDs 和直接 actions | 顯示回傳 rows、文字 statuses 和 job links | 只使用本次 response 數據 |
| Error | 宣告失敗並提供 Retry | 宣告失敗並提供 Refresh | 顯示 unavailable，而非把錯誤當成零 |

## Keyboard 和 contrast

Skip link 將 focus 移至 main content。DOM 順序先到 navigation，再到 page content。Sidebar、theme、search、refresh 和 row links 都有 accessible names 和可見 focus。Status label 包含文字，因此使用者不必依靠顏色分辨 PASS、FAIL 或 RUNNING。Loading treatment 和 drawer transition 會遵守 reduced-motion preference。
