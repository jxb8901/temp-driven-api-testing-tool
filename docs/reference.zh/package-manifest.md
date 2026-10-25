# Package Manifest

使用 `<PACKAGE_ROOT>/package.yaml` 為單一 Package 宣告預設值、業務Resource根目錄及 Testdata inventory。此檔案可省略，schema 為獨立的 `att-package/v1`；權威定義來自相符 Engine JAR 內嵌的 registry。

Package ID 與實體根目錄來自可信註冊或既有 Local Package 選取。Manifest 不會註冊 Package、授權使用者或提供具特權的 runtime configuration。共用 Environment profiles、Tools 與 DB/MQ/HTTP/SSH Helpers 仍由 Global Configuration 管理。

## 宣告 Package 預設值

此範例選取中央既有 profile，並宣告 Package 自己的 Testdata。執行完整驗證前，先建立引用的 descriptor。

```yaml
schemaVersion: att-package/v1
metadata:
  name: Payments
  description: Payment regression resources
defaultEnvironment: SIT
resources:
  templatesRoot: templates
  testcaseRoot: testcase
testdata:
  descriptors: [testdata/accounts.yaml]
  environments:
    UAT: [testdata/uat-accounts.yaml]
    PROD: []
defaults:
  timeoutMs: 15000
  report:
    columns: {result: Result, durationMs: Duration}
  xml: {namespaceMode: preserve}
```

中央 `environments` 必須定義 `SIT`、`UAT` 及 `PROD`。某個 Environment 未出現在 `testdata.environments` 時，繼承 `descriptors`；存在的 list 會整份取代 inventory，`[]` 代表清空。

所有路徑使用 `/`，相對於已選取 Package。絕對路徑、`..`、不安全 symlink 及越界路徑均會被拒絕。檔案上限為 262144 UTF-8 bytes。未知欄位、重複 YAML keys、aliases、anchors 及 `x-` extensions 均不允許。

沒有 Manifest 時，使用慣例根目錄 `templates`、`testcase`，Package Testdata inventory 為空。Global `templates.root`、`testcase.root` 不再決定 Package 業務根目錄。

## 解析 Environment

明確 request 或既有 CLI `--env` 優先；未指定時使用 Package 的 `defaultEnvironment`，再回退至中央預設值。名稱會解析成中央 profile 的正式拼寫。Effective 檢視顯示實際 Environment 及來源。

Server 在解析後套用 policy。Package 預設值不能繞過允許清單或明確選取要求。新建 execution drafts、直接提交及 schedules 會保存已解析 Environment。從已驗證 draft 建立 schedule 而未提供 Environment 時，繼承 draft 的選取。日後修改預設值不能重新導向該 snapshot；來源 revision 檢查可能要求重新審閱。

Registry adapter 可透過 `FrameworkConfigLoader.loadContext` 與明確的 `PackageContext` 提供選取內容；Environment 優先序為 request → Context → Package → Global。來源證據只採用 adapter 提供的已註冊 Package ID，不會由資料夾名稱或登入身分推導。CLI Context 的持久化指令仍屬另一項變更。

執行結果在 `summary.configuration` 保留不含設定值的來源快照：標準 Environment、原始選取層、Global 與 Manifest 摘要、有效設定識別、Engine 版本與 revision、Schema Registry 摘要，以及欄位來源與邏輯撰寫路徑。RUN、Debug、Load 亦在受管理產物目錄寫入 `configuration.json`。Server Draft、排隊 Job、Schedule 定義保留核准快照，包含已註冊 Package ID 與驗證時的 Package revision；Job 歷史與結果在 Server 重啟後仍保留此資料。呼叫端不能提供這些內部核准欄位。

Worker 在執行前核對已核准的有效設定識別。採用 `ignore` Package 變更政策的 Schedule 會以目前來源重新驗證執行副本；已儲存的核准內容保持不變，執行證據另保留原核准設定識別以供比較。此證據不包含設定值、descriptor 紀錄、憑證或主機路徑。

## 套用 typed defaults

只允許以下執行與呈現預設值；未提供的欄位繼承 Global Configuration。

| Manifest path | 行為 |
|---|---|
| `defaults.timeoutMs` | 正整數，不得超過中央 `packagePolicy.maxTimeoutMs`；預設上限 300000 ms |
| `defaults.run.id.default` | 只接受 `timestamp` |
| `defaults.run.id.timestampFormat` | 使用既有 timestamp format 驗證 |
| `defaults.execution.runIdFormat` | 使用既有 Run/Load ID expression 契約 |
| `defaults.execution.debugIdFormat` | 使用既有 Debug ID expression 契約 |
| `defaults.report.fileNamePattern` | 使用既有報表檔名契約 |
| `defaults.report.columns` | 整份取代 column mapping；包括明確 `{}` |
| `defaults.report.html.caseLogInlineLimitBytes` | 整數 0–1048576 |
| `defaults.report.junit.caseLogEmbedThresholdBytes` | 整數 0–1048576 |
| `defaults.caseLog.yamlAnchors` | Boolean |
| `defaults.xml.namespaceMode` | `ignore` 或 `preserve` |

Manifest 不可覆寫 output directories、process output limits、scheduling limits、authentication、credentials、helper definitions、worker settings 或 schema locations。完整欄位白名單由 Engine 內嵌 Manifest schema 定義。

## 解析 invocation Testdata

Base inventory 屬於已選取 Package 及 Environment。不同 Package 可使用相同 descriptor ID，但不共用 records，也不匯入鄰近 Package 的檔案。

執行只驗證引用所需的 base Testdata dependency closure。未引用的缺失或無效 base descriptor 不會阻止 Quick Load。明確 invocation-local Debug/Load imports 仍採嚴格驗證：明確匯入的缺失或無效檔案會失敗。Invocation descriptor 以相同 ID 整份取代 base descriptor，不合併 fields 或 records；同一 layer 的重複 ID 仍屬錯誤。

完整 Package validation 檢查 Manifest 宣告的所有 descriptor layers，包括各 Environment lists。內建 default Package 可執行：

```bash
./att.sh validate --package --format json
```

按 diagnostics 中的 Package ownership、logical path、layer 與 descriptor ID 修正問題，再發布或分發 Package。

## 在 Studio 檢視及發布

選取已註冊 Package 後，開啟 **Configuration**，再選 **Package Configuration**。**Source**、**Declared**、**Effective**、**Compare with Global** 分別檢視 Manifest；**Shared Configuration** 保留中央共用設定。All Packages 共用 inspector 仍只使用中央來源。

Source reading 沿用既有 source browsing policy 及結構化遮蔽。已遮蔽 projection 只供閱讀。編輯需要 Server 明確啟用，以及已驗證、有效且 login 與明確註冊 Package ID 完全相同的 USER。其他 USER 保留一般操作；只有 ADMIN 的帳戶沒有隱含編輯權限。Login 相同不會自動註冊目錄。

1. 在 **Source** 編輯可見 Manifest，或建立新的可選 Manifest。
2. 選 **Create Manifest draft**，按目前 digest 暫存私有且會到期的 candidate。
3. 選 **Validate Manifest draft**，由 Worker 驗證 candidate，期間不修改 live file。
4. 審閱安全的 declaration、effective values、Environment 與 origins，再選 **Review validated Manifest**。
5. 選 **Publish reviewed Manifest**。Server 重新檢查 ownership、live digest、Global/Package dependencies 與 schema revision，再以 atomic replacement 寫入 `package.yaml`。

Studio 會保護已修改的 Manifest source 與尚未發佈的 Draft；建立 Draft 或明確捨棄 source 變更前，不能重新整理或切換檢視。離開頁面亦使用現有的未儲存變更確認流程。

發生衝突時需建立新 draft。發布前保存 audit intent，發布後記錄完成狀態。History 位於私有 Server data，API 只回傳 revisions，不回傳歷史原始 source。**Stage rollback** 只建立 candidate，仍需驗證與審閱。回復至沒有 Manifest 前的預設行為時，使用 canonical empty `att-package/v1` 檔案，審閱畫面會明確標示。

以下可信部署 policy 可省略；預設不允許編輯：

```yaml
server:
  packageConfiguration:
    sourceEditingEnabled: false
    maxDrafts: 128
    maxDraftsPerUser: 8
    draftTtlSeconds: 900
    maxHistory: 50
    allowedEnvironments: [SIT, UAT]
    requireExplicitEnvironments: [UAT]
```

`allowedEnvironments: []` 允許中央定義的任何 profile。`requireExplicitEnvironments` 在 execution validation 前拒絕隱含選取其列出的 profile。Draft 數量、到期時間、history 與 source size 均設上限。Source editing 亦要求 inspection 及既有已驗證 USER 授權流程。

## 遷移舊 Package inventory

Global `testdata` 及 profile `testdata` lists 已棄用，預設不使用。中央可暫時設定 `packagePolicy.legacyTestdata: true`，只為沒有 Manifest 的 Package 恢復舊 inventory。存在 Manifest 時完全採用 Package ownership，包括明確空 inventory。Migration warnings 會指出舊 ownership；相容 switch 應只在有期限的遷移期間使用。

Migration helper 依可信 Server 明確 registry 產生有版本的唯讀計畫。從 repository 執行，Python environment 需先安裝 `tools/requirements-migration.txt`：

```bash
python tools/migrate_package_config.py --config config/config.yaml --server-config /ABSOLUTE/PATH/att-server.yaml
```

將 `/ABSOLUTE/PATH/att-server.yaml` 換成部署 registry。Helper 掃描各已註冊 Package 的業務文字與受限 XLSX text，只建議屬於本 Package 且被引用的 descriptors；保留 Environment 明確空 lists，略過既有 Manifest，並報告缺失、未使用或不安全 imports。它只列出繼承預設值的欄位名稱，不輸出其值或 descriptor records。重複 mapping keys 或 YAML aliases 會令 inventory 失敗。工具不發布檔案，也不推斷註冊。

審閱各 proposed Manifest，補齊所需 descriptors，再執行完整 Package validation。透過 source workflow 或受控部署流程發布後，移除舊 Global inventories 並停用 `legacyTestdata`。

## API paths

以下路徑相對於 `/api/v1`，沿用已驗證 USER、origin、CSRF、source policy 與 request size 限制。

| Method 與 path | 結果 |
|---|---|
| `GET /packages/{id}/package-configuration/{source\|declared\|effective\|compare}` | 安全 projection；effective selection 可提供 `environment` query |
| `POST /packages/{id}/package-configuration/drafts` | 暫存 `{source, expectedManifestDigest}` |
| `POST /packages/{id}/package-configuration/drafts/{draftId}/{validate\|review\|publish\|discard}` | 以 `{}` 推進私有 draft |
| `GET /packages/{id}/package-configuration/history` | 只供 owner 存取的有上限 revision list |
| `POST /packages/{id}/package-configuration/rollback` | 暫存 `{revision, expectedManifestDigest}`，等待審閱 |

來源 revision 衝突回傳 `409`；source 存取或 Environment policy 拒絕回傳 `403`；draft 到期或不可用回傳 `404`；draft 容量不足回傳 `429`。發布不會執行測試或修改中央 profile definitions。
