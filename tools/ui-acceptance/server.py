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
BUILT_FRONTEND_ROOT = REPOSITORY / "att-web/frontend/dist"
OVERRIDE_UI_ROOT = os.environ.get("ATT_UI_DIST")
UI_ROOT = Path(OVERRIDE_UI_ROOT).expanduser().resolve() if OVERRIDE_UI_ROOT else (
    BUILT_UI_ROOT if (BUILT_UI_ROOT / "index.html").is_file() else (
        BUILT_FRONTEND_ROOT if (BUILT_FRONTEND_ROOT / "index.html").is_file()
        else REPOSITORY / "att-web/src/main/resources/META-INF/resources/ui"
    )
)
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
SCHEDULE = {
    "id": "SCHED-204", "name": "Nightly payments", "description": "Checkout soak", "tags": ["nightly"],
    "packageId": "payments", "scenario": "load/nightly.yaml", "config": "config/config.yaml",
    "environment": "SIT", "timezone": "Asia/Hong_Kong", "cron": "0 2 * * *", "load": {},
    "maxConcurrentRuns": 1, "enabled": True, "nextRunAt": "2026-10-15T18:00:00Z",
}
SCHEDULE_OCCURRENCES = [
    {"occurrenceId": "O-SKIPPED", "scheduleId": "SCHED-204", "scheduledFor": "2026-10-14T18:00:00Z", "triggerType": "scheduled", "status": "skipped", "reason": "schedule-overlap-limit"},
    {"occurrenceId": "O-SUBMITTED", "scheduleId": "SCHED-204", "scheduledFor": "2026-10-13T18:00:00Z", "triggerType": "scheduled", "status": "submitted", "jobId": "JOB-4821"},
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
        if scenario in {"auth-admin", "auth-user", "auth-container"} and resource.startswith("auth/"):
            if scenario == "auth-container":
                self._json(404, {"error": {"code": "NOT_FOUND", "summary": "ATT local authentication is unavailable."}})
                return
            if self.headers.get("X-ATT-CSRF") != "csrf-fixture":
                self._json(403, {"error": {"code": "ATT-AUTH-CSRF-INVALID", "summary": "Request verification failed."}})
                return
            if resource == "auth/users" and scenario != "auth-admin":
                self._json(403, {"error": {"code": "ATT-SERVER-FORBIDDEN", "summary": "Administrator access is required."}})
                return
            if resource == "auth/users":
                login_id = str(body.get("loginId", "")).strip().lower()
                if any(user["loginId"] == login_id for user in self.server.auth_users):
                    self._json(409, {"error": {"code": "ATT-AUTH-LOGIN-ID-TAKEN", "summary": "Authentication request could not be completed."}})
                    return
                user = {"loginId": login_id, "displayName": body.get("displayName", ""), "role": body.get("role", "USER"), "active": True}
                self.server.auth_users.append(user)
                self._json(201, {"user": {key: user[key] for key in ("loginId", "displayName", "role")}})
                return
            if resource == "auth/password":
                self.server.auth_password_changes += 1
                self._json(200, {"updated": True})
                return
            parts = [unquote(part) for part in resource.strip("/").split("/") if part]
            if len(parts) == 4 and parts[:2] == ["auth", "users"] and parts[3] == "password":
                self.server.auth_password_resets.append(parts[2])
                self._json(200, {"updated": True})
                return
            if len(parts) == 4 and parts[:2] == ["auth", "users"] and parts[3] == "enable":
                user = next((item for item in self.server.auth_users if item["loginId"] == parts[2]), None)
                if user is None:
                    self._json(404, {"error": {"code": "ATT-SERVER-NOT-FOUND", "summary": "User was not found."}})
                else:
                    user["active"] = True
                    self._json(200, {"enabled": True})
                return
        if resource.startswith("load-schedules"):
            if scenario == "schedule-unauthenticated":
                self._json(401, {"error": {"code": "AUTHENTICATION_REQUIRED", "summary": "Sign in to manage Scheduled Load."}})
                return
            if scenario == "schedule-forbidden":
                self._json(403, {"error": {"code": "SCHEDULE_ADMIN_REQUIRED", "summary": "Scheduled Load administrator role required."}})
                return
            if resource == "load-schedules/preview":
                self._json(200, {"timezone": body.get("timezone", "UTC"), "cron": body.get("cron", "0 2 * * *"), "nextRunAt": "2026-10-15T18:00:00Z"})
                return
            if resource == "load-schedules":
                created = {**body, "id": "SCHED-CREATED", "nextRunAt": "2026-10-15T18:00:00Z"}
                self.server.schedules.insert(0, created)
                self._json(201, created)
                return
            if resource.endswith("/trigger"):
                occurrence = {"occurrenceId": "O-NOW", "scheduleId": resource.split("/")[1], "scheduledFor": "2026-10-14T10:00:00Z", "triggerType": "manual", "status": "submitted", "jobId": "JOB-4821"}
                self.server.schedule_occurrences.insert(0, occurrence)
                self._json(202, occurrence)
                return
            if resource.endswith("/pause") or resource.endswith("/resume"):
                schedule_id = resource.split("/")[1]
                enabled = resource.endswith("/resume")
                updated = next((item for item in self.server.schedules if item["id"] == schedule_id), None)
                if updated is None:
                    self._json(404, {"error": {"code": "NOT_FOUND", "summary": "Schedule not found."}})
                else:
                    updated["enabled"] = enabled
                    self._json(200, updated)
                return
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

    def do_PATCH(self) -> None:
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
        if resource.startswith("load-schedules"):
            status = self._schedule_access_error(scenario)
            if status:
                return
            schedule_id = resource.split("/")[1]
            updated = next((item for item in self.server.schedules if item["id"] == schedule_id), None)
            if updated is None:
                self._json(404, {"error": {"code": "NOT_FOUND", "summary": "Schedule not found."}})
            else:
                updated.update(body)
                self._json(200, updated)
            return
        self._json(404, {"error": {"code": "FIXTURE_NOT_FOUND", "summary": "No fixture route is defined."}})

    def do_DELETE(self) -> None:
        request = urlsplit(self.path)
        if not request.path.startswith("/att/api/v1/"):
            self.send_error(404)
            return
        resource = request.path.removeprefix("/att/api/v1/")
        scenario = self._fixture()
        if scenario in {"auth-admin", "auth-user"} and resource.startswith("auth/users/"):
            if self.headers.get("X-ATT-CSRF") != "csrf-fixture":
                self._json(403, {"error": {"code": "ATT-AUTH-CSRF-INVALID", "summary": "Request verification failed."}})
                return
            if scenario != "auth-admin":
                self._json(403, {"error": {"code": "ATT-SERVER-FORBIDDEN", "summary": "Administrator access is required."}})
                return
            login_id = unquote(resource.removeprefix("auth/users/"))
            user = next((item for item in self.server.auth_users if item["loginId"] == login_id), None)
            if user is None:
                self._json(404, {"error": {"code": "ATT-SERVER-NOT-FOUND", "summary": "User was not found."}})
            else:
                user["active"] = False
                self.server.auth_revoked_users.append(login_id)
                self._json(200, {"disabled": True})
            return
        if self._fixture() == "load-workspace" and resource == "jobs/" + LOAD_JOB["jobId"]:
            self.server.cancel_requested = True
            self._json(202, {"jobId": LOAD_JOB["jobId"], "status": "CANCEL_REQUESTED"})
            return
        if resource.startswith("load-schedules/"):
            scenario = self._fixture()
            if self._schedule_access_error(scenario):
                return
            schedule_id = resource.split("/")[1]
            self.server.schedules = [item for item in self.server.schedules if item["id"] != schedule_id]
            self._json(200, {"scheduleId": schedule_id, "status": "DELETED"})
            return
        self._json(404, {"error": {"code": "FIXTURE_NOT_FOUND", "summary": "No fixture route is defined."}})

    def _json(self, status: int, value: dict[str, object], headers: dict[str, str] | None = None) -> None:
        body = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for name, header_value in (headers or {}).items():
            self.send_header(name, header_value)
        self.end_headers()
        self.wfile.write(body)

    def _fixture(self) -> str:
        cookies = SimpleCookie()
        cookies.load(self.headers.get("Cookie", ""))
        morsel = cookies.get("att-ui-fixture")
        return morsel.value if morsel else "loaded"

    def _schedule_access_error(self, scenario: str) -> bool:
        if scenario == "schedule-unauthenticated":
            self._json(401, {"error": {"code": "AUTHENTICATION_REQUIRED", "summary": "Sign in to manage Scheduled Load."}})
            return True
        if scenario == "schedule-forbidden":
            self._json(403, {"error": {"code": "SCHEDULE_ADMIN_REQUIRED", "summary": "Scheduled Load administrator role required."}})
            return True
        if scenario == "schedule-disabled":
            self._json(404, {"error": {"code": "SCHEDULING_DISABLED", "summary": "Scheduled Load is disabled."}})
            return True
        return False

    def _api(self, resource: str, query: dict[str, list[str]]) -> None:
        scenario = self._fixture()
        if resource == "version":
            schedules_enabled = scenario in {"scheduled-workspace", "schedule-forbidden", "schedule-unauthenticated"}
            auth_mode = "local" if scenario in {"auth-admin", "auth-user"} else "container" if scenario == "auth-container" else None
            payload = {"apiVersion": "1", "inlineLoadEnabled": True, "loadSchedulingEnabled": schedules_enabled}
            if auth_mode:
                payload["authMode"] = auth_mode
            self._json(200, payload)
            return
        if scenario == "authenticated" and self.headers.get("Authorization") != "Basic YXR0LWZpeHR1cmU6cGFzcw==":
            self._json(401, {"error": {"code": "AUTHENTICATION_REQUIRED", "summary": "Fixture authentication is required."}})
            return
        parts = [unquote(part) for part in resource.strip("/").split("/") if part]
        if scenario in {"auth-admin", "auth-user"} and parts == ["auth", "me"]:
            user = self.server.auth_users[0 if scenario == "auth-admin" else 1]
            user_role = "ADMIN" if scenario == "auth-admin" else "USER"
            self._json(200, {"loginId": user["loginId"], "displayName": user["displayName"], "role": user_role, "csrfToken": "csrf-fixture"}, {"X-ATT-CSRF": "csrf-fixture"})
            return
        if scenario in {"auth-admin", "auth-user", "auth-container"} and parts == ["auth", "bootstrap", "status"]:
            self._json(200, {"required": False})
            return
        if scenario in {"auth-admin", "auth-user"} and parts == ["auth", "users"]:
            self.server.auth_user_list_requests += 1
            if scenario != "auth-admin":
                self._json(403, {"error": {"code": "ATT-SERVER-FORBIDDEN", "summary": "Administrator access is required."}})
            else:
                self._json(200, {"items": [dict(user) for user in self.server.auth_users]})
            return
        if parts and parts[0] == "load-schedules":
            if self._schedule_access_error(scenario):
                return
            if parts == ["load-schedules"]:
                self._json(200, {"items": self.server.schedules})
                return
            if len(parts) == 3 and parts[2] == "executions":
                occurrences = [item for item in self.server.schedule_occurrences if item.get("scheduleId") == parts[1]]
                self._json(200, {"items": occurrences[: int(query.get("limit", ["100"])[0])]})
                return
            if len(parts) == 2:
                schedule = next((item for item in self.server.schedules if item["id"] == parts[1]), None)
                self._json(200 if schedule else 404, schedule or {"error": {"code": "NOT_FOUND", "summary": "Schedule not found."}})
                return
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
            self._json(200, {
                "model": query.get("model", ["virtualUsers"])[0],
                "policy": {
                    "enabled": True, "maxVirtualUsers": 200, "maxArrivalRate": 50,
                    "load": {"duration": "10s", "users": 2, "arrivalRate": "2/s", "maxConcurrent": 5},
                    "execution": {"thinkTime": "250ms"},
                    "thresholds": {"errorRate": 0.01, "p95Ms": 500},
                    "evidence": {"mode": "failures"},
                },
                "previewYaml": "load:\n  enabled: true\n  duration: 10s\n", "redacted": False,
            })
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
            if scenario == "scheduled-workspace" and job_id == "JOB-4821":
                if len(parts) == 2:
                    self._json(200, JOBS[0])
                    return
                if parts[2] == "result":
                    self._json(200, {"job": JOBS[0], "result": {"status": "PASS", "metrics": {"completedThroughput": 120, "p95Ms": 85}}, "diagnostic": None})
                    return
                if parts[2] == "artifacts" and len(parts) == 3:
                    self._json(200, {"items": []})
                    return
                if parts[2] == "events":
                    self._events()
                    return
            if scenario == "explorer-workspace" and job_id == "JOB-DEBUG-204":
                self._json(200, {"jobId": job_id, "packageId": "payments", "command": "debug", "status": "PASS", "createdAt": "2026-10-13T09:50:00Z"})
                return
            self._json(404, {"error": {"code": "JOB_NOT_FOUND", "summary": "Job not found."}})
            return
        elif len(parts) == 3 and parts[:2] == ["packages", "payments"] and parts[2] == "configuration":
            self._json(200, {
                "state": "ready", "view": "declared", "environments": [
                    {"name": "dev", "state": "active", "default": True},
                    {"name": "staging", "state": "active"}, {"name": "prod", "state": "active"},
                ],
                "globals": {"baseDir": {"state": "visible", "value": "config"}},
                "sections": [
                    {"id": "testdata", "title": "Testdata", "entryCount": 1},
                    {"id": "helpers", "title": "Helpers", "entryCount": 1},
                ],
            })
            return
        elif len(parts) == 4 and parts[:3] == ["packages", "payments", "configuration"] and parts[3] == "files":
            requested = query.get("path", [""])[0].strip("/")
            search = query.get("query", [""])[0].lower()
            if requested == "":
                items = [
                    {"name": "config", "path": "config", "kind": "directory"},
                    {"name": "load", "path": "load", "kind": "directory"},
                ]
            elif requested == "config":
                items = [
                    {"name": "application.yaml", "path": "config/application.yaml", "kind": "file", "format": "yaml", "sizeBytes": 248, "sourceAvailable": True},
                    {"name": "config.yaml", "path": "config/config.yaml", "kind": "file", "format": "yaml", "sizeBytes": 432, "sourceAvailable": True},
                    {"name": "restricted.yaml", "path": "config/restricted.yaml", "kind": "file", "format": "yaml", "sizeBytes": 96, "sourceAvailable": False, "reason": "visibility-policy"},
                    {"name": "testdata", "path": "config/testdata", "kind": "directory"},
                ]
            elif requested == "config/testdata":
                items = [{"name": "accounts.yaml", "path": "config/testdata/accounts.yaml", "kind": "file", "format": "yaml", "sizeBytes": 156, "sourceAvailable": True}]
            elif requested == "load":
                items = [{"name": "load.visualuser.yaml", "path": "load/load.visualuser.yaml", "kind": "file", "format": "yaml", "sizeBytes": 188, "sourceAvailable": False, "reason": "visibility-policy"}]
            else:
                items = []
            if search:
                all_items = [
                    {"name": "application.yaml", "path": "config/application.yaml", "kind": "file", "format": "yaml", "sizeBytes": 248, "sourceAvailable": True},
                    {"name": "config.yaml", "path": "config/config.yaml", "kind": "file", "format": "yaml", "sizeBytes": 432, "sourceAvailable": True},
                    {"name": "accounts.yaml", "path": "config/testdata/accounts.yaml", "kind": "file", "format": "yaml", "sizeBytes": 156, "sourceAvailable": True},
                ]
                items = [item for item in all_items if search in item["path"].lower()]
            offset = int(query.get("offset", ["0"])[0])
            limit = int(query.get("limit", ["100"])[0])
            page = items[offset:offset + limit]
            next_offset = offset + len(page) if offset + len(page) < len(items) else None
            self._json(200, {"path": requested, "items": page, "total": len(items), "nextOffset": next_offset, "truncated": False})
            return
        elif len(parts) == 4 and parts[:3] == ["packages", "payments", "configuration"] and parts[3] == "source":
            file_path = query.get("path", [""])[0]
            text = {
                "config/application.yaml": "server:\n  environment: dev\n  timeout: 5s\n# descriptor: config/testdata/accounts.yaml\n",
                "config/config.yaml": "schemaVersion: att-config/v1\nprofiles:\n  - name: dev\n    config: application.yaml\n",
                "config/testdata/accounts.yaml": "schemaVersion: att-testdata/v1\nrecords:\n  - id: account-001\n    tier: standard\n",
            }.get(file_path)
            if text is None:
                self._json(200, {"available": False, "reason": "visibility-policy", "logicalPath": file_path, "format": "yaml"})
            else:
                self._json(200, {"available": True, "logicalPath": file_path, "format": "yaml", "text": text})
            return
        elif len(parts) == 4 and parts[:3] == ["packages", "payments", "configuration"] and parts[3] == "effective":
            environment = query.get("environment", ["dev"])[0]
            section = query.get("section", ["globals"])[0]
            if section == "testdata":
                entries = [{
                    "id": "accounts", "source": "config/testdata/accounts.yaml", "generated": False,
                    "fields": {
                        "recordCount": {"state": "visible", "value": 3},
                        "selection.strategy": {"state": "visible", "value": "roundRobin"},
                        "selection.exhaustion": {"state": "visible", "value": "cycle"},
                    },
                    "references": [{"type": "template", "resourceId": "checkout", "resolution": "resolved"}],
                }]
                sections = [{"id": "testdata", "title": "Testdata", "entries": entries}]
            else:
                sections = [
                    {"id": "testdata", "title": "Testdata", "entries": []},
                    {"id": "helpers", "title": "Helpers", "entries": []},
                ]
            self._json(200, {
                "state": "ready", "view": "effective", "environment": environment, "section": section,
                "globals": {"baseDir": {"state": "visible", "value": "/opt/att/packages/payments/config"}},
                "sections": sections, "nextOffset": None,
            })
            return
        elif len(parts) == 4 and parts[:3] == ["packages", "payments", "configuration"] and parts[3] == "compare":
            self._json(200, {
                "state": "ready", "view": "compare",
                "leftEnvironment": query.get("left", ["dev"])[0], "rightEnvironment": query.get("right", ["staging"])[0],
                "fields": [{"path": "database.password", "left": {"state": "hidden"}, "right": {"state": "missing"}, "change": "changed"}],
                "nextOffset": None,
            })
            return
        elif len(parts) == 6 and parts[:4] == ["packages", "payments", "configuration", "testdata"] and parts[5] == "records":
            self._json(200, {
                "descriptorId": parts[4], "environment": query.get("environment", ["dev"])[0],
                "state": "ready", "offset": int(query.get("offset", ["0"])[0]),
                "limit": int(query.get("limit", ["50"])[0]), "total": 3, "nextOffset": None,
                "redactionEnabled": True, "records": [
                    {"index": 0, "sequence": 1, "value": {"id": "account-001", "tier": "standard"}},
                    {"index": 1, "sequence": 2, "value": {"id": "account-002", "tier": "business"}},
                    {"index": 2, "sequence": 3, "value": {"id": "account-003", "tier": "standard"}},
                ],
            })
            return
        elif scenario == "explorer-workspace" and len(parts) == 3 and parts[:2] == ["packages", "payments"] and parts[2] in {"config", "declared-configuration", "effective-configuration", "compare-configuration"}:
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
            if scenario in {"loaded", "empty", "error", "loading", "authenticated", "anonymous", "explorer-workspace", "load-workspace", "scheduled-workspace", "schedule-forbidden", "schedule-unauthenticated", "schedule-disabled", "auth-admin", "auth-user", "auth-container"}:
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
    server.auth_users = [
        {"loginId": "admin", "displayName": "Studio Admin", "role": "ADMIN", "active": True},
        {"loginId": "jane", "displayName": "Jane Tester", "role": "USER", "active": True},
    ]
    server.auth_user_list_requests = 0
    server.auth_password_changes = 0
    server.auth_password_resets = []
    server.auth_revoked_users = []
    server.schedules = [dict(SCHEDULE)]
    server.schedule_occurrences = list(SCHEDULE_OCCURRENCES)
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
