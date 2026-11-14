# SPDX-License-Identifier: Apache-2.0
"""Results ledger for CI and release gates (CR-01, RM-12, decision 112: a skipped check is never reported as PASS).

Every required check writes one JSON file <ledger-dir>/<check>.json:
  {check, status, mode, inputs_expected, inputs_seen, sha256_of_inputs, tool_version, exit_code, command, reason}
with status one of PASS, FAIL, SKIPPED, BLOCKED, NOT-RUN. No other value exists.

  run     runs a check command. Every --expect path (or --expect-glob PATTERN=MIN) must exist first, else the
          command is not run and the status is BLOCKED. Exit 0 -> PASS, 1 -> FAIL, 2 -> BLOCKED, anything else -> FAIL.
          --report-only: the findings never fail the job, and the status is NOT-RUN (report-only), never PASS.
  record  writes SKIPPED, BLOCKED or NOT-RUN with a reason (PASS can only come from run).
  gate    reads the ledger against release-checks.json. release mode: every required check must be PASS.
          pr mode: a required check that is FAIL, SKIPPED or BLOCKED fails; NOT-RUN (report-only) is shown.
          A required check with no ledger line is missing and fails in both modes.

Exit codes: run returns 0 only for PASS (and for report-only); gate returns 0 only when the gate passes; 2 = usage.
Standard library only.
"""
import argparse
import glob
import hashlib
import json
import os
import subprocess
import sys

STATUSES = ("PASS", "FAIL", "SKIPPED", "BLOCKED", "NOT-RUN")
CHECK_ID_CHARS = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.")


def valid_check_id(check):
    if not check or not set(check) <= CHECK_ID_CHARS or check.startswith("."):
        raise ValueError(f"bad check id {check!r}")
    return check


def sha256_files(paths):
    h = hashlib.sha256()
    for p in sorted(paths):
        h.update(p.replace(os.sep, "/").encode("utf-8") + b"\0")
        if os.path.isfile(p):
            with open(p, "rb") as f:
                for block in iter(lambda: f.read(1 << 20), b""):
                    h.update(block)
        h.update(b"\0")
    return h.hexdigest()


def resolve_inputs(expect, expect_glob):
    """Returns (expected description, seen paths, missing descriptions)."""
    expected, seen, missing = [], [], []
    for p in expect:
        expected.append(p)
        (seen if os.path.exists(p) else missing).append(p)
    for spec in expect_glob:
        pattern, _, minimum = spec.rpartition("=")
        if not pattern:
            raise ValueError(f"--expect-glob needs PATTERN=MIN, got {spec!r}")
        n = int(minimum)
        hits = sorted(glob.glob(pattern, recursive=True))
        expected.append(f"{pattern} (at least {n})")
        seen.extend(hits)
        if len(hits) < n:
            missing.append(f"{pattern}: {len(hits)} found, {n} expected")
    return expected, seen, missing


def write_line(ledger_dir, line):
    valid_check_id(line["check"])
    if line["status"] not in STATUSES:
        raise ValueError(f"status {line['status']!r} is not one of {STATUSES}")
    os.makedirs(ledger_dir, exist_ok=True)
    path = os.path.join(ledger_dir, line["check"] + ".json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(line, f, indent=2, sort_keys=True)
        f.write("\n")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(f"- ledger `{line['check']}`: **{line['status']}**"
                    + (f" ({line['reason']})" if line.get("reason") else "") + "\n")
    return path


def status_for_exit(code):
    return {0: "PASS", 1: "FAIL", 2: "BLOCKED"}.get(code, "FAIL")


def cmd_run(a):
    expected, seen, missing = resolve_inputs(a.expect, a.expect_glob)
    line = {"check": a.check, "mode": "report-only" if a.report_only else "gate", "inputs_expected": expected,
            "inputs_seen": seen, "sha256_of_inputs": sha256_files(seen), "tool_version": a.tool_version or "",
            "command": a.command, "exit_code": None, "reason": ""}
    if not a.command:
        raise ValueError("run needs a command after --")
    if missing:
        line.update(status="BLOCKED", reason="missing input: " + "; ".join(missing))
        print(f"::error title={a.check} BLOCKED::{line['reason']}")
        write_line(a.ledger_dir, line)
        return 0 if a.report_only else 1
    code = subprocess.call(a.command)
    line["exit_code"] = code
    status = status_for_exit(code)
    if a.report_only and status != "BLOCKED":
        line.update(status="NOT-RUN", reason=f"report-only: findings do not gate (tool exit {code})")
    else:
        line["status"] = status
        if status != "PASS":
            line["reason"] = f"tool exit {code}"
    write_line(a.ledger_dir, line)
    if line["status"] == "PASS" or a.report_only:
        return 0
    print(f"::error title={a.check} {line['status']}::{line['reason']}")
    return 1


def cmd_record(a):
    if a.status == "PASS":
        raise ValueError("record never writes PASS: a PASS comes only from run")
    if not a.reason:
        raise ValueError("record needs --reason")
    write_line(a.ledger_dir, {"check": a.check, "status": a.status, "mode": "record", "inputs_expected": [],
                              "inputs_seen": [], "sha256_of_inputs": "", "tool_version": "", "command": [],
                              "exit_code": None, "reason": a.reason})
    return 0


def load_ledger(ledger_dir):
    lines = {}
    for path in sorted(glob.glob(os.path.join(ledger_dir, "**", "*.json"), recursive=True)):
        with open(path, encoding="utf-8") as f:
            line = json.load(f)
        if not isinstance(line, dict) or line.get("status") not in STATUSES or "check" not in line:
            raise ValueError(f"{path}: not a ledger line")
        prior = lines.get(line["check"])
        if prior is not None and prior["status"] != line["status"]:
            # the same check reported twice with different results (for example two matrix legs): the worse wins
            order = {"FAIL": 0, "BLOCKED": 1, "SKIPPED": 2, "NOT-RUN": 3, "PASS": 4}
            line = line if order[line["status"]] < order[prior["status"]] else prior
        lines[line["check"]] = line
    return lines


def evaluate(required, lines, mode):
    """Returns (ok, rows). rows: (check, status, verdict)."""
    rows, ok = [], True
    for check in required:
        line = lines.get(check)
        status = line["status"] if line else "MISSING"
        if mode == "release":
            good = status == "PASS"
        else:
            good = status in ("PASS", "NOT-RUN")
        ok = ok and good
        rows.append((check, status, "ok" if good else "FAILS THE GATE"))
    return ok, rows


def cmd_gate(a):
    with open(a.required, encoding="utf-8") as f:
        spec = json.load(f)
    name = a.set or a.mode
    if name not in spec or not isinstance(spec[name], list) or not spec[name]:
        raise ValueError(f"{a.required}: no required-check list named {name!r}")
    required = spec[name]
    lines = load_ledger(a.ledger_dir)
    ok, rows = evaluate(required, lines, a.mode)
    extra = sorted(set(lines) - set(required))
    text = [f"### Results ledger, {a.mode} gate ({name}): {'PASS' if ok else 'FAIL'}", "", "| check | status | verdict |", "|---|---|---|"]
    text += [f"| {c} | {s} | {v} |" for c, s, v in rows]
    if extra:
        text += ["", "Also recorded (not required in this mode): " + ", ".join(f"{c}={lines[c]['status']}" for c in extra)]
    out = "\n".join(text) + "\n"
    print(out)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(out)
    if a.out:
        with open(a.out, "w", encoding="utf-8", newline="\n") as f:
            json.dump({"mode": a.mode, "result": "PASS" if ok else "FAIL",
                       "checks": [{"check": c, "status": s} for c, s, _ in rows]}, f, indent=2)
    return 0 if ok else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--check", required=True)
    r.add_argument("--ledger-dir", default="results")
    r.add_argument("--expect", action="append", default=[])
    r.add_argument("--expect-glob", action="append", default=[])
    r.add_argument("--tool-version")
    r.add_argument("--report-only", action="store_true")
    r.add_argument("command", nargs=argparse.REMAINDER)
    rec = sub.add_parser("record")
    rec.add_argument("--check", required=True)
    rec.add_argument("--ledger-dir", default="results")
    rec.add_argument("--status", required=True, choices=STATUSES)
    rec.add_argument("--reason", required=True)
    g = sub.add_parser("gate")
    g.add_argument("--required", required=True)
    g.add_argument("--mode", required=True, choices=("release", "pr"))
    g.add_argument("--set", help="name of the required-check list (default: the mode)")
    g.add_argument("--ledger-dir", default="results")
    g.add_argument("--out")
    a = ap.parse_args(argv)
    if a.cmd == "run" and a.command and a.command[0] == "--":
        a.command = a.command[1:]
    try:
        if a.cmd == "run":
            valid_check_id(a.check)
            return cmd_run(a)
        if a.cmd == "record":
            valid_check_id(a.check)
            return cmd_record(a)
        return cmd_gate(a)
    except (ValueError, OSError, KeyError) as e:
        print(f"::error title=results ledger::{e}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
