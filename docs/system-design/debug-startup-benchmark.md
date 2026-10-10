# Debug startup benchmark

`tools/benchmark_debug_startup.py` compares the v3.7.3 baseline release and a candidate using both built source trees and extracted binary distributions. It copies each runtime into a temporary launch root, then installs identical synthetic fixtures and the same schema catalog. It verifies runtime version labels and starts a new process for every sample.

The fixtures cover `version`, `help`, a no-op Template, a three-action Flow, a configured Tool, and an HTTP-backed Template calling a local test server. Cold samples have no explicit benchmark warmups; warm samples follow the configured warmup count. Both conditions still start a fresh process each time. The report includes p50, p95, mean, standard deviation and coefficient of variation for process-spawn-to-first-output and total wall time. It compares baseline and candidate source distributions separately from binary distributions. A positive improvement percentage means the candidate was faster. Candidate `performance.json` reports are captured in additional profiled runs and excluded from comparison samples.

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

Build each source tree explicitly and extract each binary distribution before running the harness. Source trees must contain prebuilt module `target/classes` (or the legacy root `target/classes`); binary distributions must contain `att.sh`/`att.bat` and `lib/att-*.jar`. The harness invokes those launchers, so wall timings include the shell or batch launcher and JVM startup. Use the same Java runtime and machine conditions for all four variants. These measurements are comparative evidence, not an absolute latency guarantee; filesystem and host load affect the result. The no-warmup samples do not flush the operating system's file cache.

The candidate profile separately records ATT timings from Java main entry, including `cliArgumentParseMs`, `processToFirstConsoleEventMs`, `processToFirstActionMs`, `debugResourceSetupMs`, `debugExecutionMs`, and `debugFinalizationMs`. Java main entry occurs after launcher and JVM startup. The generated JSON is a baseline artifact suitable for retaining with the release evidence.
