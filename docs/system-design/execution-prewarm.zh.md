# 一次性 execution prewarming

`workers.executionPool` 為預設關閉的可選功能，提供尚未使用且已 READY 的 JVM 給符合條件的 RUN/DEBUG Job。每個租用 JVM 只執行一個 Job，之後退出，不會返回 pool。LOAD 與公開 Validate Job 沿用 cold one-shot Worker；Inspection 則維持原有獨立唯讀 pool。

```yaml
workers:
  heapMaxMb: 256
  executionPool:
    enabled: false
    strategy: prewarmed-one-shot
    minIdle: 1
    maxIdle: 2
    heapBudgetMb: 512
    idleTimeoutMs: 300000
    startupTimeoutMs: 30000
    packageIds: [default]
```

Package ID 必須已登記。啟用時要求正數 Worker heap cap，且 `maxIdle * heapMaxMb <= heapBudgetMb`。此預算限制 idle JVM 的設定 heap 上限，並非 native memory 或 RSS。`minIdle` 為 0–16，`maxIdle` 為 1–16，minimum 不得超過 maximum。Idle expiry 為 1,000–3,600,000 ms，READY timeout 為 1,000–120,000 ms。不接受未知設定或 strategy。設回 `enabled: false` 後，dispatch 返回 cold path；即使關閉，restart recovery 仍會清理已記錄的未使用 JVM。

## Eligibility 與 environment

Idle startup 只載入 ATT 共用 class，確認 protocol/product/commit/schema identity、固定 Package CWD 及未變更的 runtime metadata 後才 READY。不載入 Package configuration、不執行 Tool，也不繼承 application credentials；只保留平台 bootstrap environment。分配 Job 後，才透過私有 request pipe 傳送有上限、不可修改的 environment，供 framework environment reference 及子 process Tool 使用。

此 overlay 無法改變第三方 JVM client 直接呼叫 `System.getenv()` 的結果。因此，只要 effective configuration 有 DB/MQ/SSH/HTTP helper、helper discovery failure，或配置額外 Worker library directory，就使用 cold dispatch；即使 helper 未被選取也同樣處理。這些 Job 保留原有 native environment 與 client semantics。JVM startup environment option（`JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`_JAVA_OPTIONS`）及 font bootstrap override 亦使用 cold path。Inspection 關閉、無法使用或 environment 超出上限時同樣 fallback，不修改 native environment。

Eligibility 使用內部 typed Inspector request、原有 configuration parser 及 fresh package revision boundary。執行時不持有 Job cancellation/process-publication lock，並遵守既有 bounded Inspector queue 與 timeout。Warm Worker 執行前會獨立重建、核對 revision 與 compatibility。來源變更會被拒絕，不會推測性 retry。Draft validation、admission 與 Worker start 各自保留 authoritative source check；只用於顯示的 form 仍可重用 Inspector snapshot。

## Ownership 與觀測

內部 handshake 必須包含 `type: READY`、`protocolVersion: att-prewarm/v1`、`productVersion`、`gitCommit`、`schemaRegistryDigest` 及 `cwd`。JSON frame 上限為 8,192 UTF-8 bytes，不計換行。READY 前沒有 Job ID 或 request。Product、已知 commit、schema registry digest 與 canonical CWD 必須符合 Server 預期；Server 在接受 READY 及 lease 前均比較 effective runtime metadata。缺少、過大、無效或 timeout 的 handshake 會令 process retire。

Assigned request 仍使用 `protocolVersion: att-worker/v1`，必須有 `expectedRevisionDigest`，並由 Server 私下加入 `executionEnvironment`：最多 4,096 entry、key/value 合計 131,072 character，不接受 NUL 或無效名稱。Server 會丟棄 caller 提供的 environment，只在 warm assignment 加入自身 map；normal 與 inspection Worker 拒絕此欄位。空白、無效或多 document request、錯誤 Package、非支援 command 或不相容 revision 均不執行 business action。Process 只讀取一個 document 與 EOF，最多發送一個 terminal RESULT 後退出。Pipe 中斷或 crash 會令 Job 失敗，不會自動重送。

READY 使用 `att-prewarm/v1`；Job input 仍為單一 JSON document 加 EOF。只有 Job process identity 已持久記錄後，ownership 才交給 Job process fence。Idle process 死亡、過期或 runtime metadata 變更時會 retire 並 cold fallback；啟動失敗採 bounded backoff。非預期 idle descendant 或不確定的 enumeration 不能授權 lease。Shutdown 沿用 process-tree fence；不確定的 cleanup 保留 recovery evidence 與 bounded idle reservation。Restart 檢查 PID/start identity，不會 signal 已回收的 PID，並在所有已發現 owned descendant 退出後才刪除 evidence。

`performance.workerDispatch` 顯示 `warm` 或 `cold`；可選的 `prewarmFallbackReason` 只包含固定 reason code，不含 exception text 或 environment value。Timing 包括 `prewarmEligibilityMs`、`prewarmLeaseMs`、`prewarmBootstrapMs`、原有 readiness/teardown timing，以及以 LOG/PROGRESS（非 STATUS）為準的 `firstMeaningfulEventMs`。Pool statistics 提供 starts、leases、無 READY Worker 的 fallback、startup failure、owned/ready JVM、設定 heap cap，及可用時的 CPU。無法取得的 CPU/RSS 不代表零。

完整 lifecycle 與 performance 驗證進度見 `docs/performance/issues-278-281-2026-10-23.md`。目前短 Job 樣本證明 dispatch 與 fallback 可成功運作，並非 production throughput 保證。Concurrency、idle RSS 與 WAR undeploy 測量仍是 release gate。
