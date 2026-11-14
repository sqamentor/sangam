"""E2: natural interleavings, no pause injected. Per trial, in a fresh lock folder under runs/:
  A: real CLI `acquire A --pid <sleeper> --lease L`; the sleeper is then killed (holder dead, runner alive).
  B: real CLI `acquire B --pid <driver>` started at once; it polls (SHARED_LOCK_POLL_SECONDS) and auto-releases
     A's lock at the first poll after lease_until.
  A's runner: `release A` launched so that it lands close to lease_until (offset swept).
BAD = B's acquire printed `acquired by B` and exited 0, A printed `released by A`, and the lock file is gone
afterwards (B believes it holds, the lock is free: the next acquire would succeed).

argv: root mode trials poll
  mode cli : A's release is the real CLI (copied, unmodified heavy_lock.py)
  mode log : A's release through a_release_wrapped.py (os.remove attempts logged; behaviour unchanged)
  mode v1  : control, a_release_wrapped.py with no retry after PermissionError (v1 shape)
"""
import json
import os
import random
import statistics
import subprocess
import sys
import time

root, mode, trials, poll = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
code = os.path.join(root, "code")
heavy = os.path.join(code, "heavy_lock.py")
wrapped = os.path.join(root, "harness", "a_release_wrapped.py")
PY = sys.executable
LEASE = "0.5"
base = {k: v for k, v in os.environ.items() if not (k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID")}
run_root = os.path.join(root, "runs", f"e2-{mode}-{time.time_ns()}")
os.makedirs(run_root)


def env_for(d):
    e = dict(base)
    e["SHARED_LOCK_DIR"] = d
    e["SHARED_LOCK_POLL_SECONDS"] = poll
    return e


def release_cmd(d):
    if mode == "cli":
        return [PY, "-I", heavy, "release", "A"]
    return [PY, "-I", wrapped, code, mode, os.path.join(d, "A.remove.log")]


def record(d):
    try:
        with open(os.path.join(d, "heavy-build.lock"), encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return None


# calibrate: Popen -> exit of `release A` on an uncontended lock
cal_dir = os.path.join(run_root, "calibrate")
os.makedirs(cal_dir)
lat = []
for _ in range(12):
    subprocess.run([PY, "-I", heavy, "acquire", "A", "cal"], env=env_for(cal_dir), capture_output=True)
    t = time.time()
    subprocess.run(release_cmd(cal_dir), env=env_for(cal_dir), capture_output=True)
    lat.append(time.time() - t)
latency = statistics.median(lat)
print(f"mode={mode} poll={poll}s trials={trials} release latency median={latency*1000:.1f} ms "
      f"(min {min(lat)*1000:.1f}, max {max(lat)*1000:.1f})", flush=True)

counts = {}
bad_examples = []
for i in range(trials):
    d = os.path.join(run_root, f"t{i:04d}")
    os.makedirs(d)
    env = env_for(d)
    holder = subprocess.Popen([PY, "-I", "-c", "import time; time.sleep(30)"])
    subprocess.run([PY, "-I", heavy, "acquire", "A", "job A", "--pid", str(holder.pid), "--lease", LEASE],
                   env=env, capture_output=True)
    lease_until = (record(d) or {}).get("lease_until", time.time())
    holder.kill(); holder.wait()
    b_out = open(os.path.join(d, "B.out"), "w"); b_err = open(os.path.join(d, "B.err"), "w")
    b = subprocess.Popen([PY, "-I", heavy, "acquire", "B", "job B", "--pid", str(os.getpid())],
                         stdout=b_out, stderr=b_err, env=env)
    offset = random.uniform(-0.070, 0.015)            # where A's remove should land relative to lease_until
    launch_at = lease_until + offset - latency
    delay = launch_at - time.time()
    if delay > 0:
        time.sleep(delay)
    a = subprocess.run(release_cmd(d), env=env, capture_output=True, text=True)
    try:
        b.wait(20)
    except subprocess.TimeoutExpired:
        b.kill(); b.wait()
    b_out.close(); b_err.close()
    bo = open(os.path.join(d, "B.out")).read()
    after = record(d)
    a_says = (a.stdout.strip() or a.stderr.strip().splitlines()[-1] if (a.stdout.strip() or a.stderr.strip()) else f"rc={a.returncode}")
    b_ok = b.returncode == 0 and "acquired by B: job B" in bo
    after_owner = None if after is None else after.get("owner")
    key = f"A:{a_says[:40]!s} | B acquired={b_ok} | lock after={after_owner}"
    counts[key] = counts.get(key, 0) + 1
    removes = ""
    if mode != "cli":
        try:
            removes = open(os.path.join(d, "A.remove.log")).read().strip().replace("\n", "; ")
        except OSError:
            removes = "?"
    if b_ok and after is None and a.stdout.strip() == "released by A":
        bad_examples.append((i, offset, removes, d))
    elif mode != "cli" and "PermissionError" in removes:
        counts.setdefault("(info) PermissionError seen in non-BAD trials", 0)
        counts["(info) PermissionError seen in non-BAD trials"] += 1

print("outcomes:", flush=True)
for k, v in sorted(counts.items(), key=lambda kv: -kv[1]):
    print(f"  {v:4d}  {k}")
print(f"BAD (B holds per its own output, lock file gone, A said released): {len(bad_examples)} / {trials}")
for i, off, rem, d in bad_examples[:6]:
    print(f"  trial {i}: offset {off*1000:+.1f} ms; A's os.remove attempts: {rem or '(not observed in cli mode)'}")
    with open(os.path.join(d, "heavy-build.lock.served.log"), encoding="utf-8") as f:
        for line in f:
            print("     ", line.rstrip())
