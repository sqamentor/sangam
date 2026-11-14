"""E1: the finding's scenario s2, deterministic. Real CLI (copied, unmodified heavy_lock.py/shared_lock.py) for
acquire A / acquire B / acquire C / release B / status; A's release runs the same code with a pause between
read and remove. Lock folder is a fresh temp folder under runs/ (SHARED_LOCK_DIR), never a live lock.

argv: root (the conc05 folder)
"""
import json
import os
import subprocess
import sys
import time

root = sys.argv[1]
code = os.path.join(root, "code")
heavy = os.path.join(code, "heavy_lock.py")
harness = os.path.join(root, "harness")
lockdir = os.path.join(root, "runs", f"e1-{time.time_ns()}")
os.makedirs(lockdir)
PY = sys.executable

env = {k: v for k, v in os.environ.items() if not (k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID")}
env["SHARED_LOCK_DIR"] = lockdir
env["SHARED_LOCK_POLL_SECONDS"] = "0.2"


def cli(*args, timeout=30):
    r = subprocess.run([PY, "-I", heavy, *args], capture_output=True, text=True, env=env, timeout=timeout, cwd=lockdir)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def owner():
    try:
        with open(os.path.join(lockdir, "heavy-build.lock"), encoding="utf-8") as f:
            return json.load(f).get("owner")
    except FileNotFoundError:
        return None


def show(label, res):
    rc, out, err = res
    print(f"{label}: rc={rc}\n    stdout: {out!r}\n    stderr: {err!r}", flush=True)


print(f"lock folder: {lockdir}")
# 1. A takes a pid lock whose recorded holder is a separate process (the CONC-01 shape: the runner that will call
#    release is alive, the recorded holder_pid is not).
holder = subprocess.Popen([PY, "-I", "-c", "import time; time.sleep(60)"])
show("acquire A --pid <holder> --lease 1", cli("acquire", "A", "job A", "--pid", str(holder.pid), "--lease", "1"))
holder.kill(); holder.wait()          # Popen handle kept: the pid is not reused during the test
time.sleep(1.5)                       # lease expired, holder dead
st = cli("status")[1].splitlines()
print("status before:", st[1:3])

# 2. A's runner runs `release A`; it reads A's record and is descheduled before the remove.
ready = os.path.join(lockdir, "A.ready"); go = os.path.join(lockdir, "A.go")
a = subprocess.Popen([PY, "-I", os.path.join(harness, "a_release_paused.py"), code, ready, go],
                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env, cwd=lockdir)
t0 = time.time()
while not os.path.exists(ready) and time.time() - t0 < 20:
    time.sleep(0.01)
print("A's release has read:", open(ready, encoding="utf-8").read()[:80], "...")

# 3. Oldest waiter B: judges A's lock stale, auto-releases it, creates its own lock.
show("acquire B --pid <driver>", cli("acquire", "B", "job B", "--pid", str(os.getpid())))
print("lock owner after B's acquire:", owner())

# 4. A resumes: removes the file by path -> B's lock.
open(go, "w").close()
out, err = a.communicate(timeout=30)
print(f"release A resumed: rc={a.returncode}\n    stdout: {out.strip()!r}\n    stderr: {err.strip()!r}")
print("lock owner after A's release:", owner())

# 5. C arrives while B's heavy step 'runs'.
show("acquire C --pid <driver>", cli("acquire", "C", "job C", "--pid", str(os.getpid()), timeout=10))
print("lock owner now:", owner(), "-> B's acquire returned 0 and B never released: B and C both hold")

# 6. B finishes and releases.
show("release B", cli("release", "B"))
print("served log:")
with open(os.path.join(lockdir, "heavy-build.lock.served.log"), encoding="utf-8") as f:
    for line in f:
        print("   ", line.rstrip())
cli("release", "C")
