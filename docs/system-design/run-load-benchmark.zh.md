# Run 與 Load 效能基準

`tools/benchmark_run_load_workloads.py` 以產生的 20-case workbook 測量 4.0.1 CLI 的新程序 Run 路徑，並以 loopback HTTP fixture 測量 Load generator。每個 Load 條件使用固定 arrival rate 及一種 evidence policy（`metrics`、`failures` 或 `samples`）。Harness 會將程序啟動與首個輸出時間、Run case 數量與輸出大小、Load throughput／latency／scheduler metrics、generator CPU／heap／GC／thread metrics、resource pool observations 及原始重複樣本記錄為 JSON。

Fixture 只回傳固定 JSON response，並非外部 system under test。其 HTTP helper 設定為 `maxConnections: 256` 及 `maxConnectionsPerRoute: 256`；這些是 benchmark override，並非產品預設值。請參閱 [HTTPHelper pool 預設值及 sizing](../reference/resources/httphelper.md)、[DBHelper pool 預設值](../reference/resources/dbhelper.md)、[MQHelper pool 預設值](../reference/resources/mqhelper.md)，以及 [Load generator telemetry 與 PayloadCache 上限](load-telemetry.zh.md)。

## 重現方式

建立 4.0.1 package、解壓縮，然後在 repository root 執行：

```sh
env MAVEN_ARGS=-o ./build.sh
mkdir -p /tmp/att-4.0.1-runtime
tar -xzf dist/releases/att-4.0.1-local.tar.gz -C /tmp/att-4.0.1-runtime
python3 tools/benchmark_run_load_workloads.py \
  --runtime-root /tmp/att-4.0.1-runtime/att-4.0.1-local \
  --runtime-revision <source-commit> \
  --rates 100,500,1000 \
  --evidence-modes metrics,failures,samples \
  --duration 5s --load-warmup 1s --max-concurrent 256 --max-samples 10 \
  --runs 3 --warmups 1 \
  --output target/run-load-benchmark-4.0.1.json
```

Runner 只使用 Python standard library，會建立臨時 minimal ATT package、產生並 snapshot workbook，並啟動只監聽 loopback 的 HTTP server。它不會連接外部網絡。若 Load run 出現 runtime errors，報告仍會保留其 exit status 及 metrics；`failures` evidence run 亦會包含最多三個保留的 case log 範例。解讀結果時不可刪除出錯的 run。

已提交的 [4.0.1 報告](baselines/issue-177-run-load-4.0.1.json)包含 package tree hash、source revision、作業系統、硬件、logical CPU 數、Python／JVM 版本、benchmark 設定、原始樣本、每個 helper 的 HTTP pool snapshot 及跨 run 摘要。Whole-process p95 使用三個測量樣本的 nearest-rank，因此等於三次觀察中的最大值。每次 Load run 的 p95／p99 則取自 ATT bounded latency reservoir。這些結果是本機 baseline，並非可移植的效能門檻。

## 已記錄的 4.0.1 結果

此測量使用 source revision `b35bc653561b45d441cafda4537a8d66e09eb039`，在 Mac14,2 MacBook Air、Apple M2、8 GB RAM、macOS 26.1、8 個 logical CPU、OpenJDK 26.0.1 及 Python 3.13.1 執行。JSON 報告記錄 binary tree SHA-256。當時本機沒有 Java 8 或 17 可供此次 benchmark 使用。

20-case Run 的三次測量均執行全部 case。由程序啟動至首個輸出為 p50 299.838 ms／p95 300.525 ms；程序總 wall time 為 765.419／770.085 ms。以上數值包含 launcher 及 JVM 啟動時間。

| Arrival rate | Evidence | PASS 次數 | Achieved TPS 中位數 | 每次 run 的 p95 中位數 | 三次測量的 warmup runtime errors |
| ---: | --- | ---: | ---: | ---: | --- |
| 100/s | metrics | 3/3 | 99.780 | 3 ms | 0, 0, 0 |
| 100/s | failures | 3/3 | 99.860 | 3 ms | 0, 0, 0 |
| 100/s | samples | 3/3 | 99.840 | 3 ms | 0, 0, 0 |
| 500/s | metrics | 3/3 | 499.301 | 1 ms | 0, 0, 0 |
| 500/s | failures | 2/3 | 499.700 | 2 ms | 0, 0, 6 |
| 500/s | samples | 1/3 | 499.700 | 2 ms | 4, 0, 1 |
| 1,000/s | metrics | 0/3 | 998.403 | 1 ms | 57, 14, 41 |
| 1,000/s | failures | 1/3 | 998.801 | 1 ms | 33, 31, 0 |
| 1,000/s | samples | 0/3 | 999.001 | 1 ms | 70, 49, 63 |

所有測量階段均沒有 runtime error、SUT failure 或 dropped arrival。出錯 run 的 failure evidence 顯示，在 warmup 期間連接 loopback fixture 出現 `HTTP_TIMEOUT`／`HTTP connect timed out`；ATT 因此正確將整個 run 標記為 `ERROR`。HTTP pool 上限設定為 256，觀察到的最高 active connections 為 179，pool waiting 維持為零。數據顯示 generator 在測量階段排定請求率接近目標，但此本機設定的初始 connection burst 在 500/s 時偶爾失敗、在 1,000/s 時經常失敗。這些樣本不能視為乾淨的高負載通過，也不能當作一般 ATT capacity 結論；目前無法判定連線 timeout 是 loopback fixture、host socket 行為或 HTTP client startup 所致。

測量目標只限 loopback。這些數據不代表遠端 HTTP latency、Server Workers、Tomcat、SSE delivery 或 end-to-end job throughput；這些需要獨立的 Server benchmark。
