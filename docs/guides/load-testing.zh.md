# 執行 Load test

可在 Studio 使用六種 Load Template catalog 及 validated builder，或使用目前 4.2.5 Local CLI 執行已撰寫的 scenario。

## Studio workflow

1. 開啟 Load Testing，選擇符合 workload shape 的 template。
2. 綁定所有必要 Target，並在 editor 設定 workload pacing 與 business input。
3. 驗證組合後的 scenario，並檢視已遮蔽的 preview。
4. 確認 Run Now，或使用已驗證的 draft 建立 Schedule。
5. 在 Jobs and Results 檢視 execution record 與 evidence。

六種 mode 定義請參閱 [Load model](../concepts/load-model.zh.md)。[Load Reference](../reference.zh/execution-modes/load.md) 定義 template、binding 及 execution rules。

## 目前 4.2.5 CLI

執行已撰寫的 scenario，或將一個支援的 Debug sidecar 提升為 Load workload：

~~~sh
./att.sh load ../examples/load/closed-smoke.yaml --config config/config.yaml --env SIT
./att.sh load --debug template PAYMENT_INVOKE --load-template builtin:single-vu --preview
./att.sh load --debug --load-template load/templates/mixed.yaml --bindings load/bindings/mixed.yaml --env SIT --preview
~~~

List 和 preview operation 不會啟動 traffic。從有效的 execution command 移除 preview 可能會產生 Load traffic。不要將 credential 或真實 endpoint 放進已提交的範例。

目前 4.2.5 Load CLI 與 proposed CLI vNext 是不同 interface。請參閱 [CLI Reference](../reference/cli.md) 及[規劃中的 vNext 頁面](../interfaces/cli/vnext-proposed.zh.md)。
