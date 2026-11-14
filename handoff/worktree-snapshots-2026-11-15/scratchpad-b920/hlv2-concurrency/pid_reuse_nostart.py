"""Records exactly as acquire writes them for a --pid that is not running (holder_start null, shared_lock.py:380-382
and :430): how often does the verdict flip to 'yes' because Windows hands the number to another process?"""
import importlib.util
import os
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("shared_lock", os.path.join(BASE, "code", "shared_lock.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

N, DURATION = int(sys.argv[1]), float(sys.argv[2])
records = []
for _ in range(N):
    p = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(60)"])
    records.append((p, {"holder_pid": p.pid, "holder_start": None}))
for p, _ in records:
    p.kill()
    p.wait()
recs = [r for _, r in records]
t0 = time.time()
ever_yes = set()
while time.time() - t0 < DURATION:
    counts = {"none": 0, "no": 0, "yes": 0, "unknown": 0}
    for r in recs:
        state, detail = m.liveness(r)
        counts[state] += 1
        if state == "yes":
            ever_yes.add(r["holder_pid"])
    print(f"t={time.time() - t0:5.0f}s  dead holders with holder_start=null: {counts}; ever judged alive: {len(ever_yes)}",
          flush=True)
    time.sleep(15)
