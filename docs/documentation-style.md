# Documentation contracts and terminology

Reference is the normative source of truth for ATT public behavior. README, Quick Start and examples explain narrower tasks and link to the owning Reference section.

Each contract has one semantic owner:

| Contract | Owner |
|---|---|
| Testcase / Workbook / Sidecar / Snapshot / Stage / Template / Flow authoring | Test Authoring |
| Ordered Action execution, typed results, Log, Assign and collector shape | Actions |
| EXEC / META inventory, scope and lifetime | Runtime and Context |
| Expression grammar, typed evaluation, operators and built-ins | Expressions |
| Mode inputs, bootstrap scope, identity and output lifecycle | Run / Debug / Load |
| Resource descriptors and resource-local expressions | Resource chapter |
| Status, runWhen, onFailure, timeout, retry, aggregation and replay caution | Reliability |
| Global configuration, profiles, precedence and registry binding | Configuration and Environments |
| Artifact navigation and interpreting failed evidence | Results |
| Exact limits, collector projection guarantees and platform diagnostics | Appendix D |

Keep core concepts in chapters 1–5, working reference in chapters 6–13, and version matrices, compatibility, migration and advanced lookup in lettered appendices. Move existing technical content to its owner and replace duplicate normative tables with a summary and link.

Use the project version from `pom.xml` and active schemas from `schemas/catalog.yaml`. Current examples must use active contracts. Old versions are permitted only under `docs/history/`, migration/compatibility appendices, or explicitly bounded historical comparisons:

```text
<!-- att-docs:historical -->
An explicitly labelled historical comparison.
<!-- /att-docs:historical -->
```

Historical blocks must be paired and must not enclose normal current examples. Generated manuals carry the same appendix boundaries. Numeric contracts such as collector limits must remain documented when moved out of the main narrative.

Traditional Chinese prose keeps canonical ATT terms in English: Testcase, Stage, Template, Flow, Action, Tool, Resource, Sidecar, Context, Workbook, Snapshot, Evidence, Retry, Timeout, Runtime, Debug, Load, CLI and helper names. Explain a term in Chinese on first use when useful; its English name stays canonical. Keep common engineering terms in English when clearer, including payload, stdout, stderr, argv, thread, process, pool, transaction, parser, codec, transport, endpoint, artifact, manifest and profile. Code identifiers, schema fields, Context paths, filenames, options and literal configuration values remain exactly as authored. Avoid competing translated aliases and preserve readable mixed Chinese/English prose.

After edits, run:

```sh
python3 tools/build_reference_manual.py
python3 tools/verify_documentation.py
```

The release gate checks generated Markdown/HTML freshness, chapter/appendix order, EN/ZH structure, active schema matrices, stale version/schema claims throughout current documentation and examples, manifest integrity, canonical ZH terminology, local links and selected high-risk Context/CLI facts. Regression tests demonstrate rejection of stale examples, duplicate numbering, misplaced historical exemptions and invalid manifests.
