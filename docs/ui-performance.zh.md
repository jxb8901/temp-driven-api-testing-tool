# 測量 UI 效能

使用 development-only Playwright harness，在相同的 built UI 及 synthetic
fixture data 上比較 ATT Studio 的 browser-side 效能。

## 執行 harness

先建置 UI、安裝 acceptance dependencies，再以 Chromium 執行三次：

```sh
cd att-web/frontend && npm run build
cd ../../tools/ui-acceptance && npm ci
npm run perf -- --label before
```

Harness 會在 `/att/ui/` 提供 built files，Playwright 只存在於這個
development package，不會把 Node.js 加入 production WAR。使用
`ATT_UI_DIST=/path/to/dist` 選擇其他 built output directory。用
`--browser firefox` 或 `--browser webkit` 選擇其他 engine；用
`--reduced-motion` 執行另一組 reduced-motion 測量；用 `--repeat N` 改變
樣本次數。`--scenario NAME` 可選擇一個或多個 scenario；未知名稱會由
harness 清楚報錯。

Built Vite entry 仍然是 external module script，並保留現有 CSP nonce
placeholder。Test-only instrumentation 由 Playwright 注入，不會在 served UI
加入 inline scripts 或 assets。

結果會寫入
`tools/ui-acceptance/artifacts/perf/<label>/perf.json` 及 `summary.md`，而
raw output 會被 Git 忽略。完成 reviewed change 後，可不啟動 browser 比較兩
個 label：

```sh
npm run perf -- --compare before after
```

## Scenario 與測量內容

Fixture 涵蓋每秒 10、50、200 個事件的穩定 Live Output/Events stream、包含
replay 及一次 reconnect 的 10,000-event burst、500 筆 retained Job events、
100/1,000/4,096 個 metric buckets、100 筆 Jobs、100 個帶有未來 active
countdown 的 Schedules、core route 的 cold/warm navigation，以及 hidden tab、
反覆切換 route、paused auto-scroll 等 resilience actions。

每個樣本記錄 route-ready time、超過 50 ms 的 long tasks、search/filter
輸入至下一次繪製的 proxy、DOM mutation batches 及 rAF frame durations、DOM
node 數量、browser 提供時的 JS heap、incoming/rendered events、最新顯示事件
及 terminal delay、Event-ID 順序診斷、每個 Job 每個 tab 的 EventSource
instance 數量、JS/CSS transferred bytes，以及從 Vite manifest 計算的每個
chunk gzip size。Report 亦記錄 machine、OS、CPU count、browser、Playwright
version、commit 及 reduced-motion 設定。

Displayed-event delay 是從最新事件到達頁面 EventSource listener，到該事件首次出現在 DOM 的 animation frame 之間的時間：Events 檢視以其 `#id` 判斷，Live Output 以其 console 行判斷。Terminal delay 是從終端 `result` 事件到首次顯示 `PASS` 狀態標籤或 `SSE completed` 的 frame 之間的時間。10,000 筆事件 burst 由 fixture server 提供，而非 Playwright route fulfilment，因為 Chromium 重新連線到 fulfil 的回應時不會送出 `Last-Event-ID`；server 會記錄重新連線的 cursor，若重新連線不是從第 10,000 筆事件繼續，本次執行會回報錯誤。

Harness 會保留所有 raw samples，並計算 median（p50）及 p95。現階段沒有
numeric thresholds；只有 browser page/render error 或同一 tab 的同一 Job
出現多於一個 EventSource instance 才會以 non-zero 結束。效能數字及
duplicate/missing order diagnostics 是 review evidence，不是 pass/fail gate。

Curated baseline 應在 review 後放入 `docs/system-design/perf/`，並保留完整
environment metadata。不要把 synthetic fixture 當成 Server、Worker、JVM
或 end-to-end throughput evidence。
