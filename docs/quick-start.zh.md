# ATT V3.5.1 快速入門

[English Quick Start](quick-start.md) · [Reference Manual](generated/reference.zh.html)

本指南的目標很單純：從乾淨的 repository checkout 開始，用最小但完整的例子跑通第一次 ATT 測試。內容刻意先教正常的 Run 流程；Debug、Load、DB/MQ、environment、retry 及完整 Context 模型都放到最後的「下一步」。

已提交的 Quick Start 例子刻意保持離線可執行：第一個 Case 只使用 `assign`、`log`、`assert`；第二個 Case 再加入 ATT 內置的本地 sample Tool，不需要資料庫、MQ、API endpoint、credential 或網絡連線。

## 1. 你將會執行甚麼

ATT 正常的案例作者流程可以先理解為：

```text
Excel Testcase
   -> Stage
      -> Template
         -> ordered Actions
```

本指南直接使用 repository 內已準備好的檔案：

```text
testcase/quick_start.xlsx
testcase/quick_start.yaml
testcase/quick_start.xml
templates/QUICK_START/template.yaml
config/config.yaml
```

Workbook 內有兩個 Case：

| Case | 用途 | 外部依賴 |
|---|---|---|
| `quickStart.default.QS001` | 第一次成功執行 ATT | 無 |
| `quickStart.default.QS002` | 同一流程再加入本地 sample Tool | 無 |

第一次使用時不需要先理解 ATT 全部 schema。

## 2. 前置條件

ATT 需要 Java 8 或以上版本。macOS/Linux 在 repository root 執行；如有需要，先令 launcher 可執行：

```sh
chmod +x att.sh
```

確認 CLI 可以啟動：

```sh
./att.sh version
```

Windows 使用者把下文的 `./att.sh` 換成 `att.bat` 即可。

## 3. 看懂 Workbook 與 Sidecar

打開 `testcase/quick_start.xlsx`。第二列是真正由 sidecar 使用的 Excel header：

```text
Case ID | Tags | Name | Amount | Use Tool | Template | Expected
```

`testcase/quick_start.yaml` 把 Excel 欄位映射成 ATT input：

```yaml
schemaVersion: att-sidecar/v2.2
id: quickStart
excel:
  sheet: QuickStart
  headerRows: 2
  caseId: Case ID
  tags: Tags
  dataColumns: name=Name, amount=Amount, useTool=Use Tool(yaml), expected=Expected

stages:
  - key: main
    template: Template
    required: true
    onFailure: stop
    runWhen: normal
```

`Use Tool(yaml)` 後面的 `(yaml)` 令 Excel 中的 `true` / `false` 保留為 Boolean，而不是普通字串。

`testcase/quick_start.xml` 是 ATT 由 workbook 正規化產生的 snapshot。它是執行時驗證的一部分，不應手工修改。

## 4. 看懂最小 Template

兩行案例都選擇 `QUICK_START`，實作位於 `templates/QUICK_START/template.yaml`。

第一次只需要掌握三個概念：

```yaml
actions:
  captureAmount:
    type: assign
    name: quickStartAmount
    expression: "${EXEC.INPUT.amount}"

  showInput:
    type: log
    message: "Quick Start case ${META.SOURCE.caseId}: amount=${EXEC.VARS.quickStartAmount}"

  verifyAmount:
    type: assert
    assert: "#{${EXEC.VARS.quickStartAmount} == ${EXEC.INPUT.expected}}"
    expected: "${EXEC.INPUT.expected}"
    actual: "${EXEC.VARS.quickStartAmount}"
```

在這個例子中：

- `EXEC.INPUT` 是由 Testcase row 物化而來的輸入；
- `EXEC.VARS` 是由 `assign` 明確建立的 runtime value；
- `${...}` 用來讀取或插入 Context 值；
- `#{...}` 用來執行 typed expression。

第一次執行知道這些已經足夠。完整定義見 [Runtime and Context](reference.zh/03_runtime_context.md) 及 [Expressions](reference.zh/07_expressions.md)。

## 5. 重新產生 Snapshot

ATT 會檢查 Excel workbook 與 XML snapshot 是否一致。執行：

```sh
./att.sh snapshot --suite testcase/quick_start.xlsx
```

日後修改 Excel 後，也應再次執行同一指令，並在 commit 前 review XML diff。

## 6. 執行前先 Validate

先驗證整個 package：

```sh
./att.sh validate --package
```

Validation 會檢查 schema、workbook/snapshot 一致性、Template reference、expression、Tool/resource reference 等契約，但不會真正執行測試。

正式 SIT/UAT package 應先解決 validation error，再開始執行。

## 7. 第一次真正 Run

先只執行完全離線的 Case：

```sh
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
```

預期結果是 `PASS`。

先記住這個簡化版 status 模型即可：

| Status | 意義 |
|---|---|
| `PASS` | 執行成功，而且 assertion 成立 |
| `FAIL` | framework 成功執行，但業務 assertion 不成立 |
| `ERROR` | runtime／integration／I/O 等執行錯誤 |
| `INVALID` | validation 失敗，因此沒有開始執行 |
| `SKIPPED` | 被規則刻意跳過 |

遇到真實案例問題時再看 [Validation and Diagnostics](reference.zh/12_validation_diagnostics.md)。

## 8. 查看結果

正常 Run 會寫入：

```text
output/<runId>/
  run.yaml
  ... case output ...
  ... case.log ...
```

只有完整完成的 Run 才會更新 `output/latest-run.yaml`。

Quick Start 的 `case.log` 應包含 `showInput` 寫出的訊息，而最後的 assertion 會比較 Excel 的 `Amount` 與 `Expected`。

完整 report、Action evidence、attempt history 及輸出格式請看 [Results, Reports, and Evidence](reference.zh/11_results_reports_evidence.md)。第一次使用毋須先理解整個 evidence tree。

## 9. 刻意製造一次 FAIL

理解 `FAIL` 和 `ERROR` 的最好方法，是刻意讓業務 assertion 失敗一次。

在 `testcase/quick_start.xlsx` 把 `QS001` 的 `Expected` 從 `100` 改成 `999`，然後：

```sh
./att.sh snapshot --suite testcase/quick_start.xlsx
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS001
```

這次應得到 `FAIL`，而不是 `ERROR`：ATT 本身正常完成，只是 assertion 為 false。

練習後把 `Expected` 改回 `100`，再重新產生 snapshot。

## 10. 加入真正的 Tool Action

同一個 Template 已包含一個可選 Tool：

```yaml
readDate:
  type: tool
  call: "#{sample.getAcDate()}"
  runWhen: "#{${EXEC.INPUT.useTool} == true}"
```

`QS001` 的 `Use Tool = false`，所以這個 Action 會被跳過；`QS002` 則為 `true`。

執行第二個 Case：

```sh
./att.sh run --suite testcase/quick_start.xlsx --case quickStart.default.QS002
```

這會調用 `config/tools/sample.yaml` 中已提交的 `sample.getAcDate`。它是本地 command-backed Tool，因此仍然不需要外部服務。

此時先建立這個 mental model：

```text
Testcase input -> Template Action -> Tool -> Action output/evidence
```

完整的 Tool descriptor、command-backed/call-backed 差異、arguments、output 及 evidence 契約請看 [Resources - Tool](reference.zh/05_resources/tools.md)。

## 11. 執行整個 Quick Start Workbook

兩個 Case 都看懂後，可一起執行：

```sh
./att.sh run --suite testcase/quick_start.xlsx
```

到這裡你已經完成 ATT 最重要的日常循環：

```text
修改 Excel / Template
        |
        v
snapshot
        |
        v
validate
        |
        v
run
        |
        v
查看 log / report
```

SIT/UAT 中更大的 package，本質上也是在這個循環之上增加可重用 Flow、Tool、DB/MQ、environment 和更多 validation contract。

## 12. 下一步學甚麼

Quick Start 不應變成第二本 Reference Manual。按你真正要做的工作繼續閱讀：

| 我想要…… | 下一步 |
|---|---|
| 理解 Workbook、Sidecar、Snapshot、Template、Flow | [Test Authoring](reference.zh/02_test_authoring.md) |
| 理解 `EXEC`、`META`、`EXEC.VARS`、`EXEC.ACTIONS`、`output` | [Runtime and Context](reference.zh/03_runtime_context.md) |
| 不經 Excel 單獨測試 Template/Flow/Tool | [Standalone Debug](reference.zh/04_execution_modes/debug.md) |
| 做 load test | [Load](reference.zh/04_execution_modes/load.md) |
| 調用 script/program 或 framework-native Tool | [Tool](reference.zh/05_resources/tools.md) |
| 查詢／更新資料庫 | [DBHelper](reference.zh/05_resources/dbhelper.md) |
| 發送／接收／request MQ message | [MQHelper](reference.zh/05_resources/mqhelper.md) |
| 在 SIT/UAT 間切換 resource binding | [Environment and Test Data](reference.zh/06_environment_testdata.md) |
| 正確使用 `${...}` / `#{...}` | [Expressions](reference.zh/07_expressions.md) |
| 使用 assertion、timeout、retry、`runWhen`、`onFailure` | [Reliability and Execution Control](reference.zh/08_reliability_execution_control.md) |
| 查 CLI command / option | [CLI Reference](reference.zh/10_cli.md) |
| 排查 `FAIL`、`ERROR`、`INVALID` | [Validation and Diagnostics](reference.zh/12_validation_diagnostics.md) |
| 接入 CI 或打包部署 | [CI, Packaging, and Operations](reference.zh/13_ci_packaging_operations.md) |

需要查完整欄位與 public contract 時，直接使用生成的 [ATT V3.5.1 中文 Reference Manual](generated/reference.zh.html)。
