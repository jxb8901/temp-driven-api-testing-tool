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
        repeat = self.run_cli(*options, "--write")
        self.assertNotEqual(0, repeat.returncode)
        self.assertIn("Refusing to overwrite", repeat.stderr)

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
