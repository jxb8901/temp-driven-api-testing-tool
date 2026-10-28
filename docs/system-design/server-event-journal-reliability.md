# Server Job event journal reliability

## Limits

Each Job owns `events.jsonl`. The record-count limit remains `server.maxEventsPerJob` (default 10,000; 100–1,000,000). `server.maxEventJournalBytes` adds an independent on-disk cap (default 64 MiB; 1–256 MiB). Event data stays capped at 65,536 UTF-8 JSON bytes and each JSONL record at 131,072 bytes. Rehydration streams only the last configured byte window, parses one bounded line at a time, and keeps at most the configured record count plus the journal-memory allowance. The 128 MiB accounted allowance is divided across `workers.maxConcurrent + workers.queuedLimit`, with a 1 KiB minimum share per Job. Each journal keeps its newest event even if that single event exceeds its share; this is bounded by the JSONL record limit, and metrics expose the resulting over-budget amount.

If a historical journal is larger than its configured cap, the Server reads only the bounded tail and leaves the original file untouched. A malformed or oversized tail marks that Job's history degraded. If the latest sequence cannot be verified, the Server does not assign new cursor IDs, preventing reuse of an ID hidden in the original bytes. For a terminal Job with a verified sequence but a missing result marker, the Server can restore the result event in memory from the separately persisted Job metadata without rewriting the journal. A terminal stream closes with a diagnostic if no safe result marker can be recovered.

Terminal Jobs are rehydrated on demand and cached by an access-ordered LRU limited to 16 Jobs and 128 MiB of accounted retained memory. Active Job journals are already held by their Job objects. This prevents repeated terminal-history reads from rehydrating a large file on every request.

## Append and compaction

Appends serialize per journal path, check usable storage headroom, and keep the next complete line within the byte cap. The free-space check leaves an 8 MiB threshold but cannot reserve filesystem capacity. When the next append would cross the byte cap, the Server synchronously compacts the retained window before writing. Compaction writes each JSON record directly to a unique sibling temp file; it does not build a full journal string.

Background compaction still starts every 128 appended events. It writes a bounded snapshot outside the append lock, then acquires the per-path lock for bounded tail catch-up and replacement. If a busy producer outgrows catch-up, the snapshot is discarded; the hard byte-cap path takes a fresh snapshot under the writer lock and converges. Before replacement, the temp file size and SHA-256 are verified. Only an atomic same-filesystem move replaces the original. An unsupported atomic move or storage error leaves the original journal intact.

## Storage failure behavior

An append or compaction failure affects only that Job journal. The Server stops hammering storage, rolls a partial line back to the previous file boundary when possible, and rate-limits persistence retries from 5 seconds up to 5 minutes. Event delivery can continue in bounded memory with monotonically increasing in-process IDs; nondurable events are marked in the returned event and the stream emits a separate diagnostic. Later durable events can contain ID gaps, which are detected on reload and reported as truncated history. The Job execution status and its result remain separate from this journal diagnostic.

`GET /api/v1/metrics` exposes active journal bytes, retained event and memory counts, compaction attempts/failures, degraded active Jobs, and bounded terminal-cache size. Per-Job SSE diagnostics include the byte cap, storage headroom, retained count, compaction status, and whether history was truncated. The 80% byte threshold is reported as a warning.

These protections bound journal-driven heap and disk growth; they do not reserve filesystem space or make unrelated H2, report, checkpoint, Worker-output, or artifact writes succeed when their filesystem is full. A full-filesystem event must preserve its original execution cause and report journal persistence separately. Operators must confirm the exact large file path before attributing an incident to `events.jsonl`; the reported 6.3 GB file path and the initiating OOM allocation remain unverified without deployment evidence.

## Operator recovery

1. Record the exact path, file type, filesystem, size, growth interval, free bytes/inodes, and related Job ID. Do not assume the large file is the Job journal.
2. Copy the original file to separate storage and verify the copy before attempting any repair. Preserve OOM/GC logs, JVM heap settings, Worker exit details, archive status, and timestamps.
3. Restore filesystem headroom. Check the Job's `journalPersistence`, `journalBytes`, retained tail, and compaction diagnostics in its event stream/result and in `GET /api/v1/metrics`.
4. For an oversized historical journal, keep the source untouched until an operator has preserved it. Then apply a reviewed repair on the copy or a maintenance window, verify event IDs and the terminal result, and retain the original as audit evidence.
5. Re-run the affected Job only after checking output/report/archive state. Do not delete a Job directory to recover space unless a separate verified backup and retention decision exist.

## Verification boundary

The regression fixture creates a sparse file larger than 6 GiB and writes only a small valid tail; it asserts bounded scan bytes, correct latest IDs, retained result delivery, and no modification to the oversized original. Separate tests cover oversized lines, byte-cap compaction, concurrent producers, terminal cache reuse, and simulated zero storage headroom. These synthetic tests demonstrate bounded source behavior, not the cause of the reported production incident or a 23-hour/100-TPS soak.
