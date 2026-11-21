# SPDX-License-Identifier: Apache-2.0
"""security.yml job scorecard runs only on the default branch, and nothing else in the job changes.

ossf/scorecard-action supports only the default branch; route_build dispatches security.yml on ci/* branches, where
the job always failed (lane study 2026-11-21, section ci). Joint condition EV-M-0979: the change must not alter what
makes the job pass or fail, so the rest of the job is pinned here as it was at 42439c36.
"""
import pathlib
import re
import unittest

WF = pathlib.Path(__file__).resolve().parents[1] / "workflows" / "security.yml"
IF = ("if: github.event_name != 'pull_request' && "
      "github.ref == format('refs/heads/{0}', github.event.repository.default_branch)")
# The job at 42439c36 after its `if:` line, unchanged.
REST = """    runs-on: ubuntu-24.04
    timeout-minutes: 15
    continue-on-error: true  # guard-allow: report-only and not in release-checks.json; decision 133 names no Scorecard gate
    permissions:
      contents: read
      security-events: write
      actions: read
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          persist-credentials: false
      - uses: ossf/scorecard-action@fa7117db83c52d787ae5a89f350999701505d78e # v2.5.0
        with:
          results_file: scorecard.sarif
          results_format: sarif
          publish_results: false
      - uses: github/codeql-action/upload-sarif@33d7b85da1a04171e211befe2c739303b9a4c066 # v4.39.3
        if: always() && hashFiles('scorecard.sarif') != ''
        with:
          sarif_file: scorecard.sarif
          category: scorecard
"""


def job(text, name):
    m = re.search(rf"(?ms)^  {name}:\n(.*?)(?=^  [\w-]+:\n|\Z)", text)
    return m.group(1) if m else ""


class ScorecardDefaultBranchOnly(unittest.TestCase):
    def setUp(self):
        self.body = job(WF.read_text(encoding="utf-8"), "scorecard")
        self.assertTrue(self.body, "security.yml has no scorecard job")
        lines = self.body.splitlines(keepends=True)
        self.name, self.cond, self.rest = lines[0], lines[1].strip(), "".join(lines[2:]).rstrip("\n") + "\n"

    def test_runs_only_on_the_default_branch_and_never_on_pull_requests(self):
        self.assertEqual(IF, self.cond)

    def test_the_rest_of_the_job_is_unchanged(self):
        self.assertIn("name: OpenSSF Scorecard (report-only, not a required check)", self.name)
        self.assertEqual(REST, self.rest)

    def test_the_parser_rejects_a_job_without_the_branch_check(self):
        old = "jobs:\n  scorecard:\n    name: x\n    if: github.event_name != 'pull_request'\n    runs-on: y\n"
        self.assertNotEqual(IF, job(old, "scorecard").splitlines()[1].strip())


if __name__ == "__main__":
    unittest.main()
