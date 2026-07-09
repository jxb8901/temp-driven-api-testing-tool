# ATT 配置參考手冊

**Version:** Draft
**Status:** Draft
**Last Updated:** 2026-07-09

---

# 1. 手冊用途

這份手冊是 ATT 的完整配置參考。

它適合以下讀者：

- 測試案例編寫者
- 模板維護者
- 套件配置維護者
- 需要排查執行問題的人

這份手冊的目標不是只讓你「跑起來」，而是讓你能夠：

- 理解每個配置項的作用
- 寫出可維護的測試案例
- 讀懂執行結果
- 排查常見錯誤

---

# 2. 整體模型

ATT 以三層配置為核心：

- 全局配置 `config.yaml`
- 套件配置 `testcase/<suite>.yaml`
- 模板配置由全局 `templatePaths` 指定的搜索路徑提供

執行時，資料會進入 Context，然後由模板與工具一步步消化。

你可以把它理解成：

```text
Excel + sidecar + templates + tools -> Context -> actions -> output
```

---

# 3. 目錄結構

推薦的專案結構如下：

```text
project/
  att.sh
  config.yaml
  testcase/
    payment_regression.xlsx
    payment_regression.yaml
  templates/
    shared/
      payment_transfer.yaml
    common/
      success_check.yaml
  output/
```

說明：

- `att.sh` 是執行入口
- `config.yaml` 定義工具和全局默認值
- `config.yaml` 也定義模板搜索路徑
- `testcase/*.xlsx` 是案例資料
- `testcase/*.yaml` 是 sidecar 配置
- `templates/` 是默認模板搜索路徑
- `output/` 是執行結果

---

# 4. 快速教程

## 4.1 第一步：準備 Excel

先建一份 Excel，例如：

```text
testcase/payment_regression.xlsx
```

至少要有：

- sheet 名稱
- header 列
- 普通欄位
- YAML 欄位
- stage 模板欄位

## 4.2 第二步：建立同名 sidecar

對應建立：

```text
testcase/payment_regression.yaml
```

最小範例：

```yaml
testCaseTemplate: payment_transfer_cases

testcase:
  sheet: 測試案例
  headerRows: 2
  columns: 案例編號, 案例名稱, 標籤, 金額, 扣帳帳號, 幣種, creditAcNo=入帳帳號
  yamlColumns: data=其它測試數據, 更多測試數據
  report:
    columns:
      result: 測試結果
      durationMs: 耗時(ms)
      actualResult: 實際結果
      caseLog: 案例日誌

stages:
  prepare:
    templateColumn: 準備模板
    yamlColumns: 準備資料
    required: false
  invoke:
    templateColumn: 調用模板
    yamlColumns: 調用資料
    required: true
  verify:
    templateColumn: 驗證模板
    yamlColumns: 驗證資料
    required: true
```

## 4.3 第三步：建立模板

例如：

```text
templates/shared/payment_transfer.yaml
shared-templates/common/success_check.yaml
```

模板單元格內容是 YAML，最少包含：

```yaml
name: 中文支付調用模板
```

這裡要特別注意：

- `templates/shared/payment_transfer.yaml` 是檔案路徑示例
- `shared-templates/common/success_check.yaml` 也是檔案路徑示例
- `name: 中文支付調用模板` 是模板的邏輯名稱
- `template.name` 不直接等於檔案路徑
- ATT 會按 `templatePaths` 的順序搜索模板文件，再用 `name` 去匹配實際模板內容

也就是說，`template.name` 用來「找模板」，不是用來「拼檔名」。

如果有多份模板，則以 YAML 內的 `name` 作為唯一識別更安全。
模板可以在不同 stage 或不同 testcase 間共享，路徑不需要和 stage/testcase 綁定。

## 4.4 第四步：執行

通常使用：

```sh
./att.sh
```

或者使用你環境裡已配置好的執行方式。

---

# 5. 全局配置 `config.yaml`

`config.yaml` 主要放工具和全局默認值。

它通常包含：

- tools
- environment 信息
- global log level
- common execution defaults

示例：

```yaml
environment:
  name: SIT
  region: HK

logLevel: INFO

templatePaths:
  - templates
  - shared-templates

tools:
  invokePaymentApi:
    command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
    arguments: "requestFile*, environment='SIT', traceId"
    script: "./scripts/invoke_payment_api.sh"
    retry:
      count: 2
      exitCode: [1, 2]
```

工具 script 可放在任何位置，只要 `command` 能正確執行即可，不需要跟模板目錄或 testcase 目錄綁定。

## 5.1 什麼放這裡

適合放在 `config.yaml` 的內容：

- 工具定義
- 共用環境資訊
- 全局 log level
- 一般執行默認行為
- 模板搜索路徑

## 5.2 什麼不要放這裡

不要把這些放進 `config.yaml`：

- testcase 欄位定義
- stage 定義
- suite 專屬報表欄位
- suite 專屬解析規則
- 模板內容本體
- 工具 script 放置目錄約束

這些屬於 sidecar。

---

# 6. 套件配置 `testcase/<suite>.yaml`

sidecar 是每個 Excel 套件的主配置。

它負責告訴 ATT：

- 讀哪個 sheet
- header 幾行
- 哪些欄位是普通欄位
- 哪些欄位是 YAML 欄位
- 哪些 stage 要跑
- 報表怎麼輸出

## 6.1 `testcase.columns`

`testcase.columns` 使用簡化清單語法。

示例：

```yaml
testcase:
  columns: 案例編號, 金額, 扣帳帳號, 幣種, creditAcNo=入帳帳號
```

含義：

- `案例編號`、`金額`、`扣帳帳號`、`幣種` 進入 Context
- `creditAcNo=入帳帳號` 表示同一個單元格可用兩個名字引用

引用示例：

```text
${creditAcNo}
${入帳帳號}
```

## 6.2 `testcase.yamlColumns`

`yamlColumns` 用於 YAML 格式欄位。

示例：

```yaml
testcase:
  yamlColumns: data=其它測試數據, 更多測試數據
```

YAML 單元格可以這樣寫：

```yaml
payment:
  channel: ATM
  expected:
    status: SUCCESS
```

引用示例：

```text
${Case.data.payment.channel}
${Case.data.payment.expected.status}
${更多測試數據.payment.channel}
```

## 6.3 `testcase.report`

報表欄位映射通常寫在 sidecar 中：

```yaml
testcase:
  report:
    columns:
      result: 測試結果
      durationMs: 耗時(ms)
      actualResult: 實際結果
      caseLog: 案例日誌
```

你可以在這裡定義：

- 報表欄位名
- 報表檔名模式
- trace fields

---

# 7. Stage 配置

stage 是案例執行的步驟。

常見 stage：

- `prepare`
- `invoke`
- `verify`
- `cleanup`
- `rollback`

示例：

```yaml
stages:
  prepare:
    templateColumn: 準備模板
    yamlColumns: 準備資料
    required: false
  invoke:
    templateColumn: 調用模板
    yamlColumns: 調用資料
    required: true
  verify:
    templateColumn: 驗證模板
    yamlColumns: 驗證資料
    required: true
```

## 7.1 `templateColumn`

每個 stage 都必須有 `templateColumn`。

它指出 Excel 裡哪一欄存放該 stage 的模板名稱。

## 7.2 `yamlColumns`

stage 也可以有自己的 YAML 欄位。

如果該欄存在，ATT 會把它解析成 Context 可用資料。

## 7.3 `required`

`required: true` 代表這個 stage 不能空。

空值包含：

- 空白
- `NA`
- `N/A`
- `n/a`

---

# 8. 模板配置

模板 cell 本身是 YAML。

最小模板：

```yaml
name: PAYMENT_PRECHECK
```

帶 remark：

```yaml
name: 中文支付調用模板
remark: |
  第一行備註
  第二行備註
```

模板可以放：

- name
- remark
- 額外 metadata

---

# 9. Context 與引用

執行時，ATT 會把資料放進 Context。

常見引用方式：

```text
${案例編號}
${金額}
${creditAcNo}
${Case.data.payment.channel}
${STAGES.invoke.template.name}
${ACTIONS.renderRequest.outputFile}
```

## 9.1 同名欄位

如果不同來源有同名欄位，後面寫入的值可能會覆蓋前面。

系統通常會給 warning。

## 9.2 建議

建議你：

- 不要故意重名
- 需要別名時就用 `alias=columnLabel`
- YAML 結構資料統一放 `yamlColumns`

---

# 10. 工具配置

工具定義在 `config.yaml`。

示例：

```yaml
tools:
  invokePaymentApi:
    command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
    arguments: "requestFile*, environment='SIT', traceId"
    retry:
      count: 2
      exitCode: [1, 2]
```

## 10.1 `arguments`

`arguments` 是開放式參數約定，不是嚴格 schema。

推薦寫法：

```yaml
arguments: "requestFile*, environment='SIT', traceId"
```

語義：

- `requestFile*` 必填
- `environment='SIT'` 帶預設值
- `traceId` 可選

## 10.2 `retry`

retry 目前只針對特定 exit code 重試。

示例：

```yaml
retry:
  count: 2
  exitCode: [1, 2]
```

含義：

- 最多重試 2 次
- 當退出碼命中 `1` 或 `2` 時才重試

---

# 11. 執行與輸出

執行後通常會有：

```text
output/<RunID>/<CaseID>/<CaseID>.001.log
output/<RunID>/<CaseID>/<ActionID>/
output/<RunID>/<suiteName>.result.xlsx
output/<RunID>/run.yaml
```

你可以優先看：

- case log
- result workbook
- run.yaml

## 11.1 case log

case log 用來看：

- 執行順序
- tool command
- input / output 摘要
- 錯誤訊息

## 11.2 report workbook

report workbook 用來看：

- 測試結果
- 耗時
- 實際結果
- 案例日誌

---

# 12. 最佳實踐

## 12.1 欄位設計

- 普通值用 `columns`
- 結構化資料用 `yamlColumns`
- 同一值需要不同名字引用時才用 alias

## 12.2 stage 設計

- `invoke` 用來發出請求
- `verify` 用來驗證結果
- `cleanup` 用來做收尾
- `rollback` 用來處理失敗恢復

## 12.3 模板命名

- 模板名稱要穩定
- 中文模板名可以用
- `remark` 用來寫說明，不要堆太多業務邏輯

## 12.4 日誌設計

- 只記關鍵資訊
- 長內容盡量放 file
- 日誌裡保留路徑與摘要

---

# 13. 注意事項

## 13.1 空值

`NA`、`N/A`、`n/a`、空白都會當成空值處理。

## 13.2 空格

ATT 會對必要的匹配欄位做去空格處理，但你還是應該避免手工輸入多餘空格。

## 13.3 YAML 格式

YAML 欄位必須是合法 YAML。

如果縮排錯了，案例會失敗。

## 13.4 重名

不同來源寫到同一個 Context key 時，後寫入值可能覆蓋前值。

---

# 14. 調試與排錯

## 14.1 模板找不到

先檢查：

- `templateColumn` 的值是否正確
- 模板 `name` 是否一致
- `templatePaths` 是否包含實際模板所在目錄
- 模板內容是否在配置的搜索路徑中

## 14.2 YAML 解析失敗

先檢查：

- 縮排
- 引號
- list / map 格式
- 多行內容是否正確

## 14.3 工具執行失敗

先檢查：

- tool command 是否能在 shell 直接執行
- `arguments` 是否有漏參數
- `retry.exitCode` 是否命中

## 14.4 報表欄位不對

先檢查：

- sidecar 的 `testcase.report.columns`
- 生成結果時是否使用了正確的 sidecar

## 14.5 日誌太多或太少

先檢查：

- global `logLevel`
- testcase / stage / action 的 log level
- `att.sh` 是否覆蓋了預設值

---

# 15. 進階排查清單

如果你遇到奇怪問題，可以按這個順序查：

1. Excel sheet 名稱是否正確
2. sidecar 是否與 Excel 同名
3. `testcase.headerRows` 是否正確
4. `columns` 是否對應到實際欄位名
5. `yamlColumns` 是否是合法 YAML
6. stage 的 `templateColumn` 是否有值
7. `templatePaths` 是否包含實際模板目錄
8. 模板 `name` 是否能找到
9. tool command 是否可執行
10. retry exit code 是否合理
11. log level 是否太低，導致看不到足夠資訊

---

# 16. 示例合集

## 16.1 單一普通欄位

```yaml
testcase:
  columns: 案例編號, 金額, 幣種
```

## 16.2 同一欄位多名稱引用

```yaml
testcase:
  columns: creditAcNo=入帳帳號
```

## 16.3 多個 YAML 欄位

```yaml
testcase:
  yamlColumns: data=其它測試數據, 更多測試數據
```

## 16.4 一個完整 stage

```yaml
stages:
  invoke:
    templateColumn: 調用模板
    yamlColumns: 調用資料
    required: true
```

## 16.5 一個完整工具

```yaml
tools:
  invokePaymentApi:
    command: "./tools/invoke_payment_api.sh --input ${TOOL.inputFile} --output ${TOOL.outputFile}"
    output: xml
    arguments: "requestFile*, environment='SIT', traceId"
    retry:
      count: 2
      exitCode: [1, 2]
```

---

# 17. 你應該記住的最小集合

- `testcase.columns` 用清單
- `testcase.yamlColumns` 用 YAML 欄位
- `stages.<stage>.templateColumn` 必填
- 模板 cell 用 YAML，`name` 是關鍵
- 模板由 `templatePaths` 搜索，不綁 stage/testcase
- 工具 script 不綁模板或 testcase 目錄
- 資料都進 Context
- `arguments` 是開放式參數約定
- `retry` 只看 exit code

---

# 18. 一句話總結

這份手冊的核心只有一句：

> 把 Excel、sidecar、模板、工具配置對齊，ATT 就會按 Context 與 stage 規則幫你完成自動化測試。
