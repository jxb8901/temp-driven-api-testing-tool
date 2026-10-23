# Private artifact inventory

Completed Job archive publication walks the private output tree without following symlinks. Before accounting against the 50,000 candidate limit, it prunes exactly `load/<run>/.exec-id-reservations`, the historical per-iteration LOAD identity namespace. Only real directories at that position qualify. Case aliases, links and similarly named directories elsewhere receive the ordinary untrusted-path checks and limits.

The pruned namespace is never eligible for publication, including files with otherwise supported extensions. It creates neither manifest entries nor omissions. Pruning does not delete historical files; completed-Job retention owns their removal after Worker cleanup. Other paths, including directories and unsupported files, consume the candidate budget. A directory at depth 64 fails closed. Unreadable paths and traversal errors abort publication.

The inventory change does not alter sanitized export staging, HTML link rewriting, final-byte digests, CRC checks, ZIP verification, fsync, atomic publication, retry or ZIP-backed readers. See `ArtifactInventoryTest` for the 100k-file historical fixture, exact classification, candidate boundary and depth limits.
