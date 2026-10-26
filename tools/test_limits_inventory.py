import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INVENTORY = ROOT / "docs/operations/internal-limits-inventory.json"
TAXONOMY = {
    "EXECUTION_ERROR", "SUBMISSION_REJECTED", "ARCHIVE_FAILED", "ARTIFACT_OMITTED",
    "DATA_TRUNCATED", "HISTORY_EVICTED", "METRICS_SAMPLED",
}


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
            if row["allowedMin"] is not None and row["allowedMax"] is not None:
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

    def test_source_contracts_bind_inventory_defaults_and_configured_ranges(self):
        for contract in self.data["sourceContracts"]:
            source = (ROOT / contract["path"]).read_text(encoding="utf-8")
            self.assertRegex(source, contract["pattern"], contract["settingName"])

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
