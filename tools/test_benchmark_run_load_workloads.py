import importlib.util
import pathlib
import sys
import tempfile
import time
import unittest
from unittest import mock
from http.server import ThreadingHTTPServer


MODULE_PATH = pathlib.Path(__file__).with_name("benchmark_run_load_workloads.py")
SPEC = importlib.util.spec_from_file_location("benchmark_run_load_workloads", MODULE_PATH)
benchmark = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)


class RunLoadBenchmarkTest(unittest.TestCase):
    def test_listen_backlog_is_set_before_server_activation(self):
        observed = []
        with mock.patch.object(ThreadingHTTPServer, "server_bind", lambda _server: None), \
                mock.patch.object(ThreadingHTTPServer, "server_activate",
                                  lambda server: observed.append(server.request_queue_size)):
            server = benchmark.create_http_server(12)
            server.server_close()
        self.assertEqual([128], observed)

        observed.clear()
        with mock.patch.object(ThreadingHTTPServer, "server_bind", lambda _server: None), \
                mock.patch.object(ThreadingHTTPServer, "server_activate",
                                  lambda server: observed.append(server.request_queue_size)):
            server = benchmark.create_http_server(512)
            server.server_close()
        self.assertEqual([512], observed)

    def test_timeout_terminates_process_group_and_returns_sample_record(self):
        child_code = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c','import time;time.sleep(30)']); print('child-started',flush=True); time.sleep(30)"
        with tempfile.TemporaryDirectory() as temporary:
            started = time.monotonic()
            result = benchmark.run_command([sys.executable, "-c", child_code], temporary, 0.3)
            elapsed = time.monotonic() - started

        self.assertTrue(result["timedOut"])
        self.assertEqual(0.3, result["timeoutSeconds"])
        self.assertIn("process group/tree terminated", result["terminationReason"])
        self.assertLess(elapsed, 8)
        self.assertIn("child-started", "\n".join(result["outputTail"]))

    def test_timed_out_run_and_load_samples_remain_in_report(self):
        successful = {"exitCode": 0, "firstOutputMs": 1.0, "totalMs": 2.0,
                      "outputTail": [], "timedOut": False}
        timeout = {"exitCode": -9, "firstOutputMs": 1.0, "totalMs": 300.0,
                   "outputTail": ["stdout: started"], "timedOut": True,
                   "timeoutSeconds": 0.1, "terminationReason": "process group/tree terminated"}
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            with mock.patch.object(benchmark, "run_command", side_effect=[successful, timeout, timeout]):
                run_report = benchmark.run_run_benchmark(root, runs=2, warmups=0,
                                                          case_count=20, timeout_seconds=0.1)
            self.assertEqual(2, run_report["timedOutSamples"])
            self.assertEqual(2, len(run_report["measurements"]))
            self.assertIsNone(run_report["wallTime"]["totalMs"])

            (root / "load").mkdir()
            with mock.patch.object(benchmark, "run_command", return_value=timeout):
                load_report = benchmark.run_load_benchmarks(
                    root, rates=[1], evidence_modes=["metrics"], duration="1s", warmup="0s",
                    max_concurrent=1, max_samples=0, runs=1, warmups=0, timeout_seconds=0.1)
            condition = load_report["1TPS-metrics"]
            self.assertEqual(1, condition["timedOutSamples"])
            self.assertTrue(condition["measurements"][0]["timedOut"])
            self.assertEqual("process group/tree terminated",
                             condition["measurements"][0]["terminationReason"])


if __name__ == "__main__":
    unittest.main()
