### 7.1 Operation Result 與 Evidence

ATT 將 operation 的邏輯結果與執行 evidence 分開：

~~~text
Operation
├── result       # native typed value
└── evidence     # 有界的 execution/transport metadata
~~~

Action 在 output.result 發布最後的 operation value。Action status、assertion detail、diagnostic、attempts 描述執行，不會取代 business result。Command stdout 使用 stdoutFormat 解析；HTTP/MQ response 使用 responseFormat；DB operation 回傳 native typed value。Render 回傳 DocumentValue，詳見[Action 與型別化值](../14_actions.md)。

Resource evidence 可包含低成本 metadata。Helper 也可選擇配置人類可讀 snapshot：

~~~yaml
evidence:
  output:
    format: json
    maxChars: 10000
~~~

Evidence output 支援 json、yaml、xml、text、sqlplus。這只用於 presentation，不會修改或取代 output.result。含 secrets 的值會過濾或省略。

Load scenario 可將 evidence.resources.output 設為 inherit（預設）或 none。none 略過可選的 resource-output formatting/materialization；inherit 會等 success sample 或 failure 取得 retention slot 後才格式化。Metrics-only iteration 不序列化 resource output，也不建立 evidence workspace。Transport parsing 與 Render representation 不變。
