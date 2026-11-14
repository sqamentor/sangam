"""E2b: natural interleavings with `release A` invoked at a chosen instant near lease_until (no pause inside
release). Per trial, fresh lock folder under runs/:
  A: real CLI `acquire A --pid <sleeper> --lease LEASE`; sleeper killed (recorded holder dead, A's runner alive).
  B: real CLI `acquire B --pid <driver>` (oldest waiter), polling every POLL s; auto-releases at the first poll
     after lease_until.
  A's runner: a_release_at.py calls release at lease_until + offset, offset uniform in [lo, hi] ms.
BAD = B printed `acquired by B` and exited 0, A printed `released by A`, and the lock file is gone afterwards.

argv: root mode trials poll lo_ms hi_ms tag
"""
import json
import os
import random
import subprocess
import sys
import time

root, mode, trials, poll, lo, hi, tag = (sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4],
                                         float(sys.argv[5]) / 1000, float(sys.argv[6]) / 1000, sys.argv[7])
code = os.path.join(root, "code")
heavy = os.path.join(code, "heavy_lock.py")
at = os.path.join(root, "harness", "a_release_at.py")
PY = sys.executable
LEASE = "2.5"
base = {k: v for k, v in os.environ.items() if not (k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID")}
run_root = os.path.join(root, "runs", f"e2b-{mode}-{tag}-{time.time_ns()}")
os.makedirs(run_root)


def record(d):
    try:
        with open(os.path.join(d, "heavy-build.lock"), encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None


counts, bad, perm_trials = {}, [], 0
for i in range(trials):
    d = os.path.join(run_root, f"t{i:04d}")
    os.makedirs(d)
    env = dict(base, SHARED_LOCK_DIR=d, SHARED_LOCK_POLL_SECONDS=poll)
    holder = subprocess.Popen([PY, "-I", "-c", "import time; time.sleep(30)"])
    subprocess.run([PY, "-I", heavy, "acquire", "A", "job A", "--pid", str(holder.pid), "--lease", LEASE],
                   env=env, capture_output=True)
    lease_until = (record(d) or {}).get("lease_until")
    holder.kill(); holder.wait()
    offset = random.uniform(lo, hi)
    b_out = open(os.path.join(d, "B.out"), "w"); b_err = open(os.path.join(d, "B.err"), "w")
    b = subprocess.Popen([PY, "-I", heavy, "acquire", "B", "job B", "--pid", str(os.getpid())],
                         stdout=b_out, stderr=b_err, env=env)
    a = subprocess.Popen([PY, "-I", at, code, mode, os.path.join(d, "A.remove.log"), repr(lease_until + offset)],
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
    a_out, a_err = a.communicate(timeout=30)
    try:
        b.wait(20)
    except subprocess.TimeoutExpired:
        b.kill(); b.wait()
    b_out.close(); b_err.close()
    bo = open(os.path.join(d, "B.out")).read()
    after = record(d)
    try:
        removes = open(os.path.join(d, "A.remove.log")).read().strip().replace("\n", "; ")
    except OSError:
        removes = "?"
    a_says = a_out.strip() or (a_err.strip().splitlines()[-1] if a_err.strip() else f"rc={a.returncode}")
    b_ok = b.returncode == 0 and "acquired by B: job B" in bo
    key = f"A:{a_says[:40]} | B acquired={b_ok} | lock after={None if after is None else after.get('owner')}"
    counts[key] = counts.get(key, 0) + 1
    if "PermissionError" in removes:
        perm_trials += 1
    if b_ok and after is None and a_out.strip() == "released by A":
        bad.append((i, offset, removes, d))

print(f"mode={mode} poll={poll}s lease={LEASE}s offsets=[{lo*1000:.0f},{hi*1000:.0f}] ms trials={trials} "
      f"folder={run_root}", flush=True)
for k, v in sorted(counts.items(), key=lambda kv: -kv[1]):
    print(f"  {v:4d}  {k}")
print(f"trials where A's os.remove hit PermissionError (sharing violation): {perm_trials}")
print(f"BAD (B holds per its own output, lock file gone, A said 'released by A'): {len(bad)} / {trials}")
for i, off, rem, d in bad[:5]:
    print(f"  trial {i}: release called at lease_until{off*1000:+.1f} ms; A's os.remove attempts: {rem}")
    with open(os.path.join(d, "heavy-build.lock.served.log"), encoding="utf-8") as f:
        for line in f:
            print("     ", line.rstrip())
