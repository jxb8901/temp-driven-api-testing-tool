<!-- att-doc-status: current release=4.2.4 -->

# Studio Schedule

## 排程 route 與獨立 console（4.2.4）

Schedules 清單不顯示 Schedule ID。LOAD 依每個 workload 顯示已保存的 configured VU 或 arrival rate、planned total duration、可用 warmup/ramp/steady phase 及有界 Target bindings。缺少的歷史設定顯示 Not available；清單只讀已保存的 sanitized summary，不逐列請求 YAML。
History → Back to list → Edit 會同時按 route view 及 schedule ID 載入。重新整理已授權清單時保留同一表單的未儲存修改；不存在或禁止存取的定義顯示不可用狀態。沒有可編輯 validated snapshot 的舊 RUN 定義會說明如何審閱替代 RUN。

**Open full Job Details in a new tab** 開啟 `#/jobs/{encodedJobId}?standalone=1&tab=live`（或 `events`），包括 Overview、Live Output、Events、Results、Artifacts、Metrics。切換 tab 更新 URL 並固定同一 Job。舊 `#/jobs/{jobId}/console?view=live|events` 書籤使用完整 Details adapter。同源 `target="_blank" rel="noopener noreferrer"` 連結支援 `/att/ui/`、重新整理、Back/Forward。每個分頁內共用單一 Job stream；floating/docked/minimized console 維持原有行為。未授權或缺少的 Job 顯示安全 unavailable／登入訊息。
