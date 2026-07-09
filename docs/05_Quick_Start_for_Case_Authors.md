# ATT 測試案例編寫快速上手

**Version:** Draft
**Status:** Draft
**Last Updated:** 2026-07-09

---

# 1. 這份文檔是什麼

這份快速上手是給測試案例編寫者看的。它只回答一個問題：

> 我要怎樣把一個 Excel 測試案例寫成 ATT 可以執行的格式？

你只需要記住三個檔案：

- `xxx.xlsx`：測試案例本體
- `xxx.yaml`：同名 sidecar 配置
- `templates/`：模板與工具配置

---

# 2. 最小目錄結構

一個典型案例套件長這樣：

```text
testcase/
  payment_regression.xlsx
  payment_regression.yaml

templates/
  payment_transfer_cases/
    invoke/
      standard.yaml
    verify/
      success.yaml

config.yaml
```

你主要編輯的是：

- Excel 裡的案例資料
- 同名 `payment_regression.yaml`
- `templates/` 裡的模板

---

# 3. 先看 sidecar

每個 Excel 套件都需要一份 sidecar YAML。

最小範例如下：

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

你可以先抓住這幾個重點：

- `columns` 是普通欄位，寫成簡單清單
- `yamlColumns` 是 YAML 欄位，可以有多個
- `templateColumn` 是每個 stage 對應的模板欄位
- `required: true` 表示該 stage 不能空

---

# 4. `columns` 怎麼寫

`testcase.columns` 不需要寫傳統 mapping。

你可以直接寫：

```yaml
columns: 案例編號, 金額, 扣帳帳號, 幣種, creditAcNo=入帳帳號
```

意思是：

- `案例編號`、`金額`、`扣帳帳號`、`幣種` 這些欄位會直接進入 Context
- `creditAcNo=入帳帳號` 表示這個單元格同時可以用兩個名字引用

例如同一個單元格可以這樣引用：

```text
${creditAcNo}
${入帳帳號}
```

兩者指向同一份數據。

---

# 5. `yamlColumns` 怎麼寫

`yamlColumns` 用來放 YAML 格式的欄位。

例如：

```yaml
yamlColumns: data=其它測試數據, 更多測試數據
```

這代表有兩個 YAML 欄位：

- `其它測試數據`，同時也可以用別名 `data`
- `更多測試數據`

如果單元格內容是：

```yaml
payment:
  channel: ATM
  expected:
    status: SUCCESS
```

你可以這樣引用：

```text
${Case.data.payment.channel}
${Case.data.payment.expected.status}
${更多測試數據.payment.channel}
```

YAML 欄位支持：

- 中文 key
- 多行內容
- 註解
- 巢狀結構

---

# 6. Stage 怎麼用

每個 stage 都有自己的 `templateColumn`。

常見的 stage 例子是：

- `prepare`
- `invoke`
- `verify`
- `cleanup`
- `rollback`

例如：

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
  cleanup:
    templateColumn: 清理模板
    required: false
  rollback:
    templateColumn: 回滾模板
    required: false
```

你可以這樣理解：

- `templateColumn` 決定這一列要用哪個模板
- `yamlColumns` 提供這個 stage 需要的額外 YAML 數據
- `required` 決定這個 stage 能不能空

如果某個 required stage 的模板欄位是空白、`NA` 或 `N/A`，ATT 會視為空值並報錯。

---

# 7. 模板單元格怎麼寫

stage 對應的模板欄位內容本身就是 YAML。

最簡單的模板單元格：

```yaml
name: PAYMENT_PRECHECK
```

帶 remark 的寫法：

```yaml
name: 中文支付調用模板
remark: |
  第一行備註
  第二行備註
```

你可以把它理解成：

- `name` 是真正要找的模板名稱
- `remark` 是給人看的註解

---

# 8. Context 怎麼引用

ATT 不再要求你去分辨什麼是 Shared Case Data、什麼是 Stage Data。

你只要記住：

- `columns` 的值進 Context
- `yamlColumns` 的 YAML 也進 Context
- stage 模板資訊也進 Context
- action 的輸出也進 Context

常見引用方式：

```text
${CaseID}
${金額}
${creditAcNo}
${Case.data.payment.channel}
${STAGES.invoke.template.name}
${ACTIONS.renderRequest.outputFile}
```

如果兩個來源有同名欄位，後面的來源可能覆蓋前面的來源，ATT 會給 warning。

---

# 9. 一個完整範例

下面是一個很小但完整的想法示例。

## 9.1 Excel 欄位

| 案例編號 | 案例名稱 | 金額 | 扣帳帳號 | 入帳帳號 | 調用模板 | 調用資料 |
|----------|----------|------|----------|----------|----------|----------|
| TC001 | 轉帳成功 | 100 | 123456789 | 987654321 | `name: 中文支付調用模板` | `payment:\n  channel: ATM` |

## 9.2 sidecar

```yaml
testCaseTemplate: payment_transfer_cases

testcase:
  sheet: 測試案例
  headerRows: 1
  columns: 案例編號, 案例名稱, 金額, 扣帳帳號, creditAcNo=入帳帳號
  yamlColumns: data=調用資料
  report:
    columns:
      result: 測試結果
      durationMs: 耗時(ms)
      actualResult: 實際結果

stages:
  invoke:
    templateColumn: 調用模板
    yamlColumns: 調用資料
    required: true
```

## 9.3 這筆資料可怎麼用

```text
${案例編號}
${creditAcNo}
${入帳帳號}
${Case.data.payment.channel}
```

---

# 10. 寫案例時的建議

- 普通文字欄位放 `columns`
- 需要 YAML 結構的欄位放 `yamlColumns`
- 同一個值如果想用不同名字引用，就用 `alias=columnLabel`
- 模板名稱盡量穩定，不要隨便改
- `required: true` 的 stage 不要留空
- `remark` 放給人看的說明，不要塞業務邏輯

---

# 11. 常見問題

## 11.1 `columns` 一定要寫 mapping 嗎？

不用。現在可以直接寫清單。

## 11.2 `yamlColumns` 可以有幾個？

可以有多個。

## 11.3 `NA`、`N/A` 算什麼？

算空值，不算模板名稱。

## 11.4 模板欄位能不能寫中文？

可以。

## 11.5 報表會怎麼輸出？

sidecar 的 `testcase.report.columns` 會決定報表欄位名稱。

---

# 12. 一句話版

把 Excel 裡的普通欄位、YAML 欄位、stage 模板、stage 參數都寫清楚，ATT 會幫你把它們放進 Context，然後按模板一步一步執行。
