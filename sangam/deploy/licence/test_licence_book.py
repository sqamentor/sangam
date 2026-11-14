# SPDX-License-Identifier: Apache-2.0
"""Unit tests of licence_book.py (M-088). Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py"
(governance.yml). Written in Wave 5, run in the test phase."""
import os
import tempfile
import unittest

import licence_book as lb

HEADER = "Third-party components of the x distribution (from its CycloneDX SBOM).\nLicence texts are in licenses/.\n\n"


class Book(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def bundle(self, dist, rows, texts):
        base = os.path.join(self.dir, "notices", dist)
        os.makedirs(os.path.join(base, "licenses"))
        with open(os.path.join(base, "THIRD-PARTY.txt"), "w", encoding="utf-8", newline="\n") as f:
            f.write(HEADER + "".join(r + "\n" for r in rows))
        for i, t in texts.items():
            with open(os.path.join(base, "licenses", f"{i}.txt"), "w", encoding="utf-8", newline="\n") as f:
                f.write(t)

    def run_book(self):
        out = os.path.join(self.dir, "book.txt")
        rc = lb.main([os.path.join(self.dir, "notices"), "--version", "0.1.0", "--out", out])
        text = open(out, encoding="utf-8").read() if os.path.exists(out) else None
        return rc, text

    def test_components_merge_across_distributions_and_each_text_appears_once(self):
        a = "pkg:maven/org.a/a@1.0\tdeclared: Apache-2.0\tused: Apache-2.0"
        b = "pkg:maven/org.b/b@2.0\tdeclared: EPL-2.0 OR GPL-2.0-only\tused: EPL-2.0"
        self.bundle("server", [a, b], {"Apache-2.0": "Apache text\n", "EPL-2.0": "EPL text\n"})
        self.bundle("gateway", [a], {"Apache-2.0": "Apache text\n"})
        rc, text = self.run_book()
        self.assertEqual(rc, 0)
        self.assertIn("Part 1. Components (2)", text)
        self.assertIn("pkg:maven/org.a/a@1.0\n    declared: Apache-2.0\n    licence used: Apache-2.0\n"
                      "    distributions: gateway, server\n", text)
        self.assertEqual(text.count("Apache text"), 1)
        self.assertIn("==== EPL-2.0 (used by 1 component) ====", text)
        self.assertIn("==== Apache-2.0 (used by 1 component) ====", text)
        self.assertNotIn("GPL-2.0-only (used", text)  # declared alternative not used: no text
        rc2, text2 = self.run_book()
        self.assertEqual((rc2, text2), (0, text))  # same bundles, same bytes

    def test_missing_text_fails_and_writes_nothing(self):
        self.bundle("server", ["pkg:maven/org.b/b@2.0\tdeclared: MIT\tused: MIT"], {})
        self.assertEqual(self.run_book(), (1, None))

    def test_different_texts_for_one_id_fail(self):
        row = "pkg:maven/org.b/b@2.0\tdeclared: MIT\tused: MIT"
        self.bundle("server", [row], {"MIT": "MIT text\n"})
        self.bundle("ui", [row], {"MIT": "another MIT text\n"})
        self.assertEqual(self.run_book(), (1, None))

    def test_missing_third_party_list_and_bad_row_fail(self):
        os.makedirs(os.path.join(self.dir, "notices", "empty"))
        self.bundle("server", ["pkg:maven/org.b/b@2.0\tMIT"], {})
        self.assertEqual(self.run_book(), (1, None))

    def test_unreadable_directory_is_blocked(self):
        self.assertEqual(lb.main([os.path.join(self.dir, "nope"), "--version", "1", "--out",
                                  os.path.join(self.dir, "o.txt")]), 2)


if __name__ == "__main__":
    unittest.main()
