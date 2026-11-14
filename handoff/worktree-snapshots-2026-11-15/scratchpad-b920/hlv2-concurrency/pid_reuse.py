"""How fast are freed holder pids reused on this machine, and how often does the v2 verdict on a freed pid come out
'unknown' (kept as alive) instead of 'no'? Spawns and kills only its own sleepers; read-only otherwise."""
import importlib.util
import os
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(BASE, "code", "shared_lock.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

N = int(sys.argv[1]) if len(sys.argv) > 1 else 60
DURATION = float(sys.argv[2]) if len(sys.argv) > 2 else 600
records = []
for _ in range(N):
    p = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(60)"])
    alive, start, _ = m.probe_process(p.pid)
    records.append({"holder_pid": p.pid, "holder_start": start, "proc": p})
for r in records:
    r["proc"].kill()
    r["proc"].wait()
    del r["proc"]           # handle closed: the pid may now be reused
t0 = time.time()
ever = {"no-reused": set(), "unknown": set()}
while time.time() - t0 < DURATION:
    counts = {"none": 0, "no": 0, "yes": 0, "unknown": 0}
    for r in records:
        state, detail = m.liveness(r)
        counts[state] += 1
        if state == "unknown":
            ever["unknown"].add(r["holder_pid"])
        if state == "no" and "reused" in detail:
            ever["no-reused"].add(r["holder_pid"])
    print(f"t={time.time() - t0:5.0f}s  verdicts over {N} dead holders: {counts}  "
          f"(ever reused by a readable process: {len(ever['no-reused'])}, ever 'unknown': {len(ever['unknown'])})",
          flush=True)
    time.sleep(30)
