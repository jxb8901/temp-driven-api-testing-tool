#!/usr/bin/env python3
"""Compare ATT launch and Debug startup for source and binary distributions."""

import argparse
import json
import math
import platform
import queue
import shutil
import statistics
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


MODULE_CLASSES = ("att-cli", "att-engine", "att-remote", "att-server-api")


class ReadyHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        payload = b"benchmark-ready"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, _format, *_args):
        pass


def launcher_command(root, arguments):
    if sys.platform == "win32":
        return ["cmd.exe", "/c", str(root / "att.bat")] + arguments
    return [str(root / "att.sh")] + arguments


def run_command(command, cwd):
    started = time.perf_counter_ns()
    process = subprocess.Popen(command, cwd=str(cwd), stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, bufsize=1)
    output = queue.Queue()

    def collect(name, stream):
        try:
            for line in iter(stream.readline, ""):
                if line.strip():
                    output.put((time.perf_counter_ns(), name, line.rstrip()))
        finally:
            stream.close()
            output.put((None, name, None))

    readers = [threading.Thread(target=collect, args=(name, stream), daemon=True)
               for name, stream in (("stdout", process.stdout), ("stderr", process.stderr))]
    for reader in readers:
        reader.start()

    first_output_ns = None
    finished_streams = set()
    tail = []
    while len(finished_streams) < 2:
        try:
            timestamp, name, line = output.get(timeout=0.05)
        except queue.Empty:
            continue
        if timestamp is None:
            finished_streams.add(name)
        else:
            if first_output_ns is None:
                first_output_ns = timestamp
            tail.append("{}: {}".format(name, line))
            del tail[:-8]

    exit_code = process.wait()
    finished = time.perf_counter_ns()
    for reader in readers:
        reader.join()
    return {
        "exitCode": exit_code,
        "firstOutputMs": None if first_output_ns is None else (first_output_ns - started) / 1e6,
        "totalMs": (finished - started) / 1e6,
        "outputTail": tail,
    }


def link_or_copy_classes(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    try:
        destination.symlink_to(source.resolve(), target_is_directory=True)
    except OSError:
        shutil.copytree(source, destination)


def copy_source_runtime(source, destination):
    copied = []
    for module in MODULE_CLASSES:
        classes = source / module / "target" / "classes"
        if classes.is_dir():
            target = destination / module / "target" / "classes"
            link_or_copy_classes(classes, target)
            copied.append(module)
    if not copied:
        classes = source / "target" / "classes"
        if classes.is_dir():
            target = destination / "target" / "classes"
            link_or_copy_classes(classes, target)
            copied.append("root")
    if not copied:
        raise RuntimeError("No prebuilt source classes found under {}".format(source))
    optional_lib = source / "lib"
    if optional_lib.is_dir():
        target_lib = destination / "lib"
        target_lib.mkdir()
        for jar in optional_lib.glob("*.jar"):
            if not jar.name.startswith("att-"):
                shutil.copy2(jar, target_lib / jar.name)


def install_runtime(source, destination, distribution, schemas_dir):
    launcher = "att.bat" if sys.platform == "win32" else "att.sh"
    source_launcher = source / launcher
    if not source_launcher.is_file():
        raise RuntimeError("Missing {} in distribution root {}".format(launcher, source))
    destination.mkdir(parents=True)
    shutil.copy2(source_launcher, destination / launcher)
    if sys.platform != "win32":
        (destination / launcher).chmod(0o755)
    shutil.copytree(schemas_dir, destination / "schemas")

    if distribution == "source":
        copy_source_runtime(source, destination)
    else:
        source_lib = source / "lib"
        if not source_lib.is_dir() or not list(source_lib.glob("att-*.jar")):
            raise RuntimeError("Binary distribution has no lib/att-*.jar in {}".format(source))
        shutil.copytree(source_lib, destination / "lib")


def write_yaml(path, contents):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(contents, encoding="utf-8")


def install_fixture(root, schemas_dir, http_port):
    shutil.copytree(schemas_dir, root / "schemas", dirs_exist_ok=True)
    (root / "config").mkdir(parents=True)
    (root / "templates" / "NOOP").mkdir(parents=True)
    (root / "templates" / "RESOURCE").mkdir(parents=True)
    (root / "templates" / "flows" / "common" / "benchmark").mkdir(parents=True)
    (root / "config" / "tools").mkdir(parents=True)
    (root / "config" / "httphelpers").mkdir(parents=True)
    (root / "testcase").mkdir()
    (root / "tools").mkdir()

    write_yaml(root / "config" / "config.yaml",
        "schemaVersion: att-config/v2.11\n"
        "outputDirectory: output\n"
        "environment: SIT\n"
        "templates: {root: templates}\n"
        "testcase: {root: testcase}\n"
        "toolGroups: [config/tools/benchmark.yaml]\n"
        "httphelpers: [config/httphelpers/benchmark.yaml]\n"
        "tools: {}\n")

    directory = root / "templates" / "NOOP"
    write_yaml(directory / "template.yaml",
        "schemaVersion: att-template/v3.4\nname: NOOP\n"
        "description: no-op startup benchmark\nactions:\n"
        "  ready: {type: log, message: ready}\n")
    write_yaml(directory / "debug.yaml", "schemaVersion: att-debug/v1.2\n")

    flow_directory = root / "templates" / "flows" / "common" / "benchmark"
    write_yaml(flow_directory / "flow.yaml",
        "schemaVersion: att-flow/v3.4\nid: common.benchmark.v1\n"
        "name: benchmark\ndescription: representative startup benchmark flow\nactions:\n"
        "  prepare: {type: log, message: flow prepare}\n"
        "  execute: {type: log, message: flow execute}\n"
        "  finish: {type: log, message: flow finish}\n")
    write_yaml(flow_directory / "debug.yaml", "schemaVersion: att-debug/v1.2\n")

    resource_directory = root / "templates" / "RESOURCE"
    write_yaml(resource_directory / "template.yaml",
        "schemaVersion: att-template/v3.4\nname: RESOURCE\n"
        "description: local HTTP-backed startup benchmark\nactions:\n"
        "  request: {type: tool, call: \"#{http.benchmark.get(path='/ready', responseFormat='text')}\"}\n")
    write_yaml(resource_directory / "debug.yaml", "schemaVersion: att-debug/v1.2\n")
    write_yaml(root / "config" / "httphelpers" / "benchmark.yaml",
        "schemaVersion: att-httphelper/v1.1\nid: benchmark\n"
        "name: Local benchmark endpoint\ndescription: local-only timing fixture\n"
        "baseUrl: http://127.0.0.1:{}\n".format(http_port))

    if sys.platform == "win32":
        tool_script = root / "tools" / "benchmark_tool.bat"
        tool_script.write_text("@echo off\necho benchmark-ready\n", encoding="utf-8")
        tool_command = ["cmd.exe", "/c", "tools/benchmark_tool.bat"]
    else:
        tool_script = root / "tools" / "benchmark_tool.sh"
        tool_script.write_text("#!/bin/sh\nprintf '%s\\n' benchmark-ready\n", encoding="utf-8")
        tool_script.chmod(0o755)
        tool_command = ["./tools/benchmark_tool.sh"]
    command = json.dumps(tool_command)
    write_yaml(root / "config" / "tools" / "benchmark.yaml",
        "schemaVersion: att-tool-group/v2.9\nid: benchmark\n"
        "name: Benchmark tools\ndescription: local-only timing fixture\ntools:\n"
        "  noop:\n    name: Benchmark Tool\n    description: local process startup fixture\n"
        "    command: {}\n    stdoutFormat: text\n    arguments: {{}}\n".format(command))
    write_yaml(root / "config" / "tools" / "benchmark.debug.yaml",
        "schemaVersion: att-debug/v1.2\n")


def summarize_metric(values):
    if not values:
        return None
    ordered = sorted(values)
    standard_deviation = statistics.stdev(values) if len(values) > 1 else 0.0
    mean = statistics.mean(values)
    return {
        "p50": round(statistics.median(values), 3),
        "p95": round(ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)], 3),
        "mean": round(mean, 3),
        "standardDeviation": round(standard_deviation, 3),
        "coefficientOfVariationPercent": round(standard_deviation * 100.0 / mean, 2) if mean else 0.0,
    }


def summarize(samples):
    first = [sample["firstOutputMs"] for sample in samples if sample["firstOutputMs"] is not None]
    total = [sample["totalMs"] for sample in samples]
    return {"samples": len(samples), "firstOutputMs": summarize_metric(first),
            "totalMs": summarize_metric(total)}


def measure(command, root, identity):
    result = run_command(command, root)
    if result["exitCode"] != 0:
        raise RuntimeError("Benchmark command failed (exit {}): {}\n{}".format(
            result["exitCode"], identity, "\n".join(result["outputTail"])))
    return {key: result[key] for key in ("exitCode", "firstOutputMs", "totalMs")}


def debug_command(root, target, identity, profile=False):
    args = ["debug"] + target + ["--debug-id", identity, "--output-dir", "output", "--format", "json"]
    if profile:
        args.append("--profile")
    return launcher_command(root, args)


def run_distribution(label, root, runs, warmups, expected_version):
    cases = {
        "version": ["version"],
        "help": ["help"],
        "noopTemplate": ["template", "NOOP"],
        "flow": ["flow", "common.benchmark.v1"],
        "tool": ["tool", "benchmark.noop"],
        "resourceHttp": ["template", "RESOURCE"],
    }
    report = {"version": None, "cases": {}}
    version_result = run_command(launcher_command(root, ["version"]), root)
    if version_result["exitCode"] != 0 or not version_result["outputTail"]:
        raise RuntimeError("Could not read version for {}: {}".format(label, version_result["outputTail"]))
    report["version"] = version_result["outputTail"][-1].split(": ", 1)[-1]
    if expected_version not in report["version"]:
        raise RuntimeError("{} reports {!r}, expected version label {!r}".format(
            label, report["version"], expected_version))

    for case_name, target in cases.items():
        samples = {"cold": [], "warm": []}
        for index in range(runs):
            if case_name in ("version", "help"):
                command = launcher_command(root, target)
            else:
                command = debug_command(root, target, "{}-cold-{}".format(case_name, index))
            samples["cold"].append(measure(command, root, "{} cold {} {}".format(label, case_name, index)))

        for index in range(warmups):
            if case_name in ("version", "help"):
                command = launcher_command(root, target)
            else:
                command = debug_command(root, target, "{}-warmup-{}".format(case_name, index))
            measure(command, root, "{} warmup {} {}".format(label, case_name, index))
        for index in range(runs):
            if case_name in ("version", "help"):
                command = launcher_command(root, target)
            else:
                command = debug_command(root, target, "{}-warm-{}".format(case_name, index))
            samples["warm"].append(measure(command, root, "{} warm {} {}".format(label, case_name, index)))

        report["cases"][case_name] = {
            "cold": {"summary": summarize(samples["cold"]), "raw": samples["cold"]},
            "warm": {"summary": summarize(samples["warm"]), "raw": samples["warm"]},
        }

        if label.startswith("candidate") and case_name not in ("version", "help"):
            identity = "{}-profile".format(case_name)
            profile_sample = measure(debug_command(root, target, identity, profile=True), root,
                                     "{} profile {}".format(label, case_name))
            profile_path = root / "output" / "debug" / identity / "performance.json"
            report["cases"][case_name]["profileCaptureWallMs"] = round(profile_sample["totalMs"], 3)
            report["cases"][case_name]["profile"] = json.loads(profile_path.read_text(encoding="utf-8"))
    return report


def compare_pair(baseline, candidate):
    comparisons = {}
    for case_name in baseline["cases"]:
        comparisons[case_name] = {}
        for condition in ("cold", "warm"):
            old = baseline["cases"][case_name][condition]["summary"]
            new = candidate["cases"][case_name][condition]["summary"]
            comparisons[case_name][condition] = {}
            for metric in ("firstOutputMs", "totalMs"):
                old_median = old[metric]["p50"]
                new_median = new[metric]["p50"]
                old_p95 = old[metric]["p95"]
                new_p95 = new[metric]["p95"]
                comparisons[case_name][condition][metric] = {
                    "medianImprovementPercent": round((old_median - new_median) * 100.0 / old_median, 2)
                    if old_median else None,
                    "p95ImprovementPercent": round((old_p95 - new_p95) * 100.0 / old_p95, 2)
                    if old_p95 else None,
                }
    return comparisons


def runtime_specs(args):
    return (
        ("baselineSource", args.baseline_source, "source"),
        ("baselineBinary", args.baseline_binary, "binary"),
        ("candidateSource", args.candidate_source, "source"),
        ("candidateBinary", args.candidate_binary, "binary"),
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-source", type=Path, required=True,
                        help="Built ATT 3.7.3 source checkout")
    parser.add_argument("--baseline-binary", type=Path, required=True,
                        help="Extracted ATT 3.7.3 binary distribution")
    parser.add_argument("--candidate-source", type=Path, required=True,
                        help="Built candidate source checkout")
    parser.add_argument("--candidate-binary", type=Path, required=True,
                        help="Extracted candidate binary distribution")
    parser.add_argument("--baseline-label", default="3.7.3")
    parser.add_argument("--candidate-label", default="4.0.1")
    parser.add_argument("--schemas-dir", type=Path, required=True,
                        help="One shared package schemas directory")
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--warmups", type=int, default=3)
    parser.add_argument("--output", type=Path, default=Path("debug-startup-benchmark.json"))
    args = parser.parse_args()
    if args.runs < 2 or args.warmups < 0:
        parser.error("--runs must be >= 2 and --warmups must be >= 0")
    schemas = args.schemas_dir.resolve()
    if not schemas.is_dir():
        parser.error("schemas directory does not exist: {}".format(schemas))

    http_server = ThreadingHTTPServer(("127.0.0.1", 0), ReadyHandler)
    http_thread = threading.Thread(target=http_server.serve_forever, daemon=True)
    http_thread.start()
    http_port = http_server.server_address[1]
    try:
        with tempfile.TemporaryDirectory(prefix="att-startup-benchmark-") as temporary:
            temp_root = Path(temporary)
            fixture_root = temp_root / "fixture"
            fixture_root.mkdir()
            install_fixture(fixture_root, schemas, http_port)
            measurements = {}
            for label, source, distribution in runtime_specs(args):
                install_root = temp_root / label
                install_runtime(source.resolve(), install_root, distribution, schemas)
                # Each launcher runs from its own temporary package. Copy the identical fixture
                # files in while retaining that distribution's prebuilt classes or release jars.
                for relative in ("config", "templates", "tools", "testcase"):
                    source_path = fixture_root / relative
                    if source_path.exists():
                        shutil.copytree(source_path, install_root / relative, dirs_exist_ok=True)
                expected = args.baseline_label if label.startswith("baseline") else args.candidate_label
                measurements[label] = run_distribution(label, install_root, args.runs, args.warmups, expected)
    finally:
        http_server.shutdown()
        http_server.server_close()
        http_thread.join(timeout=2)

    result = {
        "schemaVersion": "att-debug-startup-benchmark/v1",
        "baseline": {key: value for key, value in measurements.items() if key.startswith("baseline")},
        "candidate": {key: value for key, value in measurements.items() if key.startswith("candidate")},
        "comparison": {
            "source": compare_pair(measurements["baselineSource"], measurements["candidateSource"]),
            "binary": compare_pair(measurements["baselineBinary"], measurements["candidateBinary"]),
        },
        "environment": {
            "platform": platform.platform(),
            "machine": platform.machine(),
            "processor": platform.processor(),
            "python": platform.python_version(),
            "javaVersion": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.strip(),
            "runsPerCondition": args.runs,
            "warmups": args.warmups,
        },
        "measurement": {
            "cold": "New JVM processes without explicit benchmark warmups; the OS may cache files after the first sample.",
            "warm": "New JVM processes measured after the configured warmup invocations.",
            "firstOutputMs": "Launcher process spawn to the first non-empty stdout or stderr line.",
            "scope": "Includes shell launcher and JVM startup. Candidate profile captures are separate from comparison samples.",
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), "distributions": list(measurements),
                      "runsPerCondition": args.runs, "warmups": args.warmups}, indent=2))


if __name__ == "__main__":
    main()
