# SPDX-License-Identifier: Apache-2.0
"""Unit tests for traceability_gate.py (M-096). Run: python -I -m unittest discover -s .github/scripts"""
from __future__ import annotations

import io
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import traceability_gate as g

HEADER = "id,family,defined_in,title,stage,status,where (module:file / pack),tests that prove it,proves (tests only),readme_refs,note\n"


class TraceabilityGateTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def register(self, body: str, header: str = HEADER) -> Path:
        p = self.dir / "TRACEABILITY-REGISTER.csv"
        p.write_text(header + body, encoding="utf-8")
        return p

    def run_main(self, *args: str) -> tuple[int, str]:
        out = io.StringIO()
        with redirect_stdout(out):
            code = g.main(list(args))
        return code, out.getvalue()

    def test_placeholders_are_not_tests(self):
        self.assertEqual([], g.proving_tests(""))
        self.assertEqual([], g.proving_tests(None))
        self.assertEqual([], g.proving_tests("add test"))
        self.assertEqual([], g.proving_tests("No Test"))
        self.assertEqual(["T23"], g.proving_tests("T23, add test"))
        self.assertEqual(["T06", "T07"], g.proving_tests("T06; T07"))
        self.assertEqual(["PackDeploymentTest+AllPacksSmokeTest (deploy/first wait)"],
                         g.proving_tests("PackDeploymentTest+AllPacksSmokeTest (deploy/first wait)"))

    def test_only_claimed_requirements_outside_family_t_are_subjects(self):
        p = self.register(
            'R1,R,S:1,a,C,implemented,m,"T06, T07",,1,\n'
            "R2,R,S:2,b,C,implemented,m,,,1,\n"
            "R3,R,S:3,c,C,partial,m,add test,,1,\n"
            "R4,R,S:4,d,C,test-pending,m,no test,,1,\n"
            "R5,R,S:5,e,C,gap,m,,,1,\n"
            "R6,R,S:6,f,C,data-only,m,,,1,\n"
            "R7,R,S:7,g,C,owner-side,m,,,1,\n"
            "T01,T,S:8,h,C,implemented,m,,R1,1,\n")
        rows = g.load(p)
        self.assertEqual(["R2", "R3", "R4"], [r["id"] for r in g.unproven(rows)])
        self.assertEqual(4, sum(1 for r in rows if g.is_subject(r)))

    def test_report_only_exits_zero_and_writes_the_summary(self):
        p = self.register("R2,R,S:2,b,C,implemented,m,,,1,\n")
        summary = self.dir / "summary.md"
        code, out = self.run_main("--register", str(p), "--summary", str(summary))
        self.assertEqual(0, code)
        self.assertIn("R2\tR\timplemented\tno proving test", out)
        self.assertIn("::warning title=Traceability gate (report-only)::1 requirement rows", out)
        text = summary.read_text(encoding="utf-8")
        self.assertIn("### Traceability gate (report-only)", text)
        self.assertIn("| R | 1 |", text)
        self.assertIn("Ids: R2", text)

    def test_enforce_exits_one_on_a_finding_and_zero_without(self):
        p = self.register("R2,R,S:2,b,C,implemented,m,,,1,\n")
        self.assertEqual(1, self.run_main("--register", str(p), "--enforce")[0])
        p = self.register('R1,R,S:1,a,C,implemented,m,"T06",,1,\n')
        code, out = self.run_main("--register", str(p), "--enforce")
        self.assertEqual(0, code)
        self.assertIn("0 of 1 requirement rows", out)

    def test_broken_register_is_an_error_in_both_modes(self):
        missing = self.dir / "absent.csv"
        self.assertEqual(2, self.run_main("--register", str(missing))[0])
        p = self.register("R1,R,implemented\n", header="id,family,status\n")
        code, out = self.run_main("--register", str(p))
        self.assertEqual(2, code)
        self.assertIn("missing column(s) ['tests that prove it']", out)
        p = self.register("R1,R,S:1,a,C,implemented,m,T06,,1,\nR1,R,S:1,a,C,implemented,m,T07,,1,\n")
        code, out = self.run_main("--register", str(p), "--enforce")
        self.assertEqual(2, code)
        self.assertIn("duplicate id(s) ['R1']", out)

    def test_short_rows_and_bom_are_read(self):
        p = self.dir / "TRACEABILITY-REGISTER.csv"
        p.write_text("﻿" + HEADER + "R2,R,S:2,b,C,implemented\n", encoding="utf-8")
        self.assertEqual(["R2"], [r["id"] for r in g.unproven(g.load(p))])

    def test_summary_lists_at_most_the_limit(self):
        rows = [{"id": f"R{i}", "family": "R", "status": "implemented", "tests that prove it": ""}
                for i in range(g.SUMMARY_LIMIT + 3)]
        text = g.summary_markdown(rows, len(rows), enforce=True)
        self.assertIn("### Traceability gate (enforced)", text)
        self.assertIn("(and 3 more in the job log)", text)
        self.assertNotIn(f"R{g.SUMMARY_LIMIT}", text.split("Ids: ")[1].split(" (and")[0].split(", "))


if __name__ == "__main__":
    unittest.main()
