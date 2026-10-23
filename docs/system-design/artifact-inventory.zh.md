# 私有 artifact inventory

Completed Job 的 archive publication 不跟隨 symlink 遍歷私有 output tree。在計入 50,000 candidate 上限前，只剪除歷史 LOAD identity namespace `load/<run>/.exec-id-reservations`。只有位於此位置的真實目錄符合條件；大小寫別名、link 及其他位置的相似目錄仍接受一般不可信路徑檢查與上限。

剪除的 namespace 永遠不屬於可發佈內容，包括使用支援副檔名的檔案；它不產生 manifest entry 或 omission。此操作不刪除歷史檔案，檔案由 Worker cleanup 後的 completed-Job retention 負責移除。其他路徑，包括目錄與不支援檔案，仍消耗 candidate 預算。深度 64 的目錄會 fail closed；無法讀取的路徑或 traversal error 會中止 publication。

此變更不影響 sanitized export staging、HTML link rewriting、final-byte digest、CRC、ZIP verification、fsync、atomic publication、retry 或 ZIP-backed reader。`ArtifactInventoryTest` 涵蓋 100k 歷史檔案、精確分類、candidate 邊界與深度上限。
