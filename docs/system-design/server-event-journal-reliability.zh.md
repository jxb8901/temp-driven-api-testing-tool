# Server Job Event Journal 可靠性

## 上限

每個 Job 使用自己的 `events.jsonl`。`server.maxEventsPerJob` 維持記錄數限制（預設 10,000；範圍 100–1,000,000）。`server.maxEventJournalBytes` 新增獨立磁碟上限（預設 64 MiB；範圍 1–256 MiB）。Event data 維持最多 65,536 個 UTF-8 JSON 位元組，每行 JSONL 最多 131,072 位元組。重新載入會串流讀取最後設定的 byte window，每次只解析一行，並按記錄數及日誌記憶體預算限制保留內容。128 MiB 的已計量預算會由 `workers.maxConcurrent + workers.queuedLimit` 分配，每個 Job 最低 1 KiB。每個日誌會保留最新一筆 event，即使其超過單一 Job 的預算；超額幅度受 JSONL 行長上限限制，metrics 會列出實際超額量。

若既有日誌大於設定上限，Server 只讀取有界尾段並保留原檔。格式錯誤或超大的尾段會將該 Job history 標示為 degraded。若無法確認最新序列，Server 不會分配新的 cursor ID，以免重用原始位元組中可能存在的 ID。若 terminal Job 的序列可確認但缺少 result marker，Server 可從獨立保存的 Job metadata 在記憶體恢復 result event，不會改寫日誌。若無法安全恢復 result marker，terminal stream 會發出診斷後關閉。

Terminal Job 會按需重新載入，並透過 access-ordered LRU 快取；上限為 16 個 Job 及 128 MiB 已計量保留記憶體。Active Job 日誌由 Job object 持有，因此一般請求不會重複載入。這可避免每次讀取 terminal history 都重新載入大型檔案。

## Append 及壓縮

Append 會按 journal 路徑序列化、檢查可用儲存 headroom，並確保下一行不會超過 byte cap。可用空間檢查會保留 8 MiB 門檻，但不會向 filesystem 預留空間。若下一筆 append 將超過上限，Server 會先同步壓縮保留窗口。壓縮會逐筆直接寫入唯一的 sibling 暫存檔，不會把整份 journal 拼成單一字串。

背景壓縮仍會每 128 筆 event 執行。它會先在 append lock 外寫有界 snapshot，然後取得 per-path lock 執行有界 tail catch-up 及替換。若持續寫入令 catch-up 超出上限，會丟棄 snapshot；byte cap 路徑會在 writer lock 下重新建立 snapshot，確保前進。替換前會核對 temp file 大小及 SHA-256。只有同一 filesystem 的 atomic move 才會取代原檔。不支援 atomic move 或發生儲存錯誤時會保留原日誌。

## 儲存故障行為

Append 或壓縮故障只會影響該 Job 的 journal。Server 會停止密集重試，並在可行時將不完整行回復至前一個 file boundary；持久化重試間隔會由 5 秒遞增至最長 5 分鐘。Event 傳送仍可在有界記憶體中繼續，in-process ID 保持遞增；未持久化 event 會在回傳內容標記狀態，stream 亦會發出獨立診斷。後續持久化 event 在重新載入時可能出現 ID gap，並會報告 history 已截斷。Job 執行狀態／結果與 journal 診斷分開保存。

`GET /api/v1/metrics` 會提供 active journal bytes、保留 event／記憶體數、壓縮嘗試／失敗數、degraded active Job 數及 terminal 快取大小。Per-Job SSE 診斷包含 byte cap、可用 headroom、保留記錄、壓縮狀態及 history 是否截斷。使用量達 byte cap 的 80% 時會發出警告。

這些保護限制 journal 導致的 heap 及磁碟增長；它們不會預留 filesystem 空間，也不能令已滿磁碟上的 H2、report、checkpoint、Worker output 或 artifact 寫入成功。Filesystem 已滿時，原始 execution cause 應與 journal persistence 狀態分開保留。歸因前必須確認大檔案的確切路徑；沒有部署環境證據時，事故所述 6.3 GB 檔案路徑及最初導致 OOM 的配置仍未能核實。

## 管理員復原步驟

1. 記錄確切路徑、檔案類型、filesystem、大小、增長時間、可用位元組／inode 及 Job ID；不要假設大檔案就是 Job journal。
2. 嘗試修復前，先將原始檔複製到其他儲存空間並核實副本。保留 OOM/GC log、JVM Heap 設定、Worker exit 詳情、archive 狀態及時間戳。
3. 恢復 filesystem headroom，並從 Job event stream/result 及 `GET /api/v1/metrics` 檢查 `journalPersistence`、`journalBytes`、保留尾段及壓縮診斷。
4. 若歷史 journal 超過上限，需先保留原檔；之後才可在副本或維護時段執行經審核的修復，並核對 event ID 及 terminal result，再將原檔保留作 audit evidence。
5. 確認 output/report/archive 狀態後才重跑受影響的 Job。未有獨立且已核實的備份及 retention 決策前，不要刪除 Job 目錄。

## 驗證範圍

回歸測試會建立超過 6 GiB 的 sparse file，只在尾端寫入少量有效事件；測試會核對掃描位元組有上限、最新 ID 正確、可保留 result，且不修改超大原檔。其他測試涵蓋超大單行、byte-cap 壓縮、並行 producer、terminal cache 重用，以及模擬零可用儲存空間。這些合成測試驗證有界 source 行為，不能證明生產事故原因，也不等同 23 小時／100 TPS soak。
