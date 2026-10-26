# Internal limits and diagnostics design

This note defines how ATT records internal capacity limits and explains their
outcomes. The machine-readable source is
[`../operations/internal-limits-inventory.json`](../operations/internal-limits-inventory.json).
Operator guidance is published in the paired
[Limits Reference](../operations/limits-reference.md) and
[繁體中文容量參考](../operations/limits-reference.zh.md).

## Outcome taxonomy

Limit outcomes use one of these classifications: `EXECUTION_ERROR`,
`SUBMISSION_REJECTED`, `ARCHIVE_FAILED`, `ARTIFACT_OMITTED`, `DATA_TRUNCATED`,
`HISTORY_EVICTED`, or `METRICS_SAMPLED`. Execution status, exit code, threshold
verdict, archive lifecycle, and data completeness remain separate values.

Diagnostics identify a primary execution or admission cause and may carry
bounded secondary notices for transport, presentation, artifact, or coverage
limits. Safe fields include a stable limit name, configured limit, observed
usage, scope, outcome, stage, remediation hint, and logical artifact reference.
They must not contain secrets, business payloads, or private host paths.
Server API and CLI JSON preserve these bounded fields. Human CLI output prints
the primary diagnostic code first, followed by separate notice and transport
lines; it omits free-form diagnostic text. Studio follows the same primary-first
ordering while keeping notices separate from the execution result.

## Precedence

ATT selects a genuine runtime, preparation, or Worker failure before considering
transport truncation or archive availability. `ATT-WORKER-EVENT-TRUNCATED` and
`ATT-JOURNAL-EVENT-TRUNCATED` remain secondary notices. Truncation, sampling,
history eviction, and archive unavailability alone cannot change a clean
execution to `ERROR`. Publication failure remains an archive outcome; it does
not rewrite the Job result.

## Inventory contract

Each JSON inventory entry records the setting, source path and class, default,
allowed range, scope, configurability, enforcing boundary, external result,
diagnostic, presentation surface, and safe remediation. `hardCoded` bounds use
`null` for unsupported configurable ranges. Tests bind named safety bounds to
their source constants and bind configurable settings to their validators.
English and Traditional Chinese operator references are generated and reviewed
from the same verified entries; the inventory remains the authority.

## Bounded observability

Existing Engine, Worker, Server, archive, and LOAD metrics are reused. Usage is
reported as `usage`, `limit`, and, when meaningful, `threshold`; absent
measurements are represented as unavailable, not zero. Telemetry stays
aggregated per execution or subsystem, with no per-iteration cardinality.
The 0.8 threshold is an advisory early-warning signal in bounded metric snapshots;
it never changes execution outcome and does not create per-event SSE or Journal
traffic.

## Ownership and follow-ups

This cross-cutting contract builds on [#297 compact terminal results](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/297),
[#86 LOAD metrics](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/86),
[#236 archive publication](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/236)/[#243](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/243),
[#265 live-output controls](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/265),
[#281 metrics](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/281),
and [#263 execution behavior](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/263).
It does not change their feature-specific limits. The rolling one-second series
retains 4,096 buckets while a bounded coarse series covers the full execution.
Requirements for higher-resolution history across long runs are documented in the
[separate time-series retention note](load-time-series-retention.md) and tracked
by [#299](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/299).
This issue reports measured coverage and does not implement a long soak.
