#!/usr/bin/env python3
"""Benchmark a synthetic 20-case Run and local-HTTP fixed-arrival Load."""

import argparse
import hashlib
import json
import math
import os
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
from xml.etree import ElementTree as ET
from zipfile import ZIP_DEFLATED, ZipFile


SHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
OFFICE_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
CONTENT_TYPE_NS = "http://schemas.openxmlformats.org/package/2006/content-types"


class ReadyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

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
    launcher = root / ("att.bat" if os.name == "nt" else "att.sh")
    if os.name == "nt":
        return ["cmd.exe", "/c", str(launcher)] + arguments
    return [str(launcher)] + arguments


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


def write_text(path, contents):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(contents, encoding="utf-8")


def create_workbook(path, case_count):
    ET.register_namespace("x", SHEET_NS)
    ET.register_namespace("r", OFFICE_REL_NS)
    ET.register_namespace("", PACKAGE_REL_NS)
    ET.register_namespace("", CONTENT_TYPE_NS)

    def cell(reference, value):
        item = ET.Element("{%s}c" % SHEET_NS, {"r": reference, "t": "inlineStr"})
        inline = ET.SubElement(item, "{%s}is" % SHEET_NS)
        text = ET.SubElement(inline, "{%s}t" % SHEET_NS)
        text.text = value
        return item

    headers = ["Case ID", "Tags", "Name", "Template"]
    values = [(1, ["Synthetic Run benchmark", "", "", ""]), (2, headers)]
    values.extend((index + 2, ["BENCH{:03d}".format(index), "benchmark",
                               "Synthetic no-op case", "BENCH_NOOP"])
                  for index in range(1, case_count + 1))
    worksheet = ET.Element("{%s}worksheet" % SHEET_NS)
    ET.SubElement(worksheet, "{%s}dimension" % SHEET_NS,
                  {"ref": "A1:D{}".format(case_count + 2)})
    sheet_data = ET.SubElement(worksheet, "{%s}sheetData" % SHEET_NS)
    for row_number, row_values in values:
        row = ET.SubElement(sheet_data, "{%s}row" % SHEET_NS, {"r": str(row_number)})
        for column, value in enumerate(row_values, 1):
            row.append(cell("{}{}".format(chr(64 + column), row_number), value))

    content_types = ET.Element("{%s}Types" % CONTENT_TYPE_NS)
    ET.SubElement(content_types, "{%s}Default" % CONTENT_TYPE_NS,
                  {"Extension": "rels", "ContentType": "application/vnd.openxmlformats-package.relationships+xml"})
    ET.SubElement(content_types, "{%s}Default" % CONTENT_TYPE_NS,
                  {"Extension": "xml", "ContentType": "application/xml"})
    ET.SubElement(content_types, "{%s}Override" % CONTENT_TYPE_NS,
                  {"PartName": "/xl/workbook.xml",
                   "ContentType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"})
    ET.SubElement(content_types, "{%s}Override" % CONTENT_TYPE_NS,
                  {"PartName": "/xl/worksheets/sheet1.xml",
                   "ContentType": "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"})

    relationships = ET.Element("{%s}Relationships" % PACKAGE_REL_NS)
    ET.SubElement(relationships, "{%s}Relationship" % PACKAGE_REL_NS,
                  {"Id": "rId1", "Type": OFFICE_REL_NS + "/officeDocument",
                   "Target": "xl/workbook.xml"})
    workbook = ET.Element("{%s}workbook" % SHEET_NS)
    sheets = ET.SubElement(workbook, "{%s}sheets" % SHEET_NS)
    ET.SubElement(sheets, "{%s}sheet" % SHEET_NS,
                  {"name": "Benchmark", "sheetId": "1",
                   "{%s}id" % OFFICE_REL_NS: "rId1"})
    workbook_relationships = ET.Element("{%s}Relationships" % PACKAGE_REL_NS)
    ET.SubElement(workbook_relationships, "{%s}Relationship" % PACKAGE_REL_NS,
                  {"Id": "rId1", "Type": OFFICE_REL_NS + "/worksheet",
                   "Target": "worksheets/sheet1.xml"})

    parts = {
        "[Content_Types].xml": content_types,
        "_rels/.rels": relationships,
        "xl/workbook.xml": workbook,
        "xl/_rels/workbook.xml.rels": workbook_relationships,
        "xl/worksheets/sheet1.xml": worksheet,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(path, "w", ZIP_DEFLATED) as archive:
        for name, root in parts.items():
            archive.writestr(name, ET.tostring(root, encoding="utf-8", xml_declaration=True))


def install_package(runtime_root, destination, http_port, case_count):
    launcher_name = "att.bat" if os.name == "nt" else "att.sh"
    launcher = runtime_root / launcher_name
    if not launcher.is_file():
        raise RuntimeError("Missing {} in runtime distribution {}".format(launcher_name, runtime_root))
    if not (runtime_root / "lib").is_dir() or not list((runtime_root / "lib").glob("att-*.jar")):
        raise RuntimeError("Runtime distribution must contain lib/att-*.jar")
    if not (runtime_root / "schemas").is_dir():
        raise RuntimeError("Runtime distribution must contain schemas/")

    shutil.copy2(launcher, destination / launcher_name)
    if os.name != "nt":
        (destination / launcher_name).chmod(0o755)
    shutil.copytree(runtime_root / "lib", destination / "lib")
    shutil.copytree(runtime_root / "schemas", destination / "schemas")
    write_text(destination / "config" / "config.yaml",
        "schemaVersion: att-config/v2.12\n"
        "outputDirectory: output\n"
        "environment: BENCHMARK\n"
        "testcase: {root: testcase}\n"
        "templates: {root: templates}\n"
        "httphelpers: [config/httphelpers/benchmark.yaml]\n")
    write_text(destination / "config" / "httphelpers" / "benchmark.yaml",
        ("schemaVersion: att-httphelper/v1.1\n"
        "id: benchmark\nname: Local benchmark endpoint\n"
        "description: Loopback-only fixed response\n"
        "baseUrl: http://127.0.0.1:{}\n"
        "pool: {{maxConnections: 256, maxConnectionsPerRoute: 256}}\n").format(http_port))
    write_text(destination / "templates" / "BENCH_NOOP" / "template.yaml",
        "schemaVersion: att-template/v3.6\nname: BENCH_NOOP\n"
        "description: Synthetic no-op Run benchmark\nactions:\n"
        "  record: {type: log, message: \"case ${META.SOURCE.caseId?}\"}\n")
    write_text(destination / "templates" / "BENCH_HTTP" / "template.yaml",
        "schemaVersion: att-template/v3.6\nname: BENCH_HTTP\n"
        "description: Local HTTP fixed-arrival Load benchmark\nactions:\n"
        "  request: {type: tool, call: \"#{http.benchmark.get(path='/ready')}\"}\n")
    sidecar = (
        "schemaVersion: att-sidecar/v2.2\n"
        "id: synthetic20\n"
        "excel:\n  sheet: Benchmark\n  headerRows: 2\n"
        "  caseId: Case ID\n  tags: Tags\n"
        "stages:\n  - key: main\n    template: Template\n"
        "    required: true\n    onFailure: stop\n")
    write_text(destination / "testcase" / "run-20.yaml", sidecar)
    create_workbook(destination / "testcase" / "run-20.xlsx", case_count)


def summarize_metric(values):
    if not values:
        return None
    ordered = sorted(values)
    deviation = statistics.stdev(values) if len(values) > 1 else 0.0
    mean = statistics.mean(values)
    return {
        "samples": len(values),
        "p50": round(statistics.median(values), 3),
        "p95": round(ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)], 3),
        "mean": round(mean, 3),
        "standardDeviation": round(deviation, 3),
        "coefficientOfVariationPercent": round(deviation * 100.0 / mean, 2) if mean else 0.0,
    }


def file_metrics(root):
    files = [path for path in root.rglob("*") if path.is_file()]
    return {"fileCount": len(files), "bytes": sum(path.stat().st_size for path in files)}


def require_success(result, command_name):
    if result["exitCode"] != 0:
        raise RuntimeError("{} failed (exit {}):\n{}".format(
            command_name, result["exitCode"], "\n".join(result["outputTail"])))


def parse_final_json(output_tail):
    for item in reversed(output_tail):
        line = item.split(": ", 1)[-1]
        try:
            value = json.loads(line)
            if isinstance(value, dict):
                return value
        except ValueError:
            continue
    return None


def numeric_summary(samples, sections):
    keys = set()
    for sample in samples:
        current = sample
        for section in sections:
            current = current.get(section, {}) if isinstance(current, dict) else {}
        if isinstance(current, dict):
            keys.update(key for key, value in current.items()
                        if isinstance(value, (int, float)) and not isinstance(value, bool))
    result = {}
    for key in sorted(keys):
        values = []
        for sample in samples:
            current = sample
            for section in sections:
                current = current.get(section, {}) if isinstance(current, dict) else {}
            value = current.get(key) if isinstance(current, dict) else None
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                values.append(float(value))
        result[key] = summarize_metric(values)
    return result


def run_run_benchmark(root, runs, warmups, case_count):
    suite = "testcase/run-20.xlsx"
    snapshot = run_command(launcher_command(root, ["snapshot", "--suite", suite]), root)
    require_success(snapshot, "Run benchmark snapshot generation")
    samples = []
    total_invocations = warmups + runs
    for index in range(total_invocations):
        run_id = "run20-{:02d}".format(index + 1)
        run_directory = root / "output" / run_id
        if run_directory.exists():
            shutil.rmtree(run_directory)
        result = run_command(launcher_command(root, ["run", "--suite", suite,
            "--run-id", run_id, "--output-dir", "output", "--format", "json", "--quiet"]), root)
        require_success(result, "Run benchmark {}".format(run_id))
        run_summary = parse_final_json(result["outputTail"])
        if run_summary is None or int(run_summary.get("total", -1)) != case_count:
            raise RuntimeError("Run benchmark {} did not report {} cases: {}".format(
                run_id, case_count, run_summary))
        if index >= warmups:
            samples.append({
                "exitCode": result["exitCode"],
                "firstOutputMs": result["firstOutputMs"],
                "totalMs": result["totalMs"],
                "summary": run_summary,
                "output": file_metrics(run_directory),
            })
    return {
        "caseCount": case_count,
        "warmups": warmups,
        "measurements": samples,
        "wallTime": {
            "firstOutputMs": summarize_metric([sample["firstOutputMs"] for sample in samples
                                                if sample["firstOutputMs"] is not None]),
            "totalMs": summarize_metric([sample["totalMs"] for sample in samples]),
        },
    }


def load_scenario(rate, duration, warmup, max_concurrent, evidence_mode, max_samples):
    return (
        "schemaVersion: att-load/v1.6\n"
        "seed: 177\n"
        "workloads:\n"
        "  - id: local-http\n"
        "    target: {{type: template, id: BENCH_HTTP}}\n"
        "    load:\n"
        "      arrivalRate: {}/s\n"
        "      warmup: {}\n"
        "      duration: {}\n"
        "      maxConcurrent: {}\n"
        "      overloadPolicy: drop\n"
        "evidence:\n"
        "  mode: {}\n"
        "  maxSamples: {}\n".format(rate, warmup, duration, max_concurrent,
                                      evidence_mode, max_samples))


def selected_load_metrics(metrics):
    fields = (
        "model", "configuredArrivalRatePerSecond", "configuredMaxConcurrent",
        "scheduled", "started", "completed", "success", "measuredSuccess",
        "failure", "measuredFailure", "runtimeError", "measuredRuntimeError",
        "dropped", "measuredDropped", "warmupCompleted", "measuredCompleted",
        "measuredAchievedArrivalRate", "achievedArrivalRatePercent",
        "completedThroughput", "schedulerLagMeanMs", "schedulerLagMaxMs",
        "p50Ms", "p95Ms", "p99Ms", "latencyMinMs", "latencyMeanMs",
        "latencyMaxMs", "latencyObservationCount", "latencySampleCount",
        "latencySampleCapacity", "latencySampleRate", "errorClassifications",
    )
    result = {key: metrics.get(key) for key in fields if key in metrics}
    result["generator"] = metrics.get("generator", {})
    return result


def run_load_benchmarks(root, rates, evidence_modes, duration, warmup, max_concurrent,
                        max_samples, runs, warmups):
    conditions = {}
    for rate in rates:
        for evidence_mode in evidence_modes:
            key = "{}TPS-{}".format(rate, evidence_mode)
            measured = []
            for index in range(warmups + runs):
                run_id = "load-{}-{}-{:02d}".format(rate, evidence_mode, index + 1)
                scenario = root / "load" / (run_id + ".yaml")
                write_text(scenario, load_scenario(rate, duration, warmup, max_concurrent,
                                                   evidence_mode, max_samples))
                result = run_command(launcher_command(root, ["load", str(scenario.relative_to(root)),
                    "--run-id", run_id, "--output-dir", "output", "--format", "json",
                    "--quiet", "--profile"]), root)
                run_directory = root / "output" / "load" / run_id
                summary_path = run_directory / "load-summary.json"
                profile_path = run_directory / "performance.json"
                if not summary_path.is_file():
                    require_success(result, "Load benchmark {}".format(run_id))
                    raise RuntimeError("Missing Load summary for {}".format(run_id))
                summary = json.loads(summary_path.read_text(encoding="utf-8"))
                metrics = summary.get("metrics", {})
                failure_diagnostics = []
                if result["exitCode"] != 0:
                    evidence = summary.get("evidence", {}).get("items", [])
                    for item in evidence[:3]:
                        case_log = item.get("caseLog")
                        if case_log:
                            log_path = run_directory / case_log
                            if log_path.is_file():
                                failure_diagnostics.append({"event": item, "caseLog": log_path.read_text(
                                    encoding="utf-8", errors="replace")[-4000:]})
                sample = {
                    "exitCode": result["exitCode"],
                    "firstOutputMs": result["firstOutputMs"],
                    "totalMs": result["totalMs"],
                    "status": summary.get("status"),
                    "metrics": selected_load_metrics(metrics),
                    "resources": summary.get("resources", {}),
                    "output": file_metrics(run_directory),
                }
                if result["exitCode"] != 0:
                    sample["failureDiagnostics"] = {
                        "runtimeError": metrics.get("runtimeError"),
                        "failure": metrics.get("failure"),
                        "dropped": metrics.get("dropped"),
                        "errorClassifications": metrics.get("errorClassifications", {}),
                        "retainedFailureExamples": failure_diagnostics,
                    }
                if profile_path.is_file():
                    sample["performanceProfile"] = json.loads(profile_path.read_text(encoding="utf-8"))
                if index >= warmups:
                    measured.append(sample)
            conditions[key] = {
                "ratePerSecond": rate,
                "evidenceMode": evidence_mode,
                "duration": duration,
                "warmup": warmup,
                "warmupRuns": warmups,
                "measurements": measured,
                "wallTime": {
                    "firstOutputMs": summarize_metric([sample["firstOutputMs"] for sample in measured
                                                        if sample["firstOutputMs"] is not None]),
                    "totalMs": summarize_metric([sample["totalMs"] for sample in measured]),
                },
                "loadMetricsAcrossRuns": {
                    "load": numeric_summary(measured, ["metrics"]),
                    "generator": numeric_summary(measured, ["metrics", "generator"]),
                },
            }
    return conditions


def sha256_directory(directory):
    digest = hashlib.sha256()
    for path in sorted(path for path in directory.rglob("*") if path.is_file()):
        digest.update(path.relative_to(directory).as_posix().encode("utf-8"))
        digest.update(b"\0")
        with path.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()


def parse_csv(value, cast, label):
    try:
        result = [cast(item.strip()) for item in value.split(",") if item.strip()]
    except ValueError:
        raise argparse.ArgumentTypeError("{} must be a comma-separated list".format(label))
    if not result:
        raise argparse.ArgumentTypeError("{} must not be empty".format(label))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime-root", type=Path, required=True,
                        help="Extracted 4.0.1 binary distribution")
    parser.add_argument("--runtime-revision", default="unknown",
                        help="Source commit used to build this distribution")
    parser.add_argument("--rates", default="100,500,1000")
    parser.add_argument("--evidence-modes", default="metrics,failures,samples")
    parser.add_argument("--duration", default="5s", help="Measured Load phase duration")
    parser.add_argument("--load-warmup", default="1s", help="Warmup phase inside every Load run")
    parser.add_argument("--max-concurrent", type=int, default=256)
    parser.add_argument("--max-samples", type=int, default=10,
                        help="Maximum retained success samples for samples evidence")
    parser.add_argument("--runs", type=int, default=3, help="Measured runs per condition")
    parser.add_argument("--warmups", type=int, default=1,
                        help="Discarded whole-process warmup runs per condition")
    parser.add_argument("--case-count", type=int, default=20)
    parser.add_argument("--output", type=Path, default=Path("run-load-benchmark-4.0.1.json"))
    args = parser.parse_args()
    if args.runs < 2 or args.warmups < 0:
        parser.error("--runs must be >= 2 and --warmups must be >= 0")
    if args.max_concurrent < 1 or args.max_samples < 0 or args.case_count < 20:
        parser.error("--max-concurrent must be positive, --max-samples non-negative, and --case-count >= 20")
    rates = parse_csv(args.rates, int, "--rates")
    evidence_modes = parse_csv(args.evidence_modes, str, "--evidence-modes")
    invalid = sorted(set(evidence_modes) - {"metrics", "failures", "samples", "all"})
    if invalid:
        parser.error("unsupported evidence mode(s): {}".format(", ".join(invalid)))

    runtime_root = args.runtime_root.resolve()
    http_server = ThreadingHTTPServer(("127.0.0.1", 0), ReadyHandler)
    http_server.daemon_threads = True
    http_server.request_queue_size = max(128, args.max_concurrent)
    http_thread = threading.Thread(target=http_server.serve_forever, daemon=True)
    http_thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="att-run-load-benchmark-") as temporary:
            package_root = Path(temporary) / "package"
            package_root.mkdir()
            install_package(runtime_root, package_root, http_server.server_address[1], args.case_count)
            version = run_command(launcher_command(package_root, ["version"]), package_root)
            require_success(version, "Read runtime version")
            runtime_version = "\n".join(version["outputTail"])
            run_result = run_run_benchmark(package_root, args.runs, args.warmups, args.case_count)
            load_results = run_load_benchmarks(package_root, rates, evidence_modes,
                args.duration, args.load_warmup, args.max_concurrent, args.max_samples,
                args.runs, args.warmups)
    finally:
        http_server.shutdown()
        http_server.server_close()
        http_thread.join(timeout=2)

    report = {
        "schemaVersion": "att-run-load-benchmark/v1",
        "runtime": {"versionOutput": runtime_version, "sourceRevision": args.runtime_revision,
                    "binaryTreeSha256": sha256_directory(runtime_root)},
        "environment": {
            "platform": platform.platform(),
            "machine": platform.machine(),
            "logicalCpuCount": os.cpu_count(),
            "pythonVersion": platform.python_version(),
            "javaVersion": subprocess.run(["java", "-version"], capture_output=True,
                                           text=True).stderr.strip(),
        },
        "measurement": {
            "loadTarget": "Local loopback HTTP helper returning a fixed JSON response; not an external SUT.",
            "arrivalRatesPerSecond": rates,
            "evidenceModes": evidence_modes,
            "sampleRuns": args.runs,
            "wholeProcessWarmupRuns": args.warmups,
            "loadWarmup": args.load_warmup,
            "loadDuration": args.duration,
            "maxConcurrent": args.max_concurrent,
            "maxRetainedSamples": args.max_samples,
            "wallTime": "Includes launcher and JVM startup; firstOutputMs is process spawn to first non-empty stdout/stderr line.",
            "percentile": "p95 uses nearest-rank over whole-process samples; per-run Load p95 is ATT's bounded latency reservoir.",
        },
        "run20": run_result,
        "load": load_results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), "runtime": runtime_version,
                      "loadConditions": len(load_results), "sampleRuns": args.runs,
                      "runCases": args.case_count}, indent=2))


if __name__ == "__main__":
    main()
