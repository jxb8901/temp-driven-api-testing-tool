# 舊式 Authoring

本指南說明為既有 Package 保留的 Workbook 撰寫契約。

## Resource 流程

```text
Workbook row -> Stage -> Template -> ordered Actions -> Tools and Helpers
                                         `-> Flow
```

Testcase row 提供 Case 資料。YAML sidecar 定義 worksheet groups、headers、columns 和 Stage selectors。產生 Snapshot 會在 Workbook 旁寫入 canonical XML 表示。Stage 選擇 Template 並提供 Stage 專屬值。Template 宣告有序 Actions，亦可呼叫 Flow。Flow、Tool、DBHelper、MQHelper、HTTPHelper、SSHHelper、scripts 和 Testdata 都屬於個別 Package。

## Workbook、sidecar 和 Snapshot 檔案

Workbook Package 使用相同 basename 的 `.xlsx`、`.yaml` 和 `.xml`，放在已配置的 testcase 目錄。YAML 檔使用 `att-sidecar/v2.2`；產生的 XML 檔使用 `att-testcases/v2.4`。修改 Workbook 和 sidecar 後，重新產生 XML Snapshot，檢查三個檔案，再驗證 Package。

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh snapshot --suite testcase/quick_start.xlsx
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh validate --package
```

不要手動修改產生的 XML。VALIDATE 只讀取現有檔案，不會建立或更新 Snapshot。

## Stage 和 Template

Stage selector 會識別一個 Template。Template descriptor 是 Package resource root 下的 `template.yaml`，使用 `att-template/v3.6`。Template 宣告非空的有序 `actions` map。Stage value 可在該 Stage 執行期間覆蓋同名 Case input；Stage 結束後覆蓋即失效。

Case ID 保留 Workbook identity path：`workbookId.groupId.rowCaseId`。若既有報告或自動化依賴這些 ID，請保持 Workbook ID、group ID、row ID 和 Stage key 穩定。

## Data columns 和空白值

Sidecar 的 `dataColumns` 字串支援逗號分隔欄位、別名、`(yaml)` parsing 和引號分隔符。來源欄必須明確選取，Testcase 才能使用其值。空字串、`N/A`、`NA`、`NULL` 和 `NONE` 等標記會在 mapping 和 Stage selection 前正規化為空白。

Excel formula cell、日期／百分比／科學記號 cell、多行標題、XML entity escaping 和大小上限，見維護中的[遷移矩陣](../maintenance/native-case-default-package-migration-s12.md)及 `att-testcases/v2.4` schema。

## 重用 Flow

Flow 是 `flow.yaml` 中以 `att-flow/v3.6` 宣告的有序 Actions。Template 可用靜態 canonical `use` ID 呼叫 Flow。每次 invocation 取得新的 `EXEC.ACTIONS` scope，返回後恢復 caller scope。`META.FLOW` 只在 invocation 期間存在。

## 相關契約

| Legacy schema | Active version |
|---|---|
| Workbook sidecar | `att-sidecar/v2.2` |
| Testcase Snapshot | `att-testcases/v2.4` |
| Template descriptor | `att-template/v3.6` |

精確 input、selector 和 validation 欄位由 `schemas/att-sidecar-v2.2.schema.json`、`schemas/att-testcases-v2.4.schema.json` 和 `schemas/att-template-v3.6.schema.json` 定義。Package-local Resource lookup 不會跨到其他 Package。
