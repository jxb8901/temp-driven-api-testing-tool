# 內部限制與診斷設計

本文件定義 ATT 如何記錄內部容量限制並說明其結果。機器可讀的權威來源
是[內部限制清單](../operations/internal-limits-inventory.json)。操作指引
位於[英文 Limits Reference](../operations/limits-reference.md)及
[繁體中文容量參考](../operations/limits-reference.zh.md)。

## 結果分類

限制結果使用以下分類之一：`EXECUTION_ERROR`、`SUBMISSION_REJECTED`、
`ARCHIVE_FAILED`、`ARTIFACT_OMITTED`、`DATA_TRUNCATED`、`HISTORY_EVICTED`
或 `METRICS_SAMPLED`。執行狀態、exit code、threshold verdict、封存生命週期
及資料完整度仍是各自獨立的值。

診斷會標示主要執行或 admission 原因，並可附上有界的次要傳輸、呈現、
artifact 或 coverage 限制通知。安全欄位包括穩定的限制名稱、設定上限、
觀察到的用量、範圍、結果、階段、修復提示及邏輯 artifact 參照。診斷不得
包含秘密、業務 payload 或私有主機路徑。
Server API 及 CLI JSON 會保留這些有界欄位。CLI 人類可讀輸出會先顯示主要
診斷代碼，再分別列出 notice 和 transport 通知；不會輸出自由格式診斷文字。
Studio 亦採用主要原因優先次序，並將通知與執行結果分開顯示。

## 優先次序

ATT 先選取真正的 runtime、preparation 或 Worker 失敗，再處理傳輸截斷或
封存可用性。`ATT-WORKER-EVENT-TRUNCATED` 和
`ATT-JOURNAL-EVENT-TRUNCATED` 保留為次要通知。單獨的截斷、抽樣、歷史資料
淘汰或封存不可用，不會把成功的執行改成 `ERROR`。發布失敗屬於封存結果，
不會改寫 Job 結果。

## 清單契約

每項 JSON 清單會記錄 setting、來源路徑與類別、預設值、允許範圍、適用範圍、
是否可設定、執行邊界、對外結果、診斷、呈現位置及安全修復方式。硬編碼的
界限以 `hardCoded` 表示，且不支援的可設定範圍使用 `null`。測試會將具名安全
上限對應至來源常數，並將可設定項對應至 validator。中英文操作參考根據同一組
已核實項目維護；清單是權威來源。

## 有界觀測資料

重用既有 Engine、Worker、Server、archive 及 LOAD metrics。用量以 `usage`、
`limit` 及適用時的 `threshold` 表示；沒有量測時會明確標示 unavailable，
不會當成零。遙測按 execution 或 subsystem 彙總，不會按 iteration 增加
cardinality。0.8 threshold 是有界 metric snapshot 中的預警訊號；它不會改變執行結果，
亦不會為每個 event 建立 SSE 或 Journal 流量。

## 責任範圍與後續工作

此跨領域契約延伸 [#297 compact terminal result](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/297)、
[#86 LOAD metrics](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/86)、
[#236 archive publication](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/236)/[#243](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/243)、
[#265 live-output controls](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/265)、
[#281 metrics](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/281) 及
[#263 execution 行為](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/263)，
不改變這些功能各自擁有的限制。rolling one-second series 只保留 4,096 個 bucket，
另有有界 coarse series 涵蓋整次執行。若長時間執行需要更高解析度歷史資料，請參閱
[獨立的時間序列保留設計](load-time-series-retention.md)，並追蹤於
[#299](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/299)。本 issue
只回報實際 coverage，不實作長時間 soak。
