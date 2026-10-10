import importlib.util
import pathlib
import random
import sys
import tempfile
import time
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("benchmark_debug_startup.py")
SPEC = importlib.util.spec_from_file_location("benchmark_debug_startup", MODULE_PATH)
benchmark = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)


class DebugStartupBenchmarkTest(unittest.TestCase):
    def test_paired_order_is_seeded_and_keeps_both_variants(self):
        labels = ("baselineSource", "candidateSource")
        first = benchmark.randomized_pair(labels, random.Random(17))
        second = benchmark.randomized_pair(labels, random.Random(17))
        self.assertEqual(first, second)
        self.assertEqual(set(labels), set(first))

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


if __name__ == "__main__":
    unittest.main()
