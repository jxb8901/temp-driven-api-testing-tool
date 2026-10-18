# CI, packaging, and operations

ATT supports source-tree development and offline release packages.

## Development/release gates

```sh
mvn clean verify
python3 tools/build_reference_manual.py --check
python3 tools/validate_reference_content.py
./att.sh validate --package
```

`build.sh` runs the release gate, regenerates the modular Markdown Reference Manual, builds the application jar and release archives, and smoke-tests the extracted local package. It requires Java/Maven and Python 3. It outputs `att-4.2.1-local.tar.gz` for the CLI, `att-4.2.1-server.tar.gz` for the WAR and deployment guides, and `att-4.2.1-src.tar.gz` for source. Each binary archive must be smaller than 25 MB. The local package no longer includes the WAR or its duplicate runtime libraries.

## Runtime dependencies

The CLI, Engine, and Worker remain Java 8-compatible. The ATT Server WAR requires Java 17+ and external Tomcat 10.1+. ATT does not bundle JDBC drivers for CLI integrations; place required driver/dependency jars in `lib/`. IBM MQ is optional: the default CLI build remains usable without MQ client classes, while MQ deployments package the supported IBM client jar/profile.

## Studio inspection caches

Resource metadata discovery defers supported DB/MQ/SSH/HTTP Helper `${ENV:NAME}` values without reading process secrets. Resource summaries expose `metadataState` separately from `executionReadiness`: `not-validated` is not execution approval; `dependency-unresolved` includes value-free `ATT-RESOURCE-ENV-REQUIRED` diagnostics for discoverable transitive Helper references. These diagnostics name requirements, not confirmed missing variables or network failures. Explorer displays the effective environment and remediation while keeping safe metadata available. The legacy Resource `state: ready` describes metadata, not execution readiness.

Discovery still enforces root YAML/schema/profile checks and shared-config path boundaries. After validating those boundaries, it isolates malformed or missing DB/MQ/SSH/HTTP Helper descriptors, keeps healthy Helpers and unrelated resource metadata available, and reports value-free `ATT-RESOURCE-HELPER-INVALID` / `ATT-RESOURCE-HELPER-UNAVAILABLE` warnings in Explorer. Failed descriptors do not create usable Helper bindings. Explorer returns at most four individual Helper failure diagnostics plus a count of additional failures. Discovery is bounded to 256 descriptors per Helper type, 3,000,000 bytes per descriptor and 16 MiB per type; unsafe/symlink paths, duplicate paths/Helper IDs, trusted-schema failures and exceeded limits remain fatal. Tool groups with missing or invalid SSH Helper descriptors retain their logical bindings for discovery. Explorer propagates SSH binding and ENV requirements through command-backed Tools to dependent targets; unresolved bindings do not authorize execution. Tool-group structural/schema failures remain fatal. Configuration-page degradation remains a separate limitation. Selected-target-only strict loading and the unified automatic/manual reindex operation are not yet implemented. Explicit validation and execution continue to use strict configuration loading, so an unrelated active Helper with a missing variable can still block them. Metadata-only configurations cannot execute. Supply required variables to the Tomcat/Worker process environment and validate again; do not add production placeholder credentials to make browsing work.

Studio keeps Resource pages, Detail, safe Source, and ordinary Configuration projections in session memory. The shared cache retains at most 128 responses and 8 MiB of encoded payload, with a 1 MiB per-response admission limit. Entries remain fresh for 15 seconds; returning to an older view shows its cached projection while a background read refreshes it. In-flight reads are deduplicated, and aborting one consumer does not cancel another consumer's request. Search, sorting, pagination and scroll state remain available on view return.

Every reusable read first checks the authenticated inspection context. Identity, session, role or inspection-policy changes discard previous responses. Package/environment selection, explicit refresh, logout, page suspension, a newly observed package revision, and stale-cursor or access errors invalidate the affected memory. Nothing stores Configuration or Source content in localStorage, IndexedDB, a service worker, or an HTTP cache. Auth/CSRF responses and `includeAllFields` administrator responses bypass the query cache. `Cache-Control: no-store` remains enforced on HTTP responses.

Each inspection Worker still handles one request at a time and retains two Resource inspectors. Dispatch prefers an idle Worker already holding the same package/config/environment and policy key, then falls back to another idle Worker. Parsed Configuration snapshots have a separate four-entry LRU and a 32 MiB weighted retention budget per JVM; the configured Worker heap limit remains the hard JVM bound. Root configuration, selected Helper/Tool Group/Testdata dependencies, and the schema tree undergo bounded content reconciliation on reuse. Renames, deletions, same-timestamp replacements and unknown dependency changes therefore force a fresh load. Cache failures fall back to ordinary uncached validation. Every request constructs a fresh permission-checked projection; an administrator projection cannot enter the ordinary cache.

Optional `server.inspection.warmup` defaults to `enabled: false`, `workers: 1`, `keys: []`. `workers` must be from 1 through `server.inspection.maxConcurrent`. Each key contains a `packageId` and optional Engine `environment`; configure no more than twice the warmup Worker count, with no duplicates. Omit `environment` to warm Explorer's package-default index. Keys are distributed among Workers with at most two per Worker. With no keys, each selected Worker warms the first registered package. Missing packages and preload failures do not block Tomcat startup. Warmup skips interactive demand, retries each key at most three times with backoff, reloads after a daemon replacement, and stops at shutdown. Interactive traffic can cancel a preload when every Worker is occupied.

`GET /api/v1/metrics` follows the configured Server authentication policy and includes `inspection.server` and the latest bounded `inspection.workers` snapshots. Counters and total nanoseconds cover HTTP handling, queueing, process launch, dispatch, processing, Configuration load/parse, source requests, workbook/YAML parsing and index builds. They also report cache hits/misses/evictions, watcher invalidation/overflow counts, watched directories, retained keys, instrumented read bytes, protocol response bytes, heap-pool peaks, GC and process CPU. Up to 16 opaque package/environment aggregates retain request count, processing time, index builds, Configuration loads and instrumented read bytes per Worker. Package keys are hashed; counters never contain paths, configuration values or file contents. Byte counters cover instrumented inspection reads rather than OS-level physical I/O. Process-launch timing excludes JVM readiness; dispatch includes readiness and protocol transport. Benchmarks record sample p50/p95 separately. If optional inspection telemetry cannot fit within the configured response-byte budget, the Worker omits that snapshot and the Server clears its routing hint; metrics never relax the response limit or make an otherwise valid response exceed it.

Display snapshots never authorize execution. Debug/Quick Load approval and Worker execution retain authoritative package revision checks, `ATT-SERVER-DRAFT-STALE`, principal-bound cursors and stale-cursor HTTP `409`. See [Server deployment](../server-deployment.md) for the configuration example and [inspection benchmarks](../system-design/web-ui-inspection-inline-jobs.md) for measurement procedures.

## Documentation operations

`./att.sh docs` generates package documentation at `build/docs/index.html` from the validated ATT package model. The normative product Reference is independently generated from `docs/reference*` by `tools/build_reference_manual.py`. `./att.sh clean` removes documented generated runtime/build outputs but preserves source inputs.

## CI and environment promotion

CI should validate the package before executing external integration tests, keep Run/Debug/Load evidence as job artifacts as appropriate, and select environments through explicit `--config`/`--env` policy. Stable logical DB/MQ IDs let the same Templates move across SIT/UAT/PREPROD without Action edits.

Parallel jobs should use unique Run IDs and, when independent retention/latest-run state is required, separate output roots. Destructive operations such as clean/report/archive over one shared output root must be serialized.

Maintainer implementation sequencing, scheduler internals and resource-owner details live in `docs/system-design/`, not in this end-user Reference.
