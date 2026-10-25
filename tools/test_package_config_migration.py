#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path
import yaml
from migrate_package_config import inventory, MigrationError


class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.a = self.root / "packages/a"
        self.b = self.root / "packages/b"
        self.a.mkdir(parents=True); self.b.mkdir(parents=True)
        self.runtime = self.root / "config.yaml"
        self.registry = self.root / "server.yaml"
        self.write(self.registry, {"packages": {"allowedRoots": [str(self.root / "packages")],
                                                  "entries": {"a": str(self.a), "b": str(self.b)}}})

    def write(self, path, document):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(yaml.safe_dump(document), encoding="utf-8")

    def test_ownership_usage_environment_empty_and_no_file_changes(self):
        self.write(self.runtime, {"environment": "SIT", "testdata": ["testdata/accounts.yaml", "testdata/unused.yaml"],
                                 "environments": {"SIT": {}, "UAT": {"testdata": []}}})
        self.write(self.a / "testdata/accounts.yaml", {"schemaVersion": "att-testdata/v1.0", "id": "accounts", "records": [{"secret": "never-output"}]})
        self.write(self.a / "testdata/unused.yaml", {"schemaVersion": "att-testdata/v1.0", "id": "unused", "records": [{"value": "private"}]})
        self.write(self.a / "templates/example/template.yaml", {"value": "@{accounts.id}"})
        plan = inventory(self.runtime, self.registry)
        self.assertTrue(plan["dryRun"])
        self.assertEqual(["testdata/accounts.yaml"], plan["packages"][0]["manifest"]["testdata"]["descriptors"])
        self.assertEqual([], plan["packages"][0]["manifest"]["testdata"]["environments"]["UAT"])
        self.assertEqual([], plan["packages"][1]["manifest"]["testdata"]["descriptors"])
        self.assertNotIn("never-output", str(plan)); self.assertNotIn("private", str(plan))
        self.assertFalse((self.a / "package.yaml").exists()); self.assertFalse((self.b / "package.yaml").exists())

    def test_existing_manifest_is_not_overwritten_and_escape_is_reported(self):
        self.write(self.runtime, {"environment": "SIT", "testdata": ["../other.yaml"]})
        self.write(self.a / "package.yaml", {"schemaVersion": "att-package/v1"})
        plan = inventory(self.runtime, self.registry)
        self.assertEqual("manifest-exists", plan["packages"][0]["status"])
        self.assertEqual("invalid-or-outside-package", plan["packages"][1]["diagnostics"][0]["reason"])

    def test_workbook_references_are_bounded_and_scoped_to_registered_package(self):
        import zipfile
        self.write(self.runtime, {"testdata": ["testdata/accounts.yaml"], "report": {"fileNamePattern": "private-never-output"}})
        self.write(self.a / "testdata/accounts.yaml", {"schemaVersion": "att-testdata/v1.0", "id": "accounts", "records": [{"secret": "never-output"}]})
        workbook = self.a / "testcase/example.xlsx";workbook.parent.mkdir(parents=True)
        with zipfile.ZipFile(workbook, "w") as archive:
            archive.writestr("xl/sharedStrings.xml", "<si><t>@{accounts.id}</t></si>")
        plan = inventory(self.runtime, self.registry)
        self.assertEqual(["testdata/accounts.yaml"], plan["packages"][0]["manifest"]["testdata"]["descriptors"])
        self.assertEqual([], plan["packages"][1]["manifest"]["testdata"]["descriptors"])
        self.assertNotIn("never-output", str(plan))
        self.assertIn("report.fileNamePattern", plan["inheritedDefaults"])

    def test_duplicate_yaml_keys_and_aliases_are_rejected_without_writes(self):
        for source in ("environment: SIT\nenvironment: UAT\n", "value: &value {}\nother: *value\n"):
            self.runtime.write_text(source, encoding="utf-8")
            with self.assertRaises(MigrationError): inventory(self.runtime, self.registry)
        self.assertFalse((self.a / "package.yaml").exists())

    def test_duplicate_descriptor_ids_and_wrong_contract_are_not_proposed(self):
        self.write(self.runtime, {"testdata": ["testdata/one.yaml", "testdata/two.yaml", "testdata/wrong.yaml"]})
        for name in ("one", "two"):
            self.write(self.a / ("testdata/" + name + ".yaml"), {"schemaVersion": "att-testdata/v1.0", "id": "accounts", "records": [{"id": 1}]})
        self.write(self.a / "testdata/wrong.yaml", {"schemaVersion": "att-package/v1", "id": "wrong"})
        self.write(self.a / "templates/example/template.yaml", {"value": "@{accounts.id} @{wrong.id}"})
        plan = inventory(self.runtime, self.registry)
        self.assertEqual([], plan["packages"][0]["manifest"]["testdata"]["descriptors"])
        diagnostics = plan["packages"][0]["diagnostics"]
        self.assertEqual(2, sum(item["reason"] == "duplicate-id" for item in diagnostics))
        self.assertTrue(any(item["reason"] == "invalid-or-outside-package" for item in diagnostics))

    def test_unregistered_root_is_rejected(self):
        self.write(self.runtime, {})
        self.write(self.registry, {"packages": {"allowedRoots": [str(self.a)], "entries": {"b": str(self.b)}}})
        with self.assertRaises(MigrationError): inventory(self.runtime, self.registry)


if __name__ == "__main__":
    unittest.main()
