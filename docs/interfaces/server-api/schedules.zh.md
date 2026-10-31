<!-- att-doc-status: current release=4.2.8 -->

# Schedule API

Schedule 是由 Server 擁有的定義，可於稍後提交 Job，但它不是 Local Run ID；只有 occurrence 被接納後才會建立 Job。

目前 REST route table、request shape、authorization 及 occurrence-history path 定義於 [REST API v1](v1.zh.md#rest-api)。Browser Schedule 畫面請參閱 [Studio Schedules](../studio/schedules.zh.md)。

Package revision policy、保留的 history、overlap limit、shutdown 及 recovery 行為請參閱 [Jobs and Schedules operations](../../operations/server-jobs-schedules.zh.md)。

Legacy path 名稱 /load-schedules 仍受目前 4.2.8 REST API 支援。本 interface 不會宣告 #294 未來 CLI management command 已提供。

## 審閱並替換執行設定

`POST /api/v1/schedules/{id}/execution-draft` 驗證執行設定變更，不會提交 Job。傳入 `expectedDefinitionVersion`，以及 RUN 或 inline LOAD 快照的 `overrides`，或 package-path LOAD Schedule 的 `packageEdit`。回應包含短期有效的 `draftId`、安全的變更前後預覽、Package 版本、政策及下次執行時間。審閱綁定建立它的已認證操作使用者。具備排程操作權限的使用者可審閱共用 Schedule；預覽只包含安全摘要，不會公開底層執行 draft，套用時亦會重新檢查執行身分。工作負載 override 可用 `mixEdits` 按 ID 更新現有 mix 項目的權重或 target；未變更的項目保留 inputs，變更 target 則會清除該項目的 inputs 和 variables。

使用 `PATCH /api/v1/schedules/{id}/execution` 確認審閱，JSON 內容包括 `draftId`、`expectedDefinitionVersion` 和 `confirm: true`。成功後，Schedule ID 與觸發條件不變，`snapshotVersion` 遞增，新設定用於其後領取的執行。已領取的 occurrence 和已接受的 Job 保留原有輸入。定義、draft 或 Package 版本過期時，須重新審閱。Schedule 最多保留 20 筆安全的快照歷史，包括前後版本、操作者、時間、revision 與概括的變更摘要；較舊記錄可能沒有新增欄位。Job 自身的執行證據依 Job 保留政策處理。

Package 路徑編輯仍以 Package 路徑執行。Package Catalog 快照按 package-backed 限制重新驗證後仍保持為快照，即使停用瀏覽器建立 inline load 亦然。沒有保留 Package 版本的舊 Schedule 不能使用此替換端點。
