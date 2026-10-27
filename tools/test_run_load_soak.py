import hashlib
import importlib.util
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

MODULE_PATH = Path(__file__).with_name("run_load_soak.py")
SPEC = importlib.util.spec_from_file_location("run_load_soak", MODULE_PATH)
soak = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(soak)


class LoadSoakEvidenceTest(unittest.TestCase):
    def test_macos_resource_sampling_uses_supported_process_thread_rows(self):
        def process_output(command, **kwargs):
            if command[:3] == ["ps", "-o", "rss="]:
                return "120000\n"
            if command[:2] == ["ps", "-M"]:
                return "USER PID COMMAND\nuser 123 java\n  123 thread-a\n  123 thread-b\n"
            if command[0] == "lsof":
                return "COMMAND PID USER FD TYPE NAME\njava 123 user cwd DIR /tmp\njava 123 user txt REG /tmp/att.jar\n"
            raise AssertionError("unexpected command: {}".format(command))

        with mock.patch.object(soak.platform, "system", return_value="Darwin"), \
                mock.patch.object(soak.subprocess, "check_output", side_effect=process_output):
            sample = soak.process_resource_sample(123)

        self.assertTrue(sample["supported"])
        self.assertEqual(120000 * 1024, sample["residentBytes"])
        self.assertEqual(3, sample["threads"])
        self.assertEqual(2, sample["openFileDescriptors"])

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
                                    "failure": 1, "runtimeError": 0, "otherOutcome": 0, "dropped": 0})
        row = soak.delta_row(before, after)
        self.assertEqual(150.0, row["scheduledTPS"])
        self.assertEqual(150.0, row["startedTPS"])
        self.assertEqual(100.0, row["completedTPS"])
        self.assertAlmostEqual(29999 / 300.0, row["successfulTPS"])
        self.assertEqual(1, row["failedCompletions"])
        self.assertEqual(0, row["otherOutcomes"])
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
                        "metrics": {"accounting": {"state": "VERIFIED"}},
                        "report": "report/index.html",
                        "reportManifest": "load-report-manifest.json",
                    }).encode("utf-8")
                else:
                    data = ("content:" + name).encode("utf-8")
                target.write_bytes(data)
                rows.append({"path": name, "lengthBytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
            manifest = {"schemaVersion": "att-load-report-manifest/v1", "state": "COMPLETE",
                        "complete": True, "runId": "run", "durationMs": 90000000,
                        "executionStatus": "PASS", "performanceVerdict": "NOT_EVALUATED",
                        "accountingStatus": "VERIFIED", "files": rows}
            (run / "load-report-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            self.assertEqual("COMPLETE", soak.verify_report_bundle(run)["state"])
            (run / "report/index.html").write_text("tampered", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                soak.verify_report_bundle(run)

    def test_interruption_preserves_verified_checkpoints_as_incomplete_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            run = root / "scratch" / "output" / "load" / "run-2"
            checkpoint_dir = run / "checkpoints" / soak.CHECKPOINT_SCOPE
            checkpoint_dir.mkdir(parents=True)
            (run / "partial.txt").write_text("preserve", encoding="utf-8")
            payload = {"metrics": {"scheduled": 5000, "started": 5000, "completed": 4999,
                                   "success": 4999, "failure": 0, "runtimeError": 0,
                                   "dropped": 0, "currentInFlight": 1}}
            checkpoint = {
                "schemaVersion": "att-load-metrics-checkpoint/v1", "state": "CHECKPOINT",
                "runId": "run-2", "scope": soak.CHECKPOINT_SCOPE, "sequence": 7,
                "capturedAt": datetime.now(timezone.utc).isoformat(), "finalized": False,
                "payload": payload,
            }
            checkpoint["integritySha256"] = hashlib.sha256(
                soak.canonical_json_bytes(payload)).hexdigest()
            (checkpoint_dir / "checkpoint-00000000000000000007.json").write_text(
                json.dumps(checkpoint, separators=(",", ":")), encoding="utf-8")
            evidence_root = root / "evidence"
            result = soak.capture_incomplete_evidence(evidence_root, run, checkpoint_dir, "run-2",
                                                      "abc123", 301.5, "interrupted", -15)
            self.assertEqual("PARTIAL", result["result"])
            self.assertFalse(result["complete"])
            self.assertEqual(7, result["latestCheckpoint"]["sequence"])
            self.assertTrue((evidence_root / "load" / "run-2" / "partial.txt").is_file())
            saved = json.loads((evidence_root / "acceptance.json").read_text(encoding="utf-8"))
            self.assertEqual("PARTIAL", saved["result"])

    def test_fake_process_failure_preserves_checkpoint_before_temporary_runtime_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runtime = root / "runtime"
            (runtime / "lib").mkdir(parents=True)
            launcher = runtime / "att.sh"
            launcher.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            evidence = root / "evidence"
            args = SimpleNamespace(
                repository_root=Path(__file__).resolve().parents[1], runtime_root=runtime,
                output=evidence, run_id="fake-process-run", commit_sha="abc123",
                duration_hours=25, arrival_rate=150, max_concurrent=512,
            )

            class FakeProcess:
                pid = 987654
                returncode = None

                def poll(self):
                    return self.returncode

                def wait(self, timeout=None):
                    self.returncode = -15
                    return self.returncode

            def start_fake_process(command, cwd, env, **kwargs):
                checkpoint_dir = Path(env["ATT_PACKAGE_ROOT"]) / "output/load/fake-process-run/checkpoints" / soak.CHECKPOINT_SCOPE
                checkpoint_dir.mkdir(parents=True)
                payload = {"metrics": {"scheduled": 12, "started": 12, "completed": 11,
                                        "success": 11, "failure": 0, "runtimeError": 0,
                                        "dropped": 0, "currentInFlight": 1}}
                checkpoint = {
                    "schemaVersion": "att-load-metrics-checkpoint/v1", "state": "CHECKPOINT",
                    "runId": "fake-process-run", "scope": soak.CHECKPOINT_SCOPE,
                    "sequence": 2, "capturedAt": datetime.now(timezone.utc).isoformat(),
                    "finalized": False, "payload": payload,
                    "integritySha256": hashlib.sha256(soak.canonical_json_bytes(payload)).hexdigest(),
                }
                (checkpoint_dir / "checkpoint-00000000000000000002.json").write_text(
                    json.dumps(checkpoint, separators=(",", ":")), encoding="utf-8")
                return FakeProcess()

            with mock.patch.object(soak.subprocess, "Popen", side_effect=start_fake_process), \
                    mock.patch.object(soak.os, "killpg", return_value=None), \
                    self.assertRaisesRegex(RuntimeError, "first verified checkpoint is missing"):
                soak.run_soak(args)

            copied = evidence / "load/fake-process-run/checkpoints" / soak.CHECKPOINT_SCOPE
            saved_checkpoint = soak.latest_checkpoint(copied, "fake-process-run")
            self.assertIsNotNone(saved_checkpoint)
            self.assertEqual(2, saved_checkpoint["sequence"])
            saved = json.loads((evidence / "acceptance.json").read_text(encoding="utf-8"))
            self.assertEqual("PARTIAL", saved["result"])
            self.assertFalse(saved["complete"])

    def test_resource_summaries_report_observed_slope_and_unavailable_counters(self):
        rows = [
            {"elapsedSeconds": 0, "residentBytes": 1000, "threads": 10},
            {"elapsedSeconds": 3600, "residentBytes": 1600, "threads": 12},
        ]
        summary = soak.summarize_resource_series(rows, "", ("residentBytes", "threads", "openFileDescriptors"))
        self.assertEqual(600, summary["residentBytes"]["slopePerHour"])
        self.assertEqual(12, summary["threads"]["maximum"])
        self.assertFalse(summary["openFileDescriptors"]["available"])

    def test_resource_acceptance_requires_dense_os_and_jvm_trends(self):
        process_rows = []
        for elapsed in range(0, 25 * 3600 + 1, 60):
            process_rows.append({"elapsedSeconds": elapsed, "pid": 123, "supported": True,
                                 "residentBytes": 100000 + elapsed, "threads": 20,
                                 "openFileDescriptors": 100})
        jvm_rows = []
        for sequence, elapsed in enumerate(range(0, 25 * 3600 + 1, 300), 1):
            jvm_rows.append({"elapsedSeconds": elapsed, "generator": {
                "heapUsedBytes": 1000 + sequence, "heapCommittedBytes": 2000,
                "liveThreads": 20, "gcCollectionCount": sequence,
                "gcCollectionTimeMs": sequence * 2,
            }})
        result = soak.assess_resource_evidence(process_rows, jvm_rows, 24 * 3600)
        self.assertEqual("CAPTURED", result["status"])
        self.assertTrue(result["process"]["series"]["openFileDescriptors"]["summary"]["available"])
        self.assertEqual("STABLE_WITHIN_TOLERANCE", result["growth"]["threads"]["status"])
        with self.assertRaisesRegex(RuntimeError, "resource series openFileDescriptors"):
            missing_fd = [dict(row, openFileDescriptors=None) for row in process_rows]
            soak.assess_resource_evidence(missing_fd, jvm_rows, 24 * 3600)
        with self.assertRaisesRegex(RuntimeError, "heapUsedBytes increased"):
            growing_heap = [dict(row, generator=dict(row["generator"], heapUsedBytes=index * 1024 * 1024))
                            for index, row in enumerate(jvm_rows)]
            soak.assess_resource_evidence(process_rows, growing_heap, 24 * 3600)


if __name__ == "__main__":
    unittest.main()
