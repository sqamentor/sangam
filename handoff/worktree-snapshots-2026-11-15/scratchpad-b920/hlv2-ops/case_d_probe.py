"""Instrumented re-run of self-test case (d) (old-style lock, STALE warning) to find why it printed no stderr."""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ops_tests as o  # noqa: E402

for attempt in range(3):
    d = o.lock_dir(f"Dprobe{attempt}")
    t0 = time.time()
    print("acquire A:", o.run(d, ["acquire", "A", "old style"], SHARED_LOCK_STALE_SECONDS="1"), f"{time.time() - t0:.2f}s")
    print("  queue after A:", os.listdir(os.path.join(d, "heavy-build.lock.queue")))
    time.sleep(1.5)
    t1 = time.time()
    w = o.bg(d, ["acquire", "B", "job B"], "B", SHARED_LOCK_STALE_SECONDS="1")
    seen = None
    while time.time() - t1 < 12:
        err = o.bg_text(d, "B")[1]
        if "STALE" in err and seen is None:
            seen = time.time() - t1
            break
        time.sleep(0.05)
    print(f"  STALE line seen after {seen if seen is None else round(seen, 2)} s; queue={os.listdir(os.path.join(d, 'heavy-build.lock.queue'))}")
    print("  stderr:", o.bg_text(d, "B")[1][:200])
    o.stop(w)
    o.run(d, ["release", "A"])

t = time.time()
import subprocess
for _ in range(3):
    t = time.time(); subprocess.run([o.PY, "-I", "-c", "pass"]); print(f"python startup {time.time() - t:.2f}s")
