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
from urllib.parse import urlsplit


REPOSITORY = Path(__file__).resolve().parents[2]
UI_ROOT = REPOSITORY / "att-web/src/main/resources/META-INF/resources/ui"
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


class Handler(BaseHTTPRequestHandler):
    server_version = "ATT-UI-Acceptance-Fixture/1.0"

    def log_message(self, _format: str, *_args: object) -> None:
        pass

    def do_GET(self) -> None:
        request = urlsplit(self.path)
        if request.path.startswith("/att/api/v1/"):
            self._api(request.path.removeprefix("/att/api/v1/"))
            return
        if request.path == "/att/ui" or request.path.startswith("/att/ui/"):
            self._asset(request.path, request.query)
            return
        self.send_error(404)

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

    def _api(self, resource: str) -> None:
        scenario = self._fixture()
        if resource == "version":
            self._json(200, {"apiVersion": "1", "inlineLoadEnabled": True})
            return
        if resource == "packages" or resource.startswith("packages?"):
            data = PACKAGES
        elif resource == "jobs" or resource.startswith("jobs?"):
            data = JOBS
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
        if query.startswith("fixture="):
            scenario = query.split("=", 1)[1].split("&", 1)[0]
            if scenario in {"loaded", "empty", "error", "loading"}:
                self.send_header("Set-Cookie", f"att-ui-fixture={scenario}; Path=/att/; SameSite=Lax")
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    if not UI_ROOT.is_dir():
        raise SystemExit(f"UI assets not found: {UI_ROOT}")
    port = int(os.environ.get("ATT_UI_FIXTURE_PORT", "0"))
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
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
