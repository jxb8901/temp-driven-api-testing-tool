# SSHHelper 系統設計

狀態：維護者文件。公開契約以[使用手冊](../reference.zh/05_resources/sshhelper.md)為準。

## 解析與驗證

Config loader 驗證 `att-config/v2.10`，對所選 `environments.<name>` 進行清單級 shallow replacement，載入 `att-sshhelper/v1.0` descriptor，然後以選定 registry 解析 current Tool group 的 `ssh.helper`。Action 層沒有路由狀態。Descriptor loader 以 `LinkedHashMap` 保留清單次序，拒絕不安全／重複路徑與忽略大小寫後重複的 ID，先套用 defaults 再套用 instance override，建立不可變的有效 `SshConfig`。Schema 與語意檢查在外部執行前拒絕未知欄位、缺少有效 host/user、錯誤 port/strategy 和找不到的邏輯綁定。舊 v2.6／v2.2 直接 SSH 路徑維持不變。

```text
CLI --env -> 有效 config -> SSHHelper registry -> Tool group binding
                                             -> command argv -> selection -> SSH transport
```

## 選擇與執行

Tool group strategy 覆蓋 helper 預設。`single` 選唯一 instance；`random` 均勻選一臺；`roundRobin` 對每個 helper 使用 atomic 循環計數器，並行呼叫不會重用同一計數位置。`all` 按 descriptor 次序為每個 instance 建立一個 task，透過最多 `fanout.maxConcurrency` 個 worker 的固定 pool 執行。每個 task 使用已展開的命令 argv 和有效 Action／Tool／全域 timeout；不會改到另一臺重試。Outcome 按 descriptor 次序而非完成先後組裝。單臺 I/O／parse／timeout 錯誤保留在該筆，不清除其他主機 outcome；命令正常完成但 exit code 非零時，仍屬執行成功，`exitCode` 留給 Action assertion 判定，本身不會令 fan-out 失敗。所有 instance 均無操作失敗時 aggregate 才成功。父執行緒中斷會取消 pool task；`CommandRunner` 強制銷毀已中斷的 OpenSSH process，`JschSshClient` 在 `finally` 斷開 channel/session。

OpenSSH 與 Java SSH 使用同一個有效 `SshConfig` 及安全引號處理的 logical argv。完整 `${ENV:NAME}` identityFile 路徑在載入 descriptor 時解析；變數缺失時不顯示 secret。Transport 選擇屬基礎設施選擇，不是 routing strategy。兩者均嚴格驗證 host key。`identityFile` 內容、環境提供的 key 路徑、resolved credential 和 session handle 不會進入 Context 或 evidence。

Evidence 邊界會在 transport stderr preview、串流 Case log 診斷、argv 及 exception message 中遮蔽環境提供的 identity path。單目標和 fan-out 路徑都適用；解析後的業務 stdout 不變，因此遠端命令不可輸出祕密。

## Result 與 evidence 邊界

單目標呼叫保留既有已解析 Tool result，另加 logical ID、所選 instance／host、strategy／source、transport metadata。`all` 的 operation value 為 `{instances: {<id>: <per-host result>}}`；每臺記錄 status、endpoint、transport、起訖／持續時間、exit code、受限的 stdout/stderr、解析後 output 或 error。Tool operation evidence 隨後由共同 Action lifecycle 包裝。Assertion 與之後的 Action reference 可讀取有序的 per-instance map。混合 outcome 時，aggregate failure 有分類，同時在 exception evidence 保留完整 map。這不是分散式交易，也不是 failover 結果。

## 範圍與驗證

驗證應涵蓋 SIT/UAT 重新綁定、descriptor 繼承／覆蓋、無效 schema／未知 binding、並行 round-robin、受限且混合結果的 `all`、timeout／cancellation、兩種 transport 和舊式直接 SSH。執行 focused tests、完整 Maven test/package gate，並重新生成／檢查中英文 Reference Manual。未來 host discovery、per-call selection 與跨主機 orchestration 需要另訂契約。
