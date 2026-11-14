"""CONC-06 refutation attempt: heartbeat killed between os.write and os.ftruncate.

Runs ONLY against the private copy in ./code and private lock folders under ./locks (SHARED_LOCK_DIR is always set,
and the copied wrapper's own default folder is ./code, never the live scratchpad).

Part 1 (kill): a heartbeat child is parked exactly between os.write and os.ftruncate (os.ftruncate is hooked to block
only when the new record is shorter than the file), then the parent kills it with TerminateProcess (Popen.kill), the
way Stop-Job / a process-tree kill would. Then: raw file, json parse, status, release by the owner, holder dies, lease
passes, a waiter tries to acquire.
Part 2 (control): identical, but the heartbeat completes normally; the waiter must auto-release.
"""
import json
import os
import shutil
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(BASE, "code")
HEAVY = os.path.join(CODE, "heavy_lock.py")
PY = sys.executable
LIVE_MARKER = "ff9f2caf"

CHILD = r'''
import importlib.util, os, sys, time
code_dir = sys.argv[1]
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(code_dir, "shared_lock.py"))
sl = importlib.util.module_from_spec(spec); spec.loader.exec_module(sl)
real_ftruncate = os.ftruncate
def ftruncate_hook(fd, length):
    size = os.fstat(fd).st_size
    if length < size:
        sys.stdout.write(f"IN-WINDOW new_len={length} file_size_after_write={size}\n"); sys.stdout.flush()
        time.sleep(60)   # parked after os.write, before os.ftruncate; the parent kills us here
    return real_ftruncate(fd, length)
os.ftruncate = ftruncate_hook
rc = sl.main(["heavy_lock.py", "heartbeat", "A"], lock_name="heavy-build.lock", default_dir=code_dir)
sys.stdout.write(f"EXIT rc={rc}\n"); sys.stdout.flush()
'''


def env_for(lock_dir):
    assert LIVE_MARKER not in lock_dir and lock_dir.startswith(BASE), lock_dir
    e = dict(os.environ)
    for k in ("HEAVY_LOCK_PID", "SHARED_LOCK_NO_AUTORELEASE", "SHARED_LOCK_STALE_SECONDS"):
        e.pop(k, None)
    e["SHARED_LOCK_DIR"] = lock_dir
    e["SHARED_LOCK_POLL_SECONDS"] = "0.2"
    return e


def run(args, env, timeout=30):
    return subprocess.run([PY, "-I", HEAVY] + args, env=env, capture_output=True, text=True, timeout=timeout)


def fresh(name):
    d = os.path.join(BASE, "locks", name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    return d


def holder():
    return subprocess.Popen([PY, "-I", "-c", "import time; time.sleep(300)"])


def waiter(env, seconds):
    try:
        r = run(["acquire", "B", "waiter"], env, timeout=seconds)
        return "finished", r.returncode, r.stdout, r.stderr
    except subprocess.TimeoutExpired as e:
        out = e.stdout.decode() if isinstance(e.stdout, bytes) else (e.stdout or "")
        err = e.stderr.decode() if isinstance(e.stderr, bytes) else (e.stderr or "")
        return f"STILL WAITING after {seconds}s (killed by the harness)", None, out, err


def part1():
    print("=== Part 1: heartbeat killed between os.write and os.ftruncate ===")
    d = fresh("s7-kill")
    env = env_for(d)
    lock = os.path.join(d, "heavy-build.lock")
    h = holder()
    try:
        r = run(["acquire", "A", "repro", "--pid", str(h.pid), "--lease", "3"], env)
        print(f"acquire A rc={r.returncode} stdout={r.stdout.strip()!r}")
        hit = None
        for attempt in range(1, 300):
            before = open(lock, "rb").read()
            p = subprocess.Popen([PY, "-I", "-c", CHILD, CODE], env=env, stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, text=True)
            line = p.stdout.readline()
            if line.startswith("IN-WINDOW"):
                p.kill()          # TerminateProcess while parked between os.write and os.ftruncate
                p.wait()
                hit = (attempt, before, line.strip())
                break
            p.wait()
            time.sleep(0.011)
        if hit is None:
            print("never got a shorter record in 300 heartbeats")
            return
        attempt, before, line = hit
        after = open(lock, "rb").read()
        print(f"normal heartbeats before the shorter one: {attempt - 1}; child said: {line}")
        print(f"record before: {len(before)} bytes; after kill: {len(after)} bytes")
        print(f"tail before : {before[-48:]!r}")
        print(f"tail after  : {after[-48:]!r}")
        try:
            json.loads(after)
            print("json parse of the file: VALID")
        except ValueError as exc:
            print(f"json parse of the file: INVALID ({exc})")
        r = run(["status"], env)
        print(f"status line 1: {r.stdout.splitlines()[0] if r.stdout else ''}")
        print(f"status line 2: {r.stdout.splitlines()[1] if len(r.stdout.splitlines()) > 1 else ''}")
        r = run(["release", "A"], env)
        print(f"release A rc={r.returncode} stderr={r.stderr.strip()!r} stdout={r.stdout.strip()!r}")
        r = run(["heartbeat", "A"], env)
        print(f"heartbeat A rc={r.returncode} stderr={r.stderr.strip()!r}")
    finally:
        h.kill()
        h.wait()
    print("holder killed; sleeping 4 s so a valid 3 s lease would have expired")
    time.sleep(4)
    state, rc, out, err = waiter(env, 8)
    print(f"waiter B: {state} rc={rc}")
    print(f"waiter B stderr: {err.strip()!r}")
    print(f"lock file still present: {os.path.exists(lock)}")
    # manual recovery path: release under the placeholder owner name
    r = run(["release", "?"], env)
    print(f"manual 'release ?' rc={r.returncode} stdout={r.stdout.strip()!r}; lock present: {os.path.exists(lock)}")


def part2():
    print("=== Part 2 (control): same, heartbeat completes normally ===")
    d = fresh("s7-control")
    env = env_for(d)
    lock = os.path.join(d, "heavy-build.lock")
    h = holder()
    try:
        r = run(["acquire", "A", "control", "--pid", str(h.pid), "--lease", "3"], env)
        print(f"acquire A rc={r.returncode}")
        r = run(["heartbeat", "A"], env)
        print(f"heartbeat A rc={r.returncode} stdout={r.stdout.strip()!r}")
        json.loads(open(lock, "rb").read())
        print("json parse of the file: VALID")
    finally:
        h.kill()
        h.wait()
    print("holder killed; sleeping 4 s")
    time.sleep(4)
    state, rc, out, err = waiter(env, 20)
    print(f"waiter B: {state} rc={rc} stdout={out.strip()!r}")
    print(f"waiter B stderr: {err.strip()!r}")
    r = run(["release", "B"], env)
    print(f"release B rc={r.returncode} stdout={r.stdout.strip()!r}")


if __name__ == "__main__":
    part1()
    part2()
