import json
import ast
import copy
import operator
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "docs/operations/internal-limits-inventory.json"
TAXONOMY = {
    "EXECUTION_ERROR", "SUBMISSION_REJECTED", "ARCHIVE_FAILED", "ARTIFACT_OMITTED",
    "DATA_TRUNCATED", "HISTORY_EVICTED", "METRICS_SAMPLED",
}


def _java_integer(expression, source, seen=None):
    """Evaluate a numeric Java constant expression from an explicitly captured source span."""
    seen = set() if seen is None else seen
    text = re.sub(r"(?<=\d)_(?=\d)", "", expression.strip())
    text = re.sub(r"(?<=\d)[lL]\b", "", text)
    identifiers = set(re.findall(r"\b[A-Za-z_$][A-Za-z0-9_$]*\b", text))
    for name in sorted(identifiers, key=len, reverse=True):
        if name in {"L", "l"}:
            continue
        if name in seen:
            raise AssertionError("cyclic Java constant expression: " + name)
        declaration = re.search(r"\b" + re.escape(name) + r"\s*=\s*([^;]+);", source)
        if not declaration:
            raise AssertionError("unresolved Java constant in source binding: " + name)
        replacement = str(_java_integer(declaration.group(1), source, seen | {name}))
        text = re.sub(r"\b" + re.escape(name) + r"\b", replacement, text)
    tree = ast.parse(text, mode="eval")

    def evaluate(node):
        if isinstance(node, ast.Expression):
            return evaluate(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, int):
            return node.value
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.USub):
            return -evaluate(node.operand)
        operations = {ast.Add: operator.add, ast.Sub: operator.sub,
                      ast.Mult: operator.mul, ast.FloorDiv: operator.floordiv,
                      ast.Div: operator.floordiv}
        if isinstance(node, ast.BinOp) and type(node.op) in operations:
            return operations[type(node.op)](evaluate(node.left), evaluate(node.right))
        raise AssertionError("unsupported Java integer expression: " + expression)

    return evaluate(tree)


def verify_source_bindings(entries, contracts, source_for):
    """Require captured source values for every inventory default and configured range."""
    by_id = {row["id"]: row for row in entries}
    bound = {contract["entryId"] for contract in contracts}
    if bound != set(by_id):
        raise AssertionError("source bindings must cover every entry exactly: missing=%r extra=%r" %
                             (sorted(set(by_id) - bound), sorted(bound - set(by_id))))
    captured = {entry_id: {} for entry_id in by_id}
    for contract in contracts:
        row = by_id[contract["entryId"]]
        source = source_for(contract["path"])
        match = re.search(contract["pattern"], source, re.DOTALL)
        if not match:
            raise AssertionError("source pattern did not match: " + row["id"])
        for field, group in contract.get("fields", {}).items():
            captured[row["id"]][field] = _java_integer(match.group(group), source)
        for field, value in contract.get("literalFields", {}).items():
            captured[row["id"]][field] = value
    for row in entries:
        expected_fields = {"default"}
        if row["configurable"]:
            expected_fields.update(field for field in ("allowedMin", "allowedMax")
                                   if row[field] is not None)
        for field in expected_fields:
            if field not in captured[row["id"]]:
                raise AssertionError("source binding omitted %s for %s" % (field, row["id"]))
            if captured[row["id"]][field] != row[field]:
                raise AssertionError("%s source mismatch for %s: inventory=%r source=%r" %
                                     (field, row["id"], row[field], captured[row["id"]][field]))


class LimitsInventoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = json.loads(INVENTORY.read_text(encoding="utf-8"))
        cls.entries = cls.data["entries"]
        cls.product_sources = "\n".join(
            path.read_text(encoding="utf-8", errors="replace")
            for base in ("att-engine", "att-server", "att-server-api", "att-worker", "att-web/frontend/src")
            for path in (ROOT / base).rglob("*") if path.is_file() and path.suffix in {".java", ".ts", ".tsx", ".js"}
        )

    def test_inventory_has_complete_unique_records_and_existing_sources(self):
        self.assertEqual("att-internal-limits/v1", self.data["schemaVersion"])
        self.assertEqual(TAXONOMY, set(self.data["outcomeTaxonomy"]))
        self.assertGreaterEqual(len(self.entries), 70)
        seen = set()
        required = {
            "id", "settingName", "sourcePath", "sourceClass", "default", "allowedMin", "allowedMax",
            "scope", "configurable", "hardCoded", "enforcingBoundary", "resultingStatus",
            "outcome", "diagnosticCode", "whereShown", "suggestedRemediation", "suggestedRemediationZh",
        }
        for row in self.entries:
            self.assertEqual(set(), required - set(row), row.get("id"))
            self.assertNotIn(row["id"], seen)
            seen.add(row["id"])
            self.assertTrue((ROOT / row["sourcePath"]).is_file(), row["id"])
            self.assertTrue(row["sourceClass"])
            self.assertIn(row["sourceClass"], (ROOT / row["sourcePath"]).read_text(encoding="utf-8"), row["id"])
            self.assertEqual(not row["configurable"], row["hardCoded"], row["id"])
            if row["outcome"] is not None:
                self.assertIn(row["outcome"], TAXONOMY, row["id"])
            self.assertTrue(row["diagnosticCode"] is None or isinstance(row["diagnosticCode"], str), row["id"])
            if row["diagnosticCode"] is not None:
                self.assertIn(row["diagnosticCode"], self.product_sources, row["id"])
            self.assertTrue(row["resultingStatus"], row["id"])
            self.assertEqual({"executionStatus", "exitCode", "httpStatus", "archiveStatus"},
                             set(row["resultingStatus"]), row["id"])
            if (isinstance(row["allowedMin"], (int, float)) and
                    isinstance(row["allowedMax"], (int, float))):
                self.assertLessEqual(row["allowedMin"], row["allowedMax"], row["id"])
            if row.get("sourceCheck"):
                text = (ROOT / row["sourcePath"]).read_text(encoding="utf-8")
                self.assertRegex(text, row["sourceCheck"], row["id"])

    def test_named_defaults_and_ranges_match_enforcing_sources(self):
        rows = {row["id"]: row for row in self.entries}
        expected = {
            "worker-frame": (1048576, None, None),
            "journal-event-data": (65536, None, None),
            "journal-history": (10000, 100, 1000000),
            "db-rows": (1000, 1, 1000000),
            "db-cell": (1048576, 1, 1073741824),
            "db-result": (10485760, 1, 1073741824),
            "mq-wait": (10000, 0, 3600000),
            "server-request": (1048576, 1024, 16777216),
            "inspection-concurrent": (2, 1, 16),
            "inspection-queue": (16, 0, 1024),
            "inspection-timeout": (30000, 100, 300000),
            "inspection-response": (262144, 1024, 1048576),
            "archive-entry-bytes": (16777216, None, None),
            "archive-total-bytes": (134217728, None, None),
            "archive-entry-count": (10000, None, None),
            "archive-candidates": (50000, None, None),
            "load-buckets": (4096, None, None),
            "load-latency-samples": (4096, None, None),
        }
        for key, values in expected.items():
            row = rows[key]
            self.assertEqual(values, (row["default"], row["allowedMin"], row["allowedMax"]), key)

    def test_source_contracts_bind_every_inventory_default_and_configured_range(self):
        self.assertEqual({row["id"] for row in self.entries},
                         {contract["entryId"] for contract in self.data["sourceContracts"]})
        verify_source_bindings(
            self.entries,
            self.data["sourceContracts"],
            lambda path: (ROOT / path).read_text(encoding="utf-8"),
        )

    def test_source_and_inventory_drift_each_fail_the_binding_gate(self):
        report_id = "load-report-total"
        source_text = (ROOT / "att-engine/src/main/java/att/load/LoadReportWriter.java").read_text(encoding="utf-8")
        sources = {contract["path"]: (ROOT / contract["path"]).read_text(encoding="utf-8")
                   for contract in self.data["sourceContracts"]}
        self.assertIn("MAX_REPORT_TOTAL_BYTES", source_text)
        sources["att-engine/src/main/java/att/load/LoadReportWriter.java"] = source_text.replace(
            "MAX_REPORT_TOTAL_BYTES = 120L", "MAX_REPORT_TOTAL_BYTES = 121L", 1)
        with self.assertRaises(AssertionError):
            verify_source_bindings(self.entries, self.data["sourceContracts"], lambda path: sources[path])

        changed_entries = copy.deepcopy(self.entries)
        next(row for row in changed_entries if row["id"] == report_id)["default"] += 1
        with self.assertRaises(AssertionError):
            verify_source_bindings(changed_entries, self.data["sourceContracts"], lambda path: (ROOT / path).read_text(encoding="utf-8"))

    def test_workload_evidence_quota_matches_emitted_truncation_outcome(self):
        row = next(item for item in self.entries if item["id"] == "load-evidence-workload")
        source = (ROOT / "att-engine/src/main/java/att/load/LoadRunResult.java").read_text(encoding="utf-8")
        self.assertEqual("DATA_TRUNCATED", row["outcome"])
        self.assertIn("LimitOutcome.DATA_TRUNCATED", source)
        self.assertIn("ATT-LOAD-EVIDENCE-QUOTA", source)

    def test_reviewed_inspector_limits_have_explicit_outcomes(self):
        rows = {row["id"]: row for row in self.entries}
        expected = {
            "resource-source-references": (100, "DATA_TRUNCATED", 200, None),
            "resource-workbook-bytes-individual": (33554432, "DATA_TRUNCATED", 200, "ATT-RESOURCE-CASE-INVALID"),
            "resource-revision-bytes": (268435456, "SUBMISSION_REJECTED", 413, "ATT-RESOURCE-LIMIT"),
            "resource-yaml-candidates": (20000, "SUBMISSION_REJECTED", 413, "ATT-RESOURCE-LIMIT"),
            "resource-config-tree-entries": (5000, "SUBMISSION_REJECTED", 413, "ATT-RESOURCE-LIMIT"),
            "resource-config-tree-bytes": (268435456, "SUBMISSION_REJECTED", 413, "ATT-RESOURCE-LIMIT"),
        }
        for key, values in expected.items():
            row = rows[key]
            self.assertEqual(values, (row["default"], row["outcome"],
                                      row["resultingStatus"]["httpStatus"], row["diagnosticCode"]), key)

    def test_operator_references_cover_every_setting_in_both_languages(self):
        for name in ("limits-reference.md", "limits-reference.zh.md"):
            text = (ROOT / "docs/operations" / name).read_text(encoding="utf-8")
            for row in self.entries:
                self.assertIn(row["settingName"], text, name + ": " + row["id"])

    def test_non_outcome_budgets_are_explicitly_marked(self):
        rows = {row["id"]: row for row in self.entries}
        for key in ("archive-download-chunk", "archive-memory-export"):
            self.assertIsNone(rows[key]["outcome"], key)
            self.assertIsNone(rows[key]["diagnosticCode"], key)
            self.assertIn("no limit outcome" if key == "archive-download-chunk" else "spilling is not data loss",
                          rows[key]["whereShown"] if key == "archive-download-chunk" else rows[key]["whereShown"], key)


if __name__ == "__main__":
    unittest.main()
