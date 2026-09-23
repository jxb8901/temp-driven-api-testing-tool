## 06 環境與測試數據

Environment selection 改變 resource binding，不改變 Action logic。

### Environment profiles

`att-config/v2.6` 可以定義 `environment` default 與 `environments` map。`--config` 選擇 base configuration file；`--env` 在該 configuration 內選擇 named binding。明確 `--env` 優先於 configured default；未知 environment 在任何 external execution 前失敗。

Profile 是 typed shallow binding，不是 generic recursive YAML inheritance。目前 profile 可擁有 `dbhelpers`、`mqhelpers` list：profile 明確提供某 list 時會取代 root 的該 resource list；沒有提供的 list 則繼承 common root list。

```yaml
environment: SIT
environments:
  SIT:
    dbhelpers: [config/dbhelpers/sit/orders.yaml]
    mqhelpers: [config/mqhelpers/sit/payment.yaml]
  UAT:
    dbhelpers: [config/dbhelpers/uat/orders.yaml]
    mqhelpers: [config/mqhelpers/uat/payment.yaml]
```

不同 environment 的 descriptor 應暴露相同 stable logical ID（例如 `orders`、`payment`），因此 Template/Flow/Action 在 SIT/UAT/PREPROD 之間不需要修改。

### Topology 與 secrets

Topology 可以隨 descriptor/environment 改變。Secret 應在 descriptor 支援的位置使用 `${ENV:NAME}` 注入，不應提交到 repo，也不應出現在 effective metadata/diagnostic。缺少 required environment variable 屬 validation/configuration error；錯誤會指出 field/variable name，但不列印 resolved secret。

### Cross-mode consistency

Run、Validate、Debug、Load 在 mode-specific 工作前都經過相同 effective-config environment resolution。因此 `--env` 不是 Action branching，也不會產生 mode-specific helper ID。

### 從獨立 config 遷移

原有 `--config config/environments/sit.yaml` / `uat.yaml` 工作方式仍可使用。若 package contract 相同、只改 typed DB/MQ binding，profile 更簡潔；若整體 policy、root、Tool topology 或 configuration ownership 有重大差異，仍應使用 separate config。

### Test data 擴展位置

Workbook/sidecar/snapshot 仍是目前 Testcase data contract。未來 logical environment-bound fixture（#38）應擴展本章，並沿用 stable logical-name 原則，而不是把 environment branch 放入 Action。
