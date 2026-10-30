# Load model

ATT LOAD separates workload shape, target binding and pacing. A mix weight chooses the next iteration target; it does not divide the configured VU pool.

## Six workload shapes

The current Load Template catalog has six entries. Single VU and Single TPS use the Quick Load editing flow. The other four use the multi-workload builder.

| Mode | Workloads | Target bindings | Pacing |
|---|---:|---|---|
| Single VU | 1 | One fixed Target | Closed VU |
| Single TPS | 1 | One fixed Target | Arrival rate |
| Single Workload Mix | 1 | Weighted mix of Targets | Closed VU |
| Multi-workload VU | Multiple | One fixed Target per workload | Independent closed VU pools |
| Multi-workload TPS | Multiple | One fixed Target per workload | Independent arrival rates |
| Multi-workload Mix | Multiple | An independent weighted mix per workload | Independent closed VU pools |

For Multi-workload Mix, Workload A and B can bind the same Target independently and use different weights. A scenario with two workloads, four mix entries and three unique Targets still has two workload pools. TPS values are configured arrival targets; achieved throughput and drops are reported separately.

All workloads in a scenario use the shared phase timing envelope required by the Engine. Mixed workloads use closed VU pacing. The UI catalog and Load Template CLI compose a normal target-bound scenario for validation before execution.

## Where the contracts live

The [Load Reference](../reference/execution-modes/load.md) owns schema, composition, scheduler, telemetry, thresholds and evidence. The [Load guide](../guides/load-testing.md) shows the current 4.2.8 workflow. This page is a model overview, not a second schema specification.
