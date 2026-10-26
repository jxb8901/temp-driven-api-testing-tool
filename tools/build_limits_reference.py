#!/usr/bin/env python3
"""Render the paired operator limits matrices from the verified JSON inventory."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "docs/operations/internal-limits-inventory.json"
EN = ROOT / "docs/operations/limits-reference.md"
ZH = ROOT / "docs/operations/limits-reference.zh.md"


def cell(value):
    if value is None:
        return "—"
    if isinstance(value, dict):
        return "; ".join("%s: %s" % (key, val) for key, val in value.items())
    return str(value).replace("|", "\\|").replace("\n", " ")


def source(row):
    return "`%s` · `%s`" % (row["sourcePath"], row["sourceClass"])


def build(language, data):
    zh = language == "zh"
    if zh:
        title = "# ATT 內部限制參考與容量矩陣"
        intro = "本文件列出 Engine、Worker、Server、artifact 發布及 Studio 的容量邊界。權威機器可讀清單：[internal-limits-inventory.json](internal-limits-inventory.json)。目前的安全限制有意保持不變；遇到容量問題時，先採用有界回應、分頁、精簡 evidence 及核准 artifact。"
        taxonomy = "## 結果分類與閱讀方式\n\n`EXECUTION_ERROR` 表示工作執行失敗；`SUBMISSION_REJECTED` 表示請求未獲接納；`ARCHIVE_FAILED` 與 `ARTIFACT_OMITTED` 描述 artifact 發布結果；`DATA_TRUNCATED`、`HISTORY_EVICTED`、`METRICS_SAMPLED` 表示資料呈現或保留有限。這些分類不會改寫執行 `status`、`exitCode`、threshold verdict 或 archive lifecycle。沒有量測資料會顯示為 unavailable，不代表零。"
        headers = "| Setting／來源 | 預設／允許範圍 | 範圍與執行邊界 | 結果／分類／診斷 | 顯示位置 | 安全處理方式 |\n|---|---|---|---|---|---|"
        summary = "## 容量矩陣"
        triage = "## 操作排查順序\n\n| 症狀／診斷 | 子系統 | 安全處理 |\n|---|---|---|\n| `HTTP 413`／request 或 inspection response 過大 | Server admission／inspection | 縮小 request，採用分頁、篩選及 bounded source excerpt。 |\n| `HTTP 429`、`503` 或 `504` | Server queue／inspection／SSE | 降低提交尖峰；待容量釋放後重試，並分開檢查 queue、inspection deadline 及 SSE observer。 |\n| `DB_POOL_TIMEOUT`、`MQ_POOL_TIMEOUT`、`HTTP_POOL_TIMEOUT` | Engine helper pool | 分別檢查 active／idle／waiting／timeout；先降低爭用及持有時間。 |\n| `*_RESPONSE_TOO_LARGE` | Engine DB／MQ／HTTP | 先投影欄位、分頁或縮減 evidence，再考慮設定範圍內的調整。 |\n| `ATT-WORKER-EVENT-TRUNCATED`、`ATT-JOURNAL-EVENT-TRUNCATED` | Worker／Journal transport | 以 compact result 作主資訊；從 archive 或分頁取得核准細節。不要把單獨截斷當作 ERROR。 |\n| `ATT-LOAD-EVIDENCE-QUOTA` | LOAD evidence | 查看 skipped evidence count； aggregate metrics 保持完整，先調整 evidence selection／sampling。 |\n| `LOAD_REPORT_WRITE_FAILED` | LOAD report publication | 執行狀態及 exit code 保持原值；縮減 detail budget 使用量或待儲存空間可用後重試。 |\n| `ATT-ARCHIVE-LIMIT-EXCEEDED` 或 `ATT-ARTIFACT-OMITTED` | Artifact publication | 分別查看 archive FAILED 或 omission manifest；執行狀態不會因此變更。 |\n| `METRICS_SAMPLED`／bucket coverage 不完整 | LOAD metrics | 按 observation count、sample count 及 coverage window 解讀圖表。完整長時間圖表需另行設計。 |\n| `ATT-VALIDATION-LIMIT` | Validation budget | 使用 selected scope、縮小 dependency closure，並保留 partial coverage 診斷。 |"
        ownership = "## 已完成的相鄰工作與後續\n\n本參考重用 #297 compact terminal RESULT、#86 LOAD metrics、#236/#243 archive publication、#265 live-output controls、#281 metrics 及 #263 execution behavior。完整 multi-hour time-series downsampling／segmented history 屬於另一設計，連結 #299；本文件不實作 25 小時 soak。"
        footer = "本清單涵蓋相關 Engine、Worker、Server、artifact publication、Studio 設定與固定容量界限；`configurable=false` 項目沒有 operator 設定範圍。"
    else:
        title = "# ATT Internal Limits Reference and Capacity Matrix"
        intro = "This reference lists capacity boundaries across Engine, Worker, Server, artifact publication, and Studio. The authoritative machine-readable inventory is [internal-limits-inventory.json](internal-limits-inventory.json). Existing safety limits remain unchanged; address pressure first with bounded responses, pagination, smaller evidence policies, and approved artifacts."
        taxonomy = "## Outcome taxonomy and interpretation\n\n`EXECUTION_ERROR` means execution failed; `SUBMISSION_REJECTED` means admission rejected the request; `ARCHIVE_FAILED` and `ARTIFACT_OMITTED` describe publication outcomes; `DATA_TRUNCATED`, `HISTORY_EVICTED`, and `METRICS_SAMPLED` describe bounded data presentation or retention. These classifications do not rewrite execution `status`, `exitCode`, threshold verdict, or archive lifecycle. An unavailable measurement is not zero."
        headers = "| Setting / source | Default / allowed range | Scope and enforcing boundary | Result / outcome / diagnostic | Shown in | Safe remediation |\n|---|---|---|---|---|---|"
        summary = "## Capacity matrix"
        triage = "## Operational triage\n\n| Symptom / diagnostic | Subsystem | Safe remediation |\n|---|---|---|\n| `HTTP 413` / oversized request or inspection response | Server admission / inspection | Narrow the request; use pagination, filters, and bounded source excerpts. |\n| `HTTP 429`, `503`, or `504` | Server queue / inspection / SSE | Reduce submission bursts; retry after capacity releases; inspect queue, inspection deadline, and SSE observer limits separately. |\n| `DB_POOL_TIMEOUT`, `MQ_POOL_TIMEOUT`, `HTTP_POOL_TIMEOUT` | Engine helper pool | Inspect active / idle / waiting / timeouts independently; first reduce contention and hold time. |\n| `*_RESPONSE_TOO_LARGE` | Engine DB / MQ / HTTP | Project fields, paginate, or reduce evidence before considering an in-range setting change. |\n| `ATT-WORKER-EVENT-TRUNCATED`, `ATT-JOURNAL-EVENT-TRUNCATED` | Worker / Journal transport | Use the compact result as primary; fetch approved detail from archive or paginated history. Truncation alone is not ERROR. |\n| `ATT-LOAD-EVIDENCE-QUOTA` | LOAD evidence | Inspect skipped evidence counts. Aggregate metrics remain complete; first narrow evidence selection or adjust sampling. |\n| `LOAD_REPORT_WRITE_FAILED` | LOAD report publication | Execution status and exit code stay unchanged; reduce detail budget use or retry when storage is available. |\n| `ATT-ARCHIVE-LIMIT-EXCEEDED` or `ATT-ARTIFACT-OMITTED` | Artifact publication | Inspect FAILED archive state or omission manifest separately; execution status does not change. |\n| `METRICS_SAMPLED` / incomplete bucket coverage | LOAD metrics | Interpret charts with observation count, sample count, and coverage window. Full long-duration charts need a separate design. |\n| `ATT-VALIDATION-LIMIT` | Validation budget | Use selected scope, narrow the dependency closure, and preserve partial-coverage diagnostics. |"
        ownership = "## Related completed work and follow-up\n\nThis reference reuses #297 compact terminal RESULT, #86 LOAD metrics, #236/#243 archive publication, #265 live-output controls, #281 metrics, and #263 execution behavior. Full multi-hour time-series downsampling or segmented history is a separate design linked to #299; this issue does not implement the 25-hour soak."
        footer = "This inventory covers relevant Engine, Worker, Server, artifact publication, Studio, and fixed-capacity boundaries. Entries with `configurable=false` have no operator setting range."
    lines = [title, "", intro, "", "**Inventory schema:** `%s`; entries: %d." % (data["schemaVersion"], len(data["entries"])), "", taxonomy, "", summary, "", headers]
    for row in data["entries"]:
        result = "<br>".join("%s: %s" % (k, v) for k, v in row["resultingStatus"].items() if v is not None) if isinstance(row["resultingStatus"], dict) else row["resultingStatus"]
        titlepart = "**%s**<br>%s" % (row["settingName"], source(row))
        limits = "default `%s`<br>min `%s`, max `%s`" % (cell(row["default"]), cell(row["allowedMin"]), cell(row["allowedMax"]))
        scope = "%s<br>**%s**" % (row["scope"], row["enforcingBoundary"])
        outcome = row["outcome"] if row["outcome"] is not None else (
            "無限制結果；有界實作細節" if zh else "no limit outcome; bounded implementation detail")
        diagnostic = "`%s`" % row["diagnosticCode"] if row["diagnosticCode"] else (
            "沒有專屬結構化診斷" if zh else "no dedicated structured diagnostic")
        status = "%s<br>`%s`<br>%s" % (cell(result), outcome, diagnostic)
        remediation = row.get("suggestedRemediationZh", "請採用有界分頁並先檢查實際容量。") if zh else row["suggestedRemediation"]
        columns = [titlepart, limits, scope, status, cell(row["whereShown"]), cell(remediation)]
        lines.append("| " + " | ".join(columns) + " |")
    lines.extend(["", triage, "", ownership, "", footer, ""])
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    data = json.loads(INVENTORY.read_text(encoding="utf-8"))
    expected = {EN: build("en", data), ZH: build("zh", data)}
    stale = []
    for path, content in expected.items():
        if args.check:
            if not path.is_file() or path.read_text(encoding="utf-8") != content:
                stale.append(str(path.relative_to(ROOT)))
        else:
            path.write_text(content, encoding="utf-8")
    if stale:
        raise SystemExit("stale limits reference: " + ", ".join(stale))
    print("Limits References are current for %d inventory entries" % len(data["entries"]))


if __name__ == "__main__":
    main()
