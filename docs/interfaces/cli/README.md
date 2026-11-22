<!-- att-doc-status: current release-target=4.6.0 status=unpublished legacy-code-line=4.3.1 -->

# CLI interfaces

The legacy Local CLI and ATT Remote compatibility surface corresponds to the 4.3.1 code line. The unpublished 4.6.0 release-target stack retains those interfaces and adds explicit Native Case and Native Suite RUN commands. The 4.6.0 release and tag have not been published; deployed acceptance remains pending.

| Interface | Status | Guide |
|---|---|---|
| Local commands: run, validate, snapshot, debug, load, docs, report, build, clean | Legacy CLI surface from 4.3.1 code line | [CLI Reference](../../reference/cli.md) |
| att remote profiles, Jobs, events and artifacts | Legacy compatibility surface from 4.3.1 code line | [ATT Remote guide](../../remote-client.md) |
| Native Case `run native-case` and implicit Suite `run native-suite` | Implemented in the 4.6.0 target stack; release and deployed acceptance pending | [CLI Reference](../../reference/cli.md) |
| Broader common run, load and validate grammar with Local/Server routing | Planned; not released | [Proposed CLI vNext](vnext-proposed.md) |

The broader vNext proposal does not remove existing Server, Studio or REST capabilities. Native Server RUN uses the validated `att-execution/v2` draft flow; the legacy `att remote` interface remains available. Job management, detach and other #294 P3 proposals are outside that grammar.
