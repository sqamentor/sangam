# SPDX-License-Identifier: Apache-2.0
"""Licence gate over the air-gap bundle image list (RM-13, CR-06): no image whose recorded licence is forbidden.

Reads images.txt ("<group> <image>  # licence: <SPDX expression>"). Every image line must record a licence. For the
groups the bundle ships (product, runtime, observability) the expression is judged with licence-policy.json, the same
rules as check_licences.py: a forbidden or unknown licence FAILS (AGPL-3.0 Grafana is the case RM-13 removed). The
devdb group is never shipped by Sangam (the customer saves those images under their own acceptance), so its lines
are listed, not judged.

Exit codes: 0 pass, 1 findings, 2 unreadable input. Standard library only.
  python3 -I sangam/deploy/airgap/bundle_licence_gate.py sangam/deploy/airgap/images.txt
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "licence"))
from check_licences import Checker, InputError, expression_alternatives, load_json  # noqa: E402

SHIPPED = ("product", "runtime", "observability")
LINE = re.compile(r"^(\S+)\s+(\S+)\s*(?:#\s*licence:\s*(.+?))?\s*$")


def parse(path):
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError as e:
        raise InputError(f"cannot read {path}: {e}") from e
    out = []
    for n, raw in enumerate(lines, 1):
        s = raw.strip()
        if not s or s.startswith("#"):
            continue
        m = LINE.match(s)
        if not m:
            raise InputError(f"{path}:{n}: not '<group> <image>  # licence: <expr>'")
        out.append((n, m.group(1), m.group(2), m.group(3)))
    return out


def gate(entries, policy):
    checker = Checker(policy, True)
    findings, listed = [], []
    for n, group, image, expr in entries:
        if not expr:
            findings.append(f"line {n}: {image} records no licence")
            continue
        if group not in SHIPPED:
            listed.append(f"line {n}: [{group}] {image} ({expr}) not shipped by Sangam, not judged")
            continue
        try:
            alternatives = expression_alternatives(expr)
        except ValueError as e:
            findings.append(f"line {n}: {image}: bad licence expression {expr!r}: {e}")
            continue
        status = checker.judge(alternatives, f"pkg:oci/{image}")
        if status not in ("allowed", "conditional"):
            findings.append(f"line {n}: [{group}] {image}: licence {expr} is {status.upper()} by licence-policy.json")
    return findings, listed


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    path = argv[0] if argv else os.path.join(HERE, "images.txt")
    policy_path = argv[1] if len(argv) > 1 else os.path.join(HERE, "..", "licence", "licence-policy.json")
    try:
        findings, listed = gate(parse(path), load_json(policy_path))
    except InputError as e:
        print(f"::error title=bundle licence gate BLOCKED::{e}")
        return 2
    for x in listed:
        print(x)
    for f in findings:
        print(f"::error title=bundle licence gate::{f}")
    print(f"bundle licence gate: {'FAIL' if findings else 'PASS'}, {len(findings)} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
