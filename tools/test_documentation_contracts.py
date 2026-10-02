"""Regression tests for the documentation gate, independent of checked-in text."""
import unittest
from pathlib import Path
from documentation_contracts import (active_schemas, stale_claims, current_html,
                                     manifest_errors, structure_errors, overview_resource_errors,
                                     chapter_label_errors)

VERSION = "3.7.0"
CATALOG = """schemaVersion: att-schema-catalog/v3.0
schemas:
  att-load/v1.5: att-load-v1.5.schema.json
  att-load/v1.4: history/att-load-v1.4.schema.json
  globalConfig: att-config-v2.11.schema.json
  att-testdata/v1.0: att-testdata-v1.0.schema.json
"""


class DocumentationContractsTest(unittest.TestCase):
    def setUp(self):
        self.active = active_schemas(CATALOG)

    def test_catalog_history_does_not_override_active_version(self):
        self.assertEqual({"att-load": "1.5", "att-config": "2.11", "att-testdata": "1.0"}, self.active)

    def test_stale_schemas_in_normal_text_examples_and_filenames_fail(self):
        for text in ("schemaVersion: att-load/v1.2", "Load v1.2",
                     "schemas/att-config-v2.9.schema.json", "ATT 3.6.2"):
            with self.subTest(text=text):
                self.assertTrue(stale_claims(text, self.active, VERSION))

    def test_current_schemas_pass(self):
        self.assertEqual([], stale_claims("ATT 3.7.0; att-load/v1.5; config v2.11; att-testdata/v1.0",
                                          self.active, VERSION))

    def test_testdata_mapping_example_is_bootstrap_safe_and_uses_selected_record_paths(self):
        root = Path(__file__).resolve().parents[1]
        paths = (
            root / "docs/reference/02_test_authoring.md",
            root / "docs/reference.zh/02_test_authoring.md",
            root / "docs/reference.html",
            root / "docs/reference.zh.html",
        )
        for path in paths:
            with self.subTest(path=path):
                text = path.read_text(encoding="utf-8")
                self.assertNotIn("ORD-${EXEC.INPUT.region}", text)
                self.assertNotIn("@{accounts[0].id}", text)
                self.assertIn("@{accounts.id}", text)

    def test_input_mapping_pre_input_context_contract_is_published(self):
        root = Path(__file__).resolve().parents[1]
        english = (
            root / "docs/reference/02_test_authoring.md",
            root / "docs/reference.md",
        )
        chinese = (
            root / "docs/reference.zh/02_test_authoring.md",
            root / "docs/reference.zh.md",
        )
        for path in english:
            with self.subTest(path=path):
                text = path.read_text(encoding="utf-8")
                self.assertIn("before the scheduler starts for Load", text)
                self.assertIn("Load workload `inputs`", text)
                self.assertIn("`EXEC.ID` and `EXEC.OUTPUT_DIR` are initialized only after input resolution", text)
                self.assertIn("`EXEC.VARS`, `EXEC.ACTIONS`", text)
        for path in chinese:
            with self.subTest(path=path):
                text = path.read_text(encoding="utf-8")
                self.assertIn("Load 則在 scheduler 啟動前驗證", text)
                self.assertIn("Load workload `inputs`", text)
                self.assertIn("`EXEC.ID` 與 `EXEC.OUTPUT_DIR` 只會在 input 解析後初始化", text)
                self.assertIn("`EXEC.VARS`、`EXEC.ACTIONS`", text)

    def test_input_mapping_contract_does_not_claim_builtin_calls(self):
        root = Path(__file__).resolve().parents[1]
        paths = (
            root / "docs/reference/02_test_authoring.md",
            root / "docs/reference.zh/02_test_authoring.md",
            root / "docs/reference.md",
            root / "docs/reference.zh.md",
            root / "docs/reference.html",
            root / "docs/reference.zh.html",
        )
        for path in paths:
            with self.subTest(path=path):
                text = path.read_text(encoding="utf-8")
                self.assertNotIn("bootstrap-safe built-ins", text)
                self.assertNotIn("Calls are limited to pure bootstrap-safe", text)
                self.assertNotIn("Calls 只允許 pure bootstrap-safe", text)
        for path in (root / "docs/reference/02_test_authoring.md", root / "docs/reference.md"):
            self.assertIn("built-in calls are not evaluated", path.read_text(encoding="utf-8"))
        for path in (root / "docs/reference.zh/02_test_authoring.md", root / "docs/reference.zh.md"):
            self.assertIn("不會評估 built-in call", path.read_text(encoding="utf-8"))

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


    def test_html_historical_markers_outside_appendices_are_preserved(self):
        # Compact HTML reproduces both stripping and boundary-loss failures.
        text = ("<h2>05 Expressions</h2>"
                "<!-- att-docs:historical --><p>att-load/v1.2; ATT 3.6.0</p>"
                "<!-- /att-docs:historical --><p>att-load/v1.5</p>")
        self.assertEqual([], stale_claims(current_html(text), self.active, VERSION))
        # Identical stale text following the closing marker remains an error.
        outside = text + "<p>att-load/v1.2</p>"
        self.assertEqual(1, len(stale_claims(current_html(outside), self.active, VERSION)))

    def test_html_historical_markers_must_be_balanced(self):
        for text in ("<!-- att-docs:historical --><p>att-load/v1.2</p>",
                     "<!-- /att-docs:historical --><p>att-load/v1.2</p>"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                stale_claims(current_html(text), self.active, VERSION)

    def test_overview_requires_five_peers_in_definition_text_and_diagram(self):
        names = "Tool, DBHelper, MQHelper, HTTPHelper and SSHHelper"
        good = ("A **Resource** is " + names + ".\n"
                "### Resources are peers\n" + names + " are peers.\n"
                "\x60\x60\x60text\nTool --\\\nDBHelper --+\nMQHelper --+\n"
                "HTTPHelper --+\nSSHHelper --/\n\x60\x60\x60\n")
        self.assertEqual([], overview_resource_errors(good))
        zh = good.replace("A **Resource** is", "**Resource** 是")
        zh = zh.replace("### Resources are peers", "### 五種 Resource 是同級概念")
        self.assertEqual([], overview_resource_errors(zh))
        variants = (
            good.replace("A **Resource** is " + names,
                         "A **Resource** is Tool, DBHelper or MQHelper"),
            good.replace(names + " are peers.", "Tool, DBHelper and MQHelper are peers."),
            good.replace("HTTPHelper --+\n", ""),
            good.replace("SSHHelper --/\n", ""),
            zh.replace("五種 Resource", "三種 Resource"),
        )
        for text in variants:
            with self.subTest(text=text):
                self.assertTrue(overview_resource_errors(text))

    def test_secondary_link_labels_use_current_taxonomy(self):
        for label, target in (
                ("Environment and Test Data", "09_configuration.md"),
                ("Validation and Diagnostics", "12_validation_diagnostics.md")):
            for root in ("reference/", "reference.zh/"):
                with self.subTest(label=label, root=root):
                    self.assertTrue(chapter_label_errors("[%s](%s%s)" % (label, root, target)))
        self.assertEqual([], chapter_label_errors(
            "[Configuration and Environments](reference/09_configuration.md)\n"
            "[Validation and Troubleshooting](reference.zh/12_validation_diagnostics.md)"))
        self.assertEqual([], chapter_label_errors(
            "<!-- att-docs:historical -->\n"
            "[Environment and Test Data](old.md)\n"
            "<!-- /att-docs:historical -->"))

    def test_manifest_rejects_duplicates_traversal_and_absolute_paths(self):
        for items in ([], ["a.md", "a.md"], ["../a.md"], ["/a.md"],
                      ["x/../a.md"], ["x//a.md"], ["x\\a.md"]):
            self.assertTrue(manifest_errors(items), items)
        self.assertEqual([], manifest_errors(["a.md", "x/b.md"]))

    def test_duplicate_number_missing_appendix_and_order_drift_fail(self):
        good = "\n".join(["## %02d Chapter %s" % (n, n) for n in range(1, 14)] +
                         ["## Appendix " + c + " — Lookup " + c for c in "ABCD"])
        self.assertEqual([], structure_errors(good))
        self.assertTrue(structure_errors(good.replace("## 03", "## 02")))
        self.assertTrue(structure_errors(good.replace("Chapter 3", "Chapter 2")))
        self.assertTrue(structure_errors(good.replace("## Appendix D — Lookup D", "")))
        self.assertTrue(structure_errors(good.replace("## 03", "## 04")
                                             .replace("## 05", "## 03")))


if __name__ == "__main__":
    unittest.main()
