# ATT V2.4 System Design

Target release: 2.4.0

## 1. Scope and source of truth

V2.4 adds testcase version control without changing the established workbook → Case → ordered stage → template → ordered action → tool model. The `.xlsx` workbook remains the only editable source. Every workbook has two adjacent companions:

```text
testcase/payment.xlsx   # editable source
testcase/payment.yaml   # workbook sidecar
testcase/payment.xml    # generated semantic snapshot
```

The XML is not an unpacked OOXML workbook and cannot be used to regenerate Excel. It records only values ATT consumes after sidecar mapping and normalization. Styles, widths, comments, filters, formulas outside configured columns, and unconfigured sheets/columns are excluded.

## 2. Snapshot contract

The root is `<testcases schemaVersion="att-testcases/v2.4" workbookId="...">`. Groups follow sidecar order and contain `id`, physical `sheet`, and Cases in workbook order. A Case is identified by `groupId.rowCaseId` and contains ordered tags, configured case data, and configured stages. Row numbers are not persisted.

Values use explicit `type="string|integer|decimal|boolean|null|map|list"`. Maps use ordered `<entry name="...">` children and lists use ordered `<item>` children. This preserves parsed `(yaml)` cell types without relying on XML text inference.

Canonical output is UTF-8 without BOM, LF-only, two-space indentation, fixed element/attribute ordering, and one final newline. String values containing LF or XML-special `&`, `<`, or `>` characters use CDATA so the testcase text remains directly readable and unescaped; ordinary strings use normal escaped XML text. A literal `]]>` is losslessly split across adjacent CDATA sections in one deterministic form. Spaces or tabs immediately before an LF are emitted as `&#32;` or `&#9;` outside the adjacent CDATA so the parsed value remains exact without introducing Git trailing-whitespace errors. DTDs, external entities, XInclude, and external schema access are disabled. `schemas/att-testcases-v2.4.xsd` publishes the document shape; runtime strict parsing additionally enforces value-type/content rules and canonical bytes.

## 3. Generation and verification

```sh
./att.sh snapshot --suite testcase/payment.xlsx
./att.sh snapshot --all
```

Generation resolves the mandatory sidecar and fully loads every selected workbook before writing any snapshot. Each destination is written through a same-directory temporary file and atomically replaced when supported. A parse/build failure does not overwrite the existing XML.

`run`, `validate --selected`, and `validate --package` require every selected workbook snapshot to exist, parse securely, match the current schema, be canonical, and equal the current normalized testcase projection. Verification is read-only and never regenerates XML. A mismatch is `ATT-TC-001` and reports added/removed/reordered groups or Cases plus field paths such as `payment.TC001.data.amount` or `payment.TC001.stages.invoke.name`.

Framework planning verifies the snapshot before creating the output or `.in-progress` directory. Completed run manifests hash the workbook, sidecar, and testcase snapshot as separate inputs.

## 4. Excel boundaries and workflow

Formula cells in configured Case ID, tags, data, stage selector, or stage-data columns are invalid because a formula and its cached/displayed result can diverge. Authors must paste literal values and use Excel Text for identifiers or leading-zero values. Any merged region intersecting configured testcase columns below the header area is invalid; merged presentation headers within `excel.headerRows` remain supported.

Normal authoring flow is: edit xlsx → run snapshot → review the XML diff → run `validate --package` → commit xlsx, YAML when changed, and XML together. XML must not be manually edited. Binary workbook merge conflicts are reconciled in Excel and followed by fresh snapshot generation.

## 5. Release gates

- Deterministic output across repeated generation, locales, and LF/CRLF environments.
- Semantic changes produce minimal field-level diffs; style-only changes do not alter XML.
- Chinese names, multiline/CDATA strings, XML-special characters including literal `]]>`, empty strings, nested maps/lists, booleans, integers, decimals, and null values round-trip exactly.
- Missing, stale, malformed, non-canonical, XXE/DTD-bearing, formula-backed, and merged-data inputs fail before output mutation.
- `mvn test`, `./build.sh`, repository `validate --package`, and built-package `validate --package` pass with all sample snapshots present.
