## 06 環境與測試資料

Environment selection 改變 resource binding，不改變 Action logic。

### Environment profiles

`att-config/v2.10` 定義現行 `environment` default 與 `environments` map。`--config` 選擇共用 configuration；`--env` 選擇 profile 並覆蓋 default。名稱比對不區分大小寫。未知 profile 會在 external execution 前失敗。

Profile 是 typed shallow binding，不是 generic recursive YAML inheritance。Profile 可整組替換 `dbhelpers`、`mqhelpers`、`sshhelpers` 或 `httphelpers` list；未提供的 list 會繼承 common root list。

```yaml
schemaVersion: att-config/v2.10
environment: SIT
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
    sshhelpers: [config/sshhelpers/sit/application.yaml]
    httphelpers: [config/httphelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
    sshhelpers: [config/sshhelpers/uat/application.yaml]
    httphelpers: [config/httphelpers/uat/payment.yaml]
```

各環境應提供相同的 stable logical ID（例如 `orders`、`payment`、`application`），Template、Flow、Action 和 Tool-group binding 才能在 SIT/UAT/PREPROD 之間保持不變。SSH endpoint detail 不會公開為 `META.SSHHELPER`；見[SSHHelper 章](05_resources/sshhelper.md)及[Runtime Context 欄位清單](03_runtime_context.md)。

### Topology 與 secrets

Topology 可隨 descriptor/environment 改變。在 descriptor 支援處使用 `${ENV:NAME}` 注入 secret；不可提交，也不可將解析後的值公開在 META、report 或 diagnostic。缺少 required variable 時會指出 field/name，但不列印 secret。

### Cross-mode consistency

Run、Validate、Debug、Load 透過同一 effective configuration 解析所選 environment。`--env` 不是 Action branching，也不會產生 mode-specific helper ID。

### 分開的 configuration files

若 package roots、report policy、Tool topology 或其他 config 刻意不同，可繼續使用 `--config config/environments/sit.yaml` 與 `uat.yaml`。若 package contract 相同而只改 resource binding，使用 profiles。

### Test data 擴充位置

Workbook/sidecar/snapshot 仍是 Testcase data contract。Environment-bound business input 放在 `EXEC.INPUT`；environment selection 屬於 configuration，不是 Action expression。
