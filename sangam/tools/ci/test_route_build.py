# SPDX-License-Identifier: Apache-2.0
"""route_build --main-workflows verdict (R9-mainci). Run: python -I -m unittest discover -s sangam/tools/ci -p "test_*.py"."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import route_build as rb  # noqa: E402


class MainVerdict(unittest.TestCase):
    def test_all_six_main_workflows_are_dispatched(self):
        self.assertEqual({"ci-build.yml", "governance.yml", "ui.yml", "sdks.yml", "security.yml", "licence-check.yml"},
                         set(rb.MAIN_WORKFLOWS))

    def test_green_runs_pass(self):
        self.assertEqual([], rb.main_verdict({w: {"conclusion": "success"} for w in rb.MAIN_WORKFLOWS}))

    def test_a_failed_job_fails_and_is_named(self):
        bad = rb.main_verdict({"ui.yml": {"conclusion": "failure", "jobs": [{"name": "UI e2e", "conclusion": "failure"}]}})
        self.assertEqual(["ui.yml: failure (UI e2e)"], bad)

    def test_only_the_main_only_scorecard_job_failing_is_not_a_failure(self):
        runs = {"security.yml": {"conclusion": "failure", "jobs": [
            {"name": "OpenSSF Scorecard (report-only, not a required check)", "conclusion": "failure"},
            {"name": "CodeQL java-kotlin", "conclusion": "success"}]}}
        self.assertEqual([], rb.main_verdict(runs))

    def test_a_run_never_dispatched_or_failing_without_jobs_fails(self):
        self.assertEqual(["sdks.yml: not dispatched (no job detail)"], rb.main_verdict({"sdks.yml": {"conclusion": "not dispatched"}}))


if __name__ == "__main__":
    unittest.main()
