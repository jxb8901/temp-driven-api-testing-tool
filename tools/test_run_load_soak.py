import hashlib
import importlib.util
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("run_load_soak.py")
SPEC = importlib.util.spec_from_file_location("run_load_soak", MODULE_PATH)
soak = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(soak)


class LoadSoakEvidenceTest(unittest.TestCase):
    def test_checkpoint_reader_checks_identity_and_integrity(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            payload = {"metrics": {"completed": 30000, "success": 30000}}
            checkpoint = {
                "schemaVersion": "att-load-metrics-checkpoint/v1",
                "state": "CHECKPOINT",
                "runId": "run-1",
                "scope": soak.CHECKPOINT_SCOPE,
                "sequence": 1,
                "capturedAt": datetime.now(timezone.utc).isoformat(),
                "payload": payload,
                "integritySha256": hashlib.sha256(soak.canonical_json_bytes(payload)).hexdigest(),
            }
            (directory / "checkpoint-00000000000000000001.json").write_text(
                json.dumps(checkpoint, separators=(",", ":")), encoding="utf-8")
            self.assertEqual(1, soak.latest_checkpoint(directory, "run-1")["sequence"])
            self.assertIsNone(soak.latest_checkpoint(directory, "other-run"))
            checkpoint["payload"]["metrics"]["completed"] = 1
            (directory / "checkpoint-00000000000000000001.json").write_text(json.dumps(checkpoint), encoding="utf-8")
            self.assertIsNone(soak.latest_checkpoint(directory, "run-1"))

    def test_five_minute_rates_keep_scheduled_started_completed_and_success_distinct(self):
        start = datetime(2026, 10, 26, tzinfo=timezone.utc)

        def checkpoint(sequence, seconds, metrics):
            return {
                "sequence": sequence,
                "capturedAt": (start + timedelta(seconds=seconds)).isoformat(),
                "payload": {"metrics": metrics},
            }

        before = checkpoint(1, 0, {"scheduled": 0, "started": 0, "completed": 0, "success": 0,
                                   "failure": 0, "runtimeError": 0, "dropped": 0})
        after = checkpoint(2, 300, {"configuredArrivalRatePerSecond": 150, "scheduled": 45000,
                                    "started": 45000, "completed": 30000, "success": 29999,
                                    "failure": 1, "runtimeError": 0, "dropped": 0})
        row = soak.delta_row(before, after)
        self.assertEqual(150.0, row["scheduledTPS"])
        self.assertEqual(150.0, row["startedTPS"])
        self.assertEqual(100.0, row["completedTPS"])
        self.assertAlmostEqual(29999 / 300.0, row["successfulTPS"])
        self.assertEqual(1, row["failedCompletions"])
        self.assertEqual(0, row["dropped"])

    def test_report_bundle_requires_every_hash_and_essential_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            run = Path(temporary) / "run"
            run.mkdir()
            rows = []
            for name in sorted(soak.REPORT_FILES):
                target = run / name
                target.parent.mkdir(parents=True, exist_ok=True)
                if name == "load-summary.json":
                    data = json.dumps({
                        "runId": "run",
                        "durationMs": 90000000,
                        "executionStatus": "PASS",
                        "performanceVerdict": "NOT_EVALUATED",
                        "report": "report/index.html",
                        "reportManifest": "load-report-manifest.json",
                    }).encode("utf-8")
                else:
                    data = ("content:" + name).encode("utf-8")
                target.write_bytes(data)
                rows.append({"path": name, "lengthBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
            manifest = {"schemaVersion": "att-load-report-manifest/v1", "state": "COMPLETE",
                        "complete": True, "runId": "run", "durationMs": 90000000,
                        "executionStatus": "PASS", "performanceVerdict": "NOT_EVALUATED", "files": rows}
            (run / "load-report-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual("COMPLETE", soak.verify_report_bundle(run)["state"])
            (run / "report/index.html").write_text("tampered", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                soak.verify_report_bundle(run)


if __name__ == "__main__":
    unittest.main()
