#!/usr/bin/env python3
"""Validate current public documentation against project/catalog metadata."""
from pathlib import Path
from html import unescape
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
            if name in active and active[name] != version:
                raise ValueError("multiple active versions for " + name)
            active[name] = version
    if not active:
        raise ValueError("catalog contains no active schema files")
    return active


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
        heading = re.match(r"^##\s+Appendix\s+([A-D])\b", line)
        if heading:
            appendix = heading.group(1)
        elif re.match(r"^##\s+[0-9]+\b", line):
            appendix = None
        if not historical and appendix not in ("B", "C"):
            yield line
    if historical:
        raise ValueError("unclosed historical block")


def current_html(text):
    text = re.sub(r"<h2\b[^>]*>(.*?)</h2>", lambda m: "\n## " +
                  unescape(re.sub(r"<[^>]*>", "", m.group(1))) + "\n",
                  text, flags=re.S)
    text = re.sub(r"<!--(?!/?\s*att-docs:historical)[\s\S]*?-->", "", text)
    text = re.sub(r"<[^>]*>", " ", text)
    return unescape(text)


def stale_claims(text, active, version):
    errors = []
    for lineno, line in enumerate(current_blocks(text), 1):
        for match in SCHEMA_TOKEN.finditer(line):
            name, found = match.groups()
            if name.lower() in active and found != active[name.lower()]:
                errors.append("stale schema %s/v%s; active is v%s (line %s)" %
                              (name, found, active[name.lower()], lineno))
        aliases = {
            "Load": "att-load", "Debug": "att-debug", "DBHelper": "att-dbhelper",
            "MQHelper": "att-mqhelper", "HTTPHelper": "att-httphelper",
            "Tool Group": "att-tool-group", "Template": "att-template",
            "Flow": "att-flow", "config": "att-config",
        }
        for label, name in aliases.items():
            for found in re.findall(r"\b" + re.escape(label) +
                                    r"\s+v([0-9]+(?:\.[0-9]+)*)\b", line, re.I):
                if name in active and found != active[name]:
                    errors.append("stale %s v%s; active is v%s" % (label, found, active[name]))
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
    shape = []
    for line in without_code(text).splitlines():
        match = re.match(r"^##\s+([0-9]+|Appendix\s+[A-D])\b", line)
        if match:
            shape.append(match.group(1))
    return shape


def structure_errors(text):
    expected = ["%02d" % n for n in range(1, 14)] + ["Appendix " + c for c in "ABCD"]
    errors = []
    shape = chapter_shape(text)
    if shape != expected:
        errors.append("chapter/appendix order must be %s; found %s" % (expected, shape))
    headings = re.findall(r"^##\s+(.+)$", without_code(text), re.M)
    folded = [s.casefold() for s in headings]
    if len(folded) != len(set(folded)):
        errors.append("duplicate top-level chapter title")
    if len(headings) != len(expected):
        errors.append("unexpected or missing top-level chapter heading")
    return errors


def current_files(root):
    files = {root / "README.md"}
    for directory, suffixes in (("docs", (".md", ".html")),
                               ("examples", (".md", ".html", ".yaml", ".yml", ".json", ".xml"))):
        for path in (root / directory).rglob("*"):
            if path.is_file() and path.suffix.lower() in suffixes and "history" not in path.relative_to(root).parts:
                files.add(path)
    return sorted(files)


def validate(root=ROOT):
    errors = []
    version = re.search(r"<version>([^<]+)</version>",
                        (root / "pom.xml").read_text(encoding="utf-8")).group(1).strip()
    active = active_schemas((root / "schemas/catalog.yaml").read_text(encoding="utf-8"))
    manifest = root / "docs/reference-manifest.txt"
    items = [s.strip() for s in manifest.read_text(encoding="utf-8").splitlines()
             if s.strip() and not s.lstrip().startswith("#")]
    errors += manifest_errors(items)
    assembled = {}
    for lang in ("reference", "reference.zh"):
        module_root = root / "docs" / lang
        chunks = []
        for item in items:
            path = module_root / item
            if not path.is_file() or not path.resolve().is_relative_to(module_root.resolve()):
                errors.append("missing or escaping module: %s/%s" % (lang, item))
                continue
            chunks.append(path.read_text(encoding="utf-8"))
        text = "\n".join(chunks)
        assembled[lang] = text
        errors += [lang + ": " + e for e in structure_errors(text)]
        matrix = (module_root / "appendices/schema_matrix.md").read_text(encoding="utf-8")
        found = {(m.group(1).lower(), m.group(2)) for m in SCHEMA_TOKEN.finditer(matrix)}
        expected = set(active.items())
        if found != expected:
            errors.append("%s active schema matrix differs from catalog: missing=%s extra=%s" %
                          (lang, sorted(expected - found), sorted(found - expected)))
    if chapter_shape(assembled["reference"]) != chapter_shape(assembled["reference.zh"]):
        errors.append("EN/ZH top-level chapter/appendix structure differs")
    for path in current_files(root):
        rel = path.relative_to(root).as_posix()
        if re.search(r"/appendices/(?:migrations|compatibility)\.md$", rel):
            continue
        text = path.read_text(encoding="utf-8")
        if path.suffix == ".html":
            text = current_html(text)
        try:
            errors += [rel + ": " + e for e in stale_claims(text, active, version)]
        except ValueError as exc:
            errors.append(rel + ": " + str(exc))
        if rel.startswith(("docs/reference/", "docs/reference.zh/")) and path.name != "README.md":
            if re.search(r"\b(?:issue\s*)?#(?:38|39|41|42)\b", text, re.I):
                errors.append(rel + ": maintainer/future issue reference in normative narrative")
        if rel.startswith(("docs/reference.zh/", "docs/quick-start.zh.md")):
            prose = re.sub(r"\x60[^\x60]*\x60", "", without_code(text))
            if re.search(r"模板|動作|动作|工作簿|側車|侧车|上下文|資源|资源|快照", prose):
                errors.append(rel + ": use canonical English ATT terms in ZH prose")
        if re.search(r"\$\{output\.replyReceived\}", text):
            errors.append(rel + ": use output.result.replyReceived")
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
