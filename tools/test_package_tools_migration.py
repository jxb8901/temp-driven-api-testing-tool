#!/usr/bin/env python3
"""Safety and cutover regressions for the real Package Tool migration CLI."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import yaml

SCRIPT = Path(__file__).resolve().parent / "migrate_package_tools.py"


class PackageToolMigrationTest(unittest.TestCase):
    def setUp(self):
        box = tempfile.TemporaryDirectory()
        self.addCleanup(box.cleanup)
        self.root = Path(box.name)
        self.config = self.root / "config/config.yaml"
        self.config.parent.mkdir(parents=True)
        self.a = self.root / "packages/a"
        self.b = self.root / "packages/b"
        self.a.mkdir(parents=True)
        self.b.mkdir(parents=True)

    def run_cli(self, *options):
        return subprocess.run([sys.executable, str(SCRIPT), "--config", str(self.config),
                               "--package", str(self.a), *options],
                              capture_output=True, text=True, check=False)

    def test_empty_registries_require_explicit_cutover_even_without_definitions(self):
        original = "schemaVersion: att-config/v2.13\nenvironment: SIT\ntools: {}\ntoolGroups: []\n"
        self.config.write_text(original, encoding="utf-8")
        dry = self.run_cli()
        self.assertEqual(0, dry.returncode, dry.stderr)
        self.assertIn("DRY RUN: 0 Package-local descriptors", dry.stdout)
        self.assertEqual(original, self.config.read_text(encoding="utf-8"))
        write = self.run_cli("--write")
        self.assertEqual(0, write.returncode, write.stderr)
        migrated = yaml.safe_load(self.config.read_text(encoding="utf-8"))
        self.assertEqual("SIT", migrated["environment"])
        self.assertNotIn("tools", migrated)
        self.assertNotIn("toolGroups", migrated)
        self.assertFalse((self.a / "tools").exists())

    def test_two_explicit_packages_are_copied_without_shared_fallback(self):
        original = ("schemaVersion: att-config/v2.12\nenvironment: SIT\ntools:\n"
                    "  echo:\n    name: Echo\n    description: Independent\n"
                    "    command: [echo, '${input.message}']\n    stdoutFormat: text\n"
                    "    arguments:\n      message: {name: Message, required: true}\n")
        self.config.write_text(original, encoding="utf-8")
        options = ("--package", str(self.b))
        dry = self.run_cli(*options)
        self.assertEqual(0, dry.returncode, dry.stderr)
        self.assertIn("DRY RUN: 2 Package-local descriptors", dry.stdout)
        self.assertEqual(original, self.config.read_text(encoding="utf-8"))
        self.assertFalse((self.a / "tools/echo.yaml").exists())
        written = self.run_cli(*options, "--write")
        self.assertEqual(0, written.returncode, written.stderr)
        for root in (self.a, self.b):
            tool = yaml.safe_load((root / "tools/echo.yaml").read_text(encoding="utf-8"))
            self.assertEqual("att-tool/v1", tool["schemaVersion"])
            self.assertEqual(["echo", "${input.message}"], tool["command"])
        self.assertNotIn("tools", yaml.safe_load(self.config.read_text(encoding="utf-8")))
        # Repeating an already completed cutover is a harmless no-op.
        repeat = self.run_cli(*options, "--write")
        self.assertEqual(0, repeat.returncode, repeat.stderr)
        # Re-introducing the legacy source must never overwrite existing Package Tools.
        self.config.write_text(original, encoding="utf-8")
        collision = self.run_cli(*options, "--write")
        self.assertNotEqual(0, collision.returncode)
        self.assertIn("Refusing to overwrite", collision.stderr)
        self.assertEqual(original, self.config.read_text(encoding="utf-8"))

    def test_disabled_entries_are_not_migrated(self):
        self.config.write_text(
            "schemaVersion: att-config/v2.12\ntools:\n"
            "  x-disabled: [invalid, value]\n"
            "  echo: {name: Echo, description: Valid, command: [echo], stdoutFormat: text}\n",
            encoding="utf-8")
        result = self.run_cli("--write")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((self.a / "tools/echo.yaml").exists())
        self.assertFalse((self.a / "tools/x-disabled.yaml").exists())

    def test_tool_group_migrates_to_folder_without_changing_caller_ids_or_dispatcher(self):
        group_file = self.root / "config/tools/sample.yaml"
        group_file.parent.mkdir(parents=True)
        group_file.write_text(
            "schemaVersion: att-tool-group/v2.9\n"
            "id: sample\nname: Sample tools\ndescription: Sample group\n"
            "script: [./tools/dispatch.sh]\n"
            "tools:\n"
            "  getAcDate:\n"
            "    name: Account date\n    description: Date\n"
            "    command: [./tools/date.sh]\n    stdoutFormat: text\n"
            "  getSeq:\n"
            "    name: Sequence\n    description: Sequence\n"
            "    command: [./tools/seq.sh]\n    stdoutFormat: text\n",
            encoding="utf-8")
        self.config.write_text(
            "schemaVersion: att-config/v2.13\n"
            "toolGroups: [config/tools/sample.yaml]\n", encoding="utf-8")
        dry = self.run_cli()
        self.assertEqual(0, dry.returncode, dry.stderr)
        self.assertIn("tools/sample/tool-group.yaml", dry.stdout)
        self.assertFalse((self.a / "tools").exists())
        result = self.run_cli("--write")
        self.assertEqual(0, result.returncode, result.stderr)
        folder = self.a / "tools/sample"
        manifest = yaml.safe_load((folder / "tool-group.yaml").read_text(encoding="utf-8"))
        self.assertEqual("att-tool-group/v3.0", manifest["schemaVersion"])
        self.assertEqual("sample", manifest["id"])
        self.assertEqual(["getAcDate.yaml", "getSeq.yaml"], manifest["tools"])
        self.assertEqual(["./tools/dispatch.sh"], manifest["script"])
        tool = yaml.safe_load((folder / "getAcDate.yaml").read_text(encoding="utf-8"))
        self.assertEqual("getAcDate", tool["id"])
        self.assertEqual(["./tools/date.sh"], tool["command"])
        self.assertNotIn("script", tool)
        self.assertNotIn("toolGroups", yaml.safe_load(self.config.read_text(encoding="utf-8")))

    def test_tool_group_ssh_helper_binding_is_retained(self):
        group_file = self.root / "config/tools/app.yaml"
        group_file.parent.mkdir(parents=True)
        group_file.write_text(
            "schemaVersion: att-tool-group/v2.9\nid: app\n"
            "name: App\ndescription: App tools\n"
            "ssh: {helper: appserver, selection: {strategy: roundRobin}}\n"
            "tools:\n"
            "  status: {name: Status, description: Status, command: [echo, ok], stdoutFormat: text}\n",
            encoding="utf-8")
        self.config.write_text(
            "schemaVersion: att-config/v2.13\n"
            "toolGroups: [config/tools/app.yaml]\n", encoding="utf-8")
        result = self.run_cli("--write")
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = yaml.safe_load(
            (self.a / "tools/app/tool-group.yaml").read_text(encoding="utf-8"))
        self.assertEqual("appserver", manifest["ssh"]["helper"])
        self.assertEqual("roundRobin", manifest["ssh"]["selection"]["strategy"])

    def test_env_scoped_tools_in_sit_and_uat_fail_before_any_write(self):
        original = ("schemaVersion: att-config/v2.13\n"
                    "environments:\n"
                    "  SIT:\n    tools:\n      lookup: {name: Lookup, command: [echo]}\n"
                    "  UAT:\n    toolGroups: [config/tools/uat.yaml]\n")
        self.config.write_text(original, encoding="utf-8")
        for options in ((), ("--write",)):
            response = self.run_cli(*options)
            self.assertNotEqual(0, response.returncode)
            self.assertIn("environments.SIT", response.stderr)
            self.assertIn("Environment-scoped legacy Tool", response.stderr)
            self.assertEqual(original, self.config.read_text(encoding="utf-8"))
            self.assertFalse((self.a / "tools").exists())
        self.config.write_text(
            "schemaVersion: att-config/v2.13\n"
            "environments:\n  SIT: {}\n  UAT: {toolGroups: [config/tools/uat.yaml]}\n",
            encoding="utf-8")
        rejected = self.run_cli("--write")
        self.assertNotEqual(0, rejected.returncode)
        self.assertIn("environments.UAT", rejected.stderr)
        self.assertFalse((self.a / "tools").exists())

    def test_group_member_script_paths_are_checked_from_package_root(self):
        group = self.root / "config/tools/sample.yaml"
        group.parent.mkdir(parents=True)
        group.write_text(
            "schemaVersion: att-tool-group/v2.9\nid: sample\n"
            "name: Sample\ndescription: Sample tools\n"
            "script: [./tools/dispatcher.sh]\n"
            "tools:\n"
            "  getAcDate: {name: Date, description: Date tool, command: [./tools/date.sh]}\n",
            encoding="utf-8")
        self.config.write_text(
            "schemaVersion: att-config/v2.13\n"
            "toolGroups: [config/tools/sample.yaml]\n", encoding="utf-8")
        scripts = self.a / "tools"
        scripts.mkdir()
        (scripts / "dispatcher.sh").write_text("#!/bin/sh\n", encoding="utf-8")
        (scripts / "date.sh").write_text("#!/bin/sh\n", encoding="utf-8")
        dry = self.run_cli()
        self.assertEqual(0, dry.returncode, dry.stderr)
        self.assertNotIn("WARNING: review Package-local", dry.stdout)
        (scripts / "date.sh").unlink()
        missing = self.run_cli()
        self.assertEqual(0, missing.returncode, missing.stderr)
        self.assertIn("WARNING: review Package-local script path: ./tools/date.sh", missing.stdout)

    def test_dependency_report_recognizes_db_mq_http_helper_calls(self):
        self.config.write_text(
            "schemaVersion: att-config/v2.13\n"
            "tools:\n"
            '  orders: {name: Orders, description: Query, call: "#{db.orders.query(sql=\\'select 1\\')}"}\n'
            '  broker: {name: Broker, description: Request, call: "#{mq.fpp.publish(payload=\\'x\\')}"}\n'
            '  api: {name: API, description: HTTP, call: "#{http.sut.get(url=\\'/\\')}"}\n',
            encoding="utf-8")
        dry = self.run_cli()
        self.assertEqual(0, dry.returncode, dry.stderr)
        self.assertIn("depends on global Helper(s): db.orders", dry.stdout)
        self.assertIn("depends on global Helper(s): mq.fpp", dry.stdout)
        self.assertIn("depends on global Helper(s): http.sut", dry.stdout)

    def test_unsupported_group_ssh_refuses_before_any_writes(self):
        group = self.root / "config/tools/legacy.yaml"
        group.parent.mkdir(parents=True)
        group.write_text(
            "schemaVersion: att-tool-group/v2.9\nid: demo\nname: Demo\n"
            "ssh: {host: old.example}\ntools:\n"
            "  query: {name: Query, description: Query, command: [echo]}\n",
            encoding="utf-8")
        original = "schemaVersion: att-config/v2.12\ntoolGroups: [config/tools/legacy.yaml]\n"
        self.config.write_text(original, encoding="utf-8")
        result = self.run_cli("--write")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Unsupported legacy Tool Group SSH", result.stderr)
        self.assertEqual(original, self.config.read_text(encoding="utf-8"))
        self.assertFalse((self.a / "tools").exists())


if __name__ == "__main__":
    unittest.main()
