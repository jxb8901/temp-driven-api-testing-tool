#!/usr/bin/env python3
"""Build the deterministic source, example, language and link inventory."""
import argparse
import csv
import io
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"
OUTPUT = DOCS / "system-design" / "document-inventory.csv"
ASSET_TEST = ROOT / "att-cli" / "src" / "test" / "java" / "att" / "docs" / "DocumentationExamplesTest.java"
FIELDS = (
    "source_path", "language_pair", "audience", "semantic_owner",
    "applicable_version", "feature_status", "current_duplicate_stale",
    "disposition", "destination", "test_coverage", "inbound_markdown_links",
)


def release_version():
    match = re.search(r"<version>([^<]+)</version>",
                      (ROOT / "pom.xml").read_text(encoding="utf-8"))
    if not match:
        raise RuntimeError("cannot read project version")
    return match.group(1).strip()


def discovered_files():
    files = {ROOT / "README.md", DOCS / "reference-manifest.txt"}
    for base in (DOCS, ROOT / "examples"):
        if base.is_dir():
            files.update(path for path in base.rglob("*") if path.is_file() and path.name != ".DS_Store")
    # Include every fixture explicitly protected by the executable docs test.
    if ASSET_TEST.is_file():
        source = ASSET_TEST.read_text(encoding="utf-8")
        for name in re.findall(r'"((?:config|examples|packages/default)/[A-Za-z0-9_./-]+)"', source):
            path = ROOT / name
            if path.is_file():
                files.add(path)
    return sorted(path for path in files if path.is_file())


def paired_language(path):
    rel = path.relative_to(ROOT).as_posix()
    if not rel.endswith(".md"):
        return ""
    if ".zh.md" in rel:
        paired = ROOT / rel.replace(".zh.md", ".md")
        return paired.relative_to(ROOT).as_posix() if paired.is_file() else "MISSING"
    if "/reference.zh/" in rel:
        paired = ROOT / rel.replace("/reference.zh/", "/reference/")
    else:
        paired = ROOT / (rel[:-3] + ".zh.md")
    return paired.relative_to(ROOT).as_posix() if paired.is_file() else "—"


def inbound_link_counts(files):
    counts = {path.resolve(): 0 for path in files}
    corpus = [ROOT / "README.md"]
    corpus += [path for base in (DOCS, ROOT / "examples") if base.exists()
               for path in base.rglob("*.md") if path.is_file()]
    for source in corpus:
        text = source.read_text(encoding="utf-8", errors="replace")
        for raw in re.findall(r"(?<!!)\[[^\]]+\]\(([^)]+)\)", text):
            target = raw.strip().split()[0].strip("<>")
            if re.match(r"^(?:https?|mailto|tel|data|javascript):", target, re.I):
                continue
            local = target.split("#", 1)[0]
            resolved = (source.parent / local).resolve() if local else source.resolve()
            if resolved in counts:
                counts[resolved] += 1
    return counts


def row_for(path, version, inbound):
    rel = path.relative_to(ROOT).as_posix()
    marker = ""
    if path.suffix.lower() == ".md":
        marker_match = re.search(r"<!-- att-doc-status: ([^>]+) -->",
                                 path.read_text(encoding="utf-8", errors="replace")[:1500])
        marker = marker_match.group(1).strip() if marker_match else ""
    zh = rel.endswith(".zh.md") or "/reference.zh/" in rel
    pair = paired_language(path)
    if rel.startswith("docs/history/"):
        audience, owner, applicable, status = "Maintainer / archive reader", "Historical record", "Historical version", "Historical"
        quality, disposition, destination = "Historical; retained for compatibility and chronology", "KEEP ARCHIVED", rel
        tests = "History index and local-link checks"
    elif "planned" in marker and rel.startswith("docs/interfaces/server-api/"):
        audience, owner, applicable, status = "Server administrator / integrator", "Proposed Server API validation endpoint", "Unreleased; #287", "Planned, not released"
        quality, disposition, destination = "Planned Server endpoint; excluded from Current route table", "KEEP PLANNED", rel
        tests = "API route, auth, schema and status checks; language-pair and link checks"
    elif "planned" in marker:
        audience, owner, applicable, status = "CLI user / contributor", "Proposed CLI vNext contract", "Unreleased; #287", "Planned, not released"
        quality, disposition, destination = "Planned syntax; excluded from Current examples", "KEEP PLANNED", rel
        tests = "Status and link checks; excluded from current CLI execution suite"
    elif rel in ("docs/server-deployment.md", "docs/server-deployment.zh.md",
                 "docs/att-server-web-ui.md", "docs/att-server-web-ui.zh.md"):
        audience, owner, applicable, status = "Existing bookmark holder", "Focused Operations or Studio destination", version, "Compatibility"
        quality, disposition = "Compatibility pointer; old H2/H3 anchors retained", "KEEP COMPATIBILITY STUB"
        destination = "Focused destination pages under docs/operations/ or docs/interfaces/"
        tests = "Old-path anchor and local-link checks"
    elif rel in ("docs/reference.md", "docs/reference.zh.md"):
        audience, owner, applicable, status = "End user", "reference-manifest.txt and paired Reference sources", version, "Generated Current"
        quality, disposition, destination = "Generated from paired normative modules", "GENERATE", rel
        tests = "build_reference_manual.py --check; semantic coverage"
    elif rel.startswith("docs/reference/") or rel.startswith("docs/reference.zh/"):
        audience, owner, applicable, status = "Test author / operator / integrator", rel, version, "Current normative source"
        quality, disposition, destination = "Normative owner; one source per contract", "KEEP", rel
        tests = "documentation_contracts.py; validate_reference_content.py; generated freshness"
    elif rel.startswith("docs/interfaces/"):
        audience, owner, applicable, status = "CLI user / Studio operator / integrator", rel, version, "Current interface guide"
        quality, disposition, destination = "Current interface guide; contract details link to Reference/API owner", "KEEP", rel
        tests = "Current status, language-pair, and Markdown link checks"
    elif rel.startswith("docs/operations/"):
        audience, owner, applicable, status = "Server administrator / operator", rel, version, "Current operations guide"
        quality, disposition, destination = "Operational owner; API details remain under interfaces/server-api", "KEEP", rel
        tests = "Current status, language-pair, and Markdown link checks"
    elif rel.startswith("docs/concepts/") or rel.startswith("docs/guides/"):
        audience, owner, applicable, status = "End user / test author", rel, version, "Current task guidance"
        quality, disposition, destination = "Task guidance links to normative owners", "KEEP", rel
        tests = "Current status, language-pair, CLI command and Markdown link checks"
    elif rel.startswith("docs/system-design/"):
        audience, owner, applicable, status = "Maintainer / contributor", rel, version, "Current maintainer documentation"
        quality, disposition, destination = "Current; architecture and verification material", "KEEP", rel
        tests = "Documentation contract and Markdown link checks"
    elif rel.startswith("docs/remote-client"):
        audience, owner, applicable, status = "CLI user / Server operator", "Legacy ATT Remote compatibility interface", version, "Legacy-current"
        quality, disposition, destination = "Legacy syntax retained; vNext proposal is separate", "KEEP LEGACY", rel
        tests = "Current CLI contract and Markdown link checks"
    elif rel.startswith("docs/quick-start"):
        audience, owner, applicable, status = "New user", "Current 4.2.8 CLI workflow", version, "Current tutorial"
        quality, disposition, destination = "Runnable Current workflow; Snapshot authoring remains explicit", "KEEP", rel
        tests = "DocumentationExamplesTest; current CLI and Markdown link checks"
    elif rel.startswith("docs/performance/"):
        audience, owner, applicable, status = "Maintainer / performance reviewer", "Recorded benchmark artifact", "Recorded date / commit", "Historical benchmark data"
        quality, disposition, destination = "Evidence snapshot; do not treat as a current product contract", "KEEP EVIDENCE", rel
        tests = "Referenced by benchmark docs where applicable"
    elif rel.startswith("examples/") or rel.startswith("config/") or rel.startswith("packages/default/"):
        audience, owner, applicable, status = "Test author / executable example", "Embedded ATT schemas and Engine semantic validators", version, "Current example"
        quality, disposition, destination = "Fixture; no production endpoint or credential", "KEEP FIXTURE", rel
        tests = ("DocumentationExamplesTest and LoadAcceptanceTest"
                 if rel.startswith("examples/load/") else "DocumentationExamplesTest")
    elif rel == "docs/reference-manifest.txt":
        audience, owner, applicable, status = "Maintainer", "Reference generator", version, "Current build manifest"
        quality, disposition, destination = "Source order for generated EN/ZH manuals", "KEEP", rel
        tests = "build_reference_manual.py --check"
    elif rel == "README.md" or rel == "docs/README.md":
        audience, owner, applicable, status = "All readers", "Documentation navigation", version, "Current entry point"
        quality, disposition, destination = "Task-first navigation", "KEEP", rel
        tests = "Documentation ownership and Markdown link checks"
    else:
        audience, owner, applicable, status = "Maintainer / reader", rel, version, marker or "Current"
        quality, disposition, destination = "Review through documentation release gate", "KEEP", rel
        tests = "Documentation release gate"
    return {
        "source_path": rel,
        "language_pair": pair,
        "audience": audience,
        "semantic_owner": owner,
        "applicable_version": applicable,
        "feature_status": status,
        "current_duplicate_stale": quality,
        "disposition": disposition,
        "destination": destination,
        "test_coverage": tests,
        "inbound_markdown_links": str(inbound.get(path.resolve(), 0)),
    }


def render():
    version = release_version()
    files = discovered_files()
    inbound = inbound_link_counts(files)
    rows = [row_for(path, version, inbound) for path in files]
    buffer = io.StringIO(newline="")
    writer = csv.DictWriter(buffer, fieldnames=FIELDS, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)
    return buffer.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if the committed inventory is stale")
    args = parser.parse_args()
    expected = render()
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_text(encoding="utf-8") != expected:
            print("documentation inventory is stale; run python3 -B tools/build_documentation_inventory.py", file=sys.stderr)
            return 1
        print("Documentation inventory is current (%d rows)" % (expected.count("\n") - 1))
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(expected, encoding="utf-8")
    print("Wrote %s (%d rows)" % (OUTPUT.relative_to(ROOT), expected.count("\n") - 1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
