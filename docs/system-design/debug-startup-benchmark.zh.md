# Debug startup benchmark

`tools/benchmark_debug_startup.py` 會使用已建置的 source tree 和解壓後的 binary distribution 比較 v3.7.3 baseline release 與候選版本。它會將各 runtime 複製到暫存 launcher root，再安裝完全相同的合成 fixture 和 schema catalog；每個樣本都會啟動新的 process，並核對 runtime 版本。

Fixture 包括 `version`、`help`、no-op Template、三個 action 的 Flow、已配置 Tool，以及呼叫本機測試 server 的 HTTP Template。Cold samples 不做明確 benchmark warmup；warm samples 則在設定的 warmup 次數後執行。兩種條件都會為每個樣本啟動新 process。報告會提供 process spawn 到第一個輸出，以及整個 command wall time 的 p50、p95、mean、standard deviation 和 coefficient of variation；source 與 binary distribution 會分開比較。正數 improvement percentage 代表候選版本較快。候選版本會額外執行 profile 並擷取 `performance.json`，這些額外樣本不會計入比較。

```sh
python3 tools/benchmark_debug_startup.py \
  --baseline-source /path/to/att-3.7.3-source \
  --baseline-binary /path/to/extracted/att-3.7.3 \
  --candidate-source "$PWD" \
  --candidate-binary /path/to/extracted/att-4.0.1 \
  --schemas-dir ./schemas \
  --warmups 3 --runs 10 \
  --output /tmp/att-debug-startup-4.0.1.json
```

執行前請明確 build 每個 source tree，並解壓每個 binary distribution。Source tree 必須有已編譯 module `target/classes`（或舊式 root `target/classes`）；binary distribution 必須包含 `att.sh`/`att.bat` 和 `lib/att-*.jar`。Harness 會實際呼叫 launcher，因此 wall timing 包含 shell/batch launcher 和 JVM startup。四個 variants 應使用相同 Java runtime 和機器環境。這些量度用於比較，不保證固定延遲；檔案系統和主機負載都會影響結果。No-warmup samples 不會清除作業系統 file cache。

候選版本的 profile 會分開記錄 Java main entry 之後的 ATT timing，包括 `cliArgumentParseMs`、`processToFirstConsoleEventMs`、`processToFirstActionMs`、`debugResourceSetupMs`、`debugExecutionMs` 和 `debugFinalizationMs`。Java main entry 發生在 launcher 和 JVM startup 之後。產生的 JSON 可作為 release evidence 保存的 baseline artifact。
