## 02 測試案例編寫

### 編寫契約

本章说明正常日常工作流中的数据流转顺序。

### 2.1 工作簿

#### 工作簿、侧车和快照之间的关系

每个 `.xlsx` 工作簿都要求有一个 YAML 侧车和一个生成的 XML 快照，它们必须具有相同的基名且位于同一目录：

```text
testcase/payment_regression.xlsx
testcase/payment_regression.yaml
testcase/payment_regression.xml
```

侧车将 Excel 结构映射为 ATT 概念。它负责 sheet 映射、表头、用例数据、有序阶段及可选报告列标签；timeout/retry 不属于工作簿配置。

```yaml
schemaVersion: att-sidecar/v2.2
id: paymentRegression
excel:
  sheet: payment=支付測試案例集, batch=批量測試案例集
  headerRows: 2
  caseId: 案例編號
  tags: 標籤
  dataColumns: caseName=案例名稱, amount=金額, expected=預期結果(yaml)
stages:
  - key: invoke
    template: 執行模板
    dataColumns: channel=渠道, options=執行參數(yaml)
    required: true
    runWhen: normal
    onFailure: stop
```

根 `id` 是必需的，并且必须在整个包中唯一。`excel.sheet` 可以接受一个 sheet 名称，或以逗号分隔的 `groupId=sheetName` 条目。如果只给出一个 sheet 且没有 group ID，ATT 会使用 `default`。完整 Case ID 的形式始终是 `workbookId.groupId.rowCaseId`，并且必须在整个包中唯一。

在修改 Excel 后，执行 `./att.sh snapshot --suite testcase/payment_regression.xlsx`。生成的 `payment_regression.xml` 使用模式 `att-testcases/v2.4`，并仅存储归一化后的侧车映射语义。它保留 group、Case、标签、map/list 和阶段顺序，使用显式值类型，并排除样式和无关工作簿内容。包含 LF 或 XML 特殊字符 `&`、`<`、`>` 的字符串值会使用 CDATA；文字 `]]>` 会被拆分成相邻 CDATA 段，并在解析时精确重建。LF 前的空格或制表符会使用 `&#32;`/`&#9;` 插入两个 CDATA 段之间，从而保留值而不触发 Git 行尾空白警告。请审查并提交该 XML；不要手工修改它。

普通 `run` 和每一种 `validate` 模式都会保持只读，如果 XML 缺失、无效、非规范或过期，则会在输出创建前失败。`run --update-snapshot` 会显式允许 ATT 在应用相同验证与校验规则前，仅为选中的完整工作簿刷新已更改的快照。它不会写入部分 Case/标签快照，不会在更新期间调用工具，拒绝快照符号链接，并且当与 `--dry-run` 组合使用时仍会执行授权更新。字节内容完全相同的快照不会被重写。

#### 映射数据列

`dataColumns` 可以接受：

```text
ColumnName
alias=ColumnName
ColumnName(yaml)
alias=ColumnName(yaml)
```

普通列作为字符串进入 Context。`(yaml)` 列则会把显示的单元格值解析为 YAML 标量、列表或映射。

双引号可保护逗号、等号和括号：

```yaml
dataColumns: amount=金額, note="備註,補充", formula="規則=值", payload="請求(yaml)"(yaml)
```

最后的 `(yaml)` 是 ATT 的解析标记。在最后一个例子中，物理 Excel 表头名是 `請求(yaml)`。

#### 空白值

`N/A`、`NA`、`NULL`、`NONE`、空单元格和仅包含空白字符的值都会归一化为空白。普通空白数据值会变为空字符串。空白 `(yaml)` 单元格则保持为空白，不进行解析。

必需阶段选择器会拒绝空白值。可选阶段如果选择器为空白，则跳过。

#### 公式、日期、百分比和科学记数法单元格

V2.4 会拒绝在配置的 Case ID、标签、Case 数据、阶段选择器和阶段数据列中使用公式单元格。公式定义与缓存/显示结果可能不一致，因此不能用于生成可信的语义快照。请在 Excel 中重新计算后将结果粘贴为字面值，或者在专门的 ATT 步骤中进行计算。

与配置测试用例列相交且位于 `excel.headerRows` 以下的合并区域也会被拒绝。完全位于配置表头区域内的合并展示单元格则允许。

对于非公式单元格，ATT 导入显示文本。其精确表示遵循工作簿单元格格式和运行时区域设置：

| Excel 值与格式 | Context 值 |
|---|---|
| `45292` 格式化为 `yyyy-mm-dd` | `2024-01-01` |
| `0.125` 格式化为 `0.0%` | `12.5%` |
| `123000` 格式化为 `0.00E+00` | `1.23E+05` |
| `000123` 以文本形式存储/格式化 | `000123` |

普通列仍然是字符串。`(yaml)` 列可能将显示文本转换为其他 YAML 类型。对于日期、百分比、科学计数、账号或代码这类文本，应该使用引号把 YAML 标量包起来，以便保持为字符串。

#### 多行表头

`headerRows: 2` 表示第 1–2 行是表头，数据从第 3 行开始。ATT 会扫描每个物理列从上到下，使用最后一个非空且已去除首尾空白的表头单元格：

```text
第 1 行：基础数据 |           | 执行 |
第 2 行：Case ID    | Case name | Template  | Parameters
有效值：Case ID, Case name, Template, Parameters
```

ATT 不会拼接父子标签。表头匹配会移除空格、制表符、换行符、NBSP 以及其他 Unicode 空白字符；匹配其余部分仍区分大小写。例如，`案例 編號`、`案例\n編號`、`案例編號` 会被视为同一列。每个有效表头在归一化后必须唯一，因此仅因空白差异而不同的两个物理表头会被认为是重复表头错误。测试用例加载和结果工作簿写回使用相同的投影逻辑；结果列如果原本不存在，则会写入最终表头行。

#### 阶段与模板选择

每个侧车阶段都有一个不含点号的 `key`，以及一个命名物理 Excel 选择器列的 `template` 字段。选择器单元格可以包含符号模板名、完整相对模板路径，或 YAML 映射：

| 单元格值 | 含义 |
|---|---|
| `PAYMENT_INVOKE` | 符号名称简写 |
| `payment/local/CT001` | 相对 `templates.root` 的完整路径简写 |
| `name: PAYMENT_INVOKE` | 明确的符号名称映射 |
| `name: PAYMENT_INVOKE` 加其他键 | 模板选择 + 阶段私有行数据 |

ATT 会先将 `name` 作为全局唯一的符号名解析。只有在没有符号名匹配时，才会尝试完整相对模板路径。绝对路径、部分路径、以及逃逸出 `templates.root` 的路径都是非法的。

所有选择器映射键（包括 `name`）都会复制到阶段 Context 中。`stages[].dataColumns` 会增加更多阶段私有值。选择器映射与阶段数据列之间如果出现重复键，则报错。

#### 阶段执行控制

| 设置 | 值/默认值 | 含义 |
|---|---|---|
| `required` | boolean/`false` | 空白选择器是否视作错误 |
| `runWhen` | `normal`/默认、`onSuccess`、`onFailure`、`always` | 阶段何时有资格运行 |
| `onFailure` | `stop`/默认、`continue` | 后续适格工作是否继续 |

`continue` 不会把 FAIL 或 ERROR 改成 PASS，只是允许后续适格工作继续运行。

| 先前结果 | 后续 `normal` | `onSuccess` | `onFailure` | `always` |
|---|---:|---:|---:|---:|
| PASS | 运行 | 运行 | 跳过 | 运行 |
| FAIL/ERROR 且 `stop` | 跳过 | 跳过 | 运行 | 运行 |
| FAIL/ERROR 且 `continue` | 运行 | 跳过 | 运行 | 运行 |

使用 `onFailure` 做回滚/诊断，使用 `always` 做清理或最终证据收集。

### 2.2 Template

只有直接包含 template.yaml 的目錄才是可呼叫 Template。ATT 3.6.2 使用 att-template/v3.3。每個 Template 都需要非空且有序的 actions map，以及 description。

每個 Action 依類型使用不同契約。Render 回傳原樣 String，不寫入檔案。Tool/DB/HTTP/MQ/SSH action 發布原生型別化 operation result。Log 將 typed value 格式化為人類可讀內容。Assign 將值發布至 EXEC.VARS；Flow 在巢狀 Action scope 執行。

完整欄位、範例、typed result/evidence model、String Render 行為、HTTP/MQ/SSH boundary 與 migration guidance，請參閱[動作與型別化值](14_actions.md)。[Expressions and Built-ins](07_expressions.md) 說明共用 expression engine 與 Load ID initialization scope。
