#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"
MANIFEST = DOCS / "reference-manifest.txt"

REQUIRED = {
    "runtime-context.md": ["EXEC.INPUT", "EXEC.VARS", "EXEC.ACTIONS", "EXEC.LOAD", "META", "EXEC.ACTIONS.<id>.output", "META.HTTPHELPER"],
    "execution-modes/run.md": ["run", "Testcase", "Stage", "latest-run.yaml", "exit"],
    "execution-modes/debug.md": ["att-debug/v1.2", "vars", "EXEC.VARS", "template", "flow", "tool", "debug.yaml", "--input", "--env", "output/debug", "exit"],
    "execution-modes/load.md": ["att-load/v1.6", "testdata", "scope", "selection", "users", "arrivalRate", "maxConcurrent", "overloadPolicy", "EXEC.LOAD", "execIdFormat", "load-summary", "failures/<EXEC.ID>"],
    "resources/tools.md": ["command-backed", "call-backed", "output.result", "stdoutFormat", "evidence"],
    "resources/dbhelper.md": ["att-dbhelper/v2.6", "query", "update", "transaction", "JDBC", "evidence"],
    "resources/mqhelper.md": ["att-mqhelper/v1.2", "send", "receive", "request", "IBM MQ", "String", "evidence"],
    "resources/operation-result.md": ["output.result", "evidence", "diagnostic", "attempts", "String", "responseFormat"],
    "actions.md": ["output.result", "String", "stdoutFormat", "responseFormat", "evidence.output"],
    "configuration.md": ["--env", "dbhelpers", "mqhelpers", "ENV", "logical", "run", "validate", "debug", "load"],
    "reliability-execution-control.md": ["assert", "runWhen", "onFailure", "timeout", "retry", "attempts", "PASS", "FAIL", "ERROR", "INVALID", "SKIPPED"],
}

FORBIDDEN = [
    "Transitional placement produced by issue #41",
    "This module establishes the target information-architecture location",
    "本模組先固定新的資訊架構位置",
    "#42 owns semantic reorganization",
]


def fail(errors, message):
    errors.append(message)


def main():
    errors = []
    if not MANIFEST.is_file():
        fail(errors, "missing docs/reference-manifest.txt")
        items = []
    else:
        items = [x.strip() for x in MANIFEST.read_text(encoding="utf-8").splitlines()
                 if x.strip() and not x.lstrip().startswith("#")]

    if "resources/operation-result.md" not in items:
        fail(errors, "manifest must include resources/operation-result.md")
    for item in items:
        if any(re.match(r"^\d+[_-]", part) for part in item.split("/")):
            fail(errors, "manifest path encodes order in a numeric prefix: " + item)

    for rel in items:
        for lang_root in (DOCS / "reference", DOCS / "reference.zh"):
            path = lang_root / rel
            if not path.is_file():
                fail(errors, "missing module: %s" % path.relative_to(ROOT))
                continue
            text = path.read_text(encoding="utf-8")
            for phrase in FORBIDDEN:
                if phrase in text:
                    fail(errors, "%s still contains transitional #41 text: %s" %
                         (path.relative_to(ROOT), phrase))
            if re.search(r"(?m)^### (0[1-9]|10)\s+", text):
                fail(errors, "%s still exposes a legacy top-level 09 chapter wrapper" %
                     path.relative_to(ROOT))

    for rel, tokens in REQUIRED.items():
        for lang_root in (DOCS / "reference", DOCS / "reference.zh"):
            path = lang_root / rel
            if not path.is_file():
                continue
            folded = path.read_text(encoding="utf-8").casefold()
            for token in tokens:
                if token.casefold() not in folded:
                    fail(errors, "%s missing required semantic coverage token: %s" %
                         (path.relative_to(ROOT), token))

    migration = DOCS / "history" / "reference-migration-map.md"
    if not migration.is_file():
        fail(errors, "missing archived docs/history/reference-migration-map.md")
    else:
        text = migration.read_text(encoding="utf-8")
        for chapter in range(1, 11):
            if not re.search(r"(?m)^\|\s*%02d\b" % chapter, text):
                fail(errors, "migration map missing legacy chapter %02d" % chapter)
        for disposition in ("KEEP", "MOVE", "MERGE", "DELETE", "REWRITE", "SPLIT"):
            if disposition not in text:
                fail(errors, "migration map never uses disposition %s" % disposition)

    quick = DOCS / "quick-start.md"
    if not quick.is_file():
        fail(errors, "missing canonical docs/quick-start.md")

    system_design = DOCS / "system-design" / "runtime-execution.md"
    if not system_design.is_file():
        fail(errors, "missing docs/system-design/runtime-execution.md")
    else:
        sd = system_design.read_text(encoding="utf-8")
        if "Validation pipeline" not in sd or "Execution and aggregation" not in sd:
            fail(errors, "system-design/runtime-execution.md does not contain migrated maintainer material")
        if "canonical runtime roots are `EXEC` and `META`" not in sd:
            fail(errors, "system-design/runtime-execution.md must explicitly align with canonical EXEC/META Context")
        stale_system_design = (
            "authoritative persisted runtime tree has one `CASE` root",
            "formula expressions, not cached results, enter Context",
        )
        for stale in stale_system_design:
            if stale in sd:
                fail(errors, "system-design/runtime-execution.md still contains stale architecture text: %s" % stale)

    readme = (ROOT / "README.md").read_text(encoding="utf-8") if (ROOT / "README.md").is_file() else ""
    for link in ("docs/README.md", "docs/quick-start.md", "docs/reference.html", "docs/system-design/"):
        if link not in readme:
            fail(errors, "README missing current documentation entry point: %s" % link)

    for output in (DOCS / "reference.md", DOCS / "reference.html",
                   DOCS / "reference.zh.md", DOCS / "reference.zh.html"):
        if not output.is_file():
            fail(errors, "missing generated Reference output: %s" % output.relative_to(ROOT))

    if (DOCS / "generated").exists():
        fail(errors, "docs/generated must not be used for current generated manuals; outputs belong directly under docs/")

    # Reference must no longer contain the old tutorial/cookbook or maintainer chapter.
    for lang_root in (DOCS / "reference", DOCS / "reference.zh"):
        authoring = lang_root / "test-authoring.md"
        if authoring.is_file():
            text = authoring.read_text(encoding="utf-8")
            for old in ("## Quick Start", "## Cookbook", "### Tool", "### Running Tests", "### Reports"):
                if old in text:
                    fail(errors, "%s still contains migrated material: %s" %
                         (authoring.relative_to(ROOT), old))
            required_sections = ("Workbook", "Stage", "Template", "Flow", "Authoring lifecycle")
            if any(not re.search(r"(?m)^##\s+" + re.escape(section) + r"\s*$", text)
                   for section in required_sections):
                fail(errors, "%s must expose Workbook, Stage, Template, Flow and authoring lifecycle peer sections" %
                     authoring.relative_to(ROOT))

    en_ops = DOCS / "reference" / "ci-packaging-operations.md"
    if en_ops.is_file() and "Architecture for Maintainers" in en_ops.read_text(encoding="utf-8"):
        fail(errors, "Reference 13 still contains Architecture for Maintainers")

    if errors:
        print("Reference content validation failed:", file=sys.stderr)
        for error in errors:
            print("  - " + error, file=sys.stderr)
        return 2
    print("Reference content semantic coverage verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
