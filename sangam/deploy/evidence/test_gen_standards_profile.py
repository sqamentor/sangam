# SPDX-License-Identifier: Apache-2.0
"""Unit tests of gen_standards_profile.py (M-052). Standard library only: python -I test_gen_standards_profile.py"""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_standards_profile as g  # noqa: E402

MD = """# Doc
## X.0g ADR-D-26: Sangam FEEL policy (exact numbers, strict evaluation) (DECIDED 2026-11-08)

Intro.

| Topic | Rule |
|---|---|
| Exact numbers | Never `Double`. |
| Strict evaluation | A failure fails the decision \\| no null. |

## X.0h Next
| Topic | Rule |
|---|---|
| Not this | x |
"""
COMPAT = {"generatedAt": "2026-01-01T00:00:00Z", "rows": [
    {"notation": "DMN", "construct": "Context", "modeller": "SOURCE_EDITOR", "engine": "PARSED", "status": "SUPPORTED",
     "note": "", "source": "Operaton", "xml": ["context"]},
    {"notation": "BPMN", "construct": "ScriptTask", "modeller": "PALETTE", "engine": "PARSED", "status": "REJECTED",
     "note": "Rejected at deploy | worker", "source": "SangamBpmnParseListener", "xml": ["scriptTask"]},
    {"notation": "BPMN", "construct": "UserTask", "modeller": "PALETTE", "engine": "PARSED", "status": "SUPPORTED",
     "note": "", "source": "Operaton", "xml": ["userTask"]},
]}


class StandardsProfileTest(unittest.TestCase):

    def test_feel_policy_is_the_x0g_table_only(self):
        rows = g.feel_policy(MD)
        self.assertEqual(["Exact numbers", "Strict evaluation"], [r["topic"] for r in rows])

    def test_rows_are_ordered_by_notation_then_status_and_counted(self):
        p = g.profile(COMPAT, g.feel_policy(MD), "0.1.0")
        self.assertEqual(["UserTask", "ScriptTask", "Context"], [r["construct"] for r in p["constructs"]])
        bpmn = next(n for n in p["notations"] if n["notation"] == "BPMN")
        self.assertEqual(1, bpmn["counts"]["SUPPORTED"])
        self.assertEqual(1, bpmn["counts"]["REJECTED"])

    def test_output_is_deterministic_and_escapes_table_cells(self):
        p = g.profile(COMPAT, g.feel_policy(MD), "0.1.0")
        md = g.markdown(p)
        self.assertEqual(md, g.markdown(g.profile(COMPAT, g.feel_policy(MD), "0.1.0")))
        self.assertIn("Rejected at deploy \\| worker", md)
        self.assertEqual(p, json.loads(json.dumps(p)))

    def test_partial_is_a_status_below_supported_and_needs_a_note(self):
        row = dict(COMPAT["rows"][0], notation="CMMN", construct="DiscretionaryItem", status="PARTIAL",
                   note="Partial: applicable items listed, runtime planning not supported")
        p = g.profile({"rows": [row]}, [], "0.1.0")
        self.assertEqual("PARTIAL", p["constructs"][0]["status"])
        self.assertEqual(1, next(n for n in p["notations"] if n["notation"] == "CMMN")["counts"]["PARTIAL"])
        self.assertIn("| DiscretionaryItem | Partial |", g.markdown(p))
        for empty in ("", "   ", None):
            with self.assertRaises(SystemExit):
                g.profile({"rows": [dict(row, note=empty)]}, [], "0.1.0")

    def test_unknown_status_is_refused(self):
        bad = {"rows": [dict(COMPAT["rows"][0], status="MAYBE")]}
        with self.assertRaises(SystemExit):
            g.profile(bad, [], "0.1.0")

    def test_out_dir_writes_the_tag_version_and_leaves_the_committed_files(self):
        import tempfile
        before = {p: open(p, "rb").read() for p in (g.OUT_JSON, g.OUT_MD) if os.path.exists(p)}
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(0, g.main(["--version", "9.8.7-rc1", "--out-dir", d]))
            with open(os.path.join(d, "standards-profile.json"), encoding="utf-8") as f:
                self.assertEqual("9.8.7-rc1", json.load(f)["version"])
            self.assertTrue(os.path.exists(os.path.join(d, "STANDARDS-PROFILE.md")))
        self.assertEqual(before, {p: open(p, "rb").read() for p in (g.OUT_JSON, g.OUT_MD) if os.path.exists(p)})
        with self.assertRaises(SystemExit):
            g.main(["--check", "--out-dir", "x"])

    def test_version_drops_snapshot(self):
        pom = "<project><artifactId>sangam-parent</artifactId>\n  <version>0.1.0-SNAPSHOT</version></project>"
        self.assertEqual("0.1.0", g.version_from_pom(pom))


if __name__ == "__main__":
    unittest.main()
