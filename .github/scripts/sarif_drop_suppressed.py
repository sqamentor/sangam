# SPDX-License-Identifier: Apache-2.0
"""Drop SARIF results that carry an in-source suppression before the file is uploaded to code scanning.

Semgrep 1.x writes a finding silenced by a reviewed `// nosemgrep: <rule>` comment into its SARIF with
`"suppressions": [{"kind": "inSource"}]`, and GitHub code scanning keeps such a result as an OPEN alert (evidence:
alerts 687 and 688 on main, Semgrep 1.185.0 run 44107247562). The review lives in the source comment, so the uploaded
file carries only the findings nobody has reviewed. Any other suppression kind (external) is kept: only a suppression
written next to the code, where a reviewer sees it, is honoured.

Usage: python -I sarif_drop_suppressed.py <in.sarif> <out.sarif>   (in and out may be the same file)
"""
import json
import sys


def drop_in_source(sarif):
    """Returns (sarif, dropped) with every result whose suppressions are all kind inSource removed."""
    dropped = 0
    for run in sarif.get("runs", []):
        kept = []
        for result in run.get("results", []):
            sup = result.get("suppressions") or []
            if sup and all(s.get("kind") == "inSource" and s.get("status", "accepted") == "accepted" for s in sup):
                dropped += 1
            else:
                kept.append(result)
        if "results" in run:
            run["results"] = kept
    return sarif, dropped


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    with open(argv[1], encoding="utf-8") as f:
        sarif = json.load(f)
    sarif, dropped = drop_in_source(sarif)
    with open(argv[2], "w", encoding="utf-8") as f:
        json.dump(sarif, f)
    print(f"{dropped} in-source suppressed result(s) left out of {argv[2]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
