# SPDX-License-Identifier: Apache-2.0
"""M-096 traceability gate (SANGAM.md A1/A5, BACKLOG P1-029): a requirement without a proving test.

Reads sangam/docs/records/TRACEABILITY-REGISTER.csv and lists every requirement row that claims code coverage but names no
test that proves it.

* A row is a requirement under the gate when its status is one of SUBJECT_STATUSES and its family is not "T" (the T
  rows are the tests themselves; the register lists what they prove in "proves (tests only)").
* A row is proven when "tests that prove it" names at least one test after the placeholders "add test" and "no test"
  are removed ("T23, add test" is proven by T23; "add test" alone is not).

Report-only by default: it prints the findings (and a Markdown summary with --summary) and exits 0. With --enforce
it exits 1 when a finding exists. A register that cannot be read, lacks a needed column or repeats an id is an error
in both modes (exit 2), so a broken input never reads as "no findings".
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
from collections import Counter
from pathlib import Path

ID = "id"
FAMILY = "family"
STATUS = "status"
TESTS = "tests that prove it"
REQUIRED_COLUMNS = (ID, FAMILY, STATUS, TESTS)
SUBJECT_STATUSES = frozenset({"implemented", "partial", "test-pending"})
TEST_FAMILY = "T"
PLACEHOLDERS = frozenset({"add test", "no test"})
SUMMARY_LIMIT = 200


class RegisterError(Exception):
    """The register cannot be checked (missing file, missing column, duplicate id)."""


def load(path: Path) -> list[dict[str, str]]:
    try:
        with path.open(encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            header = reader.fieldnames or []
            missing = [c for c in REQUIRED_COLUMNS if c not in header]
            if missing:
                raise RegisterError(f"{path}: missing column(s) {missing}; header is {header}")
            # strip every cell; a short row reads its missing cells as "" (DictReader gives None)
            rows = [{k: (v or "").strip() for k, v in r.items() if k is not None} for r in reader]
    except OSError as e:
        raise RegisterError(f"{path}: {e}") from e
    dupes = sorted(i for i, n in Counter(r[ID] for r in rows).items() if n > 1)
    if dupes:
        raise RegisterError(f"{path}: duplicate id(s) {dupes}")
    return rows


def proving_tests(cell: str | None) -> list[str]:
    """The test names in a "tests that prove it" cell, placeholders removed."""
    tokens = (t.strip() for t in re.split(r"[,;]", cell or ""))
    return [t for t in tokens if t and t.lower() not in PLACEHOLDERS]


def is_subject(row: dict[str, str]) -> bool:
    return row[STATUS] in SUBJECT_STATUSES and row[FAMILY] != TEST_FAMILY


def unproven(rows: list[dict[str, str]]) -> list[dict[str, str]]:
    return [r for r in rows if is_subject(r) and not proving_tests(r[TESTS])]


def summary_markdown(findings: list[dict[str, str]], subjects: int, enforce: bool) -> str:
    mode = "enforced" if enforce else "report-only"
    lines = [f"### Traceability gate ({mode})", "",
             f"{len(findings)} of {subjects} requirement rows (status {', '.join(sorted(SUBJECT_STATUSES))}; "
             f"family {TEST_FAMILY} excluded) name no proving test.", ""]
    if findings:
        lines += ["| family | rows |", "|---|---|"]
        for fam, n in sorted(Counter(r[FAMILY] for r in findings).items()):
            lines.append(f"| {fam} | {n} |")
        shown = ", ".join(r[ID] for r in findings[:SUMMARY_LIMIT])
        more = f" (and {len(findings) - SUMMARY_LIMIT} more in the job log)" if len(findings) > SUMMARY_LIMIT else ""
        lines += ["", f"Ids: {shown}{more}"]
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--register", type=Path, default=Path("sangam/docs/records/TRACEABILITY-REGISTER.csv"))
    p.add_argument("--summary", type=Path, help="append a Markdown summary here (e.g. $GITHUB_STEP_SUMMARY)")
    p.add_argument("--enforce", action="store_true", help="exit 1 when a requirement has no proving test")
    a = p.parse_args(argv)
    try:
        rows = load(a.register)
    except RegisterError as e:
        print(f"::error title=Traceability register unreadable::{e}")
        return 2
    subjects = sum(1 for r in rows if is_subject(r))
    findings = unproven(rows)
    for r in findings:
        print(f"{r[ID]}\t{r[FAMILY]}\t{r[STATUS]}\tno proving test")
    print(f"traceability: {len(findings)} of {subjects} requirement rows name no proving test "
          f"({'enforced' if a.enforce else 'report-only'})")
    if a.summary:
        with a.summary.open("a", encoding="utf-8") as f:
            f.write(summary_markdown(findings, subjects, a.enforce))
    if findings and a.enforce:
        print(f"::error title=Traceability gate::{len(findings)} requirement rows name no proving test")
        return 1
    if findings:
        print(f"::warning title=Traceability gate (report-only)::{len(findings)} requirement rows name no proving test")
    return 0


if __name__ == "__main__":
    sys.exit(main())
