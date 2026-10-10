#!/usr/bin/env python3
"""Compare ATT launch and Debug startup for source and binary distributions."""

import argparse
import hashlib
import json
import math
import os
import platform
import queue
import random
import signal
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
        payload = b'{"status":"ready"}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, _format, *_args):
        pass


def launcher_command(root, arguments):
    if sys.platform == "win32":
        return ["cmd.exe", "/c", str(root / "att.bat")] + arguments
    return [str(root / "att.sh")] + arguments


def terminate_process_tree(process):
    if os.name == "nt":
        taskkill = shutil.which("taskkill")
        if taskkill:
            try:
                subprocess.run([taskkill, "/PID", str(process.pid), "/T", "/F"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                pass
        if process.poll() is None:
            process.kill()
    else:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            pass
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        if process.poll() is None:
            process.kill()


def run_command(command, cwd, timeout_seconds=120.0):
    started = time.perf_counter_ns()
    options = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
    process = subprocess.Popen(command, cwd=str(cwd), stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, text=True, bufsize=1, **options)
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
    deadline = time.monotonic() + timeout_seconds
    timed_out = False
    while len(finished_streams) < 2:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            timed_out = True
            break
        try:
            timestamp, name, line = output.get(timeout=min(0.05, remaining))
        except queue.Empty:
            continue
        if timestamp is None:
            finished_streams.add(name)
        else:
            if first_output_ns is None:
                first_output_ns = timestamp
            tail.append("{}: {}".format(name, line))
            del tail[:-8]

    if not timed_out:
        try:
            process.wait(timeout=max(0.001, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            timed_out = True
    if timed_out:
        terminate_process_tree(process)
    try:
        exit_code = process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        process.kill()
        exit_code = process.wait(timeout=2)
    finished = time.perf_counter_ns()
    for reader in readers:
        reader.join(timeout=2)
    while True:
        try:
            timestamp, name, line = output.get_nowait()
        except queue.Empty:
            break
        if timestamp is not None:
            if first_output_ns is None:
                first_output_ns = timestamp
            tail.append("{}: {}".format(name, line))
            del tail[:-8]
    result = {
        "exitCode": exit_code,
        "firstOutputMs": None if first_output_ns is None else (first_output_ns - started) / 1e6,
        "totalMs": (finished - started) / 1e6,
        "outputTail": tail,
        "timedOut": timed_out,
    }
    if timed_out:
        result["timeoutSeconds"] = timeout_seconds
        result["terminationReason"] = "per-sample timeout; process group/tree terminated"
    return result


def link_or_copy_classes(source, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    try:
        destination.symlink_to(source.resolve(), target_is_directory=True)
    except OSError:
        shutil.copytree(source, destination)


def copy_source_runtime(source, destination, packaged_distribution):
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
    source_tree = source / "src" / "main" / "java"
    if source_tree.is_dir():
        shutil.copytree(source_tree, destination / "src" / "main" / "java")

    # Source launchers need the same external dependency set as their matching
    # binary release. Keep ATT jars out so the source-built classes are used.
    source_lib = source / "lib"
    packaged_lib = packaged_distribution / "lib"
    dependencies_by_name = {}
    for dependency_lib in (packaged_lib, source_lib):
        if dependency_lib.is_dir():
            for jar in dependency_lib.glob("*.jar"):
                if not jar.name.startswith("att-"):
                    dependencies_by_name[jar.name] = jar
    dependencies = list(dependencies_by_name.values())
    if not dependencies:
        raise RuntimeError("No external dependency jars found in {} or {}".format(
            source_lib, packaged_lib))
    target_lib = destination / "lib"
    target_lib.mkdir()
    for jar in dependencies:
        shutil.copy2(jar, target_lib / jar.name)


def install_runtime(source, destination, distribution, schemas_dir, packaged_distribution):
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
        copy_source_runtime(source, destination, packaged_distribution)
    else:
        source_lib = source / "lib"
        if not source_lib.is_dir() or not list(source_lib.glob("att-*.jar")):
            raise RuntimeError("Binary distribution has no lib/att-*.jar in {}".format(source))
        shutil.copytree(source_lib, destination / "lib")


def write_yaml(path, contents):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(contents, encoding="utf-8")


def install_fixture(root, schemas_dir, http_port, baseline):
    shutil.copytree(schemas_dir, root / "schemas", dirs_exist_ok=True)
    config_version = "2.11" if baseline else "2.12"
    debug_version = "1.1" if baseline else "1.2"
    (root / "config").mkdir(parents=True)
    (root / "templates" / "NOOP").mkdir(parents=True)
    (root / "templates" / "RESOURCE").mkdir(parents=True)
    (root / "templates" / "flows" / "common" / "benchmark").mkdir(parents=True)
    (root / "config" / "tools").mkdir(parents=True)
    (root / "config" / "httphelpers").mkdir(parents=True)
    (root / "testcase").mkdir()
    (root / "tools").mkdir()

    write_yaml(root / "config" / "config.yaml",
        "schemaVersion: att-config/v{}\n".format(config_version) +
        "outputDirectory: output\n"
        "environment: SIT\n"
        "templates: {root: templates}\n"
        "testcase: {root: testcase}\n"
        "toolGroups: [config/tools/benchmark.yaml]\n"
        "httphelpers: [config/httphelpers/benchmark.yaml]\n"
        "tools: {}\n")

    directory = root / "templates" / "NOOP"
    write_yaml(directory / "template.yaml",
        "schemaVersion: att-template/v3.6\nname: NOOP\n"
        "description: no-op startup benchmark\nactions:\n"
        "  ready: {type: log, message: ready}\n")
    write_yaml(directory / "debug.yaml", "schemaVersion: att-debug/v{}\n".format(debug_version))

    flow_directory = root / "templates" / "flows" / "common" / "benchmark"
    write_yaml(flow_directory / "flow.yaml",
        "schemaVersion: att-flow/v3.6\nid: common.benchmark.v1\n"
        "name: benchmark\ndescription: representative startup benchmark flow\nactions:\n"
        "  prepare: {type: log, message: flow prepare}\n"
        "  execute: {type: log, message: flow execute}\n"
        "  finish: {type: log, message: flow finish}\n")
    write_yaml(flow_directory / "debug.yaml", "schemaVersion: att-debug/v{}\n".format(debug_version))

    resource_directory = root / "templates" / "RESOURCE"
    write_yaml(resource_directory / "template.yaml",
        "schemaVersion: att-template/v3.6\nname: RESOURCE\n"
        "description: local HTTP-backed startup benchmark\nactions:\n"
        "  request: {type: tool, call: \"#{http.benchmark.get(path='/ready')}\"}\n")
    write_yaml(resource_directory / "debug.yaml", "schemaVersion: att-debug/v{}\n".format(debug_version))
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
        "schemaVersion: att-debug/v{}\n".format(debug_version))


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
    successful = [sample for sample in samples if not sample.get("timedOut")]
    first = [sample["firstOutputMs"] for sample in successful if sample["firstOutputMs"] is not None]
    total = [sample["totalMs"] for sample in successful]
    return {"samples": len(samples), "successfulSamples": len(successful),
            "timedOutSamples": len(samples) - len(successful),
            "firstOutputMs": summarize_metric(first), "totalMs": summarize_metric(total)}


def directory_sha256(directory):
    digest = hashlib.sha256()
    for path in sorted(path for path in directory.rglob("*") if path.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode("utf-8"))
        digest.update(b"\0")
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()


def source_runtime_sha256(source):
    digest = hashlib.sha256()
    launcher = source / ("att.bat" if sys.platform == "win32" else "att.sh")
    files = [launcher] if launcher.is_file() else []
    class_dirs = [source / module / "target" / "classes" for module in MODULE_CLASSES]
    class_dirs = [directory for directory in class_dirs if directory.is_dir()]
    if not class_dirs and (source / "target" / "classes").is_dir():
        class_dirs = [source / "target" / "classes"]
    for directory in class_dirs:
        files.extend(path for path in directory.rglob("*") if path.is_file())
    source_lib = source / "lib"
    if source_lib.is_dir():
        files.extend(path for path in source_lib.glob("*.jar") if path.is_file())
    for path in sorted(set(files)):
        digest.update(path.relative_to(source).as_posix().encode("utf-8"))
        digest.update(b"\0")
        with path.open("rb") as source_file:
            for chunk in iter(lambda: source_file.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()


def source_revision(source, explicit_revision):
    if explicit_revision:
        return explicit_revision
    result = subprocess.run(["git", "-C", str(source), "rev-parse", "HEAD"],
                            capture_output=True, text=True)
    return result.stdout.strip() if result.returncode == 0 else "unknown"


def hardware_details():
    details = {"model": None, "processor": platform.processor(), "memory": None}
    if sys.platform != "darwin":
        return details
    result = subprocess.run(["system_profiler", "SPHardwareDataType"],
                            capture_output=True, text=True, timeout=10)
    if result.returncode != 0:
        return details
    values = {}
    for line in result.stdout.splitlines():
        if ":" in line:
            key, value = line.split(":", 1)
            values[key.strip()] = value.strip()
    model = values.get("Model Name")
    identifier = values.get("Model Identifier")
    details["model"] = "{} ({})".format(model, identifier) if model and identifier else model or identifier
    details["processor"] = values.get("Chip", details["processor"])
    details["memory"] = values.get("Memory")
    return details


def measure(command, root, identity, timeout_seconds):
    result = run_command(command, root, timeout_seconds)
    if result["timedOut"]:
        return {key: result[key] for key in ("exitCode", "firstOutputMs", "totalMs", "timedOut",
                                             "timeoutSeconds", "terminationReason", "outputTail")}
    if result["exitCode"] != 0:
        raise RuntimeError("Benchmark command failed (exit {}): {}\n{}".format(
            result["exitCode"], identity, "\n".join(result["outputTail"])))
    return {key: result[key] for key in ("exitCode", "firstOutputMs", "totalMs", "timedOut")}


def debug_command(root, target, identity, profile=False):
    # ATT 3.7.3 does not support --debug-id. Isolate each invocation with its
    # own output root so the same command works against both the baseline and
    # candidate while keeping generated evidence out of later samples.
    output_root = Path("output") / identity
    args = ["debug"] + target + ["--output-dir", str(output_root), "--format", "json"]
    if profile:
        args.append("--profile")
    return launcher_command(root, args)


def prepare_distribution(label, root, expected_version, timeout_seconds):
    version_result = run_command(launcher_command(root, ["version"]), root, timeout_seconds)
    if version_result["timedOut"] or version_result["exitCode"] != 0 or not version_result["outputTail"]:
        raise RuntimeError("Could not read version for {}: {}".format(label, version_result["outputTail"]))
    version = version_result["outputTail"][-1].split(": ", 1)[-1]
    if expected_version not in version:
        raise RuntimeError("{} reports {!r}, expected version label {!r}".format(label, version, expected_version))
    return {"version": version, "cases": {}}


def sample_command(label, root, case_name, target, condition, index):
    if case_name in ("version", "help"):
        return launcher_command(root, target)
    identity = "{}-{}-{}-{}".format(case_name, condition, index, label)
    return debug_command(root, target, identity)


def randomized_pair(labels, rng):
    order = list(labels)
    rng.shuffle(order)
    return order


def capture_candidate_profile(label, root, case_name, target, timeout_seconds, report):
    identity = "{}-profile".format(case_name)
    sample = measure(debug_command(root, target, identity, profile=True), root,
                     "{} profile {}".format(label, case_name), timeout_seconds)
    report["cases"][case_name]["profileCaptureWallMs"] = round(sample["totalMs"], 3)
    if sample.get("timedOut"):
        report["cases"][case_name]["profileCaptureTimedOut"] = True
        report["cases"][case_name]["profileCaptureTimeout"] = sample
        return
    profile_root = root / "output" / identity
    profile_paths = list(profile_root.rglob("performance.json"))
    if len(profile_paths) != 1:
        raise RuntimeError("Expected one profile under {}, found {}".format(profile_root, len(profile_paths)))
    report["cases"][case_name]["profile"] = json.loads(profile_paths[0].read_text(encoding="utf-8"))


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
                    if old_median is not None and new_median is not None and old_median else None,
                    "p95ImprovementPercent": round((old_p95 - new_p95) * 100.0 / old_p95, 2)
                    if old_p95 is not None and new_p95 is not None and old_p95 else None,
                }
    return comparisons


def runtime_specs(args):
    return (
        ("baselineSource", args.baseline_source, "source", args.baseline_binary),
        ("baselineBinary", args.baseline_binary, "binary", args.baseline_binary),
        ("candidateSource", args.candidate_source, "source", args.candidate_binary),
        ("candidateBinary", args.candidate_binary, "binary", args.candidate_binary),
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
    parser.add_argument("--baseline-revision", default=None)
    parser.add_argument("--candidate-revision", default=None)
    parser.add_argument("--schemas-dir", type=Path, required=True,
                        help="One shared package schemas directory")
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--warmups", type=int, default=3)
    parser.add_argument("--timeout-seconds", type=float, default=120.0,
                        help="Per-invocation deadline; timed-out samples are retained in the report")
    parser.add_argument("--order-seed", type=int, default=20261010,
                        help="Seed for paired baseline/candidate sample ordering")
    parser.add_argument("--output", type=Path, default=Path("debug-startup-benchmark.json"))
    args = parser.parse_args()
    if args.runs < 2 or args.warmups < 0:
        parser.error("--runs must be >= 2 and --warmups must be >= 0")
    if args.timeout_seconds <= 0:
        parser.error("--timeout-seconds must be positive")
    schemas = args.schemas_dir.resolve()
    if not schemas.is_dir():
        parser.error("schemas directory does not exist: {}".format(schemas))

    cases = {
        "version": ["version"],
        "help": ["help"],
        "noopTemplate": ["template", "NOOP"],
        "flow": ["flow", "common.benchmark.v1"],
        "tool": ["tool", "benchmark.noop"],
        "resourceHttp": ["template", "RESOURCE"],
    }
    specs = runtime_specs(args)
    rng = random.Random(args.order_seed)
    orders = []
    http_server = ThreadingHTTPServer(("127.0.0.1", 0), ReadyHandler)
    http_thread = threading.Thread(target=http_server.serve_forever, daemon=True)
    http_thread.start()
    http_port = http_server.server_address[1]
    try:
        with tempfile.TemporaryDirectory(prefix="att-startup-benchmark-") as temporary:
            temp_root = Path(temporary)
            measurements = {}
            roots = {}
            for label, source, distribution, packaged_distribution in specs:
                fixture_root = temp_root / (label + "-fixture")
                fixture_root.mkdir()
                install_fixture(fixture_root, schemas, http_port,
                                baseline=label.startswith("baseline"))
                install_root = temp_root / label
                install_runtime(source.resolve(), install_root, distribution, schemas,
                                packaged_distribution.resolve())
                # Each launcher runs from its own temporary package. Copy the identical fixture
                # files in while retaining that distribution's prebuilt classes or release jars.
                for relative in ("config", "templates", "tools", "testcase"):
                    source_path = fixture_root / relative
                    if source_path.exists():
                        shutil.copytree(source_path, install_root / relative, dirs_exist_ok=True)
                roots[label] = install_root
                measurements[label] = {"version": None, "cases": {}}

            preflight_order = randomized_pair([label for label, *_ in specs], rng)
            for label in preflight_order:
                expected = args.baseline_label if label.startswith("baseline") else args.candidate_label
                measurements[label]["version"] = prepare_distribution(
                    label, roots[label], expected, args.timeout_seconds)["version"]

            comparison_pairs = [("source", ("baselineSource", "candidateSource")),
                                ("binary", ("baselineBinary", "candidateBinary"))]
            for case_name, target in cases.items():
                for condition in ("cold", "warm"):
                    raw_by_label = {label: [] for label in measurements}
                    if condition == "warm":
                        for warmup_index in range(args.warmups):
                            group_order = randomized_pair(comparison_pairs, rng)
                            for comparison_name, pair in group_order:
                                pair_order = randomized_pair(pair, rng)
                                order_entry = {"case": case_name, "condition": condition,
                                               "phase": "warmup", "sampleIndex": warmup_index,
                                               "comparison": comparison_name, "order": pair_order}
                                outcomes = {}
                                for label in pair_order:
                                    sample = measure(sample_command(label, roots[label], case_name, target,
                                                                    condition, "warmup-{}".format(warmup_index)),
                                                     roots[label], "{} warmup {} {}".format(
                                                         label, case_name, warmup_index), args.timeout_seconds)
                                    outcomes[label] = {"timedOut": sample.get("timedOut", False),
                                                      "outputTail": sample.get("outputTail", [])}
                                order_entry["outcomes"] = outcomes
                                orders.append(order_entry)

                    for sample_index in range(args.runs):
                        group_order = randomized_pair(comparison_pairs, rng)
                        for comparison_name, pair in group_order:
                            pair_order = randomized_pair(pair, rng)
                            orders.append({"case": case_name, "condition": condition,
                                           "phase": "measured", "sampleIndex": sample_index,
                                           "comparison": comparison_name, "order": pair_order})
                            for label in pair_order:
                                sample = measure(sample_command(label, roots[label], case_name, target,
                                                                condition, sample_index), roots[label],
                                                 "{} {} {} {}".format(label, condition,
                                                                       case_name, sample_index),
                                                 args.timeout_seconds)
                                raw_by_label[label].append(sample)

                    for label, samples in raw_by_label.items():
                        measurements[label]["cases"].setdefault(case_name, {})[condition] = {
                            "summary": summarize(samples), "raw": samples,
                        }

            for case_name, target in cases.items():
                if case_name in ("version", "help"):
                    continue
                for label in ("candidateSource", "candidateBinary"):
                    capture_candidate_profile(label, roots[label], case_name, target,
                                               args.timeout_seconds, measurements[label])
    finally:
        http_server.shutdown()
        http_server.server_close()
        http_thread.join(timeout=2)

    hardware = hardware_details()
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
            "processor": hardware["processor"],
            "hardwareModel": hardware["model"],
            "logicalCpuCount": os.cpu_count(),
            "memory": hardware["memory"],
            "python": platform.python_version(),
            "javaVersion": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.strip(),
            "runsPerCondition": args.runs,
            "warmups": args.warmups,
            "timeoutSeconds": args.timeout_seconds,
            "orderSeed": args.order_seed,
        },
        "provenance": {
            "baselineRevision": source_revision(args.baseline_source, args.baseline_revision),
            "candidateRevision": source_revision(args.candidate_source, args.candidate_revision),
            "baselineSourceRuntimeSha256": source_runtime_sha256(args.baseline_source),
            "candidateSourceRuntimeSha256": source_runtime_sha256(args.candidate_source),
            "baselineBinaryTreeSha256": directory_sha256(args.baseline_binary),
            "candidateBinaryTreeSha256": directory_sha256(args.candidate_binary),
        },
        "measurement": {
            "fixtureSchemaVersions": {
                "baseline": {"config": "att-config/v2.11", "template": "att-template/v3.6",
                             "flow": "att-flow/v3.6", "debug": "att-debug/v1.1"},
                "candidate": {"config": "att-config/v2.12", "template": "att-template/v3.6",
                              "flow": "att-flow/v3.6", "debug": "att-debug/v1.2"},
            },
            "cold": "New JVM processes without explicit benchmark warmups; the OS may cache files after the first sample.",
            "warm": "New JVM processes measured after configured warmups; warmups and samples are paired and interleaved.",
            "orderSeed": args.order_seed,
            "pairedSampleOrder": orders,
            "timeouts": "Timed-out sample records include their deadline, termination reason, and captured output tail; summaries exclude timed-out samples.",
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
