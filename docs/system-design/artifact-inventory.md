# Private artifact inventory

Completed Job archive publication walks the private output tree without following symlinks. Before accounting against the 50,000 candidate limit, it prunes exactly `load/<run>/.exec-id-reservations`, the historical per-iteration LOAD identity namespace. Only real directories at that position qualify. Case aliases, links and similarly named directories elsewhere receive the ordinary untrusted-path checks and limits.

The pruned namespace is never eligible for publication, including files with otherwise supported extensions. It creates neither manifest entries nor omissions. Pruning does not delete historical files; completed-Job retention owns their removal after Worker cleanup. Other paths, including directories and unsupported files, consume the candidate budget. A directory at depth 64 fails closed. Unreadable paths and traversal errors abort publication.

The inventory change does not alter sanitized export staging, HTML link rewriting, final-byte digests, CRC checks, ZIP verification, fsync, atomic publication, retry or ZIP-backed readers. See `ArtifactInventoryTest` for the 100k-file historical fixture, exact classification, candidate boundary and depth limits.

## Bounded sanitized staging

After classification and one call to the approved-output Publisher, publication retains up to 8 MiB of sanitized payloads in memory per writer. Payloads that do not fit spill to the existing private export directory. The cap covers retained payload bytes, not JVM RSS or total allocations; the existing single-entry buffer can still reach the unchanged 16 MiB entry limit. The 128 MiB aggregate, 10k-entry and manifest limits remain enforced before publication. Publication never buffers the whole maximum-size archive.

Pass 2 consumes only those approved snapshots, from memory or spill, after the complete approved path set is known. It never rereads raw Worker files. HTML rewriting still removes links to omitted files. SHA-256 and CRC describe the final emitted bytes after HTML rewriting; full archive verification, file fsync and atomic replacement are unchanged. Raw mutation after a Publisher call cannot replace the already-approved bytes. Failed attempts remove temporary exports and staging ZIPs through the same cleanup path.

Additional bounded archive metrics are `temporaryExportFiles`, `temporaryExportBytes` (sanitized spill bytes written, and subsequently read once), and `memoryExportPeakBytes` (retained sanitized payload high-water mark). These contain no filenames or content. Historical metrics lacking these fields are unavailable, not zero. `ArtifactArchiveTest.cappedMemoryAndSpillPublishExactlyTheSameApprovedSnapshot` compares both storage paths, including redaction, later raw mutation, omission/link rewriting, manifest/digest/CRC verification and staging cleanup. The explicit `ArtifactPipelinePerformanceBenchmark` records reproducible small/large publication measurements.
