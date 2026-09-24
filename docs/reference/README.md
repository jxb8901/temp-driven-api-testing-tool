# Reference Manual source modules

These modules are the authoritative editable sources for the current ATT Reference Manual.

- English sources: `docs/reference/`
- Chinese sources: `docs/reference.zh/`
- Deterministic order: `docs/reference-manifest.txt`
- Generated English outputs: `docs/reference.md` and `docs/reference.html`
- Generated Chinese outputs: `docs/reference.zh.md` and `docs/reference.zh.html`
- Canonical task navigation: `docs/README.md`
- Historical versioned/manual compatibility files: `docs/history/`

After editing modules, run:

```sh
python3 tools/build_reference_manual.py
python3 tools/validate_reference_content.py
python3 tools/verify_documentation.py
```

Tutorial material belongs in `docs/quick-start.md`; maintainer internals belong in `docs/system-design/`. Completed migration plans, old versioned manuals, and issue-specific design notes belong in `docs/history/` rather than beside current documentation.
