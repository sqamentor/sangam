# SPDX-License-Identifier: Apache-2.0
"""Unit tests of results_ledger.py and release-checks.json (CR-01, RM-12: a skipped check is never PASS).

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml). Written in Wave 4,
run in the test phase."""
import json
import os
import sys
import tempfile
import unittest

import results_ledger as rl

HERE = os.path.dirname(os.path.abspath(__file__))
PY = [sys.executable, "-I", "-c"]


class Ledger(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = os.path.join(self.tmp.name, "results")
        os.environ.pop("GITHUB_STEP_SUMMARY", None)

    def tearDown(self):
        self.tmp.cleanup()

    def line(self, check):
        with open(os.path.join(self.dir, check + ".json"), encoding="utf-8") as f:
            return json.load(f)

    def run_check(self, check, code, *extra):
        return rl.main(["run", "--check", check, "--ledger-dir", self.dir, *extra, "--",
                        *PY, f"import sys; sys.exit({code})"])

    def test_exit_codes_map_to_status(self):
        self.assertEqual(self.run_check("ok", 0), 0)
        self.assertEqual(self.line("ok")["status"], "PASS")
        self.assertEqual(self.run_check("bad", 1), 1)
        self.assertEqual(self.line("bad")["status"], "FAIL")
        self.assertEqual(self.run_check("blocked", 2), 1)
        self.assertEqual(self.line("blocked")["status"], "BLOCKED")
        self.assertEqual(self.run_check("crash", 137), 1)
        self.assertEqual(self.line("crash")["status"], "FAIL")

    def test_missing_input_is_blocked_and_command_not_run(self):
        marker = os.path.join(self.tmp.name, "ran")
        code = rl.main(["run", "--check", "x", "--ledger-dir", self.dir, "--expect", os.path.join(self.tmp.name, "nope"),
                        "--", *PY, f"open({marker!r}, 'w').close()"])
        self.assertEqual(code, 1)
        self.assertEqual(self.line("x")["status"], "BLOCKED")
        self.assertFalse(os.path.exists(marker))

    def test_glob_minimum(self):
        open(os.path.join(self.tmp.name, "a.cdx.json"), "w").close()
        pattern = os.path.join(self.tmp.name, "*.cdx.json")
        self.assertEqual(self.run_check("g1", 0, "--expect-glob", f"{pattern}=1"), 0)
        self.assertEqual(self.run_check("g2", 0, "--expect-glob", f"{pattern}=2"), 1)
        self.assertEqual(self.line("g2")["status"], "BLOCKED")

    def test_report_only_is_never_pass(self):
        self.assertEqual(self.run_check("r", 0, "--report-only"), 0)
        self.assertEqual(self.line("r")["status"], "NOT-RUN")
        self.assertEqual(self.run_check("r2", 1, "--report-only"), 0)
        self.assertEqual(self.line("r2")["status"], "NOT-RUN")

    def test_record_refuses_pass(self):
        self.assertEqual(rl.main(["record", "--check", "s", "--ledger-dir", self.dir, "--status", "PASS",
                                  "--reason", "x"]), 2)
        self.assertEqual(rl.main(["record", "--check", "s", "--ledger-dir", self.dir, "--status", "SKIPPED",
                                  "--reason", "no lane variable"]), 0)
        self.assertEqual(self.line("s")["status"], "SKIPPED")

    def test_bad_check_id(self):
        self.assertEqual(rl.main(["record", "--check", "../x", "--ledger-dir", self.dir, "--status", "SKIPPED",
                                  "--reason", "x"]), 2)

    def gate(self, mode, required):
        spec = os.path.join(self.tmp.name, "req.json")
        with open(spec, "w", encoding="utf-8") as f:
            json.dump({"s": required}, f)
        return rl.main(["gate", "--required", spec, "--mode", mode, "--set", "s", "--ledger-dir", self.dir])

    def test_gate_release_needs_pass(self):
        self.run_check("a", 0)
        self.run_check("b", 0, "--report-only")
        self.assertEqual(self.gate("release", ["a"]), 0)
        self.assertEqual(self.gate("release", ["a", "b"]), 1)
        self.assertEqual(self.gate("release", ["a", "missing"]), 1)

    def test_gate_pr_allows_report_only_not_skipped(self):
        self.run_check("a", 0)
        self.run_check("b", 1, "--report-only")
        self.assertEqual(self.gate("pr", ["a", "b"]), 0)
        rl.main(["record", "--check", "c", "--ledger-dir", self.dir, "--status", "SKIPPED", "--reason", "x"])
        self.assertEqual(self.gate("pr", ["a", "c"]), 1)

    def test_worse_duplicate_wins(self):
        os.makedirs(os.path.join(self.dir, "leg1"))
        os.makedirs(os.path.join(self.dir, "leg2"))
        rl.main(["run", "--check", "m", "--ledger-dir", os.path.join(self.dir, "leg1"), "--", *PY, "pass"])
        rl.main(["record", "--check", "m", "--ledger-dir", os.path.join(self.dir, "leg2"), "--status", "NOT-RUN",
                 "--reason", "lane not accepted"])
        self.assertEqual(rl.load_ledger(self.dir)["m"]["status"], "NOT-RUN")
        self.assertEqual(self.gate("release", ["m"]), 1)


class RequiredChecks(unittest.TestCase):
    def test_sets_are_well_formed(self):
        with open(os.path.join(HERE, "release-checks.json"), encoding="utf-8") as f:
            spec = json.load(f)
        sets = {k: v for k, v in spec.items() if not k.startswith("_")}
        self.assertIn("release", sets)
        for name, checks in sets.items():
            self.assertTrue(checks, name)
            self.assertEqual(len(checks), len(set(checks)), name)
            for c in checks:
                rl.valid_check_id(c)

    def test_release_set_covers_every_distribution(self):
        with open(os.path.join(HERE, "release-checks.json"), encoding="utf-8") as f:
            release = set(json.load(f)["release"])
        with open(os.path.join(HERE, "distributions.json"), encoding="utf-8") as f:
            dists = json.load(f)["distributions"]
        for d in dists:
            self.assertIn(f"licence-{d['id']}", release, d["id"])
            if d["kind"] in ("fat-jar", "jar", "lib-ext", "lib-dir", "npm-bundle", "image"):
                self.assertIn(f"register-{d['id']}", release, d["id"])
            if d.get("notices"):
                self.assertIn(f"notices-{d['id']}", release, d["id"])
            if d["kind"] in ("fat-jar", "lib-ext", "lib-dir"):
                self.assertIn(f"sbom-match-{d['id']}", release, d["id"])


if __name__ == "__main__":
    unittest.main()
