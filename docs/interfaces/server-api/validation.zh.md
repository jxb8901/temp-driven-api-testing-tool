<!-- att-doc-status: current release=4.2.10 -->

# Server Validation API

`POST /api/v1/validation` 會同步執行 Server 端既有 validator，並回傳 `att-validation/v1` report。Studio 透過此有界唯讀 route 驗證 Server Runtime Config、已註冊 Package 或單一所選 resource，不會建立 execution draft 或 Job。Server 是 validation、authorization、生效設定及 resource revision 的唯一權威來源。

## Endpoint 與 capability

| 方法 | 路徑 | 結果 |
|---|---|---|
| `POST` | `/api/v1/validation` | 同步回傳 `att-validation/v1` report；不建立 Job。 |
| `POST` | `/api/v1/validations` | 接受持久非同步 validation record；回傳 `202`。 |
| `GET` | `/api/v1/validations/{validationId}` | 讀取 owner 範圍內的 record 及 report。 |
| `GET` | `/api/v1/validations` | 列出已驗證用戶近期的有限筆 record。 |
| `POST` | `/api/v1/validations/{validationId}/cancel` | 取消 owner 的排隊中或執行中 record。 |

支援此 route 及 report contract 的 Server 會在 `GET /api/v1/version` 宣告 `validationApiVersion: "att-validation/v1"`。Studio 會先檢查此 marker，再啟用 **Read-only validation**。若 marker 不存在或版本不同，代表 Server build 沒有宣告所需 capability。

兩種 submit route 都會透過有界 inspection Worker 呼叫既有 Server validator，並採用相同 typed request contract。它們不會執行 business action、提交 RUN/LOAD 工作、建立 execution draft、建立 Schedule 或保存 snapshot。同步 route 仍供相容 client 使用；需要保存進度及結果供之後恢復時，請使用非同步 route。

## Request

請使用 `Content-Type: application/json`。

允許欄位：`command`、`targetKind`、`target`、`packageId`、`environment`、`all`。

| 欄位 | Contract |
|---|---|
| `command` | 必填，必須是 `validate`。 |
| `targetKind` | 選填，預設為 `config`。支援 `config`、`package`、`case`、`suite`、`template`、`flow`、`tool` 及 `load` 範圍。 |
| `target` | 選定 resource 範圍必填。Case 使用 logical Case ID；Suite 及 LOAD 使用 package-relative path；Template、Flow 及 Tool 使用 Server 的 canonical package-local resource ID。`config` 及 `package` 不接受此欄位。 |
| `packageId` | `package` 及選定 resource 範圍必填。`config` 可選；提供時會選取該已註冊 Package 的生效 Config。不提供則驗證 Server Runtime Config。 |
| `environment` | Package 範圍的 active Environment Profile，選填。省略時由 Server 解析 Package 的 effective/default Environment，並放入 report context。Server 會套用設定的授權政策。 |
| `all` | 選填 boolean。設為 `true` 會驗證整個安裝範圍。不可與 `targetKind`、`target`、`packageId` 或 `environment` 同時提供；此範圍需要 system-administrator 權限，Studio 不會提供此功能。 |

範例：驗證 `payments` 在 `SIT` 的生效 Config：

```json
{
  "command": "validate",
  "targetKind": "config",
  "packageId": "payments",
  "environment": "SIT"
}
```

全安裝驗證 request 為 `{"command":"validate","all":true}`，不可指定 Package、target 或 Environment。

## Response

驗證完成後會回傳 HTTP `200`，即使 report 記錄失敗亦然。JSON response 遵循 [att-validation/v1 schema](../../../schemas/att-validation-v1.schema.json)，包含：

| 欄位 | 意義 |
|---|---|
| `schemaVersion` | 固定為 `att-validation/v1`。 |
| `scope`、`backend` | 所選範圍及 `SERVER`。 |
| `sections`、`diagnostics` | 階層式檢查項目及攤平後的 findings。 |
| `counts` | `pass`、`fail`、`warn`、`skip`、`errors` 及 `warnings` 數量。 |
| `overallStatus`、`complete`、`exitCode` | `PASS`、`INVALID` 或 `PARTIAL`；完整性；與 CLI 相容的結果代碼。不完整 report 不代表通過。 |
| `context` | 選填的安全 validation context；適用時包含解析後的 Package 及 Environment identity。 |
| `requestId` | Server request identifier，供追蹤使用。 |

Validation finding 會放在 report body；HTTP `200` 不代表所有檢查均通過。Response 不會回傳 Job ID 或 draft ID。

## Authorization 與錯誤

每個 request 都需要已驗證的 Server principal。範圍檢查需要操作權限（設定中的 Server operator role 或 active local USER account）；`all: true` 需要 system-administrator capability。Endpoint 會套用 Server 的 Origin/CSRF policy、request 大小限制、inspection Worker 容量及 response 大小限制。Server 會逐次檢查 Package 及 Environment access。

| HTTP 狀態 | 意義 |
|---|---|
| `400` | JSON 格式錯誤、不支援的欄位或無效 validation plan。 |
| `401` | 需要身份驗證或 session 已失效。 |
| `403` | Operator/system-administrator 權限或 Environment authorization 被拒絕。 |
| `404` | 找不到已註冊 Package 或所選 validation target。 |
| `409` | Source revision 已變更或 request 被判定為過期。 |
| `413` | Request 或 report 超出已設定大小限制。 |
| `415` | Request 不是 JSON。 |
| `500` | 已淨化的 validation failure；不會回傳 Server stack trace。 |
| `503` | Inspection Worker 容量已滿。 |
| `504` | 唯讀 validation 超過時限。 |

Studio 範圍選擇、上下文入口、report 檢視及取消行為請參閱 [Studio Read-only validation](../studio/validation.zh.md)。

## 持久非同步 record

`POST /api/v1/validations` 接受與同步 route 相同的 JSON 欄位。Server 先將標準化 plan 及初始 `QUEUED` record 寫入資料庫，再回傳 HTTP `202`、`validationId` 及 `Location` header。Admission 會保留既有有界 inspection 容量；佇列已滿時回傳 `503`。

Record 包含 typed plan identity、生命週期狀態、計時、選用的安全 source revision、安全錯誤資訊，以及完成後的 `att-validation/v1` report。它不會保存提交的 source text、任意 filesystem path 或 client 提供的 configuration。讀取或列出時，Server 會再次檢查已註冊 Package 及 Environment 權限。Record 只供 owner 存取；installation-wide record 另需 system-administrator 權限。List endpoint 不會列出其他 owner 的 record。

`GET /api/v1/validations` 接受選用的 `packageId`、`scope`、`limit` 及不透明 `cursor` query parameter。`limit` 預設為 `20`，範圍為 `1` 至 `100`。Response 包含 `items` 及 `nextCursor`；list item 不含 report body。結果採用有限筆數，並依最新提交時間排序。已撤銷或不可用的 Package 或 Environment 不會出現在結果中。

`GET /api/v1/validations/{validationId}` 會在可用時回傳 record 及 report。`POST /api/v1/validations/{validationId}/cancel` 會在 Server 端取消排隊中或執行中的 record。若 record 已結束，request 會回傳目前狀態。關閉 browser view、切換 route 或停止 polling 不會取消 record；只有明確呼叫此 endpoint 才會要求取消。

Transport state 與 report outcome 分開表示：

| Record state | 意義 |
|---|---|
| `QUEUED` | 持久 admission 成功，正在等候 inspection slot。 |
| `RUNNING` | 既有 validator 正在執行；phase 可能無法取得。 |
| `COMPLETED` | 已產生 report；請讀取 `overallStatus`、`complete` 及 `exitCode`。 |
| `FAILED` | Server 無法產生 report。 |
| `TIMED_OUT` | 已達設定的 inspection 時限。 |
| `CANCELLED` | Owner 要求 Server 端取消。 |
| `INTERRUPTED` | Server shutdown 中斷 validation。 |

Report result 仍是 `PASS`、`INVALID` 或 `PARTIAL`。只有 `overallStatus: PASS`、`complete: true` 及 `exitCode: 0` 才代表通過。API 會把 `applicability` 設為 `UNVERIFIED`；歷史 PASS 不能證明目前 source 沒有變更。只有 validator 提供安全 revision digest 時才會包含 `sourceRevision`。

預設保留 record 七日。可在 `att-server.yaml` 設定 `server.validationRecords.enabled`、`retentionDays`、`maxRecords`、`maxTotalSizeMb` 及 `maxRecordBytes`，調整保留時間與儲存上限。預設為啟用、`7` 日、`5000` 筆、`64` MiB 及每筆 `2097152` bytes。若 `maxTotalSizeMb` 低於每筆預設上限，系統會調低每筆預設值以符合總上限。系統會刪除過期或最舊的終止 record 以符合上限，不會淘汰作用中的 record。若記錄庫沒有空間，admission 會回傳 `503`。此功能及 inspection Worker 啟用時，`/api/v1/version` 會宣告 `validationRecordsApiVersion: "att-validation-records/v1"`。

Server 啟動時，狀態仍為 `QUEUED` 或 `RUNNING` 的 record 會轉成 `INTERRUPTED`。已完成 record 會保留給同一個已驗證 owner，直到保留期限或儲存清理移除。保留 record ID 的 route 或 bookmark，可在重新導覽或重載 browser 後開啟 report。
