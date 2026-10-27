<!-- att-doc-status: planned release=unreleased issue=287 -->

# Proposed Server Validation API

> **規劃中，尚未發佈。** 此擬議 route 不屬於 ATT 4.2.5。現行 path-based validation 請使用 [REST API v1](v1.zh.md) 的 `POST /api/v1/jobs/validate`；該 endpoint 會提交非同步 Job。

## Endpoint

| 方法 | 路徑 | 結果 |
|---|---|---|
| `POST` | `/api/v1/validation` | 同步、唯讀的 `att-validation/v1` report；不會建立 Job。 |

此 endpoint 透過有界的 inspection Worker 執行 Server-side validation，不會提交 RUN/LOAD Job 或執行業務 I/O。

## Request

請使用 `Content-Type: application/json`。

允許欄位：`command`、`targetKind`、`target`、`packageId`、`environment`、`all`。

| 欄位 | Contract |
|---|---|
| `command` | 必填，且必須是 `validate`。 |
| `targetKind` | 選填；預設為 `config`。支援 `config`、`package`、`case`、`suite`、`template`、`flow`、`tool` 及 `load`。 |
| `target` | `case`、`suite`、`template`、`flow`、`tool` 及 `load` 必填。Case/Resource 使用邏輯 ID；Suite/Load 使用 Package-relative path。`config` 和 `package` 不接受此欄位。 |
| `packageId` | `package` 和選中目標 scope 必填。`config` 可省略；提供時必須是已註冊 Package。 |
| `environment` | 選填的 Environment Profile，供 Package scope validation 使用；Server 會套用已設定的 allow policy。 |
| `all` | 選填 boolean。設為 `true` 時驗證整個安裝範圍；不得與 `targetKind`、`target`、`packageId` 或 `environment` 同時提供。 |

Runtime Config 由 Server 管理。Client 不可傳送 Config 檔案路徑或其他任意欄位。省略 `packageId` 的 Config check 會檢查 Server Runtime Config；提供已註冊 `packageId` 時，則檢查該 Package 的有效 Config。Package 和選中目標檢查需要已註冊的 Package ID。

以下範例檢查 `payments` Package 在 `SIT` 的有效 Config：

```json
{
  "command": "validate",
  "targetKind": "config",
  "packageId": "payments",
  "environment": "SIT"
}
```

整個安裝範圍的 request 為 `{"command":"validate","all":true}`，不得指定 Package、target 或 Environment。

## Response

完成 validation 後會回傳 HTTP `200`；即使 report 記錄 validation failure，HTTP 狀態仍為 200。JSON report 遵循 [att-validation/v1 schema](../../../schemas/att-validation-v1.schema.json)，並包括：

| 欄位 | 說明 |
|---|---|
| `schemaVersion` | 固定值 `att-validation/v1`。 |
| `scope`、`backend` | 請求 scope 及 `SERVER`。 |
| `sections`、`diagnostics` | 階層式狀態區段及攤平後的診斷。 |
| `counts` | `pass`、`fail`、`warn`、`skip`、`errors` 及 `warnings` 數量。 |
| `overallStatus`、`complete`、`exitCode` | 整體 `PASS`、`INVALID` 或 `PARTIAL`；完整性；與 CLI 相容的結果代碼。 |
| `context` | 選填 validation context。 |
| `requestId` | Server request identifier，供追蹤使用。 |

Validation finding 會放在 report body；HTTP `200` 不代表所有區段均通過。回應不會附上 Job ID。

## Authorization and errors

每個 request 都需要已驗證的 Server principal。一般 scope 需要操作權限（設定中的 Server operator role 或 active local USER account）；`all: true` 需要系統管理員能力。Endpoint 亦會套用 Server 的 Origin/CSRF policy 及 request/inspection response 大小限制。

| HTTP 狀態 | 說明 |
|---|---|
| `400` | JSON 格式錯誤、不支援的欄位或無效 validation plan。 |
| `401` | 需要驗證身份。 |
| `403` | Operator／系統管理員權限或 Environment authorization 被拒絕。 |
| `404` | 找不到已註冊 Package 或選中 validation target。 |
| `413` | Request 或 report 超出大小限制。 |
| `415` | Request 不是 JSON。 |
| `500` | 已淨化的 validation failure；不會回傳 Server stack trace。 |
| `503` | Inspection Worker 容量已滿。 |
| `504` | 唯讀 validation 超過時間限制。 |

這些 route 和行為在 #287 發佈並完成 API contract 核實前均屬 Planned。
