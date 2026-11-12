# SPDX-License-Identifier: Apache-2.0
"""Licence report for a Python tool environment (owner 2026-11-12: Semgrep's pip dependencies), REPORT-ONLY.

Input: the JSON of `pip-licenses --format=json --from=mixed --python <venv python>` (a list of objects with Name,
Version and License) and sangam/deploy/licence/licence-policy.json. Each package is classified:
  build-time exception  the package is a build_time_tools entry (e.g. semgrep itself, LGPL-2.1, owner 2026-11-12);
  allowed               one licence alternative uses only "allowed" licences;
  forbidden             a forbidden licence (or name pattern) and no allowed alternative;
  unknown               anything else (no data, or a licence the policy does not name): needs a human look.
pip-licenses joins several classifiers with "; "; each is read as an alternative, as is an SPDX " OR ".

Exit 0 whatever the findings (report-only: these packages run on the CI runner and are never shipped); exit 2 when
an input cannot be read. With --summary the table is appended as Markdown (e.g. to $GITHUB_STEP_SUMMARY).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ALIASES = {
    "mit": "MIT", "mit license": "MIT", "the mit license": "MIT", "mit-0": "MIT-0",
    "apache software license": "Apache-2.0", "apache 2.0": "Apache-2.0", "apache-2.0": "Apache-2.0",
    "apache license 2.0": "Apache-2.0", "apache license, version 2.0": "Apache-2.0", "apache 2": "Apache-2.0",
    "apache license version 2.0": "Apache-2.0", "apache-2": "Apache-2.0",
    "bsd license": "BSD-3-Clause", "bsd": "BSD-3-Clause", "bsd-3-clause": "BSD-3-Clause", "bsd 3-clause": "BSD-3-Clause",
    "new bsd license": "BSD-3-Clause", "bsd-2-clause": "BSD-2-Clause", "bsd 2-clause": "BSD-2-Clause",
    "isc license (iscl)": "ISC", "isc": "ISC", "isc license": "ISC",
    "mozilla public license 2.0 (mpl 2.0)": "MPL-2.0", "mpl-2.0": "MPL-2.0", "mpl 2.0": "MPL-2.0",
    "python software foundation license": "Python-2.0", "psf-2.0": "Python-2.0", "psf": "Python-2.0",
    "the unlicense (unlicense)": "Unlicense", "unlicense": "Unlicense",
    "zlib/libpng license": "Zlib", "zlib": "Zlib", "cc0 1.0 universal (cc0 1.0) public domain dedication": "CC0-1.0",
    "gnu lesser general public license v2 (lgplv2)": "LGPL-2.1", "lgpl-2.1": "LGPL-2.1", "lgpl-2.1-only": "LGPL-2.1",
    "lgpl-2.1-or-later": "LGPL-2.1", "gnu lesser general public license v2 or later (lgplv2+)": "LGPL-2.1",
    "gnu lesser general public license v3 (lgplv3)": "LGPL-3.0", "lgpl-3.0": "LGPL-3.0",
    "gnu general public license v2 (gplv2)": "GPL-2.0", "gnu general public license v3 (gplv3)": "GPL-3.0",
    "gnu affero general public license v3": "AGPL-3.0",
}


class InputError(Exception):
    pass


def load_json(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as e:
        raise InputError(f"{path}: {e}") from e


def normalise(term: str) -> str:
    t = term.strip()
    return ALIASES.get(t.lower(), t)


def alternatives(licence: str) -> list[list[str]]:
    """Alternatives (OR / ';'), each a list of AND-ed terms. Outer SPDX parentheses are dropped; a classifier's own
    parentheses ("ISC License (ISCL)") are kept, as the alias table names them."""
    text = (licence or "").strip()
    if text.startswith("(") and text.endswith(")"):
        text = text[1:-1]
    out = []
    for alt in re.split(r";|\s+OR\s+", text, flags=re.IGNORECASE):
        terms = [normalise(t) for t in re.split(r"\s+AND\s+", alt, flags=re.IGNORECASE) if t.strip()]
        if terms and not all(t.upper() == "UNKNOWN" for t in terms):
            out.append(terms)
    return out


def build_time_names(policy: dict) -> set[str]:
    names = set()
    for e in policy.get("build_time_tools", []):
        c = e.get("coordinate", "")
        if c.startswith("pypi:"):
            names.add(c[len("pypi:"):].split("==", 1)[0].lower())
    return names


def classify(pkg: dict, policy: dict) -> str:
    name = (pkg.get("Name") or "").lower()
    if name in build_time_names(policy):
        return "build-time exception"
    allowed = set(policy.get("allowed", []))
    forbidden = set(policy.get("forbidden_licenses", []))
    patterns = [re.compile(p) for p in policy.get("forbidden_license_name_patterns", [])]
    alts = alternatives(pkg.get("License") or "")
    if any(all(t in allowed for t in alt) for alt in alts):
        return "allowed"
    if any(t in forbidden or any(p.search(t) for p in patterns) for alt in alts for t in alt):
        return "forbidden"
    return "unknown"


def report(packages: list[dict], policy: dict) -> tuple[dict[str, list[str]], str]:
    groups: dict[str, list[str]] = {"forbidden": [], "unknown": [], "build-time exception": [], "allowed": []}
    for p in sorted(packages, key=lambda x: (x.get("Name") or "").lower()):
        groups[classify(p, policy)].append(f"{p.get('Name')} {p.get('Version')} ({p.get('License') or 'no licence data'})")
    lines = ["### Semgrep pip dependencies: licence report (report-only)", "",
             f"{len(packages)} packages: " + ", ".join(f"{len(v)} {k}" for k, v in groups.items()), ""]
    for k in ("forbidden", "unknown", "build-time exception"):
        if groups[k]:
            lines.append(f"**{k}**")
            lines += [f"- {x}" for x in groups[k]]
            lines.append("")
    return groups, "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--packages", type=Path, required=True, help="pip-licenses --format=json output")
    ap.add_argument("--policy", type=Path, default=Path("sangam/deploy/licence/licence-policy.json"))
    ap.add_argument("--summary", type=Path)
    a = ap.parse_args(argv)
    try:
        packages = load_json(a.packages)
        policy = load_json(a.policy)
        if not isinstance(packages, list):
            raise InputError(f"{a.packages}: expected a JSON list from pip-licenses")
    except InputError as e:
        print(f"::error title=pip licence report::{e}")
        return 2
    groups, text = report(packages, policy)
    print(text)
    if a.summary:
        with a.summary.open("a", encoding="utf-8") as f:
            f.write(text)
    flagged = len(groups["forbidden"]) + len(groups["unknown"])
    if flagged:
        print(f"::warning title=pip licences (report-only)::{len(groups['forbidden'])} forbidden, "
              f"{len(groups['unknown'])} unknown licence(s) among the Semgrep pip dependencies")
    return 0


if __name__ == "__main__":
    sys.exit(main())
