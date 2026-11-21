# SPDX-License-Identifier: Apache-2.0
"""Unit tests of results_ledger.py and release-checks.json (CR-01, RM-12: a skipped check is never PASS).

Run: python -I -m unittest discover -s sangam/deploy/licence -p "test_*.py" (governance.yml). Written in Wave 4,
run in the test phase."""
import json
import os
import re
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


NVD = "SSM parameter /shared/security/nvd-api-key not readable"


class DependencyCheckWaiver(unittest.TestCase):
    """Decision 417: dependency-check may be WAIVED (never PASS) for tag v0.1.0 only, only when it is BLOCKED because
    the NVD key is missing, and only while image-vuln-scan (Grype) and codeql are PASS."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = os.path.join(self.tmp.name, "results")
        os.makedirs(self.dir)
        os.environ.pop("GITHUB_STEP_SUMMARY", None)
        self.spec = os.path.join(HERE, "release-checks.json")
        self.out = os.path.join(self.tmp.name, "gate.json")

    def tearDown(self):
        self.tmp.cleanup()

    def put(self, check, status, reason=None):
        with open(os.path.join(self.dir, check + ".json"), "w", encoding="utf-8") as f:
            json.dump({"check": check, "status": status, "reason": reason}, f)

    def ledger(self, dc=("BLOCKED", NVD), grype="PASS", codeql="PASS", other=None):
        with open(self.spec, encoding="utf-8") as f:
            release = json.load(f)["release"]
        for c in release:
            self.put(c, "PASS")
        self.put("dependency-check", *dc)
        self.put("image-vuln-scan", grype)
        self.put("codeql", codeql)
        if other:
            self.put(*other)

    def gate(self, tag):
        return rl.main(["gate", "--required", self.spec, "--mode", "release", "--set", "release",
                        "--ledger-dir", self.dir, "--out", self.out, "--tag", tag])

    def test_spec_names_the_waiver_and_codeql(self):
        with open(self.spec, encoding="utf-8") as f:
            spec = json.load(f)
        self.assertIn("codeql", spec["release"])
        self.assertIn("image-vuln-scan", spec["release"])
        [w] = rl.load_waivers(spec)
        self.assertEqual((w["check"], w["tag"], w["decision"], w["status"]), ("dependency-check", "v0.1.0", 417, "BLOCKED"))
        self.assertEqual(w["reasons"], [NVD])
        self.assertEqual(sorted(w["requires_pass"]), ["codeql", "image-vuln-scan"])

    def test_v010_blocked_by_missing_key_is_waived_never_pass(self):
        self.ledger()
        self.assertEqual(self.gate("v0.1.0"), 0)
        with open(self.out, encoding="utf-8") as f:
            row = [c for c in json.load(f)["checks"] if c["check"] == "dependency-check"][0]
        self.assertEqual((row["status"], row["verdict"]), ("BLOCKED", "WAIVED"))

    def test_no_waiver_for_another_tag(self):
        self.ledger()
        for tag in ("v0.1.1", "v0.2.0", "v0.1.0-rc1", "0.1.0", ""):
            self.assertEqual(self.gate(tag), 1, tag)

    def test_no_waiver_for_another_check(self):
        self.ledger(dc=("PASS", None), other=("semgrep", "BLOCKED", NVD))
        self.assertEqual(self.gate("v0.1.0"), 1)
        self.ledger(other=("secret-scan", "BLOCKED", NVD))
        self.assertEqual(self.gate("v0.1.0"), 1)

    def test_a_real_fail_is_never_waived(self):
        self.ledger(dc=("FAIL", NVD))
        self.assertEqual(self.gate("v0.1.0"), 1)

    def test_other_blocked_reasons_are_not_waived(self):
        for reason in ("SSM parameter /shared/security/nvd-api-key empty", "no GitHub OIDC token for this run", None):
            self.ledger(dc=("BLOCKED", reason))
            self.assertEqual(self.gate("v0.1.0"), 1, reason)

    def test_no_waiver_unless_grype_and_codeql_pass(self):
        for grype, codeql in (("FAIL", "PASS"), ("BLOCKED", "PASS"), ("PASS", "FAIL"), ("PASS", "NOT-RUN")):
            self.ledger(grype=grype, codeql=codeql)
            self.assertEqual(self.gate("v0.1.0"), 1, (grype, codeql))
        self.ledger()
        os.remove(os.path.join(self.dir, "codeql.json"))
        self.assertEqual(self.gate("v0.1.0"), 1, "codeql line missing")

    # the release gate's "every upstream job succeeded" step
    def jobs(self, tag, security="failure", failed=("OWASP Dependency-Check",), causes=("dependency-check",),
             others=None):
        needs = {k: {"result": "success"} for k in ("meta", "build", "images", "chart", "db-matrix", "governance")}
        needs["security"] = {"result": security}
        needs.update(others or {})
        names = ["CodeQL java-kotlin", "CodeQL javascript-typescript", "OWASP Dependency-Check", "Semgrep", "Gitleaks",
                 "OpenSSF Scorecard (report-only, not a required check)"]
        run = {"jobs": [{"name": f"security / {n}", "conclusion": "failure" if n in failed else "success"}
                        for n in names] + [{"name": "build", "conclusion": "success"}]}
        rj = os.path.join(self.tmp.name, "jobs.json")
        with open(rj, "w", encoding="utf-8") as f:
            json.dump(run, f)
        cd = os.path.join(self.tmp.name, "causes")
        os.makedirs(cd, exist_ok=True)
        for n in os.listdir(cd):
            os.remove(os.path.join(cd, n))
        for i, c in enumerate(causes):
            with open(os.path.join(cd, f"c{i}.json"), "w", encoding="utf-8") as f:
                json.dump({"workflow": "security", "check": c}, f)
        return rl.main(["jobs", "--required", self.spec, "--needs-json", json.dumps(needs), "--run-jobs", rj,
                        "--exit-cause-dir", cd, "--ledger-dir", self.dir, "--tag", tag])

    def test_jobs_accepts_security_failed_only_by_the_waived_check(self):
        self.ledger()
        self.assertEqual(self.jobs("v0.1.0"), 0)
        self.assertEqual(self.jobs("v0.1.0", security="success", failed=(), causes=()), 0)

    def test_jobs_refuses_another_tag_or_another_failure(self):
        self.ledger()
        self.assertEqual(self.jobs("v0.1.1"), 1)
        self.assertEqual(self.jobs("v0.1.0", failed=("OWASP Dependency-Check", "Semgrep")), 1)
        self.assertEqual(self.jobs("v0.1.0", failed=("Semgrep",)), 1)
        self.assertEqual(self.jobs("v0.1.0", causes=()), 1, "no recorded exit cause")
        self.assertEqual(self.jobs("v0.1.0", causes=("dependency-check", "semgrep")), 1)
        self.assertEqual(self.jobs("v0.1.0", security="cancelled"), 1)
        self.assertEqual(self.jobs("v0.1.0", others={"build": {"result": "failure"}}), 1)

    def test_jobs_ignores_only_the_continue_on_error_jobs_of_security_yml(self):
        self.ledger()
        scorecard = "OpenSSF Scorecard (report-only, not a required check)"
        self.assertEqual(self.jobs("v0.1.0", failed=("OWASP Dependency-Check", scorecard)), 0)
        with open(os.path.join(HERE, "..", "..", "..", ".github", "workflows", "security.yml"), encoding="utf-8") as f:
            wf = f.read()
        coe = set()
        for block in re.split(r"\n  (?=[a-z][a-z0-9_-]*:\n)", wf.split("\njobs:\n", 1)[1]):
            name = re.search(r"\n    name: (.+)", block)
            if name and re.search(r"\n    continue-on-error: true", block):
                coe.add(name.group(1).strip())
        with open(self.spec, encoding="utf-8") as f:
            [w] = rl.load_waivers(json.load(f))
        self.assertEqual(set(w["report_only_jobs"]), coe)

    def test_jobs_refuses_when_the_ledger_waiver_does_not_hold(self):
        self.ledger(dc=("FAIL", NVD))
        self.assertEqual(self.jobs("v0.1.0"), 1)
        self.ledger(codeql="FAIL")
        self.assertEqual(self.jobs("v0.1.0"), 1)
        self.ledger(grype="BLOCKED")
        self.assertEqual(self.jobs("v0.1.0"), 1)


if __name__ == "__main__":
    unittest.main()
