#!/usr/bin/env python3
from pathlib import Path
from html.parser import HTMLParser
from urllib.parse import unquote
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"
SCHEMAS = ROOT / "schemas"
MANIFEST = DOCS / "reference-manifest.txt"

errors = []


def fail(message):
    errors.append(message)


def read(path):
    return path.read_text(encoding="utf-8")


def product_version():
    match = re.search(r"<version>([^<]+)</version>", read(ROOT / "pom.xml"))
    if not match:
        raise SystemExit("Unable to read ATT version from pom.xml")
    return match.group(1).strip()


def run_gate(label, args):
    result = subprocess.run(args, cwd=str(ROOT), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            universal_newlines=True)
    if result.returncode != 0:
        fail("%s failed (exit %s):\n%s" % (label, result.returncode, result.stdout.rstrip()))


def manifest_items():
    if not MANIFEST.is_file():
        fail("missing docs/reference-manifest.txt")
        return []
    return [line.strip() for line in read(MANIFEST).splitlines()
            if line.strip() and not line.lstrip().startswith("#")]


def numbered_heading_shape(path):
    shape = []
    for line in read(path).splitlines():
        match = re.match(r"^(#{1,6})\s+([0-9]+(?:\.[0-9]+)*)\b", line)
        if match:
            shape.append((len(match.group(1)), match.group(2)))
    return shape


def markdown_slug(text):
    text = re.sub(r"`([^`]*)`", r"\1", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)
    text = text.strip().lower()
    text = "".join(ch for ch in text if ch.isalnum() or ch in " _-")
    text = re.sub(r"\s+", "-", text)
    text = re.sub(r"-+", "-", text)
    return text.strip("-")


def markdown_anchors(path):
    anchors = set()
    counts = {}
    for line in read(path).splitlines():
        match = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not match:
            continue
        base = markdown_slug(match.group(1))
        if not base:
            continue
        count = counts.get(base, 0)
        counts[base] = count + 1
        anchors.add(base if count == 0 else "%s-%d" % (base, count))
    return anchors


def is_external(target):
    return bool(re.match(r"^(?:https?|mailto|tel|data|javascript):", target, re.I))


def normalize_link_target(raw):
    raw = raw.strip()
    if raw.startswith("<") and ">" in raw:
        return raw[1:raw.index(">")]
    # Markdown optional titles are not part of the path.
    match = re.match(r"([^\s]+)(?:\s+['\"(].*)?$", raw)
    return match.group(1) if match else raw


def check_markdown_links(path):
    text = read(path)
    for match in re.finditer(r"(?<!!)\[[^\]]+\]\(([^)]+)\)", text):
        target = normalize_link_target(match.group(1))
        if not target or is_external(target):
            continue
        path_part, sep, fragment = target.partition("#")
        if not path_part:
            resolved = path
        else:
            resolved = (path.parent / unquote(path_part)).resolve()
            try:
                resolved.relative_to(ROOT.resolve())
            except ValueError:
                fail("%s link escapes repository root: %s" % (path.relative_to(ROOT), target))
                continue
            if not resolved.exists():
                fail("%s broken local link: %s -> %s" %
                     (path.relative_to(ROOT), target, resolved.relative_to(ROOT)))
                continue
        if fragment and resolved.is_file() and resolved.suffix.lower() == ".md":
            anchor = unquote(fragment).lower()
            if anchor not in markdown_anchors(resolved):
                fail("%s broken Markdown anchor: %s" % (path.relative_to(ROOT), target))


class IdHrefParser(HTMLParser):
    def __init__(self):
        HTMLParser.__init__(self)
        self.ids = set()
        self.hrefs = []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if "id" in values:
            self.ids.add(values["id"])
        if tag == "a" and "href" in values:
            self.hrefs.append(values["href"])


def check_html_links(path):
    parser = IdHrefParser()
    parser.feed(read(path))
    for href in parser.hrefs:
        if not href or is_external(href):
            continue
        path_part, sep, fragment = href.partition("#")
        if not path_part:
            if fragment and fragment not in parser.ids:
                fail("%s broken generated HTML anchor: #%s" % (path.relative_to(ROOT), fragment))
            continue
        resolved = (path.parent / unquote(path_part)).resolve()
        try:
            resolved.relative_to(ROOT.resolve())
        except ValueError:
            fail("%s HTML link escapes repository root: %s" % (path.relative_to(ROOT), href))
            continue
        if not resolved.exists():
            fail("%s broken generated HTML local link: %s -> %s" %
                 (path.relative_to(ROOT), href, resolved.relative_to(ROOT)))
            continue
        if fragment and resolved == path and fragment not in parser.ids:
            fail("%s broken generated HTML anchor: %s" % (path.relative_to(ROOT), href))


def check_version_consistency(version):
    exact_prefixes = {
        ROOT / "README.md": "# ATT %s - Automated Testing Tool" % version,
        DOCS / "quick-start.md": "# ATT V%s " % version,
        DOCS / "generated/reference.md": "# ATT V%s Reference Manual" % version,
        DOCS / "generated/reference.zh.md": "# ATT V%s 使用手冊與參考" % version,
    }
    for path, expected in exact_prefixes.items():
        if not path.is_file():
            fail("missing versioned current document: %s" % path.relative_to(ROOT))
            continue
        first = read(path).splitlines()[0] if read(path).splitlines() else ""
        if not first.startswith(expected):
            fail("%s version/title drift: expected prefix %r, found %r" %
                 (path.relative_to(ROOT), expected, first))

    for path in (DOCS / "generated/reference.md", DOCS / "generated/reference.zh.md"):
        if path.is_file() and ("Version: " + version) not in read(path)[:500]:
            fail("%s does not declare authoritative Version: %s" % (path.relative_to(ROOT), version))

    html_titles = {
        DOCS / "generated/reference.html": "<title>ATT V%s Reference Manual</title>" % version,
        DOCS / "generated/reference.zh.html": "<title>ATT V%s 使用手冊與參考</title>" % version,
    }
    for path, expected in html_titles.items():
        if not path.is_file():
            fail("missing generated HTML: %s" % path.relative_to(ROOT))
        elif expected not in read(path)[:2000]:
            fail("%s generated HTML title/version drift: expected %s" % (path.relative_to(ROOT), expected))


def check_language_parity(items):
    expected = set(items)
    roots = (DOCS / "reference", DOCS / "reference.zh")
    for root in roots:
        actual = set(str(path.relative_to(root)).replace("\\", "/") for path in root.rglob("*.md"))
        for missing in sorted(expected - actual):
            fail("%s missing manifest module: %s" % (root.relative_to(ROOT), missing))
        for extra in sorted(actual - expected):
            fail("%s contains module not listed in reference-manifest.txt: %s" %
                 (root.relative_to(ROOT), extra))

    for rel in items:
        en = DOCS / "reference" / rel
        zh = DOCS / "reference.zh" / rel
        if not en.is_file() or not zh.is_file():
            continue
        en_shape = numbered_heading_shape(en)
        zh_shape = numbered_heading_shape(zh)
        if en_shape != zh_shape:
            fail("EN/ZH numbered heading mismatch for %s: EN=%s ZH=%s" % (rel, en_shape, zh_shape))

    for name in ("reference.md", "reference.zh.md", "reference.html", "reference.zh.html"):
        if not (DOCS / "generated" / name).is_file():
            fail("missing generated EN/ZH artifact: docs/generated/%s" % name)


def check_schema_references():
    current = [ROOT / "README.md", DOCS / "quick-start.md"]
    current += list((DOCS / "reference").rglob("*.md"))
    current += list((DOCS / "reference.zh").rglob("*.md"))
    current += list((DOCS / "system-design").rglob("*.md"))
    tokens = set()
    for path in current:
        if path.is_file():
            tokens.update(re.findall(r"\batt-[a-z0-9-]+/v[0-9]+(?:\.[0-9]+)*\b", read(path), re.I))

    schema_corpus = "\n".join(read(path) for path in SCHEMAS.iterdir()
                                if path.is_file() and path.suffix.lower() in (".json", ".xsd", ".yaml", ".yml"))
    for token in sorted(tokens):
        if token not in schema_corpus:
            fail("current documentation references schemaVersion with no matching schema contract: %s" % token)

    catalog = SCHEMAS / "catalog.yaml"
    if not catalog.is_file():
        fail("missing schemas/catalog.yaml")
    else:
        for filename in re.findall(r":\s*([A-Za-z0-9._-]+\.(?:json|xsd))\s*$", read(catalog), re.M):
            if not (SCHEMAS / filename).is_file():
                fail("schemas/catalog.yaml references missing schema file: %s" % filename)


def check_cli_documentation():
    source = read(ROOT / "src/main/java/att/core/ExecutionOptions.java")
    supported_options = set(re.findall(r'"(--[a-z][a-z0-9-]*)"', source))
    public_commands = set(("run", "validate", "snapshot", "docs", "report", "build", "clean", "version", "debug", "load"))

    for rel in ("reference/10_cli.md", "reference.zh/10_cli.md"):
        path = DOCS / rel
        if not path.is_file():
            fail("missing CLI Reference: docs/%s" % rel)
            continue
        text = read(path)
        for command in sorted(public_commands):
            if not re.search(r"(?:\./att\.sh|att\.bat)\s+%s\b" % re.escape(command), text):
                fail("%s does not document public command: %s" % (path.relative_to(ROOT), command))
        documented_options = set(re.findall(r"--[a-z][a-z0-9-]*", text))
        for option in sorted(supported_options - documented_options):
            fail("%s does not document supported CLI option: %s" % (path.relative_to(ROOT), option))
        for option in sorted(documented_options - supported_options):
            fail("%s documents unsupported CLI option: %s" % (path.relative_to(ROOT), option))
        for code in ("0", "1", "2", "3"):
            if not re.search(r"(?:exit(?:\s+code)?|退出码|退出碼)[^\n]{0,80}\b%s\b|\b%s\b[^\n]{0,80}(?:PASS|FAIL|INVALID|ERROR|runtime|validation)" % (code, code), text, re.I):
                fail("%s does not clearly cover exit code %s" % (path.relative_to(ROOT), code))

    quick = read(DOCS / "quick-start.md") if (DOCS / "quick-start.md").is_file() else ""
    for command in re.findall(r"\./att\.sh\s+([a-z][a-z0-9-]*)", quick):
        if command not in public_commands:
            fail("docs/quick-start.md uses unsupported ATT command: %s" % command)
    for option in re.findall(r"--[a-z][a-z0-9-]*", quick):
        if option not in supported_options:
            fail("docs/quick-start.md uses unsupported ATT option: %s" % option)


def check_secret_placeholders():
    files = []
    for root in (ROOT / "config", ROOT / "examples", ROOT / "templates"):
        if root.is_dir():
            files += [path for path in root.rglob("*") if path.is_file() and path.suffix.lower() in (".yaml", ".yml", ".md")]
    files += [ROOT / "README.md", DOCS / "quick-start.md"]
    files += list((DOCS / "reference").rglob("*.md"))
    files += list((DOCS / "reference.zh").rglob("*.md"))

    pattern = re.compile(r"(?im)^\s*(password|token|secret|credential(?:s)?)\s*:\s*([^#\n]*?)\s*$")
    for path in files:
        if not path.is_file():
            continue
        for match in pattern.finditer(read(path)):
            value = match.group(2).strip().strip("'\"")
            if not value:
                continue
            safe = ("${ENV:" in value or value.startswith("<") or "placeholder" in value.lower() or
                    "example" in value.lower() or "changeme" in value.lower() or set(value) <= set("*xX"))
            if not safe:
                fail("%s contains a literal value in secret-like field %s; use ENV/placeholder syntax" %
                     (path.relative_to(ROOT), match.group(1)))


def check_critical_examples():
    assets = (
        "testcase/payment2.xlsx",
        "templates/PAYMENT_INVOKE/debug.yaml",
        "examples/load/closed-smoke.yaml",
        "examples/load/arrival-smoke.yaml",
        "config/config.yaml",
        "config/environments/sit.yaml",
        "config/environments/uat.yaml",
        "config/dbhelpers/sit/orders.yaml",
        "config/dbhelpers/uat/orders.yaml",
        "config/mqhelpers/sit/payment.yaml",
        "config/mqhelpers/uat/payment.yaml",
        "config/tools/sample.yaml",
        "config/tools/orders-db.yaml",
    )
    for rel in assets:
        if not (ROOT / rel).is_file():
            fail("missing checked-in documentation example asset: %s" % rel)

    command_group = ROOT / "config/tools/sample.yaml"
    call_group = ROOT / "config/tools/orders-db.yaml"
    if command_group.is_file() and "command:" not in read(command_group):
        fail("config/tools/sample.yaml must remain a command-backed Tool documentation example")
    if call_group.is_file() and "call:" not in read(call_group):
        fail("config/tools/orders-db.yaml must remain a call-backed Tool documentation example")

    test = ROOT / "src/test/java/att/docs/DocumentationExamplesTest.java"
    if not test.is_file():
        fail("missing Java documentation example regression test: src/test/java/att/docs/DocumentationExamplesTest.java")
    else:
        test_text = read(test)
        for rel in ("testcase/payment2.xlsx", "templates/PAYMENT_INVOKE/debug.yaml",
                    "examples/load/closed-smoke.yaml", "examples/load/arrival-smoke.yaml",
                    "config/environments/sit.yaml", "config/environments/uat.yaml",
                    "config/mqhelpers/sit/payment.yaml", "config/tools/orders-db.yaml"):
            if rel not in test_text:
                fail("DocumentationExamplesTest does not regression-protect documented asset: %s" % rel)


def check_ownership_links():
    readme = read(ROOT / "README.md")
    for canonical in ("docs/quick-start.md", "docs/generated/reference.html", "docs/system-design/"):
        if canonical not in readme:
            fail("README missing canonical documentation entry point: %s" % canonical)
    if re.search(r"\[[^\]]*(?:Reference|System Design|Quick Start)[^\]]*\]\(docs/history/", readme, re.I):
        fail("README points a primary current-documentation label to docs/history/")


def main():
    version = product_version()

    # Existing #41/#42 gates remain part of the #43 release gate.
    run_gate("generated Reference freshness", [sys.executable, "tools/build_reference_manual.py", "--check"])
    run_gate("Reference semantic coverage", [sys.executable, "tools/validate_reference_content.py"])

    items = manifest_items()
    check_version_consistency(version)
    check_language_parity(items)
    check_schema_references()
    check_cli_documentation()
    check_secret_placeholders()
    check_critical_examples()
    check_ownership_links()

    markdown_files = [ROOT / "README.md", DOCS / "quick-start.md", DOCS / "documentation-architecture.md",
                      DOCS / "reference-migration-map.md", DOCS / "02_System_Design_V3.md",
                      DOCS / "08_Quick_Start_V3.md", DOCS / "generated/reference.md",
                      DOCS / "generated/reference.zh.md"]
    markdown_files += list((DOCS / "system-design").rglob("*.md"))
    for path in markdown_files:
        if path.is_file():
            check_markdown_links(path)

    for path in (DOCS / "generated/reference.html", DOCS / "generated/reference.zh.html"):
        if path.is_file():
            check_html_links(path)

    if errors:
        print("Documentation release gate failed:", file=sys.stderr)
        for error in errors:
            print("  - " + error.replace("\n", "\n    "), file=sys.stderr)
        return 2

    print("Documentation release gate verified for ATT V%s" % version)
    return 0


if __name__ == "__main__":
    sys.exit(main())
