# ATT 3.3.0 diagnostic implementation and acceptance

Status: implementation and release checks complete on 2026-09-17.

## Scope

Improve error provenance and explanations without changing the shared Template/Flow Context, existing Action execution policies, or V2 configuration semantics. Keep Excel row/column separate from physical YAML/payload line/column. Preserve the innermost cause and source while recording caller context separately. Do not expose secrets through source excerpts or diagnostic values.

## Phase 1 — Diagnostic foundation

- [x] Add immutable physical `SourceLocation` and execution `DiagnosticContext`.
- [x] Preserve provenance through `DiagnosticException` wrapping and conversion.
- [x] Share CLI human/JSON rendering, including unexpected failures in JSON mode.
- [x] Stop overwriting nested Flow source locations; carry explicit Flow descriptor paths.
- [x] Verify producers/converters preserve the new fields, including reports.

## Phase 2 — Source location

- [x] Capture YAML node marks in the same safe parse used to construct documents.
- [x] Integrate configuration, Tool group, DB helper, sidecar, Template and Flow loaders.
- [x] Report the precise Tool definition field and actual DB helper file.
- [x] Give schema violations individual structured paths/locations.
- [x] Map expression offsets to source tokens; distinguish exact token locations from scalar-range fallback for folded/escaped YAML.
- [x] Preserve precise Action subfields and payload-file locations for static and runtime validation.
- [x] Verify selected/package validation paths, aliases, changed-file caches and parse-count limits.

## Phase 3 — Expression and Context

- [x] Add structured syntax exceptions with expected/actual tokens and offsets, preserving nested offsets.
- [x] Distinguish missing, explicit null, deferred shape, malformed path, scalar traversal, index bounds and ambiguous shorthand.
- [x] Only defer values whose runtime dependency is established; remove broad unknown suppression.
- [x] Preserve typed null arguments while retaining null-to-empty behavior for text interpolation.
- [x] Verify legacy expression/call contracts and error compatibility.

## Phase 4 — Runtime and evidence

- [x] Preserve Flow call chains and inner failure summaries in top-level results.
- [x] Tool errors include attempts, effective timeout, elapsed time, capture/parse status and artifact locations.
- [x] DB errors identify binding index/name, scalar row/column counts, effective timeout and cancellation outcome.
- [x] Path errors identify configured path, resolved path, allowed root and concrete reason.
- [x] Evidence/log failures remain visible without replacing the original execution failure; distinguish cleanup warnings and intentional stream closure.

## Phase 5 — Reporting and release

- [x] Carry structured diagnostics into Case YAML, persisted results, regenerated reports, CLI JSON and CI output.
- [x] Bump product version to 3.3.0 (not schema versions or dependency/plugin versions).
- [x] Update README, CHANGELOG, system design, Quick Start, English/Chinese reference manuals and generated HTML.
- [x] Regression coverage for all five phases, including physical versus Excel coordinates and redaction.
- [x] `mvn test` (264 tests, no failures)
- [x] `./att.sh validate --package` (2 suites, 24 cases, 9 templates, 21 tools)
- [x] Flow end-to-end run and inspect nested evidence/report diagnostics (`FrameworkEngineTest.runsNestedV31FlowThroughFullCaseLifecycle`)
- [x] `./att.sh docs`
- [x] `./build.sh`
- [x] Unpack binary distribution and run `./att.sh validate --package`
- [x] `git diff --check`

## Completion rule

Only mark the release complete after every requirement has current authoritative evidence. Passing older tests alone is insufficient. Keep this checklist current as implementation proceeds; do not turn unsupported diagnostics into silent validation success.
