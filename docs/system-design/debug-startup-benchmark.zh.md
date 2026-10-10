# Debug startup benchmark

`tools/benchmark_debug_startup.py` 會使用已建置的 source tree 和解壓後的 binary distribution 比較 v3.7.3 baseline release 與候選版本。它會將各 runtime 複製到暫存 launcher root，再安裝功能相同的合成 fixture 和同一個 schema catalog。Config 和 Debug 的 `schemaVersion` 會採用各 release 的 current schema；fixture action 和 input 保持一致。Harness 會核對 runtime 版本，並為每個 sample 啟動新的 process。

Fixture 包括 `version`、`help`、no-op Template、三個 action 的 Flow、已配置 Tool，以及呼叫本機測試 server 的 HTTP Template。Cold samples 不做明確 benchmark warmup；warm samples 則在設定的 warmup 次數後執行。兩種條件都會為每個樣本啟動新 process。報告會提供 process spawn 到第一個輸出，以及整個 command wall time 的 p50、p95、mean、standard deviation 和 coefficient of variation；source 與 binary distribution 會分開比較。正數 improvement percentage 代表候選版本較快。候選版本會額外執行 profile 並擷取 `performance.json`，這些額外樣本不會計入比較。

```sh
python3 tools/benchmark_debug_startup.py \
  --baseline-source /path/to/att-3.7.3-source \
  --baseline-binary /path/to/extracted/att-3.7.3 \
  --candidate-source "$PWD" \
  --candidate-binary /path/to/extracted/att-4.0.1 \
  --baseline-revision BASELINE_SHA \
  --candidate-revision CANDIDATE_SHA \
  --schemas-dir ./schemas \
  --warmups 3 --runs 10 \
  --output /tmp/att-debug-startup-4.0.1.json
```

執行前請明確 build 每個 source tree，並解壓每個 binary distribution。Source tree 必須有已編譯 module `target/classes`（或舊式 root `target/classes`）。Harness 會以相應 binary package 的 non-ATT dependencies，以及 source `lib/` 內的其他 dependencies，搭配 source-built ATT classes；binary distribution 必須包含 `att.sh`/`att.bat` 和 `lib/att-*.jar`。如果 source tree 是沒有 Git metadata 的 archive，請傳入 source revision；報告亦會記錄 binary tree SHA-256、機器資訊、Java 和 Python 版本。Harness 會實際呼叫 launcher，因此 wall timing 包含 shell/batch launcher 和 JVM startup。四個 variants 應使用相同 Java runtime 和機器環境。這些量度用於比較，不保證固定延遲；檔案系統和主機負載都會影響結果。No-warmup samples 不會清除作業系統 file cache。

候選版本的 profile 會分開記錄 Java main entry 之後的 ATT timing，包括 `cliArgumentParseMs`、`processToFirstConsoleEventMs`、`processToFirstActionMs`、`debugResourceSetupMs`、`debugExecutionMs` 和 `debugFinalizationMs`。Java main entry 發生在 launcher 和 JVM startup 之後。每個 target 會額外執行一次 profile；這不是 percentile measurement。

## 已記錄的 4.0.1 比較

2026-10-10 的測量使用 MacBook Air (Mac14,2、Apple M2、8 GB)、macOS 26.1、OpenJDK 26.0.1 和 Python 3.13.1；每個 condition 有十個 samples 和三次 warmup。此機器沒有 Java 8 或 Java 17，因此這次測量沒有驗證相關 runtime contract。十個 samples 下的 nearest-rank p95 等於觀察到的最大 sample，請把它視為探索性比較。

這次測量中，候選 binary 的 warm Debug wall time 較慢。No-op Template 的 total wall p50/p95 由 336.383/347.174 ms 變為 356.483/370.759 ms（p50 慢 5.98%）。HTTP-backed Template 由 440.465/458.138 ms 變為 460.234/479.674 ms（p50 慢 4.49%）。Source no-op Template 的 p50 由 345.272 ms 變為 400.401 ms（慢 15.97%）；候選版本的 coefficient of variation 為 4.97%，baseline 為 1.31%。這次結果未能證明效能有所改善；可先用 raw data 調查回退，再於支援的 Java 版本上重測，之後才設定門檻。

完整量度、raw samples、候選 profile、fixture schema versions 和 artifact provenance，請參閱[2026-10-10 JSON 報告](../performance/baselines/issue-177-debug-startup-macos-arm64-2026-10-10.json)。
