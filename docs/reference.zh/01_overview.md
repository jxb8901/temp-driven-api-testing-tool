## 01 概覽與核心概念

ATT 把測試意圖與整合機制分離。測試數據以 workbook／sidecar／snapshot 版本化；Template 與 Flow 定義可重用行為；Resource 把這些行為連接到外部系統。

### 產品模型

```text
Testcase
  `-- ordered Stage
        `-- Template
              |-- Action
              |     |-- render / assert / log / assign
              |     |-- Tool
              |     `-- DB
              `-- Flow -> ordered Actions
```

**Testcase** 是一個標準化 workbook row；**Stage** 選擇 Template 並提供 stage-private data；**Template** 是可執行 scenario 邊界；**Flow** 是具有獨立 Action scope 的可重用 Template 邏輯；**Action** 是一個有序工作單元；**Resource** 是 Action 或允許的 expression call 所使用的 Tool、DBHelper 或 MQHelper。

### 三種執行模式是同級概念

Run、Debug、Load 把不同輸入適配到同一 execution-neutral Context 和同一批 reusable components：

| 模式 | 主要輸入 | 重用內容 |
|---|---|---|
| Run | workbook Testcase 與 Stage selector | Template、Flow、Tool、DB/MQ |
| Debug | `att-debug/v1.0` sidecar 或 `--input` | 單一 Template、Flow 或 Tool target |
| Load | `att-load/v1.0` scenario | 重複執行單一 Template、Flow 或 Tool target |

可重用 Template/Flow 應依賴 `EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS`、`META` 和 Action-local `output`。執行模式與 scheduler identity 只保留在 framework evidence，不會成為 expression data。

### 三種 Resource 是同級概念

Tool、DBHelper、MQHelper 是獨立 Resource 類型。它們的配置與 lifecycle 不同，但 operation data 最終都收斂到同一 Action envelope。公開 expression 應讀取 Action result/evidence，而不是 resource 內部 connection/process state。

```text
Tool ----\
DBHelper --+--> operation result/evidence --> Action output
MQHelper -/
```

### Package 邊界

一般 package 包含 `config/`、`testcase/`、`templates/`、`tools/`、`schemas/` 和生成的 `output/`。ATT 在執行前驗證 path 與 identifier。Credential 應放在環境變數或外部 secrets 管理，不應提交到 YAML。

需要逐步建立一個可工作的 package，請使用 `docs/quick-start.md`；本 Reference 其餘內容是規範性查閱文件。
