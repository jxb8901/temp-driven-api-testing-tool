# Reference Manual source modules

These modules are the authoritative editable sources for the current ATT Reference Manual.

- English sources: `docs/reference/`
- Chinese sources: `docs/reference.zh/`
- Deterministic order: `docs/reference-manifest.txt`
- Generated combined outputs: `docs/generated/`
- Legacy `docs/09_Reference_Manual_V3*` paths are generated compatibility outputs.

Run `python3 tools/build_reference_manual.py` after editing source modules. The generator reads the ATT version from `pom.xml`, requires Python 3 and Pandoc, writes both languages in the manifest order, and refreshes the generated and compatibility outputs. Use `python3 tools/build_reference_manual.py --check` in CI to detect missing modules or stale outputs.

`build.sh` also runs the generator before the release gate, so packaged documentation is assembled from these modular sources rather than from a hand-maintained monolithic Markdown file.

Issue #41 performs structural modularization only. Transitional legacy-section placement is intentionally preserved until #42 completes the semantic migration/rewrite defined by `docs/documentation-architecture.md`.
