<!-- att-doc-status: current release=4.2.5 -->

# 25 小時 LOAD soak acceptance

使用選擇性 CLI Local benchmark，量度 ATT arrival generator 和 Engine 執行確定性的本機 no-op Template。此 run 不會呼叫外部 system under test。

## 準備 runtime

從要量度的 commit 建立 Local distribution，然後解壓縮。將 `RUNTIME_ROOT` 設為包含 `att.sh`（或 `att.bat`）及 `lib/` 的解壓目錄。

Acceptance 預設使用 seed `177`、`LOAD_SOAK_NOOP` Template、每秒 150 個 configured arrival、25 小時 measured duration、最大 concurrency 512、沒有 warm-up 或 ramp，並只保留 metrics evidence。Scenario 要求整體 completed 和 successful throughput 至少為 100/s，且沒有 dropped arrival。

## 執行 acceptance

在 repository root 執行：

```sh
python3 tools/run_load_soak.py \
  --runtime-root "$RUNTIME_ROOT" \
  --output target/load-soak-evidence \
  --commit-sha "$(git rev-parse HEAD)"
```

執行期間保持 host 喚醒，直至 process 結束。Script 不接受少於 25 小時的 duration，亦不接受每秒 100 或以下的 configured arrival rate。不要使用已包含該 run ID 的 output directory。

## 驗證結果

只有 command 成功結束且 `acceptance.json` 記錄 `result: PASS`，才算通過。Gate 會檢查 exact scheduled arrivals、整體及合格五分鐘 interval 的 completed throughput、零 drops 和 failures、verified accounting 和 checkpoints、完整 report manifest、`MET` report performance verdict，以及足夠密集的 resource samples 和有界的 RSS、heap、thread、file descriptor 增長。

Resource samples 必須涵蓋合格 duration 至少 90%，每項必要 metric 的 sample coverage 至少 95%。Gate 會比較首尾 30 分鐘 endpoint window 的 median。Open file descriptor 最多增加 2 個；thread 增長不得超過 baseline 的 5% 或 2 個 thread（取較大值）。RSS 增長不得超過 64 MiB，或 baseline 的 10% 與 256 MiB 兩者中的較小值（取較大值）。Heap-used 增長上限為 16–64 MiB，依 configured maximum heap 的 1% 計算。

Evidence directory 包含 `run-metadata.json`、`acceptance.json`、`five-minute-trend.json`、`jvm-resource-trend.json`、`process-resource-samples.json`、logs，以及 `load/<RUN_ID>/` 下保存的 report 和 checkpoints。Metadata 記錄 commit、seed、target、workload controls、作業系統、處理器、記憶體及 Java runtime。

中斷的 run 若有 verified checkpoint，會記為 `PARTIAL`；若沒有可用 checkpoint，會記為 `REPORT_UNAVAILABLE`。兩者均不算通過。請保留 evidence directory 供診斷。

長時間量度使用 CLI Local，不會建立 Server Job ZIP。Server Job publication、archive integrity 和 retrieval 由一般 CI 的 Server LOAD integration tests 驗證。
