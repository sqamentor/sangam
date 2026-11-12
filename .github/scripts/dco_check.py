# SPDX-License-Identifier: Apache-2.0
"""M-096 DCO check (SANGAM.md governance: DCO sign-off): every commit of a range is signed off by its author.

A commit passes when its message has a trailer line "Signed-off-by: Name <email>" whose email equals the commit
author's email (case-insensitive), as `git commit -s` writes it. Merge commits and GitHub bot commits
(<name>[bot]@users.noreply.github.com) are not checked.

Report-only by default (exit 0, findings printed as warnings). --enforce exits 1 on a finding. Enforcement waits for the
owner's answer to Q-10 (also Q19 / SG-18); the workflow switch is the repository variable SANGAM_DCO_ENFORCE, unset.
A git error is exit 2 in both modes.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

SIGNED_OFF = re.compile(r"^Signed-off-by:\s*.+?<([^<>\s]+)>\s*$", re.IGNORECASE | re.MULTILINE)
BOT_EMAIL = re.compile(r"\[bot\]@users\.noreply\.github\.com$", re.IGNORECASE)
# %H, parents, author email, raw body; fields split by NUL, records by RS (0x1e)
LOG_FORMAT = "%H%x00%P%x00%ae%x00%B%x1e"


@dataclass(frozen=True)
class Commit:
    sha: str
    parents: tuple[str, ...]
    author_email: str
    message: str


def parse_log(raw: str) -> list[Commit]:
    out = []
    for record in raw.split("\x1e"):
        record = record.lstrip("\n")
        if not record:
            continue
        sha, parents, email, message = record.split("\x00", 3)
        out.append(Commit(sha, tuple(parents.split()), email.strip(), message))
    return out


def sign_off_emails(message: str) -> list[str]:
    return [m.lower() for m in SIGNED_OFF.findall(message)]


def problem(c: Commit) -> str | None:
    """Why the commit fails the DCO check, or None when it passes or is not checked."""
    if len(c.parents) > 1 or BOT_EMAIL.search(c.author_email):
        return None
    emails = sign_off_emails(c.message)
    if not emails:
        return "no Signed-off-by trailer"
    if c.author_email.lower() not in emails:
        return f"Signed-off-by ({', '.join(emails)}) does not match the author ({c.author_email})"
    return None


def git_log(rev_range: str, repo: Path) -> str:
    return subprocess.run(["git", "-C", str(repo), "log", f"--format={LOG_FORMAT}", rev_range],
                          check=True, capture_output=True, text=True, encoding="utf-8").stdout


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("range", help="commit range, e.g. <base sha>..<head sha>")
    p.add_argument("--repo", type=Path, default=Path("."))
    p.add_argument("--summary", type=Path, help="append a Markdown summary here (e.g. $GITHUB_STEP_SUMMARY)")
    p.add_argument("--enforce", action="store_true", help="exit 1 when a commit is not signed off (Q-10)")
    a = p.parse_args(argv)
    try:
        commits = parse_log(git_log(a.range, a.repo))
    except (subprocess.CalledProcessError, OSError) as e:
        detail = getattr(e, "stderr", "") or str(e)
        print(f"::error title=DCO check::git log {a.range} failed: {detail.strip()}")
        return 2
    findings = [(c, why) for c in commits if (why := problem(c))]
    mode = "enforced" if a.enforce else "report-only"
    for c, why in findings:
        print(f"{c.sha}\t{why}")
    print(f"dco: {len(findings)} of {len(commits)} commits not signed off ({mode})")
    if a.summary:
        with a.summary.open("a", encoding="utf-8") as f:
            f.write(f"### DCO sign-off ({mode}, Q-10 open)\n\n{len(findings)} of {len(commits)} commits not signed off.\n")
            for c, why in findings:
                f.write(f"- `{c.sha[:12]}`: {why}\n")
    if findings and a.enforce:
        print(f"::error title=DCO::{len(findings)} commits are not signed off (git commit -s)")
        return 1
    if findings:
        print(f"::warning title=DCO (report-only, Q-10)::{len(findings)} commits are not signed off (git commit -s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
