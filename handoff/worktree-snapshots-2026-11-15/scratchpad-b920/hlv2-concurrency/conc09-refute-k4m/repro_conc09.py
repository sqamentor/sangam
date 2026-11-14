"""CONC-09 reproduction against a PRIVATE copy of heavy_lock v2 and a PRIVATE temp lock folder.

Variants:
  short   : holder A takes the lock with --pid <short-lived child> --lease 3; the child exits; the lease runs out for real.
  planted : --lease 1800; the child exits; lease_until is planted 60 s in the past (same technique as selftest.py:317),
            standing in for "the lease ran out while the machine slept".
  control : as 'short' but no heartbeat is injected (the correct, expected auto-release).
In 'short' and 'planted' the heartbeat of A is run as a separate process (real CLI) after X's judgement and before
X's _auto_release -- see x_waiter.py.
"""
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(HERE, "code")
HEAVY = os.path.join(CODE, "heavy_lock.py")
PY = sys.executable


def env_for(lockdir):
    env = os.environ.copy()
    for k in list(env):
        if k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID":
            del env[k]
    env["SHARED_LOCK_DIR"] = lockdir
    env["SHARED_LOCK_POLL_SECONDS"] = "0.2"
    return env


def iso(t):
    return datetime.fromtimestamp(t, timezone.utc).isoformat(timespec="milliseconds")


def run_variant(name, lease, plant_expired, inject):
    print(f"\n==================== variant {name}: lease={lease} plant_expired={plant_expired} inject_heartbeat={inject}")
    lockdir = os.path.join(HERE, f"lockdir-{name}")
    if os.path.exists(lockdir):
        shutil.rmtree(lockdir)
    os.makedirs(lockdir)
    env = env_for(lockdir)
    holder = subprocess.Popen([PY, "-I", "-c", "import time; time.sleep(1.5)"])
    r = subprocess.run([PY, "-I", HEAVY, "acquire", "A", "job A", "--pid", str(holder.pid), "--lease", str(lease)],
                       capture_output=True, text=True, env=env, timeout=30)
    print(f"A acquire rc={r.returncode} stdout={r.stdout.strip()!r}\n  stderr={r.stderr.strip()!r}")
    lock = os.path.join(lockdir, "heavy-build.lock")
    with open(lock, encoding="utf-8") as f:
        rec = json.load(f)
    holder.wait()
    if plant_expired:
        rec["lease_until"] = time.time() - 60
        with open(lock, "w", encoding="utf-8") as f:
            json.dump(rec, f)
    else:
        while time.time() < rec["lease_until"] + 0.3:
            time.sleep(0.05)
    st = subprocess.run([PY, "-I", HEAVY, "status"], capture_output=True, text=True, env=env)
    print("status before X:\n  " + "\n  ".join(st.stdout.strip().splitlines()))
    x = subprocess.run([PY, "-I", os.path.join(HERE, "x_waiter.py"), CODE, lockdir, "1" if inject else "0"],
                       capture_output=True, text=True, env=env, timeout=60)
    print(f"X rc={x.returncode}\nX stdout (non-report): "
          + repr([ln for ln in x.stdout.splitlines() if not ln.startswith("X-REPORT ")]))
    print("X stderr:\n  " + "\n  ".join(x.stderr.strip().splitlines()))
    rep = None
    for ln in x.stdout.splitlines():
        if ln.startswith("X-REPORT "):
            rep = json.loads(ln[len("X-REPORT "):])
    if rep is None:
        print("NO X REPORT")
        return
    for e in rep["events"]:
        e = dict(e)
        e["t"] = iso(e["t"])
        print("  event:", json.dumps(e))
    print(f"  X acquired at {iso(rep['t_acquired'])}; final owner={rep['final_record_owner']}")
    print("  served log:\n    " + "\n    ".join(rep["served_log"]))
    same = [e for e in rep["events"] if e["ev"].startswith("_same_record")]
    if same:
        s = same[-1]
        verdict = ("DEFECT SHOWN: record deleted while its lease_until was "
                   f"{s['taken_lease_left_s_at_check']:+.3f} s in the future"
                   if s["result"] and s["taken_lease_left_s_at_check"] > 0
                   else "no defect in this run (deleted record's lease had expired, or record was put back)")
        print("  VERDICT:", verdict)
    hb = subprocess.run([PY, "-I", HEAVY, "heartbeat", "A"], capture_output=True, text=True, env=env)
    print(f"  A's next heartbeat after X took over: rc={hb.returncode} stderr={hb.stderr.strip()!r}")
    subprocess.run([PY, "-I", HEAVY, "release", "X"], capture_output=True, text=True, env=env)


if __name__ == "__main__":
    run_variant("short", 3, False, True)
    run_variant("planted", 1800, True, True)
    run_variant("control", 3, False, False)
