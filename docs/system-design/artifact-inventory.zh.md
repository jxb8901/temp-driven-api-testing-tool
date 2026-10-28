# 私有 artifact inventory

Completed Job 的 archive publication 不跟隨 symlink 遍歷私有 output tree。在計入 50,000 candidate 上限前，只剪除歷史 LOAD identity namespace `load/<run>/.exec-id-reservations`。只有位於此位置的真實目錄符合條件；大小寫別名、link 及其他位置的相似目錄仍接受一般不可信路徑檢查與上限。

剪除的 namespace 永遠不屬於可發佈內容，包括使用支援副檔名的檔案；它不產生 manifest entry 或 omission。此操作不刪除歷史檔案，檔案由 Worker cleanup 後的 completed-Job retention 負責移除。其他路徑，包括目錄與不支援檔案，仍消耗 candidate 預算。深度 64 的目錄會 fail closed；無法讀取的路徑或 traversal error 會中止 publication。

Inventory 保留既有的 candidate、path、type 及 size 上限。Artifact producer 在寫入 private output tree 前保護敏感內容。Archive 驗證有界 UTF-8 文字，並封裝擷取的 producer bytes，不會重新遮罩文字或改寫 HTML。新 archive 使用 `att-export-approved-bytes-v1` policy 和 `preserved` entry decision。為相容既有 archive，verifier 仍接受舊的 `att-export-safe-text-v2` manifest，但只驗證並回傳 ZIP 中已儲存的 bytes，不會再執行 sanitizer。`ArtifactInventoryTest` 涵蓋 100k 歷史檔案、精確分類、candidate 邊界與深度上限。

## 有上限的 producer-byte staging

完成 path 與檔案驗證後，每個 writer 最多在 memory 保留 8 MiB producer bytes；超出可用預算的 bytes 沿用私有 export directory 作 spill。這個上限只計算保留的 payload bytes，並非 JVM RSS 或總 allocation；單一 entry buffer 仍受原有 16 MiB 上限約束。128 MiB aggregate、10k entry 及 manifest 上限保持不變，不會把整個最大 archive 留在 heap。

第二階段只讀取已擷取的 memory 或 spill snapshot，並在完整 approved path set 已知後封裝，不會重讀 Worker 檔案。Relative link 與其他 HTML bytes 完全保留，包括指向因 archive 上限而省略檔案的 link。SHA-256 與 CRC 對應 ZIP entry 中完全相同的 producer bytes；完整 ZIP verification、file fsync 與 atomic replacement 不變。擷取後的來源變更不會取代封裝 bytes。失敗時沿用原有 cleanup 清除 export 與 staging ZIP。

新增的 bounded archive metrics：`temporaryExportFiles`、`temporaryExportBytes`（寫入 spill 並讀回一次的 producer bytes）及 `memoryExportPeakBytes`（保留 producer bytes 的高水位）。這些欄位不含檔名或內容。歷史資料若缺少欄位，代表 unavailable，並非零。`ArtifactArchiveTest.cappedMemoryAndSpillPublishExactlyTheSameProducerSnapshot` 比較兩種 storage path，涵蓋 exact-byte preservation、後續來源變更、omission、manifest/digest/CRC verification 與 staging cleanup。`ArtifactPipelinePerformanceBenchmark` 提供可重現的小型與大型 publication 測量。

Archive 預設使用 STORED。`server.artifacts.compression` 可選擇逐 entry DEFLATE 或 AUTO，level 範圍為 1–9。Deflate 只對已擷取的 payload 單次壓縮；writer 將 compressed temp bytes 複製到 final ZIP，不會重讀 Worker output。AUTO 對小於 256 bytes 的 entry 使用 STORED，並要求 payload 至少節省 5%。任何候選若超過既有 verifier 的 200:1 ratio，會退回 STORED。Archive metrics 回報設定及實際 mode、entry 方法數、compressed payload、ZIP overhead、節省量、壓縮耗時及有界 fallback 原因。`ArtifactPipelinePerformanceBenchmark` 會對可重現的 random text、重複 structured log 和高壓縮比 log 比較 store/deflate/auto；只有明確選取 benchmark 時才會執行額外比較樣本。
