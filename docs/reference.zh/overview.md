# 概覽與 Product model

ATT 把測試意圖與整合機制分離。測試數據以 workbook／sidecar／snapshot 版本化；Template 與 Flow 定義可重用行為；Resource 把這些行為連接到外部系統。

## 產品模型

```text
Testcase
  `-- ordered Stage
        `-- Template
              |-- Action
              |     |-- file-content expression / assert / log / assign
              |     |-- Tool
              |     `-- DB
              `-- Flow -> ordered Actions
```

**Testcase** 是作者編寫並正規化的 Workbook row。**Case execution** 是 Run 對該 Testcase 的一次執行；其完整 Case ID 標識 Testcase，並沿用於執行結果和 evidence。討論 Workbook data 和 authoring 時使用 *Testcase*；討論 runtime status、日誌、報表和 artifacts 時使用 *Case execution*。**Stage** 選擇 Template 並提供 stage-private data；**Template** 是可執行 scenario 邊界；**Flow** 是具有獨立 Action scope 的可重用 Template 邏輯；**Action** 是一個有序工作單元；**Resource** 是 Action 或允許的 expression call 所使用的 Tool、DBHelper、MQHelper、HTTPHelper 或 SSHHelper。

## 三種執行模式是同級概念

Run、Debug、Load 把不同輸入適配到同一 execution-neutral Context 和同一批 reusable components：

| 模式 | 主要輸入 | 重用內容 |
|---|---|---|
| Run | workbook Testcase 與 Stage selector | Template、Flow、Tool、DB/MQ/HTTP/SSH |
| Debug | `att-debug/v1.2` sidecar 或 `--input` | 單一 Template、Flow 或 Tool target |
| Load | `att-load/v1.6` scenario | 重複執行一個或多個 Template、Flow 或 Tool workload |

可重用 Template/Flow 應依賴 `EXEC.INPUT`、`EXEC.VARS`、`EXEC.ACTIONS`、`META` 和 Action-local `output`。執行模式與 scheduler identity 只保留在 framework evidence，不會成為 expression data。

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

一般 package 包含 `config/`、`testcase/`、`templates/`、`tools/`、`schemas/` 和生成的 `output/`。ATT 在執行前驗證 path 與 identifier。Credential 應放在環境變數或外部 secrets 管理，不應提交到 YAML。

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
