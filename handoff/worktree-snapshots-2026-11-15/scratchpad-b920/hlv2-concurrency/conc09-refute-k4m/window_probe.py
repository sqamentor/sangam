"""Measure the CONC-09 window on this machine: from the moment X's wait loop has read the lock bytes it judges
(shared_lock.py:409) to the moment its os.replace (shared_lock.py:340) succeeds. A heartbeat whose write lands inside
this window is deleted by _auto_release. Private copy, private lock folder, planted dead+expired records (as in
selftest.py:317). No heartbeat is injected here."""
import contextlib
import importlib.util
import io
import json
import os
import shutil
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CODE = os.path.join(HERE, "code")
LOCKDIR = os.path.join(HERE, "lockdir-window")
if os.path.exists(LOCKDIR):
    shutil.rmtree(LOCKDIR)
os.makedirs(LOCKDIR)
for k in list(os.environ):
    if k.startswith("SHARED_LOCK_") or k == "HEAVY_LOCK_PID":
        del os.environ[k]
os.environ["SHARED_LOCK_DIR"] = LOCKDIR
os.environ["SHARED_LOCK_POLL_SECONDS"] = "0.2"
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(CODE, "shared_lock.py"))
sl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sl)
cfg = sl.Config("heavy-build.lock", CODE)
assert cfg.dir == os.path.abspath(LOCKDIR)


def dead_holder():
    child = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(0.3)"])
    _, start, _ = sl.probe_process(child.pid)
    child.wait()
    return child.pid, start


marks = {}
real_read, real_replace = sl.read, os.replace


def read_spy(c):
    v = real_read(c)
    if "replace_first" not in marks:          # only the wait-loop read that precedes the rename
        marks["read_ret"] = time.perf_counter()
    return v


def replace_spy(src, dst):
    if src == cfg.lock:
        marks.setdefault("replace_first", time.perf_counter())
        try:
            r = real_replace(src, dst)
        except PermissionError:
            marks["perm_errors"] = marks.get("perm_errors", 0) + 1
            raise
        marks["replace_ok"] = time.perf_counter()
        return r
    return real_replace(src, dst)


sl.read = read_spy
os.replace = replace_spy
N = int(sys.argv[1]) if len(sys.argv) > 1 else 60
pid, start = dead_holder()
windows, retries = [], []
for i in range(N):
    rec = {"owner": "A", "what": "job A", "since": sl.now(), "epoch": time.time() - 100, "held_by": sl.HELD_BY_NOTE,
           "acquirer_pid": 1, "acquirer_pid_note": sl.ACQUIRER_PID_NOTE, "holder_pid": pid, "holder_start": start,
           "lease_until": time.time() - 5, "lease_seconds": 1800}
    if sl.liveness(rec)[0] != "no":
        pid, start = dead_holder()
        rec.update(holder_pid=pid, holder_start=start)
    with open(cfg.lock, "x", encoding="utf-8") as f:
        json.dump(rec, f)
    marks.clear()
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as err:
        sl.acquire(cfg, "X", "job X")
        sl.release(cfg, "X")
    if "auto-released stale lock" not in err.getvalue():
        print("trial", i, "no auto-release?", err.getvalue()[:200])
        continue
    windows.append((marks["replace_ok"] - marks["read_ret"]) * 1000.0)
    retries.append(marks.get("perm_errors", 0))
os.replace = real_replace
ws = sorted(windows)
print(f"trials={len(ws)}  window read->replace_ok in ms: min={ws[0]:.2f} median={statistics.median(ws):.2f} "
      f"p90={ws[int(0.9 * (len(ws) - 1))]:.2f} max={ws[-1]:.2f}")
print(f"trials with PermissionError retries on the rename: {sum(1 for r in retries if r)} (max retries {max(retries)})")
p = statistics.median(ws) / 1000.0 / 600.0
print(f"chance that a heartbeat on a 600 s cadence lands in a median window: {p:.2e} per auto-release")
