# Reference Manual source modules

These modules are the authoritative editable sources for the current ATT Reference Manual.

- English sources: `docs/reference/`
- Chinese sources: `docs/reference.zh/`
- Deterministic order: `docs/reference-manifest.txt`
- Generated combined outputs: `docs/generated/`
- Legacy `docs/09_Reference_Manual_V3*` paths are generated compatibility outputs.

After editing modules, run:

```sh
python3 tools/build_reference_manual.py
python3 tools/validate_reference_content.py
```

Issue #42 completed the semantic migration into the information architecture defined by `docs/documentation-architecture.md`. Tutorial material belongs in `docs/quick-start.md`; maintainer internals belong in `docs/system-design/`.
