<!-- att-doc-status: current release=4.2.8 -->

# Studio Schedule

![ATT Studio 排程 Load 畫面](../../system-design/ui-shell-baseline/scheduled-load-1440-light.png)

*排程 Load 靜態 UI shell 基線截圖，使用示例 Schedule 資料；圖中版本標籤屬於截圖基線。*

## 排程 route 與獨立 console（4.2.4）

Schedules 清單不顯示 Schedule ID。LOAD 依每個 workload 顯示已保存的 configured VU 或 arrival rate、planned total duration、可用 warmup/ramp/steady phase 及有界 Target bindings。缺少的歷史設定顯示 Not available；清單只讀已保存的 sanitized summary，不逐列請求 YAML。
History → Back to list → Edit 會同時按 route view 及 schedule ID 載入。重新整理已授權清單時保留同一表單的未儲存修改；不存在或禁止存取的定義顯示不可用狀態。沒有可編輯 validated snapshot 的舊 RUN 定義會說明如何審閱替代 RUN。

從 Job 開啟的 **Schedule / Reschedule…** 會建立新 Schedule draft；**View / Edit originating schedule** 是獨立操作。**Run now** 亦與兩者分開。Schedule 的 `strict`／`pause`／`ignore` 只套用於排程提交；手動 Job Rerun 會另外選擇 current 或 strict source。

**Open full Job Details in a new tab** 開啟 `#/jobs/{encodedJobId}?standalone=1&tab=live`（或 `events`），包括 Overview、Live Output、Events、Results、Artifacts、Metrics。切換 tab 更新 URL 並固定同一 Job。舊 `#/jobs/{jobId}/console?view=live|events` 書籤使用完整 Details adapter。同源 `target="_blank" rel="noopener noreferrer"` 連結支援 `/att/ui/`、重新整理、Back/Forward。每個分頁內共用單一 Job stream；floating/docked/minimized console 維持原有行為。未授權或缺少的 Job 顯示安全 unavailable／登入訊息。

## 審閱執行設定變更

開啟現有 Schedule，選擇 **Edit execution settings**。請先儲存或放棄未儲存的 Schedule Settings。對 inline LOAD 快照，編輯器支援工作負載的 users、arrival rate、concurrency、個別或共用階段覆寫值、單一 target、現有 mix 的權重和 target、environment，以及 thresholds／evidence／testdata／execution／seed 等共用進階 JSON。變更 mix target 會清除該 binding 的 inputs 和 variables。對 RUN 快照，可編輯 case ID 或 resource target、選擇性 input bindings，以及 debug／fail-fast 選項。變更 resource target 會清除原有的 input bindings。Package-backed LOAD Schedule 保留 Package scenario 路徑、Load overrides 與選擇性 environment。Package Catalog 快照可使用相同的 LOAD 控制項，審閱後仍保持為固定快照。

選擇 **Validate changes**，比較安全的變更前後預覽、Package 版本、政策與下次執行時間。審閱後才選擇 **Save as new snapshot**。Schedule ID、觸發條件、下次執行時間和既有 Job 均保持不變。審閱過期或 Schedule 同時被修改時，須重新驗證。具備排程操作權限的使用者可編輯共用 Schedule；審閱只顯示安全摘要，並保留原本的執行身分。
