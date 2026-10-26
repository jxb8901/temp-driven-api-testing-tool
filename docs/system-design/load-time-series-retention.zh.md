# LOAD 時間序列保留後續設計

**狀態：**[#299](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/299) 的設計後續。本文件記錄 #298 提出的保留問題；不會實作長時間 soak，亦不會改變 LOAD 排程語義。

## 目前行為

`LoadMetrics` 以 rolling ring 保留最多 4,096 個一秒 bucket。slot 重用時會
淘汰較舊 bucket，但精確 aggregate counter 仍涵蓋整次執行。`LoadWindowMetrics`
另以 uniform power-of-two roll-up 保留有界的全程時間序列。terminal result
會回報 ring 容量、目前保留範圍、淘汰數量、執行時間及粗粒度序列的 bin 寬度。
詳見[LOAD terminal result 契約](load-terminal-result.md)。

此設計提供精確總數及有界趨勢資料。長時間執行的 rolling one-second series
會失去較早的細節；全程 coarse series 的執行時間越長，bin interval 亦越寬。

## #299 後續設計問題

1. 操作人員需要哪些執行時長及圖表解析度，包括近期高解析度資料與較早粗粒度
   歷史資料是否需要並存。
2. 固定 multi-resolution hierarchy 或有界分段 sidecar，哪種方式能在明確的記憶體
   與 artifact 預算內保留更多有用細節。
3. 各類指標的 downsampling 方法：加總 counter 必須保持精確；latency 分布則需有
   文件說明的有界 sketch 或 percentile 規則。
4. API 與 Studio 如何按時間範圍讀取、分頁載入較舊分段，並顯示缺口、roll-up 寬度
   及 coverage，而毋須一次載入所有歷史資料。
5. result-size、archive entry 及 artifact 總量限制如何約束 sidecar 格式與保留期限。

## 未來實作的限制

- 執行狀態、exit code、threshold verdict 及精確 aggregate counter 必須與抽樣時間序列
  coverage 分開保留。
- #297 compact terminal result 必須維持目前大小預算；詳細序列應透過有界且授權的
  artifact 參照發布。
- 記錄抽樣方法、interval、涵蓋起訖時間，以及省略或淘汰數量，避免圖表把資料呈現為
  不存在的全解析度歷史。
- 限制記憶體、儲存、API response 大小及每次請求的工作量。分頁或時間範圍查詢不得
  無界讀取歷史資料或增加遙測 cardinality。
- 透過 #299 的獨立 soak 計畫驗證解析度與儲存預算，不納入 #298。

待 #299 確認所需圖表時長與解析度後，再決定具體設計。本文件是該範圍決策的起點。
