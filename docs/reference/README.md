# Reference Manual source modules

These modules are the authoritative editable sources for the current ATT Reference Manual.

- English sources: `docs/reference/`
- Chinese sources: `docs/reference.zh/`
- Deterministic order: `docs/reference-manifest.txt`
- Generated combined outputs: `docs/generated/`
- Legacy `docs/09_Reference_Manual_V3*` paths are generated compatibility outputs.

Run `python3 tools/build_reference_manual.py` after editing source modules. Use `--check` in CI.

Issue #41 performs structural modularization only. Transitional legacy-section placement is intentionally preserved until #42 completes the semantic migration/rewrite defined by `docs/documentation-architecture.md`.
