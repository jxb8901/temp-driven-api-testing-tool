# Load identity measurements

Measured on 2026-10-22 on macOS arm64 with JDK 26, against PR 274 head `1eedaa0dd28de1e5cbb7eac458560524e98ec302`. This isolates real `LoadRunResources` allocation/reservation code. It is not an end-to-end Load throughput benchmark and does not establish an application TPS improvement.

`tools/benchmark_load_identity.py` compiles the unchanged baseline resource owner under a separate class name, then invokes baseline default allocation, baseline custom-ID reservation, and current allocation in one JVM. Each condition has three seconds of paced single-thread arrivals, alternating workload labels. The custom condition includes creating the iteration request and allocating its formatted custom ID. The baseline's expression evaluator and target actions are excluded. The default/current conditions allocate identity only. JVM warmup/order effects remain; these are one observation per condition, not statistical estimates. The process CPU includes background JVM work. Thread allocation counts exclude other JVM threads. GC values are process counters. All conditions reached their requested count; paced rates do not measure maximum capacity.

| Identity path | Target arrivals/s | Allocations | Process CPU ms | Thread allocated bytes | GC count / ms | Files after close | Cleanup ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| legacy-default | 100 | 300 | 21.99 | 159232 | 0 / 0 | 0 | 0.08 |
| legacy-custom | 100 | 300 | 603.21 | 6432648 | 0 / 0 | 300 | 31.84 |
| engine-owned | 100 | 300 | 24.33 | 46960 | 0 / 0 | 1 | 1.14 |
| legacy-default | 500 | 1500 | 26.57 | 95208 | 0 / 0 | 0 | 0.04 |
| legacy-custom | 500 | 1500 | 1090.09 | 30455856 | 2 / 6 | 1500 | 97.82 |
| engine-owned | 500 | 1500 | 37.98 | 258160 | 1 / 2 | 1 | 1.82 |
| legacy-default | 1000 | 3000 | 56.92 | 191208 | 0 / 0 | 0 | 0.02 |
| legacy-custom | 1000 | 3000 | 1333.33 | 60201504 | 3 / 9 | 3000 | 171.80 |
| engine-owned | 1000 | 3000 | 56.85 | 522160 | 0 / 0 | 1 | 1.83 |

The removed custom-ID path created one SHA256 reservation file for every iteration. The current path created one durable `.execution-owner` claim per run and no reservation directory. The old default path already needed no per-iteration reservation. Current run ownership adds one file, retained after close to reject restarted or second owners with a reset sequence. The measured default/current CPU values do not establish a meaningful performance difference.

Cleanup measurements delete only disposable harness directories after both owners close. Production does not delete legacy marker directories at startup or shutdown; ordinary completed-job artifact retention owns their lifecycle. Historical evidence remains at its existing paths. Metrics/failure evidence remains lazy; the single run claim is the only new metrics-only identity file.

Reproduce after Engine tests have generated a Surefire classpath:

```bash
python3 tools/benchmark_load_identity.py --seconds 3 --output /tmp/load-identity.json
```

The [raw observations](load-identity-2026-10-22.json) include elapsed time and measured allocation rates. Broader application performance can be measured independently with `tools/benchmark_run_load_workloads.py`; this identity experiment makes no claim about target latency, scheduler throughput or Windows filesystem performance.
