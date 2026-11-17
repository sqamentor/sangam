# SPDX-License-Identifier: Apache-2.0
"""Route a lane build to level 1 (local heavy lock), level 2 (GitHub Actions, ci/* branch) or level 3 (AWS
CodeBuild), owner decisions 411, 412 and 413; sangam/docs/CI-LANES.md.

Decision (--level auto):
  local   expected duration <= 30 min and local heavy-lock queue wait <= 30 min
  github  otherwise, unless GitHub itself is backed up (oldest queued ci-lanes run > 30 min) or its Actions minutes
          are exhausted (--github-exhausted, or a billing answer showing no minutes left)
  aws     when GitHub is backed up or exhausted

Level 1 builds nothing here: it prints the decision; the lane runs its own heavy-lock script.
Level 2: makes a commit = HEAD + ci-lane.json (git plumbing; the working tree and index are not touched), pushes it
  with `git push origin <sha>:refs/heads/ci/<lane>-<shortsha>-<cisha6>` (only refs/heads/ci/..., never main or a tag), waits
  with `gh run watch`, downloads the report artifacts and prints the test counts per job.
Level 3: `git archive` of the same commit to s3://sangam-ci-<account>/src/sangam-src.zip, `aws codebuild start-build`
  on project sangam-ci-lanes with CI_MODULES/CI_DBS/CI_CHUNKS/CI_UI overrides, waits, downloads the reports from the
  build's artifact location and prints the same summary. Only resources named sangam-ci-* are used.

  python -I route_build.py --lane r8-ci --dbs postgres --chunks A-contracts --modules sangam-engine --expected-min 40
Exit: 0 green, 1 a job or test failed, 2 refused or a tool failed. Standard library only (git, gh, aws on PATH).
"""
import argparse
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

LIMIT_MIN = 30
WORKFLOW = "ci-lanes.yml"
HEAVY_LOCK = r"C:\Users\Lokendra Singh\AppData\Local\Temp\claude\D--GITHUB--Project-IFMS" \
             r"\ff9f2caf-1246-4228-ae70-82a16cf209ae\scratchpad\heavy_lock.py"
BRANCH_RX = re.compile(r"^ci/[a-z0-9][a-z0-9._-]{0,80}$")
AWS_PROJECT = "sangam-ci-lanes"
AWS_REGION = "ap-south-1"


def run(cmd, check=True, capture=True, **kw):
    r = subprocess.run(cmd, text=True, capture_output=capture, **kw)
    if check and r.returncode != 0:
        raise SystemExit(f"command failed ({r.returncode}): {' '.join(cmd)}\n{(r.stderr or '')[-2000:]}")
    return r


def parse_ts(s):
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


# ---------------------------------------------------------------- decision
def lock_wait_minutes(status_text, now=None):
    """Estimated wait in the local heavy-lock queue: waiters ahead x the mean hold of the recent served entries
    (spacing of consecutive 'served' timestamps), plus the current holder's remaining share of one mean hold."""
    now = now or dt.datetime.now(dt.timezone.utc)
    lines = status_text.splitlines()
    waiters = [x for x in lines if x.startswith("waiting ")]
    served = [parse_ts(x.split()[0]) for x in lines if " served " in x]
    gaps = [(b - a).total_seconds() / 60 for a, b in zip(served, served[1:]) if b > a]
    mean = sum(gaps) / len(gaps) if gaps else 20.0
    held = 0.0
    if lines and lines[0].startswith("{"):
        try:
            since = parse_ts(json.loads(lines[0])["since"])
            held = max(0.0, mean - (now - since).total_seconds() / 60)
        except (ValueError, KeyError):
            held = mean
    return len(waiters) * mean + held


def github_queue_minutes(now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    r = run(["gh", "run", "list", "--workflow", WORKFLOW, "--status", "queued", "--json", "createdAt", "-L", "50"],
            check=False)
    if r.returncode != 0:
        return 0.0
    ages = [(now - parse_ts(x["createdAt"])).total_seconds() / 60 for x in json.loads(r.stdout or "[]")]
    return max(ages, default=0.0)


def decide(expected_min, lock_wait, gh_wait, gh_exhausted):
    if expected_min <= LIMIT_MIN and lock_wait <= LIMIT_MIN:
        return "local"
    if gh_exhausted or gh_wait > LIMIT_MIN:
        return "aws"
    return "github"


# ---------------------------------------------------------------- commit with ci-lane.json
def lane_commit(selection, lane):
    """HEAD plus ci-lane.json as a new commit object (temporary index; worktree and real index untouched)."""
    head = run(["git", "rev-parse", "HEAD"]).stdout.strip()
    blob = run(["git", "hash-object", "-w", "--stdin"], input=json.dumps(selection, indent=2) + "\n").stdout.strip()
    with tempfile.TemporaryDirectory() as td:
        env = dict(os.environ, GIT_INDEX_FILE=os.path.join(td, "index"))
        run(["git", "read-tree", head], env=env)
        run(["git", "update-index", "--add", "--cacheinfo", f"100644,{blob},ci-lane.json"], env=env)
        tree = run(["git", "write-tree"], env=env).stdout.strip()
    msg = f"ci-lane {lane}: {json.dumps(selection)} (test-only, decision 411)\n"
    commit = run(["git", "commit-tree", tree, "-p", head], input=msg).stdout.strip()
    return head, commit


def ci_branch(lane, head, commit):
    # The CI commit = HEAD + ci-lane.json, so a rerun of the same HEAD with another selection has a different commit.
    # Naming the branch by HEAD alone made such a rerun a non-fast-forward push (seen 2026-11-17); add the CI commit.
    br = f"ci/{lane.lower()}-{head[:8]}-{commit[:6]}"
    if not BRANCH_RX.match(br):
        raise SystemExit(f"refused: branch name {br!r} is not ci/<lane>-<shortsha>-<cisha6>")
    return br


def push_ci(commit, branch):
    ref = f"refs/heads/{branch}"
    if not ref.startswith("refs/heads/ci/") or ".." in ref:
        raise SystemExit(f"refused: {ref} is not under refs/heads/ci/ (decision 411: never main or tags)")
    # The default Windows credential helper can wait forever on a hidden sign-in prompt (seen 2026-11-17:
    # the push hung with no output). Use the already signed-in gh CLI as the only helper and never prompt.
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never")
    run(["git", "-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential",
         "push", "origin", f"{commit}:{ref}"], capture=False, env=env)


# ---------------------------------------------------------------- reports
def count_reports(root):
    """{job/artifact dir: {tests, failures, errors, skipped}} from TEST-*.xml under root."""
    out = {}
    for d, _, names in os.walk(root):
        for n in names:
            if not (n.startswith("TEST-") and n.endswith(".xml")):
                continue
            rel = os.path.relpath(d, root).replace(os.sep, "/")
            key = rel.split("/")[0] + (" failsafe" if "failsafe-reports" in rel else " surefire")
            try:
                el = ET.parse(os.path.join(d, n)).getroot()
            except ET.ParseError:
                continue
            c = out.setdefault(key, {"tests": 0, "failures": 0, "errors": 0, "skipped": 0})
            for k in c:
                c[k] += int(float(el.get(k, 0) or 0))
    return out


def print_counts(counts):
    bad = False
    for k in sorted(counts):
        c = counts[k]
        bad |= c["failures"] + c["errors"] > 0
        print(f"  {k:40s} tests={c['tests']} failures={c['failures']} errors={c['errors']} skipped={c['skipped']}")
    return bad


# ---------------------------------------------------------------- level 2
def github(selection, lane, out_dir):
    head, commit = lane_commit(selection, lane)
    branch = ci_branch(lane, head, commit)
    push_ci(commit, branch)
    run_id = None
    for _ in range(60):
        r = run(["gh", "run", "list", "--workflow", WORKFLOW, "--branch", branch, "--json", "databaseId,headSha",
                 "-L", "20"])
        hit = [x for x in json.loads(r.stdout) if x["headSha"] == commit]
        if hit:
            run_id = str(hit[0]["databaseId"])
            break
        time.sleep(5)
    if not run_id:
        raise SystemExit(f"no {WORKFLOW} run appeared for {branch} at {commit}")
    # `gh run watch` returns early on a network error (a TLS handshake timeout ended a watch on 2026-11-17 while
    # the run kept going), so wait until the run reports `completed`, tolerating failed polls.
    view, fails = None, 0
    while True:
        run(["gh", "run", "watch", run_id, "--interval", "30"], check=False, capture=False)
        r = run(["gh", "run", "view", run_id, "--json", "url,status,conclusion,createdAt,updatedAt,jobs"], check=False)
        if r.returncode == 0:
            view, fails = json.loads(r.stdout), 0
            if view.get("status") == "completed":
                break
        else:
            fails += 1
            if fails > 20:
                raise SystemExit(f"gave up reading run {run_id} after 20 failed polls: {(r.stderr or '')[-500:]}")
        time.sleep(30)
    dest = os.path.join(out_dir, f"gh-{run_id}")
    os.makedirs(dest, exist_ok=True)
    run(["gh", "run", "download", run_id, "-D", dest], check=False)
    print(f"\nlevel 2 GitHub: {view['url']}\n  branch {branch} commit {commit} (HEAD {head})")
    print(f"  conclusion {view['conclusion']}, {view['createdAt']} -> {view['updatedAt']}")
    for j in view["jobs"]:
        mins = (parse_ts(j["completedAt"]) - parse_ts(j["startedAt"])).total_seconds() / 60 \
            if j.get("completedAt") and j.get("startedAt") else 0
        print(f"  job {j['name']:40s} {j['conclusion']:10s} {mins:5.1f} min")
    bad = print_counts(count_reports(dest))
    print(f"  reports in {dest}")
    return 0 if view["conclusion"] == "success" and not bad else 1


# ---------------------------------------------------------------- level 3
def aws(*args):
    return json.loads(run(["aws", "--region", AWS_REGION, "--output", "json", *args]).stdout or "{}")


def aws_level(selection, lane, out_dir):
    head, commit = lane_commit(selection, lane)
    account = aws("sts", "get-caller-identity")["Account"]
    bucket = f"sangam-ci-{account}"
    with tempfile.TemporaryDirectory() as td:
        z = os.path.join(td, "sangam-src.zip")
        run(["git", "archive", "--format=zip", "-o", z, commit])
        run(["aws", "--region", AWS_REGION, "s3", "cp", z, f"s3://{bucket}/src/sangam-src.zip"])
    env = [{"name": "CI_MODULES", "value": selection["modules"]}, {"name": "CI_DBS", "value": selection["dbs"]},
           {"name": "CI_CHUNKS", "value": selection["chunks"]}, {"name": "CI_UI", "value": str(selection["ui"]).lower()},
           {"name": "CI_LANE", "value": lane}, {"name": "CI_COMMIT", "value": commit}]
    b = aws("codebuild", "start-build", "--project-name", AWS_PROJECT,
            "--environment-variables-override", json.dumps(env))["build"]
    bid = b["id"]
    print(f"level 3 AWS CodeBuild build {bid}")
    while True:
        b = aws("codebuild", "batch-get-builds", "--ids", bid)["builds"][0]
        if b["buildComplete"]:
            break
        time.sleep(30)
    dest = os.path.join(out_dir, "aws-" + bid.split(":")[-1])
    loc = b.get("artifacts", {}).get("location", "")
    if loc.startswith("arn:aws:s3:::"):
        s3 = "s3://" + loc[len("arn:aws:s3:::"):]
        if s3.endswith(".zip"):
            os.makedirs(dest, exist_ok=True)
            run(["aws", "--region", AWS_REGION, "s3", "cp", s3, os.path.join(dest, "reports.zip")])
            shutil.unpack_archive(os.path.join(dest, "reports.zip"), dest)
        else:
            run(["aws", "--region", AWS_REGION, "s3", "cp", "--recursive", s3, dest])
    mins = (parse_ts(b["endTime"]) - parse_ts(b["startTime"])).total_seconds() / 60 \
        if isinstance(b.get("endTime"), str) else 0
    print(f"  status {b['buildStatus']}, {mins:.1f} min (LARGE Linux ~ USD 0.02/min: ~USD {mins * 0.02:.2f})")
    bad = print_counts(count_reports(dest))
    return 0 if b["buildStatus"] == "SUCCEEDED" and not bad else 1


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lane", required=True)
    ap.add_argument("--modules", default="")
    ap.add_argument("--dbs", default="postgres")
    ap.add_argument("--chunks", default="A-contracts")
    ap.add_argument("--ui", action="store_true")
    ap.add_argument("--expected-min", type=float, default=0.0)
    ap.add_argument("--level", choices=["auto", "local", "github", "aws"], default="auto")
    ap.add_argument("--github-exhausted", action="store_true", help="GitHub Actions minutes are used up")
    ap.add_argument("--heavy-lock", default=HEAVY_LOCK)
    ap.add_argument("--out", default=os.path.join(os.getcwd(), "ci-reports"))
    a = ap.parse_args(argv)
    if not re.match(r"^[a-z0-9][a-z0-9._-]{0,40}$", a.lane.lower()):
        raise SystemExit("refused: --lane must be a short lowercase name")
    selection = {"modules": a.modules, "dbs": a.dbs, "chunks": a.chunks, "ui": a.ui}
    level = a.level
    if level == "auto":
        st = run([sys.executable, "-I", a.heavy_lock, "status", "x"], check=False)
        lw = lock_wait_minutes(st.stdout) if st.returncode == 0 else 0.0
        gw = github_queue_minutes()
        level = decide(a.expected_min, lw, gw, a.github_exhausted)
        print(f"expected {a.expected_min:.0f} min, heavy-lock wait ~{lw:.0f} min, GitHub queue {gw:.0f} min -> {level}")
    if level == "local":
        print("level 1: run locally under the heavy lock (the lane's own script); nothing pushed")
        return 0
    os.makedirs(a.out, exist_ok=True)
    return github(selection, a.lane, a.out) if level == "github" else aws_level(selection, a.lane, a.out)


if __name__ == "__main__":
    sys.exit(main())
