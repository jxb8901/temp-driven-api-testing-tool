<!-- att-doc-status: current release=4.2.4 -->

# ATT Server Job 與 Schedule 操作

## 已發布 Live Output 限制（4.2.4）

設定 `server.liveOutput.maxEntryChars`（整數 1–32000，預設 4000）及 `server.liveOutput.maxEntryLines`（整數 1–200，預設 40）：

```yaml
server:
  liveOutput:
    maxEntryChars: 16000
    maxEntryLines: 100
```

重新啟動 Server 後，新提交的 RUN、Debug、LOAD Job 使用固定的可信政策；Package YAML 或 REST 欄位不能提升限制。CLI 維持 16000 字元／80 行，Case Log 與 artifacts 維持各自政策。舊 journal 只保留已儲存文字，不能恢復曾被截斷內容。小數、字串數字、越界值及未知設定會使啟動驗證失敗。

Worker 在截斷前遮罩秘密並移除 ANSI／控制字元。字元或行數超限顯示 `[truncated]`。Journal 每筆 payload 在 UTF-8 JSON 編碼後最多 65536 bytes。Payload 過大時，會保留結構化 allow-list 欄位 `status`、`code`、`message`、`executionId`、`caseId`、`stage`、`action`、`executionEventType`（字串最多 512 字元），以及數值 `exitCode`、`durationMs`；最多以 `droppedFields` 記錄 32 個被省略的欄位名稱。Payload 會包含 `truncated: true`、`journalLimit` 及有界的摘要。可行時保留完整 console；若 console 單獨超出 byte 預算，會加入 `consoleTruncated: true` 與 `[truncated by journal]`。Worker protocol 仍限制為 1 MiB；多位元組及 JSON escaping 可較早耗盡 journal 預算。

Studio 保留最近 500 筆瀏覽器事件；瀏覽器歷史淘汰與 journal 歷史到期各有提示。Events 預覽 4000 字元，提供明確展開，最多顯示 65536 個已保留字元；展開不能還原上游截斷內容。

提高限制會增加遮罩／JSON CPU、SSE／重播流量與 journal 磁碟用量。16000 個 ASCII 字元乘以 10000 筆事件，每個 Job 的文字可約為 160 MB，另加 metadata；500 筆瀏覽器事件約為 800 萬字元。Unicode／escaping 可增加編碼大小。配額仍計入 pinned Job；高流量 LOAD 宜保留預設值並監察用量。


以 `mvn -pl att-server -am -Dtest=JobEventsTest#concurrentExpandedEntriesKeepReplayBoundedAndReportMeasuredCost -Dsurefire.failIfNoSpecifiedTests=false test` 重現並行 journal／重播檢查：四個 Job 寫入共 800 筆擴大項目，重新開啟 journal，核對各自保留的 100 筆視窗與完整文字，並輸出經過毫秒數及保留的 journal bytes。WAR 整合測試另以 16000／100 政策核對 8000 字元診斷經 Worker → journal → SSE 重播，包含企圖覆寫限制的不可信 request。

## Pinned evidence 與待處理 Job 刪除（4.2.4）

USER operator 可對 terminal RUN、Debug、LOAD Job 使用 Pin/Unpin 圖示；第一次點擊即持久化，不需要確認或填寫原因。Pin 保護整個 Job，阻止 age/size retention，自動到期機制不適用，且跨 restart 保留。API 的選填 `pinReason` 維持 200 字元上限及 secret redaction；受保護 bytes 仍計入 quota。

`GET /api/v1/jobs` 在 Server 執行 filter、排序及 offset pagination。參數包括 `offset`、`limit`（1–100，預設 100）、`principal`、`principalMode`（`all`、`text`、`mine`、`schedule`）、`packageId`、`executionType`、`status`、`from`、`to`、`search`、`sort`（`newest`、`oldest`、`target-asc`、`target-desc`）、`pinnedOnly`、`deletion`（`all`、`pending`、`unmarked`）及 `groupBy=target`。日期使用 ISO-8601 instant；RUN 包括 Debug。Mine 使用 authenticated identity；Schedule 依 canonical schedule principal 或已保存來源識別。自由文字採 literal substring。回應包括 `items`、授權範圍的 `total`、`offset`、`limit`、`nextOffset`、有界 principal/package 建議及目前頁面群組的完整 filtered counts/statuses。現有 operator policy 允許 USER 共用 Job history；ADMIN 不可使用 operator endpoints。建議依相同權限，排除 Schedule ID。

Jobs 以 Target 為主要連結，次行顯示 Package/Environment，並提供縮短 Job ID 及複製完整 ID。Target identity 是 submit 時保存的 allowlisted immutable projection；缺乏來源的歷史紀錄顯示 Unknown target，仍可開啟。Group key 包括 Package、Environment、完整 target-binding signature；multi-target Job 只出現一次。每頁按個別 Job 分頁，群組總數涵蓋完整 matching history；頁面操作包含已收合群組的成員。預設依最新建立時間排序，footer 記住 25/50/100 列，初次預設 100。

清單只有兩個 footer 操作選單：Pin all / Unpin all，以及 Delete all / Undelete all。範圍是當下頁面明確 Job ID snapshot，不含其他頁。逐 ID 回應區分 updated、already、rejected/skipped、failed；不使用選取 checkbox 或確認對話框。

下列 state-setting endpoint 最多接受 100 個去重 ID；body 上限為 16384 bytes 或較小的設定值：

- `POST /api/v1/jobs/{jobId}/pin`，body 為 `{"pinned":true}` 或 `false`。
- `POST /api/v1/jobs/pin`，body 為 `{"pinned":true,"jobIds":["J0123456789ABCDEF"]}`。相同 bulk state 回傳 `ALREADY_IN_STATE`。
- `POST /api/v1/jobs/{jobId}/delete`，使用 `{"requested":true}` 標記、`{"requested":false}` 取消標記。舊 `{"confirm":true}` 仍視為標記。
- `POST /api/v1/jobs/delete`，body 為 `{"requested":true,"jobIds":["J0123456789ABCDEF"]}`；`false` 代表取消標記。

標記回傳 `MARKED` 並保存 `DELETE_REQUESTED`；HTTP request 不會遞迴刪除檔案。只有 cleanup claim 前可以 `UNMARKED`。相同狀態回傳 `ALREADY_IN_STATE`；已 claim 為 `ALREADY_CLAIMED`，pinned 為 `PINNED_UNPIN_FIRST`，active 為 `NOT_TERMINAL` 或 `JOB_BUSY`，recovery/quarantine fence 為 `NOT_ELIGIBLE`。單筆拒絕回傳 HTTP 409 outcome map；bulk 回傳逐 ID 結果。Pending Job 可列出及存取；claim 後 endpoints 隱藏 Job，取消標記無法恢復 artifacts。不存在紀錄回傳 `ALREADY_UNAVAILABLE`。

有界背景工作每五秒執行，至少保留 30 秒 pending grace period。Claim 以 conditional transition 將 `DELETE_REQUESTED` 改為 `DELETING`，重新檢查 pin、terminal state、Worker identity/descendant、recovery、quarantine 及 archive lifecycle lock。Startup 與定期 reconciliation 完成中斷的 `DELETING`，pending intent 跨 restart 保留。Filesystem cleanup 可重試，audit 保留 requesting actor。`DELETE /api/v1/jobs/{jobId}` 維持取消執行語意；Schedule history 與 package source 保留。刪除不立即壓縮 H2；災難復原仍需獨立備份。

## 排程 package 變更及 runtime 關閉（4.2.4）

`server.scheduling.packageChangePolicy` 接受 `strict`（預設）、`pause` 或 `ignore`；未知值會阻止啟動。每個排程可透過 `packageChangePolicy` 覆寫；`null` 或未設定時沿用 Server 預設，舊有記錄亦然。Studio 選項為 **Default (Server)**、**Strict**、**Auto-pause** 及 **Revalidate latest**。

- **Strict**：保留的 revision 改變時，以 `package-revision-changed` 記錄拒絕提交；排程維持啟用但受阻，原有輸入及 digest 保留。
- **Auto-pause**：記錄相同原因，並以原子操作只暫停受影響排程，保留原因及時間，寫入一次 `SCHEDULE_AUTO_PAUSE` audit。若期間定義已被編輯，舊的暫停決策不會覆蓋新定義。
- **Revalidate latest**：只略過與保留 package digest 的相等檢查。仍驗證目前註冊的 package root、執行身分、typed target、精確 Case IDs、schema、environment、受保護輸入分類及限制。Worker 接收新的 digest，並於執行前再次檢查；儲存的定義保持不可變。`SCHEDULE_SOURCE_REVALIDATED` audit 記錄兩個 revision 及 policy 來自 Server 或排程。早於私有 package 註冊身分 pin 的舊記錄，在接受變更來源前需要重新審閱並建立替代排程。

週期與手動提交，以及啟用／恢復時均會檢查 policy。新排程必須通過目前輸入驗證。來源過期時仍可儲存名稱、描述、tags、週期、timezone 及 policy，而不更換來源或清除 stale／paused 狀態。RUN 輸入、target、package 及 environment 不可原地更換；需要重新驗證 RUN 並建立替代排程。Package-wide digest 包括共用設定及 Engine／schema 身分，因此無關 package 檔案的修改也可能觸發 policy；target dependency digest 留待後續最佳化。沒有保留 digest 的舊檔案式 LOAD 定義，繼續在每次觸發時驗證目前 package scenario。

Server 擁有有界的 event-compaction executor，供新建、恢復及之後讀取的 journal 使用。關閉時拒絕新 Job，停止排程及 retention producer，結束 Worker／inspection／SSE 活動，再排空 artifact writer 及 compaction queue，最後釋放資料庫。每個 executor 等待都有時間上限；中斷 compaction 後仍可從 append journal 恢復。重複關閉安全。共用 ATT classloader 的 runtime 以引用計數管理 H2 註冊，最後關閉時只移除該 classloader 擁有的 H2 driver；container 擁有的 JDBC driver 保留。測試在同一 Tomcat JVM 部署三次，執行 compaction 並於重新部署後讀回排程及 journal；CI 亦在 Tomcat 11 執行此案例。
