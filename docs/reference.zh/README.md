# Reference manual source modules

These modules are the authoritative editable sources for the current ATT Reference Manual.

- English sources: `docs/reference/`
- Chinese sources: `docs/reference.zh/`
- Deterministic order: `docs/reference-manifest.txt`
- Generated English output: `docs/reference.md`
- Generated Chinese output: `docs/reference.zh.md`
- Canonical task navigation: `docs/README.md`
- Historical versioned/manual compatibility files: `docs/history/`

After editing modules, run:

```sh
python3 tools/build_reference_manual.py
python3 tools/validate_reference_content.py
python3 tools/verify_documentation.py
```

合併後的手冊使用 Markdown，GitHub 會直接呈現，不需要 HTML renderer。

Tutorial material belongs in `docs/quick-start.md`; maintainer internals belong in `docs/system-design/`. Completed migration plans, old versioned manuals, and issue-specific design notes belong in `docs/history/` rather than beside current documentation.

See the [documentation style and terminology policy](../documentation-style.md) for contract ownership, current-version rules and canonical English terms in Traditional Chinese prose.
