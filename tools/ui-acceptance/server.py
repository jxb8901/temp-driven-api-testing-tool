#!/usr/bin/env python3
"""Serve the real ATT UI assets with a small, development-only API fixture."""

from __future__ import annotations

import json
import mimetypes
import os
import time
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit


REPOSITORY = Path(__file__).resolve().parents[2]
BUILT_UI_ROOT = REPOSITORY / "att-web/target/classes/META-INF/resources/ui"
UI_ROOT = BUILT_UI_ROOT if (BUILT_UI_ROOT / "index.html").is_file() else REPOSITORY / "att-web/src/main/resources/META-INF/resources/ui"
PACKAGES = [
    {"packageId": "payments"},
    {"packageId": "ledger"},
]
JOBS = [
    {
        "jobId": "JOB-4821",
        "packageId": "payments",
        "command": "run",
        "principal": "qa-smoke",
        "status": "RUNNING",
        "createdAt": "2026-10-13T09:42:00Z",
    },
    {
        "jobId": "JOB-4819",
        "packageId": "ledger",
        "command": "validate",
        "principal": "release-check",
        "status": "PASS",
        "createdAt": "2026-10-13T09:35:00Z",
    },
]
LOAD_JOB = {
    "jobId": "JOB-LOAD-204",
    "packageId": "payments",
    "command": "load",
    "principal": "qa-smoke",
    "status": "RUNNING",
    "createdAt": "2026-10-13T09:50:00Z",
    "startedAt": "2026-10-13T09:50:01Z",
    "progress": 42,
    "performance": {"timings": {"queueMs": 4, "executionMs": 950}, "worker": {"id": "worker-fixture-1"}},
}
RESOURCE_ITEMS = [
    {"type": "template", "logicalId": "checkout", "state": "ready", "name": "Checkout template"},
    {"type": "flow", "logicalId": "purchase-flow", "state": "ready", "name": "Purchase flow"},
    {"type": "tool", "logicalId": "seed-data", "state": "ready", "name": "Seed data"},
]


class Handler(BaseHTTPRequestHandler):
    server_version = "ATT-UI-Acceptance-Fixture/1.0"

    def log_message(self, _format: str, *_args: object) -> None:
        pass

    def do_GET(self) -> None:
        request = urlsplit(self.path)
        if request.path.startswith("/att/api/v1/"):
            self._api(request.path.removeprefix("/att/api/v1/"), parse_qs(request.query))
            return
        if request.path == "/att/ui" or request.path.startswith("/att/ui/"):
            self._asset(request.path, request.query)
            return
        self.send_error(404)

    def do_POST(self) -> None:
        request = urlsplit(self.path)
        if not request.path.startswith("/att/api/v1/"):
            self.send_error(404)
            return
        resource = request.path.removeprefix("/att/api/v1/")
        length = int(self.headers.get("Content-Length", "0"))
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
        except (json.JSONDecodeError, UnicodeDecodeError):
            self._json(400, {"error": {"code": "INVALID_JSON", "summary": "Request body must be JSON."}})
            return
        scenario = self._fixture()
        if scenario == "explorer-workspace" and resource in {"drafts/debug", "debug/drafts", "debug/preview"}:
            draft_id = "DEBUG-DRAFT-204"
            self.server.valid_drafts.add(draft_id)
            self._json(201, {"draftId": draft_id, "packageId": "payments", "target": body.get("target", {"type": "template", "id": "checkout"}), "preview": {"status": "PASS", "message": "Debug preview completed."}, "previewYaml": "target: checkout\n", "expiresAt": "2026-10-13T10:00:00Z", "diagnostics": []})
            return
        if scenario == "explorer-workspace" and resource in {"jobs/debug", "debug/submit"}:
            self._json(202, {"jobId": "JOB-DEBUG-204", "status": "QUEUED"})
            return
        if scenario == "load-workspace" and resource in {"drafts/load", "jobs/load"}:
            draft_id = str(body.get("draftId", "LOAD-DRAFT-204"))
            if resource == "jobs/load" and draft_id not in self.server.valid_drafts:
                self._json(409, {"error": {"code": "DRAFT_EXPIRED", "summary": "The load draft expired. Create a new draft."}})
                return
            self.server.valid_drafts.add(draft_id)
            if resource == "drafts/load":
                self._json(201, {"draftId": draft_id, "packageId": "payments", "model": "virtualUsers", "preview": {"status": "PASS", "users": 12}, "previewYaml": "scenario:\n  virtualUsers: 12\n", "redacted": False, "diagnostics": [], "expiresAt": "2026-10-13T10:00:00Z"})
            else:
                self.server.cancel_requested = False
                self._json(202, {"jobId": LOAD_JOB["jobId"], "status": "RUNNING", "command": "load", "packageId": "payments"})
            return
        if scenario == "explorer-workspace" and resource in {"jobs/run", "jobs/case"}:
            self._json(202, {"jobId": "JOB-DEBUG-204", "status": "QUEUED"})
            return
        self._json(404, {"error": {"code": "FIXTURE_NOT_FOUND", "summary": "No fixture route is defined."}})

    def do_DELETE(self) -> None:
        request = urlsplit(self.path)
        if not request.path.startswith("/att/api/v1/"):
            self.send_error(404)
            return
        resource = request.path.removeprefix("/att/api/v1/")
        if self._fixture() == "load-workspace" and resource == "jobs/" + LOAD_JOB["jobId"]:
            self.server.cancel_requested = True
            self._json(202, {"jobId": LOAD_JOB["jobId"], "status": "CANCEL_REQUESTED"})
            return
        self._json(404, {"error": {"code": "FIXTURE_NOT_FOUND", "summary": "No fixture route is defined."}})

    def _json(self, status: int, value: dict[str, object]) -> None:
        body = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _fixture(self) -> str:
        cookies = SimpleCookie()
        cookies.load(self.headers.get("Cookie", ""))
        morsel = cookies.get("att-ui-fixture")
        return morsel.value if morsel else "loaded"

    def _api(self, resource: str, query: dict[str, list[str]]) -> None:
        scenario = self._fixture()
        if resource == "version":
            self._json(200, {"apiVersion": "1", "inlineLoadEnabled": True})
            return
        if scenario == "authenticated" and self.headers.get("Authorization") != "Basic YXR0LWZpeHR1cmU6cGFzcw==":
            self._json(401, {"error": {"code": "AUTHENTICATION_REQUIRED", "summary": "Fixture authentication is required."}})
            return
        parts = [unquote(part) for part in resource.strip("/").split("/") if part]
        if parts == ["packages"]:
            data = PACKAGES
        elif parts == ["jobs"]:
            if scenario == "load-workspace":
                self.server.job_list_requests += 1
                if self.server.job_list_requests == 2:
                    time.sleep(0.8)
                load_job = dict(LOAD_JOB)
                if self.server.cancel_requested:
                    load_job["status"] = "CANCELLED"
                    load_job["finishedAt"] = "2026-10-13T09:51:00Z"
                    load_job["exitCode"] = 130
                data = [load_job, JOBS[1]]
            else:
                data = JOBS
        elif len(parts) == 3 and parts[0] == "packages" and parts[2] == "resources":
            if scenario in {"explorer-workspace", "load-workspace"}:
                self._json(200, {"items": RESOURCE_ITEMS, "total": len(RESOURCE_ITEMS), "nextCursor": None, "diagnostics": []})
                return
            self._json(404, {"error": {"code": "FIXTURE_NOT_FOUND", "summary": "No fixture route is defined."}})
            return
        elif len(parts) == 3 and parts[:2] == ["packages", "payments"] and parts[2] == "load-policy":
            self._json(200, {"model": query.get("model", ["virtualUsers"])[0], "policy": {"enabled": True, "maxVirtualUsers": 200, "maxArrivalRate": 50}, "previewYaml": "load:\n  enabled: true\n", "redacted": False})
            return
        elif (len(parts) == 3 and parts[:2] == ["packages", "payments"] and parts[2] == "quick-load-form") or (len(parts) == 6 and parts[:2] == ["packages", "payments"] and parts[2] == "resources" and parts[5] == "quick-load-form"):
            target_type = parts[3] if len(parts) == 6 else query.get("type", ["template"])[0]
            target_id = parts[4] if len(parts) == 6 else query.get("id", ["checkout"])[0]
            self._json(200, {"target": {"type": target_type, "id": target_id}, "input": {"inputs": {"virtualUsers": 12, "durationSeconds": 30}, "vars": {}}, "preview": {"status": "PASS"}, "previewYaml": "scenario:\n  virtualUsers: 12\n", "redacted": False})
            return
        elif len(parts) >= 2 and parts[0] == "jobs":
            job_id = parts[1]
            if scenario == "load-workspace" and job_id == LOAD_JOB["jobId"]:
                if len(parts) == 2:
                    job = dict(LOAD_JOB)
                    job["status"] = "CANCELLED" if self.server.cancel_requested else "RUNNING"
                    job["finishedAt"] = "2026-10-13T09:51:00Z" if self.server.cancel_requested else None
                    job["exitCode"] = 130 if self.server.cancel_requested else None
                    self._json(200, job)
                    return
                if parts[2] == "result":
                    self._json(200, {"job": LOAD_JOB, "result": {"status": "PASS", "metrics": {
                        "completedThroughput": 120, "p95Ms": 85,
                        "buckets": {
                            "1791885002000": {"bucketStartEpochMs": 1791885002000, "bucketStart": "2026-10-13T09:50:02Z", "completedTps": 120, "p95Ms": 85, "p99Ms": 110},
                            "1791885001000": {"bucketStartEpochMs": 1791885001000, "bucketStart": "2026-10-13T09:50:01Z", "completedTps": 96, "p95Ms": 74, "p99Ms": 99},
                        },
                    }}, "diagnostic": None})
                    return
                if parts[2] == "artifacts" and len(parts) == 3:
                    self._json(200, {"items": [{"path": "load-summary.json", "size": 512}]})
                    return
                if parts[2] == "artifacts" and len(parts) > 3:
                    self._json(200, {"path": "/".join(parts[3:]), "contents": "{\"status\":\"PASS\"}\n"})
                    return
                if parts[2] == "events":
                    self._events()
                    return
            if scenario == "explorer-workspace" and job_id == "JOB-DEBUG-204":
                self._json(200, {"jobId": job_id, "packageId": "payments", "command": "debug", "status": "PASS", "createdAt": "2026-10-13T09:50:00Z"})
                return
        elif scenario == "explorer-workspace" and len(parts) == 3 and parts[:2] == ["packages", "payments"] and parts[2] in {"configuration", "config", "declared-configuration", "effective-configuration", "compare-configuration"}:
            self._json(200, {"state": "ready", "environments": ["dev", "staging", "prod"], "globals": {}, "sections": [], "items": [], "total": 0})
            return
        elif scenario == "explorer-workspace" and parts[:2] == ["packages", "payments"] and len(parts) >= 5 and parts[2] == "resources":
            resource_type, resource_id = parts[3], parts[4]
            if parts[-1] in {"source", "safe-source"}:
                self._json(200, {"available": True, "text": "keywords:\n  - ATT\n", "format": "yaml"})
            elif parts[-1] in {"debug-form", "debug"}:
                self._json(200, {"target": {"type": resource_type, "id": resource_id}, "input": {"environment": "dev", "variables": {}}, "redacted": False})
            else:
                self._json(200, {"resource": {"type": resource_type, "logicalId": resource_id, "state": "ready", "name": resource_id}, "definition": {"name": resource_id}, "diagnostics": []})
            return
        else:
            self._json(404, {"error": {"code": "FIXTURE_NOT_FOUND", "summary": "No fixture route is defined."}})
            return

        if scenario == "loading":
            time.sleep(1.4)
        if scenario == "error":
            self._json(503, {
                "error": {
                    "code": "FIXTURE_UNAVAILABLE",
                    "summary": "The local fixture simulates an unavailable API.",
                }
            })
            return
        if scenario == "empty":
            data = []
        self._json(200, {"items": data})

    def _events(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        try:
            self.wfile.write(b"id: 1\nevent: status\ndata: {\"status\":\"RUNNING\"}\n\n")
            self.wfile.flush()
            for event_id in range(2, 52):
                if self.server.cancel_requested:
                    self.wfile.write(f"id: {event_id}\nevent: status\ndata: {json.dumps({'status': 'CANCELLED'})}\n\n".encode("utf-8"))
                    self.wfile.flush()
                    return
                self.wfile.write(f"id: {event_id}\nevent: progress\ndata: {json.dumps({'message': 'Load running', 'percent': min(event_id * 2, 98)})}\n\n".encode("utf-8"))
                self.wfile.flush()
                time.sleep(0.2)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _asset(self, request_path: str, query: str) -> None:
        relative = request_path.removeprefix("/att/ui/")
        if not relative:
            relative = "index.html"
        candidate = (UI_ROOT / relative).resolve()
        try:
            candidate.relative_to(UI_ROOT.resolve())
        except ValueError:
            self.send_error(404)
            return
        if not candidate.is_file():
            self.send_error(404)
            return
        body = candidate.read_bytes()
        content_type = mimetypes.guess_type(candidate.name)[0] or "application/octet-stream"
        if content_type.startswith("text/") or content_type in {"application/javascript", "application/json"}:
            content_type += "; charset=utf-8"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        if "fixture=" in query:
            scenario = parse_qs(query).get("fixture", [""])[0]
            if scenario in {"loaded", "empty", "error", "loading", "authenticated", "anonymous", "explorer-workspace", "load-workspace"}:
                self.send_header("Set-Cookie", f"att-ui-fixture={scenario}; Path=/att/; SameSite=Lax")
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    if not UI_ROOT.is_dir():
        raise SystemExit(f"UI assets not found: {UI_ROOT}")
    port = int(os.environ.get("ATT_UI_FIXTURE_PORT", "0"))
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.valid_drafts = set()
    server.cancel_requested = False
    server.job_list_requests = 0
    actual_port = server.server_address[1]
    print(f"READY http://127.0.0.1:{actual_port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
