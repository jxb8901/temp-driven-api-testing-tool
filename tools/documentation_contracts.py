#!/usr/bin/env python3
"""Validate current public documentation against project/catalog metadata."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SCHEMA_TOKEN = re.compile(r"\b(att-[a-z0-9-]+)(?:/v|-v)([0-9]+(?:\.[0-9]+)*)", re.I)
HISTORICAL_START = "<!-- att-docs:historical -->"
HISTORICAL_END = "<!-- /att-docs:historical -->"


def active_schemas(catalog):
    active = {}
    for line in catalog.splitlines():
        match = re.search(r":\s*(att-[a-z0-9-]+)-v([0-9]+(?:\.[0-9]+)*)\.(?:schema\.json|xsd)\s*$", line)
        if match:
            name, version = match.groups()
            active.setdefault(name, set()).add(version)
    if not active:
        raise ValueError("catalog contains no active schema files")
    return {name: frozenset(versions) for name, versions in active.items()}


def proposed_schemas(catalog):
    """Validate every proposed catalog entry; ignore only blank/comment lines."""
    allowed_top = ("schemaVersion", "status", "schemas")
    schemas = {}
    seen_keys = set()
    seen_top = set()
    schema_version = None
    status = None
    in_schemas = False
    for lineno, line in enumerate(catalog.splitlines(), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if re.match(r"^[^\s#]", line):
            in_schemas = False
            top = re.match(r"^([A-Za-z0-9_-]+)\s*:(.*)$", line)
            if not top:
                raise ValueError("proposed schema catalog has invalid top-level entry on line %s" % lineno)
            key = top.group(1)
            value = top.group(2).strip()
            if key not in allowed_top:
                raise ValueError("proposed schema catalog has unexpected field: %s" % key)
            if key in seen_top:
                raise ValueError("proposed schema catalog has duplicate field: %s" % key)
            seen_top.add(key)
            if key == "schemaVersion":
                schema_version = value
                if schema_version != "att-proposed-schema-catalog/v1":
                    raise ValueError("proposed schema catalog must declare att-proposed-schema-catalog/v1")
            elif key == "status":
                status = value
                if status != "proposed":
                    raise ValueError("proposed schema catalog must declare status: proposed")
            else:
                # Allow an explicit empty mapping after every proposed schema is promoted.
                if value and value != "{}":
                    raise ValueError("proposed schema catalog schemas must be a mapping on line %s" % lineno)
                in_schemas = True
            continue
        if not in_schemas:
            raise ValueError("proposed schema catalog has unexpected indented entry on line %s" % lineno)
        match = re.match(
            r"^\s+(att-[a-z0-9-]+)/v([0-9]+(?:\.[0-9]+)*):\s*([A-Za-z0-9._-]+\.schema\.json)\s*$",
            line, re.I)
        if not match:
            raise ValueError("proposed schema catalog has invalid schemas entry on line %s" % lineno)
        name, version, filename = match.groups()
        catalog_key = "%s/v%s" % (name.lower(), version)
        if catalog_key in seen_keys:
            raise ValueError("proposed schema catalog has duplicate entry: %s" % catalog_key)
        seen_keys.add(catalog_key)
        filename_version = re.search(r"-v([0-9]+(?:\.[0-9]+)*)\.schema\.json$", filename, re.I)
        if not filename_version or filename_version.group(1) != version:
            raise ValueError("proposed schema catalog filename does not match %s/v%s" % (name, version))
        expected = "%s-v%s.schema.json" % (name.lower(), version)
        if filename.lower() != expected:
            raise ValueError("proposed schema catalog filename does not match %s/v%s" % (name, version))
        schemas.setdefault(name.lower(), set()).add(version)
    if schema_version is None:
        raise ValueError("proposed schema catalog must declare att-proposed-schema-catalog/v1")
    if status is None:
        raise ValueError("proposed schema catalog must declare status: proposed")
    if "schemas" not in seen_top:
        raise ValueError("proposed schema catalog must declare schemas:")
    return {name: frozenset(versions) for name, versions in schemas.items()}


def without_code(text):
    lines = []
    fence = None
    for line in text.splitlines():
        match = re.match(r"^\s*(\x60{3,}|~{3,})", line)
        if match:
            if fence is None:
                fence = match.group(1)[0]
            elif fence == match.group(1)[0]:
                fence = None
            continue
        if fence is None:
            lines.append(line)
    return "\n".join(lines)


def current_blocks(text):
    """Only explicit historical blocks and Appendix B/C permit old contracts."""
    historical = False
    appendix = None
    for line in text.splitlines():
        if HISTORICAL_START in line:
            if historical:
                raise ValueError("nested historical block")
            historical = True
            continue
        if HISTORICAL_END in line:
            if not historical:
                raise ValueError("unmatched historical block end")
            historical = False
            continue
        heading = re.match(r"^#{1,2}\s+Appendix\s+([A-D])\b", line)
        if heading:
            appendix = heading.group(1)
        elif re.match(r"^##\s+[0-9]+\b", line):
            appendix = None
        if not historical and appendix not in ("B", "C"):
            yield line
    if historical:
        raise ValueError("unclosed historical block")


def stale_claims(text, active, version):
    errors = []
    for lineno, line in enumerate(current_blocks(text), 1):
        for match in SCHEMA_TOKEN.finditer(line):
            name, found = match.groups()
            versions = active.get(name.lower(), ())
            if versions and found not in versions:
                errors.append("stale schema %s/v%s; active versions are %s (line %s)" %
                              (name, found, ", ".join("v" + value for value in sorted(versions)), lineno))
        aliases = {
            "Load": "att-load", "Debug": "att-debug", "DBHelper": "att-dbhelper",
            "MQHelper": "att-mqhelper", "HTTPHelper": "att-httphelper",
            "Tool Group": "att-tool-group", "Template": "att-template",
            "Flow": "att-flow", "config": "att-config",
        }
        for label, name in aliases.items():
            for found in re.findall(r"\b" + re.escape(label) +
                                    r"\s+v([0-9]+(?:\.[0-9]+)*)\b", line, re.I):
                versions = active.get(name, ())
                if versions and found not in versions:
                    errors.append("stale %s v%s; active versions are %s" %
                                  (label, found, ", ".join("v" + value for value in sorted(versions))))
        for found in re.findall(r"\bATT\s+[Vv]?([0-9]+(?:\.[0-9]+){2})\b|"
                                r'["\x27]?attVersion["\x27]?\s*:\s*["\x27]([0-9.]+)', line):
            claimed = found[0] or found[1]
            if claimed != version:
                errors.append("stale ATT version %s; project is %s" % (claimed, version))
    return errors


def manifest_errors(items):
    errors = []
    if not items:
        errors.append("empty Reference manifest")
    if len(items) != len(set(items)):
        errors.append("duplicate Reference manifest entry")
    for item in items:
        parts = item.split("/")
        if item.startswith("/") or "\\" in item or any(p in ("", ".", "..") for p in parts) or not item.endswith(".md"):
            errors.append("invalid Reference manifest entry: " + item)
    return errors


def chapter_shape(text):
    return [2 for line in without_code(text).splitlines()
            if re.match(r"^##\s+", line)]


def structure_errors(text):
    expected_count = 16
    errors = []
    shape = chapter_shape(text)
    if len(shape) != expected_count:
        errors.append("expected %d top-level chapter/appendix headings; found %d" %
                      (expected_count, len(shape)))
    headings = re.findall(r"^##\s+(.+)$", without_code(text), re.M)
    folded = [s.casefold() for s in headings]
    if len(folded) != len(set(folded)):
        errors.append("duplicate top-level chapter title")
    return errors



PEER_RESOURCES = ("Tool", "DBHelper", "MQHelper", "HTTPHelper", "SSHHelper")
RETIRED_CHAPTER_LABELS = {
    "environment and test data": "Configuration and Environments",
    "validation and diagnostics": "Validation and Troubleshooting",
}


def overview_resource_errors(text):
    """Check each place that teaches the inventory, not merely global presence."""
    errors = []
    definition = re.search(r"\*\*Resource\*\*([^\n]*)", text)
    peers = re.search(r"^## [^\n]*Resource[^\n]*\n(.*?)(?=^## |\Z)",
                      text, re.M | re.S)
    sections = {
        "Resource definition": definition.group(1) if definition else "",
        "peer Resource explanation": without_code(peers.group(1)) if peers else "",
    }
    diagrams = re.findall(r"(?:\x60{3}|~{3})text\n(.*?)(?:\x60{3}|~{3})",
                          peers.group(1), re.S) if peers else []
    diagram = "\n".join(diagrams)
    for location, content in sections.items():
        for resource in PEER_RESOURCES:
            if not re.search(r"\b" + resource + r"\b", content):
                errors.append("%s omits peer resource %s" % (location, resource))
    for resource in PEER_RESOURCES:
        if not re.search(r"^\s*" + resource + r"\b", diagram, re.M):
            errors.append("peer Resource diagram omits " + resource)
    if re.search(r"^##\s+(?:三種|3\s+)[^\n]*Resource", text, re.M):
        errors.append("obsolete three-resource heading")
    return errors


def _heading_section(text, match):
    marks = re.match(r"^(#+)", text[match.start():])
    level = len(marks.group(1))
    rest = text[match.end():]
    nxt = re.search(r"^#{1,%d} " % level, rest, re.M)
    return rest[:nxt.start()] if nxt else rest


def product_model_order_errors(text):
    """Keep the published Product Model Flow-centric and free of legacy execution chains."""
    errors = []
    model = re.search(r"^#{1,6} (?:Product model|產品模型)\s*$", text, re.M)
    if not model:
        return ["missing Product model heading"]
    section = _heading_section(text, model)
    blocks = re.findall(r"(?:\x60{3}|~{3})text\n(.*?)(?:\x60{3}|~{3})", section, re.S)
    if not blocks:
        errors.append("Product model has no text diagram")
    else:
        first = blocks[0]
        if "Resolved Case" not in first or not re.search(r"(?m)^Test Case Definition\b", first):
            errors.append("first Product model diagram is not the Flow-centric Case path")
        if "ordered Stage" in first:
            errors.append("first Product model diagram is the legacy Stage chain")
    if re.search(r"^#{1,6} (?:Supported legacy compatibility|受支援的 Legacy 相容模型)\s*$",
                 text, re.M):
        errors.append("legacy compatibility section belongs in docs/legacy")
    if re.search(r"Workbook row\s*[-=]+>\s*Stage|Workbook row\s*\n\s*`-- ordered Stage", text):
        errors.append("legacy Workbook/Stage chain belongs in docs/legacy")
    primary = section
    if re.search(r"reusable Template logic|可重用 Template 邏輯", primary):
        errors.append("primary model defines Flow as Template logic")
    if re.search(r"normalized workbook row|正規化的 Workbook row", primary):
        errors.append("primary model defines a Testcase as a workbook row")
    return errors


def flow_authoring_errors(text):
    """The Flow chapter defines a first-class Action sequence, not Template logic."""
    errors = []
    if re.search(r"reusable Template logic|可重用的 Template logic|可重用 Template 邏輯", text):
        errors.append("Flow is defined as Template logic")
    heading = re.search(r"^#{1,6} Flow\s*$", text, re.M)
    if not heading:
        return errors + ["missing Flow heading"]
    section = _heading_section(text, heading)
    if "Test Case Definition" not in section:
        errors.append("Flow section does not bind a Native Test Case Definition")
    if "ordered Actions" not in section and "有序 Actions" not in section:
        errors.append("Flow section does not define ordered Actions")
    if not re.search(r"Test Case Definition.{0,100}(?:binds|綁定).{0,80}Flow|"
                     r"Native Test Case Definition.{0,100}Flow", section, re.I | re.S):
        errors.append("Flow section does not bind the Native Test Case Definition directly")
    if re.search(r"legacy Template|舊式 Template", section):
        errors.append("legacy Template invocation belongs in docs/legacy")
    return errors


LEGACY_AUTHORING_PATTERNS = (
    r"\batt-sidecar/v[0-9]",
    r"\batt-template/v[0-9]",
    r"\batt-testcases/v[0-9]",
    r"\bMETA\.TEMPLATE\b",
    r"Workbook/Sidecar/Snapshot",
    r"Workbook row\s*(?:[-=]+>|\n\s*ordered)\s*Stage",
    r"^#{1,6}\s+(?:Workbook|Stage|Template)(?:\b|\s|$)",
    r"\b(?:Workbook|Sidecar)\s+(?:authoring|execution model|data source|input|schema)\b",
    r"\b(?:Template|Stage)\s+(?:authoring|definition|selection|execution model)\b",
)


def legacy_authoring_ownership_errors(rel, text):
    """Reject old authoring concepts only on current primary documentation surfaces."""
    errors = []
    prose = without_code(text)
    for pattern in LEGACY_AUTHORING_PATTERNS:
        if re.search(pattern, prose, re.I | re.M):
            errors.append(rel + ": legacy authoring or execution detail belongs in docs/legacy")
            break
    return errors


def documentation_ownership_errors(root, manifest_items):
    """Keep current docs Native-first and give earlier Package behavior a bilingual home."""
    errors = []
    if any("legacy/" in item.casefold() for item in manifest_items):
        errors.append("Legacy pages must stay outside the generated current Reference manual")
    required = (
        "README.md", "README.zh.md", "authoring.md", "authoring.zh.md",
        "execution.md", "execution.zh.md", "configuration.md", "configuration.zh.md",
        "migration.md", "migration.zh.md", "cli.md", "cli.zh.md",
        "reliability-reference.md", "reliability-reference.zh.md",
        "server-run-reference.md", "server-run-reference.zh.md",
        "report-reference.md", "report-reference.zh.md",
        "schema-matrix.md", "schema-matrix.zh.md",
        "compatibility-reference.md", "compatibility-reference.zh.md",
        "migration-reference.md", "migration-reference.zh.md",
    )
    for rel in required:
        path = root / "docs" / "legacy" / rel
        if not path.is_file():
            errors.append("missing dedicated Legacy documentation: docs/legacy/" + rel)

    legacy_index = root / "docs/legacy/README.md"
    legacy_index_zh = root / "docs/legacy/README.zh.md"
    if legacy_index.is_file():
        index_text = legacy_index.read_text(encoding="utf-8")
        for rel in required:
            if rel.endswith(".zh.md") or rel in ("README.md", "README.zh.md"):
                continue
            if "(" + rel + ")" not in index_text:
                errors.append("docs/legacy/README.md does not link " + rel)
    if legacy_index_zh.is_file():
        index_text = legacy_index_zh.read_text(encoding="utf-8")
        for rel in required:
            if not rel.endswith(".zh.md") or rel == "README.zh.md":
                continue
            if "(" + rel + ")" not in index_text:
                errors.append("docs/legacy/README.zh.md does not link " + rel)

    for rel in (
            "docs/quick-start.md", "docs/reference/overview.md",
            "docs/reference/test-authoring.md", "docs/reference/execution-modes/run.md",
            "docs/reference/execution-modes/debug.md", "docs/reference/validation-diagnostics.md"):
        path = root / rel
        if path.is_file() and "legacy/README.md" not in path.read_text(encoding="utf-8"):
            errors.append(rel + ": add one Legacy navigation link for existing Packages")
    for rel in (
            "docs/quick-start.zh.md", "docs/reference.zh/overview.md",
            "docs/reference.zh/test-authoring.md", "docs/reference.zh/execution-modes/run.md",
            "docs/reference.zh/execution-modes/debug.md", "docs/reference.zh/validation-diagnostics.md"):
        path = root / rel
        if path.is_file() and "legacy/README.zh.md" not in path.read_text(encoding="utf-8"):
            errors.append(rel + ": add one Legacy navigation link for existing Packages")

    current_surfaces = (
        "docs/quick-start.md", "docs/quick-start.zh.md",
        "docs/concepts/authoring-model.md", "docs/concepts/authoring-model.zh.md",
        "docs/concepts/execution-model.md", "docs/concepts/execution-model.zh.md",
        "docs/concepts/package-model.md", "docs/concepts/package-model.zh.md",
        "docs/guides/run-tests.md", "docs/guides/run-tests.zh.md",
        "docs/guides/load-testing.md", "docs/guides/load-testing.zh.md",
        "docs/reference.md", "docs/reference.zh.md",
    )
    current_surfaces += tuple("docs/reference/" + item for item in manifest_items)
    current_surfaces += tuple("docs/reference.zh/" + item for item in manifest_items)
    zh_ci = root / "docs/reference.zh/ci-packaging-operations.md"
    if zh_ci.is_file():
        zh_ci_text = zh_ci.read_text(encoding="utf-8")
        if "相同 Template" in zh_ci_text:
            errors.append("docs/reference.zh/ci-packaging-operations.md explains cross-environment reuse with Template")
        if "同一個 Flow" not in zh_ci_text:
            errors.append("docs/reference.zh/ci-packaging-operations.md does not explain cross-environment reuse with Flow")
    for rel in ("docs/reference/package-manifest.md", "docs/reference.zh/package-manifest.md"):
        path = root / rel
        if not path.is_file():
            errors.append("missing " + rel)
            continue
        text = path.read_text(encoding="utf-8")
        if "templates/flows/" not in text:
            errors.append(rel + ": state that the current runtime discovers Flows under templates/flows/")
        if "not yet supported" not in text and "尚未支援" not in text:
            errors.append(rel + ": state that a standalone flows/ package is not yet supported")
        if "resources.flowRoot" not in text:
            errors.append(rel + ": state that resources.flowRoot is not defined")

    for rel in current_surfaces:
        path = root / rel
        if path.is_file():
            errors.extend(legacy_authoring_ownership_errors(
                rel, path.read_text(encoding="utf-8")))

    return errors


def chapter_label_errors(text):
    errors = []
    for line in current_blocks(text):
        for label in re.findall(r"(?<!!)\[([^\]]+)\]\([^)]+\)", line):
            expected = RETIRED_CHAPTER_LABELS.get(label.strip().casefold())
            if expected:
                errors.append("retired chapter link label %r; use %r" % (label, expected))
    return errors


NUMBERED_CHAPTER_REFERENCE = re.compile(
    r"\bchapters?\s+\d+(?:\s*[–-]\s*\d+)?\b", re.I
)


def chapter_reference_errors(text):
    """Reject references to manual positions that change when modules move."""
    errors = []
    prose = re.sub(r"`[^`]*`", "", without_code(text))
    for lineno, line in enumerate(prose.splitlines(), 1):
        line = re.sub(r"\]\([^)]*\)", "]", line)
        if NUMBERED_CHAPTER_REFERENCE.search(line):
            errors.append("line %d: replace the numbered chapter reference with a descriptive link" % lineno)
    return errors


POSITIONAL_REFERENCE = re.compile(
    r"\b(?:see|refer to|use|compare\s+(?:with|to)|consult|read|review)\b"
    r"[^.!?;]{0,100}\b(?:above|below|following section|preceding section|"
    r"chapters?\s+\d+(?:\s*[–-]\s*\d+)?)\b|"
    r"\bchapters?\s+\d+(?:\s*[–-]\s*\d+)?\b|"
    r"\b(?:described|explained|listed|defined|discussed|covered|shown)"
    r"\s+(?:the\s+)?(?:above|below)\b|"
    r"\b(?:commands?|options?|sections?|tables?|examples?|procedures?|steps?|items?)"
    r"\s+(?:above|below|following|preceding)\b|"
    r"\b(?:above|below|following|preceding)\s+(?:section|table|example|procedure|step)\b",
    re.I,
)
NUMBERED_HEADING = re.compile(
    r"^#{1,6}\s+(?:\d+\.(?=\s)|\d+(?:\.\d+)+(?:\s|$)|(?:0[1-9]|1[0-4])\s+)"
)


def _markdown_headings(text):
    headings = []
    fence_char = None
    fence_length = 0
    for lineno, line in enumerate(text.splitlines(), 1):
        fence = re.match(r"^\s*(`{3,}|~{3,})", line)
        if fence:
            marker = fence.group(1)
            if fence_char is None:
                fence_char, fence_length = marker[0], len(marker)
            elif marker[0] == fence_char and len(marker) >= fence_length:
                fence_char, fence_length = None, 0
            continue
        if fence_char is not None:
            continue
        heading = re.match(r"^(#{1,6})\s+(.+?)\s*#*\s*$", line)
        if heading:
            headings.append((lineno, len(heading.group(1)), heading.group(2)))
    return headings


def standalone_page_errors(text):
    """Check that a Markdown page has one H1 and a continuous heading hierarchy."""
    headings = _markdown_headings(text)
    errors = ["line %d: remove a numeric sequence prefix from the heading" % lineno
              for lineno, _, title in headings
              if NUMBERED_HEADING.match("#" * _ + " " + title)]

    h1s = [lineno for lineno, level, _ in headings if level == 1]
    if len(h1s) != 1:
        errors.append("expected exactly one H1, found %d" % len(h1s))
    previous = 0
    for lineno, level, _ in headings:
        if level > previous + 1:
            errors.append("line %d: heading level skips from H%d to H%d" %
                          (lineno, previous, level))
        previous = level
    return errors


def editorial_source_files(root):
    """Return editable current English Markdown, excluding generated manuals and history."""
    candidates = {root / "README.md"}
    for directory in (root / "docs", root / "examples"):
        if directory.is_dir():
            candidates.update(directory.rglob("*.md"))
    generated = {root / "docs" / name for name in
                 ("reference.md", "reference.zh.md")}
    return sorted(path for path in candidates
                  if path.is_file()
                  and path not in generated
                  and "history" not in path.relative_to(root).parts
                  and not path.name.lower().endswith(".zh.md")
                  and "reference.zh" not in path.relative_to(root).parts)


def standalone_markdown(path, root):
    """Whether an editable Markdown file is presented as a standalone page."""
    rel = path.relative_to(root).as_posix()
    if rel.startswith("docs/reference/"):
        return path.name == "README.md"
    return (rel == "README.md" or rel.startswith(("docs/", "examples/")))


def editorial_errors(text, standalone=False, allow_reference_structure=False):
    """Check low-noise Google-style rules on authored prose, not code samples."""
    errors = []
    prose = []
    headings = []
    fence_char = None
    fence_length = 0
    for lineno, line in enumerate(text.splitlines(), 1):
        fence = re.match(r"^\s*(`{3,}|~{3,})", line)
        if fence:
            marker = fence.group(1)
            if fence_char is None:
                fence_char, fence_length = marker[0], len(marker)
            elif marker[0] == fence_char and len(marker) >= fence_length:
                fence_char, fence_length = None, 0
            continue
        if fence_char is not None:
            continue
        heading = re.match(r"^(#{1,6})\s+(.+?)\s*#*\s*$", line)
        if heading:
            headings.append((lineno, len(heading.group(1)), heading.group(2)))
            if NUMBERED_HEADING.match(line) and not allow_reference_structure:
                errors.append("line %d: remove a numeric sequence prefix from the heading" % lineno)
        # Inline code and Markdown link destinations contain identifiers or
        # examples, so they are not treated as editorial prose.
        plain = re.sub(r"`[^`]*`", "", line)
        plain = re.sub(r"\]\([^)]*\)", "]", plain)
        prose.append((lineno, plain))

    if standalone:
        h1s = [lineno for lineno, level, _ in headings if level == 1]
        if len(h1s) != 1:
            errors.append("expected exactly one H1, found %d" % len(h1s))

    for lineno, line in prose:
        if re.search(r"\be\.g\.", line, re.I):
            errors.append("line %d: use 'for example' or 'such as' instead of 'e.g.'" % lineno)
        if re.search(r"\band/or\b", line, re.I):
            errors.append("line %d: replace 'and/or' with an unambiguous alternative" % lineno)
        if POSITIONAL_REFERENCE.search(line):
            errors.append("line %d: replace a positional cross-reference with descriptive link text" % lineno)
    return errors


def current_files(root):
    files = {root / "README.md"}
    for directory, suffixes in (("docs", (".md",)),
                               ("examples", (".md", ".yaml", ".yml", ".json", ".xml"))):
        for path in (root / directory).rglob("*"):
            if path.is_file() and path.suffix.lower() in suffixes and "history" not in path.relative_to(root).parts:
                files.add(path)
    return sorted(files)


def validate(root=ROOT):
    errors = []
    version = re.search(r"<version>([^<]+)</version>",
                        (root / "pom.xml").read_text(encoding="utf-8")).group(1).strip()
    active = active_schemas((root / "schemas/catalog.yaml").read_text(encoding="utf-8"))
    proposed_catalog = root / "schemas/proposed/catalog.yaml"
    if not proposed_catalog.is_file():
        errors.append("missing schemas/proposed/catalog.yaml")
        proposed = {}
    else:
        try:
            proposed = proposed_schemas(proposed_catalog.read_text(encoding="utf-8"))
            for name, versions in proposed.items():
                if name in active:
                    overlap = versions & active[name]
                    if overlap:
                        errors.append("schema is both proposed and active: %s/%s" %
                                      (name, ", ".join("v" + value for value in sorted(overlap))))
                for schema_version in versions:
                    filename = name + "-v" + schema_version + ".schema.json"
                    if not (proposed_catalog.parent / filename).is_file():
                        errors.append("proposed schema catalog references missing file: schemas/proposed/%s" % filename)
        except ValueError as exc:
            errors.append(str(exc))
            proposed = {}
    known_schemas = {name: set(versions) for name, versions in active.items()}
    for name, versions in proposed.items():
        known_schemas.setdefault(name, set()).update(versions)
    manifest = root / "docs/reference-manifest.txt"
    items = [s.strip() for s in manifest.read_text(encoding="utf-8").splitlines()
             if s.strip() and not s.lstrip().startswith("#")]
    errors += manifest_errors(items)
    errors += documentation_ownership_errors(root, items)
    assembled = {}
    source_shapes = {}
    for lang in ("reference", "reference.zh"):
        module_root = root / "docs" / lang
        source_files = sorted(module_root.rglob("*.md"))
        source_shapes[lang] = {}
        for path in source_files:
            rel = path.relative_to(module_root).as_posix()
            text = path.read_text(encoding="utf-8")
            errors += [lang + "/" + rel + ": " + e for e in standalone_page_errors(text)]
            source_shapes[lang][rel] = [level for _, level, _ in _markdown_headings(text)]
        chunks = []
        for item in items:
            path = module_root / item
            if not path.is_file() or not path.resolve().is_relative_to(module_root.resolve()):
                errors.append("missing or escaping module: %s/%s" % (lang, item))
                continue
            chunks.append(path.read_text(encoding="utf-8"))
        text = "\n".join(chunks)
        generated = root / "docs" / (lang + ".md")
        assembled[lang] = generated.read_text(encoding="utf-8") if generated.is_file() else text
        overview = (module_root / "overview.md").read_text(encoding="utf-8")
        errors += [lang + "/overview.md: " + e for e in overview_resource_errors(overview)]
        errors += [lang + "/overview.md: " + e for e in product_model_order_errors(overview)]
        errors += [lang + ".md: " + e for e in product_model_order_errors(assembled[lang])]
        authoring = (module_root / "test-authoring.md").read_text(encoding="utf-8")
        errors += [lang + "/test-authoring.md: " + e for e in flow_authoring_errors(authoring)]
        errors += [lang + ".md: " + e for e in flow_authoring_errors(assembled[lang])]
        errors += [lang + ": " + e for e in structure_errors(assembled[lang])]
        matrix = (module_root / "appendices/schema-matrix.md").read_text(encoding="utf-8")
        found = {(m.group(1).lower(), m.group(2)) for m in SCHEMA_TOKEN.finditer(matrix)}
        legacy_authoring_schemas = {"att-sidecar", "att-testcases", "att-template"}
        expected = {(name, version) for name, versions in active.items()
                    for version in versions if name not in legacy_authoring_schemas}
        if found != expected:
            errors.append("%s active schema matrix differs from catalog: missing=%s extra=%s" %
                          (lang, sorted(expected - found), sorted(found - expected)))
        legacy_docs = root / "docs" / "legacy"
        legacy_corpus = "\n".join(path.read_text(encoding="utf-8")
                                  for path in legacy_docs.glob("*.md"))
        legacy_found = {(m.group(1).lower(), m.group(2))
                        for m in SCHEMA_TOKEN.finditer(legacy_corpus)}
        legacy_expected = {(name, version) for name, versions in active.items()
                           for version in versions if name in legacy_authoring_schemas}
        if not legacy_expected.issubset(legacy_found):
            errors.append("Legacy authoring schema documentation is incomplete: missing=%s" %
                          sorted(legacy_expected - legacy_found))
    if chapter_shape(assembled["reference"]) != chapter_shape(assembled["reference.zh"]):
        errors.append("EN/ZH top-level chapter/appendix structure differs")
    if set(source_shapes["reference"]) != set(source_shapes["reference.zh"]):
        errors.append("EN/ZH Reference source file sets differ")
    for rel in sorted(set(source_shapes["reference"]) & set(source_shapes["reference.zh"])):
        if source_shapes["reference"][rel] != source_shapes["reference.zh"][rel]:
            errors.append("EN/ZH Reference heading structure differs: " + rel)
    for path in current_files(root):
        rel = path.relative_to(root).as_posix()
        if re.search(r"/appendices/(?:migrations|compatibility)\.md$", rel):
            continue
        text = path.read_text(encoding="utf-8")
        if path.suffix == ".md":
            errors += [rel + ": " + e for e in chapter_reference_errors(text)]
        if path.suffix == ".md" and (
                rel in ("README.md", "docs/README.md", "docs/quick-start.md", "docs/quick-start.zh.md")
                or rel.startswith("examples/")):
            errors += [rel + ": " + e for e in chapter_label_errors(text)]
        try:
            errors += [rel + ": " + e for e in stale_claims(text, known_schemas, version)]
        except ValueError as exc:
            errors.append(rel + ": " + str(exc))
        if rel.startswith(("docs/reference/", "docs/reference.zh/")) and path.name != "README.md":
            if re.search(r"(?:\bissue\s+#\d+\b|(?<![&\w])#\d+\b)", text, re.I):
                errors.append(rel + ": maintainer/future issue reference in normative narrative")
        if rel.startswith(("docs/reference.zh/", "docs/quick-start.zh.md")):
            prose = re.sub(r"\x60[^\x60]*\x60", "", without_code(text))
            if re.search(r"模板|動作|动作|工作簿|側車|侧车|上下文|資源|资源|快照", prose):
                errors.append(rel + ": use canonical English ATT terms in ZH prose")
        if re.search(r"\$\{output\.replyReceived\}", text):
            errors.append(rel + ": use output.result.replyReceived")
    for path in editorial_source_files(root):
        rel = path.relative_to(root).as_posix()
        try:
            errors += [rel + ": " + error for error in
                       editorial_errors(
                           path.read_text(encoding="utf-8"),
                           standalone_markdown(path, root),
                           allow_reference_structure=rel.startswith("docs/reference/")
                           and path.name != "README.md")]
        except ValueError as exc:
            errors.append(rel + ": " + str(exc))
    return errors


def main():
    try:
        errors = validate()
    except (OSError, ValueError, AttributeError) as exc:
        errors = [str(exc)]
    if errors:
        print("Documentation contracts failed:", file=sys.stderr)
        for error in errors:
            print("  - " + error, file=sys.stderr)
        return 2
    print("Documentation chapter, version, schema and terminology contracts verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
