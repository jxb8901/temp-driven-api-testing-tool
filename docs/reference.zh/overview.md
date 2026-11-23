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

## RUN 與 LOAD

RUN 以 qualified ID、tag 或 implicit Suite 選擇 Resolved Case。Debug 直接診斷 Flow 或 Tool。LOAD 重複執行 Flow 或 Tool workload；Native Case LOAD target 仍在延後範圍。詳見[統一 RUN 與排程](execution-modes/unified-run.md)。

| 模式 | 輸入 | 結果 |
|---|---|---|
| RUN | Definition 與所選 Resolved Case | 每個所選 Case 執行一次 |
| Debug | Flow 或 Tool target 與明確 inputs | 一次獨立診斷執行 |
| LOAD | 已驗證且包含 Flow 或 Tool workload 的 scenario | 重複 iteration 和 workload evidence |

可重用 Flow 使用 `EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS`、`META` 和 Action-local `output`。Execution mode 和 scheduler identity 是保留 evidence 中的 framework diagnostics，不是 expression data。

## Resource 是同級概念

Tool、DBHelper、MQHelper、HTTPHelper 和 SSHHelper 是獨立 Resource 類型。其 configuration 與 lifecycle 各有不同，但 Actions 會透過 `output.result` 發布 typed operation result，並將選用的 presentation evidence 分開保存。Public expression 應讀取 Action result/evidence，不應依賴 Resource 內部 connection/process state。SSH Resource 操作在一般 `type: tool` Action 中使用共通的 `ssh.<helperId>.<operation>` 格式。

```text
Tool ------\
DBHelper ---+
MQHelper ---+--> typed operation result --> Action output
HTTPHelper -+
SSHHelper --/
```

## Package 邊界

每個 Package 擁有 Flows、Test Case Definitions、Package-local Tools、Testdata 和生成的 output。所有 Package 共用 `ATT_HOME/config/` 下的 runtime configuration；受信任 schema 由 ATT 內嵌。Package 不需要自己的 `config/` 或 `schemas/` 目錄。執行前會驗證路徑與 identifier。Credential 應放在受保護的 runtime input 或 secret store。

逐步建立範例請使用[快速入門](../quick-start.zh.md)。維護以較早模型編寫的 Package，請參閱[舊版文件](../legacy/README.zh.md)。本手冊其餘部分是規範性查閱文件。

## 如何使用本手冊

| 目標 | 文件 |
|---|---|
| 建立第一個 ATT Package | [Quick Start](../quick-start.zh.md) |
| 理解 ATT 核心 model | [Product model](overview.md)、[Test Authoring](test-authoring.md)、[Actions](actions.md)、[Runtime and Context](runtime-context.md) 和 [Run mode](execution-modes/run.md) |
| 配置 DB/MQ/HTTP/SSH | [Resources](resources/overview.md) |
| 查閱 CLI option | [CLI Reference](cli.md) |
| 診斷失敗 | [Validation and Troubleshooting](validation-diagnostics.md) |
| 維護既有 Package | [舊版文件](../legacy/README.zh.md) |

Reference 定義 public contract；README、Quick Start 與 examples 按特定任務說明這份 contract。每項 contract 由一個 semantic owner 定義，其他 Reference 頁面提供摘要並連結至 owner。
