# 概覽與 Product model

ATT 把可重用的執行邏輯與業務資料分開。現行 Case 路徑是：Test Case Definition 綁定 Flow 與 Data Source，解析成所選的 Resolved Case，再以 Flow 的有序 Actions 和 Resource 執行每一個 Case。

## 產品模型

```text
Test Case Definition (att-testcase/v1)
  |-- Flow (att-flow/v3.6)
  `-- Data Source
        -> Resolution
        -> Resolved Case
        -> Suite selection
        -> Case Execution
              `-- Flow
                    `-- ordered Actions
                          |-- file-content expression / assert / log / assign / set
                          `-- Resource
                                |-- Tool
                                |-- DBHelper
                                |-- MQHelper
                                |-- HTTPHelper
                                `-- SSHHelper
```

**Test Case Definition**（`att-testcase/v1`，通常是 `testcase.yaml`）綁定一個 Flow 和一個 Data Source。**Flow** 是可重用的有序 Actions。**Resolution** 把 Definition 解析成 Resolved Cases。V1 把每一筆非空白來源紀錄正好解析成一個 **Resolved Case**；它不會把一筆紀錄展開、合併或額外產生多個 Case。**Suite selection** 選擇要執行哪些 Resolved Case。不需要強制的 `suite.yaml`。**Case execution** 是所選 Resolved Case 的一次 RUN。**Action** 是該 Flow 內的一個有序工作單元。**Resource** 是 Action 或允許的 expression call 所使用的 Tool、DBHelper、MQHelper、HTTPHelper 或 SSHHelper。

## 受支援的 Legacy 相容模型

Workbook、Sidecar、Snapshot、Stage 和 Template 仍是受支援的 runtime。本節不從 ATT 移除 Template。下面的圖是 Legacy 相容路徑，不是現行 Case 模型。

```text
Workbook row
  `-- ordered Stage
        `-- Template
              |-- Action
              |     |-- file-content expression / assert / log / assign / set
              |     |-- Tool
              |     `-- DB
              `-- Flow -> ordered Actions
```

舊式 **Testcase** 是作者編寫並正規化的 Workbook row。**Stage** 選擇 Template 並提供 stage-private data。**Template** 是這條路徑的可執行 scenario 邊界，並可呼叫 Flow。Legacy RUN、Debug 和 LOAD 繼續執行這條路徑。

## RUN 與 LOAD 執行模型

RUN 執行一次所選的功能目標或 Case selection。Native RUN 以 qualified ID、tag 或 implicit Suite 選取 Resolved Case。Legacy RUN 選取 workbook Testcase 與 Stage selector。Debug 診斷單一 Template、Flow 或 Tool。LOAD 重複執行 Template、Flow 或 Tool workload。請參閱[統一 RUN 與排程](execution-modes/unified-run.md)。

| 模式 | 主要輸入 | 重用內容 |
|---|---|---|
| Run | Native：Resolved Case ID、tag 或 implicit Suite。Legacy：workbook Testcase 與 Stage selector | Flow 與 Actions；Legacy RUN 也使用 Template |
| Debug | `att-debug/v1.2` sidecar 或 `--input` | 單一 Template、Flow 或 Tool target |
| Load | `att-load/v1.9` scenario | 重複執行一個或多個 Template、Flow 或 Tool workload |

可重用 Flow 應依賴 `EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS`、`META` 和 Action-local `output`。執行模式與 scheduler identity 只保留在 framework evidence，不會成為 expression data。

## 五種 Resource 是同級概念

Tool、DBHelper、MQHelper、HTTPHelper 和 SSHHelper 是獨立 Resource 類型。它們的配置與 lifecycle 不同，但 Action 會透過 `output.result` 發布原生 typed result，並將可選的 presentation evidence 分開保存。公開 expression 應讀取 Action result/evidence，而不是 resource 內部 connection/process state。

```text
Tool ------\
DBHelper ---+
MQHelper ---+--> typed operation result --> Action output
HTTPHelper -+
SSHHelper --/
```

## Package 邊界

每個 Package 包含自己的 business files，例如 `testcase/`、`templates/`、package-local `tools/`、`testdata/` 及生成的 `output/`。所有 Package 共用 `ATT_HOME/config/` 下的 runtime configuration；受信任 schemas 由 ATT 內嵌載入。Package 不需要自己的 `config/` 或 `schemas/` 目錄。ATT 會在執行前驗證 path 與 identifier。Credential 應放在環境變數或外部 secrets 管理，不應提交到 YAML。

需要逐步建立一個可工作的 package，請使用 `docs/quick-start.md`；本 Reference 其餘內容是規範性查閱文件。

## 如何使用本手冊

| 目標 | 文件 |
|---|---|
| 建立第一個 ATT package | [Quick Start](../quick-start.zh.md) |
| 理解核心 ATT model | [Product model](overview.md)、[Test Authoring](test-authoring.md)、[Actions](actions.md)、[Runtime and Context](runtime-context.md) 和 [Run mode](execution-modes/run.md) |
| 配置 DB/MQ/HTTP/SSH | [Resources](resources/overview.md) |
| 查閱 CLI option | [CLI Reference](cli.md) |
| 診斷失敗 | [Validation and Troubleshooting](validation-diagnostics.md) |
| 升級舊 package | [Migration Notes](appendices/migrations.md) |

Reference 定義 public contract；README、Quick Start 與 examples 按特定任務說明這份 contract。每項 contract 由一個 semantic owner 定義，其他 Reference 頁面提供摘要並連結至 owner。
