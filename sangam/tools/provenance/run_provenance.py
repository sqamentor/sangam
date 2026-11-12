# SPDX-License-Identifier: Apache-2.0
"""SG-16: record the provenance of a test run, so every result can be tied to the exact code it ran on.

Every CI test job and every local runner calls this twice, before and after Maven:

    python3 -I sangam/tools/provenance/run_provenance.py start  --db <db> --out <dir>
    mvn ... verify
    python3 -I sangam/tools/provenance/run_provenance.py finish --db <db> --out <dir> --reports sangam

``start`` writes ``<dir>/start.json``: the commit (``git rev-parse HEAD``), whether the working tree differs from it
and the SHA-256 of that difference (``git diff HEAD --binary``), the JDK that runs Maven, the configured zone and the
database lane. ``finish`` writes ``<dir>/end.json`` with the same facts again, plus what the test JVMs themselves
reported in their surefire/failsafe XML (``java.version``, ``java.vendor``, ``user.timezone``, ``sangam.db``) and the
test counts. If the commit or the working-tree difference changed while the tests ran, ``drift`` is true: the results
then belong to no single state of the code. With ``--summary`` (``$GITHUB_STEP_SUMMARY`` in CI) a Markdown table is
appended for the job page. Standard library only; it never fails the build (it records, the gates decide).
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

# System properties every forked test JVM writes into its report (surefire/failsafe <properties>).
JVM_KEYS = ("java.version", "java.vendor", "java.vm.name", "user.timezone", "sangam.db")
SCHEMA = 1


def _run(cmd, cwd=None, binary=False):
    """Runs a command; returns stdout (str, or bytes when binary) and stderr text, or (None, error) when it cannot run."""
    try:
        p = subprocess.run(cmd, cwd=cwd, capture_output=True, check=False)
    except OSError as e:
        return None, str(e)
    if p.returncode != 0:
        return None, p.stderr.decode("utf-8", "replace").strip()
    out = p.stdout if binary else p.stdout.decode("utf-8", "replace")
    return out, p.stderr.decode("utf-8", "replace")


def git_state(root):
    """Commit, dirty flag and SHA-256 of the uncommitted difference (tracked files) of the tree at root."""
    sha, err = _run(["git", "rev-parse", "HEAD"], cwd=root)
    if sha is None:
        return {"sha": None, "dirty": None, "diff_sha256": None, "error": err}
    diff, err = _run(["git", "diff", "HEAD", "--binary"], cwd=root, binary=True)
    if diff is None:
        return {"sha": sha.strip(), "dirty": None, "diff_sha256": None, "error": err}
    return {"sha": sha.strip(), "dirty": bool(diff), "diff_sha256": hashlib.sha256(diff).hexdigest() if diff else None}


def java_command(env):
    home = env.get("JAVA_HOME")
    if home:
        exe = os.path.join(home, "bin", "java.exe" if os.name == "nt" else "java")
        if os.path.exists(exe):
            return exe
    return "java"


def parse_java_settings(text):
    """Picks the JVM_KEYS out of ``java -XshowSettings:properties -version`` output."""
    found = {}
    for line in text.splitlines():
        m = re.match(r"\s*([\w.]+) = (.*)$", line)
        if m and m.group(1) in JVM_KEYS:
            found[m.group(1)] = m.group(2).strip()
    return found


def maven_jdk(env):
    """The JDK that runs Maven (JAVA_HOME, else PATH), as that JVM reports itself."""
    cmd = java_command(env)
    try:
        p = subprocess.run([cmd, "-XshowSettings:properties", "-version"], capture_output=True, check=False)
    except OSError as e:
        return {"java": cmd, "error": str(e)}
    props = parse_java_settings(p.stderr.decode("utf-8", "replace") + p.stdout.decode("utf-8", "replace"))
    return {"java": cmd, "java.version": props.get("java.version"), "java.vendor": props.get("java.vendor"),
            "default.timezone": props.get("user.timezone") or None}


def read_reports(reports_root):
    """Aggregates the test JVMs' own properties and the counts from every TEST-*.xml below reports_root."""
    values = {k: set() for k in JVM_KEYS}
    counts = {"suites": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    unreadable = []
    pattern = os.path.join(reports_root, "**", "*-reports", "TEST-*.xml")
    for path in sorted(glob.glob(pattern, recursive=True)):
        try:
            suite = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            unreadable.append(path)
            continue
        counts["suites"] += 1
        for k in ("tests", "failures", "errors", "skipped"):
            try:
                counts[k] += int(suite.get(k, "0"))
            except ValueError:
                pass
        for prop in suite.iter("property"):
            name = prop.get("name")
            if name in values and prop.get("value") is not None:
                values[name].add(prop.get("value"))
    return {"jvm": {k: sorted(v) for k, v in values.items()}, "counts": counts, "unreadable": unreadable}


def record(phase, root, db, env, reports_root=None, start=None, now=None):
    rec = {
        "schema": SCHEMA,
        "phase": phase,
        "at": (now or datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "git": git_state(root),
        "jdk": maven_jdk(env),
        "zone": {"TZ": env.get("TZ"), "test_jvm_forced": "UTC (sangam/pom.xml surefire/failsafe -Duser.timezone=UTC)"},
        "db": db,
        "runner": {k: env.get(k) for k in ("GITHUB_WORKFLOW", "GITHUB_JOB", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT",
                                         "GITHUB_SHA", "GITHUB_REF", "RUNNER_OS") if env.get(k)},
    }
    if reports_root is not None:
        rec["observed"] = read_reports(reports_root)
    if start is not None:
        rec["start_at"] = start.get("at")
        rec["drift"] = drift(start.get("git", {}), rec["git"])
    return rec


def drift(before, after):
    """True when the commit or the uncommitted difference changed between start and finish; None if unknown."""
    keys = ("sha", "diff_sha256")
    if before.get("sha") is None or after.get("sha") is None:
        return None
    return any(before.get(k) != after.get(k) for k in keys)


def summary_markdown(rec):
    g, j, o = rec["git"], rec["jdk"], rec.get("observed", {})
    jvm = o.get("jvm", {})
    c = o.get("counts", {})

    def joined(key):
        return ", ".join(jvm.get(key, [])) or "-"

    rows = [
        ("commit", f"`{g.get('sha')}`" + (" + uncommitted changes (diff sha256 `%s`)" % g.get("diff_sha256")
                                           if g.get("dirty") else "")),
        ("drift during run", {True: "**YES: results match no single code state**", False: "no",
                              None: "unknown"}[rec.get("drift")]),
        ("Maven JDK", f"{j.get('java.version')} ({j.get('java.vendor')})"),
        ("test JVM java.version", joined("java.version")),
        ("test JVM zone (user.timezone)", joined("user.timezone")),
        ("database lane", f"{rec.get('db')} (test JVMs: {joined('sangam.db')})"),
        ("suites / tests / failures / errors / skipped",
         " / ".join(str(c.get(k, 0)) for k in ("suites", "tests", "failures", "errors", "skipped"))),
    ]
    return "### Test-run provenance (SG-16)\n\n| | |\n|---|---|\n" + "".join(f"| {a} | {b} |\n" for a, b in rows)


def main(argv=None, env=None):
    env = dict(os.environ if env is None else env)
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("phase", choices=["start", "finish"])
    ap.add_argument("--db", required=True, help="database lane, e.g. h2, postgres, sqlserver2025")
    ap.add_argument("--out", required=True, help="folder for start.json / end.json")
    ap.add_argument("--root", default=".", help="any folder inside the git working tree (default: .)")
    ap.add_argument("--reports", help="finish: folder searched for surefire/failsafe TEST-*.xml")
    ap.add_argument("--summary", help="finish: Markdown file to append to (GITHUB_STEP_SUMMARY)")
    a = ap.parse_args(argv)
    os.makedirs(a.out, exist_ok=True)
    start = None
    if a.phase == "finish":
        try:
            with open(os.path.join(a.out, "start.json"), encoding="utf-8") as f:
                start = json.load(f)
        except (OSError, ValueError):
            start = {}
    rec = record(a.phase, a.root, a.db, env, reports_root=a.reports if a.phase == "finish" else None, start=start)
    name = "start.json" if a.phase == "start" else "end.json"
    with open(os.path.join(a.out, name), "w", encoding="utf-8", newline="\n") as f:
        json.dump(rec, f, indent=2, sort_keys=True)
        f.write("\n")
    if a.phase == "finish" and a.summary:
        with open(a.summary, "a", encoding="utf-8", newline="\n") as f:
            f.write(summary_markdown(rec) + "\n")
    print(f"provenance {a.phase}: sha={rec['git'].get('sha')} dirty={rec['git'].get('dirty')} db={a.db}"
          + (f" drift={rec.get('drift')}" if a.phase == "finish" else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
