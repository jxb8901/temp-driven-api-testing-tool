"""Regression tests for the documentation gate, independent of checked-in text."""
import unittest
from documentation_contracts import (active_schemas, stale_claims, current_html,
                                     manifest_errors, structure_errors)

VERSION = "3.6.1"
CATALOG = """schemaVersion: att-schema-catalog/v3.0
schemas:
  att-load/v1.3: att-load-v1.3.schema.json
  att-load/v1.2: history/att-load-v1.2.schema.json
  globalConfig: att-config-v2.10.schema.json
"""


class DocumentationContractsTest(unittest.TestCase):
    def setUp(self):
        self.active = active_schemas(CATALOG)

    def test_catalog_history_does_not_override_active_version(self):
        self.assertEqual({"att-load": "1.3", "att-config": "2.10"}, self.active)

    def test_stale_schemas_in_normal_text_examples_and_filenames_fail(self):
        for text in ("schemaVersion: att-load/v1.2", "Load v1.2",
                     "schemas/att-config-v2.9.schema.json", "ATT 3.6.0"):
            with self.subTest(text=text):
                self.assertTrue(stale_claims(text, self.active, VERSION))

    def test_current_schemas_pass(self):
        self.assertEqual([], stale_claims("ATT 3.6.1; att-load/v1.3; config v2.10",
                                          self.active, VERSION))

    def test_explicit_historical_block_is_scoped_and_balanced(self):
        text = ("<!-- att-docs:historical -->\natt-load/v1.2\n"
                "<!-- /att-docs:historical -->\natt-load/v1.2")
        self.assertEqual(1, len(stale_claims(text, self.active, VERSION)))
        with self.assertRaises(ValueError):
            stale_claims("<!-- att-docs:historical -->", self.active, VERSION)

    def test_generated_appendix_exemption_does_not_hide_appendix_d(self):
        text = ("## Appendix C — Migration Notes\natt-load/v1.2\n"
                "## Appendix D — Limits\natt-load/v1.2")
        self.assertEqual(1, len(stale_claims(text, self.active, VERSION)))

    def test_generated_html_migration_scope_uses_visible_headings(self):
        text = ("<h2>Appendix C — 遷移</h2><p>att-load/v1.2</p>"
                "<h2>Appendix D — 限制</h2><p>att-load/v1.2</p>")
        self.assertEqual(1, len(stale_claims(current_html(text), self.active, VERSION)))

    def test_manifest_rejects_duplicates_traversal_and_absolute_paths(self):
        for items in ([], ["a.md", "a.md"], ["../a.md"], ["/a.md"],
                      ["x/../a.md"], ["x//a.md"], ["x\\a.md"]):
            self.assertTrue(manifest_errors(items), items)
        self.assertEqual([], manifest_errors(["a.md", "x/b.md"]))

    def test_duplicate_number_missing_appendix_and_order_drift_fail(self):
        good = "\n".join(["## %02d Chapter %s" % (n, n) for n in range(1, 14)] +
                         ["## Appendix " + c + " — Lookup" for c in "ABCD"])
        self.assertEqual([], structure_errors(good))
        self.assertTrue(structure_errors(good.replace("## 03", "## 02")))
        self.assertTrue(structure_errors(good.replace("## Appendix D — Lookup", "")))
        self.assertTrue(structure_errors(good.replace("## 03", "## 04")
                                             .replace("## 05", "## 03")))


if __name__ == "__main__":
    unittest.main()
