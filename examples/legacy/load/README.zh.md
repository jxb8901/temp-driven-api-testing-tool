# 舊版 LOAD scenarios

這些範例保留 Template target，並與 `examples/load/` 的現行 Flow 和 Tool scenarios 分開。target 資源位於 `packages/legacy`；執行時須將該 Package 設為工作目錄。

| Scenario | 用途 |
|---|---|
| `closed-minimal.yaml` | 以最小 closed VU 執行 `V3_FLOW_EXAMPLE`。 |
| `closed.yaml` | 完整 Template target workload，包含 inputs、think time、threshold 和 evidence。 |
| `testdata-generated.yaml` | 將 iteration-scoped generated Testdata 對應到離線 `QUICK_START` Template。 |

在 repository root 執行：

```sh
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh load ../../examples/legacy/load/closed-minimal.yaml --users 1 --duration 100ms
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh load ../../examples/legacy/load/closed.yaml --users 1 --duration 100ms
ATT_PACKAGE_ROOT="$PWD/packages/legacy" ./att.sh load ../../examples/legacy/load/testdata-generated.yaml
```

Generated Testdata scenario 會從 `examples/testdata/generated-account.yaml` 讀取四筆資料，資料用盡後停止。這些 scenario 示範保留的 Template-target LOAD 路徑，詳見[舊版文件](../../../docs/legacy/README.zh.md)。
