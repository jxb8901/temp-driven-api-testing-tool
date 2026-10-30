# 統一 RUN 與排程

<!-- att-doc-status: current release=4.2.8 -->

ATT 4.2.8 將功能性單次執行統一為 **RUN**，支援 Case、Template、Flow、Tool，以及所選 Case 批次。**Debug** 是 RUN 的診斷選項。**LOAD** 保留效能引擎、負載控制和專用報告。

已發行的 Local CLI 保留其 interface-specific 相容邊界：run verb 選取 workbook Case，standalone debug 執行一個 Template、Flow 或 Tool。ATT Remote 保留舊版 Server command。此共用 RUN model 不代表 proposed vNext CLI grammar 已發行；請參閱[規劃中的 vNext page](../../interfaces/cli/vnext-proposed.zh.md)。

## 驗證及執行 RUN

使用 ATT Studio 相同的已驗證 Server origin 和 context path，向 `POST /api/v1/drafts/run` 提交：

```json
{
  "schemaVersion": "att-execution/v1",
  "executionType": "run",
  "packageId": "payments",
  "environment": "SIT",
  "target": {"type": "template", "id": "PAYMENT"},
  "input": {"schemaVersion": "att-debug/v1.2", "inputs": {"amount": 100}},
  "options": {"debug": false}
}
```

將 `payments`、`SIT`、`PAYMENT` 換成已設定的套件值。檢閱安全預覽後，向 `POST /api/v1/jobs/run` 提交 `{"packageId":"payments","draftId":"R..."}`。工作沿用現有狀態、取消、事件、結果和 artifact 路徑。開啟頁面或切換模式不會提交工作。

請求遵循 `schemas/att-execution-v1.schema.json`。未知欄位、混合 target/selection 的請求均會被拒絕。Draft 有效期為十分鐘，綁定驗證身分及套件 revision。套件改變後必須重新驗證。無效輸入回傳 HTTP 400、不可用 draft 回傳 404、revision 改變回傳 409、工作或 draft 容量已滿回傳 429。Inspection 的容量、時間及大小限制仍然生效。

單一 Case 使用 `target:{"type":"case","id":"payments.default.PAY001"}`，並省略 `input`。Case 沿用 Excel/sidecar 綁定。Template、Flow、Tool 保留 Debug sidecar 的省略、明確 null、literal、expression、vars 和 Tool arguments 語義；不會將 Case 參數轉成Resource輸入。

## 選取批次

以明確 selection 取代 target 和 input：

```json
{
  "schemaVersion": "att-execution/v1",
  "executionType": "run",
  "packageId": "payments",
  "environment": "UAT",
  "selection": {
    "suites": ["testcase/payments.xlsx"],
    "caseIds": ["payments.default.PAY001"],
    "tags": ["smoke"],
    "excludeTags": ["slow"]
  },
  "options": {"debug": true, "failFast": false}
}
```

`suites` 與 `suiteDirectory` 互斥；後者選取套件內的 group 目錄。Case/tag 篩選沿用 CLI 交集規則，任一要求的 tag 可匹配，excludeTags 則排除匹配 Case。`all:true` 表示套件選取。陣列必須非空、不重複，最多 1000 個值。驗證會解析並固定最多 10000 個 Case ID；空選取不會退回執行全部 Case。

`rerunFailed:true` 從呼叫者最近 100 個擁有的工作中，選取同套件最近一個已完成 Case RUN 的失敗項目，與明確 Case/tag 篩選取交集，再固定 ID。Manifest 不可用或沒有匹配失敗項目會驗證失敗。既有 CLI `--rerun-failed` 繼續使用本機 output history。

## 啟用 Debug 診斷

`options.debug:true` 會保留額外 Debug 執行事件，不會增加次數、並行度、權限或啟用 unsafe failure details。Server 中央大小限制、披露政策、敏感資料過濾和 artifact 控制仍然適用。功能測試和 Resource 執行保留專用報告，並共用工作路徑。

## 排程已驗證的定義

Canonical 建立請求使用 `schemas/att-schedule-v2.schema.json`；舊路由保留原有的選用欄位。

向 `POST /api/v1/schedules` 建立 RUN 排程：

```json
{
  "schemaVersion": "att-schedule/v2",
  "name": "Nightly payment checks",
  "executionType": "run",
  "packageId": "payments",
  "environment": "SIT",
  "draftId": "R...",
  "timezone": "Asia/Hong_Kong",
  "cron": "0 2 * * *",
  "maxConcurrentRuns": 1,
  "enabled": false
}
```

使用驗證回傳的 draft ID。RUN 排程保存已驗證輸入、Case 選取及 revision，而非可變查詢。套件、目標、environment、config 改變後必須建立新排程。受保護的套件預設值以路徑綁定 keep marker 表示；提交的敏感覆寫值不可持久化。

LOAD 排程繼續支援套件 scenario 路徑及已驗證 Load draft，沿用 scenario 驗證及負載上限。舊式 package-path LOAD 排程每次執行前驗證目前套件 scenario；公開 `revisionPolicy` 區分此模式與 revision 綁定 RUN snapshot。

可使用五欄 `cron` 加 IANA timezone，或單次 `runAt` instant，例如 `2026-10-20T02:00:00+08:00`；不可同時提供。排程只保留一個 trigger：更新時提供任一欄位會取代已儲存的 trigger，兩者皆未提供則保留現有 trigger。單次觸發只會被領取一次。春季 DST 不存在的本地時間會跳過；秋季重複時間可產生兩個 instant，但仍受最短間隔限制。啟用前可用 `POST /api/v1/schedules/preview` 預覽時間。

共用 scheduler 先記錄 occurrence，再提交工作。每排程及全域 overlap 容量已滿會跳過該次；一般 Worker/Load admission 上限仍生效。重啟或延誤 misfire 不會補跑。提交失敗遵循 `server.scheduling.failurePolicy`，不會自動重試執行。取消工作只取消該次 occurrence，暫停排程則阻止未來觸發。工作與 occurrence retention 沿用 Server 上限。

所有 active local `USER` 或通過 `server.authorization.userRole` 的 authenticated container principal 均可管理共用 RUN/LOAD 排程。`ADMIN` 僅有系統及帳戶管理權限。排程建立者會保留作為 audit 資訊，但不限制其他 active USER 查看、編輯、暫停、觸發或刪除排程。Local auth 的排程建立者在 scheduled RUN 觸發時必須仍是 active `USER`。Container role 會在每個管理請求檢查；背景 scheduler 無法查詢外部 realm，因此建立者失去 USER role 時，其他 operator 必須暫停排程。Scheduled RUN job 仍以建立者身分記錄 audit。

## 遷移既有用戶端

| 既有介面 | 4.2.8 對應 |
|---|---|
| CLI `validate`、`run`、`report` | 保留驗證、Case RUN、報告命令 |
| CLI `--suite`、`--suite-dir`、`--case`、`--tag`、`--rerun-failed` | 保留選取行為；Server 批次使用 `selection` |
| Debug CLI、sidecar、`/jobs/debug`、`/drafts/debug` | 相容Resource RUN，保留舊式診斷行為 |
| `/jobs/run` 舊式 Case selection | 保留 Case adapter |
| `/load-schedules`、`jobType:load/debug` | 共用 scheduler 相容路徑，保留既有定義 |
| `/jobs/...`、既有 Studio 工作/結果連結 | 保留共用 history、取消、事件與結果路徑 |

4.2.8 沒有移除舊 endpoint。`GET /api/v1/version` 公開 `unifiedRunVersion:att-execution/v1`。新 Studio Resource表單在舊 Server 缺少此能力時，可使用舊 Debug draft。統一 Case/batch draft 需要 4.2.6；舊部署可繼續使用 Explorer Case 操作。Server/Worker 應配套部署，舊 Worker 不支援Resource RUN dispatch。

持久化使用既有 H2 table 的附加 JSON 欄位，不改寫舊 LOAD row。降版前先停止排程、匯出新定義、移除 RUN/單次定義，再還原升級前資料庫備份，避免舊 scheduler 把 RUN 當成 LOAD。保留的 artifact 與資料庫 metadata 分開儲存。

## Replay 與從 Job 建立新執行

在 **Jobs**、**Job Details** 或 **LOAD History** 選擇 **Rerun with same settings** 或 **Configure & Rerun**。兩者都會建立一個獲授權的 Server draft，並在執行前依目前 Package/Environment 驗證。標準 RUN 或 LOAD editor 只會收到不透明的 `draftRef`，進入後再向 Server 取得 sanitized 定義；定義不會放入 URL 或 browser storage。

**Rerun with same settings** 預設使用 **Use current Package**：沿用 Job 中安全保留的執行參數，依目前 Package 重新解析 Target 與設定，再顯示原始／目前 revision 及驗證結果供確認。它不會在無提示下以最新 YAML defaults 取代保留的 Scenario。**Require original revision (strict)** 是重現選項。Revision 不符時回傳 `ATT-SERVER-REPLAY-REVISION-CONFLICT`，並提供 **Review with current Package**；儲存的 metadata 無法還原已缺失的歷史 source bytes。`server.scheduling.packageChangePolicy` 及 Schedule 層級的 `ignore` 只套用於排程執行，不會控制手動 Rerun。

**Configure & Rerun** 會在一般 editor 預填支援且安全保留的設定。可修改 RUN Target／Case selection、options 與安全 inputs，或 LOAD timing、workloads、模型專屬 pacing、concurrency、thresholds、evidence 及安全 testdata selection。**Validate** 會以有界 typed overrides 建立新 review intent；**Review changes** 顯示前後值、LOAD phases 總和、目前 revision，以及 configured arrival rate 與 measured TPS 的差異。按 **Run** 會以 `rerunOfJobId` 建立獨立 Job；Cancel／Back 不會修改來源 Job、報告、Schedule 或 package files。Package-file-backed LOAD 使用安全保留的 package-relative source route，不套用僅 inline draft 使用的 duration 上限。

Schedule 維持獨立流程：**Edit existing schedule**、**Create new schedule** 或 **Run now**。既有排程 `strict`／`pause`／`ignore` 語意不變。

新保留的 execution spec 使用 `att-job-execution/v2`，parser 仍接受 v1。Replay draft 使用 `att-job-replay-draft/v1`。Draft reference 和 confirmation intent 綁定目前 principal，十分鐘後到期；兩者存在 Server 記憶體，Server restart 後會失效，需重新從來源 Job 建立。沒有安全保留定義的來源 Job、缺少敏感輸入、draft 過期、Resource 移除、schema 無效及容量拒絕會使用不同錯誤回應。系統不會從 log 或 artifact 重建秘密、Worker transport data、host root 或不安全路徑。

未保留執行定義的歷史 Job 會顯示 **Rerun unavailable for this historical job**。僅驗證的 Job、安全保留失敗或包含已分類敏感輸入的 Job，不能進行精確 replay。含有原始 overrides 或未解析 `rerunFailed` 歷史選擇的舊式直接提交，須先建立新的驗證 draft 才能安全保留。請設定新執行，並透過正常的受保護輸入流程補充憑證。不可從 log、artifact 或已遮罩預覽重建執行。安全定義上限為 256 KiB，不包含 Worker transport 欄位及主機根目錄。

Job lifecycle API 在 `/api/v1` 下提供以下 endpoint：

| Method 與 path | 結果 |
| --- | --- |
| `GET /jobs/{jobId}/replay` | 已授權的安全定義及不可用原因。 |
| `POST /jobs/{jobId}/replay-draft`，body 為 `{"sourcePolicy":"current"}` 或 `{"sourcePolicy":"strict","overrides":{...}}` | 驗證 `att-job-replay-draft/v1`、不透明 `draftRef` 及確認 `intentId`；不建立 Job 或 Schedule。省略 `sourcePolicy` 時沿用舊版 strict 行為。 |
| `GET /jobs/{jobId}/replay-draft/{draftRef}` | 綁定 principal 的 sanitized editor 定義及目前 configuration provenance；route 不包含定義。 |
| `POST /jobs/{jobId}/rerun`，body 為 `{"intentId":"...","confirm":true,"sourcePolicy":"current"}` | 建立獨立 Job，回傳 HTTP 202 及 `Location`；相同 intent 重試時回傳已接受的 Job。 |

Schedule 使用一般已驗證 draft 合約。RUN Debug 仍使用 `options.debug`。Replay 限制於提交者或保留的排程執行 owner。若 Package 在驗證後改變，回傳 `ATT-SERVER-REPLAY-DRAFT-STALE`；draft 過期或 Server restart 後遺失則回傳 `ATT-SERVER-REPLAY-DRAFT-EXPIRED`；容量限制回傳 HTTP 429。重用已接受 intent 會回傳同一 Job。
