# Documentation contracts and terminology

## Editorial baseline

Current ATT documentation follows the [Google Developer Documentation Style Guide](https://developers.google.com/style) unless an ATT-specific rule below overrides it. Write for a global developer audience in clear, direct English. Use sentence case for page titles and headings. Standalone Markdown pages have exactly one H1. Do not number headings to show sequence; use numbered lists for procedures. Use imperative headings for tasks and descriptive noun phrases for concepts and reference sections.

Prefer descriptive link text over positional references such as “above” and “below.” Avoid numbered chapter references; name and link to the destination section. Write “for example” or “such as” instead of the abbreviation `e.g.` Replace `and/or` with clear alternatives such as “either,” “both,” or “one or both.” Avoid subjective difficulty words such as “easy,” “simple,” and “simply” unless a measurable qualification makes the claim objective.

ATT-specific rules take precedence where the external guide conflicts with product contracts:

- Preserve canonical ATT terms and capitalization, including Testcase, Stage, Template, Flow, Action, Tool, Resource, Context, Debug, Load, CLI, and helper names.
- Keep code identifiers, schema fields, Context paths, filenames, command-line options, and literal values exactly as authored.
- In Traditional Chinese prose, keep canonical ATT technical terms in English as defined by the terminology policy below.
- Reference source modules are standalone pages composed in the order declared by `docs/reference-manifest.txt`; the generator rebases their headings when building the combined manual.

The documentation release gate checks editable English Markdown for standalone-page H1 count, numbered sequence headings, `e.g.`, `and/or`, and selected positional cross-references. It also checks that each current Reference source is a standalone page with one H1 and a continuous heading hierarchy. It ignores fenced and inline code, Markdown link destinations, generated Reference Markdown/HTML, and historical documentation. Generated Reference outputs remain freshness-checked against their source modules. Keep EN/ZH structure checks and the ATT-specific contract checks in force.

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

Organize the Reference around core concepts, working lookup guidance, and appendices for version matrices, compatibility, migration, and advanced lookup. Move existing technical content to its owner and replace duplicate normative tables with a summary and link.

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
