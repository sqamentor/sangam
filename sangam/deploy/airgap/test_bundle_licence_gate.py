# SPDX-License-Identifier: Apache-2.0
"""Unit tests of bundle_licence_gate.py (RM-13, CR-06).

Run: python -I -m unittest discover -s sangam/deploy/airgap -p "test_*.py" (governance.yml). Written in Wave 4,
run in the test phase."""
import os
import tempfile
import unittest

import bundle_licence_gate as g

HERE = os.path.dirname(os.path.abspath(__file__))
POLICY = g.load_json(os.path.join(HERE, "..", "licence", "licence-policy.json"))


def run(text):
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, "images.txt")
        with open(p, "w", encoding="utf-8") as f:
            f.write(text)
        return g.gate(g.parse(p), POLICY)


class Gate(unittest.TestCase):
    def test_committed_list_passes_without_grafana(self):
        entries = g.parse(os.path.join(HERE, "images.txt"))
        self.assertFalse([e for e in entries if "grafana" in e[2]])
        findings, listed = g.gate(entries, POLICY)
        self.assertEqual(findings, [])
        self.assertEqual(len(listed), 3)

    def test_agpl_image_fails(self):
        findings, _ = run("observability  grafana/grafana-oss:12.1.1  # licence: AGPL-3.0-only\n")
        self.assertEqual(len(findings), 1)
        self.assertIn("FORBIDDEN", findings[0])

    def test_missing_and_unknown_licence_fail(self):
        findings, _ = run("runtime  a:1\nruntime  b:1  # licence: LicenseRef-Mystery\n")
        self.assertEqual(len(findings), 2)

    def test_devdb_is_listed_not_judged(self):
        findings, listed = run("devdb  mysql:8.4  # licence: GPL-2.0-only\n")
        self.assertEqual((findings, len(listed)), ([], 1))

    def test_cli(self):
        self.assertEqual(g.main([os.path.join(HERE, "images.txt")]), 0)
        self.assertEqual(g.main([os.path.join(HERE, "nope.txt")]), 2)


if __name__ == "__main__":
    unittest.main()
