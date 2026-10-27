#!/usr/bin/env python3
"""Run the opt-in, 25-hour ATT Local LOAD acceptance against a local no-op Template."""

import argparse
import hashlib
import json
import math
import os
import platform
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from pathlib import Path

AGGREGATE_CHECKPOINT_SCOPE = "aggregate"
CHECKPOINT_SCOPE = "workload-local-soak"
CHECKPOINT_SCHEMA = "att-load-metrics-checkpoint/v2"
REQUIRED_DURATION_HOURS = 25
MIN_COMPLETED_TPS = 100.0
MIN_COMPLETIONS = 9_000_000
SOAK_SEED = 177
SOAK_TARGET_TYPE = "template"
SOAK_TARGET_ID = "LOAD_SOAK_NOOP"
SOAK_THRESHOLDS = {
    "overall": {
        "completedRate": ">= 100/s",
        "successfulRate": ">= 100/s",
        "droppedRate": "<= 0%",
    },
}
REPORT_FILES = {
    "load-summary.json",
    "load-summary.yaml",
    "report/index.html",
    "details/v1/manifest.json",
}


def workload_checkpoint_directory(workload_id):
    value = "default" if workload_id is None else str(workload_id)
    safe = "".join(character if character.isascii() and
                    (character.isalnum() or character in "._-") else "_" for character in value)
    return "workload-{}-{}".format(safe, hashlib.sha256(value.encode("utf-8")).hexdigest())


CHECKPOINT_DIRECTORY = workload_checkpoint_directory("local-soak")


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def checkpoint_integrity_envelope_bytes(source):
    data = source.encode("utf-8") if isinstance(source, str) else source
    marker = b',"integritySha256":"'
    marker_start = data.rfind(marker)
    if marker_start < 0:
        raise ValueError("checkpoint integrity field is absent")
    digest_start = marker_start + len(marker)
    if len(data) != digest_start + 66 or data[digest_start + 64:] != b'"}':
        raise ValueError("checkpoint integrity field is malformed or not the final envelope field")
    digest = data[digest_start:digest_start + 64]
    if any(character not in b"0123456789abcdef" for character in digest):
        raise ValueError("checkpoint integrity digest is malformed")
    return data[:marker_start] + b"}"


def verified_checkpoints(directory, run_id, scope=AGGREGATE_CHECKPOINT_SCOPE):
    verified = []
    for path in sorted(directory.glob("checkpoint-*.json"), reverse=True):
        try:
            source = path.read_text(encoding="utf-8")
            checkpoint = json.loads(source)
            payload = checkpoint["payload"]
            if checkpoint.get("schemaVersion") != CHECKPOINT_SCHEMA:
                continue
            if checkpoint.get("runId") != run_id or checkpoint.get("scope") != scope:
                continue
            sequence = int(checkpoint.get("sequence", 0))
            state = checkpoint.get("state")
            finalized = state == "FINAL_CHECKPOINT"
            if (sequence < 1 or path.name != "checkpoint-{:020d}.json".format(sequence)
                    or state not in ("CHECKPOINT", "FINAL_CHECKPOINT")
                    or checkpoint.get("finalized", False) is not finalized):
                continue
            digest = hashlib.sha256(checkpoint_integrity_envelope_bytes(source)).hexdigest()
            if digest != checkpoint.get("integritySha256"):
                continue
            verified.append(checkpoint)
        except (OSError, ValueError, KeyError, TypeError):
            continue
    return sorted(verified, key=lambda checkpoint: int(checkpoint["sequence"]))


def latest_checkpoint(directory, run_id, scope=AGGREGATE_CHECKPOINT_SCOPE):
    checkpoints = verified_checkpoints(directory, run_id, scope)
    return checkpoints[-1] if checkpoints else None


def java_string(java_home):
    java = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java") if java_home else Path(shutil.which("java") or "java")
    try:
        result = subprocess.run([str(java), "-version"], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
        return result.stdout.splitlines()[0] if result.stdout else "unavailable"
    except Exception:
        # Runtime identification is best-effort and must not prevent truthful
        # failure/checkpoint evidence from being retained.
        return "unavailable"


def total_memory_bytes():
    try:
        for line in Path("/proc/meminfo").read_text(encoding="ascii").splitlines():
            if line.startswith("MemTotal:"):
                return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        pass
    try:
        page_size = int(os.sysconf("SC_PAGE_SIZE"))
        page_count = int(os.sysconf("SC_PHYS_PAGES"))
        if page_size > 0 and page_count > 0:
            return page_size * page_count
    except (AttributeError, OSError, ValueError):
        pass
    return None


def parameter_set(arrival_rate, duration_hours, max_concurrent):
    return {
        "executionPath": "CLI Local",
        "scenarioFile": "load-soak.yaml",
        "model": "ARRIVAL_RATE",
        "target": {"type": SOAK_TARGET_TYPE, "id": SOAK_TARGET_ID},
        "seed": SOAK_SEED,
        "arrivalRatePerSecond": arrival_rate,
        "warmupSeconds": 0,
        "rampUpSeconds": 0,
        "durationHours": duration_hours,
        "rampDownSeconds": 0,
        "maxConcurrent": max_concurrent,
        "overloadPolicy": "drop",
        "thresholds": SOAK_THRESHOLDS,
        "evidenceMode": "metrics",
        "expectedScheduledArrivals": expected_scheduled_arrivals(arrival_rate, duration_hours),
    }


def runtime_metadata(environment):
    machine = platform.machine()
    return {
        "platform": "{} {} {}".format(platform.system(), platform.release(), platform.version()),
        "processor": os.environ.get("PROCESSOR_IDENTIFIER") or machine,
        "architecture": machine,
        "cpuCount": os.cpu_count(),
        "memoryBytes": total_memory_bytes(),
        "java": java_string(environment.get("JAVA_HOME")),
    }


def process_resource_sample(pid):
    """Read bounded OS counters for the ATT JVM process without opening /proc files for output."""
    proc = Path("/proc") / str(pid)
    if proc.is_dir():
        values = {"pid": int(pid), "supported": True}
        try:
            for line in (proc / "status").read_text(encoding="ascii", errors="replace").splitlines():
                if line.startswith("VmRSS:"):
                    values["residentBytes"] = int(line.split()[1]) * 1024
                elif line.startswith("Threads:"):
                    values["threads"] = int(line.split()[1])
            values["openFileDescriptors"] = sum(1 for _ in (proc / "fd").iterdir())
            return values
        except (OSError, ValueError, IndexError):
            return {"pid": int(pid), "supported": False, "reason": "process counters became unavailable"}
    if platform.system() == "Darwin":
        try:
            rss = subprocess.check_output(["ps", "-o", "rss=", "-p", str(pid)],
                                          text=True, stderr=subprocess.DEVNULL).strip().split()
            threads = subprocess.check_output(["ps", "-M", "-p", str(pid)],
                                              text=True, stderr=subprocess.DEVNULL).splitlines()
            values = {"pid": int(pid), "supported": bool(rss and len(threads) > 1)}
            if rss:
                values["residentBytes"] = int(rss[0]) * 1024
            if len(threads) > 1:
                # macOS ps -M emits a header followed by one row for each thread.
                values["threads"] = len(threads) - 1
            descriptors = subprocess.check_output(["lsof", "-n", "-p", str(pid)],
                                                   text=True, stderr=subprocess.DEVNULL)
            values["openFileDescriptors"] = max(0, len(descriptors.splitlines()) - 1)
            return values
        except (OSError, subprocess.CalledProcessError, ValueError):
            pass
    return {"pid": int(pid), "supported": False, "reason": "OS process counters are unavailable on this host"}


def summarize_resource_series(rows, section, keys):
    samples = []
    for row in rows:
        values = row.get(section, {}) if section else row
        if isinstance(values, dict):
            samples.append((row, values))
    result = {"sampleCount": len(samples)}
    for key in keys:
        available = [(row, values[key]) for row, values in samples if isinstance(values.get(key), (int, float))]
        if not available:
            result[key] = {"available": False}
            continue
        first_row, first = available[0]
        last_row, last = available[-1]
        first_elapsed = float(first_row.get("elapsedSeconds", 0.0))
        last_elapsed = float(last_row.get("elapsedSeconds", first_elapsed))
        span_hours = max(0.0, (last_elapsed - first_elapsed) / 3600.0)
        result[key] = {
            "available": True,
            "first": first,
            "last": last,
            "maximum": max(value for _, value in available),
            "slopePerHour": ((last - first) / span_hours) if span_hours > 0.0 else None,
        }
    return result


def assess_resource_evidence(process_rows, jvm_rows, minimum_span_seconds):
    """Require sufficiently dense OS and JVM measurements across the qualified soak window."""
    requirements = (
        ("process", process_rows, "", ("residentBytes", "threads", "openFileDescriptors"), 60.0),
        ("jvm", jvm_rows, "generator", ("heapUsedBytes", "heapCommittedBytes", "liveThreads",
                                        "gcCollectionCount", "gcCollectionTimeMs"), 300.0),
    )
    assessment = {"status": "CAPTURED", "minimumSpanSeconds": minimum_span_seconds}
    for name, rows, section, keys, interval_seconds in requirements:
        if len(rows) < 2:
            raise RuntimeError("{} resource evidence has fewer than two samples".format(name))
        elapsed = [float(row.get("elapsedSeconds", 0.0)) for row in rows]
        span = max(elapsed) - min(elapsed)
        if span < minimum_span_seconds * 0.9:
            raise RuntimeError("{} resource evidence covers only {:.2f} hours of a {:.2f}-hour qualified window".format(
                name, span / 3600.0, minimum_span_seconds / 3600.0))
        expected_samples = max(2, int(span / interval_seconds * 0.8))
        if len(rows) < expected_samples:
            raise RuntimeError("{} resource evidence has {} samples; at least {} are required".format(
                name, len(rows), expected_samples))
        series = summarize_resource_series(rows, section, keys)
        series_results = {}
        for key in keys:
            available_count = 0
            for row in rows:
                values = row.get(section, {}) if section else row
                if isinstance(values, dict) and isinstance(values.get(key), (int, float)):
                    available_count += 1
            coverage = available_count / float(len(rows))
            series_results[key] = {"coverage": coverage, "summary": series[key]}
            if coverage < 0.95:
                raise RuntimeError("{} resource series {} covers only {:.1%} of samples".format(name, key, coverage))
        assessment[name] = {
            "sampleCount": len(rows),
            "spanSeconds": span,
            "sampleIntervalSeconds": interval_seconds,
            "series": series_results,
        }
    pids = {row.get("pid") for row in process_rows if isinstance(row.get("pid"), int)}
    if len(pids) != 1:
        raise RuntimeError("process resource evidence does not identify one stable ATT JVM process")
    assessment["process"]["processIdCount"] = len(pids)
    endpoint_window_seconds = min(1800.0, minimum_span_seconds * 0.1)
    growth = {}
    for name, rows, section, key, tolerance in (
        ("openFileDescriptors", process_rows, "", "openFileDescriptors", 2.0),
        ("residentBytes", process_rows, "", "residentBytes", None),
        ("threads", process_rows, "", "threads", None),
        ("heapUsedBytes", jvm_rows, "generator", "heapUsedBytes", None),
    ):
        ordered = sorted(rows, key=lambda row: float(row.get("elapsedSeconds", 0.0)))
        first_time = float(ordered[0].get("elapsedSeconds", 0.0))
        last_time = float(ordered[-1].get("elapsedSeconds", first_time))
        start_values = []
        end_values = []
        for row in ordered:
            values = row.get(section, {}) if section else row
            value = values.get(key) if isinstance(values, dict) else None
            if not isinstance(value, (int, float)) or value < 0:
                continue
            elapsed = float(row.get("elapsedSeconds", 0.0))
            if elapsed <= first_time + endpoint_window_seconds:
                start_values.append(float(value))
            if elapsed >= last_time - endpoint_window_seconds:
                end_values.append(float(value))
        if not start_values or not end_values:
            raise RuntimeError("{} resource growth could not be assessed from endpoint samples".format(name))
        baseline = statistics.median(start_values)
        ending = statistics.median(end_values)
        if tolerance is None and name == "threads":
            tolerance = max(2.0, baseline * 0.05)
        elif tolerance is None and name == "residentBytes":
            tolerance = max(64.0 * 1024.0 * 1024.0,
                            min(256.0 * 1024.0 * 1024.0, baseline * 0.10))
        elif tolerance is None:
            heap_limits = [row.get("generator", {}).get("heapMaxBytes") for row in jvm_rows
                           if isinstance(row.get("generator"), dict)]
            heap_limit = max([float(value) for value in heap_limits if isinstance(value, (int, float)) and value > 0]
                             or [0.0])
            tolerance = min(64.0 * 1024.0 * 1024.0, max(16.0 * 1024.0 * 1024.0, heap_limit * 0.01))
        increase = ending - baseline
        growth[name] = {
            "baselineEndpointMedian": baseline,
            "endingEndpointMedian": ending,
            "increase": increase,
            "tolerance": tolerance,
            "status": "STABLE_WITHIN_TOLERANCE" if increase <= tolerance else "INCREASING",
        }
        if increase > tolerance:
            raise RuntimeError("{} increased by {:.0f}, beyond the {:.0f} resource-growth tolerance".format(
                name, increase, tolerance))
    assessment["endpointWindowSeconds"] = endpoint_window_seconds
    assessment["growth"] = growth
    return assessment


def capture_incomplete_evidence(evidence_root, run_directory, checkpoint_dir, run_id,
                                commit_sha, elapsed_seconds, reason, exit_code=None,
                                parameters=None, runtime=None):
    target_run = evidence_root / "load" / run_id
    if run_directory.is_dir():
        target_run.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(run_directory, target_run, dirs_exist_ok=True)
    checkpoint = latest_checkpoint(checkpoint_dir, run_id)
    counters = ("scheduled", "started", "completed", "success", "failure", "runtimeError", "otherOutcome", "dropped",
                "currentInFlight", "measuredCompleted", "measuredCompletedThroughput")
    values = read_checkpoint_metrics(checkpoint) if checkpoint else {}
    partial_metrics = {key: values.get(key) for key in counters if key in values}
    if "generator" in values:
        partial_metrics["generator"] = values["generator"]
    result = "PARTIAL" if checkpoint else "REPORT_UNAVAILABLE"
    reason_text = str(reason).strip()
    if not reason_text:
        reason_text = "runner interrupted" if isinstance(reason, KeyboardInterrupt) else type(reason).__name__
    acceptance = {
        "schemaVersion": "att-load-25h-soak/v1",
        "runId": run_id,
        "commitSha": commit_sha,
        "result": result,
        "complete": False,
        "elapsedSeconds": max(0.0, elapsed_seconds),
        "reason": reason_text[:2000],
        "metadataFile": "run-metadata.json",
        "checkpointDirectory": "load/{}/checkpoints/{}".format(run_id, AGGREGATE_CHECKPOINT_SCOPE),
    }
    if parameters is not None:
        acceptance["parameterSet"] = parameters
    if runtime is not None:
        acceptance["runtime"] = runtime
    if exit_code is not None:
        acceptance["exitCode"] = exit_code
    if checkpoint:
        acceptance["latestCheckpoint"] = {
            "sequence": checkpoint.get("sequence"),
            "capturedAt": checkpoint.get("capturedAt"),
            "finalized": bool(checkpoint.get("finalized", False)),
            "metrics": partial_metrics,
        }
    atomic_json(evidence_root / "acceptance.json", acceptance)
    return acceptance


def install_soak_package(repository, runtime_root, package_root, arrival_rate, duration_hours, max_concurrent):
    launcher = runtime_root / ("att.bat" if os.name == "nt" else "att.sh")
    if not launcher.is_file() or not (runtime_root / "lib").is_dir():
        raise RuntimeError("runtime-root must be an extracted ATT Local distribution")
    install_root = package_root.parent.parent
    shutil.copy2(launcher, install_root / launcher.name)
    if os.name != "nt":
        (install_root / launcher.name).chmod(0o755)
    shutil.copytree(runtime_root / "lib", install_root / "lib")
    shutil.copytree(repository / "schemas", package_root / "schemas")
    (package_root / "package.yaml").write_text(
        "schemaVersion: att-package/v1\n"
        "metadata:\n"
        "  name: ATT LOAD Soak\n"
        "  description: Deterministic local generator soak package\n"
        "resources:\n"
        "  templatesRoot: templates\n"
        "  testcaseRoot: testcase\n", encoding="utf-8")
    (install_root / "config").mkdir(parents=True, exist_ok=True)
    (package_root / "testcase").mkdir(parents=True, exist_ok=True)
    (package_root / "testdata").mkdir(parents=True, exist_ok=True)
    (package_root / "templates" / "LOAD_SOAK_NOOP").mkdir(parents=True, exist_ok=True)
    (install_root / "config" / "config.yaml").write_text(
        "schemaVersion: att-config/v2.13\n"
        "outputDirectory: output\n"
        "environment: SOAK\n"
        "testcase: {root: testcase}\n"
        "templates: {root: templates}\n", encoding="utf-8")
    (package_root / "templates" / "LOAD_SOAK_NOOP" / "template.yaml").write_text(
        "schemaVersion: att-template/v3.6\n"
        "name: LOAD_SOAK_NOOP\n"
        "description: Deterministic local no-op target for ATT generator acceptance\n"
        "actions:\n"
        "  record:\n"
        "    type: assign\n"
        "    name: soakIteration\n"
        "    expression: synthetic\n", encoding="utf-8")
    scenario = (
        "schemaVersion: att-load/v1.9\n"
        "seed: {}\n"
        "workloads:\n"
        "  - id: local-soak\n"
        "    target: {{type: {}, id: {}}}\n"
        "    load:\n"
        "      arrivalRate: {}/s\n"
        "      warmup: 0s\n"
        "      rampUp: 0s\n"
        "      duration: {:d}h\n"
        "      rampDown: 0s\n"
        "      maxConcurrent: {}\n"
        "      overloadPolicy: drop\n"
        "thresholds:\n"
        "  overall:\n"
        "    completedRate: \">= 100/s\"\n"
        "    successfulRate: \">= 100/s\"\n"
        "    droppedRate: \"<= 0%\"\n"
        "evidence:\n"
        "  mode: metrics\n"
    ).format(SOAK_SEED, SOAK_TARGET_TYPE, SOAK_TARGET_ID, arrival_rate, duration_hours, max_concurrent)
    scenario_path = package_root / "load-soak.yaml"
    scenario_path.write_text(scenario, encoding="utf-8")
    return scenario_path, install_root / launcher.name


def read_checkpoint_metrics(checkpoint):
    return checkpoint.get("payload", {}).get("metrics", {})


def delta_row(previous, current):
    previous_metrics = read_checkpoint_metrics(previous)
    current_metrics = read_checkpoint_metrics(current)
    elapsed = (datetime.fromisoformat(current["capturedAt"].replace("Z", "+00:00"))
               - datetime.fromisoformat(previous["capturedAt"].replace("Z", "+00:00"))).total_seconds()
    if elapsed <= 0:
        raise RuntimeError("checkpoint timestamps did not advance")
    counters = ("scheduled", "started", "completed", "success", "failure", "runtimeError", "otherOutcome", "dropped")
    deltas = {key: int(current_metrics.get(key, 0)) - int(previous_metrics.get(key, 0)) for key in counters}
    if any(value < 0 for value in deltas.values()):
        raise RuntimeError("checkpoint counters moved backwards")
    configured = float(current_metrics.get("configuredArrivalRatePerSecond", 0.0))
    return {
        "checkpointSequence": current.get("sequence"),
        "start": previous.get("capturedAt"),
        "end": current.get("capturedAt"),
        "durationSeconds": elapsed,
        "configuredArrivalRatePerSecond": configured,
        "scheduledTPS": deltas["scheduled"] / elapsed,
        "startedTPS": deltas["started"] / elapsed,
        "completedTPS": deltas["completed"] / elapsed,
        "successfulTPS": deltas["success"] / elapsed,
        "failedCompletions": deltas["failure"] + deltas["runtimeError"],
        "otherOutcomes": deltas["otherOutcome"],
        "dropped": deltas["dropped"],
        "counts": deltas,
    }


def expected_scheduled_arrivals(arrival_rate, duration_hours):
    """The soak scenario has one steady phase and no warmup or ramps."""
    if arrival_rate <= 0 or duration_hours <= 0:
        raise ValueError("arrival rate and duration must be positive")
    return int(arrival_rate) * int(duration_hours) * 60 * 60


def verify_scheduled_arrivals(metrics, arrival_rate, duration_hours):
    expected_rate = float(arrival_rate)
    configured_rate = metrics.get("configuredArrivalRatePerSecond")
    if not isinstance(configured_rate, (int, float)) or configured_rate != expected_rate:
        raise RuntimeError("LOAD report configured arrival rate does not match the soak scenario")
    expected = expected_scheduled_arrivals(arrival_rate, duration_hours)
    scheduled = metrics.get("scheduled")
    if not isinstance(scheduled, int) or scheduled != expected:
        raise RuntimeError("scheduled arrivals {} did not match the configured scenario total {}".format(
            scheduled, expected))
    return expected


def verify_report_bundle(run_directory):
    manifest_path = run_directory / "load-report-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != "att-load-report-manifest/v1" or manifest.get("state") != "COMPLETE" or manifest.get("complete") is not True:
        raise RuntimeError("LOAD report commit manifest is absent or incomplete")
    if manifest.get("runId") != run_directory.name:
        raise RuntimeError("LOAD report commit manifest belongs to a different run")
    rows = manifest.get("files")
    if not isinstance(rows, list) or not rows:
        raise RuntimeError("LOAD report commit manifest has no file inventory")
    listed = set()
    total_bytes = 0
    for row in rows:
        relative = row.get("path")
        if (not isinstance(relative, str) or not relative or relative.startswith("/")
                or "\\" in relative or ":" in relative
                or any(part in ("", ".", "..") for part in relative.split("/"))):
            raise RuntimeError("unsafe path in report commit manifest")
        target = run_directory
        for part in relative.split("/"):
            target = target / part
            if target.is_symlink():
                raise RuntimeError("report bundle contains a symbolic link: " + relative)
        resolved_root = run_directory.resolve()
        resolved_target = target.resolve()
        try:
            resolved_target.relative_to(resolved_root)
        except ValueError:
            raise RuntimeError("report file escaped the run directory")
        if relative in listed or not target.is_file():
            raise RuntimeError("report commit manifest contains a duplicate or missing path")
        data = target.read_bytes()
        length = row.get("lengthBytes")
        digest = row.get("sha256")
        if not isinstance(length, int) or length < 0 or length > 16 * 1024 * 1024:
            raise RuntimeError("report commit manifest contains an invalid file size")
        if total_bytes > 128 * 1024 * 1024 - length:
            raise RuntimeError("report bundle exceeds its bounded size")
        total_bytes += length
        if len(data) != length or hashlib.sha256(data).hexdigest() != digest:
            raise RuntimeError("report bundle checksum mismatch: " + relative)
        listed.add(relative)
    if not REPORT_FILES.issubset(listed):
        raise RuntimeError("report bundle is missing one or more required files")
    summary = json.loads((run_directory / "load-summary.json").read_text(encoding="utf-8"))
    if (summary.get("runId") != manifest.get("runId")
            or summary.get("durationMs") != manifest.get("durationMs")
            or summary.get("executionStatus") != manifest.get("executionStatus")
            or summary.get("performanceVerdict") != manifest.get("performanceVerdict")
            or summary.get("metrics", {}).get("accounting", {}).get("state") != manifest.get("accountingStatus")
            or summary.get("report") != "report/index.html"
            or summary.get("reportManifest") != "load-report-manifest.json"):
        raise RuntimeError("LOAD summary does not match its committed report bundle")
    return manifest


def verify_soak_performance_verdict(manifest, summary):
    if manifest.get("executionStatus") != "PASS":
        raise RuntimeError("the completed LOAD report does not have PASS execution status")
    if manifest.get("performanceVerdict") != "MET":
        raise RuntimeError("the completed LOAD report did not evaluate its performance thresholds as MET")
    threshold_summary = summary.get("thresholds")
    if not isinstance(threshold_summary, dict) or not isinstance(threshold_summary.get("results"), list):
        raise RuntimeError("the LOAD report does not contain evaluated threshold results")
    if threshold_summary.get("status") != "MET" or threshold_summary.get("performanceVerdict") != "MET":
        raise RuntimeError("the LOAD report threshold summary did not reach MET")
    threshold_rows = threshold_summary["results"]
    required_thresholds = {"overall.completedRate", "overall.successfulRate", "overall.droppedRate"}
    threshold_results = {row.get("name"): row for row in threshold_rows if isinstance(row, dict)}
    passed = {name for name, row in threshold_results.items() if row.get("status") == "PASS"}
    missing = required_thresholds - passed
    if missing:
        raise RuntimeError("the LOAD report is missing passing soak thresholds: {}".format(", ".join(sorted(missing))))
    metrics = summary.get("metrics")
    if not isinstance(metrics, dict):
        raise RuntimeError("the LOAD report does not contain metrics")
    window_metrics = metrics.get("windowMetrics")
    windows = window_metrics.get("windows", {}) if isinstance(window_metrics, dict) else {}
    overall = windows.get("all", {}) if isinstance(windows, dict) else {}
    if not isinstance(overall, dict):
        raise RuntimeError("the LOAD report does not contain overall window metrics")
    for metric in ("completedRate", "successfulRate"):
        value = overall.get(metric)
        if not isinstance(value, (int, float)) or not math.isfinite(value) or value < MIN_COMPLETED_TPS:
            raise RuntimeError("the overall LOAD report {} is below {:.0f}/s or unavailable".format(
                metric, MIN_COMPLETED_TPS))
        actual = threshold_results["overall." + metric].get("actual")
        if not isinstance(actual, str) or not actual.endswith("/s"):
            raise RuntimeError("the LOAD report threshold actual is invalid for {}".format(metric))
        try:
            threshold_rate = float(actual[:-2])
        except ValueError:
            raise RuntimeError("the LOAD report threshold actual is invalid for {}".format(metric))
        if not math.isfinite(threshold_rate) or threshold_rate < MIN_COMPLETED_TPS \
                or abs(threshold_rate - float(value)) > 0.00051:
            raise RuntimeError("the LOAD report threshold result does not match overall {}".format(metric))
    if overall.get("droppedRate") != 0:
        raise RuntimeError("the overall LOAD report contains dropped arrivals")
    dropped_actual = threshold_results["overall.droppedRate"].get("actual")
    if dropped_actual != "0.000%":
        raise RuntimeError("the LOAD report dropped-rate threshold did not record zero drops")
    return manifest["performanceVerdict"]


def verify_final_checkpoint(directory, run_id, scope, expected_sequence):
    checkpoint = latest_checkpoint(directory, run_id, scope)
    if checkpoint is None or checkpoint.get("state") != "FINAL_CHECKPOINT":
        raise RuntimeError("the final {} metrics checkpoint is absent or invalid".format(scope))
    if not checkpoint.get("finalized") or int(checkpoint.get("sequence", 0)) != int(expected_sequence):
        raise RuntimeError("the final {} checkpoint does not match its committed report".format(scope))
    return checkpoint


def run_soak(args):
    repository = args.repository_root.resolve()
    runtime_root = args.runtime_root.resolve()
    evidence_root = args.output.resolve()
    evidence_root.mkdir(parents=True, exist_ok=True)
    run_id = args.run_id or "load-soak-{}".format(datetime.now().strftime("%Y%m%d%H%M%S%f"))
    if (evidence_root / "load" / run_id).exists():
        raise RuntimeError("evidence already exists for run ID {}".format(run_id))
    try:
        previous_acceptance = json.loads((evidence_root / "acceptance.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        previous_acceptance = {}
    if previous_acceptance.get("runId") == run_id and previous_acceptance.get("result") == "PASS":
        raise RuntimeError("acceptance evidence already exists for run ID {}".format(run_id))
    args.run_id = run_id
    parameters = parameter_set(args.arrival_rate, args.duration_hours, args.max_concurrent)
    environment = os.environ.copy()
    runtime = runtime_metadata(environment)
    metadata = {
        "schemaVersion": "att-load-25h-soak/v1",
        "runId": run_id,
        "commitSha": args.commit_sha,
        "parameterSet": parameters,
        "runtime": runtime,
    }
    atomic_json(evidence_root / "run-metadata.json", metadata)
    atomic_json(evidence_root / "acceptance.json", {
        "schemaVersion": "att-load-25h-soak/v1",
        "runId": run_id,
        "commitSha": args.commit_sha,
        "result": "RUNNING",
        "metadataFile": "run-metadata.json",
        "parameterSet": parameters,
        "runtime": runtime,
    })
    if args.duration_hours < REQUIRED_DURATION_HOURS:
        raise RuntimeError("the release gate requires at least 25 measured hours")
    if args.arrival_rate <= MIN_COMPLETED_TPS:
        raise RuntimeError("configured arrivals must exceed 100/s to provide generator headroom")
    with tempfile.TemporaryDirectory(prefix="att-load-soak-") as temp:
        temp_root = Path(temp)
        package_root = temp_root / "runtime" / "packages" / "default"
        package_root.mkdir(parents=True)
        scenario, launcher = install_soak_package(repository, runtime_root, package_root,
                                                   args.arrival_rate, args.duration_hours, args.max_concurrent)
        checkpoint_dir = package_root / "output" / "load" / run_id / "checkpoints" / AGGREGATE_CHECKPOINT_SCOPE
        logs = evidence_root / "logs"
        logs.mkdir(parents=True, exist_ok=True)
        stdout_log = logs / "att.stdout.log"
        stderr_log = logs / "att.stderr.log"
        command = [str(launcher), "load", scenario.name, "--run-id", run_id,
                   "--output-dir", "output", "--format", "json", "--quiet"]
        if os.name == "nt":
            command = [os.environ.get("COMSPEC", "cmd.exe"), "/c"] + command
        environment["ATT_HOME"] = str(package_root.parent.parent)
        environment["ATT_PACKAGE_ROOT"] = str(package_root)
        started_wall = datetime.now().astimezone().isoformat()
        started_monotonic = time.monotonic()
        options = {"start_new_session": True} if os.name != "nt" else {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
        with stdout_log.open("w", encoding="utf-8") as stdout, stderr_log.open("w", encoding="utf-8") as stderr:
            process = subprocess.Popen(command, cwd=str(package_root), env=environment,
                                       stdout=stdout, stderr=stderr, **options)
            previous = None
            last_sequence = 0
            trend = []
            jvm_resource_samples = []
            process_resource_samples = []
            last_progress = time.monotonic()
            last_os_sample = 0.0

            def consume_checkpoints():
                nonlocal previous, last_sequence, last_progress
                for checkpoint in verified_checkpoints(checkpoint_dir, run_id, AGGREGATE_CHECKPOINT_SCOPE):
                    sequence = int(checkpoint.get("sequence", 0))
                    if sequence <= last_sequence:
                        continue
                    if previous is None and sequence != 1:
                        raise RuntimeError("the first verified checkpoint is missing")
                    if previous and sequence != int(previous["sequence"]) + 1:
                        raise RuntimeError("checkpoint sequence gap prevents complete five-minute trend evidence")
                    if previous:
                        trend.append(delta_row(previous, checkpoint))
                        atomic_json(evidence_root / "five-minute-trend.json", trend)
                    previous = checkpoint
                    last_sequence = sequence
                    last_progress = time.monotonic()
                    metrics = read_checkpoint_metrics(checkpoint)
                    jvm_resource_samples.append({
                        "checkpointSequence": sequence,
                        "capturedAt": checkpoint.get("capturedAt"),
                        "elapsedSeconds": last_progress - started_monotonic,
                        "generator": metrics.get("generator", {}),
                    })
                    atomic_json(evidence_root / "jvm-resource-trend.json", jvm_resource_samples)

            try:
                while process.poll() is None:
                    consume_checkpoints()
                    now = time.monotonic()
                    if now - last_os_sample >= 60.0:
                        sample = process_resource_sample(process.pid)
                        sample["sampledAt"] = datetime.now().astimezone().isoformat()
                        sample["elapsedSeconds"] = now - started_monotonic
                        process_resource_samples.append(sample)
                        atomic_json(evidence_root / "process-resource-samples.json", process_resource_samples)
                        last_os_sample = now
                    if time.monotonic() - last_progress > 600:
                        raise RuntimeError("no new verified LOAD checkpoint arrived for ten minutes")
                    time.sleep(2.0)
                exit_code = process.wait()
                consume_checkpoints()
            except BaseException as failure:
                if process.poll() is None:
                    if os.name == "nt":
                        process.kill()
                    else:
                        os.killpg(process.pid, signal.SIGTERM)
                        try:
                            process.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            os.killpg(process.pid, signal.SIGKILL)
                run_directory = package_root / "output" / "load" / run_id
                capture_incomplete_evidence(evidence_root, run_directory, checkpoint_dir, run_id,
                                            args.commit_sha, time.monotonic() - started_monotonic,
                                            failure, process.poll(), parameters, runtime)
                raise

        run_directory = package_root / "output" / "load" / run_id
        summary_path = run_directory / "load-summary.json"
        if not summary_path.is_file():
            if run_directory.is_dir():
                target_run = evidence_root / "load" / run_id
                target_run.parent.mkdir(parents=True, exist_ok=True)
                shutil.copytree(run_directory, target_run)
            atomic_json(evidence_root / "acceptance.json", {
                "schemaVersion": "att-load-25h-soak/v1",
                "runId": run_id,
                "commitSha": args.commit_sha,
                "result": "REPORT_UNAVAILABLE",
                "metadataFile": "run-metadata.json",
                "parameterSet": parameters,
                "runtime": runtime,
                "exitCode": process.returncode,
                "checkpointDirectory": "load/{}/checkpoints/{}".format(run_id, AGGREGATE_CHECKPOINT_SCOPE),
                "reason": "ATT exited without a canonical LOAD summary or report commit manifest; any retained checkpoints remain incomplete evidence.",
            })
            raise RuntimeError("ATT exited without a canonical LOAD summary; see the captured logs")
        target_run = evidence_root / "load" / run_id
        target_run.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(run_directory, target_run)
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
        if exit_code != 0:
            atomic_json(evidence_root / "acceptance.json", {
                "schemaVersion": "att-load-25h-soak/v1",
                "runId": run_id,
                "commitSha": args.commit_sha,
                "result": "FAILED",
                "metadataFile": "run-metadata.json",
                "parameterSet": parameters,
                "runtime": runtime,
                "exitCode": exit_code,
                "reportManifest": "load/{}/load-report-manifest.json".format(run_id),
            })
            raise RuntimeError("ATT LOAD exited with {}: {}".format(exit_code, "\n".join(stderr_log.read_text(encoding="utf-8", errors="replace").splitlines()[-20:])))
        manifest = verify_report_bundle(run_directory)
        metrics = summary.get("metrics", {})
        verify_soak_performance_verdict(manifest, summary)
        accounting = metrics.get("accounting", {})
        aggregate_checkpoints = metrics.get("checkpoints", {})
        workload = summary.get("workloads", {}).get("local-soak", {})
        workload_metrics = workload.get("metrics", {})
        workload_accounting = workload_metrics.get("accounting", {})
        workload_checkpoints = workload_metrics.get("checkpoints", {})
        verify_scheduled_arrivals(metrics, args.arrival_rate, args.duration_hours)
        verify_scheduled_arrivals(workload_metrics, args.arrival_rate, args.duration_hours)
        if accounting.get("state") != "VERIFIED" or workload_accounting.get("state") != "VERIFIED":
            raise RuntimeError("LOAD run, phase, or workload accounting did not reach VERIFIED state")
        workload_aggregate = accounting.get("workloadAggregate", {})
        if workload_aggregate and workload_aggregate.get("state") != "VERIFIED":
            raise RuntimeError("aggregate counters do not equal the sum of workload counters")
        if (not aggregate_checkpoints.get("enabled") or aggregate_checkpoints.get("writeFailures") != 0
                or not workload_checkpoints.get("enabled") or workload_checkpoints.get("writeFailures") != 0):
            raise RuntimeError("LOAD checkpointing was disabled or reported a write failure")
        verify_final_checkpoint(run_directory / "checkpoints" / AGGREGATE_CHECKPOINT_SCOPE, run_id, AGGREGATE_CHECKPOINT_SCOPE,
                                manifest.get("checkpointSequences", {}).get("aggregate"))
        verify_final_checkpoint(run_directory / "checkpoints" / CHECKPOINT_DIRECTORY, run_id, CHECKPOINT_SCOPE,
                                manifest.get("checkpointSequences", {}).get("local-soak"))
        duration_ms = int(summary.get("durationMs", 0))
        completed = int(metrics.get("measuredCompleted", 0))
        throughput = float(metrics.get("measuredCompletedThroughput", metrics.get("completedThroughput", 0.0)))
        if duration_ms < int(REQUIRED_DURATION_HOURS * 60 * 60 * 1000):
            raise RuntimeError("completed report covers less than 25 measured hours")
        if completed < MIN_COMPLETIONS or throughput < MIN_COMPLETED_TPS:
            raise RuntimeError("acceptance failed: completed={} completedTPS={:.3f}".format(completed, throughput))
        if int(metrics.get("dropped", 0)) != 0:
            raise RuntimeError("acceptance failed: generator reported {} dropped arrivals".format(metrics.get("dropped")))
        if int(metrics.get("scheduled", 0)) != int(metrics.get("started", 0)) + int(metrics.get("dropped", 0)):
            raise RuntimeError("arrival conservation failed: scheduled != started + dropped")
        if int(metrics.get("completed", 0)) != int(metrics.get("success", 0)) + int(metrics.get("failure", 0)) + int(metrics.get("runtimeError", 0)):
            raise RuntimeError("completion conservation failed: completed outcomes do not reconcile")
        if int(metrics.get("failure", 0)) + int(metrics.get("runtimeError", 0)) != 0:
            raise RuntimeError("acceptance failed: completed LOAD iterations include SUT failures or runtime errors")
        if int(metrics.get("otherOutcome", 0)) != 0:
            raise RuntimeError("acceptance failed: completed LOAD iterations include non-pass outcomes")
        if int(metrics.get("success", 0)) != int(metrics.get("completed", 0)):
            raise RuntimeError("acceptance failed: not every completed LOAD iteration succeeded")
        if int(metrics.get("currentInFlight", 0)) != 0:
            raise RuntimeError("clean drain left unresolved iterations")
        steady_rows = []
        for index, row in enumerate(trend):
            interval_seconds = row["durationSeconds"]
            if interval_seconds < 240.0:
                if index != len(trend) - 1:
                    raise RuntimeError("an internal checkpoint interval was shorter than four minutes")
                continue  # The final checkpoint can follow a short clean-drain interval.
            if interval_seconds > 360.0:
                raise RuntimeError("a checkpoint interval exceeded six minutes")
            if (row["completedTPS"] < MIN_COMPLETED_TPS or row["dropped"] != 0
                    or row["failedCompletions"] != 0 or row["otherOutcomes"] != 0):
                raise RuntimeError("five-minute acceptance window failed: {}".format(row))
            steady_rows.append(row)
        sustained_seconds = sum(row["durationSeconds"] for row in steady_rows)
        if sustained_seconds <= 24.0 * 60.0 * 60.0:
            raise RuntimeError("only {:.2f} continuous hours met the five-minute completion gate".format(sustained_seconds / 3600.0))
        resource_assessment = assess_resource_evidence(
            process_resource_samples, jvm_resource_samples, sustained_seconds)

        evidence = {
            "schemaVersion": "att-load-25h-soak/v1",
            "runId": run_id,
            "commitSha": args.commit_sha,
            "metadataFile": "run-metadata.json",
            "parameterSet": parameters,
            "startedAt": started_wall,
            "elapsedSeconds": time.monotonic() - started_monotonic,
            "durationHours": duration_ms / 3_600_000.0,
            "configuredArrivalRatePerSecond": metrics.get("configuredArrivalRatePerSecond"),
            "configuredMaxConcurrent": metrics.get("configuredMaxConcurrent"),
            "scheduled": metrics.get("scheduled"),
            "started": metrics.get("started"),
            "completed": metrics.get("completed"),
            "successful": metrics.get("success"),
            "sutFailures": metrics.get("failure"),
            "runtimeErrors": metrics.get("runtimeError"),
            "otherOutcomes": metrics.get("otherOutcome"),
            "dropped": metrics.get("dropped"),
            "measuredCompletedTPS": throughput,
            "fiveMinuteIntervals": len(steady_rows),
            "qualifiedDurationSeconds": sustained_seconds,
            "checkpointSequence": manifest.get("checkpointSequence"),
            "checkpointSequences": manifest.get("checkpointSequences", {}),
            "runtime": runtime,
            "resources": {
                "processSamples": len(process_resource_samples),
                "jvmCheckpointSamples": len(jvm_resource_samples),
                "assessment": resource_assessment,
                "process": summarize_resource_series(process_resource_samples, "", ("residentBytes", "threads", "openFileDescriptors")),
                "jvm": summarize_resource_series(jvm_resource_samples, "generator", (
                    "heapUsedBytes", "heapPeakUsedBytes", "heapCommittedBytes", "liveThreads",
                    "liveThreadsPeak", "gcCollectionCount", "gcCollectionTimeMs", "processCpuPercent")),
                "processSamplesFile": "process-resource-samples.json",
                "jvmTrendFile": "jvm-resource-trend.json",
            },
            "reportManifest": "load/" + run_id + "/load-report-manifest.json",
            "trendFile": "five-minute-trend.json",
            "result": "PASS",
        }
        atomic_json(evidence_root / "acceptance.json", evidence)
        print("PASS: completed {:.3f} TPS across {:.2f} hours; evidence: {}".format(
            throughput, duration_ms / 3_600_000.0, evidence_root))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository-root", type=Path, default=Path.cwd())
    parser.add_argument("--runtime-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("target/load-soak-evidence"))
    parser.add_argument("--run-id")
    parser.add_argument("--commit-sha", default=os.environ.get("GITHUB_SHA", "local"))
    parser.add_argument("--arrival-rate", type=int, default=150)
    parser.add_argument("--duration-hours", type=int, default=25)
    parser.add_argument("--max-concurrent", type=int, default=512)
    args = parser.parse_args()
    try:
        run_soak(args)
    except KeyboardInterrupt:
        evidence_root = args.output.resolve()
        evidence_root.mkdir(parents=True, exist_ok=True)
        try:
            existing = json.loads((evidence_root / "acceptance.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            existing = {}
        if existing.get("result") not in ("PARTIAL", "REPORT_UNAVAILABLE", "PASS"):
            existing.update({
                "schemaVersion": "att-load-25h-soak/v1",
                "runId": args.run_id or existing.get("runId"),
                "commitSha": args.commit_sha,
                "result": "REPORT_UNAVAILABLE",
                "complete": False,
                "reason": "Soak runner was interrupted before it could preserve a verified checkpoint.",
            })
            atomic_json(evidence_root / "acceptance.json", existing)
        print("LOAD soak interrupted; retained evidence is marked incomplete.", file=sys.stderr)
        raise SystemExit(130)
    except Exception as failure:
        evidence_root = args.output.resolve()
        try:
            existing = json.loads((evidence_root / "acceptance.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            existing = {}
        if existing.get("result") != "PASS":
            state = existing.get("result")
            existing.update({
                "schemaVersion": "att-load-25h-soak/v1",
                "runId": args.run_id or existing.get("runId"),
                "commitSha": args.commit_sha,
                "result": state if state in ("PARTIAL", "REPORT_UNAVAILABLE") else "FAILED",
                "failure": str(failure),
            })
            atomic_json(evidence_root / "acceptance.json", existing)
        print("LOAD soak failed: {}".format(failure), file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
