"""CONC-10 reproduction against the COPIED v2 code and a TEMP lock folder only (never the live ff9f2caf locks).

Case U (defect): a dead holder's pid is now occupied by a process this user cannot open (a service svchost).
    Planted record: holder_pid = that pid, holder_start = a different (earlier) creation time, lease expired.
    Expected if CONC-10 is real: the waiter never auto-releases; MISSED HEARTBEAT alive=unknown.
Case C (control): same record, but the pid is occupied by a process this user CAN open (own sleeper).
    Expected: the start-time guard says 'pid reused', the lock is auto-released and the waiter acquires.
"""
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CODE = os.path.join(BASE, "code")
HEAVY = os.path.join(CODE, "heavy_lock.py")
LOCKROOT = os.path.join(BASE, "lockdir")
LIVE_MARK = "ff9f2caf"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


sl = load("shared_lock", os.path.join(CODE, "shared_lock.py"))
nq = load("ntqsi", os.path.join(BASE, "scripts", "ntqsi.py"))


def env_for(lock_dir):
    assert LIVE_MARK not in lock_dir and lock_dir.startswith(BASE), lock_dir
    env = {k: v for k, v in os.environ.items() if not (k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID")}
    env["SHARED_LOCK_DIR"] = lock_dir
    env["SHARED_LOCK_POLL_SECONDS"] = "0.2"
    return env


def plant(lock_dir, holder_pid, holder_start, lease_expired_ago=60):
    now = time.time()
    rec = {"owner": "DEADHOLDER", "what": "planted: holder died, its pid now belongs to another process",
           "since": sl.iso(now - 4000), "epoch": now - 4000, "held_by": sl.HELD_BY_NOTE,
           "acquirer_pid": 4, "acquirer_pid_note": sl.ACQUIRER_PID_NOTE,
           "holder_pid": holder_pid, "holder_start": holder_start,
           "lease_until": now - lease_expired_ago, "lease_seconds": 1800}
    with open(os.path.join(lock_dir, "heavy-build.lock"), "w", encoding="utf-8") as f:
        json.dump(rec, f)
    return rec


def run_waiter(lock_dir, seconds):
    env = env_for(lock_dir)
    out_p, err_p = os.path.join(lock_dir, "B.stdout"), os.path.join(lock_dir, "B.stderr")
    with open(out_p, "w", encoding="utf-8") as out, open(err_p, "w", encoding="utf-8") as err:
        proc = subprocess.Popen([sys.executable, "-I", HEAVY, "acquire", "B", "job B"], stdout=out, stderr=err,
                                env=env, cwd=CODE)
        try:
            proc.wait(seconds)
            finished = True
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
            finished = False
    with open(out_p, encoding="utf-8") as f:
        so = f.read()
    with open(err_p, encoding="utf-8") as f:
        se = f.read()
    return finished, proc.returncode, so, se


def lock_owner(lock_dir):
    try:
        with open(os.path.join(lock_dir, "heavy-build.lock"), encoding="utf-8") as f:
            return json.load(f).get("owner")
    except FileNotFoundError:
        return None


def served(lock_dir):
    try:
        with open(os.path.join(lock_dir, "heavy-build.lock.served.log"), encoding="utf-8") as f:
            return [line.rstrip() for line in f]
    except FileNotFoundError:
        return []


def status(lock_dir):
    r = subprocess.run([sys.executable, "-I", HEAVY, "status"], capture_output=True, text=True, env=env_for(lock_dir),
                       cwd=CODE, timeout=30)
    return [line for line in r.stdout.splitlines() if line.startswith(("holder pid:", "lease remaining:"))]


def fresh(name):
    d = os.path.join(LOCKROOT, name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    return d


# ---------------------------------------------------------------- case U: pid now held by an unopenable process
procs = nq.snapshot()
now = time.time()
cands = []
for pid, (start, name, _ppid) in procs.items():
    if pid in (0, 4) or start is None:
        continue
    alive, pstart, detail = sl.probe_process(pid)
    if alive is None and detail == "process exists but access is denied":
        cands.append((now - start, pid, name, start))
cands.sort()
age, upid, uname, ustart = next(((a, p, n, s) for a, p, n, s in cands if n.lower() == "svchost.exe"), cands[0])
print(f"[U] pid {upid} is {uname}, created {sl.iso(ustart)} ({int(age)} s ago, CreateTime read via "
      f"NtQuerySystemInformation without OpenProcess); copied probe says {sl.probe_process(upid)}")
ud = fresh("case_U")
rec = plant(ud, upid, ustart - 3600.0)
print(f"[U] planted holder_pid={upid} holder_start={sl.iso(rec['holder_start'])} (1 h before that process: a different "
      f"process), lease_until={sl.iso(rec['lease_until'])} (expired 60 s ago)")
print(f"[U] liveness() -> {sl.liveness(rec)}")
print(f"[U] evaluate() -> {sl.evaluate(sl.Config('heavy-build.lock', ud), rec)}")
print(f"[U] status -> {status(ud)}")
finished, rc, so, se = run_waiter(ud, 10)
print(f"[U] waiter after 10 s (poll 0.2 s, ~50 evaluations): finished={finished} rc={rc}")
print(f"[U] waiter stdout={so.strip()!r}")
print(f"[U] waiter stderr={se.strip()!r}")
print(f"[U] lock owner now={lock_owner(ud)!r}; served-log auto-release lines="
      f"{[line for line in served(ud) if 'auto-released' in line]}")

# ---------------------------------------------------------------- case C: pid now held by an openable process
cd = fresh("case_C")
sleeper = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(60)"])
time.sleep(0.3)
alive, cstart, detail = sl.probe_process(sleeper.pid)
print(f"[C] own sleeper pid {sleeper.pid}: probe -> {(alive, cstart, detail)}")
rec = plant(cd, sleeper.pid, cstart - 3600.0)
print(f"[C] liveness() -> {sl.liveness(rec)}")
finished, rc, so, se = run_waiter(cd, 10)
print(f"[C] waiter: finished={finished} rc={rc} stdout={so.strip()!r}")
print(f"[C] waiter stderr={se.strip()!r}")
print(f"[C] lock owner now={lock_owner(cd)!r}; served-log auto-release lines="
      f"{[line for line in served(cd) if 'auto-released' in line]}")
r = subprocess.run([sys.executable, "-I", HEAVY, "release", "B"], capture_output=True, text=True, env=env_for(cd),
                   cwd=CODE, timeout=30)
print(f"[C] release B -> {r.stdout.strip()!r}")
sleeper.kill()
sleeper.wait()
