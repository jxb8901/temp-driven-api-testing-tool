#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs"

MANIFEST = [
    "01_overview.md",
    "02_test_authoring.md",
    "03_runtime_context.md",
    "04_execution_modes/index.md",
    "04_execution_modes/run.md",
    "04_execution_modes/debug.md",
    "04_execution_modes/load.md",
    "05_resources/index.md",
    "05_resources/tools.md",
    "05_resources/dbhelper.md",
    "05_resources/mqhelper.md",
    "06_environment_testdata.md",
    "07_expressions.md",
    "08_reliability_execution_control.md",
    "09_configuration.md",
    "10_cli.md",
    "11_results_reports_evidence.md",
    "12_validation_diagnostics.md",
    "13_ci_packaging_operations.md",
    "appendices/index.md",
    "appendices/schema_matrix.md",
    "appendices/compatibility.md",
    "appendices/migrations.md",
    "appendices/limits_defaults.md",
]

EN_TITLES = {
    "01_overview.md": "## 01 Overview and Concepts",
    "02_test_authoring.md": "## 02 Test Authoring",
    "03_runtime_context.md": "## 03 Runtime and Context Model",
    "04_execution_modes/index.md": "## 04 Execution Modes",
    "04_execution_modes/run.md": "### 4.1 Run Mode",
    "04_execution_modes/debug.md": "### 4.2 Standalone Debug",
    "04_execution_modes/load.md": "### 4.3 Load Mode",
    "05_resources/index.md": "## 05 Resources and Integrations",
    "05_resources/tools.md": "### 5.1 Tool",
    "05_resources/dbhelper.md": "### 5.2 DBHelper",
    "05_resources/mqhelper.md": "### 5.3 MQHelper",
    "06_environment_testdata.md": "## 06 Environment and Test Data",
    "07_expressions.md": "## 07 Expressions and Built-ins",
    "08_reliability_execution_control.md": "## 08 Reliability and Execution Control",
    "09_configuration.md": "## 09 Configuration Reference",
    "10_cli.md": "## 10 CLI Reference",
    "11_results_reports_evidence.md": "## 11 Results, Reports, and Evidence",
    "12_validation_diagnostics.md": "## 12 Validation and Diagnostics",
    "13_ci_packaging_operations.md": "## 13 CI, Packaging, and Operations",
    "appendices/index.md": "## 14 Appendices",
    "appendices/schema_matrix.md": "### 14.1 Schema and Version Matrix",
    "appendices/compatibility.md": "### 14.2 Compatibility and Deprecated Aliases",
    "appendices/migrations.md": "### 14.3 Migration Notes",
    "appendices/limits_defaults.md": "### 14.4 Limits and Defaults",
}

ZH_TITLES = {
    "01_overview.md": "## 01 概覽與核心概念",
    "02_test_authoring.md": "## 02 測試案例編寫",
    "03_runtime_context.md": "## 03 Runtime 與 Context 模型",
    "04_execution_modes/index.md": "## 04 執行模式",
    "04_execution_modes/run.md": "### 4.1 Run 模式",
    "04_execution_modes/debug.md": "### 4.2 Standalone Debug",
    "04_execution_modes/load.md": "### 4.3 Load 模式",
    "05_resources/index.md": "## 05 資源與整合",
    "05_resources/tools.md": "### 5.1 Tool",
    "05_resources/dbhelper.md": "### 5.2 DBHelper",
    "05_resources/mqhelper.md": "### 5.3 MQHelper",
    "06_environment_testdata.md": "## 06 環境與測試數據",
    "07_expressions.md": "## 07 表達式與 Built-ins",
    "08_reliability_execution_control.md": "## 08 可靠性與執行控制",
    "09_configuration.md": "## 09 配置參考",
    "10_cli.md": "## 10 CLI 參考",
    "11_results_reports_evidence.md": "## 11 結果、報告與 Evidence",
    "12_validation_diagnostics.md": "## 12 驗證與診斷",
    "13_ci_packaging_operations.md": "## 13 CI、打包與運維",
    "appendices/index.md": "## 14 附錄",
    "appendices/schema_matrix.md": "### 14.1 Schema 與版本矩陣",
    "appendices/compatibility.md": "### 14.2 相容性與已棄用 Alias",
    "appendices/migrations.md": "### 14.3 遷移說明",
    "appendices/limits_defaults.md": "### 14.4 限制與預設值",
}

LEGACY_ASSIGNMENT = {
    "01_overview.md": ["01"],
    "02_test_authoring.md": ["02", "03", "04"],
    "07_expressions.md": ["07"],
    "09_configuration.md": ["06"],
    "10_cli.md": ["05"],
    "11_results_reports_evidence.md": ["08"],
    "12_validation_diagnostics.md": ["09"],
    "13_ci_packaging_operations.md": ["10"],
}

BRIDGE_EN = "This module establishes the target information-architecture location. The detailed normative material is preserved in the migrated legacy sections in this transitional #41 structure and will be moved here by #42 without changing runtime behavior."
BRIDGE_ZH = "本模組先固定新的資訊架構位置。現有規範內容在 #41 過渡結構中完整保留於已遷移的舊章節，#42 會在不改變 runtime 行為的前提下把相關內容移入本章。"


def extract_chapters(text: str):
    matches = list(re.finditer(r"(?m)^## (0[1-9]|10)\s+.*$", text))
    if len(matches) != 10:
        raise SystemExit("Expected exactly 10 legacy top-level chapters, found %d" % len(matches))
    out = {}
    for i, match in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        out[match.group(1)] = text[match.start():end].rstrip() + "\n"
    return out


def demote(text: str) -> str:
    lines = []
    for line in text.splitlines():
        m = re.match(r"^(#{2,5})(\s+.*)$", line)
        if m:
            line = "#" + m.group(1) + m.group(2)
        lines.append(line)
    return "\n".join(lines).rstrip() + "\n"


def write_tree(source_file: Path, target_root: Path, titles, bridge):
    source = source_file.read_text(encoding="utf-8")
    chapters = extract_chapters(source)
    target_root.mkdir(parents=True, exist_ok=True)
    for rel in MANIFEST:
        path = target_root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        parts = [titles[rel], ""]
        assigned = LEGACY_ASSIGNMENT.get(rel, [])
        if assigned:
            parts.append("<!-- Transitional placement produced by issue #41. #42 owns semantic reorganization. -->")
            parts.append("")
            for chapter in assigned:
                parts.append(demote(chapters[chapter]).rstrip())
                parts.append("")
        else:
            parts.append(bridge)
            parts.append("")
        path.write_text("\n".join(parts).rstrip() + "\n", encoding="utf-8")


def main():
    en = DOCS / "09_Reference_Manual_V3.md"
    zh = DOCS / "09_Reference_Manual_V3.zh.md"
    if not en.exists() or not zh.exists():
        raise SystemExit("Legacy EN/ZH Reference Manual sources are required for bootstrap")
    write_tree(en, DOCS / "reference", EN_TITLES, BRIDGE_EN)
    write_tree(zh, DOCS / "reference.zh", ZH_TITLES, BRIDGE_ZH)
    (DOCS / "reference-manifest.txt").write_text("\n".join(MANIFEST) + "\n", encoding="utf-8")
    readme = """# Reference Manual source modules\n\nThese modules are the authoritative editable sources for the current ATT Reference Manual.\n\n- English sources: `docs/reference/`\n- Chinese sources: `docs/reference.zh/`\n- Deterministic order: `docs/reference-manifest.txt`\n- Generated combined outputs: `docs/generated/`\n- Legacy `docs/09_Reference_Manual_V3*` paths are generated compatibility outputs.\n\nRun `python3 tools/build_reference_manual.py` after editing source modules. Use `--check` in CI.\n\nIssue #41 performs structural modularization only. Transitional legacy-section placement is intentionally preserved until #42 completes the semantic migration/rewrite defined by `docs/documentation-architecture.md`.\n"""
    (DOCS / "reference" / "README.md").write_text(readme, encoding="utf-8")
    (DOCS / "reference.zh" / "README.md").write_text(readme, encoding="utf-8")
    print("Bootstrapped modular Reference Manual sources")


if __name__ == "__main__":
    main()
