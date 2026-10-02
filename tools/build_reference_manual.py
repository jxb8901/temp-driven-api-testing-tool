#!/usr/bin/env python3
from pathlib import Path
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import unquote

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
    for item in items:
        if (item.startswith("/") or "\\" in item or
                any(part in ("", ".", "..") for part in item.split("/")) or
                not item.endswith(".md")):
            raise SystemExit("Invalid Reference manifest entry: " + item)
    return items


def rebase_for_docs_root(text, lang, module_rel):
    """Rebase source-module links for the combined manual's docs/ location."""
    module_root = LANGS[lang]["root"].resolve()
    source = (module_root / module_rel).resolve()

    def replace_link(match):
        raw_target = match.group(1)
        target, marker, fragment = raw_target.partition("#")
        if not target or re.match(r"^(?:[A-Za-z][A-Za-z0-9+.-]*:|//)", target):
            return match.group(0)
        resolved = (source.parent / unquote(target)).resolve()
        try:
            within_modules = resolved.relative_to(module_root)
            rebased = ("reference" if lang == "en" else "reference.zh") + "/" + within_modules.as_posix()
        except ValueError:
            rebased = os.path.relpath(str(resolved), str(DOCS.resolve())).replace("\\", "/")
        suffix = marker + fragment if marker else ""
        return "](" + rebased + suffix + ")"

    return re.sub(r"(?<!!)\]\(([^)]+)\)", replace_link, text)


def markdown_slug(text):
    """Match the repository's Markdown anchor convention for generated TOC links."""
    text = re.sub(r"`([^`]*)`", r"\1", text)
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)
    text = text.strip().lower()
    text = "".join(ch for ch in text if ch.isalnum() or ch in " _-")
    text = re.sub(r"\s+", "-", text)
    text = re.sub(r"-+", "-", text)
    return text.strip("-")


def table_of_contents(chunks, lang, title):
    """Build a localized H2/H3 TOC whose anchors match Markdown heading slugs."""
    label = "目錄" if lang == "zh" else "Contents"
    lines = ["**" + label + "**", ""]
    title_slug = markdown_slug(title)
    counts = {title_slug: 1} if title_slug else {}

    for chunk in chunks:
        fence_char = None
        fence_length = 0
        for line in chunk.splitlines():
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
            if not heading:
                continue
            level = len(heading.group(1))
            text = heading.group(2)
            slug = markdown_slug(text)
            if not slug:
                continue
            count = counts.get(slug, 0)
            counts[slug] = count + 1
            anchor = slug if count == 0 else "%s-%d" % (slug, count)
            if level in (2, 3):
                indent = "  " if level == 3 else ""
                lines.append("%s- [%s](#%s)" % (indent, text, anchor))

    return "\n".join(lines)


def markdown(lang, items, ver, include_toc=True):
    cfg = LANGS[lang]
    chunks = []
    for rel in items:
        path = cfg["root"] / rel
        if not path.is_file():
            raise SystemExit("Missing %s Reference module: %s" %
                             (lang.upper(), path.relative_to(ROOT)))
        chunks.append(rebase_for_docs_root(path.read_text(encoding="utf-8").rstrip(), lang, rel))

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
    combined = "\n".join(header)
    if include_toc:
        combined += "\n" + table_of_contents(chunks, lang, title) + "\n"
    combined += "\n\n".join(chunks) + "\n"
    return combined


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
            html_source = markdown(lang, items, ver, include_toc=False)
            md_tmp = tmp / ("reference.zh.md" if lang == "zh" else "reference.md")
            html_tmp = tmp / ("reference.zh.html" if lang == "zh" else "reference.html")
            md_tmp.write_text(html_source, encoding="utf-8")
            render_html(md_tmp, html_tmp, cfg["title"].format(version=ver))
            check_or_write(cfg["md"], md_text.encode("utf-8"), args.check, errors)
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
