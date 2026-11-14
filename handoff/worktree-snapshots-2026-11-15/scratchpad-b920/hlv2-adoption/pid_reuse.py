"""How fast does Windows hand a freed pid to the next new process? (review experiment, no lock files touched)"""
import subprocess, sys, time

PY = sys.executable
SLEEP = [PY, "-I", "-c", "import time; time.sleep(30)"]


def spawn():
    return subprocess.Popen(SLEEP, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def free(p):
    p.kill()
    p.wait()
    p._handle.Close()          # drop our handle so the pid can really be reused


hits, trials, keep = 0, 20, []
for _ in range(trials):
    a = spawn()
    pa = a.pid
    time.sleep(0.2)
    free(a)
    found = None
    for i in range(30):
        b = spawn()
        if b.pid == pa:
            found = i
            keep.append(b)
            break
        keep.append(b)
    if found is not None:
        hits += 1
    print(f"freed pid {pa}: reused by new process #{found}" if found is not None else f"freed pid {pa}: not reused in 30 spawns")
for p in keep:
    try:
        free(p)
    except Exception:
        pass
print(f"RESULT: {hits}/{trials} freed pids were handed to one of the next 30 new processes")
