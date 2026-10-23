# Load scheduler design

This page describes the Load implementation for ATT maintainers. User-facing scenario fields, pacing semantics, cancellation behavior, result schemas, and metric interpretation belong to the [Load Reference](../reference/execution-modes/load.md).

## Workload scheduling

`ClosedVuScheduler` models each virtual user as scheduler state rather than assigning a permanently occupied thread to it. `LoadWorkerPool` creates platform workers lazily as synchronous iterations block, up to the aggregate configured concurrency slots across workloads. This preserves configured closed-user concurrency while avoiding eager thread creation for idle users.

The coordinated scheduler waits for an arrival/VU deadline, a phase boundary, a completion event, or cancellation. It does not poll at a fixed millisecond interval. Arrival-rate scheduling calculates the next due time directly from the phase start and rate; overdue admissions are handled without searching all earlier arrivals. A capacity-rejected arrival is recorded as a generator drop and is never queued as SUT work. Arrival notifications are coalesced so a slow scheduler cannot accumulate an unbounded queue of wakeups.

Each admitted workload task has a cancellable handle. Cancellation first stops admission, then interrupts that workload's admitted tasks. The scheduler drains completion/finally events before freezing the workload result snapshot, so iteration outcomes and metrics include work that completed while cancellation was in progress.

## Execution identity and artifact storage

ATT allocates one engine-managed `EXEC.ID` per started iteration: `<runId>-execution-<sequence>`. One run-scoped, thread-safe monotonic allocator serves every workload, target, mix entry and phase. The ID is published before bootstrap variables and target actions. `EXEC.RUN_ID` remains the enclosing Run identity. Collision tracking uses no per-iteration files or growing identity registry.

`execution.execIdFormat` is rejected in current and historical Load Scenarios, policy-only files, inline drafts and saved Schedule/Worker snapshots, including an empty value. Remove the field and migrate to `att-load/v1.8`; review artifact-path consumers and store business correlation in a separate input/variable. Existing artifacts keep their original paths. Historical schema files remain available for inspection; historical resources without customization normalize to the current contract. Replace affected immutable schedules through the normal reviewed-draft workflow.

Generated identities retain the existing safe-segment grammar and 240-character limit. The Run ID must leave room for `-execution-` plus a signed-long sequence. Exhaustion fails permanently without wrapping. Every Run output namespace is claimed atomically once using `.execution-owner`. A second owner or restart must choose a fresh outer Run ID. Claims remain until safe completed-Job artifact retention removes the Run directory. Old reservation directories must only be removed by safe completed-Job retention; never delete markers while an old process can still use them. Metrics-only evidence remains lazy: this single Run metadata file does not materialize per-iteration workspaces.

Outer `execution.runIdFormat`, `execution.debugIdFormat`, exact `--run-id` and `--debug-id` behavior is unchanged. Diagnostic iteration/VU/Workload/mix IDs and scheduler event sequences remain separate from `EXEC.ID`. Independent distributed Workers must use disjoint namespaces or a shared allocator; separate process-local counters cannot safely share a Run namespace.

An iteration's `EXEC.OUTPUT_DIR` is a logical planned path. Metrics-only work does not create a durable per-iteration directory by itself. Resource-output formatting and deferred case-log evidence are materialized when retention selects the iteration. Temporary workspaces are cleaned after their data has been discarded or copied into retained evidence.

Failure capture follows the effective evidence policy and remaining retention capacity. When bounded failure capture is eligible, actions append to a redacted rolling tail in memory; the file is written only if the iteration is retained. Selected successes and full-success policies use deferred full logs. The implementation reserves evidence capacity before an iteration starts, so concurrent reservations can conservatively suppress capture when the remaining capacity is exhausted.

## Telemetry storage

Load latency percentiles use a bounded primitive `long` reservoir. Exact counts, mean, minimum, and maximum are tracked separately. Run-level, phase, and one-second time-series observations use separate capacities; the time-series store is a circular ring that keeps the newest 4,096 one-second buckets.

Generator and resource telemetry sampling is event-triggered and rate-limited to one observation per 100 ms. The heap soak test forces GC at warm-up and final checkpoints, then checks retained heap growth and confirms that iteration-scoped testdata state does not grow with iteration count. See [Load Generator Telemetry](load-telemetry.md) for the reported fields and maintainer verification procedure.

## Plan and resolver reuse

Load compiles reachable Template/Flow actions, primary Tool calls, argument expressions, guards, assertions, retry conditions, Render plans, and testdata input mappings before scheduling. Compiled structure and package sources are shared across iterations; each iteration evaluates them against its own Context. Load Render payload files are resolved and frozen once per run, while time, random, sequence, Context, and external values remain per-iteration.
