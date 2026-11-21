# SPDX-License-Identifier: Apache-2.0
"""Proves the narrow gitleaks allowlists in .gitleaks.toml against the pinned gitleaks binary.

Runs `gitleaks git` (the mode security.yml uses) on a throw-away repository built in a temp directory, so no token
shape is ever committed to this repository's history. The 64-hex values are random and generated at run time.

Needs GITLEAKS_BIN (security.yml job gitleaks sets it after fetch_tool.py). Without it the tests skip, so the
governance unit-test step stays independent of the binary; the proof is the gitleaks job.

E-1 (code-scanning alerts 895, 896): the sentry-access-token rule reads `updateCaseSentryPart, <SHA-256>"` in
CaseMigrationEngineContractTest.java as a Sentry token. The allowlist lets through exactly that shape in exactly
that file; the other cases prove that the rule still fires elsewhere and on any other shape in the same file.
"""
import json
import os
import pathlib
import secrets
import subprocess
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
CONFIG = REPO / ".gitleaks.toml"
BIN = os.environ.get("GITLEAKS_BIN", "")
RULE = "sentry-access-token"
CMMN_TEST = "sangam/sangam-flowable-ports/src/test/java/org/sangam/ports/cmmn/CaseMigrationEngineContractTest.java"


def _git(cwd, *args):
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid", "-c", "commit.gpgsign=false",
                    *args], cwd=cwd, check=True, capture_output=True, text=True)


def scan(files):
    """files: {relative path: text}. Returns [(RuleID, File)] that gitleaks reports for one commit holding them."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp) / "r"
        root.mkdir()
        _git(root, "init", "-q")
        for rel, text in files.items():
            p = root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding="utf-8")
        _git(root, "add", "-A")
        _git(root, "commit", "-q", "-m", "fixture")
        report = pathlib.Path(tmp) / "report.json"
        r = subprocess.run([BIN, "git", "--config", str(CONFIG), "--no-banner", "--exit-code", "0",
                            "--report-format", "json", "--report-path", str(report), "."],
                           cwd=root, capture_output=True, text=True)
        if r.returncode != 0:
            raise AssertionError(f"gitleaks failed ({r.returncode}): {r.stderr[-2000:]}")
        return [(f["RuleID"], f["File"]) for f in json.loads(report.read_text(encoding="utf-8") or "[]")]


def csv_row(digest):
    # The shape of line 42 in commits bbe4de04 and c1c65ab6.
    return f'class T {{\n  String[] rows = {{\n    "CaseSentryPart, updateCaseSentryPart, {digest}",\n  }};\n}}\n'


@unittest.skipUnless(BIN, "GITLEAKS_BIN not set; the security.yml gitleaks job runs these")
class SentryDigestAllowlist(unittest.TestCase):

    def test_digest_row_in_the_cmmn_contract_test_is_not_reported(self):
        found = scan({CMMN_TEST: csv_row(secrets.token_hex(32))})
        self.assertNotIn((RULE, CMMN_TEST), found)

    def test_same_row_in_another_file_is_still_reported(self):
        other = "sangam/sangam-flowable-ports/src/test/java/org/sangam/ports/cmmn/OtherContractTest.java"
        found = scan({other: csv_row(secrets.token_hex(32))})
        self.assertIn((RULE, other), found)

    def test_same_file_path_in_another_folder_is_still_reported(self):
        moved = "sangam/other/" + CMMN_TEST
        found = scan({moved: csv_row(secrets.token_hex(32))})
        self.assertIn((RULE, moved), found)

    def test_other_shape_in_the_cmmn_contract_test_is_still_reported(self):
        text = f'class T {{\n  String sentryToken = "{secrets.token_hex(32)}";\n}}\n'
        found = scan({CMMN_TEST: text})
        self.assertIn((RULE, CMMN_TEST), found)

    def test_digest_row_plus_a_real_token_in_the_same_file_reports_the_token(self):
        text = csv_row(secrets.token_hex(32)) + f'// sentry_auth = "{secrets.token_hex(32)}"\n'
        found = scan({CMMN_TEST: text})
        self.assertEqual(1, found.count((RULE, CMMN_TEST)), found)


if __name__ == "__main__":
    unittest.main()
