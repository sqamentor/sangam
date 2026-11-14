"""Waiter X for the CONC-09 check. Runs the REAL shared_lock.acquire() from the private copy.

Instrumentation only:
  * evaluate() is wrapped: the real verdict is computed first and returned unchanged; when it is 'stale' (the first
    time only), the holder's heartbeat is issued as a SEPARATE PROCESS through the real CLI
    (heavy_lock.py heartbeat A) -- i.e. it lands after X's read+judgement (shared_lock.py:409-410) and before
    X's _auto_release (shared_lock.py:411 -> :340). Nothing in shared_lock.py serialises those two processes.
  * _same_record() is wrapped as a passive spy (the real result is returned) so the record X actually deleted is
    visible.
argv: <codedir> <lockdir> <inject: 1|0>
"""
import importlib.util
import json
import os
import subprocess
import sys
import time

codedir, lockdir, inject = sys.argv[1], sys.argv[2], sys.argv[3] == "1"
os.environ["SHARED_LOCK_DIR"] = lockdir
os.environ["SHARED_LOCK_POLL_SECONDS"] = "0.2"
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(codedir, "shared_lock.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)
cfg = sl.Config("heavy-build.lock", codedir)
assert cfg.dir == os.path.abspath(lockdir), (cfg.dir, lockdir)

events = []
real_evaluate, real_same = sl.evaluate, sl._same_record
injected = {"done": False}


def evaluate_hook(cfg_, held, at=None):
    t = time.time()
    action, msg = real_evaluate(cfg_, held, at)
    events.append({"ev": "evaluate", "t": t, "action": action,
                   "judged_lease_until": held.get("lease_until"),
                   "judged_lease_left_s": None if held.get("lease_until") is None else round(held["lease_until"] - t, 3)})
    if inject and action == "stale" and not injected["done"]:
        injected["done"] = True
        env = os.environ.copy()
        r = subprocess.run([sys.executable, "-I", os.path.join(codedir, "heavy_lock.py"), "heartbeat", "A"],
                           capture_output=True, text=True, env=env)
        with open(cfg_.lock, encoding="utf-8") as f:
            renewed = json.load(f)
        th = time.time()
        events.append({"ev": "heartbeat(A) as separate process", "t": th, "rc": r.returncode,
                       "stdout": r.stdout.strip(), "stderr": r.stderr.strip(),
                       "record_lease_until_now": renewed.get("lease_until"),
                       "record_lease_left_s": round(renewed.get("lease_until", 0) - th, 3)})
    return action, msg


def same_spy(taken, judged):
    res = real_same(taken, judged)
    t = time.time()
    events.append({"ev": "_same_record(taken, judged)", "t": t, "result": res,
                   "taken_lease_until": taken.get("lease_until"),
                   "taken_lease_left_s_at_check": round((taken.get("lease_until") or 0) - t, 3),
                   "judged_lease_until": judged.get("lease_until"),
                   "taken_equals_judged_whole_record": taken == judged})
    return res


sl.evaluate = evaluate_hook
sl._same_record = same_spy
rc = sl.acquire(cfg, "X", "job X")
t_done = time.time()
final = sl.read(cfg)
try:
    with open(cfg.served_log, encoding="utf-8") as f:
        served = [line.rstrip() for line in f]
except OSError:
    served = []
print("X-REPORT " + json.dumps({"acquire_rc": rc, "t_acquired": t_done, "final_record_owner": (final or {}).get("owner"),
                                "events": events, "served_log": served}), flush=True)
