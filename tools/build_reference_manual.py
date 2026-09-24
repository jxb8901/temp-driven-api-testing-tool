#!/usr/bin/env python3
from pathlib import Path
import argparse
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"
MANIFEST = DOCS / "reference-manifest.txt"

LANGS = {
    "en": {
        "root": DOCS / "reference",
        "md": DOCS / "reference.md",
        "html": DOCS / "reference.html",
        "title": "ATT V{version} Reference Manual",
        "status": "Normative end-user documentation; generated from modular sources",
    },
    "zh": {
        "root": DOCS / "reference.zh",
        "md": DOCS / "reference.zh.md",
        "html": DOCS / "reference.zh.html",
        "title": "ATT V{version} 使用手冊與參考",
        "status": "規範性使用者文件；由模組化來源自動生成",
    },
}


def version():
    text = (ROOT / "pom.xml").read_text(encoding="utf-8")
    match = re.search(r"<version>([^<]+)</version>", text)
    if not match:
        raise SystemExit("Unable to read project version from pom.xml")
    return match.group(1).strip()


def manifest():
    if not MANIFEST.is_file():
        raise SystemExit("Missing docs/reference-manifest.txt")
    items = [line.strip() for line in MANIFEST.read_text(encoding="utf-8").splitlines()
             if line.strip() and not line.lstrip().startswith("#")]
    if not items or len(items) != len(set(items)):
        raise SystemExit("Reference manifest must be non-empty and contain unique paths")
    return items


def rebase_for_docs_root(text):
    # Reference modules are authored below docs/reference*/, where ../../ reaches
    # the repository root. The generated combined manuals now live directly
    # below docs/, so repository-root links need one fewer parent traversal.
    return text.replace("](../../", "](../")


def markdown(lang, items, ver):
    cfg = LANGS[lang]
    chunks = []
    for rel in items:
        path = cfg["root"] / rel
        if not path.is_file():
            raise SystemExit("Missing %s Reference module: %s" %
                             (lang.upper(), path.relative_to(ROOT)))
        chunks.append(path.read_text(encoding="utf-8").rstrip())

    title = cfg["title"].format(version=ver)
    header = [
        "# " + title,
        "",
        "Author: Jeffrey + ChatGPT",
        "Version: " + ver,
        "Status: " + cfg["status"],
        "",
        "<!-- GENERATED FILE. Edit docs/reference*/ modules, not this combined output. -->",
        "",
    ]
    combined = "\n".join(header) + "\n\n".join(chunks) + "\n"
    return rebase_for_docs_root(combined)


def render_html(md_path, html_path, title):
    pandoc = shutil.which("pandoc")
    if not pandoc:
        raise SystemExit("pandoc is required to generate the Reference Manual HTML")
    cmd = [
        pandoc,
        "--standalone",
        "--toc",
        "--metadata", "title=" + title,
        "--from", "gfm",
        "--to", "html5",
        str(md_path),
        "-o", str(html_path),
    ]
    subprocess.run(cmd, check=True, cwd=str(ROOT))


def compare(expected, path):
    if not path.is_file():
        return False, "missing"
    if path.read_bytes() == expected:
        return True, ""
    return False, "stale"


def check_or_write(path, data, check, errors):
    ok, why = compare(data, path)
    if check:
        if not ok:
            errors.append("%s: %s" % (path.relative_to(ROOT), why))
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description="Build ATT modular Reference Manual outputs")
    parser.add_argument("--check", action="store_true",
                        help="fail when generated outputs differ from modular sources")
    args = parser.parse_args()

    ver = version()
    items = manifest()
    errors = []

    for rel in items:
        for lang in LANGS:
            if not (LANGS[lang]["root"] / rel).is_file():
                errors.append("missing %s module: %s" % (lang.upper(), rel))
    if errors:
        raise SystemExit("\n".join(errors))

    with tempfile.TemporaryDirectory(prefix="att-reference-") as tmpdir:
        tmp = Path(tmpdir)
        for lang, cfg in LANGS.items():
            md_text = markdown(lang, items, ver)
            md_tmp = tmp / ("reference.zh.md" if lang == "zh" else "reference.md")
            html_tmp = tmp / ("reference.zh.html" if lang == "zh" else "reference.html")
            md_tmp.write_text(md_text, encoding="utf-8")
            render_html(md_tmp, html_tmp, cfg["title"].format(version=ver))
            check_or_write(cfg["md"], md_tmp.read_bytes(), args.check, errors)
            check_or_write(cfg["html"], html_tmp.read_bytes(), args.check, errors)

    if args.check and errors:
        print("Reference Manual generated outputs are stale:", file=sys.stderr)
        for error in errors:
            print("  - " + error, file=sys.stderr)
        print("Run: python3 tools/build_reference_manual.py", file=sys.stderr)
        return 2

    print("Reference Manual outputs %s for ATT V%s" %
          ("verified" if args.check else "generated", ver))
    return 0


if __name__ == "__main__":
    sys.exit(main())
