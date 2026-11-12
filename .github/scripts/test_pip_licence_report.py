# SPDX-License-Identifier: Apache-2.0
"""Unit tests for pip_licence_report.py (owner 2026-11-12). Run: python -I -m unittest discover -s .github/scripts"""
from __future__ import annotations

import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import pip_licence_report as r

REPO = Path(__file__).resolve().parents[2]
REAL_POLICY = REPO / "sangam" / "deploy" / "licence" / "licence-policy.json"


class PipLicenceReportTest(unittest.TestCase):

    def setUp(self):
        self.policy = json.loads(REAL_POLICY.read_text(encoding="utf-8"))
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def pkg(self, name, licence, version="1.0"):
        return {"Name": name, "Version": version, "License": licence}

    def test_classifier_names_map_to_policy_ids(self):
        self.assertEqual("allowed", r.classify(self.pkg("click", "BSD License"), self.policy))
        self.assertEqual("allowed", r.classify(self.pkg("attrs", "MIT"), self.policy))
        self.assertEqual("allowed", r.classify(self.pkg("requests", "Apache Software License"), self.policy))
        self.assertEqual("allowed", r.classify(self.pkg("x", "Mozilla Public License 2.0 (MPL 2.0)"), self.policy))
        self.assertEqual("allowed", r.classify(self.pkg("y", "Python Software Foundation License"), self.policy))

    def test_alternatives_and_conjunctions(self):
        self.assertEqual("allowed", r.classify(self.pkg("dual", "GPL-3.0 OR MIT"), self.policy))
        self.assertEqual("allowed", r.classify(self.pkg("multi", "GNU General Public License v3 (GPLv3); MIT License"), self.policy))
        self.assertEqual("forbidden", r.classify(self.pkg("both", "MIT AND GPL-3.0"), self.policy))
        self.assertEqual([["MIT"], ["Apache-2.0"]], r.alternatives("MIT License; Apache Software License"))
        self.assertEqual([["MIT"], ["Apache-2.0"]], r.alternatives("(MIT OR Apache-2.0)"))
        self.assertEqual([["ISC"]], r.alternatives("ISC License (ISCL)"))

    def test_semgrep_is_the_build_time_exception_and_other_lgpl_is_flagged(self):
        self.assertEqual("build-time exception", r.classify(self.pkg("semgrep", "LGPL-2.1"), self.policy))
        self.assertEqual("unknown", r.classify(self.pkg("other-lib", "LGPL-2.1"), self.policy))
        self.assertEqual("forbidden", r.classify(self.pkg("agpl", "GNU Affero General Public License v3"), self.policy))
        self.assertEqual("unknown", r.classify(self.pkg("nodata", "UNKNOWN"), self.policy))
        self.assertEqual("unknown", r.classify(self.pkg("empty", ""), self.policy))

    def test_report_only_exit_zero_and_summary(self):
        packages = self.dir / "p.json"
        packages.write_text(json.dumps([self.pkg("semgrep", "LGPL-2.1", "1.185.0"), self.pkg("click", "BSD License"),
                                        self.pkg("weird", "Custom EULA")]), encoding="utf-8")
        summary = self.dir / "s.md"
        out = io.StringIO()
        with redirect_stdout(out):
            code = r.main(["--packages", str(packages), "--policy", str(REAL_POLICY), "--summary", str(summary)])
        self.assertEqual(0, code)
        text = summary.read_text(encoding="utf-8")
        self.assertIn("3 packages: 0 forbidden, 1 unknown, 1 build-time exception, 1 allowed", text)
        self.assertIn("weird 1.0 (Custom EULA)", text)
        self.assertIn("::warning title=pip licences (report-only)::0 forbidden, 1 unknown", out.getvalue())

    def test_bad_input_is_exit_two(self):
        out = io.StringIO()
        with redirect_stdout(out):
            self.assertEqual(2, r.main(["--packages", str(self.dir / "absent.json"), "--policy", str(REAL_POLICY)]))
        bad = self.dir / "obj.json"
        bad.write_text("{}", encoding="utf-8")
        with redirect_stdout(out):
            self.assertEqual(2, r.main(["--packages", str(bad), "--policy", str(REAL_POLICY)]))


if __name__ == "__main__":
    unittest.main()
