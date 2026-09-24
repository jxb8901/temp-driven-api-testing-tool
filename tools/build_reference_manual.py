#!/usr/bin/env python3
from pathlib import Path
import argparse
import difflib
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
        "md": DOCS / "generated" / "reference.md",
        "html": DOCS / "generated" / "reference.html",
        "compat_md": DOCS / "09_Reference_Manual_V3.md",
        "compat_html": DOCS / "09_Reference_Manual_V3.html",
        "title": "ATT V{version} Reference Manual",
        "status": "Normative end-user documentation; generated from modular sources",
    },
    "zh": {
        "root": DOCS / "reference.zh",
        "md": DOCS / "generated" / "reference.zh.md",
        "html": DOCS / "generated" / "reference.zh.html",
        "compat_md": DOCS / "09_Reference_Manual_V3.zh.md",
        "compat_html": DOCS / "09_Reference_Manual_V3.zh.html",
        "title": "ATT V{version} 使用手冊與參考",
        "status": "規範性使用者文件；由模組化來源自動生成",
    },
}


def version():
    text = (ROOT / "pom.xml").read_text(encoding="utf-8")
    m = re.search(r"<version>([^<]+)</version>", text)
    if not m:
        raise SystemExit("Unable to read project version from pom.xml")
    return m.group(1).strip()


def manifest():
    if not MANIFEST.is_file():
        raise SystemExit("Missing docs/reference-manifest.txt")
    items = [line.strip() for line in MANIFEST.read_text(encoding="utf-8").splitlines() if line.strip() and not line.lstrip().startswith("#")]
    if not items or len(items) != len(set(items)):
        raise SystemExit("Reference manifest must be non-empty and contain unique paths")
    return items


def markdown(lang, items, ver, compatibility=False):
    cfg = LANGS[lang]
    root = cfg["root"]
    chunks = []
    for rel in items:
        path = root / rel
        if not path.is_file():
            raise SystemExit("Missing %s Reference module: %s" % (lang.upper(), path.relative_to(ROOT)))
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
    if compatibility:
        current = "generated/reference.zh.html" if lang == "zh" else "generated/reference.html"
        header += [
            "> Compatibility path only: use the [canonical current Reference Manual](%s) or the [documentation landing page](README.md)." % current,
            "> This generated file is retained for existing versioned links and is not a separate documentation source.",
            "",
        ]
    return "\n".join(header) + "\n\n".join(chunks) + "\n"


def render_html(md_path, html_path, title):
    pandoc = shutil.which("pandoc")
    if not pandoc:
        raise SystemExit("pandoc is required to generate the Reference Manual HTML")
    cmd = [pandoc, "--standalone", "--toc", "--metadata", "title=" + title, "--from", "gfm", "--to", "html5", str(md_path), "-o", str(html_path)]
    subprocess.run(cmd, check=True, cwd=str(ROOT))


def rebase_compatibility_links(text):
    # Reference modules are authored below docs/reference*/, where ../../ reaches
    # the repository root. Legacy combined outputs live directly below docs/, so
    # the same repository targets need one fewer parent traversal.
    return text.replace("](../../", "](../")


def compare(expected: bytes, path: Path):
    if not path.is_file():
        return False, "missing"
    actual = path.read_bytes()
    if actual == expected:
        return True, ""
    return False, "stale"


def check_or_write(path: Path, data: bytes, check: bool, errors):
    ok, why = compare(data, path)
    if check:
        if not ok:
            errors.append("%s: %s" % (path.relative_to(ROOT), why))
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description="Build ATT modular Reference Manual outputs")
    parser.add_argument("--check", action="store_true", help="fail when generated outputs differ from modular sources")
    args = parser.parse_args()

    ver = version()
    items = manifest()
    errors = []

    # Structural EN/ZH parity is intentionally enforced here; #43 can extend this gate.
    for rel in items:
        for lang in LANGS:
            if not (LANGS[lang]["root"] / rel).is_file():
                errors.append("missing %s module: %s" % (lang.upper(), rel))
    if errors:
        raise SystemExit("\n".join(errors))

    with tempfile.TemporaryDirectory(prefix="att-reference-") as tmp:
        tmp = Path(tmp)
        for lang, cfg in LANGS.items():
            md_text = markdown(lang, items, ver)
            md_tmp = tmp / ("reference.zh.md" if lang == "zh" else "reference.md")
            html_tmp = tmp / ("reference.zh.html" if lang == "zh" else "reference.html")
            md_tmp.write_text(md_text, encoding="utf-8")
            render_html(md_tmp, html_tmp, cfg["title"].format(version=ver))
            md_bytes = md_tmp.read_bytes()
            html_bytes = html_tmp.read_bytes()
            check_or_write(cfg["md"], md_bytes, args.check, errors)
            check_or_write(cfg["html"], html_bytes, args.check, errors)
            compat_md_text = markdown(lang, items, ver, compatibility=True)
            compat_md_text = rebase_compatibility_links(compat_md_text)
            compat_md_tmp = tmp / ("compat-reference.zh.md" if lang == "zh" else "compat-reference.md")
            compat_html_tmp = tmp / ("compat-reference.zh.html" if lang == "zh" else "compat-reference.html")
            compat_md_tmp.write_text(compat_md_text, encoding="utf-8")
            render_html(compat_md_tmp, compat_html_tmp, cfg["title"].format(version=ver))
            check_or_write(cfg["compat_md"], compat_md_tmp.read_bytes(), args.check, errors)
            check_or_write(cfg["compat_html"], compat_html_tmp.read_bytes(), args.check, errors)

    if args.check and errors:
        print("Reference Manual generated outputs are stale:", file=sys.stderr)
        for error in errors:
            print("  - " + error, file=sys.stderr)
        print("Run: python3 tools/build_reference_manual.py", file=sys.stderr)
        return 2

    print("Reference Manual outputs %s for ATT V%s" % ("verified" if args.check else "generated", ver))
    return 0


if __name__ == "__main__":
    sys.exit(main())
