# One-shot execution prewarming

`workers.executionPool` is an optional, default-off source of unused READY JVMs for eligible RUN/DEBUG Jobs. A leased JVM executes one Job and exits; it is never returned to the pool. LOAD and public Validate Jobs continue using cold one-shot Workers. Inspection remains a separate existing read-only pool.

```yaml
workers:
  heapMaxMb: 256
  executionPool:
    enabled: false
    strategy: prewarmed-one-shot
    minIdle: 1
    maxIdle: 2
    heapBudgetMb: 512
    idleTimeoutMs: 300000
    startupTimeoutMs: 30000
    packageIds: [default]
```

Package IDs must be registered. Enabled pools require a positive Worker heap cap and `maxIdle * heapMaxMb <= heapBudgetMb`. This budget bounds configured idle JVM heap caps, not native memory or RSS. `minIdle` is a global floor of 0–16 idle Workers, and `maxIdle` is the global cap of 1–16; minimum cannot exceed maximum. The floor is initially distributed by rotating through configured package IDs. A request for a package without a READY Worker records demand, allowing maintenance to add that package up to the global cap even after the minimum is met. At the global cap, an uncovered package stays on cold dispatch; existing idle Workers are not evicted to rotate package affinity. Set `maxIdle` high enough for the packages that must have warm capacity, subject to the configured heap budget. Idle expiry accepts 1,000–3,600,000 ms; READY timeout accepts 1,000–120,000 ms. Unknown settings and strategies are rejected. Set `enabled: false` to return dispatch to the cold path; restart recovery still cleans recorded unused JVMs when disabled.

## Eligibility and environment

Idle startup loads shared ATT classes and proves protocol/product/commit/schema identity, fixed package CWD and unchanged runtime metadata before READY. It does not load package configuration, execute Tools or inherit application credentials. Only platform bootstrap environment values are retained, including `TZ` before JVM startup so `ZoneId.systemDefault()` and `TimeZone.getDefault()` match cold Workers. After assignment, a bounded immutable environment is sent over the private request pipe for framework environment references and child process Tools.

An in-JVM third-party client's direct `System.getenv()` is not changed by that overlay. Therefore any effective DB/MQ/SSH/HTTP helper, helper discovery failure, or configured additional Worker library directory requires cold dispatch. This conservative package-wide check includes unused helpers. Those Jobs retain the original native environment and resource semantics. JVM startup environment options (`JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS`) and font bootstrap overrides also require cold dispatch. Disabled/unavailable inspection and an over-limit environment use the same safe fallback. No native environment mutation is attempted.

Eligibility is a typed internal Inspector request, using the existing configuration parser and fresh package revision boundary. A cheap, non-reserving READY-capacity probe runs first, so a saturated or still-starting pool goes directly to cold dispatch without package inspection. Capacity may change during inspection; the later lease remains authoritative and a lost race safely falls back to cold dispatch. Eligibility runs outside the Job's cancellation/process-publication lock and obeys the existing bounded Inspector queue and timeout. Before a warm Worker can execute, it independently rebuilds and verifies the revision and compatibility again. Changed source is rejected, with no speculative retry. Draft validation, draft admission and Worker start each retain their authoritative source checks; display-only forms may reuse Inspector snapshots.

## Ownership and observations

The internal prewarm handshake has these required fields: `type: READY`, `protocolVersion: att-prewarm/v1`, `productVersion`, `gitCommit`, `schemaRegistryDigest` and `cwd`. Its JSON frame is capped at 8,192 UTF-8 bytes, excluding the line delimiter. There is no Job ID and no request before READY. Product, known commit, schema registry digest and canonical CWD must match the Server expectation; the Server also compares effective runtime metadata before accepting READY and before leasing. Missing, oversized, invalid or timed-out handshakes retire the process. The ordinary execution frame budget applies only after assignment.

The assigned request still uses `protocolVersion: att-worker/v1`, requires `expectedRevisionDigest`, and privately adds `executionEnvironment` (at most 4,096 entries and 131,072 total key/value characters, no NUL or invalid names). The Server discards caller-supplied environment data and supplies its own map only on warm assignment. Normal and inspection Workers reject that field. An empty/malformed/multiple-document request, wrong package, unsupported command or incompatible revision produces no business execution. The process consumes one document and EOF, emits at most one terminal RESULT, and exits. A lost pipe or crash is a Job failure, never an automatic resubmission.

READY is versioned `att-prewarm/v1`; normal Job input remains one JSON document followed by EOF. Pool ownership transfers to the Job process fence only after durable Job process identities are recorded. Idle death, expiry or changed runtime metadata causes retirement and cold fallback, with bounded startup backoff. Unexpected idle descendants or uncertain enumeration cannot authorize a lease. Shutdown uses the existing process-tree fence; uncertain cleanup retains recovery evidence and its bounded idle reservation. Restart checks PID/start identities, never signals a recycled PID, and waits for all discovered owned descendants before deleting recovery evidence. Complete `.prewarm-*.pending` records are recovered like committed `.json` records because they may contain identities published just before a crash. A partial or invalid pending record cannot safely identify a process: startup continues and retains the record, prewarming stays disabled while the uncertain evidence remains, and cold dispatch remains available. Invalid committed records remain a startup error.

`performance.workerDispatch` identifies `warm` or `cold`. Optional `prewarmFallbackReason` is a fixed reason code, not exception text or environment content. Timings include `prewarmEligibilityMs`, `prewarmLeaseMs`, `prewarmBootstrapMs`, the existing readiness/teardown timings and `firstMeaningfulEventMs` (LOG/PROGRESS, not STATUS). Pool statistics expose starts, leases, unavailable-ready fallbacks, startup failures, owned/ready JVMs, configured heap caps and CPU when available. An unavailable CPU/RSS measurement is not zero.

The feature is still under the broader lifecycle and performance verification described in `docs/performance/issues-278-281-2026-10-23.md`. Current short-job samples establish successful dispatch and fallback, not a production throughput guarantee. Concurrency, idle RSS and WAR undeploy measurements remain release gates.
