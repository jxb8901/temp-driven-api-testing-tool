<!-- att-doc-status: current release=4.2.10 -->

# Server Validation API

`POST /api/v1/validation` 會同步執行 Server 端既有 validator，並回傳 `att-validation/v1` report。Studio 透過此有界唯讀 route 驗證 Server Runtime Config、已註冊 Package 或單一所選 resource，不會建立 execution draft 或 Job。Server 是 validation、authorization、生效設定及 resource revision 的唯一權威來源。

## Endpoint 與 capability

| 方法 | 路徑 | 結果 |
|---|---|---|
| `POST` | `/api/v1/validation` | 同步回傳 `att-validation/v1` report；不建立 Job。 |

支援此 route 及 report contract 的 Server 會在 `GET /api/v1/version` 宣告 `validationApiVersion: "att-validation/v1"`。Studio 會先檢查此 marker，再啟用 **Read-only validation**。若 marker 不存在或版本不同，代表 Server build 沒有宣告所需 capability。

此 route 會透過有界 inspection Worker 呼叫既有 Server validator。它不會執行 business action、提交 RUN/LOAD 工作、建立或保存 draft、建立 Schedule 或保存 snapshot。在 Studio 取消時會中止 browser request 並丟棄較晚回傳的結果；Server 仍會套用已設定的 validation 時間及資源上限。

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
