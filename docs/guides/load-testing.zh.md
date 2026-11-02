# 執行 Load test

可在 Studio 使用六種 Load Template catalog 及 validated builder，或使用目前 4.2.8 Local CLI 執行已撰寫的 scenario。

## Studio workflow

1. 開啟 Load Testing，選擇符合 workload shape 的 template。
2. 綁定所有必要 Target，並在 editor 設定 workload pacing 與 business input。
3. 驗證組合後的 scenario，並檢視已遮蔽的 preview。
4. 確認 Run Now，或使用已驗證的 draft 建立 Schedule。
5. 在 Jobs and Results 檢視 execution record 與 evidence。

六種 mode 定義請參閱 [Load model](../concepts/load-model.zh.md)。[Load Reference](../reference.zh/execution-modes/load.md) 定義 template、binding 及 execution rules。

## 目前 4.2.8 CLI

執行已撰寫的 scenario，或將一個支援的 Debug sidecar 提升為 Load workload：

~~~sh
./att.sh load ../examples/load/closed-smoke.yaml --config config/config.yaml --env SIT
./att.sh load --debug template PAYMENT_INVOKE --load-template builtin:single-vu --preview
./att.sh load --debug --load-template load/templates/mixed.yaml --bindings load/bindings/mixed.yaml --env SIT --preview
~~~

List 和 preview operation 不會啟動 traffic。從有效的 execution command 移除 preview 可能會產生 Load traffic。不要將 credential 或真實 endpoint 放進已提交的範例。

## 長時間 LOAD run

Browser Quick／Advanced Load draft 及 package-backed scenario 均使用 Server 設定的上限 `server.inlineLoad.maxDurationSeconds`，最多 864,000 秒（10 日）。完整 envelope 依 ramp-up、warmup、steady duration 及 ramp-down 順序執行。長時間 run 會執行唯讀 preflight，估算保留 evidence、Tool output、artifact archive 及輸出磁碟空間；這些風險以 warning 顯示，duration 本身不會拒絕 run。

目前 4.2.8 Load CLI 與 proposed CLI vNext 是不同 interface。請參閱 [CLI Reference](../reference/cli.md) 及[規劃中的 vNext 頁面](../interfaces/cli/vnext-proposed.zh.md)。

### 診斷 MQ pool 等候

LOAD MQ borrow 以同一個等候預算處理 admission、實體容量及連線建立。timeout 為零時，只會取得立即可用的 idle 連線。連線建立超過預算後，實體容量仍會保留至建立完成；遲到的連線可供重用，或在關閉時釋放。Pool shutdown 會非同步啟動實體 disconnect，並最多等候 borrow timeout（含短暫下限）處理未完成的 create/close；若仍有 reservation，teardown 會以 `LOAD_RESOURCE_TEARDOWN_TIMEOUT` 失敗，並在診斷中保留 `residualCreating` / `residualClosing`，不會把未確認的實體容量當成已釋放。逾時後才完成的 disconnect 仍會更新 reservation 計數，且不會允許超容量替換。若 pool 已滿，而實體連線正在建立或關閉，borrower 會等候可用容量；預算到期時回報 `MQ_POOL_TIMEOUT`。診斷包含受限長度的 helper、instance 識別及 pool 快照。請連同設定的 pool 大小與 borrow timeout 檢查 active、idle、creating、closing 和 waiting 數值。實體 disconnect 失敗時，容量會維持佔用，直到能確認 teardown。Pool 正確運作不需要延長 ramp-down 或將 `minIdle` 設為零。

若所有業務 iteration 都是 PASS，而最後的 disconnect 回報 MQRC 2009，請把 PRE_TEARDOWN 的 `resources.mq[<logicalId>::<instanceId>]` 與 `resources.postTeardown` 分開看。Teardown 前記錄的 `failures` 不包含這些 disconnect。Teardown 紀錄使用 `<logicalId>:<instanceId>`，並標成 `PHYSICAL_DISCONNECT_FAILED` 或 `LOAD_RESOURCE_TEARDOWN_TIMEOUT`。Job 應維持 ERROR。請用失敗時間對照 queue manager、channel、client 與 network 日誌；reason 2009 本身不能證明是 idle timeout，也不能證明 queue manager 已釋放 session。可選的 `pool.heartbeatInterval`、channel HBINT 與作業系統 TCP keepalive 是較早發現失效 socket 的支援方式。`staleIdleValidation` 與 `maxLifetime` 可以在不送出業務訊息的情況下回收 idle 連線。設定 `maxIdle` 或 `maxLifetime` 時，背景掃描大約每 30 秒也會關閉沒有再被借用的 idle session，而不會留到最終 teardown。一般的 borrow 與歸還通知不會提早執行這次掃描，`close()` 會立刻停止掃描。連線失敗會把 disconnect 排入佇列，不會堵住 LOAD worker。每個 pool 最多同時執行 4 個 disconnect；已排隊的 close 會保留容量，shutdown 會把已排隊與執行中的 disconnect 一併計算。見 [閒置 MQ 連線與 teardown](../reference.zh/resources/mqhelper.md#閒置-mq-連線與-teardown)。
