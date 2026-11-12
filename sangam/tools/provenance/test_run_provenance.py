# SPDX-License-Identifier: Apache-2.0
"""Unit tests for run_provenance (SG-16). Run: python3 -I -m unittest discover -s sangam/tools/provenance"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_provenance as rp  # noqa: E402

SUITE = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="{name}" tests="{tests}" failures="{failures}" errors="0" skipped="1">
  <properties>
    <property name="java.version" value="{java}"/>
    <property name="java.vendor" value="Eclipse Adoptium"/>
    <property name="user.timezone" value="{zone}"/>
    <property name="sangam.db" value="{db}"/>
    <property name="unrelated" value="x"/>
  </properties>
  <testcase name="t"/>
</testsuite>
"""


def git(cwd, *args):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


class RunProvenanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="prov-")
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)

    def write(self, rel, text):
        path = os.path.join(self.tmp, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        return path

    def repo(self):
        root = os.path.join(self.tmp, "repo")
        os.makedirs(root)
        git(root, "init", "-q")
        git(root, "config", "user.email", "t@example.invalid")
        git(root, "config", "user.name", "t")
        git(root, "config", "core.autocrlf", "false")
        with open(os.path.join(root, "a.txt"), "w", encoding="utf-8", newline="\n") as f:
            f.write("one\n")
        git(root, "add", "a.txt")
        git(root, "commit", "-q", "-m", "c1")
        return root

    def test_reports_aggregate_test_jvm_properties_and_counts(self):
        self.write("m1/target/surefire-reports/TEST-A.xml",
                   SUITE.format(name="A", tests=3, failures=1, java="25.0.1", zone="UTC", db="h2"))
        self.write("m2/target-ws/failsafe-reports/TEST-B.xml",
                   SUITE.format(name="B", tests=2, failures=0, java="25.0.1", zone="UTC", db="sqlserver2025"))
        self.write("m3/target/surefire-reports/TEST-broken.xml", "<testsuite")
        self.write("m3/target/surefire-reports/not-a-report.xml", "<x/>")
        got = rp.read_reports(self.tmp)
        self.assertEqual(["25.0.1"], got["jvm"]["java.version"])
        self.assertEqual(["UTC"], got["jvm"]["user.timezone"])
        self.assertEqual(["h2", "sqlserver2025"], got["jvm"]["sangam.db"])
        self.assertEqual({"suites": 2, "tests": 5, "failures": 1, "errors": 0, "skipped": 2}, got["counts"])
        self.assertEqual(1, len(got["unreadable"]))

    def test_non_utc_test_jvm_is_visible_not_hidden(self):
        self.write("m/target/surefire-reports/TEST-A.xml",
                   SUITE.format(name="A", tests=1, failures=0, java="21.0.5", zone="Europe/Berlin", db="postgres"))
        self.write("n/target/surefire-reports/TEST-B.xml",
                   SUITE.format(name="B", tests=1, failures=0, java="21.0.5", zone="UTC", db="postgres"))
        self.assertEqual(["Europe/Berlin", "UTC"], rp.read_reports(self.tmp)["jvm"]["user.timezone"])

    def test_git_state_clean_then_dirty_with_stable_diff_hash(self):
        root = self.repo()
        clean = rp.git_state(root)
        self.assertRegex(clean["sha"], r"^[0-9a-f]{40}$")
        self.assertFalse(clean["dirty"])
        self.assertIsNone(clean["diff_sha256"])
        with open(os.path.join(root, "a.txt"), "a", encoding="utf-8", newline="\n") as f:
            f.write("two\n")
        d1, d2 = rp.git_state(root), rp.git_state(root)
        self.assertTrue(d1["dirty"])
        self.assertEqual(clean["sha"], d1["sha"])
        self.assertRegex(d1["diff_sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual(d1["diff_sha256"], d2["diff_sha256"])

    def test_git_state_outside_a_repository_reports_error_not_crash(self):
        got = rp.git_state(self.tmp)
        self.assertIsNone(got["sha"])
        self.assertIn("error", got)

    def test_drift(self):
        a = {"sha": "1" * 40, "diff_sha256": None}
        self.assertFalse(rp.drift(a, dict(a)))
        self.assertTrue(rp.drift(a, {"sha": "2" * 40, "diff_sha256": None}))
        self.assertTrue(rp.drift(a, {"sha": "1" * 40, "diff_sha256": "f" * 64}))
        self.assertIsNone(rp.drift({}, a))

    def test_parse_java_settings(self):
        text = ("Property settings:\n    java.vendor = Eclipse Adoptium\n    java.version = 25.0.1\n"
                "    user.timezone = \n    other = y\nopenjdk version \"25.0.1\"\n")
        self.assertEqual({"java.vendor": "Eclipse Adoptium", "java.version": "25.0.1", "user.timezone": ""},
                         rp.parse_java_settings(text))

    def test_start_finish_round_trip_detects_drift_and_writes_summary(self):
        root = self.repo()
        out = os.path.join(self.tmp, "prov")
        self.write("repo/m/target/failsafe-reports/TEST-A.xml",
                   SUITE.format(name="A", tests=4, failures=0, java="25.0.1", zone="UTC", db="postgres"))
        env = {"PATH": os.environ.get("PATH", ""), "TZ": "UTC", "GITHUB_SHA": "abc", "SECRET_TOKEN": "nope"}
        self.assertEqual(0, rp.main(["start", "--db", "postgres", "--out", out, "--root", root], env=env))
        with open(os.path.join(root, "a.txt"), "a", encoding="utf-8", newline="\n") as f:
            f.write("edited during the run\n")
        summary = os.path.join(self.tmp, "summary.md")
        self.assertEqual(0, rp.main(["finish", "--db", "postgres", "--out", out, "--root", root,
                                     "--reports", root, "--summary", summary], env=env))
        with open(os.path.join(out, "start.json"), encoding="utf-8") as f:
            start = json.load(f)
        with open(os.path.join(out, "end.json"), encoding="utf-8") as f:
            end = json.load(f)
        self.assertFalse(start["git"]["dirty"])
        self.assertNotIn("observed", start)
        self.assertTrue(end["git"]["dirty"])
        self.assertIs(True, end["drift"])
        self.assertEqual("postgres", end["db"])
        self.assertEqual(["postgres"], end["observed"]["jvm"]["sangam.db"])
        self.assertEqual({"GITHUB_SHA": "abc"}, end["runner"])  # only the allow-listed runner variables
        self.assertNotIn("nope", json.dumps(end))
        with open(summary, encoding="utf-8") as f:
            md = f.read()
        self.assertIn("Test-run provenance (SG-16)", md)
        self.assertIn(start["git"]["sha"], md)
        self.assertIn("**YES", md)

    def test_finish_without_start_records_unknown_drift(self):
        root = self.repo()
        out = os.path.join(self.tmp, "prov2")
        self.assertEqual(0, rp.main(["finish", "--db", "h2", "--out", out, "--root", root, "--reports", root],
                                    env={"PATH": os.environ.get("PATH", "")}))
        with open(os.path.join(out, "end.json"), encoding="utf-8") as f:
            self.assertIsNone(json.load(f)["drift"])


if __name__ == "__main__":
    unittest.main()
