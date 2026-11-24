# 統一 RUN 與排程

Native RUN 會執行 Test Case Definition 中所選的 Resolved Cases。每個 Case 會解析至 Flow 和 Data Source；開始執行前，ATT 會驗證所選的 dependency closure。新工作請從[快速入門](../../quick-start.zh.md)開始。以較早模型編寫的 Package 請參閱[舊版文件](../../legacy/README.zh.md)。

## 驗證及執行 RUN

使用 ATT Studio 相同的已驗證 Server origin 和 context path。向 `POST /api/v1/drafts/run` 提交 Native Case draft：

~~~json
{
  "schemaVersion": "att-execution/v2",
  "executionType": "run",
  "packageId": "payments",
  "environment": "SIT",
  "target": {
    "type": "nativeCase",
    "definitionId": "payments.authorize",
    "selection": {"resolvedCaseIds": ["accepted"]}
  },
  "options": {"debug": false}
}
~~~

請將 package、environment、Definition 和 local Case ID 換成已登錄的值。檢閱安全預覽後，向 `POST /api/v1/jobs/run` 提交 `{"packageId":"payments","draftId":"R..."}`。Draft 綁定驗證身分及 Package revision，十分鐘後到期。Package 改變後必須重新驗證。Preview 和 validation 不會提交 Job。

Native Suite draft 使用 `target.type: nativeSuite`、非空 `definitionIds` 清單，以及一種 selection：`all:true`、`qualifiedCaseIds` 或 tag filters。Included tags 使用 any-match semantics；excluded tags 優先。Validation 最多解析並固定 10,000 個 Case ID。空 selection 或未知值會驗證失敗，不會擴大成全部 Cases。

未知欄位或不合法組合會驗證失敗。輸入無效回傳 HTTP 400、不可用 draft 回傳 404、revision 改變回傳 409、Job 或 draft 容量已滿回傳 429。Inspection 大小、時間及並行上限亦適用。

每個 Resolved Case 會產生一次 Case execution，各自有 execution ID、status 和 evidence。Server draft 會凍結所選 Case ID 和 Package source revision；source 改變後必須重新驗證。已接受的 Job 使用標準 status、取消、event、result 和 artifact routes。

## 重跑失敗 Cases

Native Suite 的 `rerunFailed:true` 只會從同一 owner、Package、Definition IDs 和 Package revision 下，最近一個符合資格且已完成的 Native Suite RUN，選取已執行但失敗的 Cases。Server 按完成時間排列候選，不會只搜尋最近 100 個 Job。

本機執行會使用按 Package/Definition 區分並驗證 manifest hash 的 Native Suite pointer。Dry run 和不相關的 RUN 不會取代它。Server draft 會凍結來源 Job/run、manifest digest、revision、來源選取數、retry attempt number 和精確的失敗 Qualified Case ID。提交時會重新驗證來源；若驗證後有更新的相符 RUN 完成，便拒絕過期 draft。來源中的 Skipped Cases 不會重跑。新 RUN 只報告本次選取、執行和略過數；retry 次數與來源統計分開保存。Manifest 不可用或不一致、來源變更，或沒有已執行的失敗 Case 都會驗證失敗。

## 啟用 Debug diagnostics

設定 `options.debug:true` 會保留額外診斷事件。這不會重複執行、增加並行度或權限，也不會啟用不安全的 failure details。Server 大小限制、披露政策、secret filtering 和 artifact controls 仍然適用。

## 排程已驗證的定義

標準排程建立使用 `att-schedule/v2`。使用已驗證的 draft 建立 RUN schedule：

~~~json
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
~~~

RUN schedule 會保留已驗證的 inputs、Case selection 和 source revision。Package、target、environment 或 configuration 改變後，必須重新驗證並建立新定義。受保護的 Package defaults 會使用綁定路徑的 keep marker。提交的敏感 overrides 不可保存至 schedule。

可使用五欄 `cron` 加 IANA timezone，或單次 `runAt` instant；不可同時提供。更新若提供任一 trigger，會取代已保存的 trigger；兩者皆未提供則保留現值。單次 trigger 只會被領取一次。春季 DST 不存在的本地時間會跳過；秋季重複時間可產生兩個 instants，但仍受最短間隔限制。啟用前可用 `POST /api/v1/schedules/preview` 預覽時間。

Scheduler 會先記錄 occurrence，再提交執行。每 schedule 及全域 overlap 上限已滿時會跳過該次；一般 Worker 和 Load admission 上限仍生效。延遲或重啟期間錯過的觸發不會補跑。提交失敗遵循 `server.scheduling.failurePolicy`，不會自動重試 execution。取消 Job 只會取消該 occurrence；暫停 schedule 會阻止未來觸發。Job 和 occurrence retention 使用已設定的 Server limits。

所有 active local `USER` 或符合 `server.authorization.userRole` 的 authenticated container principal 均可管理共用 schedule。`ADMIN` 僅有 system 和 account administration 權限。建立者會保留作為 audit，但不限制其他 active USER 查看、編輯、暫停、觸發或刪除 schedule。使用 local authentication 時，scheduled RUN 提交時建立者必須仍是 active USER。Container roles 會在每個管理請求檢查；建立者失去指定 role 時，operator 必須暫停 schedule，因背景 scheduler 無法查詢外部 realm。

## Replay 或設定新執行

在 Jobs 或 Job Details 選擇 **Rerun with same settings** 或 **Configure & Rerun**。Server 會建立獲授權的 draft，並以目前 Package 和 Environment 驗證。Editor 只收到不透明的 `draftRef`，再向 Server 取得 sanitized definition；定義不會放入 URL 或 browser storage。

Rerun 會保留安全保存的參數，並在確認前顯示來源和目前 revisions。Strict source revision 選項在 revision 不符時拒絕執行，並讓使用者檢閱目前 Package。Configure & Rerun 會在一般 editor 預填支援且安全的設定，驗證變更後建立獨立 Job。Cancel 或 Back 不會修改來源 Job 或 Package。系統不會從 logs 或 artifacts 重建 secrets、transport data、host roots 或不安全路徑。

Draft reference 和 confirmation intent 綁定 principal，十分鐘後到期，且保存在 Server 記憶體中。Server restart 後必須從來源 Job 建立新 draft。Package 過期、draft 到期、Resource 移除、schema 無效或容量已滿均有不同回應。

[Run mode Reference](run.md) 說明本機 Native execution 和 output paths。[CLI Reference](../cli.md) 定義 command syntax 和 exit code。
