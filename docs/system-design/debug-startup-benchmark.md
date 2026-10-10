# Debug startup benchmark

`tools/benchmark_debug_startup.py` compares the v3.7.3 baseline release and a candidate using both built source trees and extracted binary distributions. It copies each runtime into a temporary launch root, then installs equivalent synthetic fixtures and the same schema catalog. Config and Debug `schemaVersion` values follow each release's current schema; the fixture actions and inputs stay the same. The harness verifies runtime version labels and starts a new process for every sample.

The fixtures cover `version`, `help`, a no-op Template, a three-action Flow, a configured Tool, and an HTTP-backed Template calling a local test server. Cold samples have no explicit benchmark warmups; warm samples follow the configured warmup count. Both conditions still start a fresh process each time. The report includes p50, p95, mean, standard deviation and coefficient of variation for process-spawn-to-first-output and total wall time. It compares baseline and candidate source distributions separately from binary distributions. A positive improvement percentage means the candidate was faster. Candidate `performance.json` reports are captured in additional profiled runs and excluded from comparison samples.

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

Build each source tree explicitly and extract each binary distribution before running the harness. Source trees must contain prebuilt module `target/classes` (or the legacy root `target/classes`). The harness pairs source-built ATT classes with non-ATT dependencies from the matching binary package and any source `lib/`; binary distributions must contain `att.sh`/`att.bat` and `lib/att-*.jar`. Pass source revisions when the source trees are archives without Git metadata; the report also records binary tree SHA-256 digests, machine details, Java, and Python versions. The harness invokes the launchers, so wall timings include the shell or batch launcher and JVM startup. Use the same Java runtime and machine conditions for all four variants. These measurements are comparative evidence, not an absolute latency guarantee; filesystem and host load affect the result. The no-warmup samples do not flush the operating system's file cache.

The candidate profile separately records ATT timings from Java main entry, including `cliArgumentParseMs`, `processToFirstConsoleEventMs`, `processToFirstActionMs`, `debugResourceSetupMs`, `debugExecutionMs`, and `debugFinalizationMs`. Java main entry occurs after launcher and JVM startup. Each target gets one additional profile capture; it is not a percentile measurement.

## Recorded 4.0.1 comparison

The 2026-10-10 capture used a MacBook Air (Mac14,2, Apple M2, 8 GB), macOS 26.1, OpenJDK 26.0.1, and Python 3.13.1. It used ten samples per condition and three warmups. This host had no Java 8 or Java 17 installation, so the capture does not verify those runtime contracts. With ten samples, the nearest-rank p95 is the maximum observed sample; treat it as an exploratory comparison.

The candidate binary's warm Debug wall times were slower in this capture. For the no-op Template, total wall p50/p95 changed from 336.383/347.174 ms to 356.483/370.759 ms (+5.98% at p50). For the HTTP-backed Template, it changed from 440.465/458.138 ms to 460.234/479.674 ms (+4.49% at p50). Source no-op Template p50 changed from 345.272 ms to 400.401 ms (+15.97%); its candidate coefficient of variation was 4.97%, compared with 1.31% for the baseline. This run does not establish a performance improvement; use the raw data to investigate the regressions and repeat on supported Java versions before setting thresholds.

The full measurements, raw samples, candidate profiles, fixture schema versions, and artifact provenance are in [the 2026-10-10 JSON report](../performance/baselines/issue-177-debug-startup-macos-arm64-2026-10-10.json).
