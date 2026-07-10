# ATT V2 新手入門

這份入門用中文 Excel 完成工具、模板、案例、驗證、執行、報告、文件及打包流程。

## 1. 核心關係

```text
test case --1:n stage--> template --1:n action--> tool
```

Test case、template、tool 是核心概念。Stage 只定義一個案例調用模板的數量及順序；Action 只定義模板調用工具或執行 render/assert/log 的數量及順序。

## 2. 目錄

```text
config/config.yaml
testcase/支付回歸.xlsx
testcase/支付回歸.yaml
templates/payment/local/CT001/template.yaml
templates/payment/local/CT001/request.tmp.xml
tools/invoke_payment_api.sh
```

只有直接包含 `template.yaml` 的目錄才是模板；上例模板完整路徑是 `payment/local/CT001`。

## 3. 配置工具

```yaml
outputDirectory: output
environment: SIT
timeoutSeconds: 120
templates: {root: templates}
tools:
  invokePaymentApi:
    name: Invoke Payment API
    description: 調用付款 API
    command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
    arguments:
      requestFile: {name: Request File, description: 已渲染 XML, required: true}
      environment: {name: Environment, description: 執行環境, required: true}
```

`arguments` 用於驗證及工具文件。每個參數只定義 `name`、`description`、`required`；只有最後一個參數可增加 `delimit`。

## 4. 建立模板

`templates/payment/local/CT001/template.yaml`：

```yaml
name: 本地付款
description: 產生付款 XML 並調用 API
actions:
  renderRequest:
    type: render
    payload: request.tmp.xml
    saveAs: request.xml
  invokeApi:
    type: tool
    call: "#{invokePaymentApi(requestFile=${ACTIONS.renderRequest.outputFile}, environment=${CASE.environment})}"
  assertStatus:
    type: assert
    expression: "${ACTIONS.invokeApi.output.Response.Status} == '${CASE.expected.status}'"
```

`request.tmp.xml`：

```xml
<PaymentRequest>
  <CaseId>${CASE.caseId}</CaseId>
  <DebitAccount>${CASE.debitAccount}</DebitAccount>
  <Amount>${CASE.amount}</Amount>
</PaymentRequest>
```

## 5. 中文 Excel 和 sidecar

Excel 表頭：

| 案例編號 | 案例名稱 | 標籤 | 扣賬帳號 | 金額 | 預期結果 | 執行模板 |
|---|---|---|---|---:|---|---|
| TC001 | 本地付款成功 | smoke,付款 | 111111 | 100 | `status: SUCCESS` | `name: 本地付款` |

相鄰的 `testcase/支付回歸.yaml`：

```yaml
excel:
  sheet: 支付測試案例集
  caseId: 案例編號
  tags: 標籤
  dataColumns: caseName=案例名稱, debitAccount=扣賬帳號, amount=金額, expected=預期結果(yaml)
stages:
  - key: invoke
    template: 執行模板
    required: true
    runWhen: normal
    onFailure: stop
report:
  columns:
    result: 測試結果
    reportLink: 詳細報告
```

模板單元格必須是含 `name` 的 YAML map；`name` 可使用 symbolic name 或完整路徑。該 map 的所有 key-value 都會加入 stage data。`N/A`、`NA`、`NULL`、`NONE` 和空白會正規化為 blank。

多 sheet 使用：

```yaml
sheet: payment=支付測試案例集, batch=批量測試案例集
```

完整 Case ID 分別是 `payment.TC001`、`batch.TC001`。

## 6. 表達式

- `${CASE.amount}`：讀取 Context。
- `${CASE.STAGES.invoke.TEMPLATE.ACTIONS.invokeApi.output}`：跨 stage 完整路徑。
- `${ACTIONS.invokeApi.output}`：目前 template 的便捷路徑。
- `#{tool(name='中文,字串', count=2, enabled=true)}`：調用工具，支援字串、數字、布爾 literal。

核心節點 `CASE`、`STAGES`、`TEMPLATE`、`ACTIONS`、`TOOL` 使用大寫；`caseId`、`outputFile` 等 metadata 使用 camelCase。

## 7. 驗證及執行

```sh
./att.sh validate --suite testcase/支付回歸.xlsx
./att.sh run --suite testcase/支付回歸.xlsx
./att.sh run --all
./att.sh run --all --case payment.TC001
./att.sh run --all --tag smoke
```

不帶參數或使用 `--help` 顯示完整用法。

## 8. 報告、文件和打包

```sh
./att.sh report --run-id <RunID>
./att.sh docs
./att.sh docs --single-page
./att.sh build
```

- 單頁測試報告：`output/<RunID>/report/index.html`
- 多頁套件文件：`build/docs/index.html`
- 單頁套件文件：`build/docs/single-page.html`
- 結果工作簿：`output/<RunID>/workbooks/`
- 最近完成 run 的 archive：`dist/att-<RunID>.tar.gz`

## 9. 常見問題

- 找不到模板：檢查目錄中的 `template.yaml`、symbolic name 或完整路徑。
- Context 是空值：檢查大寫核心節點及 ID；stage/action ID 不可包含 `.`。
- Tool 驗證失敗：檢查 unknown、missing required 或 duplicate argument。
- YAML cell 失敗：確認內容是 map，模板 selector 包含 `name`。
- 中文連結：V2 使用 Unicode-safe anchor，可支援中文 Case ID、模板名及路徑。

進一步內容見 [V2 完整使用手冊](09_ATT_V2_Complete_Manual.md) 和 [案例開發參考手冊](08_Quick_Start_for_Case_Authors.md)。
