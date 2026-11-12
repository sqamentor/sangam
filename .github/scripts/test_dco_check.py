# SPDX-License-Identifier: Apache-2.0
"""Unit tests for dco_check.py (M-096). Run: python -I -m unittest discover -s .github/scripts"""
from __future__ import annotations

import io
import unittest
from contextlib import redirect_stdout
from unittest import mock

import dco_check as d


def record(sha: str, parents: str, email: str, message: str) -> str:
    return f"{sha}\x00{parents}\x00{email}\x00{message}\x1e\n"


class DcoCheckTest(unittest.TestCase):

    def run_main(self, raw_log: str, *args: str) -> tuple[int, str]:
        out = io.StringIO()
        with mock.patch.object(d, "git_log", return_value=raw_log), redirect_stdout(out):
            code = d.main(["a..b", *args])
        return code, out.getvalue()

    def test_parse_log_reads_every_field(self):
        raw = record("s1", "p1", "a@x.org", "Fix\n\nSigned-off-by: A <a@x.org>\n") + record("s2", "p1 p2", "b@x.org", "Merge\n")
        commits = d.parse_log(raw)
        self.assertEqual(["s1", "s2"], [c.sha for c in commits])
        self.assertEqual(("p1", "p2"), commits[1].parents)
        self.assertEqual("a@x.org", commits[0].author_email)
        self.assertIn("Signed-off-by: A <a@x.org>", commits[0].message)
        self.assertEqual([], d.parse_log(""))

    def test_sign_off_must_match_the_author(self):
        ok = d.Commit("s", ("p",), "A@X.org", "m\n\nSigned-off-by: A Name <a@x.org>\n")
        self.assertIsNone(d.problem(ok))
        other = d.Commit("s", ("p",), "a@x.org", "m\n\nSigned-off-by: B <b@x.org>\n")
        self.assertEqual("Signed-off-by (b@x.org) does not match the author (a@x.org)", d.problem(other))
        self.assertEqual("no Signed-off-by trailer", d.problem(d.Commit("s", ("p",), "a@x.org", "m\n")))
        # the trailer must be its own line, not text inside a sentence
        inline = d.Commit("s", ("p",), "a@x.org", "m mentions Signed-off-by: A <a@x.org> inline\n")
        self.assertEqual("no Signed-off-by trailer", d.problem(inline))

    def test_merges_and_bots_are_not_checked(self):
        self.assertIsNone(d.problem(d.Commit("s", ("p1", "p2"), "a@x.org", "Merge\n")))
        bot = "49699333+dependabot[bot]@users.noreply.github.com"
        self.assertIsNone(d.problem(d.Commit("s", ("p",), bot, "Bump x\n")))

    def test_report_only_by_default_enforce_on_request(self):
        raw = record("s1", "p", "a@x.org", "no sign-off\n")
        code, out = self.run_main(raw)
        self.assertEqual(0, code)
        self.assertIn("s1\tno Signed-off-by trailer", out)
        self.assertIn("::warning title=DCO (report-only, Q-10)::1 commits", out)
        code, out = self.run_main(raw, "--enforce")
        self.assertEqual(1, code)
        self.assertIn("::error title=DCO::1 commits", out)
        signed = record("s2", "p", "a@x.org", "x\n\nSigned-off-by: A <a@x.org>\n")
        self.assertEqual(0, self.run_main(signed, "--enforce")[0])

    def test_git_failure_is_an_error_in_both_modes(self):
        err = d.subprocess.CalledProcessError(128, ["git"], stderr="fatal: bad revision")
        out = io.StringIO()
        with mock.patch.object(d, "git_log", side_effect=err), redirect_stdout(out):
            self.assertEqual(2, d.main(["a..b"]))
        self.assertIn("fatal: bad revision", out.getvalue())


if __name__ == "__main__":
    unittest.main()
